#!/bin/zsh
# Постоянный ключ подписи QDebit (один раз; повторный запуск безопасен — ключ не пересоздаётся).
#  1. ~/.qdebit/release.jks — ключ (RSA 4096, PKCS12, 100 лет). Если уже есть — берётся он.
#  2. ~/.zshenv — QDEBIT_KEYSTORE, QDEBIT_KEYSTORE_PASSWORD, QDEBIT_KEY_ALIAS: ими подписывает build-android.sh.
#  3. Secrets репо Q00000P/QDebit — тот же ключ для сборки релизов на GitHub (зашифрованы ключом репо, читать их нельзя).
# Токен — из GITHUB_TOKEN (classic: repo; fine-grained: Secrets — Read and write).
# Потеряешь ~/.qdebit/release.jks и пароль — новые сборки не встанут поверх старых без удаления. Сохрани копию.
set -e
setopt pipefail

OWNER=Q00000P
REPO=QDebit
API=https://api.github.com
DIR="$HOME/.qdebit"
KS="$DIR/release.jks"
ALIAS=qdebit

[ -n "$GITHUB_TOKEN" ] || { echo "✗ Нет GITHUB_TOKEN в окружении"; exit 1; }

KT=""
for c in "$JAVA_HOME/bin/keytool" \
         "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
         "$(/usr/libexec/java_home 2>/dev/null)/bin/keytool"; do
  [ -x "$c" ] && { KT="$c"; break; }
done
[ -n "$KT" ] || { echo "✗ Не нашёл keytool (JDK / Android Studio)"; exit 1; }
echo "keytool: $KT"

mkdir -p "$DIR"; chmod 700 "$DIR"
if [ -f "$KS" ]; then
  PASS="${QDEBIT_KEYSTORE_PASSWORD:-}"
  [ -n "$PASS" ] || { echo "✗ Ключ $KS уже есть, а QDEBIT_KEYSTORE_PASSWORD в окружении нет — без пароля его не открыть"; exit 1; }
  "$KT" -list -keystore "$KS" -storepass "$PASS" -alias "$ALIAS" >/dev/null || { echo "✗ Пароль из QDEBIT_KEYSTORE_PASSWORD не подходит к $KS"; exit 1; }
  echo "✓ ключ уже есть: $KS"
else
  PASS=$(openssl rand -base64 33 | tr -d '/+=\n' | cut -c1-32)
  "$KT" -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
        -storepass "$PASS" -keypass "$PASS" -dname "CN=QDebit, O=$OWNER" >/dev/null 2>&1
  chmod 600 "$KS"
  echo "✓ создан ключ: $KS"
fi
FP=$("$KT" -list -v -keystore "$KS" -storepass "$PASS" -alias "$ALIAS" | grep -m1 'SHA256:' | sed 's/.*SHA256: //')
echo "  SHA-256: $FP"

# окружение: старые строки QDEBIT_KEY* заменяются
touch ~/.zshenv
sed -i '' '/^export QDEBIT_KEY/d' ~/.zshenv
printf 'export QDEBIT_KEYSTORE="%s"\nexport QDEBIT_KEYSTORE_PASSWORD="%s"\nexport QDEBIT_KEY_ALIAS="%s"\n' "$KS" "$PASS" "$ALIAS" >> ~/.zshenv
echo "✓ ~/.zshenv: QDEBIT_KEYSTORE, QDEBIT_KEYSTORE_PASSWORD, QDEBIT_KEY_ALIAS"

# Secrets репо: шифрование sealed box (libsodium) — PyNaCl в своём venv, систему не трогает
VENV="$DIR/venv"
if ! "$VENV/bin/python" -c 'import nacl' 2>/dev/null; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" -q install --disable-pip-version-check pynacl
fi
"$VENV/bin/python" - "$OWNER/$REPO" "$KS" "$PASS" "$ALIAS" <<'PY'
import base64, json, os, sys, urllib.request, urllib.error
from nacl import public, encoding
repo, ks, pw, alias = sys.argv[1:5]
H = {"Authorization": "Bearer " + os.environ["GITHUB_TOKEN"], "Accept": "application/vnd.github+json",
     "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json"}
def call(method, path, body=None):
    req = urllib.request.Request("https://api.github.com/repos/" + repo + path, method=method, headers=H,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req) as r: return r.status, r.read()
    except urllib.error.HTTPError as e: return e.code, e.read()
st, raw = call("GET", "/actions/secrets/public-key")
if st != 200: sys.exit(f"✗ ключ репо для секретов: HTTP {st} {raw.decode()[:200]}")
pk = json.loads(raw)
box = public.SealedBox(public.PublicKey(pk["key"].encode(), encoding.Base64Encoder()))
vals = {
    "SIGNING_KEYSTORE_B64": base64.b64encode(open(ks, "rb").read()).decode(),
    "SIGNING_STORE_PASSWORD": pw,
    "SIGNING_KEY_PASSWORD": pw,
    "SIGNING_KEY_ALIAS": alias,
}
bad = 0
for name, v in vals.items():
    enc = base64.b64encode(box.encrypt(v.encode())).decode()
    st, raw = call("PUT", "/actions/secrets/" + name, {"encrypted_value": enc, "key_id": pk["key_id"]})
    ok = st in (201, 204)
    bad += not ok
    print(("✓ " if ok else "✗ ") + f"secret {name}: HTTP {st}" + ("" if ok else " " + raw.decode()[:200]))
sys.exit(1 if bad else 0)
PY

echo
echo "Готово. Сохрани копию ключа и пароля в надёжное место (менеджер паролей):"
echo "  файл:   $KS"
echo "  пароль: в ~/.zshenv (QDEBIT_KEYSTORE_PASSWORD)"
echo "  SHA-256: $FP"

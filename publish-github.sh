#!/bin/zsh
# QDebit → github.com/Q00000P/QDebit (публичный).
# Создаёт репо (если нет), коммитит текущее состояние папки, пушит main, включает Pages (из Actions),
# ставит тег vВЕРСИЯ (по versionName Android) — по нему GitHub собирает APK и делает релиз.
#
# Git-история лежит ОТДЕЛЬНО от проекта: ~/dev/.qdebit.git (bare). Поэтому установка новой версии через
# «rm -rf ~/dev/QDebit && unzip …» историю не теряет: удалённые файлы попадут в коммит как удаления.
#
# Токен — только из окружения: GITHUB_TOKEN (в ~/.zshenv). В скрипте и в git-конфиге он не сохраняется.
# Fine-grained токен: Repository access — All repositories; права: Administration, Contents, Workflows, Pages — Read and write.
# Классический токен: scopes repo + workflow.
set -e
setopt pipefail

OWNER=Q00000P
REPO=QDebit
DESC="Дебит скважины, модель скважины по замерам и подбор насоса — веб + Android"
HOME_URL="https://q00000p.github.io/QDebit/"
WT="$(cd "$(dirname "$0")" && pwd)"
GD="${QDEBIT_GIT_DIR:-$(dirname "$WT")/.qdebit.git}"
API=https://api.github.com
URL="https://github.com/$OWNER/$REPO.git"

[ -n "$GITHUB_TOKEN" ] || { echo "✗ Нет GITHUB_TOKEN в окружении. Добавь: echo 'export GITHUB_TOKEN=github_pat_AAAAABBBBB' >> ~/.zshenv && source ~/.zshenv"; exit 1; }
[ -d "$WT/.git" ] && { echo "✗ В $WT есть .git — история ведётся в $GD. Убери $WT/.git и запусти снова."; exit 1; }

H=(-H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")
AUTH="Authorization: Basic $(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 | tr -d '\n')"
g() { git --git-dir="$GD" --work-tree="$WT" -c http.extraheader="$AUTH" "$@"; }

VER=$(grep -m1 'versionName' "$WT/android/app/build.gradle.kts" | sed -E 's/.*"(.*)".*/\1/')
echo "QDebit $VER  ·  папка: $WT  ·  история: $GD"

# 1. токен живой?
UC=$(curl -sS -o /tmp/qdebit-user.json -w '%{http_code}' "${H[@]}" "$API/user")
WHO=$(python3 -c 'import json; print(json.load(open("/tmp/qdebit-user.json")).get("login",""))' 2>/dev/null || true)
if [ -z "$WHO" ]; then
  echo "✗ GitHub не принял токен: HTTP $UC, $(python3 -c 'import json; print(json.load(open("/tmp/qdebit-user.json")).get("message",""))' 2>/dev/null)"
  echo "  в окружении: ${GITHUB_TOKEN[1,7]}… (${#GITHUB_TOKEN} симв.)  — github_pat_AAAAABBBBB/ghp_XXXXXYYYYY значит, что плашку не заменили"
  echo "  где задан: $(grep -ln 'GITHUB_TOKEN' ~/.zshenv ~/.zprofile ~/.zshrc 2>/dev/null | tr '\n' ' ')"
  exit 1
fi
echo "✓ токен: $WHO"

# 2. репо
CODE=$(curl -sS -o /tmp/qdebit-repo.json -w '%{http_code}' "${H[@]}" "$API/repos/$OWNER/$REPO")
if [ "$CODE" = 404 ]; then
  BODY=$(python3 -c 'import json,sys; print(json.dumps({"name":sys.argv[1],"description":sys.argv[2],"homepage":sys.argv[3],"private":False,"has_wiki":False,"has_projects":False}))' "$REPO" "$DESC" "$HOME_URL")
  CODE=$(curl -sS -o /tmp/qdebit-repo.json -w '%{http_code}' "${H[@]}" -X POST "$API/user/repos" -d "$BODY")
  [ "$CODE" = 201 ] || { echo "✗ Не создал репо ($CODE):"; cat /tmp/qdebit-repo.json; exit 1; }
  echo "✓ создан публичный репо $OWNER/$REPO"
else
  [ "$CODE" = 200 ] || { echo "✗ Репо не читается ($CODE):"; cat /tmp/qdebit-repo.json; exit 1; }
  PRIV=$(python3 -c 'import json; print(json.load(open("/tmp/qdebit-repo.json"))["private"])')
  echo "✓ репо уже есть (private=$PRIV)"
  [ "$PRIV" = True ] && echo "  ⚠ репо приватное — сделать публичным: Settings → General → Danger Zone → Change visibility (скрипт видимость не трогает)"
fi

# 3. локальная история
if [ ! -d "$GD" ]; then
  if git -c http.extraheader="$AUTH" ls-remote --heads "$URL" main 2>/dev/null | grep -q main; then
    git -c http.extraheader="$AUTH" clone -q --bare "$URL" "$GD"
    g config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*'
    g reset -q main
    echo "✓ история взята с GitHub"
  else
    git init -q --bare -b main "$GD"
    g config core.bare false
    g remote add origin "$URL"
    echo "✓ новая история в $GD"
  fi
fi
g config core.bare false
g config status.showUntrackedFiles normal

# 4. коммит (всё, что не в .gitignore). Папка — истина: новый коммит ложится поверх того, что на GitHub,
#    и его содержимое = текущей папке (правки, сделанные прямо на GitHub, должны быть и в архиве — иначе откатятся)
g fetch -q origin main 2>/dev/null && g reset -q --soft FETCH_HEAD || true
g add -A
if g diff --cached --quiet 2>/dev/null; then
  echo "• изменений нет"
else
  g diff --cached --stat | tail -1
  NAME=$(git config --global user.name || true); MAIL=$(git config --global user.email || true)
  g -c user.name="${NAME:-$OWNER}" -c user.email="${MAIL:-$OWNER@users.noreply.github.com}" \
    commit -q -m "QDebit $VER" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
  echo "✓ коммит: $(g log -1 --format='%h %s')"
fi
g push -q origin main
echo "✓ main → $URL"

# 5. Pages из Actions (раз и навсегда)
PC=$(curl -sS -o /tmp/qdebit-pages.json -w '%{http_code}' "${H[@]}" "$API/repos/$OWNER/$REPO/pages")
if [ "$PC" = 404 ]; then
  PC=$(curl -sS -o /tmp/qdebit-pages.json -w '%{http_code}' "${H[@]}" -X POST "$API/repos/$OWNER/$REPO/pages" -d '{"build_type":"workflow"}')
  [ "$PC" = 201 ] && echo "✓ Pages включены" || { echo "⚠ Pages не включились ($PC) — у токена нет права Pages? Ответ:"; cat /tmp/qdebit-pages.json; }
  curl -sS -o /dev/null "${H[@]}" -X POST "$API/repos/$OWNER/$REPO/actions/workflows/pages.yml/dispatches" -d '{"ref":"main"}' || true
else
  echo "✓ Pages уже включены"
fi
curl -sS -o /dev/null "${H[@]}" -X PATCH "$API/repos/$OWNER/$REPO" -d "{\"homepage\":\"$HOME_URL\"}" || true

# 6. тег версии → релиз с APK
TAG="v$VER"
if g ls-remote --tags origin "refs/tags/$TAG" | grep -q "$TAG"; then
  echo "• тег $TAG уже есть — релиз не пересобираю (подними versionName для нового релиза)"
else
  g tag -f "$TAG" >/dev/null
  g push -q origin "refs/tags/$TAG"
  echo "✓ тег $TAG → Actions собирает APK и делает релиз"
fi

echo
echo "Репо:     https://github.com/$OWNER/$REPO"
echo "Веб:      $HOME_URL   (первый деплой ~1–2 мин)"
echo "Релизы:   https://github.com/$OWNER/$REPO/releases   (APK ~5–8 мин)"
echo "Сборки:   https://github.com/$OWNER/$REPO/actions"

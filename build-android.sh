#!/bin/zsh
# QDebit: сборка APK и установка на телефон по USB (если подключён)
set -e
cd "$(dirname "$0")/android"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties
if [ -n "$QDEBIT_KEYSTORE" ] && [ -f "$QDEBIT_KEYSTORE" ]; then
  echo "Подпись: постоянный ключ $QDEBIT_KEYSTORE"
else
  echo "Подпись: debug-ключ этого мака (постоянный ключ: ~/dev/QDebit/tools/signing-setup.sh)"
fi
./gradlew --console=plain testDebugUnitTest assembleDebug
VER=$(grep -m1 'versionName' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
cp app/build/outputs/apk/debug/app-debug.apk "../QDebit-$VER-debug.apk"
echo "APK: $(cd .. && pwd)/QDebit-$VER-debug.apk"
ADB="$SDK/platform-tools/adb"; command -v adb >/dev/null && ADB=adb
if "$ADB" get-state >/dev/null 2>&1; then
  OUT=$("$ADB" install -r "../QDebit-$VER-debug.apk" 2>&1) && { echo "Установлено на телефон"; exit 0; }
  echo "$OUT"
  if echo "$OUT" | grep -qE "UPDATE_INCOMPATIBLE|signatures do not match"; then
    echo
    echo "⚠ На телефоне QDebit с другой подписью — поверх не встанет. Удаление стирает данные приложения."
    echo "  1) В приложении: История → «Экспорт JSON» (или убедись, что всё ушло в InfluxDB)."
    echo "  2) Затем: $ADB uninstall org.qdebit.android && $ADB install ../QDebit-$VER-debug.apk"
    echo "  3) В приложении: История → «Импорт» (или подтянуть из InfluxDB на «Синк»)."
  fi
  exit 1
else
  echo "Телефон по USB не найден — APK лежит рядом"
fi

#!/bin/zsh
# QDebit: сборка APK и установка на телефон по USB (если подключён)
set -e
cd "$(dirname "$0")/android"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties
./gradlew --console=plain testDebugUnitTest assembleDebug
VER=$(grep -m1 'versionName' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
cp app/build/outputs/apk/debug/app-debug.apk "../QDebit-$VER-debug.apk"
echo "APK: $(cd .. && pwd)/QDebit-$VER-debug.apk"
ADB="$SDK/platform-tools/adb"; command -v adb >/dev/null && ADB=adb
if "$ADB" get-state >/dev/null 2>&1; then "$ADB" install -r "../QDebit-$VER-debug.apk" && echo "Установлено на телефон"; else echo "Телефон по USB не найден — APK лежит рядом"; fi

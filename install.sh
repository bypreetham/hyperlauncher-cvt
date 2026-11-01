#!/usr/bin/env bash
# Build HyperLauncher and install it on a TV over the network.
#   ./install.sh <TV_IP> [path/to/app.apk]     (give an APK to skip the build)
# Needs: adb; and, unless you pass an APK, JDK 17 and the Android SDK (ANDROID_HOME).
set -euo pipefail
cd "$(dirname "$0")"
TV="${1:?usage: ./install.sh <TV_IP> [app.apk]}"
APK="${2:-}"
command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools and put it on PATH." >&2; exit 1; }

if [ -z "$APK" ]; then
  case "$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1)" in
    *'version "17.'*) ;;
    *) echo "JDK 17 is required (set JAVA_HOME to it). Newer JDKs break this Gradle version." >&2; exit 1 ;;
  esac
  if [ ! -f local.properties ]; then
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [ -n "$SDK" ] || { echo "Set ANDROID_HOME to your Android SDK folder." >&2; exit 1; }
    echo "sdk.dir=$(printf '%s' "$SDK" | tr '\\' '/')" > local.properties
  fi
  ./gradlew --no-daemon :app:assembleDebug -q
  APK=app/build/outputs/apk/debug/app-debug.apk
fi

adb connect "$TV:5555" >/dev/null
adb -s "$TV:5555" get-state >/dev/null 2>&1 || {
  echo "The TV did not answer on $TV:5555. Turn on Developer options > USB debugging and Network debugging," >&2
  echo "accept the 'Allow debugging' prompt on the TV (tick Always allow), then run this again." >&2
  exit 1
}
adb -s "$TV:5555" install -r "$APK"
adb -s "$TV:5555" shell am start -n com.hyperlauncher.tv/com.hyperlauncher.control.ControlCenterActivity >/dev/null
echo "Installed. The Control Center is open on the TV; use 'Home & Theme' > 'Customize Launcher' and Settings > 'Start HyperLauncher on start'."

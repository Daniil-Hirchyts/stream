#!/bin/sh
# Deliberately refuses physical devices, even if ANDROID_SERIAL points at one.
set -eu
cd -- "$(dirname -- "$0")"
serial=${1:-emulator-5556}
case "$serial" in emulator-*) ;; *) echo 'Only an emulator serial is allowed.' >&2; exit 2 ;; esac
number=${serial#emulator-}
case "$number" in ''|*[!0-9]*) echo 'Invalid emulator serial.' >&2; exit 2 ;; esac
adb_path=${ADB:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platform-tools/adb}
if [ "$("$adb_path" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]; then
  echo 'Refusing to test a device that is not an emulator.' >&2
  exit 2
fi
# A custom serial needs its AVD named too (DANIIL_CAST_AVD), so another emulator is never the target.
expected_avd=${DANIIL_CAST_AVD:-DaniilCast_Local_TV}
running_avd=$("$adb_path" -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | head -n 1)
if [ "$running_avd" != "$expected_avd" ]; then
  echo "Refusing $serial: it runs AVD '$running_avd', not '$expected_avd'." >&2
  exit 2
fi
./gradlew assembleDebug assembleDebugAndroidTest
"$adb_path" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_path" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
result_file=$(mktemp)
trap 'rm -f "$result_file"' EXIT HUP INT TERM
"$adb_path" -s "$serial" shell am instrument -w -r \
  -e class tv.daniil.cast.SubtitleRegressionTest,tv.daniil.cast.PlaybackErrorsTest,tv.daniil.cast.PointerControlsTest \
  tv.daniil.cast.test/androidx.test.runner.AndroidJUnitRunner | tee "$result_file"
# Android's instrument command may exit zero even when a test failed.
grep -Eq '^OK \([0-9]+ tests?\)' "$result_file"

#!/bin/sh
# Local validation signature only; production release signing/distribution is separate.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
android_dir=$(CDPATH= cd -- "$here/../android" && pwd)
. "$here/emulator.sh"
require_emulator
if [ -x /usr/libexec/java_home ]; then JAVA_HOME=$(/usr/libexec/java_home -v 21); export JAVA_HOME; fi
ANDROID_HOME=$SDK_ROOT; export ANDROID_HOME
evidence="$here/.cache/evidence/release"
mkdir -p "$evidence"
(cd "$android_dir" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest)
(cd "$android_dir" && ./gradlew -q -PreleaseValidation=true :app:assembleRelease :app:assembleReleaseAndroidTest)
previous_checkjni=$(device shell getprop debug.checkjni | tr -d '\r')
case "$previous_checkjni" in ''|0|1|true|false) ;; *) fail 'Unexpected CheckJNI property value.'; exit 1 ;; esac
restore() {
  trap - EXIT HUP INT TERM
  device shell "setprop debug.checkjni '$previous_checkjni'"
  device install -r "$android_dir/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
  device install -r -t "$android_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
}
trap restore EXIT HUP INT TERM
device install -r "$android_dir/app/build/outputs/apk/release/app-release.apk"
device install -r -t "$android_dir/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
device shell setprop debug.checkjni 1
device shell am instrument -w -r \
  -e fixtures http://127.0.0.1:58133 -e seeder 127.0.0.1:58134 \
  tv.daniil.stream.test/tv.daniil.stream.ReleaseProbeInstrumentation | tee "$evidence/instrumentation.txt"
grep -Eq '^OK \(1 test\)' "$evidence/instrumentation.txt"
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=' "$evidence/instrumentation.txt"; then exit 1; fi
printf 'Shrunk release JNI, verified torrent bytes and native exception construction passed.\n'

#!/bin/sh
# Expects the current debug app/test APKs installed. Isolated DB, local-only peers, and one verified AVD.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$here/emulator.sh"
require_emulator
evidence="$here/.cache/evidence/process-death"
mkdir -p "$evidence"
umask 077
database="test-process-death-$(node -e 'process.stdout.write(require("crypto").randomUUID())').db"
had_options=false
if device shell run-as tv.daniil.stream test -f shared_prefs/instrumentation.xml; then
  device exec-out run-as tv.daniil.stream cat shared_prefs/instrumentation.xml >"$evidence/options.xml"
  had_options=true
fi
previous_checkjni=$(device shell getprop debug.checkjni | tr -d '\r')
case "$previous_checkjni" in ''|0|1|true|false) ;; *) fail 'Unexpected CheckJNI property value.'; exit 1 ;; esac
restore() {
  trap - EXIT HUP INT TERM
  device shell am force-stop tv.daniil.stream >/dev/null
  if [ "$had_options" = true ]; then
    device shell run-as tv.daniil.stream sh -c "'cat > shared_prefs/instrumentation.xml'" <"$evidence/options.xml"
  else device shell run-as tv.daniil.stream rm -f shared_prefs/instrumentation.xml; fi
  device shell "setprop debug.checkjni '$previous_checkjni'"
  rm -f "$evidence/options.xml"
}
trap restore EXIT HUP INT TERM
device shell setprop debug.checkjni 1
device shell run-as tv.daniil.stream rm -f files/validation-death-ready.json
device shell am instrument -w -r -e class tv.daniil.stream.ProcessDeathTest -e phase prepare -e database "$database" \
  -e fixtures http://127.0.0.1:58133 -e seeder 127.0.0.1:58134 \
  tv.daniil.stream.test/androidx.test.runner.AndroidJUnitRunner >"$evidence/before.txt" 2>&1 &
instrument_pid=$!
attempt=0
until device shell run-as tv.daniil.stream test -f files/validation-death-ready.json; do
  kill -0 "$instrument_pid" || { cat "$evidence/before.txt"; exit 1; }
  attempt=$((attempt + 1)); [ "$attempt" -lt 90 ] || { fail 'Playback did not reach durable progress.'; exit 1; }
  sleep 1
done
device exec-out run-as tv.daniil.stream cat files/validation-death-ready.json >"$evidence/ready.json"
device shell run-as tv.daniil.stream ls -l cache/payload >"$evidence/payload-before.txt"
device shell am force-stop tv.daniil.stream
wait "$instrument_pid" || true
device shell am instrument -w -r -e class tv.daniil.stream.ProcessDeathTest -e phase resume -e database "$database" \
  -e fixtures http://127.0.0.1:58133 -e seeder 127.0.0.1:58134 \
  tv.daniil.stream.test/androidx.test.runner.AndroidJUnitRunner | tee "$evidence/after.txt"
grep -Eq '^OK \(1 test\)' "$evidence/after.txt"
device shell run-as tv.daniil.stream rm -f files/validation-death-ready.json
printf 'Process death: real playback, durable progress, orphan recovery and resumed playback passed.\n'

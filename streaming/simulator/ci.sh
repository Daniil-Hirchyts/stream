#!/bin/sh
# Dedicated Linux CI emulator. Never discovers or selects an attached device.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$here/../.." && pwd)
. "$here/emulator.sh"
state=$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)
[ -z "$state" ] || { fail "CI requires an unused $SERIAL; found $state."; exit 1; }
"$root/casting/simulator/setup.sh" "$SDK_ROOT" "$AVD_NAME"
mkdir -p "$here/.cache/evidence"
"$SDK_ROOT/emulator/emulator" -avd "$AVD_NAME" -port "$PORT" -no-window -no-snapshot -gpu swiftshader \
  -no-audio -feature -Vulkan -no-metrics -crash-report-mode never >"$here/.cache/evidence/emulator.log" 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT HUP INT TERM
attempt=0
while ! require_emulator >/dev/null 2>&1; do
  kill -0 "$emulator_pid" || { cat "$here/.cache/evidence/emulator.log"; exit 1; }
  attempt=$((attempt + 1)); [ "$attempt" -lt 300 ] || { fail 'CI emulator did not boot.'; exit 1; }
  sleep 1
done
cd "$root"
./streaming/simulator.sh test
./streaming/simulator.sh process-death
./streaming/simulator.sh release-test
./casting/receiver/test-emulator.sh "$SERIAL"
if [ "${DANIILTV_CI_SOAK:-false}" = true ]; then ./streaming/simulator.sh soak; fi
./streaming/simulator.sh profile

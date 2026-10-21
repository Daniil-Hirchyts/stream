#!/bin/sh
# Daniil TV's local test environment. Every device operation is pinned to one verified emulator (see
# simulator/emulator.sh); fixtures never leave this computer and the emulator's loopback.
#
#   ./streaming/simulator.sh fixtures        Generate test media and torrents
#   ./streaming/simulator.sh services        Start the fixture service (58133) and seeder (58134) for the emulator
#   ./streaming/simulator.sh test            Native engine tests on the emulator (CheckJNI, real BitTorrent)
#   ./streaming/simulator.sh status | logs | stop-services
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
SIM_DIR="$SCRIPT_DIR/simulator"
ANDROID_DIR="$SCRIPT_DIR/android"
NATIVE_DIR="$SCRIPT_DIR/native"
. "$SIM_DIR/emulator.sh"

STATE_DIR="${TMPDIR:-/tmp}/daniil-tv-simulator"
FIXTURE_PORT=58133
SEEDER_PORT=58134
FIXTURE_URL="http://127.0.0.1:$FIXTURE_PORT"
FIXTURE_TOOL="$NATIVE_DIR/build/host-dev/dtv-fixture"

prepare_java() {
  if [ -x /usr/libexec/java_home ]; then
    detected=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)
    if [ -n "$detected" ]; then JAVA_HOME=$detected; export JAVA_HOME; fi
  fi
  ANDROID_HOME=$SDK_ROOT
  export ANDROID_HOME
}

listener_pid() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -n 1
}

# Starts a detached service unless ours already runs; a port held by anything else is an error, never stopped.
start_service() { # name port log program [args...]
  name=$1; port=$2; log=$3; shift 3
  pid_file="$STATE_DIR/$name.pid"
  holder=$(listener_pid "$port")
  if [ -n "$holder" ]; then
    if [ -f "$pid_file" ] && [ "$(cat "$pid_file")" = "$holder" ]; then return 0; fi
    fail "Port $port is held by another process (PID $holder). Free it; this script won't stop it."
    return 1
  fi
  started=$(node "$ROOT_DIR/casting/simulator/detach.mjs" "$log" "$@") || return 1
  printf '%s\n' "$started"
  printf '%s\n' "$started" | sed -n 's/.*(PID \([0-9]*\)).*/\1/p' >"$pid_file"
  attempt=0
  until [ -n "$(listener_pid "$port")" ]; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 60 ] || { tail -n 20 "$log" >&2; fail "$name did not start."; return 1; }
    sleep 0.5
  done
}

start_services() {
  require_emulator || return 1
  mkdir -p "$STATE_DIR" || return 1
  command -v node >/dev/null 2>&1 || fail 'Node.js is needed for the fixture service.' || return 1
  "$SIM_DIR/fixtures.sh" || return 1
  if health=$(curl -fsS --max-time 2 "$FIXTURE_URL/health" 2>/dev/null); then
    node -e 'const h=JSON.parse(process.argv[1]);if(h.serial!==process.argv[2]||h.avd!==process.argv[3]){console.error("The fixture service belongs to "+h.avd+" ("+h.serial+"); stop it before using another emulator.");process.exit(1)}' \
      "$health" "$SERIAL" "$AVD_NAME" || return 1
  fi
  start_service fixtures "$FIXTURE_PORT" "$STATE_DIR/fixtures.log" "$(command -v node)" "$SIM_DIR/server.mjs" \
    "$ADB_BIN" "$SERIAL" "$AVD_NAME" "$FIXTURE_TOOL" || return 1
  # Each torrent followed by the directory it seeds from.
  args=$(node -e '
    const path = require("path"), fixtures = require(process.argv[1]), dir = process.argv[2];
    for (const f of fixtures) { console.log(path.join(dir, "torrents", f.torrent)); console.log(path.join(dir, "media", f.save)); }
  ' "$SIM_DIR/fixtures.json" "$SIM_DIR/.cache")
  # Faster than fixture playback, slow enough to prove first-frame playback before the full payload arrives.
  set -- seed --listen "127.0.0.1:$SEEDER_PORT" --rate 4194304
  old_ifs=$IFS
  IFS='
'
  for a in $args; do set -- "$@" "$a"; done
  IFS=$old_ifs
  start_service seeder "$SEEDER_PORT" "$STATE_DIR/seeder.log" "$FIXTURE_TOOL" "$@" || return 1
  # The emulator reaches both on its own loopback.
  device reverse "tcp:$FIXTURE_PORT" "tcp:$FIXTURE_PORT" >/dev/null || return 1
  device reverse "tcp:$SEEDER_PORT" "tcp:$SEEDER_PORT" >/dev/null || return 1
  printf 'Fixtures on %s and the seeder on 127.0.0.1:%s reach %s (%s).\n' "$FIXTURE_URL" "$SEEDER_PORT" "$SERIAL" "$AVD_NAME"
}

stop_services() {
  for name in seeder fixtures; do
    pid_file="$STATE_DIR/$name.pid"
    [ -f "$pid_file" ] || continue
    pid=$(cat "$pid_file")
    # Only a process this script started, and only if it still listens where ours did.
    case "$name" in seeder) port=$SEEDER_PORT ;; *) port=$FIXTURE_PORT ;; esac
    if [ -n "$pid" ] && [ "$(listener_pid "$port")" = "$pid" ]; then kill "$pid" && printf 'Stopped %s (PID %s).\n' "$name" "$pid"; fi
    rm -f "$pid_file"
  done
}

# Runs an instrumentation APK's tests and requires the runner's own OK line: am instrument can exit zero on failure.
instrument() ( # test-package [extra args...]
  package=$1; shift
  previous_checkjni=$(device shell getprop debug.checkjni | tr -d '\r') || return 1
  case "$previous_checkjni" in ''|0|1|true|false) ;; *) fail 'Unexpected CheckJNI property value.'; return 1 ;; esac
  # Only this verified emulator, restored when instrumentation ends (including failures).
  trap 'device shell "setprop debug.checkjni '\''$previous_checkjni'\''" >/dev/null 2>&1' EXIT
  device shell setprop debug.checkjni 1 || return 1
  mkdir -p "$SIM_DIR/.cache/evidence" || return 1
  result="$SIM_DIR/.cache/evidence/$package-instrumentation.txt"
  device shell am instrument -w -r "$@" "$package/androidx.test.runner.AndroidJUnitRunner" | tee "$result"
  status=0
  grep -Eq '^OK \([0-9]+ tests?\)' "$result" || status=1
  grep -q 'FAILURES!!!' "$result" && status=1
  grep -Eq 'INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg=' "$result" && status=1
  return "$status"
)

run_tests() {
  start_services || return 1
  prepare_java
  printf 'Building the native engine tests for the emulator...\n'
  (cd "$ANDROID_DIR" && ./gradlew -q :torrent:assembleDebugAndroidTest) || return 1
  require_emulator || return 1
  device install -r -t "$ANDROID_DIR/torrent/build/outputs/apk/androidTest/debug/torrent-debug-androidTest.apk" || return 1
  instrument tv.daniil.torrent.test -e fixtures "$FIXTURE_URL" -e seeder "127.0.0.1:$SEEDER_PORT"
}

show_status() {
  require_emulator || return 1
  printf 'AVD: %s (%s), Android %s (API %s)\n' "$AVD_NAME" "$SERIAL" "$(emulator_property ro.build.version.release)" \
    "$(emulator_property ro.build.version.sdk)"
  printf 'Fixture service: %s\n' "$(curl -fsS --max-time 2 "$FIXTURE_URL/health" 2>/dev/null || echo 'not running')"
  holder=$(listener_pid "$SEEDER_PORT")
  printf 'Seeder: %s\n' "${holder:+running (PID $holder)}${holder:-not running}"
}

case "${1:-}" in
  fixtures) "$SIM_DIR/fixtures.sh" ;;
  services) start_services ;;
  test) run_tests ;;
  status) show_status ;;
  logs)
    device logcat -d -t 400 -v time
    for log in "$STATE_DIR/fixtures.log" "$STATE_DIR/seeder.log"; do [ -f "$log" ] && { printf '\n== %s\n' "$log"; tail -n 40 "$log"; }; done ;;
  stop-services) stop_services ;;
  *) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac

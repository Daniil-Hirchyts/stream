#!/bin/sh
# Stream's local test environment. Every device operation is pinned to one verified emulator (see
# simulator/emulator.sh); fixtures never leave this computer and the emulator's loopback.
#
#   ./simulator.sh fixtures        Generate test media and torrents
#   ./simulator.sh services        Start the fixture service (58133) and seeder (58134) for the emulator
#   ./simulator.sh test            Engine, data and core app regressions (verified emulator only)
#   ./simulator.sh engine-test | data-test
#   ./simulator.sh live-film       Optional: real public-domain films from the internet through the store
#   ./simulator.sh live-addons     Optional: current public store manifests/resources/review installs
#   ./simulator.sh requested-title tt1234567  Explicit title audit using currently accepted providers
#   ./simulator.sh status | logs | stop-services
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$SCRIPT_DIR
SIM_DIR="$SCRIPT_DIR/simulator"
ANDROID_DIR="$SCRIPT_DIR/android"
NATIVE_DIR="$SCRIPT_DIR/native"
. "$SIM_DIR/emulator.sh"

STATE_DIR="${TMPDIR:-/tmp}/stream-simulator"
FIXTURE_PORT=58133
SEEDER_PORT=58134
FIXTURE_URL="http://127.0.0.1:$FIXTURE_PORT"
FIXTURE_TOOL="$NATIVE_DIR/build/host-dev/stream-fixture"

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
  started=$(node "$SIM_DIR/detach.mjs" "$log" "$@") || return 1
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
  for name in tls seeder fixtures; do
    pid_file="$STATE_DIR/$name.pid"
    [ -f "$pid_file" ] || continue
    pid=$(cat "$pid_file")
    # Only a process this script started, and only if it still listens where ours did.
    case "$name" in tls) port=58135 ;; seeder) port=$SEEDER_PORT ;; *) port=$FIXTURE_PORT ;; esac
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
  cp "$result" "$SIM_DIR/.cache/evidence/$package-$(date +%Y%m%d-%H%M%S)-instrumentation.txt"
  return "$status"
)

run_tests() {
  start_services || return 1
  prepare_java
  printf 'Building the native engine tests for the emulator...\n'
  (cd "$ANDROID_DIR" && ./gradlew -q :torrent:assembleDebugAndroidTest) || return 1
  require_emulator || return 1
  device install -r -t "$ANDROID_DIR/torrent/build/outputs/apk/androidTest/debug/torrent-debug-androidTest.apk" || return 1
  instrument tv.stream.torrent.test -e fixtures "$FIXTURE_URL" -e seeder "127.0.0.1:$SEEDER_PORT"
}

run_data_tests() {
  test_classes=${1:-tv.stream.app.AddonTest,tv.stream.app.StoreTest,tv.stream.app.SourcePolicyTest}
  [ $# -eq 0 ] || shift
  start_services || return 1
  tls_dir="$SIM_DIR/.cache/tls"
  mkdir -p "$tls_dir" || return 1
  if [ ! -f "$tls_dir/cert.pem" ]; then
    (umask 077; openssl req -x509 -newkey rsa:2048 -nodes -keyout "$tls_dir/key.pem" \
      -out "$tls_dir/cert.pem" -days 7 -subj '/CN=localhost' >/dev/null 2>&1) || return 1
  fi
  start_service tls 58135 "$STATE_DIR/tls.log" "$(command -v node)" "$SIM_DIR/tls-fixture.mjs" \
    "$tls_dir/key.pem" "$tls_dir/cert.pem" || return 1
  device reverse tcp:58135 tcp:58135 >/dev/null || return 1
  prepare_java
  (cd "$ANDROID_DIR" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest) || return 1
  device install -r "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk" || return 1
  device install -r -t "$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" || return 1
  instrument tv.stream.app.test -e class "$test_classes" \
    -e fixtures "$FIXTURE_URL" -e seeder "127.0.0.1:$SEEDER_PORT" -e badCertificate https://127.0.0.1:58135/manifest.json "$@"
  status=$?
  case "$test_classes" in *ProfileTest*) [ "$status" -ne 0 ] || {
    device exec-out run-as tv.stream.app cat files/validation-profile.json >"$SIM_DIR/.cache/evidence/playback-profile.json" || status=1
    device exec-out run-as tv.stream.app cat files/validation-profile.png >"$SIM_DIR/.cache/evidence/playback-profile.png" || status=1
  } ;; esac
  case "$test_classes" in *NavigationPerformanceTest*) [ "$status" -ne 0 ] || {
    device exec-out run-as tv.stream.app cat files/validation-navigation-performance.json >"$SIM_DIR/.cache/evidence/navigation-performance.json" || status=1
  } ;; esac
  clear_test_mode
  return "$status"
}

# Keep routine runs focused on real playback and durable user outcomes. The remaining
# UI/import/settings paths are available with acceptance; see android/TESTING.md.
run_core_app_tests() {
  core_tests=tv.stream.app.AppFlowTest,tv.stream.app.PlayerInteractionTest
  for method in directMp4MkvHlsAndDashRenderAndAdvance \
    automaticFailoverIsBoundedAndManualSourcesDoNotSilentlyChange \
    decoderAndNetworkFailuresAfterFramesResumeOnDifferentSources \
    contentFileAndMagnetImportsUseRealMetadataAndPlayback; do
    core_tests="$core_tests,tv.stream.app.AcceptanceTest#$method"
  done
  run_data_tests "$core_tests"
}

# Whatever happened to the run, the owner's debug app leaves test mode: the harness overrides and any isolated test
# databases an interrupted run left behind go. The owner's database and player preferences are never touched.
clear_test_mode() {
  device shell am force-stop tv.stream.app >/dev/null 2>&1
  device shell run-as tv.stream.app sh -c "'rm -f shared_prefs/instrumentation.xml databases/test-* databases/addon-test-* shared_prefs/player-test-*.xml'" >/dev/null 2>&1
}

start_emulator() {
  state=$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)
  if [ "$state" = device ]; then require_emulator; return $?; fi
  [ "$state" != offline ] || { fail "$SERIAL is offline; close its window before restarting it."; return 1; }
  "$SIM_DIR/setup.sh" "$SDK_ROOT" "$AVD_NAME" || return 1
  mkdir -p "$STATE_DIR" || return 1
  # The windowed emulator draws with the host GPU; software rendering makes the glass blur and motion stutter.
  # STREAM_GPU=swiftshader suits a host without a usable GPU (headless CI starts its own in simulator/ci.sh).
  set -- -avd "$AVD_NAME" -port "$PORT" -no-snapshot -gpu "${STREAM_GPU:-host}" -feature -Vulkan -no-metrics -crash-report-mode never
  # Opt-in DNS override for hosts whose resolver is unreachable from the emulator's virtual network.
  if [ -n "${STREAM_DNS_SERVERS:-}" ]; then set -- "$@" -dns-server "$STREAM_DNS_SERVERS"; fi
  node "$SIM_DIR/detach.mjs" "$STATE_DIR/emulator.log" "$SDK_ROOT/emulator/emulator" "$@" || return 1
  attempt=0
  while [ "$attempt" -lt 180 ]; do
    state=$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)
    if [ "$state" = device ]; then
      [ "$(emulator_property ro.kernel.qemu)" = 1 ] || fail 'Refusing a non-emulator device.' || return 1
      name=$("$ADB_BIN" -s "$SERIAL" emu avd name | tr -d '\r' | head -n 1)
      [ "$name" = "$AVD_NAME" ] || fail 'Unexpected AVD at this port.' || return 1
      if [ "$(emulator_property sys.boot_completed)" = 1 ]; then require_emulator; return $?; fi
    fi
    attempt=$((attempt + 1)); sleep 1
  done
  fail 'The emulator did not finish booting.'
}

install_app() {
  require_emulator || return 1
  prepare_java
  (cd "$ANDROID_DIR" && ./gradlew -q :app:assembleDebug) || return 1
  device install -r "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
}
open_app() { device shell am start -n tv.stream.app/.MainActivity; }
profile_app() {
  require_emulator || return 1
  evidence="$SIM_DIR/.cache/evidence/profile"
  mkdir -p "$evidence" || return 1
  device shell dumpsys meminfo tv.stream.app >"$evidence/meminfo.txt" || return 1
  device shell dumpsys gfxinfo tv.stream.app >"$evidence/gfxinfo.txt" || return 1
  device exec-out screencap -p >"$evidence/screen.png" || return 1
  printf '%s (%s), API %s, %s\n' "$AVD_NAME" "$SERIAL" "$(emulator_property ro.build.version.sdk)" "$(emulator_property ro.product.cpu.abi)" >"$evidence/device.txt"
  printf 'Emulator measurements saved in %s\n' "$evidence"
}

show_status() {
  require_emulator || return 1
  printf 'AVD: %s (%s), Android %s (API %s)\n' "$AVD_NAME" "$SERIAL" "$(emulator_property ro.build.version.release)" \
    "$(emulator_property ro.build.version.sdk)"
  printf 'Fixture service: %s\n' "$(curl -fsS --max-time 2 "$FIXTURE_URL/health" 2>/dev/null || echo 'not running')"
  holder=$(listener_pid "$SEEDER_PORT")
  if [ -n "$holder" ]; then printf 'Seeder: running (PID %s)\n' "$holder"; else printf 'Seeder: not running\n'; fi
}

case "${1:-up}" in
  up) start_emulator && start_services && install_app && open_app ;;
  start) start_emulator ;;
  install) install_app ;;
  open) start_services && open_app ;;
  site) start_services && printf 'Live test room: %s/\n' "$FIXTURE_URL" ;;
  fixtures) "$SIM_DIR/fixtures.sh" ;;
  services) start_services ;;
  test) run_tests && run_data_tests && run_core_app_tests ;;
  engine-test) run_tests ;;
  data-test) shift; run_data_tests "$@" ;;
  smoke) run_data_tests tv.stream.app.AppFlowTest#catalogueFavouritesPlaybackControlsAndEvictedRewindWork ;;
  decoder-test) sh "$SIM_DIR/decoder-fixtures.sh" && run_data_tests tv.stream.app.DecoderPlaybackTest ;;
  # Not a required suite: public services and swarms change. Uses the internet, never the LAN or a physical TV.
  live-film) run_data_tests tv.stream.app.LiveFilmTest ;;
  live-addons)
    run_data_tests tv.stream.app.LiveAddonTest
    result=$?
    device exec-out run-as tv.stream.app cat files/validation-addons.json >"$SIM_DIR/.cache/evidence/live-addons.json" || exit 1
    exit "$result"
    ;;
  live-torrents)
    case "${2:-}" in *[!A-Za-z0-9,]*) fail 'Use comma-separated provider names without spaces.'; exit 2 ;; esac
    run_data_tests tv.stream.app.LiveTorrentProvidersTest -e providers "${2:-all}"
    result=$?
    device exec-out run-as tv.stream.app cat files/validation-torrents.json >"$SIM_DIR/.cache/evidence/live-torrents.json" || exit 1
    exit "$result"
    ;;
  requested-title)
    printf '%s' "${2:-}" | grep -Eq '^tt[0-9]+(,tt[0-9]+)*$' || { fail 'Supply the explicitly requested IMDb title ID.'; exit 2; }
    run_data_tests tv.stream.app.InstalledTitlePlaybackTest -e titleIds "$2"
    result=$?
    device exec-out run-as tv.stream.app cat files/validation-requested-title.json >"$SIM_DIR/.cache/evidence/requested-title.json" || exit 1
    exit "$result"
    ;;
  acceptance) run_data_tests "${2:-tv.stream.app.AppFlowTest,tv.stream.app.AcceptanceTest,tv.stream.app.PlayerInteractionTest}" ;;
  process-death)
    start_services && install_app && (prepare_java; cd "$ANDROID_DIR"; ./gradlew -q :app:assembleDebugAndroidTest) && \
      device install -r -t "$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" && "$SIM_DIR/process-death.sh"
    ;;
  release-test) start_services && "$SIM_DIR/release-probe.sh" ;;
  soak)
    run_data_tests tv.stream.app.SoakTest || exit $?
    device exec-out run-as tv.stream.app cat files/validation-soak.json >"$SIM_DIR/.cache/evidence/soak.json"
    ;;
  profile) profile_app ;;
  status) show_status ;;
  logs)
    device logcat -d -t 400 -v time || exit $?
    for log in "$STATE_DIR/fixtures.log" "$STATE_DIR/seeder.log"; do [ -f "$log" ] && { printf '\n== %s\n' "$log"; tail -n 40 "$log"; }; done ;;
  stop-services) stop_services ;;
  *) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac

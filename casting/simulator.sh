#!/bin/sh
# Local Daniil Cast simulator menu. Every device operation is pinned to one AVD.
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
RECEIVER_DIR="$SCRIPT_DIR/receiver"
PANEL_DIR="$SCRIPT_DIR/sidepanel"
AVD_NAME=${DANIIL_CAST_AVD:-DaniilCast_Local_TV}
PORT=${DANIIL_CAST_PORT:-5556}
case "$PORT" in
  ''|*[!0-9]*) printf 'DANIIL_CAST_PORT must be an even emulator console port.\n' >&2; exit 2 ;;
esac
if [ "$PORT" -lt 5554 ] || [ "$PORT" -gt 5682 ] || [ $((PORT % 2)) -ne 0 ]; then
  printf 'DANIIL_CAST_PORT must be an even port from 5554 through 5682.\n' >&2
  exit 2
fi
SERIAL=emulator-$PORT
PACKAGE=tv.daniil.cast
ACTIVITY=tv.daniil.cast/.PlayerActivity
PREVIEW_URL=http://127.0.0.1:58131
SIM_STATE_DIR="${TMPDIR:-/tmp}/daniil-cast-simulator"
PREVIEW_LOG="$SIM_STATE_DIR/sidepanel-preview.log"
TEST_URL=http://127.0.0.1:58132

if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  SDK_ROOT=$ANDROID_SDK_ROOT
elif [ -n "${ANDROID_HOME:-}" ]; then
  SDK_ROOT=$ANDROID_HOME
else
  SDK_ROOT="$HOME/Library/Android/sdk"
fi

ADB_BIN=${ADB:-$SDK_ROOT/platform-tools/adb}
EMULATOR_BIN="$SDK_ROOT/emulator/emulator"

fail() {
  printf 'Error: %s\n' "$1" >&2
  return 1
}

require_android_tools() {
  [ -x "$ADB_BIN" ] || fail "ADB not found at $ADB_BIN (set ANDROID_HOME or ANDROID_SDK_ROOT)." || return 1
  [ -x "$EMULATOR_BIN" ] || fail "Android emulator not found at $EMULATOR_BIN." || return 1
}

emulator_property() {
  "$ADB_BIN" -s "$SERIAL" shell getprop "$1" 2>/dev/null | tr -d '\r'
}

require_emulator() {
  require_android_tools || return 1
  state=$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)
  [ "$state" = device ] || fail "The local emulator is not ready. Choose Start emulator first." || return 1
  qemu=$(emulator_property ro.kernel.qemu)
  [ "$qemu" = 1 ] || fail "Refusing device $SERIAL because Android did not identify it as an emulator." || return 1
  running_avd=$("$ADB_BIN" -s "$SERIAL" emu avd name 2>/dev/null | tr -d '\r' | head -n 1)
  [ "$running_avd" = "$AVD_NAME" ] || fail "$SERIAL is running $running_avd, not $AVD_NAME. Choose a free DANIIL_CAST_PORT." || return 1
  boot=$(emulator_property sys.boot_completed)
  [ "$boot" = 1 ] || fail "The emulator is still booting. Choose Start emulator again in a moment." || return 1
}

wait_for_emulator() {
  attempt=0
  while [ "$attempt" -lt 180 ]; do
    state=$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)
    if [ "$state" = device ] && [ "$(emulator_property ro.kernel.qemu)" = 1 ]; then
      if [ "$(emulator_property sys.boot_completed)" = 1 ]; then
        configure_bridges || return 1
        printf 'Emulator is ready (%s).\n' "$SERIAL"
        return 0
      fi
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  printf 'Emulator did not finish booting. Recent emulator log:\n' >&2
  tail -n 30 "$SIM_STATE_DIR/emulator.log" 2>/dev/null || true
  fail 'Timed out waiting for the local emulator.'
}

start_emulator() {
  require_android_tools || return 1
  mkdir -p "$SIM_STATE_DIR" || return 1
  state=$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)
  if [ "$state" = device ]; then
    [ "$(emulator_property ro.kernel.qemu)" = 1 ] || fail 'Refusing a non-emulator device.' || return 1
    running_avd=$("$ADB_BIN" -s "$SERIAL" emu avd name 2>/dev/null | tr -d '\r' | head -n 1)
    [ "$running_avd" = "$AVD_NAME" ] || fail "$SERIAL is running $running_avd, not $AVD_NAME." || return 1
    wait_for_emulator
    return $?
  fi
  if [ "$state" = offline ]; then
    fail "$SERIAL is offline. Close that emulator window, then choose Start emulator again."
    return 1
  fi

  "$SCRIPT_DIR/simulator/setup.sh" "$SDK_ROOT" "$AVD_NAME" || return 1
  printf 'Starting %s with a visible window and sound...\n' "$AVD_NAME"
  node "$SCRIPT_DIR/simulator/detach.mjs" "$SIM_STATE_DIR/emulator.log" \
    "$EMULATOR_BIN" -avd "$AVD_NAME" -port "$PORT" \
    -no-snapshot -gpu "${DANIIL_CAST_GPU:-host}" -feature -Vulkan || return 1
  wait_for_emulator
}

prepare_java() {
  if [ -x /usr/libexec/java_home ]; then
    detected_java=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)
    if [ -n "$detected_java" ]; then
      JAVA_HOME=$detected_java
      export JAVA_HOME
    fi
  fi
  export ANDROID_HOME="$SDK_ROOT"
  export ANDROID_SDK_ROOT="$SDK_ROOT"
  export ADB="$ADB_BIN"
}

build_install_launch_receiver() {
  require_emulator || return 1
  prepare_java
  printf 'Building the current receiver source...\n'
  (cd "$RECEIVER_DIR" && ./gradlew assembleDebug) || return 1
  require_emulator || return 1
  printf 'Installing only to %s...\n' "$SERIAL"
  "$ADB_BIN" -s "$SERIAL" install -r \
    "$RECEIVER_DIR/app/build/outputs/apk/debug/app-debug.apk" || return 1
  # Android 10+ requires this per-app permission to open a paired cast from Home.
  # Only the verified emulator's debug receiver is configured, never a LAN device.
  if "$ADB_BIN" -s "$SERIAL" shell dumpsys package "$PACKAGE" | grep -q DEBUGGABLE; then
    "$ADB_BIN" -s "$SERIAL" shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow || return 1
  fi
  launch_receiver
}

launch_receiver() {
  require_emulator || return 1
  app_path=$("$ADB_BIN" -s "$SERIAL" shell pm path "$PACKAGE" 2>/dev/null || true)
  case "$app_path" in
    package:*) ;;
    *) fail 'Receiver is not installed yet. Choose Build + install receiver first.'; return 1 ;;
  esac
  "$ADB_BIN" -s "$SERIAL" shell am start -n "$ACTIVITY" || return 1
  bridge_emulator
}

run_receiver_tests() {
  require_emulator || return 1
  prepare_java
  "$RECEIVER_DIR/test-emulator.sh" "$SERIAL"
  test_result=$?
  launch_receiver || return 1
  return "$test_result"
}

run_playback_tests() {
  require_emulator || return 1
  launch_receiver || return 1
  bridge_emulator || return 1
  open_test_site || return 1
  node "$SCRIPT_DIR/simulator/smoke.mjs" "$ADB_BIN" "$SERIAL" "$AVD_NAME"
}

build_panel() {
  command -v npm >/dev/null 2>&1 || fail 'npm was not found; install Node.js first.' || return 1
  printf 'Checking and building the side panel...\n'
  if [ ! -d "$PANEL_DIR/node_modules" ]; then
    (cd "$PANEL_DIR" && npm ci) || return 1
  fi
  (cd "$PANEL_DIR" && npm run check) || return 1
  (cd "$PANEL_DIR" && npm test) || return 1
  (cd "$PANEL_DIR" && npm run build)
}

open_panel_preview() {
  command -v node >/dev/null 2>&1 || fail 'Node.js was not found; install Node.js first.' || return 1
  command -v curl >/dev/null 2>&1 || fail 'curl is required to check the local preview server.' || return 1
  mkdir -p "$SIM_STATE_DIR" || return 1
  if ! curl -fsS "$PREVIEW_URL/" 2>/dev/null | grep -q '<title>Daniil Cast</title>'; then
    printf 'Starting the sample-data side-panel preview...\n'
    (cd "$PANEL_DIR" && nohup node tests/preview.mjs >"$PREVIEW_LOG" 2>&1 </dev/null &)
    attempt=0
    ready=0
    while [ "$attempt" -lt 20 ]; do
      if curl -fsS "$PREVIEW_URL/" 2>/dev/null | grep -q '<title>Daniil Cast</title>'; then
        ready=1
        break
      fi
      attempt=$((attempt + 1))
      sleep 1
    done
    [ "$ready" = 1 ] || {
      tail -n 20 "$PREVIEW_LOG" 2>/dev/null || true
      fail 'Could not start the side-panel preview.'
      return 1
    }
  fi
  printf 'Opening %s (stubbed Chrome APIs and sample data).\n' "$PREVIEW_URL"
  if command -v open >/dev/null 2>&1; then
    open "$PREVIEW_URL"
  else
    printf 'Open this URL in a browser: %s\n' "$PREVIEW_URL"
  fi
}

show_status() {
  if ! require_emulator; then
    printf 'Start the local emulator with menu option 1.\n'
    return 1
  fi
  printf 'AVD: %s (%s)\n' "$AVD_NAME" "$SERIAL"
  printf 'Boot complete: %s\n' "$(emulator_property sys.boot_completed)"
  printf 'Android: %s (API %s)\n' "$(emulator_property ro.build.version.release)" "$(emulator_property ro.build.version.sdk)"
  app_path=$("$ADB_BIN" -s "$SERIAL" shell pm path "$PACKAGE" 2>/dev/null || true)
  if [ -n "$app_path" ]; then
    version=$("$ADB_BIN" -s "$SERIAL" shell dumpsys package "$PACKAGE" 2>/dev/null \
      | sed -n 's/.*versionName=//p' | head -n 1 | tr -d '\r')
    printf 'Receiver: installed (version %s)\n' "${version:-unknown}"
  else
    printf 'Receiver: not installed\n'
  fi
}

configure_bridges() {
  require_emulator || return 1
  "$ADB_BIN" -s "$SERIAL" forward --no-rebind tcp:8765 tcp:8765 2>/dev/null || {
    bridge_owner=$("$ADB_BIN" -s "$SERIAL" forward --list | awk '$2 == "tcp:8765" {print $1}')
    [ "$bridge_owner" = "$SERIAL" ] || { fail "Port 8765 already belongs to $bridge_owner. Remove that emulator bridge first."; return 1; }
  }
  "$ADB_BIN" -s "$SERIAL" reverse tcp:58132 tcp:58132 || return 1
}

bridge_emulator() {
  configure_bridges || return 1
  command -v curl >/dev/null 2>&1 || fail 'curl is required to verify the emulator bridge.' || return 1
  attempt=0
  until curl -fsS --max-time 2 http://127.0.0.1:8765/v1/info >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 15 ] || { fail 'The receiver did not start. Check emulator logs with the logs command.'; return 1; }
    sleep 1
  done
  printf 'Emulator receiver is reachable at 127.0.0.1:8765.\n'
  printf 'In the extension, open Developer → Connect by address and enter 127.0.0.1.\n'
  printf 'The receiver will show a pairing code in the emulator. Pairing this adds the emulator as a separate device.\n'
}

open_test_site() {
  require_emulator || return 1
  command -v node >/dev/null 2>&1 || fail 'Node.js is needed for the local test site.' || return 1
  mkdir -p "$SIM_STATE_DIR" || return 1
  "$SCRIPT_DIR/simulator/fixtures.sh" || return 1
  if ! curl -fsS "$TEST_URL/health" >/dev/null 2>&1; then
    node "$SCRIPT_DIR/simulator/detach.mjs" "$SIM_STATE_DIR/test-site.log" \
      "$(command -v node)" --watch "$SCRIPT_DIR/simulator/server.mjs" "$ADB_BIN" "$SERIAL" "$AVD_NAME" || return 1
    attempt=0
    until curl -fsS "$TEST_URL/health" >/dev/null 2>&1; do
      attempt=$((attempt + 1))
      [ "$attempt" -lt 20 ] || { cat "$SIM_STATE_DIR/test-site.log"; return 1; }
      sleep 1
    done
  fi
  health=$(curl -fsS "$TEST_URL/health") || return 1
  node -e 'const h=JSON.parse(process.argv[1]); if(h.serial!==process.argv[2]||h.avd!==process.argv[3]) { console.error("The test site belongs to another emulator. Stop that test-site process before switching AVDs."); process.exit(1); }' "$health" "$SERIAL" "$AVD_NAME" || return 1
  "$ADB_BIN" -s "$SERIAL" reverse tcp:58132 tcp:58132 || return 1
  printf 'Local test page: %s\n' "$TEST_URL"
  if command -v open >/dev/null 2>&1; then open -a 'Google Chrome' "$TEST_URL"; fi
}

start_everything() {
  start_emulator || return 1
  build_install_launch_receiver || return 1
  build_panel || return 1
  printf 'Building and installing the current desktop helper...\n'
  "$SCRIPT_DIR/native/build.sh" --install || return 1
  bridge_emulator || return 1
  node "$SCRIPT_DIR/simulator/select-device.mjs" "$ADB_BIN" "$SERIAL" "$AVD_NAME" || return 1
  open_test_site || return 1
  node "$SCRIPT_DIR/simulator/demo.mjs" "$ADB_BIN" "$SERIAL" "$AVD_NAME" || return 1
  printf '\nReady: the local sample is playing in the emulator.\n'
  printf 'Open Daniil Cast in Chrome to cast another video. Use ./casting/simulator.sh menu for other commands.\n'
  printf 'The visible Android TV window is the receiving screen. Sound is enabled.\n'
}

remove_emulator_bridge() {
  require_android_tools || return 1
  state=$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)
  [ "$state" = device ] || fail "The local emulator ($SERIAL) is not ready." || return 1
  [ "$(emulator_property ro.kernel.qemu)" = 1 ] || fail "Refusing device $SERIAL because Android did not identify it as an emulator." || return 1
  "$ADB_BIN" -s "$SERIAL" forward --remove tcp:8765
}

show_logs() {
  require_emulator || return 1
  "$ADB_BIN" -s "$SERIAL" logcat -d -t 400 -v time
}

save_screenshot() {
  require_emulator || return 1
  mkdir -p "$SIM_STATE_DIR" || return 1
  screenshot="$SIM_STATE_DIR/screen-$(date +%Y%m%d-%H%M%S).png"
  "$ADB_BIN" -s "$SERIAL" exec-out screencap -p >"$screenshot" || return 1
  printf 'Saved emulator screenshot to %s\n' "$screenshot"
  if command -v open >/dev/null 2>&1; then open "$screenshot"; fi
}

stop_emulator() {
  require_android_tools || return 1
  state=$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)
  if [ "$state" != device ]; then
    printf 'The local emulator is not running.\n'
    return 0
  fi
  [ "$(emulator_property ro.kernel.qemu)" = 1 ] || fail "Refusing to stop $SERIAL because Android did not identify it as an emulator." || return 1
  require_emulator || return 1
  "$ADB_BIN" -s "$SERIAL" emu kill
}

pause_menu() {
  printf '\nPress Return to continue...'
  IFS= read -r _ || true
}

case "${1:-up}" in
  up) start_everything; exit $? ;;
  start) start_emulator; exit $? ;;
  install) build_install_launch_receiver; exit $? ;;
  open) launch_receiver; exit $? ;;
  test) run_receiver_tests; exit $? ;;
  smoke) run_playback_tests; exit $? ;;
  site) open_test_site; exit $? ;;
  bridge) bridge_emulator; exit $? ;;
  status) show_status; exit $? ;;
  logs) show_logs; exit $? ;;
  stop) stop_emulator; exit $? ;;
  setup) "$SCRIPT_DIR/simulator/setup.sh" "$SDK_ROOT" "$AVD_NAME"; exit $? ;;
  menu) ;;
  *) printf 'Usage: %s [up|menu|start|install|open|test|smoke|site|bridge|status|logs|stop|setup]\n' "$0"; exit 2 ;;
esac

while :; do
  printf '\nDaniil Cast local simulator · %s (%s)\n' "$AVD_NAME" "$SERIAL"
  printf '  0  Start everything (latest app + extension + local test page)\n'
  printf '  1  Start emulator\n'
  printf '  2  Build, install latest receiver, and open it\n'
  printf '  3  Open installed receiver\n'
  printf '  4  Run receiver tests (subtitles, errors, touch and mouse)\n'
  printf '  5  Check and build extension side panel\n'
  printf '  6  Open side-panel preview (sample data)\n'
  printf '  7  Show emulator and app status\n'
  printf '  8  Show recent emulator logs\n'
  printf '  9  Save/open emulator screenshot\n'
  printf ' 10  Stop emulator\n'
  printf ' 11  Bridge emulator for extension pairing\n'
  printf ' 12  Remove emulator pairing bridge\n'
  printf ' 13  Open real local playback test page\n'
  printf ' 14  Test playback, audio, subtitles, quality and conversion\n'
  printf '  q  Quit\n'
  printf 'Select: '
  IFS= read -r choice || exit 0
  case "$choice" in
    0) start_everything; pause_menu ;;
    1) start_emulator; pause_menu ;;
    2) build_install_launch_receiver; pause_menu ;;
    3) launch_receiver; pause_menu ;;
    4) run_receiver_tests; pause_menu ;;
    5) build_panel; pause_menu ;;
    6) open_panel_preview; pause_menu ;;
    7) show_status; pause_menu ;;
    8) show_logs; pause_menu ;;
    9) save_screenshot; pause_menu ;;
    10) stop_emulator; pause_menu ;;
    11) bridge_emulator; pause_menu ;;
    12) remove_emulator_bridge; pause_menu ;;
    13) open_test_site; pause_menu ;;
    14) run_playback_tests; pause_menu ;;
    q|Q) exit 0 ;;
    *) printf 'Choose a number from 0 to 14, or q.\n' ;;
  esac
done

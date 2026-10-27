# Sourced by the Stream simulator scripts. Every device operation names one emulator, verified by serial,
# ro.kernel.qemu and AVD name; there is no fallback to another device. A custom AVD needs both STREAM_AVD and
# STREAM_PORT, so the name and the port can't disagree.

case "${STREAM_AVD:+avd}-${STREAM_PORT:+port}" in
  avd-|-port) printf 'Set both STREAM_AVD and STREAM_PORT, or neither.\n' >&2; exit 2 ;;
esac
AVD_NAME=${STREAM_AVD:-Stream_Local_TV}
PORT=${STREAM_PORT:-5558}
case "$PORT" in
  ''|*[!0-9]*) printf 'STREAM_PORT must be an even emulator console port.\n' >&2; exit 2 ;;
esac
if [ "$PORT" -lt 5554 ] || [ "$PORT" -gt 5682 ] || [ $((PORT % 2)) -ne 0 ]; then
  printf 'STREAM_PORT must be an even port from 5554 through 5682.\n' >&2
  exit 2
fi
SERIAL=emulator-$PORT

if [ -n "${ANDROID_SDK_ROOT:-}" ]; then SDK_ROOT=$ANDROID_SDK_ROOT
elif [ -n "${ANDROID_HOME:-}" ]; then SDK_ROOT=$ANDROID_HOME
else SDK_ROOT="$HOME/Library/Android/sdk"
fi
ADB_BIN=${ADB:-$SDK_ROOT/platform-tools/adb}

fail() {
  printf 'Error: %s\n' "$1" >&2
  return 1
}

emulator_property() {
  "$ADB_BIN" -s "$SERIAL" shell getprop "$1" 2>/dev/null | tr -d '\r'
}

require_emulator() {
  [ -x "$ADB_BIN" ] || fail "ADB not found at $ADB_BIN (set ANDROID_HOME or ANDROID_SDK_ROOT)." || return 1
  state=$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)
  [ "$state" = device ] || fail "$SERIAL is not running. Start it with ./simulator.sh start." || return 1
  [ "$(emulator_property ro.kernel.qemu)" = 1 ] \
    || fail "Refusing $SERIAL: Android did not identify it as an emulator." || return 1
  running_avd=$("$ADB_BIN" -s "$SERIAL" emu avd name 2>/dev/null | tr -d '\r' | head -n 1)
  [ "$running_avd" = "$AVD_NAME" ] || fail "Refusing $SERIAL: it runs '$running_avd', not '$AVD_NAME'." || return 1
  [ "$(emulator_property sys.boot_completed)" = 1 ] || fail "$SERIAL is still booting." || return 1
}

# adb for the verified emulator only.
device() {
  require_emulator || return 1
  "$ADB_BIN" -s "$SERIAL" "$@"
}

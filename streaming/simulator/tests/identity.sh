#!/bin/sh
# Mocked-device tests for simulator/emulator.sh: a fake adb answers as a chosen device, and every case checks that
# only the verified emulator would be used. No real device is contacted.
set -u
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
fake="$work/adb"
calls="$work/calls"
cat >"$fake" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$FAKE_CALLS"
[ "$1" = -s ] || { echo 'no serial' >&2; exit 9; }
shift 2
case "$*" in
  get-state) printf '%s\n' "${FAKE_STATE:-device}" ;;
  'shell getprop ro.kernel.qemu') printf '%s\r\n' "${FAKE_QEMU:-1}" ;;
  'shell getprop sys.boot_completed') printf '%s\r\n' "${FAKE_BOOT:-1}" ;;
  'emu avd name') printf '%s\r\nOK\r\n' "${FAKE_AVD:-DaniilCast_Local_TV}" ;;
  *) exit 0 ;;
esac
EOF
chmod +x "$fake"

passed=0
failed=0
# expect <ok|refused|exit2> <description> [VAR=value...]
expect() {
  want=$1; what=$2; shift 2
  : >"$calls"
  (
    export ADB="$fake" FAKE_CALLS="$calls"
    unset DANIIL_CAST_AVD DANIIL_CAST_PORT
    for assignment in "$@"; do export "$assignment"; done
    . "$here/../emulator.sh"
    require_emulator 2>/dev/null
  )
  status=$?
  case "$want:$status" in
    ok:0|refused:1|exit2:2) result=pass ;;
    *) result=fail ;;
  esac
  # Every adb call names the expected emulator serial; nothing goes to another device.
  if grep -qv '^-s emulator-[0-9]* ' "$calls"; then result=fail; fi
  serial=$(sed -n 's/^-s \(emulator-[0-9]*\) .*/\1/p' "$calls" | sort -u)
  for s in $serial; do
    case "$*" in *DANIIL_CAST_PORT=*) expected="emulator-$(printf '%s\n' "$@" | sed -n 's/^DANIIL_CAST_PORT=//p')" ;; *) expected=emulator-5556 ;; esac
    [ "$s" = "$expected" ] || result=fail
  done
  if [ "$result" = pass ]; then passed=$((passed + 1)); printf 'PASS %s\n' "$what"
  else failed=$((failed + 1)); printf 'FAIL %s (status %s)\n' "$what" "$status"; cat "$calls"; fi
}

expect ok 'the default emulator is accepted'
expect refused 'a device that is not an emulator is refused' FAKE_QEMU=0
expect refused 'another AVD on the expected port is refused' FAKE_AVD=DaniilCast_TV
expect refused 'an offline emulator is refused' FAKE_STATE=offline
expect refused 'a booting emulator is refused' FAKE_BOOT=0
expect exit2 'a custom AVD without its port is rejected' DANIIL_CAST_AVD=Other
expect exit2 'a custom port without its AVD is rejected' DANIIL_CAST_PORT=5558
expect exit2 'an odd console port is rejected' DANIIL_CAST_AVD=Other DANIIL_CAST_PORT=5557
expect exit2 'a port outside the emulator range is rejected' DANIIL_CAST_AVD=Other DANIIL_CAST_PORT=5555
expect ok 'a custom AVD and port are accepted together' DANIIL_CAST_AVD=Other DANIIL_CAST_PORT=5558 FAKE_AVD=Other
expect refused 'a custom port running a different AVD is refused' DANIIL_CAST_AVD=Other DANIIL_CAST_PORT=5558

printf '%d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]

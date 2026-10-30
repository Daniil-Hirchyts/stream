#!/bin/sh
# Checks already built release outputs: ABI and universal APKs, the App Bundle, 16 KiB alignment, stored native
# libraries, the shipped library set and builder paths. It builds, installs and signs nothing.
#
#   (cd android && ./gradlew -PabiSplits=true :app:assembleRelease)
#   (cd android && ./gradlew :app:bundleRelease)
#   simulator/check-distribution.sh [outputs-dir]    # default: android/app/build/outputs
#
# Exits nonzero when anything fails. One known exception: the NDK's armeabi-v7a libc++_shared.so is a 4 KiB prebuilt,
# and 16 KiB pages exist only on 64-bit Android (see docs/decoders.md). Any other library below 16 KiB fails.
set -u
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
outputs=${1:-$root/android/app/build/outputs}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
ndk=$(awk '$1 == "ndk" {print $2}' "$root/native/deps.lock")
tools=$(find "$sdk/ndk/$ndk/toolchains/llvm/prebuilt" -name llvm-readelf | head -n 1)
build_tools=$sdk/build-tools/$(ls "$sdk/build-tools" | sort -V | tail -n 1)
abis='armeabi-v7a arm64-v8a x86_64'
required='libandroidx.graphics.path libavcodec libavutil libswresample libswscale libmedia3ext libc++_shared libstream'
builder_paths="/Users/|/home/[a-z]|/private/|/var/folders/|/opt/homebrew|/usr/local/|Library/Android|$HOME"

[ -x "$tools" ] && [ -x "$build_tools/zipalign" ] && [ -x "$build_tools/aapt2" ] || { echo "Missing NDK or build tools under $sdk" >&2; exit 2; }
strings_tool=$(dirname "$tools")/llvm-strings
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fail() { printf 'FAIL %s\n' "$1" >>"$work/failures"; }

[ -d "$outputs" ] || { echo "No outputs at $outputs" >&2; exit 2; }

# check_libs <label> <directory holding lib/<abi>/*.so>; prints one row per library.
check_libs() {
  label=$1; dir=$2
  for abi in $abis; do
    [ -d "$dir/lib/$abi" ] || continue
    for name in $required; do
      [ -f "$dir/lib/$abi/$name.so" ] || fail "$label: $abi lacks $name.so"
    done
    # Media3 extracts containers; the shipping APK has never needed libavformat or its network stack.
    [ ! -f "$dir/lib/$abi/libavformat.so" ] || fail "$label: $abi packages libavformat.so"
    for so in "$dir/lib/$abi"/*.so; do
      lib=$(basename "$so")
      case " $required " in
        *" ${lib%.so} "*) ;;
        *) fail "$label: $abi packages unexpected library $lib" ;;
      esac
      bytes=$(wc -c <"$so" | tr -d ' ')
      align=$("$tools" -lW "$so" | awk '$1 == "LOAD" {print $NF}' | while read -r hex; do printf '%d\n' "$hex"; done | sort -n | head -n 1)
      note=
      if [ -z "$align" ]; then
        fail "$label: $abi/$lib has no LOAD segment"; align=0
      elif [ "$align" -lt 16384 ]; then
        if [ "$abi/$lib" = armeabi-v7a/libc++_shared.so ] && [ "$align" -ge 4096 ]; then
          note='known 32-bit NDK prebuilt'
        else
          fail "$label: $abi/$lib LOAD alignment $align"; note=FAIL
        fi
      fi
      if "$tools" -dW "$so" | grep NEEDED | grep -Eq 'lib(avformat|mbedtls|mbedcrypto|mbedx509)\.so'; then
        fail "$label: $abi/$lib depends on an unshipped format/network library"
      fi
      leaked=$("$strings_tool" -a "$so" | grep -E "$builder_paths" | head -n 2 | cut -c 1-100)
      if [ -n "$leaked" ]; then
        fail "$label: $abi/$lib names builder paths: $leaked"; note="$note paths!"
      fi
      printf '%-14s %-12s %-28s %10s %6s %s\n' "$label" "$abi" "$lib" "$bytes" "$((align / 1024))K" "$note" >>"$work/libraries"
    done
  done
}

check_apk() {
  apk=$1; label=$2
  apk_bytes=$(wc -c <"$apk" | tr -d ' ')
  if "$build_tools/zipalign" -c -P 16 -v 4 "$apk" >"$work/zipalign" 2>&1; then
    aligned=OK
  else
    aligned=FAIL; fail "$label: zipalign -c -P 16 -v 4"
  fi
  if grep -E 'lib/.*\.so' "$work/zipalign" | grep -v '(OK)$' >"$work/compressed"; [ -s "$work/compressed" ]; then
    stored=NO; fail "$label: native libraries are compressed or misaligned: $(head -n 1 "$work/compressed")"
  else
    stored=yes
  fi
  extract=$("$build_tools/aapt2" dump xmltree --file AndroidManifest.xml "$apk" 2>/dev/null |
    sed -n 's/.*extractNativeLibs([^)]*)=\([a-z]*\).*/\1/p' | head -n 1)
  [ "$extract" = false ] || fail "$label: extractNativeLibs is '${extract:-absent}'"
  rm -rf "$work/x"; mkdir "$work/x"
  unzip -q "$apk" 'lib/*' -d "$work/x" 2>/dev/null || fail "$label: cannot extract native libraries"
  if [ "$label" = universal ]; then expected_abis=$abis; else expected_abis=$label; fi
  for expected_abi in $expected_abis; do
    [ -d "$work/x/lib/$expected_abi" ] || fail "$label: no $expected_abi libraries"
  done
  for abi_dir in "$work/x/lib/"*; do
    [ -d "$abi_dir" ] || continue
    case " $expected_abis " in
      *" $(basename "$abi_dir") "*) ;;
      *) fail "$label: unexpected ABI $(basename "$abi_dir")" ;;
    esac
  done
  check_libs "$label" "$work/x"
  printf '%-26s %12s %-9s %-7s %s\n' "$label" "$apk_bytes" "$aligned" "$stored" "${extract:-absent}" >>"$work/outputs"
}

for abi in $abis universal; do
  apk=$(find "$outputs/apk/release" -maxdepth 1 -name "app-$abi-*.apk" 2>/dev/null | head -n 1)
  if [ -n "$apk" ]; then check_apk "$apk" "$abi"; else fail "no $abi APK under $outputs/apk/release (build with -PabiSplits=true)"; fi
done

bundle=$outputs/bundle/release/app-release.aab
if [ -f "$bundle" ]; then
  rm -rf "$work/x"; mkdir "$work/x"
  unzip -q "$bundle" 'base/lib/*' -d "$work/x" 2>/dev/null || fail 'bundle: cannot extract native libraries'
  check_libs bundle "$work/x/base"
  for abi in $abis; do [ -d "$work/x/base/lib/$abi" ] || fail "bundle: no $abi libraries"; done
  for abi_dir in "$work/x/base/lib/"*; do
    [ -d "$abi_dir" ] || continue
    case " $abis " in
      *" $(basename "$abi_dir") "*) ;;
      *) fail "bundle: unexpected ABI $(basename "$abi_dir")" ;;
    esac
  done
  # Read the pinned BundleConfig protobuf directly; Gradle's bundletool dependency is not an executable jar.
  # BundleConfig.optimizations (2) -> uncompress_native_libraries (2) -> alignment (2), enum 2 = 16 KiB.
  page=$(python3 - "$bundle" <<'PYCONFIG'
import sys, zipfile

def fields(data):
    i = 0
    def varint():
        nonlocal i
        value = shift = 0
        while i < len(data) and shift < 70:
            byte = data[i]; i += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value
            shift += 7
        raise ValueError("invalid protobuf varint")
    while i < len(data):
        key = varint()
        number, wire = key >> 3, key & 7
        if number == 0:
            raise ValueError("invalid protobuf field")
        if wire == 0:
            yield number, varint()
        elif wire in (1, 5):
            i += 8 if wire == 1 else 4
        elif wire == 2:
            size = varint()
            end = i + size
            if end > len(data):
                raise ValueError("truncated protobuf field")
            yield number, data[i:end]
            i = end
        else:
            raise ValueError("unexpected protobuf wire type")
        if i > len(data):
            raise ValueError("truncated protobuf field")

def field(data, number):
    return next((value for key, value in fields(data) if key == number), None)

try:
    with zipfile.ZipFile(sys.argv[1]) as bundle:
        optimizations = field(bundle.read("BundleConfig.pb"), 2) or b""
        native = field(optimizations, 2) or b""
        print({1: "4K", 2: "16K", 3: "64K"}.get(field(native, 2), "unset"))
except (OSError, KeyError, ValueError, TypeError, zipfile.BadZipFile) as error:
    print(f"Cannot read BundleConfig: {error}", file=sys.stderr)
    sys.exit(1)
PYCONFIG
) || page=unreadable
  [ "$page" = 16K ] || fail "bundle: BundleConfig page alignment is '$page', expected 16K"
  printf '%-26s %12s %-9s %-7s %s\n' bundle "$(wc -c <"$bundle" | tr -d ' ')" "$page" - - >>"$work/outputs"
else
  fail "no App Bundle at $bundle (build :app:bundleRelease)"
fi

printf '%-26s %12s %-9s %-7s %s\n' Output Bytes Zipalign Stored extractNativeLibs
[ ! -f "$work/outputs" ] || cat "$work/outputs"
printf '\n%-14s %-12s %-28s %10s %6s\n' Output ABI Library Bytes LOAD
[ ! -f "$work/libraries" ] || cat "$work/libraries"
echo
if [ -f "$work/failures" ]; then
  cat "$work/failures"
  printf '%s failure(s).\n' "$(wc -l <"$work/failures" | tr -d ' ')"
  exit 1
fi
echo 'All distribution checks passed. Ordinary release outputs may be unsigned; this checks neither signatures nor devices.'

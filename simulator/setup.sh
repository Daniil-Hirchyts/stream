#!/bin/sh
# Install a current TV image. Preserve existing AVD data and enable pointer input.
set -eu
sdk=$1
avd=$2
case "$(uname -m)" in arm64|aarch64) abi=arm64-v8a ;; *) abi=x86_64 ;; esac
image="system-images;android-36;android-tv;$abi"
image_dir="$sdk/system-images/android-36/android-tv/$abi"
tools=
for candidate in "$sdk"/cmdline-tools/latest "$sdk"/cmdline-tools/*; do
  if [ -x "$candidate/bin/sdkmanager" ]; then tools=$candidate; break; fi
done
if [ -z "$tools" ]; then
  printf 'Install Android SDK Command-line Tools in %s/cmdline-tools first.\n' "$sdk" >&2
  exit 1
fi
if [ -x /usr/libexec/java_home ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)
  export JAVA_HOME
fi
if [ ! -s "$image_dir/system.img" ] || [ ! -s "$image_dir/vendor.img" ]; then
  printf 'Installing Android TV 16 system image and emulator (first run only)...\n'
  "$tools/bin/sdkmanager" --sdk_root="$sdk" emulator "$image"
fi
if ! "$sdk/emulator/emulator" -list-avds | grep -Fxq "$avd"; then
  printf 'Creating %s...\n' "$avd"
  printf 'no\n' | "$tools/bin/avdmanager" create avd -n "$avd" -k "$image" -d tv_1080p
  avd_dir=${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}
  config="$avd_dir/$avd.avd/config.ini"
  # Set existing keys once; do not append duplicate configuration entries.
  node - "$config" "$avd" <<'JS'
const fs = require('node:fs');
const [file, name] = process.argv.slice(2);
const settings = new Map(fs.readFileSync(file, 'utf8').trim().split('\n').map(line => {
  const i = line.indexOf('='); return [line.slice(0,i),line.slice(i+1)];
}));
for (const [key, value] of Object.entries({AvdId:name, 'avd.name':name,
  'avd.ini.displayname':'Stream Local TV', 'hw.initialOrientation':'landscape',
  'hw.ramSize':'4096', 'hw.keyboard':'yes', 'hw.camera.back':'none', 'hw.audioInput':'no',
  'hw.audioOutput':'yes', 'hw.gpu.enabled':'yes', 'hw.gpu.mode':'auto',
  'showDeviceFrame':'no', 'fastboot.forceColdBoot':'yes', 'fastboot.forceFastBoot':'no'})) settings.set(key,value);
fs.writeFileSync(file,[...settings].map(([k,v])=>`${k}=${v}`).join('\n')+'\n');
JS
fi
# TV hardware profiles default to no-touch, which turns emulator clicks into
# remote navigation. A touchscreen-capable virtual display exercises real clicks.
avd_dir=${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}
node - "$avd_dir/$avd.avd/config.ini" <<'JS'
const fs = require('node:fs');
const file=process.argv[2];
const text=fs.readFileSync(file,'utf8');
const next=/^hw\.screen=.*$/m.test(text)?text.replace(/^hw\.screen=.*$/m,'hw.screen=touch'):text+'\nhw.screen=touch\n';
if(next!==text)fs.writeFileSync(file,next);
JS
printf '%s is installed and ready.\n' "$avd"

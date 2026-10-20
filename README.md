# Fire TV cleanup — 20 October 2026

Applied to Fire TV Stick HD, model `AFTSS`, codename `sheldon`, Fire OS
`7.7.1.7 / PS7717.5741N`, at `192.0.2.10`.

**Current owner preference:** ADB Debugging was re-enabled after cleanup and
should stay enabled. Do not disable it unless the owner explicitly requests it.

**Later customization:** At the owner's request, window animations, transition
animations and animator duration were set to **0.5×** and read back successfully.
ADB remained enabled. Their original values (`1.0`, `1.0`, and the unset default)
are recorded in `backups/animation-scales.json`.

**Direct video casting:** A custom receiver, `tv.daniil.cast` (Daniil Cast), was
built and sideloaded at the owner's request. The matching Chrome extension and
local native-messaging helper are in [casting/README.md](casting/README.md).
The helper is registered and the owner loaded the extension in Chrome. Direct
MP4 and embedded HLS playback, pause/resume/seek, automatic browser pause and
continued TV playback after closing the source tab were verified with public
samples. No AirScreen package was installed. ADB remains enabled.

## Changes applied

- Disabled the 12 reporting, content-recognition and advertising packages in
  `profiles/firetv-preserve-features.txt` for user 0.
- Turned **Device Usage Data**, **Collect App Usage Data**, and **Interest-based
  Ads** off through the Fire TV's Privacy Settings screen. All three were on
  before cleanup.
- Preserved installed apps, Alexa, Live TV, casting, software updates, the
  Amazon launcher, and the metrics API library used by streaming apps and OTA.
- Preserved the previously disabled NFC package's state.
- Turned ADB Debugging off when finished; TCP port 5555 was confirmed closed.

No packages were uninstalled or app data cleared. The installed-package inventory
still contains the same 247 packages. Amazon's sponsored Home carousel can still
appear; this cleanup reduces selected background services and optional tracking.

## Checks completed

After a restart, all 12 package changes persisted, the original Home remained
available, and Wi-Fi reported validated internet connectivity. Netflix, Prime
Video, YouTube, Apple TV and Disney+ launched successfully. The post-restart
crash log contained no fatal exceptions.

These checks cover app startup and package states. Video playback, the physical
microphone and casting from a second device were not exercised. No performance
benchmark was taken.

## Undo

1. On the stick, enable **Settings → My Fire TV → Developer Options → ADB
   Debugging** and keep the Mac on the same network.
2. Run on the Mac:

   ```sh
   zsh $HOME/Developer/Hobby/daniiltv/restore-firetv.sh
   ```

   Accept the debugging prompt on the TV if requested. The script uses the
   recorded original package states, Home component and animation scales.
   If the stick's IP changes, update `TV_HOST` in the restore script first.
3. To undo the privacy choices as well, open **Settings → Preferences → Privacy
   Settings** and turn the three switches listed above back on.
4. Leave ADB Debugging enabled, as requested by the owner.

## Saved files

- `profiles/firetv-preserve-features.txt`: exact package selection.
- `backups/2026-10-20-before/`: original settings, inventory, package metadata,
  screenshot and a copy of the recovery record.
- `backups/2026-10-20-after/cleanup-results.json`: individual disable results.
- `backups/2026-10-20-after/verification.json`: restart, network and app checks.
- `backups/2026-10-20-after/privacy-off.png`: three privacy switches shown off.
- `backups/2026-10-20-after/home-final.png`: Home after cleanup.

CleanYourTV was checked out at commit
`022c57da7c4f07466141229cb5625f6ae4fd7ba0`. Its original example was reduced to
match the requested feature-preservation choices. Its recovery record is also
saved at `$HOME/.cleanyourtv/recovery-192.0.2.10-6a187bbf.json`.

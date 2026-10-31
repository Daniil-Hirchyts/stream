# Building and running

## What you need

- macOS or Linux with Java 21 (or 17), the Android SDK, Node.js and FFmpeg
- Rust through rustup: the pinned toolchain (`native/core/rust-toolchain.toml`)
  and its Android targets install themselves on first use
- The Android NDK and CMake versions pinned in `native/deps.lock`
  (`sdkmanager --install "ndk;29.0.14206865"`)
- For the bundled software decoders: Git, curl, tar, a C/C++ host toolchain,
  Python, Meson, Ninja, NASM and pkg-config
  (`brew install meson ninja nasm pkgconf` on macOS)

The first build downloads and verifies the pinned decoder sources (FFmpeg,
dav1d, mbedTLS, NextLib) and takes a while; later builds are incremental.

## Try it on an emulator

`simulator.sh` creates and drives one local Android TV emulator,
`Stream_Local_TV` on `emulator-5558` (Android TV 16, API 36). Every device
command checks that serial, that it is an emulator and its AVD name, and never
falls back to another device.

```sh
./simulator.sh up       # start the emulator, build, install and open the app
./simulator.sh site     # a browser page with the emulator's screen and a remote
./simulator.sh smoke    # generated catalogue → BitTorrent → visible playback
```

`./simulator.sh site` serves a live view with pointer and remote controls at
`http://127.0.0.1:58133/`. The fixture service and a real loopback BitTorrent
seeder (port 58134) supply generated films, so nothing leaves the computer.
A different emulator needs both `STREAM_AVD` and `STREAM_PORT`; if the emulator
can't resolve names after the host's network changed, start it with
`STREAM_DNS_SERVERS=1.1.1.1,8.8.8.8 ./simulator.sh start`.

## Build an APK for a TV

```sh
cd android
./gradlew -PabiSplits=true :app:assembleRelease   # one APK per ABI, plus a universal one
./gradlew :app:bundleRelease                      # an App Bundle
cd .. && simulator/check-distribution.sh          # check libraries, ABIs and 16 KiB alignment
```

Release outputs are unsigned: sign them with your own key (`apksigner`) before
installing. Most Android TV devices are `arm64-v8a`; older and cheaper sticks
are often `armeabi-v7a`. To install on your own TV, enable developer options
and network debugging on it, then `adb connect <tv-address>` and
`adb install app-arm64-v8a-release-signed.apk`. Signed builds are published on
[GitHub Releases](https://github.com/Daniil-Hirchyts/stream/releases) by the
release workflow; see [Releasing](releasing.md).

## Engine on the host

```sh
./native/build-host.sh dev        # debug build, then all engine tests
./native/build-host.sh release    # optimised, as the app ships
./native/build-host.sh dev v2_    # only tests whose names contain "v2_"
```

`native/eval/interop.sh` checks the engine against independent clients
(librqbit, libtorrent, aria2, Transmission, WebTorrent) on loopback; see
[the streaming engine](streaming-engine.md#compatibility-and-limitations).

## Generated assets

| Asset | Regenerate with |
| --- | --- |
| Interface sounds | `sounds/sounds.py`, checked by `sounds/verify.py` ([sounds](sounds.md)) |
| Geist font and icons | `android/branding/make_geist.py` ([fonts and icons](fonts-and-icons.md)) |
| Subtitle fonts | `android/branding/make_subtitle_fonts.py` |
| Launcher icon and banner | `android/branding/make_brand.py` |
| Engine open source notice | `python3 native/core/notices.py` after `Cargo.lock` changes |
| Baseline and Startup Profiles | `./simulator.sh baseline-profile` ([testing](testing.md#baseline-and-startup-profiles)) |
| README screenshots and GIFs | `./simulator.sh readme-media` (internet; real catalogue, plays only Tears of Steel, CC BY 3.0) |

## Tests

See [testing](testing.md): which suite proves what, focused commands, device
rules and measurements.

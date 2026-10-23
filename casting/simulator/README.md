# Local casting environment

From the repository root:

```sh
./casting/simulator.sh
```

Or double-click `casting/Simulator.command` in Finder. It starts everything and
leaves the command menu open.

The launcher starts **DaniilCast_Local_TV**, a visible Android TV 16 emulator
with sound on port 5556, builds and installs the current receiver, builds the
extension side panel, rebuilds/installs the Rust helper, connects the emulator API
to the Mac, selects its pairing, opens the real local test room in Chrome, and
casts a generated sample. Startup succeeds only after rendered playback advances:

**http://127.0.0.1:58132/**

The emulator and test server run independently of the launcher, so closing its
terminal or quitting the menu leaves them running. The test server watches its
source and restarts after edits; refresh the Chrome page to see HTML changes. Use `stop` to stop the TV.

Open Daniil Cast in Chrome's toolbar. The selected device is **Daniil Cast Local
TV**. Press Cast, then use Remote. The test room shows the actual emulator
screen as it changes. Click or tap this live image, or the emulator window, to
show controls. Buttons and menu rows work by mouse/touch, and the timeline can
be dragged. Remote keys (arrows, Enter, Back/Menu) remain optional shortcuts. Any supported video website can be used
instead of the test room.

The test room uses generated media, the real installed extension, the real Rust
helper and the real receiver. MP4 has two audio tracks; HLS tests playlist
playback; DASH has two qualities and two audio tracks; AV1 tests another codec.
The subtitles include VTT, SRT and ASS. Seek to 1:25 and enable a subtitle: its
long cue lasts until 2:10. The MP4 configuration also supplies three episodes.
An emulator with native AV1 support plays it directly; the smoke test explicitly
requests conversion to exercise the desktop converter.

The emulator is already paired on this Mac. On a fresh AVD, the launcher prompts in Terminal for the code shown in the
emulator. You can also pair through the extension at Developer → Connect by
address → `127.0.0.1`. Later launches select the saved emulator automatically.
After editing extension JavaScript, use Developer → Reload extension once.
Use `127.0.0.1` for pairing from the Mac, not the emulator's internal
`10.0.2.15` address. Start, Open and Install also restore the local bridges;
`./casting/simulator.sh open` repairs a missing bridge without rebuilding.

## Commands

| Command after `./casting/simulator.sh` | Action |
| --- | --- |
| no argument / `up` | Build/start everything, connect and verify a playing sample |
| `menu` | Open the interactive command menu |
| `install` | Rebuild, install and open the receiver |
| `open` | Open the installed receiver |
| `test` | Run eight Android subtitle/error/mouse/touch tests and reopen the app |
| `smoke` | Test playback, controls, episodes and conversion end to end |
| `site` | Open the local test room |
| `status` | Show the running AVD, Android version and receiver version |
| `logs` | Show recent emulator logs |
| `stop` | Stop the emulator |

The Android SDK is found automatically at `~/Library/Android/sdk`; a missing
`ANDROID_HOME` variable is fine. Java 21 (or 17), Node/npm, Rust/C linker and Android command-line tools
are needed. The helper is built and installed by full startup. FFmpeg from the installed helper
generates the fixtures. `setup` installs the complete Android TV image if needed
and creates the AVD without replacing userdata. It enables `hw.screen=touch` so
clicks reach the Android UI; cold restart is needed after a hardware change. SDK licenses must
already be accepted, as on this Mac.

Local startup uses the host GPU for graphics, with Vulkan disabled for GLES
compatibility on macOS. The default AVD has 4 GB RAM; software rendering can
cause sluggish navigation even when Android has plenty of free memory. If host
graphics cause rendering problems, start with `DANIIL_CAST_GPU=swiftshader`.
Changing the renderer requires stopping and starting the emulator. Headless CI
continues to use software rendering.

Every ADB device operation checks the emulator identity. Ports 8765 (Mac to
receiver) and 58132 (receiver to local test media) are bridged automatically.
The debug receiver's “display over other apps” permission is granted **inside
the emulator** so paired casts can open the player from Android Home. No TLS
validation or system app verification is disabled.

The Android TV 16 image replaces the old Android 9 image as the default because
the latter's 2019 CA store cannot verify the current video CDN certificate.
The receiver now targets Android 16 and declares its connected-device service
type; its minimum Android version remains 8. Physical Fire TV hardware and
saved pairings are not updated by these commands. Emulator codec performance
and DRM support do not establish physical-TV compatibility.

To deliberately use an existing different AVD, set both `DANIIL_CAST_AVD` and
`DANIIL_CAST_PORT`. A port already occupied by another AVD is rejected. The
sample-data UI preview on port 58131 is a separate menu item; it is not used by
the working local test room on port 58132.

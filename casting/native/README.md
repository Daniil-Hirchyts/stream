# Daniil Cast desktop helper

The desktop component is one Rust executable. It speaks the existing Chrome
native-messaging protocol, talks to the TV’s HTTP API, and can run as a detached
conversion server. End users do not need Rust, Python, Node.js, Homebrew, or this
repository. Conversion tools must be included in their package.

## Build and install on this Mac

Developers need Rust 1.85 or newer and the platform’s C linker. From this folder:

```sh
cargo test --locked
./build.sh --install
```

The script builds a release executable, packages `build/Daniil Cast.app` with
the available FFmpeg 7 tools, and installs a copy in `~/Applications/`. Set
`DANIILCAST_TOOLS_DIR` to supply another FFmpeg directory. Without tools, the
package supports direct playback and controls; conversion needs FFmpeg.

Chrome’s native-host manifest points into the installed app, so moving this
repository does not break it. Existing `devices.json` pairings and Activity logs
are preserved. Reopen the side panel after installing. The Rust app retains
bundle identity `tv.daniil.cast.helper`; macOS may ask for Local Network access
again because its executable changed. Allow it in System Settings if prompted.

## Distribute a compiled helper

```sh
cargo build --release --locked
./target/release/daniil-cast package --output build --tools /path/to/ffmpeg/bin
```

On macOS this creates an app with its executable, FFmpeg/ffprobe, and relocated
non-system libraries. Double-clicking that app installs it and registers it with
Chrome. Re-running installation with the same helper leaves its binary alone.
The packager derives the app’s minimum macOS version from the bundled tools.
This Mac’s Homebrew FFmpeg was built for **macOS 27**, so a package using those
tools requires macOS 27. Supply tools built for an older deployment target when
preparing a broader release. Local packages are ad-hoc signed; public releases
need Developer ID signing and notarization.

On Windows/Linux the package is a directory containing `daniil-cast[.exe]` and
the supplied tools. Run `daniil-cast install` to register it with Chrome. Windows
registration is per-user, with no administrator requirement. Linux uses the
user’s Chrome configuration directory. The installer accepts `--extension-id`
for a different Chrome Web Store extension identity. Build separately for each
OS and CPU architecture. Use self-contained FFmpeg builds on Linux; Windows DLLs
in the supplied tools directory are copied. Include the source/license materials
required by the FFmpeg build being distributed.

The CI workflow builds/tests Windows, Linux and macOS; those remote jobs must
pass before calling a platform release verified. Local verification covers
Apple Silicon macOS.

## Commands

The same binary supplies the old `castctl.py` functions:

```sh
daniil-cast devices
daniil-cast discover
daniil-cast pair 192.168.1.11
daniil-cast status --watch
daniil-cast load https://example.org/video.mp4 --title Test
daniil-cast seek 120
daniil-cast send '{"action":"subtitles","track":"off"}'
daniil-cast log -n 30
daniil-cast stop
```

The executable is inside `~/Applications/Daniil Cast.app/Contents/MacOS/` on a
Mac. You can also use the release binary in `target/release/` during development.
`native` explicitly selects Chrome’s binary-framed stdin/stdout protocol;
Chrome supplies its extension origin when it launches the installed app.

## Runtime design

- `native.rs`: bounded message queues, 16 concurrent requests, request correlation,
  a dedicated stdin reader and stdout writer. Input frames are capped at 4 MiB;
  output respects Chrome’s 1 MiB limit. Stdout carries protocol messages only.
- `bridge.rs` / `validate.rs`: validated commands and compatibility with the
  existing extension/TV messages. Status reads remain responsive during casting.
- `tv.rs`: pooled HTTP connections, native network-interface enumeration, UDP
  discovery concurrent with emulator probing, and a bounded fallback scan.
- `devices.rs`: cross-process locking, atomic saves and the existing pairing
  format. Corrupted stores are reported and preserved for recovery.
- `journal.rs`: bounded, rotating activity logs with URL/token redaction.
- `relay.rs`: on-demand FFmpeg conversion, authenticated loopback control,
  TV-address/session-token checks, two active encoding windows, at most six
  retained windows, and cleanup on stop or inactivity. macOS uses VideoToolbox;
  the emulator and other OSes use x264. Encoding remains FFmpeg’s responsibility.
  Output is capped at 1280×720 and preserves smaller source dimensions instead
  of enlarging them. The real conversion test checks this with ffprobe.
- `install.rs`: installed binaries/app bundles and Chrome registration.

Conversion is a separate process of the same executable, so closing the panel
does not stop the TV’s converted stream. Control uses a random port and secret
in a private `control.json`; it is not exposed on the LAN. Video is served on the
interface toward the selected TV. The conversion cache is under
`DaniilCast/native-v2`, separate from an older Python converter still running
during an upgrade. It expires after 30 minutes without media requests; the idle
daemon exits after a further minute. macOS prevents idle sleep while converting;
Linux uses `systemd-inhibit` when available.

Test overrides: `DANIILCAST_HOME`, `DANIILCAST_LOG`, `DANIILCAST_CACHE`,
`DANIILCAST_FFMPEG`, `DANIILCAST_FFPROBE`. These allow testing without touching
the real TV, pairings or logs.

## Verify and measure

```sh
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test --release --locked
# Real AV1 → H.264/AAC, seeking, authentication and cache cleanup:
cargo test --release --test conversion -- --ignored
# Helper overhead over Chrome’s actual framed protocol; no TV traffic:
cargo run --release --example benchmark -- ./target/release/daniil-cast native
```

The conversion test requires FFmpeg with `libaom-av1`, `libopus` and `libx264`.
Protocol tests launch a real helper process and a local mock TV, exercise pairing
and controls, and verify that a slow cast does not delay another request. Storage
tests cover concurrent writers, secret redaction and corrupted-store recovery.
Benchmarks measure startup and request overhead, not video encoding speed.

On this Apple Silicon Mac (21 October 2026), the installed Rust app answered its
first native message in a median **3.04 ms**, versus **77.51 ms** for the Python
helper: about **25.5× faster process startup** over 30 samples. Warm sequential
requests averaged **0.071 ms** versus **0.075 ms**, essentially comparable at
this scale. These are helper-overhead measurements with warm filesystem caches;
they do not measure network latency, time to a video frame, or FFmpeg encoding.
Raw results: [benchmarks/2026-10-21-macos-arm64.json](benchmarks/2026-10-21-macos-arm64.json).

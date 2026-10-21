# Desktop release validation

The local Apple Silicon macOS workflow is verified on 21 October 2026:

- Plain `./casting/simulator.sh`, starting from a stopped emulator, builds and
  installs current components, selects the local receiver and confirms rendered,
  advancing video.
- Eight Android instrumentation tests pass, including actual mouse/touch input,
  remote navigation after touch, long subtitles after seeking/late selection,
  caption clearance above the draggable timeline, retry and up-next controls.
- Real Chrome extension detection/casting, pause, and clicking Play inside the
  browser's live emulator screen were exercised.
- Native helper smoke tests pass for MP4/HLS/DASH, audio, four subtitle tracks,
  episodes, speed, quality, AV1 conversion/seeking and casting from Android Home.
- Rust formatting, Clippy, unit/protocol/installation tests and the real AV1
  conversion test pass locally with the installed FFmpeg 7 tools.
- GitHub's receiver APK/test-APK builds and side-panel checks/tests/bundle check
  pass: [UI workflow](https://github.com/Daniil-Hirchyts/daniiltv/actions/runs/39633041522).

No physical TV was controlled or updated during this work. Screenshots and
runtime logs remain in the ignored `casting/simulator/.cache/` directory.

## Open platform issue

Linux conversion is **not release-verified**. On GitHub's Ubuntu runner with
FFmpeg 6.1.1, `real_av1_conversion_seeking_authentication_and_cleanup` fails:
ffprobe identifies H.264/AAC in the first returned TS segment but reports video
width/height as zero. The FFmpeg 7 macOS run of this same test passes. The test
now includes probe diagnostics and checks ffprobe's process exit status; it has
not been weakened or disabled. The root cause still needs investigation.

See [native workflow](https://github.com/Daniil-Hirchyts/daniiltv/actions/runs/39633498528).
Linux Rust compilation/unit/protocol checks pass. Windows compilation/unit tests
also ran in CI; macOS hosted-runner jobs were still queued at this handoff.
These results do not establish public desktop installer compatibility. Signing,
notarization, portable conversion-tool packaging and OS/CPU release coverage
remain separate from the working local simulator.

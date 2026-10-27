# Stream

Stream is an Android TV app for catalogues, search, a private local library,
add-ons and direct or BitTorrent playback. This repository owns its Android UI,
playback library, native engine, codec integration, sounds and local simulator.
No other application checkout or installed desktop helper is required.

```sh
./simulator.sh up           # Start the isolated local emulator, build, install and open
./simulator.sh site         # Live emulator screen with pointer and remote controls
./simulator.sh smoke        # Generated catalogue → BitTorrent → visible playback
```

The default test target is `Stream_Local_TV` on `emulator-5558` (Android TV 16,
API 36). Custom targets require both `STREAM_AVD` and `STREAM_PORT`; every device
operation checks the serial, emulator property and AVD name. Physical TVs are
never selected automatically. FFmpeg, Node.js, an Android SDK and Java 21/17 are
local simulator prerequisites. The app needs none of these tools at runtime.

See the [Android guide](android/README.md), [focused verification commands](android/TESTING.md),
[native engine](native/README.md), [playback implementation](PLAYBACK_IMPLEMENTATION.md)
and [agent guide](AGENTS.md). Private fixture captures and build output stay ignored.

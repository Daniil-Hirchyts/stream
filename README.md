# Daniil TV

Daniil Cast sends video from a Chrome tab to an Android/Fire TV receiver. The
project contains the Chrome extension, its Rust desktop helper, the Android app,
and a complete local Android TV test environment.

From the repository root on this Mac:

```sh
./casting/simulator.sh
```

This starts the visible emulator with sound, builds and installs the current
receiver and helper, builds the extension, restores local network bridges,
selects the emulator, opens the test room in Chrome, and plays a local sample.
It reports success only after the receiver renders video and playback advances.
On first use, enter the pairing code displayed in the emulator when prompted.

Click/tap the emulator screen to reveal playback controls. Play/pause, seeking,
options and every menu work with mouse/touch. Remote keys are optional. The
[local test room](http://127.0.0.1:58132/) also provides an interactive live screen.

```sh
./casting/simulator.sh menu    # Interactive commands
./casting/simulator.sh test    # Android subtitle, error and pointer regressions
./casting/simulator.sh smoke   # Actual helper → receiver playback/conversion tests
./casting/simulator.sh stop    # Stop the emulator
```

Double-click `casting/Simulator.command` for startup followed by the command menu.
The simulator never installs to or controls a physical TV.

See [simulator setup and commands](casting/simulator/README.md),
[casting installation and architecture](casting/README.md),
[desktop helper](casting/native/README.md), [agent instructions](AGENTS.md),
and [validation results and release limitations](docs/desktop-validation.md).
A separate standalone streaming app, Daniil TV, is
[being implemented](docs/STREAMING_APP_PLAN.md). Its native engine and Android
data layers are described in the [validation record](docs/streaming-validation.md);
the standalone navigation/player screens are still unfinished.

The older physical Fire TV cleanup tools remain separate from casting. Their
[recovery notes](docs/firetv-cleanup.md), package profile and restore script are
preserved. CleanYourTV is pinned as a Git submodule; initialize it only if using
those maintenance tools:

```sh
git submodule update --init cleanyourtv
```

Private `backups/` records stay on the owner's Mac and are excluded from new
commits. Existing historical snapshots remain in Git history.

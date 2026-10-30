# Interface sounds

The app uses **Soft**, chosen over the earlier Glass set and the Felt alternative in listening tests. Its 28 cues
include navigation, typing, choices, dialogs, player controls and startup.
`sounds.py` is the production entry point; `palettes.py` defines the Soft and
Felt voices, scores and mastering. Android resources are generated, not edited.

## Regenerate and verify

From the repository root:

```sh
python3 -m venv sounds/build/venv
sounds/build/venv/bin/python -m pip install -r sounds/requirements.txt
sounds/build/venv/bin/python sounds/sounds.py
sounds/build/venv/bin/python sounds/verify.py
```

Regeneration writes `android/app/src/main/res/raw/sfx_*.wav`.
`soft-approval.json` records the mastering calibration and SHA-256 fingerprints
of the selected preview. Every generated app file must match that audition
byte for byte. Generation does not depend on installed WAVs, ignored listening
files, a running server, or a previously built app. Android playback windows in
`ui.Sounds.Sound` match the new cue durations rather than the previous Glass
set's longer sounds. One-voice priorities, throttling and the 0.7 playback gain
are preserved.

## Listening

`sounds.py audition` also renders all six historical/current instruments to
`build/index.html`. Its chosen markers show Soft.

## Sound design

**Soft** uses a rounded pluck: a brief harmonic FM onset settles into a smooth
tone without the previous Glass instrument's bell partial or felt impact. New
gestures distinguish returning, choosing, pausing and resuming. Positive actions
use a short two-part resolution; frequent movement and typing stay single
impulses. Touch cues are 45–58 ms. Startup lasts 2.05 seconds and follows the
existing logo-fill/name-reveal beats.

**Felt** uses short wood/membrane modes and filtered-noise excitation. It is
drier and more tactile, with less sustained musical pitch, its own event scores
and a separate startup arrangement. Touch cues are 36–44 ms. The first study's
slight Glass refinement was superseded after feedback.

## Focused validation

`verify.py` checks all app cues against the approved fingerprints, independently
regenerates them, and checks mono/44.1 kHz/16-bit PCM, level matching, headroom,
DC offset, smooth endpoints and Android resource/duration coverage. The
calibration preserves the previous set's relative levels between quiet touches
and larger confirmations, including the peak-limited Warning. Mastering rejects
excessive peaks instead of clipping. Finite envelopes retain short transients;
reusing the old mastering fade would erase them.

The focused emulator `tv.stream.app.ui.SoundAssetsTest` decodes every
packaged resource through Android SoundPool and verifies each can start, at
zero volume. It opens no activities, store, provider or owner preferences.
See [testing](testing.md) for device identity requirements.

Generated previews, validation data, virtual environment and local server
files live under ignored `build/`. The app WAVs and their generation/calibration
sources belong in Git. No full app test suite is needed for sound-only work.

# Interface sounds

The app uses **Soft**, selected as B in the second listening study. Its 28 cues
include navigation, typing, choices, dialogs, player controls and startup.
`sounds.py` is the production entry point; `palettes.py` defines the Soft and
Felt voices, scores and mastering. Android resources are generated, not edited.

## Regenerate and verify

From the repository root:

```sh
python3 -m venv streaming/sounds/build/refinement-venv
streaming/sounds/build/refinement-venv/bin/python -m pip install -r streaming/sounds/requirements.txt
streaming/sounds/build/refinement-venv/bin/python streaming/sounds/sounds.py
streaming/sounds/build/refinement-venv/bin/python streaming/sounds/verify.py
```

Regeneration writes `streaming/android/app/src/main/res/raw/sfx_*.wav`.
`soft-approval.json` records the mastering calibration and SHA-256 fingerprints
of the selected preview. Every generated app file must match that audition
byte for byte. Generation does not depend on installed WAVs, ignored listening
files, a running server, or a previously built app. Android playback windows in
`ui.Sounds.Sound` match the new cue durations rather than the previous Glass
set's longer sounds. One-voice priorities, throttling and the 0.7 playback gain
are preserved.

## Listening comparison

```sh
streaming/sounds/build/refinement-venv/bin/python streaming/sounds/refinement.py
python3 -m http.server 58136 --bind 127.0.0.1 --directory streaming/sounds/build/refinement
```

Open `http://127.0.0.1:58136/`. A keeps the **previous Glass** set, B is the
selected **Soft** set and C is the **Felt** alternative. The original Glass
renderer reproduced all former app WAVs byte for byte, so it remains the
baseline after adopting Soft. The comparison never overwrites app resources.

Each has all 28 events and six sequences: Navigation, Quick browsing, Typing &
results, Player, Confirmations and Startup. Repeated touches replace the
previous touch in the rapid sequences; startup exit layers over the opening
when it is still sounding, following the app's playback policy. Choose a
comparison direction to hear Previous then Soft/Felt. Another control replaces
playback; Stop or Escape cancels it. Default listening gain is 0.7, like the app.
Computer and TV speakers will sound different.

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

The focused emulator `tv.daniil.stream.ui.SoundAssetsTest` decodes every
packaged resource through Android SoundPool and verifies each can start, at
zero volume. It opens no activities, store, provider or owner preferences.
See `streaming/android/TESTING.md` for device identity requirements.

The comparison renderer separately checks deterministic synthesis and sequence
peaks; its measurements are in `build/refinement/validation.json`. Browser
checks cover file decoding, A/B/C playback, comparison, replacement, Stop/Escape
and responsive layout. Technical checks do not replace listening.

Generated previews, validation data, virtual environment and local server
files live under ignored `build/`. The app WAVs and their generation/calibration
sources belong in Git. No full app test suite is needed for sound-only work.

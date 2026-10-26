#!/usr/bin/env python3
"""
Daniil TV's interface sounds.

The method is gym-lib's (scripts/sounds/packs.py in Rauta): sounds are made, not
sampled. Each moment is a short score of notes, rendered by a small synth,
mastered to one loudness scale weighted as the ear hears it, and chosen by ear
from the same moments played on different instruments. Rauta's listening test
kept two: Pure (soft sines with a trace of harmonics) and Marimba. For the TV
the owner selected Soft from the second audition: rounded electronic plucks,
brief confirmations and a new startup arrangement (palettes.py). Glass,
Felt, Pure and Marimba remain available on the listening page.

    python3 streaming/sounds/sounds.py            the app's sounds
    python3 streaming/sounds/sounds.py audition   every moment on every instrument

The first writes streaming/android/app/src/main/res/raw/sfx_<name>.wav. The
second writes streaming/sounds/build/ (ignored): one .wav per instrument and
moment, and index.html, the listening page. Deterministic: running either again
changes nothing. Needs numpy and scipy.

Navigation has quiet ticks (focus, a row's end, tabs, pages, typing); OK, Back,
choices, failures and the player have fuller ones. `ui.Sounds` plays them one at
a time and leans the small ones up to a semitone with their place across the
screen, so a tap is never heard quite the same twice.
"""

import json
import os
import sys
import wave
import zlib

import numpy as np
from scipy.signal import bilinear, butter, lfilter, sosfilt

SR = 44_100
TAU = 2 * np.pi
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RAW = os.path.join(ROOT, "streaming", "android", "app", "src", "main", "res", "raw")
BUILD = os.path.join(HERE, "build")

# id, name, when it is heard, group, level against the loudest moments (dB)
EVENTS = [
    ("intro", "Start", "The start screen: mark traces, glass fills, the name rises", "Start", -7),
    ("intro-exit", "Start leaves", "The start screen zooms away into Home", "Start", -15),
    ("focus", "Focus", "Focus moves to another control", "Navigation", -22),
    ("edge", "Edge", "Focus can't go further (a row's end)", "Navigation", -18),
    ("select", "Select", "OK on a button, card or row", "Navigation", -12),
    ("back", "Back", "Back closes a page or a menu", "Navigation", -12),
    ("tab", "Tab", "A tab of the top bar is chosen", "Navigation", -14),
    ("open", "Open", "A page opens: details, sources, a catalogue", "Navigation", -12),
    ("key", "Key", "A letter typed on the search keyboard", "Search", -19),
    ("delete", "Delete", "Delete or Clear on the keyboard", "Search", -18),
    ("results", "Results", "Search results arrive", "Search", -17),
    ("chip", "Chip", "A sort order or filter is chosen", "Choices", -14),
    ("favourite-on", "Favourite", "A title is added to favourites", "Choices", -8),
    ("favourite-off", "Unfavourite", "A title is removed from favourites", "Choices", -10),
    ("watched-on", "Watched", "A title or episode is marked watched", "Choices", -7),
    ("watched-off", "Unwatched", "Marked unwatched again", "Choices", -10),
    ("alert", "Alert", "A dialog opens", "Dialogs", -13),
    ("cancel", "Cancel", "A dialog is cancelled or closed", "Dialogs", -12),
    ("installed", "Installed", "An add-on is installed", "Dialogs", -5),
    ("warning", "Warning", "Something failed or was refused", "Dialogs", -7),
    ("pause", "Pause", "Playback pauses", "Player", -11),
    ("resume", "Resume", "Playback resumes", "Player", -11),
    ("seek-forward", "Skip forward", "The remote skips forward", "Player", -16),
    ("seek-back", "Skip back", "The remote skips back", "Player", -16),
    ("menu-open", "Menu", "The player's options open", "Player", -13),
    ("menu-close", "Menu closes", "The player's options close", "Player", -14),
    ("up-next", "Up next", "The next episode is offered", "Player", -6),
    ("finished", "Finished", "A title played to its end", "Player", -3),
]

# Each moment as notes: (start s, note, gain, decay s, bend). Decay is the time
# to fall to about a third; bend is where the pitch starts as a ratio of the note
# it slides into (1 does not slide). A chord is a list of notes.
SCORES = {
    "focus": [(0, "E6", 1, 0.022)],
    "edge": [(0, "E4", 1, 0.035, 1.35)],
    "tab": [(0, "B5", 0.7, 0.04), (0.05, "E6", 1, 0.07)],
    "open": [(0, "E5", 0.55, 0.05), (0.06, "B5", 1, 0.13)],
    "key": [(0, "A5", 1, 0.02)],
    "delete": [(0, "E5", 1, 0.03, 1.4)],
    "results": [(0, "E6", 0.5, 0.04), (0.045, "G#6", 0.6, 0.04), (0.09, "B6", 0.75, 0.09)],
    "chip": [(0, "C#6", 1, 0.045)],
    "alert": [(0, ["A5", "E6"], 0.55, 0.2)],
    "seek-forward": [(0, "A5", 0.6, 0.025), (0.04, "E6", 0.8, 0.035)],
    "seek-back": [(0, "E6", 0.6, 0.025), (0.04, "A5", 0.8, 0.035)],
    "menu-open": [(0, "A5", 1, 0.035, 0.66)],
    "menu-close": [(0, "A5", 1, 0.035, 1.5)],
    "select": [(0, "E6", 1, 0.05, 0.62)],
    "back": [(0, "B5", 1, 0.05, 1.45)],
    "favourite-on": [(0, "E6", 0.8, 0.04), (0.07, "A6", 1, 0.1)],
    "favourite-off": [(0, "D5", 1, 0.05, 1.55)],
    "watched-on": [(0, "D5", 0.85, 0.1), (0.075, "G5", 1, 0.16)],
    "watched-off": [(0, "G5", 0.9, 0.06), (0.07, "D5", 0.8, 0.08)],
    "cancel": [(0, "G5", 1, 0.045, 1.5)],
    "installed": [(0, "G5", 0.9, 0.1), (0.07, "B5", 0.9, 0.1), (0.14, "D6", 1, 0.25)],
    "warning": [(0, "Bb4", 1, 0.06, 1.06), (0.14, "Bb4", 1, 0.08, 1.12)],
    "pause": [(0, "B5", 1, 0.035, 1.25)],
    "resume": [(0, "B5", 1, 0.035, 0.8)],
    "up-next": [(0, "E5", 0.8, 0.1), (0.12, "B5", 1, 0.25)],
    "finished": [(0, "G5", 0.8, 0.1), (0.075, "A5", 0.85, 0.1), (0.15, "B5", 0.9, 0.1), (0.225, "D6", 1, 0.3)],
}

# The previous app set: Glass, made darker and plainer, like a TV's own interface. No melodies: each
# moment is one low, damped tone with a felt tap, or the same tone tapped twice; a small slide up or down says
# which way something went. The scores above stay for the other instruments on the listening page.
TV_SCORES = {
    "focus": [(0, "D5", 1, 0.016)],
    "edge": [(0, "G3", 1, 0.03, 1.25)],
    "tab": [(0, "C5", 1, 0.03)],
    "open": [(0, "E4", 1, 0.06, 0.82)],
    "key": [(0, "F5", 1, 0.014)],
    "delete": [(0, "F4", 1, 0.025, 1.2)],
    "results": [(0, "A4", 1, 0.05)],
    "chip": [(0, "D5", 1, 0.025)],
    "alert": [(0, "D4", 1, 0.1, 0.94)],
    "seek-forward": [(0, "C5", 1, 0.02, 0.92)],
    "seek-back": [(0, "C5", 1, 0.02, 1.08)],
    "menu-open": [(0, "E4", 1, 0.04, 0.86)],
    "menu-close": [(0, "E4", 1, 0.04, 1.15)],
    "select": [(0, "A4", 1, 0.035, 0.86)],
    "back": [(0, "A4", 1, 0.035, 1.16)],
    "favourite-on": [(0, "E5", 1, 0.06, 0.88)],
    "favourite-off": [(0, "E4", 1, 0.05, 1.12)],
    "watched-on": [(0, "A4", 0.75, 0.04), (0.065, "A4", 1, 0.08)],
    "watched-off": [(0, "A3", 1, 0.05, 1.1)],
    "cancel": [(0, "D4", 1, 0.035, 1.15)],
    "installed": [(0, "A4", 0.75, 0.05), (0.07, "A4", 1, 0.12)],
    "warning": [(0, "F3", 1, 0.05), (0.12, "F3", 1, 0.06)],
    "pause": [(0, "G4", 1, 0.03, 1.12)],
    "resume": [(0, "G4", 1, 0.03, 0.9)],
    "up-next": [(0, "D4", 1, 0.14)],
    "finished": [(0, "A3", 1, 0.24)],
}


# ------------------------------------------------------------------ basics

def hz(note):
    """A frequency from a note name ("A4", "C#5", "Bb3")."""
    steps = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
    pitch, i = steps[note[0]], 1
    if note[i] == "#":
        pitch, i = pitch + 1, i + 1
    elif note[i] == "b":
        pitch, i = pitch - 1, i + 1
    return 440.0 * 2 ** ((12 * (int(note[i:]) + 1) + pitch - 69) / 12)


def env(t, attack, decay):
    """A raised-cosine attack into an exponential decay."""
    rise = 0.5 * (1 - np.cos(np.pi * np.clip(t / attack, 0, 1)))
    return rise * np.exp(-t / decay)


def phase(f, bend, n, time=0.018):
    """The running phase of a pitch that starts at f * bend and settles on f."""
    t = np.arange(n) / SR
    return TAU * np.cumsum(f + f * (bend - 1) * np.exp(-t / time)) / SR


def lp(x, cutoff):
    return sosfilt(butter(2, cutoff, "low", fs=SR, output="sos"), x)


def hp(x, cutoff):
    return sosfilt(butter(2, cutoff, "high", fs=SR, output="sos"), x)


# ------------------------------------------------------------------ instruments

def pure(f, decay, bend):
    """Rauta's Pure: a soft sine with a second and third that die sooner."""
    attack = 0.005
    n = int(SR * (attack + 7 * decay))
    t = np.arange(n) / SR
    ph = phase(f, bend, n)
    x = np.sin(ph) + 0.10 * np.sin(2 * ph) * np.exp(-t / (decay * 0.5)) + 0.03 * np.sin(3 * ph) * np.exp(-t / (decay * 0.3))
    return x * env(t, attack, decay)


def marimba(f, decay, bend, seed):
    """Rauta's Marimba: a rosewood bar, its second partial two octaves up, under a soft mallet."""
    d = max(decay * 1.6, 0.30 * (440 / f) ** 0.55 * min(1.0, decay / 0.1))
    n = int(SR * (0.002 + 7 * d))
    t = np.arange(n) / SR
    ph = phase(f, bend, n)
    x = np.sin(ph) * np.exp(-t / d)
    if 4 * f < 15000:
        x += 0.25 * np.sin(4 * ph) * np.exp(-t / (d * 0.2))
    if 10 * f < 15000:
        x += 0.09 * np.sin(10 * ph) * np.exp(-t / (d * 0.05))
    x *= np.clip(t / 0.0015, 0, 1)
    thock = lp(np.random.default_rng(seed).standard_normal(n), min(2600, 3 * f)) * np.exp(-t / 0.005)
    return x + 0.17 * thock / (np.max(np.abs(thock)) + 1e-12)


def glass(f, decay, bend, seed):
    """Frosted glass: a low, damped tone with only a trace of its bell partial, a felt tap on the front, and no
    highs: the quiet, short sound of a TV's interface rather than a ringing bell."""
    attack = 0.002
    n = int(SR * (attack + 7 * decay))
    t = np.arange(n) / SR
    ph = phase(f, bend, n, 0.012)
    body = np.sin(ph) + 0.12 * np.sin(2 * ph) * np.exp(-t / (decay * 0.4)) + 0.05 * np.sin(2.76 * ph) * np.exp(-t / (decay * 0.25))
    tap = lp(hp(np.random.default_rng(seed).standard_normal(n), 300), 1800) * np.exp(-t / 0.003)
    return lp(body * env(t, attack, decay) + 0.22 * tap / (np.max(np.abs(tap)) + 1e-12), 3200)


def bright_glass(f, decay, bend):
    """The first Glass: a small bell with two inharmonic partials that shimmer and fade first."""
    attack = 0.003
    d = decay * 1.4
    n = int(SR * (attack + 7 * d))
    t = np.arange(n) / SR
    ph = phase(f, bend, n)
    x = np.sin(ph) + 0.22 * np.sin(2.76 * ph) * np.exp(-t / (d * 0.45)) + 0.08 * np.sin(5.4 * ph) * np.exp(-t / (d * 0.2))
    x *= 1 + 0.04 * np.sin(TAU * 5.5 * t)
    return x * env(t, attack, d)


INSTRUMENTS = {
    "soft": ("Soft", "The selected app set: rounded electronic plucks, warm confirmations and a new startup cue."),
    "felt": ("Felt", "Dry, cushioned taps with less musical tone and separate event scores."),
    "glass": ("Glass", "The previous app set: low, damped tones with a felt tap, no melodies."),
    "bright-glass": ("Bright glass", "The first Glass: a small ringing bell, playing the melodic scores."),
    "pure": ("Pure", "Soft sines with a trace of harmonics: Rauta's favourite, melodic scores."),
    "marimba": ("Marimba", "A rosewood bar: Rauta's sound for things completed, melodic scores."),
}
# The app plays this instrument for every moment.
CHOSEN = "soft"


# ------------------------------------------------------------------ the start screen
# The one place with a little music: timed to IntroView. The mark traces for 0.9 s, its glass fills from 0.55 s and
# the name's six letters rise from 0.63 s to about 1.3 s; the screen may then stay while Home loads, and zooms away.

def pad(f, attack, hold, release, seed):
    """A dark, slow chord voice: three slightly detuned sines swelling in and fading, low-passed."""
    n = int(SR * (attack + hold + 6 * release))
    t = np.arange(n) / SR
    rng = np.random.default_rng(seed)
    x = sum(np.sin(TAU * f * 2 ** (c / 1200) * t + rng.uniform(0, TAU)) for c in (-5.0, 0.0, 5.0)) / 3
    x = x + 0.12 * np.sin(TAU * 2 * f * t + rng.uniform(0, TAU))
    rise = 0.5 * (1 - np.cos(np.pi * np.clip(t / attack, 0, 1)))
    fall = np.exp(-np.clip(t - attack - hold, 0, None) / release)
    return lp(x * rise * fall, 1400)


def layer(parts):
    out = np.zeros(max(int(round(at * SR)) + len(x) for at, x, _ in parts))
    for at, x, gain in parts:
        i = int(round(at * SR))
        out[i:i + len(x)] += gain * x
    return out


def intro():
    """A low fifth swells as the mark traces, a glass tap as it fills, and a warm chord settles as the name lands."""
    seed = zlib.crc32(b"intro")
    return layer([
        (0.0, pad(hz("D3"), 0.75, 0.45, 0.9, seed), 0.55),
        (0.05, pad(hz("A3"), 0.8, 0.4, 0.9, seed + 1), 0.35),
        (0.55, glass(hz("A4"), 0.13, 0.92, seed), 0.55),
        (1.22, pad(hz("D4"), 0.25, 0.5, 0.9, seed + 2), 0.28),
        (1.28, glass(hz("D4"), 0.4, 1.0, seed), 0.42),
        (1.29, glass(hz("F#4"), 0.38, 1.0, seed), 0.3),
        (1.30, glass(hz("A4"), 0.42, 1.0, seed), 0.36),
        (1.33, glass(hz("E5"), 0.3, 1.0, seed), 0.18),
    ])


def intro_exit():
    """Air rushing past as the mark zooms away, over the low D settling."""
    seed = zlib.crc32(b"intro-exit")
    n = int(SR * 0.7)
    t = np.arange(n) / SR
    air = hp(lp(np.random.default_rng(seed).standard_normal(n), 1300), 220)
    shape = np.sin(np.pi * np.clip(t / 0.62, 0, 1)) ** 2
    return layer([(0.0, air * shape / (np.max(np.abs(air)) + 1e-12), 0.5), (0.04, glass(hz("D3"), 0.16, 0.9, seed), 0.6)])


PIECES = {"intro": intro, "intro-exit": intro_exit}


def render(instrument, name):
    if instrument in ("soft", "felt"):
        from palettes import render as render_palette
        return render_palette(instrument, name)
    if name in PIECES:
        return PIECES[name]()
    seed = zlib.crc32(name.encode())
    layers = []
    for item in (TV_SCORES if instrument == "glass" else SCORES)[name]:
        at, notes, gain, decay = item[0], item[1], item[2], item[3]
        bend = item[4] if len(item) > 4 else 1.0
        for note in notes if isinstance(notes, list) else [notes]:
            f = hz(note)
            if instrument == "pure":
                s = pure(f, decay, bend)
            elif instrument == "marimba":
                s = marimba(f, decay, bend, seed)
            elif instrument == "glass":
                s = glass(f, decay, bend, seed)
            else:
                s = bright_glass(f, decay, bend)
            layers.append((at, s, gain))
    out = np.zeros(max(int(round(at * SR)) + len(s) for at, s, _ in layers))
    for at, s, gain in layers:
        i = int(round(at * SR))
        out[i:i + len(s)] += gain * s
    return out


# ------------------------------------------------------------------ mastering (Rauta's)

def _a_weighting():
    f1, f2, f3, f4 = 20.598997, 107.65265, 737.86223, 12194.217
    num = [(TAU * f4) ** 2 * 10 ** (1.9997 / 20), 0, 0, 0, 0]
    den = np.polymul([1, 2 * TAU * f4, (TAU * f4) ** 2], [1, 2 * TAU * f1, (TAU * f1) ** 2])
    den = np.polymul(np.polymul(den, [1, TAU * f3]), [1, TAU * f2])
    return bilinear(num, den, SR)


A_B, A_A = _a_weighting()


def loudness(x):
    """How loud the loudest tenth of a second is, as the ear weighs it (dB)."""
    w = lfilter(A_B, A_A, np.concatenate([x, np.zeros(int(0.1 * SR))]))
    n = int(0.1 * SR)
    power = np.cumsum(np.concatenate([[0.0], w ** 2]))
    return 10 * np.log10(np.max((power[n:] - power[:-n]) / n) + 1e-12)


def master(x, level):
    """Trims the tail, rounds the ends and sets the loudness on one scale for every sound."""
    x = hp(x - np.mean(x), 70)
    peak = np.max(np.abs(x))
    audible = np.nonzero(np.abs(x) > peak * 10 ** (-44 / 20))[0]
    x = x[: min(len(x), audible[-1] + int(0.06 * SR))]
    fade_in, fade_out = int(0.0005 * SR), min(len(x), int(0.06 * SR))
    x[:fade_in] *= np.linspace(0, 1, fade_in)
    x[-fade_out:] *= 0.5 * (1 + np.cos(np.linspace(0, np.pi, fade_out)))
    x = x * 10 ** ((-14.0 + level - loudness(x)) / 20)
    ceiling = 10 ** (-1.0 / 20)
    if np.max(np.abs(x)) > ceiling:
        x *= ceiling / np.max(np.abs(x))
    return x


def write_wav(path, x):
    """16-bit mono: SoundPool plays it without decoding, and the bytes are the same on every run."""
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).round().astype("<i2").tobytes())


def event_audio(instrument, name, level):
    x = render(instrument, name)
    if instrument in ("soft", "felt"):
        from palettes import finish
        return finish(name, x)
    return master(x, level)


def app():
    os.makedirs(RAW, exist_ok=True)
    for name, _, _, _, level in EVENTS:
        instrument = CHOSEN
        x = event_audio(instrument, name, level)
        write_wav(os.path.join(RAW, "sfx_" + name.replace("-", "_") + ".wav"), x)
        print(f"sfx_{name.replace('-', '_'):16s} {instrument:12s} {len(x) / SR * 1000:4.0f} ms")


def audition():
    manifest = {"instruments": [], "events": [], "chosen": {}}
    for key, (title, about) in INSTRUMENTS.items():
        os.makedirs(os.path.join(BUILD, key), exist_ok=True)
        manifest["instruments"].append({"id": key, "name": title, "about": about})
        for name, *_rest in EVENTS:
            level = _rest[-1]
            write_wav(os.path.join(BUILD, key, name + ".wav"), event_audio(key, name, level))
    for name, label, when, group, _ in EVENTS:
        manifest["events"].append({"id": name, "name": label, "when": when, "group": group})
        manifest["chosen"][name] = CHOSEN
    with open(os.path.join(HERE, "audition.html")) as f:
        page = f.read().replace("/*MANIFEST*/null", json.dumps(manifest))
    with open(os.path.join(BUILD, "index.html"), "w") as f:
        f.write(page)
    print(os.path.join(BUILD, "index.html"))


if __name__ == "__main__":
    # palettes shares these DSP helpers when this file is used as the CLI entry point.
    sys.modules.setdefault("sounds", sys.modules[__name__])
    audition() if sys.argv[1:] == ["audition"] else app()

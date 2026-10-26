"""Two independently voiced and composed interface sound palettes.

Soft: rounded plucks with a brief, harmonically tuned FM attack.
Felt: dry modal knocks with a cushioned noise excitation and little ring.
No Glass voice, old scores, pitch-shifted samples, or old intro arrangement.
Raw voices are mastered using the calibration from the approved audition.
"""
import json
from pathlib import Path
import zlib
import numpy as np
from scipy.signal import iirpeak, lfilter
import sounds as dsp

SR, TAU = dsp.SR, dsp.TAU
APPROVAL = json.loads((Path(__file__).parent / "soft-approval.json").read_text())
PALETTES = {
    "soft": {"name": "Soft", "letter": "B", "about": "Rounded electronic plucks. Clear, warm, quietly expressive."},
    "felt": {"name": "Felt", "letter": "C", "about": "Dry, cushioned taps. More texture, less musical tone."},
}


def contour(t, duration, attack, decay):
    rise = np.sin(np.pi / 2 * np.clip(t / attack, 0, 1)) ** 2
    fall = np.cos(np.pi / 2 * np.clip((t - duration * .58) / (duration * .42), 0, 1)) ** 2
    return rise * np.exp(-t / decay) * fall


def trajectory(f, t, travel):
    # Continuous movement across the body, rather than Glass's initial bend.
    u = np.clip(t / .065, 0, 1)
    u = u * u * (3 - 2 * u)
    return TAU * np.cumsum(f * 2 ** (travel * (u - .5) / 12)) / SR


def soft(f, duration, travel, colour, seed):
    t = np.arange(round(duration * SR)) / SR
    ph = trajectory(f, t, travel)
    index = (.45 + .5 * colour) * np.exp(-t / .009)
    body = np.sin(ph + index * np.sin(2 * ph))
    body += (.05 + .08 * colour) * np.sin(2 * ph) * np.exp(-t / .025)
    body += .025 * np.sin(3 * ph) * np.exp(-t / .012)
    return dsp.lp(body * contour(t, duration, .0045, duration * .29), 4200)


def felt(f, duration, travel, colour, seed):
    t = np.arange(round(duration * SR)) / SR
    ph = trajectory(f, t, travel * .4)
    body = .60 * np.sin(ph) * np.exp(-t / (duration * .24))
    body += .24 * np.sin(1.47 * ph) * np.exp(-t / (duration * .11))
    body += .12 * np.sin(2.09 * ph) * np.exp(-t / (duration * .065))
    noise = np.random.default_rng(seed).standard_normal(len(t))
    strike = dsp.lp(dsp.hp(noise, 420), 2600) * np.exp(-t / .006)
    resonance = np.zeros(len(t))
    for ratio, gain in ((.85, .6), (1.7, .3), (2.8, .13)):
        b, a = iirpeak(min(f * ratio, 5000), 3.2, fs=SR)
        resonance += gain * lfilter(b, a, strike)
    resonance /= np.max(np.abs(resonance)) + 1e-12
    skin = strike / (np.max(np.abs(strike)) + 1e-12)
    x = body + (.38 + .3 * colour) * resonance + .13 * skin
    return dsp.lp(x * contour(t, duration, .0028, duration * .55), 3300)


# at(s), note, duration(ms), gain, movement(semitones), colour(0..1).
# Frequent touches are dry single impulses; larger confirmations can resolve
# in two parts. These scores are separate from both scores in sounds.py.
SOFT = {
    "focus": [(0, "C5", 58, 1, 0, .25)],
    "key": [(0, "A5", 45, 1, 0, .4)],
    "edge": [(0, "F4", 94, 1, -1.2, .12)],
    "select": [(0, "C5", 130, 1, 1.0, .5)],
    "back": [(0, "A4", 112, 1, -2.0, .18)],
    "tab": [(0, "F5", 82, 1, .4, .32)],
    "open": [(0, "F4", 130, .72, .8, .15), (.042, "C5", 156, .8, 0, .35)],
    "delete": [(0, "G4", 75, 1, -1.8, .3)],
    "results": [(0, "A4", 108, .7, 0, .2), (.055, "C5", 137, .85, 0, .3)],
    "chip": [(0, "D5", 78, 1, .5, .5)],
    "favourite-on": [(0, "A4", 130, .8, .5, .28), (.072, "C5", 200, 1, .3, .25)],
    "favourite-off": [(0, "A4", 150, 1, -2.2, .15)],
    "watched-on": [(0, "C5", 110, .65, 0, .4), (.080, "F5", 218, 1, 0, .25)],
    "watched-off": [(0, "C5", 105, .65, 0, .15), (.050, "A4", 132, .8, -.5, .15)],
    "alert": [(0, "F4", 185, .7, 0, .2), (.018, "C5", 175, .45, 0, .12)],
    "cancel": [(0, "G4", 100, 1, -1.3, .1)],
    "installed": [(0, "F4", 155, .7, 0, .25), (.105, "C5", 275, 1, 0, .35), (.110, "A4", 250, .35, 0, .1)],
    "warning": [(0, "G4", 100, .85, -.5, .1), (.135, "G4", 128, 1, -.7, .1)],
    "pause": [(0, "C5", 118, 1, -2.2, .12)],
    "resume": [(0, "C5", 118, 1, 2.2, .4)],
    "seek-forward": [(0, "A4", 47, .6, 0, .2), (.032, "C5", 61, 1, .3, .28)],
    "seek-back": [(0, "C5", 47, .6, 0, .2), (.032, "A4", 61, 1, -.3, .28)],
    "menu-open": [(0, "A4", 143, 1, 2.4, .18)],
    "menu-close": [(0, "A4", 116, 1, -2.4, .12)],
    "up-next": [(0, "A4", 160, .65, 0, .2), (.110, "D5", 285, 1, .35, .28)],
    "finished": [(0, "C5", 165, .65, 0, .2), (.140, "F4", 405, 1, 0, .18), (.146, "A4", 340, .35, 0, .12)],
}
FELT = {
    "focus": [(0, "D5", 44, 1, 0, .48)],
    "key": [(0, "G5", 36, 1, 0, .65)],
    "edge": [(0, "G4", 68, 1, -1, .75)],
    "select": [(0, "F5", 91, 1, 0, .3)],
    "back": [(0, "C5", 74, 1, -.7, .6)],
    "tab": [(0, "A5", 62, 1, 0, .38)],
    "open": [(0, "C5", 90, .55, 0, .65), (.036, "F5", 120, 1, .5, .2)],
    "delete": [(0, "A4", 57, 1, -1.0, .75)],
    "results": [(0, "A5", 140, 1, 0, .15)],
    "chip": [(0, "G5", 58, 1, 0, .32)],
    "favourite-on": [(0, "F5", 110, .75, 0, .35), (.062, "A5", 163, 1, 0, .12)],
    "favourite-off": [(0, "D5", 119, 1, -1.0, .5)],
    "watched-on": [(0, "C5", 75, .6, 0, .5), (.079, "F5", 188, 1, 0, .16)],
    "watched-off": [(0, "D5", 107, 1, -.8, .55)],
    "alert": [(0, "C5", 142, 1, 0, .28)],
    "cancel": [(0, "A4", 81, 1, -.7, .7)],
    "installed": [(0, "C5", 115, .7, 0, .35), (.095, "F5", 260, 1, 0, .12)],
    "warning": [(0, "A4", 81, .8, 0, .8), (.125, "A4", 105, 1, -.3, .75)],
    "pause": [(0, "C5", 94, 1, -1.8, .5)],
    "resume": [(0, "F5", 98, 1, 1.8, .2)],
    "seek-forward": [(0, "D5", 36, .55, 0, .45), (.029, "G5", 44, 1, 0, .32)],
    "seek-back": [(0, "G5", 36, .55, 0, .32), (.029, "D5", 44, 1, 0, .45)],
    "menu-open": [(0, "C5", 108, 1, 1.0, .2)],
    "menu-close": [(0, "C5", 82, 1, -1.0, .65)],
    "up-next": [(0, "F5", 105, .7, 0, .4), (.110, "A5", 210, 1, 0, .12)],
    "finished": [(0, "A5", 120, .6, 0, .22), (.130, "F5", 340, 1, 0, .08), (.136, "C5", 290, .3, 0, .15)],
}


def bloom(note, duration, attack):
    """Finite soft breath for the new logo cue; no old pad or arrangement."""
    t = np.arange(round(duration * SR)) / SR
    f = dsp.hz(note)
    rise = np.sin(np.pi / 2 * np.clip(t / attack, 0, 1)) ** 2
    release = np.cos(np.pi / 2 * np.clip((t - duration * .38) / (duration * .62), 0, 1)) ** 2
    x = np.sin(TAU * f * t) + .13 * np.sin(TAU * 2 * f * t)
    return dsp.lp(x * rise * release, 2000)


def air(duration, seed, inward=False):
    t = np.arange(round(duration * SR)) / SR
    x = dsp.hp(dsp.lp(np.random.default_rng(seed).standard_normal(len(t)), 1800), 650)
    shape = np.sin(np.pi * t / duration) ** 2
    if inward:
        shape *= 1 - t / duration
    return x / (np.max(np.abs(x)) + 1e-12) * shape


def render(palette, name):
    if palette not in PALETTES:
        raise ValueError("Unknown palette: " + palette)
    voice = soft if palette == "soft" else felt
    seed = zlib.crc32((palette + ":" + name).encode())
    def hit(note, ms, travel=0, colour=.25, offset=0):
        return voice(dsp.hz(note), ms / 1000, travel, colour, seed + offset)
    if name == "intro":
        # Fits the existing mark-fill / name-reveal beats, with a new short F6/9 resolution.
        if palette == "soft":
            return dsp.layer([
                (.0, bloom("F3", 1.55, .35), .20),
                (.22, bloom("C4", 1.50, .35), .16),
                (.55, hit("C5", 225, 1.2), .58),
                (1.20, hit("F4", 850, 0, .15), .76),
                (1.22, hit("A4", 740, 0, .12), .34),
                (1.25, hit("G5", 660, 0, .08), .16),
            ])
        return dsp.layer([
            (.0, air(.65, seed), .035),
            (.05, bloom("F3", 1.50, .40), .13),
            (.55, hit("C5", 180, 0, .35), .52),
            (1.20, hit("F5", 660, 0, .1), .72),
            (1.23, hit("A5", 530, 0, .05), .28),
            (1.23, bloom("C4", .82, .08), .12),
        ])
    if name == "intro-exit":
        return dsp.layer([
            (.0, air(.32 if palette == "soft" else .25, seed, True), .10),
            (.0, hit("C5" if palette == "soft" else "F5", 180, -2, .1), .55),
        ])
    score = (SOFT if palette == "soft" else FELT)[name]
    return dsp.layer([(at, hit(note, ms, travel, colour, i), gain)
                      for i, (at, note, ms, gain, travel, colour) in enumerate(score)])


def finish(name, signal):
    """The approved mastering, independent of installed WAVs and audition caches."""
    # Glass's old 60 ms fade would erase the new 45–58 ms touch cues.
    x = dsp.hp(signal - np.mean(signal), 70)
    fade = min(round(.003 * SR), len(x) // 8)
    x[:fade] *= np.sin(np.linspace(0, np.pi / 2, fade)) ** 2
    x[-fade:] *= np.cos(np.linspace(0, np.pi / 2, fade)) ** 2
    target = APPROVAL["events"][name]["reference_loudness_db"]
    x *= 10 ** ((target - dsp.loudness(x)) / 20)
    if not np.isfinite(x).all() or np.max(np.abs(x)) >= 10 ** (-.5 / 20):
        raise ValueError("Excessive or invalid audio peaks: " + name)
    return x

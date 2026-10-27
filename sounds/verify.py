#!/usr/bin/env python3
"""Focused checks: approved audio, asset integrity, generation and Android timings."""
import hashlib
import re
import tempfile
import wave
from pathlib import Path
import numpy as np
import palettes
import sounds


def verify():
    assert sounds.CHOSEN == palettes.APPROVAL["pack"] == "soft"
    root = Path(sounds.ROOT)
    java = (root / "android/app/src/main/java/tv/stream/app/ui/Sounds.java").read_text()
    timings = dict(re.findall(r"R\.raw\.sfx_(\w+), Size\.\w+, ([\d.]+)", java))
    expected_names = {name.replace("-", "_") for name, *_ in sounds.EVENTS}
    assert set(timings) == expected_names, "Android / generator event mismatch"
    pcm_bytes = 0
    for name, _, _, _, level in sounds.EVENTS:
        resource = "sfx_" + name.replace("-", "_") + ".wav"
        asset = Path(sounds.RAW) / resource
        expected = palettes.APPROVAL["events"][name]
        actual_hash = hashlib.sha256(asset.read_bytes()).hexdigest()
        assert actual_hash == expected["sha256"], (name, "differs from approved preview")
        with wave.open(str(asset), "rb") as file:
            assert (file.getframerate(), file.getnchannels(), file.getsampwidth()) == (sounds.SR, 1, 2), name
            assert file.getnframes() == expected["frames"], name
            samples = np.frombuffer(file.readframes(file.getnframes()), dtype="<i2").astype(float) / 32768
        assert np.max(np.abs(samples)) < 10 ** (-.5 / 20), (name, "headroom")
        assert abs(np.mean(samples)) < .002, (name, "DC offset")
        assert abs(samples[0]) < .0002 and abs(samples[-1]) < .0002, (name, "click at endpoint")
        assert abs(sounds.loudness(samples) - expected["reference_loudness_db"]) < .15, (name, "level")
        assert abs(float(timings[name.replace("-", "_")]) - len(samples) / sounds.SR) < .001, (name, "stale playback window")
        with tempfile.TemporaryDirectory() as temporary:
            regenerated = Path(temporary) / resource
            sounds.write_wav(str(regenerated), sounds.event_audio(sounds.CHOSEN, name, level))
            assert regenerated.read_bytes() == asset.read_bytes(), (name, "not reproducible from source")
        pcm_bytes += len(samples) * 2
    print(f"PASS: {len(expected_names)} approved cues, reproducible generation, PCM/levels/endpoints and matching Android playback windows.")
    print(f"Total decoded mono PCM: {pcm_bytes:,} bytes.")


if __name__ == "__main__":
    verify()

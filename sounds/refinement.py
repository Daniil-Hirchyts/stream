#!/usr/bin/env python3
"""Compare the previous Glass set with the approved Soft set and Felt alternative.

Writes only build/refinement/. Never installs or replaces Android sound assets.
"""
from pathlib import Path
import hashlib
import json
import wave
import numpy as np
import sounds as dsp
import palettes

HERE = Path(__file__).resolve().parent
OUT = HERE / "build" / "refinement"
VERSIONS = ["current", *palettes.PALETTES]


def read_wav(path):
    with wave.open(str(path), "rb") as file:
        assert (file.getframerate(), file.getnchannels(), file.getsampwidth()) == (dsp.SR, 1, 2), path
        return np.frombuffer(file.readframes(file.getnframes()), dtype="<i2").astype(float) / 32768


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def timeline(names):
    return list(zip([.12 + .55 * i for i in range(len(names))], names))


FLOWS = {
    "navigation": ("Navigation", timeline(["focus", "focus", "edge", "tab", "select", "open", "back"])),
    "rapid": ("Quick browsing", [(.12 + i * .105, "focus") for i in range(12)]
              + [(1.50, "edge"), (1.90, "select"), (2.35, "open"), (2.85, "back")]),
    "search": ("Typing & results", [(.12 + i * .15, "key") for i in range(7)]
               + [(1.35, "delete"), (1.65, "key"), (2.1, "results"), (2.65, "chip")]),
    "player": ("Player", timeline(["menu-open", "pause", "resume", "seek-forward", "seek-back", "menu-close"])),
    "moments": ("Confirmations", timeline(["favourite-on", "favourite-off", "watched-on", "watched-off", "installed", "warning", "up-next", "finished"])),
    "startup": ("Startup", [(0, "intro"), (2.6, "intro-exit")]),
}


def render_flow(version, cues, audio):
    # Rapid touches replace the previous touch, like SoundPool's one-voice
    # policy. Startup exit layers over the logo tail, as in the app.
    duration = max(at + len(audio[(version, name)]) / dsp.SR for at, name in cues) + .12
    result = np.zeros(round(duration * dsp.SR))
    for i, (at, name) in enumerate(cues):
        x = audio[(version, name)].copy()
        if i + 1 < len(cues) and cues[i + 1][1] != "intro-exit":
            available = round((cues[i + 1][0] - at) * dsp.SR)
            if len(x) > available:
                x = x[:available]
                fade = min(round(.002 * dsp.SR), len(x))
                x[-fade:] *= np.linspace(1, 0, fade)
        start = round(at * dsp.SR)
        result[start:start + len(x)] += x
    assert np.max(np.abs(result)) < 10 ** (-.5 / 20), (version, "flow peak")
    return result


def generate():
    manifest = {"events": [], "flows": [], "versions": VERSIONS,
                "palettes": {"current": {"name": "Previous", "letter": "A", "about": "The previous Glass sounds. Soft is now selected for the app."}, **palettes.PALETTES}}
    metrics, audio, source_hashes = [], {}, {}
    for version in VERSIONS:
        (OUT / version).mkdir(parents=True, exist_ok=True)
    for name, label, when, group, level in dsp.EVENTS:
        original = Path(dsp.RAW) / ("sfx_" + name.replace("-", "_") + ".wav")
        source_hashes[str(original)] = digest(original)
        current_path = OUT / "current" / (name + ".wav")
        # The old renderer reproduced all original app WAVs byte for byte.
        # Keep that baseline after adopting Soft; never compare Soft against itself.
        dsp.write_wav(str(current_path), dsp.event_audio("glass", name, level))
        current = read_wav(current_path)
        audio[("current", name)] = current
        row = {"event": name, "current_ms": round(len(current) / dsp.SR * 1000)}
        for version in palettes.PALETTES:
            raw = palettes.render(version, name)
            assert np.array_equal(raw, palettes.render(version, name)), (version, name, "determinism")
            signal = palettes.finish(name, raw)
            path = OUT / version / (name + ".wav")
            dsp.write_wav(str(path), signal)
            rendered = read_wav(path)
            difference = dsp.loudness(rendered) - dsp.loudness(current)
            assert abs(difference) < .15, (version, name, difference)
            assert abs(np.mean(rendered)) < .002, (version, name, "DC")
            assert len(rendered) <= len(current), (version, name, "longer than current")
            assert abs(rendered[0]) < .0002 and abs(rendered[-1]) < .0002, (version, name, "ends")
            assert digest(path) != digest(current_path), (version, name, "unchanged")
            audio[(version, name)] = rendered
            row[version] = {"ms": round(len(rendered) / dsp.SR * 1000),
                            "loudness_difference_db": round(difference, 4),
                            "peak_dbfs": round(20 * np.log10(np.max(np.abs(rendered)) + 1e-12), 2)}
        metrics.append(row)
        manifest["events"].append({"id": name, "name": label, "when": when, "group": group})
    for flow_id, (label, cues) in FLOWS.items():
        for version in VERSIONS:
            dsp.write_wav(str(OUT / version / ("flow-" + flow_id + ".wav")), render_flow(version, cues, audio))
        manifest["flows"].append({"id": "flow-" + flow_id, "name": label, "events": [name for _, name in cues]})
    (OUT / "index.html").write_text((HERE / "refinement.html").read_text().replace("/*MANIFEST*/null", json.dumps(manifest)))
    report = {"events": metrics, "source_sha256": source_hashes, "baseline": "Previous Glass assets, reproduced by their original renderer",
              "method": "Two original voices, independent event scores and new startup arrangements; A-weighted per-event level matched",
              "app_modified": False, "selected_app_palette": dsp.CHOSEN}
    (OUT / "validation.json").write_text(json.dumps(report, indent=2) + "\n")
    assert all(digest(Path(path)) == checksum for path, checksum in source_hashes.items()), "App assets changed"
    print(f"Rendered {len(metrics)} events × {len(VERSIONS)} palettes and {len(FLOWS)} sequences each.")
    print(f"Level match: {max(abs(row[v]['loudness_difference_db']) for row in metrics for v in palettes.PALETTES):.4f} dB maximum difference")
    print("App assets untouched by this audition. " + str(OUT / "index.html"))


if __name__ == "__main__":
    generate()

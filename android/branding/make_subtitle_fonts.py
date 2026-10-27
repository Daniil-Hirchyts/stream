#!/usr/bin/env python3
"""Generate bundled medium/italic subtitle fonts; requires fonttools==4.60.1, never needed at runtime."""
import hashlib
import io
from pathlib import Path
from urllib.parse import quote
from urllib.request import urlopen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

REVISION = "e7db6ca603fd4862010383ff17ffd6cc4ffac1c0"
ROOT = Path(__file__).resolve().parents[1] / "app/src/main"
SOURCES = [
    ("notosans", "NotoSans[wdth,wght].ttf", "bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d", "subtitle_noto_medium", {"wght": 500, "wdth": 100}),
    ("notosans", "NotoSans-Italic[wdth,wght].ttf", "58e6e0ebd1931b29a365aa2d3e2ee9a9e831a3af7cf3ad1462d4e72154f0b291", "subtitle_noto_medium_italic", {"wght": 500, "wdth": 100}),
    ("atkinsonhyperlegiblenext", "AtkinsonHyperlegibleNext[wght].ttf", "5a455d1cfa099b601ab70751bb9673e8fe1854dc4500c80e1a220d0d75e31745", "subtitle_atkinson_medium", {"wght": 500}),
    ("atkinsonhyperlegiblenext", "AtkinsonHyperlegibleNext-Italic[wght].ttf", "ce9cffed32742ad2d9238c561a93220385e5934cdc02b8eb4097a50efa957dc6", "subtitle_atkinson_medium_italic", {"wght": 500}),
]

def fetch(family, name, digest):
    address = f"https://raw.githubusercontent.com/google/fonts/{REVISION}/ofl/{family}/{quote(name)}"
    with urlopen(address, timeout=30) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"Upstream checksum mismatch: {family}/{name}")
    return data

def main():
    for family, name, digest, output, axes in SOURCES:
        font = TTFont(io.BytesIO(fetch(family, name, digest)), recalcTimestamp=False)
        instance = instantiateVariableFont(font, axes, updateFontNames=True)
        instance.recalcTimestamp = False
        path = ROOT / f"res/font/{output}.ttf"
        instance.save(path)
        print(f"{path.name}: {path.stat().st_size} bytes, SHA256 {hashlib.sha256(path.read_bytes()).hexdigest()}")
    for family, output, digest in [
        ("notosans", "noto-sans", "cee9892f9f0cc8fe882c9e9537ee6a89621d86ee7ceaf70b02e2b2b1c25c061a"),
        ("atkinsonhyperlegiblenext", "atkinson-hyperlegible", "aca6a428580965d2297d1b718042dd427c2a9443ece3b0d02d758e161e0c4030"),
    ]:
        (ROOT / f"assets/notices/{output}.txt").write_bytes(fetch(family, "OFL.txt", digest))

if __name__ == "__main__":
    main()

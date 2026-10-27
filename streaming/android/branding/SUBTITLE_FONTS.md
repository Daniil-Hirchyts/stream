# Subtitle fonts

Daniil TV bundles Noto Sans Medium (500) as its Standard/default subtitle face,
and Atkinson Hyperlegible Next Medium (500) as an optional accessibility face.
Both families include real medium italics. Atkinson's Latin-only coverage is
supplemented with explicit Noto Sans runs for Cyrillic; other writing systems
retain Android's system fallback. The old eight font preference keys/indices
are preserved, and System sans-serif exposes the previous Standard face.

The font selector, preview, and renderer are app-owned. The shared receiver's
font choices and rendering are unchanged. Cue italic/bold emphasis survives;
provider font, size, and colour overrides do not replace the viewer's choices.
Existing subtitle sizes, colours, backgrounds, and positions are preserved.

The four static TTF files are generated from checksum-verified Google Fonts
sources at commit `e7db6ca603fd4862010383ff17ffd6cc4ffac1c0`:

```sh
python3 -m venv /tmp/daniiltv-subtitle-font-tools
/tmp/daniiltv-subtitle-font-tools/bin/pip install fonttools==4.60.1
/tmp/daniiltv-subtitle-font-tools/bin/python streaming/android/branding/make_subtitle_fonts.py
```

The build and installed app use committed font resources without Python or
network access. Upstream SIL Open Font License 1.1 notices ship in
`app/src/main/assets/notices/` and are listed in Settings → Open source notices.

`SubtitleFontsTest` checks saved-choice compatibility, emphasis, and explicit
Cyrillic fallback on API 26 and 36. `SubtitleFontRenderingTest` exercises the
Settings chooser and renders English, French, and Russian through Media3 on the
verified emulator, matching lowercase height for its comparison image.

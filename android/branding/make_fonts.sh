#!/bin/sh
# Subsets Inter 4.0 (SIL Open Font License 1.1, https://github.com/rsms/inter) into the app's font resources: Latin,
# Latin-1 and Extended-A, Greek, basic Cyrillic and common punctuation and symbols, with kerning, ligatures, tabular
# and case-sensitive forms. Anything else (Vietnamese, CJK, Arabic, ...) falls back to the system font.
#
#     INTER=/usr/share/fonts/opentype/inter sh branding/make_fonts.sh
#
# Needs fontTools (pyftsubset).
set -eu
INTER=${INTER:-/usr/share/fonts/opentype/inter}
OUT=$(dirname "$0")/../app/src/main/res/font
mkdir -p "$OUT"
for pair in Inter-Regular:inter_regular Inter-Medium:inter_medium Inter-SemiBold:inter_semibold InterDisplay-SemiBold:inter_display_semibold; do
    pyftsubset "$INTER/${pair%%:*}.otf" --output-file="$OUT/${pair##*:}.otf" --no-hinting \
        --layout-features='kern,liga,calt,ccmp,locl,mark,mkmk,tnum,case' \
        --unicodes='U+0020-007E,U+00A0-017F,U+0218-021B,U+0300-036F,U+0370-03FF,U+0400-045F,U+0490-0491,U+2000-206F,U+20AC,U+20B4,U+20BD,U+2116,U+2122,U+2190-2193,U+2212,U+2605,U+2713'
done

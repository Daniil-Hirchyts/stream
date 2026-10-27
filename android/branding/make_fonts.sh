#!/bin/sh
# Download the pinned Geist Sans faces and their notice. No font tools or system fonts are needed.
set -eu
exec python3 "$(dirname "$0")/make_geist.py" fonts

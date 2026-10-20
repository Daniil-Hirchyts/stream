#!/bin/zsh
set -eu
task_root="$(cd -- "$(dirname -- "$0")" && pwd)"
export TV_HOST=192.0.2.10
cd "$task_root/cleanyourtv"
exec tools/restore.sh "$task_root/profiles/firetv-preserve-features.txt" com.amazon.tv.launcher

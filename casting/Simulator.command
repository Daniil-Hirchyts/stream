#!/bin/sh
cd -- "$(dirname -- "$0")/.." || exit 1
if ! ./casting/simulator.sh up; then
  printf '\nStartup failed. Press Return to close...'
  read -r _
  exit 1
fi
exec ./casting/simulator.sh menu

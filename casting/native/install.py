"""Register this project's native host in the current user's Chrome profile."""
import hashlib
import base64
import json
from pathlib import Path
import shlex
import sys

ROOT = Path(__file__).resolve().parents[2]
NATIVE = Path(__file__).resolve().parent
manifest = json.loads((ROOT / "casting/extension/manifest.json").read_text())
digest = hashlib.sha256(base64.b64decode(manifest["key"])).hexdigest()[:32]
extension_id = "".join(chr(ord("a") + int(c, 16)) for c in digest)
config = NATIVE / "config.json"
if not config.exists():
    config.write_text(json.dumps({"tv_host": "192.168.1.11"}, indent=2) + "\n")
    config.chmod(0o600)
runner = NATIVE / "run-host.sh"
runner.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " + shlex.quote(str(NATIVE / "host.py")) + "\n")
runner.chmod(0o700)
host = {"name": "tv.daniil.cast", "description": "Daniil Cast direct video sender",
        "path": str(runner), "type": "stdio", "allowed_origins": [f"chrome-extension://{extension_id}/"]}
directory = Path.home() / "Library/Application Support/Google/Chrome/NativeMessagingHosts"
directory.mkdir(parents=True, exist_ok=True)
target = directory / "tv.daniil.cast.json"
target.write_text(json.dumps(host, indent=2) + "\n")
target.chmod(0o600)
print("Native host registered:", target)
print("Extension ID:", extension_id)
print("Load unpacked extension:", ROOT / "casting/extension")

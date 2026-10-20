"""Save a private, read-only Fire TV baseline through CleanYourTV's ADB client."""
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "cleanyourtv" / "tools"))
import tv

DEST = ROOT / "backups" / "2026-10-20-before"
DEST.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(DEST, 0o700)
commands = {
    "getprop.txt": "getprop",
    "packages.txt": "pm list packages",
    "packages-disabled.txt": "pm list packages -d",
    "packages-third-party.txt": "pm list packages -3",
    "settings-global.txt": "settings list global",
    "settings-secure.txt": "settings list secure",
    "settings-system.txt": "settings list system",
    "home.txt": "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
    "memory.txt": "cat /proc/meminfo",
    "storage.txt": "df /data",
    "activities.txt": "dumpsys activity activities",
    "connectivity.txt": "dumpsys connectivity",
}
device = tv.connect(timeout=25)
try:
    for name, command in commands.items():
        target = DEST / name
        if target.exists():
            continue
        result = device.shell(command, read_timeout_s=40.0)
        target.write_text(result or "")
        os.chmod(target, 0o600)
    summary = {}
    for key in ("ro.product.model", "ro.product.device", "ro.build.display.id",
                "ro.build.version.incremental", "ro.adb.secure"):
        summary[key] = device.shell("getprop " + key).strip()
    print(json.dumps(summary, indent=2))
    print("Third-party apps:")
    print((DEST / "packages-third-party.txt").read_text())
    print("Previously disabled:")
    print((DEST / "packages-disabled.txt").read_text())
    print("Backup directory:", DEST)
finally:
    device.close()

"""Run explicitly supplied ADB UI actions and save the resulting screen."""
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "cleanyourtv/tools"))
import tv

device = tv.connect(timeout=20)
try:
    for command in sys.argv[1:]:
        print(device.shell(command, read_timeout_s=25.0), end="", flush=True)
        time.sleep(0.7)
    time.sleep(1.5)
    remote = "/sdcard/cleanyourtv-ui.png"
    device.shell("screencap -p " + remote, read_timeout_s=20.0)
    target = ROOT / "backups/2026-10-20-after/ui-latest.png"
    device.pull(remote, str(target), transport_timeout_s=20.0, read_timeout_s=20.0)
    device.shell("rm " + remote)
    print(str(target))
finally:
    device.close()

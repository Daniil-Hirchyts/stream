"""Apply the reviewed package list, logging outcomes and checking each batch."""
import json
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "cleanyourtv/tools"))
import recovery
import tv

PROFILE = ROOT / "profiles/firetv-preserve-features.txt"
BEFORE = ROOT / "backups/2026-10-20-before"
AFTER = ROOT / "backups/2026-10-20-after"
AFTER.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(AFTER, 0o700)
RESULTS = AFTER / "cleanup-results.json"


def packages(text):
    return {line.partition(":")[2].strip() for line in text.splitlines()
            if line.startswith("package:")}


def save_json(path, data):
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(data, indent=2) + "\n")
    os.chmod(temp, 0o600)
    temp.replace(path)


def snapshot(device, name):
    remote = "/sdcard/cleanyourtv-check.png"
    device.shell("screencap -p " + remote, read_timeout_s=30.0)
    device.pull(remote, str(AFTER / name), transport_timeout_s=30.0, read_timeout_s=30.0)
    device.shell("rm " + remote)


target_names = recovery.read_list(PROFILE)
original_installed = packages((BEFORE / "packages.txt").read_text())
original_disabled = packages((BEFORE / "packages-disabled.txt").read_text())
record_file = recovery.record_path(PROFILE, os.environ.get("TV_HOST", ""))
record = recovery.load_record(record_file)
if not all(name in record["packages"] for name in target_names):
    raise SystemExit("Missing original package states; save the recovery record first")
save_json(BEFORE / "recovery.json", record)
if RESULTS.exists():
    raise SystemExit("A cleanup log already exists; inspect it before retrying")
results = {"host": os.environ.get("TV_HOST"), "profile": str(PROFILE), "packages": []}
device = tv.connect(timeout=20)
try:
    model = device.shell("getprop ro.product.model").strip()
    build = device.shell("getprop ro.build.display.id").strip()
    if (model, build) != ("AFTSS", "PS7717.5741N"):
        raise RuntimeError("Unexpected device or software build")
    initial_disabled = packages(device.shell("pm list packages -d"))
    if initial_disabled != original_disabled:
        raise RuntimeError("Package states changed since the baseline")
    for index, name in enumerate(target_names):
        state = record["packages"][name]
        entry = {"package": name, "before": state}
        if state == "enabled":
            response = device.shell("pm disable-user --user 0 " + name, read_timeout_s=20.0)
            disabled = packages(device.shell("pm list packages -d"))
            entry.update(response=response.strip(), disabled=name in disabled)
            print(name + ": " + ("disabled" if entry["disabled"] else "unchanged / refused"), flush=True)
        else:
            entry["skipped"] = True
            print(name + ": skipped (" + state + ")", flush=True)
        results["packages"].append(entry)
        save_json(RESULTS, results)
        if (index + 1) % 4 == 0 or index == len(target_names) - 1:
            home = device.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            if "com.amazon.tv.launcher/" not in home:
                raise RuntimeError("Home no longer resolves to the original launcher")
            current_installed = packages(device.shell("pm list packages"))
            current_disabled = packages(device.shell("pm list packages -d"))
            if current_installed != original_installed:
                raise RuntimeError("Unexpected installed-package change")
            if (current_disabled - original_disabled) - set(target_names):
                raise RuntimeError("An unselected package became disabled")
            device.shell("input keyevent KEYCODE_HOME")
            time.sleep(1)
            snapshot(device, "batch-" + str((index // 4) + 1) + ".png")
            print("Batch checked: original Home and installed apps preserved", flush=True)
    for filename, command in {
        "packages.txt": "pm list packages",
        "packages-disabled.txt": "pm list packages -d",
        "home.txt": "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
        "connectivity.txt": "dumpsys connectivity",
    }.items():
        path = AFTER / filename
        path.write_text(device.shell(command, read_timeout_s=30.0))
        os.chmod(path, 0o600)
    results["complete"] = True
    results["disabled_count"] = sum(bool(x.get("disabled")) for x in results["packages"])
    save_json(RESULTS, results)
    print("Cleanup complete: " + str(results["disabled_count"]) + " selected packages disabled")
finally:
    device.close()

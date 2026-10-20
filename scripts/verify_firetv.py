"""Check cleanup persistence, connectivity, and streaming-app startup after reboot."""
import json
import os
from pathlib import Path
import re
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "cleanyourtv/tools"))
import tv

BEFORE = ROOT / "backups/2026-10-20-before"
AFTER = ROOT / "backups/2026-10-20-after"


def packages(text):
    return {line.partition(":")[2].strip() for line in text.splitlines()
            if line.startswith("package:")}


def capture(device, name):
    remote = "/sdcard/cleanyourtv-verify.png"
    device.shell("screencap -p " + remote, read_timeout_s=30.0)
    device.pull(remote, str(AFTER / name), transport_timeout_s=30.0, read_timeout_s=30.0)
    device.shell("rm " + remote)


results = {"apps": []}
report = AFTER / "verification.json"


def checkpoint():
    report.write_text(json.dumps(results, indent=2) + "\n")
    os.chmod(report, 0o600)


device = tv.connect(timeout=20)
try:
    results["boot_completed"] = device.shell("getprop sys.boot_completed").strip() == "1"
    if not results["boot_completed"]:
        raise RuntimeError("Fire TV has not finished booting")
    installed = packages(device.shell("pm list packages"))
    disabled = packages(device.shell("pm list packages -d"))
    original_installed = packages((BEFORE / "packages.txt").read_text())
    original_disabled = packages((BEFORE / "packages-disabled.txt").read_text())
    changes = json.loads((AFTER / "cleanup-results.json").read_text())
    expected = {p["package"] for p in changes["packages"] if p.get("disabled")}
    results["installed_apps_unchanged"] = installed == original_installed
    results["disabled_packages_match"] = disabled == original_disabled | expected
    if not results["installed_apps_unchanged"] or not results["disabled_packages_match"]:
        raise RuntimeError("Package states do not match the reviewed cleanup")
    print("Reboot complete; all " + str(len(expected)) + " changes persisted; installed apps unchanged", flush=True)
    connectivity = device.shell("dumpsys connectivity", read_timeout_s=30.0)
    (AFTER / "connectivity-after-reboot.txt").write_text(connectivity)
    results["wifi_validated"] = any("Transports: WIFI" in line and "VALIDATED" in line for line in connectivity.splitlines())
    print("Validated Wi-Fi:", results["wifi_validated"], flush=True)
    checkpoint()
    device.shell("input keyevent KEYCODE_HOME")
    time.sleep(2)
    for name, package in [
        ("Netflix", "com.netflix.ninja"),
        ("Prime Video", "com.amazon.firebat"),
        ("YouTube", "com.amazon.firetv.youtube"),
        ("Apple TV", "com.apple.atve.amazon.appletv"),
        ("Disney+", "com.disney.disneyplus"),
    ]:
        component = None
        for category in ("LEANBACK_LAUNCHER", "LAUNCHER"):
            resolved = device.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category." + category + " " + package)
            candidates = [s.strip() for s in resolved.splitlines() if re.fullmatch(r"[A-Za-z_][\w.]*/[\w.$]+", s.strip())]
            if candidates:
                component = candidates[-1]
                break
        if component is None:
            results["apps"].append({"app": name, "started": False, "reason": "No launcher component found"})
            print(name + ": no launcher component found", flush=True)
            continue
        response = device.shell("am start -W -n " + component, read_timeout_s=45.0)
        time.sleep(2)
        activities = device.shell("dumpsys activity activities", read_timeout_s=20.0)
        resumed = [s.strip() for s in activities.splitlines() if "ResumedActivity" in s or "topResumedActivity" in s]
        started = "Status: ok" in response and any(package in s for s in resumed)
        entry = {"app": name, "component": component, "started": started,
                 "response": response.strip(), "resumed": resumed}
        results["apps"].append(entry)
        checkpoint()
        print(name + ": " + ("launch checked" if started else "needs review") + " | " + " / ".join(resumed), flush=True)
        device.shell("input keyevent KEYCODE_HOME")
        time.sleep(1)
    device.shell("input keyevent KEYCODE_HOME")
    time.sleep(2)
    capture(device, "home-final.png")
    home = device.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    results["original_home_enabled"] = "com.amazon.tv.launcher/" in home
    crashes = device.shell("logcat -b crash -d -t 60", read_timeout_s=20.0)
    (AFTER / "crash-log-after-reboot.txt").write_text(crashes)
    results["crash_log_has_fatal_exception"] = "FATAL EXCEPTION" in crashes
    checkpoint()
    print("Verification saved:", report, flush=True)
finally:
    device.close()

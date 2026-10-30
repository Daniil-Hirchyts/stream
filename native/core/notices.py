#!/usr/bin/env python3
"""Writes the open source notice for the Rust crates linked into the app's libstream.so.

Lists every crate the Android library is built from (normal dependencies on an Android target; build scripts and
procedural macros run only on the build machine) with its version and license, followed by each crate's own license
files. Identical texts are printed once. Run after changing Cargo.lock:

    python3 native/core/notices.py
"""

import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "android", "app", "src", "main", "assets", "notices", "streaming-engine.txt")
LICENSE_PREFIXES = ("license", "licence", "copying", "copyright", "notice", "unlicense")


def main():
    cargo = os.environ.get("CARGO") or os.path.expanduser("~/.cargo/bin/cargo")
    meta = json.loads(subprocess.check_output(
        [cargo, "metadata", "--locked", "--format-version", "1", "--filter-platform", "aarch64-linux-android"], cwd=HERE))
    packages = {p["id"]: p for p in meta["packages"]}
    nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
    root = meta["resolve"]["root"]
    shipped, stack = set(), [root]
    while stack:
        current = stack.pop()
        if current in shipped:
            continue
        shipped.add(current)
        for dep in nodes[current]["deps"]:
            normal = any(k["kind"] is None for k in dep["dep_kinds"])
            macro = any("proc-macro" in t["kind"] for t in packages[dep["pkg"]]["targets"])
            if normal and not macro:
                stack.append(dep["pkg"])
    shipped.discard(root)

    crates = sorted((packages[i] for i in shipped), key=lambda p: (p["name"], p["version"]))
    lines = [
        "Stream's streaming engine (libstream.so) is Stream's own Rust code (BSD-3-Clause) built with these crates.",
        "",
    ]
    lines += [f"  {p['name']} {p['version']}: {p['license']}" for p in crates]
    seen = {}
    for p in crates:
        directory = os.path.dirname(p["manifest_path"])
        files = sorted(f for f in os.listdir(directory) if f.lower().startswith(LICENSE_PREFIXES))
        if not files:
            sys.exit(f"{p['name']} {p['version']} has no license file")
        for name in files:
            with open(os.path.join(directory, name), encoding="utf-8", errors="replace") as f:
                text = f.read().strip()
            heading = f"{p['name']} {p['version']}: {name}"
            lines += ["", "-" * 78, heading, ""]
            if text in seen:
                lines.append(f"Same text as {seen[text]}.")
            else:
                seen[text] = heading
                lines.append(text)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{len(crates)} crates -> {os.path.relpath(OUT)}")


if __name__ == "__main__":
    main()

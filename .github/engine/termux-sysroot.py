#!/usr/bin/env python3
"""termux-sysroot.py OUTDIR PACKAGE...: Termux's aarch64 packages (and what they depend on) unpacked into OUTDIR.

The phone's QEMU is linked against Termux's libraries (pull-libs.sh copies them into the APK), so the
engine is cross-built against the same ones: headers, .pc files and .so files from Termux's own apt repository.
"""
import re
import subprocess
import sys
import urllib.request

BASE = "https://packages.termux.dev/apt/termux-main"
out, wanted = sys.argv[1], sys.argv[2:]

index = urllib.request.urlopen(f"{BASE}/dists/stable/main/binary-aarch64/Packages", timeout=120).read().decode()
pkgs, provides = {}, {}
for block in index.split("\n\n"):
    f = dict(re.findall(r"^([A-Za-z-]+): (.*)$", block, re.M))
    if "Package" in f and "Filename" in f:
        pkgs[f["Package"]] = f
        for p in f.get("Provides", "").split(","):
            if p.strip():
                provides.setdefault(p.strip().split(" ")[0], f["Package"])

seen, todo = [], list(wanted)
while todo:
    n = todo.pop()
    n = n if n in pkgs else provides.get(n, n)
    if n in seen:
        continue
    if n not in pkgs:
        print(f"termux-sysroot: no package {n}, skipped", file=sys.stderr)
        continue
    seen.append(n)
    for dep in pkgs[n].get("Depends", "").split(","):
        dep = dep.split("|")[0].strip().split(" ")[0]
        if dep:
            todo.append(dep)

for n in sorted(seen):
    deb = f"/tmp/{n}.deb"
    urllib.request.urlretrieve(f"{BASE}/{pkgs[n]['Filename']}", deb)
    subprocess.run(["dpkg-deb", "-x", deb, out], check=True)
    print(f"termux-sysroot: {n} {pkgs[n].get('Version', '')}")

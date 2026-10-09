#!/usr/bin/env python3
"""Replays the (TSGO.5) CLI recording through a NATIVE executable of the port and compares ((TSGO.6)).

The recording (scripts/tsgo-cli-oracle.py → build/goport/cli-oracle) is what the shipped tsgo binary prints, exits
with and writes. This runs every recorded case through BINARY — the GraalVM image
(`./gradlew :xemantic-typescript-compiler-tsgo:nativeImage`, `build/native/xtsc-tsgo`) — with the recorder's own
`run_case` (same arguments, environment and copy layout; copies at build/goport/cli-work/native, the same depth as
the recorder's), into build/goport/cli-native, and FAILS on any difference in stdout bytes, exit status or the
written file set/bytes. `XTSC_TSGO_LIB_DIR` points at the tsgo binary's own `lib`, as CliParityTest's lib directory
does. Per-case wall seconds land in build/goport/cli-native/times.json.

Usage: scripts/tsgo-cli-native-replay.py [--bin PATH] [--workers N] [--only NAME,...]
"""

import argparse
import concurrent.futures as cf
import importlib.util
import json
import os
import shutil
import sys
import time

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", default=os.path.join(REPO, "xemantic-typescript-compiler-tsgo/build/native/xtsc-tsgo"))
    ap.add_argument("--workers", type=int, default=1)
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    if not os.access(a.bin, os.X_OK):
        sys.exit(f"REFUSED: no native executable at {a.bin}")
    spec = importlib.util.spec_from_file_location("oracle", os.path.join(REPO, "scripts/tsgo-cli-oracle.py"))
    o = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(o)
    recorded = os.path.join(REPO, "build/goport/cli-oracle")
    cases = json.load(open(os.path.join(recorded, "cases.json")))["cases"]
    only = set(filter(None, a.only.split(",")))
    cases = [c for c in cases if not only or c["name"] in only]
    o.TSC = os.path.abspath(a.bin)
    o.OUT = os.path.join(REPO, "build/goport/cli-native")
    o.WORK = os.path.join(REPO, "build/goport/cli-work/native")
    o.ENV = dict(o.ENV)
    o.ENV["XTSC_TSGO_LIB_DIR"] = os.path.join(REPO, "tools/tsgo-7.0.2/lib")
    for d in (o.OUT, o.WORK):
        shutil.rmtree(d, ignore_errors=True)
    times = {}

    def timed(c):
        t = time.time()
        r = o.run_case(c)
        times[c["name"]] = time.time() - t
        return r

    with cf.ThreadPoolExecutor(a.workers) as ex:
        for name, code, _, _ in ex.map(timed, cases):
            print(f"{name:40s} exit={code} {times[name]:.1f}s", flush=True)
    with open(os.path.join(o.OUT, "times.json"), "w") as f:
        json.dump(times, f, indent=1, sort_keys=True)
    differ = []
    for c in cases:
        n = c["name"]
        want = open(os.path.join(recorded, n, "stdout.txt"), "rb").read()
        got = open(os.path.join(o.OUT, n, "stdout.txt"), "rb").read()
        rw = json.load(open(os.path.join(recorded, n, "result.json")))
        rg = json.load(open(os.path.join(o.OUT, n, "result.json")))
        what = [k for k, x, y in (("stdout", want, got), ("exit", rw["exit"], rg["exit"]), ("files", rw["files"], rg["files"])) if x != y]
        if what:
            differ.append(f"{n}: {', '.join(what)} (exit {rg['exit']}, stderr {rg['stderr'][:200]!r})")
    print(f"native replay: {len(cases)} cases: equal {len(cases) - len(differ)}, differ {len(differ)}; {sum(times.values()):.0f} s")
    for d in differ:
        print("  DIFFER " + d)
    if differ:
        sys.exit(1)


if __name__ == "__main__":
    main()

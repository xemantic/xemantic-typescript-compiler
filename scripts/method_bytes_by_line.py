#!/usr/bin/env python3
"""(JIT.1) round 816 — per-SOURCE-LINE bytecode attribution for one method.

`scripts/huge_methods.py` answers *which* methods HotSpot refuses to compile.
This answers *where inside one of them the bytecodes are*, so a split's
boundaries can be chosen from a MEASUREMENT instead of a line-count estimate
(rounds 807 and 810 each landed "just over"/"just under" the limit and had to
extract once more).

The instrument is `javap -c -l -p`: the `LineNumberTable` maps every bytecode
offset to a source line, so the bytes charged to a line are the offsets between
its entry and the next one IN OFFSET ORDER.

Two things a reader must know, both learned here:

  * a Kotlin INLINE function (`map`, `filter`, `let`, `run`, `joinToString`,
    `buildString`, …) is expanded into the caller, and its bytecodes carry
    SYNTHETIC line numbers PAST THE END OF THE FILE (the JSR-45 `SMAP` in
    `SourceDebugExtension` maps them back to the inlined declaration). For
    `compileParsedCore` that is 4,938 of 21,535 bytecodes — 23% of the method
    is code that is not in its source at all. Those bytes are charged here to
    the last REAL line seen before them, i.e. to the inlining call site, which
    is the attribution a split needs;
  * the table is emitted in bytecode order but its LINE numbers are not
    monotone, so the offsets must be sorted before differencing.

Usage:
  scripts/method_bytes_by_line.py <class> <method> [--cp DIR] [--src FILE]
                                  [--range A B ...] [--top N]
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
#  xemantic-typescript-compiler - a conformant TypeScript compiler and type
#  checker that runs on JVM, native, and WebAssembly
#  Copyright (C) 2026 Kazimierz Pogoda / Xemantic
#
#  This program is free software: you can redistribute it and/or modify
#  it under the terms of the GNU Affero General Public License as
#  published by the Free Software Foundation, version 3 of the License.
import argparse
import collections
import re
import subprocess
import sys


def per_line(cls, method, cp, nlines):
    """{source line -> bytecodes}, inlined bodies charged to their call site."""
    out = subprocess.run(
        ["javap", "-c", "-l", "-p", "-cp", cp, cls],
        capture_output=True, text=True, check=True).stdout
    lines = out.split("\n")
    start = None
    for i, l in enumerate(lines):
        if method + "(" in l and l.strip().endswith(";"):
            start = i
            break
    if start is None:
        raise SystemExit(f"method {method} not found in {cls}")
    lnt, maxoff, mode, j = [], 0, None, start
    while j < len(lines):
        l = lines[j]
        if j > start and re.match(r"^  [a-zA-Z].*;\s*$", l) and method not in l:
            break
        if "LineNumberTable:" in l:
            mode = "lnt"
            j += 1
            continue
        if mode == "lnt":
            m = re.match(r"\s*line (\d+): (\d+)\s*$", l)
            if m:
                lnt.append((int(m.group(1)), int(m.group(2))))
            else:
                mode = None
        m2 = re.match(r"\s*(\d+): [a-z]", l)
        if m2:
            maxoff = max(maxoff, int(m2.group(1)))
        j += 1
    lnt.sort(key=lambda t: t[1])
    per, synthetic, last_real = collections.Counter(), 0, None
    for k, (ln, off) in enumerate(lnt):
        nxt = lnt[k + 1][1] if k + 1 < len(lnt) else maxoff
        b = max(0, nxt - off)
        if ln <= nlines:
            last_real = ln
            per[ln] += b
        else:
            synthetic += b
            per[last_real if last_real else 0] += b
    return per, maxoff, synthetic


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cls")
    ap.add_argument("method")
    ap.add_argument("--cp", default="xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main")
    ap.add_argument("--src", default=None)
    ap.add_argument("--range", nargs="*", default=[])
    ap.add_argument("--top", type=int, default=0)
    a = ap.parse_args()
    nlines = 10 ** 9
    src = None
    if a.src:
        src = open(a.src).read().split("\n")
        nlines = len(src)
    per, size, synthetic = per_line(a.cls, a.method, a.cp, nlines)
    print(f"{a.cls}::{a.method}  size {size} bytecodes, "
          f"attributed {sum(per.values())}")
    if a.src:
        print(f"  of which INLINED (synthetic line numbers past EOF): "
              f"{synthetic} ({100.0 * synthetic / max(1, size):.1f}%)")
    for spec in a.range:
        lo, hi, *name = spec.split(":")
        lo, hi = int(lo), int(hi)
        s = sum(v for k, v in per.items() if lo <= k <= hi)
        print(f"  {lo:6}-{hi:<6} {s:7}  {name[0] if name else ''}")
    if a.top:
        for ln, v in sorted(per.items(), key=lambda t: -t[1])[:a.top]:
            txt = src[ln - 1].strip()[:88] if src else ""
            print(f"  {v:6}  line {ln}: {txt}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

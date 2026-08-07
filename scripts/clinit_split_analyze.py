#!/usr/bin/env python3
"""(JIT.1)(e) round 820 — measure `Checker.<clinit>` and choose the split.

The static initializer is the LAST shape in this arc that no contiguity
argument settles, because there is no control flow in it at all: it is a
sequence of `putstatic`s, one per companion-object property, and the only way
it shrinks is by moving a property's INITIALIZER EXPRESSION into a method it
calls.

Two things a reader must know before trusting the numbers below:

  * `javap` renders a static initializer as `  static {};` — **with no
    parameter list** — so the header regex in `scripts/huge_methods.py`
    (which requires a `(`) did not start a method there until round 817.
    This script therefore matches the `static {};` rendering explicitly;
  * a Kotlin `private const val` of a primitive/String type carries a
    `ConstantValue` attribute and costs `<clinit>` NOTHING. The 10,339
    bytecodes are entirely the COLLECTION constants (`setOf`/`mapOf` over
    hundreds of string literals), which is why the split is a hoist and not
    a partition of statements.

Per-property attribution comes from the `LineNumberTable`, exactly as
`scripts/method_bytes_by_line.py` does for a normal method: the bytes charged
to a source line are the offsets between its entry and the next one IN OFFSET
ORDER, and a property's cost is the sum over the lines of its declaration.

Usage:
  scripts/clinit_split_analyze.py [--cp DIR] [--src FILE] [--top N]
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

CLS = "com.xemantic.typescript.compiler.Checker"
DECL = re.compile(
    r"^        (?:private |internal |public )?(?:const )?(?:val|var|fun) "
    r"([A-Za-z_][A-Za-z0-9_]*)")


def clinit_per_line(cp):
    """{source line -> bytecodes} for `Checker.<clinit>`, plus its size."""
    out = subprocess.run(["javap", "-c", "-l", "-p", "-cp", cp, CLS],
                         capture_output=True, text=True, check=True).stdout
    lines = out.split("\n")
    start = next((i for i, l in enumerate(lines) if l.rstrip() == "  static {};"), None)
    if start is None:
        sys.exit("error: no `static {};` rendering in javap output — no <clinit>?")
    lnt, maxoff, mode = [], 0, None
    for l in lines[start:]:
        if "LineNumberTable:" in l:
            mode = "lnt"
            continue
        if mode == "lnt":
            m = re.match(r"\s+line (\d+): (\d+)\s*$", l)
            if m:
                lnt.append((int(m.group(2)), int(m.group(1))))
                continue
            mode = None
        m = re.match(r"^\s+(\d+): [a-z]", l)
        if m:
            maxoff = max(maxoff, int(m.group(1)))
    lnt.sort()
    per = collections.Counter()
    for i, (off, line) in enumerate(lnt):
        nxt = lnt[i + 1][0] if i + 1 < len(lnt) else maxoff
        per[line] += nxt - off
    return per, maxoff


def companion_span(src):
    """(first, last) 1-based line numbers of the `companion object` body."""
    first = next(n for n in range(1, len(src) + 1)
                 if src[n - 1].strip() == "companion object {")
    depth, started = 0, False
    for j in range(first - 1, len(src)):
        for ch in src[j]:
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
                if started and depth == 0:
                    return first, j + 1
    sys.exit("error: companion object never closes")


def property_spans(src, lo, hi):
    """[(name, declLine, closeLine)] for every companion property with a
    PARENTHESISED initializer — the only shape this split moves."""
    out = []
    for n in range(lo + 1, hi):
        m = DECL.match(src[n - 1])
        if not m:
            continue
        if " = setOf(" not in src[n - 1] and " = mapOf(" not in src[n - 1] \
                and " = listOf(" not in src[n - 1] and " = hashSetOf(" not in src[n - 1]:
            continue
        depth, close = 0, None
        for j in range(n - 1, hi):
            t = re.sub(r"//.*$", "", re.sub(r'"(?:\\.|[^"\\])*"', '""', src[j]))
            for ch in t:
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        close = j + 1
                        break
            if close:
                break
        if close:
            out.append((m.group(1), n, close))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cp", default="xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main")
    ap.add_argument("--src", default="src/commonMain/kotlin/Checker.kt")
    ap.add_argument("--top", type=int, default=15)
    a = ap.parse_args()

    per, size = clinit_per_line(a.cp)
    src = open(a.src).read().split("\n")
    lo, hi = companion_span(src)
    print(f"Checker.<clinit>      : {size} bytecodes "
          f"({'OVER' if size > 8000 else 'under'} HotSpot's 8,000 limit)")
    print(f"companion object body : lines {lo}-{hi}")
    rows = []
    for name, decl, close in property_spans(src, lo, hi):
        rows.append((sum(per[l] for l in range(decl, close + 1)), name, decl, close))
    rows.sort(reverse=True)
    print(f"parenthesised collection properties: {len(rows)}, "
          f"{sum(r[0] for r in rows)} of {size} bytecodes")
    print()
    print("bytecodes  lines            property")
    run = size
    for b, name, d, c in rows[:a.top]:
        run -= b
        print(f"{b:9d}  {d}-{c:<9d} {name:28s} (entry would be {run + 6})")
    return 0


if __name__ == "__main__":
    sys.exit(main())

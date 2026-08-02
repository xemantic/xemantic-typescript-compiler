#!/usr/bin/env python3
"""(JIT.1) — census of methods HotSpot refuses to JIT-compile because of size.

HotSpot's `DontCompileHugeMethods` is a PRODUCT flag defaulting to **true**, and
`HugeMethodLimit` is **8000 bytecodes**. A method above that limit is never
compiled by C1 or C2 — it runs in the interpreter for the entire process, no
matter how hot it gets, and nothing in a profile says so: `-XX:+PrintCompilation`
prints no "too large" line (the compile is never *proposed*, so it is never
*skipped*), and a JFR profile shows the cost smeared across the method's callees.

Round 734 checked ONE function this way (`checkSingleCallExpressionTypesCore`'s
core was 3,587 bytecodes) and generalised no further. Round 802 ran the census
over every class and found **13 methods above the limit — including the five
largest measured costs in the compiler**.

This is a static, deterministic census: it needs no run, no profile and no
timing, so it is a gate-able number rather than a measurement.

Usage:
  scripts/huge_methods.py                 # census, sorted, with the over-limit set
  scripts/huge_methods.py --limit 8000    # HotSpot's HugeMethodLimit (default)
  scripts/huge_methods.py --top 40
  scripts/huge_methods.py --fail-over 13  # non-zero exit above N over-limit methods

Reading the output: the size printed is the offset of the LAST bytecode in the
method, which is what HotSpot compares against `HugeMethodLimit` (it is the code
length, not an instruction count). Only real opcodes are counted — a naive parse
of `javap -c` picks up `lookupswitch`/`tableswitch` KEY lines and reports
nonsense in the billions.
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
#
#  This program is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU Affero General Public License for more details.
#
#  You should have received a copy of the GNU Affero General Public
#  License along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
#  As a special exception, this file contains Helper Code covered by the
#  xemantic-typescript-compiler Output Exception; additional permissions
#  are granted as described in the file LICENSE-EXCEPTION.

import argparse
import glob
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLASSES = os.path.join(REPO, "build", "classes", "kotlin", "jvm", "main")

SIG = re.compile(r"^  [a-zA-Z$<].*\(")
# An opcode line: "    1234: aload_0". The trailing letter is what separates a real
# instruction from a switch key line ("        42: 1234"), which has a number there.
OPCODE = re.compile(r"^\s+(\d+): [a-z]")


def census(class_files):
    out = []
    for cf in class_files:
        try:
            text = subprocess.run(
                ["javap", "-c", "-p", cf],
                capture_output=True, text=True, check=False,
            ).stdout
        except FileNotFoundError:
            sys.exit("error: `javap` not on PATH (it ships with the JDK)")
        owner = os.path.basename(cf)[:-len(".class")]
        cur, mx = None, 0
        for line in text.split("\n"):
            if SIG.match(line):
                if cur:
                    out.append((mx, owner, cur))
                cur, mx = line.strip(), 0
            else:
                m = OPCODE.match(line)
                if m:
                    mx = max(mx, int(m.group(1)))
        if cur:
            out.append((mx, owner, cur))
    out.sort(reverse=True)
    return out


def main():
    ap = argparse.ArgumentParser(description="(JIT.1) huge-method census")
    ap.add_argument("--limit", type=int, default=8000,
                    help="HotSpot HugeMethodLimit (default 8000)")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--fail-over", type=int, default=None,
                    help="exit 1 when more than N methods exceed the limit")
    ap.add_argument("--classes", default=CLASSES)
    args = ap.parse_args()

    files = sorted(glob.glob(os.path.join(args.classes, "**", "*.class"), recursive=True))
    if not files:
        sys.exit("error: no class files under %s — run `./gradlew compileKotlinJvm`" % args.classes)
    rows = census(files)
    over = [r for r in rows if r[0] > args.limit]

    print("classes scanned : %d" % len(files))
    print("methods         : %d" % len(rows))
    print("HugeMethodLimit : %d bytecodes (DontCompileHugeMethods defaults to true)" % args.limit)
    print("OVER THE LIMIT  : %d  <- never JIT-compiled, interpreted for the whole run" % len(over))
    print()
    print("%9s  %s" % ("bytecodes", "method"))
    for size, owner, sig in rows[:max(args.top, len(over))]:
        mark = "  <<<" if size > args.limit else ""
        print("%9d  %s :: %s%s" % (size, owner, sig[:96], mark))

    if args.fail_over is not None and len(over) > args.fail_over:
        sys.exit("FAIL: %d methods over the limit, budget is %d" % (len(over), args.fail_over))


if __name__ == "__main__":
    main()

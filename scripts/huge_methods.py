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

**(JIT.1)(f) round 817 — THIS IS A ROUND-GATE STEP NOW, AS A RATCHET.** Run

    python3 scripts/huge_methods.py --fail-over 0

next to `cost_gate.py` in every round that touches compiled code. **The census is
0 as of round 821, which closed (JIT.1)**: nothing in the compiled output is over
the limit, so this gate's whole job is now to fail the moment a NEW method crosses
8,000 — which is exactly how this family grew unnoticed for 800 rounds. Raising
the number is never the fix for a red gate — split the method.

The same ratchet also runs inside the suite, so it cannot be forgotten:
`HugeMethodLimitTest` (src/jvmTest) censuses the whole compiled main output from
the class files and fails both on a new offender and on a STALE entry (i.e. when
a split has landed and the ratchet was not tightened). Wiring this script into
Gradle's `check` is a build-system change and is owner-gated as queue item
(JIT.3).

Usage:
  scripts/huge_methods.py                 # census, sorted, with the over-limit set
  scripts/huge_methods.py --limit 8000    # HotSpot's HugeMethodLimit (default)
  scripts/huge_methods.py --top 40
  scripts/huge_methods.py --fail-over 1   # non-zero exit above N over-limit methods

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
# ROUND-853 FIX, the round-MOD.3 trap for the SECOND time (round 852 found it in
# `grid838.sh`). Until now this pointed at the ROOT project's class dir. Since the
# module split the compiler lives in `-core`, and the root path is a stale leftover
# — so from MOD.3 until here every `--fail-over 0` run censused a PRE-SPLIT binary
# and printed a green `OVER THE LIMIT: 0` about code nobody was shipping. The claim
# survived only because `HugeMethodLimitTest` runs the same whole-program census
# INSIDE the suite, locating the classes from a marker resource on the test
# classpath (which is exactly why CLAUDE.md insists on the second instrument).
# Do not "simplify" this back to a bare REPO-relative path.
MODULE = "xemantic-typescript-compiler-core"
CLASSES = os.path.join(REPO, MODULE, "build", "classes", "kotlin", "jvm", "main")
LEGACY_CLASSES = os.path.join(REPO, "build", "classes", "kotlin", "jvm", "main")

SIG = re.compile(r"^  [a-zA-Z$<].*\(")
# (JIT.1)(f) round 817 — javap renders the STATIC INITIALIZER as `  static {};`,
# with NO parameter list, so [SIG] (which requires a `(`) never starts a method
# there and every one of `<clinit>`'s bytecodes was charged to whatever method
# happened to precede it in the class file. That is not hypothetical: it is how
# `Checker.access$checkBigintPropertyNames$emit` — a 16-byte access bridge — has
# been reported as a **10,339-bytecode over-limit method** since round 802, and
# how the REAL offender at that size, `Checker.<clinit>`, stayed invisible to
# fourteen rounds of this census. Found by the round-817 suite ratchet, which
# reads `Code` attribute lengths out of the class file and so has no parser to
# be wrong.
CLINIT = re.compile(r"^  static \{\};")
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
            if SIG.match(line) or CLINIT.match(line):
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

    # The census is only evidence about the binary you ship. A path that resolves to
    # the pre-split root leftover answers about a different compiler, with exit 0 and
    # no tell — which is what happened between MOD.3 and round 853.
    if os.path.abspath(args.classes) == os.path.abspath(LEGACY_CLASSES):
        sys.exit("error: %s is the PRE-MODULE-SPLIT root class dir — the compiler lives in "
                 "%s since MOD.3, and censusing the leftover reports a green 0 about a binary "
                 "nobody ships. Pass --classes <core module dir> or delete the leftover."
                 % (args.classes, MODULE))
    files = sorted(glob.glob(os.path.join(args.classes, "**", "*.class"), recursive=True))
    if not files:
        sys.exit("error: no class files under %s — run "
                 "`./gradlew :%s:compileKotlinJvm`" % (args.classes, MODULE))
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

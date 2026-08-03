#!/usr/bin/env python3
"""(JIT.1)(e) round 819 — WHY the split's parts sum to fewer bytecodes than the
monolith, measured rather than assumed.

Rounds 816-818 found two independent mechanisms and each round measured the
other one ABSENT or PRESENT — so neither prior transfers and both have to be
counted every time:

  * round 816 — a `var` CAPTURED BY A LAMBDA is boxed into a `Ref$*Ref`, and
    every read of it inside the function is a `getfield` on that box. Splitting
    can drop the capture, and with it the box;
  * round 817 — LOCAL SLOT ADDRESSING. A monolith with ~60 live locals pays the
    2-byte `aload N` / `astore N` form for almost every access; inside a helper
    the same values sit in slots 0-3 and take the 1-byte `aload_N`.

THE INSTRUMENT TRAP THIS FILE EXISTS NOT TO REPEAT (round 818): a per-method
attribution keyed on `line.strip()[:70]` reported ZERO boxed reads in a method
that had 31, because the method's own NAME sits past character 70 of its
signature. So the key is the whole signature line, and the script ASSERTS that a
key containing the method name exists before it reports anything.

Run:  python3 scripts/tcjs_size_mechanism.py <preDir> <postDir>
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import subprocess
import sys

CLS = "com.xemantic.typescript.compiler.Transformer"
SIG = re.compile(r"^  [a-zA-Z$<].*\(")
OPCODE = re.compile(r"^\s+(\d+): ([a-z][\w]*)")
WIDE = re.compile(r"^\s+\d+: (a|i|l|f|d)(load|store)\s+\d+\s*$")
NARROW = re.compile(r"^\s+\d+: (a|i|l|f|d)(load|store)_\d\s*$")


def methods(cp):
    """{whole signature line -> [body lines]} for one class."""
    out = subprocess.run(["javap", "-c", "-p", "-cp", cp, CLS],
                         capture_output=True, text=True, check=True).stdout
    cur, acc, res = None, [], {}
    for line in out.split("\n"):
        if SIG.match(line):
            if cur:
                res[cur] = acc
            cur, acc = line.strip(), []
        elif cur:
            acc.append(line)
    if cur:
        res[cur] = acc
    return res


def stats(bodies):
    n_ref = sum(len(re.findall(r"Ref\$\w+Ref", l)) for b in bodies for l in b)
    wide = sum(1 for b in bodies for l in b if WIDE.match(l))
    narrow = sum(1 for b in bodies for l in b if NARROW.match(l))
    size = 0
    for b in bodies:
        mx = 0
        for l in b:
            m = OPCODE.match(l)
            if m:
                mx = max(mx, int(m.group(1)))
        size += mx
    return size, n_ref, wide, narrow


def main():
    pre_dir, post_dir = sys.argv[1], sys.argv[2]
    pre, post = methods(pre_dir), methods(post_dir)

    def pick(ms, pred, why):
        got = [b for s, b in ms.items() if pred(s)]
        assert got, f"no method matched {why} — the key is wrong, not the answer"
        return got

    a = pick(pre, lambda s: "transformToCommonJS(" in s, "transformToCommonJS in PRE")
    assert len(a) == 1, len(a)
    b = pick(post, lambda s: "transformToCommonJS(" in s or "tcjs" in s,
             "transformToCommonJS/tcjs* in POST")
    # positive control on the key: the monolith is GONE from post at its old size
    assert len(b) >= 20, len(b)

    rows = [("monolith (pre)", stats(a)), ("entry + 19 helpers (post)", stats(b))]
    print(f"{'':28} {'bytes':>8} {'Ref$ reads':>11} {'2-byte':>8} {'1-byte':>8}")
    for name, (size, ref, w, n) in rows:
        print(f"{name:28} {size:8} {ref:11} {w:8} {n:8}")
    d = [y - x for x, y in zip(rows[0][1], rows[1][1])]
    print(f"{'delta':28} {d[0]:+8} {d[1]:+11} {d[2]:+8} {d[3]:+8}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

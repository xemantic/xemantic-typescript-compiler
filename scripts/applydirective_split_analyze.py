#!/usr/bin/env python3
"""(JIT.1)(e) — analysis for the `applyDirective` split (round 815).

`CompilerOptionsKt.applyDirective` is 13,694 bytecodes: one `when (key)` over
~116 String-constant arms, each of which is an `options.copy(...)` call. A
`copy` on a ~150-field data class compiles to `copy$default` with the full
argument vector plus the default bitmasks, so EVERY arm costs ~110 bytecodes at
its call site — the size is the arm COUNT times the data class's field count,
and nothing about it is a cost model.

This script is the analysis half of round 805's five-check protocol:

  1. enumerate the arms, verbatim, with their line spans;
  2. assert the arm KEYS are pairwise distinct (which is what makes a partition
     into chained helpers order-independent — see --keys);
  3. per arm, record whether it reads `value` and whether it reads `boolValue`
     (an unused parameter is a build warning in this project, and the build is
     warning-clean);
  4. propose N contiguous, in-order groups balanced by line count;
  5. print the accounting the apply/verify steps close against.

Usage:
  scripts/applydirective_split_analyze.py            # census + proposed groups
  scripts/applydirective_split_analyze.py --groups 4
  scripts/applydirective_split_analyze.py --keys     # just the key list
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception

import argparse
import re
import sys

SRC = "src/commonMain/kotlin/CompilerOptions.kt"
HEAD_RE = re.compile(r"^internal fun applyDirective\(")
ARM_RE = re.compile(r'^        ("(?:[a-z0-9]+)"(?:\s*,\s*"[a-z0-9]+")*)\s*->')
ELSE_RE = re.compile(r"^        else ->")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read().split("\n")


def find_function(lines):
    """Return (start, end) 0-based inclusive line indices of applyDirective."""
    start = next(i for i, l in enumerate(lines) if HEAD_RE.match(l))
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count("{") - lines[i].count("}")
        if i > start and depth == 0:
            return start, i
    raise SystemExit("applyDirective: no matching close brace")


def parse_arms(lines, start, end):
    """Arms of the top-level `when (key)`, each a (keys, first, last) triple."""
    when_line = next(
        i for i in range(start, end) if lines[i].strip().startswith("return when (key)")
    )
    else_line = next(i for i in range(when_line, end) if ELSE_RE.match(lines[i]))
    arms = []
    starts = [i for i in range(when_line + 1, else_line) if ARM_RE.match(lines[i])]
    for n, s in enumerate(starts):
        e = (starts[n + 1] if n + 1 < len(starts) else else_line) - 1
        keys = re.findall(r'"([a-z0-9]+)"', ARM_RE.match(lines[s]).group(1))
        arms.append((keys, s, e))
    return when_line, else_line, arms


def uses(text, name):
    return re.search(r"\b" + name + r"\b", text) is not None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--groups", type=int, default=4)
    ap.add_argument("--keys", action="store_true")
    ap.add_argument("--src", default=SRC)
    a = ap.parse_args()

    lines = read(a.src)
    start, end = find_function(lines)
    when_line, else_line, arms = parse_arms(lines, start, end)

    if a.keys:
        for keys, s, e in arms:
            print(",".join(keys))
        return 0

    print(f"applyDirective: lines {start + 1}..{end + 1} ({end - start + 1} lines)")
    print(f"  when at {when_line + 1}, else at {else_line + 1}, arms {len(arms)}")

    # (2) keys pairwise distinct
    seen = {}
    dupes = []
    for keys, s, e in arms:
        for k in keys:
            if k in seen:
                dupes.append((k, seen[k], s + 1))
            seen[k] = s + 1
    print(f"  distinct keys: {len(seen)}  duplicates: {len(dupes)}")
    for k, a1, a2 in dupes:
        print(f"    DUPLICATE {k!r}: lines {a1} and {a2}")
    if dupes:
        print("  !! a duplicate key makes the chained partition ORDER-DEPENDENT")

    # (3) free-variable usage, per arm
    body_of = lambda s, e: "\n".join(lines[s : e + 1])
    n_value = sum(1 for k, s, e in arms if uses(body_of(s, e), "value"))
    n_bool = sum(1 for k, s, e in arms if uses(body_of(s, e), "boolValue"))
    print(f"  arms reading `value`: {n_value}   arms reading `boolValue`: {n_bool}")
    others = set()
    for keys, s, e in arms:
        for tok in re.findall(r"\b[a-zA-Z_][a-zA-Z0-9_]*\b", body_of(s, e)):
            others.add(tok)
    # anything that is neither a CompilerOptions field name nor a known callee
    # is reported for eyeballing; the apply step only ever passes the four.
    interesting = {t for t in others if t in ("options", "key", "value", "boolValue")}
    print(f"  captured locals actually named: {sorted(interesting)}")

    # (4) contiguous groups balanced by line count
    total = sum(e - s + 1 for _, s, e in arms)
    target = total / a.groups
    groups, cur, acc = [], [], 0
    for idx, (keys, s, e) in enumerate(arms):
        cur.append(idx)
        acc += e - s + 1
        remaining = a.groups - len(groups) - 1
        if remaining > 0 and acc >= target and len(arms) - idx - 1 > remaining:
            groups.append(cur)
            cur, acc = [], 0
    groups.append(cur)
    print(f"\n  proposed {len(groups)} contiguous groups (total arm lines {total}):")
    for gi, g in enumerate(groups):
        s0 = arms[g[0]][1]
        e0 = arms[g[-1]][2]
        txt = body_of(s0, e0)
        print(
            f"    group {gi + 1}: arms {len(g):3d}  lines {s0 + 1}..{e0 + 1}"
            f" ({e0 - s0 + 1:3d})  value={uses(txt, 'value')} boolValue={uses(txt, 'boolValue')}"
            f"  first={arms[g[0]][0][0]!r} last={arms[g[-1]][0][0]!r}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())

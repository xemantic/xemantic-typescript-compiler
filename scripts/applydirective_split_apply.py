#!/usr/bin/env python3
"""(JIT.1)(e) — apply the `applyDirective` split (round 815).

Rewrites `CompilerOptions.kt`'s `applyDirective` into an entry that chains N
`CompilerOptions?`-returning helpers, each holding one CONTIGUOUS, IN-ORDER run
of the original `when (key)` arms VERBATIM (zero dedent — the arms stay at their
original 8-space indentation because each helper is written as a block body with
`return when (key) {`).

The equivalence argument is the one the analyzer checks:

  * the arm keys are pairwise DISTINCT, so a `when` over all of them and a chain
    of `when`s over a partition of them select the same arm;
  * no arm evaluates to `null` (every arm is an `options` or `options.copy(…)`
    expression), so `?:` cannot skip a matched arm;
  * the runs are contiguous and in order, so even a hypothetical duplicate key
    would still resolve to the first arm.

Usage:
  scripts/applydirective_split_apply.py --groups 4          # in place
  scripts/applydirective_split_apply.py --groups 4 --dry-run
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception

import argparse
import sys

sys.path.insert(0, "scripts")
from applydirective_split_analyze import find_function, parse_arms, read, uses  # noqa: E402

SRC = "src/commonMain/kotlin/CompilerOptions.kt"

DOC = """/**
 * (JIT.1)(e) round 815 — one contiguous run of [applyDirective]'s `when (key)`
 * arms, verbatim. Returns `null` for a key this run does not name, which is what
 * lets [applyDirective] chain the runs with `?:`; no arm ever evaluates to
 * `null` itself, and the arm keys are pairwise distinct, so the chain selects
 * exactly the arm the single `when` selected.
 */"""


def build(lines, start, end, when_line, else_line, arms, groups):
    out = []
    out.append(lines[start])  # signature
    for i in range(start + 1, when_line):
        out.append(lines[i])  # `val boolValue = …`
    calls = [
        f"    return applyDirectiveArms{n + 1}(options, key, value, boolValue)"
        if n == 0
        else f"        ?: applyDirectiveArms{n + 1}(options, key, value, boolValue)"
        for n in range(len(groups))
    ]
    calls.append("        ?: options")
    out.extend(calls)
    out.append(lines[end])  # closing brace of applyDirective

    helpers = []
    for gi, g in enumerate(groups):
        s0, e0 = arms[g[0]][1], arms[g[-1]][2]
        helpers.append("")
        helpers.append(DOC)
        helpers.append(f"private fun applyDirectiveArms{gi + 1}(")
        helpers.append("    options: CompilerOptions,")
        helpers.append("    key: String,")
        helpers.append("    value: String,")
        helpers.append("    boolValue: Boolean,")
        helpers.append("): CompilerOptions? {")
        helpers.append("    return when (key) {")
        helpers.extend(lines[s0 : e0 + 1])
        helpers.append("        else -> null")
        helpers.append("    }")
        helpers.append("}")
    return out + helpers


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--groups", type=int, default=4)
    ap.add_argument("--src", default=SRC)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    lines = read(a.src)
    start, end = find_function(lines)
    when_line, else_line, arms = parse_arms(lines, start, end)

    # same balanced partition the analyzer prints
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

    # every helper must use all four parameters (the build is warning-clean)
    for gi, g in enumerate(groups):
        s0, e0 = arms[g[0]][1], arms[g[-1]][2]
        txt = "\n".join(lines[s0 : e0 + 1])
        for name in ("options", "value", "boolValue"):
            if not uses(txt, name):
                raise SystemExit(f"group {gi + 1} never reads `{name}` — unused parameter warning")

    new = build(lines, start, end, when_line, else_line, arms, groups)
    result = lines[:start] + new + lines[end + 1 :]
    print(f"applyDirective {end - start + 1} lines -> entry {len(new)} lines"
          f" ({len(groups)} helpers, {len(arms)} arms)")
    if a.dry_run:
        print("\n".join(new[:20]))
        return 0
    with open(a.src, "w", encoding="utf-8") as f:
        f.write("\n".join(result))
    print(f"wrote {a.src}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

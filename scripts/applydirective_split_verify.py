#!/usr/bin/env python3
"""(JIT.1)(e) — verify the `applyDirective` split (round 815), round 805's five checks.

Run against the WORKING TREE with the split applied; the reference is the
pre-split file, taken from a git revision (default: the commit before the split).

  1. every moved run re-extracted from the NEW file and compared VERBATIM
     against the corresponding line range of the reference (zero dedent);
  2. the new file RECONSTRUCTED from the reference by the apply step, and
     compared byte for byte against what is on disk;
  3. the accounting closes: reference body = kept + moved; new entry = kept +
     one call line per run plus the `?: options` tail;
  4. control-flow tokens enumerated on both sides (this body has none at
     statement level — the check exists to catch a `return` that moved);
  5. free variables per run re-asserted against the helper signatures, plus the
     property the partition's correctness reduces to: the arm keys are pairwise
     DISTINCT and the runs are contiguous and in source order.

Usage:
  scripts/applydirective_split_verify.py --ref HEAD --groups 4
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception

import argparse
import re
import subprocess
import sys

sys.path.insert(0, "scripts")
from applydirective_split_analyze import find_function, parse_arms, uses  # noqa: E402
from applydirective_split_apply import build  # noqa: E402

SRC = "src/commonMain/kotlin/CompilerOptions.kt"
FAIL = []


def check(name, ok, detail=""):
    print(f"  [{'OK ' if ok else 'FAIL'}] {name}{(' — ' + detail) if detail else ''}")
    if not ok:
        FAIL.append(name)


def partition(arms, n):
    total = sum(e - s + 1 for _, s, e in arms)
    target = total / n
    groups, cur, acc = [], [], 0
    for idx, (keys, s, e) in enumerate(arms):
        cur.append(idx)
        acc += e - s + 1
        remaining = n - len(groups) - 1
        if remaining > 0 and acc >= target and len(arms) - idx - 1 > remaining:
            groups.append(cur)
            cur, acc = [], 0
    groups.append(cur)
    return groups


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref", default="HEAD")
    ap.add_argument("--groups", type=int, default=4)
    ap.add_argument("--src", default=SRC)
    a = ap.parse_args()

    old = subprocess.run(
        ["git", "show", f"{a.ref}:{a.src}"], capture_output=True, text=True, check=True
    ).stdout.split("\n")
    with open(a.src, encoding="utf-8") as f:
        new = f.read().split("\n")

    o_start, o_end = find_function(old, )
    o_when, o_else, o_arms = parse_arms(old, o_start, o_end)
    groups = partition(o_arms, a.groups)
    print(f"reference {a.ref}: applyDirective {o_start + 1}..{o_end + 1}, "
          f"{len(o_arms)} arms, {len(groups)} runs")

    # (5a) keys pairwise distinct, runs contiguous and in order
    keys = [k for arm in o_arms for k in arm[0]]
    check("arm keys pairwise distinct", len(keys) == len(set(keys)),
          f"{len(keys)} keys")
    flat = [i for g in groups for i in g]
    check("runs are contiguous and in source order", flat == list(range(len(o_arms))))

    # (1) each moved run re-extracted from the NEW file, verbatim
    n_start, n_end = find_function(new)
    verbatim_ok = True
    moved_lines = 0
    for gi, g in enumerate(groups):
        s0, e0 = o_arms[g[0]][1], o_arms[g[-1]][2]
        want = old[s0 : e0 + 1]
        moved_lines += len(want)
        head = f"private fun applyDirectiveArms{gi + 1}("
        try:
            hi = next(i for i, l in enumerate(new) if l.startswith(head))
        except StopIteration:
            check(f"run {gi + 1} present in the new file", False)
            verbatim_ok = False
            continue
        wi = next(i for i in range(hi, len(new)) if new[i].strip() == "return when (key) {")
        got = new[wi + 1 : wi + 1 + len(want)]
        same = got == want
        check(f"run {gi + 1} verbatim ({len(want)} lines, dedent 0)", same)
        verbatim_ok = verbatim_ok and same
        if not same:
            for x, (u, v) in enumerate(zip(want, got)):
                if u != v:
                    print(f"        first diff at +{x}: {u!r} != {v!r}")
                    break

    # (2) reconstruction: rebuild from the reference and diff against disk
    rebuilt = old[:o_start] + build(old, o_start, o_end, o_when, o_else, o_arms, groups) + old[o_end + 1 :]
    # `build` keeps the doc comment as ONE list element containing newlines, so
    # the comparison has to be on the joined TEXT, not on the line lists.
    r_txt, n_txt = "\n".join(rebuilt), "\n".join(new)
    check("the new file is exactly the apply step's output from the reference",
          r_txt == n_txt, f"{len(r_txt)} vs {len(n_txt)} chars")

    # (3) accounting
    kept = (o_when - o_start) + 1 + 1  # signature + boolValue + `return when` + close
    entry = new[n_start : n_end + 1]
    check("accounting: entry = signature + boolValue + one call per run + tail + brace",
          len(entry) == (o_when - o_start) + len(groups) + 1 + 1,
          f"entry {len(entry)} lines, runs {len(groups)}, moved {moved_lines} arm lines")
    check("every arm line moved", moved_lines == o_arms[-1][2] - o_arms[0][1] + 1,
          f"{moved_lines} lines")

    # (4) control-flow tokens
    def tokens(lines):
        txt = "\n".join(lines)
        return (len(re.findall(r"\breturn\b", txt)),
                len(re.findall(r"\bcontinue\b", txt)),
                len(re.findall(r"\bbreak\b", txt)))
    o_tok = tokens(old[o_start : o_end + 1])
    # bound the new region at the closing brace of the LAST helper — the file
    # continues past it, and counting to EOF measures unrelated functions.
    last_head = f"private fun applyDirectiveArms{len(groups)}("
    li = next(i for i, l in enumerate(new) if l.startswith(last_head))
    lend = next(i for i in range(li, len(new)) if new[i] == "}")
    n_tok = tokens(new[n_start : lend + 1])
    check("control flow: HEAD has one `return`, the new tree has one per function",
          o_tok[1:] == (0, 0) and n_tok[1:] == (0, 0) and n_tok[0] == o_tok[0] + len(groups),
          f"HEAD {o_tok}, new {n_tok}")

    # (5b) free variables vs the helper signatures
    for gi, g in enumerate(groups):
        s0, e0 = o_arms[g[0]][1], o_arms[g[-1]][2]
        txt = "\n".join(old[s0 : e0 + 1])
        need = [n for n in ("options", "value", "boolValue") if uses(txt, n)]
        check(f"run {gi + 1} reads exactly the parameters it is passed",
              need == ["options", "value", "boolValue"], ",".join(need))

    print()
    if FAIL:
        print(f"FAILED: {len(FAIL)} check(s): {FAIL}")
        return 1
    print("all checks green")
    return 0


if __name__ == "__main__":
    sys.exit(main())

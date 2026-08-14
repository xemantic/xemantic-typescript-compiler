#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""(WARM.33) round 906 — inject the ReachMemoCensus hooks at EVERY memo array
access of the 45 per-file INV.4 reach/depth memos.

Round 875's instrument counted CONSULTATIONS and EDGE EVALUATIONS. The
transposition question is about the ACCESSES themselves — the probe at the
node's own slot, the ascent probes at its ancestors, and the write of each
folded status — so this injector hooks the `memo[...]` lines, one guarded call
each, and the classifier is taken from the nearest enclosing
`val memo = spine<X>Memo` binding rather than from a function name (some
accesses live in marker/backfill helpers that carry no classifier in their
name).

Two accesses to the SAME slot on one line (`memo[id].toInt() == 0` guarding
`memo[id] = v`) are hooked ONCE: a read-modify-write of one slot is one cache
line touch, which is the quantity being modelled.

`--check` verifies the tree is instrumented; `--revert` is `git checkout`
(never on a tree with uncommitted work — CLAUDE.md round 789).
"""
import re
import subprocess
import sys

CHECKER = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"

MEMO_BIND = re.compile(r"^\s+val memo = (spine[A-Za-z0-9]+)Memo\s*$")
HOPS = {
    "spineUResExpr": "uresExprHops",
    "spineUResType": "uresTypeHops",
}
# the memo field stem -> ReachCensus id constant (or a ReachMemoCensus one)
EXTRA = {"spineArgDepth": "ReachMemoCensus.ARGDEPTH", "spineIaDepth": "ReachMemoCensus.IADEPTH"}
SUFFIXES = ("Reach", "Status", "")


def classifier(stem: str) -> str:
    if stem in EXTRA:
        return EXTRA[stem]
    s = stem[len("spine"):]
    for suf in ("Reach", "Status"):
        if s.endswith(suf):
            s = s[: -len(suf)]
            break
    return "ReachCensus." + s.upper()


def main() -> None:
    src = open(CHECKER, errors="replace").read()
    lines = src.split("\n")

    if "--check" in sys.argv:
        n = src.count("ReachMemoCensus.")
        print(f"{n} ReachMemoCensus hook sites")
        sys.exit(0 if n > 100 else 1)

    if "--revert" in sys.argv:
        subprocess.check_call(["git", "checkout", "--", CHECKER])
        print("reverted")
        return

    if "ReachMemoCensus." in src:
        sys.exit("REFUSED: already instrumented")

    out = []
    stem = None
    skip_next_assign_for = None
    counts = {"p": 0, "pa": 0, "s": 0, "w": 0}
    for i, line in enumerate(lines):
        m = MEMO_BIND.match(line)
        if m:
            stem = m.group(1)
        if "memo[" not in line or "memo[startIn.id]" in line:
            out.append(line)
            continue
        if stem is None:
            sys.exit(f"line {i+1}: memo access with no binding in scope")
        if skip_next_assign_for is not None and skip_next_assign_for >= i:
            out.append(line)
            continue
        indent = line[: len(line) - len(line.lstrip())]
        cid = classifier(stem)
        call = None
        if "memo[pid]" in line:
            call = f"ReachMemoCensus.s({cid}, pid, (cur as NodeBase).nodeId)"
            counts["s"] += 1
        elif "memo[cid]" in line:
            call = f"ReachMemoCensus.w({cid}, cid)"
            counts["w"] += 1
        elif stem in HOPS and re.search(r"memo\[id\]\.toInt\(\)\s*!=\s*0|val m = memo\[id\]\.toInt\(\)", line):
            call = f"ReachMemoCensus.pa({cid}, id, {HOPS[stem]})"
            counts["pa"] += 1
            # UResExpr's `verdict = memo[id].toInt()` on the NEXT line is the
            # same slot on the same line pair: one touch.
            if i + 1 < len(lines) and "memo[id]" in lines[i + 1]:
                skip_next_assign_for = i + 1
        elif "memo[id] =" in line:
            call = f"ReachMemoCensus.w({cid}, id)"
            counts["w"] += 1
        elif "val m = memo[id].toInt()" in line:
            call = f"ReachMemoCensus.p({cid}, id)"
            counts["p"] += 1
        elif re.search(r"memo\[id\]\.toInt\(\)\s*==\s*0\)\s*\{\s*$", line):
            # a guarded backfill BLOCK: the write is the next line, one touch
            call = f"ReachMemoCensus.w({cid}, id)"
            counts["w"] += 1
            if i + 1 < len(lines) and "memo[id]" in lines[i + 1]:
                skip_next_assign_for = i + 1
        else:
            sys.exit(f"line {i+1}: unclassified memo access: {line.strip()}")
        out.append(f"{indent}if (ReachMemoCensus.on) {call}")
        out.append(line)

    open(CHECKER, "w").write("\n".join(out))
    print(f"injected {counts} = {sum(counts.values())} hooks")


if __name__ == "__main__":
    main()

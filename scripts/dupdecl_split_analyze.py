#!/usr/bin/env python3
"""(JIT.1)(d) round 812 — analysis helper for the split of
`checkDuplicateDeclarations` (12,935 bytecodes, the largest `Checker` method
left over HotSpot's 8,000-byte `HugeMethodLimit`).

Reuses round 811's LENGTH-PRESERVING Kotlin string/comment stripper (which walks
`${ … }` template expressions with a brace counter — a scanner that stops at the
next quote desynchronises and silently blanks 40,000 lines) and adds the one
thing this target needs and round 811's did not: a BRACE-MATCHING census of
which loop each `continue` binds to. `checkDuplicateDeclarations` is a loop body
inside a loop body: a `continue` that binds to the OUTER `for ((_, group) in
byName)` becomes a return signal when its region moves, and a `continue` that
binds to an inner loop must stay verbatim. Indentation is not evidence.

Run:  python3 scripts/dupdecl_split_analyze.py
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402

PATH = "src/commonMain/kotlin/Checker.kt"

FN_START, FN_END = 41227, 42098
GROUP_LOOP = 41416  # `for ((_, group) in byName) {`

REGIONS = {
    "I_imports": (41433, 41486),
    "E_enums": (41488, 41541),
    # 41548 (`val hasInterface = …`) STAYS in the entry: the value is read again
    # by the V region's TS2451 gates, so it is not the generics block's to own.
    "G_generics": (41549, 41698),
    "X_exportuniformity": (41735, 41832),
    "V_valueredecl": (41837, 42085),
}


def group_loop_continues(sl, a, b, loop_line):
    """Lines in [a,b] whose `continue` binds to the loop opened at `loop_line`.

    The stack is seeded by scanning from the function start, so a region's own
    braces are matched against the real enclosing context.
    """
    stack = []
    out = []
    for i in range(FN_START - 1, FN_END):
        s = sl[i]
        opened_here = []
        for ch in s:
            if ch == "{":
                stack.append(i + 1)
                opened_here.append(i + 1)
            elif ch == "}" and stack:
                stack.pop()
        if re.search(r"(?<![@\w.])continue\b", s) and a <= i + 1 <= b:
            # innermost enclosing LOOP header
            innermost = None
            for ln in reversed(stack):
                t = sl[ln - 1].strip()
                if re.match(r"(\}\s*)?(else\s*)?(for|while|do)\s*[({]", t) or \
                        re.search(r"\bfor\s*\(", t):
                    innermost = ln
                    break
            if innermost == loop_line:
                out.append(i + 1)
    return out


def main():
    raw = open(PATH).read()
    st = strip(raw)
    assert len(st) == len(raw)
    rl, sl = raw.split("\n"), st.split("\n")
    assert all(len(a) == len(b) for a, b in zip(rl, sl)), "length not preserved"
    # POSITIVE CONTROL (round 811's lesson: blanking preserves length, so a
    # length check cannot see a desynchronised stripper) — known declarations
    # inside the range must survive stripping.
    assert "data class DeclInfo" in sl[41234], sl[41234]
    assert "for ((_, group) in byName)" in sl[GROUP_LOOP - 1], sl[GROUP_LOOP - 1]
    assert "val hasBlockScoped = hasLet || hasConst" in sl[41988], sl[41988]

    decl_re = re.compile(r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)")
    # locals declared at the two levels the regions can see: the function body
    # (indent 8) and the group-loop body (indent 12) and the else arm (16).
    outer = []
    for ln in range(FN_START, FN_END + 1):
        s = sl[ln - 1]
        indent = len(s) - len(s.lstrip(" "))
        if indent in (8, 12, 16):
            for m in decl_re.finditer(s):
                outer.append((ln, m.group(1), indent))

    for name, (a, b) in REGIONS.items():
        body = sl[a - 1:b]
        bal = sum(l.count("{") - l.count("}") for l in body)
        bares = [a + i for i, l in enumerate(body)
                 if re.search(r"(?<![@\w.])return\s*$", l)]
        gl = group_loop_continues(sl, a, b, GROUP_LOOP)
        allc = [a + i for i, l in enumerate(body)
                if re.search(r"(?<![@\w.])continue\b", l)]
        used = set()
        for l in body:
            used |= set(re.findall(r"\b[A-Za-z_][A-Za-z0-9_]*\b", l))
        # declared INSIDE the region
        inside = {v for ln, v, _ in outer if a <= ln <= b}
        free = sorted({v for ln, v, _ in outer if ln < a and v in used} - inside)
        print(f"=== {name} {a}..{b} ({b - a + 1} lines) brace-balance={bal}")
        print(f"    whole-function bare returns: {bares}")
        print(f"    continues: {len(allc)} total, GROUP-LOOP-BOUND {len(gl)}: {gl}")
        print(f"    free outer locals: {free}")
        print()

    body = sl[FN_START - 1:FN_END]
    bares = [FN_START + i for i, l in enumerate(body)
             if re.search(r"(?<![@\w.])return\s*$", l)]
    print(f"function bare (whole-function) returns: {len(bares)} -> {bares}")
    print("group-loop continues OUTSIDE any region:",
          [l for l in group_loop_continues(sl, FN_START, FN_END, GROUP_LOOP)
           if not any(a <= l <= b for a, b in REGIONS.values())])
    moved = sum(b - a + 1 for a, b in REGIONS.values())
    print(f"body lines {FN_END - FN_START + 1}, moved {moved}, "
          f"kept {FN_END - FN_START + 1 - moved}")


if __name__ == "__main__":
    sys.exit(main())

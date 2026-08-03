#!/usr/bin/env python3
"""(JIT.1)(d) round 813 — analysis helper for the split of
`checkIndexSigInStatement` (10,928 bytecodes, over HotSpot's 8,000-byte
`HugeMethodLimit`, so never JIT-compiled).

Reuses round 811's LENGTH-PRESERVING Kotlin string/comment stripper (which walks
`${ … }` template expressions with a brace counter) plus round 812's
brace-matching `continue` census, and adds the question this target poses:
this body is a straight sequence of self-contained blocks, so the correctness
question is not which loop a `continue` binds to (every `continue` here is
inside a loop the region OWNS) but which locals CROSS a region boundary.

Run:  python3 scripts/indexsig_split_analyze.py
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402

PATH = "src/commonMain/kotlin/Checker.kt"

FN_START, FN_END = 125554, 126096

# Regions to move, in source order. Every one is a CONTIGUOUS run; leading
# explanatory comments stay in the ENTRY, above the call site, so the entry
# reads as a table of contents and the moved text is pure code.
REGIONS = {
    "R_NUMPROP": (125604, 125641),   # the `for (member in members)` numeric-name loop
    "R_STRSIG": (125645, 125676),    # own-then-inherited string index signature lookup
    "R_ANON": (125686, 125742),      # TS2413, anonymous object index values
    "R_NAMED": (125759, 125884),     # TS2413, named-interface index values
    "R_NUMMETH": (125890, 125935),   # TS2411, numeric-named methods vs number index
    "R_PRIMMETH": (125948, 125998),  # TS2411, methods vs a PRIMITIVE string index
    "R_PROPLOOP": (126001, 126095),  # TS2411, every named property vs the string index
}


def loop_bound_continues(sl, a, b):
    """For each `continue` in [a,b], the innermost enclosing LOOP header line.

    The stack is seeded from the function start so a region's braces are matched
    against their real enclosing context (round 812: indentation is not
    evidence). A `continue` whose innermost loop lies OUTSIDE the region is an
    exit from the region and would need a return signal.
    """
    out = []
    stack = []
    for i in range(FN_START - 1, FN_END):
        s = sl[i]
        for ch in s:
            if ch == "{":
                stack.append(i + 1)
            elif ch == "}" and stack:
                stack.pop()
        if re.search(r"(?<![@\w.])continue\b", s) and a <= i + 1 <= b:
            innermost = None
            for ln in reversed(stack):
                if re.search(r"\b(for|while)\s*\(", sl[ln - 1]):
                    innermost = ln
                    break
            out.append((i + 1, innermost))
    return out


def main():
    raw = open(PATH).read()
    st = strip(raw)
    assert len(st) == len(raw)
    rl, sl = raw.split("\n"), st.split("\n")
    assert all(len(a) == len(b) for a, b in zip(rl, sl)), "length not preserved"
    # POSITIVE CONTROL (round 811: blanking preserves length, so a length check
    # cannot see a desynchronised stripper) — known declarations inside the
    # range must SURVIVE stripping.
    assert "fun checkIndexSigInStatement" in sl[FN_START - 1], sl[FN_START - 1]
    assert "val numberIndexSig" in sl[125599 - 1], sl[125599 - 1]
    assert "val stringIndexType " in sl[125937 - 1], sl[125937 - 1]
    assert "val methodNameCounts" in sl[126003 - 1], sl[126003 - 1]
    # ...and a known string CONTENT must be gone.
    assert "index type" not in sl[125615 - 1], sl[125615 - 1]

    decl_re = re.compile(r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)")
    outer = []
    for ln in range(FN_START, FN_END + 1):
        s = sl[ln - 1]
        indent = len(s) - len(s.lstrip(" "))
        if indent == 8:  # function-body level
            for m in decl_re.finditer(s):
                outer.append((ln, m.group(1)))

    print(f"function {FN_START}..{FN_END} ({FN_END - FN_START + 1} lines)")
    print("body-level locals:", [f"{v}@{ln}" for ln, v in outer])
    print()
    for name, (a, b) in REGIONS.items():
        body = sl[a - 1:b]
        bal = sum(l.count("{") - l.count("}") for l in body)
        bares = [a + i for i, l in enumerate(body)
                 if re.search(r"(?<![@\w.])return\s*$", l)]
        rets = [a + i for i, l in enumerate(body)
                if re.search(r"(?<![@\w.])return\b", l)]
        cont = loop_bound_continues(sl, a, b)
        escaping = [(ln, lp) for ln, lp in cont if lp is None or not (a <= lp <= b)]
        # A local is FREE in the region only if it is read as a NAME — `.members`
        # is a property access on some other receiver and must not count, which
        # is exactly the false positive an unqualified `\bmembers\b` produces.
        txt = "\n".join(body)
        inside = {v for ln, v in outer if a <= ln <= b}
        free = sorted({v for ln, v in outer if ln < a
                       and re.search(r"(?<![.\w])" + v + r"\b", txt)} - inside)
        # body-level locals DECLARED inside the region and read AFTER it
        after = set()
        for ln in range(b + 1, FN_END + 1):
            after |= set(re.findall(r"\b[A-Za-z_][A-Za-z0-9_]*\b", sl[ln - 1]))
        crossing = sorted(inside & after)
        print(f"=== {name} {a}..{b} ({b - a + 1} lines) brace-balance={bal}")
        print(f"    bare (whole-function) returns: {bares}")
        print(f"    any `return` token lines: {rets}")
        print(f"    continues: {len(cont)}, ESCAPING the region: {escaping}")
        print(f"    free outer locals: {free}")
        print(f"    locals it declares that are read AFTER it: {crossing}")
        print()

    body = sl[FN_START - 1:FN_END]
    bares = [FN_START + i for i, l in enumerate(body)
             if re.search(r"(?<![@\w.])return\s*$", l)]
    print(f"whole-function bare returns in the FUNCTION: {len(bares)} -> {bares}")
    moved = sum(b - a + 1 for a, b in REGIONS.values())
    n = FN_END - FN_START + 1
    print(f"body lines {n}, moved {moved}, kept {n - moved}")
    # regions must be disjoint and in order
    spans = sorted(REGIONS.values())
    for (a1, b1), (a2, b2) in zip(spans, spans[1:]):
        assert b1 < a2, (a1, b1, a2, b2)
    print("regions disjoint and in source order: OK")


if __name__ == "__main__":
    sys.exit(main())

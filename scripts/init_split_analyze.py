#!/usr/bin/env python3
"""(JIT.1)(d) round 814 — analysis helper for the split of the `Checker`
CONSTRUCTOR (`<init>`, 11,298 bytecodes, over HotSpot's 8,000-byte
`HugeMethodLimit`, so never JIT-compiled).

Shape, and why it differs from every earlier target in this arc: the body is a
`try { … } catch (StackOverflowError)` holding an ORDERED SEQUENCE of ~437
`pass("name") { … }` dispatches, with exactly three branches
(`if (PassTiming.enabled || censusMode)`, `if (declarationOnly)`,
`if (!declarationOnly)`), NO loops at body level, NO `return`/`break`/`continue`
at body level, and exactly TWO body-level locals. So the correctness questions
are only: (a) does any region carry body-level control flow (it must not),
(b) does any local cross a region boundary, and (c) is the sequence order
preserved.

Run:  python3 scripts/init_split_analyze.py
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402

# Line numbers are HEAD's, so pass a HEAD copy when Checker.kt is already split.
PATH = sys.argv[1] if len(sys.argv) > 1 else "src/commonMain/kotlin/Checker.kt"

INIT_START, INIT_END = 5872, 7965

# Regions to move, in source order. Every one is a CONTIGUOUS run of whole
# statements. Leading explanatory comments stay in the ENTRY, above the call
# site, so the entry reads as a table of contents.
from init_split_apply import REGIONS as APPLY_REGIONS  # noqa: E402

# Regions to move, in source order — the SAME table `init_split_apply.py` uses,
# each a CONTIGUOUS run that starts at the leading comment block of its first
# statement (so the entry keeps no orphan comment describing moved code).
REGIONS = {r[0]: (r[1], r[2]) for r in APPLY_REGIONS}



def main():
    raw = open(PATH).read()
    st = strip(raw)
    assert len(st) == len(raw)
    rl, sl = raw.split("\n"), st.split("\n")
    assert all(len(a) == len(b) for a, b in zip(rl, sl)), "length not preserved"
    # POSITIVE CONTROLS (round 811: blanking preserves length, so a length check
    # cannot see a desynchronised stripper) — known code must SURVIVE stripping…
    assert sl[INIT_START - 1].strip() == "init {", sl[INIT_START - 1]
    assert "var preAugmentationGlobalsKeys" in sl[5997 - 1], sl[5997 - 1]
    assert "val shouldCheckDefiniteAssignment" in sl[6200 - 1], sl[6200 - 1]
    assert "if (!declarationOnly) {" in sl[6053 - 1], sl[6053 - 1]
    # …and a known string CONTENT and a known comment must be gone.
    assert "init:mergeLibGlobals" not in sl[5893 - 1], sl[5893 - 1]
    assert "SETUP" not in sl[5887 - 1], sl[5887 - 1]

    # ---- brace depth, relative to the `init {` line -------------------------
    depth = 0
    d_at = {}
    for ln in range(INIT_START, INIT_END + 1):
        d_at[ln] = depth
        for ch in sl[ln - 1]:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
    assert depth == 0, f"init block does not close at {INIT_END}: depth {depth}"

    # depth 2 == a statement of the `try` body; depth 3 inside 6053..7953 == a
    # statement of the `if (!declarationOnly)` body.
    body_stmts = [ln for ln in range(INIT_START, INIT_END + 1)
                  if sl[ln - 1].strip() and
                  (d_at[ln] == 2
                   or (d_at[ln] == 3 and 6053 < ln < 7953)      # !declarationOnly
                   or (d_at[ln] == 3 and 6021 < ln < 6051))]    # declarationOnly
    print(f"init {INIT_START}..{INIT_END} ({INIT_END - INIT_START + 1} lines), "
          f"{len(body_stmts)} body-level statements")

    # (a) NO body-level control flow anywhere in the block.
    ctrl = [ln for ln in body_stmts
            if re.search(r"(?<![@\w.])(return|break|continue)\b", sl[ln - 1])]
    print(f"body-level return/break/continue: {ctrl} (must be empty)")
    assert not ctrl

    # body-level locals
    decl_re = re.compile(r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)")
    locals_ = [(ln, m.group(1)) for ln in body_stmts
               for m in decl_re.finditer(sl[ln - 1])]
    print("body-level locals:", [f"{v}@{ln}" for ln, v in locals_])

    npass = sum(1 for ln in range(INIT_START, INIT_END + 1)
                if re.search(r"\bpass\(", sl[ln - 1]))
    print(f"`pass(` dispatch lines in the block: {npass}")
    print()

    ok = True
    for name, (a, b) in REGIONS.items():
        body = sl[a - 1:b]
        bal = sum(l.count("{") - l.count("}") for l in body)
        # every region must start and end on a body-level statement boundary
        # a region may open on the leading COMMENT block of its first statement
        first_code = next(ln for ln in range(a, b + 1) if sl[ln - 1].strip())
        starts_ok = first_code in body_stmts and all(
            not sl[ln - 1].strip() for ln in range(a, first_code))
        # the region ends on a statement boundary iff the next non-blank stripped
        # line is at a depth no greater than the region's own opening depth (a
        # sibling statement, or the `}` that closes the enclosing block).
        nxt = next((ln for ln in range(b + 1, INIT_END + 1)
                    if sl[ln - 1].strip()), None)
        ends_ok = nxt is not None and d_at[nxt] <= d_at[a]
        rets = [a + i for i, l in enumerate(body)
                if re.search(r"(?<![@\w.])(return|break|continue)\b", l)
                and d_at[a + i] in (2, 3)]
        # a local declared before the region and NAMED inside it
        txt = "\n".join(body)
        inside = {v for ln, v in locals_ if a <= ln <= b}
        free = sorted({v for ln, v in locals_ if ln < a
                       and re.search(r"(?<![.\w])" + v + r"\b", txt)} - inside)
        after = set()
        for ln in range(b + 1, INIT_END + 1):
            after |= set(re.findall(r"\b[A-Za-z_][A-Za-z0-9_]*\b", sl[ln - 1]))
        crossing = sorted(inside & after)
        code = sum(1 for l in body if l.strip())
        p = sum(1 for l in body if re.search(r"\bpass\(", l))
        print(f"=== {name} {a}..{b} ({b - a + 1} lines, {code} code, {p} passes) "
              f"brace-balance={bal}")
        print(f"    starts on a statement: {starts_ok}   ends on a boundary: {ends_ok}")
        print(f"    body-level control flow inside: {rets} (must be empty)")
        print(f"    free body-level locals (need a parameter): {free}")
        print(f"    locals it declares that are read AFTER it: {crossing}")
        ok &= (bal == 0 and starts_ok and ends_ok and not rets
               and not free and not crossing)
        print()

    spans = sorted(REGIONS.values())
    for (a1, b1), (a2, b2) in zip(spans, spans[1:]):
        assert b1 < a2, (a1, b1, a2, b2)
    print("regions disjoint and in source order: OK")
    moved = sum(b - a + 1 for a, b in REGIONS.values())
    n = INIT_END - INIT_START + 1
    print(f"init lines {n}, moved {moved}, kept {n - moved}")
    print("ALL REGION CHECKS PASS" if ok else "REGION CHECKS FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

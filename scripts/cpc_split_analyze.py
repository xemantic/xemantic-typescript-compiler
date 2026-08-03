#!/usr/bin/env python3
"""(JIT.1)(e) round 816 — analysis helper for the split of
`TypeScriptCompiler.compileParsedCore` (21,535 bytecodes, 2.7x HotSpot's
8,000-byte `HugeMethodLimit`, so never JIT-compiled).

Two things are new here relative to rounds 811-815.

1.  **The boundaries are MEASURED, not estimated.** `scripts/method_bytes_by_line.py`
    attributes every one of the 21,535 bytecodes to a source line via javap's
    `LineNumberTable`, so each candidate region's size is known BEFORE the edit.
    Rounds 807 and 810 each landed one extraction short/over and had to build
    twice.

2.  **A scope-aware free-variable computation.** The earlier scripts matched
    declaration names textually, which cannot tell a local declared in the
    single-file branch from the same name re-declared in the multi-file branch.
    Here the scopes are simulated with a brace stack, so "visible at the region"
    means what Kotlin means by it. The answer is the helper's PARAMETER LIST;
    the compiler then enforces it (and an unused parameter is a `w:` in this
    warning-clean build, so the list is exact in both directions).

Run:  python3 scripts/cpc_split_analyze.py
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402

PATH = "src/commonMain/kotlin/TypeScriptCompiler.kt"
FN_START, FN_END = 167, 1946
PARAMS = ["parsed", "baseOptions", "fileName", "recheckOnly"]

# name -> (first, last, dedent). Every region is a CONTIGUOUS run of the body;
# the leading explanatory comments stay in the CALLER, above the call site, so
# the caller reads as a table of contents and the moved text is pure code.
REGIONS = {
    "R1_DEPRECATIONS": (196, 386, 0),
    "R2_EMIT_CONFLICTS": (388, 577, 0),
    "R3_MODULE_LIB": (579, 799, 0),
    "R4_PROJECT_SHAPE": (801, 915, 0),
    "S_SINGLE": (921, 1049, 4),
    "M_SCAN": (1208, 1477, 4),
    "M_BINDCHECK": (1479, 1529, 4),
    "M_EMIT": (1640, 1728, 4),
    "M_ORPH": (1764, 1868, 4),
}
# the two branches move WHOLE, so their own whole-function `return`s go with
# them and NO region needs a return signal (round 813's property).
BRANCHES = {"S_SINGLE": (921, 1049), "M_MULTI": (1051, 1944)}

DECL = re.compile(r"\b(?:val|var)\s+(?:\w+\s*:\s*\w+\s*=\s*)?([A-Za-z_]\w*)")
FORV = re.compile(r"\bfor\s*\(\s*\(?([A-Za-z_][\w,\s_]*)\)?\s+in\b")
LAMV = re.compile(r"\{\s*(?:\(([^)]*)\)|([A-Za-z_]\w*))\s*->")
FUNP = re.compile(r"\bfun\s+\w+\s*\(([^)]*)\)")


def decls_on(line):
    """(outer, inner) — names this line binds in the CURRENT scope, and names it
    binds in the scope it OPENS (a `for`/lambda/local-`fun` header)."""
    outer, inner = set(), set()
    for m in DECL.finditer(line):
        outer.add(m.group(1))
    for m in FORV.finditer(line):
        inner |= {p.strip() for p in m.group(1).split(",") if p.strip()}
    for m in LAMV.finditer(line):
        g = m.group(1) or m.group(2) or ""
        inner |= {p.split(":")[0].strip() for p in g.split(",") if p.strip()}
    for m in FUNP.finditer(line):
        inner |= {p.split(":")[0].strip().lstrip("*") for p in m.group(1).split(",")
                  if p.strip() and ":" in p}
    ok = lambda s: {n for n in s if n and re.match(r"^[A-Za-z_]\w*$", n)}
    return ok(outer), ok(inner)


def visible_at(sl, target):
    """Names visible at `target` — a brace-stack simulation from the function head."""
    stack = [set(PARAMS)]
    for ln in range(FN_START, target):
        s = sl[ln - 1]
        outer, inner = decls_on(s)
        # a `for`/lambda/local-`fun` header binds INTO the block it opens;
        # a val/var binds into the block current BEFORE the line's braces.
        stack[-1] |= outer
        for _ in range(s.count("{")):
            stack.append(set())
        stack[-1] |= inner
        for _ in range(s.count("}")):
            if len(stack) > 1:
                stack.pop()
    out = set()
    for sc in stack:
        out |= sc
    return out


def local_fun_spans(sl):
    """[(header, first-body-line, last-body-line)] for every local `fun` in the
    function — a `return` inside one of these is that fun's, not the caller's.
    The header may span many lines (`fun f(\\n a: X,\\n) {`), which is exactly
    what a one-line `fun`-regex on the brace-opening line cannot see."""
    spans = []
    for ln in range(FN_START + 1, FN_END + 1):
        if not re.search(r"\bfun\s+\w+\s*\(", sl[ln - 1]):
            continue
        j, par = ln, 0
        while j <= FN_END:
            par += sl[j - 1].count("(") - sl[j - 1].count(")")
            if par == 0 and "{" in sl[j - 1].split(")")[-1]:
                break
            j += 1
        depth, k = 0, j
        while k <= FN_END:
            depth += sl[k - 1].count("{") - sl[k - 1].count("}")
            if depth == 0:
                break
            k += 1
        spans.append((ln, j, k))
    return spans


FUNS = []


def escaping_jumps(sl, a, b):
    """`continue`/`break` in [a,b] whose innermost enclosing LOOP is OUTSIDE it,
    and `return`s not inside a local `fun` declared inside it (round 812:
    indentation is not evidence — the brace stack is)."""
    out = []
    stack = []
    for i in range(FN_START - 1, FN_END):
        s = sl[i]
        opened_here = []
        for ch in s:
            if ch == "{":
                stack.append(i + 1)
                opened_here.append(i + 1)
            elif ch == "}" and stack:
                stack.pop()
        if not (a <= i + 1 <= b):
            continue
        if re.search(r"(?<![@\w.])(continue|break)\b", s):
            loop = next((ln for ln in reversed(stack)
                         if re.search(r"\b(for|while)\s*\(", sl[ln - 1])), None)
            if loop is None or not (a <= loop <= b):
                out.append(("jump", i + 1, loop))
        if re.search(r"(?<![@\w.])return\b", s):
            fn = next((h for h, _, e in FUNS if a <= h and i + 1 <= e and h <= i + 1), None)
            if fn is None or not (a <= fn <= b):
                out.append(("return", i + 1, fn))
    return out


def region_free(sl, a, b):
    """Free variables of [a,b]: visible at `a`, read inside, not bound inside."""
    txt = "\n".join(sl[a - 1:b])
    inside = set()
    for ln in range(a, b + 1):
        o, i = decls_on(sl[ln - 1])
        inside |= o | i
    vis = visible_at(sl, a) - inside
    return sorted(v for v in vis
                  if re.search(r"(?<![.\w$])" + re.escape(v) + r"\b", txt))


def main():
    raw = open(PATH).read()
    st = strip(raw)
    assert len(st) == len(raw)
    rl, sl = raw.split("\n"), st.split("\n")
    assert all(len(x) == len(y) for x, y in zip(rl, sl)), "length not preserved"
    # POSITIVE CONTROLS for the stripper (round 811: blanking preserves length,
    # so a length check alone cannot see a desynchronised stripper).
    assert "fun compileParsedCore" in sl[FN_START - 1], sl[FN_START - 1]
    assert "val diagnostics" in sl[183 - 1], sl[183 - 1]
    assert "val requireOnlyOrphans" in sl[1772 - 1], sl[1772 - 1]
    assert "resolveJsonModule" not in sl[1170 - 1], sl[1170 - 1]  # a string is gone

    FUNS.extend(local_fun_spans(sl))
    print(f"function {FN_START}..{FN_END} ({FN_END - FN_START + 1} lines)\n")
    moved = 0
    for name, (a, b, ded) in REGIONS.items():
        body = sl[a - 1:b]
        bal = sum(l.count("{") - l.count("}") for l in body)
        rets = [a + i for i, l in enumerate(body)
                if re.search(r"(?<![@\w.])return\b", l)]
        conts = [a + i for i, l in enumerate(body)
                 if re.search(r"(?<![@\w.])(continue|break)\b", l)]
        indents = [len(l) - len(l.lstrip(" ")) for l in body if l.strip()]
        free = region_free(sl, a, b)
        print(f"=== {name} {a}..{b} ({b - a + 1} lines) dedent={ded} "
              f"brace-balance={bal} min-indent={min(indents)}")
        print(f"    free variables (= the parameter list): {free}")
        print(f"    return lines: {rets}")
        print(f"    continue/break lines: {conts}")
        esc = escaping_jumps(sl, a, b)
        print(f"    jumps/returns ESCAPING the region: {esc}")
        assert bal == 0, f"{name} is not brace-balanced"
        assert min(indents) >= ded, f"{name} cannot be dedented by {ded}"
        moved += b - a + 1
    print()
    for name, (a, b) in BRANCHES.items():
        rets = [a + i for i, l in enumerate(sl[a - 1:b])
                if re.search(r"(?<![@\w.])return\b", l)]
        print(f"{name} {a}..{b}: whole-function `return`s inside = {rets}")

    spans = sorted((a, b) for a, b, _ in REGIONS.values())
    for (a1, b1), (a2, b2) in zip(spans, spans[1:]):
        assert b1 < a2, (a1, b1, a2, b2)
    n = FN_END - FN_START + 1
    print(f"\nregions disjoint and in source order: OK")
    print(f"body lines {n}, moved {moved}, kept {n - moved}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

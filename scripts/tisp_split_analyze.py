#!/usr/bin/env python3
"""(JIT.1)(e) round 821 — DATA-FLOW analysis for the split of
`Checker.tryInferSingleTypeParamFromArgs` (11,930 bytecodes, 1.5x HotSpot's
8,000-byte `HugeMethodLimit`, so never JIT-compiled; the LAST method in the
census).

Every earlier target in this arc was a CONTIGUITY problem: a fat `when` arm or a
run of statements that could be lifted because nothing crossed its boundary.
This one is not. Round 820 measured why: the bytecodes are FLAT (largest 25-line
window 449 of 11,930) and the body is essentially one `for (tp in orderedTps)`
loop whose three big regions all mutate the SAME locals — `candidates`,
`tpSawAnyArg`, `mapperPairs`. So the boundaries have to be chosen from
READ/WRITE SETS and LIVENESS, not from a shape.

What this adds over round 819's `region_free`/`region_defs`:

  * `region_writes(a, b)` — the visible locals a region MUTATES, split into
    REBINDS (`x = …`, `x += …`) and CONTAINER mutations (`x.add(…)`,
    `x.addAll(…)`, `x[k] = v`). The distinction is the whole design: a rebound
    local must become a RETURN VALUE, while a mutated container can be passed
    as a parameter and keeps working by reference. Fielding either one is what
    the round-821 prompt forbids and what the arc's own gotchas warn about;
  * `region_exits(a, b)` — `return` / `continue` / `break` counts, with the
    `continue`s and `breaks` classified as targeting a loop opened INSIDE the
    region or the CALLER's loop. A region holding a caller-targeting jump cannot
    be lifted into a plain helper at all.

**THE MEASUREMENT TRAP FOR ANY TOOL POINTED AT THIS FUNCTION** (round 820, and
it is silent): `Checker.kt` exceeds 65,536 lines and a class file's
`LineNumberTable` is a `u2`, so every line number `javap` prints for this method
has WRAPPED. They report as 49559-50622 and must have 65,536 added back —
un-corrected they land inside the `companion object` and look plausible.
`--bytes` below does that correction and refuses to run if the corrected span
does not contain the function.

Run:  python3 scripts/tisp_split_analyze.py            # the three regions
      python3 scripts/tisp_split_analyze.py --bytes    # per-region bytecodes
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
import collections
import re
import subprocess
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402
from tcb_split_analyze import DECL, FUNV, bound_on  # noqa: E402

PATH = "src/commonMain/kotlin/Checker.kt"
FN_START, FN_END = 115095, 116158
PARAMS = ["sig", "args", "source", "fileName", "forReturnType"]

# The three regions, chosen from the byte measurement below.
REGIONS = [
    ("PASS1", 115196, 115511),
    ("PASS2", 115512, 115933),
    ("CONSTRAINT", 115969, 116099),
]

REBIND = re.compile(r"(?<![.\w$])([A-Za-z_]\w*)\s*(?:=[^=]|\+=|-=|\*=|/=)")
MUTATE = re.compile(r"(?<![.\w$])([A-Za-z_]\w*)\s*\.\s*"
                    r"(add|addAll|remove|removeAll|removeAt|clear|put|putAll|set|sortBy|"
                    r"sortWith|reverse|retainAll|addFirst|addLast)\s*\(")
INDEXSET = re.compile(r"(?<![.\w$])([A-Za-z_]\w*)\s*\[[^\]]*\]\s*=[^=]")
RETURN = re.compile(r"(?<![@\w.])return\b(?!@)")
JUMP = re.compile(r"(?<![@\w.])(continue|break)\b(?!@)")


def visible_locals(sl, target):
    """Locals of the function visible at line `target` — a brace-stack
    simulation, so a name bound inside a branch that has closed is not
    visible."""
    stack = [set(PARAMS)]
    for ln in range(FN_START, target):
        s = sl[ln - 1]
        outer, inner = bound_on(s)
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
    return {n for n in out if re.match(r"^[A-Za-z_]\w*$", n or "")}


def bound_inside(sl, a, b):
    inside = set()
    for ln in range(a, b + 1):
        outer, inner = bound_on(sl[ln - 1])
        inside |= outer | inner
    return inside


def region_free(sl, a, b):
    """Locals visible at `a`, referenced inside [a,b], not bound inside it —
    i.e. the READ set the helper must receive.

    Round 819's filter applies here too: a name whose EVERY occurrence is a
    named-argument LABEL (`fromObjLit = true`) is not a read, and passing it
    would be a parameter the helper never uses."""
    txt = "\n".join(sl[a - 1:b])
    vis = visible_locals(sl, a) - bound_inside(sl, a, b)
    out = []
    for v in sorted(vis):
        hits = [sl[ln - 1] for ln in range(a, b + 1)
                if re.search(r"(?<![.\w$])" + re.escape(v) + r"\b", sl[ln - 1])]
        if hits and any(not re.search(r"(?<![.\w$])" + re.escape(v) + r"\s*=(?!=)", h)
                        for h in hits):
            out.append(v)
    return out


def region_defs(sl, a, b):
    """Names declared at the region's OWN brace level that are still referenced
    after line `b` — i.e. what the helper must hand back.

    A later occurrence that is itself a DECLARATION is a different variable in a
    different scope (`var ok` inside the constraint block vs `val ok` inside the
    conflict loop), so it is reported separately as SHADOW rather than counted
    as a live-out — otherwise the analysis demands a return value for a name the
    caller never reads."""
    depth, defs = 0, set()
    for ln in range(a, b + 1):
        s = sl[ln - 1]
        if depth == 0:
            defs |= {m.group(1) for m in DECL.finditer(s)}
            defs |= {m.group(1) for m in FUNV.finditer(s)}
        depth += s.count("{") - s.count("}")
    live, shadow = [], []
    for v in sorted(defs):
        hits = [sl[ln - 1] for ln in range(b + 1, FN_END + 1)
                if re.search(r"(?<![.\w$])" + re.escape(v) + r"\b", sl[ln - 1])]
        if not hits:
            continue
        # the FIRST occurrence after the region decides: a re-declaration shadows
        # everything that follows it, a read means the caller really needs it.
        if v in bound_on(hits[0])[0] | bound_on(hits[0])[1]:
            shadow.append(v)
        else:
            live.append(v)
    return live, shadow


def region_writes(sl, a, b):
    """({rebound}, {container-mutated}) among the locals visible at `a`.

    A REBIND must become a return value; a CONTAINER mutation survives being
    passed by reference. Names bound INSIDE the region are excluded — their
    writes never cross the boundary.

    THE TRAP THIS CARRIES A CONTROL FOR: a NAMED ARGUMENT (`fileName = fileName`,
    `fromObjLit = true`) is textually identical to an assignment. It is told
    apart by PARENTHESIS DEPTH — a statement-level rebind happens at depth 0 —
    which needs char-level tracking, not a per-line regex."""
    vis = visible_locals(sl, a) - bound_inside(sl, a, b)
    rebind, mutate = set(), set()
    depth = 0
    for ln in range(a, b + 1):
        s = sl[ln - 1]
        at0 = []                       # column ranges of this line at depth 0
        run = 0 if depth == 0 else None
        for i, ch in enumerate(s):
            if ch in "([":
                if depth == 0 and run is not None:
                    at0.append((run, i))
                    run = None
                depth += 1
            elif ch in ")]":
                depth -= 1
                if depth == 0:
                    run = i + 1
        if run is not None:
            at0.append((run, len(s)))
        for m in REBIND.finditer(s):
            if m.group(1) in vis and any(lo <= m.start() < hi for lo, hi in at0):
                rebind.add(m.group(1))
        for m in list(MUTATE.finditer(s)) + list(INDEXSET.finditer(s)):
            if m.group(1) in vis:
                mutate.add(m.group(1))
    return sorted(rebind), sorted(mutate)


def region_exits(sl, a, b):
    """(returns, continues-to-caller, breaks-to-caller, jumps-kept-inside)."""
    depth, loops = 0, []
    rets = inner = outer_c = outer_b = 0
    for ln in range(a, b + 1):
        s = sl[ln - 1]
        rets += len(RETURN.findall(s))
        opens = re.search(r"(?<![.\w$])(for|while)\s*\(", s) is not None
        for m in JUMP.finditer(s):
            if loops:
                inner += 1
            elif m.group(1) == "continue":
                outer_c += 1
            else:
                outer_b += 1
        d0 = depth
        depth += s.count("{") - s.count("}")
        if opens and depth > d0:
            loops.append(d0)
        while loops and depth <= loops[-1]:
            loops.pop()
    return rets, outer_c, outer_b, inner


def per_line_bytes(cls, method, cp, lo, hi):
    """Bytecodes per SOURCE line, with the u2 `LineNumberTable` WRAP undone.

    Inlined stdlib bodies carry synthetic line numbers, which after the wrap are
    simply lines outside [lo,hi]; they are charged to the last real line seen
    before them, i.e. to their inlining call site."""
    out = subprocess.run(["javap", "-c", "-l", "-p", "-cp", cp, cls],
                         capture_output=True, text=True, check=True).stdout
    lines = out.split("\n")
    start = next(i for i, l in enumerate(lines)
                 if method + "(" in l and l.strip().endswith(";"))
    lnt, maxoff, mode, j = [], 0, None, start
    while j < len(lines):
        l = lines[j]
        if j > start and re.match(r"^  [a-zA-Z].*;\s*$", l) and method not in l:
            break
        if "LineNumberTable:" in l:
            mode = "lnt"
            j += 1
            continue
        if mode == "lnt":
            m = re.match(r"\s*line (\d+): (\d+)\s*$", l)
            if m:
                lnt.append((int(m.group(1)), int(m.group(2))))
            else:
                mode = None
        m2 = re.match(r"\s*(\d+): [a-z]", l)
        if m2:
            maxoff = max(maxoff, int(m2.group(1)))
        j += 1
    corrected = [l + 65536 for l, _ in lnt]
    if not any(lo <= c <= hi for c in corrected):
        raise SystemExit("WRAP CONTROL FAILED: no corrected line lands in the "
                         f"function span [{lo},{hi}] — check the +65536")
    lnt.sort(key=lambda t: t[1])
    per, synth, last = collections.Counter(), 0, None
    for k, (ln, off) in enumerate(lnt):
        nxt = lnt[k + 1][1] if k + 1 < len(lnt) else maxoff
        b = max(0, nxt - off)
        c = ln + 65536
        if lo <= c <= hi:
            last = c
            per[c] += b
        else:
            synth += b
            per[last if last else 0] += b
    return per, maxoff, synth


def main():
    src = open(PATH, encoding="utf-8").read()
    sl = strip(src).split("\n")
    # POSITIVE CONTROLS for the instrument, in both directions.
    assert len(strip(src)) == len(src)
    assert "fun tryInferSingleTypeParamFromArgs(" in sl[FN_START - 1], sl[FN_START - 1]
    assert '"Array"' in src and '"Array"' not in strip(src)
    o, _ = bound_on(sl[115950 - 1])            # `val nonNeverCands = candidates…`
    assert "nonNeverCands" in o and "candidates" not in o, o
    # …and the write analysis, in BOTH directions: over pass 1's loop alone
    # (where `tpSawAnyArg` is declared OUTSIDE and rebound inside) it must see
    # the rebind, must NOT call the container mutation of `candidates` one, and
    # must NOT be fooled by the named arguments `fromObjLit = true` (depth 2)
    # or `fileName = fileName` (depth 1, in the CONSTRAINT region).
    r, mu = region_writes(sl, 115204, 115511)
    assert r == ["tpSawAnyArg"], r
    assert mu == ["candidates"], mu
    assert "fileName" not in region_writes(sl, 115969, 116099)[0], \
        region_writes(sl, 115969, 116099)[0]

    if "--bytes" in sys.argv:
        per, size, synth = per_line_bytes(
            "com.xemantic.typescript.compiler.Checker",
            "tryInferSingleTypeParamFromArgs",
            "xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main", FN_START, FN_END)
        print(f"tryInferSingleTypeParamFromArgs  {size} bytecodes, "
              f"{synth} ({100.0 * synth / size:.1f}%) INLINED stdlib")
        spans = [("gate loop", 115106, 115158), ("orderedTps", 115159, 115193),
                 ("PASS1", 115196, 115511), ("PASS2", 115512, 115933),
                 ("effective", 115934, 115962), ("CONSTRAINT", 115963, 116100),
                 ("CONFLICT", 116101, 116147), ("entry rest", 115102, 115105)]
        for name, a, b in spans:
            print(f"  {a}-{b:<7}{sum(v for k, v in per.items() if a <= k <= b):7}"
                  f"  {name}")
        return 0

    for name, a, b in REGIONS:
        rets, oc, ob, inner = region_exits(sl, a, b)
        rebind, mutate = region_writes(sl, a, b)
        live, shadow = region_defs(sl, a, b)
        print(f"{name}  [{a},{b}]  {b - a + 1} lines")
        print("   READ  (free):", region_free(sl, a, b))
        print("   WRITE rebind:", rebind, " container:", mutate)
        print("   LIVE-OUT    :", live, " (re-declared later, not live:", shadow, ")")
        print(f"   EXITS       : return {rets}, continue->caller {oc}, "
              f"break->caller {ob}, jumps kept inside {inner}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""(JIT.1)(e) round 819 — free-variable analysis for the split of
`Transformer.transformToCommonJS` (28,991 bytecodes, 3.6x HotSpot's 8,000-byte
`HugeMethodLimit`, so never JIT-compiled; the largest method in the compiler).

Same scope-simulating machinery as round 818's `tcb_split_analyze.py`, with the
two additions this target needs:

  * `region_defs(a, b)` — names DECLARED at the region's own brace level and
    still read AFTER it. Those are what a helper has to hand back, and reading
    them off a measurement rather than off a diff is what stops a return value
    being forgotten (round 818's RETURN-SIGNAL seam, in advance);
  * `PARAMS` seeded with `transformToCommonJS`'s own two parameters.

Run:  python3 scripts/tcjs_split_analyze.py
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402
from tcb_split_analyze import DECL, FUNV, bound_on  # noqa: E402

PATH = "src/commonMain/kotlin/Transformer.kt"
FN_START, FN_END = 1390, 3562

PARAMS = ["statements", "originalSourceFile"]


def visible_locals(sl, target):
    """Locals of `transformToCommonJS` visible at line `target` — a brace-stack
    simulation, so a name bound inside a branch that has closed is not visible."""
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


def region_free(sl, a, b):
    """Locals visible at `a`, referenced inside [a,b], not bound inside it."""
    inside = set()
    for ln in range(a, b + 1):
        outer, inner = bound_on(sl[ln - 1])
        inside |= outer | inner
    txt = "\n".join(sl[a - 1:b])
    vis = visible_locals(sl, a) - inside
    return sorted(v for v in vis
                  if re.search(r"(?<![.\w$])" + re.escape(v) + r"\b", txt))


def region_defs(sl, a, b):
    """Names declared at the region's OWN brace level (depth 0 relative to `a`)
    that are still referenced after line `b` — i.e. what the helper must return."""
    depth, defs = 0, set()
    for ln in range(a, b + 1):
        s = sl[ln - 1]
        if depth == 0:
            defs |= {m.group(1) for m in DECL.finditer(s)}
            defs |= {m.group(1) for m in FUNV.finditer(s)}
        depth += s.count("{") - s.count("}")
    after = "\n".join(sl[b:FN_END])
    return sorted(v for v in defs
                  if re.search(r"(?<![.\w$])" + re.escape(v) + r"\b", after))


def main():
    src = open(PATH).read()
    sl = strip(src).split("\n")
    assert len(strip(src)) == len(src)
    assert "fun transformToCommonJS(" in sl[FN_START - 1], sl[FN_START - 1]
    assert '"__esModule"' in src and '"__esModule"' not in strip(src)
    # the round-818 DECL control: the name is bound, never the initialiser's
    # first token. HEAD 2886 is `val lateExportLocalName: String? = if (...)`.
    o, _ = bound_on(sl[2886 - 1])
    assert "lateExportLocalName" in o and "if" not in o, o
    for spec in sys.argv[1:]:
        name, a, b = spec.split(":")
        a, b = int(a), int(b)
        print(f"{name}  [{a},{b}]")
        print("   free:", region_free(sl, a, b))
        print("   defs:", region_defs(sl, a, b))
    return 0


if __name__ == "__main__":
    sys.exit(main())

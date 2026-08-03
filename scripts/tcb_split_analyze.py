#!/usr/bin/env python3
"""(JIT.1)(e) round 818 — free-variable analysis for the split of
`Transformer.transformClassBody` (16,233 bytecodes, over HotSpot's 8,000-byte
`HugeMethodLimit`, so never JIT-compiled).

Round 817's `transform_split_verify.region_free` was written for a function whose
locals are almost all declared without a type annotation. `transformClassBody`
is not: it holds a dozen `val x: T = <expr>` declarations, and round 817's

    \\b(?:val|var)\\s+(?:\\w+\\s*:\\s*[\\w<>?, .]+\\s*=\\s*)?([A-Za-z_]\\w*)

matches the OPTIONAL annotation group against `x: T = ` and then captures the
first token of the INITIALISER — so `val members: List<ClassElement> = if (…)`
binds the name `if`, the real name `members` is never bound, and the free set of
every region comes out both polluted and incomplete. That is the same class of
instrument error rounds 815-817 each paid for once; the fix here is to bind the
name FIRST and treat the annotation as text to skip.

Also seeds the visibility simulation with `transformClassBody`'s own PARAMETERS
(round 817's helper hard-codes `transform`'s single `sourceFile`).

Run:  python3 scripts/tcb_split_analyze.py
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402

PATH = "src/commonMain/kotlin/Transformer.kt"
FN_START, FN_END = 11375, 12695

# `transformClassBody`'s own parameters — the roots of the visibility simulation.
PARAMS = [
    "name", "typeParameters", "heritageClauses", "membersIn", "modifiers",
    "trailingVarName", "isClassExpression", "assignedName",
]

# name FIRST, annotation skipped as text — see the module doc.
DECL = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)")
DESTRUCT = re.compile(r"\b(?:val|var)\s*\(([^)]*)\)\s*=")
FORV = re.compile(r"\bfor\s*\(\s*\(?([A-Za-z_][\w,\s_]*)\)?\s+in\b")
LAMV = re.compile(r"\{\s*(?:\(([^)]*)\)|([A-Za-z_]\w*))\s*->")
FUNV = re.compile(r"\bfun\s+([A-Za-z_]\w*)\s*\(")


def bound_on(s):
    """(names bound in the ENCLOSING scope, names bound in a scope this line opens)."""
    outer = {m.group(1) for m in DECL.finditer(s)}
    outer |= {m.group(1) for m in FUNV.finditer(s)}
    for m in DESTRUCT.finditer(s):
        outer |= {p.split(":")[0].strip() for p in m.group(1).split(",") if p.strip()}
    inner = set()
    for m in FORV.finditer(s):
        inner |= {p.strip() for p in m.group(1).split(",") if p.strip()}
    for m in LAMV.finditer(s):
        g = m.group(1) or m.group(2) or ""
        inner |= {p.split(":")[0].strip() for p in g.split(",") if p.strip()}
    # a `fun f(a: T, b: U)` line binds its parameters in the body it opens
    for m in FUNV.finditer(s):
        args = s[m.end():]
        inner |= set(re.findall(r"([A-Za-z_]\w*)\s*:", args.split(")")[0]))
    return outer, inner


def visible_locals(sl, target):
    """Locals of `transformClassBody` visible at line `target` — a brace-stack
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


REGIONS = [
    ("tcbLowerAutoAccessors", 11392, 11452),
    ("tcbExtractComputedPropertyKeys", 11544, 11625),
    ("tcbAllocatePrivateState", 11712, 11813),
    ("tcbBuildInstanceInitializers", 11950, 12051),
    ("tcbBuildTransformedConstructor", 12054, 12145),
    ("tcbBuildOutputMembers", 12155, 12319),
    ("tcbCaptureClassAlias", 12372, 12430),
    ("tcbEmitAliasAndPrivateState", 12432, 12515),
    ("tcbEmitStaticFieldTrailing", 12517, 12609),
]


def main():
    src = open(PATH).read()
    sl = strip(src).split("\n")
    # POSITIVE CONTROLS on the instrument itself.
    assert len(strip(src)) == len(src)
    assert "fun transformClassBody(" in sl[FN_START - 1], sl[FN_START - 1]
    assert '"WeakMap"' in src and '"WeakMap"' not in strip(src)
    # the DECL fix, pinned: HEAD line 11400 binds `members`, never `if`
    o, _ = bound_on(sl[11400 - 1])
    assert o == {"members"}, o
    for name, a, b in REGIONS:
        print(f"{name}  [{a},{b}]")
        print("   free:", region_free(sl, a, b))
    return 0


if __name__ == "__main__":
    sys.exit(main())

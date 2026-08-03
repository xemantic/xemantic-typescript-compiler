#!/usr/bin/env python3
"""(JIT.1)(e) round 817 — verify the split of `Transformer.transform` by round
805's five checks, against the pre-split file taken from git.

  1. every moved region, re-extracted from the NEW file, is VERBATIM HEAD's run
     modulo the stated dedent (here: 0 for all seven);
  2. the new file RECONSTRUCTS from HEAD byte for byte;
  3. the line accounting closes exactly — a PARTITION of HEAD's body;
  4. the control-flow tokens are enumerated on both sides, BOUNDED to the changed
     region on both (round 815: a whole-file census measures the file);
  5. the free variables computed per region equal the helper's PARAMETER LIST,
     and every call site passes them by NAME.

THREE INSTRUMENT TRAPS THIS FILE EXISTS TO NOT REPEAT — rounds 815 and 816 each
produced FALSE failures from exactly this class of error:

  * brace matching runs on the STRING/COMMENT-STRIPPED text, or a `{` inside a
    template expression or a regex literal is counted as a block;
  * every census is bounded to the changed region, never run to EOF;
  * `return@filter` is a LABELLED return belonging to a lambda, not a
    whole-function `return`. `transform`'s alias elision contains one, so the
    naive `(?<![@\\w.])return\\b` over-counts by one on BOTH sides and would have
    made check 4 read `+6` where the truth is `+5`.

Run:  python3 scripts/transform_split_verify.py
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import subprocess
import sys

sys.path.insert(0, "scripts")
import transform_split_apply as AP  # noqa: E402
from ccet_split_analyze import strip  # noqa: E402

PATH = AP.PATH
FAILS = []

# a whole-function `return` — NOT `return@label`, which belongs to a lambda.
RETURN = re.compile(r"(?<![@\w.])return\b(?!@)")
JUMP = re.compile(r"(?<![@\w.])(continue|break)\b(?!@)")


def check(name, cond, detail=""):
    print(("  OK   " if cond else "  FAIL ") + name + (f"  {detail}" if detail else ""))
    if not cond:
        FAILS.append(name)


def fun_span(lines, name):
    """(first body line, last body line) of `private fun <name>` — 0-based,
    inclusive. Brace matching runs on the STRIPPED text."""
    lines = strip("\n".join(lines)).split("\n")
    start = next(i for i, l in enumerate(lines)
                 if l.startswith(f"    private fun {name}("))
    j, par = start, 0
    while True:
        par += lines[j].count("(") - lines[j].count(")")
        if par == 0 and "{" in lines[j].split(")")[-1]:
            break
        j += 1
    depth, k = 0, j
    while True:
        depth += lines[k].count("{") - lines[k].count("}")
        if depth == 0:
            break
        k += 1
    return j + 1, k - 1


def visible_locals(sl, target):
    """Locals of `transform` visible at line `target` — a brace-stack simulation
    from the function head, so a name bound inside a branch that has already
    closed is NOT visible (a textual matcher cannot tell)."""
    DECL = re.compile(r"\b(?:val|var)\s+(?:\w+\s*:\s*[\w<>?, .]+\s*=\s*)?([A-Za-z_]\w*)")
    FORV = re.compile(r"\bfor\s*\(\s*\(?([A-Za-z_][\w,\s_]*)\)?\s+in\b")
    LAMV = re.compile(r"\{\s*(?:\(([^)]*)\)|([A-Za-z_]\w*))\s*->")
    stack = [{"sourceFile"}]
    for ln in range(AP.FN_START, target):
        s = sl[ln - 1]
        outer = {m.group(1) for m in DECL.finditer(s)}
        inner = set()
        for m in FORV.finditer(s):
            inner |= {p.strip() for p in m.group(1).split(",") if p.strip()}
        for m in LAMV.finditer(s):
            g = m.group(1) or m.group(2) or ""
            inner |= {p.split(":")[0].strip() for p in g.split(",") if p.strip()}
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
    """Locals visible at `a`, read inside [a,b], not bound inside it."""
    DECL = re.compile(r"\b(?:val|var)\s+(?:\w+\s*:\s*[\w<>?, .]+\s*=\s*)?([A-Za-z_]\w*)")
    FORV = re.compile(r"\bfor\s*\(\s*\(?([A-Za-z_][\w,\s_]*)\)?\s+in\b")
    LAMV = re.compile(r"\{\s*(?:\(([^)]*)\)|([A-Za-z_]\w*))\s*->")
    inside = set()
    for ln in range(a, b + 1):
        s = sl[ln - 1]
        inside |= {m.group(1) for m in DECL.finditer(s)}
        for m in FORV.finditer(s):
            inside |= {p.strip() for p in m.group(1).split(",") if p.strip()}
        for m in LAMV.finditer(s):
            g = m.group(1) or m.group(2) or ""
            inside |= {p.split(":")[0].strip() for p in g.split(",") if p.strip()}
    txt = "\n".join(sl[a - 1:b])
    vis = visible_locals(sl, a) - inside
    return sorted(v for v in vis
                  if re.search(r"(?<![.\w$])" + re.escape(v) + r"\b", txt))


def main():
    head = subprocess.run(["git", "show", f"HEAD:{PATH}"],
                          capture_output=True, text=True, check=True).stdout
    new = open(PATH).read()
    hl, nl = head.split("\n"), new.split("\n")
    sl = strip(head).split("\n")
    # POSITIVE CONTROLS for the stripper: it must preserve length AND really have
    # blanked a string (round 811 — blanking preserves length, so a length check
    # alone cannot see a desynchronised stripper).
    assert len(strip(head)) == len(head)
    assert "fun transform(sourceFile" in sl[AP.FN_START - 1]
    # ... and a STRING LITERAL really is gone. `tslibNames.add` keeps the bare
    # word, so the control must name the quoted form or it fails on an identifier.
    assert '"tslib"' in head and '"tslib"' not in strip(head)

    print("1. every moved region is VERBATIM HEAD's run (modulo dedent)")
    for name, a, b, ded, _kdoc, _sig, ret in AP.HELPERS:
        lo, hi = fun_span(nl, name)
        got = nl[lo:hi + 1]
        if ret is not None:
            check(f"{name}: trailing `{ret.strip()}`", got[-1] == ret, repr(got[-1]))
            got = got[:-1]
        want = AP.dedent(hl[a - 1:b], ded)
        check(f"{name}: {b - a + 1} HEAD lines at dedent {ded}", got == want,
              "" if got == want else
              (f"first diff at +{next(i for i, (x, y) in enumerate(zip(got, want)) if x != y)}"
               if len(got) == len(want) else f"{len(got)} vs {len(want)} lines"))

    print("2. the new file RECONSTRUCTS from HEAD byte for byte")
    rebuilt = AP.build(head)
    check("reconstruction", rebuilt == new, f"{len(rebuilt)} vs {len(new)} chars")

    print("3. the line accounting closes — a PARTITION of HEAD's body")
    claim = {}

    def claim_range(a, b, what):
        for ln in range(a, b + 1):
            assert ln not in claim, (ln, what, claim[ln])
            claim[ln] = what

    ln = AP.FN_START
    for name, a, b, *_ in AP.HELPERS:
        claim_range(ln, a - 1, f"kept before {name}")
        claim_range(a, b, f"moved -> {name}")
        ln = b + 1
    claim_range(ln, AP.FN_END, "kept: the tail")
    body = AP.FN_END - AP.FN_START + 1
    check("every HEAD body line claimed exactly once", len(claim) == body,
          f"{len(claim)} of {body}")
    moved = sum(1 for v in claim.values() if v.startswith("moved"))
    kept = body - moved
    entry_lo, entry_hi = fun_span(nl, "transform") if False else (0, 0)
    check("the moved bulk is the majority of the body", moved > kept,
          f"moved {moved}, kept {kept}")

    print("4. control-flow tokens, BOUNDED to the changed region on both sides")
    hregion = strip("\n".join(hl[AP.FN_START - 1:AP.FN_END]))
    last = max(fun_span(nl, h[0])[1] for h in AP.HELPERS)
    nregion = strip("\n".join(nl[AP.FN_START - 1:last + 1]))
    added = sum(1 for h in AP.HELPERS if h[6] is not None)
    for tok, rx, delta in (("return", RETURN, added), ("continue/break", JUMP, 0)):
        h = len(rx.findall(hregion))
        n = len(rx.findall(nregion))
        check(f"`{tok}`: HEAD {h} + {delta} == new {n}", n == h + delta, f"new {n}")
    # the labelled return really is present, i.e. the RETURN regex is doing work
    check("positive control: HEAD holds a `return@` the regex must NOT count",
          "return@filter" in hregion and len(RETURN.findall("return@filter")) == 0)

    print("5. free variables == the helper parameter list, passed by NAME")
    for name, a, b, _ded, _kdoc, sig, _ret in AP.HELPERS:
        free = set(region_free(sl, a, b))
        params = set(re.findall(r"^\s{8}(\w+):", sig, re.M))
        check(f"{name}: free set == parameters", free == params,
              f"free-params {sorted(free - params)} params-free {sorted(params - free)}")
        lines_ = AP.CALLS[name]
        # the ARGUMENT lines only — the head line may be `val x = fn(`, and
        # counting `x` as a named argument is round 816's own false failure.
        named = {m for l in lines_[1:] for m in re.findall(r"^\s+(\w+) = ", l)}
        check(f"{name}: call site names every parameter", named == params,
              f"{sorted(named)} vs {sorted(params)}")

    print()
    if FAILS:
        print(f"FAILED: {len(FAILS)} -> {FAILS}")
        return 1
    print("all five checks green")
    return 0


if __name__ == "__main__":
    sys.exit(main())

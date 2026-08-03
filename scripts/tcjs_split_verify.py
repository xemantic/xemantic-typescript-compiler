#!/usr/bin/env python3
"""(JIT.1)(e) round 819 — verify the split of `Transformer.transformToCommonJS`
by round 805's five checks, against the pre-split file taken from git.

  1. every moved region, re-extracted from the NEW file, is VERBATIM HEAD's run
     modulo the stated dedent (0 for the twelve body-level regions, 12 for the
     five plain `when` arms, 8 for the two arms that move inside a ONE-ITERATION
     FRAME);
  2. the new file RECONSTRUCTS from HEAD byte for byte;
  3. the line accounting closes exactly — a PARTITION of HEAD's body;
  4. the control-flow tokens are enumerated on both sides, BOUNDED to the
     changed region on both. `continue`/`break` must be EQUAL, which is the whole
     point of the frame: six `continue`s that targeted the caller's loop still
     target a loop, and every other one moved with its own nested loop;
  5. the free variables computed per region equal the helper's PARAMETER LIST
     plus its declared PROLOGUE, and every call site passes every parameter by
     NAME — 19 helpers and 128 arguments, of which 74 are same-typed mutable
     containers a POSITIONAL call could permute and still type-check.

Plus one check this target needs and no earlier one did: 6. the frame really is
a one-iteration frame, and it is present on exactly the regions that hold a
`continue` targeting the CALLER's loop — measured, not asserted by hand.

The instrument traps inherited from rounds 815-818 (the stripper's length
control, `DECL` binding the name rather than the initialiser's first token, and
named arguments that look like reads) all carry POSITIVE CONTROLS below.

Run:  python3 scripts/tcjs_split_verify.py
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import subprocess
import sys

sys.path.insert(0, "scripts")
import tcjs_split_apply as AP  # noqa: E402
import tcjs_split_analyze as AN  # noqa: E402
from tcb_split_analyze import bound_on  # noqa: E402
from tcb_split_verify import fun_span, named_arg  # noqa: E402
from ccet_split_analyze import strip  # noqa: E402

PATH = AP.PATH
FAILS = []

RETURN = re.compile(r"(?<![@\w.])return\b(?!@)")
JUMP = re.compile(r"(?<![@\w.])(continue|break)\b(?!@)")
FRAME = re.compile(r"^        for \((\w+) in listOf\((\w+)\)\) \{$")


def check(name, cond, detail=""):
    print(("  OK   " if cond else "  FAIL ") + name + (f"  {detail}" if detail else ""))
    if not cond:
        FAILS.append(name)


def region_reads(sl, a, b):
    """`tcjs_split_analyze.region_free`, minus the names whose only occurrences
    are named-argument/assignment shaped (`name = syntheticId(x)`)."""
    out = []
    for v in AN.region_free(sl, a, b):
        hits = [sl[ln - 1] for ln in range(a, b + 1)
                if re.search(r"(?<![.\w$])" + re.escape(v) + r"\b", sl[ln - 1])]
        if any(not named_arg(v, h) for h in hits):
            out.append(v)
    return sorted(out)


def outer_continues(sl, a, b):
    """`continue`s inside [a,b] that are NOT enclosed by a loop opened inside
    [a,b] — i.e. the ones that targeted the CALLER's loop. Depth is tracked on
    the STRIPPED text, and a loop header is remembered by the brace depth it
    opens at."""
    depth, loops, n = 0, [], 0
    for ln in range(a, b + 1):
        s = sl[ln - 1]
        opens = re.search(r"(?<![.\w$])(for|while)\s*\(", s) is not None
        for m in JUMP.finditer(s):
            if m.group(1) == "continue" and not loops:
                n += 1
        d0 = depth
        depth += s.count("{") - s.count("}")
        if opens and depth > d0:
            loops.append(d0)
        while loops and depth <= loops[-1]:
            loops.pop()
    return n


def main():
    head = subprocess.run(["git", "show", f"HEAD:{PATH}"],
                          capture_output=True, text=True, check=True).stdout
    new = open(PATH).read()
    hl, nl = head.split("\n"), new.split("\n")
    sl = strip(head).split("\n")

    # POSITIVE CONTROLS: the stripper preserves length AND really blanks a
    # string (blanking preserves length, so a length check alone is blind).
    assert len(strip(head)) == len(head)
    assert "fun transformToCommonJS(" in sl[AP.FN_START - 1]
    assert '"__esModule"' in head and '"__esModule"' not in strip(head)
    # ... the DECL matcher binds the NAME, not the initialiser's first token ...
    o, _ = bound_on(sl[2886 - 1])
    assert "lateExportLocalName" in o and "if" not in o, o
    # ... the named-argument filter, in BOTH directions ...
    assert named_arg("name", "            name = syntheticId(localName),")
    assert not named_arg("name", "            val n = stmt.name.text")
    # ... and `outer_continues` sees a caller-loop `continue` and not a nested one.
    assert outer_continues(sl, 2379, 2631) == 5, outer_continues(sl, 2379, 2631)
    assert outer_continues(sl, 2635, 2837) == 0, outer_continues(sl, 2635, 2837)

    print("1. every moved region is VERBATIM HEAD's run (modulo dedent)")
    for h in AP.HELPERS:
        lo, hi = fun_span(nl, h["name"])
        got = nl[lo:hi + 1]
        pre, post = h.get("pre", []), AP.tail(h)
        if pre:
            check(f"{h['name']}: {len(pre)} prologue line(s)", got[:len(pre)] == pre,
                  repr(got[:len(pre)]))
            got = got[len(pre):]
        if post:
            check(f"{h['name']}: {len(post)} tail line(s)", got[-len(post):] == post,
                  repr(got[-len(post):]))
            got = got[:-len(post)]
        want = AP.dedent(hl[h["a"] - 1:h["b"]], h["ded"])
        check(f"{h['name']}: {h['b'] - h['a'] + 1} HEAD lines at dedent {h['ded']}",
              got == want,
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
    for h in AP.HELPERS:
        claim_range(ln, h["a"] - 1, f"kept before {h['name']}")
        claim_range(h["a"], h["b"], f"moved -> {h['name']}")
        ln = h["b"] + 1
    claim_range(ln, AP.FN_END, "kept: the tail")
    body = AP.FN_END - AP.FN_START + 1
    check("every HEAD body line claimed exactly once", len(claim) == body,
          f"{len(claim)} of {body}")
    moved = sum(1 for v in claim.values() if v.startswith("moved"))
    check("the moved bulk is the majority of the body", moved > body - moved,
          f"moved {moved}, kept {body - moved}")
    check("nothing is dropped: kept + moved == body",
          moved + sum(1 for v in claim.values() if v.startswith("kept")) == body)

    print("4. control-flow tokens, BOUNDED to the changed region on both sides")
    hregion = strip("\n".join(hl[AP.FN_START - 1:AP.FN_END]))
    last = max(fun_span(nl, h["name"])[1] for h in AP.HELPERS)
    nregion = strip("\n".join(nl[AP.FN_START - 1:last + 2]))
    added = sum(1 for h in AP.HELPERS if h.get("ret"))
    for tok, rx, delta in (("return", RETURN, added), ("continue/break", JUMP, 0)):
        a = len(rx.findall(hregion))
        b = len(rx.findall(nregion))
        check(f"`{tok}`: HEAD {a} + {delta} == new {b}", b == a + delta, f"new {b}")
    check("positive control: HEAD's body holds `continue`s at all",
          len(JUMP.findall(hregion)) >= 5, f"{len(JUMP.findall(hregion))}")

    print("5. free variables == parameters + prologue, passed by NAME")
    for h in AP.HELPERS:
        free = set(region_reads(sl, h["a"], h["b"]))
        params = {p for p, _t, _a in h["params"]}
        pre = h.get("pre", [])
        prologue = set()
        for l in pre:
            outer, inner = bound_on(l)
            prologue |= outer | inner
        by_prologue = {p for p in params
                       if any(re.search(r"(?<![.\w$])" + re.escape(p) + r"\b", l) for l in pre)}
        used = (free - prologue) | by_prologue
        check(f"{h['name']}: reads == parameters + prologue", used == params,
              f"free-params {sorted(used - params)} params-free {sorted(params - used)}")
        named = {m for l in AP.call(h) for m in re.findall(r"^\s+(\w+) = \S", l)
                 if not l.lstrip().startswith("val ")}
        check(f"{h['name']}: call site names every parameter", named >= params,
              f"{sorted(params - named)} missing")

    print("6. the ONE-ITERATION FRAME is on exactly the regions that need it")
    for h in AP.HELPERS:
        outer = outer_continues(sl, h["a"], h["b"])
        framed = [l for l in h.get("pre", []) if FRAME.match(l)]
        check(f"{h['name']}: {outer} caller-loop `continue`(s) -> "
              f"{'framed' if framed else 'no frame'}",
              (outer > 0) == bool(framed), f"{outer} vs {len(framed)}")
        if framed:
            m = FRAME.match(framed[0])
            check(f"{h['name']}: frame iterates ONE element, bound to `{m.group(1)}`",
                  m.group(2) in {p for p, _t, _a in h["params"]} and
                  h.get("post") == ["        }"] and framed[0] == h["pre"][-1])
    total = sum(outer_continues(sl, h["a"], h["b"]) for h in AP.HELPERS)
    check("all six caller-loop `continue`s are inside a frame", total == 6, f"{total}")

    print()
    args = sum(len(h["params"]) for h in AP.HELPERS)
    print(f"{len(AP.HELPERS)} helpers, {args} arguments")
    if FAILS:
        print(f"FAILED: {len(FAILS)} -> {FAILS}")
        return 1
    print("all six checks green")
    return 0


if __name__ == "__main__":
    sys.exit(main())

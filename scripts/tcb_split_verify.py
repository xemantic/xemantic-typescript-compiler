#!/usr/bin/env python3
"""(JIT.1)(e) round 818 — verify the split of `Transformer.transformClassBody` by
round 805's five checks, against the pre-split file taken from git.

  1. every moved region, re-extracted from the NEW file, is VERBATIM HEAD's run
     modulo the stated dedent (0 for the six body-level regions, 4 for the three
     that lived inside the static-trailing `if`);
  2. the new file RECONSTRUCTS from HEAD byte for byte;
  3. the line accounting closes exactly — a PARTITION of HEAD's body — and the
     ONE line that is not simply kept or moved (the lifted local data class) is
     named, checked to occur once, and checked to be present in its new form;
  4. the control-flow tokens are enumerated on both sides, BOUNDED to the changed
     region on both (round 815: a whole-file census measures the file);
  5. the free variables computed per region equal the helper's PARAMETER LIST
     plus its declared PROLOGUE, and every call site passes every parameter by
     NAME.

THE INSTRUMENT TRAP THIS FILE EXISTS TO NOT REPEAT — rounds 815, 816 and 817 each
produced a FALSE failure from an unbounded or unsanitised instrument, and this
target has two more of the same family:

  * round 817's free-variable matcher binds `val x: T = <expr>` to the first
    token of the INITIALISER, so on this function it bound `if` and never bound
    `members` (see `tcb_split_analyze.py`); and
  * a NAMED ARGUMENT (`name = ...`, `initializer = ...`, `modifiers = ...`)
    looks exactly like a read of the same-named local. `transformClassBody` has
    parameters called `name` and `modifiers`, and the AST constructors it calls
    take arguments of those names — so an unfiltered matcher reports every
    region as capturing `name`. Both filters carry a POSITIVE CONTROL below, so
    the sanitising cannot silently start over-filtering instead.

Run:  python3 scripts/tcb_split_verify.py
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import subprocess
import sys

sys.path.insert(0, "scripts")
import tcb_split_apply as AP  # noqa: E402
import tcb_split_analyze as AN  # noqa: E402
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


def named_arg(v, line):
    """True when every occurrence of `v` on this line is a NAMED ARGUMENT (or an
    assignment) rather than a read — `name = syntheticId(x)` vs `name.text`."""
    return bool(re.search(r"(?<![.\w$])" + re.escape(v) + r"\s*=(?!=)", line))


def region_reads(sl, a, b):
    """`tcb_split_analyze.region_free`, minus the names whose only occurrences
    are named-argument/assignment shaped."""
    out = []
    for v in AN.region_free(sl, a, b):
        hits = [sl[ln - 1] for ln in range(a, b + 1)
                if re.search(r"(?<![.\w$])" + re.escape(v) + r"\b", sl[ln - 1])]
        if any(not named_arg(v, h) for h in hits):
            out.append(v)
    return sorted(out)


def main():
    head = subprocess.run(["git", "show", f"HEAD:{PATH}"],
                          capture_output=True, text=True, check=True).stdout
    new = open(PATH).read()
    hl, nl = head.split("\n"), new.split("\n")
    sl = strip(head).split("\n")

    # POSITIVE CONTROLS for the stripper: it must preserve length AND really have
    # blanked a string (round 811 — blanking preserves length, so a length check
    # alone cannot see a desynchronised stripper). The quoted form is named, not
    # the bare word: `WeakMap` also appears as an identifier (round 817's trap).
    assert len(strip(head)) == len(head)
    assert "fun transformClassBody(" in sl[AP.FN_START - 1]
    assert '"WeakMap"' in head and '"WeakMap"' not in strip(head)
    # ... and for the two sanitising filters, in BOTH directions.
    assert AN.bound_on(sl[11400 - 1])[0] == {"members"}, "DECL binds the name, not the RHS"
    assert named_arg("name", "            name = syntheticId(storageName),")
    assert not named_arg("name", "                    replaceIdentifierInStmt(it, name.text, tv)")

    print("1. every moved region is VERBATIM HEAD's run (modulo dedent)")
    for name, a, b, ded, _kdoc, _sig, pre, ret in AP.HELPERS:
        lo, hi = fun_span(nl, name)
        got = nl[lo:hi + 1]
        if pre:
            check(f"{name}: {len(pre)} prologue line(s)", got[:len(pre)] == pre, repr(got[:len(pre)]))
            got = got[len(pre):]
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
    check("the moved bulk is the majority of the body", moved > body - moved,
          f"moved {moved}, kept {body - moved}")
    # the ONE line that is neither kept nor moved: the lifted local data class.
    check("the local data class occurs exactly once in HEAD's body",
          "\n".join(hl[AP.FN_START - 1:AP.FN_END]).count(AP.LOCAL_PFI) == 1)
    check("it is GONE from the new file at body indent", AP.LOCAL_PFI not in nl)
    check("... and present as a private nested class",
          "    private data class PrivateFieldInfo(" in new)

    print("4. control-flow tokens, BOUNDED to the changed region on both sides")
    hregion = strip("\n".join(hl[AP.FN_START - 1:AP.FN_END]))
    last = max(fun_span(nl, h[0])[1] for h in AP.HELPERS)
    nregion = strip("\n".join(nl[AP.FN_START - 1:last + 2]))
    added = sum(1 for h in AP.HELPERS if h[7] is not None)
    for tok, rx, delta in (("return", RETURN, added), ("continue/break", JUMP, 0)):
        h = len(rx.findall(hregion))
        n = len(rx.findall(nregion))
        check(f"`{tok}`: HEAD {h} + {delta} == new {n}", n == h + delta, f"new {n}")
    # the region really does hold the `return`s the count is about
    check("positive control: HEAD's body holds at least one `return`",
          len(RETURN.findall(hregion)) >= 2)

    print("5. free variables == parameters + prologue, passed by NAME")
    for name, a, b, _ded, _kdoc, sig, pre, _ret in AP.HELPERS:
        free = set(region_reads(sl, a, b))
        params = set(re.findall(r"^\s{8}(\w+):", sig, re.M))
        prologue = {m for l in pre for m in re.findall(r"\b(?:val|var)\s+(\w+)", l)}
        # a parameter may be consumed by a PROLOGUE line instead of by the moved
        # region itself (`var finalHeritage = heritageIn`) — that still makes it
        # used exactly once, so it counts, but nothing else is let through.
        by_prologue = {p for p in params
                       if any(re.search(r"(?<![.\w$])" + re.escape(p) + r"\b", l)
                              for l in pre)}
        used = (free - prologue) | by_prologue
        check(f"{name}: reads == parameters + prologue", used == params,
              f"free-params {sorted(used - params)} params-free {sorted(params - used)}")
        lines_ = AP.CALLS[name]
        # the ARGUMENT lines only — the head line may be `val x = fn(`, and
        # counting `x` as a named argument is round 816's own false failure.
        named = {m for l in lines_ for m in re.findall(r"^\s+(\w+) = \S", l)
                 if not l.lstrip().startswith("val ")}
        check(f"{name}: call site names every parameter", named >= params,
              f"{sorted(params - named)} missing")

    print()
    if FAILS:
        print(f"FAILED: {len(FAILS)} -> {FAILS}")
        return 1
    print("all five checks green")
    return 0


if __name__ == "__main__":
    sys.exit(main())

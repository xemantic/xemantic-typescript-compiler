#!/usr/bin/env python3
"""(JIT.1)(e) round 816 — verify the split of `compileParsedCore` by round 805's
five checks, against the pre-split file taken from git.

  1. every moved region, re-extracted from the NEW file, is VERBATIM HEAD's run
     modulo the stated dedent;
  2. the new file RECONSTRUCTS from HEAD byte for byte (the apply step is a pure
     function of HEAD, so the working tree cannot have drifted);
  3. the line accounting closes exactly;
  4. the control-flow tokens are enumerated on both sides — **bounded to the
     changed region on both**, which is round 815's lesson: a whole-file census
     measures the file, not the change;
  5. the free variables computed per region equal the helper's PARAMETER LIST,
     and every call site passes them by NAME.

Run:  python3 scripts/cpc_split_verify.py
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import subprocess
import sys

sys.path.insert(0, "scripts")
import cpc_split_analyze as AN  # noqa: E402
import cpc_split_apply as AP  # noqa: E402

PATH = AP.PATH
FAILS = []


def check(name, cond, detail=""):
    print(("  OK   " if cond else "  FAIL ") + name + (f"  {detail}" if detail else ""))
    if not cond:
        FAILS.append(name)


def fun_span(lines, name):
    """(first body line, last body line) of `private fun <name>` — 0-based,
    inclusive. Brace matching runs on the STRING/COMMENT-STRIPPED text: a `{`
    inside a template expression or a regex literal is not a block (this is what
    made the first run of this check read 1,018 lines for a 422-line body)."""
    lines = AN.strip("\n".join(lines)).split("\n")
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


def main():
    head = subprocess.run(["git", "show", f"HEAD:{PATH}"],
                          capture_output=True, text=True, check=True).stdout
    new = open(PATH).read()
    hl, nl = head.split("\n"), new.split("\n")

    print("1. every moved region is VERBATIM HEAD's run (modulo dedent)")
    for name, a, b, ded, _kdoc, _sig, tail in AP.HELPERS:
        lo, hi = fun_span(nl, name)
        got = nl[lo:hi + 1]
        if tail:
            check(f"{name}: trailing signal line", got[-len(tail):] == tail,
                  repr(got[-1]))
            got = got[:-len(tail)]
        if name == "cpcCompileMultiFile":
            # the arm minus its own four sub-regions, plus their call sites
            want, ln = [], a
            for sub in AP.IN_MULTI:
                sa, sb = next((h[1], h[2]) for h in AP.HELPERS if h[0] == sub)
                want += AP.dedent(hl[ln - 1:sa - 1], 4)
                want += AP.dedent(AP.CALLS[sub], 4)
                ln = sb + 1
            want += AP.dedent(hl[ln - 1:b], 4)
        else:
            want = AP.dedent(hl[a - 1:b], ded)
        check(f"{name}: {b - a + 1} HEAD lines at dedent {ded}", got == want,
              "" if got == want else
              f"first diff at +{next(i for i, (x, y) in enumerate(zip(got, want)) if x != y)}"
              if len(got) == len(want) else f"{len(got)} vs {len(want)} lines")

    print("2. the new file RECONSTRUCTS from HEAD byte for byte")
    rebuilt = AP.build(head)
    check("reconstruction", rebuilt == new,
          f"{len(rebuilt)} vs {len(new)} chars")

    print("3. the line accounting closes — a PARTITION of HEAD's body, exhaustive"
          " and disjoint")
    claim = {}

    def claim_range(a, b, what):
        for ln in range(a, b + 1):
            assert ln not in claim, (ln, what, claim[ln])
            claim[ln] = what

    claim_range(AP.FN_START, 195, "entry: signature and the option head")
    claim_range(916, 919, "entry: the paths diagnostics")
    for name, a, b, *_ in AP.HELPERS:
        if name in AP.IN_MULTI:
            continue  # inside cpcCompileMultiFile's range, claimed by it
        claim_range(a, b, f"moved -> {name}")
    for ln in (387, 578, 800):
        claim_range(ln, ln, "dropped separator blank")
    for ln in (920, 1050, 1945, AP.FN_END):
        claim_range(ln, ln, "replaced by the dispatch")
    body = AP.FN_END - AP.FN_START + 1
    check("every HEAD body line claimed exactly once", len(claim) == body,
          f"{len(claim)} of {body}")
    check("the dropped lines really are blank",
          all(hl[ln - 1].strip() == "" for ln in (387, 578, 800)))
    moved = sum(1 for v in claim.values() if v.startswith("moved"))
    entry_lo, entry_hi = fun_span(nl, "compileParsedCore")
    check("the entry is small", entry_hi - entry_lo + 1 < 70,
          f"{entry_hi - entry_lo + 1} lines, moved {moved}, kept 33")

    print("4. control-flow tokens, BOUNDED to the changed region on both sides")
    hregion = AN.strip("\n".join(hl[AP.FN_START - 1:AP.FN_END]))
    last = max(fun_span(nl, h[0])[1] for h in AP.HELPERS)
    nregion = AN.strip("\n".join(nl[AP.FN_START - 1:last + 1]))
    # the four `return`s the split ADDS: two dispatch arms, `return checker`,
    # `return requireOnlyOrphans`.
    for tok, added in (("return", 4), ("continue", 0), ("break", 0)):
        h = len(re.findall(r"(?<![@\w.])" + tok + r"\b", hregion))
        n = len(re.findall(r"(?<![@\w.])" + tok + r"\b", nregion))
        check(f"`{tok}`: HEAD {h} + {added} == new {n}", n == h + added)

    print("5. free variables == the helper parameter list, passed by NAME")
    st = AN.strip(head).split("\n")
    AN.FUNS.extend(AN.local_fun_spans(st))
    for name, a, b, _ded, _kdoc, sig, _tail in AP.HELPERS:
        if name in ("cpcCompileSingleFile", "cpcCompileMultiFile"):
            continue  # a whole arm: its parameters are the entry's own
        free = set(AN.region_free(st, a, b))
        params = set(re.findall(r"^\s{8}(\w+):", sig, re.M))
        check(f"{name}: free set == parameters", free == params,
              f"free-params {sorted(free - params)} params-free {sorted(params - free)}")
        if name in AP.CALLS:
            # the ARGUMENT lines only: the head line may be `val x = fn(`, and
            # counting `x` as a named argument is the same unbounded-region error
            # round 815 recorded (here it is caught by the check disagreeing).
            lines_ = AP.CALLS[name]
            head_ = re.sub(r"^\s*val \w+ = ", "", lines_[0])
            call = "\n".join([head_] + lines_[1:])
            named = {m for m in re.findall(r"(\w+)\s*=\s*\w", call)}
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

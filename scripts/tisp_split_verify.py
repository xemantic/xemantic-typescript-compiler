#!/usr/bin/env python3
"""(JIT.1)(e) round 821 — verify the split of
`Checker.tryInferSingleTypeParamFromArgs` by round 805's five checks, against
the pre-split file taken from git.

  1. every moved region, re-extracted from the NEW file, is VERBATIM HEAD's run
     modulo the stated dedent (4 for the two pass regions, 8 for the constraint
     block);
  2. the new file RECONSTRUCTS from HEAD byte for byte — un-applied by pulling
     each helper's body back to its call site, not by re-running the applier,
     which is what proves nothing ELSE in the file moved;
  3. the line accounting closes exactly — a PARTITION of HEAD's body;
  4. the control-flow tokens are enumerated on both sides, bounded to the
     changed regions on both. Here `return` must be EQUAL (all 22 were
     whole-function bails and remain `return null` in a `Boolean?` helper), and
     `continue`/`break` must be equal AND must all target a loop inside their
     own region — a caller-targeting jump would make a plain helper illegal;
  5. the free variables computed per region equal the helper's PARAMETER LIST,
     and the one REBOUND local (`tpSawAnyArg`) is a RETURN VALUE, not a
     parameter and not a field. Every call site passes every argument BY NAME,
     which matters more here than in any earlier target: `source`/`fileName`
     are both `String?` and `constraint`/`firstWidened` are both `Type`, so a
     positional permutation would type-check and be wrong.

Plus two this target needs and no earlier one did:

  6. the hoisted `Candidate` data class carries HEAD's parameter list unchanged
     (it is the only thing in this round that is not a pure move), and is
     referenced only from the entry and the three helpers;
  7. every helper is called EXACTLY ONCE.

Run:  python3 scripts/tisp_split_verify.py
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
import re
import subprocess
import sys

sys.path.insert(0, "scripts")
import tisp_split_apply as AP  # noqa: E402
import tisp_split_analyze as AN  # noqa: E402
from ccet_split_analyze import strip  # noqa: E402
from tcb_split_verify import fun_span  # noqa: E402

FAILS = []
RETURN = re.compile(r"(?<![@\w.])return\b(?!@)")
JUMP = re.compile(r"(?<![@\w.])(continue|break)\b(?!@)")
MAPPER_PAIRS = "        val mapperPairs = mutableListOf<Pair<Type.TypeParam, Type>>()"


def check(name, cond, detail=""):
    print(("  OK   " if cond else "  FAIL ") + name + (f"  {detail}" if detail else ""))
    if not cond:
        FAILS.append(name)


def tokens(lines):
    t = "\n".join(lines)
    return len(RETURN.findall(t)), len(JUMP.findall(t))


def main():
    head = subprocess.run(["git", "show", f"HEAD:{AP.PATH}"],
                          capture_output=True, text=True, check=True).stdout
    new = open(AP.PATH, encoding="utf-8").read()
    hl, nl = head.split("\n"), new.split("\n")
    AP.locate(hl)                      # resolves and CHECKS every anchor

    # POSITIVE CONTROLS for the instruments, in both directions.
    assert len(strip(head)) == len(head)
    assert '"Array"' in head and '"Array"' not in strip(head)
    assert tokens(["return null", "// return x"]) == (2, 0)      # sees returns…
    assert tokens(["return@any false"]) == (0, 0)                # …not labelled ones
    lo, hi = fun_span(nl, "tispCheckConstraint")
    assert "Round 729" in nl[lo], nl[lo]

    print("1. every moved region is VERBATIM HEAD's run (modulo dedent)")
    bodies = {}
    for h in AP.HELPERS:
        lo, hi = fun_span(nl, h["name"])
        got = nl[lo:hi + 1]
        tail = h["tail"][:1]           # `fun_span` stops BEFORE the closing brace
        check(f"{h['name']}: tail is {tail}", got[-len(tail):] == tail, repr(got[-2:]))
        moved = got[:-len(tail)]
        bodies[h["name"]] = moved
        want = hl[h["a"]:h["b"] + 1]
        reind = [(" " * h["dedent"] + l if l.strip() else l) for l in moved]
        check(f"{h['name']}: {len(want)} line(s) verbatim at dedent {h['dedent']}",
              reind == want,
              "" if reind == want else
              f"first diff at {next(i for i, (x, y) in enumerate(zip(reind, want)) if x != y)}")

    print("2. the new file RECONSTRUCTS from HEAD byte for byte")
    rec = list(nl)
    # a. pull each helper's body back to its call site, and delete the helper
    for h in sorted(AP.HELPERS, key=lambda x: -x["a"]):
        call = h["call"]
        idx = [i for i in range(len(rec)) if rec[i:i + len(call)] == call]
        assert len(idx) == 1, (h["name"], idx)
        reind = [(" " * h["dedent"] + l if l.strip() else l) for l in bodies[h["name"]]]
        rec[idx[0]:idx[0] + len(call)] = reind
    for h in AP.HELPERS:
        lo, hi = fun_span(rec, h["name"])
        start = lo - len(h["sig"]) - len(h["doc"])
        end = hi + 2                       # closing brace + the blank line after
        assert rec[start] == h["doc"][0], repr(rec[start])
        assert rec[end] == "", repr(rec[end])
        del rec[start:end + 1]
    # b. un-hoist the data class
    decl = AP.CANDIDATE_HOIST.index(AP.CANDIDATE_HOIST[-2])
    i = rec.index(AP.CANDIDATE_HOIST[-2]) - decl     # `    /**` is not unique
    assert rec[i:i + len(AP.CANDIDATE_HOIST)] == AP.CANDIDATE_HOIST, rec[i:i + 3]
    del rec[i:i + len(AP.CANDIDATE_HOIST)]
    j = rec.index(MAPPER_PAIRS)
    rec[j:j] = [AP.CANDIDATE]
    check("byte-for-byte", "\n".join(rec) == head,
          f"{len(head)} chars head, {len('\n'.join(rec))} chars reconstructed")

    print("3. the line accounting closes exactly (a PARTITION of HEAD's body)")
    removed = sum(h["b"] - h["a"] + 1 for h in AP.HELPERS) + 1     # +1 data class
    added = sum(len(h["call"]) for h in AP.HELPERS)
    delta = len(nl) - len(hl)
    expect = (-removed + added
              + sum(len(h["doc"]) + len(h["sig"]) + (h["b"] - h["a"] + 1) + len(h["tail"])
                    for h in AP.HELPERS)
              + len(AP.CANDIDATE_HOIST))
    check(f"{removed} removed, {added} call lines, net {delta:+}", delta == expect,
          f"expected {expect:+}")
    spans = sorted((h["a"], h["b"]) for h in AP.HELPERS)
    check("regions disjoint and inside the function",
          all(b1 < a2 for (_, b1), (a2, _) in zip(spans, spans[1:]))
          and spans[0][0] > 115094 and spans[-1][1] < 116158)

    print("4. control flow: tokens equal, and every jump stays inside its region")
    for h in AP.HELPERS:
        hr, hj = tokens(hl[h["a"]:h["b"] + 1])
        nr, nj = tokens(bodies[h["name"]])
        check(f"{h['name']}: {hr} return / {hj} continue|break preserved",
              (hr, hj) == (nr, nj), f"new {nr}/{nj}")
    sl = strip(head).split("\n")
    for name, a, b in AN.REGIONS:
        rets, oc, ob, inner = AN.region_exits(sl, a, b)
        check(f"{name}: 0 caller-targeting jumps ({inner} kept inside)",
              oc == 0 and ob == 0, f"continue {oc}, break {ob}")
    check("each call site adds exactly one `?: return null`",
          all(h["call"][-1].strip().endswith("?: return null") for h in AP.HELPERS))

    print("5. free variables == the helper's parameter list; the rebind is RETURNED")
    for h, (name, a, b) in zip(AP.HELPERS, AN.REGIONS):
        free = set(AN.region_free(sl, a, b))
        par = {l.strip().split(":")[0] for l in h["sig"][1:-1]}
        check(f"{h['name']}: params == free vars {sorted(free)}", par == free,
              f"params {sorted(par)}")
        rebind, mutate = AN.region_writes(sl, a, b)
        check(f"{h['name']}: no rebind of a caller local", rebind == [],
              str(rebind))
        live, _ = AN.region_defs(sl, a, b)
        want_ret = h["tail"][0].strip().split()[1]
        check(f"{h['name']}: live-out {live} handed back as `{want_ret}`",
              (live == [want_ret]) or (live == [] and want_ret == "true"))
        # every argument passed BY NAME, and every parameter passed exactly once
        named = {m.group(1) for l in h["call"]
                 for m in [re.fullmatch(r"\s*(\w+) = \w+,", l)] if m}
        check(f"{h['name']}: all {len(par)} arguments passed by NAME", named == par,
              f"named {sorted(named)}")

    print("6. the hoisted `Candidate` carries HEAD's parameter list unchanged")
    old = AP.CANDIDATE.strip()
    got = AP.CANDIDATE_HOIST[-2].strip()
    check("declaration identical modulo `private`", got == "private " + old,
          repr(got))
    code = [i + 1 for i, l in enumerate(nl)
            if re.search(r"(?<![.\w$])Candidate\b", l)
            and not l.lstrip().startswith(("//", "*", "/*"))]
    lo = next(i for i, l in enumerate(nl) if l == AP.FN_HEAD) + 1
    hi = fun_span(nl, AP.HELPERS[-1]["name"])[1] + 2
    check(f"every CODE reference ({len(code)}) is inside the entry + the split block",
          all(lo <= i <= hi for i in code),
          str([i for i in code if not lo <= i <= hi]))

    print("7. every helper is called EXACTLY ONCE")
    for h in AP.HELPERS:
        n = sum(len(re.findall(r"(?<![.\w$])" + h["name"] + r"\b", l)) for l in nl
                if not l.lstrip().startswith(("//", "*", "/*")))
        check(f"{h['name']}: {n} CODE occurrences (declaration + one call)", n == 2)

    print(("\nALL CHECKS PASS" if not FAILS else f"\n{len(FAILS)} FAILED: {FAILS}"))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())

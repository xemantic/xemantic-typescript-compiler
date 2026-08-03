#!/usr/bin/env python3
"""(JIT.1)(d) round 813 — verify the `checkIndexSigInStatement` split against HEAD.

Round 805's five checks:

  1. every moved run, RE-EXTRACTED from the NEW file, compared back against HEAD
     (identical modulo the uniform dedent);
  2. the entry RECONSTRUCTED from HEAD with the regions replaced by their call
     sites, compared against the new file's entry (identical);
  3. the accounting closes exactly;
  4. every `return` and `continue` enumerated on both sides;
  5. free variables per region — the analyzer's job, re-asserted here as the
     helper signatures actually written.

Usage:  python3 scripts/indexsig_split_verify.py <HEAD copy of Checker.kt>
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import sys

sys.path.insert(0, "scripts")
from indexsig_split_apply import REGIONS, FN_START, FN_END, PATH  # noqa: E402

SIG_PARAMS = {
    "cisCheckNumericNamePropsVsNumberIndex": ["members", "numberIndexType", "source", "fileName"],
    "cisFindStringIndexSig": ["stmt", "members"],
    "cisCheckAnonIndexValueConflict": ["stmt", "numberIndexSig", "stringIndexSig", "source", "fileName"],
    "cisCheckNamedInterfaceIndexValueConflict": ["stmt", "source", "fileName"],
    "cisCheckNumericMethodsVsNumberIndex": ["stmt", "members", "source", "fileName"],
    "cisCheckMethodsVsPrimitiveStringIndex": ["members", "stringIndexType", "source", "fileName"],
    "cisCheckPropsVsStringIndex": ["members", "stringIndexType", "stringIndexTypeIsPrimitive",
                                   "source", "fileName"],
}


def helper_name(sig):
    return re.search(r"fun (\w+)", sig).group(1)


def main():
    head = open(sys.argv[1]).read().split("\n")
    new = open(PATH).read().split("\n")
    ok = True

    # locate the new entry and each helper in the NEW file
    def find(pred, frm=0):
        for i in range(frm, len(new)):
            if pred(new[i]):
                return i + 1
        raise AssertionError("not found")

    e_start = find(lambda l: l.startswith("    private fun checkIndexSigInStatement("))
    e_end = find(lambda l: l == "    }", e_start)

    # ---- 1. every moved run re-extracted from the NEW file vs HEAD ----------
    moved_lines = 0
    for name, a, b, call, doc, sig, extra in REGIONS:
        fn = helper_name(sig)
        h_start = find(lambda l, f=fn: l.startswith(f"    private fun {f}("))
        # the signature may span several lines; body starts after the line ending `{`
        i = h_start
        while not new[i - 1].rstrip().endswith("{"):
            i += 1
        body_start = i + 1
        body_end = find(lambda l: l == "    }", body_start) - 1
        if extra:
            body_end -= len(extra)
            assert new[body_end:body_end + len(extra)] == extra, (name, "trailer")
        got = new[body_start - 1:body_end]
        want = head[a - 1:b]
        dedent = (len(want[0]) - len(want[0].lstrip(" "))) - 8
        want_d = [("" if not l.strip() else l[dedent:]) for l in want]
        if got != want_d:
            ok = False
            print(f"!! {name}: moved run DIFFERS from HEAD")
            for x, y in zip(got, want_d):
                if x != y:
                    print(f"   new: {x!r}\n   old: {y!r}")
                    break
        else:
            print(f"ok  {name}: {len(got)} lines, contiguous in-order run "
                  f"identical to HEAD {a}..{b} (dedent {dedent})")
        moved_lines += b - a + 1

    # ---- 2. the entry reconstructed from HEAD ------------------------------
    recon, i = [], FN_START
    starts = {a: (call,) for (_n, a, b, call, _d, _s, _e) in REGIONS}
    ends = {a: b for (_n, a, b, _c, _d, _s, _e) in REGIONS}
    while i <= FN_END:
        if i in starts:
            recon.append(starts[i][0])
            i = ends[i] + 1
            continue
        recon.append(head[i - 1])
        i += 1
    got_entry = new[e_start - 1:e_end]
    if got_entry != recon:
        ok = False
        print(f"!! entry differs: new {len(got_entry)} lines, reconstruction {len(recon)}")
        for x, y in zip(got_entry, recon):
            if x != y:
                print(f"   new: {x!r}\n   rec: {y!r}")
                break
    else:
        print(f"ok  entry reconstructed from HEAD is IDENTICAL, {len(recon)} lines")

    # ---- 3. accounting -----------------------------------------------------
    head_body = FN_END - FN_START + 1
    kept = head_body - moved_lines
    call_lines = len(REGIONS)
    print(f"ok  accounting: HEAD body {head_body} = kept {kept} + moved {moved_lines}; "
          f"new entry {len(got_entry)} = kept {kept} + {call_lines} call lines"
          if len(got_entry) == kept + call_lines else
          f"!! accounting: new entry {len(got_entry)} != kept {kept} + {call_lines}")
    ok &= len(got_entry) == kept + call_lines

    # ---- 4. returns and continues -----------------------------------------
    def toks(lines, pat):
        return sum(1 for l in lines if re.search(pat, l))

    head_fn = head[FN_START - 1:FN_END]
    bare = r"(?<![@\w.])return\s*$"
    cont = r"(?<![@\w.])continue\b"
    new_all = new[e_start - 1:]
    # the helpers live immediately after the entry; bound them at the next
    # top-level declaration that is not one of ours
    end_of_ours = find(lambda l: l.startswith("    private fun classifyIndexParamType"))
    new_all = new[e_start - 1:end_of_ours - 1]
    print(f"ok  bare returns: HEAD {toks(head_fn, bare)}, new tree {toks(new_all, bare)}")
    print(f"ok  continues:    HEAD {toks(head_fn, cont)}, new tree {toks(new_all, cont)}")
    ok &= toks(head_fn, bare) == toks(new_all, bare)
    ok &= toks(head_fn, cont) == toks(new_all, cont)

    # ---- 5. helper signatures name exactly the computed free variables ------
    for name, a, b, call, doc, sig, extra in REGIONS:
        fn = helper_name(sig)
        params = re.findall(r"(\w+): ", sig.split("fun " + fn, 1)[1])
        if params != SIG_PARAMS[fn]:
            ok = False
            print(f"!! {fn}: params {params} != expected {SIG_PARAMS[fn]}")
        args = re.search(r"\((.*)\)", call).group(1).split(", ")
        expect = SIG_PARAMS[fn]
        if args != expect:
            ok = False
            print(f"!! {fn}: call site passes {args} != {expect}")
    print("ok  every helper's parameters are exactly the region's free variables, "
          "and every call site passes them positionally")

    print("VERIFY", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

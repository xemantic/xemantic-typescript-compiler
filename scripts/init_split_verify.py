#!/usr/bin/env python3
"""(JIT.1)(d) round 814 — verify the `Checker` constructor split against HEAD.

Round 805's five checks, adapted to a target whose body is a pure ORDERED
SEQUENCE:

  1. every moved run, RE-EXTRACTED from the NEW file, compared back against HEAD
     (identical modulo the uniform dedent);
  2. the entry RECONSTRUCTED from HEAD with the regions replaced by their call
     sites, compared against the new file's `init` block (identical);
  3. the accounting closes exactly;
  4. `return`/`break`/`continue` enumerated on both sides (HEAD's `init` body has
     ZERO at body level, so the new tree must too);
  5. free variables per region — there are NONE, so every helper takes no
     parameters, and the ORDER of the call sites is asserted to be the source
     order of the regions.

Usage:  python3 scripts/init_split_verify.py <HEAD copy of Checker.kt>
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import sys

sys.path.insert(0, "scripts")
from init_split_apply import REGIONS, FN_START, FN_END, PATH, call_site  # noqa: E402


def main():
    head = open(sys.argv[1]).read().split("\n")
    new = open(PATH).read().split("\n")
    ok = True

    def find(pred, frm=0):
        for i in range(frm, len(new)):
            if pred(new[i]):
                return i + 1
        raise AssertionError("not found")

    e_start = find(lambda l: l == "    init {")
    e_end = find(lambda l: l == "    }", e_start)

    # ---- 1. every moved run re-extracted from the NEW file vs HEAD ----------
    moved_lines = 0
    for name, a, b, fn, _what in REGIONS:
        h_start = find(lambda l, f=fn: l == f"    private fun {f}() {{")
        body_start = h_start + 1
        body_end = find(lambda l: l == "    }", body_start) - 1
        got = new[body_start - 1:body_end]
        want = head[a - 1:b]
        first = next(l for l in want if l.strip())
        dedent = (len(first) - len(first.lstrip(" "))) - 8
        want_d = [("" if not l.strip() else l[dedent:]) for l in want]
        if got != want_d:
            ok = False
            print(f"!! {name}: moved run DIFFERS from HEAD")
            for x, y in zip(got, want_d):
                if x != y:
                    print(f"   new: {x!r}\n   old: {y!r}")
                    break
        else:
            print(f"ok  {name} -> {fn}: {len(got)} lines, contiguous in-order run "
                  f"identical to HEAD {a}..{b} (dedent {dedent})")
        moved_lines += b - a + 1

    # ---- 2. the entry reconstructed from HEAD ------------------------------
    recon, i = [], FN_START
    starts = {a: r for r in REGIONS for a in (r[1],)}
    while i <= FN_END:
        if i in starts:
            name, a, b, fn, _w = starts[i]
            recon.append(call_site(name, fn))
            i = b + 1
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
    good = len(got_entry) == kept + call_lines
    ok &= good
    print(("ok  accounting: HEAD init %d = kept %d + moved %d; new entry %d = kept %d + %d call lines"
           % (head_body, kept, moved_lines, len(got_entry), kept, call_lines)) if good else
          ("!! accounting: new entry %d != kept %d + %d" % (len(got_entry), kept, call_lines)))

    # ---- 4. returns / breaks / continues at BODY level ----------------------
    # The whole `init` body has none; a split that invented one would be a
    # behaviour change (a `return` inside a helper would skip the rest of ITS
    # run, which the constructor could not express at all).
    ctrl = r"(?<![@\w.])(return|break|continue)\s*$"
    h_ctrl = [FN_START + i for i, l in enumerate(head[FN_START - 1:FN_END])
              if re.search(ctrl, l)]
    last = find(lambda l: l == f"    private fun {REGIONS[-1][3]}() {{")
    last_end = find(lambda l: l == "    }", last)
    n_ctrl = [e_start + i for i, l in enumerate(new[e_start - 1:last_end])
              if re.search(ctrl, l)]
    print(f"ok  bare return/break/continue: HEAD {len(h_ctrl)}, new tree {len(n_ctrl)}")
    ok &= len(h_ctrl) == len(n_ctrl)

    # ---- 5. no parameters anywhere, and the call order is the source order --
    for name, a, b, fn, _w in REGIONS:
        sig = new[find(lambda l, f=fn: l == f"    private fun {f}() {{") - 1]
        if "()" not in sig:
            ok = False
            print(f"!! {fn}: takes parameters — the split has a cross-boundary value")
    calls = [l.strip() for l in got_entry if re.match(r"\s+initSetupPasses\(\)|"
                                                      r"\s+initDeclarationOnlyPasses\(\)|"
                                                      r"\s+initCheckPasses\d\(\)", l)]
    want_calls = [f"{r[3]}()" for r in REGIONS]
    if calls != want_calls:
        ok = False
        print(f"!! call order {calls} != source order {want_calls}")
    else:
        print("ok  every helper is parameterless (no cross-boundary values) and the "
              f"{len(calls)} call sites are in the regions' source order")

    print("VERIFY", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

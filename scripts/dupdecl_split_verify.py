#!/usr/bin/env python3
"""(JIT.1)(d) round 812 — round 805's five equivalence checks for the split of
`checkDuplicateDeclarations`.

  1. every moved region re-extracted from the NEW file is a CONTIGUOUS, IN-ORDER
     run of HEAD, identical modulo the dedent and the `continue` -> `return true`
     rewrite;
  2. the entry RECONSTRUCTED from HEAD — regions replaced by their call sites and
     the hoisted `data class DeclInfo` line removed — is IDENTICAL to the new one;
  3. the line accounting closes exactly;
  4. every `return` and every `continue` enumerated, on both sides;
  5. free variables per region (in dupdecl_split_analyze.py).

Usage:  python3 scripts/dupdecl_split_verify.py <copy-of-Checker.kt-at-HEAD>
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
import re
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402
from dupdecl_split_apply import (  # noqa: E402
    REGIONS, CALLS, DECLINFO_LINE, FN_END, FN_HEAD, rewrite,
)

PATH = "src/commonMain/kotlin/Checker.kt"
NAMES = {
    "I": "cddCheckImportBindings",
    "E": "cddCheckMergedEnums",
    "G": "cddCheckMergedTypeParameters",
    "X": "cddCheckExportUniformity",
    "V": "cddCheckValueRedeclarations",
}
TAILS = {"X": "        return emitted2395", "V": "        return false"}


def read(path):
    return open(path).read().split("\n")


def fn_range(lines, name):
    start = next(i for i, l in enumerate(lines)
                 if l.startswith(f"    private fun {name}("))
    end = next(i for i in range(start + 1, len(lines)) if lines[i] == "    }")
    return start + 1, end + 1  # 1-based inclusive


def main():
    head = read(sys.argv[1])
    new = read(PATH)
    st_head = strip("\n".join(head)).split("\n")
    ok = True

    # ---- 1. each moved region is a verbatim run of HEAD ---------------------
    for key, a, b, dedent, continues, tail in REGIONS:
        fa, fb = fn_range(new, NAMES[key])
        hdr = next(i for i in range(fa, fb) if new[i - 1].rstrip().endswith("{"))
        body = new[hdr:fb - 1]
        if tail:
            assert body[-1] == TAILS[key], body[-1]
            body = body[:-1]
        expected = rewrite(head[a - 1:b], st_head[a - 1:b], a, dedent, set(continues))
        same = body == expected
        print(f"[1] {NAMES[key]}: body {len(body)} lines, verbatim run of HEAD "
              f"{a}..{b} -> {'IDENTICAL' if same else 'DIFFERS'}")
        if not same:
            ok = False
            for i, (x, y) in enumerate(zip(body, expected)):
                if x != y:
                    print("   first diff at", i, repr(x), repr(y))
                    break

    # ---- 2. entry reconstruction -------------------------------------------
    ha, hb = FN_HEAD, FN_END
    na, nb = fn_range(new, "checkDuplicateDeclarations")
    reg = {a: b for _, a, b, _, _, _ in REGIONS}
    call = {a: CALLS[k] for k, a, b, _, _, _ in REGIONS}
    recon = []
    i = ha
    while i <= hb:
        if i == DECLINFO_LINE:
            i += 1
            continue
        if i in reg:
            recon.extend(call[i].rstrip("\n").split("\n"))
            i = reg[i] + 1
            continue
        recon.append(head[i - 1])
        i += 1
    actual = new[na - 1:nb]
    same = recon == actual
    print(f"[2] entry reconstruction: {len(recon)} vs {len(actual)} lines -> "
          f"{'IDENTICAL' if same else 'DIFFERS'}")
    if not same:
        ok = False
        for i, (x, y) in enumerate(zip(recon, actual)):
            if x != y:
                print("   first diff at", i, repr(x), repr(y))
                break

    # ---- 3. accounting -----------------------------------------------------
    head_body = hb - ha + 1
    moved = sum(b - a + 1 for _, a, b, _, _, _ in REGIONS)
    call_lines = sum(len(CALLS[k].rstrip("\n").split("\n")) for k, *_ in REGIONS)
    kept = head_body - moved - 1  # -1: the hoisted DeclInfo declaration
    got = nb - na + 1
    print(f"[3] accounting: HEAD entry {head_body} = kept {kept} + moved {moved} "
          f"+ 1 hoisted; new entry {got} = kept {kept} + call sites {call_lines} "
          f"-> {'OK' if kept + call_lines == got else 'MISMATCH'}")
    if kept + call_lines != got:
        ok = False

    # ---- 4. return / continue census ---------------------------------------
    def census(lines, a, b, pat):
        s = strip("\n".join(lines)).split("\n")
        return [a + i for i, l in enumerate(s[a - 1:b]) if re.search(pat, l)]

    bare_ret = r"(?<![@\w.])return\s*$"
    any_cont = r"(?<![@\w.])continue\b"
    print(f"[4] HEAD entry: bare returns {len(census(head, ha, hb, bare_ret))}, "
          f"continues {len(census(head, ha, hb, any_cont))}")
    print(f"    new entry : bare returns {len(census(new, na, nb, bare_ret))}, "
          f"continues {len(census(new, na, nb, any_cont))}")
    tot_cont = len(census(new, na, nb, any_cont))
    tot_true = 0
    for key in NAMES:
        fa, fb = fn_range(new, NAMES[key])
        c = len(census(new, fa, fb, any_cont))
        t = len(census(new, fa, fb, r"(?<![@\w.])return true\s*$"))
        tot_cont += c
        tot_true += t
        print(f"    {NAMES[key]}: continues {c}, `return true` {t}, "
              f"bare returns {len(census(new, fa, fb, bare_ret))}")
    head_cont = len(census(head, ha, hb, any_cont))
    # the V call site adds ONE `continue` the monolith did not have: it is the
    # replay of the seven signals, not an eighth exit.
    replay = sum(1 for k, *_ in REGIONS if "continue" in CALLS[k])
    print(f"    continue accounting: HEAD {head_cont} = new total {tot_cont} "
          f"- replay {replay} + signals {tot_true} -> "
          f"{'OK' if head_cont == tot_cont - replay + tot_true else 'MISMATCH'}")
    if head_cont != tot_cont - replay + tot_true:
        ok = False

    print("\nRESULT:", "ALL CHECKS PASS" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

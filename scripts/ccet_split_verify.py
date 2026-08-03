#!/usr/bin/env python3
"""(JIT.1)(c) round 811 — round 805's five equivalence checks for the split of
`checkSingleCallExpressionTypesCore`.

  1. every moved region re-extracted from the NEW file is a CONTIGUOUS, IN-ORDER
     run of HEAD, identical modulo the dedent and the return-signal rewrite;
  2. the entry function RECONSTRUCTED from HEAD with the regions replaced by
     their call sites is IDENTICAL to the new entry;
  3. the line accounting closes exactly;
  4. every `return` enumerated;
  5. free variables computed per region (done in ccet_split_analyze.py).
"""
import re
import subprocess
import sys

sys.path.insert(0, "scripts")
from ccet_split_analyze import strip  # noqa: E402
from ccet_split_apply import REGIONS, CALLS, rewrite_returns  # noqa: E402

PATH = "src/commonMain/kotlin/Checker.kt"
HEAD_COPY = sys.argv[1] if len(sys.argv) > 1 else None


def read(path):
    return open(path).read().split("\n")


def fn_range(lines, name):
    start = next(i for i, l in enumerate(lines)
                 if l.startswith(f"    private fun {name}("))
    end = next(i for i in range(start + 1, len(lines)) if lines[i] == "    }")
    return start + 1, end + 1  # 1-based inclusive


def main():
    head = read(HEAD_COPY)
    new = read(PATH)
    ok = True

    # ---- 1. each moved region is a verbatim run of HEAD ---------------------
    names = {"P": "ccetPrologueWalkers", "U": "ccetUnionCalleeChecks",
             "N": "ccetNoCallSignatureDiagnostics", "T": "ccetExplicitTypeArguments"}
    st_head = strip("\n".join(head)).split("\n")
    for name, a, b, dedent, signal in REGIONS:
        fa, fb = fn_range(new, names[name])
        # helper body = after the `) {` / `{` header line, before the closing `}`
        hdr = next(i for i in range(fa, fb) if new[i - 1].rstrip().endswith("{"))
        body = new[hdr:fb - 1]
        if signal:
            assert body[-1] == "        return false", body[-1]
            body = body[:-1]
        expected = rewrite_returns(head[a - 1:b], st_head[a - 1:b], dedent, signal)
        same = body == expected
        print(f"[1] {names[name]}: body {len(body)} lines, verbatim run of HEAD "
              f"{a}..{b} -> {'IDENTICAL' if same else 'DIFFERS'}")
        if not same:
            ok = False
            for i, (x, y) in enumerate(zip(body, expected)):
                if x != y:
                    print("   first diff at", i, repr(x), repr(y))
                    break

    # ---- 2. entry reconstruction -------------------------------------------
    ha, hb = fn_range(head, "checkSingleCallExpressionTypesCore")
    na, nb = fn_range(new, "checkSingleCallExpressionTypesCore")
    recon = []
    i = ha
    reg = {a: b for _, a, b, _, _ in REGIONS}
    call = {a: CALLS[n] for n, a, b, _, _ in REGIONS}
    while i <= hb:
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
    moved = sum(b - a + 1 for _, a, b, _, _ in REGIONS)
    call_lines = sum(len(CALLS[n].rstrip("\n").split("\n")) for n, *_ in REGIONS)
    kept = head_body - moved
    print(f"[3] accounting: HEAD entry {head_body} = kept {kept} + moved {moved}; "
          f"new entry {nb - na + 1} = kept {kept} + call sites {call_lines} -> "
          f"{'OK' if kept + call_lines == nb - na + 1 else 'MISMATCH'}")
    if kept + call_lines != nb - na + 1:
        ok = False

    # ---- 4. return census --------------------------------------------------
    def bares(lines, a, b):
        s = strip("\n".join(lines)).split("\n")
        return [a + i for i, l in enumerate(s[a - 1:b])
                if re.search(r"(?<![@\w.])return\s*$", l)]

    def signals(lines, a, b):
        s = strip("\n".join(lines)).split("\n")
        return [a + i for i, l in enumerate(s[a - 1:b])
                if re.search(r"(?<![@\w.])return true\s*$", l)]

    hb_bare = bares(head, ha, hb)
    print(f"[4] HEAD entry bare returns: {len(hb_bare)}")
    print(f"    new entry bare returns : {len(bares(new, na, nb))}")
    for name in names.values():
        fa, fb = fn_range(new, name)
        print(f"    {name}: bare {len(bares(new, fa, fb))}, "
              f"`return true` {len(signals(new, fa, fb))}")

    print("\nRESULT:", "ALL CHECKS PASS" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

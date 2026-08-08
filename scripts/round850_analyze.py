#!/usr/bin/env python3
"""Round 850 — reduce `scripts/round849-warm-sections.sh 2`'s logs to the three
WARM intra-handler tables (`cta` / `cpa` / `arg`).

Three things this does that a cold reduction did not have to:

  * it prices the probe boundary WARM and DIFFERENTIALLY (round 734: never by an
    empty-span loop, and — new this round — never by inheriting a cold `net`
    column, which oversubtracts by 2.4-5.2x);
  * it computes each level's boundary count FROM THE CSV (sum of closes), so the
    ON and COARSE arms are compared on counts neither report had to agree on;
  * it flags every row whose raw ns/call is BELOW the boundary price as
    UNRESOLVED rather than printing a negative `net` as if it were a cost.

Usage: python3 scripts/round850_analyze.py [outdir]
"""
import glob
import json
import os
import re
import sys
from collections import defaultdict

OUT = sys.argv[1] if len(sys.argv) > 1 else "build/bench/round849"

HDR = re.compile(r'^\{"instrumented":true,.*\}$')
ROW3 = re.compile(r'^"(.*)",(\d+),(\d+)(?:,(\d+))?$')          # cta / arg csv
ROW4 = re.compile(r'^([A-Z]+),"(.*)",(\d+),(\d+)$')            # cpa csv (level col)

# Rows that are NESTED inside another row of the same level, or are pure
# censuses with no nanos — they must never enter a level total.
def nested(level, name):
    return (name.startswith("of which") or name.startswith("- its")
            or name.startswith("narrow ") or name.startswith("argType of")
            or name.endswith("(probe-only)") or name.startswith("probe boundary")
            or level in ("ARM", "DEFER", "RETRY", "URETRY")
            or name.startswith("D arm:"))


def level_of(name, col):
    if col:
        return col
    m = re.match(r'^([A-E]): ', name)
    return m.group(1) if m else "-"


def parse(path):
    runs, cur, in_csv = [], None, False
    for line in open(path):
        line = line.rstrip("\n")
        if HDR.match(line):
            cur = json.loads(line)
            cur["rows"] = []
            runs.append(cur)
            in_csv = False
            continue
        if cur is None:
            continue
        if line.startswith("== ") and line.endswith(" csv =="):
            in_csv = True
            continue
        if line.startswith("== ") and line.endswith(" csv end =="):
            in_csv = False
            continue
        if not in_csv:
            continue
        m = ROW4.match(line)
        if m:
            cur["rows"].append((m.group(1), m.group(2), int(m.group(3)), int(m.group(4))))
            continue
        m = ROW3.match(line)
        if m:
            nm = m.group(1)
            cur["rows"].append((level_of(nm, None), nm, int(m.group(2)), int(m.group(3))))
    return runs


def mean(xs):
    return sum(xs) / len(xs)


def main():
    runs = []
    for p in sorted(glob.glob(os.path.join(OUT, "warm-cta*.log"))) + \
             sorted(glob.glob(os.path.join(OUT, "warm-cpaarg*.log"))):
        for r in parse(p):
            r["log"] = os.path.basename(p)
            runs.append(r)

    by_tier = defaultdict(list)
    for r in runs:
        by_tier[r["tier"]].append(r)

    meds = {}
    for log in sorted({r["log"] for r in runs}):
        meds[log] = [r["medianMs"] for r in runs if r["log"] == log][0]
    denom = mean(list(meds.values()))
    print("== probe-free warm medians (the denominator for every % below) ==")
    for k, v in meds.items():
        print(f"  {k:<18} {v:8.1f} ms")
    print(f"  MEAN = {denom:.1f} ms   (spread {min(meds.values()):.0f}-{max(meds.values()):.0f})")

    print("\n== instrumented runs (overheadMs = the probe's own price on that rebuild) ==")
    for r in runs:
        print(f"  {r['log']:<18} run{r['run']} {r['tier']:<10} ms={r['ms']:8.1f} "
              f"overhead={r['overheadMs']:+8.1f} files={r['files']} errors={r['errors']}")

    for base, coarse, label in (
            ("cta", "ctacoarse", "(TYPE.2)  spineCtaM3StatementAnchor / checkVarDeclAssignability"),
            ("cpa", "cpacoarse", "(ENGINE.2) cpaSpineLeave / checkPropertyAccessInExpr"),
            ("arg", "argcoarse", "(CALL.2)  checkArgumentsAgainstSignature")):
        on, co = by_tier[base], by_tier[coarse]
        if not on or not co:
            continue
        print(f"\n\n{'#' * 78}\n### {label}\n### ON draws {len(on)}   COARSE draws {len(co)}")

        def agg(rs):
            """{(level,name): (closes, mean_ms, min_ms, max_ms)} over draws."""
            acc = defaultdict(list)
            closes = {}
            for r in rs:
                for lv, nm, c, ns in r["rows"]:
                    acc[(lv, nm)].append(ns / 1e6)
                    closes[(lv, nm)] = c
            return {k: (closes[k], mean(v), min(v), max(v)) for k, v in acc.items()}

        A, C = agg(on), agg(co)

        # ── boundary price: ON-vs-COARSE differential, per level ──────────────
        # Only the PARTITION levels carry a boundary each; `N` (cpa's nested
        # sub-measures), `REXIT` (its exit census) and the arm/verifier censuses
        # are inside them and must not be counted as levels.
        PARTITION_LEVELS = {"A", "B", "C", "D", "E", "P", "Q", "R", "-"}

        def lvl_tot(t):
            d = defaultdict(lambda: [0, 0.0])
            for (lv, nm), (c, m, _, _) in t.items():
                if nested(lv, nm) or lv not in PARTITION_LEVELS:
                    continue
                d[lv][0] += c
                d[lv][1] += m
            return d

        LA, LC = lvl_tot(A), lvl_tot(C)
        # NESTING. These partitions are LAYERED — cta's B/C/D/E open inside level
        # A's rows, cpa's Q inside P and R inside Q — so an extra boundary at a
        # DEEPER level executes inside the shallower level's spans and inflates
        # them too. Summing every level's delta and dividing by every level's
        # extra boundaries therefore counts a deep boundary once per level above
        # it and OVERSTATES the price (215 vs 127 ns for cpa). The outermost
        # level's own delta, divided by ALL extra boundaries, is the estimator
        # that counts each boundary exactly once; every leaf level (one that
        # contains no other) gives an independent direct reading.
        print("\n-- WARM boundary, ON-minus-COARSE differential (round 734) --")
        print(f"{'level':<7}{'ON ms':>9}{'ON bnd':>12}{'CO ms':>9}{'CO bnd':>11}"
              f"{'d ms':>8}{'d bnd':>11}{'ns/bnd':>9}")
        tn = tb = cn = cb = 0.0
        for lv in sorted(LA):
            if lv not in LC:
                continue
            ab, am = LA[lv]
            cbb, cm = LC[lv]
            d_ms, d_b = am - cm, ab - cbb
            tn += am; tb += ab; cn += cm; cb += cbb
            ns = d_ms * 1e6 / d_b if d_b else float("nan")
            print(f"{lv:<7}{am:9.0f}{ab:12,}{cm:9.0f}{cbb:11,}{d_ms:8.0f}{d_b:11,}{ns:9.0f}")
        naive = (tn - cn) * 1e6 / (tb - cb)
        print(f"{'SUM':<7}{tn:9.0f}{tb:12,.0f}{cn:9.0f}{cb:11,.0f}"
              f"{tn - cn:8.0f}{tb - cb:11,.0f}{naive:9.0f}  <- OVERSTATES (double-counts nesting)")
        outer = sorted(LA)[0]
        d_outer = LA[outer][1] - LC[outer][1]
        B = d_outer * 1e6 / (tb - cb)
        print(f"  NESTING-AWARE: outermost level '{outer}' delta {d_outer:.0f} ms over ALL "
              f"{tb - cb:,.0f} extra boundaries = {B:.0f} ns/boundary  <- USED BELOW")
        oh_on, oh_co = mean([r["overheadMs"] for r in on]), mean([r["overheadMs"] for r in co])
        print(f"  cross-check, BenchMain overheadMs: ON {oh_on:+.0f} / COARSE {oh_co:+.0f} ms "
              f"-> {(oh_on - oh_co) * 1e6 / (tb - cb):.0f} ns/boundary (whole-run, noisy)")
        # The level totals below must subtract every boundary INSIDE the level,
        # not only the level's own rows — the same nesting correction.
        # Which levels open INSIDE which: cta's B/C/D/E are siblings inside A's
        # rows (B in A_VDECL, C in A_RETURN, D in A_WALKFN, E in A_ASSIGN);
        # cpa's are a straight chain P > Q > R.
        if base == "cta":
            contains = {lv: (set(LA) if lv == "A" else {lv}) for lv in LA}
        else:
            contains = {lv: {l for l in LA if l >= lv} for lv in LA}
        inner = {lv: sum(LA[l][0] for l in contains[lv] if l in LA) for lv in LA}
        innerC = {lv: sum(LC[l][0] for l in contains[lv] if l in LC) for lv in LC}

        # ── level totals, three ways ─────────────────────────────────────────
        print(f"\n-- level totals (ms). `net` subtracts EVERY boundary inside the level; "
              f"the COARSE column, having ~2.5x fewer, is the better estimate --")
        print(f"{'level':<7}{'ON raw':>9}{'ON net':>9}{'CO raw':>9}{'CO net':>9}"
              f"{'CO net % warm':>15}")
        for lv in sorted(LA):
            if lv not in LC:
                continue
            am, cm = LA[lv][1], LC[lv][1]
            an, cn2 = am - inner[lv] * B / 1e6, cm - innerC[lv] * B / 1e6
            print(f"{lv:<7}{am:9.0f}{an:9.0f}{cm:9.0f}{cn2:9.0f}{cn2 / denom * 100:14.2f}%")

        # ── the rows ─────────────────────────────────────────────────────────
        print(f"\n-- rows, mean of {len(on)} ON draws; boundary {B:.0f} ns; "
              f"UNRESOLVED = raw ns/call below the boundary --")
        print(f"{'lvl':<4}{'section':<55}{'raw ms':>8}{'net ms':>8}{'%warm':>7}"
              f"{'closes':>10}{'ns/call':>10}{'draw spread':>13}  flag")
        for (lv, nm), (c, m, lo, hi) in sorted(A.items(), key=lambda kv: (kv[0][0], -kv[1][1])):
            each = m * 1e6 / c if c else 0.0
            net = m - c * B / 1e6
            spread = (hi - lo) / m * 100 if m else 0.0
            flags = []
            if nested(lv, nm):
                flags.append("nested")
            if each < B:
                flags.append("UNRESOLVED")
            if spread > 30 and m > 5:
                flags.append("noisy")
            print(f"{lv:<4}{nm[:54]:<55}{m:8.1f}{net:8.1f}{net / denom * 100:7.2f}"
                  f"{c:10,}{each:10.0f}{lo:6.1f}-{hi:<6.1f}  {','.join(flags)}")


if __name__ == "__main__":
    main()

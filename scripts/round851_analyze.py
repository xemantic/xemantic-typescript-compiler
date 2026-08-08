#!/usr/bin/env python3
"""Round 851 — reduce `scripts/round851-warm-call.sh`'s logs to the WARM
intra-function table of `checkSingleCallExpressionTypes` (the `call` probe).

Carries round 850's three calibration rules, and adds the exit census:

  * the probe boundary is priced by an ON-vs-COARSE DIFFERENTIAL, never by an
    empty-span loop and never inherited from a cold table (a cold `net` column
    over-subtracts a warm row by 2.5-5x);
  * this partition is FLAT (one level), so the naive estimator and the
    nesting-aware one coincide — which is stated rather than assumed, by
    printing both;
  * a row whose raw ns/call is BELOW the boundary price is UNRESOLVED, not free.

Usage: python3 scripts/round851_analyze.py [outdir]
"""
import glob
import json
import os
import re
import sys
from collections import defaultdict

OUT = sys.argv[1] if len(sys.argv) > 1 else "build/bench/round851"

HDR = re.compile(r'^\{"instrumented":true,.*\}$')
ROW = re.compile(r'^"(.*)",(\d+),(\d+)$')

# The partition is `names[0..FIRST_NESTED)`; everything the report calls a
# nested sub-measure starts with two spaces in `names`, which the csv trims —
# so they are recognised by prefix instead.
NESTED_PREFIX = ("of which", "wrapper transition", "probe boundary", "(2g)")
CENSUS_PREFIX = ("exitPro:", "exitCallee:", "exitEmit:")


def is_nested(name):
    return name.startswith(NESTED_PREFIX)


def is_census(name):
    return name.startswith(CENSUS_PREFIX)


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
        m = ROW.match(line)
        if m:
            cur["rows"].append((m.group(1), int(m.group(2)), int(m.group(3))))
    return runs


def mean(xs):
    return sum(xs) / len(xs)


def main():
    runs = []
    for p in sorted(glob.glob(os.path.join(OUT, "warm-call*.log"))):
        for r in parse(p):
            r["log"] = os.path.basename(p)
            runs.append(r)
    if not runs:
        sys.exit(f"no instrumented runs found under {OUT}")

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

    on, co = by_tier["call"], by_tier["callcoarse"]
    print(f"\n### (CALL.1) checkSingleCallExpressionTypes — ON draws {len(on)}, "
          f"COARSE draws {len(co)}")

    def agg(rs):
        acc, closes = defaultdict(list), {}
        for r in rs:
            for nm, c, ns in r["rows"]:
                acc[nm].append(ns / 1e6)
                closes[nm] = c
        return {k: (closes[k], mean(v), min(v), max(v)) for k, v in acc.items()}

    A, C = agg(on), agg(co)

    # DETERMINISM: every count must be bit-identical across a tier's draws, or
    # the nanos means are means over different compiles.
    bad = []
    for tier, rs in (("call", on), ("callcoarse", co)):
        first = {nm: c for nm, c, _ in rs[0]["rows"]}
        for r in rs[1:]:
            for nm, c, _ in r["rows"]:
                if first.get(nm) != c:
                    bad.append((tier, nm, first.get(nm), c))
    print(f"\n-- determinism: {'ALL COUNTS IDENTICAL across draws' if not bad else bad[:5]}")

    def partition_total(t):
        b = ms = 0.0
        for nm, (c, m, _, _) in t.items():
            if is_nested(nm) or is_census(nm):
                continue
            b += c
            ms += m
        return b, ms

    def all_boundaries(t):
        """Every timestamp pair the arm executed: partition closes + nested closes."""
        return sum(c for nm, (c, _, _, _) in t.items() if not is_census(nm))

    ab, am = partition_total(A)
    cb, cm = partition_total(C)
    tb, tcb = all_boundaries(A), all_boundaries(C)
    print("\n-- WARM boundary, ON-minus-COARSE differential (round 734) --")
    print(f"  partition rows      ON {am:8.0f} ms over {ab:12,.0f} closes")
    print(f"                  COARSE {cm:8.0f} ms over {cb:12,.0f} closes")
    print(f"  ALL boundaries      ON {tb:12,.0f}   COARSE {tcb:12,.0f}   extra {tb - tcb:12,.0f}")
    naive = (am - cm) * 1e6 / (ab - cb) if ab != cb else float("nan")
    B = (am - cm) * 1e6 / (tb - tcb) if tb != tcb else float("nan")
    print(f"  naive (partition closes only)      : {naive:6.0f} ns/boundary")
    print(f"  ALL-boundary estimator  <- USED    : {B:6.0f} ns/boundary")
    print(f"  the partition is FLAT (one level), so no nesting correction applies")
    oh_on, oh_co = mean([r["overheadMs"] for r in on]), mean([r["overheadMs"] for r in co])
    print(f"  cross-check, BenchMain overheadMs: ON {oh_on:+.0f} / COARSE {oh_co:+.0f} ms "
          f"-> {(oh_on - oh_co) * 1e6 / (tb - tcb):.0f} ns/boundary (whole-run, noisy)")
    net_on = am - tb * B / 1e6
    net_co = cm - tcb * B / 1e6
    print(f"\n-- the function's total: ON raw {am:.0f} ms -> net {net_on:.0f} ms "
          f"({net_on / denom * 100:.2f}% warm);  COARSE raw {cm:.0f} -> net {net_co:.0f} "
          f"({net_co / denom * 100:.2f}%)")

    print(f"\n-- rows, mean of {len(on)} ON draws; boundary {B:.0f} ns; "
          f"UNRESOLVED = raw ns/call below the boundary --")
    print(f"{'section':<50}{'raw ms':>8}{'net ms':>8}{'%warm':>7}"
          f"{'closes':>10}{'ns/call':>10}{'spread':>14}  flag")
    for nm, (c, m, lo, hi) in sorted(A.items(), key=lambda kv: -kv[1][1]):
        if is_census(nm):
            continue
        each = m * 1e6 / c if c else 0.0
        net = m - c * B / 1e6
        spread = (hi - lo) / m * 100 if m else 0.0
        flags = []
        if is_nested(nm):
            flags.append("nested")
        if each < B:
            flags.append("UNRESOLVED")
        if spread > 30 and m > 5:
            flags.append("noisy")
        print(f"{nm[:49]:<50}{m:8.1f}{net:8.1f}{net / denom * 100:7.2f}"
              f"{c:10,}{each:10.0f}{lo:6.1f}-{hi:<7.1f}  {','.join(flags)}")

    # ── the exit census ──────────────────────────────────────────────────────
    print("\n-- (WARM.5) EXIT CENSUS — invocations by the row they RETURNED from --")
    print(f"{'row':<50}{'left':>9}{'emitted':>9}{'emit%':>7}"
          f"{'prologue ms':>13}{'callee ms':>11}{'of it any/err':>15}")
    tot_left = tot_emit = tot_pro = tot_cal = tot_bail = 0
    rows = []
    for nm, (c, m, _, _) in A.items():
        if not nm.startswith("exitPro: "):
            continue
        base = nm[len("exitPro: "):]
        left, pro = c, m
        cal_c, cal_ms = A.get("exitCallee: " + base, (0, 0.0, 0, 0))[:2]
        emit = A.get("exitEmit: " + base, (0, 0.0, 0, 0))[0]
        rows.append((base, left, emit, pro, cal_ms, cal_c))
    for base, left, emit, pro, cal_ms, bail in sorted(rows, key=lambda r: -r[1]):
        tot_left += left; tot_emit += emit; tot_pro += pro; tot_cal += cal_ms; tot_bail += bail
        print(f"{base[:49]:<50}{left:9,}{emit:9,}{100 * emit / left if left else 0:6.1f}%"
              f"{pro:13.1f}{cal_ms:11.1f}{bail:15,}")
    inv = [r for r in on[0].get("rows", []) if r[0] == "getCalleeType"]
    print(f"{'TOTAL':<50}{tot_left:9,}{tot_emit:9,}"
          f"{100 * tot_emit / tot_left if tot_left else 0:6.1f}%{tot_pro:13.1f}{tot_cal:11.1f}"
          f"{tot_bail:15,}")
    if inv:
        print(f"  (partition check: getCalleeType reached {inv[0][1]:,} times)")


if __name__ == "__main__":
    main()

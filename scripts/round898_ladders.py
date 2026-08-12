#!/usr/bin/env python3
"""(WARM.25) round 898 — read the copy-amplification ladders.

Each `warm-<tag>.log` is one JVM: a warm-up, a measured loop whose median is the
process's reference, then SIX instrumented rebuilds (the tier list), each of
which prints its own `{"instrumented":true,...,"ms":...}` line and a census
block carrying the arithmetic falsifier `ampSink (expected N)`.

What this script enforces, and why each check is here:

* **the falsifier is arithmetic, not statistical** — `ampSink` must equal
  `copyAmp x entries(armed)` on EVERY rebuild, which is what rules out a JIT
  that hoisted the extra copies away. A mismatch voids the draw, it does not
  widen its error bar.
* **the leading draw of each process is reported separately.** Round 891 lost a
  4x to it. Pooling the two mirrored rotations is the primary answer; the
  drop-leading-draw figure is printed beside it as the internal consistency
  check, exactly as round 891 did.
* **the census populations are re-read from every log** and required to agree
  across all six processes, because the whole instrument rests on the armed
  family's entry count being deterministic.
"""
import glob
import json
import os
import re
import sys
from collections import defaultdict

OUT = "build/bench/round898"

TIER_RE = re.compile(r'^\{"instrumented":true,.*\}$')
AMP_RE = re.compile(r"ampSink (\d+) \(expected (\d+)\)")
ROW_RE = re.compile(
    r"^\s{4}(\S.*?)\s{2,}pushes\s+(\d+)\s+entries\s+(\d+)\s+mean\s+\S+\s+max\s+(\d+)"
    r"\s+writes\s+(\d+)\s+undo\s+(\d+)\s+touchedCalls\s+(\d+)\s+touchedEntries\s+(\d+)\s*$"
)
REPS_RE = re.compile(r"amp=(\d+) kinds=(-?\d+)")


def reps_of(tier):
    d = re.sub(r"^copyamp[a-z]*", "", tier)
    return int(d)


def read(path):
    """-> (tag, [(tier, reps, ms, ampOk)], census rows)."""
    draws, rows, medians = [], {}, []
    pending_ms = None
    pending_tier = None
    with open(path, "r", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if TIER_RE.match(line):
                j = json.loads(line)
                pending_tier, pending_ms = j["tier"], j["ms"]
                medians.append(j["medianMs"])
            m = ROW_RE.match(line)
            if m:
                rows[m.group(1).strip()] = tuple(int(m.group(i)) for i in range(2, 9))
            m = AMP_RE.search(line)
            if m and pending_tier is not None:
                got, exp = int(m.group(1)), int(m.group(2))
                draws.append((pending_tier, reps_of(pending_tier), pending_ms, got == exp, got, exp))
                pending_tier = None
    return draws, rows, medians


def fit(by_r):
    """Least-squares slope of ms against r, through the arm MEANS."""
    xs = sorted(by_r)
    n = len(xs)
    mx = sum(xs) / n
    my = sum(sum(by_r[x]) / len(by_r[x]) for x in xs) / n
    num = sum((x - mx) * (sum(by_r[x]) / len(by_r[x]) - my) for x in xs)
    den = sum((x - mx) ** 2 for x in xs)
    return num / den if den else float("nan")


def main():
    fams = defaultdict(dict)          # family prefix -> tag -> draws
    census = {}
    for path in sorted(glob.glob(os.path.join(OUT, "warm-*.log"))):
        tag = os.path.basename(path)[5:-4]
        if tag == "census":
            _, rows, _ = read(path)
            census["census"] = rows
            continue
        draws, rows, _ = read(path)
        if not draws:
            print(f"!! {tag}: no instrumented draws — run died?")
            continue
        fams[tag[:-1]][tag] = draws
        census[tag] = rows

    # the populations must be identical everywhere
    base = census.get("census")
    if base:
        for tag, rows in census.items():
            for name, vals in rows.items():
                b = base.get(name)
                if b and b[:2] != vals[:2]:
                    print(f"!! {tag}: population moved for {name}: {vals[:2]} vs {b[:2]}")
        print("== census (pushes, entries, max, writes, undo, touchedCalls, touchedEntries) ==")
        for name, v in base.items():
            if v[1] == 0:
                continue
            head = f"  {name:<40} pushes {v[0]:>7} entries {v[1]:>9} writes {v[3]:>8}"
            # ROUND 849's LAW, APPLIED TO THIS SCRIPT'S OWN OUTPUT: only the
            # `Epoch*` families carry a first-write hook. Printing "100.0% of
            # volume untouched" for a family that has NO hook would be an
            # un-instrumented zero dressed as a finding — which is exactly the
            # error this instrument exists to avoid making about someone else.
            if v[5] == 0 and v[3] > 0:
                print(head + "  | untouched: NOT INSTRUMENTED for this family")
            else:
                print(
                    head + f"  | untouched {v[0] - v[5]:>7} calls {v[1] - v[6]:>9} entries "
                    f"({100.0 * (v[1] - v[6]) / v[1]:.1f}% of volume)"
                )
        print()

    for fam, tags in sorted(fams.items()):
        print(f"== family arm `{fam}` ==")
        pooled = defaultdict(list)
        pooled_nolead = defaultdict(list)
        for tag in sorted(tags):
            draws = tags[tag]
            bad = [d for d in draws if not d[3]]
            print(f"  {tag}: " + "  ".join(f"r={d[1]}:{d[2]:.0f}" for d in draws))
            if bad:
                print(f"    !! ARITHMETIC FALSIFIER FAILED on {len(bad)} draw(s): "
                      + "; ".join(f"r={b[1]} got {b[4]} expected {b[5]}" for b in bad))
            by_r = defaultdict(list)
            for i, d in enumerate(draws):
                by_r[d[1]].append(d[2])
                pooled[d[1]].append(d[2])
                if i > 0:
                    pooled_nolead[d[1]].append(d[2])
            print(f"    slope {fit(by_r):.2f} ms/rep  (arms: "
                  + ", ".join(f"r={r} {sum(v)/len(v):.0f}" for r, v in sorted(by_r.items())) + ")")
        print(f"  POOLED slope {fit(pooled):.2f} ms/rep  (arms: "
              + ", ".join(f"r={r} n={len(v)} {sum(v)/len(v):.0f}" for r, v in sorted(pooled.items())) + ")")
        if pooled_nolead:
            print(f"  POOLED, leading draw of each process dropped: {fit(pooled_nolead):.2f} ms/rep")
        # sub-interval agreement (round 891's only internal consistency check)
        rs = sorted(pooled)
        if len(rs) >= 3:
            for a, b in zip(rs, rs[1:]):
                ma = sum(pooled[a]) / len(pooled[a])
                mb = sum(pooled[b]) / len(pooled[b])
                print(f"    sub-interval r={a}->{b}: {(mb - ma) / (b - a):.2f} ms/rep")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())

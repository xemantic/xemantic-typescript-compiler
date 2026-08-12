#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""Report of the round-899 cumulative warm A/B (rounds 895-898).

Reads `<ARMS_DIR>/ab/samples.txt` written by `scripts/round899-ab.sh`, one line
per PROCESS: `<tag> <median-ms> <files> <errors> <n> <min> <max>`, tag
`b<batch>p<pair><arm>`.

What it prints, and why each line is there:

  * per-arm n / median / mean / sd — `ab-warm.sh`'s rule is that a warm arm sd
    over ~1% was not measured on a quiet box, so the sd is quoted whatever it
    says (round 893's arms were 2.21% / 3.44% and said so);
  * the PAIRED deltas, which are the only quotable quantity (CLAUDE.md: the
    sequential anchor drifts up to 12.8% across rounds on identical code), with
    the win rate, the per-pair range, and each batch separately — round 840(c)
    requires the replication, round 891 requires the opposite rotations;
  * a two-sided sign test, so the sign evidence is quantified rather than
    asserted;
  * the files/errors CONTROL: every process must answer the same program.
"""
import math
import statistics
import sys
from collections import defaultdict


def sd_pct(xs):
    if len(xs) < 2:
        return float("nan")
    return 100.0 * statistics.stdev(xs) / statistics.mean(xs)


def sign_test_p(wins, n):
    """Two-sided exact binomial p at q=0.5 for `wins` successes in n trials."""
    if n == 0:
        return float("nan")
    k = min(wins, n - wins)
    tail = sum(math.comb(n, i) for i in range(0, k + 1)) / (2.0 ** n)
    return min(1.0, 2.0 * tail)


def main(path):
    rows = {}
    ctrl = set()
    for line in open(path):
        parts = line.split()
        if len(parts) < 7:
            sys.exit(f"REFUSED: unparsable sample line: {line!r}")
        tag, med, files, errors, n = parts[0], float(parts[1]), int(parts[2]), int(parts[3]), int(parts[4])
        rows[tag] = med
        ctrl.add((files, errors, n))
    if len({(f, e) for f, e, _ in ctrl}) != 1:
        sys.exit(f"REFUSED: arms disagree on the program: {sorted(ctrl)}")
    files, errors, iters = sorted(ctrl)[0]

    arms = defaultdict(list)
    for tag, med in rows.items():
        arms[tag[-1]].append(med)

    print(f"CONTROL: every process answers {files} files / {errors} errors, "
          f"{iters} measured rebuilds each")
    print()
    for arm in ("A", "B"):
        xs = arms[arm]
        print(f"arm {arm}: n={len(xs):2d}  median={statistics.median(xs):7.1f}  "
              f"mean={statistics.mean(xs):7.1f}  sd={statistics.stdev(xs):5.1f} "
              f"({sd_pct(xs):.2f}%)  min={min(xs):.0f} max={max(xs):.0f}")
    print()

    pairs = []
    for batch in (1, 2):
        for p in range(1, 7):
            a = rows.get(f"b{batch}p{p}A")
            b = rows.get(f"b{batch}p{p}B")
            if a is None or b is None:
                continue
            pairs.append((batch, p, a, b, 100.0 * (b - a) / a))

    print("pair   A ms    B ms    delta%   lead")
    for batch, p, a, b, d in pairs:
        lead = "A" if (p + batch) % 2 == 1 else "B"
        print(f"b{batch}p{p}  {a:7.1f} {b:7.1f}  {d:+7.2f}%   {lead}")
    print()

    def summarise(label, sel):
        ds = [d for (batch, p, a, b, d) in pairs if sel(batch)]
        if not ds:
            return
        wins = sum(1 for d in ds if d < 0)
        print(f"{label}: n={len(ds)}  median={statistics.median(ds):+.2f}%  "
              f"mean={statistics.mean(ds):+.2f}%  B faster in {wins}/{len(ds)}  "
              f"range [{min(ds):+.2f}%, {max(ds):+.2f}%]  "
              f"sign-test p={sign_test_p(wins, len(ds)):.4f}")

    summarise("POOLED ", lambda b: True)
    summarise("batch 1", lambda b: b == 1)
    summarise("batch 2", lambda b: b == 2)
    print()
    ma, mb = statistics.median(arms["A"]), statistics.median(arms["B"])
    print(f"median-of-medians: {100.0 * (mb - ma) / ma:+.2f}%")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/bench/round899/ab/samples.txt")

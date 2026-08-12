#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""Cross-round comparison of the warm leaf profile (rounds 868 / 870 / 874 / 888).

Generalises `round874_compare.py` over an arbitrary round list. The JFR window is
a fixed wall-clock span of steady state, so the sample count is a constant and a
SHARE is a share of WALL TIME, not of a rebuild: after a speed-up an UNCHANGED
per-rebuild cost reads correspondingly HIGHER (round 870 § 14.2). Every
cross-round number is therefore quoted in **ms per rebuild** = share x that
round's median rebuild.

Usage: round888_compare.py [--top N] [--rounds 874,888] [--grep SUBSTR]
"""
import sys
from collections import Counter

sys.path.insert(0, "scripts")
from leaf_owner_profile import parse, owner  # noqa: E402

ROUNDS = {
    "868": ("build/bench/round868/deep1.txt", "build/bench/round868/deep2.txt", 7766.0),
    "870": ("build/bench/round870/deep1.txt", "build/bench/round870/deep2.txt", 7068.0),
    "874": ("build/bench/round874/deep1.txt", "build/bench/round874/deep2.txt", 6597.0),
    "888": ("build/bench/round888/deep1.txt", "build/bench/round888/deep2.txt", 5905.0),
    "893": ("build/bench/round893/deep1.txt", "build/bench/round893/deep2.txt", 5461.0),
}


def load(rounds):
    data = {}
    for r in rounds:
        p1, p2, med = ROUNDS[r]
        counts, totals = [], []
        for p in (p1, p2):
            stacks, _other, maxd = parse(p, "xtsc-deep-stack")
            if maxd <= 5:
                sys.exit(f"REFUSED: {p} truncated to 5 frames")
            c = Counter(owner(s) for s in stacks)
            c.pop(None, None)
            counts.append(c)
            totals.append(len(stacks))
        data[r] = (counts, totals, med)
    return data


def ms(data, r, key):
    counts, totals, med = data[r]
    shares = [100.0 * c.get(key, 0) / t for c, t in zip(counts, totals)]
    mean = sum(shares) / len(shares)
    return mean, mean / 100.0 * med, shares


def main():
    args = sys.argv[1:]
    top, rounds, grep = 30, ["868", "870", "874", "888"], None
    if "--top" in args:
        i = args.index("--top"); top = int(args[i + 1]); del args[i:i + 2]
    if "--rounds" in args:
        i = args.index("--rounds"); rounds = args[i + 1].split(","); del args[i:i + 2]
    if "--grep" in args:
        i = args.index("--grep"); grep = args[i + 1]; del args[i:i + 2]
    data = load(rounds)
    last, prev = rounds[-1], rounds[-2] if len(rounds) > 1 else rounds[-1]

    keys = set()
    for c in data[last][0]:
        keys |= set(c)
    if grep:
        allk = set()
        for r in rounds:
            for c in data[r][0]:
                allk |= set(c)
        keys = {k for k in allk if grep in k}

    rows = []
    for k in keys:
        cells = [ms(data, r, k) for r in rounds]
        rows.append((cells[-1][1], k, cells))
    rows.sort(reverse=True)

    hdr = "".join(f"{'ms' + r:>9s}" for r in rounds)
    print(f"{'#':>3}  {'owner':46s} {'r1':>6s} {'r2':>6s}{hdr} {'d' + prev:>8s}")
    for i, (_, k, cells) in enumerate(rows[:top], 1):
        sh = cells[-1][2]
        body = "".join(f"{c[1]:9.1f}" for c in cells)
        d = cells[-1][1] - cells[-2][1] if len(cells) > 1 else 0.0
        print(f"{i:>3}  {k[:46]:46s} {sh[0]:5.2f}% {sh[1]:5.2f}%{body} {d:+8.1f}")


if __name__ == "__main__":
    main()

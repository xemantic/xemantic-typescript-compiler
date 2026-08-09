#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""Cross-round comparison of the warm leaf profile (rounds 868 / 870 / 874).

The JFR window is a fixed wall-clock span of steady state, so the sample count
is a constant and a SHARE is a share of WALL TIME, not of a rebuild: after a
speed-up an UNCHANGED per-rebuild cost reads correspondingly HIGHER (round 870
§ 14.2). Every cross-round number here is therefore quoted in **ms per
rebuild** = share x that round's median rebuild.

Usage: round874_compare.py [--top N] [--validity]
"""
import sys
from collections import Counter

sys.path.insert(0, "scripts")
from leaf_owner_profile import parse, owner  # noqa: E402

ROUNDS = {
    "868": ("build/bench/round868/deep1.txt", "build/bench/round868/deep2.txt", 7766.0),
    "870": ("build/bench/round870/deep1.txt", "build/bench/round870/deep2.txt", 7068.0),
    "874": ("build/bench/round874/deep1.txt", "build/bench/round874/deep2.txt", 6597.0),
}

VALIDITY = [
    # round 870's fix
    "computeTypeParamInfo",
    "buildModuleSymbolScanIndex",
    "moduleSymbolScanIndex",
    # round 868's fix (should still be gone)
    "computeExportedFnDeclsThroughStars",
    "computeExportedVarDeclThroughStars",
    "resolveBarrelStarTarget",
    "buildStarExportIndex",
    # round 869's fix (should still be gone)
    "spineOsPushCopy",
    "spinePdPushCopy",
    "AnnScopeStack",
    # round 871's fix — the crawl parse
    "Parser.",
    "CrawlParseCache",
]


def load():
    data = {}
    for r, (p1, p2, med) in ROUNDS.items():
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
    top = 25
    if "--top" in args:
        i = args.index("--top"); top = int(args[i + 1]); del args[i:i + 2]
    data = load()

    if "--validity" in args:
        print("VALIDITY — owners the last three fixes touched (share r1/r2, ms/rebuild)\n")
        print(f"{'owner':56s} {'868':>18s} {'870':>18s} {'874':>18s}")
        allkeys = set()
        for r in ROUNDS:
            for c in data[r][0]:
                allkeys |= set(c)
        for needle in VALIDITY:
            for k in sorted(allkeys):
                if needle in k:
                    cells = []
                    for r in ("868", "870", "874"):
                        share, m, sh = ms(data, r, k)
                        cells.append(f"{share:5.2f}% {m:6.1f}ms")
                    print(f"{k[:56]:56s} " + " ".join(f"{c:>18s}" for c in cells))
        print()
        return

    keys = set()
    for c in data["874"][0]:
        keys |= set(c)
    rows = []
    for k in keys:
        s874, m874, sh874 = ms(data, "874", k)
        s870, m870, _ = ms(data, "870", k)
        s868, m868, _ = ms(data, "868", k)
        rows.append((s874, k, sh874, s870, m868, m870, m874))
    rows.sort(reverse=True)
    print(f"{'#':>3}  {'owner':50s} {'r1':>6s} {'r2':>6s} "
          f"{'ms868':>7s} {'ms870':>7s} {'ms874':>7s} {'d870':>7s}")
    for i, (s874, k, sh, s870, m868, m870, m874) in enumerate(rows[:top], 1):
        print(f"{i:>3}  {k[:50]:50s} {sh[0]:5.2f}% {sh[1]:5.2f}% "
              f"{m868:7.1f} {m870:7.1f} {m874:7.1f} {m874 - m870:+7.1f}")


if __name__ == "__main__":
    main()

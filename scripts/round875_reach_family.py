#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""(WARM.22) — the INV.4 REACH MACHINERY as ONE population, per CLASSIFIER.

Round 874 § 23 aggregated the warm leaf profile by MECHANISM and found the
largest family in the compile is the INV.4 migration's own reach scaffolding —
458.8 ms = 6.95% spread over 66 owners of which the biggest is 0.57%. This
script re-reads the SAME committed dumps and groups those owners by the
CLASSIFIER they belong to (`spineXx*` -> `Xx`), which is the unit a design
change would act on.

Caveats inherited unchanged from `leaf_owner_profile.py`: a share is a share of
WALL TIME, leaf attribution moves with C2 inlining between processes (so both
runs are always printed), and NO number here is a price — it is a candidate
list that a second instrument has to price (round 623, round 801).

Usage: round875_reach_family.py [dump1 dump2] [--owners] [--median MS]
"""
import re
import sys
from collections import Counter, defaultdict

sys.path.insert(0, "scripts")
from leaf_owner_profile import parse, owner  # noqa: E402

DEFAULT_DUMPS = ("build/bench/round874/deep1.txt", "build/bench/round874/deep2.txt")
DEFAULT_MEDIAN = 6597.0  # round 874's warm median rebuild, ms

# A member of the reach machinery: the memoized ancestor classifiers, their edge
# predicates, their per-pass scope-level walks and the chain/level caches that
# exist only to answer them. Deliberately NOT included: the handlers themselves
# (`spineCtaEnter`, `cpaSpineLeave`, …), which do the checking work.
REACH_RE = re.compile(
    r"^Checker\.spine[A-Z][A-Za-z0-9]*?"
    r"(Status|Edge|Edge[A-Z][A-Za-z0-9]*|Checked|Reached|Reach[A-Za-z0-9]*|"
    r"Descends|HasArm|Level|Level[A-Z][A-Za-z0-9]*|Levels|Chain[A-Za-z0-9]*|"
    r"MayFire|DeclReachable|ApplyLevels|ScopeLevels|OwnLevel|RootEdge)"
    r"(\$.*)?$"
)
# The TAV pass names its members `tav*` rather than `spineTav*` below the entry.
TAV_RE = re.compile(r"^Checker\.(tav[A-Z][A-Za-z0-9]*|spineTav[A-Za-z0-9]*)(\$.*)?$")


def is_reach(name: str) -> bool:
    return bool(REACH_RE.match(name) or TAV_RE.match(name))


def classifier(name: str) -> str:
    """`Checker.spineNaEdge` -> `Na`; `Checker.tavBuildLevel` -> `Tav`."""
    m = re.match(r"^Checker\.spine(URes|[A-Z][a-z0-9]*)", name)
    if m:
        return m.group(1)
    if name.startswith("Checker.tav"):
        return "Tav"
    return "?"


def main() -> None:
    args = sys.argv[1:]
    median = DEFAULT_MEDIAN
    show_owners = False
    while "--median" in args:
        i = args.index("--median"); median = float(args[i + 1]); del args[i:i + 2]
    while "--owners" in args:
        args.remove("--owners"); show_owners = True
    dumps = args or list(DEFAULT_DUMPS)

    counts, totals = [], []
    for p in dumps:
        stacks, _other, maxd = parse(p, "xtsc-deep-stack")
        if maxd <= 5:
            sys.exit(f"REFUSED: {p} truncated to 5 frames (see leaf_owner_profile.py)")
        c = Counter(owner(s) for s in stacks)
        c.pop(None, None)
        counts.append(c)
        totals.append(len(stacks))

    owners = set()
    for c in counts:
        owners |= {k for k in c if is_reach(k)}

    per_cls = defaultdict(lambda: [0.0, 0.0])
    per_owner = []
    for k in owners:
        sh = [100.0 * c.get(k, 0) / t for c, t in zip(counts, totals)]
        per_owner.append((sum(sh) / len(sh), k, sh))
        cl = classifier(k)
        for i, s in enumerate(sh):
            per_cls[cl][i] += s

    print(f"== dumps: {', '.join(dumps)}  ({totals[0]}, {totals[1]} compile-thread samples)")
    print(f"== median warm rebuild assumed {median:.0f} ms\n")
    print(f"{'#':>3}  {'classifier':14s} {'r1':>7s} {'r2':>7s} {'ms/rebuild':>11s}  owners")
    rows = sorted(((v[0] + v[1]) / 2, k, v) for k, v in per_cls.items())
    rows.reverse()
    tot = [0.0, 0.0]
    for i, (mean, k, v) in enumerate(rows, 1):
        tot[0] += v[0]; tot[1] += v[1]
        n = sum(1 for _, o, _ in per_owner if classifier(o) == k)
        print(f"{i:>3}  {k:14s} {v[0]:6.2f}% {v[1]:6.2f}% "
              f"{mean / 100.0 * median:10.1f}  {n}")
    mean = (tot[0] + tot[1]) / 2
    print(f"\n     {'TOTAL':14s} {tot[0]:6.2f}% {tot[1]:6.2f}% "
          f"{mean / 100.0 * median:10.1f}  {len(owners)}")

    if show_owners:
        print(f"\n{'owner':56s} {'r1':>7s} {'r2':>7s} {'ms':>7s}")
        for mean_s, k, sh in sorted(per_owner, reverse=True):
            print(f"{k[:56]:56s} {sh[0]:6.2f}% {sh[1]:6.2f}% "
                  f"{mean_s / 100.0 * median:6.1f}")


if __name__ == "__main__":
    main()

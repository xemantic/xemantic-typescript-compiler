#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""Round 874 § 23's OWNER-FAMILY aggregation of the warm leaf profile, made
reproducible and run over two rounds' dumps with the SAME patterns.

Round 874's law: the ROW is the wrong unit when a mechanism is spread across
many of them (its finding was 6.95% over 66 owners whose biggest was 0.57%).
Round 870's law: a JFR share is a share of WALL TIME in a fixed window, so a
cross-round comparison must be converted to ms/rebuild with THAT round's median.

Families are matched against the nearest-non-stdlib OWNER name (round 868's
law), first match wins, and the residue is reported so the partition is honest.

Usage: round888_families.py [--rounds 874,888] [--owners FAMILY]
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

# ORDER MATTERS — first match wins, so the narrow families precede the broad.
FAMILIES = [
    ("INV.4 reach classifiers", (
        "Status", "Edge", "Reached", "spineUResExprChecked", "spineTav",
        "tavLevel", "tavBuild", "spineUncalledDispatch", "spineUResDispatch",
    )),
    ("scope frame copies", (
        "EpochMap", "EpochSet", "AnnScopeStack", "PushCopy", "Overlay",
        "CopyTop", "spineCaCopy",
    )),
    ("flow-graph build", ("FlowGraphBuilder.", "FlowGraph.", "FlowGraph$")),
    ("narrowing walk", (
        "narrowTypeFromFlow", "getNarrowedTypeForReference", "NarrowSeen",
        "walkMemoServe", "applyConditionNarrowing", "NarrowFlowMemo",
    )),
    ("name resolution", (
        "lookupPerFile", "globalsForFile", "NameScope", "lexLevel",
        "unresolvedLexOf", "checkIdentifierResolved",
    )),
    ("module/import resolution", (
        "resolveImportedSymbol", "resolveModuleSpecifier", "findSymbolInExports",
        "computeExported", "resolveBarrel", "normalizePath", "SuffixNameSet",
        "markAliasReferenced",
    )),
    ("relation engine", (
        "Relation.", "checkTypeRelatedTo", "isSimpleTypeRelatedTo",
        "propertiesRelatedTo", "isTypeAssignableTo",
    )),
    ("type construction", (
        "getUnionType", "internUnion", "getOrInternReference", "getIntersectionType",
        "getTypeFromTypeNode", "getDeclaredType", "getTypeOfSymbol",
        "getTypeOfIdentifier", "getPropertyOfType", "resolveGenericPropertyType",
    )),
    ("cta* handlers", ("Checker.cta",)),
    ("cpa* handlers", ("Checker.cpa",)),
    ("ccet* handlers", ("Checker.ccet",)),
    ("spine walk core", (
        "spineWalkFile", "spineEnterNode", "spineLeaveNode", "spineEnterKindDispatch",
        "forEachChild",
    )),
]


def classify(name):
    for fam, pats in FAMILIES:
        if any(p in name for p in pats):
            return fam
    return None


def load(r):
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
    return counts, totals, med


def fam_ms(counts, totals, med, fam):
    shares = []
    for c, t in zip(counts, totals):
        n = sum(v for k, v in c.items() if classify(k) == fam)
        shares.append(100.0 * n / t)
    mean = sum(shares) / len(shares)
    return mean, mean / 100.0 * med, shares


def main():
    args = sys.argv[1:]
    rounds = ["874", "888"]
    show = None
    if "--rounds" in args:
        i = args.index("--rounds"); rounds = args[i + 1].split(","); del args[i:i + 2]
    if "--owners" in args:
        i = args.index("--owners"); show = args[i + 1]; del args[i:i + 2]
    data = {r: load(r) for r in rounds}

    if show:
        counts, totals, med = data[rounds[-1]]
        merged = Counter()
        for c in counts:
            merged.update({k: v for k, v in c.items() if classify(k) == show})
        tot = sum(totals)
        print(f"owners in family '{show}' (round {rounds[-1]}), "
              f"{len(merged)} owners, {sum(merged.values())} samples")
        for k, v in merged.most_common(40):
            print(f"  {v / tot * med:7.1f} ms  {k[:70]}")
        return

    names = [f for f, _ in FAMILIES]
    hdr = "".join(f"{'ms' + r:>9s}" for r in rounds)
    print(f"{'family':32s}{hdr}   {rounds[-1]} r1/r2")
    print("-" * (32 + 9 * len(rounds) + 18))
    rows = []
    for fam in names:
        cells = [fam_ms(*data[r], fam) for r in rounds]
        rows.append((cells[-1][1], fam, cells))
    rows.sort(reverse=True)
    for m, fam, cells in rows:
        body = "".join(f"{c[1]:9.1f}" for c in cells)
        sh = cells[-1][2]
        print(f"{fam:32s}{body}   {sh[0]:.2f}% / {sh[1]:.2f}%")
    # residue
    for r in rounds:
        counts, totals, med = data[r]
        unc = []
        for c, t in zip(counts, totals):
            n = sum(v for k, v in c.items() if classify(k) is None)
            unc.append(100.0 * n / t)
        mean = sum(unc) / len(unc)
        print(f"  [round {r}] unclassified residue: {mean:.2f}% = {mean / 100 * med:.1f} ms")


if __name__ == "__main__":
    main()

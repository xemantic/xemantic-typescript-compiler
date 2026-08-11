#!/usr/bin/env python3
"""Aggregate a jfr-print dump by MECHANISM family (round 874's law: read by
family, not by row).  Reports LEAF share and INCLUSIVE share per family.

Usage: mech.py <dump> [<dump> ...]
"""
import re
import sys

THREAD = "xtsc-deep-stack"

# family -> list of substrings; a frame matches the family if any substring is in it
FAMILIES = {
    "relation-engine": [
        ".checkTypeRelatedTo", ".isTypeRelatedTo", ".isSimpleTypeRelatedTo",
        ".isTypeAssignableTo", ".isTypeComparableTo", "Checker$Relation",
        ".isTypeIdenticalTo", ".isTypeSubtypeOf",
    ],
    "boxed-Long": ["java.lang.Long."],
    "boxed-Integer": ["java.lang.Integer."],
    "flow-ref-path": [".getReferencePath", ".flowPathRoot", ".narrowPathOf"],
    "scope-map-copy": [
        "MapsKt__MapsKt.toMutableMap", "MapsKt.toMutableMap",
        "HashMap.putMapEntries", "HashMap.<init>", "EpochMap.<init>",
        "SetsKt.toMutableSet", "HashSet.<init>",
    ],
    "string-build": ["StringBuilder", "StringsKt", "java.lang.String."],
    "type-intern": [
        ".getOrInternReference", ".getUnionType", ".internUnion",
        ".getIntersectionType", ".internIntersection",
    ],
    "narrow-walk": [".narrowTypeFromFlow", ".flowWalk", ".getNarrowedTypeForReference"],
    "hash-probe": ["java.util.HashMap.", "java.util.HashSet.",
                   "java.util.LinkedHashMap.", "java.util.LinkedHashSet."],
}


def parse(path):
    """Yield stacks (list of frame strings) for samples on the compile thread."""
    stacks = []
    cur = None
    in_stack = False
    thread_ok = False
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            s = line.strip()
            if s.startswith("jdk.ExecutionSample"):
                cur, in_stack, thread_ok = [], False, False
                continue
            if cur is None:
                continue
            if s.startswith("sampledThread"):
                thread_ok = THREAD in s
                continue
            if s.startswith("stackTrace = ["):
                in_stack = True
                continue
            if in_stack:
                if s == "]":
                    if thread_ok and cur:
                        stacks.append(cur)
                    cur, in_stack = None, False
                    continue
                m = re.match(r"^(\S+?)(?:\(| line:)", s)
                cur.append(m.group(1) if m else s)
    return stacks


def main():
    total_all = 0
    leaf = {k: 0 for k in FAMILIES}
    incl = {k: 0 for k in FAMILIES}
    truncated = 0
    maxdepth = 0
    for path in sys.argv[1:]:
        stacks = parse(path)
        total_all += len(stacks)
        for st in stacks:
            maxdepth = max(maxdepth, len(st))
            if len(st) >= 500:
                truncated += 1
            leaf_frame = st[0]
            for fam, pats in FAMILIES.items():
                if any(p in leaf_frame for p in pats):
                    leaf[fam] += 1
                if any(any(p in fr for p in pats) for fr in st):
                    incl[fam] += 1

    print(f"samples on {THREAD}: {total_all}   max stack depth: {maxdepth}"
          f"   truncated(>=500): {truncated}")
    if maxdepth >= 500:
        print("!! stacks may be truncated - inclusive numbers unsafe")
    print()
    print(f"{'family':<18} {'leaf%':>8} {'incl%':>8}   {'leaf n':>7} {'incl n':>7}")
    print("-" * 56)
    for fam in sorted(FAMILIES, key=lambda k: -incl[k]):
        print(f"{fam:<18} {100.0*leaf[fam]/total_all:>7.2f}% "
              f"{100.0*incl[fam]/total_all:>7.2f}%   "
              f"{leaf[fam]:>7} {incl[fam]:>7}")


if __name__ == "__main__":
    main()

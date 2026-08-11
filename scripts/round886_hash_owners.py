#!/usr/bin/env python3
"""For samples whose LEAF is in a given stdlib family, charge the sample to its
nearest non-stdlib OWNER frame and rank the owners (round 868's law: stdlib leaf
attribution is not stable across processes; the owner is).

Usage: owners.py <family> <dump> [<dump> ...]
   family: hash | string | mapcopy
"""
import re
import sys
from collections import Counter

THREAD = "xtsc-deep-stack"
OWN = "com.xemantic."

FAMS = {
    "hash": ["java.util.HashMap.", "java.util.HashSet.",
             "java.util.LinkedHashMap.", "java.util.LinkedHashSet."],
    "string": ["StringBuilder", "StringsKt", "java.lang.String."],
    "mapcopy": ["MapsKt", "putMapEntries", "HashMap.<init>", "HashSet.<init>"],
}


def parse(path):
    stacks, cur, in_stack, ok = [], None, False, False
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            s = line.strip()
            if s.startswith("jdk.ExecutionSample"):
                cur, in_stack, ok = [], False, False
                continue
            if cur is None:
                continue
            if s.startswith("sampledThread"):
                ok = THREAD in s
                continue
            if s.startswith("stackTrace = ["):
                in_stack = True
                continue
            if in_stack:
                if s == "]":
                    if ok and cur:
                        stacks.append(cur)
                    cur, in_stack = None, False
                    continue
                m = re.match(r"^(\S+?)(?:\(| line:)", s)
                cur.append(m.group(1) if m else s)
    return stacks


def main():
    fam = sys.argv[1]
    pats = FAMS[fam]
    owners, total, matched = Counter(), 0, 0
    for path in sys.argv[2:]:
        for st in parse(path):
            total += 1
            if not any(p in st[0] for p in pats):
                continue
            matched += 1
            owner = next((f for f in st if f.startswith(OWN)), "<no own frame>")
            owners[owner.rsplit(".", 1)[0].split(".")[-1] + "." +
                   owner.rsplit(".", 1)[1]] += 1
    print(f"family={fam}  samples={total}  leaf-in-family={matched} "
          f"({100.0*matched/total:.2f}%)")
    print()
    for name, n in owners.most_common(25):
        print(f"{100.0*n/total:>6.2f}%  {n:>5}  {name}")


if __name__ == "__main__":
    main()

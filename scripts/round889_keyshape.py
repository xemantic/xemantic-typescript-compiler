#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""Round 889 — what KIND of key does the 24% hash family actually probe?

Round 886 recorded "hash probing is 24.3-24.6% of compile-thread samples as a
LEAF" and named tsgo's `core.LinkStore[K,V]` (one probe returning a struct of
co-accessed fields) as the architectural answer. That answer only ports if the
family is made of **id-keyed per-entity fact probes**. This script decides that
by weighting, per JFR sample, the CONTAINERS the owning function probes and the
KEY TYPE each container is declared with.

Method (round 868's law): a stdlib leaf is charged to its nearest non-stdlib
OWNER frame, because leaf attribution is not stable across processes; the owner
is. Each owner is then classified from `Checker.kt` source: the declared key
types of every container it probes, plus whether the sample's leaf is a
COPY/CONSTRUCTION frame rather than a probe.

Usage: round889_keyshape.py <dump> [<dump> ...]
"""
import re
import sys
from collections import Counter, defaultdict

sys.path.insert(0, "scripts")
from round889_coaccess import (  # noqa: E402
    CHECKER, functions, innermost, strip_preserving_length,
)

THREAD = "xtsc-deep-stack"
OWN = "com.xemantic."
HASH = ("java.util.HashMap.", "java.util.HashSet.",
        "java.util.LinkedHashMap.", "java.util.LinkedHashSet.")
COPY_LEAVES = ("putMapEntries", "resize", "<init>", "putAll", "treeifyBin",
               "newNode", "clear")

PROBE = re.compile(
    r"(?<![.\w])([A-Za-z_][A-Za-z0-9_]*)"
    r"(?:\[|\.(?:getOrPut|containsKey|contains|getValue|get|put|add|remove)\()")

DECL = re.compile(
    r"va[lr]\s+([A-Za-z_][A-Za-z0-9_]*)\s*"
    r"(?::\s*(?P<ann>[A-Za-z_][A-Za-z0-9_.]*\s*<[^=\n]{0,140}?>))?"
    r"\s*=\s*(?P<init>[A-Za-z_][A-Za-z0-9_.]*\s*(?:<[^(\n]{0,140}?>)?)\s*\(")


def keytype(text):
    """String / Int / Long / Node / other, from a declaration's generics."""
    if text is None:
        return None
    t = re.sub(r"\s+", "", text)
    if "IntKeyMap" in t:
        return "Int"
    if "LongKeyMap" in t:
        return "Long"
    if "EpochMap" in t or "EpochSet" in t or "NameScope" in t or "SuffixNameSet" in t:
        return "String"
    m = re.search(r"<(.+)>$", t)
    if not m:
        return None
    inner, depth, first = m.group(1), 0, ""
    for c in inner:
        if c == "<":
            depth += 1
        elif c == ">":
            depth -= 1
        elif c == "," and depth == 0:
            break
        first += c
    if first in ("String",):
        return "String"
    if first in ("Int",):
        return "Int"
    if first in ("Long",):
        return "Long"
    return first or None


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
    src = strip_preserving_length(open(CHECKER, encoding="utf-8").read())
    fns = functions(src)
    decls = {}
    for m in DECL.finditer(src):
        decls.setdefault(m.group(1), keytype(m.group("ann") or m.group("init")))

    # owner short name -> Counter(keytype)
    by_owner = {}
    for name, s, e in fns:
        body = src[s:e]
        c = Counter()
        for pm in PROBE.finditer(body):
            kt = decls.get(pm.group(1))
            if kt:
                c[kt] += 1
        if c:
            by_owner.setdefault(name, Counter()).update(c)

    total, matched = 0, 0
    fam = Counter()
    detail = defaultdict(Counter)
    for path in sys.argv[1:]:
        for st in parse(path):
            total += 1
            if not any(p in st[0] for p in HASH):
                continue
            matched += 1
            leaf = st[0].rsplit(".", 1)[1]
            owner = next((f for f in st if f.startswith(OWN)), "<none>")
            short = owner.rsplit(".", 1)[1]
            if leaf in COPY_LEAVES:
                fam["COPY/CONSTRUCT"] += 1
                detail["COPY/CONSTRUCT"][owner.rsplit(".", 1)[0].split(".")[-1] + "." + short] += 1
                continue
            c = by_owner.get(short)
            if not c:
                fam["UNCLASSIFIED"] += 1
                detail["UNCLASSIFIED"][owner.rsplit(".", 1)[0].split(".")[-1] + "." + short] += 1
                continue
            kt = c.most_common(1)[0][0]
            label = f"probe:{kt}"
            fam[label] += 1
            detail[label][owner.rsplit(".", 1)[0].split(".")[-1] + "." + short] += 1

    print(f"samples={total}  hash-leaf={matched} ({100.0*matched/total:.2f}% of samples)")
    print()
    print(f"{'share-of-samples':>16} {'share-of-family':>16}  class")
    for label, n in fam.most_common():
        print(f"{100.0*n/total:>15.2f}% {100.0*n/matched:>15.1f}%  {label}")
    print()
    for label, _ in fam.most_common():
        print(f"--- {label} — top owners")
        for name, n in detail[label].most_common(8):
            print(f"    {100.0*n/total:>6.2f}%  {n:>4}  {name}")


if __name__ == "__main__":
    main()

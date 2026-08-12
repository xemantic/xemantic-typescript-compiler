#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""Round 894 — WHERE the residual HashMap/HashSet family lives, by OWNER and by
OPERATION, replicated across four processes (893 x2, 888 x2).

WHY THIS SCRIPT EXISTS.  Rounds 889/890 removed the degenerate-hash mechanism:
`HashMap$TreeNode` as a leaf went 5.91% of warm wall to ZERO samples in 16,055.
What is left of the family (1,300.5 ms/rebuild at round 893 = 23.8% of a 5,461 ms
warm rebuild) is therefore ORDINARY hashing plus linear bucket probing, and the
lever can no longer be "a better hash" — it must be "fewer map operations", a
different container, or a side array.  Ranking that residue needs three things
none of the existing aggregators do together: the sub-family split, the
OPERATION split (lookup vs insert vs resize decides which fix even applies), and
per-owner replication across processes.

THE TRAPS, EACH ONE A CLAUDE.md ENTRY, AND WHERE THIS SCRIPT HONOURS IT.

1. **The family must be matched by a `HashMap` SUBSTRING test, never by the
   prefix `"java.util.HashMap."`** (round 889/893).  The prefix form excludes
   `java.util.HashMap$TreeNode.*` and `java.util.HashMap$Node.*`; that one absent
   `$` hid 6.46% of compile-thread samples for two rounds and made round 886
   report the family as 24.3% when it was 26.42%.  `is_map_frame()` below is a
   substring test and `TreeNode` is reported as its own sub-family so its zero
   stays VISIBLE.

2. **A JFR share is a share of WALL TIME in a fixed window, not of a rebuild**
   (round 870).  Every number printed is ms/rebuild against THAT PROCESS's own
   `medianMs` (from its `warm-jfr*.log`), so an unchanged cost does not "rise"
   when the rebuild gets faster.

3. **Filter to the compile thread `xtsc-deep-stack`** (round 868) — ~3% of the
   samples are the JFR recorder and the crawl's coroutine workers.

4. **Charge a stdlib leaf to its nearest non-stdlib OWNER frame** (round 868):
   leaf attribution is a C2 inlining accident (`HashMap.getNode` read 9.66% in
   one process and 3.70% in another on the same binary), owner attribution
   replicates to a few tenths of a percent.

5. **Refuse a 5-frame-truncated dump** (round 868 — `jfr print` silently
   truncates to the top 5 frames without `--stack-depth`), and refuse one that
   hits the 512 cap.

6. **Replication is the gate** (round 888 § C): every owner row carries all four
   processes' numbers, and a row whose two same-round processes disagree by more
   than `--split-tol` is flagged `SPLIT?` — round 888 found a row that looked
   like a 35 ms regression and was a C2 inlining KEY SPLIT.

STRICT VERSUS EXTENDED.  Two family measures are printed and they answer
different questions.

* **STRICT** = the sample's LEAF class matches the map test.  This is the
  measure rounds 886/888/893 used, and it is what reproduces the 1,300.5 ms
  headline.
* **EXTENDED** = the stack contains a map frame ANYWHERE, and the sample is
  charged to the nearest non-stdlib frame BELOW the OUTERMOST map frame.  This
  catches two things STRICT cannot.  (a) the work a map does THROUGH the key —
  `String.hashCode` under `HashMap.hash`, `String.equals`/`Integer.equals` under
  `HashMap.getNode`, the array copy under `HashMap.resize`; STRICT charges those
  to the String / ArrayList families.  (b) **the key's OWN `hashCode()`/
  `equals()` when the key is an own-code object** — for an AST data class that
  is a DEEP structural recursion (round 471), and it is invisible to a
  "nearest non-stdlib owner" rule because the nearest non-stdlib frame IS the
  `hashCode` itself.  Measured here at ~62 ms/rebuild, replicating across both
  rounds; the `--keyside` section breaks it out.

  Why "outermost map frame" is the right owner rule: a `HashMap` can only
  re-enter user code through the key's `hashCode`/`equals`, so every own-code
  frame ABOVE a map frame is part of that map operation and belongs to the
  caller BELOW it.  (This would be wrong for `computeIfAbsent`/`merge`/
  `forEach(BiConsumer)`, which run a user lambda inside the map — grep before
  reusing this rule if any of those is introduced; as of round 894 the codebase
  uses Kotlin's `getOrPut`, which is a `get` then a `put` with no frame inside
  the map.)

A CAVEAT THE SUB-FAMILY SPLIT CANNOT ESCAPE.  `LinkedHashMap` INHERITS almost
every hot method from `HashMap` (`getNode`, `putVal`, `resize`, `hash`), so a
LinkedHashMap operation is printed as `java.util.HashMap.*` and lands in the
HashMap sub-family.  Only LinkedHashMap-specific frames — `afterNodeInsertion`,
`afterNodeAccess`, `newNode`, `LinkedHashMap$Entry`, the overridden `get` — are
visible as LinkedHashMap.  **So the LinkedHashMap row is a LOWER BOUND on
ordered-map cost, not a measurement of it**, and the "how much is
`mutableMapOf` costing" question cannot be answered from this instrument alone.

And the standing caveat no table here may be read past: a JFR leaf share is NOT
a wall-clock price (round 623 eliminated a 5.3% leaf for -0.3%).  The output is
a CANDIDATE LIST with an upper bound attached to each candidate.

Usage:
    python3 scripts/round894_hash_owners.py                 # default 893 vs 888
    python3 scripts/round894_hash_owners.py --rounds 893,888,874 --top 30
    python3 scripts/round894_hash_owners.py --owner Checker.getUnionType
"""
import re
import sys
from collections import Counter, defaultdict

STDLIB_PREFIXES = ("java.", "jdk.", "sun.", "kotlin.", "kotlinx.")

FRAME_RE = re.compile(r"^\s+([A-Za-z0-9_.$]+)\.([A-Za-z0-9_$<>]+)\(")
THREAD_RE = re.compile(r'^\s*sampledThread = "([^"]*)"')

THREAD = "xtsc-deep-stack"

# (dump, medianMs) per PROCESS — a share is a share of wall time in that
# process's window, so each process gets its own denominator (round 870).
# 888 and 893 carry TRUE per-process medians, read from their own
# `warm-jfr{1,2}.log`.  868/870/874 predate that discipline and are recorded
# here with the ROUND-level median repeated for both processes (the figures
# `round888_families.py` uses) — good enough to rank an old round, NOT good
# enough to quote a per-process number from one.
ROUNDS = {
    "868": [("build/bench/round868/deep1.txt", 7814.0),
            ("build/bench/round868/deep2.txt", 7717.0)],
    "870": [("build/bench/round870/deep1.txt", 7068.0),
            ("build/bench/round870/deep2.txt", 7068.0)],
    "874": [("build/bench/round874/deep1.txt", 6597.0),
            ("build/bench/round874/deep2.txt", 6597.0)],
    "888": [("build/bench/round888/deep1.txt", 5918.587),
            ("build/bench/round888/deep2.txt", 5891.243)],
    "893": [("build/bench/round893/deep1.txt", 5416.767),
            ("build/bench/round893/deep2.txt", 5505.716)],
    "899": [("build/bench/round899/deep1.txt", 5551.898),
            ("build/bench/round899/deep2.txt", 5306.309)],
}


# ---------------------------------------------------------------- family split

def is_map_frame(cls: str) -> bool:
    """SUBSTRING test — never the `java.util.HashMap.` prefix (round 889/893).

    Catches `java.util.HashMap$TreeNode.*`, `java.util.HashMap$Node.*`,
    `java.util.LinkedHashMap$Entry.*` and the four container classes.
    """
    return ("HashMap" in cls or "HashSet" in cls) and cls.startswith("java.util.")


def sub_family(cls: str) -> str:
    """Sub-family of a map frame.  ORDER MATTERS — `LinkedHashMap$Entry`
    contains the substring `HashMap`, and `HashMap$TreeNode` is the red-black
    mechanism rounds 889/890 removed, which must stay visible as its own row."""
    if "TreeNode" in cls:
        return "HashMap$TreeNode (red-black)"
    if "LinkedHashSet" in cls:
        return "LinkedHashSet"
    if "LinkedHashMap" in cls:
        return "LinkedHashMap"
    if "HashSet" in cls:
        return "HashSet"
    return "HashMap"


# Specificity ladder for the WHOLE stdlib prefix.  `HashSet.contains` delegates
# to `HashMap.getNode`, and `LinkedHashMap.put` is INHERITED from `HashMap`, so
# the nearest-to-owner frame alone under-reports both wrappers.  Scanning the
# whole prefix for the most specific container evidence is a strictly better
# LOWER BOUND (it still misses a LinkedHashMap put whose `newNode`/
# `afterNodeInsertion` frames C2 inlined away).
SPECIFICITY = ("LinkedHashSet", "LinkedHashMap", "HashSet", "HashMap")


def sub_family_of_prefix(prefix) -> str:
    best = None
    for cls, _meth in prefix:
        if not is_map_frame(cls):
            continue
        if "TreeNode" in cls:
            return "HashMap$TreeNode (red-black)"
        for i, tag in enumerate(SPECIFICITY):
            if tag in cls:
                if best is None or i < best:
                    best = i
                break
    return SPECIFICITY[best] if best is not None else "HashMap"


# Operation of a map frame, keyed on the METHOD name.  First match wins.
OPS = [
    ("resize", ("resize", "treeifyBin", "split", "untreeify", "tableSizeFor")),
    ("iterate", ("nextNode", "Iterator", "forEach", "KeySet", "EntrySet",
                 "Values", "iterator", "keySet", "entrySet", "values")),
    ("copy-construct", ("<init>", "putMapEntries", "clone", "addAll", "putAll")),
    ("remove", ("removeNode", "remove", "removeTreeNode", "afterNodeRemoval",
                "clear")),
    ("insert", ("putVal", "put", "putIfAbsent", "add", "computeIfAbsent",
                "compute", "merge", "newNode", "newTreeNode",
                "afterNodeInsertion")),
    ("lookup", ("getNode", "get", "getOrDefault", "containsKey", "contains",
                "getEntry", "containsValue", "find", "getTreeNode",
                "afterNodeAccess")),
    ("hash", ("hash", "hashCode", "comparableClassFor", "compareComparables")),
]


def operation(meth: str) -> str:
    for op, names in OPS:
        if meth in names:
            return op
    for op, names in OPS:
        if any(n in meth for n in names):
            return op
    return "other"


# MECHANISM grouping of the owners.  Round 874's law: when a cost is spread
# over hundreds of rows whose biggest is under 1%, the ROW is the wrong unit and
# the FAMILY is the right one — and a family is only useful if its members share
# a FIXABLE CAUSE, which is what these groups are chosen for.  ORDER MATTERS
# (first match wins) and the residue is printed so the partition stays honest.
MECHANISMS = [
    ("scope-frame copies", (
        "EpochMap.<init>", "EpochSet.<init>", "Overlay", "CopyTop", "FnFrame",
        "PushCopy", "ctaFnBodyFrame", "ccetFnFrame", "cpaFnFrame",
        "walkNeverDestructure", "dddStmts", "collectBindingNames",
        "MapScopeStack", "SetScopeStack", "AnnScopeStack",
    )),
    ("per-file NAME resolution", (
        "lookupPerFile", "globalsForFile", "lexLevelHas", "NameScope.",
        "unresolvedLex", "checkIdentifierResolved", "collectNamespaceNames",
        "resolveNameInScope", "scopeHas", "shadow",
    )),
    ("module / export resolution", (
        "computeExported", "resolveImported", "resolveModuleSpecifier",
        "markAliasReferenced", "SuffixNameSet", "StarExportIndex",
        "resolveBarrel", "moduleNamedExportsOf", "normalizePath",
        "resolveAliasTarget", "ModuleResolver",
    )),
    ("type-system caches", (
        "getTypeFromTypeNode", "getTypeOfSymbol", "getTypeOfIdentifier",
        "Relation.", "getPropertyOfType", "getUnionType", "getIntersectionType",
        "getOrInternReference", "resolveGenericPropertyType",
        "resolveStructuredTypeMembers", "getDeclaredType", "internKey",
        "getTypeOfExpression", "propertiesRelatedTo", "instantiate",
    )),
    ("flow graph / narrowing", (
        "FlowGraphBuilder.", "FlowGraph.", "narrow", "walkMemo",
        "NarrowFlowMemo", "flowAt", "recordFlow",
    )),
    ("INV.4 reach classifiers / memos", (
        "spineTav", "spineUres", "spineUResStatus", "Status", "Edge",
        "Reached", "spineUncalled", "Memo",
    )),
]


def mechanism(name):
    for mech, pats in MECHANISMS:
        if any(p in name for p in pats):
            return mech
    return "residue (unclassified)"


# ---------------------------------------------------------------- dump parsing

def parse(path):
    """-> (stacks, samples on other threads, max depth).  A stack is a list of
    (class, method) from LEAF to root."""
    stacks, cur, other, maxd = [], [], 0, 0
    keep = False
    with open(path, errors="replace") as fh:
        for line in fh:
            if line.startswith("jdk.ExecutionSample"):
                if cur:
                    stacks.append(cur)
                cur, keep = [], False
                continue
            m = THREAD_RE.match(line)
            if m:
                keep = (m.group(1) == THREAD)
                if not keep:
                    other += 1
                continue
            if not keep:
                continue
            f = FRAME_RE.match(line)
            if f:
                cur.append((f.group(1), f.group(2)))
                maxd = max(maxd, len(cur))
    if cur:
        stacks.append(cur)
    return [s for s in stacks if s], other, maxd


def owner_of(stack):
    """Nearest non-stdlib frame at or below the leaf (round 868's law)."""
    for i, (cls, meth) in enumerate(stack):
        if not cls.startswith(STDLIB_PREFIXES):
            return f"{cls.rsplit('.', 1)[-1]}.{meth}", i
    return None, len(stack)


# ---------------------------------------------------------------- one process

class Proc:
    def __init__(self, path, median):
        stacks, other, maxd = parse(path)
        if maxd <= 5:
            sys.exit(f"REFUSED: {path} max stack depth {maxd} — `jfr print` was "
                     f"run WITHOUT --stack-depth; every stack is truncated.")
        if maxd >= 500:
            sys.exit(f"REFUSED: {path} max stack depth {maxd} — at/near the 512 "
                     f"cap, so the owner attribution may be cut off.")
        self.path, self.median, self.maxd = path, median, maxd
        self.n, self.other = len(stacks), other

        self.strict = 0            # leaf class is a map class
        self.extended = 0          # any stdlib-prefix frame is a map class
        self.sub = Counter()       # strict, by sub-family
        self.op = Counter()        # extended, by operation
        self.sub_ext = Counter()   # extended, by sub-family
        self.own_strict = Counter()
        self.own_ext = Counter()
        self.own_total = Counter()   # ALL samples by owner, any leaf family
        self.own_inlined = Counter() # samples where the OWNER IS the leaf
        self.own_op = defaultdict(Counter)
        self.thru_leaf = Counter()  # extended-but-not-strict: what the leaf is
        self.entry = Counter()      # the stdlib entry point nearest the owner
        self.keyside = 0            # own-code frames INSIDE a map operation
        self.keyside_what = Counter()
        self.keyside_owner = Counter()

        for st in stacks:
            own, oi = owner_of(st)
            leaf_cls = st[0][0]
            leaf_is_map = is_map_frame(leaf_cls)
            if leaf_is_map:
                self.strict += 1
                self.sub[sub_family(leaf_cls)] += 1
                if own:
                    self.own_strict[own] += 1

            idx = [i for i, (c, _m) in enumerate(st) if is_map_frame(c)]
            if not idx:
                # no map frame: the TRUE owner is the ordinary nearest-non-stdlib
                if own:
                    self.own_total[own] += 1
                    if oi == 0:
                        self.own_inlined[own] += 1
                continue
            outer = idx[-1]          # map frame closest to the ROOT
            inner = idx[0]           # map frame closest to the LEAF
            # true caller: nearest non-stdlib frame BELOW the outermost map frame
            tru = None
            for cls, meth in st[outer + 1:]:
                if not cls.startswith(STDLIB_PREFIXES):
                    tru = f"{cls.rsplit('.', 1)[-1]}.{meth}"
                    break
            self.extended += 1
            self.sub_ext[sub_family_of_prefix(st[:outer + 1])] += 1
            op = operation(st[outer][1])
            self.op[op] += 1
            self.entry[f"{st[outer][0].rsplit('.', 1)[-1]}.{st[outer][1]}"] += 1
            if tru:
                self.own_ext[tru] += 1
                self.own_total[tru] += 1     # same owner rule as own_ext
                self.own_op[tru][op] += 1
            if not leaf_is_map:
                self.thru_leaf[f"{leaf_cls.rsplit('.', 1)[-1]}.{st[0][1]}"] += 1
            # key-side: an OWN-CODE frame above the outermost map frame is the
            # key's own hashCode/equals running inside the map operation
            if any(not c.startswith(STDLIB_PREFIXES) for c, _m in st[:outer]):
                self.keyside += 1
                for cls, meth in st[:outer]:
                    if not cls.startswith(STDLIB_PREFIXES):
                        self.keyside_what[
                            f"{cls.rsplit('.', 1)[-1]}.{meth}"] += 1
                        break
                if tru:
                    self.keyside_owner[tru] += 1
            _ = inner

    def ms(self, count):
        return count / self.n * self.median


def fmt_ms(procs, counts):
    return " | ".join(f"{p.ms(c):6.1f}" for p, c in zip(procs, counts))


# ---------------------------------------------------------------------- report

def main():
    args = sys.argv[1:]
    rounds, top, tol, only = ["893", "888"], 30, 0.35, None
    if "--rounds" in args:
        i = args.index("--rounds"); rounds = args[i + 1].split(","); del args[i:i + 2]
    if "--top" in args:
        i = args.index("--top"); top = int(args[i + 1]); del args[i:i + 2]
    if "--split-tol" in args:
        i = args.index("--split-tol"); tol = float(args[i + 1]); del args[i:i + 2]
    if "--owner" in args:
        i = args.index("--owner"); only = args[i + 1]; del args[i:i + 2]

    data = {r: [Proc(p, m) for p, m in ROUNDS[r]] for r in rounds}
    allp = [p for r in rounds for p in data[r]]

    print("=" * 78)
    print("ROUND 894 — the residual HashMap/HashSet family, by OWNER and OPERATION")
    print("=" * 78)
    for r in rounds:
        for p in data[r]:
            print(f"  r{r} {p.path.split('/')[-1]:10s} samples={p.n:6d} "
                  f"(other threads {p.other:5d})  maxDepth={p.maxd:4d}  "
                  f"medianMs={p.median:8.1f}")
    print("\n  STRICT   = the sample's LEAF class matches the `HashMap`/`HashSet`"
          " SUBSTRING test")
    print("             (the measure rounds 886/888/893 used — reproduces "
          "893's 1,300.5 ms)")
    print("  EXTENDED = the stack contains a map frame ANYWHERE, charged to the "
          "nearest non-stdlib")
    print("             frame BELOW the OUTERMOST map frame — adds the key's "
          "String.hashCode/equals")
    print("             AND the key's own data-class hashCode(), which no "
          "leaf-or-owner rule can see.")

    print(f"\n{'round':6s} {'measure':10s} {'proc1 ms':>10s} {'proc2 ms':>10s} "
          f"{'mean ms':>9s} {'mean %':>8s}")
    print("-" * 60)
    for r in rounds:
        ps = data[r]
        for label, attr in (("STRICT", "strict"), ("EXTENDED", "extended")):
            cs = [getattr(p, attr) for p in ps]
            mss = [p.ms(c) for p, c in zip(ps, cs)]
            mean = sum(mss) / len(mss)
            pct = sum(100.0 * c / p.n for p, c in zip(ps, cs)) / len(ps)
            print(f"{r:6s} {label:10s} {mss[0]:10.1f} {mss[1]:10.1f} "
                  f"{mean:9.1f} {pct:7.2f}%")

    # ---- sub-family
    print("\n--- SUB-FAMILY (ms/rebuild; STRICT then EXTENDED) " + "-" * 26)
    subs = set()
    for p in allp:
        subs |= set(p.sub) | set(p.sub_ext)
    print(f"{'sub-family':30s}" + "".join(f"{'r' + r + ' str':>10s}{'r' + r + ' ext':>10s}"
                                          for r in rounds))
    for s in sorted(subs, key=lambda s: -sum(p.ms(p.sub_ext.get(s, 0)) for p in allp)):
        cells = ""
        for r in rounds:
            ps = data[r]
            a = sum(p.ms(p.sub.get(s, 0)) for p in ps) / len(ps)
            b = sum(p.ms(p.sub_ext.get(s, 0)) for p in ps) / len(ps)
            cells += f"{a:10.1f}{b:10.1f}"
        print(f"{s:30s}{cells}")

    # ---- operation
    print("\n--- OPERATION (EXTENDED, ms/rebuild) " + "-" * 40)
    ops = set()
    for p in allp:
        ops |= set(p.op)
    print(f"{'operation':16s}" + "".join(f"{'r' + r:>10s}" for r in rounds)
          + "   per-process (last round)")
    for o in sorted(ops, key=lambda o: -sum(p.ms(p.op.get(o, 0)) for p in data[rounds[0]])):
        cells = "".join(
            f"{sum(p.ms(p.op.get(o, 0)) for p in data[r]) / len(data[r]):10.1f}"
            for r in rounds)
        per = " | ".join(f"{p.ms(p.op.get(o, 0)):6.1f}" for p in data[rounds[0]])
        print(f"{o:16s}{cells}   {per}")

    # ---- stdlib entry points
    print("\n--- STDLIB ENTRY POINT nearest the owner (round "
          f"{rounds[0]}, EXTENDED, ms/rebuild) " + "-" * 6)
    ent = Counter()
    for p in data[rounds[0]]:
        for k, v in p.entry.items():
            ent[k] += v
    tot = sum(p.n for p in data[rounds[0]])
    med = sum(p.median for p in data[rounds[0]]) / len(data[rounds[0]])
    for k, v in ent.most_common(18):
        print(f"   {v / tot * med:7.1f} ms  {k}")

    # ---- what the EXTENDED measure adds
    print(f"\n--- EXTENDED-minus-STRICT: the LEAF the map reached through "
          f"(round {rounds[0]}) " + "-" * 3)
    thru = Counter()
    for p in data[rounds[0]]:
        for k, v in p.thru_leaf.items():
            thru[k] += v
    for k, v in thru.most_common(12):
        print(f"   {v / tot * med:7.1f} ms  {k}")

    # ---- mechanism grouping (round 874's law: the FAMILY is the unit)
    print("\n--- MECHANISM GROUPS (EXTENDED, ms/rebuild) " + "-" * 34)
    print("      the ROW is the wrong unit at this diffusion — top owner is "
          "under 1% of a rebuild.")
    mnames = [m for m, _ in MECHANISMS] + ["residue (unclassified)"]
    print(f"{'mechanism':34s}" + "".join(f"{'r' + r:>10s}" for r in rounds)
          + f"   {rounds[0]} p1 | p2   owners")
    mrows = []
    for m in mnames:
        cells = []
        for r in rounds:
            ps = data[r]
            cells.append(sum(p.ms(sum(v for k, v in p.own_ext.items()
                                      if mechanism(k) == m)) for p in ps) / len(ps))
        per = [p.ms(sum(v for k, v in p.own_ext.items() if mechanism(k) == m))
               for p in data[rounds[0]]]
        nown = len({k for p in data[rounds[0]] for k in p.own_ext
                    if mechanism(k) == m})
        mrows.append((cells[0], m, cells, per, nown))
    mrows.sort(reverse=True)
    for _, m, cells, per, nown in mrows:
        print(f"{m:34s}" + "".join(f"{c:10.1f}" for c in cells)
              + f"   {per[0]:6.1f} | {per[1]:6.1f}  {nown:4d}")
    print(f"{'TOTAL':34s}" + "".join(
        f"{sum(p.ms(p.extended) for p in data[r]) / len(data[r]):10.1f}"
        for r in rounds))

    # ---- key-side (the round-471 hazard, priced)
    print(f"\n--- KEY-SIDE: own-code hashCode/equals running INSIDE a map "
          f"operation " + "-" * 6)
    print("      invisible to BOTH a leaf-class family table and a "
          "nearest-non-stdlib-owner table.")
    for r in rounds:
        ps = data[r]
        mss = [p.ms(p.keyside) for p in ps]
        print(f"   round {r}: {sum(mss) / len(mss):7.1f} ms/rebuild "
              f"(per process {mss[0]:.1f} | {mss[1]:.1f})")
    kw = Counter()
    for p in data[rounds[0]]:
        kw.update(p.keyside_what)
    for k, v in kw.most_common(12):
        print(f"      {v / tot * med:7.1f} ms  {k}")

    # ---- owners
    if only:
        print(f"\n--- OWNER DETAIL: {only} " + "-" * 40)
        for r in rounds:
            for p in data[r]:
                c = p.own_ext.get(only, 0)
                mix = ", ".join(f"{o}:{n}" for o, n in p.own_op[only].most_common())
                print(f"  r{r} {p.path.split('/')[-1]:10s} "
                      f"{p.ms(c):7.1f} ms  n={c:4d}  [{mix}]")
        return

    print(f"\n--- TOP {top} OWNERS by EXTENDED ms/rebuild " + "-" * 30)
    print("      'n' is TOTAL samples across the round's two processes and "
          "'+-' is 1 sigma Poisson (sqrt(n)/n) — a 20-sample row carries +-22%,")
    print("      so a within-round disagreement smaller than that is NOISE, "
          "not a split.")
    print("      'TOT' is the owner's TOTAL ms/rebuild across ALL leaf families "
          "— the map row can never")
    print("      exceed it, and TOT is what an owner-level fix is bounded by.")
    hdr = "  ".join(f"r{r}p1  r{r}p2" for r in rounds)
    print(f"\n{'#':>3} {'owner':46s} {'mean':>7s} {'+-':>5s} {'n':>4s} "
          f"{'TOT':>6s}  {hdr}   op mix (round {rounds[0]})")
    keys = set()
    for p in allp:
        keys |= set(p.own_ext)
    rows = []
    for k in keys:
        ps0 = data[rounds[0]]
        mean0 = sum(p.ms(p.own_ext.get(k, 0)) for p in ps0) / len(ps0)
        rows.append((mean0, k))
    rows.sort(reverse=True)
    for i, (mean0, k) in enumerate(rows[:top], 1):
        cells = []
        for r in rounds:
            for p in data[r]:
                cells.append(f"{p.ms(p.own_ext.get(k, 0)):5.1f}")
        ps0 = data[rounds[0]]
        nsamp = sum(p.own_ext.get(k, 0) for p in ps0)
        sigma = mean0 / (nsamp ** 0.5) if nsamp else 0.0
        a, b = (p.ms(p.own_ext.get(k, 0)) for p in ps0)
        flag = ""
        # only flag a disagreement that EXCEEDS what Poisson alone explains
        if max(a, b) > 8.0 and abs(a - b) > max(tol * max(a, b), 3.0 * sigma):
            flag = " SPLIT?"
        mix = Counter()
        for p in ps0:
            mix.update(p.own_op[k])
        tot_mix = sum(mix.values()) or 1
        mixs = " ".join(f"{o}{100 * n // tot_mix}%" for o, n in mix.most_common(3))
        tot0 = sum(p.ms(p.own_total.get(k, 0)) for p in ps0) / len(ps0)
        print(f"{i:>3} {k[:46]:46s} {mean0:7.1f} {sigma:5.1f} {nsamp:4d} "
              f"{tot0:6.1f}  " + "  ".join(cells) + f"   {mixs}{flag}")

    # ---- cross-round movers: a large single-row delta is as often a C2
    # inlining KEY SPLIT as a real change (round 888 § C), so print the movers
    # in BOTH directions together — a split shows up as a matched pair.
    if len(rounds) >= 2:
        ra, rb = rounds[0], rounds[1]
        print(f"\n--- CROSS-ROUND MOVERS r{rb} -> r{ra} (ms/rebuild) " + "-" * 22)
        print("      read UP and DOWN together: a row that fell by ~X beside a "
              "row that rose by ~X is one")
        print("      method's samples moving between two owner names (C2 "
              "inlining), NOT a saving.")
        mv = []
        for k in keys:
            pa = sum(p.ms(p.own_ext.get(k, 0)) for p in data[ra]) / len(data[ra])
            pb = sum(p.ms(p.own_ext.get(k, 0)) for p in data[rb]) / len(data[rb])
            if max(pa, pb) >= 10.0:
                mv.append((pa - pb, k, pb, pa))
        mv.sort()

        def tot_of(k, r):
            return sum(p.ms(p.own_total.get(k, 0)) for p in data[r]) / len(data[r])

        def line(d, k, pb, pa):
            tb, ta = tot_of(k, rb), tot_of(k, ra)
            # An owner whose TOTAL cost is flat while its MAP share moves has
            # not changed: C2 inlined the stdlib frames away in one round, so
            # the same work sat in the "own code" family instead (round 868).
            note = ""
            if max(tb, ta) > 0 and abs(ta - tb) < 0.4 * max(tb, ta) \
                    and abs(d) > 0.5 * max(tb, ta):
                note = "  <- INLINE MIGRATION (owner total flat)"
            print(f"   {d:+7.1f}  {k[:46]:46s} {pb:6.1f} -> {pa:6.1f}   "
                  f"[owner total {tb:6.1f} -> {ta:6.1f}]{note}")

        for d, k, pb, pa in mv[:8]:
            line(d, k, pb, pa)
        print("   ...")
        for d, k, pb, pa in mv[-8:]:
            line(d, k, pb, pa)

    print("\nNOTE  ms/rebuild uses each PROCESS's own medianMs (round 870).")
    print("NOTE  a row is CANDIDATE, not a price (round 623: a 5.3% leaf "
          "eliminated measured -0.3%).")
    print("NOTE  SPLIT? = the two same-round processes disagree by more than "
          f"max({tol:.0%}, 3 sigma) on a row over 8 ms — check for a C2 "
          "inlining key split (round 888 § C) before reading it.")


if __name__ == "__main__":
    main()

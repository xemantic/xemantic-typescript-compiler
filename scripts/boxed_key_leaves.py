#!/usr/bin/env python3
"""(WARM.31) round 904 — which OWNERS hold the boxed-primitive key leaf work.

Filters an xtsc `jfr print --stack-depth 512` dump to samples whose LEAF is a
boxed-primitive method (Integer/Long .equals/.hashCode/.valueOf) and charges
each to its nearest non-stdlib OWNER, exactly as scripts/leaf_owner_profile.py
does for the whole profile.  Build-free: it re-reads dumps already on the box.

WHAT IT FOUND, AND WHY THE OUTPUT MUST BE READ ACROSS >= 2 PROCESSES. Run over
round 899's own two dumps — same binary, same round — it reads **72.9 ms** and
**19.0 ms** per rebuild for this family: a **4x** disagreement, because C2
inlined `Integer.equals` into its callers in the second process. That is round
868's law ("LEAF attribution is NOT stable across processes") biting a whole
mechanism rather than one row, and it is why round 899 § 33.8's quoted 29.4 ms
could not be acted on. The non-JFR answer, measured by counting the operations
and pricing one of them, is **17.7 ms for all 14 sites together**
(`docs/perf/boxed-primitive-key-price.md`).

So: use this to LOCATE a boxed-key owner, never to price one. The price is
`population x premium`, the premium is 6.58 ns, and the population is
`--boxedKeyCensus`.

Usage: boxed_key_leaves.py deep1.txt deep2.txt [--rebuild-ms 5240]
"""
import re
import sys
from collections import Counter

STDLIB_PREFIXES = ("java.", "jdk.", "sun.", "kotlin.", "kotlinx.")
FRAME_RE = re.compile(r"^\s+([A-Za-z0-9_.$]+)\.([A-Za-z0-9_$<>]+)\(")
THREAD_RE = re.compile(r'^\s*sampledThread = "([^"]*)"')

BOXED = ("java.lang.Integer", "java.lang.Long", "java.lang.Short",
         "java.lang.Character", "java.lang.Byte", "java.lang.Boolean")


def parse(path, thread="xtsc-deep-stack"):
    stacks, cur, other, maxd, keep = [], [], 0, 0, False
    with open(path, errors="replace") as fh:
        for line in fh:
            if line.startswith("jdk.ExecutionSample"):
                if cur:
                    stacks.append(cur)
                cur, keep = [], False
                continue
            m = THREAD_RE.match(line)
            if m:
                keep = (m.group(1) == thread)
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


def owner(stack):
    for cls, meth in stack:
        if not cls.startswith(STDLIB_PREFIXES):
            return f"{cls.rsplit('.', 1)[-1]}.{meth}"
    return None


def main():
    args = sys.argv[1:]
    rebuild_ms = 5240.0
    while "--rebuild-ms" in args:
        i = args.index("--rebuild-ms")
        rebuild_ms = float(args[i + 1])
        del args[i:i + 2]

    for path in args:
        stacks, other, maxd = parse(path)
        if maxd <= 5:
            sys.exit(f"REFUSED: {path} truncated to 5 frames")
        n = len(stacks)
        boxed = [s for s in stacks if s[0][0].startswith(BOXED)]
        # split by leaf method
        leafm = Counter(f"{s[0][0].rsplit('.',1)[-1]}.{s[0][1]}" for s in boxed)
        own = Counter(owner(s) for s in boxed)
        own.pop(None, None)
        # what map op is it under (nearest java.util frame above the leaf)
        mapop = Counter()
        for s in boxed:
            op = "(no java.util frame)"
            for cls, meth in s[1:6]:
                if cls.startswith("java.util"):
                    op = f"{cls.rsplit('.',1)[-1]}.{meth}"
                    break
            mapop[op] += 1
        print(f"== {path}: {n} compile-thread samples ({other} other), maxdepth {maxd}")
        print(f"   boxed-primitive LEAF samples: {len(boxed)} = "
              f"{100.0*len(boxed)/n:.3f}%  = {rebuild_ms*len(boxed)/n:.2f} ms/rebuild")
        print("   by leaf method:")
        for k, v in leafm.most_common(10):
            print(f"     {k:34s} {v:5d}  {rebuild_ms*v/n:6.2f} ms")
        print("   by enclosing java.util op:")
        for k, v in mapop.most_common(10):
            print(f"     {k:34s} {v:5d}  {rebuild_ms*v/n:6.2f} ms")
        print("   by nearest non-stdlib OWNER:")
        for k, v in own.most_common(25):
            print(f"     {k:52s} {v:5d}  {rebuild_ms*v/n:6.2f} ms")
        print()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""Warm LEAF profile of an xtsc `jdk.ExecutionSample` recording, by nearest
non-stdlib OWNER.

Round 868 established the three traps this script exists to avoid, and round
870 froze them into a reusable instrument instead of ad-hoc shell:

1. `jfr print` TRUNCATES EVERY STACK TO ITS TOP 5 FRAMES unless
   `--stack-depth` is passed, silently (the stack merely ends in `...`).
   `scripts/aggregate_jfr.py` does not pass it, so its INCLUSIVE table is
   "inclusive within five frames of the leaf" — which read `checkSpine` at
   15.6% against this arc's known ~74%. This script REFUSES a dump whose
   deepest stack is 5 frames, and prints the observed max depth so an
   inclusive number can be sanity-checked before it is believed.
2. ~3% of the samples are the JFR recorder thread and the crawl's
   `DefaultDispatcher-worker-*` coroutines. Filter to the compile thread
   (`xtsc-deep-stack`) or the instrument is partly watching itself.
3. LEAF (frame-0) attribution is NOT STABLE ACROSS PROCESSES: `HashMap.getNode`
   read 9.66% in one process and 3.70% in another, on the same binary, because
   C2 inlined it into its callers in the second. So the ranked table is by
   nearest non-stdlib OWNER — stdlib work is charged to the frame that asked
   for it — which replicates to a few tenths of a percent. Always run >= 2
   processes and report both.

And the standing caveat that no table here may be read past: a JFR leaf self-%
is NOT a wall-clock price (round 623 eliminated a 5.3%-of-samples leaf and
measured -0.3%). The output is a CANDIDATE LIST.

Usage:
    python3 scripts/leaf_owner_profile.py deep1.txt [deep2.txt ...] [--top N]
                                          [--thread NAME] [--inclusive-of M]
"""
import re
import sys
from collections import Counter

# Charged THROUGH to the nearest caller below: this is stdlib work performed on
# behalf of xtsc code, and which frame it lands in is a C2 inlining accident.
STDLIB_PREFIXES = (
    "java.", "jdk.", "sun.", "kotlin.", "kotlinx.",
)

FRAME_RE = re.compile(r"^\s+([A-Za-z0-9_.$]+)\.([A-Za-z0-9_$<>]+)\(")
THREAD_RE = re.compile(r'^\s*sampledThread = "([^"]*)"')


def family(cls: str) -> str:
    """Coarse allocation-family bucket for a LEAF class (§ 2 of the doc)."""
    short = cls.rsplit(".", 1)[-1]
    if not cls.startswith(STDLIB_PREFIXES):
        return "own code"
    if "HashMap" in short or "HashSet" in short:
        return "HashMap / HashSet"
    if short.startswith(("String", "AbstractStringBuilder")) or "StringsKt" in short:
        return "String / StringBuilder"
    if short.startswith(("ArrayList", "ArrayDeque", "Arrays")):
        return "ArrayList / ArrayDeque"
    if short.startswith("Intrinsics"):
        return "Intrinsics.areEqual"
    if short.startswith("regex") or ".regex." in cls:
        return "java.util.regex"
    return "other stdlib"


def parse(path: str, thread: str):
    """-> (list of stacks, samples on other threads, max observed depth)."""
    stacks, cur, other, maxd = [], [], 0, 0
    cur_thread = None
    keep = False
    with open(path, errors="replace") as fh:
        for line in fh:
            if line.startswith("jdk.ExecutionSample"):
                if cur:
                    stacks.append(cur)
                cur, cur_thread, keep = [], None, False
                continue
            m = THREAD_RE.match(line)
            if m:
                cur_thread = m.group(1)
                keep = (thread is None or cur_thread == thread)
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
    """Nearest non-stdlib frame at or below the leaf; None if the whole stack is stdlib."""
    for cls, meth in stack:
        if not cls.startswith(STDLIB_PREFIXES):
            return f"{cls.rsplit('.', 1)[-1]}.{meth}"
    return None


def main() -> None:
    args = sys.argv[1:]
    top = 25
    thread = "xtsc-deep-stack"
    incl_of = None
    while "--top" in args:
        i = args.index("--top"); top = int(args[i + 1]); del args[i:i + 2]
    while "--thread" in args:
        i = args.index("--thread"); thread = args[i + 1] or None; del args[i:i + 2]
    while "--inclusive-of" in args:
        i = args.index("--inclusive-of"); incl_of = args[i + 1]; del args[i:i + 2]
    if not args:
        sys.exit(__doc__)

    tables = []
    for path in args:
        stacks, other, maxd = parse(path, thread)
        n = len(stacks)
        if maxd <= 5:
            sys.exit(f"REFUSED: {path} has max stack depth {maxd} — `jfr print` was run "
                     f"WITHOUT --stack-depth and every stack is truncated to 5 frames.")
        own = Counter(owner(s) for s in stacks)
        own.pop(None, None)
        fam = Counter(family(s[0][0]) for s in stacks)
        incl = None
        if incl_of:
            incl = sum(1 for s in stacks
                       if any(incl_of in f"{c}.{m}" for c, m in s))
        tables.append((path, n, other, maxd, own, fam, incl))
        print(f"== {path}: {n} samples on thread '{thread}' "
              f"({other} on other threads), max stack depth {maxd}")
        if incl is not None:
            print(f"   INCLUSIVE of '{incl_of}': {incl} = {100.0 * incl / n:.2f}%")
        print("   family shares (by LEAF class):")
        for k, v in fam.most_common():
            print(f"     {k:28s} {100.0 * v / n:6.2f}%")
    print()

    # merged ranking: sort by mean share across runs, print each run's own share
    keys = set()
    for _, _, _, _, own, _, _ in tables:
        keys |= set(own)
    rows = []
    for k in keys:
        shares = [100.0 * t[4].get(k, 0) / t[1] for t in tables]
        rows.append((sum(shares) / len(shares), k, shares))
    rows.sort(reverse=True)
    hdr = " | ".join(f"run{i + 1}" for i in range(len(tables)))
    print(f"{'#':>3}  {'owner':52s} {hdr}")
    for i, (_, k, shares) in enumerate(rows[:top], 1):
        print(f"{i:>3}  {k:52s} " + " | ".join(f"{s:5.2f}%" for s in shares))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""Aggregate jdk.ExecutionSample stacks from a JFR recording.

Usage:
    python3 scripts/aggregate_jfr.py recording.jfr [topN] [--callers-of METHOD]

Reports: total samples, SELF time by method (leaf frame), INCLUSIVE time by
method (any frame, deduped per sample), INCLUSIVE time by class, and — with
--callers-of — the direct-caller attribution for one method (first frame below
it that isn't a collection/stdlib frame).

See docs/parallel-caching.md § "JFR profiling how-to" for the recording flags
(stackdepth=1024 matters: deep flow-walk stacks truncate at the cap, so
caller attribution shows a "?" bucket = roots lost to truncation — read it as
"deep recursion", not noise).
"""
import os
import re
import shutil
import subprocess
import sys
from collections import Counter


def find_jfr_tool() -> str:
    """`jfr` is often not on PATH — resolve it next to the `java` binary."""
    tool = shutil.which("jfr")
    if tool:
        return tool
    candidates = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(os.path.join(java_home, "bin", "jfr"))
    java = shutil.which("java")
    if java:
        candidates.append(os.path.join(os.path.dirname(os.path.realpath(java)), "jfr"))
    if sys.platform == "darwin":
        try:
            home = subprocess.run(["/usr/libexec/java_home"], capture_output=True,
                                  text=True, check=True).stdout.strip()
            candidates.append(os.path.join(home, "bin", "jfr"))
        except Exception:
            pass
    for c in candidates:
        if os.path.isfile(c):
            return c
    sys.exit("error: `jfr` tool not found (tried PATH, JAVA_HOME, java's dir)")


STDLIB_PREFIXES = (
    "HashMap.", "HashSet.", "LinkedHashMap", "LinkedHashSet", "AbstractCollection.",
    "AbstractMap", "AbstractSet", "ArrayList", "ArraysSupport.", "String",
    "Intrinsics.", "CollectionsKt", "MapsKt", "SetsKt", "ArraysKt", "StringsKt",
)


def main() -> None:
    args = [a for a in sys.argv[1:]]
    callers_of = None
    if "--callers-of" in args:
        i = args.index("--callers-of")
        callers_of = args[i + 1]
        del args[i:i + 2]
    # (INV.0) --event lets the same aggregation run over allocation samples
    # (jdk.ObjectAllocationSample — enabled by settings=profile), which is the
    # split receipts' allocation profile. Counts are SAMPLES, not bytes.
    event = "jdk.ExecutionSample"
    if "--event" in args:
        i = args.index("--event")
        event = args[i + 1]
        del args[i:i + 2]
    jfr_file = args[0]
    topn = int(args[1]) if len(args) > 1 else 30

    proc = subprocess.Popen(
        [find_jfr_tool(), "print", "--events", event, jfr_file],
        stdout=subprocess.PIPE, text=True, errors="replace")

    frame_re = re.compile(r"^\s+(\S+?)\.([A-Za-z0-9_$<>]+)\([^)]*\)")
    stacks: list[list[str]] = []
    cur: list[str] = []
    in_stack = False
    assert proc.stdout is not None
    for line in proc.stdout:
        if line.startswith(event):
            if cur:
                stacks.append(cur)
            cur, in_stack = [], False
        elif "stackTrace = [" in line:
            in_stack = True
        elif in_stack:
            m = frame_re.match(line)
            if m:
                cur.append(m.group(1).split(".")[-1] + "." + m.group(2))
            elif line.strip() == "]":
                in_stack = False
    if cur:
        stacks.append(cur)

    total = len(stacks)
    leaf: Counter = Counter()
    inclusive: Counter = Counter()
    inclusive_class: Counter = Counter()
    for s in stacks:
        leaf[s[0]] += 1
        for m in set(s):
            inclusive[m] += 1
        for c in {f.split(".")[0] for f in s}:
            inclusive_class[c] += 1

    def show(counter: Counter, title: str, n: int) -> None:
        print(f"\n=== {title} (of {total} samples) ===")
        for name, c in counter.most_common(n):
            print(f"{c:6d}  {100.0 * c / total:5.1f}%  {name}")

    show(leaf, "SELF time by method (leaf frame)", topn)
    show(inclusive, "INCLUSIVE time by method", topn)
    show(inclusive_class, "INCLUSIVE time by class", 20)

    if callers_of:
        callers: Counter = Counter()
        n = 0
        for s in stacks:
            idx = [i for i, f in enumerate(s) if callers_of in f]
            if not idx:
                continue
            n += 1
            below = s[max(idx) + 1:]
            direct = next(
                (f for f in below if not any(f.startswith(p) for p in STDLIB_PREFIXES)),
                "? (stack truncated — deep recursion)")
            callers[direct] += 1
        print(f"\n=== callers of '{callers_of}' ({n} samples) ===")
        for k, v in callers.most_common(15):
            print(f"{v:6d}  {k}")


if __name__ == "__main__":
    main()

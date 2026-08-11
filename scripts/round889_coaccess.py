#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""Round 889 — the STATIC co-access census of the checker's id-keyed containers.

The question is NOT "how many id-keyed maps are there" (round 886 already said
"one map per FACT" is the shape). It is the one that decides whether tsgo's
`core.LinkStore[K,V]` shape ports at all:

    **which containers are probed with the SAME KEY at the SAME SITE, so that
    ONE probe returning a struct of co-accessed fields would replace N probes?**

A cluster is `(enclosing function, normalized key expression)` at which two or
more DISTINCT id-keyed containers are probed. Clusters are ranked by
`(distinct containers - 1) * probe sites`, i.e. by probes a merge could remove
— never by container size (round 732's law: a COUNT of avoidable work is not a
MEASURE of it, so the ranking is a candidate list that the profile then weights).

Round 809's stripper invariant is obeyed: comments and string/char literals are
blanked at PRESERVED LENGTH, so every reported offset is a real one and the
brace matcher cannot desynchronise on Checker.kt's raw-string regexes.

Usage:
    round889_coaccess.py [--min-containers N] [--top N] [--dump-fields]
"""
import argparse
import re
import sys
from collections import Counter, defaultdict

HASH_LEAF = ("java.util.HashMap.", "java.util.HashSet.",
             "java.util.LinkedHashMap.", "java.util.LinkedHashSet.")


def parse_dump(path):
    """JFR text dump -> [stack], compile thread only (round 868's filter)."""
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
                ok = "xtsc-deep-stack" in s
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

CHECKER = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"
COMMON_MAIN = sorted(
    __import__("glob").glob(
        "xemantic-typescript-compiler-core/src/commonMain/kotlin/**/*.kt",
        recursive=True))

# ---------------------------------------------------------------- stripper

_TOKENS = re.compile(
    r"""(?P<blk>/\*)
      | (?P<line>//[^\n]*)
      | (?P<raw>\"\"\"(?:.|\n)*?\"\"\")
      | (?P<str>"(?:\\.|[^"\\\n])*")
      | (?P<chr>'(?:\\u[0-9a-fA-F]{4}|\\.|[^'\\])')
    """,
    re.X,
)


def _blank(s):
    return "".join("\n" if c == "\n" else " " for c in s)


def strip_preserving_length(text):
    out, i, n = [], 0, len(text)
    while i < n:
        m = _TOKENS.search(text, i)
        if not m:
            out.append(text[i:])
            break
        out.append(text[i:m.start()])
        if m.lastgroup == "blk":
            depth, j = 1, m.end()
            while j < n and depth:
                if text.startswith("/*", j):
                    depth += 1
                    j += 2
                elif text.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            out.append(_blank(text[m.start():j]))
            i = j
        else:
            out.append(_blank(m.group(0)))
            i = m.end()
    return "".join(out)


# ------------------------------------------------------------- key shapes

# A key expression the census can compare ACROSS containers: a bare identifier
# or a simple dotted path, optionally `!!`-asserted. Anything with a call, an
# operator or an index in it is skipped — two occurrences of such a text are not
# reliably the same value, and a cluster built on them would be fiction.
#
# Round 889 note: an EARLIER version of this restricted keys to id-ish shapes
# (`x.id`, `nodeId`), on round 886's hypothesis that the family is tsgo's
# per-entity fact probing. That restriction hid the largest real cluster in the
# compiler, which is STRING-keyed (`lookupPerFileForNode`'s name). The
# co-access question is key-TYPE-agnostic; only the key's IDENTITY matters.
KEY_RE = re.compile(
    r"""^[A-Za-z_][A-Za-z0-9_]*
         (?:[.?]{1,2}[A-Za-z_][A-Za-z0-9_]*)*
         (?:!!)?$""",
    re.X,
)

PROBE_RE = re.compile(
    r"""(?<![.\w])(?P<map>[A-Za-z_][A-Za-z0-9_]*)
        (?:
            \[(?P<k1>[^\[\]\n]{1,60}?)\]
          | \.(?P<op>getOrPut|containsKey|contains|getValue|get|put|add|remove)
            \((?P<k2>[^(),\n]{1,60}?)(?P<tail>[,)])
        )""",
    re.X,
)

# `name in container` — the Kotlin idiom for containsKey/contains, and the shape
# `lookupPerFileForNode`'s largest cluster is written in.
IN_RE = re.compile(
    r"""(?<![.\w])(?P<key>[A-Za-z_][A-Za-z0-9_]*(?:[.?]{1,2}[A-Za-z_][A-Za-z0-9_]*)*)
        \s+(?:!)?in\s+
        (?P<map>[A-Za-z_][A-Za-z0-9_]*)
        (?![.\w(\[])""",
    re.X,
)

# Containers whose key is NOT an entity id even though the expression looks
# like one, or which are not caches at all.
NOT_A_CONTAINER = {
    "listOf", "setOf", "mapOf", "arrayOf", "buildList", "buildMap", "it",
    "this", "require", "check", "println", "String", "Regex", "maxOf", "minOf",
}


def functions(src):
    """[(name, start, end)] for every `fun <name>(` — nested ones included."""
    out = []
    for m in re.finditer(r"(?<![\w.])fun\s+(?:<[^>]{1,80}>\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(", src):
        name = m.group(1)
        brace = src.find("{", m.end())
        eq = src.find("=", m.end())
        nl = src.find("\n", m.end())
        # expression-bodied function: no opening brace before the `=`
        if brace < 0:
            continue
        if 0 <= eq < brace and eq < nl + 400:
            # take the expression body: to the end of the balanced expression
            depth, j = 0, eq
            while j < len(src):
                c = src[j]
                if c in "([{":
                    depth += 1
                elif c in ")]}":
                    depth -= 1
                    if depth < 0:
                        break
                elif c == "\n" and depth == 0 and j > eq + 1:
                    nxt = src[j + 1:j + 200].lstrip()
                    if nxt.startswith(("fun ", "private ", "internal ", "val ",
                                       "var ", "}", "/**", "@")):
                        break
                j += 1
            out.append((name, m.start(), j))
            continue
        depth, j = 0, brace
        while j < len(src):
            if src[j] == "{":
                depth += 1
            elif src[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        out.append((name, m.start(), j))
    return out


def innermost(fns, off):
    best = None
    for name, s, e in fns:
        if s <= off <= e and (best is None or s > best[1]):
            best = (name, s, e)
    return best[0] if best else "<top-level>"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-containers", type=int, default=2)
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--dump-fields", action="store_true")
    ap.add_argument("--profile", nargs="*", default=[],
                    help="JFR text dumps; ranks clusters by the HASH-family "
                         "samples charged to their enclosing function")
    args = ap.parse_args()

    # Round 732's law: a COUNT of avoidable probes is not a MEASURE of them.
    # With --profile the static clusters are ranked by the hash-family samples
    # their enclosing function OWNS, so a cluster of 20 cold sites cannot
    # outrank one on a 2-million-call path.
    weight = Counter()
    hash_total = 0
    for path in args.profile:
        for st in parse_dump(path):
            if not any(p in st[0] for p in HASH_LEAF):
                continue
            hash_total += 1
            owner = next((f for f in st if f.startswith("com.xemantic.")), None)
            if owner:
                weight[owner.rsplit(".", 1)[1]] += 1

    raw = open(CHECKER, encoding="utf-8").read()
    src = strip_preserving_length(raw)
    assert len(src) == len(raw)
    for a, b in zip(src.split("\n"), raw.split("\n")):
        assert len(a) == len(b), "stripper broke the length invariant"

    # Container names are harvested from EVERY commonMain source, not only
    # Checker.kt: the largest clusters probe a container declared on another
    # class (`LexicalScope.symbols`, `BinderResult.locals`).
    fields = set()
    for path in COMMON_MAIN:
        text = strip_preserving_length(open(path, encoding="utf-8").read())
        fields |= set(re.findall(
            r"va[lr]\s+([A-Za-z_][A-Za-z0-9_]*)\s*"
            r"(?::[^=\n]{0,140})?=\s*(?:HashMap|HashSet|IntKeyMap|LongKeyMap|"
            r"mutableMapOf|mutableSetOf|LinkedHashMap|LinkedHashSet|buildMap|"
            r"buildSet|hashMapOf|hashSetOf|EpochMap|EpochSet)", text))
        fields |= set(re.findall(
            r"va[lr]\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*"
            r"(?:Mutable)?(?:Map|Set|HashMap|HashSet|IntKeyMap|LongKeyMap|"
            r"EpochMap|EpochSet)\s*[<(]", text))
    if args.dump_fields:
        for f in sorted(fields):
            print(f)
        return

    fns = functions(src)
    # (fn, key) -> {container -> [line, ...]}
    clusters = defaultdict(lambda: defaultdict(list))
    per_container = defaultdict(int)
    line_of = [0] * (len(src) + 1)
    ln = 1
    for i, c in enumerate(src):
        line_of[i] = ln
        if c == "\n":
            ln += 1
    line_of[len(src)] = ln

    def record(mapname, key, off):
        mapname = mapname.replace("?.", ".")
        last = mapname.rsplit(".", 1)[-1]
        if last in NOT_A_CONTAINER or last not in fields:
            return
        key = key.strip()
        if not KEY_RE.match(key):
            return
        key = key.rstrip("!")
        clusters[(innermost(fns, off), key)][mapname].append(line_of[off])
        per_container[mapname] += 1

    for m in PROBE_RE.finditer(src):
        record(m.group("map"), m.group("k1") or m.group("k2") or "", m.start())
    for m in IN_RE.finditer(src):
        record(m.group("map"), m.group("key"), m.start())

    rows = []
    for (fn, key), by_map in clusters.items():
        n = len(by_map)
        if n < args.min_containers:
            continue
        sites = sum(len(v) for v in by_map.values())
        rows.append((weight.get(fn, 0), sites - max(len(v) for v in by_map.values()),
                     n, sites, fn, key, by_map))
    rows.sort(reverse=True, key=lambda r: (r[0], r[1], r[2]))

    print(f"containers seen: {len(per_container)}   "
          f"probe sites: {sum(per_container.values())}   "
          f"co-access clusters (>= {args.min_containers} containers): {len(rows)}")
    if args.profile:
        print(f"profile: {hash_total} hash-family samples over {len(args.profile)} dump(s)")
    print()
    print(f"{'hashsmp':>7} {'removable':>9} {'maps':>4} {'sites':>5}  function / key")
    for w, removable, n, sites, fn, key, by_map in rows[:args.top]:
        print(f"{w:>7} {removable:>9} {n:>4} {sites:>5}  {fn}  [{key}]")
        for mapname, lines in sorted(by_map.items(), key=lambda kv: -len(kv[1])):
            print(f"{'':>29}  {mapname} x{len(lines)}  @{lines[:6]}")
    print()
    print("top containers by id-keyed probe SITES in source:")
    for name, n in sorted(per_container.items(), key=lambda kv: -kv[1])[:25]:
        print(f"  {n:>4}  {name}")


if __name__ == "__main__":
    main()

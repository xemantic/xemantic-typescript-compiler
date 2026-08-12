#!/usr/bin/env python3
"""(WARM.19) round 895 — route every WHOLE-SOURCE substring scan in Checker.kt
through the `srcHas` / `srcIndexOf` / `srcLastIndexOf` helpers.

The rewrite is purely syntactic and behaviour-preserving BY THE HELPERS'
DEFINITION (`srcHas(s, n) === s.contains(n)`), so the only risks it carries are
mangling and mis-typing — and the Kotlin compiler catches the second, because the
helpers take `String` for both parameters. A `Char` first argument, or a receiver
that is not a `String`, fails to compile rather than silently changing meaning.

What is DELIBERATELY not rewritten:
  * `indexOf('c', i)` / `lastIndexOf('c', i)` — a CHAR search, and one that is
    bounded to a node position; these are the cheap majority and the filter has
    nothing to offer them (a single character is under `SourceScanFilter.K`).
  * `startsWith` / `endsWith` — already O(needle).

Run:  python3 scripts/round895_srcscan_apply.py [--check]
`--check` reports the plan and writes nothing.
"""

import re
import sys

CHECKER = "xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt"

# Receivers proven (by their `val` bindings) to hold a source file's whole text.
# `src` is EXCLUDED: 14 of its 18 bindings in this file are Types, not text.
SRC_VARS = ["source", "augSource", "targetSource2460", "targetSource", "targetSrc", "otherSource"]

OPS = {"contains": "srcHas", "indexOf": "srcIndexOf", "lastIndexOf": "srcLastIndexOf"}

CALL = re.compile(
    r"\b(" + "|".join(SRC_VARS) + r")\.(contains|indexOf|lastIndexOf)\("
)


def split_args(s):
    """Args of a call whose '(' has just been consumed; None if unbalanced."""
    depth = 0
    out = []
    cur = ""
    i = 0
    instr = None
    while i < len(s):
        ch = s[i]
        if instr:
            if ch == "\\":
                cur += s[i:i + 2]
                i += 2
                continue
            if ch == instr:
                instr = None
            cur += ch
            i += 1
            continue
        if ch in "\"'":
            instr = ch
            cur += ch
            i += 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                out.append(cur)
                return out, i
            depth -= 1
        elif ch == "," and depth == 0:
            out.append(cur)
            cur = ""
            i += 1
            continue
        cur += ch
        i += 1
    return None, -1


def rewrite_line(line):
    """Returns (newline, n_rewritten, n_skipped_char)."""
    out = line
    n = 0
    skipped = 0
    while True:
        m = CALL.search(out)
        # rescan from scratch each time, but only act on calls not yet rewritten
        found = None
        for m in CALL.finditer(out):
            args, close = split_args(out[m.end():])
            if args is None:
                continue
            first = args[0].strip()
            if first.startswith("'"):
                skipped += 1
                continue
            if len(args) not in (1, 2):
                skipped += 1
                continue
            found = (m, args, close)
            break
        if found is None:
            return out, n, skipped
        m, args, close = found
        recv, op = m.group(1), m.group(2)
        helper = OPS[op]
        newcall = f"{helper}({recv}, " + ", ".join(a.strip() for a in args) + ")"
        out = out[:m.start()] + newcall + out[m.end() + close + 1:]
        n += 1


def main():
    check = "--check" in sys.argv
    lines = open(CHECKER, encoding="utf-8", errors="surrogateescape").read().split("\n")
    total = 0
    skipped = 0
    touched = 0
    out = []
    for i, line in enumerate(lines):
        # never rewrite inside a KDoc / comment line
        if line.lstrip().startswith("*") or line.lstrip().startswith("//"):
            out.append(line)
            continue
        new, n, sk = rewrite_line(line)
        if n:
            touched += 1
            total += n
            # the length invariant of round 809 does not apply (we change text),
            # but a rewritten line must keep its indentation and its trailing text
            assert new.lstrip() != "" and new[:len(new) - len(new.lstrip())] == line[:len(line) - len(line.lstrip())]
        skipped += sk
        out.append(new)
    print(f"sites rewritten: {total}  (lines touched: {touched})")
    print(f"char-needle / multi-arg sites left alone: {skipped}")
    if check:
        return
    open(CHECKER, "w", encoding="utf-8", errors="surrogateescape").write("\n".join(out))
    print("written")


if __name__ == "__main__":
    main()

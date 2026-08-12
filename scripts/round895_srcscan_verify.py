#!/usr/bin/env python3
"""(WARM.19) round 895 — verify the `srcHas`/`srcIndexOf`/`srcLastIndexOf`
rewrite by INVERTING it and demanding the original file back, byte for byte.

This is the round-819 lesson in its cheapest form: decide a mechanical rewrite
with a parser, not by eye. Two independent checks:

  1. **Inversion.** `srcHas(X, N)` -> `X.contains(N)`, etc., applied to the new
     file must reproduce the old file EXACTLY. That catches a dropped argument,
     a swapped argument, a mangled trailing expression, and — the round-684
     hazard — any re-flowing of a string literal, whose embedded whitespace can
     be load-bearing.
  2. **String-literal multiset.** The multiset of all double-quoted literals in
     the file must be identical before and after.

Usage:  python3 scripts/round895_srcscan_verify.py <before.kt> <after.kt>
Exit 0 = identical under inversion; non-zero = the rewrite is not faithful.
"""

import collections
import re
import sys

INV = {"srcHas": "contains", "srcIndexOf": "indexOf", "srcLastIndexOf": "lastIndexOf"}
CALL = re.compile(r"\b(srcHas|srcIndexOf|srcLastIndexOf)\(")


def split_args(s):
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


def invert_line(line):
    out = line
    while True:
        target = None
        for m in CALL.finditer(out):
            args, close = split_args(out[m.end():])
            if args is None or len(args) < 2:
                continue
            target = (m, args, close)
            break
        if target is None:
            return out
        m, args, close = target
        recv = args[0].strip()
        rest = [a.strip() for a in args[1:]]
        newcall = f"{recv}.{INV[m.group(1)]}(" + ", ".join(rest) + ")"
        out = out[:m.start()] + newcall + out[m.end() + close + 1:]


def literals(text):
    return collections.Counter(re.findall(r'"((?:[^"\\\n]|\\.)*)"', text))


def main():
    before_path, after_path = sys.argv[1], sys.argv[2]
    before = open(before_path, encoding="utf-8", errors="surrogateescape").read()
    after = open(after_path, encoding="utf-8", errors="surrogateescape").read()

    b_lines = before.split("\n")
    a_lines = after.split("\n")
    if len(b_lines) != len(a_lines):
        print(f"FAIL line count {len(b_lines)} -> {len(a_lines)}")
        return 1

    bad = 0
    rewritten = 0
    for i, (b, a) in enumerate(zip(b_lines, a_lines)):
        if b == a:
            continue
        rewritten += 1
        inv = invert_line(a)
        if inv != b:
            bad += 1
            if bad <= 10:
                print(f"FAIL line {i+1}\n  before: {b}\n  after : {a}\n  invert: {inv}")
    print(f"lines differing: {rewritten}   inversion failures: {bad}")

    lb, la = literals(before), literals(after)
    if lb != la:
        only_b = lb - la
        only_a = la - lb
        print(f"FAIL string-literal multiset differs: -{sum(only_b.values())} +{sum(only_a.values())}")
        for k, n in list(only_b.items())[:10]:
            print(f"   lost x{n}: {k!r}")
        for k, n in list(only_a.items())[:10]:
            print(f"   gained x{n}: {k!r}")
        bad += 1
    else:
        print(f"string-literal multiset identical ({sum(lb.values())} literals)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

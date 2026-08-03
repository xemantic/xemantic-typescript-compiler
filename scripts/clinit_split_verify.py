#!/usr/bin/env python3
"""(JIT.1)(e) round 820 — equivalence checks for the `Checker.<clinit>` hoist.

Round 805's five checks, in the shape a CONSTANT hoist takes:

  1. VERBATIM — every helper body, re-extracted from the NEW file and re-indented
     by 8, is byte-identical to the text HEAD had inside the property.
  2. RECONSTRUCTION — un-applying the split (splice every helper body back into
     its property) reproduces HEAD's `Checker.kt` BYTE FOR BYTE. This is the
     check that makes 1 airtight: it also proves nothing ELSE in the file moved.
  3. PARTITION — every line of every removed span is claimed exactly once, and
     the file's line accounting balances (removed == moved + the declaration
     lines rewritten).
  4. CONTROL FLOW — a constant literal has none, so the check is that it HAS
     none: zero `return`/`continue`/`break`/`if`/`when` tokens in any moved
     region, and the ELEMENT COUNT of each literal (top-level commas at the
     builder's own paren depth) is identical on both sides. A hoist that
     silently dropped a member would pass a text diff of the wrong region and
     fail this.
  5. FREE VARIABLES — no moved region references a companion member or `this`.
     This is not a style preference: a Kotlin `private` companion member is
     invisible to a top-level function in the same file, so a region that read
     one could not compile — but an `internal` one COULD, and would then be
     read at a different point in class-initialisation order. The check refuses
     both.

  6. (this shape's own) CALLED EXACTLY ONCE — each helper is referenced exactly
     twice in the file (its own declaration and the one property initializer),
     and the property's declared type is unchanged from HEAD.

Usage:
  scripts/clinit_split_verify.py [--src FILE] [--rev HEAD]
"""
#  SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
#  SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
#  xemantic-typescript-compiler - a conformant TypeScript compiler and type
#  checker that runs on JVM, native, and WebAssembly
#  Copyright (C) 2026 Kazimierz Pogoda / Xemantic
#
#  This program is free software: you can redistribute it and/or modify
#  it under the terms of the GNU Affero General Public License as
#  published by the Free Software Foundation, version 3 of the License.

import argparse
import re
import subprocess
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from clinit_split_apply import TARGETS, find_decl  # noqa: E402

FAIL = []


def check(ok, msg):
    print(("  ok   " if ok else "  FAIL ") + msg)
    if not ok:
        FAIL.append(msg)


def strip(s):
    return re.sub(r"//.*$", "", re.sub(r'"(?:\\.|[^"\\])*"', '""', s))


def top_level_commas(body):
    """Commas at paren/brace/bracket depth 0 of the moved body — the literal's
    own element separators."""
    d, n = 0, 0
    for line in body:
        for ch in strip(line):
            if ch in "([{":
                d += 1
            elif ch in ")]}":
                d -= 1
            elif ch == "," and d == 0:
                n += 1
    return n


def helper_body(new, helper):
    """(returnType, initHead, bodyLines) of a top-level builder in the new file."""
    pat = re.compile(r"^private fun " + helper + r"\(\): (.*) = (setOf\(|mapOf\(|listOf\()$")
    i = next((k for k in range(len(new)) if pat.match(new[k])), None)
    if i is None:
        sys.exit(f"error: helper {helper} not found in the new file")
    m = pat.match(new[i])
    j = i + 1
    while new[j] != ")":
        j += 1
    return m.group(1), m.group(2), new[i + 1:j]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="src/commonMain/kotlin/Checker.kt")
    ap.add_argument("--rev", default="HEAD")
    a = ap.parse_args()

    old_text = subprocess.run(["git", "show", f"{a.rev}:{a.src}"],
                              capture_output=True, text=True, check=True).stdout
    old = old_text.split("\n")
    new_text = open(a.src).read()
    new = new_text.split("\n")

    print("1. VERBATIM — helper bodies against HEAD's property bodies")
    spans, claimed = {}, []
    for name, helper, rtype in TARGETS:
        decl, close = find_decl(old, name)
        spans[name] = (decl, close)
        want = old[decl:close - 1]
        rt, init, got = helper_body(new, helper)
        reind = ["        " + l if l else "" for l in got]
        check(reind == want,
              f"{helper}: {len(got)} lines == HEAD {name} lines {decl + 1}-{close - 1}")
        check(rt == rtype, f"{helper}: return type {rt!r} == declared {rtype!r}")
        check(init == old[decl - 1][old[decl - 1].index(" = ") + 3:],
              f"{helper}: builder call {init!r} is HEAD's")
        claimed.extend(range(decl, close + 1))

    print("2. RECONSTRUCTION — un-apply and diff against HEAD byte for byte")
    rebuilt = list(new)
    # drop the appended block: everything from the round-820 header marker on
    marker = next(k for k in range(len(rebuilt))
                  if "(JIT.1)(e) round 820 — companion-constant builders" in rebuilt[k])
    rebuilt = rebuilt[:marker - 1]
    while rebuilt and rebuilt[-1] == "":
        rebuilt.pop()
    for name, helper, rtype in sorted(TARGETS, key=lambda t: -find_decl(old, t[0])[0]):
        decl, close = spans[name]
        _, init, got = helper_body(new, helper)
        i = next(k for k in range(len(rebuilt)) if helper + "()" in rebuilt[k])
        # the rewritten declaration is 1 line, or 2 when the initializer had a tail
        tail = old[close - 1][len("        )"):]
        span = 2 if tail else 1
        head = old[decl - 1]
        rebuilt[i - span + 1:i + 1] = (
            [head] + ["        " + l if l else "" for l in got] + ["        )" + tail])
    check("\n".join(rebuilt) == old_text.rstrip("\n"),
          f"un-applied file == HEAD ({len(old_text)} chars)")

    print("3. PARTITION — every removed line claimed exactly once")
    check(len(claimed) == len(set(claimed)), f"{len(claimed)} removed lines, no overlap")
    moved = sum(len(helper_body(new, h)[2]) for _, h, _ in TARGETS)
    rewritten = sum(2 if old[spans[n][1] - 1] != "        )" else 1 for n, _, _ in TARGETS)
    dropped = len(claimed) - moved - 2 * len(TARGETS)  # decl line + close line per target
    check(dropped == 0,
          f"removed {len(claimed)} == moved {moved} + {len(TARGETS)} decl + "
          f"{len(TARGETS)} close lines")
    print(f"       (declarations rewritten to {rewritten} lines total)")

    print("4. CONTROL FLOW — none, and the element counts match")
    for name, helper, _ in TARGETS:
        decl, close = spans[name]
        want, got = old[decl:close - 1], helper_body(new, helper)[2]
        toks = re.findall(r"\b(return|continue|break|if|when|for|while)\b",
                          "\n".join(strip(l) for l in got))
        check(not toks, f"{helper}: no control flow ({len(toks)} tokens)")
        check(top_level_commas(want) == top_level_commas(got),
              f"{helper}: {top_level_commas(got)} top-level elements == HEAD's")

    print("5. FREE VARIABLES — no companion member, no `this`")
    # ONLY the companion's own members: an indent-8 `val` elsewhere in this
    # 176k-line file is a local of some other declaration, and counting those
    # made the first run of this check report the stdlib infix `to` as a
    # companion member. The span is the `companion object` block itself.
    lo = next(n for n in range(1, len(old) + 1) if old[n - 1].strip() == "companion object {")
    depth, started, hi = 0, False, None
    for j in range(lo - 1, len(old)):
        for ch in old[j]:
            if ch == "{":
                depth, started = depth + 1, True
            elif ch == "}":
                depth -= 1
                if started and depth == 0:
                    hi = j + 1
                    break
        if hi:
            break
    comp = set()
    for n in range(lo + 1, hi):
        m = re.match(r"^        (?:private |internal |public )?(?:const )?(?:val|var|fun) "
                     r"([A-Za-z_][A-Za-z0-9_]*)", old[n - 1])
        if m:
            comp.add(m.group(1))
    check(200 < len(comp) < 400, f"companion member census is the companion's "
                                 f"({len(comp)} names, lines {lo}-{hi})")
    check("KNOWN_GLOBALS" in comp and "to" not in comp,
          "census control: it holds the companion's constants and not the stdlib `to`")
    for name, helper, _ in TARGETS:
        body = "\n".join(strip(l) for l in helper_body(new, helper)[2])
        ids = set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", body))
        hits = sorted((ids & comp) - {name})
        check(not hits, f"{helper}: reads no companion member {hits if hits else ''}")
        check("this" not in ids, f"{helper}: no `this`")

    print("6. CALLED EXACTLY ONCE, and the property type is unchanged")
    for name, helper, _ in TARGETS:
        n = len(re.findall(r"\b" + helper + r"\b", new_text))
        check(n == 2, f"{helper}: {n} references (declaration + one call site)")
        decl, _ = spans[name]
        old_head = old[decl - 1][:old[decl - 1].index(" = ") + 3].rstrip()
        check(any(l.rstrip() == old_head or l.startswith(old_head + " ") for l in new),
              f"{name}: declaration head unchanged")

    print()
    if FAIL:
        print(f"FAILED: {len(FAIL)}")
        return 1
    print("ALL CHECKS PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""(JIT.1)(e) round 820 — hoist the seven largest companion constants out of
`Checker.<clinit>` into top-level private builder functions.

The edit is mechanical and, for the moved text, VERBATIM at dedent 8:

    -        internal val KNOWN_GLOBALS: Set<String> = setOf(
    -            "undefined", "globalThis",
    -            ...
    -        )
    +        internal val KNOWN_GLOBALS: Set<String> = ckConstKnownGlobals()

  and, appended at file level after the class:

    +private fun ckConstKnownGlobals(): Set<String> = setOf(
    +    "undefined", "globalThis",
    +    ...
    +)

**Why TOP-LEVEL and not a companion `private fun`.** A companion function is an
instance method on `Checker$Companion`, so `<clinit>` would have to reach it
through the `Companion` static field it is itself in the middle of installing —
an initialisation-order question this split does not need to have. A top-level
private function compiles to a static method on `CheckerKt`, so the call is a
plain `invokestatic` with no receiver and no ordering to reason about.

**The one thing that constrains WHICH properties can move**: a Kotlin `private`
companion member is NOT visible to a top-level function in the same file, so a
region that reads another companion property cannot be hoisted as-is. Six of the
seven read nothing; `LIB_MIN_TARGET` reads `TYPED_ARRAY_NAMES` in the
`+ TYPED_ARRAY_NAMES.flatMap { … }` TAIL of its initializer, so only the leading
`mapOf(…)` literal moves and the tail stays in the companion.

Usage:
  scripts/clinit_split_apply.py [--src FILE] [--dry-run]
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
import sys

# (property, helper, return type, doc) — in SOURCE order, which is the order
# `<clinit>` runs them in. Nothing here depends on that order (every region is
# a closed literal), but the apply must not reorder the file either.
TARGETS = [
    ("NODE_BUILTIN_MODULES", "ckConstNodeBuiltinModules", "Set<String>"),
    ("KEYWORD_IDENTIFIERS", "ckConstKeywordIdentifiers", "Set<String>"),
    ("KNOWN_GLOBALS", "ckConstKnownGlobals", "Set<String>"),
    ("DOM_GLOBAL_NAMES", "ckConstDomGlobalNames", "Set<String>"),
    ("VALUE_ONLY_GLOBALS", "ckConstValueOnlyGlobals", "Set<String>"),
    ("KNOWN_GENERIC_BUILTINS", "ckConstKnownGenericBuiltins", "Map<String, Pair<Int, String>>"),
    ("LIB_MIN_TARGET", "ckConstLibMinTargetBase", "Map<String, ScriptTarget>"),
]

HEADER = """
// ---------------------------------------------------------------------------
// (JIT.1)(e) round 820 — companion-constant builders hoisted out of
// `Checker.<clinit>`.
//
// A static initializer over 8,000 bytecodes is never JIT-compiled, and
// `Checker.<clinit>` was 10,339 — entirely the seven collection constants
// below, whose hundreds of string literals are `putstatic`-ed one at a time.
// Each builder is called exactly once, from the property initializer that used
// to hold its body, so this is a pure relocation: same values, same order, same
// visibility (the properties keep theirs; these functions are file-private).
//
// They are TOP-LEVEL rather than companion members on purpose — a companion
// function would have to be reached through the `Companion` field `<clinit>` is
// itself installing, and a top-level private function is a plain `invokestatic`
// with no initialisation order to reason about.
// ---------------------------------------------------------------------------
"""


def find_decl(src, name):
    """(declLine, closeLine) 1-based, for a companion property with a `(`-initializer."""
    pat = re.compile(r"^        (?:private |internal |public )?val " + name + r"\b")
    n = next((i for i in range(1, len(src) + 1) if pat.match(src[i - 1])), None)
    if n is None:
        sys.exit(f"error: companion property {name} not found")
    depth, close = 0, None
    for j in range(n - 1, len(src)):
        t = re.sub(r"//.*$", "", re.sub(r'"(?:\\.|[^"\\])*"', '""', src[j]))
        for ch in t:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    close = j + 1
                    break
        if close:
            break
    if close is None:
        sys.exit(f"error: initializer of {name} never closes")
    return n, close


def dedent8(line):
    if line.strip() == "":
        return ""
    if not line.startswith("        "):
        sys.exit(f"error: moved line is not indented 8: {line!r}")
    return line[8:]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="src/commonMain/kotlin/Checker.kt")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    text = open(a.src).read()
    src = text.split("\n")
    edits, helpers = [], []
    for name, helper, rtype in TARGETS:
        decl, close = find_decl(src, name)
        head = src[decl - 1]
        i = head.index(" = ")
        prefix, init = head[:i + 3], head[i + 3:]
        if init not in ("setOf(", "mapOf(", "listOf("):
            sys.exit(f"error: {name} initializer head is {init!r}, not a bare builder call")
        closing = src[close - 1]
        m = re.match(r"^        \)(.*)$", closing)
        if not m:
            sys.exit(f"error: {name} closing line is {closing!r}")
        tail = m.group(1)
        body = [dedent8(l) for l in src[decl:close - 1]]
        if '"""' in "\n".join(body):
            sys.exit(f"error: {name} body holds a raw string — dedent 8 would change it")
        helpers.append(
            f"/** [Checker.Companion.{name}]'s literal, hoisted out of `<clinit>`. */\n"
            f"private fun {helper}(): {rtype} = {init}\n"
            + "\n".join(body) + "\n)\n")
        if tail:
            new = [prefix.rstrip(), f"            {helper}(){tail}"]
        else:
            new = [prefix + f"{helper}()"]
        edits.append((decl, close, new, name, close - decl + 1, len(body)))

    # apply back-to-front so earlier line numbers stay valid
    out = list(src)
    for decl, close, new, *_ in sorted(edits, reverse=True):
        out[decl - 1:close] = new
    body = "\n".join(out)
    if not body.endswith("\n"):
        body += "\n"
    body += HEADER + "\n" + "\n".join(helpers)

    for decl, close, new, name, span, moved in sorted(edits):
        print(f"  {name:24s} lines {decl}-{close} ({span}) -> "
              f"{len(new)} line(s) + a helper of {moved} moved lines")
    if a.dry_run:
        print("(dry run — nothing written)")
        return 0
    open(a.src, "w").write(body)
    print(f"wrote {a.src}: {len(src)} -> {len(body.split(chr(10)))} lines")
    return 0


if __name__ == "__main__":
    sys.exit(main())

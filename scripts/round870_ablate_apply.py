#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
# SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
#
"""(WARM.17) round 870 — inject ONE deliberate mistake into
`buildModuleSymbolScanIndex`.

Round 807: a COMBINED ablation cannot attribute, so exactly one arm per
invocation. Round 808: the mistake must COMPILE — a compile error makes the
driver report `ran 0, failed 0`, which is indistinguishable from "the mistake
changed nothing".

Each arm is a line-based patch, applied to a clean checkout of the file by the
driver, and asserted to have matched (a silently-unapplied edit is rounds
855/856's false green).
"""
import sys

SRC = "xemantic-typescript-compiler-core/src/commonMain/kotlin/ModuleSymbolScanIndex.kt"

BODY_OLD = """internal fun buildModuleSymbolScanIndex(binderResults: List<BinderResult>): List<Symbol> {
    val out = ArrayList<Symbol>()
    for (result in binderResults) {
        for ((_, sym) in result.locals) {
            if (sym.flags.hasAny(SymbolFlags.Module)) out.add(sym)
        }
    }
    return out
}"""


def body(inner: str) -> str:
    return ("internal fun buildModuleSymbolScanIndex(binderResults: List<BinderResult>)"
            ": List<Symbol> {\n" + inner + "\n}")


# A1: the membership gate reads only ValueModule — the CLAUDE.md trap
#     (`SymbolFlags.Module` is the UNION of ValueModule and NamespaceModule).
A1 = body("""    val out = ArrayList<Symbol>()
    for (result in binderResults) {
        for ((_, sym) in result.locals) {
            if (sym.flags.hasAny(SymbolFlags.ValueModule)) out.add(sym)
        }
    }
    return out""")

# A2: the membership gate reads only NamespaceModule — the other half.
A2 = body("""    val out = ArrayList<Symbol>()
    for (result in binderResults) {
        for ((_, sym) in result.locals) {
            if (sym.flags.hasAny(SymbolFlags.NamespaceModule)) out.add(sym)
        }
    }
    return out""")

# A3: no membership gate at all — every local symbol is admitted.
A3 = body("""    val out = ArrayList<Symbol>()
    for (result in binderResults) {
        for ((_, sym) in result.locals) {
            out.add(sym)
        }
    }
    return out""")

# A4: FILE order reversed.
A4 = body("""    val out = ArrayList<Symbol>()
    for (result in binderResults.asReversed()) {
        for ((_, sym) in result.locals) {
            if (sym.flags.hasAny(SymbolFlags.Module)) out.add(sym)
        }
    }
    return out""")

# A5: WITHIN-file order sorted by name instead of the locals insertion order.
A5 = body("""    val out = ArrayList<Symbol>()
    for (result in binderResults) {
        for (sym in result.locals.values.sortedBy { it.name }) {
            if (sym.flags.hasAny(SymbolFlags.Module)) out.add(sym)
        }
    }
    return out""")

# A6: duplicates dropped — one entry per Symbol instance.
A6 = body("""    val out = ArrayList<Symbol>()
    val seen = HashSet<Int>()
    for (result in binderResults) {
        for ((_, sym) in result.locals) {
            if (sym.flags.hasAny(SymbolFlags.Module) && seen.add(sym.id)) out.add(sym)
        }
    }
    return out""")

# A7: de-duplicated by NAME rather than by instance — the plausible variant of
#     A6, and the one that keeps a same-named namespace from a second file out.
A7 = body("""    val out = ArrayList<Symbol>()
    val seen = HashSet<String>()
    for (result in binderResults) {
        for ((_, sym) in result.locals) {
            if (sym.flags.hasAny(SymbolFlags.Module) && seen.add(sym.name)) out.add(sym)
        }
    }
    return out""")

# A8: only the FIRST module symbol of each file is kept.
A8 = body("""    val out = ArrayList<Symbol>()
    for (result in binderResults) {
        val first = result.locals.values.firstOrNull { it.flags.hasAny(SymbolFlags.Module) }
        if (first != null) out.add(first)
    }
    return out""")

# A9: the index carries a COPY of each symbol rather than the file's own
#     instance, so a later write to the real symbol's `exports` is invisible
#     through it. Compiles; changes only identity.
A9 = body("""    val out = ArrayList<Symbol>()
    for (result in binderResults) {
        for ((_, sym) in result.locals) {
            if (sym.flags.hasAny(SymbolFlags.Module)) out.add(Symbol(sym.flags, sym.name))
        }
    }
    return out""")

ARMS = {"A1": A1, "A2": A2, "A3": A3, "A4": A4, "A5": A5,
        "A6": A6, "A7": A7, "A8": A8, "A9": A9}


def main() -> None:
    arm = sys.argv[1]
    if arm not in ARMS:
        sys.exit(f"unknown arm: {arm}")
    text = open(SRC).read()
    if BODY_OLD not in text:
        sys.exit(f"REFUSED: {SRC} does not carry the pristine body — is the tree clean?")
    open(SRC, "w").write(text.replace(BODY_OLD, ARMS[arm], 1))


if __name__ == "__main__":
    main()

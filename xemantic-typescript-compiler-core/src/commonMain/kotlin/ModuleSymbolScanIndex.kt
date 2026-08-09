/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 */
package com.xemantic.typescript.compiler

/**
 * (WARM.17) round 870 — the program's module/namespace symbols, in the exact
 * order a whole-program `binderResults × locals` scan would visit them.
 *
 * ### Why this exists
 *
 * `Checker.computeTypeParamInfo` — the MISS body of the `getTypeParamInfo`
 * memo — answers "how many type arguments does this name require?" and one of
 * its three lookups is "…or is it exported by some namespace anywhere in the
 * program?". It answered that by iterating EVERY entry of EVERY file's `locals`
 * and testing `SymbolFlags.Module` on each, on every miss.
 *
 * Measured on the compiler profile (`FrontEnd.TPI`, warm, deterministic to the
 * unit over two processes): 1,077 misses iterate **2,992,718 symbols** to reach
 * **2,184** module-flagged ones — i.e. **99.93% of the scan is re-deciding a
 * question that is a property of the binder tables and not of the name being
 * asked**. Which module symbols exist cannot depend on the name; only their
 * `exports` probe can.
 *
 * So the list is computed once and the probe stays where it was. The `exports`
 * table itself is deliberately NOT indexed: it is a `var` on the symbol and the
 * checker's own merging writes to it, so it is read live exactly as before.
 *
 * ### The order is the contract
 *
 * The scan is a FIRST-MATCH search: the first module symbol whose `exports`
 * answers with a usable declaration wins. So this index must preserve
 *
 * * the `binderResults` order (file order = program order, itself load-bearing
 *   — `ProjectCompiler.walk` sorts, and CLAUDE.md records that program order
 *   decides which file first touches a shared resolution), and
 * * within a file, the `locals` iteration order (a `SymbolTable` is
 *   `mutableMapOf()`, i.e. insertion-ordered), and
 * * DUPLICATES — one `Symbol` instance reachable from two files' `locals` was
 *   probed twice by the scan and is listed twice here, because dropping the
 *   second occurrence would change which occurrence is "first" for any later
 *   rule that reasons about position.
 *
 * ### When it may be built
 *
 * The module-symbol SET is settled by init pass 1b (`mergeModuleAugmentations`,
 * the only place the checker adds an entry to a file's `locals` or sets a
 * `Module` bit on one), which runs in the setup block long before any checking
 * pass. `getTypeParamInfo`'s own memo has frozen whole ANSWERS over those same
 * tables since round 481, so caching the module-symbol LIST is strictly weaker
 * than an assumption this function has relied on for 389 rounds.
 *
 * It is nonetheless built LAZILY, at the first memo miss, so the window in
 * which it could disagree with a live scan is contained in the window in which
 * the memo above it could disagree with a live recomputation.
 *
 * A top-level `internal` function taking its input as a PARAMETER, deliberately
 * — every rule above is then pinned directly by `ModuleSymbolScanIndexTest`
 * rather than through a compile, where a wrong gate would surface (if at all)
 * as an unrelated diagnostic in a file no fixture contains. That is round 868's
 * lesson from `buildStarExportIndex`.
 */
internal fun buildModuleSymbolScanIndex(binderResults: List<BinderResult>): List<Symbol> {
    val out = ArrayList<Symbol>()
    for (result in binderResults) {
        for ((_, sym) in result.locals) {
            if (sym.flags.hasAny(SymbolFlags.Module)) out.add(sym)
        }
    }
    return out
}

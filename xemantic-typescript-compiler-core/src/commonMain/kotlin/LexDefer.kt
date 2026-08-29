/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * xemantic-typescript-compiler - a conformant TypeScript compiler and type
 * checker that runs on JVM, native, and WebAssembly
 * Copyright (C) 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

/**
 * (INC.16) THE MEASUREMENT ARM for `bindLexicalScopes` — the INV.2(c) whole-tree
 * scope walk, which (INC.15) measured at **69 ms of a 74 ms bind**, i.e. 93% of
 * everything binding costs and the largest single mechanism left in the ~340 ms
 * incremental floor.
 *
 * The template is (INC.9)'s: `BinderResult.flowGraph` moved onto FIRST ASK and
 * banked 136 ms, because under a `recheckOnly` partition the spine walks ONE file
 * and nothing asks the other 122 for their graphs. The INV.2(c) tables are read
 * per-file by exactly the same shape of consumer (`spineScopeFill`,
 * `unresolvedFileRootFor`, `cpaM3LexicalScopes`, all inside `checkSpine`'s loop
 * over `checkedResults`) — plus TWO name-gated resolvers that reach a FOREIGN
 * file's tables, and ONE program-wide collector.
 *
 * This object is the arm that MEASURES which of those actually force the build,
 * and what the deferral is worth. It is NOT routed through `ModeLedger`: it is not
 * a CLI flag, and every sweep that reads it runs one arm per process.
 */
object LexDefer {

    /**
     * When true — the DEFAULT since (INC.16) — `BinderResult.lexicalScopes` builds on
     * first ask. False restores the pre-(INC.16) build at the end of `Binder.bind`, and
     * is the arm `scripts/lex-defer.sh` runs the differential against.
     */
    var deferred: Boolean = true

    /** Files whose scope tables were built lazily (i.e. asked for). */
    var lazyBuilds: Int = 0

    /** Files whose scope tables were built by the eager path at the end of `bind`. */
    var eagerBuilds: Int = 0

    /**
     * CENSUS-ONLY: which `PassTiming.currentPass` forced a lazy build, and how
     * often. Off unless [census] is set — a census that runs unconditionally is a
     * probe whose argument does the work (round 900).
     */
    var census: Boolean = false

    /** [census] output: pass name -> number of files it was the first to force. */
    val forcedBy: MutableMap<String, Int> = LinkedHashMap()

    /**
     * The POSITIVE CONTROL for the census skip: when true, `computeAllEnumValues`
     * still walks every file's scopes (so every file's tables are built, exactly as
     * before (INC.16)) and counts, in [skipViolations], every scope-space Enum or
     * TypeAlias symbol found in a file the skip would have passed over. A green run
     * is `skipViolations == 0` over a non-empty [skippedFiles] — the second half
     * matters, because a skip that never fires reports zero for the wrong reason.
     */
    var verifySkip: Boolean = false

    /** Files `computeAllEnumValues` did not walk (or, under [verifySkip], would not have). */
    var skippedFiles: Int = 0

    /**
     * (INC.52) Files whose FILE-LEVEL symbol table `computeAllEnumValues` did not walk —
     * the second of its two loops, and the one that was still visiting every symbol of
     * every file to find the program's enums.
     *
     * Counted separately from [skippedFiles] because the two loops are skipped by
     * DIFFERENT predicates over different populations: that one asks whether a file's
     * block-scoped declarations reach a fresh INV.2(c) scope, this one whether the file's
     * bind minted any enum symbol at all.
     */
    var localsSkippedFiles: Int = 0

    /** (INC.52) Enum symbols found in a file the [localsSkippedFiles] predicate skipped. */
    var localsSkipViolations: Int = 0

    /**
     * (INC.52) Symbols the second loop of `computeAllEnumValues` VISITED — the
     * deterministic instrument for what the skip removes.
     *
     * A per-pass TIME cannot decide a 6 ms change against a floor whose own draws span
     * 30 ms (CLAUDE.md: counters decide, wall time confirms). This counts the population
     * instead, and it needs no second binary: under [verifySkip] the loop walks
     * everything, so one build reports both arms — the skip's population and the one it
     * replaced.
     */
    var localsSymbolsVisited: Long = 0

    /** Scope-space Enum/TypeAlias symbols found in a file the skip passed over. */
    var skipViolations: Int = 0

    fun resetCounters() {
        skippedFiles = 0
        skipViolations = 0
        localsSkippedFiles = 0
        localsSkipViolations = 0
        localsSymbolsVisited = 0
        lazyBuilds = 0
        eagerBuilds = 0
        forcedBy.clear()
    }

    /**
     * CENSUS-ONLY: `fileName -> fingerprint` of the file's INV.2(c) tables,
     * computed at ONE fixed point in the pipeline (right after the checker is
     * built) so the two arms compare the same quantity. Deliberately id-FREE —
     * `declareLexical` mints from the descending negative `Symbol.scopeSymbol`
     * sequence, which a deferral necessarily reorders among itself, and that
     * reordering is not what hazard (a) is about.
     */
    val fingerprints: MutableMap<String, Long> = LinkedHashMap()

    /**
     * Fingerprints every file's tables. Hazard (a)'s instrument: `moduleLexicalScope`
     * and the `EnumDeclaration` arm both read the BINDER's accumulated `nodeToSymbol`,
     * whose `(pos, end)` keys COLLIDE ACROSS FILES and are last-wins in bind order —
     * so a scope built at first ask can alias a DIFFERENT symbol's `exports` than the
     * same scope built mid-bind. That shows up here as a differing `existing` key set.
     */
    fun fingerprint(results: List<BinderResult>) {
        for (r in results) {
            if (!r.lexicalScopesBuilt) continue
            var h = 1125899906842597L
            fun mix(v: Long) { h = h * 31 + v }
            val scopes = r.lexicalScopes
            for (id in scopes.keys.sorted()) {
                val scope = scopes[id] ?: continue
                mix(id.toLong())
                mix(((scope.owner as NodeBase).nodeId).toLong())
                mix((scope.parent?.owner as? NodeBase)?.nodeId?.toLong() ?: -1L)
                for (name in scope.symbols.keys.sorted()) {
                    mix(name.hashCode().toLong())
                    mix((scope.symbols[name]?.flags?.value ?: 0).toLong())
                }
                val existing = scope.existing
                if (existing == null) mix(-7L) else {
                    mix(-9L)
                    for (name in existing.keys.sorted()) mix(name.hashCode().toLong())
                }
            }
            fingerprints[r.sourceFile.fileName] = h
        }
    }

    /** Records one lazy build against the pass that forced it. */
    fun recordLazy() {
        lazyBuilds++
        if (census) {
            val p = PassTiming.currentPass ?: "<outside a pass>"
            forcedBy[p] = (forcedBy[p] ?: 0) + 1
        }
    }
}

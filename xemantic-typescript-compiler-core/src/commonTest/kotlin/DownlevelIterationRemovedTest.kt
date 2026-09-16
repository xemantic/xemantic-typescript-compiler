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
 *
 * As a special exception, this file contains Helper Code covered by the
 * xemantic-typescript-compiler Output Exception; additional permissions
 * are granted as described in the file LICENSE-EXCEPTION.
 */
package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (LEGACY.1)(i) `downlevelIteration` is a REMOVED option in TypeScript 7 — tsgo 7.0.2 reports it
 * (`program.go:875`, `TS5102 Option 'downlevelIteration' has been removed. Please remove it from
 * your configuration.`, key-anchored) and reads it NOWHERE else (`DownlevelIteration` has three
 * references in `internal/`: the parser, the field, the row).
 *
 * **Every expected value is tsgo 7.0.2's**, measured 2026-09-15 over 16 scratch projects
 * (`target` ∈ {es5, es2015, unset} × `downlevelIteration` ∈ {unset, true, false} × `lib` ∈
 * {default, [es2015, dom], [es5]}, `noLib`) through the LSP's `textDocument/diagnostic` pull,
 * because the CLI stops at the option rows. What the matrix showed:
 *
 *  * TS2802 (`Type '{0}' can only be iterated through when using the '--downlevelIteration'
 *    flag or with a '--target' of 'es2015' or higher.`) is NOT gated on the target and NOT on
 *    the option in tsgo — `getIteratedTypeOrElementType` takes the iterable protocol whenever
 *    `getGlobalIterableType() != emptyGenericType`, i.e. whenever the LIB declares `Iterable`.
 *    At a written `target: es5` tsgo's default lib is `lib.d.ts`, whose `lib.dom.d.ts` pulls in
 *    `es2015` in TypeScript 7, so every checker row at es5 is byte-identical to es2015's and
 *    the emit is ES2015-native (`for (const x of m)`, `function* g()`, `[...u]` — no downlevel).
 *    TS2802 is reachable ONLY with an explicit `lib` that excludes es2015 (`lib: ["es5"]` — a
 *    typed-array spread reports it at es5 AND at es2015), which is a LIB gate the deleted block
 *    never had. The block (`checkDownlevelIteration`, 229 lines, seven single-caller functions)
 *    was the tsc-6 TARGET rule, firing where tsgo is silent; it is deleted whole.
 *  * `downlevelIteration: true` / `false` changes NO checker row and NO emitted byte in any cell.
 *  * TS2488 for a `never` array-destructure fires at es5 in tsgo (the lib has `Iterable`); ours
 *    keeps a `defaultedTarget < ES2015` refusal there, which is (LEGACY.1)(j)'s — the option
 *    conjunct that used to open it is gone, and [residue - …] below records the cell that lost
 *    its accidental agreement.
 *
 * The `@target: es5` directive is where a written es5 target is still expressible on the harness
 * path (`usesUnsupportedOption` drops it from the corpus, so these pins are its only gate here).
 * At the `"6.0"` default of `simulatedVersion` the option row is TS5101; tsgo's TS5102 wording
 * needs `@typeScriptVersion: 7.0` ((LEGACY.1)'s BLOCKED-PENDING-USER default). Pins named
 * `control -` were GREEN on the pre-change binary too and state the tsgo-shaped answer; every
 * other pin was RED on it (stash-ablated 2026-09-15).
 */
class DownlevelIterationRemovedTest {

    private val es5 = DOWNLEVEL_ES5
    private val es5False = "$DOWNLEVEL_ES5\n// @downlevelIteration: false"
    private val es5True = "$DOWNLEVEL_ES5\n// @downlevelIteration: true"

    // ── the target-gated TS2802 block is gone ────────────────────────────────────────────────

    /** tsgo at es5: no row on `for (const a of arguments)` (its `IArguments` has `[Symbol.iterator]` from es2015.iterable). */
    @Test
    fun `an es5 target no longer reports TS2802 for a for-of over arguments`() {
        diagnose("function f() { for (const a of arguments) { a; } }", es5) should {
            have(none { it.code == 2802 })
        }
    }

    @Test
    fun `an es5 target no longer reports TS2802 for an array destructuring of arguments`() {
        diagnose("function f() { const [p] = arguments; p; }", es5) should {
            have(none { it.code == 2802 })
        }
    }

    /** tsgo at es5 (default lib): `[...u]` on a `Uint8Array` is clean; only `lib: ["es5"]` makes it TS2802. */
    @Test
    fun `an es5 target no longer reports TS2802 for a spread of a typed array`() {
        diagnose("const u = new Uint8Array(2); [...u];", es5) should {
            have(none { it.code == 2802 })
        }
    }

    /** The old block keyed the `NodeList` arm on a `new NodeList()` INITIALIZER (an AST scan, lib-blind); tsgo: `NodeList` iterates through `dom.iterable`. */
    @Test
    fun `an es5 target no longer reports TS2802 for a spread of a NodeList`() {
        diagnose("const nl = new NodeList(); [...nl];", es5) should {
            have(none { it.code == 2802 })
        }
    }

    /** The `ArrayIterator<any>` arm: a var initialized from `arguments[i]` and called in a for-of head. */
    @Test
    fun `an es5 target no longer reports TS2802 for a for-of over an arguments-derived call`() {
        diagnose("function f() { const it = arguments[0]; for (const v of it()) { v; } }", es5) should {
            have(none { it.code == 2802 })
        }
    }

    /** `downlevelIteration: false` used to be the value that ARMED the block; tsgo reads no value. */
    @Test
    fun `downlevelIteration false at es5 does not bring TS2802 back`() {
        diagnose("const u = new Uint8Array(2); [...u]; function f() { for (const a of arguments) { a; } }", es5False) should {
            have(none { it.code == 2802 })
        }
    }

    /** `true` disarmed the old block, so this cell was clean before and after — the tsgo-shaped answer. */
    @Test
    fun `control - downlevelIteration true at es5 reports no TS2802 either`() {
        diagnose("const u = new Uint8Array(2); [...u]; function f() { for (const a of arguments) { a; } }", es5True) should {
            have(none { it.code == 2802 })
        }
    }

    @Test
    fun `control - an es2015 target never reported TS2802 for a typed-array spread or arguments`() {
        diagnose("const u = new Uint8Array(2); [...u]; function f() { for (const a of arguments) { a; } }", "// @strict: true\n// @target: es2015") should {
            have(none { it.code == 2802 })
        }
    }

    // ── the TS2488 sibling gate lost its option conjunct ─────────────────────────────────────

    private val neverDestructure = """declare const nv: { a: "foo" } & { a: "bar" }; const [d] = nv;"""

    /**
     * tsgo reports `TS2488 Type 'never' must have a '[Symbol.iterator]()' method that returns an
     * iterator.` at es5 too (its lib has `Iterable`). Until (LEGACY.1)(j2) ours refused below
     * ES2015 — the tsc-6 array-likeness leg — and `downlevelIteration: true` happened to OPEN
     * the gate, matching tsgo by accident in that one cell; (P18.109) pinned that silence as a
     * `residue`. (j2) replaced the target conjunct by tsgo's lib condition
     * (`Checker.uplevelIterationLib`), so every es5 cell now answers tsgo's row and the option
     * still changes nothing (2026-09-15).
     */
    @Test
    fun `a never array destructure at es5 reports TS2488 whatever downlevelIteration says`() {
        for (directives in listOf(es5True, es5False, es5)) {
            val rows = diagnose(neverDestructure, directives).filter { it.code == 2488 }
            assert(rows.size == 1)
            assert(rows.single().message == "Type 'never' must have a '[Symbol.iterator]()' method that returns an iterator.")
        }
    }

    /** tsgo at es2015 / unset: exactly this row, whatever the option says. */
    @Test
    fun `control - a never array destructure at es2015 reports TS2488 with and without the option`() {
        for (directives in listOf(
            "// @strict: true\n// @target: es2015",
            "// @strict: true\n// @target: es2015\n// @downlevelIteration: true\n// @ignoreDeprecations: 6.0",
            "// @strict: true\n// @target: es2015\n// @downlevelIteration: false\n// @ignoreDeprecations: 6.0",
        )) {
            val rows = diagnose(neverDestructure, directives).filter { it.code == 2488 }
            assert(rows.size == 1)
            assert(rows.single().message == "Type 'never' must have a '[Symbol.iterator]()' method that returns an iterator.")
        }
    }

    // ── the option row survives, at both versions, for both written values ───────────────────

    @Test
    fun `control - a written downlevelIteration true reports TS5101 exactly once at the 6 0 default`() {
        val rows = diagnose("const a = 1;", "// @strict: true\n// @downlevelIteration: true")
        val row = rows.single { it.code == 5101 }
        assert(row.message == "Option 'downlevelIteration' is deprecated and will stop functioning in TypeScript 7.0. Specify compilerOption '\"ignoreDeprecations\": \"6.0\"' to silence this error.")
        assert(rows.none { it.code == 5102 || it.code == 5023 })
    }

    /** tsgo: `!options.DownlevelIteration.IsUnknown()` — a written `false` is reported exactly like `true`. */
    @Test
    fun `control - a written downlevelIteration false is reported exactly like true`() {
        val rows = diagnose("const a = 1;", "// @strict: true\n// @downlevelIteration: false")
        assert(rows.count { it.code == 5101 } == 1)
        assert(rows.none { it.code == 5102 || it.code == 5023 })
    }

    /** tsgo: `Option 'downlevelIteration' has been removed. Please remove it from your configuration.` */
    @Test
    fun `control - under typeScriptVersion 7 0 the option row is tsgo's TS5102 byte for byte`() {
        for (value in listOf("true", "false")) {
            val rows = diagnose("const a = 1;", "// @strict: true\n// @typeScriptVersion: 7.0\n// @downlevelIteration: $value")
            val row = rows.single { it.code == 5102 }
            assert(row.message == "Option 'downlevelIteration' has been removed. Please remove it from your configuration.")
            assert(rows.none { it.code == 5101 })
        }
    }

    /** The option is still PARSED (a known key, never TS5023), and an unset option reports nothing. */
    @Test
    fun `control - an unset downlevelIteration reports no option row at all`() {
        diagnose("const a = 1;", "// @strict: true") should {
            have(none { it.code == 5101 || it.code == 5102 || it.code == 5023 })
        }
    }
}

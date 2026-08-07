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
import kotlin.test.Test

/**
 * (REL.4) round 780 — the last two `Debug.assertNever` stragglers, both of which
 * are ordinary narrowing gaps rather than the "not narrowable at all" the item
 * suspected.
 *
 * **Defect 1 — an ELEMENT ACCESS argument was not treated as a reference.**
 * `getReferencePath` has encoded `a[0]` since round 461, the binder records a flow
 * node at every `ElementAccessExpression`, and the flow walk compares path STRINGS
 * — but every flow-reading arm of the argument gate tested
 * `arg is Identifier || arg is PropertyAccessExpression`, so
 * `Debug.assertNever(allowedEndings[0])` after an exhaustive
 * `switch (allowedEndings[0])` read the DECLARED type
 * (tsc moduleSpecifiers.ts:1411).
 *
 * **Defect 2 — a switch `default:` subtracted neither a nullish constituent nor a
 * whole-enum constituent of a UNION subject.** `case undefined:` / `case null:` do
 * resolve to `undefinedType`/`nullType`, but `isLiteralKindForDiscriminant` is a
 * string/number/bigint/boolean-literal test, so nothing matched them; and an enum
 * constituent is ONE member-less `Type.Object` (round 763), so no per-member test
 * could touch it either — where tsc, which models a literal enum AS its member
 * union, peels all 14 constituents of `Extension | undefined`
 * (tsc stringCompletions.ts:386).
 *
 * Eight of these ten pins DISCRIMINATE — measured against a binary with
 * `REL4_ELEM_UNION_GATE` flipped to `false`, not assumed. The sharpest ones
 * discriminate by MESSAGE: what the narrowing left behind, not merely that
 * something fired.
 */
class Rel4ElementAccessAndUnionSwitchTest {

    private val prelude = """
        const enum ME { A, B, C }
        declare function assertNever(x: never): never;
    """.trimIndent() + "\n"

    // --- defect 1: an element access is a narrowable reference ----------------

    /** DISCRIMINATES — TS2345 naming `'ME'` without the fix. */
    @Test
    fun `an exhaustive switch on an element access narrows its default to never`() {
        val diagnostics = diagnose(
            prelude + """
                export function f(a: readonly ME[]): string {
                    switch (a[0]) {
                        case ME.A: return "a";
                        case ME.B: return "b";
                        case ME.C: return "c";
                        default: return assertNever(a[0]);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /**
     * DISCRIMINATES BY MESSAGE — `'ME'` without the fix, the two UNCOVERED
     * members with it. That is what proves the flow read happened, rather than
     * some other rule having gone silent.
     */
    @Test
    fun `a partial switch on an element access reports only the uncovered members`() {
        val diagnostics = diagnose(
            prelude + """
                export function f(a: readonly ME[]): string {
                    switch (a[0]) {
                        case ME.A: return "a";
                        default: return assertNever(a[0]);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'ME.B | ME.C' is not assignable to parameter of type 'never'."
        })
    }

    /** DISCRIMINATES — a STRING-literal index is a reference path too. */
    @Test
    fun `an exhaustive switch on a string keyed element access narrows to never`() {
        val diagnostics = diagnose(
            prelude + """
                export function f(o: { e: ME }): string {
                    switch (o["e"]) {
                        case ME.A: return "a";
                        case ME.B: return "b";
                        case ME.C: return "c";
                        default: return assertNever(o["e"]);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /**
     * Guard — fires on BOTH sides. A COMPUTED index is not a reference path, so
     * the declared type still reaches the parameter: the path gate, not the node
     * kind, is what admits an element access.
     */
    @Test
    fun `negative control - a computed index element access still reports the declared type`() {
        val diagnostics = diagnose(
            prelude + """
                export function f(a: readonly ME[], i: number): string {
                    switch (a[i]) {
                        case ME.A: return "a";
                        case ME.B: return "b";
                        case ME.C: return "c";
                        default: return assertNever(a[i]);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'ME' is not assignable to parameter of type 'never'."
        })
    }

    /** Guard — fires on BOTH sides: the identifier subject is untouched by this round. */
    @Test
    fun `negative control - a partial switch on a plain identifier is unchanged`() {
        val diagnostics = diagnose(
            prelude + """
                export function f(e: ME): string {
                    switch (e) {
                        case ME.A: return "a";
                        default: return assertNever(e);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'ME.B | ME.C' is not assignable to parameter of type 'never'."
        })
    }

    // --- defect 2: the union default subtraction ------------------------------

    /** DISCRIMINATES — TS2345 naming `'ME | undefined'` without the fix. */
    @Test
    fun `an exhaustive switch over an enum-or-undefined union narrows to never`() {
        val diagnostics = diagnose(
            prelude + """
                export function f(e: ME | undefined): string {
                    switch (e) {
                        case ME.A: return "a";
                        case ME.B: return "b";
                        case ME.C: return "c";
                        case undefined: return "u";
                        default: return assertNever(e);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2345 })
    }

    /**
     * DISCRIMINATES BY MESSAGE — `'2 | undefined'` without the fix, `'2'` with it.
     * This isolates the NULLISH half: the literal members already subtracted.
     */
    @Test
    fun `a case undefined clause subtracts the undefined constituent`() {
        val diagnostics = diagnose(
            prelude + """
                export function f(v: 1 | 2 | undefined): string {
                    switch (v) {
                        case 1: return "a";
                        case undefined: return "u";
                        default: return assertNever(v);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message == "Argument of type '2' is not assignable to parameter of type 'never'."
        })
    }

    /**
     * DISCRIMINATES BY MESSAGE — `'ME | undefined'` without the fix, `'undefined'`
     * with it. This isolates the ENUM half: the union keeps exactly the
     * constituent no case covered.
     */
    @Test
    fun `a fully cased enum constituent is subtracted from the union`() {
        val diagnostics = diagnose(
            prelude + """
                export function f(e: ME | undefined): string {
                    switch (e) {
                        case ME.A: return "a";
                        case ME.B: return "b";
                        case ME.C: return "c";
                        default: return assertNever(e);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'undefined' is not assignable to parameter of type 'never'."
        })
    }

    /**
     * DISCRIMINATES BY MESSAGE, and pins that the nullish subtraction matches the
     * CONSTITUENT rather than "any nullish case": `case null:` leaves `undefined`
     * standing while the enum still subtracts.
     */
    @Test
    fun `a case null clause does not subtract the undefined constituent`() {
        val diagnostics = diagnose(
            prelude + """
                export function f(e: ME | undefined): string {
                    switch (e) {
                        case ME.A: return "a";
                        case ME.B: return "b";
                        case ME.C: return "c";
                        case null: return "n";
                        default: return assertNever(e);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'undefined' is not assignable to parameter of type 'never'."
        })
    }

    /**
     * DISCRIMINATES BY MESSAGE — `'ME | undefined'` without the fix. Both halves
     * compose: the nullish constituent goes, the enum constituent is replaced by
     * its uncovered members.
     */
    @Test
    fun `a partially cased enum constituent is replaced by its uncovered members`() {
        val diagnostics = diagnose(
            prelude + """
                export function f(e: ME | undefined): string {
                    switch (e) {
                        case ME.A: return "a";
                        case undefined: return "u";
                        default: return assertNever(e);
                    }
                }
            """.trimIndent()
        )
        assert(diagnostics.any {
            it.code == 2345 &&
                it.message ==
                "Argument of type 'ME.B | ME.C' is not assignable to parameter of type 'never'."
        })
    }
}

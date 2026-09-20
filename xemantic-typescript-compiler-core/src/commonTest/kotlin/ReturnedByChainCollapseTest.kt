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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (LEGACY.0b) step 37, 2026-09-20 — the SECOND chain collapse: a call-return incompatibility
 * that drills further is dotted onto the called name, and keeps the *returned by* wording.
 *
 * tsgo's `reportRelationError` (`internal/checker/relater.go`) folds twice. The first fold
 * turns a property incompatibility followed by a call-signature return incompatibility into
 * `The types returned by 'm()'` / `'m(...)'`. The second fold then runs over that result, and
 * its `switch` lists `The_types_returned_by_0_are_incompatible_between_these_types` BESIDE the
 * two property messages — converting the message to `The_types_of_0` only when it is still the
 * property one. So a return type that drills deeper reads **`The types returned by 'm().size'`**,
 * and this compiler stopped after the first fold, printing `The types returned by 'm()'` over a
 * whole-object mismatch line.
 *
 * **The gap was invisible from the row that names it.** `complexRecursiveCollections` is served
 * by a wipe-and-pin walker, so no engine path reaches it and its `The types of 'map(...).size'`
 * line says nothing about what the engine computes. What located this was the PAIR: the same
 * mismatch through a plain nested property (`p.size`) was already byte-identical to tsgo, and
 * only the call form diverged — a one-line difference between two fixtures that differ in one
 * ingredient.
 *
 * Confined to a single call signature on both sides: with overloads, the return pair this drills
 * is not necessarily the one the elaboration chose.
 *
 * RESIDUES measured in the same matrix, both OUT of this round and neither a chain question:
 * `interface D extends B` reports TS2430 only for a DIRECT property mismatch — every deeper
 * shape (nested property, method return, with or without parameters) is entirely missing — and a
 * `class C implements B` whose method return drills deeper reports no TS2416 at all.
 */
class ReturnedByChainCollapseTest {

    private fun ts(@Language("typescript") source: String) =
        diagnose(source, directives = "// @strict: true", fileName = "t.ts")

    private fun chainOf(ds: List<Diagnostic>, code: Int): List<String> =
        ds.first { it.code == code }.let { listOf(it.message) + it.messageChain }

    /**
     * A no-argument call signature renders `m()`. tsgo prints the dotted path and the leaf pair;
     * before this round the second line was a whole-object mismatch under `'m()'`.
     */
    @Test
    fun `a call return that drills deeper is dotted onto the called name`() {
        val ds = ts(
            """
            interface B4 { m(): { size: number } }
            interface D4 { m(): { size: number | undefined } }
            declare const d4: D4;
            const x4: B4 = d4;
            """
        )
        assert(
            chainOf(ds, 2322) == listOf(
                "Type 'D4' is not assignable to type 'B4'.",
                "  The types returned by 'm().size' are incompatible between these types.",
                "    Type 'number | undefined' is not assignable to type 'number'.",
                "      Type 'undefined' is not assignable to type 'number'.",
            )
        )
    }

    /** A signature WITH parameters renders `m(...)` — the suffix rule is unchanged, the fold is not. */
    @Test
    fun `the parenthesis suffix is carried into the dotted path`() {
        val ds = ts(
            """
            interface B5 { m(x: number): { size: number } }
            interface D5 { m(x: number): { size: number | undefined } }
            declare const d5: D5;
            const x5: B5 = d5;
            """
        )
        assert(
            chainOf(ds, 2322) == listOf(
                "Type 'D5' is not assignable to type 'B5'.",
                "  The types returned by 'm(...).size' are incompatible between these types.",
                "    Type 'number | undefined' is not assignable to type 'number'.",
                "      Type 'undefined' is not assignable to type 'number'.",
            )
        )
    }

    /**
     * control - the FIRST fold alone, unchanged: a return type that does NOT drill deeper keeps
     * `The types returned by 'm()'` over the scalar leaf. This is what separates the new fold
     * from a blanket rewrite of the header.
     */
    @Test
    fun `control - a scalar return type keeps the single fold`() {
        val ds = ts(
            """
            interface B3 { m(): number }
            interface D3 { m(): string }
            declare const d3: D3;
            const x3: B3 = d3;
            """
        )
        val chain = chainOf(ds, 2322)
        assert(chain[0] == "Type 'D3' is not assignable to type 'B3'.")
        assert(chain[1] == "  The types returned by 'm()' are incompatible between these types.")
        assert(chain.none { it.contains("m().") })
    }

    /**
     * control - the plain nested-property form is untouched and keeps *The types of*. It was
     * already byte-identical to tsgo, and it is the fixture that located the gap by differing
     * from its call-shaped twin in exactly one ingredient.
     */
    @Test
    fun `control - a nested property keeps The types of`() {
        val ds = ts(
            """
            interface B2 { p: { size: number } }
            interface D2 { p: { size: number | undefined } }
            declare const d2: D2;
            const x2: B2 = d2;
            """
        )
        assert(
            chainOf(ds, 2322) == listOf(
                "Type 'D2' is not assignable to type 'B2'.",
                "  The types of 'p.size' are incompatible between these types.",
                "    Type 'number | undefined' is not assignable to type 'number'.",
                "      Type 'undefined' is not assignable to type 'number'.",
            )
        )
    }

    /**
     * residue - `interface D extends B` reports TS2430 for a DIRECT property mismatch only.
     * tsgo reports it for the nested and call-return shapes above too; we are silent for all of
     * them, so the dotted chain this round added is unreachable through `extends`. Not a chain
     * question, and the reason `complexRecursiveCollections`' walker cannot yet be retired.
     */
    @Test
    fun `residue - interface extends reports only a direct property mismatch`() {
        val direct = ts(
            """
            interface B1 { p: number }
            interface D1 extends B1 { p: string }
            """
        )
        val nested = ts(
            """
            interface B2 { p: { size: number } }
            interface D2 extends B2 { p: { size: number | undefined } }
            """
        )
        val returned = ts(
            """
            interface B4 { m(): { size: number } }
            interface D4 extends B4 { m(): { size: number | undefined } }
            """
        )
        assert(direct.count { it.code == 2430 } == 1)
        assert(nested.none { it.code == 2430 })
        assert(returned.none { it.code == 2430 })
    }
}

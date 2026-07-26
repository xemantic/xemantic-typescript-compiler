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
 * Local corner-case tests for generic inference driven by a type guard that arrives
 * as a PARAMETER.
 *
 * tsc's own `utilitiesPublic.ts` is full of this shape:
 *
 * ```ts
 * function getFirstJSDocTag<T extends JSDocTag>(node, predicate: (tag: JSDocTag) => tag is T) {
 *     return find(getTags(node), predicate);   // T | undefined
 * }
 * ```
 *
 * The callee's own type parameter binds to the CALLER's `T` through the `tag is T`
 * predicate. Two things had to be true for that and neither was: the guard had to be
 * found at all (it lives on the parameter's annotation, not on a function declaration,
 * which is all the resolver looked at), and its target `T` had to resolve (it does not
 * come through the ambient scope there, so it is interned from the enclosing
 * signature's declaration).
 *
 * The inferred type is asserted through the DISPLAY in a deliberate mismatch, because
 * that is the only place an inferred type is externally visible. The concrete-target
 * cases are the controls that keep the fix honest: they must keep working, and they
 * are what distinguishes "the guard was found" from "its target resolved".
 */
class GuardParameterInferenceTest {

    private val prelude = """
        interface Tag { kind: number; }
        interface Special extends Tag { s: number; }
        declare function find<T, U extends T>(a: readonly T[], p: (e: T) => e is U): U | undefined;
        declare function find<T>(a: readonly T[], p: (e: T) => boolean): T | undefined;
        declare function getTags(): readonly Tag[];
        declare function isSpecial(tag: Tag): tag is Special;
    """.trimIndent() + "\n"

    private fun shownType(source: String): String? =
        diagnose(prelude + source).firstOrNull { it.code == 2322 }?.message

    @Test
    fun `a PARAMETER-borne guard whose target is the caller's type parameter infers it`() {
        val message = shownType(
            """
            function f<T extends Tag>(predicate: (tag: Tag) => tag is T) {
                var shown: string = find(getTags(), predicate);
            }
            """
        )
        assert(message == "Type 'T | undefined' is not assignable to type 'string'.")
    }

    @Test
    fun `the same through Array filter infers an array of the caller's type parameter`() {
        val message = shownType(
            """
            function f<T extends Tag>(tags: Tag[], predicate: (tag: Tag) => tag is T) {
                var shown: string = tags.filter(predicate);
            }
            """
        )
        assert(message == "Type 'T[]' is not assignable to type 'string'.")
    }

    @Test
    fun `control - a PARAMETER-borne guard with a CONCRETE target infers that target`() {
        // Distinguishes "the guard was found" from "its target resolved": this one only
        // needs the first half, so it localises a future regression to one of the two.
        val message = shownType(
            """
            function f(predicate: (tag: Tag) => tag is Special) {
                var shown: string = find(getTags(), predicate);
            }
            """
        )
        assert(message == "Type 'Special | undefined' is not assignable to type 'string'.")
    }

    @Test
    fun `control - a guard passed as a named function still infers its target`() {
        // The pre-existing path (a resolvable declaration) must be untouched.
        val message = shownType(
            """
            function f() {
                var shown: string = find(getTags(), isSpecial);
            }
            """
        )
        assert(message == "Type 'Special | undefined' is not assignable to type 'string'.")
    }

    @Test
    fun `control - a NON-guard predicate parameter still selects the plain overload`() {
        // No type predicate on the annotation, so nothing may be bound and the call
        // must keep returning the element type.
        val message = shownType(
            """
            function f(predicate: (tag: Tag) => boolean) {
                var shown: string = find(getTags(), predicate);
            }
            """
        )
        assert(message == "Type 'Tag | undefined' is not assignable to type 'string'.")
    }

    @Test
    fun `control - an inner parameter SHADOWS an outer one of the same name`() {
        // The walk is innermost-first; if it were not, the outer `Special` guard would
        // be picked up inside the inner function.
        val message = shownType(
            """
            function outer(predicate: (tag: Tag) => tag is Special) {
                function inner<T extends Tag>(predicate: (tag: Tag) => tag is T) {
                    var shown: string = find(getTags(), predicate);
                }
            }
            """
        )
        assert(message == "Type 'T | undefined' is not assignable to type 'string'.")
    }
}

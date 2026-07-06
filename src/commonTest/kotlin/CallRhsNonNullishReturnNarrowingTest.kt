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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 424 — an assignment whose RHS is a CALL to a callee with a provably
 * non-nullish return ANNOTATION narrows the assigned reference non-nullish
 * (tsc checker.ts:21168: `type.restrictiveInstantiation = instantiateType(type,
 * restrictiveMapper);` followed by a read of the property — `instantiateType`
 * declares `: Type`).
 *
 * The classifier is deliberately SYNTACTIC and conservative — the sharp corners
 * pinned here are the bails: a `Type | undefined` return annotation, an
 * optional-chained call (short-circuits to `undefined`), and a reference to the
 * callee's OWN type parameter (`<T>(x: T): T` — T may be instantiated nullish)
 * must all keep the TS18048.
 */
class CallRhsNonNullishReturnNarrowingTest {

    private val prelude = """
        interface Ty { id: number; inst?: Ty; }
    """

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(
            "// @strict: true\n" + (prelude + body).trimIndent(), "t.ts",
        ).diagnostics

    @Test
    fun callRhsWithNonNullishReturnAnnotationNarrows() {
        val d = diags(
            """
            declare function instantiate(t: Ty): Ty;
            export function f(t: Ty) {
                t.inst = instantiate(t);
                t.inst.inst = t.inst;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 },
            "call RHS with `: Ty` return annotation must prove t.inst non-nullish; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun aliasReturnAnnotationResolvesThroughTypeAlias() {
        val d = diags(
            """
            type Instantiated = Ty;
            declare function instantiate(t: Ty): Instantiated;
            export function f(t: Ty) {
                t.inst = instantiate(t);
                t.inst.inst = t.inst;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 },
            "a type-alias return annotation must resolve through the alias body; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun nullableReturnAnnotationStillFires() {
        val d = diags(
            """
            declare function maybe(t: Ty): Ty | undefined;
            export function f(t: Ty) {
                t.inst = maybe(t);
                t.inst.inst = t.inst;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "a `Ty | undefined` return must keep TS18048; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun optionalChainedCallStillFires() {
        val d = diags(
            """
            declare const factory: { make(t: Ty): Ty } | undefined;
            export function f(t: Ty) {
                t.inst = factory?.make(t);
                t.inst.inst = t.inst;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "an optional-chained call short-circuits to undefined — TS18048 must stand; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun genericOwnTypeParamReturnStillFires() {
        val d = diags(
            """
            declare function ident<T>(x: T): T;
            export function f(t: Ty, m: Ty | undefined) {
                t.inst = ident(m);
                t.inst.inst = t.inst;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "a bare own-type-param return (`<T>(x: T): T`) may be nullish — TS18048 must stand; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}

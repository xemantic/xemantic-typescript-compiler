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
 * Multi-base heritage with a GENERIC base BEFORE a comma
 * (`interface NodeArray<T> extends ReadonlyArray<T>, TextRange {}`) was misparsed: the
 * non-last generic base `ReadonlyArray<T>` collapsed into a value-position instantiation
 * expression (`ParenthesizedExpression`) whose `<T>` type arguments were DROPPED, so
 * `resolveBaseSym` returned null AND the checker false-emitted TS2499 ("An interface can
 * only extend an identifier/qualified-name with optional type arguments.").
 *
 * The parser now suppresses the instantiation-expr conversion while parsing a heritage-base
 * spine (`parsingHeritageBase`), so `Foo<T>,` keeps its type arguments — matching tsc, whose
 * `parseLeftHandSideExpressionOrHigher` returns an ExpressionWithTypeArguments that heritage
 * uses verbatim. This kills the 16 self-compile TS2499 FPs (tsc's own `NodeArray`,
 * `MutableNodeArray`, `WatchCompilerHost`, `MutateMapOptions`, … all use this shape) and
 * restores base-member inheritance for the non-last generic base.
 */
class MultiBaseGenericHeritageTest {

    private fun diags(body: String): List<Diagnostic> =
        TypeScriptCompiler().compile(body.trimIndent(), "t.ts").diagnostics

    @Test
    fun `interface with non-last generic base no longer FP-emits TS2499`() {
        val d = diags(
            """
            interface TextRange { pos: number; end: number; }
            interface NodeArray<T> extends ReadonlyArray<T>, TextRange { hasTrailingComma: boolean; }
            """,
        )
        assertTrue(
            d.none { it.code == 2499 },
            "non-last generic base must NOT fire TS2499; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `three bases with two generic non-last bases - no TS2499`() {
        val d = diags(
            """
            interface A<T> { a: T; }
            interface B<U> { b: U; }
            interface Marker { m: number; }
            interface Combo<T, U> extends A<T>, B<U>, Marker {}
            """,
        )
        assertTrue(
            d.none { it.code == 2499 },
            "multiple generic non-last bases must NOT fire TS2499; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `non-last generic base preserves its type arguments - member inheritance works`() {
        // Container<T>.value is inherited into Sub<number> with T=number, so assigning it to
        // a string must be TS2322. Before the fix the base was lost, so `s.value` was `any`
        // (or TS2339) and no assignability error fired — this is the sharp signal that the
        // type arguments survived the multi-base parse.
        val d = diags(
            """
            interface Container<T> { value: T; }
            interface Named { name: string; }
            interface Sub<T> extends Container<T>, Named {}
            declare const s: Sub<number>;
            const x: string = s.value;
            """,
        )
        assertTrue(
            d.any { it.code == 2322 },
            "s.value (inherited number) assigned to string must be TS2322; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
        assertTrue(
            d.none { it.code == 2339 },
            "s.value must resolve (member inherited from the non-last generic base), not TS2339; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `class implements with non-last generic base - no TS2499`() {
        val d = diags(
            """
            interface A<T> { a: T; }
            interface B { b: number; }
            class C implements A<string>, B { a = "x"; b = 1; }
            """,
        )
        assertTrue(
            d.none { it.code == 2499 },
            "class implements with a non-last generic base must NOT fire TS2499; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `genuine non-entity-name interface base STILL fires TS2499 (negative control)`() {
        // A call-expression base is a real non-entity-name; the heritage-spine flag only
        // suppresses the instantiation-expr collapse, so this must still error.
        val d = diags(
            """
            declare function foo(): { x: number };
            interface Bad extends foo() {}
            """,
        )
        assertTrue(
            d.any { it.code == 2499 },
            "interface extends foo() must still be TS2499; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `single generic base in last position still works (regression control)`() {
        // This shape already parsed correctly (the follow token `{` is not in
        // canFollowTypeArgumentsInExpression); pin that the flag did not disturb it.
        val d = diags(
            """
            interface Base<T> { value: T; }
            interface Only<T> extends Base<T> {}
            declare const o: Only<number>;
            const y: string = o.value;
            """,
        )
        assertTrue(
            d.any { it.code == 2322 },
            "single generic base member inheritance must still work (TS2322); got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}

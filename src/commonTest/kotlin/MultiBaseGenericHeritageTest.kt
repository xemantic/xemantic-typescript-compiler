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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

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

    @Test
    fun `interface with non-last generic base no longer FP-emits TS2499`() {
        diagnose(
            """
            interface TextRange { pos: number; end: number; }
            interface NodeArray<T> extends ReadonlyArray<T>, TextRange { hasTrailingComma: boolean; }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2499 })
        }
    }

    @Test
    fun `three bases with two generic non-last bases - no TS2499`() {
        diagnose(
            """
            interface A<T> { a: T; }
            interface B<U> { b: U; }
            interface Marker { m: number; }
            interface Combo<T, U> extends A<T>, B<U>, Marker {}
            """,
            directives = "",
        ) should {
            have(none { it.code == 2499 })
        }
    }

    @Test
    fun `non-last generic base preserves its type arguments - member inheritance works`() {
        // Container<T>.value is inherited into Sub<number> with T=number, so assigning it to
        // a string must be TS2322. Before the fix the base was lost, so `s.value` was `any`
        // (or TS2339) and no assignability error fired — this is the sharp signal that the
        // type arguments survived the multi-base parse.
        diagnose(
            """
            interface Container<T> { value: T; }
            interface Named { name: string; }
            interface Sub<T> extends Container<T>, Named {}
            declare const s: Sub<number>;
            const x: string = s.value;
            """,
            directives = "",
        ) should {
            have(any { it.code == 2322 })
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `class implements with non-last generic base - no TS2499`() {
        diagnose(
            """
            interface A<T> { a: T; }
            interface B { b: number; }
            class C implements A<string>, B { a = "x"; b = 1; }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2499 })
        }
    }

    @Test
    fun `genuine non-entity-name interface base STILL fires TS2499 (negative control)`() {
        // A call-expression base is a real non-entity-name; the heritage-spine flag only
        // suppresses the instantiation-expr collapse, so this must still error.
        diagnose(
            """
            declare function foo(): { x: number };
            interface Bad extends foo() {}
            """,
            directives = "",
        ) should {
            have(any { it.code == 2499 })
        }
    }

    @Test
    fun `single generic base in last position still works (regression control)`() {
        // This shape already parsed correctly (the follow token `{` is not in
        // canFollowTypeArgumentsInExpression); pin that the flag did not disturb it.
        diagnose(
            """
            interface Base<T> { value: T; }
            interface Only<T> extends Base<T> {}
            declare const o: Only<number>;
            const y: string = o.value;
            """,
            directives = "",
        ) should {
            have(any { it.code == 2322 })
        }
    }
}

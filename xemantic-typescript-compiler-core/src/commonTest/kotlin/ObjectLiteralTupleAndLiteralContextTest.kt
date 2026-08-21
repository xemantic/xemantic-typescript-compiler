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
 * (CHK.32) An object literal's members are typed UNDER the target's shape.
 *
 * The second of the two families `docs/kir-library-readiness.md` measured in
 * the `yaml` library — every CST token is `{ …, range: [start, end] }` against
 * a `Range = [number, number]`, and the array literal typed itself `number[]`
 * in a vacuum. Two halves:
 *
 * - an array literal whose contextual property type is a TUPLE **is** that
 *   tuple (an array is not a tuple, so nothing else could make it relate);
 * - the annotation is installed as the object literal's context in a variable
 *   declaration, as the RETURN path has done since round 462 — the same object
 *   must not type differently in the two positions.
 *
 * The context is installed only where the target's shape ASKS for it (a member
 * that is a tuple or contains literals), because installing it unconditionally
 * measurably manufactured a false TS2322 on the compiler profile, where an
 * object literal assigns a GENERIC function to a non-generic member. tsgo 7.0.2
 * is clean on every case here.
 */
class ObjectLiteralTupleAndLiteralContextTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "t.ts").diagnostics

    private val prelude = """
        // @strict: true
        type Span = [number, number]
        type Kind = 'A' | 'B'
        interface Token {
            value: string
            kind: Kind | null
            range: Span
        }
    """

    @Test
    fun `a returned object literal keeps a tuple member`() {
        compile(
            prelude + "\nfunction f(): Token { return { value: 'v', kind: null, range: [1, 2] } }"
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an ANNOTATED object literal keeps a tuple member`() {
        compile(
            prelude + "\nconst t: Token = { value: 'v', kind: 'A', range: [3, 4] }"
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an annotated object literal keeps a literal member`() {
        compile(
            prelude + "\ninterface OnlyKind { kind: Kind }\nconst k: OnlyKind = { kind: 'A' }"
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `an object literal argument keeps a tuple member`() {
        compile(
            prelude + """

            declare function take(t: Token): string
            console.log(take({ value: 'v', kind: 'B', range: [5, 6] }))
            """
        ) should {
            // BOTH codes: an argument mismatch is TS2345 and a per-property one
            // is TS2322, and `it.code == 2345 && it.code == 2322` — the shape
            // this line nearly took — is a predicate no diagnostic satisfies.
            have(none { it.code == 2345 || it.code == 2322 })
        }
    }

    @Test
    fun `negative control - the wrong element count is still an error`() {
        compile(
            prelude + "\ninterface OnlySpan { range: Span }\nconst s: OnlySpan = { range: [1, 2, 3] }"
        ) should {
            have(any { it.code == 2322 || it.code == 2353 })
        }
    }

    @Test
    fun `negative control - a wrong element type is still an error`() {
        compile(
            prelude + "\ninterface OnlySpan { range: Span }\nconst s: OnlySpan = { range: [1, 'x'] }"
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a literal outside the union is still an error`() {
        compile(
            prelude + "\ninterface OnlyKind { kind: Kind }\nconst k: OnlyKind = { kind: 'Z' }"
        ) should {
            have(any { it.code == 2322 })
        }
    }

}

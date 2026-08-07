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
 * (M3.0-gap-1) A generic arrow's OWN type parameters must be in scope while its
 * parameters and return type are resolved.
 *
 * They used to be interned only when the `Signature` was built — after the return
 * had been inferred — so in `<T>(n: T) => n` the annotation `T` resolved to
 * nothing, the parameter never reached `currentLocalTypes`, the body typed as
 * `any`, and the arrow came out `<T>(n: T) => any`. Every generic arrow was
 * silently mistyped; it surfaced as a TS2403 false positive against an equivalent
 * annotation, which is what these pins hold down. The non-generic controls matter
 * because they always worked — they localise a future regression to the type-
 * parameter scope rather than to return inference in general.
 */
class GenericArrowReturnInferenceTest {

    @Test
    fun `a generic identity arrow infers its type parameter as the return type`() {
        val diagnostics = diagnose(
            """
            var f = <T>(n: T) => n;
            var f: { <T>(n: T): T };
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.none { it.code == 2403 })
    }

    @Test
    fun `a generic arrow returning an array literal infers the array of its type parameter`() {
        val diagnostics = diagnose(
            """
            var f = <T>(n: T) => [n];
            var f: { <T>(n: T): T[] };
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.none { it.code == 2403 })
    }

    @Test
    fun `a generic arrow with a block body infers through its return statement`() {
        val diagnostics = diagnose(
            """
            var f = <T>(n: T) => { return [n]; };
            var f: { <T>(n: T): T[] };
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.none { it.code == 2403 })
    }

    @Test
    fun `a constrained type parameter resolves its constraint`() {
        val diagnostics = diagnose(
            """
            interface Named { name: string }
            var f = <T extends Named>(n: T) => n.name;
            var f: { <T extends Named>(n: T): string };
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.none { it.code == 2403 })
        assert(diagnostics.none { it.code == 2339 })
    }

    @Test
    fun `control - a non-generic arrow still infers its return`() {
        val diagnostics = diagnose(
            """
            var f = (n: number) => [n];
            var f: { (n: number): number[] };
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.none { it.code == 2403 })
    }

    @Test
    fun `negative control - a genuinely different subsequent declaration still reports TS2403`() {
        val diagnostics = diagnose(
            """
            var f = <T>(n: T) => n;
            var f: { <T>(n: T): string };
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.any { it.code == 2403 })
    }

    @Test
    fun `negative control - a non-generic mismatch still reports TS2403`() {
        val diagnostics = diagnose(
            """
            var g = (n: number) => n;
            var g: { (n: number): string };
            """,
            directives = "// @strict: false",
        )
        assert(diagnostics.any { it.code == 2403 })
    }
}

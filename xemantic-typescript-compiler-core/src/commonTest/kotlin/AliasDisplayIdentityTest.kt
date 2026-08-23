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
 * (INC.11) A GENERIC ALIAS THAT ANSWERS ONE OF ITS OWN ARGUMENTS UNCHANGED IS NOT A
 * NAME FOR THAT ARGUMENT, AND REGISTERING IT AS ONE IS PROGRAM-WIDE.
 *
 * `aliasDisplayMap` is keyed by `Type.id` and `typeToString` consults it BEFORE the
 * structural union-alias table, so one wrong entry renames a type at every later
 * reference in the whole program — in a diagnostic and in a hover alike.
 *
 * The registration at the generic-instantiation site is unconditional (last wins),
 * and a conditional alias whose condition cannot be decided — because one of its
 * arguments still mentions a free type parameter — answers its CHECK type itself.
 * So `Pass<Shape, Pick<T, "a">>` used in a signature registered the name `Pass` onto
 * `Shape`, and every mention of `Shape` afterwards printed as
 * `Pass<Shape, Pick<T, "a">>`, an unbound `T` and all.
 *
 * Measured on tsc's own sources before the fix: a caret on the type reference
 * `ClassLikeDeclaration` in `es2015.ts` reported
 * `Extract<ClassDeclaration | ClassExpression, Pick<T, "kind">>`, because
 * `classThis.ts` and `namedEvaluation.ts` declare overloads returning
 * `Extract<ClassLikeDeclaration, Pick<T, "kind">>` and the setup pass resolves every
 * file-level declaration before anything is checked.
 *
 * ## Why the assertion is on a DIAGNOSTIC and not on a capture
 *
 * Type capture is off in a plain build, so a capture assertion would need the
 * project harness; the rendering is the same `typeToString` either way, and a
 * `TS2322` names the source type verbatim. That also makes this pin the cheapest
 * available statement about a display defect the 8 dashboard profiles cannot see
 * — they are one codebase, and this shape lives in two of its files.
 */
class AliasDisplayIdentityTest {

    /**
     * A conditional alias applied to a free type parameter cannot decide, answers
     * its check type, and must NOT rename it.
     */
    @Test
    fun `a conditional alias that cannot decide does not rename its own argument`() {
        val diagnostics = diagnose(
            """
            interface Shape { a: number }
            type Pass<T, U> = T extends U ? T : never;
            declare function pick<T>(x: T): Pass<Shape, Pick<T, "a">>;
            declare const s: Shape;
            const bad: number = s;
            """,
            // `Pick` is a LIB utility type, and the embedded lib declares NONE of
            // them — an undeclared name degrades to `any`, which decides the
            // conditional and takes the fixture off the mechanism entirely. The
            // first draft of this pin was silent for exactly that reason and only
            // the ablation said so (CLAUDE.md's round-725/806 pair).
            directives = "// @strict: true\n// @useRealLibs: true",
        )
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        val message = ts2322.single().message
        // The source type is `Shape`, under its own name.
        assert("'Shape'" in message)
        // …and NOT under the name of an alias that contributed nothing to it.
        assert("Pass<" !in message)
    }

    /**
     * The negative control: an alias that DOES build a new type keeps its name, so
     * the rule above is an identity test and not a blanket refusal to register.
     */
    @Test
    fun `negative control - an alias that builds a new type still names it`() {
        val diagnostics = diagnose(
            """
            interface Shape { a: number }
            type Boxed<T> = { inner: T };
            declare function box(): Boxed<Shape>;
            declare const b: Boxed<Shape>;
            const bad: number = b;
            """,
            directives = "// @strict: true\n// @useRealLibs: true",
        )
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert("Boxed<Shape>" in ts2322.single().message)
    }
}

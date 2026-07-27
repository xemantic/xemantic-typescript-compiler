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
 * Round 727: a CONSTRUCT SIGNATURE's own type parameter must not escape into the
 * new-expression's type — an uninferred one takes its DECLARED DEFAULT.
 *
 * The real lib declares `interface SetConstructor { new <T = any>(values?:
 * readonly T[] | null): Set<T> }` (the embedded lib declares a non-generic
 * `new(): Set<any>`, which is why the family is invisible on the default path),
 * so `new Set()` yielded `Set<T>` — the raw signature type parameter. tsc's
 * `(state.hasCalledUpdateShapeSignature ||= new Set()).add(path)` then resolved
 * `.add` on the union `Set<Path> | Set<T>`, and the B516 union-of-callables rule
 * correctly intersected the two parameters into `Path & T`, which nothing
 * satisfies (builderState.ts:396/457, resolutionCache.ts:1109).
 *
 * The shape is reproduced here with a locally declared constructor interface, so
 * it holds on the embedded lib too.
 */
class ConstructSignatureTypeParamDefaultTest {

    private val shape = """
        interface Holder<T> { put(v: T): void; get(): T }
        interface HolderConstructor { new <T = number>(): Holder<T>; }
        declare const Holder: HolderConstructor;
    """.trimIndent()

    @Test
    fun `an uninferred construct-signature type parameter takes its declared default`() {
        // With the raw `T` leaking, a string argument is accepted by the bare type
        // parameter and nothing fires; the substituted default makes it `number`.
        diagnose(
            shape + """

            const bad = new Holder().put("x");
            """,
        ) should {
            have(any { it.code == 2345 && it.message.contains("parameter of type 'number'") })
        }
    }

    @Test
    fun `the substituted default keeps a matching argument legal`() {
        diagnose(
            shape + """

            const ok = new Holder().put(1);
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `the leaked type parameter no longer poisons a union receiver's combined signature`() {
        // The live shape: `x ||= new Set()` unions the declared type with the new
        // expression's, and a method on that union intersects the parameters — so a
        // leaked `T` made every argument fail.
        diagnose(
            """
            interface Holder<T> { put(v: T): void }
            interface HolderConstructor { new <T = any>(): Holder<T>; }
            declare const Holder: HolderConstructor;
            type Path = string & { __pathBrand: any };
            interface State { held?: Holder<Path>; }

            function f(state: State, path: Path): void {
                (state.held ||= new Holder()).put(path);
                (state.held ??= new Holder()).put(path);
            }
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a construct-signature type parameter with no default is left alone`() {
        // Substituting `unknown` here would be a different (and unrequested) rule;
        // the defaultless case must keep its existing behaviour.
        diagnose(
            """
            interface Holder<T> { put(v: T): void }
            interface HolderConstructor { new <T>(): Holder<T>; }
            declare const Holder: HolderConstructor;

            const x = new Holder().put("anything");
            """,
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an explicit type argument still wins over the default`() {
        diagnose(
            shape + """

            const bad = new Holder<string>().put(1);
            """,
        ) should {
            have(any { it.code == 2345 && it.message.contains("parameter of type 'string'") })
        }
    }
}

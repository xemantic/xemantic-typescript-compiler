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
 * (CHK.31) A type guard written as a `const` ARROW narrows, like a `function`.
 *
 * `const isScalar = (n: any): n is Scalar => …` resolves to a
 * VariableDeclaration, which carries no parameter list and no return type — so
 * every consumer of a resolved callee found nothing and the guard narrowed
 * NOTHING, silently and in the false-positive direction: the reference kept its
 * whole declared union and every member read on it was a TS2339.
 *
 * Not a corner: it is how the `yaml` library writes ALL of its guards, and it
 * accounted for most of that library's remaining false positives. tsgo 7.0.2 is
 * clean on every case here.
 */
class ConstArrowTypeGuardTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "entry.ts").diagnostics

    private val nodes = """
        // @strict: true

        // @Filename: /proj/src/nodes.ts
        export class Doc { contents: string | null = null }
        export class Alias { alias = 'a' }
        export const isDocConst = (x: unknown): x is Doc => x instanceof Doc
        export function isDocFn(x: unknown): x is Doc { return x instanceof Doc }
    """

    @Test
    fun `a local const-arrow guard narrows`() {
        compile(
            nodes + """

            // @Filename: /proj/src/use.ts
            import { Doc, Alias } from './nodes'
            const isDocHere = (x: unknown): x is Doc => x instanceof Doc
            export function f(x: Alias | Doc): void {
                if (isDocHere(x)) { console.log(x.contents) }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `an IMPORTED const-arrow guard narrows`() {
        compile(
            nodes + """

            // @Filename: /proj/src/use.ts
            import { Doc, Alias, isDocConst } from './nodes'
            export function f(x: Alias | Doc): void {
                if (isDocConst(x)) { console.log(x.contents) }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `the function-declaration form still narrows - the control`() {
        compile(
            nodes + """

            // @Filename: /proj/src/use.ts
            import { Doc, Alias, isDocFn } from './nodes'
            export function f(x: Alias | Doc): void {
                if (isDocFn(x)) { console.log(x.contents) }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a member on neither constituent still errors`() {
        compile(
            nodes + """

            // @Filename: /proj/src/use.ts
            import { Doc, Alias, isDocConst } from './nodes'
            export function f(x: Alias | Doc): void {
                if (isDocConst(x)) { console.log((x as Doc & { nope: string }).nope) }
                console.log((x as Alias).alias)
            }
            export function g(x: Alias | Doc): void {
                if (isDocConst(x)) { console.log(x.alias) }
            }
            """
        ) should {
            // `alias` is on the OTHER constituent, so narrowing to `Doc` must
            // make it an error — the proof that this narrows rather than widens.
            have(any { it.code == 2339 })
        }
    }

}

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
 * Three defects a MODULE finds and a single file cannot, all measured against
 * tsgo 7.0.2 on the same two files and all silent in the dangerous direction.
 *
 * The shape they share is that an imported name is an ALIAS symbol, and each
 * consumer asked a question the alias cannot answer:
 *
 * 1. `x instanceof ImportedClass` — the alias has neither `SymbolFlags.Class`
 *    nor the value flags the constructor-value leg needs, so it narrowed
 *    NOTHING and every member read on the reference was a false TS2339;
 * 2. `if (importedGuard(x))` — the guard's declaration is reached by resolving
 *    the import's specifier, and that resolver knew only flat corpus-style keys,
 *    so on a path-shaped project it resolved nothing and the guard narrowed
 *    nothing (round 512's lesson, one resolver over);
 * 3. `import f from './m'` where `m` says `export default function f` — a
 *    declaration carrying both modifiers is neither an `ExportAssignment` nor
 *    bound under the name `default`, so the alias resolved to `any` and every
 *    misuse of a default-imported function went UNREPORTED.
 *
 * Each pin asserts what becomes LEGAL rather than what stays broken, except (3),
 * which asserts the diagnostic that was missing — the only assertion a
 * false-negative admits. The `local` control beside (1) and (2) is what
 * separates "this narrowing works" from "this narrowing works across a module
 * boundary", which is the whole claim.
 */
class CrossFileNarrowingAndDefaultImportTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "entry.ts").diagnostics

    private val nodes = """
        // @strict: true

        // @Filename: /proj/src/nodes.ts
        export class Doc { contents: string | null = null }
        export class Alias { alias = 'a' }
        export function isDoc(x: unknown): x is Doc { return x instanceof Doc }
    """

    @Test
    fun `instanceof narrows a class imported from another module`() {
        compile(
            nodes + """

            // @Filename: /proj/src/use.ts
            import { Doc, Alias } from './nodes'
            export function f(x: Alias | Doc): void {
                if (x instanceof Doc) { console.log(x.contents) }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a type guard imported from another module narrows`() {
        compile(
            nodes + """

            // @Filename: /proj/src/use.ts
            import { Doc, Alias, isDoc } from './nodes'
            export function f(x: Alias | Doc): void {
                if (isDoc(x)) { console.log(x.contents) }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `the same guard declared locally narrows - the control`() {
        compile(
            nodes + """

            // @Filename: /proj/src/use.ts
            import { Doc, Alias } from './nodes'
            function isDocHere(x: unknown): x is Doc { return x instanceof Doc }
            export function f(x: Alias | Doc): void {
                if (isDocHere(x)) { console.log(x.contents) }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a default-imported function has its declared type and not any`() {
        compile(
            """
            // @strict: true

            // @Filename: /proj/src/d.ts
            export default function twice(x: number): number { return x * 2 }

            // @Filename: /proj/src/use.ts
            import twice from './d'
            const probe: string = twice(1)
            """
        ) should {
            // tsgo 7.0.2 reports exactly this; before the fix the alias
            // resolved to `any`, which is assignable to everything, so the
            // program was silent — the failure mode a false negative always has.
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `a default-imported CLASS has its declared type`() {
        compile(
            """
            // @strict: true

            // @Filename: /proj/src/c.ts
            export default class Counter { value = 1 }

            // @Filename: /proj/src/use.ts
            import Counter from './c'
            const probe: string = new Counter().value
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

}

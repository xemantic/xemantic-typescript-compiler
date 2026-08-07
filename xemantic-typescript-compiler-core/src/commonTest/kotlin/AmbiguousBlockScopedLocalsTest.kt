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
 * Round 460: a name declared by TWO OR MORE block-scoped (`let`/`const`)
 * declarations in ONE function body is AMBIGUOUS in the block-UNAWARE
 * first-decl-wins `currentLocalTypes` — a read after the blocks resolved to
 * whichever declaration the walk saw FIRST. tsc's program.ts
 * `findSourceFileWorker` declares `const file = filesByName.get(path)`
 * (`SourceFile | false | undefined`) inside an if-block AND a function-level
 * `const file = host.getSourceFile(…)` (`SourceFile | undefined`); the final
 * `return file;` read the if-block binding's type → FP TS2322
 * "'false | SourceFile | undefined' is not assignable to 'SourceFile |
 * undefined'" (program.ts:3705). Such names now register anyType at body
 * entry (suppression-only).
 */
class AmbiguousBlockScopedLocalsTest {

    private val prelude = """
        interface SourceFile { fileName: string; }
        declare function hostGet(name: string): SourceFile | undefined;
        declare function mapGet(path: string): SourceFile | false | undefined;
        declare function has(path: string): boolean;

    """.trimIndent()

    @Test
    fun `two block-scoped const decls of one name - the later return is not checked against the first block's type`() {
        diagnose(prelude + """
            function findSourceFileWorker(fileName: string): SourceFile | undefined {
                if (has(fileName)) {
                    const file = mapGet(fileName);
                    return file || undefined;
                }
                const file = hostGet(fileName);
                return file;
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `annotated block-scoped duplicates - the second block's read is not checked against the first annotation`() {
        diagnose(prelude + """
            function f(cond: boolean): string {
                if (cond) {
                    const x: number = 1;
                    return String(x);
                } else {
                    const x: string = "s";
                    return x;
                }
            }
        """.trimIndent()) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a SINGLE block-scoped decl still records its type for later reads`() {
        diagnose(prelude + """
            function f(): number {
                const x: string = "s";
                return x;
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - duplicate decls each still check their OWN initializer against their annotation`() {
        diagnose(prelude + """
            function f(cond: boolean): void {
                if (cond) {
                    const x: number = "not a number";
                } else {
                    const x: number = 2;
                }
            }
        """.trimIndent()) should {
            have(any { it.code == 2322 })
        }
    }
}

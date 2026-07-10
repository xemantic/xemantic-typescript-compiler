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
 * Pins the round-464 invariant: a BARREL-imported enum's member access
 * (`NodeBuilderFlags.None` where the enum arrives via `export *` +
 * an ESM `.js` specifier) classifies as NON-NULLISH in flow narrowing, so
 * the flag-defaulting idiom `flags = flags || NodeBuilderFlags.None` strips
 * `undefined` from later reads. tsc's own `withContext` (checker.ts:6636)
 * relies on it — without the barrel fallback in [receiverResolvesToRealEnum]
 * both a later arithmetic use (TS2362) and an object-literal shorthand member
 * (TS2322 via the property chain) FP'd.
 */
class BarrelEnumDefaultInitNarrowingTest {

    private fun build(checkerSource: String): ProjectCompiler.Result {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to
                    """{ "compilerOptions": { "strict": true, "module": "nodenext", "target": "es2020" }, "include": ["src/**/*"] }""",
                "/proj/src/types.ts" to """
                    export const enum NodeBuilderFlags {
                        None = 0,
                        NoTruncation = 1,
                    }
                    export interface NodeBuilderContext {
                        flags: NodeBuilderFlags;
                        depth: number;
                    }
                """.trimIndent(),
                "/proj/src/barrel.ts" to """export * from "./types.js";""",
                "/proj/src/checker.ts" to checkerSource.trimIndent(),
            )
        )
        return ProjectCompiler(vfs).build("/proj", noEmit = true)
    }

    @Test
    fun `barrel-imported enum default-init narrows undefined out of arithmetic and objlit member reads`() {
        val result = build(
            """
            import { NodeBuilderFlags, NodeBuilderContext } from "./barrel.js";
            export function withContext(flags: NodeBuilderFlags | undefined): NodeBuilderContext {
                flags = flags || NodeBuilderFlags.None;
                const max = flags & NodeBuilderFlags.NoTruncation ? 1000 : 100;
                const context: NodeBuilderContext = {
                    flags,
                    depth: max,
                };
                return context;
            }
            """
        )
        result.diagnostics should {
            have(none { it.code == 2362 })
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - without the default-init assignment the objlit member keeps firing`() {
        val result = build(
            """
            import { NodeBuilderFlags, NodeBuilderContext } from "./barrel.js";
            export function withContext(flags: NodeBuilderFlags | undefined): NodeBuilderContext {
                const context: NodeBuilderContext = {
                    flags,
                    depth: 100,
                };
                return context;
            }
            """
        )
        result.diagnostics should {
            have(any { it.code == 2322 })
        }
    }
}

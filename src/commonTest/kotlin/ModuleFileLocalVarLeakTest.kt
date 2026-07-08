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
 * Round 442 (Blocker #3, self-compile burn-down): a top-level `let`/`var`/`const` variable in a
 * MODULE file leaks into `globals` via the file-locals merge but is NOT legitimately visible by
 * bare name in another file. A common local name like `parent` (navigationBar.ts's module-level
 * `let parent: NavigationBarNode`) otherwise shadowed every OTHER file's local `parent` — a
 * block-scoped const or nested-fn param our scope machinery misses (B83.5) — so the property-access
 * walker resolved `parent` to `NavigationBarNode` and FP-fired TS2339 on `Node`/`BinaryExpression`
 * members (279 on the services profile). `checkMemberAccessMissing` now bails for a bare-Identifier
 * receiver whose name is EXCLUSIVELY a module-file-local variable, UNLESS it IS the current file's
 * own top-level binding (currentFileLocals).
 */
class ModuleFileLocalVarLeakTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "b.ts").diagnostics

    @Test
    fun `another file's local shadows the module-var leak - no TS2339`() {
        // a.ts (module) has `let parent: NavNode`; b.ts has a block-scoped local `parent` of an
        // unrelated shape. `parent.left` in b.ts must NOT resolve to NavNode → no TS2339.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface NavNode { node: number; parent: NavNode | undefined; }
            let parent: NavNode = undefined!;
            export function walk(): number { return parent.node; }

            // @Filename: b.ts
            interface Expr { left: number; }
            export function useExpr(e: Expr): number {
                const parent = e;
                return parent.left;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `the declaring file's own module var still reports missing members - TS2339`() {
        // Negative control: within a.ts, `parent` IS the file's own top-level var (currentFileLocals),
        // so a genuinely-missing member must still fire TS2339 (the bail is gated to OTHER files).
        TypeScriptCompiler().compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface NavNode { node: number; parent: NavNode | undefined; }
            let parent: NavNode = undefined!;
            export function walk(): number { return parent.nonexistent; }

            // @Filename: b.ts
            export const other = 1;
            """.trimIndent(),
            "a.ts",
        ).diagnostics should {
            have(any { it.code == 2339 && it.message.contains("nonexistent") })
        }
    }
}

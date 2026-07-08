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
 * Round 445 (Blocker #3, self-compile burn-down): round 442's `moduleFileLocalVarNames` bail
 * (a top-level `let`/`var`/`const` in a MODULE file leaks into `globals` and shadows every
 * OTHER file's same-named block-scoped local, which is unbound per B83.5) covered TS2339 /
 * TS2345. This round extends it to the TS2322 RETURN and simple-ASSIGNMENT paths: a
 * `return parent` / `lastParent = parent` where a block/destructured `parent` leaks to
 * navigationBar.ts's `let parent: NavigationBarNode` FP'd against the real target type
 * (inferFromUsage.ts `return parent`, checker.ts `lastParent = parent`). Both bail UNLESS the
 * identifier IS the current file's own top-level binding. Suppression-only (a cross-file
 * module var is TS2304 in real tsc, never TS2322).
 */
class ModuleVarLeakAssignReturnTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "entry.ts").diagnostics

    @Test
    fun `return and assignment of a leaked module-var local do not FP TS2322`() {
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            export let parent: { aaa: number };

            // @Filename: b.ts
            interface Named { parent: Named; kind: number; }
            interface Target { bbb: number; }
            declare function getNamed(): Named;
            export function f(cond: boolean): Target | undefined {
                const { parent } = getNamed();
                if (cond) {
                    return parent;
                }
                let lastParent: Target | undefined;
                lastParent = parent;
                return lastParent;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - returning a genuinely wrong-typed local still fires TS2322`() {
        // `wrong` is this file's own local of a concrete mismatching type — must still fire.
        diagnose(
            """
            interface Target { bbb: number; }
            export function f(): Target {
                const wrong: { aaa: number } = { aaa: 1 };
                return wrong;
            }
            """,
        ) should {
            have(any { it.code == 2322 })
        }
    }
}

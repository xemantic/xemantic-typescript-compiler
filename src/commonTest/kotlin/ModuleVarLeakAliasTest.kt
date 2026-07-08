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
 * Round 448 (self-compile burn-down, Blocker #3): a function-body local ALIASED directly from a
 * leaked module var (`const invocation = parent`, where a.ts's module-level `let parent: NavNode`
 * leaked into globals per round 442 and b.ts's block/destructured `parent` is unbound per B83.5)
 * inherits the wrong cross-file type. That poisons downstream uses the bare-Identifier
 * `moduleFileLocalVarNames` bails can't reach — e.g. a nested object-literal value `{ node:
 * invocation }` in a returned object (signatureHelp.ts ArgumentListInfo). The un-annotated var-decl
 * inference now infers anyType (→ not recorded) when the initializer is a bare leaked-module-var
 * identifier that is not this file's own binding and not already a properly-typed local.
 */
class ModuleVarLeakAliasTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "b.ts").diagnostics

    @Test
    fun `a local aliased from the module-var leak does not poison a nested object-literal value`() {
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface NavNode { node: number; parent: NavNode | undefined; }
            let parent: NavNode = undefined!;
            export function walk(): number { return parent.node; }

            // @Filename: b.ts
            interface Inner { kind: number; }
            interface ArgInfo { flag: boolean; invocation: { kind: number; node: Inner; }; }
            declare const someNode: { parent: Inner };
            export function makeArgInfo(): ArgInfo | undefined {
                const { parent } = someNode;
                const invocation = parent;
                return { flag: false, invocation: { kind: 1, node: invocation } };
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `firewall - a same-named PARAM keeps its real type through an alias`() {
        // When `parent` IS a properly-typed local/param (not the unbound leak), the alias must
        // still carry the real type so a genuine member miss fires.
        compile(
            """
            // @strict: true

            // @Filename: a.ts
            export interface NavNode { node: number; parent: NavNode | undefined; }
            let parent: NavNode = undefined!;
            export function walk(): number { return parent.node; }

            // @Filename: b.ts
            interface Expr { left: number; }
            export function useExpr(parent: Expr): number {
                const alias = parent;
                return alias.left;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }
}

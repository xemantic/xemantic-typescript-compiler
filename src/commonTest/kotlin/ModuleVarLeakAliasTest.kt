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
 * INV.3(d) retire pin (originally round 448's leaked-alias bail, deleted in
 * INV.3(d)(v)): a function-body local ALIASED from a destructured binding
 * (`const { parent } = …; const invocation = parent`) must resolve from the
 * destructured member type, never from a foreign module file's same-named
 * top-level `let parent` (which pre-retire leaked into `globals` and poisoned
 * nested object-literal values — signatureHelp.ts ArgumentListInfo). The
 * fixture keeps the foreign module var as leak bait.
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

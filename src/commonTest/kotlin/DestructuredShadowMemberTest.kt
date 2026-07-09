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
 * Round 450 (self-compile burn-down, services TS2339 -3, Blocker #3): a DESTRUCTURED-const
 * local (`const { parent } = node`) shadowing a same-named module-file `let parent:
 * NavigationBarNode` (leaked into globals per round 442) is not bound (B83.5), so a body
 * read of `parent` resolved to the leaked outer declaration and FP-fired TS2339 on
 * `parent.operatorToken` (navigationBar.ts getFunctionOrClassName).
 *
 * `applyBodyLocalShadowing` handled only a simple `let x` var-decl; it now also registers
 * the binding names of a `const { … } = init` pattern as shadowed and records each from
 * the destructured property type of `init`, so a following user type-guard narrows the
 * right type and a valid member access resolves.
 */
class DestructuredShadowMemberTest {

    private val prelude = """
        interface NavNode { node: BareNode; name: string; indent: number; }
        interface BareNode { parent: BareNode; kind: number; }
        interface BinExpr extends BareNode { operatorToken: { kind: number }; left: BareNode; }
        declare function isBinExpr(n: BareNode): n is BinExpr;
        let parent: NavNode;
    """.trimIndent() + "\n"

    @Test
    fun `type-guard on a destructured-const shadow narrows the destructured type`() {
        diagnose(
            prelude + """
            export function getName(node: { parent: BareNode; name?: string }): string {
                const { parent } = node;
                if (isBinExpr(parent) && parent.operatorToken.kind === 1) {
                    return "" + parent.left.kind;
                }
                return "";
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a valid member access on a destructured-const shadow resolves to the destructured type`() {
        diagnose(
            prelude + """
            export function getKind(node: { parent: BareNode }): number {
                const { parent } = node;
                return parent.kind;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a genuinely-missing member on the destructured local still fires`() {
        diagnose(
            prelude + """
            export function bad(node: { parent: BareNode }): number {
                const { parent } = node;
                return parent.doesNotExist;
            }
            """,
        ) should {
            have(any { it.code == 2339 })
        }
    }
}

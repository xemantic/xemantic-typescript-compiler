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
 * Round 462: an object-literal property VALUE that is a guard-narrowed
 * Identifier / PropertyAccess reference substitutes its NARROWED type when the
 * CONTEXTUAL property type accepts it and the raw type does not (per-property
 * substitute-only-when-the-relation-passes). The return path now provides the
 * object-literal context (a `Result | undefined` union contributes its sole
 * non-nullish object member), mirroring the call-arg path's B83.4g.
 *
 * tsc-source shape: utilities.ts:7458
 * `return { class: node.parent.parent, isImplements: … }` after
 * `isHeritageClause(node.parent) && isClassLike(node.parent.parent)` — the
 * declared `Node` is narrowed DOWN to ClassLikeDeclaration, which the round-438
 * nullish-strip gate alone rejected.
 */
class ObjLitValueContextualNarrowDownTest {

    private val prelude = """
        interface Node2 { kind: number; parent: Node2 }
        interface ClassLike extends Node2 { members: string[] }
        interface Result { readonly cls: ClassLike; readonly isImplements: boolean }
        declare function isClassLike(n: Node2): n is ClassLike;
        declare function isHeritage(n: Node2): boolean;
    """.trimIndent()

    @Test
    fun `guard-narrowed property-access value satisfies the contextual property type`() {
        diagnose(prelude + """
            export function tryGet(node: Node2): Result | undefined {
                if (isHeritage(node.parent) && isClassLike(node.parent.parent)) {
                    return { cls: node.parent.parent, isImplements: true };
                }
                return undefined;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an unguarded value keeps the raw type and fires`() {
        diagnose(prelude + """
            export function tryGet(node: Node2): Result | undefined {
                return { cls: node.parent.parent, isImplements: true };
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a guard on a DIFFERENT path does not narrow the value`() {
        diagnose(prelude + """
            export function tryGet(node: Node2): Result | undefined {
                if (isClassLike(node.parent)) {
                    return { cls: node.parent.parent, isImplements: true };
                }
                return undefined;
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }
}

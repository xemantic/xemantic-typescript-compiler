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
 * Round 470: two coupled `this is X` guard-method narrowing rules (the tsc
 * stringCompletions.ts `getStringLiteralTypes` shape):
 *  1. [resolvePropertyMethodDecl] resolves a nullish-containing UNION receiver
 *     through its sole non-nullish member (`type.isUnion()` where the declared
 *     type is `Type | undefined`) — the guard never resolved before, so nothing
 *     narrowed.
 *  2. The this-guard UNION bail is relaxed: a mid-walk branch join (`guard() &&
 *     cond && read`) unions the negative arm's base with the positive arm's
 *     subtype; when every non-nullish member resolves the method to the SAME
 *     declaration, the general union narrowing applies. A member resolving a
 *     DIFFERENT declaration keeps the bail (the typePredicatesInUnion3 rule).
 */
class ThisGuardUnionReceiverNarrowingTest {

    private val prelude = """
        interface Type {
            flags: number;
            isUnion(): this is UnionType;
            isStringLiteral(): this is StringLiteralType;
        }
        interface UnionType extends Type { types: Type[] }
        interface StringLiteralType extends Type { value: string }
    """.trimIndent()

    @Test
    fun `a this-guard on a nullable receiver narrows in a return ternary`() {
        diagnose(
            prelude + """

            declare function skipConstraint(type: Type): Type;
            function f(type: Type | undefined): number {
                if (!type) return 0;
                type = skipConstraint(type);
                return type.isUnion() ? type.types.length : 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a this-guard survives a mid-chain non-narrowing operand`() {
        diagnose(
            prelude + """

            declare function addToSeen(seen: Set<string>, key: string): boolean;
            function f(type: Type): number {
                return type.isStringLiteral() && !(type.flags & 1024) &&
                    addToSeen(new Set(), type.value) ? 1 : 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `an if-condition guard chain narrows the body read`() {
        diagnose(
            prelude + """

            declare const cond: boolean;
            function f(type: Type): number {
                if (type.isStringLiteral() && cond) { return type.value.length; }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a union member with a DIFFERENT same-named method does not narrow`() {
        // The typePredicatesInUnion3 rule: Type2's `predicate(): boolean` is NOT
        // a guard, so `val.predicate()` must not narrow `Type1 | Type2`.
        diagnose(
            """
            interface Type1 { predicate(): this is Narrow1; a: number }
            interface Narrow1 extends Type1 { narrowed: string }
            interface Type2 { predicate(): boolean; b: number }
            declare const v: Type1 | Type2;
            if (v.predicate()) {
                v.narrowed;
            }
            """
        ) should {
            have(any { it.code == 2339 && "narrowed" in it.message })
        }
    }

    @Test
    fun `negative control - the guarded read still errors on a property absent from the target`() {
        diagnose(
            prelude + """

            function f(type: Type): number {
                return type.isStringLiteral() ? (type as StringLiteralType & { missing: never }).value.length : 0;
            }
            function g(type: Type): string {
                if (type.isUnion()) { return type.missingProp; }
                return "";
            }
            """
        ) should {
            have(any { it.code == 2339 && "missingProp" in it.message })
        }
    }
}

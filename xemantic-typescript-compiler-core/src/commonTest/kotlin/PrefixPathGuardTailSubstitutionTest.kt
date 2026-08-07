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
 * Round 462: the round-424 PREFIX-path guard branch of [narrowByCallPredicate] now
 * SUBSTITUTES the resolved tail type instead of only stripping nullish — tsc
 * re-types `x.y` from the NARROWED `x`, so after `isComputedPropertyName(parent)`
 * the access `parent.parent` has ComputedPropertyName's declared
 * `parent: Declaration`, not Node's `parent: Node` (tsc utilities.ts:5085
 * getDeclarationFromName's `return parent.parent`). A nullish-containing or
 * unresolvable tail keeps the antecedent (no substitution).
 */
class PrefixPathGuardTailSubstitutionTest {

    private val prelude = """
        interface Node2 { kind: number; readonly parent: Node2 }
        interface Declaration2 extends Node2 { _declBrand: any }
        interface ComputedPropertyName2 extends Node2 { kind: 167; readonly parent: Declaration2 }
        declare function isComputedPropertyName2(n: Node2): n is ComputedPropertyName2;
    """.trimIndent()

    @Test
    fun `a receiver-prefix guard re-types the property access from the narrowed receiver`() {
        diagnose(prelude + """
            function getDeclarationFromName(name: Node2): Declaration2 | undefined {
                const parent = name.parent;
                if (isComputedPropertyName2(parent)) return parent.parent;
                return undefined;
            }
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an unguarded access keeps the wide member type and fires`() {
        diagnose(prelude + """
            function getDeclarationFromName(name: Node2): Declaration2 | undefined {
                const parent = name.parent;
                return parent.parent;
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a guard whose tail resolves to a NON-matching type still fires`() {
        diagnose(prelude + """
            interface Other2 extends Node2 { kind: 200; readonly parent: Node2 }
            declare function isOther2(n: Node2): n is Other2;
            function f(name: Node2): Declaration2 | undefined {
                const parent = name.parent;
                if (isOther2(parent)) return parent.parent;
                return undefined;
            }
        """) should {
            have(any { it.code == 2322 })
        }
    }
}

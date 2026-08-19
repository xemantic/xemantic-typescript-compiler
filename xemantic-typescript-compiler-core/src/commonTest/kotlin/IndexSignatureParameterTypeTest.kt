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
 * (CHK.9) round 945 — **an index-signature parameter type is valid when it REDUCES to a
 * string / number / symbol key, and an INTERSECTION is the shape that carries that.**
 *
 * tsc's rule, read off the pinned sources (`checkGrammarIndexSignatureParameters` +
 * `isValidIndexKeyType`): resolve the parameter's type node, answer **TS1337** when some
 * union member is a string/number literal or the type is generic, else **TS1268** unless
 * every union member is a valid key — where a valid key is `String | Number | ESSymbol`, a
 * pattern-literal type, or an intersection that is not generic and has SOME valid
 * constituent.
 *
 * Two gaps, both measured on `indexSignatures1` (12 ours-only rows -> 0):
 *  - `classifyIndexParamType` had no INTERSECTION arm, and the resolution was not even
 *    attempted for an `IntersectionType` NODE — so every BRANDED string
 *    (`type Id = string & { __tag: 'id' }`, the whole point of the rule) was TS1268;
 *  - the generic test read only a bare `TypeReference`, so `[key: T | number]` and
 *    `[key: T & string]` were TS1268 where pristine says TS1337 — a CODE divergence, and its
 *    cause is that an alias's own `T` resolves to `anyType` at this grammar check, so the
 *    question has to be asked of the AST.
 *
 * `someType`/`everyType` distribute over UNIONS only, which is why the intersection arm
 * must NOT propagate a constituent's TS1337: `string & 'a'` is a valid key in tsc.
 */
class IndexSignatureParameterTypeTest {

    private val branded = """
        type TaggedString1 = string & { __tag1: void };
        type TaggedString2 = string & { __tag2: void };
    """

    // ---- valid keys: the fix ---------------------------------------------------------

    @Test
    fun `a branded string alias is a legal index signature parameter type`() {
        diagnose(branded + """
            type Rec1 = { [key: TaggedString1]: number };
        """) should {
            have(none { it.code == 1268 })
            have(none { it.code == 1337 })
        }
    }

    @Test
    fun `an interface index signature keyed by a branded string is legal`() {
        diagnose(branded + """
            interface I1 { [key: TaggedString1]: string }
        """) should {
            have(none { it.code == 1268 })
            have(none { it.code == 1337 })
        }
    }

    @Test
    fun `a union of branded strings is a legal index signature parameter type`() {
        diagnose(branded + """
            interface I3 { [key: TaggedString1 | TaggedString2]: string }
        """) should {
            have(none { it.code == 1268 })
            have(none { it.code == 1337 })
        }
    }

    @Test
    fun `an intersection of branded strings is a legal index signature parameter type`() {
        diagnose(branded + """
            interface I4 { [key: TaggedString1 & TaggedString2]: string }
        """) should {
            have(none { it.code == 1268 })
            have(none { it.code == 1337 })
        }
    }

    @Test
    fun `an intersection of two template literal types is a legal parameter type`() {
        diagnose(
            "declare let combo2: { [x: `\${string}xxx\${string}` & `\${string}yyy\${string}`]: string };"
        ) should {
            have(none { it.code == 1268 })
            have(none { it.code == 1337 })
        }
    }

    // ---- the codes that must still fire, and WHICH one ---------------------------------

    @Test
    fun `an object type is still refused with TS1268`() {
        diagnose("""
            type Invalid = { [key: Error]: string };
        """) should {
            have(any { it.code == 1268 })
            have(none { it.code == 1337 })
        }
    }

    @Test
    fun `an intersection with no valid constituent is refused with TS1268`() {
        diagnose("""
            type A1 = { a: 1 };
            type B1 = { b: 2 };
            type Invalid = { [key: A1 & B1]: string };
        """) should {
            have(any { it.code == 1268 })
            have(none { it.code == 1337 })
        }
    }

    @Test
    fun `a union containing an own type parameter is TS1337 and not TS1268`() {
        diagnose("""
            type Invalid<T extends string> = { [key: T | number]: string };
        """) should {
            have(any { it.code == 1337 })
            have(none { it.code == 1268 })
        }
    }

    @Test
    fun `an intersection containing an own type parameter is TS1337 and not TS1268`() {
        diagnose("""
            type Invalid<T extends string> = { [key: T & string]: string };
        """) should {
            have(any { it.code == 1337 })
            have(none { it.code == 1268 })
        }
    }

    @Test
    fun `a bare own type parameter is still TS1337`() {
        diagnose("""
            type Invalid<T extends string> = { [key: T]: string };
        """) should {
            have(any { it.code == 1337 })
            have(none { it.code == 1268 })
        }
    }

    @Test
    fun `a union of string literals is still TS1337`() {
        diagnose("""
            type Invalid = { [key: 'a' | 'b' | 'c']: string };
        """) should {
            have(any { it.code == 1337 })
            have(none { it.code == 1268 })
        }
    }

    // ---- controls ----------------------------------------------------------------------

    @Test
    fun `a plain string index signature is accepted`() {
        diagnose("type Ok = { [key: string]: number };") should {
            have(none { it.code == 1268 })
            have(none { it.code == 1337 })
            have(none { it.code == 1021 })
        }
    }

    @Test
    fun `a plain symbol index signature is accepted`() {
        diagnose("type Ok = { [key: symbol]: number };") should {
            have(none { it.code == 1268 })
            have(none { it.code == 1337 })
        }
    }
}

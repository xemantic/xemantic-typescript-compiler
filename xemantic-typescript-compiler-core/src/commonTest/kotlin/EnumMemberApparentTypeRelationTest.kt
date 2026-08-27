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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.60) AN **ENUM MEMBER** IS A STRING OR NUMBER LITERAL IN tsc, SO ITS APPARENT TYPE
 * IS THE `String` / `Number` WRAPPER — AND A TARGET THAT WRAPPER SATISFIES ACCEPTS IT.
 *
 * tsc's `TypeFlags.StringLike` is `String | StringLiteral | TemplateLiteral |
 * StringMapping` and an enum literal type carries `StringLiteral | EnumLiteral`, so
 * `getApparentType(E.A)` is `globalStringType`; a numeric member carries
 * `NumberLiteral | EnumLiteral` and answers `globalNumberType`. Here (REL.1)(b) mints a
 * member-LESS [Type.Object] instead, which [Checker.propertiesRelatedTo] rejects against
 * any target that declares a property — **including an all-optional (weak) one** — so
 * `zzzG(ZzzSE.A)` against `{ length?: number }` was a FALSE POSITIVE at every position.
 *
 * The weak rule is not what fires: it correctly DECLINES a target the source shares a
 * property with, and what emits is the ordinary relation. The fix therefore lives in
 * [Checker.structuredTypeRelatedTo]'s object/object leg, which retries an enum-literal
 * source as its apparent PRIMITIVE — routing it through exactly the legs a `string` or
 * `number` source already takes (B69.8's wrapper leg, round 430's empty-`{}` rule,
 * B418's index-signature rule and (CHK.32)'s anonymous-object rule), each with its own
 * measured guards intact.
 *
 * Every expected value read out of tsc 7.0.2 over `build/chk60/mx/m1.ts` and `m2.ts`;
 * [Diagnostic.character] is the CLI's **1-based** column verbatim. NO pristine baseline
 * carries an enum member as an object-target SOURCE, so tsgo is the only oracle for the
 * silent rows — and for a row whose right answer is *nothing*, wording cannot diverge.
 */
class EnumMemberApparentTypeRelationTest {

    /**
     * THE HEADLINE FALSE POSITIVE — a STRING enum member against a weak target it shares
     * `length` with. tsc 7.0.2 is silent at both positions; we emitted TS2345 and TS2322.
     */
    @Test
    fun `a string enum member is accepted by a weak target sharing a String member`() {
        val d = diagnose("""
            enum ZzzR1 { A = "a", B = "b" }
            declare function zzzR1g(o: { length?: number }): void;
            zzzR1g(ZzzR1.A);
            let zzzR1v: { length?: number } = {}
            zzzR1v = ZzzR1.A
        """)
        assert(d.isEmpty())
    }

    /**
     * The NUMERIC flavour, through the `Number` wrapper. tsc 7.0.2 is silent.
     */
    @Test
    fun `a numeric enum member is accepted by a target sharing a Number member`() {
        val d = diagnose("""
            enum ZzzR2 { A = 1, B = 2 }
            declare function zzzR2g(o: { toFixed?: () => string }): void;
            zzzR2g(ZzzR2.A);
        """)
        assert(d.isEmpty())
    }

    /**
     * An AUTO-numbered member carries no explicit initializer and is still numeric.
     */
    @Test
    fun `an auto numbered enum member is accepted by a target sharing a Number member`() {
        val d = diagnose("""
            enum ZzzR3 { A, B }
            declare function zzzR3g(o: { toFixed?: () => string }): void;
            zzzR3g(ZzzR3.A);
        """)
        assert(d.isEmpty())
    }

    /**
     * **THIS IS NOT THE WEAK RULE** — the target's property is REQUIRED, so the weak rule
     * never had a say, and tsc is still silent because `String` declares
     * `readonly length: number`. The row that separates "the weak walker declined" from
     * "the relation accepts".
     */
    @Test
    fun `a string enum member is accepted by a required member the String wrapper declares`() {
        val d = diagnose("""
            enum ZzzR4 { A = "a", B = "b" }
            declare function zzzR4g(o: { length: number }): void;
            zzzR4g(ZzzR4.A);
        """)
        assert(d.isEmpty())
    }

    /**
     * A NAMED interface target — the B69.8 leg's population, which the (CHK.32) primitive
     * leg deliberately does not reach.
     */
    @Test
    fun `a string enum member is accepted by a named interface the String wrapper satisfies`() {
        val d = diagnose("""
            enum ZzzR5 { A = "a", B = "b" }
            interface ZzzR5i { length?: number }
            declare function zzzR5g(o: ZzzR5i): void;
            zzzR5g(ZzzR5.A);
        """)
        assert(d.isEmpty())
    }

    /**
     * The WRAPPER interface itself — auto-boxing, B69.8's own arm.
     */
    @Test
    fun `a string enum member is accepted by the String wrapper itself`() {
        val d = diagnose("""
            enum ZzzR6 { A = "a", B = "b" }
            declare function zzzR6g(o: String): void;
            zzzR6g(ZzzR6.A);
        """)
        assert(d.isEmpty())
    }

    /**
     * CONTROL — a genuinely unrelated target still reports, at tsc 7.0.2's own code,
     * message and 1-based column (`m1.ts(17,6)`, `Type 'ZzzSE.A' has no properties in
     * common with type '{ zzzNope?: number | undefined; }'.`).
     */
    @Test
    fun `control - an enum member against a target sharing nothing still reports TS2559`() {
        val d = diagnose("""
            enum ZzzR7 { A = "a", B = "b" }
            declare function zzzR7g(o: { zzzNope?: number }): void;
            zzzR7g(ZzzR7.A);
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'ZzzR7.A' has no properties in common with type " +
            "'{ zzzNope?: number | undefined; }'.")
        assert(d[0].line == 3)
        assert(d[0].character == 8)
    }

    /**
     * CONTROL — the FLAVOUR boundary in one direction: a STRING enum member against the
     * `Number` wrapper is rejected by tsc 7.0.2 (`m2.ts(27,8)`) and must stay rejected.
     */
    @Test
    fun `control - a string enum member is refused by the Number wrapper`() {
        val d = diagnose("""
            enum ZzzR8 { A = "a", B = "b" }
            declare function zzzR8g(o: Number): void;
            zzzR8g(ZzzR8.A);
        """)
        assert(d.map { it.code } == listOf(2345))
    }

    /**
     * CONTROL — the FLAVOUR boundary in the other direction (`m2.ts(28,8)`).
     */
    @Test
    fun `control - a numeric enum member is refused by the String wrapper`() {
        val d = diagnose("""
            enum ZzzR9 { A = 1, B = 2 }
            declare function zzzR9g(o: String): void;
            zzzR9g(ZzzR9.A);
        """)
        assert(d.map { it.code } == listOf(2345))
    }

    /**
     * CONTROL — a shared NAME at a mismatched TYPE is still a rejection: the weak rule
     * declines (a property IS in common) and the structural comparison of the `String`
     * wrapper against `{ length: string }` fails on `number` vs `string`.
     */
    @Test
    fun `control - a shared member at the wrong type still reports`() {
        val d = diagnose("""
            enum ZzzRa { A = "a", B = "b" }
            declare function zzzRag(o: { length: string }): void;
            zzzRag(ZzzRa.A);
        """)
        assert(d.map { it.code } == listOf(2345))
    }

    /**
     * CONTROL — the WHOLE enum type as a source is **out of scope** and unchanged: it is
     * a member-less [Type.Object] with no `EnumLiteral` flag, and this repo accepts it
     * against every object target vacuously ((REL.1)(b)). tsc 7.0.2 reports TS2559 here
     * (`m1.ts(25,6)`), so this pin records a standing FALSE NEGATIVE, not coverage.
     */
    @Test
    fun `residue - a whole enum source stays silent against a target sharing nothing`() {
        val d = diagnose("""
            enum ZzzRb { A = "a", B = "b" }
            declare function zzzRbg(o: { zzzNope?: number }): void;
            declare const zzzRbv: ZzzRb;
            zzzRbg(zzzRbv);
        """)
        assert(d.isEmpty())
    }
}

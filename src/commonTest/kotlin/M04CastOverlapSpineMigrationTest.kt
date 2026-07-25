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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (M0.4, round 630): pins for the checkSameTargetReferenceCastOverlap
 * (TS2352 same-target-Reference cast + function-return-mismatch cast) spine
 * migration — the two emission leaves' gates (incomparable-both-ways arg
 * pairs; the Any/Unknown/Never/Void and TypeParam arg bails; the
 * array-literal excess-property chain branch; the function-return primitive
 * mismatch incl. the parenthesized form), and the shared
 * walkTypeAssertionsInStmt/-InExpr reach (nested fn / class method / OBJECT-
 * LITERAL method / class-EXPRESSION method bodies, arrow expression bodies,
 * typeof operands, template spans, case-clause expressions, for-initializer
 * declarations, call arguments, class property initializers ARE walked;
 * parameter DEFAULTS, enum member initializers, heritage expressions, and
 * computed property NAMES are NOT). All expectations verified green against
 * the pre-migration legacy pass first.
 */
class M04CastOverlapSpineMigrationTest {

    private val prelude = """
        interface Box<T> { value: T; }
        declare const nb: Box<number>;
    """.trimIndent()

    // ── emitTS2352IfSameTargetMismatch gates ───────────────────────────────

    @Test
    fun `same-target cast with incomparable type args fires exactly one TS2352`() {
        val ds = diagnose(
            prelude + "\nconst c = <Box<string>>nb;"
        )
        assert(ds.count { it.code == 2352 } == 1)
        ds should {
            have(any { it.code == 2352 && it.message.startsWith("Conversion of type") &&
                it.messageChain.any { m -> "is not comparable to type" in m } })
        }
    }

    @Test
    fun `negative control - identical type args draw no TS2352`() {
        diagnose(
            prelude + "\nconst c = <Box<number>>nb;"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - one-way assignable type args draw no TS2352`() {
        diagnose(
            """
            interface Box<T> { value: T; }
            class Animal { a = 1; }
            class Dog extends Animal { d = 1; }
            declare const bd: Box<Dog>;
            const c = <Box<Animal>>bd;
            """
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - an any type arg bails`() {
        diagnose(
            """
            interface Box<T> { value: T; }
            declare const ba: Box<any>;
            const c = <Box<string>>ba;
            """
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - a type-param arg bails`() {
        diagnose(
            prelude + "\nfunction gen<T>(b: Box<T>) { return <Box<string>>b; }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - different generic targets draw no TS2352`() {
        diagnose(
            """
            interface Box<T> { value: T; }
            interface Crate<T> { item: T; }
            declare const cn: Crate<number>;
            const c = <Box<string>>cn;
            """
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `array-literal cast with an excess property reports the excess-prop chain`() {
        val ds = diagnose(
            """const a = <{ id: number; }[]>[{ foo: "s" }];"""
        )
        assert(ds.count { it.code == 2352 } == 1)
        ds should {
            have(any { it.code == 2352 && it.length == 3 &&
                it.messageChain.any { m ->
                    "Object literal may only specify known properties" in m && "'foo'" in m
                } })
        }
    }

    // ── emitTS2352IfFunctionReturnMismatch gates ───────────────────────────

    @Test
    fun `function cast with a mismatched primitive return fires TS2352`() {
        val ds = diagnose(
            """const f = <{ (): number; }>function() { return "err"; };"""
        )
        assert(ds.count { it.code == 2352 } == 1)
        ds should {
            have(any { it.code == 2352 &&
                it.messageChain.any { m -> "'string' is not comparable to type 'number'" in m } })
        }
    }

    @Test
    fun `parenthesized function cast fires TS2352 too`() {
        diagnose(
            """const f = <{ (): number; }>(function() { return "err"; });"""
        ) should {
            have(any { it.code == 2352 &&
                it.messageChain.any { m -> "'string' is not comparable to type 'number'" in m } })
        }
    }

    @Test
    fun `negative control - a matching function return primitive draws no TS2352`() {
        diagnose(
            """const f = <{ (): number; }>function() { return 1; };"""
        ) should {
            have(none { it.code == 2352 })
        }
    }

    // ── reach pins: positions the legacy walker DOES visit ─────────────────

    @Test
    fun `cast inside a nested function body fires`() {
        diagnose(
            prelude + """

            function outer() {
                function inner() { const c = <Box<string>>nb; }
            }
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast inside a class method body fires`() {
        diagnose(
            prelude + """

            class M {
                m() { const c = <Box<string>>nb; }
            }
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast inside an object-literal method body fires`() {
        diagnose(
            prelude + """

            const o = { m() { return <Box<string>>nb; } };
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast inside a class-expression method body fires`() {
        diagnose(
            prelude + """

            const K = class { m() { return <Box<string>>nb; } };
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast in an arrow expression body fires`() {
        diagnose(
            prelude + "\nconst af = () => <Box<string>>nb;"
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast in a typeof operand fires`() {
        diagnose(
            prelude + "\nconst t = typeof <Box<string>>nb;"
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast in a template span fires`() {
        diagnose(
            prelude + "\nconst s = `x\${<Box<string>>nb}y`;"
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast in a case-clause expression fires`() {
        diagnose(
            prelude + """

            switch (nb) {
                case <Box<string>>nb: break;
            }
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast in a for-initializer declaration fires`() {
        diagnose(
            prelude + "\nfor (let i = <Box<string>>nb, k = 0; k < 1; k++) {}"
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast in a call argument fires`() {
        diagnose(
            prelude + """

            function takes(a: any) {}
            takes(<Box<string>>nb);
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast in a class property initializer fires`() {
        diagnose(
            prelude + """

            class P { x = <Box<string>>nb; }
            """
        ) should {
            have(any { it.code == 2352 })
        }
    }

    @Test
    fun `cast in a try block fires`() {
        diagnose(
            prelude + "\ntry { const c = <Box<string>>nb; } finally {}"
        ) should {
            have(any { it.code == 2352 })
        }
    }

    // ── reach pins: positions the legacy walker does NOT visit ─────────────

    @Test
    fun `negative control - cast in a parameter default is not walked`() {
        diagnose(
            prelude + "\nfunction g(p = <Box<string>>nb) {}"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - cast in an enum member initializer is not walked`() {
        diagnose(
            prelude + "\nenum E { A = <Box<string>>nb }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - cast in a heritage expression is not walked`() {
        diagnose(
            prelude + "\nclass C extends (<Box<string>>nb) {}"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - cast in a computed property name is not walked`() {
        diagnose(
            prelude + "\nconst o = { [(<Box<string>>nb).value]: 1 };"
        ) should {
            have(none { it.code == 2352 })
        }
    }
}

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
import kotlin.test.assertEquals

/**
 * (M0.4, round 648): pins for the checkTypeParamStrictSubtypeCast
 * (B60.3/B402/B60.18 — TS2352 for `<TypeParam>expr` casts + the
 * empty-object-to-nullish-constrained-TP `as` casts) spine migration.
 * Pins the emitters' gates (unconstrained TP-to-TP with the TS2208
 * related info only under an AST-carrying scope; constrained TP-to-TP;
 * concrete strict-subtype-of-constraint; the all-nullish-constraint
 * empty-object `as` arm) and the FROZEN legacy reach: fn-decl bodies /
 * class method+ctor bodies / namespace blocks walk with TP scopes
 * pushed (method PARAMS typed, ctor params NOT), while accessor bodies
 * and property initializers of such classes are SKIPPED; a fn-decl or
 * class nested inside a non-decl statement is walked by the SHARED
 * assertion walker instead — no TP push of its own, but WIDER member
 * coverage (accessor bodies + property initializers) under any OUTER
 * TP scope; the empty-objlit local prepass is whole-list at
 * TPC-walked statement lists only (never inside shared-region blocks).
 * All expectations verified green against the pre-migration legacy
 * pass first.
 */
class M04TypeParamCastSpineMigrationTest {

    private val prelude = """
        class Animal { a = 1; }
        class Dog extends Animal { d = 1; }
        declare const dog: Dog;
        declare const flag: boolean;
    """.trimIndent()

    // ── B60.18: unconstrained TP → unconstrained TP ────────────────────────

    @Test
    fun `method-body TP-to-TP cast fires TS2352 with the TS2208 related info`() {
        val ds = diagnose(
            "class C<T, U> { m(u: U): void { const t = <T>u; } }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
        ds should {
            have(any { it.code == 2352 &&
                it.message.startsWith("Conversion of type 'U' to type 'T'") &&
                it.messageChain.any { m -> "'T' could be instantiated with an arbitrary type which could be unrelated to 'U'" in m } &&
                it.relatedInformation.any { r -> r.code == 2208 &&
                    "might need an `extends T` constraint" in r.message } })
        }
    }

    @Test
    fun `negative control - casting a TP to itself draws no TS2352`() {
        diagnose(
            "class C<T> { m(t: T): void { const x = <T>t; } }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - constrained source to unconstrained target draws no TS2352`() {
        diagnose(
            prelude + "\nclass C<T, U extends Animal> { m(u: U): void { const t = <T>u; } }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - concrete source to unconstrained target draws no TS2352`() {
        diagnose(
            prelude + "\nfunction f<T>(): void { const t = <T>dog; }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    // ── B60.18b: constrained TP → constrained TP ───────────────────────────

    @Test
    fun `constrained TP-to-TP cast fires the different-subtype-of-constraint chain`() {
        val ds = diagnose(
            prelude + "\nclass C<T extends Animal, U extends Dog> { m(u: U): void { const t = <T>u; } }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
        ds should {
            have(any { it.code == 2352 &&
                it.message.startsWith("Conversion of type 'U' to type 'T'") &&
                it.messageChain.any { m ->
                    "'U' is assignable to the constraint of type 'T', but 'T' could be instantiated with a different subtype of constraint 'Animal'" in m } })
        }
    }

    // ── B60.3: concrete strict subtype of the constraint ───────────────────

    @Test
    fun `method param cast to a wider-constrained TP fires TS2352 - params are typed`() {
        val ds = diagnose(
            prelude + "\nclass C<T extends Animal> { m(d: Dog): void { const t = <T>d; } }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
        ds should {
            have(any { it.code == 2352 &&
                it.message.startsWith("Conversion of type 'Dog' to type 'T'") &&
                it.messageChain.any { m ->
                    "'Dog' is assignable to the constraint of type 'T', but 'T' could be instantiated with a different subtype of constraint 'Animal'" in m } })
        }
    }

    @Test
    fun `method OWN type params are in scope for its body casts`() {
        val ds = diagnose(
            prelude + "\nclass C { m<T extends Animal>(d: Dog): void { const t = <T>d; } }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `fn-decl body cast of a file-level const fires TS2352`() {
        val ds = diagnose(
            prelude + "\nfunction f<T extends Animal>(): void { const t = <T>dog; }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
        ds should {
            have(any { it.code == 2352 &&
                it.message.startsWith("Conversion of type 'Dog' to type 'T'") })
        }
    }

    @Test
    fun `negative control - bidirectional overlap with the constraint draws no TS2352`() {
        diagnose(
            prelude + "\nfunction f<T extends Dog>(): void { const t = <T>dog; }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `frozen quirk - fn-decl params are NOT typed so a param-source cast is silent`() {
        diagnose(
            prelude + "\nfunction f<T extends Animal>(d: Dog): void { const t = <T>d; }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    // ── B402: empty-object cast to an all-nullish-constrained TP ───────────

    @Test
    fun `direct empty object literal as nullish-constrained TP fires TS2352`() {
        val ds = diagnose(
            "function f<T extends null | undefined>(): void { const y = {} as T; }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
        ds should {
            have(any { it.code == 2352 &&
                it.message.startsWith("Conversion of type '{}' to type 'T'") &&
                it.messageChain.any { m ->
                    "'T' could be instantiated with an arbitrary type which could be unrelated to '{}'" in m } })
        }
    }

    @Test
    fun `empty-object local cast to nullish-constrained TP fires TS2352`() {
        val ds = diagnose(
            "function f<T extends undefined>(): void { const e = {}; e as T; }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `whole-list prepass - a cast BEFORE the empty-object var decl still fires`() {
        val ds = diagnose(
            "function f<T extends undefined>(): void { e as T; var e = {}; }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `an OUTER list empty-object local is visible in a nested fn body`() {
        val ds = diagnose(
            """
            const e = {};
            function f<T extends null>(): void { e as T; }
            """
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `frozen quirk - an empty-object local declared in a shared-region block never registers`() {
        diagnose(
            prelude + "\nfunction f<T extends undefined>(): void { if (flag) { const e = {}; e as T; } }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - a non-empty object literal local draws no TS2352`() {
        diagnose(
            "function f<T extends undefined>(): void { const e = { a: 1 }; e as T; }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - a non-nullish constraint draws no empty-object TS2352`() {
        diagnose(
            "function f<T extends string>(): void { const e = {}; e as T; }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `negative control - an unconstrained TP draws no empty-object TS2352`() {
        diagnose(
            "function f<T>(): void { const y = {} as T; }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    // ── Reach: TPC-list vs shared-region structural quirks ─────────────────

    @Test
    fun `a nested fn-decl at a body-list position pushes its own TPs`() {
        val ds = diagnose(
            prelude + "\nfunction outer(): void { function g<T extends Animal>(): void { const t = <T>dog; } }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `frozen quirk - a fn-decl inside an if block is shared-walked with NO TP push`() {
        diagnose(
            prelude + "\nfunction outer(): void { if (flag) { function g<T extends Animal>(): void { const t = <T>dog; } } }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `frozen quirk - a class property initializer at a list position is NOT walked`() {
        diagnose(
            prelude + "\nfunction f<T extends Animal>(): void { class K { p = <T>dog; } }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `a class property initializer in a shared region IS walked under the outer TP scope`() {
        val ds = diagnose(
            prelude + "\nfunction f<T extends Animal>(): void { if (flag) { class K { p = <T>dog; } } }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `frozen quirk - an accessor body of a list-position class is NOT walked`() {
        diagnose(
            prelude + "\nclass C<T extends Animal> { get g(): Animal { return <T>dog; } }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `an accessor body in a shared region IS walked under the outer TP scope`() {
        val ds = diagnose(
            prelude + "\nfunction f<T extends Animal>(): void { if (flag) { class K { get g(): Animal { return <T>dog; } } } }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `a constructor body sees the class TPs`() {
        val ds = diagnose(
            prelude + "\nclass C<T extends Animal> { constructor() { const t = <T>dog; } }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `frozen quirk - constructor params are NOT typed so a param-source cast is silent`() {
        diagnose(
            prelude + "\nclass C<T extends Animal> { constructor(d: Dog) { const t = <T>d; } }"
        ) should {
            have(none { it.code == 2352 })
        }
    }

    @Test
    fun `a namespace-nested class method fires through the ModuleBlock recursion`() {
        val ds = diagnose(
            prelude + "\nnamespace N { export class C<T extends Animal> { m(d: Dog): void { const t = <T>d; } } }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `an arrow expression body is reached by the shared walk`() {
        val ds = diagnose(
            prelude + "\nfunction f<T extends Animal>(): void { const g = (): Animal => <T>dog; }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `an object-literal method body is reached by the shared walk`() {
        val ds = diagnose(
            prelude + "\nfunction f<T extends Animal>(): void { const o = { m(): Animal { return <T>dog; } }; }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }

    @Test
    fun `a switch case block is reached by the shared walk`() {
        val ds = diagnose(
            prelude + "\nfunction f<T extends Animal>(): void { switch (1) { case 1: { const t = <T>dog; } } }"
        )
        assertEquals(1, ds.count { it.code == 2352 })
    }
}

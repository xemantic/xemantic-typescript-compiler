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
 * (LEGACY.0b) step 9, the ORDER family — the **REACH** half.
 *
 * Round (P18.85) landed `StableTypeOrdering` (tsc's `compareTypes`, checker.ts:53856) and
 * wired it into `Checker.getUnionType`, so a union built by the ENGINE is interned in
 * TypeScript 7's stable member order. What it did not reach are the display sites that
 * build a union STRING themselves — a `joinToString(" | ")` over annotation nodes, over a
 * per-constituent collection, or over hand-collected member names — of which
 * `Checker.kt` has 45. Those rendered the WRITTEN order, which is why eighteen ordering
 * baselines survived the comparator.
 *
 * Every expectation below was measured against **tsgo 7.0.2**
 * (`tools/tsgo-7.0.2/lib/tsc`), the sole compatibility target since the owner's
 * 2026-09-12 directive; pristine `typescript@6.0.3` is NOT an adjudicator here and
 * differs on every one of these rows by construction (it predates
 * `stableTypeOrdering` being on by default).
 *
 * ## Why these are VALUE pins and not silence pins
 *
 * A reordering changes no diagnostic's code, span or count — only the rendered string —
 * so `none { it.code == N }` cannot see it, and neither can the 8-profile grid
 * ((PARITY.1): all 46 rows of seven profiles are `Cannot find name …`, which names no
 * type at all). Each test therefore asserts the WHOLE message.
 *
 * ## The three keys these rows exercise, in tsc's order
 *
 *  1. ascending TypeScript-7 `TypeFlags` — `String` is bit 5 and `StringLiteral` bit 10,
 *     so a keyword precedes its literals; `NonPrimitive` (`object`) is bit **17** and so
 *     comes AFTER a string literal; `Index` (`keyof T`) is bit 21, later still.
 *  2. a NAMED type by NAME (`compareTypeNames`) — a class, an interface, an alias.
 *  3. per-kind data — a string literal by VALUE.
 *
 * Three of the thirteen rows this round closed are corpus-unique HARDCODED pin walkers
 * (`checkComplicatedChannelReturn`, `checkBaseClassImprovedMismatch`,
 * `checkKeyofDistributiveRemapAssign`) whose displays were transcribed verbatim from the
 * tsc 6 baselines. Their pin is their own corpus subtest, now active — a hand-written
 * fixture cannot reach them, because each is gated on a corpus-unique source token.
 */
class StableUnionDisplayReachTest {

    // -------------------------------------------------------------------------
    // The object-REST `Omit<this, …>` display (destructuringUnspreadableIntoRest).
    // The key union is synthesized from the class's member names — it never becomes
    // a `Type.Union` — so it is ordered here. tsgo: `"apple" | "mango" | "method"`.
    // -------------------------------------------------------------------------

    @Test
    fun `an object-rest Omit key union renders its string literals by value`() {
        val messages = diagnose(
            """
            class ZzzA {
                constructor(public zebra: string) {}
                get apple(): number { return 1 }
                set mango(_v: number) {}
                method() {
                    const { zebra: _1, ...rest3 } = this;
                    rest3.apple;
                }
            }
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Property 'apple' does not exist on type 'Omit<this, \"apple\" | \"mango\" | \"method\" | \"zebra\">'.",
            ),
        )
    }

    @Test
    fun `negative control - the Omit key union is not merely reversed`() {
        // A single excluded name cannot distinguish an ordering rule from any other, so
        // the pin above needs four; this one only fixes the SHAPE of the display so that
        // a future change to the walker's gate is visible as well as its order.
        val messages = diagnose(
            """
            class ZzzB {
                constructor(public keep: string) {}
                get only(): number { return 1 }
                method2() {
                    const { ...rest1 } = this;
                    rest1.only;
                }
            }
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Property 'only' does not exist on type 'Omit<this, \"method2\" | \"only\">'.",
            ),
        )
    }

    // -------------------------------------------------------------------------
    // The discriminant walkers (indirectDiscriminantAndExcessProperty TS2322 and
    // errorsForCallAndAssignmentAreSimilar's ARGUMENT-position TS2820). The union is
    // collected per union constituent in the annotation's written order.
    // -------------------------------------------------------------------------

    @Test
    fun `a discriminant literal union renders its members by value`() {
        val messages = diagnose(
            """
            type ZBlah = { kind: "zulu", a: string } | { kind: "alpha", b: number };
            declare function zthing(b: ZBlah): void;
            let zk = "zulu";
            zthing({ kind: zk, a: "x" });
            """,
        ).map { it.message }
        assert(messages == listOf("Type 'string' is not assignable to type '\"alpha\" | \"zulu\"'."))
    }

    // -------------------------------------------------------------------------
    // The discriminated-union excess-property TS2353 (excessPropertyCheck-
    // WithMultipleDiscriminants). tsc orders two named interfaces by NAME.
    // -------------------------------------------------------------------------

    @Test
    fun `a discriminated-union excess-property target renders its constituents by name`() {
        val messages = diagnose(
            """
            interface ZBeta { d: number; shared: string }
            interface ZAlpha { d: number; other: string }
            declare function ztake(v: ZBeta | ZAlpha): void;
            ztake({ d: 1, nope: 2 });
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Object literal may only specify known properties, and 'nope' does not exist in type 'ZAlpha | ZBeta'.",
            ),
        )
    }

    // -------------------------------------------------------------------------
    // B169's `(Foo | Bar)['k']` receiver (unionPropertyOfProtectedAndIntersection-
    // Property). The synthesized union property never becomes a `Type.Union`.
    // -------------------------------------------------------------------------

    @Test
    fun `an inaccessible union indexed-access receiver renders its classes by name`() {
        val messages = diagnose(
            """
            class ZFoo { protected zp: number = 1 }
            class ZBar { protected zp: string = "" }
            type Z1 = (ZFoo | ZBar)['zp'];
            """,
        ).map { it.message }
        assert(messages == listOf("Property 'zp' does not exist on type 'ZBar | ZFoo'."))
    }

    // -------------------------------------------------------------------------
    // The switch-comparability allowed set (parenthesizedJSDocCastDoesNotNarrow).
    // A keyword sorts BEFORE its literals: `number` is bit 6, `StringLiteral` bit 10.
    // -------------------------------------------------------------------------

    @Test
    fun `a switch comparability target renders a keyword before its literals`() {
        val messages = diagnose(
            """
            declare const zv: "zulu" | "alpha" | number;
            switch (zv) {
                case "nope":
                    break;
            }
            """,
        ).map { it.message }
        assert(
            messages == listOf(
                "Type '\"nope\"' is not comparable to type 'number | \"alpha\" | \"zulu\"'.",
            ),
        )
    }

    // -------------------------------------------------------------------------
    // The `in`-operator RHS walker (inDoesNotOperateOnPrimitiveTypes). Its member
    // ranking used keyword/literal/type-parameter BUCKETS, which is not tsc's order:
    // `object` is `NonPrimitive` (bit 17) and sorts AFTER a string literal (bit 10).
    // -------------------------------------------------------------------------

    @Test
    fun `an in-operator RHS union ranks object after a string literal`() {
        // The constraint is WRITTEN `object | "hello"` (the corpus fixture's own order),
        // so the rendered `"hello" | object` can only come from the ordering rule.
        val d = diagnose(
            """
            function zmix<T extends object | "hello">(thing: T) {
              "key" in thing;
            }
            """,
        )
        val messages = d.map { it.message }
        val chains = d.map { it.messageChain }
        assert(messages == listOf("Type 'T' is not assignable to type 'object'."))
        assert(
            chains == listOf(
                listOf(
                    "  Type '\"hello\" | object' is not assignable to type 'object'.",
                    "    Type 'string' is not assignable to type 'object'.",
                ),
            ),
        )
    }
}

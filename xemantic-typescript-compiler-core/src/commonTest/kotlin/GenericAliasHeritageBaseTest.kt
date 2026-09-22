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
 */


package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (P18.169): `interface D extends Omit<B, 'b'>` — a heritage base that is a generic type
 * ALIAS — must contribute its members to `D`.
 *
 * `getTypeFromBaseTypeExpression` honoured type arguments only when the base symbol's
 * declared type was a `Type.Interface`; for a generic ALIAS it fell through to the bare
 * `getDeclaredTypeOfSymbol` with the arguments SILENTLY DISCARDED, so the un-instantiated
 * alias body — a mapped type over an unbound `T` — contributed nothing at all.
 *
 * Measured against tsgo 7.0.2, the defect had three faces and the pins below cover all
 * three, because a silence-only pin here cannot tell "typed right" from "typed `any`":
 *
 *  1. a FALSE TS2353 on legal code (the inherited member looked excess) — the rows that
 *     brought `marked` from 4 ours-only diagnostics to 3;
 *  2. a member READ degrading to `any` where tsgo types it — caught by a deliberate
 *     MIS-ASSIGNMENT, whose message prints whatever the member really resolved to;
 *  3. a genuinely EXCESS property going unreported, because the first excess key wins
 *     (B560: `checkExcessProperties` reports the first and returns), so an inherited
 *     member wrongly seen as excess MASKED the real one behind it.
 *
 * Seven heritage forms were affected — `Omit` / `Pick` / `Partial` / `Required` /
 * `Readonly` / `Record` and any user-declared generic alias — and three were not: a plain
 * `extends B`, a multi-base `extends B, C` and a generic INTERFACE base `extends G<X>`.
 * The last three are pinned as the NEGATIVE CONTROLS for the split the fix keys on, so a
 * regression that "fixes" them a second way is visible.
 */
class GenericAliasHeritageBaseTest {

    /**
     * The aliases are declared LOCALLY on purpose. `Omit` / `Pick` / `Partial` /
     * `Record` are not declared by the EMBEDDED lib this harness compiles against
     * (M2.2 / round 393 — under real libs they are lib `TypeAlias` symbols, under the
     * embedded one the name resolves to nothing), so a pin spelled with them resolves
     * its base to `errorType` BEFORE reaching the arm under test and is vacuous in
     * both directions. Measured: all five such pins failed on a WORKING binary with
     * `keys == [a]`, i.e. the pre-fix answer.
     *
     * The axis the fix keys on is "a heritage base whose symbol is a generic type
     * ALIAS carrying type arguments", which these reproduce exactly; the lib-utility
     * spellings are covered by the round's CLI matrix, which is byte-identical to
     * tsgo 7.0.2 over `Omit` / `Pick` / `Partial` / `Required` / `Readonly` / `Record`.
     */
    private val prelude = """
        interface B { a?: boolean; b?: string }
        interface C { d?: number }
        interface G<X> { g?: X }
        interface Req { a: boolean; b: string }
        type M<T> = { [K in keyof T]: T[K] }
        type MyPartial<T> = { [K in keyof T]?: T[K] }
        type MyPick<T, K extends keyof T> = { [P in K]: T[P] }
        type MyRecord<K extends string, V> = { [P in K]: V }
        type Wrap<T> = { w: T }
        type Plain = { a?: boolean }
    """.trimIndent() + "\n"

    private fun ts2353Keys(d: List<Diagnostic>): List<String> =
        d.filter { it.code == 2353 }
            .map { it.message.substringAfter("and '").substringBefore("' does not exist") }

    private fun ts2322Source(d: List<Diagnostic>): String =
        d.single { it.code == 2322 }.message
            .substringAfter("Type '").substringBefore("' is not assignable")

    // ---- face 1: the false TS2353 is gone, for every affected heritage form ----

    @Test
    fun `an inherited member of a locally-declared key-picking mapped heritage base is not excess`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends MyPick<B, 'a'> { c?: number }
            const v: D = { a: true };
        """.trimIndent()))
        assert(keys.isEmpty())
    }

    @Test
    fun `an inherited member of a locally-declared optional-mapping heritage base is not excess`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends MyPartial<B> { c?: number }
            const v: D = { a: true };
        """.trimIndent()))
        assert(keys.isEmpty())
    }

    @Test
    fun `an inherited member of a plain generic alias heritage base is not excess`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends Wrap<B> { c?: number }
            const v: D = { w: { a: true } };
        """.trimIndent()))
        assert(keys.isEmpty())
    }

    @Test
    fun `an inherited member of a locally-declared record heritage base is not excess`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends MyRecord<'a' | 'b', number> { c?: number }
            const v: D = { a: 1, b: 2 };
        """.trimIndent()))
        assert(keys.isEmpty())
    }

    @Test
    fun `an inherited member of a user-declared mapped-type heritage base is not excess`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends M<B> { c?: number }
            const v: D = { a: true };
        """.trimIndent()))
        assert(keys.isEmpty())
    }

    // ---- face 2: the inherited member is really TYPED, not `any` ----
    // A mis-assignment prints the member's resolved type, so a pin that passed because
    // the read answered `any` would be RED here rather than silently green.

    @Test
    fun `a member inherited through a generic alias heritage base carries the base member type`() {
        val d = diagnose(prelude + """
            interface D extends MyPartial<B> { c?: number }
            declare const v: D;
            const probe: string = v.a!;
        """.trimIndent())
        assert(ts2322Source(d) == "boolean")
    }

    @Test
    fun `a member inherited through a user-declared mapped-type heritage base carries the base member type`() {
        val d = diagnose(prelude + """
            interface D extends M<B> { c?: number }
            declare const v: D;
            const probe: string = v.a!;
        """.trimIndent())
        assert(ts2322Source(d) == "boolean")
    }

    @Test
    fun `an own member of an interface with an alias heritage base still carries its own type`() {
        val d = diagnose(prelude + """
            interface D extends MyPick<B, 'a'> { c?: number }
            declare const v: D;
            const probe: string = v.c!;
        """.trimIndent())
        assert(ts2322Source(d) == "number")
    }

    // ---- face 3: a genuinely excess property MUST still report, through every form ----
    // Without these the fix is indistinguishable from one that deleted the check.

    @Test
    fun `a genuinely unknown property is still excess through a key-picking mapped heritage base`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends MyPick<B, 'a'> { c?: number }
            const v: D = { zz: 1 };
        """.trimIndent()))
        assert(keys == listOf("zz"))
    }

    @Test
    fun `a genuinely unknown property is still excess through an optional-mapping heritage base`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends MyPartial<B> { c?: number }
            const v: D = { zz: 1 };
        """.trimIndent()))
        assert(keys == listOf("zz"))
    }

    @Test
    fun `a genuinely unknown property is still excess through a mapped-type heritage base`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends M<B> { c?: number }
            const v: D = { zz: 1 };
        """.trimIndent()))
        assert(keys == listOf("zz"))
    }

    @Test
    fun `a property the mapped heritage base does not select is excess again`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends MyPick<B, 'a'> { c?: number }
            const v: D = { b: "x" };
        """.trimIndent()))
        assert(keys == listOf("b"))
    }

    @Test
    fun `the reported excess key is the unknown one and not the first inherited one`() {
        // B560 reports the FIRST excess key and returns, so before the fix `a` was
        // reported here and the real offender `zz` was never reached. tsgo reports `zz`.
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends M<Req> { c?: number }
            const v: D = { a: true, b: "x", zz: 1 };
        """.trimIndent()))
        assert(keys == listOf("zz"))
    }

    // ---- negative controls: the three heritage forms the fix does NOT key on ----

    @Test
    fun `a plain interface heritage base keeps reporting a genuinely excess property`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends B { c?: number }
            const v: D = { zz: 1 };
        """.trimIndent()))
        assert(keys == listOf("zz"))
    }

    @Test
    fun `a plain interface heritage base still inherits its member type`() {
        val d = diagnose(prelude + """
            interface D extends B { c?: number }
            declare const v: D;
            const probe: string = v.a!;
        """.trimIndent())
        assert(ts2322Source(d) == "boolean")
    }

    @Test
    fun `a generic interface heritage base still applies its type argument`() {
        val d = diagnose(prelude + """
            interface D extends G<number> { c?: number }
            declare const v: D;
            const probe: string = v.g!;
        """.trimIndent())
        assert(ts2322Source(d) == "number")
    }

    @Test
    fun `a non-generic alias heritage base still contributes its members`() {
        val d = diagnose(prelude + """
            interface D extends Plain { c?: number }
            declare const v: D;
            const probe: string = v.a!;
        """.trimIndent())
        assert(ts2322Source(d) == "boolean")
    }

    /**
     * THE `SymbolFlags.TypeAlias` CONJUNCT IS LOAD-BEARING AND ONLY THE CORPUS SAW IT.
     *
     * Ablated (arm a2: route EVERY `Identifier` base carrying type arguments through the
     * annotation path, not just an alias one) the whole class above reads **0 RED** while
     * `scripts/corpus-screen.sh` moves `nestedRecursiveArraysOrObjectsError01` — which
     * then produces NO diagnostics at all where the baseline expects a TS2353.
     *
     * The mechanism: a generic INTERFACE base must keep resolving through
     * `getOrInternReference(declared, args)`. [getTypeFromTypeReference] has its own
     * `"Array"` / `"ReadonlyArray"` fast paths that build an array type by a different
     * route, so an `interface StyleArray extends Array<Style>` routed there loses the
     * identity the recursive union `Style = StyleBase | StyleArray` is resolved against.
     * This pin reproduces that baseline's shape so the conjunct has a failure uniquely
     * its own outside the corpus.
     */
    @Test
    fun `a recursive generic interface heritage base still reports an excess property`() {
        val keys = ts2353Keys(diagnose("""
            type Style = StyleBase | StyleArray;
            interface StyleArray extends Array<Style> {}
            interface StyleBase { foo: string }
            const blah: Style = [
                [[{
                    foo: 'asdf',
                    jj: 1
                }]]
            ];
        """.trimIndent()))
        assert(keys == listOf("jj"))
    }

    /**
     * De-blinds the `Wrap<B>` cell above: a NON-mapped generic alias body already carries
     * the right member NAME without substitution (`{ w: T }` has `w` either way), so the
     * excess-property pin cannot discriminate there — measured, it stayed GREEN under
     * arm a1. What the substitution decides is the member's TYPE, which this reads.
     */
    @Test
    fun `a plain generic alias heritage base substitutes its type argument into the member`() {
        val d = diagnose(prelude + """
            interface D extends Wrap<B> { c?: number }
            declare const v: D;
            const probe: string = v.w;
        """.trimIndent())
        assert(ts2322Source(d) == "B")
    }

    @Test
    fun `two plain interface heritage bases both contribute their members`() {
        val keys = ts2353Keys(diagnose(prelude + """
            interface D extends B, C { c?: number }
            const v: D = { a: true, d: 1 };
        """.trimIndent()))
        assert(keys.isEmpty())
    }
}

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
 * (CHK.124) step 1 — THE EXPANDO MEMBERS OF A `FunctionDeclaration` HOST, ON THE TYPE.
 *
 * `Checker.attachExpandoMembers` gives `typeof g` the members B431's collector has
 * always known about, so `typeof g` IS `{ (): void; px: number; }` and lookup,
 * assignability and display all fall out of that one fact — which is how tsgo works
 * (its BINDER declares the property onto the host symbol's `exports`, so its checker
 * needs no expando rule at all; (P18.134)).
 *
 * **EVERY EXPECTATION HERE WAS MEASURED AGAINST `tools/tsgo-7.0.2/lib/tsc`**, the only
 * reference this project has, on the same fixture text. The probe shape is a
 * deliberate mis-assignment to `boolean`: a TS2322 PRINTS the type the checker built,
 * which is the one instrument that can tell a right member set from a wrong one (a
 * silence-asserting pin cannot).
 *
 * ### What this does NOT close, measured rather than assumed
 *
 * `const c: typeof g = h` — where `h` lacks `px` — was SILENT before and now REPORTS,
 * i.e. the members DO participate in assignability. It is still not byte-identical to
 * tsgo, on two mechanisms that are **pre-existing and have nothing to do with
 * expandos**, each verified on an expando-free fixture:
 *
 *  * tsgo emits **TS2741** *Property 'px' is missing …* where we emit the generic
 *    TS2322 whenever the TARGET carries a CALL SIGNATURE — measured with a plain
 *    `declare const src: () => void; const a: { (): void; px: number } = src`, which
 *    has no expando in it; our TS2741 emitter is fine for a signature-less target;
 *  * a `typeof X` ANNOTATION is displayed AS WRITTEN here and structurally by tsgo —
 *    measured with `declare const obj: { a: number; b: string }; const x: typeof obj =
 *    partial`, again with no function and no expando in it.
 *
 * Both belong to their own rounds; neither is a regression of this one.
 */
class ExpandoFunctionMemberModelTest {

    private fun ts2322(d: List<Diagnostic>) = d.filter { it.code == 2322 }
    private fun ts2339(d: List<Diagnostic>) = d.filter { it.code == 2339 }

    /** The probe: a deliberate mis-assignment renders the host's whole type. */
    private fun rendered(source: String): String =
        ts2322(diagnose(source)).single().message

    // --- the three answers this round set out to fix ------------------------

    /**
     * Wrong answer 1, measured: `const s: string = g.px` was SILENT here — so `g.px`
     * was `any` — where tsgo reports TS2322. Byte-identical to tsgo now.
     */
    @Test
    fun `an expando member carries its own type at a read`() {
        val d = diagnose(
            """
            function zzzG(): void {}
            zzzG.px = 1;
            const zzzS: string = zzzG.px;
            """
        )
        assert(
            ts2322(d).single().message ==
                "Type 'number' is not assignable to type 'string'."
        )
    }

    /**
     * Wrong answer 2, measured: `const d: { (): void; px: number } = g` was an
     * ours-only FALSE POSITIVE — the host had no `px` to satisfy the target — where
     * tsgo is silent. It is silent here now.
     */
    @Test
    fun `an expando host satisfies a structural target that names its member`() {
        val d = diagnose(
            """
            function zzzG(): void {}
            zzzG.px = 1;
            const zzzD: { (): void; px: number } = zzzG;
            """
        )
        assert(ts2322(d).isEmpty())
    }

    /**
     * Wrong answer 3, measured: assigning a member-LESS function to a `typeof g` slot
     * was **SILENT** because `typeof g` had no members to miss. It REPORTS now — which
     * is the sharpest evidence that the members participate in ASSIGNABILITY and not
     * merely in display.
     *
     * tsgo's row for this fixture is
     * `TS2741: Property 'px' is missing in type '() => void' but required in type
     * '{ (): void; px: number; }'`, and ours is a generic TS2322 naming the target
     * `'typeof zzzG'`. **Both differences are pre-existing and expando-free** (see the
     * class KDoc), so this pin deliberately asserts only what this round CHANGED —
     * one row, at that span — and stays green when either of them is later closed.
     * Asserting the code or the message here would make it a countdown on two
     * mechanisms this round does not touch.
     */
    @Test
    fun `a member-less function is refused where a typeof slot requires an expando`() {
        val d = diagnose(
            """
            function zzzG(): void {}
            zzzG.px = 1;
            declare function zzzH(): void;
            const zzzC: typeof zzzG = zzzH;
            """
        )
        assert(d.size == 1)
        assert(d.single().line == 4)
        assert(d.single().character == 7)
    }

    // --- the rules, each measured against tsgo 7.0.2 ------------------------

    /** tsgo: `{ (): void; a: string | number; }` — the union's member ORDER is the
     *  stable one and NOT the write order (writing `"s"` first renders identically). */
    @Test
    fun `two writes to one member union their types`() {
        assert(
            rendered(
                """
                function zzzU(): void {}
                zzzU.a = 1;
                zzzU.a = "s";
                const zzzP: boolean = zzzU;
                """
            ) == "Type '{ (): void; a: string | number; }' is not assignable to type 'boolean'."
        )
    }

    /** tsgo widens a FRESH literal right-hand side to its base primitive. */
    @Test
    fun `a literal right-hand side is widened`() {
        assert(
            rendered(
                """
                function zzzW(): void {}
                zzzW.p = 1;
                const zzzP: boolean = zzzW;
                """
            ) == "Type '{ (): void; p: number; }' is not assignable to type 'boolean'."
        )
    }

    /**
     * …and the negative control that makes the widening rule a RULE rather than a
     * blanket base-primitive answer: a DECLARED literal union is not a widening
     * literal type, so tsgo keeps `1 | 2`.
     */
    @Test
    fun `negative control - a declared literal union right-hand side is not widened`() {
        assert(
            rendered(
                """
                function zzzK(): void {}
                declare let zzzDeclared: 1 | 2;
                zzzK.p = zzzDeclared;
                const zzzP: boolean = zzzK;
                """
            ) == "Type '{ (): void; p: 1 | 2; }' is not assignable to type 'boolean'."
        )
    }

    /**
     * THE PIN THAT FORCED THE `pos` SORT. tsgo renders the members in SOURCE order and
     * `collectExpandoDecls` does not walk in source order: its DO-WHILE arm visits the
     * CONDITION before the BODY, so the collector sees `cond` first. This is the one
     * inversion in the corpus row `expandoFunctionNestedAssigments` too — without the
     * sort that whole 24-member rendering is wrong at exactly this pair.
     */
    @Test
    fun `members render in source order across a do-while`() {
        assert(
            rendered(
                """
                function zzzO(): void {}
                do { zzzO.body = 1; } while ((zzzO.cond = 1) > 2);
                const zzzP: boolean = zzzO;
                """
            ) == "Type '{ (): void; body: number; cond: number; }' is not assignable to type 'boolean'."
        )
    }

    /** …and the same question for the two write SPELLINGS, which the collector visits
     *  through different arms. */
    @Test
    fun `a property write and an element-access write share one source order`() {
        assert(
            rendered(
                """
                function zzzE(): void {}
                zzzE["zz"] = 1;
                zzzE.aa = "s";
                const zzzP: boolean = zzzE;
                """
            ) == "Type '{ (): void; zz: number; aa: string; }' is not assignable to type 'boolean'."
        )
    }

    /**
     * `g.self = g` renders `self: typeof zzzSelf` in tsgo — the self-reference ESCAPES
     * to the symbol form rather than recursing. Here that falls out of B198's existing
     * recursion cut in `typeToString`, which is reachable for the first time because
     * the B198 SENTINEL has already stored this very instance in `symbolTypes` when the
     * right-hand side is typed: attaching lazily instead would not have this identity.
     */
    @Test
    fun `a self-referential member escapes to the symbol form`() {
        assert(
            rendered(
                """
                function zzzSelf(): void {}
                zzzSelf.self = zzzSelf;
                const zzzP: boolean = zzzSelf;
                """
            ) == "Type '{ (): void; self: typeof zzzSelf; }' is not assignable to type 'boolean'."
        )
    }

    /** An OVERLOAD SET attaches, and every visible signature is rendered — deliberately
     *  wider than B431's `nameCount == 1` candidate rule, and tsgo's answer. */
    @Test
    fun `an overload set carries its expando members`() {
        assert(
            rendered(
                """
                function zzzOv(zzzX: string): void;
                function zzzOv(zzzX: number): void;
                function zzzOv(zzzX: any): void {}
                zzzOv.tag = 1;
                const zzzP: boolean = zzzOv;
                """
            ) == "Type '{ (zzzX: string): void; (zzzX: number): void; tag: number; }' " +
                "is not assignable to type 'boolean'."
        )
    }

    /** A function declared inside a NAMESPACE body takes that body as its container —
     *  a file-statement scan would miss the write entirely ((P18.134)'s rule). */
    @Test
    fun `a namespace-scoped host is scanned in its own container`() {
        assert(
            rendered(
                """
                namespace ZzzOuter {
                    export function zzzInner(): void {}
                    zzzInner.tag = 1;
                    export const zzzP: boolean = zzzInner;
                }
                """
            ) == "Type '{ (): void; tag: number; }' is not assignable to type 'boolean'."
        )
    }

    // --- the refusals, each a measured boundary rather than conservatism ----

    /**
     * A write inside a NESTED function does not declare — B431's rule, reused rather
     * than re-derived — so the host keeps its bare signature and the write itself is
     * the error. Byte-identical to tsgo on both rows.
     */
    @Test
    fun `negative control - a write inside a nested function declares nothing`() {
        val d = diagnose(
            """
            function zzzN(): void {}
            function zzzHolder(): void { zzzN.viaNested = 1; }
            const zzzP: boolean = zzzN;
            """
        )
        assert(
            ts2322(d).single().message ==
                "Type '() => void' is not assignable to type 'boolean'."
        )
        assert(
            ts2339(d).single().message ==
                "Property 'viaNested' does not exist on type '() => void'."
        )
    }

    /** Only `=` declares: a COMPOUND assignment adds no member ((P18.123)'s rule). */
    @Test
    fun `negative control - a compound assignment declares no member`() {
        assert(
            rendered(
                """
                function zzzC(): void {}
                zzzC.plus = 0;
                zzzC.plus += 1;
                const zzzP: boolean = zzzC;
                """
            ) == "Type '{ (): void; plus: number; }' is not assignable to type 'boolean'."
        )
    }

    /**
     * A function MERGED with a namespace is refused whole — tsgo attaches there and
     * renders `typeof zzzNs`, which needs the namespace-merged VALUE display we do not
     * have (measured pre-existing: the same merge with NO expando write renders
     * `() => void` here and `typeof zzzNs2` in tsgo). Admitting the host without that
     * display would produce a third answer neither compiler gives.
     */
    @Test
    fun `negative control - a namespace-merged host is refused`() {
        assert(
            rendered(
                """
                function zzzNs(): void {}
                namespace zzzNs { export const zzzInNs = 1; }
                zzzNs.tag = 1;
                const zzzP: boolean = zzzNs;
                """
            ) == "Type '() => void' is not assignable to type 'boolean'."
        )
    }

    /**
     * A function with NO expando write keeps its bare signature — the control that
     * separates "has a write" from "has an expando", and the half of
     * `ExpandoReceiverDisplayTest` section 2 that survived (CHK.124).
     */
    @Test
    fun `negative control - a function with no expando write keeps its signature`() {
        assert(
            rendered(
                """
                function zzzPlain(): void {}
                const zzzP: boolean = zzzPlain;
                """
            ) == "Type '() => void' is not assignable to type 'boolean'."
        )
    }

    /**
     * ROUTE (B) STAYS SHUT. Giving `typeof g` members takes it out of
     * `cmamCheckResolvedObjectType`'s empty-`properties` branch and into the ordinary
     * member-missing one, so that path and B431's spine anchor BOTH emitted the
     * IDENTICAL row — measured, before the `expandoAttachedTypeIds` guard. A count
     * pin, because the two rows are indistinguishable by message.
     */
    @Test
    fun `an absent member on an expando host is reported exactly once`() {
        val d = diagnose(
            """
            function zzzG(): void {}
            zzzG.px = 1;
            function zzzRead(): void { zzzG.zzzProbe; }
            """
        )
        assert(ts2339(d).size == 1)
        assert(
            ts2339(d).single().message ==
                "Property 'zzzProbe' does not exist on type '{ (): void; px: number; }'."
        )
    }

    /**
     * A member's right-hand side may read an EARLIER member of the same host
     * (tsgo types `b: number`), which re-enters member resolution on a type whose
     * table is still being built — the round-833 shape. The table is planted BEFORE
     * the member types are computed for exactly this reason; without that, the
     * re-entrant read plants an EMPTY table and masks every member.
     */
    @Test
    fun `a member right-hand side may read an earlier member of the same host`() {
        assert(
            rendered(
                """
                function zzzF(): void {}
                zzzF.a = 1;
                zzzF.b = zzzF.a;
                const zzzP: boolean = zzzF;
                """
            ) == "Type '{ (): void; a: number; b: number; }' is not assignable to type 'boolean'."
        )
    }
}

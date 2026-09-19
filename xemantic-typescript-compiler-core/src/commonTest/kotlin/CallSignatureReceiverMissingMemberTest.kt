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
 * (P18.137) (CHK.124) step 2 — A MISSING MEMBER ON AN **IDENTIFIER** RECEIVER WHOSE
 * TYPE CARRIES CALL SIGNATURES, AND THE `const`-BOUND FUNCTION-EXPRESSION HOST THAT
 * HAD TO LAND WITH IT.
 *
 * `Checker.cmamCheckResolvedObjectType`'s empty-`properties` branch had exactly one
 * emission — B63.33's `'{}'` case — and it REQUIRES `callSignatures.isNullOrEmpty()`,
 * so every function-typed identifier receiver fell through to a bare `return`.
 * Measured against `tools/tsgo-7.0.2/lib/tsc` at `target es2015 / lib esnext /
 * strict`, four real errors were lost in eleven lines, and all four are pinned below.
 *
 * (P18.134) refused to open that branch with its reason stated: with no expando member
 * model, `const f = () => {}; f.bar = 1` would have become a FALSE POSITIVE. (P18.136)
 * built the model for `FunctionDeclaration` hosts and
 * `Checker.attachVariableExpandoMembers` supplies the `const` half, so the two halves
 * are one round and this class pins both.
 *
 * **EVERY EXPECTATION HERE WAS MEASURED AGAINST `tools/tsgo-7.0.2/lib/tsc`** on the
 * same fixture text — including the ones that assert a SILENCE, because a silence this
 * model produces for the wrong reason is exactly what (P18.134) was guarding against.
 *
 * ### The two DISPLAY gaps this route exposes are PRE-EXISTING, with receipts
 *
 * Route (B) renders its receiver with `typeToString`, so it inherits two gaps that
 * were already there and are reachable without it. Each was verified on the round's
 * own BEFORE binary through an unrelated TS2322:
 *
 *  * a call-signature-bearing type carrying an INDEX SIGNATURE renders as the bare
 *    signature — `const w: boolean = nidx` with `nidx: { (): void; [k: number]: any }`
 *    prints `Type '() => void' …` on the BEFORE binary, where tsgo prints
 *    `Type '{ (): void; [k: number]: any; }' …`. An index-signature-only type renders
 *    correctly in both, which is what localises the gap to the call-signature case;
 *  * a GENERIC type ALIAS instantiation renders structurally — `FA<string>` for
 *    `type FA<T> = (t: T) => void` prints `(t: string) => void`, where a NON-generic
 *    alias prints its name in both compilers.
 *
 * Neither is special-cased away here: refusing those receivers would trade a true row
 * for hiding a display bug that is visible through other emitters anyway. The rows
 * they produce are pinned as `residue -` so closing either gap is a visible change.
 */
class CallSignatureReceiverMissingMemberTest {

    private fun ts2339(d: List<Diagnostic>) = d.filter { it.code == 2339 }

    // --- the four rows this round set out to fix ----------------------------

    /**
     * `declare const zq: () => void; zq.nope1` — an ANONYMOUS function type, the
     * commonest shape of all. tsgo:
     * `Property 'nope1' does not exist on type '() => void'.`
     */
    @Test
    fun `a missing member on an annotated function-typed const reports`() {
        val d = diagnose(
            """
            declare const zzzQ: () => void;
            const zzzA = zzzQ.zzzNope;
            """
        )
        assert(
            ts2339(d).single().message ==
                "Property 'zzzNope' does not exist on type '() => void'."
        )
    }

    /**
     * The CALL-SIGNATURE TYPE-LITERAL spelling of the same thing. tsgo renders it
     * identically — `'() => void'`, not `'{ (): void; }'` — which is why this is a
     * separate pin rather than an assumed duplicate.
     */
    @Test
    fun `a missing member on a call-signature type literal reports`() {
        val d = diagnose(
            """
            declare const zzzP: { (): void };
            const zzzA = zzzP.zzzNope;
            """
        )
        assert(
            ts2339(d).single().message ==
                "Property 'zzzNope' does not exist on type '() => void'."
        )
    }

    /**
     * A NAMED interface carrying only a call signature is displayed by its NAME.
     */
    @Test
    fun `a missing member on a callable interface reports against the interface name`() {
        val d = diagnose(
            """
            interface ZzzCallable { (): void }
            declare const zzzI: ZzzCallable;
            const zzzA = zzzI.zzzNope;
            """
        )
        assert(
            ts2339(d).single().message ==
                "Property 'zzzNope' does not exist on type 'ZzzCallable'."
        )
    }

    /**
     * The row that needed BOTH halves: a `const`-bound arrow host with an expando
     * write renders its WHOLE type, members included, and the absent member reports
     * against that. Without `Checker.attachVariableExpandoMembers` this is
     * `'() => void'`; without route (B) it is silent.
     */
    @Test
    fun `a missing member on a const bound arrow host reports against its whole type`() {
        val d = diagnose(
            """
            const zzzF = () => {};
            zzzF.ok = 1;
            const zzzA = zzzF.zzzNope;
            """
        )
        assert(
            ts2339(d).single().message ==
                "Property 'zzzNope' does not exist on type '{ (): void; ok: number; }'."
        )
    }

    // --- the expando HOST predicate, tsgo's `getInitializerSymbol` ----------

    /**
     * A `const` bound to an ARROW is a host: the write and the read are both silent.
     */
    @Test
    fun `a const bound arrow is an expando host`() {
        assert(
            diagnose(
                """
                const zzzC = () => {};
                zzzC.m = 1;
                const zzzR = zzzC.m;
                """
            ).isEmpty()
        )
    }

    /**
     * So is a `const` bound to a FUNCTION EXPRESSION — tsgo's `IsExpandoInitializer`
     * admits both.
     */
    @Test
    fun `a const bound function expression is an expando host`() {
        assert(
            diagnose(
                """
                const zzzC = function () {};
                zzzC.m = 1;
                const zzzR = zzzC.m;
                """
            ).isEmpty()
        )
    }

    /**
     * A `let` is NOT a host in a TypeScript file — tsgo reports at the WRITE and at
     * the read. This is the boundary that makes the predicate a rule rather than
     * "anything function-shaped"; it is measured, not inferred from the Go source.
     */
    @Test
    fun `a let bound arrow is not an expando host`() {
        val d = ts2339(
            diagnose(
                """
                let zzzL = () => {};
                zzzL.m = 1;
                const zzzR = zzzL.m;
                """
            )
        )
        assert(d.size == 2)
        assert(d.all { it.message == "Property 'm' does not exist on type '() => void'." })
        assert(d[0].line == 2)
        assert(d[1].line == 3)
    }

    /** Neither is a `var`. */
    @Test
    fun `a var bound arrow is not an expando host`() {
        val d = ts2339(
            diagnose(
                """
                var zzzV = () => {};
                zzzV.m = 1;
                const zzzR = zzzV.m;
                """
            )
        )
        assert(d.size == 2)
        assert(d.all { it.message == "Property 'm' does not exist on type '() => void'." })
    }

    /**
     * An ANNOTATED `const` is not a host either, and it needs no clause of its own:
     * tsgo's binder DOES declare onto the function expression's symbol, but the
     * variable's type is the ANNOTATION, so no access reaches those members — and
     * here `getTypeOfVariableOrProperty` returns from `decl.type` above the attach
     * call, so the same answer falls out structurally. That is what this pin checks.
     */
    @Test
    fun `an annotated const bound function expression is not an expando host`() {
        val d = ts2339(
            diagnose(
                """
                const zzzE: () => void = function () {};
                zzzE.m = 1;
                const zzzR = zzzE.m;
                """
            )
        )
        assert(d.size == 2)
        assert(d.all { it.message == "Property 'm' does not exist on type '() => void'." })
    }

    /** The arrow spelling of the same annotated boundary. */
    @Test
    fun `an annotated const bound arrow is not an expando host`() {
        val d = ts2339(
            diagnose(
                """
                const zzzE: () => void = () => {};
                zzzE.m = 1;
                """
            )
        )
        assert(
            d.single().message == "Property 'm' does not exist on type '() => void'."
        )
    }

    // --- what must NOT change ----------------------------------------------

    /**
     * A `FunctionDeclaration` host stays silent at the WRITE — B431 declares the
     * member and (P18.136) puts it on the type.
     */
    @Test
    fun `negative control - a function declaration host is silent at the write`() {
        assert(
            diagnose(
                """
                function zzzG() {}
                zzzG.bar = 1;
                const zzzR = zzzG.bar;
                """
            ).isEmpty()
        )
    }

    /**
     * An AMBIENT function declaration is a host too — measured: tsgo is silent at
     * both the write and the read of `declare function dg(): void; dg.bar = 1`.
     */
    @Test
    fun `negative control - an ambient function declaration host is silent at the write`() {
        assert(
            diagnose(
                """
                declare function zzzD(): void;
                zzzD.bar = 1;
                const zzzR = zzzD.bar;
                """
            ).isEmpty()
        )
    }

    /**
     * The seven `Function.prototype` members are silent on a bare `() => void` in
     * BOTH compilers, and `RUNTIME_PROPERTIES` is the clause that does it. This is
     * not a precaution: it is the ONLY clause that fires on the eight dashboard
     * profiles, where route (B) is entered 20-23 times per profile and every single
     * arrival is `prop='call'`.
     */
    @Test
    fun `negative control - the Function prototype members are silent on a function type`() {
        assert(
            diagnose(
                """
                declare const zzzQ: () => void;
                const a1 = zzzQ.call;
                const a2 = zzzQ.bind;
                const a3 = zzzQ.apply;
                const a4 = zzzQ.length;
                const a5 = zzzQ.name;
                const a6 = zzzQ.prototype;
                const a7 = zzzQ.toString;
                """
            ).isEmpty()
        )
    }

    /** A member that EXISTS on a callable interface is silent. */
    @Test
    fun `negative control - a present member on a callable interface is silent`() {
        assert(
            diagnose(
                """
                interface ZzzCallable2 { (): void; here: number }
                declare const zzzI: ZzzCallable2;
                const zzzR = zzzI.here;
                """
            ).isEmpty()
        )
    }

    /**
     * A STRING index signature legitimately supplies every name, so the receiver is
     * refused — `cmamIndexSignatureProvides`, the shared helper, and tsgo agrees.
     */
    @Test
    fun `negative control - a string index signature supplies the member`() {
        assert(
            diagnose(
                """
                declare const zzzX: { (): void; [k: string]: any };
                const zzzR = zzzX.zzzNope;
                """
            ).isEmpty()
        )
    }

    // --- the double-emission guard, the crux of this change -----------------

    /**
     * THE PIN THE WHOLE CHANGE TURNS ON. B431's spine anchor owns the read for a
     * TOP-LEVEL `FunctionDeclaration`, so route (B) must refuse it: built without
     * `Checker.cmamCallSignatureReceiverReportable`'s declaration test, `function
     * zzzG() {}` + `zzzG.zzzNope` emitted the IDENTICAL row twice at the same span.
     * A COUNT pin, because the two rows are indistinguishable by message.
     *
     * The host here has NO expando write, which is the case `expandoAttachedTypeIds`
     * cannot cover — that marker only exists for a type that HAS members — so this
     * pin and `ExpandoFunctionMemberModelTest`'s once-pin are two different guards.
     */
    @Test
    fun `an absent member on a plain function declaration is reported exactly once`() {
        val d = ts2339(
            diagnose(
                """
                function zzzG() {}
                const zzzR = zzzG.zzzNope;
                """
            )
        )
        assert(d.size == 1)
        assert(d.single().message == "Property 'zzzNope' does not exist on type '() => void'.")
    }

    /**
     * And once when the read is NESTED, which is the position B431 was originally
     * written for — the arrangement that doubled first.
     */
    @Test
    fun `an absent member on an expando function declaration is reported once from a nested read`() {
        val d = ts2339(
            diagnose(
                """
                function zzzG(): void {}
                zzzG.px = 1;
                function zzzRead(): void { zzzG.zzzProbe; }
                """
            )
        )
        assert(d.size == 1)
        assert(
            d.single().message ==
                "Property 'zzzProbe' does not exist on type '{ (): void; px: number; }'."
        )
    }

    // --- refusals, each a LOST row that tsgo reports ------------------------

    /**
     * residue - a CONSTRUCT-signature receiver is refused. tsgo reports
     * `Property 'zzzNope' does not exist on type 'new () => { a: number; }'.`; this
     * route demands CALL signatures as its positive evidence ((CHK.45)), and a
     * `new`-able receiver is a class static side or a constructor interface whose
     * member set this model does not have. Closing it is a member-model question,
     * not a wider gate — (P18.134) refused a class static side for the same reason.
     */
    @Test
    fun `residue - a construct signature receiver is refused`() {
        assert(
            diagnose(
                """
                declare const zzzC: { new (): { a: number } };
                const zzzR = zzzC.zzzNope;
                """
            ).isEmpty()
        )
    }

    /**
     * residue - a callable interface WITH a heritage clause is refused. tsgo reports
     * `Property 'zzzNope' does not exist on type 'ZzzCallExt'.`; an inherited member
     * is one this empty table would report absent, which is B153, so the base-type
     * test is (CHK.45)'s positive-evidence demand and not a precaution. The inherited
     * member itself resolves — the second assertion is the control that says so.
     */
    @Test
    fun `residue - a callable interface with heritage is refused`() {
        assert(
            diagnose(
                """
                interface ZzzBase { b: number }
                interface ZzzCallExt extends ZzzBase { (): void }
                declare const zzzE: ZzzCallExt;
                const zzzR = zzzE.zzzNope;
                const zzzOk = zzzE.b;
                """
            ).isEmpty()
        )
    }

    /**
     * residue - an IMPORTED function declaration's missing member is refused, because
     * the declaration test that stops route (B) double-emitting with B431 cannot tell
     * a same-file host from a cross-file one — and B431, being per-file, does not
     * reach this read either. tsgo reports it. The refusal is today's answer preserved
     * rather than a new loss, which is what makes it acceptable in a round whose whole
     * risk is the other direction.
     */
    @Test
    fun `residue - an imported function declaration member is refused`() {
        assert(
            diagnose(
                """
                // @Filename: m.ts
                export function zzzG(): void {}
                // @Filename: t.ts
                import { zzzG } from "./m";
                const zzzR = zzzG.zzzNope;
                """
            ).none { it.code == 2339 }
        )
    }

    /**
     * residue - the NUMBER-index display gap. The ROW is correct and tsgo reports it
     * too; only the rendering differs, and the gap is PRE-EXISTING: on this round's
     * BEFORE binary `const w: boolean = zzzX` for the same receiver already printed
     * `Type '() => void' …` where tsgo prints `Type '{ (): void; [k: number]: any; }'
     * …`, through an emitter this round does not touch. Pinned with today's text so
     * closing the display gap is a visible change.
     */
    @Test
    fun `residue - a number index receiver reports with the pre-existing display gap`() {
        val d = ts2339(
            diagnose(
                """
                declare const zzzX: { (): void; [k: number]: any };
                const zzzR = zzzX.zzzNope;
                """
            )
        )
        assert(
            d.single().message == "Property 'zzzNope' does not exist on type '() => void'."
        )
    }

    // --- shapes the route reaches that nothing pinned before ----------------

    /** A function-typed PARAMETER — one of the commonest receivers in real code. */
    @Test
    fun `a missing member on a function-typed parameter reports`() {
        val d = ts2339(
            diagnose(
                """
                function zzzTake(cb: () => void) { return cb.zzzNope; }
                """
            )
        )
        assert(
            d.single().message == "Property 'zzzNope' does not exist on type '() => void'."
        )
    }

    /** A GENERIC function type keeps its type parameters in the display. */
    @Test
    fun `a missing member on a generic function type reports with its type parameters`() {
        val d = ts2339(
            diagnose(
                """
                declare const zzzGen: <T>(t: T) => T;
                const zzzR = zzzGen.zzzNope;
                """
            )
        )
        assert(
            d.single().message ==
                "Property 'zzzNope' does not exist on type '<T>(t: T) => T'."
        )
    }

    /** An OVERLOAD SET renders in braces, exactly as tsgo renders it. */
    @Test
    fun `a missing member on an overloaded function type reports against every signature`() {
        val d = ts2339(
            diagnose(
                """
                declare const zzzOvl: { (a: string): void; (a: number): void };
                const zzzR = zzzOvl.zzzNope;
                """
            )
        )
        assert(
            d.single().message ==
                "Property 'zzzNope' does not exist on type '{ (a: string): void; (a: number): void; }'."
        )
    }

    /**
     * A NARROWED nullish union reaches the route through its surviving constituent —
     * the receiver must be read past the guard, not as its declared union.
     */
    @Test
    fun `a missing member on a narrowed function union reports against the constituent`() {
        val d = ts2339(
            diagnose(
                """
                declare const zzzOpt: (() => void) | undefined;
                if (zzzOpt) { const zzzR = zzzOpt.zzzNope; }
                """
            )
        )
        assert(
            d.single().message == "Property 'zzzNope' does not exist on type '() => void'."
        )
    }

    /** A NON-generic type alias is displayed by its NAME, as in tsgo. */
    @Test
    fun `a missing member on a function type alias reports against the alias name`() {
        val d = ts2339(
            diagnose(
                """
                type ZzzAlias = () => void;
                declare const zzzAl: ZzzAlias;
                const zzzR = zzzAl.zzzNope;
                """
            )
        )
        assert(
            d.single().message == "Property 'zzzNope' does not exist on type 'ZzzAlias'."
        )
    }
}

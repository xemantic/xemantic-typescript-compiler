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
 * (CHK.98) stage 2 — four callback-typing mechanisms the (CHK.39)/(CHK.98) pull did
 * not reach, each measured against tsgo 7.0.2 (the sole reference since the owner's
 * 2026-09-12 directive; pristine `typescript@6.0.3` agreed on every row below where
 * it was consulted) over one-shape fixtures:
 *
 * 1. `Promise.then` / `PromiseLike.then` / `catch`: the lib parameter is a UNION —
 *    `onfulfilled?: ((value: T) => TResult1 | PromiseLike<TResult1>) | undefined | null`
 *    — and two instantiations no-op'd a union-wrapped function type: the generic
 *    interface member resolver's parameter branch (the receiver's `T`) and the
 *    contextual-parameter instantiator (the method's own `TResult1`, which the
 *    free-type-parameter rule had in hand). The property-access readers additionally
 *    tested `contextualType is Type.Object` outright, so the member-existence reader
 *    stayed silent where the assignability reader reported. 12 → 3 missing rows.
 * 2. A NAMESPACE-IMPORT member callee (`import * as zns …; zns.take((p) => …)`):
 *    the alias has no type ((CHK.73)), so `getTypeOfPropertyAccess` answered `any`
 *    while the argument and return walkers already resolved the same callee through
 *    the symbol tables. The pull now reads the same tables where the access answered
 *    nothing, refusing a root shadowed by a parameter, a `catch` variable, a `for`
 *    head or an enclosing block's own declaration. 4 → 0 missing.
 * 3. Predicate `filter`: an INLINE guard `(x): x is string => …` was a shape
 *    `predicateTargetTypeOfGuardExpr` did not read at all (it resolved a named or a
 *    parameter guard only) — not, as (P18.77) recorded, a `MethodSignature`
 *    declaration kind (measured: the lib `filter` arrives as a `MethodDeclaration`).
 *    The inferred predicate additionally learns tsc 5.5's `typeof` shape, reads the
 *    METHOD receiver as its element source, and answers `never` for a filter that
 *    keeps nothing (a standing false positive at `nums.filter(x => typeof x ===
 *    "string")`). 5 → 1 missing, the one an element-access receiver gap.
 * 4. `reduce(cb, {} as Record<string, number>)`: NOT a contextual-typing defect —
 *    `Record<K, V>` resolves to `any` in this checker, so the `initialValue: T`
 *    overload is selected. Recorded as `residue -`, mechanism named.
 *
 * Every pin names the value; a `residue -` pin asserts today's wrong answer with the
 * tsgo row in its KDoc.
 */
class ContextualCallbackStage2Test {

    private val realLibs = "// @strict: true\n// @useRealLibs: true"

    private val strNotNum = "Type 'string' is not assignable to type 'number'."
    private val numNotStr = "Type 'number' is not assignable to type 'string'."

    private fun rows(source: String, code: Int, directives: String = "// @strict: true"): List<Diagnostic> =
        diagnose(source, directives = directives).filter { it.code == code }

    private fun realRows(source: String, code: Int): List<Diagnostic> = rows(source, code, realLibs)

    // ------------------------------------------------------------------ Promise.then

    @Test
    fun `Promise then callback parameter takes the receiver's type argument`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            zp.then((v) => { const bad: string = v; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `PromiseLike then callback parameter takes the receiver's type argument`() {
        val d = realRows("""
            declare const zpl: PromiseLike<number>;
            zpl.then((v) => { const bad: string = v; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `then with both handlers types the fulfilled value and leaves the reason any`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            zp.then((v) => { const bad: string = v; }, (e) => { const alsoFine: string = e; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `a function expression callback of then is typed too`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            zp.then(function (v) { const bad: string = v; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `a member read on the then callback parameter reports`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            zp.then((v) => { v.nope; });
        """, 2339)
        assert(d.map { it.message } == listOf("Property 'nope' does not exist on type 'number'."))
    }

    @Test
    fun `the then callback parameter reaches an argument position`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            declare function zt(x: string): void;
            zp.then((v) => zt(v));
        """, 2345)
        assert(d.map { it.message } == listOf("Argument of type 'number' is not assignable to parameter of type 'string'."))
    }

    @Test
    fun `a then on a call result types the callback`() {
        val d = realRows("""
            declare function zmk(): Promise<number>;
            zmk().then((v) => { const bad: string = v; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `a then after finally types the callback`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            zp.finally(() => {}).then((v) => { const bad: string = v; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `an object member of the fulfilled value is read with its own type`() {
        val d = realRows("""
            declare const zp: Promise<{ a: string }>;
            zp.then((v) => { const bad: number = v.a; });
        """, 2322)
        assert(d.map { it.message } == listOf(strNotNum))
    }

    @Test
    fun `an explicit type argument on then leaves the fulfilled value typed by the receiver`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            zp.then<string>((v) => { const bad: string = v; return "s"; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `a union-wrapped optional callback of a generic function is typed after inference`() {
        val d = rows("""
            declare function zopt<T>(seed: T, cb?: ((p: T) => void) | null): void;
            zopt(1, (p) => { const bad: string = p; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `the embedded lib's plain then parameter is typed through the older branch - control`() {
        val d = rows("""
            declare const zp: Promise<number>;
            zp.then((v) => { const bad: string = v; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `negative control - catch types its reason any`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            zp.catch((e) => { const fine: string = e; });
        """, 2322)
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - a then callback's own return is not judged against the receiver`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            zp.then((v) => "s");
            zp.then((v) => { return { k: v }; });
        """, 2322)
        assert(d.isEmpty())
    }

    /** tsgo: `t.ts:3 TS2322 Type 'string' is not assignable to type 'number'.` — the
     *  return type of the first `then` (`Promise<TResult1 | TResult2>`) needs `TResult1`
     *  inferred from the callback's RETURN; [tryInferSingleTypeParamFromArgs] binds a
     *  callback-return type parameter only for a single-parameter signature whose
     *  callback parameter is a plain function type and whose return is the bare
     *  parameter, and `then` fails all three (two type parameters, a union-wrapped
     *  parameter, a `U | PromiseLike<U>` return). */
    @Test
    fun `residue - a chained then does not type the second callback`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            zp.then((v) => String(v)).then((s) => { const bad: number = s; });
        """, 2322)
        assert(d.isEmpty())
    }

    /** tsgo: `t.ts:4 TS2322 Type 'Promise<string>' is not assignable to type
     *  'Promise<number>'.` — the same callback-return inference gap as the chain. */
    @Test
    fun `residue - the return type of then is not inferred from the callback`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            const zr = zp.then((v) => { return v.toFixed(); });
            const bad: Promise<number> = zr;
        """, 2322)
        assert(d.isEmpty())
    }

    /** tsgo: `t.ts:3 TS2322 Type 'number' is not assignable to type 'string'.` — an
     *  `await` of the same un-inferred return. */
    @Test
    fun `residue - an awaited then result is not typed`() {
        val d = realRows("""
            declare const zp: Promise<number>;
            async function zf() { const r = await zp.then((v) => v * 2); const bad: string = r; }
        """, 2322)
        assert(d.isEmpty())
    }

    // ------------------------------------------------------------ namespace import

    private val nsModule = """
        // @Filename: /proj/src/nsmod.ts
        export function ztake(cb: (p: string) => void) {}
        export const zobj = { take(cb: (p: number) => void) {} };
        export function zgen<T>(x: T, cb: (p: T) => void) {}
    """

    @Test
    fun `a namespace-import member callee types the callback parameter`() {
        val d = rows(nsModule + """

            // @Filename: /proj/src/use.ts
            import * as zns from "./nsmod";
            zns.ztake((p) => { const bad: number = p; });
        """, 2322)
        assert(d.map { it.message } == listOf(strNotNum))
    }

    @Test
    fun `a nested member of a namespace-import value is a callee too`() {
        val d = rows(nsModule + """

            // @Filename: /proj/src/use.ts
            import * as zns from "./nsmod";
            zns.zobj.take((p) => { const bad: string = p; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `a generic namespace-import member infers from its other argument`() {
        val d = rows(nsModule + """

            // @Filename: /proj/src/use.ts
            import * as zns from "./nsmod";
            zns.zgen(1, (p) => { const bad: string = p; });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `a member read on the namespace-import callback parameter reports`() {
        val d = rows(nsModule + """

            // @Filename: /proj/src/use.ts
            import * as zns from "./nsmod";
            zns.ztake((p) => { p.nope; });
        """, 2339)
        assert(d.map { it.message } == listOf("Property 'nope' does not exist on type 'string'."))
    }

    @Test
    fun `a named import callee still types the callback - control`() {
        val d = rows(nsModule + """

            // @Filename: /proj/src/use.ts
            import { ztake } from "./nsmod";
            ztake((p) => { const bad: number = p; });
        """, 2322)
        assert(d.map { it.message } == listOf(strNotNum))
    }

    @Test
    fun `a parameter shadowing the namespace alias refuses the import - the hazard`() {
        val d = rows(nsModule + """

            // @Filename: /proj/src/use.ts
            import * as zns from "./nsmod";
            function zsh(zns: any) { zns.ztake((p) => { const notAnError: number = p; }); }
        """, 2322)
        assert(d.isEmpty())
    }

    @Test
    fun `a block-scoped const shadowing the namespace alias is the local`() {
        val d = rows(nsModule + """

            // @Filename: /proj/src/use.ts
            import * as zns from "./nsmod";
            export {};
            { const zns = { ztake(cb: (p: number) => void) {} }; zns.ztake((p) => { const bad: string = p; }); }
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    // ------------------------------------------------------------ predicate filter

    @Test
    fun `an inline type guard selects the predicate overload of filter`() {
        val d = realRows("""
            declare const zs: (string | number)[];
            const zf = zs.filter((x): x is string => typeof x === "string");
            const bad: number[] = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type 'string[]' is not assignable to type 'number[]'."))
        assert(d[0].messageChain == listOf("  Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `an annotated inline guard binds S from the predicate target and not the parameter`() {
        val d = realRows("""
            declare const zs: (string | number)[];
            const zf = zs.filter((x: string | number): x is string => typeof x === "string");
            const bad: number[] = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type 'string[]' is not assignable to type 'number[]'."))
    }

    @Test
    fun `a member read on a predicate-filtered element reports`() {
        val d = realRows("""
            declare const zs: (string | number)[];
            const zf = zs.filter((x): x is string => typeof x === "string");
            zf[0].nope;
        """, 2339)
        assert(d.map { it.message } == listOf("Property 'nope' does not exist on type 'string'."))
    }

    @Test
    fun `a readonly array receiver selects the predicate overload`() {
        val d = realRows("""
            declare const zs: readonly (string | number)[];
            const zf = zs.filter((x): x is number => typeof x === "number");
            const bad: string[] = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type 'number[]' is not assignable to type 'string[]'."))
    }

    @Test
    fun `a predicate-filtered array chains into map with its element type`() {
        val d = realRows("""
            declare const zs: (string | number)[];
            const zf = zs.filter((x): x is string => typeof x === "string").map((s) => { const bad: number = s; return s; });
        """, 2322)
        assert(d.map { it.message } == listOf(strNotNum))
    }

    @Test
    fun `a named guard argument was already typed - control`() {
        val d = realRows("""
            declare const zs: (string | number)[];
            declare function zisStr(x: unknown): x is string;
            const zf = zs.filter(zisStr);
            const bad: number[] = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type 'string[]' is not assignable to type 'number[]'."))
    }

    @Test
    fun `a one-expression typeof body infers the predicate`() {
        val d = realRows("""
            declare const zs: (string | number)[];
            const zf = zs.filter(x => typeof x === "string");
            const bad: boolean[] = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type 'string[]' is not assignable to type 'boolean[]'."))
    }

    @Test
    fun `a negated typeof body infers the complement`() {
        val d = realRows("""
            declare const zs: (string | number | boolean)[];
            const zf = zs.filter(x => typeof x !== "string");
            const bad: string[] = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type '(number | boolean)[]' is not assignable to type 'string[]'."))
    }

    @Test
    fun `a non-empty object member falls to the false branch of a typeof guard`() {
        val d = realRows("""
            interface ZO { p: number }
            declare const zs: (string | ZO)[];
            const zf = zs.filter(x => typeof x === "string");
            const bad: boolean[] = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type 'string[]' is not assignable to type 'boolean[]'."))
    }

    @Test
    fun `a typeof body over a non-union element that keeps nothing is never`() {
        val d = realRows("""
            declare const zn: number[];
            const zf = zn.filter(x => typeof x === "string");
            const fine: boolean[] = zf;
        """, 2322)
        assert(d.isEmpty())
    }

    @Test
    fun `typeof with the loose equality operator infers too`() {
        val d = realRows("""
            declare const zs: (string | number)[];
            const zf = zs.filter(x => typeof x == "number");
            const bad: string[] = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type 'number[]' is not assignable to type 'string[]'."))
    }

    @Test
    fun `the literal on the left of typeof infers too`() {
        val d = realRows("""
            declare const zs: (string | number)[];
            const zf = zs.filter(x => "string" === typeof x);
            const bad: number[] = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type 'string[]' is not assignable to type 'number[]'."))
    }

    @Test
    fun `the receiver form of the discriminant predicate infers`() {
        val d = realRows("""
            interface ZS1 { scoped: true; a: number } interface ZS2 { scoped: false; b: string }
            declare const zsc: (ZS1 | ZS2)[];
            const zf = zsc.filter(h => !h.scoped);
            const bad: number = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type 'ZS2[]' is not assignable to type 'number'."))
    }

    @Test
    fun `negative control - a boolean callback keeps the non-guard overload`() {
        val d = realRows("""
            declare const zs: (string | number)[];
            const zf = zs.filter((x) => x !== 1);
            const bad: boolean[] = zf;
        """, 2322)
        assert(d.map { it.message } == listOf("Type '(string | number)[]' is not assignable to type 'boolean[]'."))
    }

    /** tsgo: `t.ts:4 TS2322 Type 'string[]' is not assignable to type 'number[]'.` — a
     *  guard carrying its OWN type parameter is refused, because its predicate would
     *  resolve under the ambient scope rather than its own. */
    @Test
    fun `residue - an inline guard carrying its own type parameter is refused`() {
        val d = realRows("""
            declare const zs: (string | number)[];
            const zf = zs.filter(<T,>(x: string | number): x is string => typeof x === "string");
            const bad: number[] = zf;
        """, 2322)
        assert(d.isEmpty())
    }

    // ---------------------------------------------------------------------- reduce

    /** tsgo: SILENT — `acc` is `Record<string, number>`. Here `Record<K, V>` resolves to
     *  `any`, so `resolveCallOverload` selects `reduce(cb, initialValue: T)` with
     *  `T = string` and `acc.nope` reads on `string`. Not a contextual-typing defect:
     *  the `Record` alias is the mechanism, and `{ n: 0 } as { n: number }` beside it
     *  selects the generic overload correctly (the control below). */
    @Test
    fun `residue - a Record-asserted initial value selects the same-type overload`() {
        val d = realRows("""
            declare const zks: string[];
            zks.reduce((acc, k) => { acc.nope; return acc; }, {} as Record<string, number>);
        """, 2339)
        assert(d.map { it.message } == listOf("Property 'nope' does not exist on type 'string'."))
    }

    @Test
    fun `an asserted object initial value selects the generic reduce overload - control`() {
        val d = realRows("""
            declare const zks: string[];
            zks.reduce((acc, k) => { const bad: string = acc.n; return acc; }, { n: 0 } as { n: number });
        """, 2322)
        assert(d.map { it.message } == listOf(numNotStr))
    }

    @Test
    fun `a Map initial value types the accumulator - control`() {
        val d = realRows("""
            declare const zks: string[];
            zks.reduce((acc, k) => { acc.nope; return acc; }, new Map<string, number>());
        """, 2339)
        assert(d.map { it.message } == listOf("Property 'nope' does not exist on type 'Map<string, number>'."))
    }
}

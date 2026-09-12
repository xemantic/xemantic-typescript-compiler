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
 * (CHK.98)(i) The contextual type of an ARGUMENT of a `NewExpression` — tsc's
 * `getContextualTypeForArgumentAtIndex` over the construct signatures of the
 * callee's static side — so a callback passed to a constructor gets its parameter
 * types.
 *
 * Before this the plain `new C(cb)` was typed through B210's syntactic path (a
 * non-generic class with one bodied constructor and no type arguments) and every
 * other `new` shape left the callback's parameters `any`: explicit type arguments,
 * an overloaded constructor, an interface `new (…)` signature, a `typeof C`
 * variable, a namespace-qualified class, a generic class inferred from another
 * argument. Measured over 47 one-shape fixtures against tsgo 7.0.2 and pristine
 * `typescript@6.0.3` (zero REF-SPLIT): 7 agree / 40 missing before, 41 / 13 after,
 * and the 13 are each attributed below as a `residue -` pin.
 *
 * The arm shares the call arm's signature-list core, and three things the core did
 * not do before land for BOTH call-likes: explicit type arguments bind the type
 * parameters, a type parameter NO argument can bind takes tsc's first-pass answer
 * (the declared default, else the constraint, else `unknown`), and — for a `new`
 * only — an overloaded constructor is selected by arity even when the first
 * signature wins. A CLASS callee's constructor parameters are typed under the
 * class's OWN type-parameter scope, because their symbols are otherwise typed
 * lazily by whoever asks first ((CHK.73): a class value is its instance type here).
 *
 * THE HAZARD the queue item named, pinned as refusals: a type parameter that some
 * non-callback argument's parameter MENTIONS is one tsc's first pass infers from,
 * so where this checker's inference cannot bind it the parameter stays `any`
 * rather than becoming `unknown` or the raw `T` — a wrong type at every use is
 * worse than silence.
 */
class NewExpressionContextualArgumentTest {

    private val realLibs = "// @strict: true\n// @useRealLibs: true"

    private val strNotNum = "Type 'string' is not assignable to type 'number'."
    private val numNotStr = "Type 'number' is not assignable to type 'string'."
    private val boolNotNum = "Type 'boolean' is not assignable to type 'number'."

    private fun rows(source: String, code: Int): List<Diagnostic> = diagnose(source).filter { it.code == code }

    @Test
    fun `explicit type arguments on a generic class type the constructor callback parameter`() {
        val d = rows("""
            class ZzzG<T> { constructor(cb: (p: T) => void) {} }
            new ZzzG<string>((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `explicit type arguments reach a member read inside the callback`() {
        val d = rows("""
            class ZzzG<T> { constructor(cb: (p: T) => void) {} }
            new ZzzG<number>((p) => { p.length; });
        """, 2339)
        assert(d.size == 1)
        assert(d[0].message == "Property 'length' does not exist on type 'number'.")
    }

    @Test
    fun `two explicit type arguments type two callback parameters`() {
        val d = rows("""
            class ZzzG<A, B> { constructor(cb: (a: A, b: B) => void) {} }
            new ZzzG<string, boolean>((a, b) => { const bad: number = a; const bad2: number = b; });
        """, 2322)
        assert(d.map { it.message } == listOf(strNotNum, boolNotNum))
    }

    @Test
    fun `an omitted trailing type argument takes its declared default`() {
        val d = rows("""
            class ZzzG<T, U = number> { constructor(cb: (a: T, b: U) => void) {} }
            new ZzzG<string>((a, b) => { const bad: number = a; const bad2: string = b; });
        """, 2322)
        assert(d.map { it.message } == listOf(strNotNum, numNotStr))
    }

    @Test
    fun `a class type parameter inferred from a bare seed argument types the callback`() {
        val d = rows("""
            class ZzzH<T> { constructor(seed: T, cb: (p: T) => void) {} }
            new ZzzH("s", (p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `inference from a seed argument reaches a member read`() {
        val d = rows("""
            class ZzzH<T> { constructor(seed: T, cb: (p: T) => void) {} }
            new ZzzH(1, (p) => { p.nope; });
        """, 2339)
        assert(d.size == 1)
        assert(d[0].message == "Property 'nope' does not exist on type 'number'.")
    }

    private val overloaded = """
        class ZzzO {
          constructor(cb: (p: string) => void);
          constructor(n: number, cb: (p: boolean) => void);
          constructor(...a: any[]) {}
        }
    """

    @Test
    fun `an overloaded constructor is selected by arity for a one-argument new`() {
        val d = rows(overloaded + """
            new ZzzO((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `an overloaded constructor is selected by arity for a two-argument new`() {
        val d = rows(overloaded + """
            new ZzzO(1, (q) => { const bad2: number = q; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == boolNotNum)
    }

    @Test
    fun `an interface construct signature types the callback`() {
        val d = rows("""
            abstract class ZzzB { constructor(cb: (p: string) => void) {} }
            interface ZzzCtor { new (cb: (p: string) => void): ZzzB }
            declare const ZzzK: ZzzCtor;
            new ZzzK((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `an interface with two construct signatures selects by arity`() {
        val d = rows("""
            interface ZzzCtor { new (cb: (p: string) => void): object; new (n: number, cb: (p: boolean) => void): object }
            declare const ZzzK: ZzzCtor;
            new ZzzK((p) => { const bad: number = p; });
            new ZzzK(1, (q) => { const bad2: number = q; });
        """, 2322)
        assert(d.map { it.message } == listOf(strNotNum, boolNotNum))
    }

    @Test
    fun `a free class type parameter is unknown in the callback`() {
        val d = diagnose("""
            class ZzzU<T> { constructor(cb: (p: T) => void) {} }
            new ZzzU((p) => { const bad: number = p; });
        """)
        assert(d.count { it.code == 2322 } == 1)
        assert(d.first { it.code == 2322 }.message == "Type 'unknown' is not assignable to type 'number'.")
        assert(d.none { it.code == 7006 })
    }

    @Test
    fun `a free class type parameter takes its declared default`() {
        val d = rows("""
            class ZzzG<T = string> { constructor(cb: (p: T) => void) {} }
            new ZzzG((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `a free class type parameter takes its constraint`() {
        val d = rows("""
            class ZzzU<T extends string> { constructor(cb: (p: T) => void) {} }
            new ZzzU((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `a namespace-qualified class types the callback`() {
        val d = rows("""
            namespace ZzzNs { export class C { constructor(cb: (p: string) => void) {} } }
            new ZzzNs.C((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `a typeof class variable types the callback`() {
        val d = rows("""
            class ZzzC { constructor(cb: (p: string) => void) {} }
            declare const ZzzV: typeof ZzzC;
            new ZzzV((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `a parenthesized callee types the callback`() {
        val d = rows("""
            class ZzzC { constructor(cb: (p: string) => void) {} }
            new (ZzzC)((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `a derived class inherits the base constructor contextual type`() {
        val d = rows("""
            class ZzzB { constructor(cb: (p: string) => void) {} }
            class ZzzD extends ZzzB {}
            new ZzzD((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    /** The instance type's construct list is inherited-FIRST; the arm must take the
     *  class's OWN constructor when it declares one, as tsc's static side does. */
    @Test
    fun `a derived class with its own constructor hides the base signature`() {
        val d = rows("""
            class ZzzB { constructor(cb: (p: boolean) => void) {} }
            class ZzzD extends ZzzB { constructor(cb: (p: string) => void) { super((b) => {}); } }
            new ZzzD((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `an abstract base constructor reaches the concrete derived new`() {
        val d = rows("""
            abstract class ZzzA { constructor(cb: (p: string) => void) {} }
            class ZzzB extends ZzzA {}
            new ZzzB((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `the callback parameter reaches the argument reader`() {
        val d = rows("""
            class ZzzC { constructor(cb: (p: string) => void) {} }
            declare function zzzTake(n: number): void;
            new ZzzC((p) => { zzzTake(p); });
        """, 2345)
        assert(d.size == 1)
        assert(d[0].message == "Argument of type 'string' is not assignable to parameter of type 'number'.")
    }

    @Test
    fun `the callback parameter reaches the property-access reader`() {
        val d = rows("""
            class ZzzC { constructor(cb: (p: string) => void) {} }
            new ZzzC((p) => { p.nope; });
        """, 2339)
        assert(d.size == 1)
        assert(d[0].message == "Property 'nope' does not exist on type 'string'.")
    }

    @Test
    fun `an expression-bodied arrow reaches the property-access reader`() {
        val d = rows("""
            class ZzzC { constructor(cb: (p: string) => number) {} }
            new ZzzC((p) => p.nope);
        """, 2339)
        assert(d.size == 1)
        assert(d[0].message == "Property 'nope' does not exist on type 'string'.")
    }

    @Test
    fun `a function expression argument is typed too`() {
        val d = rows("""
            class ZzzC { constructor(cb: (p: string) => void) {} }
            new ZzzC(function (p) { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `an object-literal method argument is typed through the constructor parameter`() {
        val d = rows("""
            interface ZzzO { m(p: string): void }
            class ZzzC { constructor(o: ZzzO) {} }
            new ZzzC({ m(p) { const bad: number = p; } });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `a union of class values types the callback`() {
        val d = rows("""
            class ZzzA { constructor(cb: (p: string) => void) {} }
            class ZzzB { constructor(cb: (p: string) => void) {} }
            declare const ZzzK: typeof ZzzA | typeof ZzzB;
            new ZzzK((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    /** The property-access walker used to hand a `new`'s arguments the ENCLOSING
     *  contextual type; both callbacks must keep their own. */
    @Test
    fun `a new inside a call argument keeps both contextual types`() {
        val d = rows("""
            class ZzzC { constructor(cb: (p: string) => void) {} }
            declare function zzzTake(f: (q: boolean) => void): void;
            zzzTake((q) => { new ZzzC((p) => { const bad: number = p; const bad2: number = q; }); });
        """, 2322)
        assert(d.map { it.message } == listOf(strNotNum, boolNotNum))
    }

    @Test
    fun `a generic new inside a generic call argument keeps both`() {
        val d = rows("""
            class ZzzG<T> { constructor(seed: T, cb: (p: T) => void) {} }
            declare function zzzTake<U>(u: U, f: (q: U) => void): void;
            zzzTake(1, (q) => { new ZzzG("s", (p) => { const bad: number = p; const bad2: string = q; }); });
        """, 2322)
        assert(d.map { it.message } == listOf(strNotNum, numNotStr))
    }

    @Test
    fun `an optional callback parameter is typed`() {
        val d = rows("""
            class ZzzC { constructor(cb?: (p: string) => void) {} }
            new ZzzC((p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `a generic construct signature infers from the seed and honours explicit type arguments`() {
        val d = rows("""
            interface ZzzCtor { new <T>(seed: T, cb: (p: T) => void): object }
            declare const ZzzK: ZzzCtor;
            new ZzzK("s", (p) => { const bad: number = p; });
            new ZzzK<boolean>(true, (p) => { const bad2: number = p; });
        """, 2322)
        assert(d.map { it.message } == listOf(strNotNum, boolNotNum))
    }

    @Test
    fun `the plain non-generic shape B210 already typed still reports`() {
        val d = rows("""
            class ZzzC { constructor(name: string, cb: (p: string) => void) {} }
            new ZzzC("x", (p) => { const bad: number = p; });
        """, 2322)
        assert(d.size == 1)
        assert(d[0].message == strNotNum)
    }

    @Test
    fun `negative control - a contextually typed constructor callback parameter is not implicitly any`() {
        val d = diagnose("""
            class ZzzC { constructor(cb: (p: string) => void) {} }
            new ZzzC((p) => { const ok: string = p; });
        """)
        assert(d.none { it.code == 7006 || it.code == 2322 })
    }

    @Test
    fun `negative control - a callback with a correct use under explicit type arguments is clean`() {
        val d = diagnose("""
            class ZzzG<T> { constructor(cb: (p: T) => void) {} }
            new ZzzG<string>((p) => { const ok: string = p; p.length; });
        """)
        assert(d.none { it.code == 7006 || it.code == 2322 || it.code == 2339 })
    }

    @Test
    fun `the Promise executor resolve is typed from explicit type arguments`() {
        val d = diagnose("""
            new Promise<number>((resolve) => { const bad: string = resolve; });
        """, directives = realLibs).filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type '(value: number | PromiseLike<number>) => void' is not assignable to type 'string'.")
    }

    @Test
    fun `a Map from explicit type arguments types its forEach callback`() {
        val d = diagnose("""
            const zzzm = new Map<string, number>([["a", 1]]);
            zzzm.forEach((v, k) => { const bad: string = v; const bad2: number = k; });
        """, directives = realLibs).filter { it.code == 2322 }
        assert(d.map { it.message } == listOf(numNotStr, strNotNum))
    }

    // ------------------------------------------------------------------
    // The hazard, pinned as refusals, and the residues — each with the
    // reference row in its KDoc (tsgo 7.0.2 and pristine 6.0.3 agree on every one).
    // ------------------------------------------------------------------

    /** Both references: `Type 'number' is not assignable to type 'string'.` — tsc's
     *  first pass infers `T = number` from `[1]` against `seed: T[]`. This checker's
     *  constructor inference binds only a BARE `T` parameter, and because `seed`
     *  MENTIONS `T` the free rule must not answer `unknown` for it: the parameter
     *  stays `any`. Arm a2 (the hazard) turns this silence into a wrong `unknown`. */
    @Test
    fun `residue - a class type parameter bound only by an uninferable seed shape is refused`() {
        val d = diagnose("""
            class ZzzH<T> { constructor(seed: T[], cb: (p: T) => void) {} }
            new ZzzH([1], (p) => { const bad: string = p; });
        """)
        assert(d.none { it.code == 2322 })
        assert(d.none { it.code == 7006 })
    }

    /** Both references: `Type 'number' is not assignable to type 'string'.` — tsc
     *  infers `T` from the NAMED function's parameter. A function-typed parameter
     *  with a non-callback argument is first-pass evidence, so the refusal holds. */
    @Test
    fun `residue - a named function argument bound to the type parameter refuses rather than guesses`() {
        val d = diagnose("""
            class ZzzC<T> { constructor(a: (p: T) => void, b: (q: T) => void) {} }
            declare function zzzh(p: number): void;
            new ZzzC(zzzh, (q) => { const bad: string = q; });
        """)
        assert(d.none { it.code == 2322 })
    }

    /** Both references: `Type 'string' is not assignable to type 'number'.` —
     *  `getTypeOfExpressionCore` types a `ClassExpression` as `any` (a standing TODO),
     *  so the callee is `any` and no construct signature exists to consult. */
    @Test
    fun `residue - a class expression callee is any`() {
        val d = diagnose("""
            const ZzzE = class { constructor(cb: (p: string) => void) {} };
            new ZzzE((p) => { const bad: number = p; });
        """)
        assert(d.none { it.code == 2322 })
    }

    /** Both references: `Type 'T' is not assignable to type 'number'.` — the callback's
     *  OWN type parameter is the contextual parameter type in tsc; the pull's
     *  unresolved-type-parameter gate refuses it here, the same as for a call. */
    @Test
    fun `residue - a callback with its own type parameter stays any`() {
        val d = diagnose("""
            class ZzzC { constructor(cb: <T>(p: T) => void) {} }
            new ZzzC((p) => { const bad: number = p; });
        """)
        assert(d.none { it.code == 2322 })
    }

    /** Both references: TS18046 `'p' is of type 'unknown'.` — this checker reports
     *  no member read on `unknown` at all (`declare const u: unknown; u.nope` is silent
     *  too), so the free parameter's `unknown` is correct and unobservable here. */
    @Test
    fun `residue - a member read on an unknown-typed callback parameter is silent`() {
        val d = diagnose("""
            class ZzzU<T> { constructor(cb: (p: T) => void) {} }
            new ZzzU((p) => { p.nope; });
        """)
        assert(d.none { it.code == 18046 || it.code == 2339 })
    }

    /** Both references: `Type 'unknown' is not assignable to type 'number'.` — tsc
     *  expands the `[number]` tuple spread and types the callback at position 1. A
     *  spread makes the positional zip a lower bound here ((CHK.98)(d)), so the whole
     *  list is refused. */
    @Test
    fun `residue - a spread before the callback of a generic constructor refuses`() {
        val d = diagnose("""
            class ZzzC<T> { constructor(a: number, cb: (p: T) => void) {} }
            declare const zzzt: [number];
            new ZzzC(...zzzt, (p) => { const bad: number = p; });
        """)
        assert(d.none { it.code == 2322 })
    }

    /** Both references: two `Type 'string' is not assignable to type 'number'.` rows —
     *  tsc's `getContextualTypeForArgumentAtIndex` indexes into a REST parameter's
     *  element type; the shared core reads the parameter list positionally, as the
     *  call arm always has, so the rest's ARRAY type is not a callable and both stay
     *  `any`. */
    @Test
    fun `residue - a rest parameter of callbacks types none of them`() {
        val d = diagnose("""
            class ZzzC { constructor(...cbs: ((p: string) => void)[]) {} }
            new ZzzC((p) => { const bad: number = p; }, (r) => { const bad2: number = r; });
        """)
        assert(d.none { it.code == 2322 })
    }

    /** Both references: `Type 'string' is not assignable to type 'number'.` at `p`
     *  and `Type 'number' is not assignable to type 'string'.` at `this.m`, nothing
     *  else. PRE-EXISTING and measured identical on the parent binary: B210's
     *  syntactic path (`contextualizeFnExprFromAnnotation`) zips the annotation's
     *  `this` pseudo-parameter onto `p`, and its own answer wins over the pull's. */
    @Test
    fun `residue - a this-typed callback parameter is zipped by the syntactic path`() {
        val d = diagnose("""
            class ZzzC { constructor(cb: (this: ZzzC, p: string) => void) { } m = 1; }
            new ZzzC(function (p) { const bad: number = p; const bad2: string = this.m; });
        """)
        assert(d.any { it.code == 2322 && it.message == "Type 'ZzzC' is not assignable to type 'number'." })
        assert(d.any { it.code == 2683 })
    }

    /** Both references: TS7006 for `p` AND `extra`, no TS2322 — a callback with MORE
     *  required parameters than the contextual signature gets no contextual signature
     *  at all (tsc's `isAritySmaller`). PRE-EXISTING and measured identical on the
     *  parent binary, and identical for a CALL (`f((p, extra) => …)`): the syntactic
     *  path types `p` regardless. */
    @Test
    fun `residue - a callback with more parameters than the signature is still typed`() {
        val d = diagnose("""
            class ZzzC { constructor(cb: (p: string) => void) {} }
            new ZzzC((p, extra) => { const bad: number = p; });
        """)
        assert(d.any { it.code == 2322 && it.message == strNotNum })
        assert(d.none { it.code == 7006 })
    }

    /** Both references: `Type '(value: unknown) => void' is not assignable to type
     *  'string'.` — the free `T` of `PromiseConstructor`'s signature is `unknown` here
     *  as there, and the ROW is right; `getUnionType` does not absorb a union into
     *  `unknown`, so the display keeps `unknown | PromiseLike<unknown>`. */
    @Test
    fun `residue - a Promise executor without type arguments displays the unreduced union`() {
        val d = diagnose("""
            new Promise((resolve) => { const bad: string = resolve; resolve(1); });
        """, directives = realLibs).filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type '(value: unknown | PromiseLike<unknown>) => void' is not assignable to type 'string'.")
    }

    /** Both references: `Type '(reason?: any) => void' is not assignable to type
     *  'string'.` — the row is right and the optional parameter's display adds
     *  `| undefined` to `any`, a display rule outside this arm. */
    @Test
    fun `residue - the reject parameter displays any or undefined`() {
        val d = diagnose("""
            new Promise<number>((resolve, reject) => { const bad: string = reject; resolve(1); });
        """, directives = realLibs).filter { it.code == 2322 }
        assert(d.size == 1)
        assert(d[0].message == "Type '(reason?: any | undefined) => void' is not assignable to type 'string'.")
    }
}

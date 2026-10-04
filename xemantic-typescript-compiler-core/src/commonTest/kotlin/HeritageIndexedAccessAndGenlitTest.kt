/*
 * SPDX-FileCopyrightText: 2026 Kazimierz Pogoda / Xemantic
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 */
package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (P18.296) (CHK.231) + (LIBS.3) GENLIT. Every expectation is tsgo 7.0.2's own output for the same
 * file (`tools/tsgo-7.0.2/lib/tsc`), rendered `line:column TScode message` with elaboration lines
 * trimmed beneath.
 *
 * (CHK.231) has three mechanisms: a member typed by an INDEXED ACCESS of a type parameter
 * (`def: I["def"]`) is re-resolved against the receiver's type argument (it was answered from the
 * parameter's constraint at declaration time); a reference that OMITS defaulted type arguments
 * (`declare const t: Tup`) is completed from the defaults instead of answering the raw generic
 * (with `type Table = TableClass` keeping its own instance so it still displays `Table`); and the
 * three defects that exposed — an `any` argument indexed is `any`, `o[k]` with an unassigned key is
 * a narrowable reference, and two identical `number | bigint | Date` operands are comparable.
 * GENLIT: an object-literal argument against a type parameter keeps a literal property where the
 * constraint's property asks for a literal.
 */
class HeritageIndexedAccessAndGenlitTest {

    private fun render(d: List<Diagnostic>): List<String> = d.flatMap { x ->
        listOf("${x.line}:${x.character} TS${x.code} ${x.message}") + x.messageChain.map { it.trim() }
    }

    private val strict = "// @strict: true"

    @Test
    fun `an indexed-access member reads the receiver's type argument through one and two levels of heritage`() {
        val d = diagnose(
            """
            interface Int { def: { type: string } }
            interface TupInt extends Int { def: { type: "tuple"; rest: number } }
            interface ZT<out I extends Int = Int> { def: I["def"]; whole: I; clone(def?: I["def"]): this; get acc(): I["def"] }
            interface Mid<out I extends Int = Int> extends ZT<I> {}
            interface Tup extends Mid<TupInt>, Other {}
            interface Tup1 extends ZT<TupInt> {}
            interface Other { o: 1 }
            declare const t: Tup;
            declare const t1: Tup1;
            t.clone({ type: "tuple", rest: 1 });
            const a1: 'z' = t.def;
            const a2: 'z' = t.whole;
            const a3: 'z' = t.acc;
            t1.clone({ type: "tuple", rest: 1 });
            const b1: 'z' = t1.def;
            t.clone({ type: "tuple", rest: 1, nope: 2 });
            """,
            strict,
        )
        assert(
            render(d).filter { !it.startsWith("Type '") } == listOf(
                "11:7 TS2322 Type '{ type: \"tuple\"; rest: number; }' is not assignable to type '\"z\"'.",
                "12:7 TS2322 Type 'TupInt' is not assignable to type '\"z\"'.",
                "13:7 TS2322 Type '{ type: \"tuple\"; rest: number; }' is not assignable to type '\"z\"'.",
                "15:7 TS2322 Type '{ type: \"tuple\"; rest: number; }' is not assignable to type '\"z\"'.",
                "16:35 TS2353 Object literal may only specify known properties, and 'nope' does not exist in type '{ type: \"tuple\"; rest: number; }'.",
            )
        )
    }

    @Test
    fun `an indexed-access member of a class base reads the argument through this and from outside`() {
        val d = diagnose(
            """
            interface Int { def: { type: string } }
            interface TupInt extends Int { def: { type: "tuple"; rest: number } }
            class CB<I extends Int = Int> { def!: I["def"]; clone(d?: I["def"]): this { return this } get acc(): I["def"] { return null! } }
            class CM<I extends Int = Int> extends CB<I> {}
            class CT extends CM<TupInt> { m() { this.clone({ type: "tuple", rest: 1 }); const x: 'z' = this.def } }
            declare const ct: CT;
            ct.clone({ type: "tuple", rest: 1 });
            const c1: 'z' = ct.def;
            const c2: 'z' = ct.acc;
            """,
            strict,
        )
        assert(
            render(d).filter { !it.startsWith("Type '") } == listOf(
                "5:83 TS2322 Type '{ type: \"tuple\"; rest: number; }' is not assignable to type '\"z\"'.",
                "8:7 TS2322 Type '{ type: \"tuple\"; rest: number; }' is not assignable to type '\"z\"'.",
                "9:7 TS2322 Type '{ type: \"tuple\"; rest: number; }' is not assignable to type '\"z\"'.",
            )
        )
    }

    @Test
    fun `a bare reference to an all-defaulted generic reads its defaults and a partial one fills the rest`() {
        val d = diagnose(
            """
            interface Int<O = unknown> { def: { type: string }; out: O }
            interface TupInt<T = string> extends Int<T> { def: { type: "tuple"; rest: T } }
            interface ZT<out O = unknown, out I extends Int<O> = Int<O>> { _zod: I; def: I["def"] }
            interface Mid<out I extends Int = Int> extends ZT<any, I> {}
            interface Tup<T extends string = string> extends Mid<TupInt<T>> { extra: T }
            declare const t: Tup;
            const a1: 'z' = t.def;
            const a4: 'z' = t._zod.def;
            declare const t2: Tup<"q">;
            const b1: 'z' = t2.def;
            const e1: 'z' = t.extra;
            interface G<T = string, U = T[]> { v: T; w: U }
            declare const g: G;
            const e2: 'z' = g.v;
            declare const g2: G<number>;
            const e3: 'z' = g2.w;
            """,
            strict,
        )
        assert(
            render(d) == listOf(
                "7:7 TS2322 Type '{ type: \"tuple\"; rest: string; }' is not assignable to type '\"z\"'.",
                "8:7 TS2322 Type '{ type: \"tuple\"; rest: string; }' is not assignable to type '\"z\"'.",
                "10:7 TS2322 Type '{ type: \"tuple\"; rest: \"q\"; }' is not assignable to type '\"z\"'.",
                "11:7 TS2322 Type 'string' is not assignable to type '\"z\"'.",
                "14:7 TS2322 Type 'string' is not assignable to type '\"z\"'.",
                "16:7 TS2322 Type 'number[]' is not assignable to type '\"z\"'.",
            )
        )
    }

    @Test
    fun `an alias of a bare defaulted generic keeps its name while the bare spelling elsewhere does not`() {
        val d = diagnose(
            """
            declare class TableClass<S = any> { _field: S; }
            type Table = TableClass;
            declare const o: Table;
            declare const p: TableClass;
            const a: boolean = o;
            const b: boolean = p;
            """,
            strict,
        )
        assert(
            render(d) == listOf(
                "5:7 TS2322 Type 'Table' is not assignable to type 'boolean'.",
                "6:7 TS2322 Type 'TableClass<any>' is not assignable to type 'boolean'.",
            )
        )
    }

    @Test
    fun `an any type argument indexed is any`() {
        val d = diagnose(
            """
            type Env = { Bindings?: object; Variables?: object };
            class Context<E extends Env = any> { env: E['Bindings'] = {} }
            const f = (c: Context) => { const { remoteAddr } = c.env; const n: number = remoteAddr; };
            declare const c2: Context<{ Bindings: { k: string } }>;
            const s: number = c2.env.k;
            declare const c3: Context<Env>;
            const t: number = c3.env;
            """,
            strict,
        )
        assert(
            render(d) == listOf(
                "5:7 TS2322 Type 'string' is not assignable to type 'number'.",
                "7:7 TS2322 Type 'object | undefined' is not assignable to type 'number'.",
                "Type 'undefined' is not assignable to type 'number'.",
            )
        )
    }

    @Test
    fun `an element access keyed by an unassigned binding narrows and an unnarrowed one is TS2532`() {
        val d = diagnose(
            """
            type Bound = number | bigint | Date;
            interface CA<N extends Bound = Bound> { minimum?: N; exclusiveMinimum?: N }
            const narrowMin = (agg: CA, key: "minimum" | "exclusiveMinimum", value: Bound): void => {
              if (agg[key] === undefined || value > agg[key]) agg[key] = value;
            };
            interface CN { minimum?: number; exclusiveMinimum?: number }
            function f(agg: CN, key: "minimum" | "exclusiveMinimum", value: number, k2: "minimum") {
              const a = value > agg[key];
              if (agg[key] !== undefined) { const c = value > agg[key]; }
              const k5 = key;
              if (agg[k5] !== undefined) { const d = value > agg[k5]; }
            }
            """,
            strict,
        )
        assert(render(d) == listOf("8:21 TS2532 Object is possibly 'undefined'."))
    }

    @Test
    fun `a literal property against a type parameter keeps its literal where the constraint asks for one`() {
        val d = diagnose(
            """
            type Opts = { mode?: 'replace' | 'spread'; deep?: boolean };
            declare function md<D, S, O extends Opts = {}>(d: D, s: S, o?: O): [D, S, O];
            md(['a'], [1], { mode: 'spread' });
            declare function md2<O extends Opts>(o: O): O;
            md2({ mode: 'spread' });
            const r1: 'z' = md2({ mode: 'spread' });
            declare function ms<O extends { mode?: string }>(o: O): O;
            const r2: 'z' = ms({ mode: 'spread' });
            declare function mn<O>(o: O): O;
            const r3: 'z' = mn({ mode: 'spread' });
            const r4: 'z' = md2({ mode: 'spread', deep: true });
            declare function mnest<O extends { a: { mode: 'x' | 'y' } }>(o: O): O;
            const r5: 'z' = mnest({ a: { mode: 'x' } });
            declare function marr<O extends { m: ('x' | 'y')[] }>(o: O): O;
            const r6: 'z' = marr({ m: ['x'] });
            md2({ mode: 'bogus' });
            declare function mnum<O extends { n?: 1 | 2; s?: string }>(o: O): O;
            const r7: 'z' = mnum({ n: 1, s: 'q' });
            """,
            strict,
        )
        assert(
            render(d) == listOf(
                "6:7 TS2322 Type '{ mode: \"spread\"; }' is not assignable to type '\"z\"'.",
                "8:7 TS2322 Type '{ mode: string; }' is not assignable to type '\"z\"'.",
                "10:7 TS2322 Type '{ mode: string; }' is not assignable to type '\"z\"'.",
                "11:7 TS2322 Type '{ mode: \"spread\"; deep: true; }' is not assignable to type '\"z\"'.",
                "13:7 TS2322 Type '{ a: { mode: \"x\"; }; }' is not assignable to type '\"z\"'.",
                "15:7 TS2322 Type '{ m: \"x\"[]; }' is not assignable to type '\"z\"'.",
                "16:7 TS2322 Type '\"bogus\"' is not assignable to type '\"replace\" | \"spread\" | undefined'.",
                "18:7 TS2322 Type '{ n: 1; s: string; }' is not assignable to type '\"z\"'.",
            )
        )
    }
}

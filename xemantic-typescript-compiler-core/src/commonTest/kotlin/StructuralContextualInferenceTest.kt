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
 * (CHK.150) rung 1, round P18.180 — contextual typing reads a generic REFERENCE's
 * members with its type arguments SUBSTITUTED, and the contextual-return leg infers
 * between DIFFERENT object types by their members.
 *
 * THE MECHANISM WAS NOT THE ONE THE QUEUE NAMED. A reference's member TABLE
 * (`resolveReferenceMembers`) instantiates `getTypeOfSymbol(prop)`, and an interface or
 * class member symbol is resolved there with the declaration's own type parameters NOT
 * in scope — so `next: (value: T) => void` on `Observer<string>` read a parameter typed
 * `error`, which the instantiation could not substitute. `lookupPropertyTypeForCtx` found
 * the reference's OWN member (the target fallback was never reached) and handed that on;
 * the object-literal arrow's parameter then stayed `any`. The fix routes a reference's
 * member through `resolveGenericPropertyType` — the resolver the property-access path has
 * always used — via one helper, `ctxMemberTypeOf`, which both halves share.
 *
 * The X2 half is tsgo's `inferFromObjectTypes` tail (`inference.go:665`, `:794`, `:804`):
 * `createOp<T>(…): Subscriber<T>` at a position typed `Observer<T_outer>` binds `T`
 * through the shared member `next` — the two are not references to one generic.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, MEASURED over `build/scratch-p18180/cells`,
 * and every pin asserts a VALUE: the probe is an ARGUMENT at a `number` parameter, whose
 * TS2345 NAMES the type the callback parameter was given.
 *
 * NOT PINNED, because the answer today is wrong and pinning it is a countdown: rung 2
 * (a UNION parameter, X3) and rung 3 (an OVERLOAD pair, X1) still answer `unknown`; a
 * mapped-type parameter of a generic class method (`LPartial<Observer<W>>`) reaches the
 * pull with the class's `W` un-instantiated, which this round only REFUSES (the
 * no-silence pin below); and the relation-side rows (`Subscriber<number>` against
 * `Observer<string>`, an object literal's `(value: number) => void` against `next`) are
 * missing for the same member-table reason in the RELATION engine, which is a separate
 * change.
 */
class StructuralContextualInferenceTest {

    private val prelude = """
        interface Observer<T> { next: (value: T) => void; error: (err: any) => void; complete: () => void; }
        declare class Subscription {}
        declare class Subscriber<T> extends Subscription implements Observer<T> {
          next(value: T): void; error(err: any): void; complete(): void;
        }
        declare function createOp<T>(dest: Subscriber<any>, onNext?: (value: T) => void): Subscriber<T>;
        declare function pn(n: number): void;
        declare function ps(s: string): void;
    """.trimIndent() + "\n"

    private fun named(type: String, param: String = "number") =
        "Argument of type '$type' is not assignable to parameter of type '$param'."

    /** Every TS2345 message, in emission order. */
    private fun argRows(source: String): List<String> =
        diagnose(prelude + source).filter { it.code == 2345 }.map { it.message }

    @Test
    fun `a generic receiver's Observer parameter binds the return type through its members`() {
        val rows = argRows("""
            declare class X2<T> { subscribe(s: Observer<T>): Subscription; }
            function x2<T>(src: X2<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `a concrete receiver's Observer parameter binds the return type through its members`() {
        val rows = argRows("""
            declare class X2<T> { subscribe(s: Observer<T>): Subscription; }
            declare const src: X2<string>;
            src.subscribe(createOp(null!, (v) => { pn(v); }));
        """.trimIndent())
        assert(rows == listOf(named("string")))
    }

    @Test
    fun `a variable annotation of a different object type binds the return type`() {
        val rows = argRows("""
            function f<T>() { const o: Observer<T> = createOp(null!, (v) => { pn(v); }); }
            const o2: Observer<string> = createOp(null!, (v) => { pn(v); });
        """.trimIndent())
        assert(rows == listOf(named("T"), named("string")))
    }

    @Test
    fun `a METHOD member on the context matches a METHOD member on the return type`() {
        val rows = argRows("""
            interface ObsM<T> { next(value: T): void; }
            declare class X<T> { subscribe(s: ObsM<T>): Subscription; }
            function x<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `a function-typed PROPERTY on the return type matches a METHOD on the context`() {
        val rows = argRows("""
            declare class SubP<T> { next: (value: T) => void; }
            declare function createP<T>(onNext?: (value: T) => void): SubP<T>;
            interface ObsM<T> { next(value: T): void; }
            declare class X<T> { subscribe(s: ObsM<T>): Subscription; }
            function x<T>(src: X<T>) { src.subscribe(createP((v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `a member the return type INHERITS two levels up still binds it`() {
        val rows = argRows("""
            declare class Sub2<T> extends Subscriber<T> { extra(): void; }
            declare function createOp2<T>(onNext?: (value: T) => void): Sub2<T>;
            declare class X2<T> { subscribe(s: Observer<T>): Subscription; }
            function x2<T>(src: X2<T>) { src.subscribe(createOp2((v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `an OPTIONAL context member is read without its undefined`() {
        val rows = argRows("""
            interface PObs<T> { next?: (value: T) => void; }
            declare function takeP(o: PObs<string>): void;
            takeP(createOp(null!, (v) => { pn(v); }));
        """.trimIndent())
        assert(rows == listOf(named("string")))
    }

    @Test
    fun `an OPTIONAL return-type member is read without its undefined`() {
        // The source side's `undefined` is stripped by the leg's nullish-union arm
        // anyway; only a TARGET-side optional member exercises `removeMissingType`.
        val rows = argRows("""
            declare class SubO<T> { next?: (value: T) => void; }
            declare function createO<T>(cb?: (value: T) => void): SubO<T>;
            interface SrcO<T> { next: (value: T) => void; }
            declare function takeO(o: SrcO<string>): void;
            takeO(createO((v) => { pn(v); }));
        """.trimIndent())
        assert(rows.contains(named("string")))
        assert(!rows.contains(named("unknown")))
    }

    @Test
    fun `a nested callback member type is matched signature by signature`() {
        val rows = argRows("""
            declare class SubR<T> { map: (f: (v: T) => void) => void; }
            declare function createR<T>(cb?: (value: T) => void): SubR<T>;
            interface SrcR<T> { map: (f: (v: T) => void) => void; }
            declare function takeR(o: SrcR<string>): void;
            takeR(createR((v) => { pn(v); }));
        """.trimIndent())
        assert(rows == listOf(named("string")))
    }

    @Test
    fun `an object literal arrow member against a generic interface instantiation is typed`() {
        val rows = argRows("""
            declare function take(o: Observer<string>): void;
            take({ next: (value) => { pn(value); }, error: (e) => {}, complete: () => {} });
        """.trimIndent())
        assert(rows == listOf(named("string")))
    }

    @Test
    fun `an object literal METHOD against a generic interface METHOD is typed`() {
        val rows = argRows("""
            interface ObsM<T> { next(value: T): void; }
            declare function take(o: ObsM<string>): void;
            take({ next(value) { pn(value); } });
            take({ next: (value) => { pn(value); } });
        """.trimIndent())
        assert(rows == listOf(named("string"), named("string")))
    }

    @Test
    fun `an object literal against an instantiation over an enclosing type parameter is typed`() {
        // The enclosing parameter is deliberately NOT named `T`: with the same name as
        // `Observer`'s own the un-substituted member type rendered correctly by accident.
        val rows = argRows("""
            function f<U>() { const o: Observer<U> = { next: (v) => { pn(v); }, error: (e) => {}, complete: () => {} }; }
        """.trimIndent())
        assert(rows == listOf(named("U")))
    }

    @Test
    fun `an object literal against an interface that INHERITS the member is typed`() {
        val rows = argRows("""
            interface Obs2<T> extends Observer<T> { extra(): void; }
            declare function take(o: Obs2<string>): void;
            take({ next: (value) => { pn(value); }, error: (e) => {}, complete: () => {}, extra() {} });
        """.trimIndent())
        assert(rows == listOf(named("string")))
    }

    @Test
    fun `control - an inline and an aliased object type were already typed`() {
        val rows = argRows("""
            type ObsA = { next: (value: string) => void; error: (err: any) => void; complete: () => void; };
            declare function takeA(o: ObsA): void;
            takeA({ next: (value) => { pn(value); }, error: (e) => {}, complete: () => {} });
            declare function takeI(o: { next: (value: string) => void }): void;
            takeI({ next: (value) => { pn(value); } });
        """.trimIndent())
        assert(rows == listOf(named("string"), named("string")))
    }

    @Test
    fun `control - the same generic on both sides still binds through its type arguments`() {
        val rows = argRows("""
            declare class X4<T> { subscribe(s: Subscriber<T>): Subscription; }
            function x4<T>(src: X4<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `control - types that are definitely unrelated infer nothing`() {
        // Each side has a REQUIRED member the other lacks, so tsgo's
        // `typesDefinitelyUnrelated` refuses even though `next` is shared.
        val rows = argRows("""
            declare class Sub3<T> { next(value: T): void; extraT(): void; }
            declare function create3<T>(cb?: (value: T) => void): Sub3<T>;
            interface Src3<T> { next: (value: T) => void; extraS: number; }
            declare function take3(o: Src3<string>): void;
            take3(create3((v) => { pn(v); }));
        """.trimIndent())
        assert(rows == listOf(named("unknown")))
    }

    @Test
    fun `control - no shared member infers nothing`() {
        val rows = argRows("""
            declare class Box<T> { get(): T; }
            declare function mkBox<T>(cb?: (value: T) => void): Box<T>;
            interface Unrelated<T> { next: (value: T) => void; }
            declare function takeU(u: Unrelated<string>): void;
            takeU(mkBox((v) => { pn(v); }));
        """.trimIndent())
        assert(rows == listOf(named("unknown")))
    }

    @Test
    fun `control - an argument candidate still beats the structural contextual one`() {
        val rows = argRows("""
            declare function createS<T>(seed: T, onNext?: (value: T) => void): Subscriber<T>;
            declare function takeO(o: Observer<string>): void;
            takeO(createS(1, (v) => { ps(v); }));
        """.trimIndent())
        assert(rows == listOf(named("number", "string")))
    }

    @Test
    fun `an un-instantiated contextual type parameter is refused rather than leaking`() {
        // The class's `W` survives in the contextual `LPartial<Observer<W>>`; handed on,
        // the callback parameter would read `any` and the probe would go SILENT. tsgo
        // reports the row naming `T`, so the COUNT is the invariant pinned here.
        val rows = argRows("""
            type LPartial<T> = { [K in keyof T]?: T[K] };
            declare class X<W> { subscribe(s: LPartial<Observer<W>>): Subscription; }
            function x<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows.size == 1)
    }
}

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
 * (CHK.150) rung 2, round P18.181 — the contextual-return leg infers from a UNION on
 * either side.
 *
 * `subscribe(s: Subscriber<T> | ((value: T) => void))` handed `createOp<T>(…):
 * Subscriber<T>`: the CONTEXT is the union (in this leg the context is the inference
 * SOURCE and the callee's return type the TARGET), and the leg refused any source union
 * with two real members, so the callback parameter fell to `unknown`. tsgo's
 * `inferFromTypes` (`inference.go:290`) infers from each source constituent; the
 * function member contributes nothing and `Subscriber<T_outer>` binds `T`. When several
 * constituents bind one parameter, return-type candidates are COMBINED
 * (`PriorityImpliesCombination`, `checker.go:318`; `getCovariantInference`,
 * `inference.go:1421`) into a subtype-reduced union.
 *
 * The mirror — the callee RETURNS a union (`Subscriber<T> | undefined`, `T |
 * Subscriber<T>`) — is tsgo's union-target head (`inference.go:102`) plus
 * `inferToMultipleTypes`: identical constituents removed, references to one generic
 * matched, a naked type parameter bound only by what nothing else matched.
 *
 * EVERY EXPECTATION IS tsgo 7.0.2's OWN ROW, measured over `build/scratch-p18181/cells`.
 * The probe is an ARGUMENT at a `number` parameter (or an assignment to `boolean` where
 * the answer is a union of primitives), whose message NAMES the callback parameter's type.
 *
 * NOT PINNED (the answer is wrong today; pinning it is a countdown): the rxjs
 * `Partial<Observer<T>> | fn` shape (still `unknown` — (P18.180)'s mapped-type leak),
 * the overload pair X1 (rung 3), and three relation rows tsgo reports and we miss
 * (`Subscriber<string>` against the union parameter, `T | Subscriber<T>` against
 * `Subscriber<T>`, and a union return against a class) — which is why the value pins
 * below filter to the probe's own parameter type.
 */
class UnionContextualReturnInferenceTest {

    private val prelude = """
        interface Observer<T> { next: (value: T) => void; error: (err: any) => void; complete: () => void; }
        declare class Subscription {}
        declare class Subscriber<T> extends Subscription implements Observer<T> {
          next(value: T): void; error(err: any): void; complete(): void;
        }
        interface Box<T> { get(): T; }
        declare function createOp<T>(dest: Subscriber<any>, onNext?: (value: T) => void): Subscriber<T>;
        declare function pn(n: number): void;
        declare function ps(s: string): void;
    """.trimIndent() + "\n"

    private fun named(type: String, param: String = "number") =
        "Argument of type '$type' is not assignable to parameter of type '$param'."

    /** The TS2345 rows naming the probe's own parameter type, in emission order. */
    private fun probeRows(source: String, param: String = "number"): List<String> =
        diagnose(prelude + source)
            .filter { it.code == 2345 && it.message.endsWith("parameter of type '$param'.") }
            .map { it.message }

    private fun assignRows(source: String): List<String> =
        diagnose(prelude + source).filter { it.code == 2322 }.map { it.message }

    @Test
    fun `a union parameter of a subscriber and a function binds the return type - X3`() {
        val rows = probeRows("""
            declare class X<T> { subscribe(s: Subscriber<T> | ((value: T) => void)): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `a union with an unrelated object member binds through the member that matches`() {
        val rows = probeRows("""
            declare class X<T> { subscribe(s: Subscriber<T> | Box<T>): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `two members binding the same type give that one candidate`() {
        val rows = probeRows("""
            declare class X<T> { subscribe(s: Subscriber<T> | Observer<T>): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `two members binding different types combine into their union`() {
        val rows = probeRows("""
            declare class X<T> { subscribe(s: Subscriber<T> | Subscriber<string>): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("string | T")))
    }

    @Test
    fun `combined concrete candidates print as tsgo's union`() {
        val rows = assignRows("""
            declare class Y { subscribe(s: Subscriber<number> | Subscriber<string>): void; }
            function f(src: Y) { src.subscribe(createOp(null!, (v) => { const b: boolean = v; })); }
        """.trimIndent())
        assert(rows == listOf("Type 'string | number' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a candidate that is a subtype of another is reduced away`() {
        val rows = probeRows("""
            declare class Y { subscribe(s: Subscriber<string> | Subscriber<"a">): void; }
            function f(src: Y) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("string")))
    }

    @Test
    fun `null beside two real members is ignored`() {
        val rows = probeRows("""
            declare class X<T> { subscribe(s: Subscriber<T> | null | ((value: T) => void)): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `a naked type parameter member of the context contributes nothing`() {
        val rows = probeRows("""
            declare class X<T> { subscribe(s: T | Subscriber<T>): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `three members bind through the two that share the parameter`() {
        val rows = probeRows("""
            declare class X<T> { subscribe(s: Subscriber<T> | Observer<T> | ((value: T) => void)): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `a concrete union parameter binds its concrete type`() {
        val rows = probeRows("""
            declare class Y { subscribe(s: Subscriber<number> | ((value: number) => void)): void; }
            function f(src: Y) { src.subscribe(createOp(null!, (v) => { ps(v); })); }
        """.trimIndent(), param = "string")
        assert(rows == listOf(named("number", "string")))
    }

    @Test
    fun `a union variable annotation binds the return type`() {
        val rows = probeRows("""
            function f<T>() { const s: Subscriber<T> | ((value: T) => void) = createOp(null!, (v) => { pn(v); }); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `a callee returning a union with undefined matches constituent to constituent`() {
        val rows = probeRows("""
            declare function createOpU<T>(dest: Subscriber<any>, onNext?: (value: T) => void): Subscriber<T> | undefined;
            declare class X<T> { subscribe(s: Subscriber<T> | undefined): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOpU(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `a callee returning a naked parameter beside a generic binds through the generic`() {
        val rows = probeRows("""
            declare function createOpN<T>(dest: Subscriber<any>, onNext?: (value: T) => void): T | Subscriber<T>;
            declare class X<T> { subscribe(s: Subscriber<T>): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOpN(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `a naked parameter takes the source when no other member matched`() {
        val rows = probeRows("""
            declare function createOpB<T>(dest: Subscriber<any>, onNext?: (value: T) => void): T | Box<T>;
            function f() { const s: Subscriber<string> = createOpB(null!, (v) => { pn(v); }); }
        """.trimIndent())
        // tsgo and our CLI print this row twice (the arrow body is checked twice); the
        // diagnose() harness de-duplicates, so the pin compares the distinct rows.
        assert(rows.distinct() == listOf(named("Subscriber<string>")))
    }

    @Test
    fun `a naked parameter yields to a structural match`() {
        val rows = probeRows("""
            declare function createOpO<T>(dest: Subscriber<any>, onNext?: (value: T) => void): T | Observer<T>;
            function f() { const s: Subscriber<string> = createOpO(null!, (v) => { pn(v); }); }
        """.trimIndent())
        // tsgo and our CLI print this row twice (the arrow body is checked twice); the
        // diagnose() harness de-duplicates, so the pin compares the distinct rows.
        assert(rows.distinct() == listOf(named("string")))
    }

    @Test
    fun `identical constituents are matched away before a naked parameter is bound`() {
        val rows = probeRows("""
            declare function createOpV<T>(dest: Subscriber<any>, onNext?: (value: T) => void): T | undefined;
            function f() { const s: string | undefined = createOpV(null!, (v) => { pn(v); }); }
        """.trimIndent())
        assert(rows == listOf(named("string")))
    }

    @Test
    fun `control - an argument candidate still beats the union context`() {
        val rows = assignRows("""
            declare function createOp2<T>(x: T, onNext?: (value: T) => void): Subscriber<T>;
            declare class X<T> { subscribe(s: Subscriber<T> | ((value: T) => void)): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOp2("s", (v) => { const b: boolean = v; })); }
        """.trimIndent())
        assert(rows == listOf("Type 'string' is not assignable to type 'boolean'."))
    }

    @Test
    fun `control - an optional parameter was already a single real member`() {
        val rows = probeRows("""
            declare class X<T> { subscribe(s?: Subscriber<T>): Subscription; }
            function f<T>(src: X<T>) { src.subscribe(createOp(null!, (v) => { pn(v); })); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

    @Test
    fun `control - an object literal against a union parameter is typed on the argument path`() {
        val rows = probeRows("""
            declare class X<T> { subscribe(o: Observer<T> | ((value: T) => void)): Subscription; }
            function f<T>(src: X<T>) { src.subscribe({ next: (v) => { pn(v); }, error() {}, complete() {} }); }
        """.trimIndent())
        assert(rows == listOf(named("T")))
    }

}

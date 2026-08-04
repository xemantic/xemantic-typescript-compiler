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
 * (JIT.1)(f) round 807 — the behavioural gate for the thirteen-way split of
 * `checkArgumentsAgainstSignatureCore`.
 *
 * The function was **23,890 bytecodes**, three times HotSpot's 8,000-byte
 * `HugeMethodLimit`, so it was never JIT-compiled and ran interpreted for the
 * whole process. Its body is now an entry of 7,173 plus thirteen `caas*` helpers,
 * each holding one CONTIGUOUS run of the committed `ArgSections` partition
 * (`docs/perf/argument-check-attribution.md`).
 *
 * **What makes this split different from (a)-(d), and what this class exists
 * for.** Those four moved `when` ARMS, whose only exit is falling off the end.
 * This one moves runs of a LOOP BODY, and a loop body exits by `continue`,
 * `break` and — twice — a whole-function `return`. None of the three can cross a
 * function boundary, so each moved region hands a SIGNAL back
 * (`CAAS_CONTINUE` / `CAAS_BREAK` / `CAAS_RETURN` / `CAAS_NONE`) that the entry's
 * call site replays. **Dropping or confusing one of those 25 signals is the
 * mistake a mechanical split of this shape actually makes, and it is invisible to
 * a size check** — so every pin below is written against a signal, not against a
 * function name:
 *
 * * a lost `CAAS_BREAK` shows up as a SECOND diagnostic on a call with two
 *   failing arguments (TypeScript reports only the first failing argument per
 *   call), which is why so many fixtures below pass the same bad value twice and
 *   assert a COUNT of one;
 * * a lost `CAAS_RETURN` shows up as the coarse whole-argument TS2345 appearing
 *   NEXT TO the fine-grained per-property TS2322 that was supposed to replace it;
 * * a lost `CAAS_CONTINUE` shows up as a diagnostic on a shape that is currently
 *   silent — so those pins assert silence, and two of them (the unconstrained
 *   type parameter, the anonymous-object argument) pin a deliberate suppression
 *   this compiler makes today. They are EQUIVALENCE pins for the split, not
 *   conformance claims: if the suppression is ever removed on purpose, restate
 *   them, do not delete them.
 *
 * **What is NOT covered, stated plainly.** Two helpers have no arm pin of their
 * own: `caasPrologueWalkers` and `caasSingleTypeParamWalkers` hold the eleven
 * `tryEmit*` gates whose shapes are corpus-unique generic-inference fixtures;
 * they are covered by the generated corpus suite and the 8-profile grid, not
 * from here. And ONE SEAM IS NOT DISCRIMINATED: `caasTypeParamConstraintArg`'s
 * trailing `CAAS_CONTINUE`. Dropping it alone was ablated and every pin stayed
 * GREEN, because `caasNonSimpleParamChecks`' own `CAAS_CONTINUE` catches the
 * same argument one helper later — so on today's code that signal is a
 * REDUNDANT guard with no observable consequence of its own.
 *
 * Round 807's ablation record, for the next agent: six deliberate mistakes were
 * injected together (each helper's signal dropped in turn) and **five of the six
 * intended pins failed and no other arm pin did** — excess-property BREAK,
 * proto-override RETURN, per-property RETURN, non-simple CONTINUE, relation
 * BREAK. The sixth (the type-parameter CONTINUE) is the undiscriminated one
 * above.
 */
class CaasSplitTest {

    // ------------------------------------------------- caasWalkerArgChecks

    @Test
    fun `walker arm - an array literal argument is checked element-wise against the array element type`() {
        val d = diagnose(
            """
            declare function s(xs: number[]): void
            s([1, "a"])
            """
        )
        assert(d.any {
            it.code == 2322 && it.message == "Type 'string' is not assignable to type 'number'."
        })
    }

    @Test
    fun `walker arm - a tuple parameter is checked element-wise and does NOT stop at the first element`() {
        val d = diagnose(
            """
            declare function v(t: [string, number]): void
            v([1, "a"])
            """
        )
        // Both element mismatches, in source order: the tuple walker reports per
        // element and hands back no signal at all, so a CAAS_BREAK invented here
        // would silently drop the second.
        assert(d.count { it.code == 2322 } == 2)
    }

    @Test
    fun `walker seam - the intersection excess-property check BREAKS so only the first bad argument reports`() {
        val d = diagnose(
            """
            interface A1 { a: number }
            interface B1 { b: string }
            declare function wf(x: A1 & B1, y: A1 & B1): void
            wf({ a: 1, c: 2 }, { a: 1, d: 3 })
            """
        )
        assert(d.count { it.code == 2353 } == 1)
        assert(d.any {
            it.code == 2353 &&
                it.message == "Object literal may only specify known properties, and 'c' does not exist in type 'A1 & B1'."
        })
    }

    @Test
    fun `walker seam - the arrow-return drill CONTINUES so no whole-argument TS2345 joins its TS2322`() {
        val d = diagnose(
            """
            interface Data { value: number }
            declare function useState(f: () => Data): void
            useState(() => ({ value: "string" }))
            """
        )
        assert(d.any {
            it.code == 2322 && it.message == "Type 'string' is not assignable to type 'number'."
        })
        assert(d.none { it.code == 2345 })
    }

    // ------------------------------------- caasObjectLiteralVsObjectParam

    @Test
    fun `objlit arm and seam - the excess-property check BREAKS so the second bad argument is silent`() {
        val d = diagnose(
            """
            interface P { a: number }
            declare function f(p: P, q: P): void
            f({ a: 1, b: 2 }, { a: 1, c: 3 })
            """
        )
        assert(d.count { it.code == 2353 } == 1)
        assert(d.any {
            it.code == 2353 &&
                it.message == "Object literal may only specify known properties, and 'b' does not exist in type 'P'."
        })
    }

    // ---------------------------------------------- caasObjLitProtoOverride

    @Test
    fun `proto-override arm and seam - the TS2322 fires and its whole-function RETURN suppresses the missing-property TS2345`() {
        val d = diagnose(
            """
            interface I2 { value: number; doStuff(): void }
            declare function f2(i: I2): void
            f2({ toString: (s) => s })
            """
        )
        assert(d.any {
            it.code == 2322 && it.message == "Type '(s: any) => any' is not assignable to type '() => string'."
        })
        // The literal is missing BOTH declared members, so without the RETURN
        // signal the arg-level TS2345 would join the property-level TS2322.
        assert(d.none { it.code == 2345 })
    }

    // --------------------------------------------- caasObjLitMissingRequired

    @Test
    fun `missing-required arm and seam - the check BREAKS so the second bad argument is silent`() {
        val d = diagnose(
            """
            interface Q { id: number; name?: string }
            declare function g(q: Q, r: Q): void
            g({ name: "hello" }, { name: "x" })
            """
        )
        assert(d.count { it.code == 2345 } == 1)
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type '{ name: string; }' is not assignable to parameter of type 'Q'."
        })
    }

    // ---------------------------------------- caasObjLitPerPropertyMismatch

    @Test
    fun `per-property arm and seam - the TS2322 fires and its whole-function RETURN suppresses the whole-argument TS2345`() {
        val d = diagnose(
            """
            interface R2i { a: string }
            declare function h(x: R2i): void
            h({ a: 1 })
            """
        )
        assert(d.any {
            it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'."
        })
        assert(d.none { it.code == 2345 })
    }

    // -------------------------------------------------- caasNullishArgGates

    @Test
    fun `nullish arm and seam - the optional-type-parameter null gate CONTINUES so BOTH bad arguments report`() {
        val d = diagnose(
            """
            declare function t2<T extends { a: number }>(x?: T, y?: T): void
            t2(null, null)
            """
        )
        // A CAAS_BREAK confused for the CAAS_CONTINUE this gate really hands back
        // would leave exactly one.
        assert(d.count { it.code == 2345 } == 2)
        assert(d.all {
            it.code != 2345 ||
                it.message == "Argument of type 'null' is not assignable to parameter of type '{ a: number; } | undefined'."
        })
    }

    // ------------------------------------------ caasObjectLiteralVsTypeParam

    @Test
    fun `objlit-vs-type-parameter arm - the constraint's property types are still compared`() {
        val d = diagnose(
            """
            declare function u<T extends { a: string }>(x: T): void
            u({ a: 1 })
            """
        )
        assert(d.any {
            it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'."
        })
    }

    // ------------------------------------------- caasTypeParamConstraintArg

    @Test
    fun `type-parameter path - a constraint violation is reported ONCE naming the constraint`() {
        // NOT a seam pin for `caasTypeParamConstraintArg` alone, and that is a
        // MEASUREMENT, not a caveat. Dropping this helper's trailing
        // CAAS_CONTINUE by itself changes NOTHING on any fixture tried: the
        // argument simply falls into `caasNonSimpleParamChecks`, whose own
        // CAAS_CONTINUE catches it. Only when BOTH are dropped does the argument
        // reach the general relation and get reported a second time, naming the
        // bare type parameter ('T') beside the constraint ('string') — which is
        // what this pin sees. The trailing CONTINUE is therefore a REDUNDANT
        // guard on today's code, and no pin can discriminate it on its own.
        val d = diagnose(
            """
            declare function q<T extends string>(t: T): void
            q(1)
            """
        )
        assert(d.count { it.code == 2345 } == 1)
        assert(d.any {
            it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'."
        })
        assert(d.none { it.message.endsWith("parameter of type 'T'.") })
    }

    @Test
    fun `type-parameter arm - an unconstrained type-parameter parameter accepts any argument`() {
        // NOT a seam pin, and recorded as such: dropping the helper's trailing
        // CAAS_CONTINUE leaves this GREEN (measured on an ablated binary),
        // because a bare `Type.TypeParam` target is accepted downstream anyway.
        // It pins the behaviour, not the signal.
        val d = diagnose(
            """
            declare function r<T>(t: T): void
            r(1)
            """
        )
        assert(d.isEmpty())
    }

    // ---------------------------------------- caasArgKindAndIndexSignature

    @Test
    fun `arg-kind arm and seam - a named class against an interface parameter reports once and BREAKS`() {
        val d = diagnose(
            """
            class Foo { a: number = 1 }
            interface Bar { a: number; b: string }
            declare function p(x: Bar, y: Bar): void
            declare const foo: Foo
            p(foo, foo)
            """
        )
        assert(d.count { it.code == 2345 } == 1)
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type 'Foo' is not assignable to parameter of type 'Bar'."
        })
    }

    // ------------------------------------------- caasNonSimpleParamChecks

    @Test
    fun `non-simple arm - a primitive argument against a named interface parameter still reports`() {
        val d = diagnose(
            """
            interface Shape { x: number }
            declare function n(s: Shape): void
            declare const num: number
            n(num)
            """
        )
        assert(d.any {
            it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'Shape'."
        })
    }

    @Test
    fun `non-simple arm - a function argument with the wrong arity still reports`() {
        val d = diagnose(
            """
            declare function fn(cb: (x: number) => void): void
            declare const g2: (x: string, y: string) => void
            fn(g2)
            """
        )
        assert(d.any {
            it.code == 2345 &&
                it.message == "Argument of type '(x: string, y: string) => void' is not assignable to parameter of type '(x: number) => void'."
        })
    }

    @Test
    fun `non-simple seam - an anonymous-object argument CONTINUES past the relation`() {
        // Equivalence pin, not a conformance claim: tsc reports here and we do
        // not, because none of the block's allow-flags hold and it hands back
        // CAAS_CONTINUE. Losing that signal makes the shape report.
        val d = diagnose(
            """
            interface Shape { x: number }
            declare function n2(s: Shape): void
            declare const o: { y: number }
            n2(o)
            """
        )
        assert(d.none { it.code == 2345 })
    }

    // ------------------------------------------ caasTailGatesAndRelation

    @Test
    fun `relation arm and seam - two failing simple arguments report exactly once`() {
        val d = diagnose(
            """
            declare function k(a: string, b: string): void
            k(1, 2)
            """
        )
        assert(d.count { it.code == 2345 } == 1)
        assert(d.any {
            it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'."
        })
    }

    @Test
    fun `tail-gate seam - an explicit undefined for an optional parameter CONTINUES past the relation`() {
        val d = diagnose(
            """
            declare function m(a?: string): void
            m(undefined)
            """
        )
        assert(d.isEmpty())
    }

    // -------------------------------- the entry's own retained sections

    @Test
    fun `entry - the weak-type gate still runs between the moved regions`() {
        val d = diagnose(
            """
            interface W { a?: number; b?: string }
            declare function w(x: W): void
            w({ z: 1 })
            """
        )
        assert(d.any {
            it.code == 2559 && it.message == "Type '{ z: number; }' has no properties in common with type 'W'."
        })
    }

    @Test
    fun `entry - the post-loop rest-argument check still runs after the loop`() {
        val d = diagnose(
            """
            declare function rs(...xs: string[]): void
            rs("a", 1, 2)
            """
        )
        assert(d.any {
            it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'."
        })
    }
}

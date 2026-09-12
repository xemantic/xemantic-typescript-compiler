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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.133)(a)+(c) — `Signature.thisType`, the model change (CHK.97) D6 was blocked on,
 * and its cheapest consumer, tsc's call-site `this` check (TS2684). Every diagnostic
 * row below was measured against tsgo 7.0.2 AND pristine `typescript@6.0.3` before
 * any code was written; where the two DISAGREE — tsgo prints the elaboration's LEAF
 * (TS2741/TS2739) as its own row for a receiver-shaped mismatch, pristine prints
 * TS2684 with the leaf as its chain — pristine is honoured (round 938's law,
 * `unionTypeCallSignatures6:39`), and the expected text here is pristine's byte for
 * byte, chain included.
 *
 * THE MODEL. A `this:` pseudo-parameter was dropped by `getParameterSymbols` and
 * invisible to every consumer; [Signature.thisType] now carries it, built by every
 * declaration-reading builder ([Checker.declaredThisType], resolved INSIDE the
 * builder's type-parameter scope), instantiated with the rest by `TypeInstantiator`,
 * INTERSECTED across the members of a union-combined signature (tsc's
 * `getUnionSignatures` / `combineUnionThisParam`) and UNIONED across an
 * intersection-combined one (`combineIntersectionThisParam`). `parameters` keeps
 * excluding it and `minArgumentCount` never counts it — the model pins assert both.
 * `compareSignaturesIdentical` grew tsc's `this` arm, so two union members whose
 * `this` types differ are no longer one signature (`unionTypeCallSignatures6:38`'s
 * TS2349 falls out of that alone).
 *
 * THE ZIP FIX that came with it: an INTERFACE method and a CLASS method declared with
 * a `this` parameter resolved their parameter types by a positional zip that handed
 * `this`'s annotation to the first real parameter (`m(this: { k: number }, x: string)`
 * read `x: { k: number }`), a pre-existing false TS2345 on every call — measured on
 * the BEFORE binary, closed by [Checker.resolveParameterTypesInScope].
 *
 * THE CONSUMER, [Checker.checkThisArgumentOfCall]: a call whose ONE signature declares
 * a non-`void` `this` type must supply a receiver assignable to it — the `x` of
 * `x.f(…)` / `x["f"](…)`, or `void` for a bare `f(…)`; TS2684 at the RECEIVER (at
 * the whole call when there is none), and the call is CONSUMED (no argument row).
 *
 * MEASURED REACH: tsc's own sources declare 387-533 `this` parameters per profile
 * (the real lib's included) and NOT ONE call site on any of the eight profiles, on
 * `marked`, `cronstrue` or the 2,400-file project reaches the check — so the
 * 8-profile grid is a CONTROL for (c) and these pins plus the corpus are its gate.
 * The census's positive control was live on every fixture of the round's matrix.
 *
 * RECORDED residues, each measured: `.call`/`.apply`/`.bind` are silent because a
 * function VALUE's apparent `Function`/`CallableFunction` members answer `any` here
 * (a separate consumer, not this check); an optional-chain receiver (`o?.f()`) is
 * silent because `o?.f` itself types `any`; a `this` type mentioning a type
 * parameter is skipped (tsc checks the INSTANTIATED candidate; this funnel has not
 * inferred the type arguments yet); an OVERLOAD SET is not `this`-checked (tsc checks
 * per candidate); the relation (`compareSignaturesRelated`'s `this` leg) still
 * ignores it — (CHK.133)(b).
 */
class SignatureThisParameterTest {

    private val prelude = """
        interface ZzzA { n: number }
        function zzzF(this: ZzzA, x: string): number { return this.n + x.length }
    """.trimIndent()

    // ------------------------------------------------------------------ (c) the call site

    @Test
    fun `a bare call of a this-parameter function is TS2684 against void at the whole call`() {
        val d = diagnose(prelude + "\nzzzF(\"x\");\nexport {};")
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type 'void' is not assignable to method's 'this' of type 'ZzzA'." &&
                    it.messageChain.isEmpty()
            })
        }
        val row = d.first { it.code == 2684 }
        // `Diagnostic.line`/`character` are 1-based on the directive-stripped source.
        assert(row.line == 3)
        assert(row.character == 1)
        assert(row.length == "zzzF(\"x\")".length)
        assert(d.none { it.code == 2345 })
    }

    @Test
    fun `a method call on a receiver lacking the required member is TS2684 at the receiver with the missing-property chain`() {
        val d = diagnose(prelude + "\nconst zzzBad = { m: \"s\", f: zzzF };\nzzzBad.f(\"x\");\nexport {};")
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type '{ m: string; f: (this: ZzzA, x: string) => number; }' is not assignable to method's 'this' of type 'ZzzA'." &&
                    it.messageChain == listOf("  Property 'n' is missing in type '{ m: string; f: (this: ZzzA, x: string) => number; }' but required in type 'ZzzA'.")
            })
        }
        val row = d.first { it.code == 2684 }
        assert(row.length == "zzzBad".length)
        assert(row.line == 4)
        assert(row.character == 1)
    }

    @Test
    fun `an element-access callee is checked through its receiver too`() {
        val d = diagnose(prelude + "\nconst zzzBad = { m: \"s\", f: zzzF };\nzzzBad[\"f\"](\"x\");\nexport {};")
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type '{ m: string; f: (this: ZzzA, x: string) => number; }' is not assignable to method's 'this' of type 'ZzzA'."
            })
        }
    }

    @Test
    fun `a receiver whose member has the wrong type gets the two-line property chain`() {
        val d = diagnose(prelude + "\nconst zzzQ = { n: \"s\", f: zzzF };\nzzzQ.f(\"x\");\nexport {};")
        d should {
            have(any {
                it.code == 2684 &&
                    it.messageChain == listOf(
                        "  Types of property 'n' are incompatible.",
                        "    Type 'string' is not assignable to type 'number'.",
                    )
            })
        }
    }

    @Test
    fun `a receiver missing several members gets the TS2740 wording as its chain`() {
        val d = diagnose("""
            interface ZzzA { n: number; m: string }
            function zzzF(this: ZzzA, x: string): number { return this.n + x.length }
            const zzzR = { f: zzzF };
            zzzR.f("x");
            export {};
        """)
        d should {
            have(any {
                it.code == 2684 &&
                    it.messageChain == listOf("  Type '{ f: (this: ZzzA, x: string) => number; }' is missing the following properties from type 'ZzzA': n, m")
            })
        }
    }

    @Test
    fun `negative control - a receiver assignable to the this type is silent and the argument is still checked`() {
        val d = diagnose(prelude + "\nconst zzzObj = { n: 1, f: zzzF };\nconst zr: number = zzzObj.f(\"x\");\nzzzObj.f(1);\nexport {};")
        assert(d.none { it.code == 2684 })
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." }) }
    }

    @Test
    fun `negative control - a this void function is callable as a method and bare`() {
        val d = diagnose("""
            function zzzV(this: void, x: string): string { return x }
            const zzzO = { v: zzzV };
            zzzO.v("x");
            zzzV("x");
            export {};
        """)
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - an arrow has no this of its own`() {
        val d = diagnose("""
            const zzzArrow = (x: string): number => x.length;
            const zzzO = { m: "s", a: zzzArrow };
            zzzO.a("x");
            export {};
        """)
        assert(d.isEmpty())
    }

    @Test
    fun `negative control - a super method call is never this-checked`() {
        val d = diagnose("""
            class ZzzBase { m(this: { zz: number }): void {} }
            class ZzzD extends ZzzBase { q() { super.m() } }
            export {};
        """)
        assert(d.none { it.code == 2684 })
    }

    @Test
    fun `an interface method with a this parameter types its real parameters from their own declarations`() {
        val d = diagnose("""
            interface ZzzI { m(this: { k: number }, x: string): void }
            interface ZzzJ { k: number; m(this: { k: number }, x: string): void }
            declare const zzzI: ZzzI;
            declare const zzzJ: ZzzJ;
            zzzI.m("x");
            zzzJ.m("x");
            export {};
        """)
        // BEFORE this round: two false TS2345 rows (`x` read `{ k: number }`).
        assert(d.none { it.code == 2345 })
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type 'ZzzI' is not assignable to method's 'this' of type '{ k: number; }'." &&
                    it.messageChain == listOf("  Property 'k' is missing in type 'ZzzI' but required in type '{ k: number; }'.")
            })
        }
        assert(d.count { it.code == 2684 } == 1)
    }

    @Test
    fun `a class method with a this parameter is checked on a detached receiver and legal on its instance`() {
        val d = diagnose("""
            class ZzzC { k = 1; m(this: { k: number }, x: string): void {} }
            new ZzzC().m("x");
            const zzzCm = { m: new ZzzC().m };
            zzzCm.m("x");
            export {};
        """)
        assert(d.none { it.code == 2345 })
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type '{ m: (this: { k: number; }, x: string) => void; }' is not assignable to method's 'this' of type '{ k: number; }'." &&
                    it.messageChain == listOf("  Property 'k' is missing in type '{ m: (this: { k: number; }, x: string) => void; }' but required in type '{ k: number; }'.")
            })
        }
        assert(d.count { it.code == 2684 } == 1)
    }

    @Test
    fun `an object-literal method with a this parameter is checked against its own literal`() {
        val d = diagnose("""
            const zzzOL = { k: 1, m(this: { k: number }, x: string): void {} };
            zzzOL.m("x");
            const zzzOL2 = { m(this: { k: number }): void {} };
            zzzOL2.m();
            export {};
        """)
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type '{ m(this: { k: number; }): void; }' is not assignable to method's 'this' of type '{ k: number; }'."
            })
        }
        assert(d.count { it.code == 2684 } == 1)
    }

    @Test
    fun `a function-type alias carries its this parameter through the type node builder`() {
        val d = diagnose("""
            interface ZzzA { n: number }
            type ZzzFn = (this: ZzzA, x: string) => number;
            declare const zzzFn: ZzzFn;
            const zzzBad = { m: "s", f: zzzFn };
            zzzBad.f("x");
            export {};
        """)
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type '{ m: string; f: ZzzFn; }' is not assignable to method's 'this' of type 'ZzzA'." &&
                    it.messageChain == listOf("  Property 'n' is missing in type '{ m: string; f: ZzzFn; }' but required in type 'ZzzA'.")
            })
        }
    }

    @Test
    fun `a union callee whose members' this types differ is checked against their intersection`() {
        val d = diagnose("""
            interface ZzzA { a: number }
            interface ZzzB { b: string }
            declare const zzzU: ((this: ZzzA, x: string) => void) | ((this: ZzzB, x: string) => void);
            const zzzR = { a: 1, u: zzzU };
            zzzR.u("x");
            const zzzR2 = { a: 1, b: "s", u: zzzU };
            zzzR2.u("x");
            export {};
        """)
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type '{ a: number; u: ((this: ZzzA, x: string) => void) | ((this: ZzzB, x: string) => void); }' is not assignable to method's 'this' of type 'ZzzA & ZzzB'." &&
                    it.messageChain == listOf("  Property 'b' is missing in type '{ a: number; u: ((this: ZzzA, x: string) => void) | ((this: ZzzB, x: string) => void); }' but required in type 'ZzzB'.")
            })
        }
        assert(d.count { it.code == 2684 } == 1)
        assert(d.first { it.code == 2684 }.line == 5)
    }

    /** pristine `unionTypeCallSignatures5` — `this: void` and `this: number` intersect to `never`. */
    @Test
    fun `a union of void and number this types intersects to never and the bare call reports it alone`() {
        val d = diagnose("""
            interface A { (this: void, b?: number): void; }
            interface B { (this: number, b?: number): void; }
            interface C { (i: number): void; }
            declare const fn: A | B | C;
            fn(0);
        """)
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type 'void' is not assignable to method's 'this' of type 'never'." &&
                    it.messageChain.isEmpty()
            })
        }
        // BEFORE this round: an ours-only `Argument of type '0' is not assignable to parameter of type 'never'.`
        assert(d.none { it.code == 2345 })
    }

    /** pristine `unionTypeCallSignatures6`, its six rows. */
    @Test
    fun `pristine unionTypeCallSignatures6 - every row and chain`() {
        val d = diagnose("""
            type A = { a: string };
            type B = { b: number };
            type C = { c: string };
            type D = { d: number };
            type F0 = () => void;
            type F1 = (this: A) => void;
            type F2 = (this: B) => void;
            declare var f1: F1 | F2;
            f1();
            declare var f2: F0 | F1;
            f2();
            interface F3 {
              (this: A): void;
              (this: B): void;
            }
            interface F4 {
              (this: C): void;
              (this: D): void;
            }
            interface F5 {
              (this: C): void;
              (this: B): void;
            }
            declare var x1: A & C & {
              f0: F0 | F3;
              f1: F1 | F3;
              f2: F1 | F4;
              f3: F3 | F4;
              f4: F3 | F5;
            }
            x1.f0();
            x1.f1();
            x1.f2();
            x1.f3();
            x1.f4();
            declare var x2: A & B & {
              f4: F3 | F5;
            }
            x2.f4();
            type F6 = (this: A & B) => void;
            declare var f3: F1 | F6;
            f3();
            interface F7 {
              (this: A & B & C): void;
              (this: A & B): void;
            }
            declare var f4: F6 | F7;
            f4();
        """)
        val rows = d.filter { it.code == 2684 || it.code == 2349 }.sortedBy { it.start }.map { Triple(it.line, it.message, it.messageChain) }
        val expected = listOf(
            Triple(9, "The 'this' context of type 'void' is not assignable to method's 'this' of type 'A & B'.", listOf("  Type 'void' is not assignable to type 'A'.")),
            Triple(11, "The 'this' context of type 'void' is not assignable to method's 'this' of type 'A'.", emptyList()),
            Triple(34, "This expression is not callable.", listOf("  Each member of the union type 'F3 | F4' has signatures, but none of those signatures are compatible with each other.")),
            Triple(35, "The 'this' context of type 'A & C & { f0: F0 | F3; f1: F1 | F3; f2: F1 | F4; f3: F3 | F4; f4: F3 | F5; }' is not assignable to method's 'this' of type 'B'.", listOf("  Property 'b' is missing in type 'A & C & { f0: F0 | F3; f1: F1 | F3; f2: F1 | F4; f3: F3 | F4; f4: F3 | F5; }' but required in type 'B'.")),
            Triple(42, "The 'this' context of type 'void' is not assignable to method's 'this' of type 'A & B'.", listOf("  Type 'void' is not assignable to type 'A'.")),
            Triple(48, "The 'this' context of type 'void' is not assignable to method's 'this' of type 'A & B'.", listOf("  Type 'void' is not assignable to type 'A'.")),
        )
        assert(rows == expected)
        assert(d.size == 6)
    }

    /**
     * A generic interface's CALL SIGNATURE is instantiated by `resolveReferenceMembers`
     * through `TypeInstantiator.instantiateSignature` — the path the previous pin does
     * not take (a METHOD member is rebuilt on the property-access path). Both references
     * print `ZzzBox<number>`.
     */
    @Test
    fun `an instantiated generic call signature's this type follows the reference's type arguments`() {
        val d = diagnose("""
            interface ZzzBox<T> { v: T }
            interface ZzzCall<T> { (this: ZzzBox<T>, x: T): void }
            declare const zzzC: ZzzCall<number>;
            const zzzOk = { v: 1, c: zzzC };
            zzzOk.c(1);
            const zzzNo = { w: 1, c: zzzC };
            zzzNo.c(1);
            export {};
        """)
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type '{ w: number; c: ZzzCall<number>; }' is not assignable to method's 'this' of type 'ZzzBox<number>'." &&
                    it.messageChain == listOf("  Property 'v' is missing in type '{ w: number; c: ZzzCall<number>; }' but required in type 'ZzzBox<number>'.")
            })
        }
        assert(d.count { it.code == 2684 } == 1)
        assert(d.first { it.code == 2684 }.line == 7)
    }

    /**
     * A generic interface's function-typed PROPERTY is instantiated through
     * `substituteOuterTypeArgsInGenericFnObject` / `substituteOuterTypeArgsInSignature`
     * (17.39), the third instantiation path. Both references print `ZzzBox<number>`.
     */
    @Test
    fun `an instantiated generic function-typed property's this type follows the type arguments`() {
        val d = diagnose("""
            interface ZzzBox<T> { v: T }
            interface ZzzP<T> { f: (this: ZzzBox<T>, x: T) => void }
            declare const zzzP: ZzzP<number>;
            const zzzNo = { w: 1, f: zzzP.f };
            zzzNo.f(1);
            export {};
        """)
        d should {
            have(any {
                it.code == 2684 &&
                    it.message.endsWith("is not assignable to method's 'this' of type 'ZzzBox<number>'.")
            })
        }
        assert(d.count { it.code == 2684 } == 1)
    }

    @Test
    fun `residue - a this type mentioning a type parameter is not checked before inference`() {
        // Both references: TS2684 on `zzzNoBox.g(2)` naming `ZzzBox<2>` (tsc checks the
        // INSTANTIATED candidate). This funnel has not inferred `T` at the check, so it
        // refuses rather than compare a raw `ZzzBox<T>`.
        val d = diagnose("""
            interface ZzzBox<T> { v: T }
            function zzzG<T>(this: ZzzBox<T>, x: T): T { return this.v }
            const zzzNoBox = { w: 1, g: zzzG };
            zzzNoBox.g(2);
            export {};
        """)
        assert(d.none { it.code == 2684 })
    }

    @Test
    fun `call and apply check the thisArg through the receiver-built member and bind is still any`() {
        // (CHK.134)(1) closed the `call`/`apply` half of this residue and (CHK.134)(2) the
        // `bind` half; the name is kept ((CHK.114)). Both references print TS2353 at each
        // object-literal `thisArg` through the lib `CallableFunction` overloads, and for
        // `bind` — whose two overloads are both arity-eligible and both refuse — pristine's
        // per-candidate TS2769 chain carrying the same excess-property line (tsgo prints the
        // *last overload* form; the corpus's oracle is pristine). `FunctionBindTest`
        // carries the family.
        val d = diagnose(prelude + "\nzzzF.call({ m: \"s\" }, \"x\");\nzzzF.apply({ m: \"s\" }, [\"x\"]);\nconst zzzB2 = zzzF.bind({ m: \"s\" });\nexport {};")
        assert(d.count { it.code == 2353 && it.message == "Object literal may only specify known properties, and 'm' does not exist in type 'ZzzA'." } == 2)
        val overload = "'(this: (this: ZzzA, x: string) => number, thisArg: ZzzA): (x: string) => number'"
        d should {
            have(any {
                it.code == 2769 && it.message == "No overload matches this call." && it.messageChain == listOf(
                    "  Overload 1 of 2, $overload, gave the following error.",
                    "    Object literal may only specify known properties, and 'm' does not exist in type 'ZzzA'.",
                    "  Overload 2 of 2, $overload, gave the following error.",
                    "    Object literal may only specify known properties, and 'm' does not exist in type 'ZzzA'.",
                )
            })
        }
        assert(d.none { it.code == 2684 })
        assert(d.size == 3)
    }

    @Test
    fun `residue - an optional-chain receiver is silent because the callee types any`() {
        // Both references: TS2684 at `zzzMaybe` (non-nullable receiver `{ m: string; f: … }`).
        val d = diagnose(prelude + "\ndeclare const zzzMaybe: { m: string; f: typeof zzzF } | undefined;\nzzzMaybe?.f(\"x\");\nexport {};")
        assert(d.none { it.code == 2684 })
    }

    // ------------------------------------------------------------------ (a) the model

    private fun buildChecker(src: String): Pair<Checker, BinderResult> {
        // `strict` gates the intersection fold exactly as tsc's `noImplicitAny` does.
        val options = CompilerOptions(strict = true)
        val result = Binder(options).bind(Parser(src.trimIndent(), "/proj/t.ts").parse())
        return Checker(options, listOf(result), isMultiFileSource = true) to result
    }

    private fun singleSig(checker: Checker, result: BinderResult, name: String): Signature {
        val sym = result.locals[name]
        assert(sym != null)
        val t = checker.getTypeOfSymbol(sym)
        val sigs = checker.getCallSignaturesOfType(t)
        assert(sigs.size == 1)
        return sigs[0]
    }

    private val modelSource = """
        interface ZzzA { n: number }
        interface ZzzB { b: string }
        function zzzF(this: ZzzA, x: string): number { return this.n + x.length }
        function zzzPlain(x: string): number { return x.length }
        function zzzVoid(this: void, x: string): number { return x.length }
        function zzzAny(this, x: string): number { return x.length }
        declare const zzzU: ((this: ZzzA, x: string) => void) | ((this: ZzzB, x: string) => void);
        declare const zzzSame: ((this: ZzzA, x: string) => void) | ((this: ZzzA, x: string) => number);
        declare const zzzMix: ((x: string) => void) | ((this: ZzzB, x: string) => void);
        declare const zzzI: ((this: ZzzA, x: string) => void) & ((this: ZzzB, x: string) => void);
        interface ZzzBox<T> { v: T; m(this: ZzzBox<T>, x: T): void }
        declare const zzzBoxN: ZzzBox<number>;
    """

    @Test
    fun `model - a declared function carries its this type and its parameters still exclude it`() {
        val (checker, result) = buildChecker(modelSource)
        val sig = singleSig(checker, result, "zzzF")
        val a = checker.getDeclaredTypeOfSymbol(result.locals["ZzzA"]!!)
        assert(sig.thisType === a)
        assert(sig.parameters.size == 1)
        assert(sig.parameters[0].name == "x")
        assert(sig.minArgumentCount == 1)
    }

    @Test
    fun `model - negative control - a function without a this parameter has a null this type`() {
        val (checker, result) = buildChecker(modelSource)
        assert(singleSig(checker, result, "zzzPlain").thisType == null)
    }

    @Test
    fun `model - a this void parameter is the void type and an unannotated this is any`() {
        val (checker, result) = buildChecker(modelSource)
        assert(singleSig(checker, result, "zzzVoid").thisType === voidType)
        assert(singleSig(checker, result, "zzzAny").thisType === anyType)
        assert(singleSig(checker, result, "zzzAny").parameters.size == 1)
    }

    @Test
    fun `model - a union-combined signature intersects the members' this types`() {
        val (checker, result) = buildChecker(modelSource)
        val u = checker.getTypeOfSymbol(result.locals["zzzU"]!!)
        assert(u is Type.Union)
        val combined = checker.combineUnionSignatures(u)
        assert(combined != null)
        assert(combined.size == 1)
        val tt = combined[0].thisType
        assert(tt is Type.Intersection)
        val a = checker.getDeclaredTypeOfSymbol(result.locals["ZzzA"]!!)
        val b = checker.getDeclaredTypeOfSymbol(result.locals["ZzzB"]!!)
        val parts = tt.types
        assert(parts.size == 2)
        assert(parts[0] === a)
        assert(parts[1] === b)
        assert(combined[0].parameters.size == 1)
    }

    @Test
    fun `model - identical this types across union members collapse to the one instance`() {
        val (checker, result) = buildChecker(modelSource)
        val u = checker.getTypeOfSymbol(result.locals["zzzSame"]!!) as Type.Union
        val combined = checker.combineUnionSignatures(u)
        assert(combined != null)
        val a = checker.getDeclaredTypeOfSymbol(result.locals["ZzzA"]!!)
        assert(combined.all { it.thisType === a })
    }

    @Test
    fun `model - a member without a this type yields to the member that declares one`() {
        val (checker, result) = buildChecker(modelSource)
        val u = checker.getTypeOfSymbol(result.locals["zzzMix"]!!) as Type.Union
        val combined = checker.combineUnionSignatures(u)
        assert(combined != null)
        val b = checker.getDeclaredTypeOfSymbol(result.locals["ZzzB"]!!)
        assert(combined.size == 1)
        assert(combined[0].thisType === b)
    }

    @Test
    fun `model - an intersection-combined signature unions the members' this types`() {
        val (checker, result) = buildChecker(modelSource)
        val i = checker.getTypeOfSymbol(result.locals["zzzI"]!!)
        assert(i is Type.Intersection)
        val sigs = i.types.map { checker.getCallSignaturesOfType(it).single() }
        val folded = checker.getIntersectedSignatures(sigs)
        assert(folded != null)
        val tt = folded.thisType
        assert(tt is Type.Union)
        val a = checker.getDeclaredTypeOfSymbol(result.locals["ZzzA"]!!)
        val b = checker.getDeclaredTypeOfSymbol(result.locals["ZzzB"]!!)
        val parts = tt.types
        assert(parts.size == 2)
        assert(parts.any { it === a } && parts.any { it === b })
    }

    /**
     * The instantiation pin is a DIAGNOSTIC one: a reference's member table keeps the
     * RAW method type (`instantiateType` no-ops a function-shaped object by design), and
     * the instantiated signature is built on the PROPERTY-ACCESS path — which is the one
     * a call resolves through. Both references print `ZzzBox<number>` here.
     */
    @Test
    fun `an instantiated generic method's this type follows the receiver's type arguments`() {
        val d = diagnose("""
            interface ZzzBox<T> { v: T; m(this: ZzzBox<T>, x: T): void }
            declare const zzzBoxN: ZzzBox<number>;
            zzzBoxN.m(1);
            const zzzNo = { w: 1, m: zzzBoxN.m };
            zzzNo.m(1);
            export {};
        """)
        // The RECEIVER's display renders the instantiated `this` too — pristine prints
        // `m: (this: ZzzBox<number>, x: number) => void`, never the declaration's `ZzzBox<T>`.
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type '{ w: number; m: (this: ZzzBox<number>, x: number) => void; }' is not assignable to method's 'this' of type 'ZzzBox<number>'." &&
                    it.messageChain == listOf("  Property 'v' is missing in type '{ w: number; m: (this: ZzzBox<number>, x: number) => void; }' but required in type 'ZzzBox<number>'.")
            })
        }
        assert(d.count { it.code == 2684 } == 1)
        assert(d.first { it.code == 2684 }.line == 5)
    }
}

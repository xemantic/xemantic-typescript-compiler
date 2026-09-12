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
 * (CHK.134) decomposition (2) — `f.bind(thisArg, ...partials)` on a function VALUE, typed
 * as tsc types it. Every diagnostic row below was measured against tsgo 7.0.2 AND pristine
 * `typescript@6.0.3` before any code was written (`scripts/ref_matrix.py`, 50 fixtures,
 * zero REF-SPLIT rows); the expected text is pristine's byte for byte, chain included.
 *
 * THE MECHANISM. The lib's `CallableFunction.bind` has two overloads, and both are
 * functions of the RECEIVER alone, so [Checker.bindType] BUILDS the member per call
 * exactly as (P18.80) built `call`/`apply`, and no conditional type is evaluated:
 *
 *  - `bind<T>(this: T, thisArg: ThisParameterType<T>): OmitThisParameter<T>` —
 *    `ThisParameterType<T>` is the receiver's declared `this` (`unknown` without one, so
 *    `f.bind(anything)` is legal), and `OmitThisParameter<T>` is the receiver ITSELF when
 *    that `this` is absent (overloads and type parameters kept) and otherwise the last
 *    signature with its `this` dropped and its type parameters erased to their
 *    constraints (`infer` runs through `getBaseSignature`).
 *  - `bind<T, A, B, R>(this: (this: T, ...args: [...A, ...B]) => R, thisArg: T, ...args:
 *    A): (...args: B) => R` — tsc splits the receiver's parameter tuple at the call's
 *    partial count, so `A` is the leading partials and `B` the rest; a trailing rest
 *    parameter absorbs surplus partials on both sides.
 *
 * The member is ONE signature wherever one decides the call; the lib's PAIR is handed to
 * the overload machinery only when overload 1 refuses the `thisArg`, because both are
 * arity-eligible there and both fail — which is what prints pristine's per-candidate
 * TS2769 chain (tsgo prints the *last overload* form; the corpus's oracle is pristine).
 *
 * MEASURED REACH: `bind` sites reaching the miss path are 5 / 24 / 14 / 8 per profile,
 * every one refused (`strictBindCallApply: false` in tsc's own tsconfigs, or an
 * optional-chain union receiver); marked, cronstrue and the 2,400-file project reach
 * none. So the 8-profile grid and the library arms are CONTROLS and these pins are the
 * gate.
 *
 * RECORDED residues, each measured and pinned `residue -` below: the ARITY of a call
 * through a VARIABLE holding the bound function ((CHK.97)'s general gap — `declare const
 * kk: (a: number, b: string) => void; kk("x")` reads the same TS2345 where tsc reads
 * TS2554); the result of an INLINE call of a bind call at a declaration/assignment/return
 * reader (a general call-of-call gap, `zzzMk()("x")` is silent too); a bound function
 * re-bound and CALLED (`spineArithRecordVarDecl`'s callable-shadow arm first-touches the
 * symbol under an ambient that reads the receiver as `any`, a pre-existing program-order
 * hazard that `const q = p(); q(1)` shares); an optional-chain receiver ((CHK.133)(c)); a
 * union receiver (tsc distributes the conditional); a spread partial (tsc's `impliedArity`
 * is undefined there); the bare `f.bind` display (tsc renders the generic lib method); a
 * class value's `typeof` display; and the overload-chain elaboration for a class `this`
 * (a mismatched member drilled before a missing one, B560's order).
 */
class FunctionBindTest {

    private val prelude = """
        interface ZzzT { m: string }
        declare const zzzO: ZzzT;
        function zzzF(this: ZzzT, x: string): number { return x.length + this.m.length }
        function zzzG(x: string): number { return x.length }
    """.trimIndent()

    private val strictReal = "// @strict: true\n// @useRealLibs: true"

    private fun d(src: String, directives: String = strictReal) =
        diagnose(prelude + "\n" + src + "\nexport {};", directives = directives)

    private val oneOfTwo = "  Overload 1 of 2, '(this: (this: ZzzT, x: string) => number, thisArg: ZzzT): (x: string) => number', gave the following error."
    private val twoOfTwo = "  Overload 2 of 2, '(this: (this: ZzzT, x: string) => number, thisArg: ZzzT): (x: string) => number', gave the following error."

    // ------------------------------------------------------------------ the zero-partial overload

    @Test
    fun `negative control - a legal bind and a call of its result are silent`() {
        val d = d("const zzzB = zzzF.bind(zzzO);\nzzzB(\"x\");\nconst zzzN: number = zzzB(\"x\");")
        assert(d.isEmpty())
    }

    @Test
    fun `the result of bind called with a wrong argument is TS2345 against the receiver's parameter`() {
        val d = d("zzzF.bind(zzzO)(1);")
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `the result of bind is the receiver's signature without its this`() {
        val d = d("const zzzS: string = zzzF.bind(zzzO);")
        d should { have(any { it.code == 2322 && it.message == "Type '(x: string) => number' is not assignable to type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `the bound result's return type is checked at a declaration`() {
        val d = d("const zzzB = zzzF.bind(zzzO);\nconst zzzBad: string = zzzB(\"x\");")
        d should { have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `the this type is dropped from the bound result`() {
        // `zzzB.call(undefined, "x")` is legal because the bound function has no `this`;
        // the same `undefined` against the receiver itself is the row.
        val d = d("const zzzB = zzzF.bind(zzzO);\nzzzB.call(undefined, \"x\");\nzzzB.call(zzzO, \"x\");\nzzzF.call(undefined, \"x\");")
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'undefined' is not assignable to parameter of type 'ZzzT'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `an unassignable thisArg is TS2769 with pristine's per-candidate chain`() {
        val d = d("zzzF.bind(undefined);")
        d should {
            have(any {
                it.code == 2769 && it.message == "No overload matches this call." &&
                    it.messageChain == listOf(
                        oneOfTwo,
                        "    Argument of type 'undefined' is not assignable to parameter of type 'ZzzT'.",
                        twoOfTwo,
                        "    Argument of type 'undefined' is not assignable to parameter of type 'ZzzT'.",
                    )
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `an object-literal thisArg elaborates at the property inside the chain`() {
        val d = d("zzzF.bind({ m: 1 });")
        d should {
            have(any {
                it.code == 2769 && it.messageChain == listOf(
                    oneOfTwo,
                    "    Type 'number' is not assignable to type 'string'.",
                    twoOfTwo,
                    "    Type 'number' is not assignable to type 'string'.",
                )
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `an unassignable identifier thisArg carries the property chain`() {
        val d = d("declare const zzzBadO: { m: number };\nzzzF.bind(zzzBadO);")
        d should {
            have(any {
                it.code == 2769 && it.messageChain == listOf(
                    oneOfTwo,
                    "    Argument of type '{ m: number; }' is not assignable to parameter of type 'ZzzT'.",
                    "      Types of property 'm' are incompatible.",
                    "        Type 'number' is not assignable to type 'string'.",
                    twoOfTwo,
                    "    Argument of type '{ m: number; }' is not assignable to parameter of type 'ZzzT'.",
                    "      Types of property 'm' are incompatible.",
                    "        Type 'number' is not assignable to type 'string'.",
                )
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `negative control - an object-literal thisArg with an extra property is the inferred T and silent`() {
        val d = d("zzzF.bind({ m: \"s\", extra: 1 });")
        assert(d.isEmpty())
    }

    @Test
    fun `bind with no arguments reports the uninstantiated rest candidate`() {
        val d = d("zzzF.bind();")
        d should { have(any { it.code == 2555 && it.message == "Expected at least 1 arguments, but got 0." }) }
        assert(d.size == 1)
    }

    // ------------------------------------------------------------------ partial application

    @Test
    fun `a partial argument drops the receiver's leading parameter`() {
        val d = d("const zzzB = zzzF.bind(zzzO, \"x\");\nzzzB();\nconst zzzBad: string = zzzB();\nconst zzzS: string = zzzF.bind(zzzO, \"x\");")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
            have(any { it.code == 2322 && it.message == "Type '() => number' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `a wrong partial argument is TS2345 against the receiver's parameter`() {
        val d = d("zzzF.bind(zzzO, 1);")
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `a wrong thisArg beside a partial is TS2345 through the second overload alone`() {
        val d = d("zzzF.bind(undefined, \"x\");")
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'undefined' is not assignable to parameter of type 'ZzzT'." && it.messageChain.isEmpty() }) }
        assert(d.size == 1)
    }

    private val three = "function zzzF3(this: ZzzT, x: string, y: number, z: boolean): number { return x.length + y + this.m.length }\n"

    @Test
    fun `two partials leave the third parameter`() {
        val d = d(three + "const zzzB2 = zzzF3.bind(zzzO, \"x\", 1);\nzzzB2(true);\nzzzB2(1);")
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'boolean'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `three partials leave a no-argument function`() {
        val d = d(three + "const zzzB3 = zzzF3.bind(zzzO, \"x\", 1, true);\nzzzB3();\nconst zzzBad: string = zzzB3();")
        d should { have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `more partials than parameters is TS2554 against the instantiated overload`() {
        val d = d(three + "zzzF3.bind(zzzO, \"x\", 1, true, 5);")
        d should { have(any { it.code == 2554 && it.message == "Expected 4 arguments, but got 5." }) }
        assert(d.size == 1)
    }

    @Test
    fun `a wrong later partial is TS2345 against its parameter`() {
        val d = d(three + "zzzF3.bind(zzzO, \"x\", \"y\");")
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." }) }
        assert(d.size == 1)
    }

    // ------------------------------------------------------------------ receivers

    @Test
    fun `a receiver without a this type takes any thisArg and binds to itself`() {
        val d = d("const zzzB = zzzG.bind(undefined);\nzzzG.bind(1);\nzzzG.bind(zzzO);\nzzzB(1);\nconst zzzBad: string = zzzB(\"x\");\nconst zzzS: string = zzzG.bind(undefined);")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
            have(any { it.code == 2322 && it.message == "Type '(x: string) => number' is not assignable to type 'string'." })
        }
        assert(d.size == 3)
    }

    @Test
    fun `a receiver without a this type takes a partial`() {
        val d = d("const zzzB = zzzG.bind(undefined, \"x\");\nzzzB();\nconst zzzBad: string = zzzB();\nzzzG.bind(1, 2);")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `a this void receiver accepts undefined and refuses an object`() {
        val d = d("function zzzV(this: void, x: string): number { return x.length }\nzzzV.bind(undefined);\nzzzV.bind(zzzO);\nconst zzzBv = zzzV.bind(undefined);\nconst zzzBad: string = zzzBv(\"x\");")
        d should {
            have(any {
                it.code == 2769 && it.messageChain == listOf(
                    "  Overload 1 of 2, '(this: (this: void, x: string) => number, thisArg: void): (x: string) => number', gave the following error.",
                    "    Argument of type 'ZzzT' is not assignable to parameter of type 'void'.",
                    "  Overload 2 of 2, '(this: (this: void, x: string) => number, thisArg: void): (x: string) => number', gave the following error.",
                    "    Argument of type 'ZzzT' is not assignable to parameter of type 'void'.",
                )
            })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `an arrow receiver binds through its own signature`() {
        val d = d("const zzzA = (x: string): number => x.length;\nconst zzzB = zzzA.bind(undefined);\nzzzB(1);\nconst zzzBad: string = zzzB(\"x\");\nzzzA.bind(zzzO);")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `an overloaded receiver with a this binds to its last overload`() {
        val d = d("declare function zzzOv(this: ZzzT, x: string): number;\ndeclare function zzzOv(this: ZzzT, x: number, y: string): boolean;\nconst zzzB = zzzOv.bind(zzzO);\nzzzB(1, \"s\");\nconst zzzBad: string = zzzB(1, \"s\");")
        d should { have(any { it.code == 2322 && it.message == "Type 'boolean' is not assignable to type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `an overloaded receiver without a this keeps its overloads`() {
        val d = d("declare function zzzOv2(x: string): number;\ndeclare function zzzOv2(x: number, y: string): boolean;\nconst zzzB = zzzOv2.bind(undefined);\nzzzB(\"s\");\nzzzB(1, \"s\");\nconst zzzBad: string = zzzB(\"s\");\nconst zzzBad2: string = zzzB(1, \"s\");\nconst zzzS: string = zzzOv2.bind(undefined);")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'boolean' is not assignable to type 'string'." })
            have(any { it.code == 2322 && it.message == "Type '{ (x: string): number; (x: number, y: string): boolean; }' is not assignable to type 'string'." })
        }
        assert(d.size == 3)
    }

    @Test
    fun `a partial on an overloaded receiver drops the last overload's leading parameter`() {
        val d = d("declare function zzzOv(this: ZzzT, x: string): number;\ndeclare function zzzOv(this: ZzzT, x: number, y: string): boolean;\nconst zzzB = zzzOv.bind(zzzO, 1);\nzzzB(\"s\");\nconst zzzBad: string = zzzB(\"s\");")
        d should { have(any { it.code == 2322 && it.message == "Type 'boolean' is not assignable to type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `a generic receiver with a this is erased to its constraints`() {
        val d = d("function zzzGen<T>(this: ZzzT, x: T): T { return x }\nconst zzzB = zzzGen.bind(zzzO);\nconst zzzBad: string = zzzB(1);\nconst zzzOk: number = zzzB(1);\nconst zzzS: string = zzzGen.bind(zzzO);")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'unknown' is not assignable to type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'unknown' is not assignable to type 'number'." })
            have(any { it.code == 2322 && it.message == "Type '(x: unknown) => unknown' is not assignable to type 'string'." })
        }
        assert(d.size == 3)
    }

    @Test
    fun `a generic receiver without a this keeps its type parameter`() {
        val d = d("function zzzId<T>(x: T): T { return x }\nconst zzzB = zzzId.bind(undefined);\nconst zzzBad: string = zzzB(1);\nconst zzzOk: number = zzzB(1);\nconst zzzS: string = zzzId.bind(undefined);")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
            have(any { it.code == 2322 && it.message == "Type '<T>(x: T) => T' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `a partial on a generic receiver is erased too`() {
        val d = d("function zzzGen<T>(this: ZzzT, x: T, y: T): T { return x }\nconst zzzB = zzzGen.bind(zzzO, 1);\nconst zzzBad: string = zzzB(2);\nzzzB(\"s\");\nconst zzzS: string = zzzGen.bind(zzzO, 1);")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'unknown' is not assignable to type 'string'." })
            have(any { it.code == 2322 && it.message == "Type '(y: unknown) => unknown' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `a rest-only receiver keeps its rest through bind and its partials`() {
        val d = d("function zzzR(this: ZzzT, ...xs: number[]): number { return xs.length }\nconst zzzB = zzzR.bind(zzzO);\nzzzB(1, 2);\nzzzB(\"s\");\nconst zzzB2 = zzzR.bind(zzzO, 1, 2);\nzzzB2(3);\nzzzB2(\"s\");\nconst zzzBad: string = zzzB2();\nconst zzzS: string = zzzR.bind(zzzO, 1);")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
            have(any { it.code == 2322 && it.message == "Type '(...args: number[]) => number' is not assignable to type 'string'." })
        }
        assert(d.count { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." } == 2)
        assert(d.size == 4)
    }

    @Test
    fun `a fixed and rest receiver splits at the partial count`() {
        val d = d("function zzzR2(this: ZzzT, x: string, ...xs: number[]): number { return xs.length }\nconst zzzB = zzzR2.bind(zzzO, \"s\");\nzzzB(1, 2);\nzzzB(\"s\");\nconst zzzB2 = zzzR2.bind(zzzO, \"s\", 1);\nzzzB2(2);\nzzzB2(\"s\");\nzzzR2.bind(zzzO, 1);")
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." }) }
        assert(d.count { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." } == 2)
        assert(d.size == 3)
    }

    @Test
    fun `an optional parameter keeps its optionality on both sides of the split`() {
        val d = d("function zzzOpt(this: ZzzT, x: string, y?: number): number { return x.length }\nconst zzzB = zzzOpt.bind(zzzO, \"s\");\nzzzB();\nzzzB(1);\nzzzB(\"s\");\nconst zzzB2 = zzzOpt.bind(zzzO);\nzzzB2(\"s\");\nzzzB2(\"s\", \"t\");\nconst zzzS: string = zzzOpt.bind(zzzO, \"s\");\nconst zzzS2: string = zzzOpt.bind(zzzO);")
        d should {
            have(any { it.code == 2322 && it.message == "Type '(y?: number | undefined) => number' is not assignable to type 'string'." })
            have(any { it.code == 2322 && it.message == "Type '(x: string, y?: number | undefined) => number' is not assignable to type 'string'." })
        }
        assert(d.count { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." } == 2)
        assert(d.size == 4)
    }

    @Test
    fun `an interface method reference binds through its signature`() {
        val d = d("interface ZzzH { m(x: string): number }\ndeclare const zzzh: ZzzH;\nconst zzzB = zzzh.m.bind(zzzh);\nzzzB(1);\nconst zzzBad: string = zzzB(\"x\");\nzzzh.m.bind(1);")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `a class method reference binds through its signature`() {
        val d = d("class ZzzC { n = 1; m(x: string): number { return this.n + x.length } }\nconst zzzc = new ZzzC();\nconst zzzB = zzzc.m.bind(zzzc);\nzzzB(1);\nconst zzzBad: string = zzzB(\"x\");")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `a callable interface receiver displays its name as the first overload's this`() {
        val d = d("interface ZzzFn { (this: ZzzT, x: string): number }\ndeclare const zzzfn: ZzzFn;\nconst zzzB = zzzfn.bind(zzzO);\nzzzB(1);\nconst zzzBad: string = zzzB(\"x\");\nzzzfn.bind(undefined);")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
            have(any {
                it.code == 2769 && it.messageChain == listOf(
                    "  Overload 1 of 2, '(this: ZzzFn, thisArg: ZzzT): (x: string) => number', gave the following error.",
                    "    Argument of type 'undefined' is not assignable to parameter of type 'ZzzT'.",
                    twoOfTwo,
                    "    Argument of type 'undefined' is not assignable to parameter of type 'ZzzT'.",
                )
            })
        }
        assert(d.size == 3)
    }

    @Test
    fun `Object prototype hasOwnProperty bind types boolean`() {
        val d = d("const zzzHas = Object.prototype.hasOwnProperty.bind(zzzO);\nconst zzzS: string = zzzHas(\"m\");\nconst zzzOk: boolean = zzzHas(\"m\");")
        d should { have(any { it.code == 2322 && it.message == "Type 'boolean' is not assignable to type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `a class value binds to a constructor without its leading parameters`() {
        val d = d("class ZzzK { constructor(x: string, y: number) {} }\nconst zzzBK = ZzzK.bind(null);\nnew zzzBK(1, 2);\nconst zzzBK2 = ZzzK.bind(null, \"s\");\nnew zzzBK2(\"t\");\nconst zzzS: string = ZzzK.bind(null, \"s\");")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." })
            have(any { it.code == 2322 && it.message == "Type 'new (y: number) => ZzzK' is not assignable to type 'string'." })
        }
        assert(d.size == 3)
    }

    @Test
    fun `a Promise result keeps its type argument through bind`() {
        val d = d("function zzzAsync(this: ZzzT, x: string): Promise<number> { return Promise.resolve(1) }\nconst zzzB = zzzAsync.bind(zzzO);\nconst zzzBad: Promise<string> = zzzB(\"x\");")
        d should {
            have(any {
                it.code == 2322 && it.message == "Type 'Promise<number>' is not assignable to type 'Promise<string>'." &&
                    it.messageChain == listOf("  Type 'number' is not assignable to type 'string'.")
            })
        }
        assert(d.size == 1)
    }

    // ------------------------------------------------------------------ the built signature as a value

    @Test
    fun `the bound result relates to a declared function type by its parameters`() {
        val d = d("const zzzD: (x: string) => number = zzzF.bind(zzzO);\nconst zzzE: (x: number) => number = zzzF.bind(zzzO);\nconst zzzE2: (this: ZzzT, x: string) => number = zzzF.bind(zzzO);")
        d should {
            have(any {
                it.code == 2322 && it.message == "Type '(x: string) => number' is not assignable to type '(x: number) => number'." &&
                    it.messageChain == listOf(
                        "  Types of parameters 'x' and 'x' are incompatible.",
                        "    Type 'number' is not assignable to type 'string'.",
                    )
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `the bound result is checked as a callback argument`() {
        val d = d("declare function zzzTake(cb: (x: string) => number): void;\nzzzTake(zzzF.bind(zzzO));\ndeclare function zzzTake2(cb: (x: number) => number): void;\nzzzTake2(zzzF.bind(zzzO));")
        d should {
            have(any {
                it.code == 2345 && it.message == "Argument of type '(x: string) => number' is not assignable to parameter of type '(x: number) => number'." &&
                    it.messageChain == listOf(
                        "  Types of parameters 'x' and 'x' are incompatible.",
                        "    Type 'number' is not assignable to type 'string'.",
                    )
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `a bound result re-bound is itself`() {
        val d = d("const zzzB = zzzF.bind(zzzO);\nconst zzzBB = zzzB.bind(undefined);\nconst zzzBad: string = zzzBB(\"x\");\nconst zzzS: string = zzzBB;")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
            have(any { it.code == 2322 && it.message == "Type '(x: string) => number' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `negative control - an any or Function receiver keeps any`() {
        val d = d("declare const zzzAny: any;\nconst zzzB = zzzAny.bind(zzzO);\nconst zzzS: string = zzzB;\ndeclare const zzzFn: Function;\nconst zzzB2 = zzzFn.bind(zzzO);\nconst zzzS2: string = zzzB2;")
        assert(d.isEmpty())
    }

    // ------------------------------------------------------------------ the option and the embedded lib

    @Test
    fun `negative control - a non-strict project takes the loose Function bind and is silent`() {
        val d = d("const zzzBad: string = zzzF.bind(zzzO)(\"x\");\nzzzF.bind(zzzO)(1);\nzzzF.bind(undefined);\nzzzF.bind(zzzO, 1);", directives = "// @strict: false\n// @useRealLibs: true")
        assert(d.isEmpty())
    }

    @Test
    fun `strictBindCallApply false under strict takes the loose half for bind`() {
        val d = d("zzzF.bind(zzzO)(1);\nzzzF.bind(undefined);\nzzzF.bind(zzzO, 1);", directives = "// @strict: true\n// @strictBindCallApply: false\n// @useRealLibs: true")
        assert(d.isEmpty())
    }

    @Test
    fun `strictBindCallApply true under strict false takes the checked half for bind`() {
        val d = d("zzzF.bind(zzzO)(1);\nzzzF.bind(zzzO, 1);", directives = "// @strict: false\n// @strictBindCallApply: true\n// @useRealLibs: true")
        assert(d.count { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." } == 2)
        assert(d.size == 2)
    }

    @Test
    fun `the embedded lib types bind exactly like the real one`() {
        // The synthesis reads the RECEIVER, never the lib's `CallableFunction` text — the
        // embedded lib has none — so the corpus half answers the same rows.
        val d = d("zzzF.bind(zzzO)(1);\nconst zzzB = zzzF.bind(zzzO, \"x\");\nconst zzzBad: string = zzzB();\nzzzF.bind(undefined);", directives = "// @strict: true")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
            have(any {
                it.code == 2769 && it.messageChain == listOf(
                    oneOfTwo,
                    "    Argument of type 'undefined' is not assignable to parameter of type 'ZzzT'.",
                    twoOfTwo,
                    "    Argument of type 'undefined' is not assignable to parameter of type 'ZzzT'.",
                )
            })
        }
        assert(d.size == 3)
    }

    // ------------------------------------------------------------------ residues

    @Test
    fun `residue - a call through a variable holding the bound function is not arity-checked`() {
        // Both references: TS2554 `Expected 0 arguments, but got 1.` at `zzzB("x")` and
        // `Expected 2 arguments, but got 1.` at `zzzBv("s")` (where we report the argument
        // instead) — (CHK.97)'s general variable-callee arity gap, identical for
        // `declare const kk: (a: number, b: string) => void; kk("x")`.
        val d = d("const zzzB = zzzF.bind(zzzO, \"x\");\nzzzB(\"x\");\ndeclare function zzzOv(this: ZzzT, x: string): number;\ndeclare function zzzOv(this: ZzzT, x: number, y: string): boolean;\nconst zzzBv = zzzOv.bind(zzzO);\nzzzBv(\"s\");")
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `residue - an inline call of a bind call is untyped at a declaration reader`() {
        // Both references: TS2322 at `zzzBad` — the general call-of-call gap
        // (`declare function mk(): (x: string) => number; const s: string = mk()("x")` is
        // silent too); the two-step form reports.
        val d = d("const zzzBad: string = zzzF.bind(zzzO)(\"x\");")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - a bound function re-bound is not argument-checked when called`() {
        // Both references: TS2345 at `zzzBB(1)`. `spineArithRecordVarDecl`'s callable-shadow
        // arm first-touches `zzzBB`'s symbol under an ambient that reads `zzzB` as `any`
        // and the answer persists — `declare function mk(): () => (x: string) => number;
        // const p = mk(); const q = p(); q(1)` is silent for the same reason.
        val d = d("const zzzB = zzzF.bind(zzzO);\nconst zzzBB = zzzB.bind(undefined);\nzzzBB(1);")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - an optional-chain receiver is silent because the receiver types any`() {
        // Both references: TS2322 `Type '((x: string) => number) | undefined' is not
        // assignable to type 'number'.` — the (CHK.133)(c) optional-chain residue.
        val d = d("declare const zzzMaybe: ((this: ZzzT, x: string) => number) | undefined;\nconst zzzB = zzzMaybe?.bind(zzzO);\nconst zzzBad: number = zzzB;")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - a union receiver is not distributed`() {
        // Both references: TS2322 `Type 'string | number' is not assignable to type
        // 'boolean'.` — `OmitThisParameter` distributes over the union; the miss keeps `any`.
        val d = d("declare const zzzU: ((this: ZzzT, x: string) => number) | ((this: ZzzT, x: string) => string);\nconst zzzB = zzzU.bind(zzzO);\nconst zzzBad: boolean = zzzB(\"x\");")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - a spread partial keeps any`() {
        // Both references: TS2345 at `zzzB(1)` (`A` inferred from the spread tuple).
        val d = d(three + "declare const zzzArr: [string, number];\nconst zzzB = zzzF3.bind(zzzO, ...zzzArr);\nzzzB(true);\nzzzB(1);")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - a bare bind read keeps any`() {
        // Both references render the lib's generic method: `Type '{ <T>(this: T, thisArg:
        // ThisParameterType<T>): OmitThisParameter<T>; <T, A extends any[], B extends
        // any[], R>(this: (this: T, ...args: [...A, ...B]) => R, thisArg: T, ...args: A):
        // (...args: B) => R; }' is not assignable to type 'string'.`
        val d = d("const zzzS: string = zzzF.bind;")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - a class value bound without partials displays its instance name`() {
        // Both references: `Type 'typeof ZzzK' is not assignable to type 'string'.` — the
        // class-value receiver reaches the miss path as the type this checker displays
        // `ZzzK` ((CHK.73)'s class-value model); the bound constructor itself is right.
        val d = d("class ZzzK { constructor(x: string) {} }\nconst zzzBK = ZzzK.bind(null);\nnew zzzBK(1);\nconst zzzBad: string = ZzzK.bind(null);")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'ZzzK' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `residue - a class this thisArg chain drills a mismatched member before the missing one`() {
        // Pristine's chain under each overload: `Argument of type 'ZzzT' is not assignable
        // to parameter of type 'ZzzC'.` / `Property 'n' is missing in type 'ZzzT' but
        // required in type 'ZzzC'.` — the overload-chain elaboration (B560) drills the
        // mismatched `m` first; the ordinary `zzzc.m.call(zzzO, "x")` prints pristine's line.
        val d = d("class ZzzC { n = 1; m(this: ZzzC, x: string): number { return this.n + x.length } }\nconst zzzc = new ZzzC();\nzzzc.m.bind(zzzO);")
        d should {
            have(any {
                it.code == 2769 && it.messageChain == listOf(
                    "  Overload 1 of 2, '(this: (this: ZzzC, x: string) => number, thisArg: ZzzC): (x: string) => number', gave the following error.",
                    "    Argument of type 'ZzzT' is not assignable to parameter of type 'ZzzC'.",
                    "      Types of property 'm' are incompatible.",
                    "        Type 'string' is not assignable to type '(this: ZzzC, x: string) => number'.",
                    "  Overload 2 of 2, '(this: (this: ZzzC, x: string) => number, thisArg: ZzzC): (x: string) => number', gave the following error.",
                    "    Argument of type 'ZzzT' is not assignable to parameter of type 'ZzzC'.",
                    "      Types of property 'm' are incompatible.",
                    "        Type 'string' is not assignable to type '(this: ZzzC, x: string) => number'.",
                )
            })
        }
        assert(d.size == 1)
    }
}

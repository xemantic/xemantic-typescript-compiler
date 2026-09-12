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
 * (CHK.134) decomposition (1) — `f.call(thisArg, ...args)` and `f.apply(thisArg, args)`
 * on a function VALUE, typed as tsc types them. Every diagnostic row below was measured
 * against tsgo 7.0.2 AND pristine `typescript@6.0.3` before any code was written
 * (`scripts/ref_matrix.py`, 55 fixtures, zero REF-SPLIT rows on the shapes pinned here);
 * the expected text is pristine's byte for byte, chain included.
 *
 * THE MECHANISM. A function-shaped receiver's `call`/`apply` used to type `any`:
 * `getApparentType` has no function-object arm and the member miss answered `anyType`
 * silently. tsc augments the property lookup ON A MISS with `globalCallableFunctionType`
 * (checker.ts `getPropertyOfType`), and `CallableFunction.call<T, A extends any[], R>(this:
 * (this: T, ...args: A) => R, thisArg: T, ...args: A): R` is inference whose only candidate
 * for every type parameter is the RECEIVER — `T` its `this` type, `A` its parameter list,
 * `R` its return. [Checker.functionObjectMemberType] therefore BUILDS the instantiated
 * member from the receiver's own (last, erased) signature — `call` as `(thisArg: T, <the
 * receiver's parameters>) => R` (tsc's expanded rest tuple, which is what its arity
 * messages count), `apply` as the arity-matching half of the lib's overload pair, its
 * `args` the receiver's parameter list as a LABELED tuple — and hands it to the ordinary
 * single-signature call machinery. The lib's declaration text is never consulted, which is
 * why the EMBEDDED lib (whose `Function` has no `this:` and no `CallableFunction`) types
 * exactly like the real one — every corpus baseline was produced with the real one.
 *
 * THE OPTION. `strictBindCallApply` is now a `CompilerOptions` field with tsc's
 * `getStrictOptionValue` default (`strict` when unset). tsc's own sources set it `false`
 * explicitly, so all eight dashboard profiles take the loose `Function.call` half (result
 * and arguments `any` — the silence this compiler always had); the first build without
 * the option manufactured one false positive per profile (`utilities.ts:11201`,
 * `stringReplace.call(s, "*", replacement)` against the LAST `String.replace` overload),
 * which is exactly what the 8-profile grid gates.
 *
 * MEASURED REACH: 28-31 `call` and 1-4 `apply` misses per profile, all loose; `marked`
 * (strict) resolves 14 sites at 18 -> 18 rows; cronstrue and the 2,400-file project
 * reach none. So the grid is a CONTROL for the synthesis and `marked` plus these pins
 * are its gate.
 *
 * RECORDED residues, each measured and pinned `residue -` below (`bind` was sub-step 2's
 * residue and is closed — `FunctionBindTest`): an optional-chain receiver (`f?.call`, the
 * (CHK.133)(c) residue — the receiver types `any`); an anonymous-object IDENTIFIER or a
 * class `this` as `thisArg` against an interface `this` type (the argument firewall's
 * pre-existing silence, `ctl1`/`ctl11` in the round's matrix, identical for an ordinary
 * call); a spread of a plain array into the expanded parameters (TS2556 is emitted from
 * a DECLARATION's parameter info, which a synthesized signature has none of); a
 * primitive against a one-element tuple `[x: string]` (a pre-existing relation
 * leniency, identical for an ordinary call); the display of a bare `f.call` read (tsc
 * renders the generic method).
 */
class FunctionCallApplyTest {

    private val prelude = """
        interface ZzzT { m: string }
        declare const zzzO: ZzzT;
        function zzzF(this: ZzzT, x: string): number { return x.length + this.m.length }
    """.trimIndent()

    private val strictReal = "// @strict: true\n// @useRealLibs: true"

    private fun d(src: String, directives: String = strictReal) =
        diagnose(prelude + "\n" + src + "\nexport {};", directives = directives)

    // ------------------------------------------------------------------ call

    @Test
    fun `negative control - a legal call with a matching thisArg and argument is silent`() {
        val d = d("zzzF.call(zzzO, \"x\");\nconst zzzN: number = zzzF.call(zzzO, \"x\");")
        assert(d.isEmpty())
    }

    @Test
    fun `a wrong argument through call is TS2345 against the receiver's parameter`() {
        val d = d("zzzF.call(zzzO, 1);")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
        }
        assert(d.size == 1)
    }

    @Test
    fun `an unassignable thisArg is TS2345 against the receiver's this type`() {
        val d = d("zzzF.call(undefined, \"x\");")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'undefined' is not assignable to parameter of type 'ZzzT'." })
        }
        assert(d.size == 1)
    }

    @Test
    fun `an unassignable object-literal thisArg elaborates at the property`() {
        val d = d("zzzF.call({ m: 1 }, \"x\");")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        assert(d.size == 1)
    }

    @Test
    fun `negative control - an object-literal thisArg with an extra property is the inferred T and silent`() {
        // tsc's T is the covariant candidate when it is a subtype of the receiver's `this`,
        // so the literal is checked against its own widened type: no excess-property row.
        val d = d("zzzF.call({ m: \"s\", extra: 1 }, \"x\");")
        assert(d.isEmpty())
    }

    @Test
    fun `too few arguments through call counts the expanded parameters`() {
        val d = d("zzzF.call(zzzO);")
        d should { have(any { it.code == 2554 && it.message == "Expected 2 arguments, but got 1." }) }
        assert(d.size == 1)
    }

    @Test
    fun `too many arguments through call counts the expanded parameters`() {
        val d = d("zzzF.call(zzzO, \"x\", 2);")
        d should { have(any { it.code == 2554 && it.message == "Expected 2 arguments, but got 3." }) }
        assert(d.size == 1)
    }

    @Test
    fun `a call with no arguments reports the uninstantiated rest candidate`() {
        val d = d("zzzF.call();")
        d should { have(any { it.code == 2555 && it.message == "Expected at least 1 arguments, but got 0." }) }
        assert(d.size == 1)
    }

    @Test
    fun `the result of call is the receiver's return type`() {
        val d = d("const zzzBad: string = zzzF.call(zzzO, \"x\");")
        d should { have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `a Promise result of call keeps its type argument`() {
        val d = d("declare function zzzAsync(this: { m: string }, x: string): Promise<number>;\nconst zzzP: Promise<number> = zzzAsync.call(zzzO, \"x\");\nconst zzzBad: Promise<string> = zzzAsync.call(zzzO, \"x\");")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'Promise<number>' is not assignable to type 'Promise<string>'." })
        }
        assert(d.size == 1)
    }

    @Test
    fun `a this void receiver refuses an object thisArg and accepts undefined`() {
        val d = d("function zzzV(this: void, x: string): number { return x.length }\nzzzV.call(zzzO, \"x\");\nzzzV.call(undefined, \"x\");\nzzzV.call(undefined, 1);")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'ZzzT' is not assignable to parameter of type 'void'." })
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `a receiver without a this type takes any thisArg`() {
        val d = d("function zzzPlain(x: string): number { return x.length }\nzzzPlain.call(zzzO, \"x\");\nzzzPlain.call(undefined, 1);\nzzzPlain.call(1, \"x\");\nconst zzzBad: string = zzzPlain.call(null, \"x\");")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `an arrow receiver is called through its own signature`() {
        val d = d("const zzzArrow = (x: string) => x.length;\nzzzArrow.call(zzzO, \"x\");\nzzzArrow.call(zzzO, 1);\nconst zzzBad: string = zzzArrow.call(zzzO, \"x\");")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `an overloaded receiver is called through its last overload`() {
        val d = d("function zzzOv(x: string): string;\nfunction zzzOv(x: number): number;\nfunction zzzOv(x: any) { return x }\nzzzOv.call(zzzO, \"x\");\nzzzOv.call(zzzO, 1);\nconst zzzR: string = zzzOv.call(zzzO, 1);\nzzzOv.call(zzzO, true);")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." })
            have(any { it.code == 2345 && it.message == "Argument of type 'boolean' is not assignable to parameter of type 'number'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        assert(d.size == 3)
    }

    @Test
    fun `a generic receiver is erased to its constraints`() {
        val d = d("function zzzGen<T>(x: T): T { return x }\nconst zzzR: number = zzzGen.call(zzzO, 1);\nzzzGen.call(zzzO);")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'unknown' is not assignable to type 'number'." })
            have(any { it.code == 2554 && it.message == "Expected 2 arguments, but got 1." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `Object prototype hasOwnProperty call types boolean`() {
        val d = d("const zzzB: boolean = Object.prototype.hasOwnProperty.call(zzzO, \"k\");\nconst zzzS: string = Object.prototype.hasOwnProperty.call(zzzO, \"k\");\nif (Object.prototype.hasOwnProperty.call(zzzO, \"k\")) {}")
        d should { have(any { it.code == 2322 && it.message == "Type 'boolean' is not assignable to type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `a class method reference is called through its signature`() {
        val d = d("class ZzzC { m(x: number): string { return String(x) } }\ndeclare const zzzOther: { q: number };\nconst zzzI = new ZzzC();\nzzzI.m.call(zzzOther, 1);\nzzzI.m.call(zzzOther, \"x\");\nconst zzzBad: number = zzzI.m.call(zzzOther, 1);")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." })
            have(any { it.code == 2322 && it.message == "Type 'string' is not assignable to type 'number'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `a callback parameter is called through its signature and returns void`() {
        val d = d("function zzzTake(cb: (a: number) => void) {\n  cb.call(undefined, 1);\n  cb.call(undefined, \"x\");\n  const zzzBad: string = cb.call(undefined, 1);\n}")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." })
            have(any { it.code == 2322 && it.message == "Type 'void' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `an element-access receiver of a function map is called through its signature`() {
        val d = d("declare const zzzMap: { [k: string]: (x: number) => void };\nzzzMap[\"a\"].call(undefined, 1);\nzzzMap[\"a\"].call(undefined, \"x\");")
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `an optional and rest receiver keeps its optionality and rest through call`() {
        val d = d("function zzzOpt(this: ZzzT, x: string, y?: number, ...rest: boolean[]): void {}\nzzzOpt.call(zzzO, \"x\");\nzzzOpt.call(zzzO, \"x\", 1, true, false);\nzzzOpt.call(zzzO, \"x\", \"y\");\nzzzOpt.call(zzzO, \"x\", 1, 2);\nzzzOpt.call(zzzO);")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." })
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'boolean'." })
            have(any { it.code == 2555 && it.message == "Expected at least 2 arguments, but got 1." })
        }
        assert(d.size == 3)
    }

    @Test
    fun `a rest-only receiver takes its rest through call`() {
        val d = d("function zzzRest(this: void, ...xs: number[]): number { return xs.length }\nzzzRest.call(undefined, 1, 2, 3);\nzzzRest.call(undefined, 1, \"x\");")
        d should { have(any { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type 'number'." }) }
        assert(d.size == 1)
    }

    // ------------------------------------------------------------------ apply

    @Test
    fun `negative control - apply with a matching tuple is silent`() {
        val d = d("zzzF.apply(zzzO, [\"x\"]);\nconst zzzN: number = zzzF.apply(zzzO, [\"x\"]);")
        assert(d.isEmpty())
    }

    @Test
    fun `apply elaborates a wrong array element against the receiver's parameter`() {
        val d = d("zzzF.apply(zzzO, [1]);")
        d should { have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `apply with too many tuple elements is TS2345 against the labeled parameter tuple`() {
        val d = d("zzzF.apply(zzzO, [\"x\", 2]);")
        d should {
            have(any {
                it.code == 2345 &&
                    it.message == "Argument of type '[string, number]' is not assignable to parameter of type '[x: string]'." &&
                    it.messageChain == listOf("  Source has 2 element(s) but target allows only 1.")
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `apply with too few tuple elements is TS2345 against the labeled parameter tuple`() {
        val d = d("function zzzTwo(this: { m: string }, a: string, b: number): boolean { return true }\nzzzTwo.apply(zzzO, [\"x\"]);\nconst zzzBad: string = zzzTwo.apply(zzzO, [\"x\", 1]);")
        d should {
            have(any {
                it.code == 2345 &&
                    it.message == "Argument of type '[string]' is not assignable to parameter of type '[a: string, b: number]'." &&
                    it.messageChain == listOf("  Source has 1 element(s) but target requires 2.")
            })
            have(any { it.code == 2322 && it.message == "Type 'boolean' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `apply with one argument on a receiver that needs arguments is TS2684 with the arity chain`() {
        val d = d("zzzF.apply(zzzO);")
        d should {
            have(any {
                it.code == 2684 &&
                    it.message == "The 'this' context of type '(this: ZzzT, x: string) => number' is not assignable to method's 'this' of type '(this: ZzzT) => number'." &&
                    it.messageChain == listOf("  Target signature provides too few arguments. Expected 1 or more, but got 0.")
            })
        }
        assert(d.size == 1)
    }

    @Test
    fun `apply arity outside the overload pair reports the pair's range`() {
        val d = d("zzzF.apply();\nzzzF.apply(zzzO, [\"x\"], 3);")
        d should {
            have(any { it.code == 2554 && it.message == "Expected 1-2 arguments, but got 0." })
            have(any { it.code == 2554 && it.message == "Expected 1-2 arguments, but got 3." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `apply on a no-argument receiver takes the first overload and an empty tuple`() {
        val d = d("function zzzNoArgs(this: { m: string }): number { return 1 }\nzzzNoArgs.apply(zzzO);\nzzzNoArgs.apply(zzzO, []);\nzzzNoArgs.apply(zzzO, [1]);\nzzzNoArgs.apply(zzzO, \"x\");\nconst zzzBad: string = zzzNoArgs.apply(zzzO);")
        d should {
            have(any {
                it.code == 2345 &&
                    it.message == "Argument of type '[number]' is not assignable to parameter of type '[]'." &&
                    it.messageChain == listOf("  Source has 1 element(s) but target allows only 0.")
            })
            have(any { it.code == 2345 && it.message == "Argument of type 'string' is not assignable to parameter of type '[]'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        assert(d.size == 3)
    }

    @Test
    fun `apply on an all-optional receiver accepts one argument and any matching tuple`() {
        val d = d("declare const zzzAllOpt: (this: { m: string }, x?: string) => number;\nzzzAllOpt.apply(zzzO);\nzzzAllOpt.apply(zzzO, [\"x\"]);\nzzzAllOpt.apply(zzzO, []);")
        assert(d.isEmpty())
    }

    @Test
    fun `apply on a rest-only receiver takes its array type`() {
        val d = d("function zzzRest(this: void, ...xs: number[]): number { return xs.length }\ndeclare const zzzNums: number[];\nzzzRest.apply(undefined, zzzNums);\nzzzRest.apply(undefined, [\"x\"]);\nconst zzzS: string = String.fromCharCode.apply(null, zzzNums);\nconst zzzBad: number = String.fromCharCode.apply(null, zzzNums);")
        d should {
            have(any { it.code == 2322 && it.message == "Type 'string' is not assignable to type 'number'." })
        }
        assert(d.size == 2)
        assert(d.all { it.code == 2322 })
    }

    @Test
    fun `apply elaborates an optional and rest receiver's tuple element-wise`() {
        val d = d("function zzzOpt(this: ZzzT, x: string, y?: number, ...rest: boolean[]): void {}\nzzzOpt.apply(zzzO, [\"x\"]);\nzzzOpt.apply(zzzO, [\"x\", 1, true]);\nzzzOpt.apply(zzzO, [\"x\", \"y\"]);")
        d should { have(any { it.code == 2322 && it.message == "Type 'string' is not assignable to type 'number'." }) }
        assert(d.size == 1)
    }

    @Test
    fun `an unassignable thisArg through apply elaborates at the property`() {
        val d = d("zzzF.apply({ m: 1 }, [\"x\"]);")
        d should { have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." }) }
        assert(d.size == 1)
    }

    // ------------------------------------------------------------------ the option and the libs

    @Test
    fun `negative control - a non-strict project takes the loose Function members and is silent`() {
        val d = d("zzzF.call(zzzO, 1);\nconst zzzBad: string = zzzF.call(zzzO, \"x\");\nzzzF.apply(zzzO, [1]);\nzzzF.call({ m: 1 }, \"x\");", directives = "// @strict: false\n// @useRealLibs: true")
        assert(d.isEmpty())
    }

    @Test
    fun `strictBindCallApply false under strict takes the loose half`() {
        val d = d("zzzF.call(zzzO, 1);\nconst zzzBad: string = zzzF.call(zzzO, \"x\");\nzzzF.apply(zzzO, [1]);", directives = "// @strict: true\n// @strictBindCallApply: false\n// @useRealLibs: true")
        assert(d.isEmpty())
    }

    @Test
    fun `strictBindCallApply true under strict false takes the checked half`() {
        val d = d("zzzF.call(zzzO, 1);\nconst zzzBad: string = zzzF.call(zzzO, \"x\");\nzzzF.apply(zzzO, [1]);", directives = "// @strict: false\n// @strictBindCallApply: true\n// @useRealLibs: true")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
        }
        assert(d.size == 3)
        assert(d.count { it.code == 2322 } == 2)
    }

    @Test
    fun `the embedded lib types call and apply exactly like the real one`() {
        // The synthesis reads the RECEIVER, never the lib's `CallableFunction` text — the
        // embedded lib has none — so the corpus half answers the same rows.
        val d = d("zzzF.call(zzzO, 1);\nconst zzzBad: string = zzzF.call(zzzO, \"x\");\nzzzF.apply(zzzO, [\"x\", 2]);\nzzzF.apply(zzzO);", directives = "// @strict: true")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
            have(any {
                it.code == 2345 &&
                    it.message == "Argument of type '[string, number]' is not assignable to parameter of type '[x: string]'." &&
                    it.messageChain == listOf("  Source has 2 element(s) but target allows only 1.")
            })
            have(any { it.code == 2684 && it.message == "The 'this' context of type '(this: ZzzT, x: string) => number' is not assignable to method's 'this' of type '(this: ZzzT) => number'." })
        }
        assert(d.size == 4)
    }

    @Test
    fun `the Function members of a function value read the lib interface`() {
        val d = d("const zzzLen: number = zzzF.length;\nconst zzzStr: string = zzzF.toString();\nconst zzzName: string = zzzF.name;\nconst zzzBad: number = zzzF.name;")
        d should { have(any { it.code == 2322 && it.message == "Type 'string' is not assignable to type 'number'." }) }
        assert(d.size == 1)
    }

    // ------------------------------------------------------------------ neighbours the round fixed on the way

    @Test
    fun `a labeled tuple displays its labels`() {
        val d = d("declare const zzzT2: [x: string];\nconst zzzN: number = zzzT2;\ndeclare const zzzRO: readonly [x: string];\nconst zzzN2: number = zzzRO;")
        d should {
            have(any { it.code == 2322 && it.message == "Type '[x: string]' is not assignable to type 'number'." })
            have(any { it.code == 2322 && it.message == "Type 'readonly [x: string]' is not assignable to type 'number'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `an optional tuple element displays tsc's optional form under strictNullChecks`() {
        val d = d("declare const zzzOT: [string, number?];\nconst zzzN: number = zzzOT;\ndeclare const zzzRT: [a: string, b?: number, ...c: boolean[]];\nconst zzzN2: number = zzzRT;")
        d should {
            have(any { it.code == 2322 && it.message == "Type '[string, (number | undefined)?]' is not assignable to type 'number'." })
            have(any { it.code == 2322 && it.message == "Type '[a: string, b?: number | undefined, ...c: boolean[]]' is not assignable to type 'number'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `too few arguments to a rest method is TS2555`() {
        val d = d("declare const zzzOm: { m(x: string, ...r: number[]): void };\nzzzOm.m();")
        d should { have(any { it.code == 2555 && it.message == "Expected at least 1 arguments, but got 0." }) }
        assert(d.size == 1)
    }

    // ------------------------------------------------------------------ residues

    @Test
    fun `residue - bind is still any`() {
        // (CHK.134)(2) closed the residue this pin was named for; the name is kept
        // ((CHK.114)) and the expectation is both references': TS2345 at `zzzB(1)` and
        // TS2322 at `zzzBad` — `FunctionBindTest` carries the family.
        val d = d("const zzzB = zzzF.bind(zzzO);\nzzzB(\"x\");\nzzzB(1);\nconst zzzBad: string = zzzB(\"x\");")
        d should {
            have(any { it.code == 2345 && it.message == "Argument of type 'number' is not assignable to parameter of type 'string'." })
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'string'." })
        }
        assert(d.size == 2)
    }

    @Test
    fun `residue - an optional-chain receiver is silent because the receiver types any`() {
        // Both references: TS2345 at `zzzMaybe?.call(zzzO, 1)` and TS2322 on the
        // `number | undefined` result — the (CHK.133)(c) optional-chain residue.
        val d = d("declare const zzzMaybe: ((this: ZzzT, x: string) => number) | undefined;\nzzzMaybe?.call(zzzO, 1);\nconst zzzBad: number = zzzMaybe?.call(zzzO, \"x\");")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - an anonymous-object identifier thisArg is not checked against an interface`() {
        // Both references: TS2345 `Argument of type '{ m: number; }' is not assignable to
        // parameter of type 'ZzzT'.` with the property chain; the argument firewall is
        // silent on the identical ordinary call `take(zzzBad)` too.
        val d = d("declare const zzzBad: { m: number };\nzzzF.call(zzzBad, \"x\");")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - a spread array into the expanded parameters is not TS2556`() {
        // Both references: TS2556 at `...zzzArr2` (a plain array spread into fixed
        // parameters); the emitter reads a DECLARATION's parameter info.
        val d = d("declare const zzzArr2: string[];\nzzzF.call(zzzO, ...zzzArr2);\ndeclare const zzzArgs: [string];\nzzzF.call(zzzO, ...zzzArgs);")
        assert(d.isEmpty())
    }

    @Test
    fun `residue - a primitive against a one-element labeled tuple through apply is silent`() {
        // Both references: TS2345 `Argument of type 'string' is not assignable to parameter
        // of type '[x: string]'.`; the identical ordinary call `zzzTup("x")` is silent too.
        val d = d("zzzF.apply(zzzO, \"x\");")
        assert(d.isEmpty())
    }
}

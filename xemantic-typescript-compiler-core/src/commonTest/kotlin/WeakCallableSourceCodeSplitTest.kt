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
 * (CHK.58) TS2560 *Did you mean to call it?* IS NOT "THE SOURCE IS CALLABLE" — IT IS
 * "CALLING IT WOULD HAVE WORKED".
 *
 * [Checker.tryEmitWeakTypeAssignment] chose the code by asking whether the source had
 * ANY call or construct signature, so every callable source took TS2560. tsc's
 * `reportErrorResults` asks a different question: is the FIRST call signature's return
 * type — or, failing that, the first CONSTRUCT signature's — related to the target?
 * Only then does it offer the *did you mean to call it* wording; everything else takes
 * the plain TS2559 sentence. **Four of the six shapes below carried the wrong CODE.**
 *
 * The corpus cannot see any of it: pristine's `weakType.errors.txt` — the one ACTIVE
 * baseline with TS2560 rows — carries only sources whose call result IS assignable, so
 * every row there is 2560 either way. The last pin is that shape, as a control.
 *
 * **THE RELATION ASKED MUST CARRY THE WEAK RULE ITSELF.** tsc's weak check lives inside
 * `isRelatedTo`, so `number` is not related to a weak object there where our relation
 * accepts it vacuously; without the [Checker.weakParamRefusesArg] veto in front,
 * `() => number` keeps the wrong code. That is the one line no shape-level reading of
 * tsc's source would have produced.
 *
 * Every expected value was read out of tsc 7.0.2 over the byte-identical fixture
 * (`build/chk58/ora2/w1.ts` … `w8.ts`); [Diagnostic.character] is the 1-based column.
 */
class WeakCallableSourceCodeSplitTest {

    private val prelude = """
        interface ZzzS { zzzT?: number; zzzE?(): void }
        declare function zzzF(s: ZzzS): void
    """.trimIndent() + "\n"

    /**
     * A call result that is NOT related to the weak target — tsc 7.0.2 over `w1.ts`:
     * `w1.ts(4,7): error TS2559: Type '() => number' has no properties in common with
     * type 'ZzzS1'.` This is the row the weak-rule veto buys: our plain relation
     * accepts `number` against an all-optional target, so asking [Checker.checkTypeRelatedTo]
     * alone would keep TS2560 here.
     */
    @Test
    fun `a callable whose result is disjoint from the weak target reports TS2559`() {
        val d = diagnose("""
            interface ZzzS1 { zzzT?: number; zzzE?(): void }
            declare function zzzF1(s: ZzzS1): void;
            declare const zzzG1: () => number;
            zzzF1(zzzG1);
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '() => number' has no properties in common with type 'ZzzS1'.")
        assert(d[0].line == 4)
        assert(d[0].character == 7)
    }

    /**
     * A call result that IS related — tsc 7.0.2 over `w2.ts`: `w2.ts(4,7): error
     * TS2560: Value of type '() => { zzzT: number; }' has no properties in common with
     * type 'ZzzS2'. Did you mean to call it?`, with the related TS6212.
     */
    @Test
    fun `a callable whose result satisfies the weak target reports TS2560`() {
        val d = diagnose("""
            interface ZzzS2 { zzzT?: number; zzzE?(): void }
            declare function zzzF2(s: ZzzS2): void;
            declare const zzzG2: () => { zzzT: number };
            zzzF2(zzzG2);
        """)
        assert(d.map { it.code } == listOf(2560))
        assert(d[0].message ==
            "Value of type '() => { zzzT: number; }' has no properties in common with " +
            "type 'ZzzS2'. Did you mean to call it?")
        assert(d[0].line == 4)
        assert(d[0].character == 7)
        assert(d[0].relatedInformation.map { it.code } == listOf(6212))
    }

    /**
     * The result is an OBJECT and still disjoint — so the split is about the RESULT's
     * relation to the target and not about "the result is an object". tsc 7.0.2 over
     * `w3.ts`: `w3.ts(4,7): error TS2559: Type '() => { zzzZ: string; }' …`.
     */
    @Test
    fun `a callable returning a disjoint object still reports TS2559`() {
        val d = diagnose("""
            interface ZzzS3 { zzzT?: number; zzzE?(): void }
            declare function zzzF3(s: ZzzS3): void;
            declare const zzzG3: () => { zzzZ: string };
            zzzF3(zzzG3);
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message ==
            "Type '() => { zzzZ: string; }' has no properties in common with type 'ZzzS3'.")
        assert(d[0].line == 4)
        assert(d[0].character == 7)
    }

    /**
     * A CONSTRUCT-only source whose result is related — tsc 7.0.2 over `w4.ts`:
     * TS2560 at `(5,7)`, and note the MAIN message still says *Did you mean to call
     * it?* while only the RELATED row switches to TS6213 *use 'new'*.
     */
    @Test
    fun `a construct signature whose result satisfies the target reports TS2560 with TS6213`() {
        val d = diagnose("""
            interface ZzzS4 { zzzT?: number; zzzE?(): void }
            declare function zzzF4(s: ZzzS4): void;
            interface ZzzC4 { new (s: string): { zzzT: number } }
            declare const zzzG4: ZzzC4;
            zzzF4(zzzG4);
        """)
        assert(d.map { it.code } == listOf(2560))
        assert(d[0].message ==
            "Value of type 'ZzzC4' has no properties in common with type 'ZzzS4'. " +
            "Did you mean to call it?")
        assert(d[0].line == 5)
        assert(d[0].character == 7)
        assert(d[0].relatedInformation.map { it.code } == listOf(6213))
    }

    /**
     * A CONSTRUCT-only source whose result is disjoint — tsc 7.0.2 over `w5.ts`:
     * `w5.ts(5,7): error TS2559: Type 'ZzzC5' has no properties in common with type
     * 'ZzzS5'.` No related row at all, because tsc attaches TS6212/TS6213 only to the
     * 2560 branch.
     */
    @Test
    fun `a construct signature with a disjoint result reports TS2559 and no related row`() {
        val d = diagnose("""
            interface ZzzS5 { zzzT?: number; zzzE?(): void }
            declare function zzzF5(s: ZzzS5): void;
            interface ZzzC5 { new (s: string): { zzzZ: string } }
            declare const zzzG5: ZzzC5;
            zzzF5(zzzG5);
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type 'ZzzC5' has no properties in common with type 'ZzzS5'.")
        assert(d[0].relatedInformation.isEmpty())
        assert(d[0].line == 5)
        assert(d[0].character == 7)
    }

    /**
     * `() => void` — the shape where [Checker.weakSourcePropertyNames] answers null for
     * the RESULT (so the weak veto declines to refuse) and the ordinary relation is what
     * rejects it. tsc 7.0.2 over `w6.ts`: TS2559 at `(4,7)`. Without BOTH halves of the
     * test this row is wrong.
     */
    @Test
    fun `a void-returning callable reports TS2559 through the ordinary relation`() {
        val d = diagnose("""
            interface ZzzS6 { zzzT?: number; zzzE?(): void }
            declare function zzzF6(s: ZzzS6): void;
            declare const zzzG6: () => void;
            zzzF6(zzzG6);
        """)
        assert(d.map { it.code } == listOf(2559))
        assert(d[0].message == "Type '() => void' has no properties in common with type 'ZzzS6'.")
        assert(d[0].line == 4)
        assert(d[0].character == 7)
    }

    /**
     * CONTROL — PRISTINE tsc's OWN `weakType.ts` SHAPE (`getDefaultSettings`), an
     * INFERRED return type rather than an annotated one. tsc 7.0.2 over `w8.ts`:
     * TS2560 at `(6,7)`, and this is the shape whose ACTIVE `weakType.errors.txt` rows
     * the split must not move.
     *
     * **IT DOES NOT PIN WHAT ITS NAME FIRST CLAIMED, AND THAT WAS MEASURED**: arms b3
     * (an unresolved result answers false) and b3b (an `any` result answers false) BOTH
     * read 0 RED against it, because this compiler DOES resolve the inferred return type
     * here — so the pin travels the same ordinary-relation path as the second test in
     * this class. The two conservative legs of
     * [Checker.weakCallResultSatisfiesTarget] are therefore UNDISCRIMINATED by every
     * input in this class; they are kept as a fail-safe (they can only ever keep a
     * TS2560 that this compiler already emitted) and are explicitly NOT claimed as pin
     * coverage (round 807).
     */
    @Test
    fun `the pristine inferred-return shape keeps TS2560`() {
        val d = diagnose("""
            interface ZzzS8 { zzzT?: number; zzzE?(): void }
            function zzzD8() {
                return { zzzT: 1000 };
            }
            declare function zzzF8(s: ZzzS8): void;
            zzzF8(zzzD8);
        """)
        assert(d.map { it.code } == listOf(2560))
        assert(d[0].message ==
            "Value of type '() => { zzzT: number; }' has no properties in common with " +
            "type 'ZzzS8'. Did you mean to call it?")
        assert(d[0].line == 6)
        assert(d[0].character == 7)
    }
}

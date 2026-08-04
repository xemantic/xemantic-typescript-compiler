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
 * (NATIVE.1), round 827 — INV.6(6c0) at the [runWithDeepStack] boundary, on EVERY
 * platform that has one.
 *
 * The compile pipeline runs on a dedicated large-stack thread (a JVM `Thread` with an
 * explicit `stackSize`; since round 827 a pthread with `pthread_attr_setstacksize` on
 * native), and the Symbol/Type id sequences are per-thread cells. So the actual must
 * SEED the compile thread from the caller's counters and WRITE THE ADVANCED VALUES
 * BACK on join — round 607 measured the missing write-back as 51 corpus failures
 * whose `--listAll` output stays byte-IDENTICAL, i.e. the failure mode is silent and
 * no output diff can find it. That is why this is pinned by direct observation of the
 * counters rather than by a green suite.
 *
 * Each pin here discriminates a specific way of getting the handoff wrong:
 *  - dropping the SEED restarts the compile thread at id 1 (`insideStart == before`
 *    fails, and 1 collides with the singleton intrinsics' own low ids);
 *  - dropping the WRITE-BACK leaves the caller's counter where it was, so a chain of
 *    compiles on one caller thread re-issues the same ids (`after == insideEnd` fails);
 *  - minting the intrinsic singletons on the compile thread instead of the caller's
 *    would give them a different identity or a different id inside the block than
 *    outside it — round 825's parallel-worker bug reached from the other side.
 */
class DeepStackHandoffTest {

    /** Advances the id sequences well past 1, so a RESTART is distinguishable from
     *  a CONTINUATION — against a counter still at 1 both look the same. */
    private fun warmIdSequences() {
        TypeScriptCompiler().compile("let warm: number = 1;\n", "warm.ts")
    }

    @Test
    fun `the deep-stack thread inherits and hands back the Type id sequence`() {
        warmIdSequences()
        val before = Type.captureThreadId()
        assert(before > 20)
        var insideStart = -1
        var insideEnd = -1
        runWithDeepStack {
            insideStart = Type.captureThreadId()
            TypeScriptCompiler().compile("let inner: string = 'a';\n", "inner.ts")
            insideEnd = Type.captureThreadId()
        }
        val after = Type.captureThreadId()
        // seeded from the caller
        assert(insideStart == before)
        // the block really minted types, so the write-back has something to carry
        assert(insideEnd > insideStart)
        // handed back
        assert(after == insideEnd)
    }

    @Test
    fun `the deep-stack thread inherits and hands back the Symbol id sequences`() {
        warmIdSequences()
        val before = Symbol.captureThreadIds()
        assert(before.first > 20)
        var insideStart = 0 to 0
        var insideEnd = 0 to 0
        runWithDeepStack {
            insideStart = Symbol.captureThreadIds()
            TypeScriptCompiler().compile("let inner: string = 'a';\n", "inner.ts")
            insideEnd = Symbol.captureThreadIds()
        }
        val after = Symbol.captureThreadIds()
        assert(insideStart == before)
        assert(insideEnd.first > insideStart.first)
        // the scope-symbol sequence counts DOWN from -2, so it may only fall
        assert(insideEnd.second <= insideStart.second)
        assert(after == insideEnd)
    }

    @Test
    fun `the intrinsic singletons keep one identity across the deep-stack boundary`() {
        warmIdSequences()
        val outerAny = anyType
        val outerString = stringType
        val outerNever = neverType
        val outerIds = listOf(outerAny.id, outerString.id, outerNever.id)
        var sameIdentity = false
        var insideIds = emptyList<Int>()
        runWithDeepStack {
            sameIdentity = anyType === outerAny &&
                stringType === outerString &&
                neverType === outerNever
            insideIds = listOf(anyType.id, stringType.id, neverType.id)
        }
        assert(sameIdentity)
        assert(insideIds == outerIds)
        // The intrinsics are minted by the very first class touch, long below any
        // compile's ids — which is what makes a compile-thread id space that
        // restarts at 1 a COLLISION rather than merely a renumbering.
        assert(outerIds.max() < Type.captureThreadId())
    }

    /** A nested call runs inline on the already-deep thread — the counters must not
     *  be re-seeded or handed back twice by the inner frame. */
    @Test
    fun `a nested call is re-entrant and leaves the sequences continuous`() {
        warmIdSequences()
        val before = Type.captureThreadId()
        var innerStart = -1
        var outerEnd = -1
        runWithDeepStack {
            runWithDeepStack {
                innerStart = Type.captureThreadId()
                TypeScriptCompiler().compile("let nested: number = 2;\n", "nested.ts")
            }
            outerEnd = Type.captureThreadId()
        }
        assert(innerStart == before)
        assert(outerEnd > innerStart)
        assert(Type.captureThreadId() == outerEnd)
    }
}

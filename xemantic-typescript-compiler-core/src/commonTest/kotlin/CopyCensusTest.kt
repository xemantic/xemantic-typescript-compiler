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

package com.xemantic.typescript.compiler.bench

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.FrontEnd
import com.xemantic.typescript.compiler.diagnose
import kotlin.test.Test

/**
 * (WARM.25) round 898 — pins the copy census's round-898 additions: the
 * per-family amplifier ARMS, the discarded-unwritten counters, the bulk write
 * counter, and the `SpineArgCtx` lookup counter.
 *
 * **The failure this file exists for is a silent WRONG MEASUREMENT, not a
 * crash.** [copyAmpKinds] answers `-1` — arm EVERY family — for any tier name
 * it does not recognise, and `-1` is also the legitimate default. So a
 * mis-spelled or unregistered per-family prefix does not fail, it quietly
 * measures the SUM of six families and reports it as one; and since rounds
 * 869/891/892 converted four of the six to undo logs, the sum happens to look
 * plausible. That is the shape round 856 named — a driver that dispatched no
 * arm and still printed a clean sweep — and the only thing that catches it is
 * a pin on the mask itself.
 *
 * The compile-driven pins at the bottom exist for the round-849 reason: a
 * counter hooked to a path the caller short-circuits reads zero, and a zero
 * from a blind instrument is indistinguishable from a real negative. Each
 * asserts a POSITIVE (the counter moved) as well as the invariant.
 */
class CopyCensusTest {

    private fun <T> withSavedMode(block: () -> T): T {
        val saved = FrontEnd.mode
        val savedAmp = FrontEnd.copyAmp
        val savedKinds = FrontEnd.copyAmpKinds
        try {
            return block()
        } finally {
            FrontEnd.mode = saved
            FrontEnd.copyAmp = savedAmp
            FrontEnd.copyAmpKinds = savedKinds
            FrontEnd.reset()
        }
    }

    // ---- the amplifier arms -------------------------------------------------

    @Test
    fun `each per-family amplifier arm names exactly its own family`() {
        assert(copyAmpKinds("copyampem16") == 1 shl FrontEnd.CP_EPOCH_MAP)
        assert(copyAmpKinds("copyampes16") == 1 shl FrontEnd.CP_EPOCH_SET)
        assert(copyAmpKinds("copyampal16") == 1 shl FrontEnd.CP_ARG_OVERLAY)
        assert(
            copyAmpKinds("copyampag16") ==
                (1 shl FrontEnd.CP_ARG_OVERLAY) or (1 shl FrontEnd.CP_ARG_EDGE)
        )
    }

    /**
     * The discriminating pin: the two new prefixes must not collide with each
     * other, and none of them may fall through to the arm-everything default.
     * `em` and `es` differ in one character and both start with `copyampe`.
     */
    @Test
    fun `no per-family arm falls through to the arm-everything default`() {
        for (t in listOf("copyampem0", "copyampes0", "copyampal0", "copyampag0")) {
            assert(copyAmpKinds(t) != -1)
        }
        assert(copyAmpKinds("copyampem8") != copyAmpKinds("copyampes8"))
        // …while a bare `copyamp<r>` still means every family, which is what
        // the whole-family census arm depends on.
        assert(copyAmpKinds("copyamp0") == -1)
    }

    @Test
    fun `the new arms carry their repetition count`() {
        assert(copyAmpReps("copyampem64") == 64)
        assert(copyAmpReps("copyampes32") == 32)
        assert(copyAmpReps("copyampal0") == 0)
        assert(copyAmpReps("copyampag16") == 16)
    }

    // ---- the amplifier itself ----------------------------------------------

    /**
     * [FrontEnd.ampCopyOrdered] exists because the `SpineArgCtx` families copy
     * with `toMutableMap()`, i.e. into a `LinkedHashMap`, and amplifying an
     * ordered copy with an unordered one under-reads the family being priced.
     * Its arithmetic falsifier is the same as [FrontEnd.ampCopyMap]'s.
     */
    @Test
    fun `the ordered amplifier sinks exactly r times the source size`() = withSavedMode {
        FrontEnd.mode = FrontEnd.ON
        FrontEnd.reset()
        FrontEnd.copyAmp = 5
        FrontEnd.copyAmpKinds = 1 shl FrontEnd.CP_ARG_OVERLAY
        FrontEnd.ampCopyOrdered(FrontEnd.CP_ARG_OVERLAY, mapOf("a" to 1, "b" to 2, "c" to 3))
        assert(FrontEnd.copyAmpSink == 15L)
    }

    @Test
    fun `the ordered amplifier is silent for a family it is not armed for`() = withSavedMode {
        FrontEnd.mode = FrontEnd.ON
        FrontEnd.reset()
        FrontEnd.copyAmp = 5
        FrontEnd.copyAmpKinds = 1 shl FrontEnd.CP_ARG_OVERLAY
        FrontEnd.ampCopyOrdered(FrontEnd.CP_ARG_EDGE, mapOf("a" to 1, "b" to 2, "c" to 3))
        assert(FrontEnd.copyAmpSink == 0L)
    }

    // ---- the counters -------------------------------------------------------

    @Test
    fun `the bulk write counter adds its whole argument`() = withSavedMode {
        FrontEnd.mode = FrontEnd.ON
        FrontEnd.reset()
        FrontEnd.noteMuts(FrontEnd.CP_ARG_OVERLAY, 7)
        FrontEnd.noteMuts(FrontEnd.CP_ARG_OVERLAY, 5)
        assert(FrontEnd.copyMuts[FrontEnd.CP_ARG_OVERLAY] == 12L)
    }

    @Test
    fun `the first-write counter records the size the copy was born with`() = withSavedMode {
        FrontEnd.mode = FrontEnd.ON
        FrontEnd.reset()
        FrontEnd.noteFirstMut(FrontEnd.CP_EPOCH_MAP, 40)
        FrontEnd.noteFirstMut(FrontEnd.CP_EPOCH_MAP, 2)
        assert(FrontEnd.copyTouchedCalls[FrontEnd.CP_EPOCH_MAP] == 2L)
        assert(FrontEnd.copyTouchedEntries[FrontEnd.CP_EPOCH_MAP] == 42L)
    }

    @Test
    fun `the arg lookup counter splits hits from misses`() = withSavedMode {
        FrontEnd.mode = FrontEnd.ON
        FrontEnd.reset()
        FrontEnd.noteArgLookup(hit = true)
        FrontEnd.noteArgLookup(hit = false)
        FrontEnd.noteArgLookup(hit = false)
        assert(FrontEnd.argLookupHits == 1L)
        assert(FrontEnd.argLookupMisses == 2L)
    }

    /**
     * NEGATIVE CONTROL for every counter above: off the census they are no-ops,
     * so a green positive pin cannot be explained by the probe simply always
     * being on in this process. This is also the production invariant — the
     * hooks sit on `EpochMap.put`, on `spineArgListOverlay` and on the arity
     * read of every call expression.
     */
    @Test
    fun `negative control - off the census every new counter is a no-op`() = withSavedMode {
        FrontEnd.mode = FrontEnd.OFF
        FrontEnd.reset()
        FrontEnd.copyAmp = 5
        FrontEnd.copyAmpKinds = -1
        FrontEnd.noteMuts(FrontEnd.CP_ARG_OVERLAY, 7)
        FrontEnd.noteFirstMut(FrontEnd.CP_EPOCH_MAP, 40)
        FrontEnd.noteArgLookup(hit = true)
        FrontEnd.noteArgLookup(hit = false)
        assert(FrontEnd.copyMuts[FrontEnd.CP_ARG_OVERLAY] == 0L)
        assert(FrontEnd.copyTouchedCalls[FrontEnd.CP_EPOCH_MAP] == 0L)
        assert(FrontEnd.copyTouchedEntries[FrontEnd.CP_EPOCH_MAP] == 0L)
        assert(FrontEnd.argLookupHits == 0L)
        assert(FrontEnd.argLookupMisses == 0L)
    }

    @Test
    fun `reset releases the round-898 counters`() = withSavedMode {
        FrontEnd.mode = FrontEnd.ON
        FrontEnd.reset()
        FrontEnd.noteMuts(FrontEnd.CP_ARG_EDGE, 3)
        FrontEnd.noteFirstMut(FrontEnd.CP_EPOCH_SET, 9)
        FrontEnd.noteArgLookup(hit = true)
        FrontEnd.reset()
        assert(FrontEnd.copyMuts[FrontEnd.CP_ARG_EDGE] == 0L)
        assert(FrontEnd.copyTouchedEntries[FrontEnd.CP_EPOCH_SET] == 0L)
        assert(FrontEnd.argLookupHits == 0L)
    }

    // ---- the hooks, through a real compile ----------------------------------

    private val src = """
        function outer(a: string, b: number) {
            let x = a;
            let y = b;
            function inner(c: string) {
                let z = c;
                return z;
            }
            if (x.length > 0) {
                let w = x;
                return inner(w);
            }
            return inner(x);
        }
        outer("k", 1);
        inner2();
        function inner2() { return 0; }
    """.trimIndent()

    /**
     * THE DISCRIMINATING COMPILE PIN. `copyTouchedCalls` can only be non-zero if
     * `EpochMap`'s copy constructor recorded the size it was born with AND the
     * first write cleared it — i.e. it fails both when the birth record is
     * dropped (reads 0, an un-instrumented zero of exactly the round-849 kind)
     * and when the clear is dropped (reads MORE than one record per copy, so
     * the touched totals overtake the copy totals).
     */
    @Test
    fun `a compile records copies that were written and never more than it copied`() =
        withSavedMode {
            FrontEnd.mode = FrontEnd.ON
            FrontEnd.reset()
            diagnose(src)
            val calls = FrontEnd.copyCalls[FrontEnd.CP_EPOCH_MAP]
            val touched = FrontEnd.copyTouchedCalls[FrontEnd.CP_EPOCH_MAP]
            val entries = FrontEnd.copyEntries[FrontEnd.CP_EPOCH_MAP]
            val touchedEntries = FrontEnd.copyTouchedEntries[FrontEnd.CP_EPOCH_MAP]
            assert(calls > 0L)
            assert(touched > 0L)
            assert(touched <= calls)
            assert(touchedEntries <= entries)
        }

    /**
     * …and the `SpineArgCtx` lookup counter is live on the same compile, with
     * BOTH outcomes represented: `outer(…)` and `inner(…)` hit, the method call
     * `x.length` and the shadow scan do not. A counter that could only ever
     * report one of the two would be measuring a predicate, not a population.
     */
    @Test
    fun `a compile records both arity lookup outcomes`() = withSavedMode {
        FrontEnd.mode = FrontEnd.ON
        FrontEnd.reset()
        diagnose(src)
        assert(FrontEnd.argLookupHits > 0L)
        assert(FrontEnd.argLookupMisses > 0L)
    }

    /**
     * The `spineArgListOverlay` copy family is reached by this fixture — the
     * nested `function inner` in `outer`'s body is exactly the shape the
     * overlay exists for. Without this the family's census would be an
     * un-exercised zero and its ceiling an unfalsifiable claim.
     */
    @Test
    fun `a compile reaches the arg list overlay copy family`() = withSavedMode {
        FrontEnd.mode = FrontEnd.ON
        FrontEnd.reset()
        diagnose(src)
        assert(FrontEnd.copyCalls[FrontEnd.CP_ARG_OVERLAY] > 0L)
        assert(FrontEnd.copyEntries[FrontEnd.CP_ARG_OVERLAY] > 0L)
    }
}

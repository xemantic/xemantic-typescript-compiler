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
 * (API.3c) The hash spread of a [TypeCaptureRequest]'s per-file key sets.
 *
 * ## Why this is a test and not a comment
 *
 * `Long.hashCode` is `(int) (v xor (v ushr 32))`, so a `(start shl 32) or end` pack
 * hashes to exactly `start xor end` — and a node's `end` is its `start` plus its own
 * length plus the FOLLOWING token (round 910), i.e. the two halves of every key in a
 * file are neighbours. `pos xor (pos + 12)` is a small number for every position, so
 * a whole file's identifier spans collapse onto a few dozen distinct hashes, every
 * bucket degenerates, and the set treeifies — round 889's defect, in the one
 * container this API fills in bulk.
 *
 * It cost nothing while a request held the handful of spans one hover asks about,
 * which is why the packing was deliberately left raw with a note to finalize it
 * "should a caller ever request spans in bulk". `Project.fileSemantics` is that
 * caller. The pin MEASURES the collapse — it computes the distinct hash count of a
 * realistic whole-file span population — so it fails on the raw pack and passes on
 * the finalized one, rather than restating the fix.
 *
 * Nothing here can be seen by any other gate: no diagnostic moves, no emitted byte
 * moves and `cost_gate.py` counts checker work, so a degenerate key set is invisible
 * to every instrument in this repo except a bucket census (CLAUDE.md, round 889).
 */
class TypeCaptureKeySpreadTest {

    /**
     * The spans of a plausible file: names of 3-14 characters, spread over ~8,000
     * characters, each `end` overshooting its own last character by a following
     * token of 1-3 characters. Modelled rather than parsed so the population is
     * explicit and the test states what it assumes.
     */
    private fun fileSpans(): List<TypeCaptureSpan> {
        val spans = ArrayList<TypeCaptureSpan>()
        var at = 0
        var length = 3
        while (at < 8_000) {
            val end = at + length + 1 + (at % 3)
            spans.add(TypeCaptureSpan("/proj/src/a.ts", at, end))
            at += length + 7
            length = 3 + (at % 12)
        }
        return spans
    }

    @Test
    fun `a whole file's spans hash to nearly as many distinct values as there are spans`() {
        val spans = fileSpans()
        // A real population, not three keys: the collapse is only visible in bulk.
        assert(spans.size > 400)
        val keys = spans.mapTo(HashSet()) {
            TypeCaptureRequest.packSpanKey(it.start, it.end)
        }
        assert(keys.size == spans.size)
        val hashes = keys.mapTo(HashSet()) { it.hashCode() }
        // The raw pack answers `start xor end` here, which is under 32 distinct
        // values for the whole file; the finalized pack is injective enough that
        // collisions are a rounding error.
        assert(hashes.size > spans.size * 9 / 10)
    }

    @Test
    fun `negative control - the RAW pack this replaced does collapse, so the pin is measuring something`() {
        // Without this, "the hashes spread" could be a property of the population
        // rather than of the packing, and the pin above would be unfalsifiable.
        val spans = fileSpans()
        val rawHashes = spans.mapTo(HashSet()) {
            ((it.start.toLong() shl 32) or (it.end.toLong() and 0xFFFFFFFFL)).hashCode()
        }
        assert(rawHashes.size < spans.size / 10)
    }

    @Test
    fun `the keys still identify a span uniquely, which is all the checker asks of them`() {
        // The finalizer is a bijection mod 2^64, so it cannot merge two distinct
        // spans — the property the per-node membership test depends on.
        val request = TypeCaptureRequest(fileSpans())
        val keys = request.keysByFile.getValue("/proj/src/a.ts")
        assert(keys.size == request.spans.size)
        assert(
            request.spans.all {
                TypeCaptureRequest.packSpanKey(it.start, it.end) in keys
            },
        )
    }

    @Test
    fun `spans are grouped per FILE, so one file's bulk request cannot be asked of another`() {
        val request = TypeCaptureRequest(
            listOf(
                TypeCaptureSpan("/proj/src/a.ts", 10, 20),
                TypeCaptureSpan("/proj/src/a.ts", 30, 40),
                TypeCaptureSpan("/proj/src/b.ts", 10, 20),
            ),
        )
        assert(request.keysByFile.size == 2)
        assert(request.keysByFile.getValue("/proj/src/a.ts").size == 2)
        assert(request.keysByFile.getValue("/proj/src/b.ts").size == 1)
    }
}

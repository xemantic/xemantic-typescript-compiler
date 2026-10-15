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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.tsgo.runtime.goAtomicCasLong
import com.xemantic.typescript.tsgo.runtime.goAtomicLocked
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/** A holder of an inlined atomic field, as the porter emits one (docs/goport-lowering.md § 3, "Inlined atomic fields"). */
private class Holder {
    @Volatile
    @JvmField
    var id: Long = 0L
}

/**
 * The runtime half of inlined atomic fields: `goAtomicCasLong` is Go's `CompareAndSwap` on a `@Volatile` field, so
 * of many threads swapping the same zero for their own value exactly one wins and every thread then reads the
 * winner's value — `ast.GetNodeId`'s contract (one id per node however many checkers ask at once).
 */
class GoAtomicFieldTest {

    private fun cas(h: Holder, old: Long, new: Long) = goAtomicCasLong(h, { h.id }, { h.id = it }, old, new)

    @Test
    fun `compare-and-swap answers Go's result on one thread`() {
        val h = Holder()
        assert(cas(h, 0L, 7L))
        assert(h.id == 7L)
        assert(!cas(h, 0L, 9L))
        assert(h.id == 7L)
        assert(cas(h, 7L, -1L))
        assert(h.id == -1L)
        goAtomicLocked(h) { h.id = 3L }
        assert(h.id == 3L)
    }

    @Test
    fun `of many threads swapping the same zero exactly one wins per holder`() {
        val threads = 8
        val holders = List(200_000) { Holder() }
        val wins = AtomicInteger(0)
        val disagreements = AtomicInteger(0)
        val start = CountDownLatch(1)
        val workers = (1..threads).map { t ->
            Thread {
                start.await()
                for (h in holders) {
                    if (cas(h, 0L, t.toLong())) wins.incrementAndGet()
                    // After its attempt every thread sees one id, never zero, and it is a winner's.
                    if (h.id == 0L) disagreements.incrementAndGet()
                }
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assert(wins.get() == holders.size)
        assert(disagreements.get() == 0)
        assert(holders.all { it.id in 1L..threads.toLong() })
    }
}

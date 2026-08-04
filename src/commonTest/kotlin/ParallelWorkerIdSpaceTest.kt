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
 * (PERF.HW.a) round 825 — the `--workers N` id-space invariant.
 *
 * Symbol/Type ids are the keys of the checker's relation caches, so two
 * concurrently-running partition checkers minting the SAME id is silent
 * cross-worker cache confusion, not a crash. Before this round every worker
 * rebased to the SAME base, which was sound only while nothing shared was ever
 * allocated from a worker's own sequence — an assumption the singleton
 * intrinsic types violated (`TypeKt`'s static initializer first ran inside
 * whichever worker reached `anyType` first, i.e. AFTER that worker had already
 * rebased, putting `anyType.id` at 1_000_000_005 — inside every other worker's
 * range).
 *
 * The structural fix is a DISJOINT slice per worker, which is what these pins
 * check: with disjoint slices a shared value minted inside one worker lands in
 * that worker's slice alone and can collide with nobody.
 */
class ParallelWorkerIdSpaceTest {

    private class Minted(val typeIds: List<Int>, val symbolIds: List<Int>, val scopeIds: List<Int>)

    private fun mintInWorkers(workers: Int, perWorker: Int): List<Minted> =
        runInDeepStackWorkers(
            (0 until workers).map {
                {
                    Minted(
                        typeIds = (0 until perWorker).map { Type.Object().id },
                        symbolIds = (0 until perWorker).map { Symbol(SymbolFlags.None, "s").id },
                        scopeIds = (0 until perWorker).map { Symbol.scopeSymbol(SymbolFlags.None, "s").id },
                    )
                }
            }
        )

    @Test
    fun `type ids minted by concurrent workers never collide`() {
        val minted = mintInWorkers(workers = 4, perWorker = 200)
        val all = minted.flatMap { it.typeIds }
        assert(all.size == 800)
        assert(all.toSet().size == 800)
    }

    @Test
    fun `symbol ids minted by concurrent workers never collide`() {
        val minted = mintInWorkers(workers = 4, perWorker = 200)
        val all = minted.flatMap { it.symbolIds }
        assert(all.toSet().size == 800)
    }

    @Test
    fun `lexical scope symbol ids minted by concurrent workers never collide`() {
        val minted = mintInWorkers(workers = 4, perWorker = 200)
        val all = minted.flatMap { it.scopeIds }
        assert(all.toSet().size == 800)
        // INV.2(c): the scope space stays strictly negative in every worker.
        assert(all.all { it <= -2 })
    }

    @Test
    fun `a worker never mints an id already held by a singleton intrinsic type`() {
        val intrinsicIds = setOf(
            anyType.id, unknownType.id, stringType.id, numberType.id, booleanType.id,
            voidType.id, undefinedType.id, nullType.id, neverType.id,
        )
        val minted = mintInWorkers(workers = 4, perWorker = 200)
        val collisions = minted.flatMap { it.typeIds }.count { it in intrinsicIds }
        assert(collisions == 0)
    }

    @Test
    fun `each worker mints a contiguous ascending run - no interleaving across workers`() {
        val minted = mintInWorkers(workers = 4, perWorker = 200)
        for (worker in minted) {
            assert(worker.typeIds == worker.typeIds.sorted())
            assert(worker.typeIds.last() - worker.typeIds.first() == 199)
        }
    }
}

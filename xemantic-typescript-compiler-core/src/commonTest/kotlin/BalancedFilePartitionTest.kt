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
 * (PERF.HW.c): the `--workers` file partition.
 *
 * Three of these are CORRECTNESS pins and one is the reason the change exists.
 *
 * The correctness three — every file assigned, no file assigned twice, and the
 * assignment a pure function of the program — are what let the INV.6 partition
 * union be the sequential diagnostic set. A dropped file is a SILENTLY MISSING
 * diagnostic, which no baseline in this repo would notice on a fixture the
 * partition happens to cover, and an order-dependent assignment would make the
 * output depend on scheduling, which is the one thing the whole byte-identical
 * verification method here cannot survive.
 *
 * The fourth is the balance claim, and it is stated as an inequality against the
 * scheme it replaced rather than as an absolute: on a program with one dominant
 * file — the shape that motivated this, since `checker.ts` is 31.6% of the tsc
 * compiler profile — round-robin puts the giant in a bucket that also draws its
 * ordinary share, while longest-processing-time-first gives that bucket the
 * giant and nothing else.
 */
class BalancedFilePartitionTest {

    private fun file(name: String, size: Int): SourceFile =
        SourceFile(fileName = name, statements = emptyList(), text = "x".repeat(size))

    /** One dominant file plus a long tail — the real shape, in miniature. */
    private val program = listOf(
        file("/p/checker.ts", 3000),
        file("/p/parser.ts", 500),
        file("/p/types.ts", 400),
        file("/p/utilities.ts", 300),
        file("/p/emitter.ts", 200),
        file("/p/binder.ts", 100),
        file("/p/scanner.ts", 50),
    )

    private val compiler = TypeScriptCompiler()

    private fun loads(buckets: List<Set<String>>): List<Int> =
        buckets.map { b -> program.filter { it.fileName in b }.sumOf { it.text.length } }

    /** The scheme this replaced, so the balance claim is against something real. */
    private fun roundRobin(workers: Int): List<Set<String>> =
        (0 until workers).map { w ->
            program.filterIndexed { i, _ -> i % workers == w }.map { it.fileName }.toSet()
        }

    @Test
    fun `every file is assigned to exactly one worker`() {
        for (workers in 1..8) {
            val buckets = compiler.balancedFilePartition(program, workers)
            val all = buckets.flatten()
            assert(all.size == program.size)
            assert(all.toSet() == program.map { it.fileName }.toSet())
        }
    }

    @Test
    fun `the partition has exactly one bucket per worker`() {
        for (workers in 1..8) {
            assert(compiler.balancedFilePartition(program, workers).size == workers)
        }
    }

    @Test
    fun `the assignment is a pure function of the program and not of input order`() {
        val forward = compiler.balancedFilePartition(program, 4)
        val reversed = compiler.balancedFilePartition(program.reversed(), 4)
        // Equal-sized files would otherwise be separated only by the order they
        // arrived in, which is why the comparator breaks ties on fileName.
        val shuffled = compiler.balancedFilePartition(program.sortedBy { it.fileName }, 4)
        assert(reversed == forward)
        assert(shuffled == forward)
    }

    @Test
    fun `equal-sized files are still assigned deterministically`() {
        val flat = (1..8).map { file("/p/f$it.ts", 100) }
        val once = compiler.balancedFilePartition(flat, 3)
        assert(compiler.balancedFilePartition(flat.reversed(), 3) == once)
    }

    @Test
    fun `the dominant file lands in a bucket of its own`() {
        val buckets = compiler.balancedFilePartition(program, 4)
        val giant = buckets.single { "/p/checker.ts" in it }
        assert(giant.size == 1)
    }

    @Test
    fun `the heaviest bucket is lighter than round-robin's at every worker count`() {
        // The wall of a parallel phase is the slowest worker, so this — not the
        // mean, and not the total — is the quantity the partition controls.
        for (workers in 2..6) {
            val balanced = loads(compiler.balancedFilePartition(program, workers)).max()
            val naive = loads(roundRobin(workers)).max()
            assert(balanced <= naive)
        }
        // Guards the loop above against being vacuously true: at 4 workers the
        // improvement must be strict, or the fixture is not exercising the shape.
        val balanced4 = loads(compiler.balancedFilePartition(program, 4)).max()
        val naive4 = loads(roundRobin(4)).max()
        assert(balanced4 < naive4)
    }
}

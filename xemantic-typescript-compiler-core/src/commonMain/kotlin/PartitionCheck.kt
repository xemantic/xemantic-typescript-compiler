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

/**
 * INV.6(6b): the opt-in partition-equivalence verification mode (the PassTiming
 * pattern — a global toggle, behavior-free when off). When [workers] > 1, the
 * multi-file compilation core additionally runs N SEQUENTIAL partition
 * checkers (fresh parse-tree bind per worker — checker init mutates shared
 * symbols via mergeSymbolTable, so workers must never reuse an already-checked
 * bind) and diffs the merged partition output against the full run. The report
 * lands in [reportLines] for the CLI to print.
 *
 * The contract under test is SEQUENTIAL EQUIVALENCE (docs/parallel-caching.md):
 * every divergence is a cross-file spine-state leak — an order-dependence bug
 * to fix before the (6c) parallel driver threads this for real.
 */
object PartitionCheck {
    /** Number of sequential partition workers to simulate; 0/1 = off. */
    var workers: Int = 0

    /** The comparison report, populated by the compilation core when enabled. */
    val reportLines: MutableList<String> = mutableListOf()

    fun reset() {
        workers = 0
        reportLines.clear()
    }
}

/**
 * INV.6(6c1): the opt-in PARALLEL check mode (`--workers N`). When [workers]
 * > 1, the multi-file compilation core replaces the single full checker with
 * N share-nothing partition checkers running concurrently on deep-stack
 * worker threads (fresh bind per worker; per-worker id-space rebase — see
 * [IntThreadLocal]); merged output is deterministic (worker order) with
 * content proven equal to the sequential run by the (6b) harness. Output
 * LINE ORDER may differ from sequential — A/B by sorted error lines.
 */
object ParallelCheckMode {
    /** Number of concurrent partition workers; 0/1 = sequential (default). */
    var workers: Int = 0
}

/**
 * INV.6(6c1): run [tasks] to completion and return their results in task
 * order. JVM: one deep-stack worker thread per task, started concurrently,
 * each with its Symbol/Type id sequences REBASED far above the shared
 * singleton-intrinsic range (ids never cross a worker boundary). Other
 * platforms: sequential inline execution with NO rebase (the single thread's
 * continuous sequence stays valid).
 */
internal expect fun <T> runInDeepStackWorkers(tasks: List<() -> T>): List<T>

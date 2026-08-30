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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.system.measureNanoTime

/**
 * (INC.64) — price the CRAWL's PIPELINE SHAPE against the work it carries.
 *
 * The floor's `read+decode (CPU sum)` is elapsed-WITH-SUSPENSION (CLAUDE.md: a file's
 * span includes waiting for a dispatcher slot), so only the crawl WALL is a price. On
 * `many-small-2400-dom` that wall is **51-57 ms** — while reading the same 2,401 files
 * sequentially costs **13-21 ms** and `computeParserFlags` over them **1.1-1.8 ms**.
 *
 * `readAndScanBatch` does TWO dispatcher hops per file (read on `Dispatchers.IO`, parse
 * on `Dispatchers.Default`) inside a 16-way `flatMapMerge` — 2 x files thread handoffs.
 * On a COLD crawl the parse dominates and that shape is the owner's measured design
 * (ARCHITECTURE-RETHINK § 4); on a WARM incremental build every parse is a cache hit,
 * so the handoffs are all that is left. These arms price that difference directly:
 *
 *   seq      — read every file on this thread
 *   pipe2    — the shipped shape: flatMapMerge(16) + withContext(IO) + withContext(Default)
 *   pipe1    — the same flow with ONE hop (read inline on the merge's own thread)
 *   pipe0    — flatMapMerge(16) with no withContext at all
 *
 * Arms are ABBA-rotated within the process because the first instrumented pass is the
 * slowest draw (CLAUDE.md round 869) and a straight ladder puts all of that bias on
 * whichever arm runs first.
 */
fun main(args: Array<String>) {
    val dir = args.getOrNull(0) ?: "build/bench/many-small-2400-dom"
    val reps = args.getOrNull(1)?.toInt() ?: 6

    val files = ArrayList<String>()
    fun walk(d: String) {
        for (e in SystemVfs.listEntries(d).sortedBy { it.path }) {
            if (e.isDirectory) walk(e.path) else if (e.path.endsWith(".ts")) files.add(e.path)
        }
    }
    walk("$dir/src")
    println("files: ${files.size}")

    val options = CompilerOptions()
    var sink = 0L
    val samples = HashMap<String, MutableList<Double>>()

    fun record(arm: String, nanos: Long) {
        samples.getOrPut(arm) { ArrayList() }.add(nanos / 1_000_000.0)
    }

    repeat(reps) {
        val order = listOf("seq", "pipe2", "pipe1", "pipe0", "pipe0", "pipe1", "pipe2", "seq")
        for (arm in order) {
            val n = measureNanoTime {
                sink += when (arm) {
                    "seq" -> files.sumOf { (SystemVfs.readText(it)?.length ?: 0).toLong() }
                    "pipe2" -> runBlocking { pipeline(files, hops = 2) }
                    "pipe1" -> runBlocking { pipeline(files, hops = 1) }
                    else -> runBlocking { pipeline(files, hops = 0) }
                }
            }
            record(arm, n)
        }
    }
    // The flags arm is sequential by construction — it is what the warm crawl's
    // "pre-parse" really is once every parse is a cache hit.
    val flagNanos = measureNanoTime {
        val contents = files.map { SystemVfs.readText(it) ?: "" }
        for (i in files.indices) sink += computeParserFlags(files[i], contents[i], options).hashCode().toLong()
    }

    for (arm in listOf("seq", "pipe0", "pipe1", "pipe2")) {
        val xs = samples.getValue(arm).sorted()
        // Drop the leading draw of each arm: round 869's law.
        val kept = xs.drop(1)
        println(
            "$arm  median=${kept[kept.size / 2]} ms  min=${kept.first()} max=${kept.last()}  n=${kept.size}"
        )
    }
    println("flags+read (sequential) ${flagNanos / 1_000_000.0} ms")
    println("sink=$sink")
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun pipeline(files: List<String>, hops: Int): Long =
    files.asFlow()
        .flatMapMerge(concurrency = 16) { path ->
            flow {
                val content =
                    if (hops >= 1) withContext(Dispatchers.IO) { SystemVfs.readText(path) }
                    else SystemVfs.readText(path)
                val len =
                    if (hops >= 2) withContext(Dispatchers.Default) { content?.length ?: 0 }
                    else content?.length ?: 0
                emit(len.toLong())
            }
        }
        .toList()
        .sum()

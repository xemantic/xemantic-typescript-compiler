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

package com.xemantic.typescript.compiler.project

import com.xemantic.typescript.compiler.SystemVfs
import java.io.File

/**
 * (INC.73) Price ONE file-existence probe over the population the module resolver
 * actually asks — the same shape as (INC.68)'s `PathUtil.normalize` micro-benchmark
 * and (INC.60)'s `listEntries` one, and taken BEFORE anything is changed.
 *
 * The question is whether kotlinx-io's `SystemFileSystem.exists` answers one boolean
 * with one syscall or with several: (INC.60) measured its `metadataOrNull` sibling at
 * **up to five** `stat`s and 7.3-8.6 us per entry, which was 60-70% of the root-file
 * glob. `java.io.File.exists` is the one-syscall control.
 */
fun main(args: Array<String>) {
    val root = args[0]
    val reps = if (args.size > 1) args[1].toInt() else 20
    // The real population: every .ts file of the fixture, i.e. exactly what a
    // relative specifier's first probe asks about.
    val paths = File(root).walkTopDown().filter { it.isFile && it.name.endsWith(".ts") }
        .map { it.path }.toList()
    require(paths.size > 100) { "REFUSED: ${paths.size} paths" }
    println("population: ${paths.size} paths")

    var sink = 0
    fun timeVfs(): Double {
        val t0 = System.nanoTime()
        for (p in paths) if (SystemVfs.exists(p)) sink++
        return (System.nanoTime() - t0).toDouble() / paths.size
    }
    fun timeFile(): Double {
        val t0 = System.nanoTime()
        for (p in paths) if (File(p).exists()) sink++
        return (System.nanoTime() - t0).toDouble() / paths.size
    }
    // Warm both, then ABBA within the process so a drift cancels.
    repeat(3) { timeVfs(); timeFile() }
    val v = ArrayList<Double>(); val f = ArrayList<Double>()
    repeat(reps) {
        v.add(timeVfs()); f.add(timeFile())
        f.add(timeFile()); v.add(timeVfs())
    }
    v.sort(); f.sort()
    println("SystemVfs.exists  median ${"%.3f".format(v[v.size / 2])} ns/call")
    println("java.io.File.exists median ${"%.3f".format(f[f.size / 2])} ns/call")
    println("ratio ${"%.2f".format(v[v.size / 2] / f[f.size / 2])}x   sink=$sink")
}

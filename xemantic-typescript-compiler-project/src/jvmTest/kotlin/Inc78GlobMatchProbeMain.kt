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

import com.xemantic.typescript.compiler.GlobMatcher
import com.xemantic.typescript.compiler.PathUtil
import com.xemantic.typescript.compiler.SystemVfs
import java.io.File

/**
 * (INC.78) PRICE THE ROOT-FILE GLOB'S MATCH ROW STANDALONE, before and after — the
 * instrument (INC.76) used, where a row measured on its own is compared against the
 * same row measured IN THE BUILD and the gap is a fact to explain rather than noise.
 *
 * `FrontEnd.CFG_MATCH` reads 4.7-8.1 ms over 2,401 candidates on the
 * `many-small-2400-dom` fixture — 1.9-3.4 us to decide one path against one include
 * and one exclude. This times the two decisions separately, so the row is attributed
 * before anything is designed:
 *
 *  - `exclude`  the `excludeGlobs.none` half alone, which REFUSES every candidate.
 *  - `include`  the `includeGlobs.any` half alone, which ACCEPTS every candidate —
 *               and an accepting regex cannot be short-circuited by any filter, which
 *               is why the exact fast path rather than a cheap refusal is the lever.
 *  - `both`     the shipped conjunction, which must reproduce the in-build row.
 *  - `regexBoth` the same conjunction forced through `GlobMatcher.regex`, i.e. the
 *               pre-(INC.78) behaviour, so the delta is measured on one binary rather
 *               than across two.
 *
 * ABBA-rotated within the process so a drift cancels; every arm consumes its result.
 * Nothing here is a gate — the gate is `GlobMatcherTest`'s differential.
 *
 * ```
 * java -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.Inc78GlobMatchProbeMainKt <projectDir> [reps]
 * ```
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [reps]" }
    val reps = if (args.size > 1) args[1].toInt() else 20
    val projectDir = PathUtil.normalize(File(args[0]).absolutePath)

    val supportedExt = listOf(".ts", ".tsx", ".mts", ".cts")
    // The fixture's own configuration, spelled the way `collectRootFiles` spells it.
    val includes = listOf(PathUtil.join(projectDir, "src/**/*"))
    val excludes = listOf(PathUtil.join(projectDir, "dist"))
    val includeGlobs = includes.map { GlobMatcher.compile(it, supportedExt) }
    val excludeGlobs = excludes.map { GlobMatcher.compile(it, supportedExt) }

    // The real candidate population: every file the walk offers the match, found the
    // same way the walk finds them, with the same pruning and the same extension filter.
    val pruned = setOf("node_modules", ".git", "bower_components", "jspm_packages")
    val candidates = ArrayList<String>()
    val stack = ArrayDeque(listOf(projectDir))
    while (stack.isNotEmpty()) {
        val d = stack.removeLast()
        for (e in SystemVfs.listEntries(d).sortedBy { it.path }) {
            if (e.isDirectory) {
                if (PathUtil.basename(e.path) !in pruned) stack.addLast(e.path)
            } else if (supportedExt.any { e.path.endsWith(it) }) {
                candidates.add(e.path)
            }
        }
    }
    require(candidates.size > 100) { "REFUSED: ${candidates.size} candidates" }

    // A dead arm is indistinguishable from a fast one, so state the population the
    // arms actually decide before timing any of them.
    val accepted = candidates.count { p ->
        excludeGlobs.none { it.matches(p) } && includeGlobs.any { it.matches(p) }
    }
    val acceptedByRegex = candidates.count { p ->
        excludeGlobs.none { it.regex.matches(p) } && includeGlobs.any { it.regex.matches(p) }
    }
    require(accepted == acceptedByRegex) { "REFUSED: fast $accepted vs regex $acceptedByRegex" }
    println("population: ${candidates.size} candidates, $accepted accepted (fast and regex agree)")
    println("fast path: include=${includeGlobs.map { it.fastSuffixes != null }} exclude=${excludeGlobs.map { it.fastSuffixes != null }}")

    var sink = 0L

    fun timeExclude(): Long {
        val t0 = System.nanoTime()
        for (p in candidates) if (excludeGlobs.none { it.matches(p) }) sink++
        return System.nanoTime() - t0
    }
    fun timeInclude(): Long {
        val t0 = System.nanoTime()
        for (p in candidates) if (includeGlobs.any { it.matches(p) }) sink++
        return System.nanoTime() - t0
    }
    fun timeBoth(): Long {
        val t0 = System.nanoTime()
        for (p in candidates) if (excludeGlobs.none { it.matches(p) } && includeGlobs.any { it.matches(p) }) sink++
        return System.nanoTime() - t0
    }
    fun timeRegexExclude(): Long {
        val t0 = System.nanoTime()
        for (p in candidates) if (excludeGlobs.none { it.regex.matches(p) }) sink++
        return System.nanoTime() - t0
    }
    fun timeRegexInclude(): Long {
        val t0 = System.nanoTime()
        for (p in candidates) if (includeGlobs.any { it.regex.matches(p) }) sink++
        return System.nanoTime() - t0
    }
    fun timeRegexBoth(): Long {
        val t0 = System.nanoTime()
        for (p in candidates) if (excludeGlobs.none { it.regex.matches(p) } && includeGlobs.any { it.regex.matches(p) }) sink++
        return System.nanoTime() - t0
    }

    val arms = listOf(
        "regexExclude" to ::timeRegexExclude,
        "regexInclude" to ::timeRegexInclude,
        "regexBoth" to ::timeRegexBoth,
        "exclude" to ::timeExclude,
        "include" to ::timeInclude,
        "both" to ::timeBoth,
    )
    repeat(5) { for ((_, f) in arms) f() }
    val draws = HashMap<String, MutableList<Long>>()
    repeat(reps) {
        for ((name, f) in arms) draws.getOrPut(name) { ArrayList() }.add(f())
        for ((name, f) in arms.reversed()) draws.getOrPut(name) { ArrayList() }.add(f())
    }
    for ((name, _) in arms) {
        val xs = draws.getValue(name)
        xs.sort()
        val median = xs[xs.size / 2]
        println(
            "%-13s median %7.3f ms   %7.0f ns/candidate"
                .format(name, median / 1e6, median.toDouble() / candidates.size)
        )
    }
    println("sink=$sink")
}

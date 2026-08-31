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
import com.xemantic.typescript.compiler.ModuleResolver
import com.xemantic.typescript.compiler.PathUtil
import com.xemantic.typescript.compiler.SystemVfs
import java.io.File

/**
 * (INC.79) DECOMPOSE THE CRAWL'S SEQUENTIAL RESOLVE ROW, before anything is changed —
 * the instrument (INC.76)/(INC.78) used, where a row measured on its own is compared
 * against the same row measured IN THE BUILD and the gap is a fact to explain.
 *
 * `FrontEnd.CRAWL_RESOLVE` is **~11.1-11.5 ms** of a ~120 ms per-keystroke query on the
 * 2,401-file `many-small-2400-dom` fixture. (INC.73)(a) refused its SYSCALL half by
 * arithmetic — 2,351 distinct resolutions at exactly one `exists` each, ~1.1 us apiece,
 * so ~2.6 ms is irreducible — which leaves ~9 ms that no round has looked at, over 4,701
 * calls. The row is not `resolve` alone: the loop it brackets also builds an import edge
 * and a per-file resolution map for every specifier.
 *
 * Arms over the SAME reconstructed population, ABBA-rotated inside the process:
 *
 *  - `resolve`      a fresh [ModuleResolver] per rep — the build's own regime, 2,351
 *                   computed plus 2,350 served by (INC.65)'s memo.
 *  - `existsOnly`   one `vfs.exists` per COMPUTED pair, the paths precomputed outside
 *                   the timer: the irreducible floor.
 *  - `arithOnly`    the per-call path arithmetic with no syscall and no memo — `dirname`,
 *                   the memo key, `isBare`, `join`/`normalize`, `extname`.
 *  - `bookkeeping`  what the crawl loop does per specifier BESIDES resolving: the edge
 *                   pair, `moduleResolutions.getOrPut { }`, the `pending`/`loaded` sets.
 *
 * The population is reconstructed from the sources' own `from` specifiers and the probe
 * REFUSES a count that does not look like the build's, because a probe measuring a
 * different population than the row is round 853's defect in a new costume.
 *
 * ```
 * java -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.Inc79CrawlResolveProbeMainKt <projectDir> [reps]
 * ```
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [reps]" }
    val reps = if (args.size > 1) args[1].toInt() else 20
    val projectDir = PathUtil.normalize(File(args[0]).absolutePath)

    // Every (importer, specifier) the crawl asks about, in file order.
    val pairs = ArrayList<Pair<String, String>>()
    val stack = ArrayDeque(listOf(projectDir))
    val files = ArrayList<String>()
    while (stack.isNotEmpty()) {
        val d = stack.removeLast()
        for (e in SystemVfs.listEntries(d).sortedBy { it.path }) {
            if (e.isDirectory) stack.addLast(e.path) else if (e.path.endsWith(".ts")) files.add(e.path)
        }
    }
    files.sort()
    val fromRegex = Regex("from\\s+\"([^\"]+)\"")
    for (f in files) {
        val text = SystemVfs.readText(f) ?: continue
        for (m in fromRegex.findAll(text)) pairs.add(f to m.groupValues[1])
    }
    require(pairs.size > 500) { "REFUSED: ${pairs.size} specifiers reconstructed" }

    // The distinct questions, i.e. what (INC.65)'s memo leaves to compute.
    val distinct = pairs.map { PathUtil.dirname(it.first) + " " + it.second }.toSet()
    // What a computed relative resolution probes first, precomputed for `existsOnly`.
    val firstProbes = pairs.map { PathUtil.join(PathUtil.dirname(it.first), it.second) + ".ts" }.distinct()
    val resolvedOnce = ModuleResolver(SystemVfs, emptyList())
    val answered = pairs.count { resolvedOnce.resolve(it.second, it.first) != null }
    println(
        "population: ${files.size} files, ${pairs.size} specifiers, ${distinct.size} distinct, " +
            "$answered resolved, ${firstProbes.size} first probes"
    )

    var sink = 0L

    fun timeResolve(): Long {
        val resolver = ModuleResolver(SystemVfs, emptyList())
        val t0 = System.nanoTime()
        for ((importer, spec) in pairs) if (resolver.resolve(spec, importer) != null) sink++
        return System.nanoTime() - t0
    }
    fun timeExistsOnly(): Long {
        val t0 = System.nanoTime()
        for (p in firstProbes) if (SystemVfs.exists(p)) sink++
        return System.nanoTime() - t0
    }
    fun timeArithOnly(): Long {
        val t0 = System.nanoTime()
        for ((importer, spec) in pairs) {
            val dir = PathUtil.dirname(importer)
            val key = dir + " " + spec
            sink += key.length.toLong()
            if (!PathUtil.isBare(spec)) {
                val base = if (PathUtil.isAbsolute(spec)) PathUtil.normalize(spec) else PathUtil.join(dir, spec)
                sink += base.length.toLong() + PathUtil.extname(base).length.toLong()
            }
        }
        return System.nanoTime() - t0
    }
    fun timeBookkeeping(): Long {
        val importEdges = ArrayList<Pair<String, String>>()
        val moduleResolutions = HashMap<String, MutableMap<String, String>>()
        val pending = HashSet<String>()
        val loaded = HashSet<String>(files)
        val t0 = System.nanoTime()
        for ((importer, spec) in pairs) {
            val resolved = importer // stands in for the answer: same shape, no resolution
            importEdges.add(importer to resolved)
            moduleResolutions.getOrPut(importer) { mutableMapOf() }[spec] = resolved
            if (resolved !in loaded && pending.add(resolved)) sink++
        }
        sink += importEdges.size.toLong() + moduleResolutions.size.toLong()
        return System.nanoTime() - t0
    }

    // The arithmetic, split: the two halves have different fixes and different
    // populations — `dirname` + the memo key run on EVERY call, `join`/`normalize` only
    // on a computed one.
    fun timeDirnameOnly(): Long {
        val t0 = System.nanoTime()
        for ((importer, _) in pairs) sink += PathUtil.dirname(importer).length.toLong()
        return System.nanoTime() - t0
    }
    fun timeKeyOnly(): Long {
        val t0 = System.nanoTime()
        for ((importer, spec) in pairs) sink += (PathUtil.dirname(importer) + " " + spec).length.toLong()
        return System.nanoTime() - t0
    }
    fun timeJoinOnly(): Long {
        val dirs = pairs.map { PathUtil.dirname(it.first) }
        val t0 = System.nanoTime()
        for (i in pairs.indices) sink += PathUtil.join(dirs[i], pairs[i].second).length.toLong()
        return System.nanoTime() - t0
    }

    val arms = listOf(
        "resolve" to ::timeResolve,
        "dirnameOnly" to ::timeDirnameOnly,
        "keyOnly" to ::timeKeyOnly,
        "joinOnly" to ::timeJoinOnly,
        "existsOnly" to ::timeExistsOnly,
        "arithOnly" to ::timeArithOnly,
        "bookkeeping" to ::timeBookkeeping,
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
            "%-12s median %7.3f ms   %7.0f ns/specifier"
                .format(name, median / 1e6, median.toDouble() / pairs.size)
        )
    }
    println("sink=$sink")
}

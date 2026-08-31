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

import com.xemantic.typescript.compiler.PathUtil
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.VfsEntry
import java.io.File

/**
 * (INC.76) DECOMPOSE THE ROOT-FILE GLOB'S LISTING ROW, before anything is changed.
 *
 * After (INC.56) the incremental floor's second-largest row on an application-shaped
 * project is `config load + @types + root glob` at ~28 ms, of which
 * `vfs.listEntries + sort (per directory)` is **20.7 ms over 50 directories** — about
 * **414 us per directory** of ~49 entries, i.e. **~8.4 us per entry**. One `stat` on
 * this box is ~1.1 us ((INC.73)(a)), so that row is ~7x what its syscalls should cost
 * and the surplus is unattributed.
 *
 * (INC.60) already took this row once, from kotlinx-io's `metadataOrNull` (up to FIVE
 * `stat`s per entry) to `File.listFiles` plus one `isDirectory`. This asks whether the
 * residue is the remaining `stat`s, the per-entry `PathUtil.normalize`, the `File`
 * allocation, or the `sortedBy` — because those have completely different fixes and
 * three of them need no promise from anyone.
 *
 * Arms, each timed over the SAME directory population and ABBA-rotated within the
 * process so a drift cancels:
 *
 *  - `listFiles`   `File(d).listFiles()` alone — the readdir and the `File[]`.
 *  - `+isDir`      that, plus `isDirectory` per entry — the syscalls.
 *  - `+path`       that, plus reading `child.path` — the String each `File` builds.
 *  - `+normalize`  that, plus `PathUtil.normalize` — i.e. `systemListEntries` itself.
 *  - `listEntries` the shipped `SystemVfs.listEntries`.
 *  - `+sorted`     the shipped call as the glob makes it, `sortedBy { it.path }`.
 *
 * Each arm is a strict superset of the one above it, so the DIFFERENCES attribute the
 * row and the last arm must reproduce the `CFG_LIST` figure. Nothing here is a gate.
 *
 * ```
 * java -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.GlobListProbeMainKt <projectDir> [reps]
 * ```
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [reps]" }
    val reps = if (args.size > 1) args[1].toInt() else 20

    // The real population: every directory the glob walk descends into, found the same
    // way the walk finds them and with the same pruning.
    val pruned = setOf("node_modules", ".git", "bower_components", "jspm_packages")
    val dirs = ArrayList<String>()
    val stack = ArrayDeque(listOf(PathUtil.normalize(File(args[0]).absolutePath)))
    while (stack.isNotEmpty()) {
        val d = stack.removeLast()
        dirs.add(d)
        for (e in SystemVfs.listEntries(d)) {
            if (e.isDirectory && PathUtil.basename(e.path) !in pruned) stack.addLast(e.path)
        }
    }
    var entries = 0
    for (d in dirs) entries += (File(d).listFiles()?.size ?: 0)
    require(dirs.size > 5) { "REFUSED: ${dirs.size} directories" }
    println("population: ${dirs.size} directories, $entries entries")

    var sink = 0L

    fun timeListFiles(): Long {
        val t0 = System.nanoTime()
        for (d in dirs) sink += (File(d).listFiles()?.size ?: 0).toLong()
        return System.nanoTime() - t0
    }
    fun timeIsDir(): Long {
        val t0 = System.nanoTime()
        for (d in dirs) for (c in File(d).listFiles() ?: emptyArray()) if (c.isDirectory) sink++
        return System.nanoTime() - t0
    }
    fun timePath(): Long {
        val t0 = System.nanoTime()
        for (d in dirs) for (c in File(d).listFiles() ?: emptyArray()) {
            if (c.isDirectory) sink++
            sink += c.path.length.toLong()
        }
        return System.nanoTime() - t0
    }
    fun timeNormalize(): Long {
        val t0 = System.nanoTime()
        for (d in dirs) for (c in File(d).listFiles() ?: emptyArray()) {
            if (c.isDirectory) sink++
            sink += PathUtil.normalize(c.path).length.toLong()
        }
        return System.nanoTime() - t0
    }
    fun timeListEntries(): Long {
        val t0 = System.nanoTime()
        for (d in dirs) sink += SystemVfs.listEntries(d).size.toLong()
        return System.nanoTime() - t0
    }
    // (INC.76) The two arms that matter for the LANGUAGE SERVICE, as opposed to the CLI.
    // `Vfs.listEntries`'s DEFAULT body is `list(path).map { VfsEntry(it, isDirectory(it)) }`
    // and `SystemVfs.isDirectory` is kotlinx-io's `metadataOrNull`, which (INC.60) measured
    // at up to FIVE `stat`s. A wrapping Vfs that does not OVERRIDE `listEntries` therefore
    // gets that body back — CLAUDE.md says so about a counting Vfs in a test, and
    // `OverlayVfs` is a wrapping Vfs on the shipped path of every `Project` build.
    fun timeDefaultBody(): Long {
        val t0 = System.nanoTime()
        for (d in dirs) sink += SystemVfs.list(d).map { VfsEntry(it, SystemVfs.isDirectory(it)) }.size.toLong()
        return System.nanoTime() - t0
    }
    fun timeOverlay(): Long {
        val overlay = OverlayVfs(SystemVfs)
        val t0 = System.nanoTime()
        for (d in dirs) sink += overlay.listEntries(d).sortedBy { it.path }.size.toLong()
        return System.nanoTime() - t0
    }

    fun timeSorted(): Long {
        val t0 = System.nanoTime()
        for (d in dirs) sink += SystemVfs.listEntries(d).sortedBy { it.path }.size.toLong()
        return System.nanoTime() - t0
    }

    val arms = listOf(
        "listFiles" to ::timeListFiles,
        "+isDir" to ::timeIsDir,
        "+path" to ::timePath,
        "+normalize" to ::timeNormalize,
        "listEntries" to ::timeListEntries,
        "+sorted" to ::timeSorted,
        "defaultBody" to ::timeDefaultBody,
        "overlay+sort" to ::timeOverlay,
    )
    repeat(5) { for ((_, f) in arms) f() }
    val draws = HashMap<String, MutableList<Long>>()
    repeat(reps) {
        for ((name, f) in arms) draws.getOrPut(name) { ArrayList() }.add(f())
        for ((name, f) in arms.reversed()) draws.getOrPut(name) { ArrayList() }.add(f())
    }
    var previous = 0L
    for ((name, _) in arms) {
        val xs = draws.getValue(name)
        xs.sort()
        val median = xs[xs.size / 2]
        val delta = if (name in setOf("listEntries", "defaultBody", "overlay+sort")) 0L else median - previous
        println(
            "%-12s median %7.3f ms   %6.0f us/dir  %6.0f ns/entry   delta %+7.3f ms"
                .format(name, median / 1e6, median / 1e3 / dirs.size, median.toDouble() / entries, delta / 1e6)
        )
        previous = median
    }
    println("sink=$sink")
}

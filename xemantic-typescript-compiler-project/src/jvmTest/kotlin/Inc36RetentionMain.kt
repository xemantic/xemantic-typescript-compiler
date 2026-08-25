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

import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.computeParserFlags
import com.xemantic.typescript.compiler.forEachChild
import java.io.File
import java.lang.reflect.Field

/**
 * (INC.36) step 1 — ATTRIBUTE the 264 MB a whole-program `referencesAt` retains.
 *
 * ## What the queue item asks and what this answers
 *
 * (INC.36) records the operational fact: a whole-program [Project.referencesAt] sweep
 * on tsc's own 78 compiler sources peaks at 1,077–1,125 MB in G1 old gen, retains
 * **264 MB** after a full GC, is green at `-Xmx2g` and OOMs at `-Xmx1g` — and says
 * explicitly that *the retention, not the peak, is the number to attack*, starting by
 * asking what the 264 MB IS rather than by trimming allocation (CLAUDE.md round 801
 * priced 367,189 removed `String` allocations at exactly 0 ms).
 *
 * This is that census. It is a SUBTRACTION LADDER in one process: `liveAfterGc` is
 * read at each of ten points, and every row is a delta against the row above it, so
 * each retainer is priced by what dropping it — and nothing else — returns. Nothing
 * is fixed here and nothing is asserted about a target; the runner asserts only its
 * own non-vacuity.
 *
 * ## Why the clears are REFLECTIVE and not test hooks
 *
 * Every step from `dropSourceIndexes` down clears one private field of [Project]
 * through `java.lang.reflect`, and the two process-global caches the same way. That
 * is deliberate: a measurement must not leave a production surface behind it, and a
 * `dropXForMeasurement()` on a public class is a surface. The cost of the choice is
 * that this file breaks if a field is RENAMED — which is why [field] fails loudly
 * with the class's whole field list rather than returning null.
 *
 * ## The controls, and why a heap ladder is worthless without them
 *
 * A heap number from a blind instrument reads exactly like a real one (CLAUDE.md
 * round 849), so three things are checked and printed rather than assumed:
 *
 *  * **non-vacuity** — the [Project.referencesAt] call must return a NON-EMPTY answer.
 *    An empty answer means the caret resolved to nothing and every row below it is a
 *    measurement of an early return.
 *  * **positive control** — `liveAfterGc` must RISE materially at the `referencesAt`
 *    step. If it does not, the premise of the queue item is wrong and that is the
 *    finding.
 *  * **attribution control** — the steps that should return nothing (`narrowed`,
 *    `recheck`, which this arm never fills) must in fact return ~0 MB. A ladder whose
 *    every step returns something is a ladder measuring GC noise.
 *
 * Two processes minimum for anything quoted as a result — the reading is a heap
 * occupancy after a collector's own decisions, not a counter.
 *
 * Modes: `ladder` (the census, default), `second` (the ladder plus a SECOND
 * [Project] opened in the same process, which prices what a second plugin project
 * costs once the process-global caches are warm), `reparse` (the timing half of the
 * recommendation: what bounding `sourceIndexes` would cost in re-parses).
 *
 * Companion to `Inc31ResidueMain`, whose `heap` arm produced the 264 MB this
 * decomposes; same profile, same process rules.
 */
private class Ladder {
    private val rows = ArrayList<Triple<String, Long, Long>>()
    private var previous = 0L

    fun read(tag: String) {
        val live = liveBytes()
        rows.add(Triple(tag, live, live - previous))
        previous = live
        println("LADDER %-26s live=%7.1fm delta=%+8.1fm".format(tag, live / MB, rows.last().third / MB))
    }

    fun deltaOf(tag: String): Long = rows.first { it.first == tag }.third

    companion object {
        const val MB = 1024.0 * 1024.0
    }
}

private fun liveBytes(): Long {
    val rt = Runtime.getRuntime()
    // Six rather than Inc31ResidueMain's four: the ladder subtracts adjacent rows, so
    // a single under-collected row corrupts TWO deltas rather than one.
    repeat(6) { rt.gc() }
    return rt.totalMemory() - rt.freeMemory()
}

private fun field(target: Any, name: String): Field {
    val cls = target.javaClass
    return try {
        cls.getDeclaredField(name).also { it.isAccessible = true }
    } catch (e: NoSuchFieldException) {
        error(
            "REFUSED: ${cls.name} has no field '$name' — this runner clears private " +
                "state reflectively and a rename silently makes a step inert. Fields: " +
                cls.declaredFields.joinToString { it.name },
        )
    }
}

@Suppress("UNCHECKED_CAST")
private fun clearMap(target: Any, name: String): Int {
    val map = field(target, name).get(target) as MutableMap<Any?, Any?>
    val n = map.size
    map.clear()
    return n
}

private fun nullOut(target: Any, name: String): Boolean {
    val f = field(target, name)
    val had = f.get(target) != null
    f.set(target, null)
    return had
}

/** The private `entries`/`parseCache` map of a Kotlin `object`, by class name. */
@Suppress("UNCHECKED_CAST")
private fun globalCache(className: String, fieldName: String): MutableMap<Any?, Any?>? = try {
    val cls = Class.forName(className)
    val instance = cls.getDeclaredField("INSTANCE").also { it.isAccessible = true }.get(null)
    cls.getDeclaredField(fieldName).also { it.isAccessible = true }
        .get(instance) as MutableMap<Any?, Any?>
} catch (e: Exception) {
    println("NOTE: $className.$fieldName unreachable (${e.javaClass.simpleName}) — not cleared")
    null
}

private fun nodeCount(root: Node): Int {
    var n = 0
    val stack = ArrayList<Node>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeAt(stack.size - 1)
        n++
        forEachChild(node) { child -> stack.add(child) }
    }
    return n
}

/**
 * A `jcmd GC.class_histogram` of THIS process, taken by an EXTERNAL helper.
 *
 * It is not taken by shelling out to `jcmd` with our own pid: that is a JVM
 * attaching to itself, and measured 2026-08-25 it HUNG the whole run — the ladder
 * printed its census row and then nothing, for thirty minutes, which reads exactly
 * like a slow measurement rather than a dead one. So the request goes out through a
 * file, the helper `scripts/inc36-retention.sh` starts answers it, and the wait has a
 * HARD DEADLINE after which this prints UNAVAILABLE and the ladder continues. A
 * histogram is corroboration here, never the finding; it may not be able to cost the
 * round.
 */
private fun histogram(tag: String, top: Int) {
    val dir = System.getenv("INC36_HIST_DIR")
    if (dir == null) {
        println("HISTOGRAM $tag SKIPPED (INC36_HIST_DIR unset — no helper to ask)")
        return
    }
    val request = File(dir, "request")
    val answer = File(dir, "answer")
    answer.delete()
    request.writeText("${ProcessHandle.current().pid()}\n$tag\n")
    val deadline = System.currentTimeMillis() + 90_000
    while (!answer.exists() && System.currentTimeMillis() < deadline) Thread.sleep(200)
    if (!answer.exists()) {
        println("HISTOGRAM $tag UNAVAILABLE: the helper did not answer within 90s")
        request.delete()
        return
    }
    val lines = answer.readText().lines().filter { it.isNotBlank() }
    println("HISTOGRAM $tag (jcmd GC.class_histogram, top $top)")
    lines.take(top + 3).forEach { println("  HIST $it") }
    lines.lastOrNull { it.contains("Total") }?.let { println("  HIST $it") }
    answer.delete()
    request.delete()
}

private fun med(v: MutableList<Long>): Long { v.sort(); return v[v.size / 2] }

fun main(args: Array<String>) {
    val dir = args[0]
    val mode = if (args.size > 1) args[1] else "ladder"
    println("mode=$mode maxHeap=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}m")

    val ladder = Ladder()
    ladder.read("0.baseline")

    val project = Project.open(dir)
    // `Project.files` BUILDS (it is `build().programFiles`), so it is read AFTER the
    // build step — asking here would fold a whole compile into the `open` row.
    ladder.read("1.open")

    val diagnostics = project.diagnostics()
    ladder.read("2.diagnostics")
    val files = project.files
    val mid = files.first { it.endsWith("src/compiler/binder.ts") }
    val big = files.first { it.endsWith("src/compiler/checker.ts") }
    println("BUILD files=${files.size} diagnostics=${diagnostics.size}")

    val text = File(mid).readText()
    val caret = text.indexOf("SyntaxKind")
    require(caret >= 0) { "REFUSED: the caret anchor is not in $mid — the arm is vacuous" }
    val at = System.nanoTime()
    val refs = project.referencesAt(mid, caret)
    val wall = (System.nanoTime() - at) / 1_000_000
    ladder.read("3.referencesAt")
    println("ANSWER referencesAt hits=${refs.size} wall=${wall}ms caret=$caret file=${mid.substringAfterLast('/')}")
    require(refs.isNotEmpty()) {
        "REFUSED: referencesAt returned NOTHING — every heap row below is a measurement " +
            "of an early return, not of a sweep."
    }

    // ---- what is actually resident, counted rather than inferred ----------------
    @Suppress("UNCHECKED_CAST")
    val indexes = field(project, "sourceIndexes").get(project) as Map<String, SourceIndex>
    var chars = 0L
    var nodes = 0L
    for (index in indexes.values) {
        chars += index.text.length.toLong()
        nodes += nodeCount(index.sourceFile).toLong()
    }
    val crawl = globalCache("com.xemantic.typescript.compiler.CrawlParseCache", "entries")
    val libs = globalCache("com.xemantic.typescript.compiler.RealLibSnapshots", "parseCache")
    println(
        "CENSUS sourceIndexes=${indexes.size} chars=$chars nodes=$nodes " +
            "crawlParseCache=${crawl?.size ?: -1} realLibParses=${libs?.size ?: -1} " +
            "capturedRefs=${refs.size}",
    )
    histogram("peak", 15)
    // The histogram's own full GC re-reads the same point; take it again so the
    // ladder's next delta is against a row measured the same way as the others.
    ladder.read("3b.afterHistogram")

    val droppedIndexes = clearMap(project, "sourceIndexes")
    ladder.read("4.dropSourceIndexes")
    println("STEP dropped sourceIndexes=$droppedIndexes")

    val droppedLineMaps = clearMap(project, "lineMaps")
    val droppedCaptures = clearMap(project, "captures")
    val hadPrepared = nullOut(project, "prepared")
    ladder.read("5.dropCaptures")
    println("STEP dropped lineMaps=$droppedLineMaps captures=$droppedCaptures prepared=$hadPrepared")

    val droppedNarrowed = clearMap(project, "narrowed")
    val hadRecheck = nullOut(project, "recheck")
    ladder.read("6.dropNarrowedRecheck")
    println("STEP dropped narrowed=$droppedNarrowed recheck=$hadRecheck")

    val hadCached = nullOut(project, "cached")
    ladder.read("7.dropCached")
    println("STEP dropped cached=$hadCached")

    project.close()
    ladder.read("8.close")

    val crawlSize = crawl?.size ?: -1
    crawl?.clear()
    ladder.read("9.dropCrawlParseCache")
    println("STEP dropped crawlParseCache=$crawlSize")

    val libSize = libs?.size ?: -1
    libs?.clear()
    ladder.read("10.dropRealLibParses")
    println("STEP dropped realLibParses=$libSize")
    histogram("afterEverything", 15)

    // ---- controls --------------------------------------------------------------
    var failed = false
    val arm = ladder.deltaOf("3.referencesAt")
    if (arm < 16L * 1024 * 1024) {
        println(
            "CONTROL FAILED positive: referencesAt returned ${arm / (1024.0 * 1024.0)}m — " +
                "the arm retains ~nothing beyond the build, so (INC.36)'s premise is wrong.",
        )
        failed = true
    } else {
        println("CONTROL positive: OK (referencesAt step = ${"%.1f".format(arm / (1024.0 * 1024.0))}m)")
    }
    val inert = ladder.deltaOf("6.dropNarrowedRecheck")
    if (inert < -16L * 1024 * 1024) {
        println(
            "CONTROL FAILED attribution: the narrowed/recheck step returned " +
                "${"%.1f".format(-inert / (1024.0 * 1024.0))}m, but this arm never fills " +
                "either — the ladder is measuring GC noise, not retainers.",
        )
        failed = true
    } else {
        println("CONTROL attribution: OK (inert step = ${"%.1f".format(-inert / (1024.0 * 1024.0))}m)")
    }

    if (mode == "second") {
        // What does a SECOND project cost, with every process-global cache cold again
        // (they were just cleared) versus warm? Only the warm half is asked here: the
        // caches were cleared above, so this rebuilds them and its own retention is
        // the honest per-project figure INCLUDING the shared part it re-earns.
        val other = Project.open(dir)
        other.diagnostics()
        ladder.read("11.secondProject.diagnostics")
        val otherRefs = other.referencesAt(mid, caret)
        ladder.read("12.secondProject.referencesAt")
        println("SECOND hits=${otherRefs.size}")
        other.close()
        ladder.read("13.secondProject.close")
    }

    if (mode == "reparse" || mode == "second" || mode == "ladder") {
        // The cost side of the recommendation: what one re-parse costs, measured, so
        // an (INC.32)-style weight-bounded LRU can be priced rather than guessed.
        val options = TsConfigLoader(SystemVfs).load(project.configPath).options
        println("== SourceIndex.of cost (ms, median of 5) ==")
        val sizes = files.map { it to File(it).length() }.sortedBy { it.second }
        val medianFile = sizes[sizes.size / 2].first
        for (f in listOf(medianFile, mid, big)) {
            val t = File(f).readText()
            val flags = computeParserFlags(f, t, options)
            SourceIndex.of(t, f, flags)
            val draws = ArrayList<Long>()
            repeat(5) {
                val start = System.nanoTime()
                SourceIndex.of(t, f, flags)
                draws.add((System.nanoTime() - start) / 1_000_000)
            }
            println("REPARSE ${f.substringAfterLast('/')} chars=${t.length} ms=${med(draws)}")
        }
    }

    if (failed) {
        println("REFUSED: a control failed — do not quote these rows.")
        kotlin.system.exitProcess(3)
    }
}

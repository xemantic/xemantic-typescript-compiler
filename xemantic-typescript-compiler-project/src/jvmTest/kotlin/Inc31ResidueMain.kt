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
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.NodeKind
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.computeParserFlags
import com.xemantic.typescript.compiler.forEachChild
import java.io.File
import java.lang.management.ManagementFactory

/**
 * (INC.31) step 1b — decompose the PER-CARET RESIDUE, price a REAL keystroke, take
 * the COLD file-wide rows, and read PEAK HEAP per pool.
 *
 * ## It grades a lever that was MEASURED AND REFUSED, and that is why it is kept
 *
 * The residue arm exists to price memoizing `SourceIndex.occurrenceNodes()` and the
 * span/covered-set derivation `Project.captureAround` redoes on every memo HIT. Run
 * 2026-08-24 it said: on `checker.ts` (3.15 MB, 125,289 occurrences) that prologue is
 * **82.7 ms of an 83 ms second-caret hover** — essentially all of it — while at the
 * MEDIAN file the whole prize is **1 – 2 ms**, which is below this repo's floor for
 * spending a round. So the lever was refused as a general win and stands only as a
 * TAIL fix for buffers over ~1 MB (`docs/language-service.md` § 14 carries the table
 * and the refusal).
 *
 * A refusal is only as durable as the instrument that can re-open it: whoever
 * proposes that memo next has to produce a number, and this is where the number comes
 * from. It also carries three cells no other runner here has — the walk-vs-sort split,
 * `diagnosticsOf` after a REAL keystroke (every other harness dirties a file by
 * writing its own bytes back, which is the cheapest possible dirty state), and the
 * per-POOL heap reading that corrected this page's "~1.9 GB" claim to "1.1 GB peak in
 * old gen, 264 MB retained, `-Xmx2g` floor".
 *
 * Companion to `Inc31CostMain`; same profile, same process rules. Asserts nothing
 * except its own non-vacuity (the injected-error arm must actually report).
 */
private fun us(block: () -> Unit): Long {
    val at = System.nanoTime()
    block()
    return (System.nanoTime() - at) / 1_000
}

private fun ms(block: () -> Unit): Long {
    val at = System.nanoTime()
    block()
    return (System.nanoTime() - at) / 1_000_000
}

private fun med(v: MutableList<Long>): Long { v.sort(); return v[v.size / 2] }

fun main(args: Array<String>) {
    val dir = args[0]
    val mode = if (args.size > 1) args[1] else "all"
    val project = Project.open(dir)
    val files = project.files
    val big = files.first { it.endsWith("src/compiler/checker.ts") }
    val mid = files.first { it.endsWith("src/compiler/binder.ts") }
    val small = files.first { it.endsWith("src/compiler/semver.ts") }
    val options = TsConfigLoader(SystemVfs).load(project.configPath).options

    if (mode == "heap") {
        // The operational question: what -Xmx does a whole-program sweep NEED?
        val text = File(mid).readText()
        val caret = text.indexOf("SyntaxKind")
        project.diagnostics()
        for (pool in ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage()
        val wall = ms { project.referencesAt(mid, caret) }
        println("heap arm: referencesAt=${wall}ms max=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}m")
        for (pool in ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.type == java.lang.management.MemoryType.HEAP) {
                println("  pool ${pool.name} peak=${pool.peakUsage.used / (1024 * 1024)}m")
            }
        }
        val rt = Runtime.getRuntime()
        repeat(4) { rt.gc() }
        println("  liveAfterGc=${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)}m")
        project.close()
        return
    }

    // ---- residue components on three file sizes --------------------------------
    println("== residue components (us, median of 25) ==")
    for (f in listOf(small, mid, big)) {
        val text = File(f).readText()
        val index = SourceIndex.of(text, f, computeParserFlags(f, text, options))
        val ids = index.identifiers()
        val occ = index.occurrenceNodes()
        // a) the WALK alone, no sort — a private copy of `identifiers()`'s body
        fun walkOnly(): ArrayList<Node> {
            val found = ArrayList<Node>()
            val stack = ArrayList<Node>()
            stack.add(index.sourceFile)
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.size - 1)
                if ((node as NodeBase).kindId == NodeKind.IDENTIFIER) found.add(node)
                forEachChild(node) { child -> stack.add(child) }
            }
            return found
        }
        val walk = ArrayList<Long>()
        val sortOnly = ArrayList<Long>()
        val identifiers = ArrayList<Long>()
        val occurrences = ArrayList<Long>()
        val spanMap = ArrayList<Long>()
        val coveredSet = ArrayList<Long>()
        val containment = ArrayList<Long>()
        walkOnly(); index.identifiers(); index.occurrenceNodes()
        repeat(25) {
            var w: ArrayList<Node>? = null
            walk.add(us { w = walkOnly() })
            val copy = ArrayList(w!!)
            sortOnly.add(us { copy.sortWith(compareBy({ it.pos }, { it.end })) })
            identifiers.add(us { index.identifiers() })
            occurrences.add(us { index.occurrenceNodes() })
            var spans: List<TypeCaptureSpan>? = null
            spanMap.add(us { spans = occ.map { TypeCaptureSpan(f, it.pos, it.end) } })
            var covered: HashSet<Long>? = null
            coveredSet.add(
                us {
                    val s = HashSet<Long>(spans!!.size * 2)
                    for (sp in spans) s.add((sp.start.toLong() shl 32) or sp.end.toLong())
                    covered = s
                },
            )
            containment.add(
                us { for (sp in spans!!) covered!!.contains((sp.start.toLong() shl 32) or sp.end.toLong()) },
            )
        }
        println(
            "COMP ${f.substringAfterLast('/')} chars=${text.length} ids=${ids.size} occ=${occ.size} " +
                "walk=${med(walk)} sort=${med(sortOnly)} identifiers()=${med(identifiers)} " +
                "occurrenceNodes()=${med(occurrences)} spanMap=${med(spanMap)} " +
                "coveredSet=${med(coveredSet)} containment=${med(containment)} " +
                "TOTALprologue=${med(occurrences) + med(spanMap) + med(coveredSet) + med(containment)}",
        )
    }

    // ---- REAL keystrokes: what an error-reporting host actually pays ------------
    val midText = File(mid).readText()
    val bodyAt = midText.lastIndexOf(") {")
    fun withBody(insert: String) =
        midText.substring(0, bodyAt + 3) + "\n" + insert + midText.substring(bodyAt + 3)
    repeat(8) { project.updateFile(mid, midText); project.diagnosticsOf(listOf(mid)) }
    println("== real keystroke, diagnosticsOf(oneFile) ==")
    val same = ArrayList<Long>()
    val comment = ArrayList<Long>()
    val body = ArrayList<Long>()
    val err = ArrayList<Long>()
    val fullSame = ArrayList<Long>()
    val fullBody = ArrayList<Long>()
    val coldHl = ArrayList<Long>()
    val coldSem = ArrayList<Long>()
    val hlSecond = ArrayList<Long>()
    val caret = midText.indexOf("SourceFile")
    val caret2 = midText.lastIndexOf("SourceFile")
    repeat(4) { r ->
        project.updateFile(mid, midText)
        same.add(ms { project.diagnosticsOf(listOf(mid)) })
        project.updateFile(mid, midText + "\n// keystroke $r\n")
        comment.add(ms { project.diagnosticsOf(listOf(mid)) })
        project.updateFile(mid, withBody("    const xtscProbe$r = 1;\n"))
        body.add(ms { project.diagnosticsOf(listOf(mid)) })
        var n = 0
        project.updateFile(mid, withBody("    const xtscProbe$r: number = \"s\";\n"))
        err.add(ms { n = project.diagnosticsOf(listOf(mid)).count { it.code == 2322 } })
        require(n > 0) { "REFUSED: the injected error is not reported — the arm is vacuous" }
        project.updateFile(mid, midText)
        fullSame.add(ms { project.diagnostics() })
        project.updateFile(mid, withBody("    const xtscProbe$r = 2;\n"))
        fullBody.add(ms { project.diagnostics() })
        // COLD file-wide rows: no hover in front of them
        project.updateFile(mid, midText)
        coldHl.add(ms { project.documentHighlightsAt(mid, caret) })
        hlSecond.add(ms { project.documentHighlightsAt(mid, caret2) })
        project.updateFile(mid, midText)
        coldSem.add(ms { project.fileSemantics(mid) })
    }
    println(
        "KEYSTROKE dirtySameBytes=${med(same)} comment=${med(comment)} body=${med(body)} " +
            "introducesError=${med(err)} | fullBuild.same=${med(fullSame)} fullBuild.body=${med(fullBody)}",
    )
    println("COLD highlights.first=${med(coldHl)} highlights.second=${med(hlSecond)} fileSemantics=${med(coldSem)}")
    project.close()
}

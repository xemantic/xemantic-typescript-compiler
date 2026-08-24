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

import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.RecheckHolder
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.TsConfigLoader
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.computeParserFlags

/**
 * (INC.41) THE CARET-CHANNEL PRIZE — what ONE hover costs served from a fresh
 * narrowed build (what ships) against the same hover served by re-entering a live
 * checker (what the `DiagnosticsOnlyRecheck` valve refuses).
 *
 * `Inc40ReplayCostMain` priced the DIAGNOSTICS query, whose request carries no
 * capture at all. A hover's request does, and (INC.13) measured a whole-file capture
 * at +9-17 ms over a bare narrowed build — so the diagnostics ratio cannot simply be
 * inherited, exactly as CLAUDE.md's "a ratio is a property of what BOTH arms were
 * asked" says. This asks both arms for the SAME single caret.
 *
 * The row a user feels is the FIRST hover in a buffer: `quickInfoAt` memoises per
 * buffer, so a second caret is already ~2 ms, and ANY edit drops the handle. So the
 * population this prices is *the first hover in a file at a program state some other
 * query already built for*, and nothing else.
 *
 * ABBA-rotated per rotation (round 869: the first instrumented draw is the slowest,
 * and an unrotated ladder puts all of that on one arm), warm, medians per arm.
 *
 * ```
 * java -cp ... Inc41HoverPriceMainKt <projectDir> [rotations] [warmups] [maxTargets]
 * ```
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [rotations] [warmups] [maxTargets]" }
    val vfs = SystemVfs
    val project = vfs.resolveAbsolute(args[0])
    val rotations = if (args.size > 1) args[1].toInt() else 4
    val warmups = if (args.size > 2) args[2].toInt() else 6
    val maxTargets = if (args.size > 3) args[3].toInt() else 40

    val compiler = ProjectCompiler(vfs)
    val configPath = if (vfs.isDirectory(project)) "$project/tsconfig.json" else project
    val options = TsConfigLoader(vfs).load(configPath).options

    var probe = compiler.build(project, noEmit = true)
    repeat(warmups - 1) { probe = compiler.build(project, noEmit = true) }
    val programFiles = probe.programFiles
    require(programFiles.size >= 4) { "REFUSED: a program of ${programFiles.size} files" }

    // ONE caret per file — the identifier nearest the file's midpoint, which is a
    // position-independent choice and not a cherry-picked cheap one.
    val caret = LinkedHashMap<String, TypeCaptureSpan>()
    for (file in programFiles) {
        val text = vfs.readText(file) ?: continue
        val ids = SourceIndex.of(text, file, computeParserFlags(file, text, options)).identifiers()
        if (ids.isEmpty()) continue
        val mid = text.length / 2
        val pick = ids.minByOrNull { kotlin.math.abs(it.pos - mid) } ?: continue
        caret[file] = TypeCaptureSpan(file, pick.pos, pick.end)
    }
    val files = caret.keys.toList()
    val seed = files.first()
    val targets = files.drop(1).take(maxTargets)
    require(targets.size >= 4) { "REFUSED: ${targets.size} targets" }
    println("project: $project")
    println("program: files=${programFiles.size}  seed=${seed.substringAfterLast('/')}  targets=${targets.size}")
    println("rotations=$rotations warmups=$warmups")

    fun median(xs: List<Long>): Long = xs.sorted()[xs.size / 2]

    val freshDraws = ArrayList<Long>()
    val replayDraws = ArrayList<Long>()
    val armDraws = ArrayList<Long>()
    var freshCaptured = 0
    var replayCaptured = 0

    fun freshArm() {
        for (f in targets) {
            val t0 = System.nanoTime()
            val r = compiler.build(
                project, noEmit = true, recheckOnly = setOf(f),
                typeCapture = TypeCaptureRequest(listOf(caret.getValue(f))),
            )
            freshDraws.add((System.nanoTime() - t0) / 1_000_000)
            if (r.capturedTypes.any { it.fileName == f }) freshCaptured++
        }
    }

    fun replayArm() {
        val holder = RecheckHolder()
        val t0 = System.nanoTime()
        compiler.build(
            project, noEmit = true, recheckOnly = setOf(seed),
            typeCapture = TypeCaptureRequest(listOf(caret.getValue(seed))),
            recheckHolder = holder,
        )
        armDraws.add((System.nanoTime() - t0) / 1_000_000)
        val recheck = requireNotNull(holder.recheck) {
            "REFUSED: no ProgramRecheck — the replay arm would be a copy of the fresh one"
        }
        for (f in targets) {
            val t1 = System.nanoTime()
            val a = recheck.recheck(setOf(f), TypeCaptureRequest(listOf(caret.getValue(f))))
            replayDraws.add((System.nanoTime() - t1) / 1_000_000)
            if (a.capturedTypes.any { it.fileName == f }) replayCaptured++
        }
    }

    for (r in 0 until rotations) {
        if (r % 2 == 0) { freshArm(); replayArm() } else { replayArm(); freshArm() }
    }

    // THE VACUITY CONTROLS. An arm that captured nothing is not a hover.
    val want = targets.size * rotations
    require(freshCaptured > want / 2) {
        "REFUSED: the fresh arm captured a type for only $freshCaptured of $want carets"
    }
    require(replayCaptured > want / 2) {
        "REFUSED: the replay arm captured a type for only $replayCaptured of $want carets"
    }

    val f = median(freshDraws)
    val p = median(replayDraws)
    println("captured: fresh=$freshCaptured replay=$replayCaptured of $want")
    println("arming (the seed build the handle comes from): median=${median(armDraws)} ms  draws=${armDraws.size}")
    println("ONE hover, fresh narrowed build : median=$f ms  p90=${freshDraws.sorted()[(freshDraws.size * 9) / 10]}  n=${freshDraws.size}")
    println("ONE hover, replay re-entry      : median=$p ms  p90=${replayDraws.sorted()[(replayDraws.size * 9) / 10]}  n=${replayDraws.size}")
    println("ratio = %.2fx   saving = %d ms per first-hover-in-a-new-file".format(f.toDouble() / p, f - p))
}

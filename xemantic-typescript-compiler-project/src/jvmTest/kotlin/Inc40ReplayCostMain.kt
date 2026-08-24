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

/**
 * (INC.40) **RE-PRICE THE RE-ENTRANT REPLAY FOR THE DIAGNOSTICS CHANNEL ONLY.**
 *
 * CLAUDE.md's standing law: *every round that shrinks the floor shrinks the
 * re-entrant replay's reason to exist — its advantage fell 3.06x -> 1.91x -> 1.68x
 * across this arc without the replay changing at all; quote a replay speed-up with
 * the floor it was measured against, and re-price it before spending a round on
 * it.* The floor has since fallen 129 -> 58 -> ~56 ms and (INC.31/32/37) landed, so
 * the 1.68x is stale too.
 *
 * ## What is different from `ReplayDifferentialMain`
 *
 * That runner is a CORRECTNESS differential and it hands BOTH arms a
 * `TypeCaptureRequest` for every identifier in the file, because its sensitive
 * channel is the capture one. This runner asks the cost question for the channel
 * the replay is graded EQUIVALENT on — diagnostics — so it passes **no capture
 * request at all**, in either arm. That is not a detail: (INC.13) measured a
 * whole-file capture at +9 to +17 ms per query, which both arms would otherwise pay
 * and which dilutes the very ratio being read.
 *
 * ## The two arms
 *
 * ```
 * FRESH  : build(recheckOnly = G)                  — what Project.diagnosticsOf does
 *                                                     on a dirty project
 * REPLAY : recheck(G) on a handle from ONE seed build
 * ```
 *
 * The seed build is the premise ("an already-built program"), so it is reported
 * separately and is NOT charged to the replay arm's per-query figures.
 *
 * ## Discipline
 *
 * * `warmups` whole-program builds first, then a DISCARDED rotation of both arms —
 *   CLAUDE.md 2026-08-10: two identical arms sit 3.3% apart at WARMUP=2 and 0.8%
 *   at 6, and the leading draw of any ladder is the slowest.
 * * arms are ABBA-rotated within each rotation, so the leading-draw bias does not
 *   land wholly on one of them (round 869/891).
 * * every draw is printed; the verdict quotes MEDIANS and the FLOOR they were
 *   measured against.
 * * the replay handle is rebuilt per rotation, because `walkedFiles` only grows and
 *   a file already walked costs nothing — a reused handle would read a second
 *   rotation as free.
 *
 * ## Controls
 *
 * * the floor is re-measured in the same process (`recheckOnly` naming a file the
 *   program does not contain) and REFUSED if it reports a diagnostic;
 * * the full build must report diagnostics, or a narrowed arm's zero is not
 *   evidence of anything (round 806 vacuity);
 * * `walkedFiles` must grow by exactly the number of files rechecked, or the replay
 *   answered from the seed partition and every figure is about work that never
 *   happened;
 * * the two arms' DIAGNOSTIC ROWS are compared per group, and a mismatch is
 *   reported — this is a cost runner, but a cost runner whose arms disagree is
 *   pricing two different things.
 *
 * ```
 * scripts/inc40-replay-cost.sh [<projectDir> [rotations [warmups [groupSizes]]]]
 * ```
 */
private const val NOWHERE = "/no/such/file/the/program/does/not/contain.ts"

private fun median(xs: List<Long>): Long {
    if (xs.isEmpty()) return 0
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [rotations] [warmups] [groupSizes]" }
    val project = SystemVfs.resolveAbsolute(args[0])
    val rotations = if (args.size > 1) args[1].toInt() else 3
    val warmups = if (args.size > 2) args[2].toInt() else 6
    val groupSizes = (if (args.size > 3) args[3] else "1,2,8").split(',').map { it.trim().toInt() }

    val compiler = ProjectCompiler(SystemVfs)
    fun full() = compiler.build(project, noEmit = true)

    println("project: $project")
    println("rotations=$rotations warmups=$warmups groupSizes=$groupSizes")

    // ---- warm-up + the program, and the two vacuity controls.
    var probeFull = full()
    require(probeFull.diagnostics.isNotEmpty()) {
        "REFUSED: the full build reports NO diagnostics, so an arm's agreement is " +
            "vacuous. Point this at a real project."
    }
    repeat(warmups - 1) { probeFull = full() }
    val programFiles = probeFull.programFiles
    require(programFiles.size >= 4) { "REFUSED: a program of ${programFiles.size} files" }
    println("program: files=${programFiles.size} diagnostics=${probeFull.diagnostics.size}")

    // The FLOOR is measured at the END, not here. A floor build exercises a code
    // path the whole-program warm-ups do not (it checks no file at all), so draws
    // taken before the arms read a warm-up ramp: measured, 129/89/96 ms before
    // against 55-62 after, where `scripts/partition-equivalence.sh` reads 54-62.
    // Same trap as round 869's leading draw, one arm over.
    // The seed stands for "the buffer the host named"; every other file is one the
    // seed checker was NOT asked about, which is the population the replay exists for.
    val seed = programFiles.first()
    val targets = programFiles.drop(1)

    fun rowsOf(rows: List<com.xemantic.typescript.compiler.Diagnostic>, group: Set<String>): List<String> =
        rows.filter { it.fileName in group }
            .map { "${it.fileName}|TS${it.code}@${it.start}+${it.length}|${it.message}" }
            .sorted()

    class ArmResult(val perQuery: List<Long>, val totalMs: Long, val seedMs: Long)

    fun freshArm(groups: List<List<String>>): ArmResult {
        val per = ArrayList<Long>()
        var total = 0L
        for (g in groups) {
            val t0 = System.nanoTime()
            val r = compiler.build(project, noEmit = true, recheckOnly = g.toSet())
            val ms = (System.nanoTime() - t0) / 1_000_000
            per.add(ms); total += ms
            freshRows[g.first()] = rowsOf(r.diagnostics, g.toSet())
        }
        return ArmResult(per, total, 0)
    }

    // (INC.40) What ARMING costs. `Project.diagnosticsOf` now passes a RecheckHolder
    // on the first narrowed query of a project state, which installs the
    // `RecheckWitnessList` over the diagnostics list and makes the checker retain
    // itself. `ProjectRecheckTest` pins that this is behaviour-FREE; this prices it.
    fun freshArmedArm(groups: List<List<String>>): ArmResult {
        val per = ArrayList<Long>()
        var total = 0L
        for (g in groups) {
            val holder = RecheckHolder()
            val t0 = System.nanoTime()
            val r = compiler.build(
                project, noEmit = true, recheckOnly = g.toSet(), recheckHolder = holder,
            )
            val ms = (System.nanoTime() - t0) / 1_000_000
            require(holder.recheck != null) {
                "REFUSED: the armed arm got no handle back, so it is a copy of the plain one"
            }
            per.add(ms); total += ms
            armedRows[g.first()] = rowsOf(r.diagnostics, g.toSet())
        }
        return ArmResult(per, total, 0)
    }

    fun replayArm(groups: List<List<String>>): ArmResult {
        val holder = RecheckHolder()
        val t0 = System.nanoTime()
        compiler.build(
            project,
            noEmit = true,
            recheckOnly = setOf(seed),
            recheckHolder = holder,
        )
        val seedMs = (System.nanoTime() - t0) / 1_000_000
        val recheck = requireNotNull(holder.recheck) {
            "REFUSED: the build handed back no ProgramRecheck — the replay arm would " +
                "silently be a second copy of the fresh arm"
        }
        val per = ArrayList<Long>()
        var total = 0L
        var walked = recheck.walkedFiles.size
        for (g in groups) {
            val at = System.nanoTime()
            val answer = recheck.recheck(g.toSet())
            val ms = (System.nanoTime() - at) / 1_000_000
            per.add(ms); total += ms
            replayRows[g.first()] = rowsOf(answer.diagnostics, g.toSet())
            val now = recheck.walkedFiles.size
            require(now == walked + g.size) {
                "REFUSED: walkedFiles went $walked -> $now over a ${g.size}-file recheck " +
                    "— the replay did not widen its partition, so this is not a measurement"
            }
            walked = now
        }
        replayedPasses = recheck.replayedPasses.size
        return ArmResult(per, total, seedMs)
    }

    // per-k accumulators across rotations
    val freshTotals = LinkedHashMap<Int, MutableList<Long>>()
    val replayTotals = LinkedHashMap<Int, MutableList<Long>>()
    val seedTotals = LinkedHashMap<Int, MutableList<Long>>()
    val armedTotals = ArrayList<Long>()
    val armedPer = ArrayList<Long>()
    val freshPer = LinkedHashMap<Int, MutableList<Long>>()
    val replayPer = LinkedHashMap<Int, MutableList<Long>>()
    var mismatches = 0

    for (rot in 0..rotations) {
        val discard = rot == 0
        for (k in groupSizes) {
            val groups = targets.chunked(k)
            freshRows.clear(); replayRows.clear()
            // ABBA: fresh first on even rotations, replay first on odd ones.
            val fresh: ArmResult
            val replay: ArmResult
            if (rot % 2 == 0) {
                fresh = freshArm(groups); replay = replayArm(groups)
            } else {
                replay = replayArm(groups); fresh = freshArm(groups)
            }
            // The arming price, at k = 1 only — one extra sweep per rotation, and the
            // arm the wiring's FIRST query pays. Its rows must equal the plain arm's:
            // arming that changed an answer would make "behaviour-free" a fiction.
            if (k == groupSizes.first()) {
                armedRows.clear()
                val armed = freshArmedArm(groups)
                var armedBad = 0
                for (key in freshRows.keys) if (freshRows[key] != armedRows[key]) armedBad++
                if (!discard) {
                    require(armedBad == 0) {
                        "REFUSED: arming changed $armedBad group(s)' diagnostic rows"
                    }
                    armedTotals.add(armed.totalMs)
                    armedPer.addAll(armed.perQuery)
                }
            }
            var bad = 0
            for (key in freshRows.keys) if (freshRows[key] != replayRows[key]) bad++
            if (bad > 0 && !discard) {
                mismatches += bad
                println("  MISMATCH k=$k rot=$rot groups=$bad of ${groups.size}")
            }
            if (discard) {
                println("rot=$rot k=$k DISCARDED (leading draw) fresh=${fresh.totalMs} replay=${replay.totalMs}")
                continue
            }
            freshTotals.getOrPut(k) { ArrayList() }.add(fresh.totalMs)
            replayTotals.getOrPut(k) { ArrayList() }.add(replay.totalMs)
            seedTotals.getOrPut(k) { ArrayList() }.add(replay.seedMs)
            freshPer.getOrPut(k) { ArrayList() }.addAll(fresh.perQuery)
            replayPer.getOrPut(k) { ArrayList() }.addAll(replay.perQuery)
            println(
                "rot=$rot k=$k queries=${groups.size} " +
                    "fresh=${fresh.totalMs} ms replay=${replay.totalMs} ms seed=${replay.seedMs} ms " +
                    "ratio=${"%.3f".format(fresh.totalMs.toDouble() / replay.totalMs.coerceAtLeast(1))}",
            )
        }
    }

    println("replayedPasses=$replayedPasses of 417 init rows")
    println("diagnosticRowMismatches=$mismatches")
    println()
    println("k  queries  freshTotalMed  replayTotalMed  ratio  freshPerQueryMed  replayPerQueryMed  seedMed")
    for (k in groupSizes) {
        val ft = freshTotals[k] ?: continue
        val rt = replayTotals.getValue(k)
        val fm = median(ft); val rm = median(rt)
        println(
            "$k  ${targets.chunked(k).size}  $fm  $rm  " +
                "${"%.3f".format(fm.toDouble() / rm.coerceAtLeast(1))}  " +
                "${median(freshPer.getValue(k))}  ${median(replayPer.getValue(k))}  " +
                "${median(seedTotals.getValue(k))}",
        )
        println("   freshTotalDraws=$ft")
        println("   replayTotalDraws=$rt")
    }
    val floorDraws = ArrayList<Long>()
    repeat(4) {
        val t0 = System.nanoTime()
        val f = compiler.build(project, noEmit = true, recheckOnly = setOf(NOWHERE))
        floorDraws.add((System.nanoTime() - t0) / 1_000_000)
        require(f.diagnostics.isEmpty()) {
            "REFUSED: the floor build reported ${f.diagnostics.size} diagnostics — " +
                "recheckOnly did not narrow the checker to nothing."
        }
    }
    println(
        "arming: freshArmedTotalMed=${median(armedTotals)} ms perQueryMed=${median(armedPer)} ms " +
            "against plain fresh ${median(freshTotals.getValue(groupSizes.first()))} / " +
            "${median(freshPer.getValue(groupSizes.first()))} ms  draws=$armedTotals",
    )
    println("floorMedian=${median(floorDraws)} ms draws=$floorDraws")
    require(mismatches == 0) {
        "REFUSED: $mismatches group(s) had DIFFERENT diagnostic rows between the arms — " +
            "the ratio above prices two different answers"
    }
}

private val freshRows = LinkedHashMap<String, List<String>>()
private val replayRows = LinkedHashMap<String, List<String>>()
private val armedRows = LinkedHashMap<String, List<String>>()
private var replayedPasses = 0

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

import com.xemantic.typescript.compiler.PassTiming
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SpineDispatch
import com.xemantic.typescript.compiler.SystemVfs

/**
 * (INC.37) step 1 — DECOMPOSE **ONE FILE'S OWN CHECKING**.
 *
 * `FloorDecompositionMain` (INC.3) decomposes the incremental FLOOR — what a
 * build costs when the checker checks NO file. That floor is now 58 ms against a
 * 108-113 ms median `diagnosticsOf(oneFile)`, so the DOMINANT remaining term on
 * the error-reporting path is the queried file's OWN checking, and **nothing in
 * this repo splits it**. This runner does.
 *
 * The quantity is a DIFFERENCE, and both of its arms are stated:
 *
 * ```
 * own(F) = build(recheckOnly = {F})  −  build(recheckOnly = {a name not in the program})
 * ```
 *
 * taken per PASS as well as per wall, so the answer is not one number but a
 * table: the per-pass delta between the two arms attributes `own(F)` to
 * `checkSpine` and to whichever tail walkers are partition-scoped, with every
 * floor-resident row cancelling to ~0 by construction (which is itself the
 * instrument's control — a floor-resident row that does NOT cancel means the two
 * arms differ in something other than the partition).
 *
 * Four tiers per target, all opt-in and all behaviour-free when off:
 *
 * * **plain** — no probe at all. The wall column, and the only one quotable as
 *   a price.
 * * **`rows`** ([PassTiming] tier 1) — the ~480-row per-pass table. Round 846
 *   measured the `full` tier's own cost landing ~100% inside `checkSpine`,
 *   which is exactly the row this question turns on, so the per-pass ms are
 *   taken HERE and nowhere else (`rows` is ~0.25% cold, 0.0% warm).
 * * **`full`** ([PassTiming] tier 3) — the outermost-guarded sub-counters
 *   `relationNanos` / `typeNodeNanos` / `memberResolveNanos` /
 *   `narrowWalkNanos` / `typeOfExprNanos`. These are read as SHARES of the
 *   same arm's own `checkSpine`, never as absolute ms, and are then applied to
 *   the `rows` arm's `checkSpine` — because the tier that measures them is the
 *   tier that inflates their denominator. The `full − rows` delta on
 *   `checkSpine` prices that inflation in situ rather than inheriting a figure.
 * * **`dispatch`** ([SpineDispatch.PROBE]) — the per-HANDLER split, with the
 *   probe's own timestamp-pair cost measured by an empty handler slot and
 *   subtracted. Round 847 established the warm per-handler ordering over a
 *   WHOLE-PROGRAM walk; a single-file partition is a different population and
 *   the ordering has to be re-measured, not inherited.
 *
 * The target ladder is a SIZE ladder (default: 6 files spanning ~1.5 KB to
 * 3.15 MB), because the interesting question is not the median file — it is
 * whether `checker.ts` at ~1,650 ms is simply 16x the mid file (linear in size,
 * so the lever is a constant factor) or SUPER-linear (so the lever is whatever
 * is quadratic). Every arm is drawn twice in a palindrome so a linear drift
 * cancels within the pair, and a plain batch is taken EARLY and LATE so the
 * process's residual drift is quoted rather than assumed.
 *
 * ```
 * scripts/file-check-decomposition.sh [<projectDir> [warmups]]
 * ```
 */
private const val NOWHERE = "/no/such/file/the/program/does/not/contain.ts"

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [warmups]" }
    val project = args[0]
    val warmups = if (args.size > 1) args[1].toInt() else 6
    val compiler = ProjectCompiler(SystemVfs)

    fun full() = compiler.build(project, noEmit = true)
    fun narrow(f: String) = compiler.build(project, noEmit = true, recheckOnly = setOf(f))

    fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    // ---- sanity + the target ladder, derived from the program itself.
    val probeFull = full()
    val probeFloor = narrow(NOWHERE)
    require(probeFull.diagnostics.isNotEmpty()) {
        "REFUSED: the full build reports NO diagnostics, so a narrowed arm's own " +
            "zero is not evidence the checker was narrowed. Point this at a real project."
    }
    require(probeFloor.diagnostics.isEmpty()) {
        "REFUSED: the floor build reported ${probeFloor.diagnostics.size} diagnostics — " +
            "`recheckOnly` did not narrow the checker to nothing, so this is not a floor."
    }
    val sizes = HashMap<String, Long>()
    for (f in probeFull.programFiles) sizes[f] = (SystemVfs.readText(f)?.length ?: 0).toLong()
    val bySize = probeFull.programFiles.sortedBy { sizes[it] ?: 0L }
    println("program: files=${bySize.size} diagnostics=${probeFull.diagnostics.size}")

    // The ladder: an env override, else ~evenly spaced decades of source size
    // plus the two extremes. Reported with their sizes so the scaling question
    // is answerable from the artifact alone.
    val override = System.getenv("XTSC_FCD_FILES")
    val targets: List<String> = if (!override.isNullOrBlank()) {
        override.split(",").map { needle ->
            bySize.firstOrNull { it.endsWith(needle.trim()) }
                ?: error("REFUSED: no program file ends with '${needle.trim()}'")
        }
    } else {
        val wanted = listOf(1_500L, 18_000L, 44_000L, 194_000L, 540_000L, 3_100_000L)
        wanted.map { w -> bySize.minByOrNull { kotlin.math.abs((sizes[it] ?: 0L) - w) }!! }
            .distinct()
    }
    for (t in targets) println("TARGET ${sizes[t]} $t")

    // ---- warm up and discard. The mix matters: a narrowed build and a full one
    // do not warm the same methods, and every measured arm below is narrowed.
    repeat(warmups) { full(); narrow(targets.last()); narrow(NOWHERE) }

    // ---- probe control.
    fun ptOn(detail: Boolean) {
        PassTiming.reset()
        PassTiming.detail = detail
        PassTiming.spineDetail = false
        PassTiming.enabled = true
    }
    fun ptOff() { PassTiming.enabled = false; PassTiming.detail = true }

    fun dumpPt(arm: String, target: String, draw: Int, wallMs: Long) {
        println(
            "PT.total $arm|$target|$draw init=${PassTiming.checkerInitNanos} " +
                "sum=${PassTiming.passNanos.values.sum()} wall=$wallMs " +
                "spineNodes=${PassTiming.spineNodes} " +
                "relation=${PassTiming.relationNanos} typeNode=${PassTiming.typeNodeNanos} " +
                "memberResolve=${PassTiming.memberResolveNanos} " +
                "narrowWalkNanos=${PassTiming.narrowWalkNanos} " +
                "narrowWalks=${PassTiming.narrowWalks} " +
                "typeOfExprNanos=${PassTiming.typeOfExprNanos} " +
                "typeOfExprCalls=${PassTiming.getTypeOfExpressionCalls}",
        )
        for ((name, nanos) in PassTiming.passNanos) {
            println("PT $arm|$target|$draw $nanos ${PassTiming.passCalls[name] ?: 0} $name")
        }
    }

    fun armPt(arm: String, detail: Boolean, target: String, draw: Int) {
        ptOn(detail)
        val ms = timed { narrow(target) }
        ptOff()
        dumpPt(arm, target, draw, ms)
    }

    fun armDispatch(target: String, draw: Int, report: Boolean = true) {
        SpineDispatch.reset()
        SpineDispatch.mode = SpineDispatch.PROBE
        val ms = timed { narrow(target) }
        SpineDispatch.mode = SpineDispatch.OFF
        if (!report) return
        // The probe's OWN timestamp-pair cost, measured in situ by an empty
        // handler slot. Quoted rather than inherited: round 847's 38-40 ns was a
        // warm WHOLE-PROGRAM figure and this is a different population.
        val ovhCalls = SpineDispatch.probeOverheadCalls
        val ovh = if (ovhCalls > 0) SpineDispatch.probeOverheadNanos / ovhCalls else 0L
        println("DISPATCH.wall $target|$draw $ms ovhNs=$ovh ovhCalls=$ovhCalls")
        var prologue = 0L
        var tail = 0L
        for (k in 0 until SpineDispatch.KINDS) {
            prologue += SpineDispatch.prologueNanos[k]
            tail += SpineDispatch.tailNanos[k]
        }
        println("DISPATCH.walk $target|$draw prologue=$prologue tail=$tail nodes=${SpineDispatch.kindNodes.sum()}")
        println(SpineDispatch.csv().lineSequence().joinToString("\n") { "DCSV $target|$draw $it" })
    }

    fun plainBatch(tag: String) {
        for (t in targets + NOWHERE) {
            val d = ArrayList<Long>()
            repeat(3) { d.add(timed { narrow(t) }) }
            d.sort()
            println("PLAIN $tag ${sizes[t] ?: 0} ${d[d.size / 2]} $d $t")
        }
    }

    // ---- (INC.37) the SCALING sweep. `own(F)` turned out to be CONTENT-dependent
    // rather than size-dependent at the small end — a 1,337-byte file costs 7.5 ms
    // and a 1,533-byte one 26.5 ms — so a 6-point ladder cannot answer the scaling
    // question and a 6-point fit's "intercept" is whichever small file it happened
    // to draw. This arm is the whole program: the probe-free arm only, every file,
    // so the answer is a scatter with a residual spread rather than a line through
    // two points. It runs INSTEAD of the instrumented arms (which cost far more
    // than the sweep and say nothing about scaling).
    if (System.getenv("XTSC_FCD_ALL") == "1") {
        val order = bySize + NOWHERE
        // Ascending then descending, so a residual JIT ramp lands on both ends of
        // the size axis rather than on the small files alone.
        val walls = HashMap<String, MutableList<Long>>()
        for (pass in 0 until 2) {
            for (f in if (pass == 0) order else order.reversed()) {
                repeat(2) { walls.getOrPut(f) { ArrayList() }.add(timed { narrow(f) }) }
            }
        }
        // …and the NODE count per file, which is the independent variable the
        // scaling question actually turns on: bytes is a 10x-noisy proxy because a
        // declaration-only file (`types.ts`, 488 KB) has almost no expressions to
        // check. The `spine` tier is the cheapest instrument that counts nodes
        // ([PassTiming.spineNodes]); its own inflation lands on `checkSpine`, which
        // is why the WALL column above is taken from the probe-free arm and only
        // the COUNT is taken from here.
        for (f in order) {
            PassTiming.reset()
            PassTiming.detail = false
            PassTiming.spineDetail = true
            PassTiming.enabled = true
            narrow(f)
            PassTiming.enabled = false
            PassTiming.detail = true
            PassTiming.spineDetail = true
            val d = walls[f]!!.sorted()
            println(
                "SWEEP ${sizes[f] ?: 0} ${d[d.size / 2]} nodes=${PassTiming.spineNodes} " +
                    "spineNs=${PassTiming.passNanos["checkSpine"] ?: 0} $d $f",
            )
        }
        return
    }

    plainBatch("early")

    // ---- the instrumented arms, palindromed per target.
    for (t in targets + NOWHERE) {
        armPt("rows", detail = false, target = t, draw = 1)
        armPt("full", detail = true, target = t, draw = 1)
        armPt("rows", detail = false, target = t, draw = 2)
        armPt("full", detail = true, target = t, draw = 2)
        armPt("full", detail = true, target = t, draw = 3)
        armPt("rows", detail = false, target = t, draw = 3)
    }

    // ---- the per-handler arm. It more than doubles the rebuild it measures, so
    // it runs LAST and its wall is never quoted as a price.
    //
    // It needs a warm-up OF ITS OWN, and that is not optional: [SpineDispatch.PROBE]
    // runs the handlers through a BY-ID dispatcher that the production walk never
    // executes, so the first target in the ladder otherwise absorbs the whole
    // ramp — run 1 read `spineCtaM3StatementAnchor` at 90 us per consultation on
    // the 171-node file it happened to measure first, which is a warm-up artifact
    // wearing a handler's name. The ladder is then walked DOWN and back UP, so
    // any residual ramp lands on both ends rather than on the small files.
    repeat(2) { armDispatch(targets.last(), 0, report = false) }
    for (t in targets.reversed()) armDispatch(t, 1)
    for (t in targets) armDispatch(t, 2)

    plainBatch("late")
}

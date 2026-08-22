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

import com.xemantic.typescript.compiler.FrontEnd
import com.xemantic.typescript.compiler.PassTiming
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs

/**
 * (INC.3) step 1 — DECOMPOSE THE FLOOR.
 *
 * The FLOOR is what a build costs when the checker checks NO file:
 * `build(dir, noEmit = true, recheckOnly = {a name the program does not contain})`.
 * (INC.1) measured it at 1,092 ms against a 1,107 ms median narrowed query, i.e.
 * **99% of everything a per-file diagnostics query costs**, and every believed
 * member of it — bind ~515 ms, the ~14 setup passes, the ~416 tail passes, the
 * crawl ~138 ms — was measured in a DIFFERENT regime by a DIFFERENT round. This
 * runner confirms or refutes each of them in ONE process on ONE binary.
 *
 * Two instruments, both already in the compiler and both opt-in:
 *
 * * `FrontEnd` (`--frontEnd`) — per-PHASE wall spans (config / crawl / parse /
 *   imports / bind / check / post, plus the bind and post sub-levels). Per-FILE
 *   boundaries, so its own cost is microseconds against the rows it draws.
 * * `PassTiming` at the **rows** tier (`--passTimingRows`) — the ~480-row
 *   per-pass table inside the checker's `init`. The `full` tier is deliberately
 *   NOT used: round 846 measured ~100% of that tier's own cost landing inside
 *   `checkSpine`, which is exactly the row the floor question turns on. `rows`
 *   is ~513 boundaries per compile, ~0.25% cold and 0% warm.
 *
 * Method, in the order the process runs it:
 *
 * 1. **Warm up and discard.** The first build in a process is the slowest draw
 *    in this repo by a wide margin, and a probe's OWN cost warms up too (round
 *    846: first instrumented rebuild 3,457 ms, second 1,856). So every arm below
 *    is drawn TWICE and the pair is reported.
 * 2. **Plain arms EARLY**, then again LATE, unchanged in between. Their
 *    difference is this process's residual drift, quoted rather than assumed —
 *    a "floor" arm taken at slot 3 of a process once read 1,632 ms where the
 *    median partition it is a strict SUBSET of read 1,107.
 * 3. **Instrumented arms in a palindrome** (fe, pt, both, both, pt, fe), so a
 *    linear drift cancels within each arm's pair.
 *
 * Every row is reported as ms AND as a share of the SAME BUILD's own total, never
 * as a share carried across arms: a share rises when everything else gets faster
 * (round 830), so only the ms is a price.
 *
 * ```
 * scripts/floor-decomposition.sh [<projectDir>]
 * ```
 */
private const val NOWHERE = "/no/such/file/the/program/does/not/contain.ts"

private class Draw(val arm: String, val draw: Int, val wallMs: Long)

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [warmups]" }
    val project = args[0]
    val warmups = if (args.size > 1) args[1].toInt() else 3
    val compiler = ProjectCompiler(SystemVfs)

    fun full() = compiler.build(project, noEmit = true)
    fun floor() = compiler.build(project, noEmit = true, recheckOnly = setOf(NOWHERE))

    fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    // ---- sanity: the floor really is a floor, and the full build really checks.
    val probeFull = full()
    val probeFloor = floor()
    println(
        "program: files=${probeFull.programFiles.size}  " +
            "fullDiagnostics=${probeFull.diagnostics.size}  " +
            "floorDiagnostics=${probeFloor.diagnostics.size}",
    )
    require(probeFull.diagnostics.isNotEmpty()) {
        "REFUSED: the full build reports NO diagnostics, so the floor's own zero is " +
            "not evidence that the checker was narrowed. Point this at a real project."
    }
    require(probeFloor.diagnostics.isEmpty()) {
        "REFUSED: the floor build reported ${probeFloor.diagnostics.size} diagnostics — " +
            "`recheckOnly` did not narrow the checker to nothing, so this is not a floor."
    }

    // ---- 1. warm up and discard.
    repeat(warmups) { full(); floor() }

    // ---- 2. plain arms, EARLY. Rotated inside the batch.
    val draws = ArrayList<Draw>()
    fun plainBatch(tag: String) {
        val f = ArrayList<Long>(); val fl = ArrayList<Long>()
        repeat(2) {
            f.add(timed { full() }); fl.add(timed { floor() })
        }
        repeat(2) {
            fl.add(timed { floor() }); f.add(timed { full() })
        }
        f.sort(); fl.sort()
        println(
            "PLAIN $tag  full median=${f[f.size / 2]}ms $f   " +
                "floor median=${fl[fl.size / 2]}ms $fl",
        )
        draws.add(Draw("plain.full.$tag", 0, f[f.size / 2]))
        draws.add(Draw("plain.floor.$tag", 0, fl[fl.size / 2]))
    }
    plainBatch("early")

    // ---- 3. instrumented arms.
    fun feOn() { FrontEnd.reset(); FrontEnd.mode = FrontEnd.ON }
    fun feOff() { FrontEnd.mode = FrontEnd.OFF }
    fun ptOn() {
        PassTiming.reset()
        PassTiming.detail = false
        PassTiming.spineDetail = false
        PassTiming.enabled = true
    }
    fun ptOff() { PassTiming.enabled = false }

    fun dumpFe(arm: String, draw: Int, wallMs: Long) {
        var total = 0L
        for (s in 0..FrontEnd.POST) {
            if (s != FrontEnd.READ && s != FrontEnd.PREPARSE) total += FrontEnd.nanos[s]
        }
        println("FE.total $arm $draw ${total} $wallMs")
        for (s in 0 until FrontEnd.N) {
            if (FrontEnd.calls[s] == 0L) continue
            println("FE $arm $draw ${s} ${FrontEnd.nanos[s]} ${FrontEnd.calls[s]} ${FrontEnd.names[s].trim()}")
        }
        println("--- FrontEnd.report() $arm draw $draw ---")
        println(FrontEnd.report())
        println("--- end FrontEnd.report() ---")
    }

    fun dumpPt(arm: String, draw: Int, wallMs: Long) {
        val sum = PassTiming.passNanos.values.sum()
        println("PT.total $arm $draw ${PassTiming.checkerInitNanos} $sum $wallMs")
        for ((name, nanos) in PassTiming.passNanos) {
            println("PT $arm $draw $nanos ${PassTiming.passCalls[name] ?: 0} $name")
        }
    }

    fun armFe(target: String, draw: Int) {
        feOn()
        val ms = timed { if (target == "floor") floor() else full() }
        feOff()
        dumpFe("fe.$target", draw, ms)
        draws.add(Draw("fe.$target", draw, ms))
    }

    fun armPt(target: String, draw: Int) {
        ptOn()
        val ms = timed { if (target == "floor") floor() else full() }
        ptOff()
        dumpPt("pt.$target", draw, ms)
        draws.add(Draw("pt.$target", draw, ms))
    }

    // The BOTH arm is not redundant: it is the only place where FrontEnd's
    // [CHECK] row and PassTiming's `checkerInitNanos` are measured on the SAME
    // build, which is what turns "these two tables agree" from an assumption
    // into a partition check. Its delta against `fe.*` also prices the rows-tier
    // probe in situ, instead of inheriting round 846's figure.
    fun armBoth(target: String, draw: Int) {
        feOn(); ptOn()
        val ms = timed { if (target == "floor") floor() else full() }
        feOff(); ptOff()
        dumpFe("both.$target", draw, ms)
        dumpPt("both.$target", draw, ms)
        draws.add(Draw("both.$target", draw, ms))
    }

    // Palindrome: draw 1 forwards, draw 2 backwards.
    armFe("floor", 1); armFe("full", 1)
    armPt("floor", 1); armPt("full", 1)
    armBoth("floor", 1); armBoth("full", 1)
    armBoth("full", 2); armBoth("floor", 2)
    armPt("full", 2); armPt("floor", 2)
    armFe("full", 2); armFe("floor", 2)

    // ---- 4. plain arms, LATE — the drift control for everything above.
    plainBatch("late")

    println("== arm walls ==")
    for (d in draws) println("WALL ${d.arm} ${d.draw} ${d.wallMs}")
}

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

import java.io.File

/**
 * Our side of the tsgo-vs-us incremental comparison: the SAME four cells
 * `scripts/tsgo-incremental-bench.sh` measures, over the SAME tree and the SAME two
 * edits, so the two tables can be read against each other.
 *
 * **The asymmetry is the point and must not be hidden.** tsgo's incremental state is
 * `.tsbuildinfo` ON DISK, so every one of its cells is a fresh process that re-reads
 * that state; ours is a live `Project`, so ours are in-session and pay neither a
 * process start nor a state read. Neither number is "the" number — a batch tool wants
 * tsgo's model and an editor wants ours, and the comparison is only honest when each
 * is labelled with the model it belongs to.
 *
 * ## Three things this runner prints that a wall-clock median cannot say
 *
 *  - **A ROW COUNT per cell.** `kir-bench.sh`'s law: a wall-clock harness reads a
 *    program that does LESS as the fastest arm, so an equivalence gate runs before any
 *    timing is quotable. A cell whose row count differs from the full build's is
 *    answering a different question and its milliseconds mean nothing.
 *  - **A SERVED receipt per cell** — [Project.incrementalAnswers] before and after.
 *    Without it a body-only cell that silently FELL BACK to a rebuild is indistinguishable
 *    from one the mechanism served, and both would be reported as "our incremental time"
 *    (round 790: a verifier reads 0 both when the skip is sound and when it is dead).
 *  - **Every draw, not just the median**, because a median with no spread beside it
 *    cannot be checked against this box's documented +-13% swing.
 *
 * ## Usage
 *
 * ```
 * <projectDir> <editFileRelative> <editsDir> [reps]
 * ```
 *
 * where `<editsDir>` holds `orig.ts`, `body.ts` and `sig.ts` — the pristine text, an
 * edit that moves no exported signature, and one that moves exactly one. Those three
 * are FILES rather than something this runner synthesizes, because whether an edit
 * moves a signature is the very property under test: a body edit that accidentally
 * moved one would make both compilers fall back and the cell would read as "no
 * speed-up available", which is a plausible-looking wrong answer.
 *
 * With one argument it falls back to the 2026-08-29 configuration (tsc's own sources,
 * `src/compiler/binder.ts`, edit variants in the shared scratchpad) so the original
 * table stays reproducible — but note the scratchpad is shared between sessions, so a
 * fresh run should pass an explicit `<editsDir>` under `build/bench/`.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [<editFileRelative> <editsDir> [reps]]" }
    val dir = args[0]
    val editRelative = args.getOrNull(1) ?: "src/compiler/binder.ts"
    val editsDir = args.getOrNull(2) ?: "/tmp/claude-1000"
    val reps = args.getOrNull(3)?.toInt() ?: 3

    val target = File(dir, editRelative)
    require(target.isFile) { "no file to edit at ${target.path}" }

    // The legacy layout names the variants `binder.<v>.ts`; an explicit edits dir uses
    // the plain names. Refusing loudly beats measuring a fixture that is not the one
    // the caller believes they pointed at.
    fun variant(name: String): String {
        val explicit = File(editsDir, "$name.ts")
        val legacy = File(editsDir, "binder.$name.ts")
        val f = if (explicit.isFile) explicit else legacy
        require(f.isFile) { "REFUSED: no '$name' variant at ${explicit.path} or ${legacy.path}" }
        return f.readText()
    }
    val orig = variant("orig")
    val body = variant("body")
    val sig = variant("sig")
    require(body != orig) { "REFUSED: the BODY variant is identical to the original" }
    require(sig != orig) { "REFUSED: the SIGNATURE variant is identical to the original" }
    require(sig != body) { "REFUSED: the two variants are identical to each other" }
    require(target.readText() == orig) {
        "REFUSED: ${target.path} does not hold the recorded original — the fixture is dirty"
    }

    println("project   : $dir")
    println("edited    : $editRelative")
    println("edits     : $editsDir")
    println("reps      : $reps")
    println()

    fun ms(block: () -> Unit): Long {
        val at = System.nanoTime(); block(); return (System.nanoTime() - at) / 1_000_000
    }
    fun median(xs: List<Long>) = xs.sorted()[xs.size / 2]

    /** One cell: its wall ms, how many rows it answered, and whether it was SERVED. */
    class Cell(val ms: Long, val rows: Int, val served: Boolean)

    fun cell(p: Project): Cell {
        val before = p.incrementalAnswers
        val at = System.nanoTime()
        val rows = p.diagnostics().size
        val took = (System.nanoTime() - at) / 1_000_000
        return Cell(took, rows, p.incrementalAnswers > before)
    }

    fun report(label: String, cells: List<Cell>) {
        val msList = cells.map { it.ms }
        val rows = cells.map { it.rows }.distinct()
        val served = cells.count { it.served }
        val rowNote = if (rows.size == 1) "rows=${rows[0]}" else "rows=$rows  <== CELLS DISAGREE"
        println(
            "%-34s: %s  median=%d ms  %s  served=%d/%d".format(
                label, msList, median(msList), rowNote, served, cells.size,
            ),
        )
    }

    // ---- COLD: a brand-new Project, nothing warmed. The JVM is cold too, which is
    //      why this cell is reported separately from every other one.
    target.writeText(orig)
    val coldProject = Project.open(dir)
    val cold0 = ms { coldProject.diagnostics() }
    val coldRows = coldProject.diagnostics().size
    coldProject.close()
    println("cold, cold JVM (first build ever) : $cold0 ms  rows=$coldRows")

    // ---- warm the JVM the way a long-lived host would be warm
    repeat(4) {
        val p = Project.open(dir)
        p.diagnostics(); p.close()
    }

    // (INC.90) The plugin's OWN per-keystroke call is not this one. Verified at
    // `Project.kt:728`: `incrementalDiagnostics()` is reached from `diagnostics()`
    // and from nowhere else, while the IntelliJ plugin asks `diagnosticsOf(listOf(
    // fileOnScreen, configPath))` exclusively — which narrows at the SOURCE (INV.6)
    // instead. They are two different questions and only the first is what tsgo's
    // `--incremental --noEmit` answers, so both are measured and neither is quoted
    // as the other.
    val config = File(dir, "tsconfig.json").path
    fun perFile(p: Project): Cell {
        val before = p.incrementalAnswers
        val at = System.nanoTime()
        val rows = p.diagnosticsOf(listOf(target.path, config)).size
        return Cell((System.nanoTime() - at) / 1_000_000, rows, p.incrementalAnswers > before)
    }

    val full = ArrayList<Cell>(); val noop = ArrayList<Cell>()
    val bodyCells = ArrayList<Cell>(); val sigCells = ArrayList<Cell>()
    val bodyFile = ArrayList<Cell>(); val sigFile = ArrayList<Cell>()
    repeat(reps) {
        target.writeText(orig)
        val p = Project.open(dir)
        full.add(cell(p))
        noop.add(cell(p))
        p.updateFile(target.path, body)
        bodyCells.add(cell(p))
        p.updateFile(target.path, orig)
        p.diagnostics()
        p.updateFile(target.path, sig)
        sigCells.add(cell(p))
        p.close()
    }
    // The plugin's path, measured in its own session so the project-wide cells above
    // cannot have pre-answered it.
    repeat(reps) {
        target.writeText(orig)
        val p = Project.open(dir)
        p.diagnosticsOf(listOf(target.path, config))
        p.updateFile(target.path, body)
        bodyFile.add(perFile(p))
        p.updateFile(target.path, orig)
        p.diagnosticsOf(listOf(target.path, config))
        p.updateFile(target.path, sig)
        sigFile.add(perFile(p))
        p.close()
    }
    println()
    report("full build, warm JVM", full)
    report("no-op (nothing changed)", noop)
    report("after BODY-ONLY edit", bodyCells)
    report("after SIGNATURE edit", sigCells)
    println()
    println("-- the plugin's own call: diagnosticsOf(edited file + tsconfig) --")
    report("body-only edit, per-file query", bodyFile)
    report("signature edit, per-file query", sigFile)
    println()
    // The mechanism claim, stated as a receipt rather than inferred from the timings:
    // a body-only edit must be SERVED incrementally and a signature edit must FALL BACK.
    // If either half is not what it should be, the edits are not what they claim and
    // every millisecond above is about some other pair of edits.
    val bodyServed = bodyCells.count { it.served }
    val sigServed = sigCells.count { it.served }
    println("RECEIPT: body-only served $bodyServed/$reps (want $reps), signature served $sigServed/$reps (want 0)")
    if (bodyServed != reps || sigServed != 0) {
        println("RECEIPT: *** the edits are NOT the two shapes this table claims — do not quote these numbers ***")
    }
    val allRows = (full + noop + bodyCells).map { it.rows }.distinct()
    if (allRows.size != 1) {
        println("EQUIVALENCE: *** full/no-op/body cells answer different row counts $allRows ***")
    }
    target.writeText(orig)
}

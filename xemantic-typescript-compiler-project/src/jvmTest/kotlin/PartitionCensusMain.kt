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
import com.xemantic.typescript.compiler.SystemVfs

/**
 * (INC.17) step 1 — THE THREE-BUCKET CENSUS OF THE CHECKER'S `init` PASSES.
 *
 * The re-entrant checker — one that can be asked about a file its first build
 * did not check, without replaying the whole `init` — is worth the same
 * 342-365 ms floor `Project.prepare` collects for a NAMED working set. What
 * decides whether it is a classification or a rewrite is ONE count: how many of
 * the ~479 `pass(...)` rows ever read the PARTITION at all.
 *
 * A pass that iterates `binderResults` is partition-INVARIANT by construction —
 * its diagnostics and its side tables are a function of the PROGRAM, so a
 * checker that has already run it need not replay it. A pass that reaches
 * `checkedResults` (or `assignedFileNames` directly) is partition-DEPENDENT and
 * must replay for the newly asked file.
 *
 * **The classification is measured, not parsed.** `checkedResults` is a getter
 * that records [PassTiming.currentPass], so the census cannot be wrong about who
 * read it — where a source analyzer over `Checker.kt` fails silently and in the
 * reassuring direction (CLAUDE.md: a stripper that handles `'x'` still
 * desynchronises on an escaped apostrophe and then reports "no hazard" over an
 * EMPTY closure, and a `pass("name") { … }` sample inside a KDoc parses as a
 * real registration).
 *
 * **Three arms, and the classification is their UNION.** A pass may read the
 * partition only inside a branch that an EMPTY partition never enters, so the
 * FLOOR arm alone under-counts; a pass may equally be reached only when there is
 * something to check, so the FULL arm alone is not a superset either. The arms:
 *
 * * `full`  — no `recheckOnly`; `checkedResults === binderResults`.
 * * `floor` — `recheckOnly` names a file the program does not contain, so the
 *   partition is EMPTY. Its per-pass ms IS the price of the floor, which is what
 *   a re-entrant replay would be paying.
 * * `one`   — `recheckOnly` names one real, mid-sized file: the actual shape a
 *   re-entrant ask has.
 *
 * The ms quoted per bucket is the FLOOR arm's, because that is the quantity
 * (INC.17) would bank. The `rows` tier is used rather than `full` (round 846:
 * ~100% of the `full` tier's own cost lands inside `checkSpine`).
 *
 * ```
 * scripts/partition-census.sh [<projectDir>]
 * ```
 */
private const val NOWHERE = "/no/such/file/the/program/does/not/contain.ts"

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [warmups]" }
    val project = args[0]
    val warmups = if (args.size > 1) args[1].toInt() else 2
    val compiler = ProjectCompiler(SystemVfs)

    fun build(only: Set<String>?) =
        if (only == null) compiler.build(project, noEmit = true)
        else compiler.build(project, noEmit = true, recheckOnly = only)

    // ---- sanity + pick the `one` arm's file: the median-sized program file.
    val probeFull = build(null)
    val files = probeFull.programFiles
        .filter { SystemVfs.exists(it) }
        .sortedBy { SystemVfs.readText(it)?.length ?: 0 }
    require(files.isNotEmpty()) { "REFUSED: the program has no readable files" }
    val one = files[files.size / 2]
    val probeFloor = build(setOf(NOWHERE))
    require(probeFull.diagnostics.isNotEmpty()) {
        "REFUSED: the full build reports NO diagnostics, so the floor's own zero is not " +
            "evidence that the checker was narrowed. Point this at a real project."
    }
    require(probeFloor.diagnostics.isEmpty()) {
        "REFUSED: the floor build reported ${probeFloor.diagnostics.size} diagnostics — " +
            "`recheckOnly` did not narrow the checker to nothing, so this is not a floor."
    }
    println("program: files=${probeFull.programFiles.size} fullDiagnostics=${probeFull.diagnostics.size}")
    println("one: $one")

    repeat(warmups) { build(null); build(setOf(NOWHERE)) }

    class Arm(
        val name: String,
        val wallMs: Long,
        val nanos: Map<String, Long>,
        val calls: Map<String, Int>,
        val reads: Map<String, Long>,
        val readsOutside: Long,
        val diagNet: Map<String, Int>,
    )

    fun arm(name: String, only: Set<String>?): Arm {
        PassTiming.reset()
        PassTiming.detail = false
        PassTiming.spineDetail = false
        PassTiming.enabled = true
        val t0 = System.nanoTime()
        build(only)
        val ms = (System.nanoTime() - t0) / 1_000_000
        PassTiming.enabled = false
        return Arm(
            name, ms,
            LinkedHashMap(PassTiming.passNanos),
            HashMap(PassTiming.passCalls),
            LinkedHashMap(PassTiming.partitionReadsByPass),
            PassTiming.partitionReadsOutsidePass,
            LinkedHashMap(PassTiming.diagNetByPass),
        )
    }

    // Palindrome over the three arms, so a linear drift cancels within each pair.
    val armsList = listOf(
        arm("full", null), arm("floor", setOf(NOWHERE)), arm("one", setOf(one)),
        arm("one", setOf(one)), arm("floor", setOf(NOWHERE)), arm("full", null),
    )
    val byName = armsList.groupBy { it.name }

    // ---- CONTROLS. A census whose controls are not printed has not been validated.
    val full = byName.getValue("full")
    val floor = byName.getValue("floor")
    val oneArm = byName.getValue("one")
    println("== controls ==")
    for ((n, pair) in byName) {
        println("CONTROL wall $n ${pair.map { it.wallMs }}")
        println("CONTROL rows $n ${pair.map { it.nanos.size }}")
        println("CONTROL reads $n ${pair.map { it.reads.size }} outside=${pair.map { it.readsOutside }}")
    }
    // The spine MUST read the partition in every arm — it is the check walk.
    for (a in armsList) {
        val spine = a.reads.keys.filter { it.contains("checkSpine") }
        println("CONTROL spineReads ${a.name} $spine")
    }

    fun union(sel: (Arm) -> Map<String, Long>): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (a in armsList) for ((k, v) in sel(a)) m[k] = maxOf(m[k] ?: 0L, v)
        return m
    }

    val readsUnion = union { it.reads }
    // Row set = every pass that RAN in any arm, in the full arm's dispatch order.
    val rows = LinkedHashSet<String>()
    for (a in armsList) rows.addAll(a.nanos.keys)

    fun medianNanos(pair: List<Arm>, name: String): Long {
        val v = pair.mapNotNull { it.nanos[name] }.sorted()
        return if (v.isEmpty()) 0L else v[v.size / 2]
    }

    println("== rows: name | floorMs | fullMs | oneMs | reads(full/floor/one) | diagNet | bucket ==")
    var depMsFloor = 0L
    var invMsFloor = 0L
    var depMsOne = 0L
    var invMsOne = 0L
    var depCount = 0
    var invCount = 0
    val retractors = ArrayList<String>()
    for (name in rows) {
        val fl = medianNanos(floor, name)
        val fu = medianNanos(full, name)
        val on = medianNanos(oneArm, name)
        val dep = readsUnion.containsKey(name)
        if (dep) { depCount++; depMsFloor += fl; depMsOne += on } else { invCount++; invMsFloor += fl; invMsOne += on }
        val net = full.mapNotNull { it.diagNet[name] }.minOrNull() ?: 0
        if (net < 0) retractors.add(name)
        println(
            "ROW $name ${fl / 1_000_000.0} ${fu / 1_000_000.0} ${on / 1_000_000.0} " +
                "${full[0].reads[name] ?: 0}/${floor[0].reads[name] ?: 0}/${oneArm[0].reads[name] ?: 0} " +
                "$net ${if (dep) "DEPENDENT" else "INVARIANT"}",
        )
    }
    println("== buckets ==")
    println("BUCKET invariant  rows=$invCount floorMs=${invMsFloor / 1_000_000.0} oneMs=${invMsOne / 1_000_000.0}")
    println("BUCKET dependent  rows=$depCount floorMs=${depMsFloor / 1_000_000.0} oneMs=${depMsOne / 1_000_000.0}")
    println("BUCKET retractors n=${retractors.size} $retractors")
    println("TOTAL rows=${rows.size} floorMs=${(invMsFloor + depMsFloor) / 1_000_000.0} oneMs=${(invMsOne + depMsOne) / 1_000_000.0}")
}

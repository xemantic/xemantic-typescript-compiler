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
import com.xemantic.typescript.compiler.SystemVfs
import java.io.File

/**
 * MEASURE THE PRIZE for incremental error reporting, before anything is built.
 *
 * `docs/language-service.md` § 3 states the cost of a dirty query as a full rebuild,
 * and § 14 gap 1 states that removing it is "the architectural inversion". Both are
 * measured against an edit that does not change any BYTE — `LanguageServiceCostMain`
 * dirties a file by writing its own text back — which is the cheapest possible dirty
 * state: the content-keyed parse cache still hits on every file including the edited
 * one. An IDE keystroke is not that. This runner measures the arms an IntelliJ-style
 * host actually performs, so the difference between them is the budget any incremental
 * seam has to work with:
 *
 * - `open+first`   opening a project and asking once (the host's startup cost)
 * - `clean`        re-asking with no edit (must be free — the cached-result control)
 * - `dirtySame`    § 14's arm: dirty, byte-identical text
 * - `dirtyComment` a real keystroke that cannot change any type: one appended comment
 * - `dirtyBody`    a real keystroke inside a function body
 * - `dirtyError`   a keystroke that INTRODUCES an error, then one that removes it —
 *                  the two states an error-reporting host alternates between
 * - `fileOnly`     `diagnostics(file)` after the same edit: what the host really asks,
 *                  and today the same full build with a filter over it
 *
 * Every arm runs in ONE process against ONE project so the figures are comparable to
 * each other, which is the only comparison CLAUDE.md admits. Not a gate and not a pin.
 *
 * ```
 * java -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.IncrementalCostMainKt \
 *   build/bench/tsc-project-<sha> src/compiler/semver.ts 3
 * ```
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <projectDir> [<fileSuffix> [rotations]]" }
    val rotations = if (args.size > 2) args[2].toInt() else 3

    fun ms(block: () -> Unit): Long {
        val at = System.nanoTime()
        block()
        return (System.nanoTime() - at) / 1_000_000
    }

    var opened: Project? = null
    val openAndFirst = ms {
        val p = Project.open(args[0])
        p.diagnostics()
        opened = p
    }
    val project = opened!!

    val suffix = if (args.size > 1) args[1] else project.files.first()
    val target = project.files.first { it.endsWith(suffix) }
    val text = File(target).readText()
    var characters = 0L
    for (file in project.files) characters += File(file).readText().length
    println(
        "files=${project.files.size} characters=$characters target=$target " +
            "targetChars=${text.length} openAndFirst=${openAndFirst}ms",
    )

    // An edit site inside the target: just past the last `) {`, i.e. inside a body.
    // Anchored on `) {` rather than on a bare brace so the site is a STATEMENT list
    // and not an object type or a string, and terminator-agnostic because the tsc
    // sources are CRLF (a file whose line endings the probe assumed cost a run).
    val bodyAt = text.lastIndexOf(") {")
    require(bodyAt > 0) { "no body-shaped edit site in $target" }
    fun withBody(insert: String) =
        text.substring(0, bodyAt + 3) + "\n" + insert + text.substring(bodyAt + 3)

    // A warm-up rebuild, discarded: the first build in a process is the slowest draw
    // in this repo by a wide margin and belongs to no arm (CLAUDE.md round 869).
    project.diagnostics()

    repeat(rotations) { rotation ->
        val clean = ms { project.diagnostics() }

        project.updateFile(target, text)
        val dirtySame = ms { project.diagnostics() }

        project.updateFile(target, text + "\n// keystroke $rotation\n")
        val dirtyComment = ms { project.diagnostics() }

        project.updateFile(target, withBody("    const xtscProbe$rotation = 1;\n"))
        val dirtyBody = ms { project.diagnostics() }

        project.updateFile(target, withBody("    const xtscProbe$rotation: number = \"s\";\n"))
        var errors = 0
        val dirtyError = ms { errors = project.diagnostics().count { it.code == 2322 } }

        project.updateFile(target, withBody("    const xtscProbe$rotation = 1;\n"))
        val dirtyFixed = ms { project.diagnostics() }

        project.updateFile(target, withBody("    const xtscProbe$rotation = 2;\n"))
        val fileOnly = ms { project.diagnostics(target) }

        println(
            "rotation=$rotation clean=$clean dirtySame=$dirtySame dirtyComment=$dirtyComment " +
                "dirtyBody=$dirtyBody dirtyError=$dirtyError(ts2322=$errors) dirtyFixed=$dirtyFixed " +
                "fileOnly=$fileOnly",
        )
    }
    project.updateFile(target, text)
    project.close()

    // ---- the PRIZE arm ------------------------------------------------------
    //
    // `recheckOnly` is the INV.6 partition seam that `--workers` already uses, and
    // `ProjectCompiler.build` already takes it — `Project.build()` passes null. So the
    // cost of "check ONLY the file the editor is showing, with the whole program's
    // context" is measurable today, without changing a line of the compiler. Both arms
    // run through the same `ProjectCompiler` so the only difference is the partition;
    // the diagnostics FOR THE TARGET FILE must agree, which is the correctness half
    // and is asserted here rather than assumed.
    val compiler = ProjectCompiler(SystemVfs)
    repeat(rotations) { rotation ->
        var fullRows = emptyList<String>()
        var partRows = emptyList<String>()
        var fullAll = 0
        val full = ms {
            val r = compiler.build(args[0], noEmit = true)
            fullAll = r.diagnostics.size
            fullRows = r.diagnostics.filter { it.fileName == target }
                .map { "${it.code}|${it.start}|${it.length}|${it.message}" }.sorted()
        }
        val partition = ms {
            val r = compiler.build(args[0], noEmit = true, recheckOnly = setOf(target))
            partRows = r.diagnostics.filter { it.fileName == target }
                .map { "${it.code}|${it.start}|${it.length}|${it.message}" }.sorted()
        }
        println(
            "rotation=$rotation fullBuild=${full}ms partitionBuild=${partition}ms " +
                "targetDiagnostics=${fullRows.size}/${partRows.size} " +
                "agree=${fullRows == partRows} programDiagnostics=$fullAll",
        )
        if (fullRows != partRows) {
            for (row in (fullRows - partRows.toSet())) println("  only-full: $row")
            for (row in (partRows - fullRows.toSet())) println("  only-part: $row")
        }
    }
}

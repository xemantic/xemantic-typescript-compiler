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
 * (API.13) Re-take `docs/language-service.md` § 14's COST TABLE, in one process.
 *
 * ## Why a runner and not a test
 *
 * The table's wall figures are a property of a real project — tsc's own 78 sources,
 * which live under `build/bench/` and exist on no fresh checkout and in no CI — and
 * of the box that took them. A test cannot defend them (a timed assertion over a
 * compile is a coin flip, CLAUDE.md round 868) and a stale one drifts silently,
 * which is what round 930 found: § 3 said ~5.2 s for the same rebuild § 14 said
 * 5.5–5.9 s. So the BUILD COUNTS are pinned by `LanguageServiceStateTest` and the
 * MILLISECONDS are re-taken by this, and § 14 says which of its rows is which.
 *
 * Every arm runs in ONE process against ONE project, three rotations, so the figures
 * are comparable to each other — the only comparison CLAUDE.md admits. Never quote a
 * number here beside a number from another round.
 *
 * ```
 * scripts/round930-ls-cost.sh                  # the compiler profile
 * java -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.LanguageServiceCostMainKt \
 *   build/bench/tsc-project-<sha> src/compiler/types.ts 'SyntaxKind {' 3
 * ```
 *
 * Not a gate and not a pin — it asserts nothing.
 */
fun main(args: Array<String>) {
    require(args.size >= 3) {
        "usage: <projectDir> <fileSuffix> <needle naming an identifier> [rotations]"
    }
    val rotations = if (args.size > 3) args[3].toInt() else 3
    val project = Project.open(args[0])
    val target = project.files.first { it.endsWith(args[1]) }
    val text = File(target).readText()
    val offset = text.indexOf(args[2])
    require(offset >= 0) { "needle not found in $target" }
    var characters = 0L
    for (file in project.files) characters += File(file).readText().length
    println("files=${project.files.size} characters=$characters caret=$target@$offset")

    fun ms(block: () -> Unit): Long {
        val at = System.nanoTime()
        block()
        return (System.nanoTime() - at) / 1_000_000
    }

    // A warm-up rebuild, discarded: the first build in a process is the slowest draw
    // in this repo by a wide margin and belongs to no arm (CLAUDE.md round 869).
    project.diagnostics()
    repeat(rotations) { rotation ->
        val dirty = { project.updateFile(target, text) }
        dirty()
        val rebuild = ms { project.diagnostics() }
        val quickInfo = ms { project.quickInfoAt(target, offset) }
        val sweep = ms { project.fileSemantics(target) }
        val highlights = ms { project.documentHighlightsAt(target, offset) }
        val referencesClean = ms { project.referencesAt(target, offset) }
        dirty()
        val referencesDirty = ms { project.referencesAt(target, offset) }
        val renameClean = ms { project.renameAt(target, offset, "RenamedForCost") }
        dirty()
        val renameDirty = ms { project.renameAt(target, offset, "RenamedForCost") }
        println(
            "rotation=$rotation rebuild=$rebuild quickInfo=$quickInfo fileSemantics=$sweep " +
                "highlights=$highlights referencesClean=$referencesClean " +
                "referencesDirty=$referencesDirty renameClean=$renameClean renameDirty=$renameDirty",
        )
    }
    project.close()
}

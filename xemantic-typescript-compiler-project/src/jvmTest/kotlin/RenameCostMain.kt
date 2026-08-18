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
 * (API.8) Measure [Project.renameAt] against [Project.referencesAt] on a REAL project
 * — the harness behind the cost table in `docs/language-service.md`.
 *
 * It exists because a measurement nobody can re-run is a claim rather than a figure:
 * this repo's own rule is that an absolute millisecond number is only comparable
 * within the round that took it, so the next agent needs the command, not the answer.
 * Not a gate and not a pin — it asserts nothing.
 *
 * ```
 * java -Xmx6g -cp <classes:deps> \
 *   com.xemantic.typescript.compiler.project.RenameCostMainKt \
 *   build/bench/tsc-project-<sha> src/compiler/types.ts 'SyntaxKind {' renamedKind
 * ```
 *
 * Three rounds are run and every one printed: the FIRST is cold, and reporting a
 * median of three without showing them would hide that.
 */
fun main(args: Array<String>) {
    require(args.size == 4) {
        "usage: <projectDir> <fileSuffix> <needle naming the identifier> <newName>"
    }
    val project = Project.open(args[0])
    val target = project.files.first { it.endsWith(args[1]) }
    val offset = File(target).readText().indexOf(args[2])
    require(offset >= 0) { "needle not found in $target" }
    println("target=$target offset=$offset")
    repeat(3) {
        var at = System.nanoTime()
        val references = project.referencesAt(target, offset)
        val referenceMs = (System.nanoTime() - at) / 1_000_000
        at = System.nanoTime()
        val plan = project.renameAt(target, offset, args[3])
        val renameMs = (System.nanoTime() - at) / 1_000_000
        println(
            "references=${references.size} referenceMs=$referenceMs | " +
                "refusal=${plan.refusal} files=${plan.files.size} " +
                "edits=${plan.files.sumOf { file -> file.edits.size }} renameMs=$renameMs",
        )
    }
    project.close()
}

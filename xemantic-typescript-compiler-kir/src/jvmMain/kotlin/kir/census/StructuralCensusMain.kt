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

package com.xemantic.typescript.compiler.kir.census

import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs
import java.io.File

/**
 * Runs [StructuralCensus] over a real TypeScript project and prints the report.
 *
 * ```
 * java -Xmx4g -cp <kir + core + deps> \
 *   com.xemantic.typescript.compiler.kir.census.StructuralCensusMainKt \
 *   build/bench/tsc-project-637d5746 [out.txt]
 * ```
 *
 * A PROJECT and not a file: the whole question is what pairs a real program
 * forms, and a single-file harness cannot resolve an import, so it would measure
 * a program in which every cross-file type is `any` — silently, because `any` is
 * assignable to everything and would classify as [EdgeClass.STRUCTURAL] or
 * vanish into [TargetClass.ANY_OR_UNKNOWN] rather than fail.
 *
 * `noEmit` because the census is a property of the CHECK; running the emit
 * behind it would only add wall time to the number this prints.
 */
public fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: structural-census <project-dir> [output-file]")
        return
    }
    val census = StructuralCensus()
    val started = System.currentTimeMillis()
    val result = ProjectCompiler(SystemVfs).build(args[0], noEmit = true, checkedSink = census)
    val elapsed = System.currentTimeMillis() - started
    val errors = result.diagnostics.count { it.category == DiagnosticCategory.Error }
    val rendered = census.report().renderReport(
        title = "${args[0]} (${result.programFiles.size} program files, $errors errors)",
        elapsedMillis = elapsed,
    )
    if (args.size > 1) File(args[1]).writeText(rendered) else print(rendered)
    System.err.println("census done in ${elapsed} ms; $errors checker errors")
}

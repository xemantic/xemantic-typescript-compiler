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

package com.xemantic.typescript.compiler.kir.lower

import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.kir.emit.IrProgramBuilder
import com.xemantic.typescript.compiler.kir.front.CheckedFacts
import com.xemantic.typescript.compiler.kir.refuse

/**
 * Lowers a whole checked PROGRAM: every file, then one entry point.
 *
 * The ordering is the only structural thing here, and it is load-bearing:
 * **every** file's declare pass runs before **any** file's define pass. A file
 * lowering already needs that within a file, because TypeScript hoists
 * functions; across files it additionally makes module dependency order
 * irrelevant, so a cycle between two modules costs nothing and no topological
 * sort is needed.
 *
 * The entry point is one file's top-level statements. Every other file may
 * declare and may not RUN: a module whose body has side effects needs module
 * initialization order, which is a real design question (JavaScript runs a
 * module's body once, on first import) and not one to answer by accident. So it
 * is refused, at the statement, naming the file.
 */
internal class KirProgramLowering(
    private val builder: IrProgramBuilder,
    private val facts: CheckedFacts,
    private val files: List<SourceFile>,
    private val entryFile: SourceFile,
    private val packageName: String,
) {

    fun lower() {
        val tables = KirProgramTables(files)
        val facades = facadeNames()
        val lowerings = files.map { file ->
            file to KirFileLowering(
                builder,
                facts,
                file,
                tables,
                packageName,
                // The entry file's facade is `MainKt` whatever it is called, so
                // the generated program's entry point has one stable name; the
                // rest are named after their own path, made unique because two
                // `index.ts` in different directories are one package here.
                if (file === entryFile) "Main.kt" else facades.getValue(file),
            )
        }
        lowerings.forEach { (_, lowering) -> lowering.declareAll() }
        lowerings.forEach { (file, lowering) ->
            lowering.defineAll()
            if (file !== entryFile) {
                lowering.executableStatements().firstOrNull()?.let { statement ->
                    refuse(
                        file, statement,
                        "a module body that RUNS is out of the spike subset — only the " +
                            "entry file's top-level statements are executed"
                    )
                }
            }
        }
        lowerings.single { (file, _) -> file === entryFile }.second.buildEntryPoint()
    }

    /**
     * A Kotlin file name per TypeScript file: `parse/cst.ts` → `Cst.kt`.
     *
     * Uniqueness is the requirement, not beauty: every file lands in ONE
     * package, so two modules both called `index.ts` would generate two
     * `IndexKt` facades — and a JVM class clash surfaces as a mangled program
     * rather than as a diagnostic. Collisions are therefore numbered, in the
     * crawl's order, which is deterministic.
     */
    private fun facadeNames(): Map<SourceFile, String> {
        val taken = mutableMapOf<String, Int>()
        return files.associateWith { file ->
            val base = file.fileName.substringAfterLast('/').substringBefore('.')
                .map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("")
                .trim('_')
                .ifEmpty { "Module" }
                .replaceFirstChar { it.uppercase() }
            val seen = taken.getOrElse(base) { 0 } + 1
            taken[base] = seen
            if (seen == 1) "$base.kt" else "$base$seen.kt"
        }
    }

}

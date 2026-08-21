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
    /** Resolved (importer, imported) edges — the module INIT order comes from them. */
    private val importEdges: List<Pair<String, String>> = emptyList(),
) {

    fun lower() {
        // A DECLARATION file has no runtime content — it is types and ambient
        // declarations only, which is what `.d.ts` means — so it is not lowered
        // at all. Its declarations still reach the backend through the checker,
        // which is the only way they were ever going to.
        val files = files.filterNot { it.fileName.endsWith(".d.ts") }
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
        lowerings.forEach { (_, lowering) -> lowering.declareShells() }
        lowerings.forEach { (_, lowering) -> lowering.declareAll() }
        lowerings.forEach { (_, lowering) -> lowering.linkOverrides() }
        lowerings.forEach { (_, lowering) -> lowering.defineAll() }
        // Every file's module init, in DEPENDENCY order, called by one `main`.
        // JavaScript runs a module's body once, on first import, dependencies
        // first — so `export const X = compute()` in one file is evaluated
        // before the file that imports it runs, which is the whole reason the
        // order is computed rather than taken as the crawl's.
        val byName = lowerings.associate { (file, lowering) -> file.fileName to lowering }
        val inits = initializationOrder().mapNotNull { byName[it]?.buildModuleInit() }
        lowerings.single { (file, _) -> file === entryFile }.second.buildEntryPoint(inits)
    }

    /**
     * The file names in module-INITIALIZATION order: imports before importers.
     *
     * A depth-first post-order over the import graph, with the ENTRY file last.
     * A cycle is broken at the point it is detected — which is what a JavaScript
     * engine does too, since one of the two bodies has to run first — rather
     * than refused, because a module cycle is ordinary in real libraries and
     * refusing it would refuse the libraries.
     */
    private fun initializationOrder(): List<String> {
        val dependencies = mutableMapOf<String, MutableList<String>>()
        importEdges.forEach { (importer, imported) ->
            dependencies.getOrPut(importer) { mutableListOf() }.add(imported)
        }
        val known = files.filterNot { it.fileName.endsWith(".d.ts") }.map { it.fileName }.toSet()
        val ordered = mutableListOf<String>()
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun visit(name: String) {
            if (name in done || name !in known || !visiting.add(name)) return
            dependencies[name]?.forEach { visit(it) }
            visiting.remove(name)
            done.add(name)
            ordered.add(name)
        }
        files.filter { it !== entryFile && !it.fileName.endsWith(".d.ts") }
            .forEach { visit(it.fileName) }
        visit(entryFile.fileName)
        return ordered
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

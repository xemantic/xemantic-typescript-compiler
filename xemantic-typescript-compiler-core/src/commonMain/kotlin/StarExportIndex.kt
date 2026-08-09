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
 */

package com.xemantic.typescript.compiler

/**
 * (WARM.15) round 868 — one file's `export`-side index, built once and shared by
 * the four `export *` barrel walks in [Checker]
 * (`computeExported{FnDecls,VarDecl,Symbol,InterfaceFile}ThroughStars`).
 *
 * **Why it exists.** Those four walks answer "which file really exports this
 * name?" by descending the star-export graph, and each VISIT used to re-derive
 * its answer from the visited file's whole top-level statement list. The first
 * leaf-level warm profile of this compiler (`docs/perf/warm-leaf-profile.md`)
 * measured the family at **6.0% of the compile thread's samples**, and the
 * `FrontEnd.STAR` census priced it at **672 ms = 9.0% of a warm rebuild**: 8,754
 * outermost walks, 290,117 file visits, **25.3 million top-level statements
 * scanned** — ~2,889 statements per question asked. None of that is a resolution
 * result; it is four immutable facts about a parsed file, re-derived per visit.
 *
 * **The index changes NOTHING about the walks' answers, and that is the whole
 * design.** Every field is a transcription of the predicate it replaces, with
 * the same order and the same first-wins rule, so the walks descend the same
 * edges in the same order and return the same declarations; the measured
 * population is identical on both sides (walks 8,754, visits 290,117, answered
 * 6,298, null 2,456), and only the SCAN WIDTH moves — 25,291,521 -> 638,464.
 *
 * @see buildStarExportIndex for the per-field correspondence.
 */
internal class StarExportIndex(
    /**
     * The file's own EXPORTED top-level `FunctionDeclaration`s grouped by name,
     * each list in statement order — i.e. all overloads plus the implementation,
     * exactly as `statements.filterIsInstance<FunctionDeclaration>().filter { … }`
     * produced them. An absent name is that filter coming back empty, which the
     * walk mapped to `null` through `ifEmpty`.
     */
    val fnDecls: Map<String, List<FunctionDeclaration>>,
    /**
     * FIRST-WINS over (statement, declaration) order among EXPORTED top-level
     * `VariableStatement`s, and only for `Identifier` names — the walk's nested
     * loop returned on its first match and never matched a binding pattern.
     */
    val varDecls: Map<String, VariableDeclaration>,
    /** The names of the file's own EXPORTED top-level `interface` declarations. */
    val interfaceNames: Set<String>,
    /**
     * Exactly the re-export statements the walks descend through, in source
     * order, already resolved to their target file. A bare `export * from "…"`
     * carries a null [ReExport.named]; an `export { a as b } from "…"` carries
     * its clause. Everything a walk skipped is absent, which is the same thing
     * as the `continue` it used to take: a clause that is neither of those two
     * shapes (`export * as ns from "…"`, which exposes only `ns`), a missing
     * module specifier (a local `export { a }`), or an unresolvable target.
     */
    val reExports: List<ReExport>,
)

/** One descendable re-export edge; [named] null means a bare `export *`. */
internal class ReExport(val named: NamedExports?, val target: SourceFile)

/**
 * Build [StarExportIndex] for [file].
 *
 * [resolveTarget] is `Checker.resolveBarrelStarTarget` — a pure function of
 * (fromFile, spec) over the frozen `fileResults`, which is what makes hoisting
 * it out of the walk sound: it cannot change what the call answers, only how
 * often it is asked. It is a parameter rather than a call so that this function,
 * and therefore every rule above, is directly testable.
 */
internal fun buildStarExportIndex(
    file: SourceFile,
    resolveTarget: (spec: String, fromFile: String) -> SourceFile?,
): StarExportIndex {
    val fnDecls = HashMap<String, MutableList<FunctionDeclaration>>()
    val varDecls = HashMap<String, VariableDeclaration>()
    val interfaceNames = HashSet<String>()
    val reExports = ArrayList<ReExport>()
    for (stmt in file.statements) {
        when (stmt) {
            is FunctionDeclaration -> {
                val n = stmt.name?.text
                if (n != null && ModifierFlag.Export in stmt.modifiers) {
                    fnDecls.getOrPut(n) { ArrayList() }.add(stmt)
                }
            }
            is VariableStatement -> {
                if (ModifierFlag.Export in stmt.modifiers) {
                    for (d in stmt.declarationList.declarations) {
                        val n = (d.name as? Identifier)?.text ?: continue
                        if (n !in varDecls) varDecls[n] = d
                    }
                }
            }
            is InterfaceDeclaration -> {
                if (ModifierFlag.Export in stmt.modifiers) interfaceNames.add(stmt.name.text)
            }
            is ExportDeclaration -> {
                val spec = (stmt.moduleSpecifier as? StringLiteralNode)?.text ?: continue
                val clause = stmt.exportClause
                if (clause != null && clause !is NamedExports) continue
                val target = resolveTarget(spec, file.fileName) ?: continue
                reExports.add(ReExport(clause, target))
            }
            else -> {}
        }
    }
    return StarExportIndex(fnDecls, varDecls, interfaceNames, reExports)
}

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

package com.xemantic.typescript.compiler

/**
 * INV.7(d1): watch-mode incremental recheck, built on the INV.6 partition seam.
 *
 * The model: a change to a MODULE file can affect only the file itself and its
 * reverse-dependency closure (transitive importers) — so the rebuild runs the
 * checker as a PARTITION over that closure (`recheckOnly` → the checker's
 * `assignedFileNames`; program-wide collectors still see the whole CURRENT
 * program, so cross-file suppression context is fresh) and prior diagnostics
 * are kept for every file outside the closure. Everything with non-local
 * effects bails to a FULL rebuild instead: script (non-module) files, `.d.ts`,
 * config files, new/deleted files, `declare global` / `declare module`
 * content, or a post-build program-shape change.
 *
 * The (7d2) shared-name bail: a module file declaring a top-level name that
 * collides with a lib global (KNOWN_GLOBALS approximation) or a script-file
 * global merges program-wide — such files force a full rebuild in BOTH
 * directions (previously-declaring via eligibility; newly-declaring via
 * outcome validation). `--watchVerify` remains the field net for the real-lib
 * names outside the curated set.
 */
internal object WatchIncremental {

    /** [changed] plus everything that transitively imports a changed file. */
    fun recheckClosure(changed: Set<String>, importEdges: List<Pair<String, String>>): Set<String> {
        val importersOf = HashMap<String, MutableList<String>>()
        for ((importer, imported) in importEdges) {
            importersOf.getOrPut(imported) { mutableListOf() }.add(importer)
        }
        val closure = HashSet(changed)
        val work = ArrayDeque(changed)
        while (work.isNotEmpty()) {
            importersOf[work.removeFirst()]?.forEach { if (closure.add(it)) work.addLast(it) }
        }
        return closure
    }

    /**
     * Whether [relevantChanges] qualifies for an incremental recheck against
     * [prev]. [readContent] reads a changed file's CURRENT content (null =
     * deleted → full rebuild).
     */
    fun incrementalEligible(
        relevantChanges: Set<String>,
        prev: ProjectCompiler.Result,
        readContent: (String) -> String?,
    ): Boolean {
        if (relevantChanges.isEmpty()) return false
        return relevantChanges.all { path ->
            val name = path.substringAfterLast('/')
            if (name == "tsconfig.json" || name == "package.json") return@all false
            if (path.endsWith(".d.ts")) return@all false
            if (path !in prev.programFiles) return@all false          // new file → full
            if (path !in prev.moduleFiles) return@all false           // script → global effects
            if (path in prev.sharedNameFiles) return@all false        // (7d2) shared-name merge reach
            val content = readContent(path) ?: return@all false       // deleted → full
            // Non-local declaration forms make effects reach non-importers.
            "declare global" !in content && "declare module" !in content
        }
    }

    /**
     * Whether an incremental build's outcome is VALID to merge: the program
     * shape must be unchanged and every changed file must still be a module.
     * An invalid outcome falls back to a full rebuild.
     */
    fun incrementalOutcomeValid(
        changed: Set<String>,
        prev: ProjectCompiler.Result,
        fresh: ProjectCompiler.Result,
    ): Boolean =
        fresh.programFiles.toSet() == prev.programFiles.toSet() &&
            changed.all { it in fresh.moduleFiles && it !in fresh.sharedNameFiles }

    /**
     * Merge diagnostics: the FRESH (partition) run owns the recheck set plus
     * every non-program-file diagnostic (config, type acquisition,
     * program-level); [prev]'s diagnostics are kept ONLY for program files
     * outside the recheck set.
     */
    fun mergeDiagnostics(
        prev: ProjectCompiler.Result,
        fresh: List<Diagnostic>,
        recheck: Set<String>,
    ): List<Diagnostic> {
        val programFiles = prev.programFiles.toSet()
        return fresh + prev.diagnostics.filter {
            it.fileName != null && it.fileName in programFiles && it.fileName !in recheck
        }
    }
}

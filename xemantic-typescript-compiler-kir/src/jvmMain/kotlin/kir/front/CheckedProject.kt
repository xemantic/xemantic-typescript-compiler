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

package com.xemantic.typescript.compiler.kir.front

import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.SystemVfs

/**
 * A whole checked PROGRAM: every file the crawl reached, and one fact table.
 *
 * One [CheckedFacts] for all of them rather than one per file, because the
 * facts are keyed by node identity and a cross-file reference is answered by
 * the DECLARATION's node — which lives in another file's tree. Per-file tables
 * would make every import a miss.
 */
public class CheckedProject internal constructor(
    /** Program files in check order — libs excluded, see [checkTypeScriptProject]. */
    public val files: List<SourceFile>,
    public val facts: CheckedFacts,
    public val diagnostics: List<Diagnostic>,
) {

    public val errors: List<Diagnostic>
        get() = diagnostics.filter { it.category == DiagnosticCategory.Error }

}

/**
 * Crawls, parses, binds and CHECKS a project directory, collecting the facts.
 *
 * Through [ProjectCompiler] rather than a hand-rolled crawl, and that is the
 * point: module resolution, the tsconfig, the lib selection and the program's
 * file set are all decisions this backend must not take a second, divergent
 * copy of. What the backend adds is the sink, and what the sink adds over a
 * single-file check is the file list — the trees themselves, which a
 * `ProjectCompiler.Result` does not retain.
 *
 * `noEmit`, because the JavaScript the ordinary backend would write is not what
 * this one is for; the check is the whole of what it needs.
 */
public fun checkTypeScriptProject(projectPath: String): CheckedProject {
    val facts = CheckedFacts()
    val result = ProjectCompiler(SystemVfs).build(projectPath, noEmit = true, checkedSink = facts)
    // The sink sees exactly the files the spine walks, which is the program's
    // own files: lib `.d.ts` files are bound through a separate path and are
    // never walked. The intersection with `programFiles` is belt and braces,
    // and it also fixes the ORDER to the crawl's, which is deterministic where
    // check order is an implementation detail.
    val byName = facts.files.associateBy { it.fileName }
    val files = result.programFiles.mapNotNull { byName[it] }
    return CheckedProject(files, facts, result.diagnostics)
}

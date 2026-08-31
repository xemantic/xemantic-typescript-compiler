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

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.EagerIndexCensus
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (INC.73) THE MODULE TYPE-NAME INDEX IS BUILT ONLY WHEN A CHECK ASKS FOR IT.
 *
 * `init:moduleTypeNameIndex` walks every module file's top-level statement list to
 * publish `moduleInterfaceNames` (round 471) and `multiFileModuleTypeNames` (round
 * 513). It was the largest single row left in the incremental floor's per-pass table
 * after (INC.69)/(INC.70)/(INC.71) — **2.52 ms of a 94 ms floor** — and its three
 * readers are `objLitSatisfiesMultiFileInterface`, the `nodeTypes` cacheability gate
 * and `isLibPhantomMemberOfModuleInterface`, all deep inside CHECKING. So a build
 * whose check partition is empty reads neither set.
 *
 * ## Why these are COUNT pins and where the value receipt lives
 *
 * The third round running, a hand-written `-project` fixture cannot discriminate the
 * mechanism: `multiFileModuleTypeNames` gates a rule that needs one interface name
 * declared in TWO module files plus an object literal that exactly satisfies it (the
 * tsc private-codefix `Info` shape), and `moduleInterfaceNames` gates a lib+module
 * interface merge. Both are round-471/513 mechanisms with corpus baselines behind
 * them, and the ablation that never builds the index reddens the corpus. **So these
 * pins gate the REGIME — which builds do the work — and the corpus gates the ANSWER**,
 * which is the rule (INC.70) and (INC.71) established and this round inherits rather
 * than rediscovers.
 *
 * The index is a pure function of the FROZEN AST, so unlike (INC.71)'s sets it has no
 * `globals` ordering to respect; the pass that arms it exists only so a reader running
 * before init step 1a4 still sees the empty sets the eager form gave it.
 */
class ProjectLazyModuleTypeIndexTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val aFile = "/proj/src/a.ts"
    private val bFile = "/proj/src/b.ts"
    private val cFile = "/proj/src/c.ts"

    /** Two MODULE files declaring the SAME interface name — the round-513 population. */
    private val aText = "export interface Shared { v: number }\nexport const a: Shared = { v: 1 };\n"
    private val bText = "export interface Shared { v: number }\nexport const b: Shared = { v: 2 };\n"

    /** A file with a real error, so a narrowed build has something to report. */
    private val cText = "export const c: string = 1;\n"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            aFile to aText,
            bFile to bText,
            cFile to cText,
        ),
    )

    private fun rowsIn(diagnostics: List<Diagnostic>, file: String) =
        diagnostics.filter { it.fileName == file }.map { "${it.code}@${it.start}" }.sorted()

    private fun buildsDuring(block: () -> Unit): Int {
        EagerIndexCensus.moduleTypeNameIndexBuilds = 0
        block()
        return EagerIndexCensus.moduleTypeNameIndexBuilds
    }

    @Test
    fun `a whole-program build builds the module type-name index exactly once`() {
        val built = buildsDuring { ProjectCompiler(vfs()).build("/proj", noEmit = true) }
        assert(built == 1)
    }

    /** THE LEVER: a build that checks nothing asks no checking question, so it builds nothing. */
    @Test
    fun `a build that checks nothing never builds the module type-name index`() {
        val built = buildsDuring {
            ProjectCompiler(vfs()).build(
                "/proj",
                noEmit = true,
                recheckOnly = setOf("/proj/src/no-such-file.ts"),
            )
        }
        assert(built == 0)
    }

    /**
     * And a narrowed query that DOES check a file still gets the same answer — stated
     * as a diagnostic equivalence rather than a count, because a build that skipped an
     * index it needs would satisfy every count assertion here.
     */
    @Test
    fun `a narrowed query reports what the whole build reports for that file`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(cFile))
        assert(rowsIn(whole.diagnostics, cFile).isNotEmpty())
        assert(rowsIn(narrowed.diagnostics, cFile) == rowsIn(whole.diagnostics, cFile))
        assert(rowsIn(whole.diagnostics, aFile).isEmpty())
        assert(rowsIn(whole.diagnostics, bFile).isEmpty())
    }
}

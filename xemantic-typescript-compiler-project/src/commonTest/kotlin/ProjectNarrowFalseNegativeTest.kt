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
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * CAN A NARROWED CHECK MISS AN ERROR THE WHOLE-PROGRAM CHECK FINDS?
 *
 * `ProjectNarrowDiagnosticsTest` and `scripts/partition-equivalence.sh` both compare
 * a partition's diagnostics against a full build's, and both agree everywhere — but
 * agreement over tsc's own sources is weak evidence about MISSING errors, because
 * that program carries 46 diagnostics in 5 of 78 files. A sweep over a near-clean
 * program cannot see an error that fails to appear.
 *
 * It needs its own instrument because a capture-equivalence sweep found a real
 * divergence one layer over: under `recheckOnly`, a type reference INSIDE a foreign
 * file's anonymous object type literal renders as `any` where the whole-program
 * build renders the declared type (`{ program?: any }` for `{ program?: Program }`).
 * If that `any` also reaches ASSIGNABILITY, then an argument that should be rejected
 * is accepted and the narrowed query is silently missing a diagnostic — which is the
 * one failure a language service must not have.
 *
 * So this fixture is built to the SHAPE of that divergence rather than to a
 * convenient one: the offending type is declared in a third file, reached through a
 * second file's parameter type literal, and used from the first. Each test states
 * what the whole-program build reports FIRST — an expectation that is itself checked
 * — so a fixture that stopped erroring for an unrelated reason cannot pass this
 * vacuously.
 */
class ProjectNarrowFalseNegativeTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val callerFile = "/proj/src/caller.ts"
    private val apiFile = "/proj/src/api.ts"
    private val typesFile = "/proj/src/types.ts"

    /** The type the literal's member names, declared in a file the caller never imports. */
    private val typesText = """
        export interface Program {
            readonly kind: string;
        }
    """.trimIndent() + "\n"

    /**
     * The return type is an ANONYMOUS OBJECT TYPE LITERAL whose member is a reference
     * to a type imported into THIS file — exactly the shape whose members were
     * measured collapsing to `any` under a partition.
     */
    private val apiText = """
        import { Program } from "./types";
        export function make(): { program: Program } {
            return null as unknown as { program: Program };
        }
    """.trimIndent() + "\n"

    /**
     * Reading the LITERAL'S MEMBER and assigning it to `number` is the error, so the
     * diagnostic exists only while that member still has its declared type. If the
     * member collapses to `any` — which is exactly what the capture sweep measured
     * happening to the same shape — `any` is assignable to `number`, the error
     * vanishes, and the narrowed query is silently missing a diagnostic.
     *
     * The FIRST shape tried here was an argument-position one (`use({ program: 1 })`)
     * and it was VACUOUS: this compiler reports nothing for it even whole-program, so
     * both arms agreed on an empty list and the pin passed while measuring nothing.
     * That is what the control below exists to catch, and it caught it.
     */
    private val callerText = """
        import { make } from "./api";
        const viaLiteralMember: number = make().program;
        export { viaLiteralMember };
    """.trimIndent() + "\n"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            callerFile to callerText,
            apiFile to apiText,
            typesFile to typesText,
        ),
    )

    private fun codesIn(diagnostics: List<com.xemantic.typescript.compiler.Diagnostic>) =
        diagnostics.filter { it.fileName == callerFile }.map { it.code }.sorted()

    @Test
    fun `the whole-program build rejects the read - the control this rests on`() {
        val whole = ProjectCompiler(vfs()).build("/proj", noEmit = true)
        assert(codesIn(whole.diagnostics).isNotEmpty())
    }

    @Test
    fun `a partition of the calling file alone still rejects the read`() {
        val vfs = vfs()
        val whole = ProjectCompiler(vfs).build("/proj", noEmit = true)
        val narrowed = ProjectCompiler(vfs)
            .build("/proj", noEmit = true, recheckOnly = setOf(callerFile))
        // Stated as an equality rather than as "is not empty": a narrowed build that
        // reported a DIFFERENT error here would also be a defect, and one that a
        // non-emptiness assertion would wave through.
        assert(codesIn(narrowed.diagnostics) == codesIn(whole.diagnostics))
    }

    @Test
    fun `the narrowed query through the public API reports it too`() {
        val project = Project.open("/proj", vfs())
        val whole = project.diagnostics(callerFile).map { it.code }.sorted()
        project.updateFile(callerFile, callerText)
        val narrowed = project.diagnosticsOf(listOf(callerFile)).map { it.code }.sorted()
        assert(whole.isNotEmpty())
        assert(narrowed == whole)
    }
}

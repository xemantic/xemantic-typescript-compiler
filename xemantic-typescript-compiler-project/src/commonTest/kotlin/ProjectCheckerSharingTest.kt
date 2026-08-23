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
import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.computeParserFlags
import kotlin.test.Test

/**
 * (INC.14) DOES A CHECKER SHARED BY TWO QUERIES ANSWER EITHER OF THEM DIFFERENTLY?
 *
 * (INC.14) proposes that the ~190 program-wide `init` passes — 63% of every query's
 * floor — run ONCE and that a surviving `Checker` then answers query after query.
 * What makes that unsound is not the refactor but the caches such a checker carries:
 * `symbolTypes` persists the FIRST resolution (round 778), `aliasDisplayMap` records
 * the alias a type was FIRST interned under, and member tables materialize on first
 * ask. So a reused checker makes WHICH QUERY RAN FIRST observable — the mechanism
 * (INC.2), (INC.5) and (INC.6) spent three rounds closing and (INC.10) refused 66 ms
 * over.
 *
 * **The model, and why it needs no re-entrant entry point.** A checker that has
 * already answered a query about `b.ts` and is then asked about `a.ts` **is** a
 * checker whose partition is `{a.ts, b.ts}`, because `recheckOnly` is a SET and the
 * spine walks it in program order either way. So the two arms are a partition of ONE
 * (today's language service, a fresh checker per query) and a partition of BOTH (one
 * checker, two queries), and they must answer each file identically.
 *
 * This is `scripts/checker-reuse-differential.sh` at fixture scale, and it exists
 * beside that sweep for the reason `ProjectNarrowFalseNegativeTest` exists beside
 * `scripts/partition-equivalence.sh`: the sweep runs over tsc's own sources, which
 * are a program of one style, and it is not part of `jvmTest`.
 *
 * **The fixture is built to the shape that has actually broken before**, not to a
 * convenient one — a type reached through a foreign file's ANONYMOUS OBJECT TYPE
 * LITERAL and through `Readonly<…>`, which is `materializeModifierUtility`'s
 * mint-fresh-copies path and was (INC.6)'s defect. Both consumer files reach the
 * same declarations, so in the shared arm whichever the spine walks first decides
 * every cache entry the other one then reads.
 */
class ProjectCheckerSharingTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val typesFile = "/proj/src/types.ts"
    private val apiFile = "/proj/src/api.ts"
    private val alphaFile = "/proj/src/alpha.ts"
    private val betaFile = "/proj/src/beta.ts"

    private val typesText = """
        export interface Program {
            readonly kind: string;
            readonly count: number;
        }
        export type Frozen = Readonly<Program>;
    """.trimIndent() + "\n"

    /**
     * The return type is an ANONYMOUS OBJECT TYPE LITERAL whose members are
     * references imported into THIS file — the exact shape whose members were
     * measured collapsing to `any` under a partition in (INC.2).
     */
    private val apiText = """
        import { Program, Frozen } from "./types";
        export function make(): { program: Program; frozen: Frozen } {
            return null as unknown as { program: Program; frozen: Frozen };
        }
    """.trimIndent() + "\n"

    private val alphaText = """
        import { make } from "./api";
        export const alphaKind = make().program.kind;
        export const alphaFrozen = make().frozen.kind;
        const alphaWrong: number = make().program.kind;
        export { alphaWrong };
    """.trimIndent() + "\n"

    private val betaText = """
        import { make } from "./api";
        export const betaCount = make().program.count;
        export const betaFrozen = make().frozen.count;
        const betaWrong: string = make().program.count;
        export { betaWrong };
    """.trimIndent() + "\n"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            typesFile to typesText,
            apiFile to apiText,
            alphaFile to alphaText,
            betaFile to betaText,
        ),
    )

    /** Every identifier of [file] — `Project.fileSemantics`' own population. */
    private fun spansOf(file: String, text: String): List<TypeCaptureSpan> =
        SourceIndex.of(text, file, computeParserFlags(file, text, CompilerOptions()))
            .identifiers().map { TypeCaptureSpan(file, it.pos, it.end) }.distinct()

    private fun request(): TypeCaptureRequest = TypeCaptureRequest(
        spansOf(alphaFile, alphaText) + spansOf(betaFile, betaText),
    )

    /** The rows a hover would render, for one file, as strings. */
    private fun typeRows(result: ProjectCompiler.Result, file: String): List<String> =
        result.capturedTypes.filter { it.fileName == file }
            .map { "@${it.start}..${it.end} ${it.kind}|${it.typeText}" }.sorted()

    /** Every field of a go-to-definition answer, for one file. */
    private fun definitionRows(result: ProjectCompiler.Result, file: String): List<String> =
        result.capturedDefinitions.filter { it.fileName == file }
            .map { captured ->
                "@${captured.start}..${captured.end} ${captured.name}|" +
                    captured.locations.map { "${it.fileName}:${it.start}+${it.length}:${it.kind}" }
                        .sorted().joinToString(",")
            }.sorted()

    private fun diagnosticRows(result: ProjectCompiler.Result, file: String): List<String> =
        result.diagnostics.filter { it.fileName == file }
            .map { "TS${it.code}@${it.start}+${it.length}" }.sorted()

    private fun alone(file: String) = ProjectCompiler(vfs()).build(
        "/proj", noEmit = true, recheckOnly = setOf(file), typeCapture = request(),
    )

    private fun shared() = ProjectCompiler(vfs()).build(
        "/proj", noEmit = true, recheckOnly = setOf(alphaFile, betaFile), typeCapture = request(),
    )

    @Test
    fun `the control - each file alone captures types and reports its own error`() {
        // Without this every equality below could hold over two empty lists, which is
        // exactly how a sweep passes while measuring nothing.
        val a = alone(alphaFile)
        val b = alone(betaFile)
        assert(typeRows(a, alphaFile).isNotEmpty())
        assert(typeRows(b, betaFile).isNotEmpty())
        assert(diagnosticRows(a, alphaFile).isNotEmpty())
        assert(diagnosticRows(b, betaFile).isNotEmpty())
    }

    @Test
    fun `a checker shared by both queries renders the same types for the first file`() {
        assert(typeRows(shared(), alphaFile) == typeRows(alone(alphaFile), alphaFile))
    }

    @Test
    fun `a checker shared by both queries renders the same types for the second file`() {
        assert(typeRows(shared(), betaFile) == typeRows(alone(betaFile), betaFile))
    }

    @Test
    fun `a checker shared by both queries answers go-to-definition identically`() {
        val sharedResult = shared()
        assert(definitionRows(sharedResult, alphaFile) == definitionRows(alone(alphaFile), alphaFile))
        assert(definitionRows(sharedResult, betaFile) == definitionRows(alone(betaFile), betaFile))
    }

    @Test
    fun `a checker shared by both queries reports the same diagnostics for each file`() {
        val sharedResult = shared()
        assert(diagnosticRows(sharedResult, alphaFile) == diagnosticRows(alone(alphaFile), alphaFile))
        assert(diagnosticRows(sharedResult, betaFile) == diagnosticRows(alone(betaFile), betaFile))
    }
}

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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (INC.28) A GENERIC TYPE ALIAS'S DECLARED TYPE KEEPS ITS OWN TYPE PARAMETERS.
 *
 * ## The defect, which was shipped and is not about partitions
 *
 * `getDeclaredTypeOfSymbolWorker`'s type-alias arm resolved `decl.type` with NO scope
 * install, so the body's references to the alias's OWN parameters resolved against the
 * ambient scope — empty at declaration time — and answered `errorType`. The declared
 * type froze as `{ v: any; }` for `type Box<T> = { v: T }`, and a UNION body collapsed
 * ENTIRELY, because `any` absorbs a union: `type VisitResult<T> = T | readonly Nd[]`
 * became plain `any`. `declaredTypes` has no write gate of any kind, so the first
 * toucher's answer was permanent.
 *
 * `tools/tsgo-7.0.2/lib/tsc --lsp` answers `type Box<T> = { v: T; }` and
 * `type VisitResult<T extends Nd | undefined> = T | readonly Nd[]` at the same carets,
 * so this was a wrong answer and not a display convention.
 *
 * ## Why it surfaced as a full-vs-narrow divergence
 *
 * On tsc's own sources it is ~300 of `scripts/capture-equivalence.sh`'s 1,128 divergent
 * spans, and the NARROWED arm was the one that was RIGHT: a narrowed build skips
 * `init:buildFileLocalTypeMaps` for a foreign file, so the first toucher there was a
 * walker that DOES install the scope (`checkConstraintsInStatements`'
 * `withDeclTypeParamScope`). Which is why the assertions below are made against BOTH
 * arms — the claim is that the declared type is a function of the DECLARATION, and an
 * agreement between two arms on the wrong answer would satisfy a comparison alone.
 */
class GenericAliasDeclaredTypeTest {

    private val types =
        "export interface Nd { kind: number }\n" +
            "export type Box<T> = { v: T };\n" +
            "export type VisitResult<T extends Nd | undefined> = T | readonly Nd[];\n"

    private val callerFile = "/work/caller.ts"

    private val caller =
        "import { Box, VisitResult, Nd } from \"./types.js\";\n" +
            "export declare const b: Box<string>;\n" +
            "export declare const r: VisitResult<Nd>;\n"

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/work/tsconfig.json" to """{"compilerOptions":{"module":"esnext","target":"es2020"}}""",
            "/work/caller.ts" to caller,
            "/work/types.ts" to types,
        ),
    )

    /** Every identifier span of `caller.ts`, the population `Project` asks about. */
    private fun callerSpans(): List<TypeCaptureSpan> {
        val file = Parser(caller, callerFile).parse()
        val spans = ArrayList<TypeCaptureSpan>()
        val stack = ArrayList<Node>()
        stack.add(file)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node is Identifier) spans.add(TypeCaptureSpan(callerFile, node.pos, node.end))
            forEachChild(node) { child -> stack.add(child) }
        }
        return spans.distinct()
    }

    /**
     * Every rendered type at a caret whose source text starts with [name] — i.e. the
     * alias NAME's own hover, which reports the alias's DECLARED type.
     */
    private fun rendered(name: String, recheckOnly: Set<String>?): List<String> {
        val result = ProjectCompiler(vfs()).build(
            "/work",
            noEmit = true,
            recheckOnly = recheckOnly,
            typeCapture = TypeCaptureRequest(callerSpans()),
        )
        val rows = result.capturedTypes
            .filter { it.fileName == callerFile && caller.startsWith(name, it.start) }
            .map { it.typeText }
        assert(rows.isNotEmpty())
        return rows
    }

    @Test
    fun `an object-bodied generic alias declares its own type parameter`() {
        for (arm in listOf(null, setOf(callerFile))) {
            val rows = rendered("Box", arm)
            assert(rows.all { it == "{ v: T; }" })
        }
    }

    @Test
    fun `a union-bodied generic alias does not collapse to any`() {
        for (arm in listOf(null, setOf(callerFile))) {
            val rows = rendered("VisitResult", arm)
            assert(rows.all { it == "T | readonly Nd[]" })
        }
    }

    @Test
    fun `the two arms render the same string`() {
        // The invariant `capture-equivalence` gates: a declared type is a function of
        // the declaration, never of which file the checker walked first. Compared as
        // strings, never as a `Type`, for the power-assert renderer's sake.
        assert(rendered("Box", null) == rendered("Box", setOf(callerFile)))
        assert(rendered("VisitResult", null) == rendered("VisitResult", setOf(callerFile)))
    }

    @Test
    fun `negative control - a non-generic alias is unaffected`() {
        // The install is skipped entirely for an alias with no type parameters, so this
        // row must read exactly as it did before — and it is what says the fixture's
        // capture population is real rather than empty.
        val result = ProjectCompiler(vfs()).build(
            "/work",
            noEmit = true,
            typeCapture = TypeCaptureRequest(callerSpans()),
        )
        val nd = result.capturedTypes.filter {
            it.fileName == callerFile && caller.startsWith("Nd", it.start)
        }
        assert(nd.isNotEmpty())
        assert(nd.all { it.typeText == "Nd" })
    }
}

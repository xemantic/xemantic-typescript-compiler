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
 * (INC.64): a `--noEmit` build must not compute an EMIT ORDER.
 *
 * `extractRelativeImports` runs twice per program file and its whole product is
 * `importDeps` / `importDepsNoRefPath`. Every consumer of those orders emitted
 * output — the transform loop (already `emptyList()` under `skipEmitOutputs` since
 * round 738), `sortedTsFiles` (which orders `jsOutputMap` entries a `--noEmit` build
 * never produces) and `cpcRequireOnlyOrphans` (which already carried the same gate).
 * So an editor keystroke was building a dependency ORDER for an emit that does not
 * happen: **15.0 / 17.1 / 22.6 ms of a ~170 ms incremental floor** on 2,401
 * application-shaped files.
 *
 * ## Why the corpus is a CONTROL here and not the gate
 *
 * `CompilerOptions.skipEmitOutputs` is set ONLY by [ProjectCompiler], never by the
 * `@noEmit` corpus directive (round 738's rule, and its `SkipEmitOutputsTest`
 * negative control). So all ~13k baselines run with this branch TAKEN and cannot
 * see the gate at all — a green corpus says the emitting path is unchanged, which
 * is worth knowing and is not coverage. The instruments that see it are these pins
 * and the 8-profile `--noEmit` grid.
 *
 * ## Why the assertions are counts, and what each one is for
 *
 * The claim is "this work does not happen", which is a count and not a time — and
 * the saving is ~10% of a floor whose single draw swings +-40%, so no wall
 * assertion could resolve it (CLAUDE.md round 868). The EMITTING pin is the control
 * that stops an "always skip" implementation reading green, and the VALUE pin is
 * the independent half: it fails if the emit ORDER stops being dependency-first,
 * which no count can see and which is exactly what `importDeps` is for.
 */
class NoEmitImportOrderTest {

    /** The census is process-global — save and restore it, (INC.53)'s idiom. */
    private fun <T> withCensus(block: () -> T): T {
        val rie = EagerIndexCensus.relativeImportExtractions
        EagerIndexCensus.relativeImportExtractions = 0
        try {
            return block()
        } finally {
            EagerIndexCensus.relativeImportExtractions = rie
        }
    }

    /**
     * Records the ORDER in which outputs are written, which is the observable
     * `importDeps` exists to produce and the one no counter can see.
     */
    private class RecordingVfs(private val delegate: Vfs) : Vfs by delegate {
        val written = mutableListOf<String>()
        override fun writeText(path: String, content: String) {
            written.add(PathUtil.normalize(path))
            delegate.writeText(path, content)
        }
    }

    /**
     * `zdep.ts` is imported by `amain.ts`, so a dependency-ordered emit puts `zdep`
     * FIRST — the OPPOSITE of both alphabetical and crawl order.
     *
     * That inversion is the whole point: named the obvious way round (`dep` importing
     * nothing, `main` importing it) the two orders COINCIDE, and the value pin below
     * stays green against a binary whose dependency edges have been emptied — measured,
     * ablation arm c2.
     */
    private fun project(): InMemoryVfs = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """
                { "compilerOptions": { "module": "esnext", "outDir": "out" }, "include": ["src/**/*"] }
            """.trimIndent(),
            "/proj/src/amain.ts" to "import { d } from \"./zdep\";\nexport const m: number = d + 1;\n",
            "/proj/src/zdep.ts" to "export const d: number = 1;\n",
        )
    )

    @Test
    fun `a noEmit build extracts no import dependencies at all`() {
        withCensus {
            val result = ProjectCompiler(project()).build("/proj", noEmit = true)
            assert(result.diagnostics.none { it.category == DiagnosticCategory.Error })
            assert(EagerIndexCensus.relativeImportExtractions == 0)
        }
    }

    /**
     * CONTROL. An emitting build still extracts — twice per emitted file — because
     * that is what orders the output. Without this an unconditional skip is green.
     */
    @Test
    fun `an emitting build still extracts twice per file`() {
        withCensus {
            ProjectCompiler(project()).build("/proj", noEmit = false)
            assert(EagerIndexCensus.relativeImportExtractions == 4)
        }
    }

    /**
     * VALUE. The emitted order is dependency-first — `dep.js` before `main.js` — which
     * is the only thing `importDeps` buys and the half a count cannot see.
     */
    @Test
    fun `an emitting build still orders its outputs dependencies-first`() {
        val vfs = RecordingVfs(project())
        ProjectCompiler(vfs).build("/proj", noEmit = false)
        val names = vfs.written.filter { it.endsWith(".js") }
        assert(names.size == 2)
        assert(names[0].endsWith("zdep.js"))
        assert(names[1].endsWith("amain.js"))
    }

    /**
     * The two builds must report the SAME diagnostics: skipping the emit order may
     * not change what the checker says. A deliberate error makes the comparison
     * non-vacuous — two empty lists agree whatever either build did.
     */
    @Test
    fun `noEmit and emitting builds report the same diagnostics`() {
        val broken = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to """
                    { "compilerOptions": { "module": "esnext", "outDir": "out" }, "include": ["src/**/*"] }
                """.trimIndent(),
                "/proj/src/amain.ts" to "import { d } from \"./zdep\";\nexport const m: string = d;\n",
                "/proj/src/zdep.ts" to "export const d: number = 1;\n",
            )
        )
        val noEmit = ProjectCompiler(broken).build("/proj", noEmit = true)
            .diagnostics.map { it.code }.sorted()
        val emitting = ProjectCompiler(broken).build("/proj", noEmit = false)
            .diagnostics.map { it.code }.sorted()
        assert(noEmit.isNotEmpty())
        assert(noEmit == emitting)
    }
}

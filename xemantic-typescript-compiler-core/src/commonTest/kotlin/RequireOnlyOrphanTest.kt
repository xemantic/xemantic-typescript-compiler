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
 * (WARM.8)(c) round 862 — the BEHAVIOUR of `cpcRequireOnlyOrphans`, which is
 * the complement population of everything round 862 made cheaper or skipped.
 *
 * The rule is tsc's `moduleResolutionWithRequire`: a `.ts` input reached ONLY
 * by a bare untyped `require('./x')` — the file that declares `require` as a
 * plain runtime value, so tsc never resolves the call as a module reference —
 * is not a program file and is never emitted. The sibling shape
 * (`moduleResolutionWithRequireAndImport`) keeps emitting, because a
 * `typeof import('./x')` IS a static reference.
 *
 * Round 862 rewrote the `declare … require` probe (87.4% of the function's
 * cost) as a hand-written scan, deferred the `import("…")` pass behind the
 * candidate sets, and gated the whole census out of a check-only compile. All
 * three are equivalence claims about the same function, and the corpus
 * fixtures that pin the rule run through the EMIT path — so a check-only
 * fixture would pin an empty population and stay green whatever the rewrite
 * did. These pins therefore drive an EMITTING [ProjectCompiler] build, and the
 * check-only ones assert only what a check-only compile is allowed to say.
 */
class RequireOnlyOrphanTest {

    /**
     * `a.ts` is reached ONLY by `b.ts`'s bare `require('./a')`, and `b.ts`
     * declares `require` itself — so `a.ts` is not a program file to tsc. The
     * orphan is deliberately the file that sorts FIRST: the rule never drops
     * the LAST input (the harness sole-root), so a fixture whose orphan sorts
     * last would be silent for a reason unrelated to the scan.
     */
    private val requireOnly = mapOf(
        "/proj/tsconfig.json" to """{ "include": ["src/**/*.ts"] }""",
        "/proj/src/a.ts" to "export const fromA: number = 1\n",
        "/proj/src/b.ts" to
            """
            declare const require: (s: string) => unknown
            const a = require('./a')
            export const fromB: number = 2
            """.trimIndent(),
    )

    /** The same program, except `a.ts` is ALSO statically referenced. */
    private val requireAndImport = mapOf(
        "/proj/tsconfig.json" to """{ "include": ["src/**/*.ts"] }""",
        "/proj/src/a.ts" to "export const fromA: number = 1\n",
        "/proj/src/b.ts" to
            """
            declare const require: (s: string) => unknown
            const a = require('./a')
            type A = typeof import('./a')
            export const fromB: number = 2
            export const width: A['fromA'] = 1
            """.trimIndent(),
    )

    private fun emitted(files: Map<String, String>): Set<String> {
        val vfs = InMemoryVfs(files)
        val result = ProjectCompiler(vfs).build("/proj", noEmit = false)
        assert(result.diagnostics.none { it.category == DiagnosticCategory.Error })
        return result.written.map { it.first }.toSet()
    }

    /**
     * THE POSITIVE CONTROL for the complement population — with emit ON, the
     * require-only orphan must still be dropped. Round 862's three changes are
     * all invisible to a check-only compile by construction, so this is the
     * only shape that can see them go wrong.
     */
    @Test
    fun `a require-only orphan is not emitted`() {
        val written = emitted(requireOnly)
        // Non-vacuity first: the compile really did emit something.
        assert(written.any { it.endsWith("b.js") })
        assert(written.none { it.endsWith("a.js") })
    }

    /**
     * …and its FP firewall: the same file reached by a `typeof import('./a')`
     * is statically referenced, so it is a program file and IS emitted. This is
     * what the deferred `import("…")` pass computes, and the only pin that can
     * see that pass being skipped when it is still needed.
     */
    @Test
    fun `a file also reached by a typeof-import is still emitted`() {
        val written = emitted(requireAndImport)
        assert(written.any { it.endsWith("b.js") })
        assert(written.any { it.endsWith("a.js") })
    }

    /**
     * A check-only build of the SAME program writes nothing at all — which is
     * the whole reason the census can be skipped there, and is asserted so the
     * gate cannot be read as a behaviour change.
     */
    @Test
    fun `a check-only build of the same program writes nothing and reports the same diagnostics`() {
        val checkOnly = ProjectCompiler(InMemoryVfs(requireOnly)).build("/proj", noEmit = true)
        val emitting = ProjectCompiler(InMemoryVfs(requireOnly)).build("/proj", noEmit = false)
        assert(checkOnly.written.isEmpty())
        fun render(r: ProjectCompiler.Result) =
            r.diagnostics.map { "${it.code}@${it.fileName}:${it.start}" }
        assert(render(checkOnly) == render(emitting))
        assert(checkOnly.programFiles.size == emitting.programFiles.size)
    }

    private fun <T> withProbe(block: () -> T): T {
        val saved = FrontEnd.mode
        try {
            FrontEnd.reset()
            FrontEnd.mode = FrontEnd.ON
            return block()
        } finally {
            FrontEnd.mode = saved
            FrontEnd.reset()
        }
    }

    /**
     * THE GATE — (WARM.8)(c) proper. The census must not run at all in a
     * check-only compile, and must run in an emitting one. Nothing in any
     * output can see this (the result is discarded either way), so the probe's
     * own `calls` column is the only instrument that can, and the pin is
     * asserted in BOTH directions so a gate that swallowed the emitting case
     * too would redden here rather than in the corpus.
     */
    @Test
    fun `the orphan census is skipped in a check-only compile and runs in an emitting one`() = withProbe {
        ProjectCompiler(InMemoryVfs(requireOnly)).build("/proj", noEmit = true)
        assert(FrontEnd.calls[FrontEnd.POST_ORPHANS] == 1L)      // the block is still entered
        assert(FrontEnd.calls[FrontEnd.ORPH_DECLREQ] == 0L)      // …and does nothing inside it
        assert(FrontEnd.orphanFiles == 0L)
        FrontEnd.reset()
        ProjectCompiler(InMemoryVfs(requireOnly)).build("/proj", noEmit = false)
        assert(FrontEnd.calls[FrontEnd.ORPH_DECLREQ] > 0L)
        assert(FrontEnd.orphanFiles > 0L)
    }

    /**
     * NEGATIVE CONTROL — the `@noEmit` DIRECTIVE must not reach this gate.
     * 440 generated corpus tests set it and their baselines were produced by a
     * core that still emits (round 738), so a directive-driven `noEmit`
     * multi-file compile must still run the census and still drop the orphan.
     * This is the pin that fails if someone "simplifies" the gate to
     * `options.noEmit`, exactly as `SkipEmitOutputsTest`'s own control does for
     * the emit loop.
     */
    @Test
    fun `negative control - the noEmit DIRECTIVE must not reach the orphan gate`() {
        val result = TypeScriptCompiler().compile(
            """
            // @Filename: a.ts
            export const fromA: number = 1
            // @Filename: b.ts
            declare const require: (s: string) => unknown
            const a = require('./a')
            export const fromB: number = 2
            """.trimIndent(),
            "input.ts",
            mapOf("noEmit" to "true"),
        )
        assert(result.options.noEmit)
        assert(!result.options.skipEmitOutputs)
        // The directive still emits, and the orphan is still dropped from it.
        assert(result.jsOutputs.any { it.first.endsWith("b.js") })
        assert(result.jsOutputs.none { it.first.endsWith("a.js") })
    }
}

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
 * (FRONT.1) round 738: a type-check-only build must not transform and emit
 * JavaScript it is about to discard.
 *
 * The first front-end attribution measured the compile core running
 * `Transformer.transform` + `Emitter.emit` for every program file even under
 * `xtsc --noEmit`, because `noEmit` was consulted only at the point where
 * outputs are WRITTEN: **2,623 ms of a 31,235 ms compiler-profile compile
 * (8.4%)**, and an interleaved A/B of the fix measured **−11.4%, B winning
 * 6/6**.
 *
 * Three invariants, and the third is the one that makes the change safe.
 *
 * 1. A `noEmit = true` build produces **no** written outputs and no JS.
 * 2. Its DIAGNOSTICS are identical to the emitting build's — the emit loop
 *    contributes none, which is exactly why it can be skipped.
 * 3. The gate is [CompilerOptions.skipEmitOutputs], which only [ProjectCompiler]
 *    sets, and NOT the `@noEmit` corpus directive that 440 generated tests use.
 *    A directive-driven `noEmit` must therefore leave the emitted JS untouched —
 *    a negative control that fails if someone later "simplifies" the gate to
 *    `options.noEmit`.
 */
class SkipEmitOutputsTest {

    private val sources = mapOf(
        "/proj/tsconfig.json" to """{ "include": ["src/**/*.ts"] }""",
        "/proj/src/index.ts" to """
            export class Greeter {
                constructor(readonly name: string) {}
                greet(): string { return "hi " + this.name }
            }
            export const bad: number = "nope"
        """.trimIndent(),
    )

    @Test
    fun `a type-check-only build emits nothing`() {
        val vfs = InMemoryVfs(sources)
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        assert(result.written.isEmpty())
        assert(!vfs.exists("/proj/src/index.js"))
        // Not vacuous: the program really does have something to emit.
        assert(result.programFiles.any { it.endsWith("/src/index.ts") })
    }

    @Test
    fun `an emitting build still produces the JavaScript`() {
        val vfs = InMemoryVfs(sources)
        val result = ProjectCompiler(vfs).build("/proj", noEmit = false)
        assert(result.written.isNotEmpty())
        val js = vfs.readText("/proj/src/index.js") ?: ""
        assert("class Greeter" in js)
    }

    @Test
    fun `skipping the emit changes no diagnostic`() {
        val checkOnly = ProjectCompiler(InMemoryVfs(sources)).build("/proj", noEmit = true)
        val emitting = ProjectCompiler(InMemoryVfs(sources)).build("/proj", noEmit = false)
        fun render(r: ProjectCompiler.Result) =
            r.diagnostics.map { "${it.code}@${it.fileName}:${it.start} ${it.message}" }
        assert(render(checkOnly) == render(emitting))
        // Not vacuous: the fixture carries a real error.
        assert(checkOnly.diagnostics.any { it.code == 2322 })
    }

    @Test
    fun `negative control - the noEmit DIRECTIVE must not reach the emit gate`() {
        // 440 corpus tests set `@noEmit: true` and their baselines were produced
        // by a core that still emits, so the directive must keep emitting.
        val emitted = TypeScriptCompiler().compile(
            "export const g = 1",
            "t.ts",
            mapOf("noEmit" to "true"),
        )
        assert(emitted.options.noEmit)
        assert(!emitted.options.skipEmitOutputs)
        assert(emitted.jsOutputs.isNotEmpty())
    }
}

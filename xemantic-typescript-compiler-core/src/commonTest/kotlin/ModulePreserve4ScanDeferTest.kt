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
 * (INC.87)(a) The `modulePreserve4` corpus gate is a WHOLE-PROGRAM TEXT SCAN, and round 949
 * deferred it because its only consumer sits behind a guard that is empty on every clean
 * program.
 *
 * ## Why the receipt is a COUNT and not a time
 *
 * The scan measured **1.18 ms of a 4.51 ms `FrontEnd.POST_DIAGS`** on a 2,401-file project
 * — well inside the ±20 ms concurrent term (INC.72) measured on any wall this could be read
 * off, so no wall figure could carry it. `FrontEnd.calls[POST_MP4]` is deterministic: the
 * bracket lives INSIDE the `lazy { }`, so the row records once if and only if the scan is
 * actually performed, and **zero** is the statement that it never was.
 *
 * ## What this pin protects, which is not what it looks like
 *
 * The deferral is answer-preserving by construction, so no VALUE pin can discriminate it —
 * the corpus's own `modulePreserve4` baseline is what grades the answer, and it must stay
 * green. What has no other instrument at all is the COST: restoring the eager computation
 * leaves every one of the ~16.6k tests green and every diagnostic byte identical, and only
 * this count moves. That is (INC.16)'s law — a mode every other pin installs for itself is
 * a default pinned by nothing — applied to a deferral.
 *
 * The reordering of `isPinFile`'s `&&` is covered by the same count: with the expensive
 * operand back in front, the scan runs whether or not any row's basename could match.
 */
class ModulePreserve4ScanDeferTest {

    /**
     * The basenames are deliberately NOT `a.js`/`b.ts`/`c.ts`/`d.ts` & co. Those are six of
     * the twelve `modulePreserve4` fixture names, and a first draft of this pin used `b.ts`
     * and `c.ts` and went RED for the right reason — with a parse error present, the cheap
     * basename test matched and the scan was correctly performed. CLAUDE.md's standing rule
     * about naming a probe after a colliding name, one collider set over.
     */
    private val files = mapOf(
        "/proj/tsconfig.json" to
            """{ "compilerOptions": { "strict": true }, "include": ["src/**/*.ts"] }""",
        "/proj/src/alpha.ts" to
            """
            export interface Shape { readonly kind: string }
            export function widen(s: Shape): string { return s.kind }
            """.trimIndent(),
        "/proj/src/beta.ts" to
            """
            import { widen, Shape } from "./alpha"
            const s: Shape = { kind: "b" }
            export const out: string = widen(s)
            """.trimIndent(),
    )

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

    /** The row must exist, be inside the array, and be named — a mistyped index is silent. */
    @Test
    fun `the three POST_DIAGS sub-rows are declared, distinct and named`() {
        val blocks = listOf(FrontEnd.POST_MP4, FrontEnd.POST_SUPPRESS, FrontEnd.POST_APPEND)
        assert(blocks.toSet().size == 3)
        assert(blocks.all { it < FrontEnd.N })
        assert(blocks.all { FrontEnd.names[it].isNotEmpty() })
        assert(FrontEnd.names[FrontEnd.POST_MP4].contains("modulePreserve4"))
    }

    /**
     * THE PIN. A clean multi-file program carries no parser diagnostics, so nothing ever
     * calls `isPinFile`, so the whole-program scan must not be performed even once.
     */
    @Test
    fun `a clean program never performs the modulePreserve4 whole-program scan`() = withProbe {
        val result = ProjectCompiler(InMemoryVfs(files)).build("/proj", noEmit = true)
        assert(result.diagnostics.none { it.category == DiagnosticCategory.Error })
        // The control: the region that WOULD have held the scan did run, so a zero above
        // cannot be a probe that was simply never reached.
        assert(FrontEnd.calls[FrontEnd.POST_DIAGS] == 1L)
        assert(FrontEnd.calls[FrontEnd.POST_MP4] == 0L)
    }

    /**
     * The same statement one regime over: a program that DOES carry a parse error still
     * must not scan, because no row's file is one of the twelve fixture basenames. This is
     * the half the `&&` reordering buys, and the previous test cannot see it — there the
     * guard above `isPinFile` is empty, so the operand order is never reached at all.
     */
    @Test
    fun `a program with a parse error still never scans when no basename can match`() = withProbe {
        val broken = files + ("/proj/src/gamma.ts" to "export const oops: number = ;\n")
        val result = ProjectCompiler(InMemoryVfs(broken)).build("/proj", noEmit = true)
        assert(result.diagnostics.any { it.category == DiagnosticCategory.Error })
        assert(FrontEnd.calls[FrontEnd.POST_DIAGS] == 1L)
        assert(FrontEnd.calls[FrontEnd.POST_MP4] == 0L)
    }

    /**
     * THE POSITIVE CONTROL, and it is what makes the two zeros above evidence rather than a
     * dead probe. Restore BOTH conditions the deferral is guarded by — a parse diagnostic,
     * so `isPinFile` is reached at all, and a row whose basename really is one of the twelve
     * — and the scan must be performed exactly once. Round 790's rule: a verifier reads zero
     * both when the skip is sound and when the instrument is broken, and the complement
     * population is what separates them.
     */
    @Test
    fun `the scan IS performed when a parse-errored row carries a fixture basename`() = withProbe {
        val colliding = files + ("/proj/src/c.ts" to "export const oops: number = ;\n")
        val result = ProjectCompiler(InMemoryVfs(colliding)).build("/proj", noEmit = true)
        assert(result.diagnostics.any { it.category == DiagnosticCategory.Error })
        assert(FrontEnd.calls[FrontEnd.POST_MP4] == 1L)
    }
}

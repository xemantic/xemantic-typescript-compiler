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
import com.xemantic.typescript.compiler.CapturedType
import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.ProgramRecheck
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.RecheckHolder
import com.xemantic.typescript.compiler.TypeCaptureRequest
import com.xemantic.typescript.compiler.TypeCaptureSpan
import com.xemantic.typescript.compiler.Vfs
import com.xemantic.typescript.compiler.computeParserFlags
import kotlin.test.Test

/**
 * (INC.19) A REPLAYED QUERY MUST NOT LOSE A TYPE PARAMETER'S CONSTRAINT.
 *
 * `Type.TypeParam` is interned per `TypeParameter` node and its `constraint` field
 * is effectively WRITE-ONCE — whichever pass touches it first freezes it for the
 * checker's life. Three walkers in the constraints/defaults region built their
 * type-parameter scope and then resolved each constraint INSIDE the same loop, so
 * for `<T extends Nd, U extends T>` the sibling `T` was resolved against the OUTER
 * scope, answered `errorType`, and froze. A fresh build hid it: `checkSpine`
 * (dispatch row 28) walks the file first and writes the right answer, so the
 * write-once guard rejects the wrong one at `checkTypeArgumentConstraints`
 * (row 261). What removes that shield is a partition — a file the seed build never
 * walked has no spine write, so row 261 is the FIRST toucher and the freeze sticks.
 *
 * **The shape is a NAMESPACE-nested generic function, and that is not decoration.**
 * `init:buildFileLocalTypeMaps` (row 13) resolves every FILE-LEVEL `Function`
 * symbol of every file, partition or not, so a top-level generic is already frozen
 * correctly before either racer runs and pins written against one are VACUOUS.
 * A function inside a `namespace` is in the namespace's exports, not in the file's
 * `locals`, so it is the shape that reaches row 261 unprotected.
 *
 * **What discriminates.** On the pre-fix binary the replay renders
 * `<T extends Nd, U>(t: T, u: U) => U` where a fresh narrowed build renders
 * `<T extends Nd, U extends T>(t: T, u: U) => U` — one row of 55 captured spans,
 * and the DIAGNOSTIC channel is completely silent about it, which is why
 * `scripts/replay-differential.sh` grades captures at all.
 *
 * The oracle needs no baseline: a fresh narrowed build over the SAME file is the
 * reference, and the two arms must agree. [`the control`] exists because an
 * equality over two `null`s would pass a binary that captured nothing.
 */
class ProjectRecheckConstraintTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val seedFile = "/proj/src/a.ts"
    private val queriedFile = "/proj/src/b.ts"

    private val seedText = """
        export const seeded: number = 1;
    """.trimIndent() + "\n"

    /**
     * `topFn` is the CONTROL shape — a file-level generic, protected by
     * `init:buildFileLocalTypeMaps` and therefore agreeing on both arms even
     * before the fix. `NS.nsFn` is the same declaration one scope deeper, where
     * nothing resolves it ahead of the constraints walker.
     */
    private val queriedText = """
        export interface Nd { kind: number; }
        export function topFn<T extends Nd, U extends T>(t: T, u: U): U { return u; }
        export namespace NS {
            export function nsFn<T extends Nd, U extends T>(t: T, u: U): U { return u; }
        }
    """.trimIndent() + "\n"

    private fun vfs(): Vfs = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to config,
            seedFile to seedText,
            queriedFile to queriedText,
        ),
    )

    /** Every identifier of the queried file — `Project.semanticsOf`'s own population. */
    private fun request(): TypeCaptureRequest {
        val index = SourceIndex.of(
            queriedText,
            queriedFile,
            computeParserFlags(queriedFile, queriedText, CompilerOptions()),
        )
        return TypeCaptureRequest(
            index.identifiers().map { TypeCaptureSpan(queriedFile, it.pos, it.end) }.distinct(),
        )
    }

    private fun rows(captured: List<CapturedType>): Map<Int, String> =
        captured.filter { it.fileName == queriedFile }.associate { it.start to it.typeText }

    /** A fresh build narrowed to the queried file — the reference arm. */
    private fun fresh(request: TypeCaptureRequest): Map<Int, String> = rows(
        ProjectCompiler(vfs()).build(
            "/proj", noEmit = true, recheckOnly = setOf(queriedFile), typeCapture = request,
        ).capturedTypes,
    )

    /** A build narrowed to the SEED file, replayed onto the queried one. */
    private fun replayed(request: TypeCaptureRequest): Map<Int, String> {
        val holder = RecheckHolder()
        ProjectCompiler(vfs()).build(
            "/proj", noEmit = true, recheckOnly = setOf(seedFile), recheckHolder = holder,
        )
        val program: ProgramRecheck = requireNotNull(holder.recheck)
        return rows(program.recheck(setOf(queriedFile), request).capturedTypes)
    }

    private fun at(needle: String): Int = queriedText.indexOf(needle)

    @Test
    fun `the control - the reference arm really does render the constraint`() {
        // Without this every equality below could hold over two absent answers,
        // which is exactly how a differential passes while measuring nothing.
        val f = fresh(request())
        assert(f[at("nsFn")] == "<T extends Nd, U extends T>(t: T, u: U) => U")
        assert(f[at("topFn")] == "<T extends Nd, U extends T>(t: T, u: U) => U")
    }

    @Test
    fun `a replayed query keeps a namespace-nested generic's sibling constraint`() {
        val request = request()
        val span = at("nsFn")
        val expected = "<T extends Nd, U extends T>(t: T, u: U) => U"
        assert(fresh(request)[span] == expected)
        assert(replayed(request)[span] == expected)
    }

    @Test
    fun `the replay agrees with a fresh narrowed build on every captured span`() {
        val request = request()
        val f = fresh(request)
        val r = replayed(request)
        // Both directions of the key set, so a span the replay simply never
        // answered is a failure rather than an absence nobody compares.
        assert(f.keys == r.keys)
        val differing = f.keys.filter { f[it] != r[it] }.map { "@$it ${f[it]} != ${r[it]}" }
        assert(differing.isEmpty())
        assert(f.size > 20)
    }
}

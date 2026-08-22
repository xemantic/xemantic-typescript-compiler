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
import kotlin.test.assertFailsWith

/**
 * (INC.4) A PARTITION MAY NOT EMIT, AND [ProjectCompiler.build] NOW REFUSES THE
 * COMBINATION RATHER THAN PRODUCING WRONG JAVASCRIPT.
 *
 * `recheckOnly` narrows the CHECKER to a subset of the program (the INV.6 partition
 * view). The Transformer then queries the checker it is handed —
 * `isReferencedAliasDeclaration` and friends are what decide whether an import
 * binding is elided from the emitted JavaScript — so under a partition it would ask
 * a checker that never walked the files whose USES keep an import alive, and elide
 * it. The output is wrong JavaScript, silently: every diagnostic still agrees,
 * because the checker's answers about the files it DID walk are unchanged.
 *
 * Nothing in this repo does that today — every driver gates incremental work on
 * `--noEmit` and `Project` always passes `noEmit = true` — so this is a guard
 * against the NEXT caller, exactly as `TypeScriptCompiler.compileParsed`'s
 * `require(checkedSink == null || recheckOnly == null)` already is for a backend
 * sink. The message names the caller's mistake for the same reason.
 *
 * The two negative controls below are what make the guard a guard rather than a
 * blanket: a whole-program EMIT build is untouched, and a NARROWED check under
 * `noEmit` — the combination the whole (INC.1)/(INC.2) arc is built on — still
 * runs.
 */
class PartitionEmitRefusalTest {

    private fun vfs() = InMemoryVfs(
        mapOf(
            "/work/tsconfig.json" to """{"compilerOptions":{"module":"esnext","target":"es2020"}}""",
            "/work/main.ts" to
                "import { add } from \"./math.js\";\nexport const r: number = add(1, 2);\n",
            "/work/math.ts" to
                "export function add(a: number, b: number): number { return a + b; }\n",
        ),
    )

    @Test
    fun `an emitting build narrowed by recheckOnly is refused`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            ProjectCompiler(vfs()).build(
                "/work",
                noEmit = false,
                recheckOnly = setOf("/work/main.ts"),
            )
        }
        // The message has to name the MISTAKE, not the invariant: a caller reading
        // "recheckOnly walks a partition" learns nothing it can act on, and the two
        // ways out are what it needs to be told.
        val message = thrown.message ?: ""
        assert("recheckOnly" in message)
        assert("noEmit" in message)
    }

    @Test
    fun `the refusal fires before any work - the default noEmit is false`() {
        // `build`'s `noEmit` DEFAULTS to false, so a caller that passes only
        // `recheckOnly` — the natural mistake, since the parameter is public and
        // reads as an optimization — is refused too rather than silently emitting.
        assertFailsWith<IllegalArgumentException> {
            ProjectCompiler(vfs()).build("/work", recheckOnly = setOf("/work/main.ts"))
        }
    }

    @Test
    fun `negative control - a whole-program emit build is unaffected`() {
        val vfs = vfs()
        val result = ProjectCompiler(vfs).build("/work", noEmit = false, outDir = "/out")
        assert(result.diagnostics.isEmpty())
        // Emit actually happened: the guard must not have been widened into one that
        // refuses an ordinary build, which an assertion on diagnostics alone cannot
        // see (a refused build throws, but a build that silently stopped emitting
        // would still report none).
        assert(vfs.readText("/out/main.js") != null)
        assert(vfs.readText("/out/math.js") != null)
    }

    @Test
    fun `negative control - a narrowed check under noEmit still runs`() {
        val result = ProjectCompiler(vfs()).build(
            "/work",
            noEmit = true,
            recheckOnly = setOf("/work/main.ts"),
        )
        // The partition is the whole point of (INC.1): the program is still whole —
        // both files are crawled, parsed and bound — and only the CHECK is narrowed.
        assert("/work/main.ts" in result.programFiles)
        assert("/work/math.ts" in result.programFiles)
        assert(result.diagnostics.isEmpty())
    }
}

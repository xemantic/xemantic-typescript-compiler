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
 * A project path that does not exist must not turn into a crawl of the whole
 * filesystem (round 873).
 *
 * `xtsc /nonexistent-project` resolved its config path to that missing file,
 * took `dirname` of it — **`/`** — and, because an unreadable config fell back
 * to the default include (the recursive everything-glob), went looking for every
 * source file under the root. Measured at over half an hour of CPU before it was killed, having
 * emitted the TS5083 "cannot read file" diagnostic first and then gone walking
 * anyway. Under `--serve` the same request is worse than slow: the server runs
 * requests sequentially on one thread by design, so it wedges the daemon for
 * good and every later client blocks with no timeout to notice.
 *
 * The distinction the fix draws — and the reason for the second test — is
 * between a config that does not EXIST (nobody named that directory, so there is
 * nothing to include) and one that exists but does not PARSE (the directory is
 * real and tsc still compiles what it finds there).
 */
class MissingProjectPathTest {

    @Test
    fun `a config that does not exist includes nothing`() {
        val vfs = InMemoryVfs(mapOf("/somewhere/src/a.ts" to "export const a = 1\n"))
        val config = TsConfigLoader(vfs).load("/nonexistent-project")
        assert(config.include.isEmpty())
        // The diagnostic is what tells the user why, and it must still be there:
        // silence plus an empty program would look like a project with no files.
        assert(config.diagnostics.any { it.code == 5083 })
    }

    @Test
    fun `a config that exists but does not parse keeps the default include`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to "{ this is not json",
                "/proj/src/a.ts" to "export const a = 1\n",
            )
        )
        val config = TsConfigLoader(vfs).load("/proj/tsconfig.json")
        assert(config.include == listOf("**/*"))
        assert(config.diagnostics.any { it.code == 5014 })
    }

    @Test
    fun `a well-formed config is unaffected`() {
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to """{ "include": ["src"] }""",
                "/proj/src/a.ts" to "export const a = 1\n",
            )
        )
        val config = TsConfigLoader(vfs).load("/proj/tsconfig.json")
        assert(config.include == listOf("src"))
        assert(config.diagnostics.isEmpty())
    }
}

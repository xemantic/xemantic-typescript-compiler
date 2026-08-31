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
import com.xemantic.typescript.compiler.FrontEnd
import com.xemantic.typescript.compiler.ProjectCompiler
import kotlin.test.Test

/**
 * (INC.79) A BUILD ASKS THE FILESYSTEM ABOUT A FILE IT HAS ALREADY LISTED — or rather,
 * it used to.
 *
 * The root-file glob lists every directory of the project and proves which files are
 * there; the crawl then resolves every specifier of every file and asks the filesystem
 * the same question again. Measured on the 2,401-file `many-small-2400-dom` fixture,
 * that was **2,351 `exists` syscalls, ~4.4 ms of a ~120 ms per-keystroke query**. Seeded
 * with the glob's own answers, the same build makes **2,351 questions and 0 syscalls**.
 *
 * The pins are a COUNT because the row they decompose has a per-process spread of
 * several milliseconds, and they come in a PAIR: the zero is meaningless without the
 * control below it, where a resolution the seed does not cover must still probe AND
 * still answer.
 */
class ProjectResolveProbeSeedTest {

    private val options =
        """"compilerOptions": { "target": "es2020", "module": "esnext", "noEmit": true, "types": [] }"""

    /** Every import names a file the glob selects, so the seed covers all of them. */
    private fun seededVfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """{ $options, "include": ["src/**/*.ts"] }""",
            "/proj/src/a.ts" to "import { b } from \"./b\";\nexport const a = b;\n",
            "/proj/src/b.ts" to "export const b = 1;\n",
        ),
    )

    /**
     * The control: `vendor` is EXCLUDED from the root set, so the glob never lists its
     * file and the seed cannot name it — yet the import is real and the file joins the
     * program through the crawl. A seed read as an authoritative file list answers null
     * here, which is a wrong program with no diagnostic ((CFG.1)).
     */
    private fun unseededVfs() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to
                """{ $options, "include": ["src/**/*.ts"], "exclude": ["src/vendor"] }""",
            "/proj/src/a.ts" to "import { v } from \"./vendor/v\";\nexport const a = v;\n",
            "/proj/src/vendor/v.ts" to "export const v = 1;\n",
        ),
    )

    private fun buildAndCount(vfs: InMemoryVfs): Pair<List<String>, Long> {
        FrontEnd.resolveExistsProbes = 0
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        return result.programFiles.filter { it.startsWith("/proj/") }.sorted() to
            FrontEnd.resolveExistsProbes
    }

    @Test
    fun `a project whose imports name root files makes no resolution syscall`() {
        val (files, probes) = buildAndCount(seededVfs())
        assert(files == listOf("/proj/src/a.ts", "/proj/src/b.ts"))
        assert(probes == 0L)
    }

    /**
     * THE CONTROL, without which the zero above is indistinguishable from a dead
     * counter — and the VALUE half in the same fixture: the excluded file is not in the
     * seed, so the resolution must reach the filesystem, and it must still be found.
     */
    @Test
    fun `a resolution the seed cannot cover still probes and still resolves`() {
        val (files, probes) = buildAndCount(unseededVfs())
        assert("/proj/src/vendor/v.ts" in files)
        assert(probes > 0L)
    }
}

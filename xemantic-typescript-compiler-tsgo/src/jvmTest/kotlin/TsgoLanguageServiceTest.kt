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

package com.xemantic.typescript.tsgo

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (TSGO.4-a) the language-service facade ([TsgoLanguageService]) over an in-memory project: the project
 * system's snapshot speaks the position encoding the client negotiated, and the facade converts the
 * typed queries' UTF-16 offsets to and from it. The answers themselves are graded against `tsc --lsp`
 * by LsParityTest; these pins fix the plumbing. A non-ASCII character precedes the declaration on its
 * line, so a snapshot and a facade disagreeing on the encoding read a shifted definition range.
 */
class TsgoLanguageServiceTest {

    private val source = """
        const s = "héllo"; const n: number = 1
        const k = s + n
    """.trimIndent() + "\n"

    private fun open(initializeParams: String) = TsgoLanguageService.open(
        "/p/tsconfig.json",
        CheckerSmokeTest.MapFS(mapOf("/p/tsconfig.json" to """{"files": ["a.ts"], "compilerOptions": {"strict": true}}""", "/p/a.ts" to source)),
        initializeParams = initializeParams,
        configuration = """[{"suggest":{"autoImports":false}},null,null,null]""",
    )

    private val utf16 by lazy { open("""{"processId":null,"rootUri":null,"capabilities":{}}""") }
    private val utf8 by lazy { open("""{"processId":null,"rootUri":null,"capabilities":{"general":{"positionEncodings":["utf-8"]}}}""") }

    private val use = source.indexOf("s + n")
    private val decl = source.indexOf("n: number")

    @Test
    fun `a hover's range is the identifier's in either encoding`() {
        for (service in listOf(utf16, utf8)) {
            val hover = service.hover("/p/a.ts", use + "s + ".length)!!
            assert(hover.range == TsgoTextRange(use + 4, use + 5))
            assert("const n: number" in hover.contents)
        }
    }

    @Test
    fun `a definition lands on the declaration in either encoding`() {
        for (service in listOf(utf16, utf8)) {
            val defs = service.definition("/p/a.ts", use + "s + ".length)
            assert(defs == listOf(TsgoLocation("/p/a.ts", TsgoTextRange(decl, decl + 1))))
        }
    }

    @Test
    fun `a clean file has no diagnostics and the project no config errors`() {
        assert(utf8.configDiagnostics.isEmpty())
        assert(utf8.diagnostics("/p/a.ts").isEmpty())
    }
}

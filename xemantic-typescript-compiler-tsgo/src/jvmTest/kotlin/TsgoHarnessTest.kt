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
import com.xemantic.typescript.tsgo.ast.localize
import com.xemantic.typescript.tsgo.harness.caseConfigurations
import com.xemantic.typescript.tsgo.harness.decodeCaseText
import com.xemantic.typescript.tsgo.harness.runConfiguration
import com.xemantic.typescript.tsgo.harness.variationName
import com.xemantic.typescript.tsgo.runtime.GoString
import kotlin.test.Test

/**
 * (TSGO.2) the PORTED compiler test harness (`harness/Harness.kt` over the generated `testrunner` and
 * `harnessutil`), always on: a case in the conformance format goes through tsgo's own configuration
 * enumeration, `makeUnitsFromTest` and check-only compile. Every expectation is tsgo 7.0.2's own answer
 * for the same case text, taken with the diag oracle (`tsgo-oracle materialize` + `diags`,
 * docs/goport-diag-oracle.md) — `file start length code text` as its JSONL records them.
 */
class TsgoHarnessTest {

    private fun onDeepStack(body: () -> Unit) {
        var failure: Throwable? = null
        val th = Thread(null, { try { body() } catch (t: Throwable) { failure = t } }, "tsgo-deep-stack", 1L shl 30)
        th.start()
        th.join()
        failure?.let { throw it }
    }

    /** Each configuration's name with its diagnostics as `file start length TScode text`. */
    private fun run(fileName: String, raw: String): Map<String, List<String>> {
        TsgoPort.init()
        val content = decodeCaseText(raw)
        return caseConfigurations(content).associate { named ->
            val check = runConfiguration(fileName, content, named).check
            variationName(named) to (0 until check.diagnostics.len).map { i ->
                val d = check.diagnostics[i]!!
                "${d.file?.fileName()} ${d.loc.pos.value} ${d.loc.end.value - d.loc.pos.value} TS${d.code} " +
                    GoString.toUtf16(d.localize(com.xemantic.typescript.tsgo.locale.Locale()))
            }
        }
    }

    @Test
    fun `a varying directive enumerates one configuration per value and compiles each`() = onDeepStack {
        val result = run(
            "/cases/compiler/harnessPinVariations.ts",
            "// @strict: true, false\n// @filename: a.ts\nexport function f(p) { return p; }\nexport const x: number = \"s\";\n" +
                "// @filename: b.ts\nimport { x } from \"./a\";\nconst y: string = x;\n",
        )
        // A SET: Go enumerates configurations in map-iteration order (`splitOptionValues`' `maps.Values`),
        // i.e. tsgo's own order is a random draw; the port's (insertion order) is one of them.
        assert(result.keys == setOf("strict=true", "strict=false"))
        assert(result["strict=true"] == listOf(
            "/.src/a.ts 18 1 TS7006 Parameter 'p' implicitly has an 'any' type.",
            "/.src/a.ts 48 1 TS2322 Type 'string' is not assignable to type 'number'.",
            "/.src/b.ts 31 1 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
        assert(result["strict=false"] == listOf(
            "/.src/a.ts 48 1 TS2322 Type 'string' is not assignable to type 'number'.",
            "/.src/b.ts 31 1 TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    @Test
    fun `a case without directives is one null configuration named after its file`() = onDeepStack {
        val result = run("/cases/compiler/harnessPinPlain.ts", "let a = 1;\nlet b: string = a;\n")
        assert(result == mapOf("_" to listOf("/.src/harnessPinPlain.ts 15 1 TS2322 Type 'number' is not assignable to type 'string'.")))
    }

    @Test
    fun `a UTF-16 case is decoded before its directives are read`() = onDeepStack {
        // The porter's switch-by-value rule: vfs/internal.decodeBytes switches on a [2]byte BOM.
        val text = "// @target: es2015\nlet u: number = \"é\";\n"
        val raw = "ÿþ" + text.toByteArray(Charsets.UTF_16LE).toString(Charsets.ISO_8859_1)
        val result = run("/cases/compiler/harnessPinUtf16.ts", raw)
        assert(result == mapOf("_" to listOf("/.src/harnessPinUtf16.ts 4 1 TS2322 Type 'string' is not assignable to type 'number'.")))
    }
}

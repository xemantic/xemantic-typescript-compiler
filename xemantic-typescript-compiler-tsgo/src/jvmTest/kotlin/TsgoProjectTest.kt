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
 * (TSGO.3-b) the type-oracle facade ([TsgoProject]) over an in-memory project: each typed query reaches its
 * ported session handler. The answers themselves are graded against the tsgo binary by ApiParityTest; these
 * pins fix the facade's plumbing (positions, handles, string conversion, errors, panic recovery).
 */
class TsgoProjectTest {

    private val source = """
        interface Point { x: number; y: number }
        function len(p: Point): number { return Math.hypot(p.x, p.y) }
        export const home: Point = { x: 0, y: 0 }
        const d = len(home)
        const s = "héllo"
    """.trimIndent() + "\n"

    private val project by lazy {
        TsgoProject.open(
            "/p/tsconfig.json",
            CheckerSmokeTest.MapFS(mapOf("/p/tsconfig.json" to """{"files": ["a.ts"], "compilerOptions": {"strict": true}}""", "/p/a.ts" to source)),
        )
    }

    private fun at(text: String, delta: Int = 0) = source.indexOf(text) + delta

    @Test
    fun `a project opens with its files and no config errors`() {
        assert(project.configDiagnostics.isEmpty())
        assert("/p/a.ts" in project.sourceFileNames())
        assert(project.semanticDiagnostics("/p/a.ts").isEmpty())
    }

    @Test
    fun `the type at a position renders as tsgo renders it`() {
        val d = project.typeAtPosition("/p/a.ts", at("d = len"))!!
        assert(project.typeToString(d) == "number")
        val s = project.typeAtPosition("/p/a.ts", at("s = "))!!
        assert(project.typeToString(s) == "\"héllo\"")
        assert(s.literalValue == "héllo")
    }

    @Test
    fun `properties symbols and their types`() {
        val origin = project.typeAtPosition("/p/a.ts", at("home:"))!!
        val props = project.propertiesOfType(origin)
        assert(props.map { it.name } == listOf("x", "y"))
        assert(project.typeToString(project.typeOfSymbol(props[0])!!) == "number")
        val sym = project.symbolAtPosition("/p/a.ts", at("Point {", 1))!!
        assert(sym.name == "Point")
        assert(project.typeToString(project.declaredTypeOfSymbol(sym)!!) == "Point")
    }

    @Test
    fun `a call resolves its signature and its argument is contextually typed`() {
        val call = project.nodeAt("/p/a.ts", at("len(home)"))!!
        assert(call.fileName == "/p/a.ts")
        val fnType = project.typeAtLocation(call)!!
        val sig = project.signaturesOfType(fnType).single()
        assert(project.parametersOfSignature(sig).map { it.name } == listOf("p"))
        assert(project.typeToString(project.returnTypeOfSignature(sig)!!) == "number")
        val arg = project.nodeAt("/p/a.ts", at("home)"))!!
        val ctx = project.contextualType(arg)!!
        assert(project.typeToString(ctx) == "Point")
        assert(project.isTypeAssignableTo(project.typeAtLocation(arg)!!, ctx))
        assert(!project.isTypeAssignableTo(project.typeAtPosition("/p/a.ts", at("s = "))!!, ctx))
    }

    @Test
    fun `the raw protocol answers any proto method`() {
        val json = project.request("getTypeAtPosition", """{"snapshot":${project.snapshotId},"project":"/p/tsconfig.json","file":"/p/a.ts","position":${at("d = len")}}""")
        assert(json.startsWith("{\"id\":") && "\"intrinsicName\":\"number\"" in json)
    }

    @Test
    fun `a refused query throws the handler's error`() {
        val e = runCatching { project.typeAtPosition("/p/missing.ts", 0) }.exceptionOrNull()
        assert(e is TsgoApiException && "source file not found" in e.message!! && !e.panicked)
    }
}

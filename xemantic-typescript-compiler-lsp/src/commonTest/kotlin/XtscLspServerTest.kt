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

package com.xemantic.typescript.compiler.lsp

import com.xemantic.kotlin.test.assert
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test

/**
 * (TSGO.4-a) the server loop over in-memory [Buffer]s, answered by tsgo's ported language service on an
 * in-memory project ([OverlayFS] with no disk under it). The ANSWERS are tsgo's (graded against the tsgo
 * binary's own language server by `-tsgo`'s LsParityTest); these pins fix the server's end: framing,
 * routing a request to the language service, the client's buffers, the project lookup, the push of
 * diagnostics and the exit codes. The expected texts were read from `tsc --lsp` 7.0.2 on the same fixture.
 */
class XtscLspServerTest {

    private val config = """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true }, "include": ["src/**/*.ts"] }"""

    private val fileUri = "file:///proj/src/a.ts"

    /** Line 0 carries an astral-plane character BEFORE `abc`, so its LSP character is UTF-16 units. */
    private val line0 = "const s = \"𝕏\"; const abc = 1;"
    private val line1 = "const other = abc + 1;"
    private val sourceText = line0 + "\n" + line1 + "\n"

    private fun disk(source: String = sourceText): OverlayFS = OverlayFS().apply {
        put("/proj/tsconfig.json", config)
        put("/proj/src/a.ts", source)
    }

    private val capabilities = buildJsonObject {
        put(
            "textDocument",
            buildJsonObject {
                put("hover", buildJsonObject { put("contentFormat", buildJsonArray { add(JsonPrimitive("plaintext")) }) })
            },
        )
    }

    // --- protocol builders ------------------------------------------------------

    private fun request(id: Int, method: String, params: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    private fun notification(method: String, params: JsonObject? = null): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    private fun initialize(id: Int = 1) = request(id, "initialize", buildJsonObject {
        put("processId", JsonNull)
        put("rootUri", "file:///proj")
        put("capabilities", capabilities)
    })

    private fun didOpen(text: String, uri: String = fileUri) = notification("textDocument/didOpen", buildJsonObject {
        put("textDocument", buildJsonObject {
            put("uri", uri)
            put("languageId", "typescript")
            put("version", 1)
            put("text", text)
        })
    })

    private fun didChange(text: String) = notification("textDocument/didChange", buildJsonObject {
        put("textDocument", buildJsonObject { put("uri", fileUri); put("version", 2) })
        put("contentChanges", buildJsonArray { add(buildJsonObject { put("text", text) }) })
    })

    private fun at(id: Int, method: String, line: Int, character: Int, uri: String = fileUri, extra: JsonObject? = null) =
        request(id, method, buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri) })
            put("position", buildJsonObject { put("line", line); put("character", character) })
            extra?.forEach { (k, v) -> put(k, v) }
        })

    private class Session(val responses: Map<Int, JsonObject>, val notifications: List<JsonObject>, val exitCode: Int)

    private fun session(server: XtscLanguageServer, vararg messages: String): Session {
        val input = Buffer()
        for (m in messages) writeFrame(input, m)
        val output = Buffer()
        val code = server.serve(input, output)
        val byId = HashMap<Int, JsonObject>()
        val notes = ArrayList<JsonObject>()
        while (true) {
            val text = readFrame(output as Source) ?: break
            val obj = Json.parseToJsonElement(text).jsonObject
            val id = obj.int("id")
            if (id != null) byId[id] = obj else notes += obj
        }
        return Session(byId, notes, code)
    }

    private fun JsonObject.result(): JsonObject = this["result"]!!.jsonObject

    private fun shutdownExit(id: Int = 99) = arrayOf(request(id, "shutdown"), notification("exit"))

    // --- the session ------------------------------------------------------------

    @Test
    fun `hover is tsgo's quick info, at an LSP position counted in UTF-16 units`() {
        val col = line0.indexOf("abc")
        val s = session(XtscLanguageServer(disk()), initialize(), didOpen(sourceText), at(2, "textDocument/hover", 0, col), *shutdownExit())
        val contents = s.responses[2]!!.result()["contents"]!!.jsonObject
        assert(contents["kind"]!!.jsonPrimitive.content == "plaintext")
        assert(contents["value"]!!.jsonPrimitive.content == "const abc: 1")
        val range = s.responses[2]!!.result()["range"]!!.jsonObject
        assert(range["start"]!!.jsonObject.int("character") == col)
        assert(s.exitCode == 0)
    }

    @Test
    fun `definition and references follow the symbol across lines`() {
        val use = line1.indexOf("abc")
        val s = session(
            XtscLanguageServer(disk()), initialize(), didOpen(sourceText),
            at(2, "textDocument/definition", 1, use),
            at(3, "textDocument/references", 1, use, extra = buildJsonObject { put("context", buildJsonObject { put("includeDeclaration", true) }) }),
            *shutdownExit(),
        )
        val def = s.responses[2]!!["result"]!!.jsonArray
        assert(def.size == 1)
        assert(def[0].jsonObject["range"]!!.jsonObject["start"]!!.jsonObject.int("line") == 0)
        val refs = s.responses[3]!!["result"]!!.jsonArray
        assert(refs.map { it.jsonObject["range"]!!.jsonObject["start"]!!.jsonObject.int("line") } == listOf(0, 1))
    }

    @Test
    fun `completion lists the file's declarations`() {
        val s = session(XtscLanguageServer(disk()), initialize(), didOpen(sourceText), at(2, "textDocument/completion", 1, line1.indexOf("abc")), *shutdownExit())
        val result = s.responses[2]!!.result()
        val labels = result["items"]!!.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content }
        assert("abc" in labels && "s" in labels && "Math" in labels)
        // `other` is being declared at the caret: tsgo does not offer it in its own initializer.
        assert("other" !in labels)
    }

    @Test
    fun `an error is pushed for the open document and cleared when the buffer is fixed`() {
        val bad = "const x: number = \"s\";\n"
        val s = session(XtscLanguageServer(disk()), initialize(), didOpen(bad), didChange("const x: number = 1;\n"), *shutdownExit())
        val pushes = s.notifications.filter { it.string("method") == "textDocument/publishDiagnostics" }
        assert(pushes.size == 2)
        val first = pushes[0]["params"]!!.jsonObject["diagnostics"]!!.jsonArray
        assert(first.size == 1)
        val d = first[0].jsonObject
        assert(d["code"]!!.jsonPrimitive.content == "2322")
        assert(d["message"]!!.jsonPrimitive.content == "Type 'string' is not assignable to type 'number'.")
        assert(pushes[1]["params"]!!.jsonObject["diagnostics"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `pull diagnostics answer the full report of the buffer`() {
        val bad = "const x: number = \"s\";\n"
        val s = session(
            XtscLanguageServer(disk()), initialize(), didOpen(bad),
            request(2, "textDocument/diagnostic", buildJsonObject { put("textDocument", buildJsonObject { put("uri", fileUri) }) }),
            *shutdownExit(),
        )
        val report = s.responses[2]!!.result()
        assert(report["kind"]!!.jsonPrimitive.content == "full")
        assert((report["items"] as JsonArray).size == 1)
    }

    @Test
    fun `a file under no tsconfig answers null and an empty report`() {
        val fs = OverlayFS().apply { put("/loose/b.ts", "const q = 1;\n") }
        val s = session(
            XtscLanguageServer(fs), initialize(),
            at(2, "textDocument/hover", 0, 6, uri = "file:///loose/b.ts"),
            request(3, "textDocument/diagnostic", buildJsonObject { put("textDocument", buildJsonObject { put("uri", "file:///loose/b.ts") }) }),
            *shutdownExit(),
        )
        assert(s.responses[2]!!["result"] is JsonNull)
        assert((s.responses[3]!!.result()["items"] as JsonArray).isEmpty())
    }

    @Test
    fun `exit after shutdown returns zero`() {
        assert(session(XtscLanguageServer(OverlayFS()), *shutdownExit()).exitCode == 0)
    }

    @Test
    fun `exit without shutdown returns one`() {
        assert(session(XtscLanguageServer(OverlayFS()), notification("exit")).exitCode == 1)
    }

    @Test
    fun `end of input without shutdown returns one`() {
        assert(session(XtscLanguageServer(OverlayFS()), initialize()).exitCode == 1)
    }

    @Test
    fun `messages after exit are not served`() {
        val s = session(XtscLanguageServer(OverlayFS()), notification("exit"), request(5, "shutdown"))
        assert(s.responses.isEmpty())
    }
}

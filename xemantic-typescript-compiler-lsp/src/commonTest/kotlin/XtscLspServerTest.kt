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
import com.xemantic.typescript.compiler.project.Project
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

/**
 * The whole server loop driven over in-memory [Buffer]s with a real [Project]
 * on an [InMemoryVfs] — byte-identical to a production stdio session.
 *
 * Every expected hover VALUE comes from a parallel [Project] over the same
 * fixture asked directly — self-consistency, never a hand-written type string —
 * so these pins survive display changes in the compiler and redden only when
 * the SERVER layer diverges from the API it wraps.
 */
class XtscLspServerTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val file = "/proj/src/a.ts"
    private val fileUri = "file:///proj/src/a.ts"

    /**
     * Line 0 carries an astral-plane character BEFORE the hover target, so an
     * LSP `character` for `abc` differs between UTF-16 units (what the protocol
     * and this API both count) and code points — the UTF-16 pin lives on it.
     */
    private val line0 = "const s = \"\uD835\uDD4F\"; const abc = 1;"
    private val line1 = "const other = abc + 1;"
    private val sourceText = line0 + "\n" + line1 + "\n"

    private fun fixture(): Map<String, String> =
        mapOf("/proj/tsconfig.json" to config, file to sourceText)

    private fun directProject(): Project = Project.open("/proj", InMemoryVfs(fixture()))

    // --- protocol builders ------------------------------------------------------

    private fun request(id: Int, method: String, params: JsonObject? = null): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    private fun notification(method: String, params: JsonObject? = null): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    private fun initializeParams(rootUri: String): JsonObject =
        buildJsonObject { put("rootUri", rootUri) }

    private fun didOpenParams(uri: String, text: String): JsonObject = buildJsonObject {
        put(
            "textDocument",
            buildJsonObject {
                put("uri", uri)
                put("languageId", "typescript")
                put("version", 1)
                put("text", text)
            },
        )
    }

    private fun hoverParams(uri: String, line: Int, character: Int): JsonObject =
        buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri) })
            put(
                "position",
                buildJsonObject {
                    put("line", line)
                    put("character", character)
                },
            )
        }

    /** All responses on [source], keyed by their integer id. */
    private fun readResponses(source: Source): Map<Int, JsonObject> {
        val byId = HashMap<Int, JsonObject>()
        while (true) {
            val text = readFrame(source) ?: return byId
            val obj = Json.parseToJsonElement(text).jsonObject
            val id = obj.int("id") ?: continue
            byId[id] = obj
        }
    }

    // --- the full session -------------------------------------------------------

    @Test
    fun `a full session over buffers hovers with the checker's own answer`() {
        val hoverChar = line0.indexOf("abc")
        val input = Buffer()
        writeFrame(input, request(1, "initialize", initializeParams("file:///proj")))
        writeFrame(input, notification("initialized"))
        writeFrame(input, notification("textDocument/didOpen", didOpenParams(fileUri, sourceText)))
        writeFrame(input, request(2, "textDocument/hover", hoverParams(fileUri, 0, hoverChar)))
        writeFrame(input, request(3, "shutdown"))
        writeFrame(input, notification("exit"))
        val output = Buffer()

        val exitCode = XtscLanguageServer(InMemoryVfs(fixture())).serve(input, output)
        assert(exitCode == 0)
        val responses = readResponses(output)

        // initialize: the announced surface.
        val capabilities = responses[1]?.obj("result")?.obj("capabilities")
        val hoverProvider = (capabilities?.get("hoverProvider") as? JsonPrimitive)?.booleanOrNull
        val syncKind = capabilities?.int("textDocumentSync")
        val serverName = responses[1]?.obj("result")?.obj("serverInfo")?.string("name")
        assert(hoverProvider == true)
        assert(syncKind == 1)
        assert(serverName == "xtsc-lsp")

        // hover: the value the embedding API answers for the same caret.
        val expected = directProject().quickInfoAt(file, sourceText.indexOf("abc"))
        assert(expected != null)
        val result = responses[2]?.obj("result")
        val kind = result?.obj("contents")?.string("kind")
        val value = result?.obj("contents")?.string("value")
        assert(kind == "plaintext")
        assert(value == expected.displayString)

        // the range round-trips to the QuickInfo span through the same API.
        // (`result` smart-casts to non-null here: the asserts above compared a
        // value reached through it against non-null expectations.)
        val start = result.obj("range")?.obj("start")
        val end = result.obj("range")?.obj("end")
        val startOffset = directProject().offsetAt(
            file,
            (start?.int("line") ?: -1) + 1,
            (start?.int("character") ?: -1) + 1,
        )
        val endOffset = directProject().offsetAt(
            file,
            (end?.int("line") ?: -1) + 1,
            (end?.int("character") ?: -1) + 1,
        )
        assert(startOffset == expected.start)
        assert(endOffset == expected.end)

        // shutdown: result null, not an error.
        val shutdownResultIsNull = responses[3]?.get("result") is JsonNull
        assert(shutdownResultIsNull)
    }

    @Test
    fun `LSP characters are UTF-16 code units - the astral pin`() {
        // Kotlin string indices ARE UTF-16 code units, so `indexOf` computes the
        // LSP column directly; the astral char before `abc` makes that column
        // differ from the code-point column by one. If either conversion counted
        // anything but UTF-16 units the served range could not equal these.
        val hoverChar = line0.indexOf("abc")
        val input = Buffer()
        writeFrame(input, request(1, "initialize", initializeParams("file:///proj")))
        writeFrame(input, request(2, "textDocument/hover", hoverParams(fileUri, 0, hoverChar)))
        val output = Buffer()
        XtscLanguageServer(InMemoryVfs(fixture())).serve(input, output)

        val result = readResponses(output)[2]?.obj("result")
        val startLine = result?.obj("range")?.obj("start")?.int("line")
        val startChar = result?.obj("range")?.obj("start")?.int("character")
        val endChar = result?.obj("range")?.obj("end")?.int("character")
        assert(startLine == 0)
        assert(startChar == hoverChar)
        assert(endChar == hoverChar + 3)
    }

    @Test
    fun `the touch rule answers one to the left of a caret just past an identifier`() {
        // The identifier is the last CONTENT of its line, so the caret one past
        // it sits on the line terminator — inside no node's real span — and only
        // the § 12 fallback at offset minus one can answer. (The file must end
        // with that newline: see the file-final-identifier pin below for what
        // happens without one.)
        val text = "const abc = 1;\nconst tail = abc\n"
        val vfsFiles = mapOf("/proj/tsconfig.json" to config, file to text)
        val lastLine = "const tail = abc"
        val input = Buffer()
        writeFrame(input, request(1, "initialize", initializeParams("file:///proj")))
        writeFrame(input, notification("textDocument/didOpen", didOpenParams(fileUri, text)))
        writeFrame(input, request(2, "textDocument/hover", hoverParams(fileUri, 1, lastLine.length)))
        val output = Buffer()
        XtscLanguageServer(InMemoryVfs(vfsFiles)).serve(input, output)

        val direct = Project.open("/proj", InMemoryVfs(vfsFiles))
        val caret = text.length - 1
        val primary = direct.quickInfoAt(file, caret)
        val expected = direct.quickInfoAt(file, caret - 1)
        assert(primary == null)
        assert(expected != null)
        val value = readResponses(output)[2]?.obj("result")?.obj("contents")?.string("value")
        assert(value == expected.displayString)
    }

    @Test
    fun `a file-final identifier with no trailing newline hovers as null - a recorded -project edge`() {
        // NOT a guarantee — a defect record, in `LanguageServiceStateTest`'s own
        // idiom, so that a fix upstream is a deliberate inversion here rather
        // than an accident nobody notices. `SourceIndex.realEndOf` snaps a
        // node's raw `end` back to "the greatest token end STRICTLY below it";
        // the last token of a file with no trailing trivia has an EXACT raw end
        // (the EOF lookahead is zero-width), so the snap lands on the token
        // BEFORE it and clamps to `pos` — an empty span no position lookup can
        // enter. `quickInfoAt` therefore answers null ANYWHERE inside such an
        // identifier, and the touch fallback cannot save the caret one past it
        // either. A trailing newline widens the raw end past the token and
        // restores every answer, which is what the touch-rule test above uses.
        val text = "const abc = 1;\nconst tail = abc"
        val vfsFiles = mapOf("/proj/tsconfig.json" to config, file to text)
        val direct = Project.open("/proj", InMemoryVfs(vfsFiles))
        val insideFinal = direct.quickInfoAt(file, text.length - 2)
        assert(insideFinal == null)

        val input = Buffer()
        writeFrame(input, request(1, "initialize", initializeParams("file:///proj")))
        writeFrame(
            input,
            request(2, "textDocument/hover", hoverParams(fileUri, 1, "const tail = a".length)),
        )
        val output = Buffer()
        XtscLanguageServer(InMemoryVfs(vfsFiles)).serve(input, output)
        val resultIsNull = readResponses(output)[2]?.get("result") is JsonNull
        assert(resultIsNull)
    }

    @Test
    fun `hover on an unknown file answers a null result`() {
        val input = Buffer()
        writeFrame(input, request(1, "initialize", initializeParams("file:///proj")))
        writeFrame(
            input,
            request(2, "textDocument/hover", hoverParams("file:///proj/src/none.ts", 0, 0)),
        )
        val output = Buffer()
        XtscLanguageServer(InMemoryVfs(fixture())).serve(input, output)
        val response = readResponses(output)[2]
        val resultIsNull = response?.get("result") is JsonNull
        val hasError = response?.obj("error") != null
        assert(resultIsNull)
        assert(!hasError)
    }

    @Test
    fun `hover on a line the file does not have answers a null result`() {
        // A client one keystroke ahead of the server names a line we do not
        // hold; `offsetAt` throws for it and the server answers "nothing".
        val input = Buffer()
        writeFrame(input, request(1, "initialize", initializeParams("file:///proj")))
        writeFrame(input, request(2, "textDocument/hover", hoverParams(fileUri, 99, 0)))
        val output = Buffer()
        XtscLanguageServer(InMemoryVfs(fixture())).serve(input, output)
        val resultIsNull = readResponses(output)[2]?.get("result") is JsonNull
        assert(resultIsNull)
    }

    @Test
    fun `didOpen text overrides what is on disk`() {
        val diskText = "const abc = 1;\n"
        val bufferText = "const abc = \"x\";\n"
        val vfsFiles = mapOf("/proj/tsconfig.json" to config, file to diskText)

        // The control that this pin can discriminate: the two texts hover
        // differently through the API itself.
        val withBuffer = Project.open("/proj", InMemoryVfs(vfsFiles)).let {
            it.updateFile(file, bufferText)
            it.quickInfoAt(file, bufferText.indexOf("abc"))
        }
        val withDisk = Project.open("/proj", InMemoryVfs(vfsFiles))
            .quickInfoAt(file, diskText.indexOf("abc"))
        assert(withBuffer != null)
        assert(withDisk != null)
        assert(withBuffer.displayString != withDisk.displayString)

        val input = Buffer()
        writeFrame(input, request(1, "initialize", initializeParams("file:///proj")))
        writeFrame(input, notification("textDocument/didOpen", didOpenParams(fileUri, bufferText)))
        writeFrame(
            input,
            request(2, "textDocument/hover", hoverParams(fileUri, 0, bufferText.indexOf("abc"))),
        )
        val output = Buffer()
        XtscLanguageServer(InMemoryVfs(vfsFiles)).serve(input, output)
        val value = readResponses(output)[2]?.obj("result")?.obj("contents")?.string("value")
        assert(value == withBuffer.displayString)
    }

    @Test
    fun `initialize falls back to rootPath when rootUri is absent`() {
        val input = Buffer()
        writeFrame(input, request(1, "initialize", buildJsonObject { put("rootPath", "/proj") }))
        writeFrame(input, request(2, "textDocument/hover", hoverParams(fileUri, 1, line1.indexOf("other"))))
        val output = Buffer()
        XtscLanguageServer(InMemoryVfs(fixture())).serve(input, output)

        val expected = directProject().quickInfoAt(file, sourceText.indexOf("other"))
        assert(expected != null)
        val value = readResponses(output)[2]?.obj("result")?.obj("contents")?.string("value")
        assert(value == expected.displayString)
    }

    @Test
    fun `a percent-encoded root URI opens the project it names`() {
        val spacedFile = "/my proj/src/a.ts"
        val text = "const abc = 1;\n"
        val vfsFiles = mapOf("/my proj/tsconfig.json" to config, spacedFile to text)
        val input = Buffer()
        writeFrame(input, request(1, "initialize", initializeParams("file:///my%20proj")))
        writeFrame(
            input,
            request(
                2,
                "textDocument/hover",
                hoverParams("file:///my%20proj/src/a.ts", 0, text.indexOf("abc")),
            ),
        )
        val output = Buffer()
        XtscLanguageServer(InMemoryVfs(vfsFiles)).serve(input, output)

        val expected = Project.open("/my proj", InMemoryVfs(vfsFiles))
            .quickInfoAt(spacedFile, text.indexOf("abc"))
        assert(expected != null)
        val value = readResponses(output)[2]?.obj("result")?.obj("contents")?.string("value")
        assert(value == expected.displayString)
    }

    @Test
    fun `initialize on a nonexistent root still succeeds and hover answers null`() {
        val input = Buffer()
        writeFrame(input, request(1, "initialize", initializeParams("file:///nowhere")))
        writeFrame(input, request(2, "textDocument/hover", hoverParams("file:///nowhere/a.ts", 0, 0)))
        val output = Buffer()
        XtscLanguageServer(InMemoryVfs(emptyMap())).serve(input, output)
        val responses = readResponses(output)
        val initialized = responses[1]?.obj("result")?.obj("capabilities") != null
        val hoverIsNull = responses[2]?.get("result") is JsonNull
        assert(initialized)
        assert(hoverIsNull)
    }

    // --- exit codes -------------------------------------------------------------

    @Test
    fun `exit after shutdown returns zero`() {
        val input = Buffer()
        writeFrame(input, request(1, "shutdown"))
        writeFrame(input, notification("exit"))
        val code = XtscLanguageServer(InMemoryVfs(emptyMap())).serve(input, Buffer())
        assert(code == 0)
    }

    @Test
    fun `exit without shutdown returns one`() {
        val input = Buffer()
        writeFrame(input, notification("exit"))
        val code = XtscLanguageServer(InMemoryVfs(emptyMap())).serve(input, Buffer())
        assert(code == 1)
    }

    @Test
    fun `end of input without shutdown returns one`() {
        val input = Buffer()
        writeFrame(input, request(1, "initialize", initializeParams("file:///proj")))
        val code = XtscLanguageServer(InMemoryVfs(fixture())).serve(input, Buffer())
        assert(code == 1)
    }

    @Test
    fun `end of input after shutdown returns zero`() {
        val input = Buffer()
        writeFrame(input, request(1, "shutdown"))
        val code = XtscLanguageServer(InMemoryVfs(emptyMap())).serve(input, Buffer())
        assert(code == 0)
    }

    @Test
    fun `messages after exit are not served`() {
        val input = Buffer()
        writeFrame(input, request(1, "shutdown"))
        writeFrame(input, notification("exit"))
        writeFrame(input, request(2, "shutdown"))
        val output = Buffer()
        XtscLanguageServer(InMemoryVfs(emptyMap())).serve(input, output)
        val responses = readResponses(output)
        val second = responses[2]
        assert(second == null)
    }
}

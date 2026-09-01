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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

/**
 * (LSP.2) pins: the document lifecycle, navigation, completion, signature help,
 * rename and diagnostics — every semantic VALUE asserted by self-consistency
 * against a parallel [Project] over the same fixture (never a hand-written
 * type string), and every refusal asserted as the protocol shape the client
 * would see.
 */
class XtscLspFeaturesTest {

    private val config =
        """{ "compilerOptions": { "target": "es2020", "module": "esnext", "strict": true },""" +
            """ "include": ["src/**/*.ts"] }"""

    private val libFile = "/proj/src/lib.ts"
    private val libUri = "file:///proj/src/lib.ts"
    private val libText =
        "export function greet(who: string, count: number): string { return who; }\n" +
            "export const answer = 41;\n"

    private val mainFile = "/proj/src/main.ts"
    private val mainUri = "file:///proj/src/main.ts"
    private val mainText =
        "import { greet, answer } from \"./lib\";\n" +
            "let total = answer;\n" +
            "total = total + 1;\n" +
            "const msg = greet(\"hi\", total);\n" +
            "const piece = msg.charAt(0);\n" +
            "const bad: string = answer;\n"

    private fun fixture(): Map<String, String> = mapOf(
        "/proj/tsconfig.json" to config,
        libFile to libText,
        mainFile to mainText,
    )

    private fun directProject(): Project = Project.open("/proj", InMemoryVfs(fixture()))

    // --- protocol builders (the XtscLspServerTest idiom) ------------------------

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

    private fun initialize(): String =
        request(1, "initialize", buildJsonObject { put("rootUri", "file:///proj") })

    private fun didOpen(uri: String, text: String): String = notification(
        "textDocument/didOpen",
        buildJsonObject {
            put(
                "textDocument",
                buildJsonObject {
                    put("uri", uri)
                    put("languageId", "typescript")
                    put("version", 1)
                    put("text", text)
                },
            )
        },
    )

    private fun didChangeFull(uri: String, text: String): String = notification(
        "textDocument/didChange",
        buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri); put("version", 2) })
            put(
                "contentChanges",
                buildJsonArray { add(buildJsonObject { put("text", text) }) },
            )
        },
    )

    private fun positioned(
        id: Int,
        method: String,
        uri: String,
        line: Int,
        character: Int,
        extra: JsonObject? = null,
    ): String = request(
        id,
        method,
        buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri) })
            put(
                "position",
                buildJsonObject { put("line", line); put("character", character) },
            )
            if (extra != null) for ((k, v) in extra) put(k, v)
        },
    )

    /** The (line0, char0) of [needle]'s [occurrence]-th appearance in [text]. */
    private fun at(text: String, needle: String, occurrence: Int = 0): Pair<Int, Int> {
        var from = 0
        var index = -1
        repeat(occurrence + 1) {
            index = text.indexOf(needle, from)
            from = index + 1
        }
        val line = text.substring(0, index).count { it == '\n' }
        val lineStart = text.lastIndexOf('\n', index - 1) + 1
        return line to (index - lineStart)
    }

    private fun offsetOf(text: String, needle: String, occurrence: Int = 0): Int {
        var from = 0
        var index = -1
        repeat(occurrence + 1) {
            index = text.indexOf(needle, from)
            from = index + 1
        }
        return index
    }

    private class Session(vfs: InMemoryVfs, messages: List<String>) {
        val responses: Map<Int, JsonObject>
        val notifications: List<JsonObject>
        val exitCode: Int

        init {
            val input = Buffer()
            for (m in messages) writeFrame(input, m)
            val output = Buffer()
            exitCode = XtscLanguageServer(vfs).serve(input, output)
            val byId = HashMap<Int, JsonObject>()
            val notes = ArrayList<JsonObject>()
            readAll(output, byId, notes)
            responses = byId
            notifications = notes
        }

        private fun readAll(
            source: Source,
            byId: MutableMap<Int, JsonObject>,
            notes: MutableList<JsonObject>,
        ) {
            while (true) {
                val text = readFrame(source) ?: return
                val obj = Json.parseToJsonElement(text).jsonObject
                val id = obj.int("id")
                if (id != null) byId[id] = obj else notes.add(obj)
            }
        }

        /** publishDiagnostics params for [uri], in arrival order. */
        fun published(uri: String): List<JsonObject> = notifications
            .filter { it.string("method") == "textDocument/publishDiagnostics" }
            .mapNotNull { it.obj("params") }
            .filter { it.string("uri") == uri }
    }

    private fun session(vararg tail: String, vfs: InMemoryVfs = InMemoryVfs(fixture())): Session =
        Session(
            vfs,
            listOf(initialize(), notification("initialized")) + tail +
                listOf(request(99, "shutdown"), notification("exit")),
        )

    private fun items(response: JsonObject?): JsonArray? =
        response?.obj("result")?.get("items") as? JsonArray

    private fun rangeStartOffset(project: Project, path: String, range: JsonObject?): Int? {
        val start = range?.obj("start") ?: return null
        return project.offsetAt(
            path,
            (start.int("line") ?: return null) + 1,
            (start.int("character") ?: return null) + 1,
        )
    }

    private fun rangeEndOffset(project: Project, path: String, range: JsonObject?): Int? {
        val end = range?.obj("end") ?: return null
        return project.offsetAt(
            path,
            (end.int("line") ?: return null) + 1,
            (end.int("character") ?: return null) + 1,
        )
    }

    // --- lifecycle ----------------------------------------------------------------

    @Test
    fun `didChange replaces the whole buffer and hover follows it`() {
        val edited = mainText.replace("let total = answer;", "let total = \"s\";")
        val caret = at(mainText, "total")
        val s = session(
            didOpen(mainUri, mainText),
            positioned(2, "textDocument/hover", mainUri, caret.first, caret.second),
            didChangeFull(mainUri, edited),
            positioned(3, "textDocument/hover", mainUri, caret.first, caret.second),
        )
        val before = s.responses[2]?.obj("result")?.obj("contents")?.string("value")
        val after = s.responses[3]?.obj("result")?.obj("contents")?.string("value")
        val direct = directProject()
        val expectedBefore = direct.quickInfoAt(mainFile, offsetOf(mainText, "total"))
        assert(expectedBefore != null)
        assert(before == expectedBefore.displayString)
        direct.updateFile(mainFile, edited)
        val expectedAfter = direct.quickInfoAt(mainFile, offsetOf(edited, "total"))
        assert(expectedAfter != null)
        assert(after == expectedAfter.displayString)
        assert(before != after)
    }

    @Test
    fun `a ranged contentChange under full sync is skipped`() {
        val caret = at(mainText, "total")
        val ranged = notification(
            "textDocument/didChange",
            buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", mainUri); put("version", 2) })
                put(
                    "contentChanges",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "range",
                                    buildJsonObject {
                                        put("start", buildJsonObject { put("line", 0); put("character", 0) })
                                        put("end", buildJsonObject { put("line", 0); put("character", 1) })
                                    },
                                )
                                put("text", "zzz")
                            },
                        )
                    },
                )
            },
        )
        val s = session(
            didOpen(mainUri, mainText),
            ranged,
            positioned(2, "textDocument/hover", mainUri, caret.first, caret.second),
        )
        val value = s.responses[2]?.obj("result")?.obj("contents")?.string("value")
        val expected = directProject().quickInfoAt(mainFile, offsetOf(mainText, "total"))
        assert(expected != null)
        assert(value == expected.displayString)
    }

    @Test
    fun `didClose makes the disk the truth again`() {
        val edited = mainText.replace("let total = answer;", "let total = \"s\";")
        val caret = at(mainText, "total")
        val s = session(
            didOpen(mainUri, edited),
            positioned(2, "textDocument/hover", mainUri, caret.first, caret.second),
            notification(
                "textDocument/didClose",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", mainUri) })
                },
            ),
            positioned(3, "textDocument/hover", mainUri, caret.first, caret.second),
        )
        val whileOpen = s.responses[2]?.obj("result")?.obj("contents")?.string("value")
        val afterClose = s.responses[3]?.obj("result")?.obj("contents")?.string("value")
        val direct = directProject()
        val expectedDisk = direct.quickInfoAt(mainFile, offsetOf(mainText, "total"))
        assert(expectedDisk != null)
        assert(afterClose == expectedDisk.displayString)
        assert(whileOpen != afterClose)
    }

    @Test
    fun `didSave with included text is authoritative`() {
        val edited = mainText.replace("let total = answer;", "let total = \"s\";")
        val caret = at(mainText, "total")
        val s = session(
            didOpen(mainUri, mainText),
            notification(
                "textDocument/didSave",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", mainUri) })
                    put("text", edited)
                },
            ),
            positioned(2, "textDocument/hover", mainUri, caret.first, caret.second),
        )
        val value = s.responses[2]?.obj("result")?.obj("contents")?.string("value")
        val direct = directProject()
        direct.updateFile(mainFile, edited)
        val expected = direct.quickInfoAt(mainFile, offsetOf(edited, "total"))
        assert(expected != null)
        assert(value == expected.displayString)
    }

    @Test
    fun `watched-file changes reload from the disk the server reads through`() {
        val vfs = InMemoryVfs(fixture())
        val edited = mainText.replace("let total = answer;", "let total = \"s\";")
        val caret = at(mainText, "total")
        // The write happens before the session is DRIVEN, but after didOpen in
        // MESSAGE order the server has staged the ORIGINAL didOpen text — the
        // watched-files notification is what makes it re-read this vfs.
        vfs.writeText(mainFile, edited)
        val s = session(
            didOpen(mainUri, mainText),
            positioned(2, "textDocument/hover", mainUri, caret.first, caret.second),
            notification(
                "workspace/didChangeWatchedFiles",
                buildJsonObject {
                    put(
                        "changes",
                        buildJsonArray {
                            add(buildJsonObject { put("uri", mainUri); put("type", 2) })
                        },
                    )
                },
            ),
            positioned(3, "textDocument/hover", mainUri, caret.first, caret.second),
            vfs = vfs,
        )
        val before = s.responses[2]?.obj("result")?.obj("contents")?.string("value")
        val after = s.responses[3]?.obj("result")?.obj("contents")?.string("value")
        val direct = directProject()
        val expectedBefore = direct.quickInfoAt(mainFile, offsetOf(mainText, "total"))
        assert(expectedBefore != null)
        assert(before == expectedBefore.displayString)
        direct.updateFile(mainFile, edited)
        val expectedAfter = direct.quickInfoAt(mainFile, offsetOf(edited, "total"))
        assert(expectedAfter != null)
        assert(after == expectedAfter.displayString)
    }

    // --- navigation -----------------------------------------------------------------

    @Test
    fun `definition crosses files and its uri round-trips`() {
        val caret = at(mainText, "greet", 1)
        val s = session(
            didOpen(mainUri, mainText),
            positioned(2, "textDocument/definition", mainUri, caret.first, caret.second),
        )
        val locations = s.responses[2]?.get("result") as? JsonArray
        assert(locations != null)
        val direct = directProject()
        val expected = direct.definitionsAt(mainFile, offsetOf(mainText, "greet", 1))
        assert(expected.isNotEmpty())
        assert(locations.size == expected.size)
        val first = locations[0] as? JsonObject
        val uri = first?.string("uri")
        assert(uri == "file://" + expected[0].fileName)
        val startOffset = rangeStartOffset(direct, expected[0].fileName, first.obj("range"))
        assert(startOffset == expected[0].start)
    }

    @Test
    fun `references honour includeDeclaration`() {
        val caret = at(mainText, "answer")
        val withDecl = positioned(
            2, "textDocument/references", mainUri, caret.first, caret.second,
            buildJsonObject {
                put("context", buildJsonObject { put("includeDeclaration", true) })
            },
        )
        val withoutDecl = positioned(
            3, "textDocument/references", mainUri, caret.first, caret.second,
            buildJsonObject {
                put("context", buildJsonObject { put("includeDeclaration", false) })
            },
        )
        val s = session(didOpen(mainUri, mainText), withDecl, withoutDecl)
        val all = s.responses[2]?.get("result") as? JsonArray
        val uses = s.responses[3]?.get("result") as? JsonArray
        val direct = directProject().referencesAt(mainFile, offsetOf(mainText, "answer"))
        assert(direct.isNotEmpty())
        val allCount = all?.size
        val usesCount = uses?.size
        assert(allCount == direct.size)
        assert(usesCount == direct.count { !it.isDeclaration })
        assert(allCount != usesCount)
    }

    @Test
    fun `document highlights carry read and write kinds`() {
        val caret = at(mainText, "total")
        val s = session(
            didOpen(mainUri, mainText),
            positioned(2, "textDocument/documentHighlight", mainUri, caret.first, caret.second),
        )
        val highlights = s.responses[2]?.get("result") as? JsonArray
        assert(highlights != null)
        val kinds = highlights.mapNotNull { (it as? JsonObject)?.int("kind") }
        assert(2 in kinds)
        assert(3 in kinds)
        val direct = directProject()
            .documentHighlightsAt(mainFile, offsetOf(mainText, "total"))
        assert(highlights.size == direct.size)
    }

    // --- completion and signature help ----------------------------------------------

    @Test
    fun `completion mirrors the embedding API's list and replacement span`() {
        val caret = at(mainText, "charAt")
        val position = caret.first to caret.second + 2
        val s = session(
            didOpen(mainUri, mainText),
            positioned(2, "textDocument/completion", mainUri, position.first, position.second),
        )
        val lspItems = items(s.responses[2])
        assert(lspItems != null)
        val direct = directProject()
        val expected = direct.completionsAt(mainFile, offsetOf(mainText, "charAt") + 2)
        assert(expected.items.isNotEmpty())
        val labels = lspItems.mapNotNull { (it as? JsonObject)?.string("label") }
        val expectedNames = expected.items.map { it.name }
        assert(labels == expectedNames)
        val charAtIndex = expectedNames.indexOf("charAt")
        assert(charAtIndex >= 0)
        val charAtItem = lspItems[charAtIndex] as? JsonObject
        val detail = charAtItem?.string("detail")
        assert(detail == expected.items[charAtIndex].typeText)
        val edit = charAtItem.obj("textEdit")
        val editStart = rangeStartOffset(direct, mainFile, edit?.obj("range"))
        val editEnd = rangeEndOffset(direct, mainFile, edit?.obj("range"))
        assert(editStart == expected.replacementStart)
        assert(editEnd == expected.replacementEnd)
    }

    @Test
    fun `signature help reports the active argument`() {
        val offset = offsetOf(mainText, "total)")
        val caret = at(mainText, "total)")
        val s = session(
            didOpen(mainUri, mainText),
            positioned(2, "textDocument/signatureHelp", mainUri, caret.first, caret.second),
        )
        val result = s.responses[2]?.obj("result")
        val direct = directProject().signatureHelpAt(mainFile, offset)
        assert(direct != null)
        val signatures = result?.get("signatures") as? JsonArray
        assert(signatures != null)
        assert(signatures.size == direct.signatures.size)
        val label = (signatures[0] as? JsonObject)?.string("label")
        assert(label == direct.signatures[0].label)
        val active = result.int("activeParameter")
        assert(active == direct.activeArgument)
    }

    // --- rename ----------------------------------------------------------------------

    @Test
    fun `prepareRename answers the identifier span and refuses a keyword`() {
        val caret = at(mainText, "total")
        val keywordCaret = at(mainText, "import")
        val s = session(
            didOpen(mainUri, mainText),
            positioned(2, "textDocument/prepareRename", mainUri, caret.first, caret.second),
            positioned(3, "textDocument/prepareRename", mainUri, keywordCaret.first, keywordCaret.second),
        )
        val direct = directProject()
        val range = s.responses[2]?.obj("result")
        val start = rangeStartOffset(direct, mainFile, range)
        val end = rangeEndOffset(direct, mainFile, range)
        assert(start == offsetOf(mainText, "total"))
        assert(end == offsetOf(mainText, "total") + "total".length)
        val keywordResult = s.responses[3]?.get("result")
        val keywordIsNull = keywordResult is JsonPrimitive || keywordResult == null ||
            keywordResult.toString() == "null"
        assert(keywordIsNull)
    }

    @Test
    fun `rename produces a workspace edit mirroring the plan`() {
        val caret = at(mainText, "answer")
        val s = session(
            didOpen(mainUri, mainText),
            positioned(
                2, "textDocument/rename", mainUri, caret.first, caret.second,
                buildJsonObject { put("newName", "answer2") },
            ),
        )
        val changes = s.responses[2]?.obj("result")?.obj("changes")
        assert(changes != null)
        val direct = directProject()
        val plan = direct.renameAt(mainFile, offsetOf(mainText, "answer"), "answer2")
        assert(plan.refusal == null)
        assert(plan.files.size == changes.size)
        for (file in plan.files) {
            val edits = changes["file://" + file.fileName] as? JsonArray
            assert(edits != null)
            assert(edits.size == file.edits.size)
            val firstStart = rangeStartOffset(direct, file.fileName, (edits[0] as? JsonObject)?.obj("range"))
            assert(firstStart == file.edits[0].start)
            val newText = (edits[0] as? JsonObject)?.string("newText")
            assert(newText == "answer2")
        }
    }

    @Test
    fun `a refused rename is an error carrying the reason`() {
        val keywordCaret = at(mainText, "import")
        val s = session(
            didOpen(mainUri, mainText),
            positioned(
                2, "textDocument/rename", mainUri, keywordCaret.first, keywordCaret.second,
                buildJsonObject { put("newName", "whatever") },
            ),
        )
        val error = s.responses[2]?.obj("error")
        assert(error != null)
        val code = error.int("code")
        assert(code == -32803)
        val message = error.string("message") ?: ""
        assert("rename refused" in message)
        assert("NOT_AN_IDENTIFIER" in message)
    }

    // --- diagnostics -------------------------------------------------------------------

    @Test
    fun `pull diagnostics answers the file's own rows`() {
        val s = session(
            didOpen(mainUri, mainText),
            request(
                2,
                "textDocument/diagnostic",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", mainUri) })
                },
            ),
        )
        val kind = s.responses[2]?.obj("result")?.string("kind")
        assert(kind == "full")
        val lspItems = items(s.responses[2])
        assert(lspItems != null)
        val direct = directProject().diagnostics(mainFile)
        assert(direct.isNotEmpty())
        assert(lspItems.size == direct.size)
        val first = lspItems[0] as? JsonObject
        assert(first?.int("severity") == 1)
        assert(first.int("code") == direct[0].code)
        val line0 = first.obj("range")?.obj("start")?.int("line")
        assert(line0 == (direct[0].line ?: 0) - 1)
    }

    @Test
    fun `didOpen publishes project-wide diagnostics including other files`() {
        // Only LIB is opened; the planted error lives in MAIN — the publish that
        // names it is the project-wide capability tsgo's LSP does not have.
        val s = session(didOpen(libUri, libText))
        val forMain = s.published(mainUri)
        assert(forMain.isNotEmpty())
        val rows = forMain.last().get("diagnostics") as? JsonArray
        assert(rows != null)
        val direct = directProject().diagnostics(mainFile)
        assert(rows.size == direct.size)
        assert(rows.isNotEmpty())
    }

    @Test
    fun `a fixed file is republished empty and didChange alone publishes nothing`() {
        val fixed = mainText.replace("const bad: string = answer;", "const bad: number = answer;")
        val s = session(
            didOpen(mainUri, mainText),
            didChangeFull(mainUri, fixed),
            positioned(2, "textDocument/hover", mainUri, 1, 4),
            notification(
                "textDocument/didSave",
                buildJsonObject {
                    put("textDocument", buildJsonObject { put("uri", mainUri) })
                    put("text", fixed)
                },
            ),
        )
        val forMain = s.published(mainUri)
        // Round 1 - didOpen - has the row; the didChange publishes NOTHING
        // (deliberate, per the handler's KDoc); the didSave round clears it.
        assert(forMain.size == 2)
        val firstRows = forMain.first().get("diagnostics") as? JsonArray
        val lastRows = forMain.last().get("diagnostics") as? JsonArray
        assert(firstRows != null)
        assert(lastRows != null)
        assert(firstRows.isNotEmpty())
        assert(lastRows.isEmpty())
    }
}

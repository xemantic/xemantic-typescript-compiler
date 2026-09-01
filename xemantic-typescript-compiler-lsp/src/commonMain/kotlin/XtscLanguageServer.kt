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

import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.Vfs
import com.xemantic.typescript.compiler.project.Project
import com.xemantic.typescript.compiler.project.TextPosition
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * (LSP.1) A Language Server Protocol server over the [-project embedding
 * API][Project] — JSON-RPC 2.0 on the LSP base protocol, speaking exactly:
 * `initialize`, `initialized`, `textDocument/didOpen`, `textDocument/hover`,
 * `shutdown`, `exit`. Everything else is (LSP.2), and the dispatch is built so
 * each addition is one entry in [requestHandlers] or [notificationHandlers]
 * plus its handler.
 *
 * ## Lifecycle and protocol policy, in one place
 *
 * One instance serves one client over one stream pair, on the caller's thread,
 * and is done: [serve] reads frames until the `exit` notification or end of
 * input, then closes the [Project] and returns the process exit code the LSP
 * spec asks for — 0 when `shutdown` was received first, 1 otherwise (end of
 * input without `exit` is graded by the same rule).
 *
 * The JSON-RPC decisions, each pinned by a test:
 *
 * - a response echoes the request's `id` VERBATIM — number and string ids alike;
 * - a message with no `id`, or with `"id": null`, is a NOTIFICATION — there is
 *   nowhere to route a response, so an unknown one is ignored silently;
 * - an unknown method WITH an id answers `-32601` MethodNotFound;
 * - unparseable JSON answers `-32700` with `"id": null`; a body that parses to
 *   something other than an object, or an object without a `method`, answers
 *   `-32600` InvalidRequest (id echoed when one could be read);
 * - after `shutdown`, every request but none of the notifications is answered:
 *   a request other than `exit` gets `-32600` (the conventional reading of the
 *   spec's "answer with InvalidRequest"), a notification other than `exit` is
 *   dropped, exactly as the spec says;
 * - a handler that throws answers `-32603` InternalError and the server keeps
 *   serving — one poisoned query must not take the session down. Only
 *   [Exception] is caught: an [Error] propagates, by this repo's doctrine
 *   (a swallowed `StackOverflowError`/cancellation would be silently wrong).
 *
 * ## The project
 *
 * Constructed AT `initialize` from `rootUri` (a `file://` URI, percent-decoded)
 * falling back to the deprecated `rootPath`. When neither is usable or
 * [Project.open] refuses the path (it throws for a nonexistent one), the server
 * still initializes — refusing would get it killed by the client — and runs
 * projectless: `didOpen` is remembered but staged nowhere, and `hover` answers
 * `null`, the protocol's own "nothing to say".
 *
 * ## Positions
 *
 * LSP positions are 0-based (line, character) with `character` counted in
 * UTF-16 code units; [Project.offsetAt]/[Project.positionAt] are 1-based and
 * offsets are Kotlin string indices, which ARE UTF-16 code units. So the
 * conversion is exactly `+1`/`-1` — pinned at an astral-plane character in
 * `XtscLspServerTest` rather than assumed. A coordinate from a client one
 * keystroke ahead of us can name a line we do not have; `offsetAt` throws
 * `IllegalArgumentException` for it, which hover treats as "no answer yet"
 * (`docs/language-service.md` § 6, § 12).
 */
public class XtscLanguageServer(
    /**
     * The filesystem the [Project] reads through — injectable so tests serve a
     * fully in-memory project; production uses the default.
     */
    private val vfs: Vfs = SystemVfs,
) {

    private val json = Json

    /** Open at `initialize`; null before it, and null when the root was unusable. */
    private var project: Project? = null

    /**
     * Documents announced by `textDocument/didOpen`: the URI exactly as the
     * client spells it, mapped to the decoded path [Project] knows the file by.
     * Hover looks the path up here first so the client's own spelling — however
     * it percent-encoded it — keeps answering, and decodes afresh only for a
     * URI never opened (hovering an un-opened file is legal and works: the
     * project reads it through the [vfs]).
     */
    private val openDocuments = HashMap<String, String>()

    /** True once `shutdown` was answered; gates every later request but `exit`. */
    private var shutdownRequested = false

    /** True once `exit` arrived; [serve] stops after the current message. */
    private var exitRequested = false

    /** Adding a REQUEST (id-carrying) method is one entry here plus its handler. */
    private val requestHandlers: Map<String, (JsonObject?) -> JsonElement> = mapOf(
        "initialize" to ::initialize,
        "shutdown" to { _ -> shutdown() },
        "textDocument/hover" to ::hover,
    )

    /** Adding a NOTIFICATION method is one entry here plus its handler. */
    private val notificationHandlers: Map<String, (JsonObject?) -> Unit> = mapOf(
        "initialized" to { _ -> },
        "textDocument/didOpen" to ::didOpen,
        "exit" to { _ -> exit() },
    )

    /**
     * Serves one LSP session: reads framed messages from [source], writes framed
     * responses to [sink], until `exit` or end of input.
     *
     * Returns the process exit code: 0 when `shutdown` preceded the end, 1
     * otherwise. A malformed FRAME (see [LspFramingException]) and a stream
     * that dies mid-frame both end the session — the base protocol has no
     * resynchronisation point — and are graded by the same rule.
     */
    public fun serve(source: Source, sink: Sink): Int {
        try {
            while (true) {
                val text = readFrame(source) ?: break
                val response = handleMessage(text)
                if (response != null) writeFrame(sink, response)
                if (exitRequested) break
            }
        } catch (_: LspFramingException) {
            // No Content-Length: the stream cannot be re-synchronised.
        } catch (_: IOException) {
            // The peer went away mid-frame or mid-write; nothing left to serve.
        } finally {
            project?.close()
            project = null
        }
        return if (shutdownRequested) 0 else 1
    }

    /**
     * Handles one raw message body and returns the response JSON to write, or
     * null when the message produces none (every notification). Internal so
     * tests can drive the protocol layer without a stream.
     */
    internal fun handleMessage(text: String): String? {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return errorResponse(JsonNull, PARSE_ERROR, "invalid JSON: ${e.message}")
        }
        val obj = root as? JsonObject
            ?: return errorResponse(JsonNull, INVALID_REQUEST, "request is not an object")
        // JSON-RPC 2.0: no id — and, for this server, `"id": null` — is a
        // notification; there is no id to route a response by.
        val id = obj["id"]?.takeIf { it !is JsonNull }
        val method = obj.string("method")
            ?: return if (id == null) null
            else errorResponse(id, INVALID_REQUEST, "request without a method")
        val params = obj.paramsObject()
        if (shutdownRequested && method != "exit") {
            return if (id == null) null
            else errorResponse(id, INVALID_REQUEST, "request after shutdown: $method")
        }
        if (id == null) {
            notificationHandlers[method]?.invoke(params)
            return null
        }
        val handler = requestHandlers[method]
            ?: return errorResponse(id, METHOD_NOT_FOUND, "method not found: $method")
        return try {
            successResponse(id, handler(params))
        } catch (e: Exception) {
            errorResponse(id, INTERNAL_ERROR, "$method failed: ${e.message}")
        }
    }

    // --- handlers ---------------------------------------------------------------

    private fun initialize(params: JsonObject?): JsonElement {
        val rootUri = params?.string("rootUri")
        val rootPath = params?.string("rootPath")
        val root = rootUri?.let { uriToPath(it) } ?: rootPath
        if (root != null) {
            project = try {
                Project.open(root, vfs)
            } catch (_: IllegalArgumentException) {
                // A root that does not exist. Initialize anyway (see class KDoc);
                // every semantic query then answers null.
                null
            }
        }
        return buildJsonObject {
            put(
                "capabilities",
                buildJsonObject {
                    put("hoverProvider", true)
                    // 1 = Full sync. Announced now so clients send whole-buffer
                    // didChange from day one; the didChange HANDLER is (LSP.2).
                    put("textDocumentSync", 1)
                },
            )
            put("serverInfo", buildJsonObject { put("name", "xtsc-lsp") })
        }
    }

    private fun shutdown(): JsonElement {
        shutdownRequested = true
        return JsonNull
    }

    private fun exit() {
        exitRequested = true
    }

    private fun didOpen(params: JsonObject?) {
        val textDocument = params?.obj("textDocument") ?: return
        val uri = textDocument.string("uri") ?: return
        val text = textDocument.string("text") ?: return
        val path = uriToPath(uri) ?: return
        openDocuments[uri] = path
        // The client's buffer is now the truth for this file, saved or not.
        project?.updateFile(path, text)
    }

    private fun hover(params: JsonObject?): JsonElement {
        val project = this.project ?: return JsonNull
        val uri = params?.obj("textDocument")?.string("uri") ?: return JsonNull
        val path = openDocuments[uri] ?: uriToPath(uri) ?: return JsonNull
        val position = params.obj("position") ?: return JsonNull
        val line0 = position.int("line") ?: return JsonNull
        val char0 = position.int("character") ?: return JsonNull
        // 0-based LSP to this API's 1-based coordinates; a line we do not have
        // (the client is a keystroke ahead) throws and means "no answer yet".
        val offset = try {
            project.offsetAt(path, line0 + 1, char0 + 1)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return JsonNull
        // The touch rule (§ 12): spans are half-open, so `abc|` is outside
        // `abc` — ask at the caret, then one to the left.
        val info = project.quickInfoAt(path, offset)
            ?: (if (offset > 0) project.quickInfoAt(path, offset - 1) else null)
            ?: return JsonNull
        return buildJsonObject {
            put(
                "contents",
                buildJsonObject {
                    put("kind", "plaintext")
                    put("value", info.displayString)
                },
            )
            val start = project.positionAt(path, info.start)
            val end = project.positionAt(path, info.end)
            // Range is optional in the protocol; omitted rather than guessed on
            // the (unreachable while offsetAt answered) null conversion.
            if (start != null && end != null) {
                put(
                    "range",
                    buildJsonObject {
                        put("start", lspPosition(start))
                        put("end", lspPosition(end))
                    },
                )
            }
        }
    }

    /** A 1-based [TextPosition] as the protocol's 0-based position object. */
    private fun lspPosition(p: TextPosition): JsonObject = buildJsonObject {
        put("line", p.line - 1)
        put("character", p.character - 1)
    }
}

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

import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.Vfs
import com.xemantic.typescript.compiler.project.Project
import com.xemantic.typescript.compiler.project.ReferenceUse
import com.xemantic.typescript.compiler.project.TextPosition
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * (LSP.1)+(LSP.2) A Language Server Protocol server over the [-project embedding
 * API][Project] — JSON-RPC 2.0 on the LSP base protocol. The surface:
 * `initialize`/`initialized`, the document lifecycle (`didOpen`, `didChange`
 * with Full sync, `didClose`, `didSave`, `workspace/didChangeWatchedFiles`),
 * `hover`, `definition`, `references`, `documentHighlight`, `completion`,
 * `signatureHelp`, `prepareRename`/`rename`, pull diagnostics
 * (`textDocument/diagnostic`) plus PROJECT-WIDE `publishDiagnostics` — the one
 * capability tsgo's LSP does not have, served by the narrowed incremental
 * [Project.diagnostics] — and `shutdown`/`exit`. The dispatch is two maps, so
 * a new method is one entry in [requestHandlers] or [notificationHandlers]
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

    /**
     * Files a `publishDiagnostics` notification currently stands for, so the next
     * round can CLEAR a file that became clean — the protocol's contract is that
     * published diagnostics persist until replaced, so a fixed file must be
     * republished with an empty list, not silently dropped.
     */
    private val publishedFiles = HashSet<String>()

    /**
     * Server-initiated notifications produced while handling one message —
     * `publishDiagnostics` after a lifecycle event. [serve] writes them after the
     * message's own response; [drainNotifications] hands them to stream-less
     * tests.
     */
    private val pendingNotifications = ArrayList<String>()

    /** True once `shutdown` was answered; gates every later request but `exit`. */
    private var shutdownRequested = false

    /** True once `exit` arrived; [serve] stops after the current message. */
    private var exitRequested = false

    /** Adding a REQUEST (id-carrying) method is one entry here plus its handler. */
    private val requestHandlers: Map<String, (JsonObject?) -> JsonElement> = mapOf(
        "initialize" to ::initialize,
        "shutdown" to { _ -> shutdown() },
        "textDocument/hover" to ::hover,
        "textDocument/definition" to ::definition,
        "textDocument/references" to ::references,
        "textDocument/documentHighlight" to ::documentHighlight,
        "textDocument/completion" to ::completion,
        "textDocument/signatureHelp" to ::signatureHelp,
        "textDocument/prepareRename" to ::prepareRename,
        "textDocument/rename" to ::rename,
        "textDocument/diagnostic" to ::documentDiagnostic,
    )

    /** Adding a NOTIFICATION method is one entry here plus its handler. */
    private val notificationHandlers: Map<String, (JsonObject?) -> Unit> = mapOf(
        "initialized" to { _ -> },
        "textDocument/didOpen" to ::didOpen,
        "textDocument/didChange" to ::didChange,
        "textDocument/didClose" to ::didClose,
        "textDocument/didSave" to ::didSave,
        "workspace/didChangeWatchedFiles" to ::didChangeWatchedFiles,
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
                for (n in drainNotifications()) writeFrame(sink, n)
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
        } catch (e: LspRequestException) {
            // A handler's own refusal — the rename contract: a refused plan is an
            // ERROR carrying its reason, never an empty edit that looks like success.
            errorResponse(id, e.code, e.message)
        } catch (e: Exception) {
            errorResponse(id, INTERNAL_ERROR, "$method failed: ${e.message}")
        }
    }

    /**
     * The server-initiated notifications the last [handleMessage] produced, in
     * order, clearing the queue. [serve] calls it per message; a stream-less test
     * calls it directly.
     */
    internal fun drainNotifications(): List<String> {
        if (pendingNotifications.isEmpty()) return emptyList()
        val out = pendingNotifications.toList()
        pendingNotifications.clear()
        return out
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
                    // 1 = Full sync: every didChange carries the whole buffer,
                    // which is exactly what [Project.updateFile] takes.
                    put("textDocumentSync", 1)
                    put("definitionProvider", true)
                    put("referencesProvider", true)
                    put("documentHighlightProvider", true)
                    put(
                        "completionProvider",
                        buildJsonObject {
                            put(
                                "triggerCharacters",
                                buildJsonArray { add("."); add("\""); add("'") },
                            )
                        },
                    )
                    put(
                        "signatureHelpProvider",
                        buildJsonObject {
                            put(
                                "triggerCharacters",
                                buildJsonArray { add("("); add(",") },
                            )
                        },
                    )
                    put("renameProvider", buildJsonObject { put("prepareProvider", true) })
                    // Pull diagnostics. `interFileDependencies` is the honest
                    // setting: an edit here can move another file's rows.
                    put(
                        "diagnosticProvider",
                        buildJsonObject {
                            put("identifier", "xtsc")
                            put("interFileDependencies", true)
                            put("workspaceDiagnostics", false)
                        },
                    )
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
        publishProjectDiagnostics()
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

    // --- (LSP.2) document lifecycle ---------------------------------------------

    /**
     * Full-sync `didChange`: the LAST whole-buffer change wins. A change carrying
     * a `range` is a client ignoring our announced `textDocumentSync: 1`; there
     * is no sound way to apply it against a buffer we may not share, so ranged
     * changes are SKIPPED (pinned) rather than guessed at.
     *
     * Deliberately does NOT publish project diagnostics — that would run a
     * narrowed build per keystroke. Publishing happens on open/close/save and
     * watched-file events; a client wanting fresher rows uses pull diagnostics.
     */
    private fun didChange(params: JsonObject?) {
        val uri = params?.obj("textDocument")?.string("uri") ?: return
        val path = openDocuments[uri] ?: uriToPath(uri) ?: return
        val changes = params.get("contentChanges") as? JsonArray ?: return
        val full = changes.lastOrNull {
            it is JsonObject && it.obj("range") == null && it.string("text") != null
        } as? JsonObject ?: return
        project?.updateFile(path, full.string("text")!!)
    }

    /**
     * `didClose`: the buffer is gone, so the DISK is the truth again —
     * [Project.reloadFile] drops the overlay and re-reads. An unchanged file
     * costs nothing downstream: the content hash sees no change.
     */
    private fun didClose(params: JsonObject?) {
        val uri = params?.obj("textDocument")?.string("uri") ?: return
        val path = openDocuments.remove(uri) ?: uriToPath(uri) ?: return
        project?.reloadFile(path)
        publishProjectDiagnostics()
    }

    /**
     * `didSave`: with `text` included, that text is authoritative; without it,
     * the disk now equals the buffer, so re-read the disk.
     */
    private fun didSave(params: JsonObject?) {
        val uri = params?.obj("textDocument")?.string("uri") ?: return
        val path = openDocuments[uri] ?: uriToPath(uri) ?: return
        val text = params.string("text")
        val project = this.project ?: return
        if (text != null) project.updateFile(path, text) else project.reloadFile(path)
        publishProjectDiagnostics()
    }

    /** Watched files changed behind the editor's back: re-read each from disk. */
    private fun didChangeWatchedFiles(params: JsonObject?) {
        val changes = params?.get("changes") as? JsonArray ?: return
        val project = this.project ?: return
        for (change in changes) {
            val uri = (change as? JsonObject)?.string("uri") ?: continue
            val path = uriToPath(uri) ?: continue
            project.reloadFile(path)
        }
        publishProjectDiagnostics()
    }

    // --- (LSP.2) navigation -----------------------------------------------------

    private fun definition(params: JsonObject?): JsonElement {
        val project = this.project ?: return JsonNull
        val at = resolvePosition(params) ?: return JsonNull
        val (path, offset) = at
        // The touch rule, as for hover: `abc|` is outside `abc`.
        var locations = project.definitionsAt(path, offset)
        if (locations.isEmpty() && offset > 0) {
            locations = project.definitionsAt(path, offset - 1)
        }
        return buildJsonArray {
            for (l in locations) {
                val range = rangeOf(l.fileName, l.start, l.start + l.length) ?: continue
                add(
                    buildJsonObject {
                        put("uri", pathToUri(l.fileName))
                        put("range", range)
                    },
                )
            }
        }
    }

    private fun references(params: JsonObject?): JsonElement {
        val project = this.project ?: return JsonNull
        val at = resolvePosition(params) ?: return JsonNull
        val (path, offset) = at
        // The spec makes `context.includeDeclaration` mandatory; a client that
        // omitted it anyway is read as wanting everything.
        val includeDeclaration =
            (params?.obj("context")?.get("includeDeclaration") as? JsonPrimitive)
                ?.booleanOrNull ?: true
        var refs = project.referencesAt(path, offset)
        if (refs.isEmpty() && offset > 0) refs = project.referencesAt(path, offset - 1)
        return buildJsonArray {
            for (r in refs) {
                if (!includeDeclaration && r.isDeclaration) continue
                val range = rangeOf(r.fileName, r.start, r.end) ?: continue
                add(
                    buildJsonObject {
                        put("uri", pathToUri(r.fileName))
                        put("range", range)
                    },
                )
            }
        }
    }

    private fun documentHighlight(params: JsonObject?): JsonElement {
        val project = this.project ?: return JsonNull
        val at = resolvePosition(params) ?: return JsonNull
        val (path, offset) = at
        var refs = project.documentHighlightsAt(path, offset)
        if (refs.isEmpty() && offset > 0) {
            refs = project.documentHighlightsAt(path, offset - 1)
        }
        return buildJsonArray {
            for (r in refs) {
                val range = rangeOf(r.fileName, r.start, r.end) ?: continue
                add(
                    buildJsonObject {
                        put("range", range)
                        // DocumentHighlightKind: 1 Text, 2 Read, 3 Write. A
                        // READ_WRITE occurrence (x += 1) highlights as a write,
                        // tsc's own choice; a non-value occurrence as Text.
                        put(
                            "kind",
                            when (r.use) {
                                ReferenceUse.WRITE, ReferenceUse.READ_WRITE -> 3
                                ReferenceUse.READ -> 2
                                else -> 1
                            },
                        )
                    },
                )
            }
        }
    }

    // --- (LSP.2) completion and signature help ----------------------------------

    private fun completion(params: JsonObject?): JsonElement {
        val project = this.project ?: return JsonNull
        val at = resolvePosition(params) ?: return JsonNull
        val (path, offset) = at
        val list = project.completionsAt(path, offset)
        val replaceRange = rangeOf(path, list.replacementStart, list.replacementEnd)
        return buildJsonObject {
            put("isIncomplete", false)
            put(
                "items",
                buildJsonArray {
                    for (item in list.items) {
                        add(
                            buildJsonObject {
                                put("label", item.name)
                                put("kind", completionItemKind(item.kind))
                                if (item.typeText.isNotEmpty()) put("detail", item.typeText)
                                // The whole word under the caret is replaced —
                                // accepting in the middle of `o.fo|o` must leave
                                // no `o` behind (CompletionList's own contract).
                                if (replaceRange != null) {
                                    put(
                                        "textEdit",
                                        buildJsonObject {
                                            put("range", replaceRange)
                                            put("newText", item.name)
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun signatureHelp(params: JsonObject?): JsonElement {
        val project = this.project ?: return JsonNull
        val at = resolvePosition(params) ?: return JsonNull
        val (path, offset) = at
        val help = project.signatureHelpAt(path, offset) ?: return JsonNull
        return buildJsonObject {
            put(
                "signatures",
                buildJsonArray {
                    for (sig in help.signatures) {
                        add(
                            buildJsonObject {
                                put("label", sig.label)
                                put(
                                    "parameters",
                                    buildJsonArray {
                                        for (param in sig.parameters) {
                                            add(
                                                buildJsonObject {
                                                    // [start, end) into the label,
                                                    // UTF-16 units — the protocol's
                                                    // preferred exact form.
                                                    put(
                                                        "label",
                                                        buildJsonArray {
                                                            add(param.labelStart)
                                                            add(param.labelEnd)
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put("activeSignature", help.activeSignature)
            put("activeParameter", help.activeArgument)
        }
    }

    // --- (LSP.2) rename ----------------------------------------------------------

    /**
     * `prepareRename`: the exact identifier span, or null for anything else.
     * Answered from the PARSE alone ([Project.nodeInfoAt] compiles nothing), so
     * an editor probing every caret costs nothing semantic.
     */
    private fun prepareRename(params: JsonObject?): JsonElement {
        val project = this.project ?: return JsonNull
        val at = resolvePosition(params) ?: return JsonNull
        val (path, offset) = at
        var info = project.nodeInfoAt(path, offset)
        if ((info == null || info.kind != "Identifier") && offset > 0) {
            info = project.nodeInfoAt(path, offset - 1)
        }
        if (info == null || info.kind != "Identifier") return JsonNull
        return rangeOf(path, info.start, info.end) ?: JsonNull
    }

    /**
     * `rename`: [Project.renameAt] plans, applies and RECOMPILES before we see
     * the answer; a refusal becomes an LSP error CARRYING ITS REASON — never an
     * empty edit that a client would render as a successful no-op.
     */
    private fun rename(params: JsonObject?): JsonElement {
        val project = this.project
            ?: throw LspRequestException(message = "rename refused: no project is open")
        val at = resolvePosition(params)
            ?: throw LspRequestException(message = "rename refused: no position")
        val (path, offset) = at
        val newName = params?.string("newName")
            ?: throw LspRequestException(message = "rename refused: no newName")
        var plan = project.renameAt(path, offset, newName)
        if (plan.refusal != null && offset > 0) {
            // The touch rule: `abc|` is outside `abc`.
            val retry = project.renameAt(path, offset - 1, newName)
            if (retry.refusal == null) plan = retry
        }
        val refusal = plan.refusal
        if (refusal != null) {
            val detail = plan.conflicts.joinToString("") { c ->
                "; ${c.fileName}:${c.start}"
            }
            throw LspRequestException(
                message = "rename refused: $refusal$detail",
            )
        }
        return buildJsonObject {
            put(
                "changes",
                buildJsonObject {
                    for (file in plan.files) {
                        put(
                            pathToUri(file.fileName),
                            buildJsonArray {
                                for (edit in file.edits) {
                                    val range =
                                        rangeOf(file.fileName, edit.start, edit.end)
                                            ?: continue
                                    add(
                                        buildJsonObject {
                                            put("range", range)
                                            put("newText", edit.newText)
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    // --- (LSP.2) diagnostics ------------------------------------------------------

    /** Pull diagnostics for one document: a full report, per-file scoped. */
    private fun documentDiagnostic(params: JsonObject?): JsonElement {
        val project = this.project
            ?: return buildJsonObject {
                put("kind", "full")
                put("items", buildJsonArray {})
            }
        val uri = params?.obj("textDocument")?.string("uri")
        val path = uri?.let { openDocuments[it] ?: uriToPath(it) }
            ?: return buildJsonObject {
                put("kind", "full")
                put("items", buildJsonArray {})
            }
        return buildJsonObject {
            put("kind", "full")
            put(
                "items",
                buildJsonArray {
                    for (d in project.diagnostics(path)) add(diagnosticJson(d))
                },
            )
        }
    }

    /**
     * PROJECT-WIDE publish — the capability tsgo's LSP does not have. One
     * narrowed incremental [Project.diagnostics] call answers about the WHOLE
     * program; the rows are grouped per file, published, and every file a
     * previous round published that is now clean is republished EMPTY (the
     * protocol's persistence contract). Runs on open/close/save/watched-files,
     * deliberately not per keystroke.
     */
    private fun publishProjectDiagnostics() {
        val project = this.project ?: return
        val byFile = HashMap<String, MutableList<Diagnostic>>()
        for (d in project.diagnostics()) {
            val file = d.fileName ?: continue
            byFile.getOrPut(file) { ArrayList() }.add(d)
        }
        for (file in publishedFiles) {
            if (file !in byFile) byFile[file] = ArrayList()
        }
        publishedFiles.clear()
        for ((file, rows) in byFile) {
            if (rows.isNotEmpty()) publishedFiles.add(file)
            pendingNotifications.add(
                notificationMessage(
                    "textDocument/publishDiagnostics",
                    buildJsonObject {
                        put("uri", pathToUri(file))
                        put(
                            "diagnostics",
                            buildJsonArray { for (d in rows) add(diagnosticJson(d)) },
                        )
                    },
                ),
            )
        }
    }

    /**
     * One [Diagnostic] in the protocol's shape. The compiler's `line`/`character`
     * are 1-based and its `start`/`length` are offsets; a diagnostic with no
     * position (a program-level report) anchors at the file head, which is what
     * every LSP client renders for "this file, no particular place".
     */
    private fun diagnosticJson(d: Diagnostic): JsonObject = buildJsonObject {
        val start = buildJsonObject {
            put("line", (d.line ?: 1) - 1)
            put("character", (d.character ?: 1) - 1)
        }
        val endOffset = d.start?.let { it + (d.length ?: 0) }
        val endPosition = endOffset?.let { off ->
            d.fileName?.let { f ->
                try {
                    project?.positionAt(f, off)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
        put(
            "range",
            buildJsonObject {
                put("start", start)
                put("end", endPosition?.let(::lspPosition) ?: start)
            },
        )
        put(
            "severity",
            when (d.category) {
                DiagnosticCategory.Error -> 1
                DiagnosticCategory.Warning -> 2
                DiagnosticCategory.Message -> 3
                DiagnosticCategory.Suggestion -> 4
            },
        )
        put("code", d.code)
        put("source", "xtsc")
        put(
            "message",
            if (d.messageChain.isEmpty()) d.message
            else (listOf(d.message) + d.messageChain).joinToString("\n  "),
        )
    }

    // --- shared helpers -----------------------------------------------------------

    /**
     * The `(path, offset)` a positioned request names, or null when the file is
     * unknown or the client is a keystroke ahead of us (§ 6's throw, read as
     * "no answer yet").
     */
    private fun resolvePosition(params: JsonObject?): Pair<String, Int>? {
        val project = this.project ?: return null
        val uri = params?.obj("textDocument")?.string("uri") ?: return null
        val path = openDocuments[uri] ?: uriToPath(uri) ?: return null
        val position = params.obj("position") ?: return null
        val line0 = position.int("line") ?: return null
        val char0 = position.int("character") ?: return null
        val offset = try {
            project.offsetAt(path, line0 + 1, char0 + 1)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return path to offset
    }

    /** A protocol range for `[start, end)` in [path], or null off the map. */
    private fun rangeOf(path: String, start: Int, end: Int): JsonObject? {
        val project = this.project ?: return null
        val s = try {
            project.positionAt(path, start)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val e = try {
            project.positionAt(path, end)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return buildJsonObject {
            put("start", lspPosition(s))
            put("end", lspPosition(e))
        }
    }

    /** [CompletionItem.kind][com.xemantic.typescript.compiler.project.CompletionItem]
     * names to the protocol's `CompletionItemKind` numbers, `Text` (1) for the rest. */
    private fun completionItemKind(kind: String): Int = when (kind) {
        "Method" -> 2
        "Function" -> 3
        "Constructor" -> 4
        "Field" -> 5
        "Variable" -> 6
        "Class" -> 7
        "Interface" -> 8
        "Module", "Namespace" -> 9
        "Property" -> 10
        "Enum" -> 13
        "Keyword" -> 14
        "EnumMember" -> 20
        "TypeAlias", "TypeParameter" -> 25
        else -> 1
    }

    /** A 1-based [TextPosition] as the protocol's 0-based position object. */
    private fun lspPosition(p: TextPosition): JsonObject = buildJsonObject {
        put("line", p.line - 1)
        put("character", p.character - 1)
    }
}

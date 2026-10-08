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

import com.xemantic.typescript.tsgo.TsgoApiException
import com.xemantic.typescript.tsgo.TsgoLanguageService
import com.xemantic.typescript.tsgo.TsgoProject
import com.xemantic.typescript.tsgo.vfs.FS
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The xtsc language server: JSON-RPC 2.0 over a framed stream ([serve]), answered by tsgo's own
 * language service PORTED to Kotlin ((TSGO.4-a): `-tsgo`'s [TsgoLanguageService], which runs the
 * handler `internal/lsp/server.go` runs for each method over `internal/ls`). So hover, go-to-definition,
 * references, completions, signature help, highlights and diagnostics are tsgo 7.0.2's answers — the
 * gate is `LsParityTest`, a differential against the shipped `tsc --lsp` — and nothing here needs Node
 * or Go.
 *
 * What this class keeps of its own is the server's END: the protocol loop, the client's open buffers
 * ([OverlayFS] over [fs]), and the project system reduced to its simplest form — a file belongs to the
 * nearest `tsconfig.json` above it (a configured project), built on first use and rebuilt after a
 * change. A file under no tsconfig has no project: its queries answer null and its diagnostics are empty
 * (tsgo's inferred projects are not ported). Diagnostics are published for open documents after every
 * lifecycle event, and served on pull (`textDocument/diagnostic`).
 *
 * The client is described to the language service as the server learned it: the `initialize` params
 * (capabilities decide markdown hovers, definition links, …) and the configuration below. Auto-import
 * completions need tsgo's project-system registry, which is not ported, so they are switched off.
 */
public class XtscLanguageServer(
    /** The disk under the client's buffers; injectable so tests serve an in-memory project. */
    fs: FS? = TsgoProject.diskFS(),
    /** Where the default `lib.*.d.ts` live (null = the libraries bundled in the port). */
    private val libDirectory: String? = null,
) {

    private val json = Json

    private val files = OverlayFS(fs)

    /** The `initialize` request's params, as the client sent them. */
    private var initializeParams: String = "{\"capabilities\":{}}"

    /** Open documents: URI as the client spells it -> path. */
    private val openDocuments = LinkedHashMap<String, String>()

    /** Language services by tsconfig path, built on first use; cleared by any change. */
    private val services = HashMap<String, TsgoLanguageService?>()

    /** URIs whose last `publishDiagnostics` was non-empty (a fixed file must be cleared explicitly). */
    private val publishedUris = HashSet<String>()

    private val pendingNotifications = ArrayList<String>()

    private var shutdownRequested = false

    private var exitRequested = false

    /** The methods answered by the ported language service, params and result passed through as JSON. */
    private val languageServiceMethods = setOf(
        "textDocument/hover",
        "textDocument/definition",
        "textDocument/typeDefinition",
        "textDocument/references",
        "textDocument/implementation",
        "textDocument/documentHighlight",
        "textDocument/completion",
        "textDocument/signatureHelp",
        "textDocument/diagnostic",
    )

    private val notificationHandlers: Map<String, (JsonObject?) -> Unit> = mapOf(
        "initialized" to { _ -> },
        "textDocument/didOpen" to ::didOpen,
        "textDocument/didChange" to ::didChange,
        "textDocument/didClose" to ::didClose,
        "textDocument/didSave" to ::didSave,
        "workspace/didChangeWatchedFiles" to ::didChangeWatchedFiles,
        "exit" to { _ -> exitRequested = true },
    )

    /**
     * Serves one LSP session: reads framed messages from [source], writes framed responses to [sink],
     * until `exit` or end of input. Returns the process exit code: 0 when `shutdown` preceded the end,
     * 1 otherwise.
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
            // The peer went away mid-frame or mid-write.
        }
        return if (shutdownRequested) 0 else 1
    }

    /** Handles one raw message and returns the response JSON, or null for a notification. */
    internal fun handleMessage(text: String): String? {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return errorResponse(JsonNull, PARSE_ERROR, "invalid JSON: ${e.message}")
        }
        val obj = root as? JsonObject
            ?: return errorResponse(JsonNull, INVALID_REQUEST, "request is not an object")
        val id = obj["id"]?.takeIf { it !is JsonNull }
        val method = obj.string("method")
            ?: return if (id == null) null else errorResponse(id, INVALID_REQUEST, "request without a method")
        val params = obj.paramsObject()
        if (shutdownRequested && method != "exit") {
            return if (id == null) null else errorResponse(id, INVALID_REQUEST, "request after shutdown: $method")
        }
        if (id == null) {
            notificationHandlers[method]?.invoke(params)
            return null
        }
        return try {
            when (method) {
                "initialize" -> successResponse(id, initialize(params))
                "shutdown" -> {
                    shutdownRequested = true
                    successResponse(id, JsonNull)
                }
                in languageServiceMethods -> successResponse(id, languageServiceRequest(method, params))
                else -> errorResponse(id, METHOD_NOT_FOUND, "method not found: $method")
            }
        } catch (e: TsgoApiException) {
            errorResponse(id, REQUEST_FAILED, "$method failed: ${e.message}")
        } catch (e: Exception) {
            errorResponse(id, INTERNAL_ERROR, "$method failed: ${e.message}")
        }
    }

    /** The server-initiated notifications the last [handleMessage] produced, clearing the queue. */
    internal fun drainNotifications(): List<String> {
        if (pendingNotifications.isEmpty()) return emptyList()
        val out = pendingNotifications.toList()
        pendingNotifications.clear()
        return out
    }

    // --- requests ----------------------------------------------------------------

    private fun initialize(params: JsonObject?): JsonElement {
        if (params != null) initializeParams = params.toString()
        return buildJsonObject {
            put(
                "capabilities",
                buildJsonObject {
                    // 1 = full sync: every didChange carries the whole buffer.
                    put("textDocumentSync", 1)
                    put("hoverProvider", true)
                    put("definitionProvider", true)
                    put("typeDefinitionProvider", true)
                    put("referencesProvider", true)
                    put("implementationProvider", true)
                    put("documentHighlightProvider", true)
                    put(
                        "completionProvider",
                        buildJsonObject {
                            put("triggerCharacters", buildJsonArray { for (c in listOf(".", "\"", "'", "`", "/", "@", "<", "#", " ")) add(c) })
                        },
                    )
                    put(
                        "signatureHelpProvider",
                        buildJsonObject {
                            put("triggerCharacters", buildJsonArray { add("("); add(","); add("<") })
                            put("retriggerCharacters", buildJsonArray { add(")") })
                        },
                    )
                    put(
                        "diagnosticProvider",
                        buildJsonObject {
                            put("interFileDependencies", true)
                            put("workspaceDiagnostics", false)
                        },
                    )
                },
            )
            put("serverInfo", buildJsonObject { put("name", "xtsc-lsp") })
        }
    }

    private fun languageServiceRequest(method: String, params: JsonObject?): JsonElement {
        val uri = params?.obj("textDocument")?.string("uri") ?: return JsonNull
        val path = openDocuments[uri] ?: uriToPath(uri) ?: return JsonNull
        val service = serviceFor(path)
            ?: return if (method == "textDocument/diagnostic") emptyReport() else JsonNull
        return json.parseToJsonElement(service.request(method, params.toString()))
    }

    private fun emptyReport(): JsonElement = buildJsonObject {
        put("kind", "full")
        put("items", buildJsonArray {})
    }

    // --- projects ----------------------------------------------------------------

    /** The nearest tsconfig.json above [path], or null. */
    private fun configOf(path: String): String? {
        var dir = path.substringBeforeLast('/', "")
        while (true) {
            val candidate = (if (dir.isEmpty()) "" else dir) + "/tsconfig.json"
            if (files.fileExists(com.xemantic.typescript.tsgo.runtime.GoString.fromUtf16(candidate))) return candidate
            if (dir.isEmpty()) return null
            dir = dir.substringBeforeLast('/', "")
        }
    }

    private fun serviceFor(path: String): TsgoLanguageService? {
        val config = configOf(path) ?: return null
        if (config in services) return services[config]
        val service = try {
            TsgoLanguageService.open(config, files, libDirectory, initializeParams, CONFIGURATION)
        } catch (_: TsgoApiException) {
            null
        }
        services[config] = service
        return service
    }

    private fun changed() {
        services.clear()
    }

    // --- document lifecycle --------------------------------------------------------

    private fun didOpen(params: JsonObject?) {
        val textDocument = params?.obj("textDocument") ?: return
        val uri = textDocument.string("uri") ?: return
        val text = textDocument.string("text") ?: return
        val path = uriToPath(uri) ?: return
        openDocuments[uri] = path
        files.put(path, text)
        changed()
        publishDiagnostics()
    }

    private fun didChange(params: JsonObject?) {
        val uri = params?.obj("textDocument")?.string("uri") ?: return
        val path = openDocuments[uri] ?: uriToPath(uri) ?: return
        val changes = params["contentChanges"] as? JsonArray ?: return
        val full = changes.lastOrNull { it is JsonObject && it.obj("range") == null && it.string("text") != null } as? JsonObject ?: return
        files.put(path, full.string("text")!!)
        changed()
        publishDiagnostics()
    }

    private fun didClose(params: JsonObject?) {
        val uri = params?.obj("textDocument")?.string("uri") ?: return
        val path = openDocuments.remove(uri) ?: uriToPath(uri) ?: return
        files.drop(path)
        changed()
        publishDiagnostics()
    }

    private fun didSave(params: JsonObject?) {
        val uri = params?.obj("textDocument")?.string("uri") ?: return
        val path = openDocuments[uri] ?: uriToPath(uri) ?: return
        params.string("text")?.let { files.put(path, it) }
        changed()
        publishDiagnostics()
    }

    private fun didChangeWatchedFiles(@Suppress("UNUSED_PARAMETER") params: JsonObject?) {
        changed()
        publishDiagnostics()
    }

    /** `publishDiagnostics` for every open document, and an empty one for a document no longer reported. */
    private fun publishDiagnostics() {
        val now = HashSet<String>()
        for ((uri, path) in openDocuments) {
            val service = serviceFor(path)
            val items = service?.let {
                val report = json.parseToJsonElement(it.request("textDocument/diagnostic", "{\"textDocument\":{\"uri\":${JsonPrimitiveString(uri)}}}")) as? JsonObject
                report?.get("items") as? JsonArray
            } ?: JsonArray(emptyList())
            if (items.isNotEmpty()) now += uri
            if (items.isNotEmpty() || uri in publishedUris) {
                pendingNotifications += notificationMessage(
                    "textDocument/publishDiagnostics",
                    buildJsonObject {
                        put("uri", uri)
                        put("diagnostics", items)
                    },
                )
            }
        }
        for (uri in publishedUris) {
            if (uri !in now && uri !in openDocuments) {
                pendingNotifications += notificationMessage(
                    "textDocument/publishDiagnostics",
                    buildJsonObject {
                        put("uri", uri)
                        put("diagnostics", buildJsonArray {})
                    },
                )
            }
        }
        publishedUris.clear()
        publishedUris += now
    }

    @Suppress("FunctionName")
    private fun JsonPrimitiveString(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

    private companion object {
        /** The `workspace/configuration` answer (js/ts, typescript, javascript, editor): no auto-imports. */
        const val CONFIGURATION: String = "[{\"suggest\":{\"autoImports\":false}},null,null,null]"
    }
}

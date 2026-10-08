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

import com.xemantic.typescript.tsgo.api.XtscLanguageService
import com.xemantic.typescript.tsgo.api.xtscLSRequest
import com.xemantic.typescript.tsgo.api.xtscMarshal
import com.xemantic.typescript.tsgo.api.xtscOpenProgram
import com.xemantic.typescript.tsgo.api.xtscResolveClientCapabilities
import com.xemantic.typescript.tsgo.api.xtscUserPreferences
import com.xemantic.typescript.tsgo.ast.localize
import com.xemantic.typescript.tsgo.compiler.Program
import com.xemantic.typescript.tsgo.lsp.lsproto.CompletionItem
import com.xemantic.typescript.tsgo.lsp.lsproto.CompletionItemsOrListOrNull
import com.xemantic.typescript.tsgo.lsp.lsproto.HoverOrNull
import com.xemantic.typescript.tsgo.lsp.lsproto.Location
import com.xemantic.typescript.tsgo.lsp.lsproto.LocationOrLocationsOrDefinitionLinksOrNull
import com.xemantic.typescript.tsgo.lsp.lsproto.LocationsOrNull
import com.xemantic.typescript.tsgo.lsp.lsproto.PositionEncodingKindUTF16
import com.xemantic.typescript.tsgo.lsp.lsproto.PositionEncodingKindUTF8
import com.xemantic.typescript.tsgo.lsp.lsproto.Range
import com.xemantic.typescript.tsgo.lsp.lsproto.RelatedFullDocumentDiagnosticReportOrUnchangedDocumentDiagnosticReport
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import com.xemantic.typescript.tsgo.runtime.goStringToBytes
import com.xemantic.typescript.tsgo.tspath.Path
import com.xemantic.typescript.tsgo.vfs.FS
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

// (TSGO.4-a) THE LANGUAGE SERVICE: tsgo's `internal/ls` (ported, gen/ls) behind its language server's
// request handling, in process. docs/goport-ls.md.
//
// A [TsgoLanguageService] is one tsconfig's program as tsgo's project system builds a configured
// project's (`api.XtscOpenProgram`) and the language server's handlers over it (`api.XtscLanguageService`,
// each handler's body as `internal/lsp/server.go` writes it): [request] takes an LSP method and its JSON
// params and answers the JSON result the server sends — the gate (LsParityTest, `TSGO_LS=1`) compares it
// with the shipped `tsc --lsp`. The typed queries ([hover], [definition], [references], [completions],
// [diagnostics]) are the same requests at a UTF-16 offset of a file's text, their answers in offsets.
//
// The client is described as the server learns it: [initializeParams] is the `initialize` request's
// params (the capabilities decide, e.g., markdown or plain-text hovers and definition links), and
// [configuration] the client's `workspace/configuration` answer for js/ts, typescript, javascript and
// editor (a JSON array; empty = tsgo's default preferences). Auto-import completions need the project
// system's registry, which is not ported: configure `{"suggest": {"autoImports": false}}`.

/** A UTF-16 range `[start, end)` of a file's text. */
data class TsgoTextRange(val start: Int, val end: Int)

/** A range of a file. */
data class TsgoLocation(val fileName: String, val range: TsgoTextRange)

/** A hover: its [contents] in [kind] (`markdown` or `plaintext`) and the range it describes. */
data class TsgoHover(val contents: String, val kind: String, val range: TsgoTextRange?)

/** A completion entry ([kind] is the LSP `CompletionItemKind`, [detail] when the server resolves it eagerly). */
data class TsgoCompletion(val label: String, val kind: Int?, val detail: String?, val sortText: String?)

/** A diagnostic ([severity] 1 error, 2 warning, 3 information, 4 hint; [code] as tsgo reports it). */
data class TsgoDiagnostic(val range: TsgoTextRange, val severity: Int?, val code: String?, val message: String)

@OptIn(ExperimentalAtomicApi::class)
class TsgoLanguageService private constructor(
    /** The tsconfig's normalized absolute path (the project's id). */
    val configFileName: String,
    /** The ported program. */
    val program: Program,
    /** tsconfig parse errors, `TSnnnn: message`. */
    val configDiagnostics: List<String>,
    private val service: XtscLanguageService,
    private val fs: FS,
    private val utf8Positions: Boolean,
) {

    private val ctx = com.xemantic.typescript.tsgo.go.context.background()
    private val requestIds = AtomicLong(0)

    companion object {

        /**
         * Opens [configFileName] (an absolute tsconfig.json path) over [fs] as a configured project of the
         * language server: [libDirectory] as in [TsgoProject.open], the client described by
         * [initializeParams] and [configuration] (see the class documentation).
         */
        fun open(
            configFileName: String,
            fs: FS = TsgoProject.diskFS(),
            libDirectory: String? = null,
            initializeParams: String = "{\"capabilities\":{}}",
            configuration: String = "",
        ): TsgoLanguageService = onGoStack {
            TsgoProject.init()
            val name = com.xemantic.typescript.tsgo.tspath.normalizePath(GoString.fromUtf16(configFileName))
            val lib = libDirectory?.let { com.xemantic.typescript.tsgo.tspath.normalizePath(GoString.fromUtf16(it)) } ?: ""
            val (program, pool, errs) = xtscOpenProgram(name, fs, lib)
            val diags = (0 until errs.len).map { i ->
                val d = errs[i]!!
                "TS${d.code}: " + GoString.toUtf16(d.localize(com.xemantic.typescript.tsgo.locale.Locale()))
            }
            if (program == null) throw TsgoApiException("$configFileName: ${diags.joinToString("; ")}")
            val (caps, cerr) = xtscResolveClientCapabilities(goStringToBytes(GoString.fromUtf16(initializeParams)))
            if (cerr != null) throw TsgoApiException("initialize params: " + GoString.toUtf16(cerr.error()))
            val config = if (configuration.isEmpty()) GoElem.BYTE.nilSlice else goStringToBytes(GoString.fromUtf16(configuration))
            val (prefs, perr) = xtscUserPreferences(config)
            if (perr != null) throw TsgoApiException("configuration: " + GoString.toUtf16(perr.error()))
            // handleInitialize: UTF-8 positions when the client offers them, else UTF-16.
            val utf8 = caps!!.general.positionEncodings.let { s -> (0 until s.len).any { s[it] == PositionEncodingKindUTF8 } }
            val proj = com.xemantic.typescript.tsgo.project.Project(Path(name), program, pool)
            // The snapshot reads files as the program does: the bundled libraries through the bundled FS.
            val snapshotFs = if (lib.isEmpty()) com.xemantic.typescript.tsgo.bundled.wrapFS(fs)!! else fs
            val snapshot = com.xemantic.typescript.tsgo.project.Snapshot(
                1uL,
                com.xemantic.typescript.tsgo.project.ProjectCollection(mapOf(name to proj)),
                snapshotFs,
                prefs,
                if (utf8) PositionEncodingKindUTF8 else PositionEncodingKindUTF16,
            )
            TsgoLanguageService(GoString.toUtf16(name), program, diags, XtscLanguageService(snapshot, proj, caps), snapshotFs, utf8)
        }
    }

    // ------------------------------------------------------------------ the raw protocol

    /**
     * One language-server request: [method] (`textDocument/hover`, `textDocument/definition`,
     * `textDocument/typeDefinition`, `textDocument/references`, `textDocument/implementation`,
     * `textDocument/completion`, `textDocument/signatureHelp`, `textDocument/documentHighlight`,
     * `textDocument/diagnostic`) with its JSON [paramsJson], answered with the JSON result the server
     * sends (`null` for none). A failed request throws [TsgoApiException] with the server's error message.
     */
    fun request(method: String, paramsJson: String): String = onGoStack {
        val result = requestValue(method, paramsJson)
        val (bytes, merr) = xtscMarshal(result)
        if (merr != null) throw TsgoApiException(GoString.toUtf16(merr.error()))
        GoString.toUtf16(goBytesToString(bytes))
    }

    private fun requestValue(method: String, paramsJson: String): Any? = recovered {
        val id = "xtsc-" + requestIds.addAndFetch(1L)
        val (result, err) = service.xtscLSRequest(ctx, GoString.fromUtf16(id), GoString.fromUtf16(method), goStringToBytes(GoString.fromUtf16(paramsJson)))
        if (err != null) throw TsgoApiException(GoString.toUtf16(err.error()))
        result
    }

    // ------------------------------------------------------------------ typed queries

    /** The hover at [offset] of [fileName] (`textDocument/hover`), or null. */
    fun hover(fileName: String, offset: Int): TsgoHover? = onGoStack {
        val r = requestValue("textDocument/hover", positionParams(fileName, offset)) as HoverOrNull
        val h = r.hover ?: return@onGoStack null
        val c = h.contents
        val (text, kind) = when {
            c.markupContent != null -> GoString.toUtf16(c.markupContent!!.value) to GoString.toUtf16(c.markupContent!!.kind.value)
            c.string != null -> GoString.toUtf16(c.string!!.value) to "plaintext"
            else -> "" to "plaintext"
        }
        TsgoHover(text, kind, h.range?.let { textRange(fileName, it) })
    }

    /** Where the symbol at [offset] of [fileName] is defined (`textDocument/definition`). */
    fun definition(fileName: String, offset: Int): List<TsgoLocation> = onGoStack {
        val r = requestValue("textDocument/definition", positionParams(fileName, offset)) as LocationOrLocationsOrDefinitionLinksOrNull
        when {
            r.location != null -> listOf(location(r.location!!))
            r.locations != null -> r.locations!!.value.let { s -> List(s.len) { location(s[it]) } }
            r.definitionLinks != null -> r.definitionLinks!!.value.let { s ->
                List(s.len) { i ->
                    val l = s[i]!!
                    val f = GoString.toUtf16(l.targetUri.fileName())
                    TsgoLocation(f, textRange(f, l.targetSelectionRange))
                }
            }
            else -> emptyList()
        }
    }

    /** The references to the symbol at [offset] of [fileName] (`textDocument/references`). */
    fun references(fileName: String, offset: Int, includeDeclaration: Boolean = true): List<TsgoLocation> = onGoStack {
        val params = positionParams(fileName, offset).dropLast(1) + ",\"context\":{\"includeDeclaration\":$includeDeclaration}}"
        val r = requestValue("textDocument/references", params) as LocationsOrNull
        r.locations?.value?.let { s -> List(s.len) { location(s[it]) } } ?: emptyList()
    }

    /** The completions at [offset] of [fileName] (`textDocument/completion`, invoked). */
    fun completions(fileName: String, offset: Int): List<TsgoCompletion> = onGoStack {
        val params = positionParams(fileName, offset).dropLast(1) + ",\"context\":{\"triggerKind\":1}}"
        val r = requestValue("textDocument/completion", params) as CompletionItemsOrListOrNull
        val items = r.list?.items ?: r.items?.value ?: GoElem.ref<CompletionItem?>().nilSlice
        List(items.len) { i ->
            val it = items[i]!!
            TsgoCompletion(
                GoString.toUtf16(it.label),
                it.kind?.value?.value?.toInt(),
                it.detail?.value?.let { d -> GoString.toUtf16(d) },
                it.sortText?.value?.let { d -> GoString.toUtf16(d) },
            )
        }
    }

    /** All diagnostics of [fileName] — syntactic, semantic, suggestion, declaration (`textDocument/diagnostic`). */
    fun diagnostics(fileName: String): List<TsgoDiagnostic> = onGoStack {
        val r = requestValue("textDocument/diagnostic", "{\"textDocument\":{\"uri\":${quote(uri(fileName))}}}") as
            RelatedFullDocumentDiagnosticReportOrUnchangedDocumentDiagnosticReport
        val items = r.fullDocumentDiagnosticReport?.items ?: return@onGoStack emptyList()
        List(items.len) { i ->
            val d = items[i]!!
            val code = d.code?.let { c -> c.integer?.value?.toString() ?: c.string?.value?.let { s -> GoString.toUtf16(s) } }
            val message = d.message.let { m -> m.string?.value ?: m.markupContent?.value ?: "" }
            TsgoDiagnostic(textRange(fileName, d.range), d.severity?.value?.value?.toInt(), code, GoString.toUtf16(message))
        }
    }

    // ------------------------------------------------------------------ positions

    private class LineMap(val text: String) {
        val starts: IntArray = run {
            val out = ArrayList<Int>()
            out += 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\r') {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    out += i + 1
                } else if (c == '\n') out += i + 1
                i++
            }
            out.toIntArray()
        }

        fun line(offset: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val m = (lo + hi + 1) ushr 1
                if (starts[m] <= offset) lo = m else hi = m - 1
            }
            return lo
        }
    }

    private val lineMaps = HashMap<String, LineMap>()
    private val lineMapsLock = com.xemantic.typescript.tsgo.go.sync.Mutex()

    private fun lineMap(fileName: String): LineMap {
        lineMapsLock.lock()
        try {
            lineMaps[fileName]?.let { return it }
        } finally {
            lineMapsLock.unlock()
        }
        val (text, ok) = fs.readFile(GoString.fromUtf16(fileName))
        if (!ok) throw TsgoApiException("file not found: $fileName")
        val map = LineMap(GoString.toUtf16(text))
        lineMapsLock.lock()
        try {
            return lineMaps.getOrPut(fileName) { map }
        } finally {
            lineMapsLock.unlock()
        }
    }

    /** An LSP `{line, character}` of a UTF-16 offset, in the session's encoding. */
    private fun position(fileName: String, offset: Int): String {
        val map = lineMap(fileName)
        val line = map.line(offset)
        val start = map.starts[line]
        val character = if (utf8Positions) map.text.substring(start, offset).encodeToByteArray().size else offset - start
        return "{\"line\":$line,\"character\":$character}"
    }

    private fun offset(fileName: String, line: Int, character: Int): Int {
        val map = lineMap(fileName)
        if (line >= map.starts.size) return map.text.length
        val start = map.starts[line]
        if (!utf8Positions) return minOf(start + character, map.text.length)
        var bytes = 0
        var i = start
        while (i < map.text.length && bytes < character) {
            val cp = map.text[i]
            bytes += when {
                cp.code < 0x80 -> 1
                cp.code < 0x800 -> 2
                cp.isHighSurrogate() -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return i
    }

    private fun textRange(fileName: String, r: Range): TsgoTextRange =
        TsgoTextRange(offset(fileName, r.start.line.toInt(), r.start.character.toInt()), offset(fileName, r.end.line.toInt(), r.end.character.toInt()))

    private fun location(l: Location): TsgoLocation {
        val f = GoString.toUtf16(l.uri.fileName())
        return TsgoLocation(f, textRange(f, l.range))
    }

    private fun uri(fileName: String): String =
        GoString.toUtf16(com.xemantic.typescript.tsgo.ls.lsconv.fileNameToDocumentURI(GoString.fromUtf16(fileName)).value)

    private fun positionParams(fileName: String, offset: Int): String =
        "{\"textDocument\":{\"uri\":${quote(uri(fileName))}},\"position\":${position(fileName, offset)}}"

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (c.code < 0x20) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }

    /** A handler's panic as the error response the server's `recover` turns it into. */
    private fun <R> recovered(block: () -> R): R = try {
        block()
    } catch (p: com.xemantic.typescript.tsgo.runtime.GoPanic) {
        val v = p.value
        val text = when (v) {
            is GoError -> GoString.toUtf16(v.error())
            is String -> GoString.toUtf16(v)
            else -> v.toString()
        }
        throw TsgoApiException("panic: $text", panicked = true)
    }
}

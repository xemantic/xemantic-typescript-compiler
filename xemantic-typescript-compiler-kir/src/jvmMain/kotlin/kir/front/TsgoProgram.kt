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

package com.xemantic.typescript.compiler.kir.front

import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.tsgo.ast.SourceFileParseOptions
import com.xemantic.typescript.tsgo.ast.localize
import com.xemantic.typescript.tsgo.compiler.CompilerHost
import com.xemantic.typescript.tsgo.compiler.Program
import com.xemantic.typescript.tsgo.compiler.ProgramOptions
import com.xemantic.typescript.tsgo.compiler.getDiagnosticsOfAnyProgram
import com.xemantic.typescript.tsgo.compiler.newCachedFSCompilerHost
import com.xemantic.typescript.tsgo.core.ScriptKind
import com.xemantic.typescript.tsgo.go.io.fs.FileInfo
import com.xemantic.typescript.tsgo.go.io.fs.WalkDirFunc
import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.vfs.Entries
import com.xemantic.typescript.tsgo.vfs.FS
import java.util.concurrent.ConcurrentHashMap
import com.xemantic.typescript.tsgo.ast.Diagnostic as TsgoDiagnostic
import com.xemantic.typescript.tsgo.ast.SourceFile as TsgoSourceFile

// (TSGO.4-c) Building a program with the PORTED tsgo checker, for the KIR front end.
//
// This is what `api.XtscOpenProgram` does for the type oracle — the tsconfig is
// parsed by tsgo's own `tsoptions`, the program is tsgo's `compiler.NewProgram`
// with the API's three-checker pool — with ONE difference that is the whole
// reason it is restated here: the compiler host caches the bundled LIBRARY
// files across programs. A KIR test compiles one small program after another,
// and without the cache each of them re-parses ~4 MB of `lib.*.d.ts`; tsgo's
// own project system and test harness share parsed library files across
// programs in exactly this way (`project/parsecache.go`,
// `harnessutil.cachedCompilerHost`), so a shared lib `SourceFile` is a
// supported state of the port and not a trick of this file.

/** Everything the KIR front end needs from one tsgo program. */
internal class TsgoProgram(
    val program: Program,
    /** tsconfig parse errors, which `getDiagnosticsOfAnyProgram` does not include. */
    val configErrors: List<TsgoDiagnostic>,
)

internal object TsgoPrograms {

    @Volatile private var initialized = false

    /** Go's package initialization for what the program reaches (`parser` installs its JSDoc hook). */
    @Synchronized
    private fun init() {
        if (initialized) return
        com.xemantic.typescript.tsgo.parser.goInitPackage()
        initialized = true
    }

    /**
     * The checker recurses as deep as Go's growable stacks allow, so every tsgo
     * call of the front end runs on ONE thread with a 1 GB stack — the reserve is
     * virtual and committed lazily, as `runWithDeepStack` does for `-core`.
     */
    fun <R> onDeepStack(block: () -> R): R {
        // `-core`'s Type and Symbol id sequences are THREAD-LOCAL and the facts
        // mint `-core` values on this thread, so the caller's counters are
        // handed over and back exactly as `runWithDeepStack` hands them: a
        // sequence restarting at 1 would collide with the singleton intrinsics,
        // and `CheckedFacts` keys its renderings by `Type.id`.
        val typeId = com.xemantic.typescript.compiler.Type.captureThreadId()
        val symbolIds = com.xemantic.typescript.compiler.Symbol.captureThreadIds()
        var result: Result<R>? = null
        var advanced: Pair<Int, Pair<Int, Int>>? = null
        val thread = Thread(null, {
            com.xemantic.typescript.compiler.Type.restoreThreadId(typeId)
            com.xemantic.typescript.compiler.Symbol.restoreThreadIds(symbolIds)
            result = runCatching(block)
            advanced = com.xemantic.typescript.compiler.Type.captureThreadId() to
                com.xemantic.typescript.compiler.Symbol.captureThreadIds()
        }, "xtsc-kir-tsgo", 1L shl 30)
        thread.start()
        thread.join()
        advanced?.let { (types, symbols) ->
            com.xemantic.typescript.compiler.Type.restoreThreadId(types)
            com.xemantic.typescript.compiler.Symbol.restoreThreadIds(symbols)
        }
        return result!!.getOrThrow()
    }

    /** Opens the project of [configFileName] (absolute) over [fs], bundled libraries included. */
    fun open(configFileName: String, fs: FS): TsgoProgram {
        init()
        val name = com.xemantic.typescript.tsgo.tspath.normalizePath(GoString.fromUtf16(configFileName))
        val wrapped = com.xemantic.typescript.tsgo.bundled.wrapFS(fs)
        val libPath = com.xemantic.typescript.tsgo.bundled.libPath()
        val cwd = com.xemantic.typescript.tsgo.tspath.getDirectoryPath(name)
        val host = LibCachingHost(newCachedFSCompilerHost(cwd, wrapped, libPath, null, null)!!, libPath)
        val (config, errors) = com.xemantic.typescript.tsgo.tsoptions.getParsedCommandLineOfConfigFile(
            name, null, null, host, null
        )
        val configErrors = List(errors.len) { errors[it]!! }
        check(config != null) {
            "tsgo could not read '$configFileName': " + configErrors.joinToString("; ") { message(it) }
        }
        val program = com.xemantic.typescript.tsgo.compiler.newProgram(
            ProgramOptions(
                host = host,
                config = config,
                useSourceOfProjectReference = true,
                // SINGLE-THREADED: the front end runs on one deep-stack thread,
                // and the pool's query checker serializes every file anyway. A
                // parallel diagnostics pass parks N goroutines on that one
                // checker's lock — and one that THROWS never releases it, so a
                // checker exception surfaced as a silent deadlock (measured)
                // where on this thread it propagates.
                singleThreaded = com.xemantic.typescript.tsgo.core.TSTrue,
                createCheckerPool = { p -> com.xemantic.typescript.tsgo.api.XtscCheckerPool(program = p) },
            )
        )!!
        program.bindSourceFiles()
        return TsgoProgram(program, configErrors)
    }

    /**
     * Every diagnostic tsgo's CLI reports for the program — config, syntactic,
     * program, global and semantic, in that order (`GetDiagnosticsOfAnyProgram`)
     * — as `-core` [Diagnostic]s, which is what the KIR API hands out.
     *
     * [fileNameOf] renames a tsgo file to the name the caller gave it.
     */
    fun diagnostics(opened: TsgoProgram, fileNameOf: (String) -> String): List<Diagnostic> {
        val program = opened.program
        val ctx = com.xemantic.typescript.tsgo.go.context.background()
        val all = getDiagnosticsOfAnyProgram(
            ctx, program, null, false,
            { c, f -> program.getBindDiagnostics(c, f) },
            { c, f -> if (f != null) program.getSemanticDiagnostics(c, f) else ownSemanticDiagnostics(opened, c) },
        )
        return (opened.configErrors + List(all.len) { all[it]!! }).map { convert(it, fileNameOf) }
    }

    /**
     * The semantic diagnostics of every file but the DEFAULT LIBRARIES.
     *
     * tsgo's CLI checks the bundled `lib.*.d.ts` too (`skipDefaultLibCheck`
     * is off by default), and they are clean — so checking them answers
     * nothing and was most of a small program's front-end wall (measured:
     * ~300 ms of a one-file KIR compile). A program's own `.d.ts` files, and
     * a package's under `node_modules`, are still checked.
     */
    private fun ownSemanticDiagnostics(
        opened: TsgoProgram,
        ctx: com.xemantic.typescript.tsgo.go.context.Context?,
    ): GoSlice<TsgoDiagnostic?> {
        val program = opened.program
        val files = program.getSourceFiles()
        var out: GoSlice<TsgoDiagnostic?> = com.xemantic.typescript.tsgo.runtime.GoElem.ref<TsgoDiagnostic?>().nilSlice
        for (i in 0 until files.len) {
            val file = files[i] ?: continue
            if (!isProgramFile(program, file)) continue
            out = out.appendSlice(program.getSemanticDiagnostics(ctx, file))
        }
        return out
    }

    private fun convert(d: TsgoDiagnostic, fileNameOf: (String) -> String): Diagnostic {
        val file = d.file
        val category = when (d.category.value) {
            0 -> DiagnosticCategory.Warning
            1 -> DiagnosticCategory.Error
            2 -> DiagnosticCategory.Suggestion
            else -> DiagnosticCategory.Message
        }
        if (file == null) return Diagnostic(message(d), category, d.code)
        val text = GoString.toUtf16(file.text)
        val offsets = Utf8Offsets(text)
        val start = offsets.toUtf16(d.loc.pos())
        val end = offsets.toUtf16(d.loc.end())
        var line = 0
        var lineStart = 0
        for (i in 0 until start) if (text[i] == '\n') { line++; lineStart = i + 1 }
        return Diagnostic(
            message = message(d),
            category = category,
            code = d.code,
            fileName = fileNameOf(GoString.toUtf16(file.fileName)),
            line = line + 1,
            character = start - lineStart + 1,
            start = start,
            length = end - start,
        )
    }

    private fun message(d: TsgoDiagnostic): String =
        GoString.toUtf16(d.localize(com.xemantic.typescript.tsgo.locale.Locale()))

    /** Whether [file] is one of the program's own files rather than a default library. */
    fun isProgramFile(program: Program, file: TsgoSourceFile): Boolean =
        !program.isSourceFileDefaultLibrary(file.path())

}

/**
 * A compiler host that shares parsed bundled-LIBRARY files across programs.
 *
 * Keyed by the parse options and the text, as the harness's
 * `sourceFileCache` is: the same text parsed under the same options is the
 * same tree, so the second program gets the first one's `SourceFile` (bound
 * once, by whichever program binds it first). Program files are never cached —
 * each compile parses them fresh.
 */
private class LibCachingHost(
    private val delegate: CompilerHost,
    private val libPath: String,
) : CompilerHost by delegate {

    override fun getSourceFile(p0: SourceFileParseOptions): TsgoSourceFile? {
        if (!p0.fileName.startsWith(libPath)) return delegate.getSourceFile(p0)
        val (text, ok) = delegate.fs()!!.readFile(p0.fileName)
        if (!ok) return null
        val key = LibKey(p0.goCopy(), text)
        cache[key]?.let { return it }
        val kind: ScriptKind = com.xemantic.typescript.tsgo.core.getScriptKindFromFileName(p0.fileName)
        val parsed = com.xemantic.typescript.tsgo.parser.parseSourceFile(p0.goCopy(), text, kind)
            ?: return null
        return cache.putIfAbsent(key, parsed) ?: parsed
    }

    private class LibKey(val options: SourceFileParseOptions, val text: String) {
        override fun equals(other: Any?): Boolean =
            other is LibKey && options.goEquals(other.options) && text == other.text
        override fun hashCode(): Int = 31 * options.goHash() + text.hashCode()
    }

    companion object {
        private val cache = ConcurrentHashMap<LibKey, TsgoSourceFile>()
    }

}

/**
 * Byte offsets of a Go (UTF-8) string mapped onto the UTF-16 offsets of the
 * same text, which is what `-core`'s positions are.
 */
internal class Utf8Offsets(text: String) {

    private val ascii = text.all { it.code < 0x80 }

    private val table: IntArray? = if (ascii) null else {
        val out = IntArray(utf8Length(text) + 1)
        var byte = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            val width = when {
                pair -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            for (k in 0 until width) out[byte + k] = i
            byte += width
            i += if (pair) 2 else 1
        }
        out[byte] = text.length
        out
    }

    fun toUtf16(byteOffset: Int): Int = table?.let { it[byteOffset.coerceIn(0, it.size - 1)] } ?: byteOffset

    private fun utf8Length(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) { n += 4; i += 2; continue }
            n += when { c.code < 0x80 -> 1; c.code < 0x800 -> 2; else -> 3 }
            i++
        }
        return n
    }

}

/**
 * A read-only in-memory `vfs.FS` over absolute paths — what a single-file KIR
 * compile checks against: the file, and a synthesized `tsconfig.json` naming it.
 */
internal class InMemoryFS(files: Map<String, String>) : FS {

    private val files = files.entries.associate { GoString.fromUtf16(it.key) to GoString.fromUtf16(it.value) }

    private val dirs: Set<String> = this.files.keys.flatMap { f ->
        f.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }.map { it.ifEmpty { "/" } }
    }.toSet() + "/"

    override fun useCaseSensitiveFileNames(): Boolean = true
    override fun fileExists(p0: String): Boolean = p0 in files
    override fun readFile(p0: String): Tuple2<String, Boolean> =
        files[p0]?.let { Tuple2(it, true) } ?: Tuple2("", false)
    override fun directoryExists(p0: String): Boolean = p0 in dirs
    override fun getAccessibleEntries(p0: String): Entries {
        val prefix = if (p0.endsWith("/")) p0 else "$p0/"
        val children = (files.keys + dirs).filter {
            it.startsWith(prefix) && it.length > prefix.length && '/' !in it.substring(prefix.length)
        }
        return Entries(
            files = GoSlice.of(GoElem.STRING, *children.filter { it in files }
                .map { it.substring(prefix.length) }.sorted().toTypedArray()),
            directories = GoSlice.of(GoElem.STRING, *children.filter { it in dirs }
                .map { it.substring(prefix.length) }.sorted().toTypedArray()),
            symlinks = GoMap.make(com.xemantic.typescript.tsgo.runtime.goUnitElem),
        )
    }
    override fun realpath(p0: String): String = p0
    override fun stat(p0: String): FileInfo? = null
    override fun walkDir(p0: String, p1: WalkDirFunc?): GoError? = error("walkDir is not supported")
    override fun writeFile(p0: String, p1: String): GoError? = error("read-only")
    override fun appendFile(p0: String, p1: String): GoError? = error("read-only")
    override fun remove(p0: String): GoError? = error("read-only")
    override fun chtimes(p0: String, p1: Time, p2: Time): GoError? = error("read-only")

}

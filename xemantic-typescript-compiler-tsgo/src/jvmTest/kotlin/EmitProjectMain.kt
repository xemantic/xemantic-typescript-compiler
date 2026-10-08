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

import com.xemantic.typescript.tsgo.compiler.EmitOptions
import com.xemantic.typescript.tsgo.compiler.ProgramOptions
import com.xemantic.typescript.tsgo.core.CompilerOptions
import com.xemantic.typescript.tsgo.go.io.fs.FileInfo
import com.xemantic.typescript.tsgo.go.io.fs.WalkDirFunc
import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.vfs.Entries
import com.xemantic.typescript.tsgo.vfs.FS
import java.io.File
import kotlin.system.exitProcess

/**
 * (TSGO.3) a PROJECT emitted by the ported compiler, the second emit receipt (docs/goport-emit-oracle.md § 5):
 *
 * `EmitProjectMain <tsconfig.json> <outDir>`
 *
 * does what `tsc -p <project> --outDir <outDir>` does: parse the tsconfig with `outDir` given on the command
 * line, build a `compiler.Program` and `Emit` it, every output written through the compiler host's file
 * system to disk. `scripts/tsgo-emit-project.sh` runs it beside the tsgo 7.0.2 binary and `diff -r`s the two
 * output trees. Prints the emitted-file count and the diagnostic count (tsc's own sources: 78 and 65).
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("usage: EmitProjectMain <tsconfig.json> <outDir>")
        exitProcess(2)
    }
    var failure: Throwable? = null
    val th = Thread(null, {
        try {
            emitProject(File(args[0]).absoluteFile.normalize(), File(args[1]).absoluteFile.normalize())
        } catch (t: Throwable) {
            failure = t
        }
    }, "emit-project-deep-stack", 1L shl 30)
    th.start()
    th.join()
    failure?.let { throw it }
}

/** The real disk, readable everywhere and writable (Go byte strings as one char per byte). */
private class WritableDiskFS : FS {
    private val disk = DiskFS()
    override fun useCaseSensitiveFileNames(): Boolean = true
    override fun fileExists(p0: String): Boolean = disk.fileExists(p0)
    override fun readFile(p0: String): Tuple2<String, Boolean> = disk.readFile(p0)
    override fun directoryExists(p0: String): Boolean = disk.directoryExists(p0)
    override fun getAccessibleEntries(p0: String): Entries = disk.getAccessibleEntries(p0)
    override fun realpath(p0: String): String = p0
    override fun stat(p0: String): FileInfo? = null
    override fun walkDir(p0: String, p1: WalkDirFunc?): GoError? = error("walkDir not supported")
    override fun writeFile(p0: String, p1: String): GoError? {
        val f = File(GoString.toUtf16(p0))
        f.parentFile.mkdirs()
        f.writeBytes(p1.toByteArray(Charsets.ISO_8859_1))
        return null
    }
    override fun appendFile(p0: String, p1: String): GoError? = error("appendFile not supported")
    override fun remove(p0: String): GoError? = error("remove not supported")
    override fun chtimes(p0: String, p1: Time, p2: Time): GoError? = error("chtimes not supported")
}

private fun emitProject(config: File, outDir: File) {
    TsgoPort.init()
    val fs = com.xemantic.typescript.tsgo.bundled.wrapFS(WritableDiskFS())
    val cwd = GoString.fromUtf16(config.parentFile.path)
    val host = com.xemantic.typescript.tsgo.compiler.newCompilerHost(cwd, fs, com.xemantic.typescript.tsgo.bundled.libPath(), null, null)!!
    val cli = CompilerOptions().also { it.outDir = GoString.fromUtf16(outDir.path) }
    val (parsed, configDiags) = com.xemantic.typescript.tsgo.tsoptions.getParsedCommandLineOfConfigFile(GoString.fromUtf16(config.path), cli, null, host, null)
    val program = com.xemantic.typescript.tsgo.compiler.newProgram(ProgramOptions(host = host, config = parsed))!!
    val ctx = com.xemantic.typescript.tsgo.go.context.background()
    val result = program.emit(ctx, EmitOptions())!!
    var diags = configDiags.len
    diags += program.getConfigFileParsingDiagnostics().len + program.getProgramDiagnostics().len +
        program.getSyntacticDiagnostics(ctx, null).len + program.getSemanticDiagnostics(ctx, null).len +
        program.getGlobalDiagnostics(ctx).len
    val written = outDir.walkTopDown().count { it.isFile }
    println("EmitProjectMain: project=$config outDir=$outDir emitSkipped=${result.emitSkipped} files=$written diagnostics=$diags")
}

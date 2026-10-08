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

import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2

/**
 * tsgo's command line — `tsc` 7.0.2 — on the port ((TSGO.5), docs/goport-cli.md).
 *
 * [run] is `cmd/tsgo`'s `runMain` for the compiler: the ported `execute.CommandLine` (`internal/execute` and
 * `internal/execute/tsc`, mechanically ported) over tsgo's own OS file system (`vfs/osvfs`, ported) wrapped
 * for the bundled libraries, exactly as `cmd/tsgo/sys.go`'s `newSystem`. Everything tsc prints goes to
 * [out] (tsgo writes it all to stdout), and the result is tsc's exit status.
 *
 * Not ported yet: `--build` (`internal/execute/build`) and `--watch` (`internal/execute/watchmanager`); they
 * answer [EXIT_NOT_IMPLEMENTED] with a one-line explanation.
 */
object TsgoCli {

    /** `tsc.ExitStatusNotImplemented`. */
    const val EXIT_NOT_IMPLEMENTED: Int = 5

    /**
     * Runs `tsc [args]` in [currentDirectory] (absolute). [libDirectory] is the default-library directory;
     * null serves the bundled lib files, as a tsgo build with embedded libs does (the shipped npm binary
     * reads the `lib.*.d.ts` next to its executable instead — pass that directory to reproduce it exactly).
     * [isTerminal] and [terminalWidth] describe stdout (tsc's `--pretty` default and help layout);
     * [environment] answers `GetEnvironmentVariable`.
     */
    fun run(
        args: List<String>,
        currentDirectory: String,
        out: (ByteArray) -> Unit,
        libDirectory: String? = null,
        isTerminal: Boolean = false,
        terminalWidth: Int = 0,
        environment: (String) -> String? = { null },
    ): Int = onGoStack {
        TsgoProject.init()
        val writer = object : com.xemantic.typescript.tsgo.go.io.Writer {
            override fun write(p: GoSlice<Int>): Tuple2<Int, GoError?> {
                val bytes = ByteArray(p.len) { p[it].toByte() }
                out(bytes)
                return Tuple2(p.len, null)
            }
        }
        val goArgs = GoSlice.of(GoElem.STRING, *args.map { GoString.fromUtf16(it) }.toTypedArray())
        val fs = com.xemantic.typescript.tsgo.bundled.wrapFS(com.xemantic.typescript.tsgo.vfs.osvfs.fs())
        val lib = libDirectory?.let { com.xemantic.typescript.tsgo.tspath.normalizePath(GoString.fromUtf16(it)) }
            ?: com.xemantic.typescript.tsgo.bundled.libPath()
        try {
            com.xemantic.typescript.tsgo.execute.xtscCommandLine(
                goArgs, fs, lib, GoString.fromUtf16(currentDirectory), writer, isTerminal, terminalWidth,
            ) { name -> GoString.fromUtf16(environment(GoString.toUtf16(name)) ?: "") }
        } catch (e: NotImplementedError) {
            // A refused declaration of the port (`--build`, `--watch` are partial stubs): say so instead of a
            // stack trace. Any other NotImplementedError is a defect and propagates.
            val message = e.message ?: ""
            if ("goport: refused" !in message) throw e
            val what = message.substringAfterLast("internal/").substringBefore(' ')
            out("error: this tsc mode is not available in the port yet ($what)\n".encodeToByteArray())
            EXIT_NOT_IMPLEMENTED
        }
    }
}

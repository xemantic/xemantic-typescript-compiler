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

package com.xemantic.typescript.tsgo.cli

import com.xemantic.typescript.tsgo.TsgoCli
import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * `tsc` 7.0.2 on the JVM ((TSGO.5), docs/goport-cli.md): `java -cp … com.xemantic.typescript.tsgo.cli.TsgoMainKt
 * <tsc arguments>` prints what the tsgo binary prints and exits with its status. `--lsp` and `--api` (tsgo's
 * other two modes) are not this entry's: the language server is `xemantic-typescript-compiler-lsp`.
 *
 * The default libraries are the bundled ones; `XTSC_TSGO_LIB_DIR=<dir>` reads the `lib.*.d.ts` of `<dir>`
 * instead, as the shipped npm binary reads those next to its executable (the CLI differential sets it to
 * `tools/tsgo-7.0.2/lib`, so file names in diagnostics agree byte for byte).
 */
fun main(args: Array<String>) {
    if (args.isNotEmpty() && (args[0] == "--lsp" || args[0] == "--api")) {
        System.err.println("${args[0]} is not served by this entry point (the language server is xemantic-typescript-compiler-lsp)")
        exitProcess(1)
    }
    val stdout = BufferedOutputStream(FileOutputStream(FileDescriptor.out), 1 shl 16)
    // tsgo asks `term.IsTerminal(stdout)`. A JVM has a Console only for a terminal (before JDK 22), and since JDK 22
    // says so through Console.isTerminal (read reflectively: the module compiles against an older API level).
    val isTerminal = System.console()?.let { c ->
        runCatching { c.javaClass.getMethod("isTerminal").invoke(c) as Boolean }.getOrDefault(true)
    } ?: false
    val status = TsgoCli.run(
        args = args.toList(),
        currentDirectory = java.io.File(System.getProperty("user.dir")).absolutePath,
        out = { stdout.write(it) },
        libDirectory = System.getenv("XTSC_TSGO_LIB_DIR")?.takeIf { it.isNotEmpty() },
        isTerminal = isTerminal,
        terminalWidth = if (isTerminal) (System.getenv("COLUMNS")?.toIntOrNull() ?: 80) else 0,
        environment = { System.getenv(it) },
    )
    stdout.flush()
    if (com.xemantic.typescript.tsgo.go.os.GoSyscall.statsOn) System.err.println(com.xemantic.typescript.tsgo.go.os.GoSyscall.stats())
    exitProcess(status)
}

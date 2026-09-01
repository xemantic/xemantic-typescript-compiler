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

import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlin.system.exitProcess

/**
 * The stdio entry point: one [XtscLanguageServer] session over this process's
 * stdin/stdout, exiting with the code the LSP spec asks for (0 after a clean
 * `shutdown`/`exit`, 1 otherwise).
 *
 * stdout belongs to the PROTOCOL — nothing else may ever be printed to it, or
 * the client reads the stray bytes as a frame header and the session dies.
 * Deliberately minimal: distribution (native image wiring, argument parsing)
 * is (LSP.2).
 */
public fun main() {
    val source = System.`in`.asSource().buffered()
    val sink = System.out.asSink().buffered()
    exitProcess(XtscLanguageServer().serve(source, sink))
}

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

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (SERVE.2) round 873 — the one funnel a served request resolves through.
 *
 * `ProjectCompiler.build` absolutizes both the project path and a `--outDir`
 * override through [Vfs.resolveAbsolute] before doing anything with either, so
 * this variable is what makes a daemon request resolve exactly as the same
 * command line would have resolved in the user's own shell. The alternative —
 * each client rewriting the arguments it believes are paths — was tried and
 * cannot work: a client does not parse the compiler's options.
 */
class SystemVfsWorkingDirectoryTest {

    @Test
    fun `an installed directory is what a relative path resolves against`() {
        val before = SystemVfs.workingDirectory
        try {
            SystemVfs.workingDirectory = "/somewhere/else"
            assert(SystemVfs.resolveAbsolute("proj") == "/somewhere/else/proj")
            assert(SystemVfs.resolveAbsolute(".") == "/somewhere/else")
            assert(SystemVfs.resolveAbsolute("./src/a.ts") == "/somewhere/else/src/a.ts")
        } finally {
            SystemVfs.workingDirectory = before
        }
    }

    @Test
    fun `an absolute path is untouched by the installed directory`() {
        val before = SystemVfs.workingDirectory
        try {
            SystemVfs.workingDirectory = "/somewhere/else"
            assert(SystemVfs.resolveAbsolute("/etc/passwd") == "/etc/passwd")
        } finally {
            SystemVfs.workingDirectory = before
        }
    }

    // Null is the one-shot CLI, where the process's own directory IS the user's.
    @Test
    fun `negative control - with nothing installed the process directory is used`() {
        val before = SystemVfs.workingDirectory
        try {
            SystemVfs.workingDirectory = null
            val resolved = SystemVfs.resolveAbsolute("proj")
            assert(resolved.startsWith("/"))
            assert(resolved.endsWith("/proj"))
            assert(!resolved.startsWith("/somewhere/else"))
        } finally {
            SystemVfs.workingDirectory = before
        }
    }
}

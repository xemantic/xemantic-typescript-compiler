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
import kotlin.test.Test
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.io.buffered

class TriageTest {
    @Test fun t() {
        val sb = StringBuilder()
        for (n in listOf("noSymbolForMergeCrash","mergeSymbolRexportFunction","checkerInitializationCrash","mergeSymbolReexportedTypeAliasInstantiation","ensureNoCrashExportAssignmentDefineProperrtyPotentialMerge")) {
            try {
                val p = Path("typescript-repo/tests/cases/compiler/$n.ts")
                val src = SystemFileSystem.source(p).buffered().readString()
                val res = TypeScriptCompiler().compile(src, "$n.ts")
                sb.appendLine("=== $n : ${res.diagnostics.size} diagnostics ===")
                for (d in res.diagnostics) sb.appendLine("  TS${d.code} ${d.fileName}:${(d.line ?: -1)+1}:${(d.character ?: -1)+1} ${d.message}")
            } catch (e: Throwable) { sb.appendLine("=== $n CRASHED: ${e::class.simpleName}: ${e.message}") }
        }
        val out = SystemFileSystem.sink(Path("/tmp/triage_out.txt")).buffered()
        out.writeString(sb.toString()); out.close()
    }
}

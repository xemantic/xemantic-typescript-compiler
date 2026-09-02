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

package com.xemantic.typescript.compiler.externals

import com.xemantic.kotlin.test.assert
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test

/**
 * (EXT.13) THE FOURTH FIXTURE-LADDER RUNG: the generator over `typescript.d.ts`
 * — TypeScript's own public API, one `declare namespace ts { … }` of ~1,300
 * root declarations (implicitly exported: an ambient namespace's members
 * are, whatever `export` says elsewhere in the body) with the nested
 * `server` / `server.protocol` / `server.typingsInstaller` / `JsTyping` /
 * `ScriptSnapshot` namespaces, `export import X = ts.X` lines and a final
 * `export = ts` — gated by the metadata compile.
 *
 * The file is NOT embedded (588 KB, and provisioned by nothing in this repo:
 * `build/tools/tsc-ref/node_modules/typescript/lib/typescript.d.ts` is the
 * reference tsc's own copy and vanishes on a `clean`). It is read from
 * `XTSC_TYPESCRIPT_DTS` (an environment variable — Gradle does not forward
 * `-D` to the test JVM) or the default path, and when it is absent the test
 * prints `SKIPPED: …` and returns: the RECEIPT for this rung is the session
 * note's census (declarations by kind, markers by category, the compile
 * verdict), and the HERMETIC pins of every namespace mechanism are in
 * `KotlinExternalsGeneratorTest` — this gate is the sensitivity arm that
 * grades those mechanisms against a real 11,448-line surface, not the
 * coverage.
 *
 * What is asserted: the compile-gate variant COMPILES (`errors.isEmpty()`).
 * The checker's own diagnostics are NOT asserted empty — the file reports one
 * false positive (TS1039 at line 2610, `protected readonly latestDistTag =
 * "latest"` in an ambient abstract class, a separate core item) and
 * [KotlinExternals.errors] is what a caller reads, so the gate records the
 * count instead of gating on it.
 */
class KotlinExternalsTypescriptGateTest {

    private val defaultPath = "build/tools/tsc-ref/node_modules/typescript/lib/typescript.d.ts"

    /**
     * The file, or null (printed) when absent. The default is resolved against
     * the REPO ROOT: a module's test worker runs in the MODULE directory, so
     * a bare relative default read `<module>/build/tools/…`, found nothing
     * and took the skip branch — a green gate that had looked at the wrong
     * place (rounds 853/873's frozen-instrument shape). The working directory
     * and its parent are both tried, and the skip line names the absolute
     * paths it looked at.
     */
    private fun typescriptDts(): Path? {
        val explicit = System.getenv("XTSC_TYPESCRIPT_DTS")
        val candidates =
            if (explicit != null) listOf(Path.of(explicit))
            else listOf(Path.of(defaultPath).toAbsolutePath(), Path.of("..", defaultPath).toAbsolutePath().normalize())
        val path = candidates.firstOrNull { Files.isRegularFile(it) }
        if (path == null) {
            println("SKIPPED: typescript.d.ts not present at ${candidates.joinToString(" or ")}")
            return null
        }
        return path
    }

    @Test
    fun `typescript dts generates and the generated kotlin compiles`() {
        val path = typescriptDts() ?: return
        val result = generateKotlinExternals("/typescript/lib/typescript.d.ts", path.readText())
        val check = compileCheck(result.compileCheckSource)
        val compileErrors = check.errors
        val diagnosticCount = result.errors.size
        println("typescript.d.ts: ${result.kotlin.lines().size} generated lines, $diagnosticCount checker errors, ${compileErrors.size} compile errors")
        val out = System.getenv("XTSC_TYPESCRIPT_DTS_OUT")
        if (out != null) {
            Files.createDirectories(Path.of(out))
            Files.writeString(Path.of(out, "generated.kt"), result.kotlin)
            Files.writeString(Path.of(out, "compile-check.kt"), result.compileCheckSource)
            Files.writeString(Path.of(out, "compile-errors.txt"), compileErrors.joinToString("") { "$it\n" })
        }
        assert(compileErrors.isEmpty())
        assert(check.successful)
    }

}

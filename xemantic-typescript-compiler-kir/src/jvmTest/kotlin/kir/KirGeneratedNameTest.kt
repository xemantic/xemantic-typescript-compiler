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

package com.xemantic.typescript.compiler.kir

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.kir.emit.GeneratedProgramClasspath
import com.xemantic.typescript.compiler.kir.emit.runGeneratedProgram
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * (KIR.NATIVE.2) A TypeScript top-level function may be named like one the lowering GENERATES.
 *
 * The program's entry is `main` and each file's initializer is `moduleInit`, both
 * zero-argument top-level functions; a TypeScript `function main()` had the same
 * JVM signature. The JVM backend reported a platform declaration clash (which the
 * emitter then lost — `KotlinIrEmitterTest` pins that half), and the Kotlin/Native
 * plugin found two entries. Expected output is node's for the same source.
 */
class KirGeneratedNameTest {

    private fun run(source: String): String {
        val project = Files.createTempDirectory("xtsc-kir-generated-name")
        val output = Files.createTempDirectory("xtsc-kir-generated-name-out")
        try {
            project.resolve("tsconfig.json").writeText(
                """{"compilerOptions":{"strict":true,"target":"ES2020","module":"ESNext","moduleResolution":"bundler"},"include":["src/**/*.ts"]}"""
            )
            val target = project.resolve("src/main.ts")
            target.parent.createDirectories()
            target.writeText(source.trimIndent() + "\n")
            val compilation = compileTypeScriptProjectToJvm(project.toString(), "main.ts", output)
            if (!compilation.successful) return "NOT COMPILED: $compilation"
            val run = runGeneratedProgram(output, compilation.mainClass, GeneratedProgramClasspath.minimal())
            return run.stdout + run.stderr
        } finally {
            project.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

    /** node prints `user main` — the program's own `main`, called from its module body. */
    @Test
    fun `a top-level function named main`() {
        val out = run(
            """
            function main(): void { console.log("user main") }
            main()
            """
        )
        assert(out == "user main\n")
    }

    /** node prints `user init` — the same for the per-file initializer's name. */
    @Test
    fun `a top-level function named moduleInit`() {
        val out = run(
            """
            function moduleInit(): void { console.log("user init") }
            moduleInit()
            """
        )
        assert(out == "user init\n")
    }

}

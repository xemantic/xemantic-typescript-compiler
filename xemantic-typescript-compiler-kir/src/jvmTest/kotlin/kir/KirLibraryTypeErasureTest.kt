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
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * (KIR.LOWER.2) A named LIBRARY type the runtime has no class for is REFUSED, not erased to the property bag.
 *
 * An instantiation (`Promise<string>`) carries no symbol of its own — its target does — so `ErasedTypes` read
 * its declaration as null, i.e. "an anonymous object type", and erased it to the bag: `p.then(…)` compiled into a
 * bag-slot call on a value that is no bag. The target-symbol fallback gives it its lib declaration, which is not
 * the program's own, so it refuses. (`WeakMap` is NOT such a type: the runtime maps it to `JsMap` by design.)
 */
class KirLibraryTypeErasureTest {

    private fun report(source: String): Pair<Boolean, String> {
        val project = Files.createTempDirectory("xtsc-kir-library-type")
        val output = Files.createTempDirectory("xtsc-kir-library-type-out")
        try {
            project.resolve("tsconfig.json").writeText(
                """{"compilerOptions":{"strict":true,"target":"ES2020","module":"ESNext","moduleResolution":"bundler"},"include":["src/**/*.ts"]}"""
            )
            val target = project.resolve("src/main.ts")
            target.parent.createDirectories()
            target.writeText(source.trimIndent() + "\n")
            val compilation = compileTypeScriptProjectToJvm(project.toString(), "main.ts", output)
            return compilation.successful to compilation.toString()
        } finally {
            project.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `negative control - a Promise-typed parameter is refused by its type`() {
        val (compiled, report) = report(
            """
            function f(p: Promise<string>): void { p.then((s) => console.log(s)) }
            console.log("ok")
            """
        )
        assert(!compiled)
        assert(report.contains("cannot map the type 'Promise<string>' (Reference)"))
    }

}

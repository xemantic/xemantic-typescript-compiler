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

package com.xemantic.typescript.compiler.cli

import com.xemantic.kotlin.test.assert
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.fail

/**
 * What the shipped GraalVM image is built from. Since (TSGO.8-b) that is the tsgo
 * port's own task, `:xemantic-typescript-compiler-tsgo:nativeImage`, whose entry is
 * the ported tsc command line (`TsgoMainKt`); this module's lean JVM entry is not
 * imaged.
 *
 * The class is RESOLVED, not only compared: a rename that misses the build script
 * fails here, in the ordinary suite, with no GraalVM needed. What this cannot see
 * is whether the image builds — `.github/workflows/native.yml` does that, and
 * replays tsgo 7.0.2 through it.
 */
class NativeImageEntryPointTest {

    @Test
    fun `the native image is built from the ported tsc command line`() {
        assert(nativeImageMainClass() == "com.xemantic.typescript.tsgo.cli.TsgoMainKt")
    }

    /** …and that name resolves to something a JVM would actually start. */
    @Test
    fun `the named entry point exists and is startable`() {
        val main = Class.forName(nativeImageMainClass())
            .getMethod("main", Array<String>::class.java)
        assert(Modifier.isStatic(main.modifiers))
        assert(Modifier.isPublic(main.modifiers))
    }

    /**
     * (TSGO.8-b) The artifact CI ships as `xtsc-linux-x64` is the port's PGO image,
     * renamed `xtsc` — and no step builds or uploads `-cli`'s retired image.
     */
    @Test
    fun `the shipped xtsc-linux-x64 is the port's image`() {
        val workflow = File(repoRoot(), ".github/workflows/native.yml").readText()
        val upload = workflow.substringAfter("name: xtsc-linux-x64\n", "").substringBefore("\n\n")
        val uploadedPath = upload.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("path:") }
            ?.substringAfter("path:")?.trim()
        assert(uploadedPath == "dist/xtsc")
        assert("cp xemantic-typescript-compiler-tsgo/build/native/xtsc-tsgo-pgo dist/xtsc" in workflow)
        assert("xemantic-typescript-compiler-cli/build/native" !in workflow)
    }

    /**
     * The image must NOT be the JVM launcher's class: that is the daemon
     * dispatcher, and with it ktor, slf4j and the socket machinery (MOD.7) kept
     * out of the closed world. Read from the script, so a rename over there does
     * not turn this into a false green.
     */
    @Test
    fun `the native image entry point is not the daemon dispatcher`() {
        val launcher = File(repoRoot(), "scripts/xtsc").readLines()
            .firstOrNull { it.startsWith("XTSC_MAIN_CLASS=") }
            ?.substringAfter('=')?.trim()?.trim('"')
            ?: fail("no XTSC_MAIN_CLASS assignment in scripts/xtsc")
        assert(nativeImageMainClass() != launcher)
        assert(launcher == "com.xemantic.typescript.compiler.server.XtscMainKt")
    }

    private companion object {

        /**
         * The `val tsgoNativeImageMainClass = "…"` assignment of the port's build
         * script — a single line by contract, which that script's comment states.
         */
        fun nativeImageMainClass(): String {
            val buildFile = File(repoRoot(), "xemantic-typescript-compiler-tsgo/build.gradle.kts")
            val line = buildFile.readLines()
                .firstOrNull { it.trimStart().startsWith("val tsgoNativeImageMainClass") }
                ?: fail("no `val tsgoNativeImageMainClass` assignment in ${buildFile.absolutePath}")
            return line.substringAfter('=').trim().trim('"')
        }

        /**
         * Keyed on `settings.gradle.kts` because exactly one directory in the tree
         * has one. The anchor is a class of THIS module: a project dependency
         * arrives as a JAR, and `File(url.toURI())` on a `jar:` URL throws.
         */
        fun repoRoot(): File {
            val anchor = "com/xemantic/typescript/compiler/cli/MainKt.class"
            val url = NativeImageEntryPointTest::class.java.classLoader.getResource(anchor)
                ?: fail("$anchor is not on the test classpath")
            var dir: File? = File(url.toURI())
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            fail("could not locate settings.gradle.kts upward from $url")
        }
    }
}

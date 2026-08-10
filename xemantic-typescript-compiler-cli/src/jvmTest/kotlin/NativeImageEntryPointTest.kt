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
 * (MOD.7) What the GraalVM image is built from — the successor to
 * `AotCacheGuardTest.the native image is built from the server dispatcher`,
 * which lived in the daemon module while the task did.
 *
 * **THE THREE SHIPPED ENTRY POINTS NOW DELIBERATELY DISAGREE**, so the old
 * "pin them all to be equal" shape would be exactly wrong:
 *
 *  - `scripts/xtsc` / `scripts/xtsc-aot` → `…compiler.server.XtscMainKt`, the
 *    mode dispatcher. They HAVE a daemon to reach.
 *  - this image → `…compiler.cli.MainKt`, which refuses the daemon modes.
 *  - `…compiler.MainKt`, the bare one-shot main, is what NEITHER may be, and is
 *    the round-840 hazard: the stale 2026-07-30 binary built from it answered
 *    `--serve --socket /tmp/x.sock` by binding no socket, adopting the socket
 *    path as the project, emitting 173 files and exiting 0.
 *
 * **THIS PIN IS STRICTLY STRONGER THAN THE ONE IT REPLACES.** The old one
 * compared two strings, so a typo'd or renamed class stayed green until a user
 * ran the artifact. This one RESOLVES the class named in `build.gradle.kts` and
 * checks it has a `public static void main(String[])` — a rename that misses the
 * build script fails here, in the ordinary suite, with no GraalVM needed.
 *
 * **WHAT IT STILL CANNOT SEE**, said rather than implied: whether the image
 * BUILDS, and whether the refusal survives closed-world analysis into the
 * binary. Nothing in `jvmTest` can — GraalVM is not installed on the development
 * box. `LeanCliEntryPointTest` pins the refusal's behaviour on the JVM;
 * `.github/workflows/native.yml` is what actually builds the thing.
 */
class NativeImageEntryPointTest {

    @Test
    fun `the native image is built from the lean CLI entry point`() {
        assert(nativeImageMainClass() == "com.xemantic.typescript.compiler.cli.MainKt")
    }

    /**
     * …and that name resolves to something a JVM would actually start. This is
     * the half the string comparison could never do.
     */
    @Test
    fun `the named entry point exists and is startable`() {
        val main = Class.forName(nativeImageMainClass())
            .getMethod("main", Array<String>::class.java)
        assert(Modifier.isStatic(main.modifiers))
        assert(Modifier.isPublic(main.modifiers))
    }

    /**
     * The image must NOT be the JVM launcher's class. Equality here would mean
     * the daemon dispatcher came back — and with it ktor, slf4j and the socket
     * machinery that (MOD.7) removed. The launcher's own class is read from the
     * script rather than hardcoded, so a package rename over there does not turn
     * this into a false green.
     */
    @Test
    fun `the native image entry point is not the daemon dispatcher`() {
        val launcher = File(repoRoot(), "scripts/xtsc").readLines()
            .firstOrNull { it.startsWith("XTSC_MAIN_CLASS=") }
            ?.substringAfter('=')?.trim()?.trim('"')
            ?: fail("no XTSC_MAIN_CLASS assignment in scripts/xtsc")
        assert(nativeImageMainClass() != launcher)
        // …and the launcher is still the dispatcher, so the inequality above
        // cannot be satisfied by BOTH having drifted somewhere else.
        assert(launcher == "com.xemantic.typescript.compiler.server.XtscMainKt")
    }

    /**
     * NEITHER may be the bare one-shot main — round 840's silent wrong success.
     * Asserted separately from the inequality above because the two failures have
     * different causes and a reader deserves to be told which one happened.
     */
    @Test
    fun `the native image entry point is not the bare compiler main`() {
        assert(nativeImageMainClass() != "com.xemantic.typescript.compiler.MainKt")
    }

    private companion object {

        /**
         * The `val nativeImageMainClass = "…"` assignment of THIS module's build
         * script — a single line by contract, which the build script's own
         * comment states.
         */
        fun nativeImageMainClass(): String {
            val buildFile = File(projectRoot(), "build.gradle.kts")
            val line = buildFile.readLines()
                .firstOrNull { it.trimStart().startsWith("val nativeImageMainClass") }
                ?: fail("no `val nativeImageMainClass` assignment in ${buildFile.absolutePath}")
            return line.substringAfter('=').trim().trim('"')
        }

        fun projectRoot(): File = ancestorContaining("build.gradle.kts")

        /**
         * Keyed on `settings.gradle.kts` because exactly one directory in the
         * tree has one, whereas every module has a `build.gradle.kts` — an upward
         * walk for the latter stops at the first module it meets, which is the
         * mistake that once broke the daemon module's copy of this walk.
         */
        fun repoRoot(): File = ancestorContaining("settings.gradle.kts")

        /**
         * The nearest ancestor of the compiled classes that holds [marker].
         *
         * The anchor is deliberately a class of THIS module. A class from the
         * compiler core reaches this test through a project dependency, which
         * Gradle puts on the runtime classpath as a JAR — and `File(url.toURI())`
         * on a `jar:` URL throws "URI is not hierarchical", so the walk would not
         * merely find the wrong directory, it would fail outright.
         */
        private fun ancestorContaining(marker: String): File {
            val anchor = "com/xemantic/typescript/compiler/cli/MainKt.class"
            val url = NativeImageEntryPointTest::class.java.classLoader.getResource(anchor)
                ?: fail("$anchor is not on the test classpath")
            var dir: File? = File(url.toURI())
            while (dir != null) {
                if (File(dir, marker).isFile) return dir
                dir = dir.parentFile
            }
            fail("could not locate $marker upward from $url")
        }
    }
}

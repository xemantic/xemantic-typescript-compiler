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

package com.xemantic.typescript.compiler.kir.emit

import com.xemantic.typescript.compiler.kir.runtime.Undefined
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Locates the classpath a generated program is compiled and run against.
 *
 * A generated program links against exactly two things: the Kotlin standard
 * library (`kotlin.io.println` and friends) and this module's own
 * `…kir.runtime` package, which carries the JS semantics that have no JVM
 * equivalent. Both are already loaded in the emitting process, so the honest
 * way to name them is to ask the JVM where it loaded them FROM — a marker
 * class's code source — rather than to pattern-match jar names out of
 * `java.class.path`.
 *
 * That distinction matters here: under Gradle the runtime is an exploded
 * `build/classes/kotlin/jvm/main` DIRECTORY and in a distribution it is a jar,
 * and a code-source lookup answers both without knowing which it is looking at.
 * [full] remains for the cases where the generated program needs the emitting
 * process's whole classpath; it is the blunt instrument and is not the default,
 * because it would drag the embedded Kotlin compiler — 60 MB of shaded IntelliJ
 * — onto the compile classpath of every generated program.
 */
public object GeneratedProgramClasspath {

    /**
     * The artifact — jar or class directory — the given class was loaded from.
     *
     * Throws rather than returning null: a marker class with no code source is
     * a class the JVM loaded from the boot classpath, which means the caller
     * picked a marker that cannot answer the question it was asked.
     */
    public fun locate(marker: Class<*>): Path {
        val codeSource = marker.protectionDomain?.codeSource
            ?: error("no code source for ${marker.name} — it is not a usable classpath marker")
        val location = codeSource.location
            ?: error("no code source location for ${marker.name}")
        return Paths.get(location.toURI())
    }

    /** The Kotlin standard library, located through [kotlin.Unit]. */
    public val kotlinStdlib: Path get() = locate(Unit::class.java)

    /** This module, located through the runtime's [Undefined] singleton. */
    public val kirRuntime: Path get() = locate(Undefined::class.java)

    /** What a generated program needs, and nothing else. */
    public fun minimal(): List<Path> = listOf(kotlinStdlib, kirRuntime)

    /** Every entry of the emitting process's own classpath. */
    public fun full(): List<Path> =
        (System.getProperty("java.class.path") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Paths.get(it) }

}

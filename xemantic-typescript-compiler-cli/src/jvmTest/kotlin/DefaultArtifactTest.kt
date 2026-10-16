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
import com.xemantic.typescript.tsgo.TsgoCli
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test

/**
 * (TSGO.8-c) The tsgo port is the default artifact: its jar carries the product name
 * (`xemantic-typescript-compiler-jvm-<version>.jar`, the Maven coordinates
 * `com.xemantic.typescript:xemantic-typescript-compiler`) and the upstream licence
 * files of the work it is derived from.
 *
 * A project dependency reaches this module's tests as the dependency's JAR, so the
 * jar the port's classes load from is the one `-tsgo`'s build produces.
 */
class DefaultArtifactTest {

    private val portJar = File(TsgoCli::class.java.protectionDomain.codeSource.location.toURI())

    @Test
    fun `the port's jar carries the product name`() {
        val name = portJar.name
        assert(name.startsWith("xemantic-typescript-compiler-jvm-") && name.endsWith(".jar"))
    }

    @Test
    fun `the port's jar carries typescript-go's licence and notice`() {
        val entries = ZipFile(portJar).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assert("META-INF/typescript-go/LICENSE" in entries)
        assert("META-INF/typescript-go/NOTICE.txt" in entries)
        assert("META-INF/LICENSE" in entries)
    }
}

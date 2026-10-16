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
import kotlin.test.Test

/**
 * (MOD.7) The pin on the module edge itself: what the lean CLI's classpath
 * must NOT contain.
 *
 * A dependency edge added in a build script is invisible until something reads
 * the graph. So this asserts the property directly, at run time, over the
 * classpath the tests actually run on — this module's `jvmRuntimeClasspath`
 * plus the two test libraries.
 *
 * Add `:xemantic-typescript-compiler-daemon` (or `-api`, which exports
 * ktor-network) to this module and the transport pin reddens in the same build;
 * put `-core` back and the retired-core pin does.
 *
 * Not pinned, because no run-time check here can see it: kotlinx-serialization
 * left this module's runtime classpath with `-core`, but the test classpath
 * still has it through `xemantic-kotlin-test`.
 *
 * The POSITIVE CONTROL is not decoration: round 853 lost three rounds to gates
 * that were green because they were pointed at nothing. If the compiler itself
 * were missing, every absence assertion below would pass vacuously.
 */
class LeanClasspathTest {

    private fun onClasspath(name: String): Boolean =
        runCatching {
            Class.forName(name, false, LeanClasspathTest::class.java.classLoader)
        }.isSuccess

    /** POSITIVE CONTROL — the thing under test is here at all. */
    @Test
    fun `the ported compiler is on the classpath`() {
        assert(onClasspath("com.xemantic.typescript.tsgo.cli.TsgoMainKt"))
        assert(onClasspath("com.xemantic.typescript.tsgo.TsgoCli"))
    }

    /** (TSGO.8-b) The retired hand-written compiler does not come along. */
    @Test
    fun `the retired compiler core is absent`() {
        assert(!onClasspath("com.xemantic.typescript.compiler.MainKt"))
        assert(!onClasspath("com.xemantic.typescript.compiler.Checker"))
    }

    /**
     * The daemon transport, in three independent places: ktor's socket API, the
     * slf4j facade ktor drags along, and the compile server itself. Each is
     * checked separately so a failure names which edge came back.
     */
    @Test
    fun `the transport is absent`() {
        assert(!onClasspath("io.ktor.network.sockets.UnixSocketAddress"))
        assert(!onClasspath("io.ktor.network.selector.SelectorManager"))
        assert(!onClasspath("org.slf4j.LoggerFactory"))
        assert(!onClasspath("com.xemantic.typescript.compiler.server.CompileServer"))
    }

    /** The wire protocol travels with the transport and has no business here either. */
    @Test
    fun `the wire protocol is absent`() {
        assert(!onClasspath("com.xemantic.typescript.compiler.protocol.CompileRequest"))
        assert(!onClasspath("com.xemantic.typescript.compiler.client.XtscClient"))
    }
}

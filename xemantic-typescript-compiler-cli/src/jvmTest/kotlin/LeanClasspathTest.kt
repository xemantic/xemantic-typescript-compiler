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
 * (MOD.7) The pin on the module edge itself: what the native image's classpath
 * must NOT contain.
 *
 * A dependency edge added in a build script is invisible until something reads
 * the graph, and "the image got bigger" is not a signal anyone watches. So this
 * asserts the property directly, at run time, over the classpath the tests
 * actually run on — which for this module is its `jvmRuntimeClasspath` plus the
 * two test libraries, and therefore a faithful stand-in for what native-image
 * analyses.
 *
 * **IT DISCRIMINATES AT THE ONLY MOMENT THAT MATTERS.** Add
 * `:xemantic-typescript-compiler-daemon` (or `-api`, which exports
 * ktor-network) to this module and [theTransportIsAbsent] reddens in the same
 * build — before a 2-minute image build, and before anyone has to explain a size
 * regression.
 *
 * **WHAT IS NOT CLAIMED, said rather than implied.** kotlinx-serialization is
 * still here and this class ASSERTS that it is, so the record cannot rot into a
 * false claim: `TsConfigLoader` parses tsconfig.json with it, so it arrives
 * through the compiler core and is reachable from every entry point this project
 * has. The split removes the ktor transport and its slf4j tail; it does not
 * remove serialization, and nothing short of changing how the core reads
 * tsconfig would.
 *
 * The POSITIVE CONTROL is not decoration: round 853 lost three rounds to gates
 * that were green because they were pointed at nothing. If the compiler core
 * itself were missing, every absence assertion below would pass vacuously.
 */
class LeanClasspathTest {

    private fun onClasspath(name: String): Boolean =
        runCatching {
            Class.forName(name, false, LeanClasspathTest::class.java.classLoader)
        }.isSuccess

    /** POSITIVE CONTROL — the thing under test is here at all. */
    @Test
    fun `the compiler core is on the classpath`() {
        assert(onClasspath("com.xemantic.typescript.compiler.MainKt"))
        assert(onClasspath("com.xemantic.typescript.compiler.Checker"))
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

    /**
     * TRUTHFUL RECORD, not an aspiration: serialization stays, because the core
     * parses tsconfig.json with it. Written as an assertion so that if it ever
     * DOES leave, this pin fails and the documentation above gets corrected
     * instead of quietly becoming wrong.
     */
    @Test
    fun `serialization stays because the core parses tsconfig with it`() {
        assert(onClasspath("kotlinx.serialization.json.Json"))
    }
}

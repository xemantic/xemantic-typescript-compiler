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

package com.xemantic.typescript.compiler.protocol

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

class SocketPathTest {

    @Test
    fun `should derive a per-user socket path`() {
        assert(xtscSocketPath("/tmp", "morisil") == "/tmp/xtsc-morisil.sock")
    }

    @Test
    fun `should give two users on one host different sockets`() {
        val first = xtscSocketPath("/tmp", "alice")
        val second = xtscSocketPath("/tmp", "bob")
        assert(first != second)
    }

    // java.io.tmpdir carries a trailing separator on macOS and not on Linux. Both
    // spellings name the same socket, so only a string comparison can see the
    // difference - which is exactly what a launcher script does.
    @Test
    fun `should produce the same path whether or not the temp dir ends in a separator`() {
        assert(xtscSocketPath("/tmp/", "morisil") == xtscSocketPath("/tmp", "morisil"))
    }

    @Test
    fun `should trim a trailing backslash as well`() {
        assert(xtscSocketPath("C:\\Temp\\", "morisil") == "C:\\Temp/xtsc-morisil.sock")
    }

    // A domain-joined Windows account name contains a separator, which would
    // otherwise redirect the socket into a different directory.
    @Test
    fun `should replace a separator in the user name`() {
        assert(xtscSocketPath("/tmp", "CORP\\morisil") == "/tmp/xtsc-CORP_morisil.sock")
    }

    @Test
    fun `should not leave an empty user name in the path`() {
        assert(xtscSocketPath("/tmp", "") == "/tmp/xtsc-unknown.sock")
    }

    @Test
    fun `should fall back to tmp when the temp dir is empty`() {
        assert(xtscSocketPath("", "morisil") == "/tmp/xtsc-morisil.sock")
    }

    @Test
    fun `should accept a plausible socket path`() {
        assert(socketPathProblem("/tmp/xtsc-morisil.sock") == null)
    }

    @Test
    fun `should reject a socket path over the sun_path limit`() {
        val tooLong = "/tmp/" + "x".repeat(MAX_SOCKET_PATH_BYTES) + ".sock"
        val problem = socketPathProblem(tooLong)
        assert(problem != null)
        assert(problem.contains("$MAX_SOCKET_PATH_BYTES"))
    }

    // The limit is a byte limit, not a character one, so a path that fits in
    // characters can still overflow the kernel's buffer.
    @Test
    fun `should measure the socket path limit in bytes rather than characters`() {
        val multiByte = "/tmp/" + "\u00e9".repeat(MAX_SOCKET_PATH_BYTES / 2)
        assert(multiByte.length <= MAX_SOCKET_PATH_BYTES)
        assert(socketPathProblem(multiByte) != null)
    }

    @Test
    fun `should reject an empty socket path`() {
        assert(socketPathProblem("") != null)
    }

}

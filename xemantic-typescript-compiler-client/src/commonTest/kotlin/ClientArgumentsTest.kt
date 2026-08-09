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

package com.xemantic.typescript.compiler.client

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

class ClientArgumentsTest {

    @Test
    fun `should take the socket path from --socket`() {
        val parsed = parseClientArguments(listOf("--socket", "/tmp/x.sock", "--noEmit"))
        assert(parsed.socketPath == "/tmp/x.sock")
    }

    // --socket and --no-spawn steer the CLIENT; everything else belongs to the
    // compiler and must survive untouched, or this stops being a pass-through
    // the moment the compiler gains an option.
    @Test
    fun `should forward every compiler argument verbatim`() {
        val parsed = parseClientArguments(
            listOf("--socket", "/tmp/x.sock", "--noEmit", "--listAll", "--strict")
        )
        assert(parsed.forwarded == listOf("--noEmit", "--listAll", "--strict"))
    }

    @Test
    fun `should not forward the client's own options`() {
        val parsed = parseClientArguments(listOf("--no-spawn", "--noEmit"))
        assert(parsed.forwarded == listOf("--noEmit"))
        assert(!parsed.allowSpawn)
    }

    @Test
    fun `should allow spawning by default`() {
        assert(parseClientArguments(listOf("--noEmit")).allowSpawn)
    }

    @Test
    fun `should fall back to the default socket when none is given`() {
        val parsed = parseClientArguments(listOf("--noEmit"))
        assert(parsed.socketPath.isNotEmpty())
        assert(parsed.socketPath.endsWith(".sock"))
    }

    // A path that does not exist stays as written: it may be a value the
    // compiler understands and this client deliberately knows nothing about.
    @Test
    fun `should leave a non-existent path argument alone`() {
        val parsed = parseClientArguments(listOf("./definitely-not-here-9zq"))
        assert(parsed.forwarded == listOf("./definitely-not-here-9zq"))
    }

    @Test
    fun `should leave flags alone even when a file of that name exists`() {
        val parsed = parseClientArguments(listOf("--noEmit", "-w"))
        assert(parsed.forwarded == listOf("--noEmit", "-w"))
    }

    // A trailing --socket with no value must not throw; the daemon-side path
    // check reports the problem, and a crash here would be a worse message.
    @Test
    fun `should survive a trailing socket option with no value`() {
        val parsed = parseClientArguments(listOf("--noEmit", "--socket"))
        assert(parsed.forwarded == listOf("--noEmit"))
    }

    // (SERVE.2) round 873. This client USED to absolutize any argument naming
    // something that exists here, and the rule was unfixable at this layer: a
    // client does not parse the compiler's options, so it cannot tell a project
    // path from the `4` in `--workers 4`, and it never sees the path a user did
    // not type. The directory travels in the request instead, so a path argument
    // that DOES exist must now also be forwarded exactly as written - restoring
    // the rewriting would make one command line produce two different requests
    // depending on which client ran it.
    @Test
    fun `should forward an existing path argument verbatim rather than absolutizing it`() {
        val parsed = parseClientArguments(listOf("--noEmit", "."))
        assert(parsed.forwarded == listOf("--noEmit", "."))
    }

    @Test
    fun `should forward an option value that looks like a path verbatim`() {
        val parsed = parseClientArguments(listOf("--outDir", "out", "--workers", "4"))
        assert(parsed.forwarded == listOf("--outDir", "out", "--workers", "4"))
    }

}

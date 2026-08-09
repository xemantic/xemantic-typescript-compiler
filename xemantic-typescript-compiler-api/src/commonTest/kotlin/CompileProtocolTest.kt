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
import kotlinx.serialization.encodeToString
import kotlin.test.Test

class CompileProtocolTest {

    @Test
    fun `should round-trip a compile request`() {
        val request = CompileRequest(
            args = listOf("--noEmit", "--listAll", "/proj"),
            protocolVersion = XTSC_PROTOCOL_VERSION
        )
        val decoded = xtscProtocolJson.decodeFromString<CompileRequest>(
            xtscProtocolJson.encodeToString(request)
        )
        assert(decoded == request)
    }

    @Test
    fun `should round-trip a compile response`() {
        val response = CompileResponse(
            output = "OK - 0 errors\n",
            exitCode = 0,
            elapsedMs = 11580,
            protocolVersion = XTSC_PROTOCOL_VERSION
        )
        val decoded = xtscProtocolJson.decodeFromString<CompileResponse>(
            xtscProtocolJson.encodeToString(response)
        )
        assert(decoded == response)
    }

    // Arguments reach the daemon verbatim, so nothing here may normalise them -
    // an empty argument and repeated whitespace are meaningful to the CLI.
    @Test
    fun `should carry arguments verbatim including empty ones`() {
        val args = listOf("--project", "", "  spaced  ", "--noEmit")
        val decoded = xtscProtocolJson.decodeFromString<CompileRequest>(
            xtscProtocolJson.encodeToString(CompileRequest(args))
        )
        assert(decoded.args == args)
    }

    // A peer that predates versioning sends no version field at all. It must
    // decode - so the mismatch is reported rather than presenting as a crash -
    // and it must NOT read as the current version.
    @Test
    fun `should read a missing version as unversioned rather than as current`() {
        val legacy = """{"args":["--noEmit"]}"""
        val decoded = xtscProtocolJson.decodeFromString<CompileRequest>(legacy)
        assert(decoded.protocolVersion == XTSC_PROTOCOL_UNVERSIONED)
        assert(decoded.protocolVersion != XTSC_PROTOCOL_VERSION)
    }

    @Test
    fun `should read a missing version in a response as unversioned`() {
        val legacy = """{"output":"OK","exitCode":0,"elapsedMs":12}"""
        val decoded = xtscProtocolJson.decodeFromString<CompileResponse>(legacy)
        assert(decoded.protocolVersion == XTSC_PROTOCOL_UNVERSIONED)
    }

    // Forward compatibility in the other direction: a newer peer may add fields,
    // and the older one has to keep working rather than fail to decode.
    @Test
    fun `should tolerate a field added by a newer peer`() {
        val newer = """{"args":["--noEmit"],"protocolVersion":$XTSC_PROTOCOL_VERSION,""" +
            """"somethingAddedLater":"/proj"}"""
        val decoded = xtscProtocolJson.decodeFromString<CompileRequest>(newer)
        assert(decoded.args == listOf("--noEmit"))
        assert(decoded.protocolVersion == XTSC_PROTOCOL_VERSION)
    }

    // (SERVE.2) round 873. The directory is part of what a request MEANS: every
    // relative path on the command line resolves against it, including the
    // project path a user did not type at all (the CLI defaults it to "."), and
    // the daemon's own directory is a different one that a JVM cannot change.
    @Test
    fun `should carry the client's working directory`() {
        val request = CompileRequest(
            args = listOf("--noEmit"),
            protocolVersion = XTSC_PROTOCOL_VERSION,
            workingDirectory = "/home/user/proj",
        )
        val decoded = xtscProtocolJson.decodeFromString<CompileRequest>(
            xtscProtocolJson.encodeToString(request)
        )
        assert(decoded.workingDirectory == "/home/user/proj")
        assert(decoded == request)
    }

    // A version-1 daemon never sent it and a version-1 client never will, so the
    // absent field has to mean "the server's own directory" - which is exactly
    // what the whole request used to mean.
    @Test
    fun `should read an absent working directory as empty rather than as a path`() {
        val v1 = """{"args":["--noEmit"],"protocolVersion":1}"""
        val decoded = xtscProtocolJson.decodeFromString<CompileRequest>(v1)
        assert(decoded.workingDirectory == "")
    }

    // The field is the whole reason for the version bump: a version-1 daemon
    // answers a version-2 request by resolving it against its OWN directory,
    // silently compiling a different tree. `protocolProblem` is what stops that,
    // so the two must move together.
    @Test
    fun `should refuse a peer that predates the working directory`() {
        assert(XTSC_PROTOCOL_VERSION >= 2)
        assert(protocolProblem(1) != null)
    }

    @Test
    fun `should accept a peer speaking the current protocol`() {
        assert(protocolProblem(XTSC_PROTOCOL_VERSION) == null)
    }

    @Test
    fun `should report a peer that predates versioning`() {
        val problem = protocolProblem(XTSC_PROTOCOL_UNVERSIONED)
        assert(problem != null)
        assert(problem.contains("restart"))
    }

    @Test
    fun `should report a peer speaking a newer protocol`() {
        val problem = protocolProblem(XTSC_PROTOCOL_VERSION + 1)
        assert(problem != null)
        assert(problem.contains("restart"))
    }

    // Reserved for "the request never ran". A compile that ran and found errors
    // exits 0, because the scripts here treat non-zero as infrastructure failure.
    @Test
    fun `should keep the refused code distinct from success`() {
        assert(XTSC_REFUSED != 0)
    }

}

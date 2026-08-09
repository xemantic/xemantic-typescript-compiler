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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire contract between the `xtsc` client and the compile daemon.
 *
 * The two are separately-built binaries with independent lifetimes — a daemon
 * started days ago keeps answering a client rebuilt this minute — so everything
 * here is versioned and tolerant by construction.
 */
public const val XTSC_PROTOCOL_VERSION: Int = 2

/**
 * The version reported by a peer that predates protocol versioning.
 *
 * Absent-means-old is why the version fields below default to this rather than
 * to [XTSC_PROTOCOL_VERSION]: defaulting to the current version would make an
 * older daemon's response indistinguishable from a current one, which is the
 * single case the field exists to detect.
 */
public const val XTSC_PROTOCOL_UNVERSIONED: Int = 0

/**
 * One compile, expressed exactly as the command line that requested it — plus
 * the directory that command line was typed in.
 *
 * **[workingDirectory] is not decoration: without it a request does not say what
 * it means** (round 873's parity sweep, protocol version 2). Every relative path
 * on a command line — `.`, `./project`, `--outDir out`, and above all the
 * project path a user simply did not type, which the CLI defaults to `"."` —
 * resolves against the client's directory, and the daemon's is a different one
 * that a JVM cannot change. Measured before this field existed:
 * `xtsc --daemon --noEmit` in a project with errors compiled the DAEMON's own
 * directory and exited **0**, and `--outDir out` wrote the user's compiled
 * JavaScript into it.
 *
 * The two clients had each grown a heuristic instead — *"rewrite an argument
 * that names something existing here"* — which cannot work, because a client
 * does not parse the compiler's options and so cannot tell a project path from
 * the `4` in `--workers 4`. Sending the directory moves the question to the one
 * place that HAS the option table: the compiler itself.
 *
 * Empty means "the server's own directory", which is what a pre-version-2 client
 * silently got.
 */
@Serializable
public data class CompileRequest(
    val args: List<String>,
    val protocolVersion: Int = XTSC_PROTOCOL_UNVERSIONED,
    val workingDirectory: String = "",
)

/**
 * The daemon's answer.
 *
 * [output] is the compiler's captured stdout, reproduced verbatim by the client,
 * so that what a user sees is identical whether the compile ran in the daemon or
 * in-process — identical by construction rather than by being kept in sync.
 */
@Serializable
public data class CompileResponse(
    val output: String,
    val exitCode: Int,
    val elapsedMs: Long,
    val protocolVersion: Int = XTSC_PROTOCOL_UNVERSIONED,
)

/**
 * Exit code for a request the daemon declined to run at all.
 *
 * Distinct from a failed compile: the CLI reports compile failure in its summary
 * line and still exits 0, because scripts here treat a non-zero exit as an
 * infrastructure failure and abort. So a non-zero code always means the request
 * never ran, never that it ran and found errors.
 */
public const val XTSC_REFUSED: Int = 2

/**
 * Why one peer should not trust the other, or null when it should.
 *
 * A version mismatch is a *restart* condition, not a hard failure: a client's
 * documented behaviour on reaching a mismatched daemon is to fall back to
 * compiling in-process, and a daemon's is to refuse the request without running
 * it ([XTSC_REFUSED]) so the client can do exactly that.
 *
 * **The wording is peer-neutral on purpose, and that is load-bearing: BOTH
 * sides print this string.** It read `"the daemon speaks protocol N, this
 * client speaks M"` until a daemon printed it about a *client* — where every
 * noun is inverted, so the log said the freshly-built daemon was the old one
 * and sent a reader looking in the wrong place.
 */
public fun protocolProblem(peerVersion: Int): String? = when {
    peerVersion == XTSC_PROTOCOL_VERSION -> null
    peerVersion == XTSC_PROTOCOL_UNVERSIONED ->
        "the peer predates protocol versioning and cannot be trusted to " +
            "understand protocol $XTSC_PROTOCOL_VERSION — restart the older of the two"
    else ->
        "the peer speaks protocol $peerVersion, this build speaks " +
            "$XTSC_PROTOCOL_VERSION — restart the older of the two"
}

/**
 * The codec both peers use.
 *
 * `ignoreUnknownKeys` is what lets a field be added to either message without
 * breaking the older peer that receives it — the version fields above then say
 * whether the newer peer should care.
 */
public val xtscProtocolJson: Json = Json {
    ignoreUnknownKeys = true
}

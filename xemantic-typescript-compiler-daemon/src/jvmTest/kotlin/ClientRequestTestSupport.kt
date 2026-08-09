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

package com.xemantic.typescript.compiler

import com.xemantic.typescript.compiler.protocol.CompileRequest
import com.xemantic.typescript.compiler.protocol.XTSC_PROTOCOL_VERSION

/**
 * A request as a CURRENT client sends one.
 *
 * [CompileRequest]'s `protocolVersion` deliberately defaults to
 * `XTSC_PROTOCOL_UNVERSIONED` — absent-means-old is the whole point of the
 * field — so a test that constructs one positionally is impersonating a client
 * that predates versioning, which the server now (correctly) refuses without
 * compiling. Every server pin here means to be a current client, so it says so
 * once, here, rather than repeating the version at a dozen call sites.
 *
 * **This helper is why the refusal guard could not be added silently.** Until
 * it existed every server test spoke the unversioned protocol and therefore
 * pinned the OPPOSITE behaviour — a mismatched request being served — which is
 * exactly how a daemon came to compile a version-1 request against its own
 * working directory, and to do it twice per invocation.
 */
internal fun clientRequest(
    args: List<String>,
    workingDirectory: String = "",
): CompileRequest = CompileRequest(
    args = args,
    protocolVersion = XTSC_PROTOCOL_VERSION,
    workingDirectory = workingDirectory,
)

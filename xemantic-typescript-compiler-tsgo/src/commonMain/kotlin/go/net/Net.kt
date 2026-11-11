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

package com.xemantic.typescript.tsgo.go.net

import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.Tuple2

// Go's `net` — the subset TypeScript 7.1's `ipc.PipeTransport` names: a `Listener` over a Unix domain socket
// (Windows: a named pipe). The JVM port's commonMain has no socket API; `Listen` reports the operation
// unsupported, so a pipe transport fails to start with an error, as Go's would on a platform without one.
// Content mappers run over stdio (`ipc.StdioTransport`), which needs none of this.

/** `net.Addr`. */
interface Addr {
    fun network(): String
    fun string(): String
}

/** `net.Conn` — the `io.ReadWriteCloser` a listener accepts. */
interface Conn : com.xemantic.typescript.tsgo.go.io.ReadWriteCloser

/** `net.Listener`. */
interface Listener {
    fun accept(): Tuple2<Conn?, GoError?>
    fun close(): GoError?
    fun addr(): Addr?
}

/** `net.ErrClosed`. */
val errClosed: GoError = GoPlainError("use of closed network connection")

/** `net.Listen(network, address)`: unsupported in the port (see the file comment). */
fun listen(network: String, address: String): Tuple2<Listener?, GoError?> =
    Tuple2(null, GoPlainError("listen $network $address: operation not supported"))

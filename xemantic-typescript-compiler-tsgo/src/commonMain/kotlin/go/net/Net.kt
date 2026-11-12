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

/**
 * `net.Pipe()`: two connected in-memory ends (the test harness's in-process content mappers,
 * `contentmappertest.spawner.Spawn`). Go's pipe is synchronous (a write returns once read); here a write
 * queues its bytes and returns — the order of bytes, and of requests and responses on top of them, is the same.
 * A read blocks (its run token given back) until bytes arrive or either end closes (then `io.EOF`).
 */
fun pipe(): com.xemantic.typescript.tsgo.runtime.Tuple2<Conn?, Conn?> {
    val ab = PipeBuffer()
    val ba = PipeBuffer()
    return com.xemantic.typescript.tsgo.runtime.Tuple2(PipeEnd(ba, ab), PipeEnd(ab, ba))
}

private class PipeBuffer {
    val lock = com.xemantic.typescript.tsgo.go.sync.Mutex()
    val cond = com.xemantic.typescript.tsgo.go.sync.Cond(lock)
    val bytes = ArrayDeque<Int>()
    var closed = false
}

private class PipeEnd(private val inbox: PipeBuffer, private val outbox: PipeBuffer) : Conn {
    override fun read(p: com.xemantic.typescript.tsgo.runtime.GoSlice<Int>): Tuple2<Int, GoError?> {
        inbox.lock.lock()
        try {
            while (inbox.bytes.isEmpty() && !inbox.closed) inbox.cond.wait()
            if (inbox.bytes.isEmpty()) return Tuple2(0, com.xemantic.typescript.tsgo.go.io.EOF)
            val n = minOf(p.len, inbox.bytes.size)
            for (i in 0 until n) p[i] = inbox.bytes.removeFirst()
            return Tuple2(n, null)
        } finally {
            inbox.lock.unlock()
        }
    }

    override fun write(p: com.xemantic.typescript.tsgo.runtime.GoSlice<Int>): Tuple2<Int, GoError?> {
        outbox.lock.lock()
        try {
            if (outbox.closed) return Tuple2(0, GoPlainError("io: read/write on closed pipe"))
            for (i in 0 until p.len) outbox.bytes.addLast(p[i] and 0xFF)
            outbox.cond.broadcast()
            return Tuple2(p.len, null)
        } finally {
            outbox.lock.unlock()
        }
    }

    override fun close(): GoError? {
        for (b in listOf(inbox, outbox)) {
            b.lock.lock()
            b.closed = true
            b.cond.broadcast()
            b.lock.unlock()
        }
        return null
    }
}

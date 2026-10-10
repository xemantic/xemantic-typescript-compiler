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

@file:OptIn(ExperimentalAtomicApi::class)

package com.xemantic.typescript.tsgo.go.os

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/**
 * The one funnel every host file-system call of the port passes through (the `platform*` actuals of
 * `go/os` and `internal_nativepath`) — Go's `entersyscall`/`exitsyscall` boundary.
 *
 * `TSGO_IO_STATS=1` counts calls, nanoseconds and bytes per operation ([stats]). The call runs on the
 * calling goroutine, which KEEPS its run token: giving it back around the call (Go's eager P hand-off)
 * or handing the call to a dedicated IO pool — the "IO dispatcher / compute dispatcher" split — was
 * measured as a regression ((TSGO.6-h), docs/goport-perf.md § 9, experiment commit 1820a9de1): a cached
 * file-system call is kernel CPU work, not a wait, so there is no idle core to give away, and the
 * token churn multiplied tsgo's unsynchronised package.json reads 20x.
 */
internal object GoSyscall {
    const val OP_READ = 0
    const val OP_STAT = 1
    const val OP_LIST = 2
    const val OP_REALPATH = 3
    const val OP_WRITE = 4
    private val names = arrayOf("read", "stat", "list", "realpath", "write")

    val statsOn: Boolean = platformGetenv("TSGO_IO_STATS").let { it != null && it.isNotEmpty() && it != "0" }

    private val calls = AtomicLongArray(names.size)
    private val nanos = AtomicLongArray(names.size)
    private val bytes = AtomicLong(0)

    fun record(op: Int, ns: Long) {
        calls.fetchAndAddAt(op, 1)
        nanos.fetchAndAddAt(op, ns)
    }

    fun recordBytes(n: Int) {
        if (statsOn) bytes.fetchAndAdd(n.toLong())
    }

    /** `io: read=<calls>/<ms> … total=<calls>/<ms> bytes=…` (nanoseconds summed over all threads). */
    fun stats(): String {
        val sb = StringBuilder("io:")
        var c = 0L
        var n = 0L
        for (i in names.indices) {
            val ci = calls.loadAt(i)
            val ni = nanos.loadAt(i)
            c += ci
            n += ni
            sb.append(' ').append(names[i]).append('=').append(ci).append('/').append(ms(ni))
        }
        return sb.append(" total=").append(c).append('/').append(ms(n)).append("ms bytes=").append(bytes.load()).toString()
    }

    private fun ms(ns: Long): String = ((ns / 10_000L) / 100.0).toString()
}

/** Runs the host call [block] at the syscall boundary (see [GoSyscall]). */
internal inline fun <R> syscall(op: Int, block: () -> R): R {
    val t0 = if (GoSyscall.statsOn) TimeSource.Monotonic.markNow() else null
    try {
        return block()
    } finally {
        if (t0 != null) GoSyscall.record(op, t0.elapsedNow().inWholeNanoseconds)
    }
}

/** The host environment variable [name] (null when unset). */
internal expect fun platformGetenv(name: String): String?

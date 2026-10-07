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

package com.xemantic.typescript.tsgo.go.time

// The `time` subset tsgo's closure reaches: `Duration` and its unit constants, and a `Time` that
// is enough for `tracing` (`Now`/`Since`/`Sub`, elapsed time from a MONOTONIC reading, as Go's
// `Sub` uses when both operands carry one) and for `vfs`/`bundled` (`time.Time{}` as a mod time).
// No calendar, zones or formatting. Nothing on the check path reads a clock.

import kotlin.time.TimeSource

/** `time.Duration` (nanoseconds). */
@kotlin.jvm.JvmInline
value class Duration(val value: Long) {
    /** `d.Nanoseconds()`. */
    fun nanoseconds(): Long = value

    /** `d.Microseconds()`. */
    fun microseconds(): Long = value / 1_000

    /** `d.Milliseconds()`. */
    fun milliseconds(): Long = value / 1_000_000

    /** `d.Seconds()`. */
    fun seconds(): Double {
        val sec = value / 1_000_000_000
        val nsec = value % 1_000_000_000
        return sec.toDouble() + nsec.toDouble() / 1e9
    }
}

val Nanosecond: Duration = Duration(1)
val Microsecond: Duration = Duration(1_000)
val Millisecond: Duration = Duration(1_000_000)
val Second: Duration = Duration(1_000_000_000)
val Minute: Duration = Duration(60_000_000_000)
val Hour: Duration = Duration(3_600_000_000_000)

/** The process-wide monotonic origin; a [Time]'s `mono` is nanoseconds since it. */
private val monoOrigin = TimeSource.Monotonic.markNow()

/**
 * `time.Time`: a struct VALUE (immutable here, so [goCopy] returns itself). [wallNanos] is
 * nanoseconds since the Unix epoch (0 for the zero `Time{}`, whose `IsZero` is then the epoch
 * — approximation: Go's zero is year 1); [mono] is a monotonic reading, `Long.MIN_VALUE` = none.
 */
class Time(internal val wallNanos: Long = Long.MIN_VALUE, internal val mono: Long = Long.MIN_VALUE) {

    /** `t.Sub(u)`: from the monotonic readings when both have one (Go's rule), else the wall clocks. */
    fun sub(u: Time): Duration =
        if (mono != Long.MIN_VALUE && u.mono != Long.MIN_VALUE) Duration(mono - u.mono)
        else Duration(wall() - u.wall())

    /** `t.IsZero()`. */
    fun isZero(): Boolean = wallNanos == Long.MIN_VALUE

    /** `t.UnixNano()`. */
    fun unixNano(): Long = wall()

    /** `t.UnixMilli()`. */
    fun unixMilli(): Long = wall().floorDiv(1_000_000L)

    /** `t.Equal(u)`. */
    fun equal(u: Time): Boolean = wall() == u.wall()

    /** `t.Before(u)` / `t.After(u)`. */
    fun before(u: Time): Boolean = if (mono != Long.MIN_VALUE && u.mono != Long.MIN_VALUE) mono < u.mono else wall() < u.wall()
    fun after(u: Time): Boolean = if (mono != Long.MIN_VALUE && u.mono != Long.MIN_VALUE) mono > u.mono else wall() > u.wall()

    private fun wall(): Long = if (wallNanos == Long.MIN_VALUE) ZERO_WALL else wallNanos

    fun goCopy(): Time = this

    fun goEquals(other: Time): Boolean = wallNanos == other.wallNanos && mono == other.mono

    fun goHash(): Int = wallNanos.hashCode() * 31 + mono.hashCode()

    override fun toString(): String = if (isZero()) "0001-01-01 00:00:00 +0000 UTC" else "Time(${wallNanos}ns)"
}

/** Unix nanoseconds of Go's zero `Time` (0001-01-01 UTC) do not fit in an `int64`; clamp. */
private const val ZERO_WALL: Long = Long.MIN_VALUE + 1

/** `time.Now()`. */
fun now(): Time {
    val mono = (TimeSource.Monotonic.markNow() - monoOrigin).inWholeNanoseconds
    return Time(kotlin.time.Clock.System.now().let { it.epochSeconds * 1_000_000_000L + it.nanosecondsOfSecond }, mono)
}

/** `time.Since(t)` = `Now().Sub(t)`. */
fun since(t: Time): Duration = now().sub(t)

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
// `Sub` uses when both operands carry one) and for `vfs`/`bundled` (`time.Time{}` as a mod time), and
// `Format` in the LOCAL zone for `tsc -b`'s status lines ([format]; the zone offset is the platform's,
// [localZoneOffsetSeconds]). Nothing on the check path reads a clock.

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

    /**
     * `t.Format(layout)` in the local zone (`time.Now()` is local in Go). Go's reference-time tokens: `2006`
     * `06` `01` `1` `Jan` `January` `02` `2` `_2` `Mon` `Monday` `15` `03` `3` `04` `4` `05` `5` `PM` `pm` and
     * fractional seconds (`.000` zero-padded, `.999` trimmed, `,` too); anything else — zone tokens included —
     * is copied literally.
     */
    fun format(layout: String): String {
        val nanos = wall()
        val unixSec = nanos.floorDiv(1_000_000_000L)
        val frac = nanos.mod(1_000_000_000L)
        val local = unixSec + localZoneOffsetSeconds(unixSec)
        val days = local.floorDiv(86_400L)
        val secOfDay = local.mod(86_400L).toInt()
        // civil-from-days (H. Hinnant)
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        val weekday = (days + 4).mod(7L).toInt() // 1970-01-01 was a Thursday; 0 = Sunday
        val hour = secOfDay / 3600
        val minute = secOfDay / 60 % 60
        val second = secOfDay % 60
        fun p2(v: Int) = if (v < 10) "0$v" else "$v"
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val sb = StringBuilder()
        var i = 0
        fun at(t: String) = layout.startsWith(t, i)
        while (i < layout.length) {
            val c = layout[i]
            if ((c == '.' || c == ',') && i + 1 < layout.length && (layout[i + 1] == '0' || layout[i + 1] == '9')) {
                val d = layout[i + 1]
                var j = i + 1
                while (j < layout.length && layout[j] == d) j++
                if (j >= layout.length || !layout[j].isDigit()) {
                    val digits = (frac + 1_000_000_000L).toString().substring(1, 1 + minOf(9, j - i - 1))
                    if (d == '0') sb.append(c).append(digits)
                    else digits.trimEnd('0').let { if (it.isNotEmpty()) sb.append(c).append(it) }
                    i = j
                    continue
                }
            }
            val (text, n) = when {
                at("January") -> MONTHS[month - 1] to 7
                at("Jan") -> MONTHS[month - 1].substring(0, 3) to 3
                at("Monday") -> DAYS[weekday] to 6
                at("Mon") -> DAYS[weekday].substring(0, 3) to 3
                at("2006") -> year.toString().padStart(4, '0') to 4
                at("01") -> p2(month) to 2
                at("02") -> p2(day) to 2
                at("03") -> p2(h12) to 2
                at("04") -> p2(minute) to 2
                at("05") -> p2(second) to 2
                at("06") -> p2(year % 100) to 2
                at("15") -> p2(hour) to 2
                at("_2") -> (if (day < 10) " $day" else "$day") to 2
                at("PM") -> (if (hour >= 12) "PM" else "AM") to 2
                at("pm") -> (if (hour >= 12) "pm" else "am") to 2
                c == '1' -> month.toString() to 1
                c == '2' -> day.toString() to 1
                c == '3' -> h12.toString() to 1
                c == '4' -> minute.toString() to 1
                c == '5' -> second.toString() to 1
                else -> c.toString() to 1
            }
            sb.append(text)
            i += n
        }
        return sb.toString()
    }

    private companion object {
        val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    }
}

/** The local zone's offset from UTC, in seconds, at [unixSeconds] (daylight saving included). */
internal expect fun localZoneOffsetSeconds(unixSeconds: Long): Int

/** Blocks the calling thread for [nanos] nanoseconds. */
internal expect fun platformSleep(nanos: Long)

/**
 * `time.After(d)`: a channel that receives the current time once [d] has passed — a goroutine that sleeps (its run
 * token given back meanwhile, docs/goport-runtime.md § 9a) and then sends into a buffer of one, as Go's timer does.
 */
fun after(d: Duration): com.xemantic.typescript.tsgo.runtime.GoChan<Time>? {
    val ch = com.xemantic.typescript.tsgo.runtime.GoChan.make(com.xemantic.typescript.tsgo.runtime.GoElem({ Time() }), 1)
    com.xemantic.typescript.tsgo.go.sync.goSpawn {
        if (d.value > 0) com.xemantic.typescript.tsgo.go.sync.blockingWait { platformSleep(d.value) }
        ch.trySend(now())
    }
    return ch
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

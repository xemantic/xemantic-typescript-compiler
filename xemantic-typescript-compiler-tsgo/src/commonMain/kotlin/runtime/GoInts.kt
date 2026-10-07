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

package com.xemantic.typescript.tsgo.runtime

// Integer semantics Kotlin does not share with Go (docs/goport-runtime.md § Integers).
//
// Representation (docs/goport-design.md § 3): int/int32/rune and the narrow kinds are Int,
// uint32 is UInt, int64 is Long, uint/uint64/uintptr are ULong. A narrow kind's value is kept in
// range by truncating on conversion AND after every `+ - * << ^` and unary `-`/`^` stored into it.

/** Conversion to `int8` (sign-extended low 8 bits). */
fun goInt8(x: Int): Int = x.toByte().toInt()

/** Conversion to `int8`. */
fun goInt8(x: Long): Int = x.toByte().toInt()

/** Conversion to `uint8`/`byte`. */
fun goUint8(x: Int): Int = x and 0xFF

/** Conversion to `uint8`/`byte`. */
fun goUint8(x: Long): Int = (x and 0xFF).toInt()

/** Conversion to `int16`. */
fun goInt16(x: Int): Int = x.toShort().toInt()

/** Conversion to `int16`. */
fun goInt16(x: Long): Int = x.toShort().toInt()

/** Conversion to `uint16`. */
fun goUint16(x: Int): Int = x and 0xFFFF

/** Conversion to `uint16`. */
fun goUint16(x: Long): Int = (x and 0xFFFF).toInt()

/** `x << n` for a 32-bit kind: Go yields 0 once `n >= 32` (Kotlin masks `n`); a negative `n` panics. */
fun goShl(x: Int, n: Int): Int {
    if (n < 0) goPanic(GoRuntimeError("negative shift amount"))
    return if (n >= 32) 0 else x shl n
}

/** `x >> n` for a SIGNED 32-bit kind: arithmetic; `n >= 32` gives 0 or -1. */
fun goShr(x: Int, n: Int): Int {
    if (n < 0) goPanic(GoRuntimeError("negative shift amount"))
    return if (n >= 32) (if (x < 0) -1 else 0) else x shr n
}

/** `x >> n` for an UNSIGNED narrow kind held in an Int (`uint8`, `uint16`): logical. */
fun goUshr(x: Int, n: Int): Int {
    if (n < 0) goPanic(GoRuntimeError("negative shift amount"))
    return if (n >= 32) 0 else x ushr n
}

/** `x << n` for `int64`. */
fun goShl(x: Long, n: Int): Long {
    if (n < 0) goPanic(GoRuntimeError("negative shift amount"))
    return if (n >= 64) 0L else x shl n
}

/** `x >> n` for `int64`. */
fun goShr(x: Long, n: Int): Long {
    if (n < 0) goPanic(GoRuntimeError("negative shift amount"))
    return if (n >= 64) (if (x < 0) -1L else 0L) else x shr n
}

/** `x << n` for `uint32`. */
fun goShl(x: UInt, n: Int): UInt {
    if (n < 0) goPanic(GoRuntimeError("negative shift amount"))
    return if (n >= 32) 0u else x shl n
}

/** `x >> n` for `uint32`. */
fun goShr(x: UInt, n: Int): UInt {
    if (n < 0) goPanic(GoRuntimeError("negative shift amount"))
    return if (n >= 32) 0u else x shr n
}

/** `x << n` for `uint64`. */
fun goShl(x: ULong, n: Int): ULong {
    if (n < 0) goPanic(GoRuntimeError("negative shift amount"))
    return if (n >= 64) 0uL else x shl n
}

/** `x >> n` for `uint64`. */
fun goShr(x: ULong, n: Int): ULong {
    if (n < 0) goPanic(GoRuntimeError("negative shift amount"))
    return if (n >= 64) 0uL else x shr n
}

/** Integer `a / b`: truncated, panics on zero (`MinValue / -1` wraps, as in Go). */
fun goDiv(a: Int, b: Int): Int = if (b == 0) goPanicDivide() else a / b

/** Integer `a % b`: sign of the dividend, panics on zero. */
fun goRem(a: Int, b: Int): Int = if (b == 0) goPanicDivide() else a % b

/** `int64` division. */
fun goDiv(a: Long, b: Long): Long = if (b == 0L) goPanicDivide() else a / b

/** `int64` remainder. */
fun goRem(a: Long, b: Long): Long = if (b == 0L) goPanicDivide() else a % b

/**
 * `int64(f)` with amd64's CVTTSD2SQ semantics: truncation toward zero, and the "integer
 * indefinite" value `math.MinInt64` for NaN and out-of-range inputs (Kotlin's `toLong` SATURATES
 * instead). The Go spec leaves out-of-range conversions implementation-defined; tsgo is built
 * for amd64/arm64, and on arm64 Go saturates — so this is exact for amd64 only.
 */
fun goFloat64ToInt64(f: Double): Long =
    if (f.isNaN() || f >= 9.223372036854775807E18 || f < -9.223372036854775808E18) Long.MIN_VALUE else f.toLong()

/** `int(f)`/`int32(f)`: via [goFloat64ToInt64], then truncated to 32 bits (the `int` assumption). */
fun goFloat64ToInt(f: Double): Int = goFloat64ToInt64(f).toInt()

/** `uint32(f)`: amd64 converts through int64 and truncates. */
fun goFloat64ToUint32(f: Double): UInt = goFloat64ToInt64(f).toUInt()

/** `uint64(f)`: amd64's two-step conversion (values >= 2^63 subtract 2^63 first). */
fun goFloat64ToUint64(f: Double): ULong =
    if (f >= 9.223372036854775807E18) (goFloat64ToInt64(f - 9.223372036854775808E18).toULong() xor (1uL shl 63))
    else goFloat64ToInt64(f).toULong()

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

package com.xemantic.typescript.tsgo.go.math.bits

/** `bits.Len64(x)`. */
fun len64(x: ULong): Int = 64 - x.countLeadingZeroBits()

/** `bits.Len32(x)`. */
fun len32(x: UInt): Int = 32 - x.countLeadingZeroBits()

/** `bits.Len(x)` (Go `uint` is 64-bit). */
fun len(x: ULong): Int = len64(x)

/** `bits.LeadingZeros64(x)`. */
fun leadingZeros64(x: ULong): Int = x.countLeadingZeroBits()

/** `bits.TrailingZeros64(x)`. */
fun trailingZeros64(x: ULong): Int = x.countTrailingZeroBits()

/** `bits.TrailingZeros32(x)`. */
fun trailingZeros32(x: UInt): Int = x.countTrailingZeroBits()

/** `bits.OnesCount64(x)`. */
fun onesCount64(x: ULong): Int = x.countOneBits()

/** `bits.OnesCount32(x)`. */
fun onesCount32(x: UInt): Int = x.countOneBits()

/** `bits.RotateLeft64(x, k)`. */
fun rotateLeft64(x: ULong, k: Int): ULong = x.rotateLeft(k)

/** `bits.RotateLeft32(x, k)`. */
fun rotateLeft32(x: UInt, k: Int): UInt = x.rotateLeft(k)

/** `bits.OnesCount(x)` (`uint` is 64-bit). */
fun onesCount(x: ULong): Int = x.countOneBits()

/** `bits.OnesCount16(x)`. */
fun onesCount16(x: Int): Int = (x and 0xFFFF).countOneBits()

/** `bits.OnesCount8(x)`. */
fun onesCount8(x: Int): Int = (x and 0xFF).countOneBits()

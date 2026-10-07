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

package com.xemantic.typescript.tsgo.go.github_com.zeebo.xxh3

/**
 * `xxh3.Uint128`. The spike closure only stores and prints a hash (`ast.SourceFile.Hash`,
 * `encoder.SourceFileHash`); it never computes one — `xxh3.Hash128` is called by the compiler
 * package, outside the closure, so the hashing algorithm itself is not ported yet.
 */
class Uint128(var hi: ULong = 0uL, var lo: ULong = 0uL) {
    fun goCopy(): Uint128 = Uint128(hi, lo)

    /** Go struct `==`. */
    fun goEquals(other: Uint128): Boolean = hi == other.hi && lo == other.lo

    fun goHash(): Int = hi.hashCode() * 31 + lo.hashCode()
}

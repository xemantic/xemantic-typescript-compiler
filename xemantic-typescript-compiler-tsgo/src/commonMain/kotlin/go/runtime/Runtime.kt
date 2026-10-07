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

package com.xemantic.typescript.tsgo.go.runtime

import com.xemantic.typescript.tsgo.runtime.Tuple4

/**
 * `runtime.Caller(skip)` → (pc, file, line, ok). The port has no Go source positions, so it
 * always answers `ok = false` (tsgo's only use is `bundled`'s test-only source-directory lookup,
 * which panics on it).
 */
@Suppress("UNUSED_PARAMETER")
fun caller(skip: Int): Tuple4<ULong, String, Int, Boolean> = Tuple4(0uL, "", 0, false)

/** `runtime.GOOS`, `runtime.GOARCH`: the port reports a Linux/amd64-like host (tsgo branches on GOOS only for Windows paths). */
const val GOOS: String = "linux"
const val GOARCH: String = "amd64"

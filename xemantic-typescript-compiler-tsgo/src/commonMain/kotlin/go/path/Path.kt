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

// Parts of this file are translated from the Go standard library (go1.27.1), Copyright The Go
// Authors, used under Go's BSD-style licence: see LICENSE-GO in this module.

package com.xemantic.typescript.tsgo.go.path

import com.xemantic.typescript.tsgo.runtime.Tuple2

// Go's slash-separated `path` package: the same lexical algorithms as the Unix `path/filepath` shim.

/** `path.Clean(p)`. */
fun clean(path: String): String = com.xemantic.typescript.tsgo.go.path.filepath.clean(path)

/** `path.Join(elem...)`. */
fun join(vararg elem: String): String = com.xemantic.typescript.tsgo.go.path.filepath.join(*elem)

/** `path.Dir(p)`. */
fun dir(path: String): String = com.xemantic.typescript.tsgo.go.path.filepath.dir(path)

/** `path.Base(p)`. */
fun base(path: String): String = com.xemantic.typescript.tsgo.go.path.filepath.base(path)

/** `path.IsAbs(p)`. */
fun isAbs(path: String): Boolean = path.startsWith("/")

/** `path.Split(p)`: (dir, file) split immediately after the final slash. */
fun split(path: String): Tuple2<String, String> {
    val i = path.lastIndexOf('/')
    return Tuple2(path.substring(0, i + 1), path.substring(i + 1))
}

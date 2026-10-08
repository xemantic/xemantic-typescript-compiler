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

package com.xemantic.typescript.tsgo.go.path.filepath

// `path/filepath` with Go's UNIX semantics (`Separator` = '/'), purely lexical. tsgo's checker
// path normalizes its own paths (`tspath`); this is reached only from `bundled`'s source-tree
// lookup. A Windows host is not modelled.

/** `filepath.Separator`, `filepath.ListSeparator`. */
const val Separator: Int = '/'.code
const val ListSeparator: Int = ':'.code

/** `filepath.FromSlash(path)`: identity on Unix. */
fun fromSlash(path: String): String = path

/** `filepath.ToSlash(path)`: identity on Unix. */
fun toSlash(path: String): String = path

/** `filepath.Clean(path)`: Go's lexical cleaning (`path.Clean`). */
fun clean(path: String): String {
    if (path.isEmpty()) return "."
    val rooted = path[0] == '/'
    val n = path.length
    val out = StringBuilder(n)
    var r = 0
    var dotdot = 0
    if (rooted) {
        out.append('/')
        r = 1
        dotdot = 1
    }
    while (r < n) {
        when {
            path[r] == '/' -> r++
            path[r] == '.' && (r + 1 == n || path[r + 1] == '/') -> r++
            path[r] == '.' && path[r + 1] == '.' && (r + 2 == n || path[r + 2] == '/') -> {
                r += 2
                when {
                    out.length > dotdot -> {
                        var w = out.length - 1
                        while (w > dotdot && out[w] != '/') w--
                        out.setLength(w)
                    }
                    !rooted -> {
                        if (out.isNotEmpty()) out.append('/')
                        out.append("..")
                        dotdot = out.length
                    }
                }
            }
            else -> {
                if (rooted && out.length != 1 || !rooted && out.isNotEmpty()) out.append('/')
                while (r < n && path[r] != '/') {
                    out.append(path[r])
                    r++
                }
            }
        }
    }
    if (out.isEmpty()) return "."
    return out.toString()
}

/** `filepath.Join(elem...)`: the non-empty elements joined with '/', then [clean]; "" if all are empty. */
fun join(vararg elem: String): String {
    for ((i, e) in elem.withIndex()) {
        if (e.isNotEmpty()) return clean(elem.drop(i).filter { it.isNotEmpty() }.joinToString("/"))
    }
    return ""
}

/** `filepath.Dir(path)`: all but the last element, cleaned. */
fun dir(path: String): String {
    var i = path.length - 1
    while (i >= 0 && path[i] != '/') i--
    return clean(path.substring(0, i + 1))
}

/** `filepath.Base(path)`. */
fun base(path: String): String {
    if (path.isEmpty()) return "."
    var p = path
    while (p.isNotEmpty() && p[p.length - 1] == '/') p = p.substring(0, p.length - 1)
    val i = p.lastIndexOf('/')
    if (i >= 0) p = p.substring(i + 1)
    if (p.isEmpty()) return "/"
    return p
}

/** `filepath.Ext(path)`. */
fun ext(path: String): String {
    var i = path.length - 1
    while (i >= 0 && path[i] != '/') {
        if (path[i] == '.') return path.substring(i)
        i--
    }
    return ""
}

/** `filepath.IsAbs(path)`. */
fun isAbs(path: String): Boolean = path.startsWith("/")

/**
 * `filepath.Abs(path)` for an ABSOLUTE path (its `Clean`) — the only kind tsgo passes ((TSGO.5): `vfs/osvfs`
 * after `nativepath.Realpath`). A relative one would need the process's working directory, which the port
 * never consults (a command line passes its own current directory explicitly): an error.
 */
fun abs(path: String): com.xemantic.typescript.tsgo.runtime.Tuple2<String, com.xemantic.typescript.tsgo.runtime.GoError?> =
    if (isAbs(path)) com.xemantic.typescript.tsgo.runtime.Tuple2(clean(path), null)
    else com.xemantic.typescript.tsgo.runtime.Tuple2("", com.xemantic.typescript.tsgo.runtime.GoPlainError("filepath.Abs: relative path $path"))

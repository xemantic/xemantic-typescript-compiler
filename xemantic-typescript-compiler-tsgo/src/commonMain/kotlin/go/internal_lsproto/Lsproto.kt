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

package com.xemantic.typescript.tsgo.lsp.lsproto

import com.xemantic.typescript.tsgo.runtime.GoBasicValue
import com.xemantic.typescript.tsgo.runtime.GoPanic

// tsgo's generated LSP protocol package (`internal/lsp/lsproto`, ~18k lines), HAND-WRITTEN as the one
// type the ported API session names ((TSGO.3-b), docs/goport-api.md): `DocumentUri`, which an API
// `DocumentIdentifier` may carry instead of a file name.

/** `lsproto.DocumentUri`. */
@kotlin.jvm.JvmInline
value class DocumentUri(val value: String) : GoBasicValue {
    override val goRaw: Any get() = value

    override fun goWithRaw(raw: Any): GoBasicValue = DocumentUri(raw as String)

    /**
     * `DocumentUri.FileName` (lsp.go): a bundled lib name as is; a `file://` URI as its decoded path
     * (`//host/path` with an authority, the Windows drive-letter fix of `fixWindowsURIPath`); any other
     * scheme as tsgo's escaped `^/scheme/authority/path` form.
     */
    fun fileName(): String {
        if (value.startsWith("bundled:///")) return value
        if (value.startsWith("file://")) {
            val rest = value.removePrefix("file://")
            val slash = rest.indexOf('/')
            val host = if (slash < 0) rest else rest.substring(0, slash)
            val path = percentDecode(if (slash < 0) "" else rest.substring(slash))
            if (host.isNotEmpty()) return "//$host$path"
            // fixWindowsURIPath: "/c:/x" → "c:/x"
            if (path.length >= 3 && path[0] == '/' && path[1].isLetter() && path[2] == ':') return path.substring(1)
            return path
        }
        val colon = value.indexOf(':')
        if (colon < 0) throw GoPanic("invalid URI: $value")
        val scheme = value.substring(0, colon)
        var path = value.substring(colon + 1)
        var authority = "ts-nul-authority"
        if (path.startsWith("//")) {
            val rest = path.substring(2)
            val s = rest.indexOf('/')
            if (s < 0) throw GoPanic("invalid URI: $value")
            authority = rest.substring(0, s)
            path = rest.substring(s + 1)
        }
        return "^/$scheme/$authority/$path"
    }
}

/** `url.Parse(...).Path`'s percent-decoding of a byte string (each char one byte). */
internal fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val b = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val v = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (v != null) {
                b.append(v.toChar())
                i += 3
                continue
            }
        }
        b.append(c)
        i++
    }
    return b.toString()
}

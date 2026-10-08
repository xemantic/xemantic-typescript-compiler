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

package com.xemantic.typescript.tsgo.ls.lsconv

import com.xemantic.typescript.tsgo.lsp.lsproto.DocumentUri
import com.xemantic.typescript.tsgo.runtime.GoPanic

// tsgo's `internal/ls/lsconv` (LSP position/URI converters), HAND-WRITTEN as the one function the ported
// API session names ((TSGO.3-b), docs/goport-api.md): `DocumentIdentifier.ToURI`'s file-name-to-URI.

/** `lsconv.FileNameToDocumentURI` (converters.go). */
fun fileNameToDocumentURI(fileName: String): DocumentUri {
    if (com.xemantic.typescript.tsgo.bundled.isBundled(fileName)) return DocumentUri(fileName)
    if (com.xemantic.typescript.tsgo.tspath.isDynamicFileName(fileName)) {
        val s = fileName.substring(2)
        val i = s.indexOf('/')
        if (i < 0) throw GoPanic("invalid file name: $fileName")
        val scheme = s.substring(0, i)
        val rest = s.substring(i + 1)
        val j = rest.indexOf('/')
        if (j < 0) throw GoPanic("invalid file name: $fileName")
        val authority = rest.substring(0, j)
        val path = rest.substring(j + 1)
        if (authority == "ts-nul-authority") return DocumentUri("$scheme:$path")
        return DocumentUri("$scheme://$authority/$path")
    }
    val (volume0, rest0, _) = com.xemantic.typescript.tsgo.tspath.splitVolumePath(fileName)
    val volume = if (volume0 != "") "/" + extraEscape(volume0) else ""
    val name = rest0.removePrefix("//")
    return DocumentUri("file://" + volume + name.split('/').joinToString("/") { extraEscape(pathEscape(it)) })
}

private val extra = mapOf(
    ':' to "%3A", '/' to "%2F", '?' to "%3F", '#' to "%23", '[' to "%5B", ']' to "%5D", '@' to "%40",
    '!' to "%21", '$' to "%24", '&' to "%26", '\'' to "%27", '(' to "%28", ')' to "%29", '*' to "%2A",
    '+' to "%2B", ',' to "%2C", ';' to "%3B", '=' to "%3D", ' ' to "%20",
)

/** `extraEscapeReplacer` (converters.go). */
private fun extraEscape(s: String): String = buildString { for (c in s) append(extra[c] ?: c.toString()) }

/** `url.PathEscape` over a byte string: everything but unreserved, `$&+,/:;=@` is percent-encoded (and `/` too). */
private fun pathEscape(s: String): String = buildString {
    for (c in s) {
        val b = c.code
        val keep = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~$&+,:;=@"
        if (keep) append(c) else {
            append('%')
            append("0123456789ABCDEF"[b shr 4])
            append("0123456789ABCDEF"[b and 15])
        }
    }
}

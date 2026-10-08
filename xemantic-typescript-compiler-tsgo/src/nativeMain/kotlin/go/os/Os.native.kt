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

@file:OptIn(ExperimentalForeignApi::class)

package com.xemantic.typescript.tsgo.go.os

// (TSGO.6) The host file system on Kotlin/Native: POSIX `stat` / `opendir` / `open`+`read`. Like the
// JVM's `java.io.File`, `stat` FOLLOWS symbolic links (a link to a directory is a directory).

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.closedir
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.stat

/** `st_mode` and `st_size` of [path] (following links); null when it cannot be stat'ed. */
private fun statOf(path: String): Pair<UInt, Long>? = memScoped {
    val st = alloc<stat>()
    if (stat(path, st.ptr) != 0) null else st.st_mode to st.st_size
}

private fun hasType(mode: UInt, type: Int): Boolean = (mode and S_IFMT.toUInt()) == type.toUInt()

internal actual fun platformIsDir(path: String): Boolean? = statOf(path)?.let { hasType(it.first, S_IFDIR) }

internal actual fun platformListDir(path: String): List<Pair<String, Boolean>>? {
    val dir = opendir(path) ?: return null
    val out = ArrayList<Pair<String, Boolean>>()
    try {
        while (true) {
            val entry = readdir(dir)?.pointed ?: break
            val name = entry.d_name.toKString()
            if (name == "." || name == "..") continue
            val child = if (path.endsWith("/")) path + name else "$path/$name"
            out += name to (statOf(child)?.let { hasType(it.first, S_IFDIR) } ?: false)
        }
    } finally {
        closedir(dir)
    }
    return out
}

internal actual fun platformReadFile(path: String): ByteArray? {
    val st = statOf(path) ?: return null
    if (!hasType(st.first, S_IFREG)) return null
    val fd = open(path, O_RDONLY)
    if (fd < 0) return null
    try {
        var buf = ByteArray(st.second.coerceIn(4096L, Int.MAX_VALUE.toLong() - 8).toInt())
        var n = 0
        while (true) {
            if (n == buf.size) buf = buf.copyOf(buf.size * 2)
            val got = buf.usePinned { p -> read(fd, p.addressOf(n), (buf.size - n).toULong()) }
            if (got < 0L) return null
            if (got == 0L) break
            n += got.toInt()
        }
        return buf.copyOf(n)
    } finally {
        close(fd)
    }
}

internal actual fun platformSize(path: String): Long = statOf(path)?.second ?: 0L

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
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.getenv
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.readlink
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.unlink
import platform.posix.utimbuf
import platform.posix.utime


/** `st_mode` and `st_size` of [path] (following links); null when it cannot be stat'ed. */
private fun statOf(path: String): Pair<UInt, Long>? = memScoped {
    val st = alloc<stat>()
    if (stat(path, st.ptr) != 0) null else st.st_mode to st.st_size
}

private fun hasType(mode: UInt, type: Int): Boolean = (mode and S_IFMT.toUInt()) == type.toUInt()

internal actual fun platformIsDir(path: String): Boolean? = statOf(path)?.let { hasType(it.first, S_IFDIR) }

internal actual fun platformListDir(path: String): List<Pair<String, Char>>? {
    val dir = opendir(path) ?: return null
    val out = ArrayList<Pair<String, Char>>()
    try {
        while (true) {
            val entry = readdir(dir)?.pointed ?: break
            val name = entry.d_name.toKString()
            if (name == "." || name == "..") continue
            val child = if (path.endsWith("/")) path + name else "$path/$name"
            // Go's ReadDir types an entry by lstat: the link itself, not its target.
            val mode = lstatMode(child)
            val kind = when {
                mode == null -> 'o'
                hasType(mode, S_IFLNK) -> 'l'
                hasType(mode, S_IFDIR) -> 'd'
                hasType(mode, S_IFREG) -> 'f'
                else -> 'o'
            }
            out += name to kind
        }
    } finally {
        closedir(dir)
    }
    return out
}

/** `st_mode` of [path] NOT following a final symbolic link; null when it cannot be lstat'ed. */
private fun lstatMode(path: String): UInt? = memScoped {
    val st = alloc<stat>()
    if (lstat(path, st.ptr) != 0) null else st.st_mode
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

// ---- (TSGO.5) writing, for the command line (vfs/osvfs): POSIX open/write/close, mkdir, unlink/rmdir, utime.

internal actual fun platformOpenWrite(path: String, create: Boolean, append: Boolean, truncate: Boolean): Pair<Any?, String?> {
    var flags = O_WRONLY
    if (create) flags = flags or O_CREAT
    if (append) flags = flags or O_APPEND
    if (truncate) flags = flags or O_TRUNC
    val fd = open(path, flags, 0x1B6) // 0666; the umask applies
    if (fd < 0) return null to (if (errno == ENOENT) "not exist" else "open failed (errno $errno)")
    return fd to null
}

internal actual fun platformWrite(handle: Any, bytes: ByteArray): String? {
    val fd = handle as Int
    var off = 0
    while (off < bytes.size) {
        val n = bytes.usePinned { p -> platform.posix.write(fd, p.addressOf(off), (bytes.size - off).toULong()) }
        if (n < 0L) return "write failed (errno $errno)"
        off += n.toInt()
    }
    return null
}

internal actual fun platformClose(handle: Any): String? = if (close(handle as Int) == 0) null else "close failed (errno $errno)"

internal actual fun platformMkdirAll(path: String): String? {
    if (statOf(path)?.let { hasType(it.first, S_IFDIR) } == true) return null
    val parent = path.trimEnd('/').substringBeforeLast('/', "")
    if (parent.isNotEmpty()) platformMkdirAll(parent)?.let { return it }
    if (mkdir(path, 0x1FFu) != 0 && errno != EEXIST) return "mkdir failed (errno $errno)" // 0777; the umask applies
    return null
}

internal actual fun platformRemoveAll(path: String): String? {
    val mode = lstatMode(path) ?: return null
    if (hasType(mode, S_IFDIR)) {
        val entries = platformListDir(path) ?: return "readdir failed"
        for ((name, _) in entries) platformRemoveAll("$path/$name")?.let { return it }
        return if (rmdir(path) == 0) null else "rmdir failed (errno $errno)"
    }
    return if (unlink(path) == 0) null else "unlink failed (errno $errno)"
}

internal actual fun platformSetModTime(path: String, epochMillis: Long): String? = memScoped {
    val times = alloc<utimbuf>()
    times.actime = epochMillis / 1000
    times.modtime = epochMillis / 1000
    if (utime(path, times.ptr) == 0) null else "utime failed (errno $errno)"
}

internal actual fun platformModTime(path: String): Long = memScoped {
    val st = alloc<stat>()
    if (stat(path, st.ptr) != 0) 0L else st.st_mtim.tv_sec * 1000L + st.st_mtim.tv_nsec / 1_000_000L
}

internal actual fun platformExecutable(): String? {
    val buf = ByteArray(4096)
    val n = buf.usePinned { p -> readlink("/proc/self/exe", p.addressOf(0), buf.size.toULong()) }
    return if (n <= 0L) null else buf.decodeToString(0, n.toInt())
}

internal actual fun platformUserCacheDir(): String? =
    getenv("XDG_CACHE_HOME")?.toKString()?.takeIf { it.isNotEmpty() }
        ?: getenv("HOME")?.toKString()?.takeIf { it.isNotEmpty() }?.let { "$it/.cache" }

internal actual fun platformTempDir(): String = getenv("TMPDIR")?.toKString()?.takeIf { it.isNotEmpty() } ?: "/tmp"

internal actual fun platformGetenv(name: String): String? = getenv(name)?.toKString()

internal actual fun goLatin1String(bytes: ByteArray): String {
    val chars = CharArray(bytes.size)
    for (i in bytes.indices) chars[i] = (bytes[i].toInt() and 0xFF).toChar()
    return chars.concatToString()
}

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

package com.xemantic.typescript.tsgo.go.os

import java.io.File

internal actual fun platformIsDir(path: String): Boolean? = File(path).let { if (it.exists()) it.isDirectory else null }

/**
 * `os.ReadDir`'s names and entry kinds. ONE `lstat` per entry (`readAttributes` NOFOLLOW): the three predicate
 * calls it replaces were up to three, and a 1,445-file project's config phase was ~40% listing (docs/goport-perf.md
 * § 16). Go reads the kind from `readdir`'s `d_type` without any. An entry gone between the listing and its `lstat`
 * is skipped, as Go's `ReadDir` drops one that vanished.
 */
internal actual fun platformListDir(path: String): List<Pair<String, Char>>? {
    val names = File(path).list() ?: return null
    val dir = java.nio.file.Path.of(path)
    val out = ArrayList<Pair<String, Char>>(names.size)
    for (name in names) {
        val a = try {
            java.nio.file.Files.readAttributes(dir.resolve(name), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        } catch (_: java.io.IOException) {
            continue
        }
        val kind = when {
            a.isSymbolicLink -> 'l'
            a.isDirectory -> 'd'
            a.isRegularFile -> 'f'
            else -> 'o'
        }
        out += name to kind
    }
    return out
}

internal actual fun platformReadFile(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

internal actual fun platformSize(path: String): Long = File(path).length()

internal actual fun platformOpenWrite(path: String, create: Boolean, append: Boolean, truncate: Boolean): Pair<Any?, String?> {
    val f = File(path)
    if (!create && !f.exists()) return null to "not exist"
    if (f.absoluteFile.parentFile?.isDirectory == false) return null to "not exist"
    return try {
        java.io.FileOutputStream(f, append && !truncate) to null
    } catch (e: java.io.IOException) {
        null to (e.message ?: "open failed")
    }
}

internal actual fun platformWrite(handle: Any, bytes: ByteArray): String? = try {
    (handle as java.io.FileOutputStream).write(bytes)
    null
} catch (e: java.io.IOException) {
    e.message ?: "write failed"
}

internal actual fun platformClose(handle: Any): String? = try {
    (handle as java.io.FileOutputStream).close()
    null
} catch (e: java.io.IOException) {
    e.message ?: "close failed"
}

internal actual fun platformMkdirAll(path: String): String? = try {
    java.nio.file.Files.createDirectories(java.nio.file.Paths.get(path))
    null
} catch (e: java.io.IOException) {
    e.message ?: "mkdir failed"
}

internal actual fun platformRemoveAll(path: String): String? {
    val f = File(path)
    if (!f.exists() && !java.nio.file.Files.isSymbolicLink(f.toPath())) return null
    return if (f.deleteRecursively()) null else "remove failed"
}

internal actual fun platformSetModTime(path: String, epochMillis: Long): String? =
    if (File(path).setLastModified(epochMillis)) null else "setting the modification time failed"

internal actual fun platformModTime(path: String): Long = File(path).lastModified()

internal actual fun platformExecutable(): String? = ProcessHandle.current().info().command().orElse(null)

internal actual fun platformUserCacheDir(): String? =
    System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotEmpty() } ?: System.getenv("HOME")?.takeIf { it.isNotEmpty() }?.let { "$it/.cache" }

internal actual fun platformTempDir(): String = System.getenv("TMPDIR")?.takeIf { it.isNotEmpty() } ?: "/tmp"

internal actual fun platformGetenv(name: String): String? = System.getenv(name)

/** ISO-8859-1 maps every byte to the `Char` of the same code: a JDK intrinsic copy into a compact string. */
internal actual fun goLatin1String(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

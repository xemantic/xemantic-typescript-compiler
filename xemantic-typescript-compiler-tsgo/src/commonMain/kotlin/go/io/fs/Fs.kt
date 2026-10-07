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

package com.xemantic.typescript.tsgo.go.io.fs

// Go's `io/fs` types that tsgo's `vfs` re-exports: the interfaces a file system answers with,
// `FileMode` (exact, incl. `String()`), the sentinel errors and the walk sentinels. No file
// system lives here — the port's file access goes through tsgo's own `vfs.FS`.

import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.Tuple2

/** `fs.FileMode` (`uint32`): type bits in the high bits, Unix permissions in the low 9. */
@kotlin.jvm.JvmInline
value class FileMode(val value: UInt) {
    /** `m.IsDir()`. */
    fun isDir(): Boolean = (value and ModeDir.value) != 0u

    /** `m.IsRegular()`. */
    fun isRegular(): Boolean = (value and ModeType.value) == 0u

    /** `m.Perm()`. */
    fun perm(): FileMode = FileMode(value and ModePerm.value)

    /** `m.Type()`. */
    fun type(): FileMode = FileMode(value and ModeType.value)

    /** `m.String()`: Go's `dalTLDpSugct?` letters then `rwxrwxrwx`. */
    fun string(): String {
        val str = "dalTLDpSugct?"
        val sb = StringBuilder()
        for ((i, c) in str.withIndex()) {
            if ((value and (1u shl (32 - 1 - i))) != 0u) sb.append(c)
        }
        if (sb.isEmpty()) sb.append('-')
        val rwx = "rwxrwxrwx"
        for ((i, c) in rwx.withIndex()) {
            sb.append(if ((value and (1u shl (9 - 1 - i))) != 0u) c else '-')
        }
        return sb.toString()
    }

    override fun toString(): String = string()
}

val ModeDir: FileMode = FileMode(1u shl 31)
val ModeAppend: FileMode = FileMode(1u shl 30)
val ModeExclusive: FileMode = FileMode(1u shl 29)
val ModeTemporary: FileMode = FileMode(1u shl 28)
val ModeSymlink: FileMode = FileMode(1u shl 27)
val ModeDevice: FileMode = FileMode(1u shl 26)
val ModeNamedPipe: FileMode = FileMode(1u shl 25)
val ModeSocket: FileMode = FileMode(1u shl 24)
val ModeSetuid: FileMode = FileMode(1u shl 23)
val ModeSetgid: FileMode = FileMode(1u shl 22)
val ModeCharDevice: FileMode = FileMode(1u shl 21)
val ModeSticky: FileMode = FileMode(1u shl 20)
val ModeIrregular: FileMode = FileMode(1u shl 19)
val ModeType: FileMode = FileMode(
    ModeDir.value or ModeSymlink.value or ModeNamedPipe.value or ModeSocket.value or
        ModeDevice.value or ModeCharDevice.value or ModeIrregular.value,
)
val ModePerm: FileMode = FileMode(511u)

/** `fs.FileInfo`. */
interface FileInfo {
    fun name(): String
    fun size(): Long
    fun mode(): FileMode
    fun modTime(): Time
    fun isDir(): Boolean
    fun sys(): Any?
}

/** `fs.DirEntry`. */
interface DirEntry {
    fun name(): String
    fun isDir(): Boolean
    fun type(): FileMode
    fun info(): Tuple2<FileInfo?, GoError?>
}

private class DirInfo(private val fileInfo: FileInfo) : DirEntry, com.xemantic.typescript.tsgo.go.fmt.Stringer {
    override fun name(): String = fileInfo.name()
    override fun isDir(): Boolean = fileInfo.isDir()
    override fun type(): FileMode = fileInfo.mode().type()
    override fun info(): Tuple2<FileInfo?, GoError?> = Tuple2(fileInfo, null)
    override fun string(): String = formatDirEntry(this)
    override fun toString(): String = string()
}

/** `fs.FileInfoToDirEntry(info)`: nil for a nil [info]. */
fun fileInfoToDirEntry(info: FileInfo?): DirEntry? = if (info == null) null else DirInfo(info)

/** `fs.FormatDirEntry(dir)`: `"d name/"` or `"- name"` style, as Go. */
fun formatDirEntry(dir: DirEntry): String {
    val name = dir.name()
    val mode = dir.type().string()
    val b = StringBuilder(mode.length + 1 + name.length + 1)
    b.append(mode, 0, mode.length - 9) // the type letters without permission bits
    b.append(' ')
    b.append(name)
    if (dir.isDir()) b.append('/')
    return b.toString()
}

/** `fs.WalkDirFunc`: `func(path string, d DirEntry, err error) error`. */
typealias WalkDirFunc = (String, DirEntry?, GoError?) -> GoError?

/** `fs.ErrInvalid` etc. (vars, so lowerCamelCase): the `oserror` sentinels (`errors.New` values; compared by identity, as in Go). */
val errInvalid: GoError = GoPlainError("invalid argument")
val errPermission: GoError = GoPlainError("permission denied")
val errExist: GoError = GoPlainError("file already exists")
val errNotExist: GoError = GoPlainError("file does not exist")
val errClosed: GoError = GoPlainError("file already closed")

/** `fs.SkipDir` (`skipDir`), `fs.SkipAll` (`skipAll`). */
val skipDir: GoError = GoPlainError("skip this directory")
val skipAll: GoError = GoPlainError("skip everything and stop the walk")

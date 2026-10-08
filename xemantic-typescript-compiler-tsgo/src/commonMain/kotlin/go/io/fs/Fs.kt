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
// `FileMode` (exact, incl. `String()`), the sentinel errors and the walk sentinels; and (for the
// ported test harness's `vfstest`/`iovfs`, (TSGO.2)) the `FS` family and the functions over it
// (`ReadFile`, `ReadDir`, `Stat`, `Sub`, `WalkDir`), translated from go1.27.1's `io/fs`.

import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoUnwrapper
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

// ---------------------------------------------------------------- the FS family

/** `fs.FS`. */
interface FS {
    fun open(name: String): Tuple2<File?, GoError?>
}

/** `fs.File`. */
interface File {
    fun stat(): Tuple2<FileInfo?, GoError?>
    fun read(p0: GoSlice<Int>): Tuple2<Int, GoError?>
    fun close(): GoError?
}

/** `fs.ReadDirFile`. */
interface ReadDirFile : File {
    fun readDir(n: Int): Tuple2<GoSlice<DirEntry?>, GoError?>
}

/** `fs.ReadFileFS`. */
interface ReadFileFS : FS {
    fun readFile(name: String): Tuple2<GoSlice<Int>, GoError?>
}

/** `fs.ReadDirFS`. */
interface ReadDirFS : FS {
    fun readDir(name: String): Tuple2<GoSlice<DirEntry?>, GoError?>
}

/** `fs.StatFS`. */
interface StatFS : FS {
    fun stat(name: String): Tuple2<FileInfo?, GoError?>
}

/** `fs.SubFS`. */
interface SubFS : FS {
    fun sub(dir: String): Tuple2<FS?, GoError?>
}

/** `fs.PathError`. */
class PathError(var op: String = "", var path: String = "", var err: GoError? = null) : GoError, GoUnwrapper {
    override fun error(): String = op + " " + path + ": " + err!!.error()
    override fun unwrap(): GoError? = err
    fun goCopy(): PathError = PathError(op, path, err)
    override fun toString(): String = error()
}

/** `fs.ValidPath(name)`. */
fun validPath(name0: String): Boolean {
    if (!com.xemantic.typescript.tsgo.go.unicode.utf8.validString(name0)) return false
    if (name0 == ".") return true
    var name = name0
    while (true) {
        var i = 0
        while (i < name.length && name[i] != '/') i++
        val elem = name.substring(0, i)
        if (elem == "" || elem == "." || elem == "..") return false
        if (i == name.length) return true
        name = name.substring(i + 1)
    }
}

/** `fs.ReadFile(fsys, name)`. */
fun readFile(fsys: FS?, name: String): Tuple2<GoSlice<Int>, GoError?> {
    if (fsys is ReadFileFS) return fsys.readFile(name)
    val (file, err) = fsys!!.open(name)
    if (err != null) return Tuple2(GoElem.BYTE.nilSlice, err)
    try {
        var size = 0
        val (info, serr) = file!!.stat()
        if (serr == null) {
            val size64 = info!!.size()
            if (size64.toInt().toLong() == size64) size = size64.toInt()
        }
        var data = GoSlice.make(GoElem.BYTE, 0, size + 1)
        while (true) {
            if (data.len >= data.cap) {
                val d = data.slice(0, data.cap).append1(0)
                data = d.slice(0, data.len)
            }
            val (n, rerr) = file.read(data.slice(data.len, data.cap))
            data = data.slice(0, data.len + n)
            if (rerr != null) return Tuple2(data, if (rerr === com.xemantic.typescript.tsgo.go.io.EOF) null else rerr)
        }
    } finally {
        file!!.close()
    }
}

/** `fs.Stat(fsys, name)`. */
fun stat(fsys: FS?, name: String): Tuple2<FileInfo?, GoError?> {
    if (fsys is StatFS) return fsys.stat(name)
    val (file, err) = fsys!!.open(name)
    if (err != null) return Tuple2(null, err)
    try {
        return file!!.stat()
    } finally {
        file!!.close()
    }
}

/** `fs.ReadDir(fsys, name)`: sorted by name. */
fun readDir(fsys: FS?, name: String): Tuple2<GoSlice<DirEntry?>, GoError?> {
    if (fsys is ReadDirFS) return fsys.readDir(name)
    val (file, err) = fsys!!.open(name)
    if (err != null) return Tuple2(GoElem.ref<DirEntry?>().nilSlice, err)
    try {
        val dir = file as? ReadDirFile
            ?: return Tuple2(GoElem.ref<DirEntry?>().nilSlice, PathError("readdir", name, GoPlainError("not implemented")))
        val (list, lerr) = dir.readDir(-1)
        com.xemantic.typescript.tsgo.go.slices.sortFunc(list) { a, b -> com.xemantic.typescript.tsgo.go.strings.compare(a!!.name(), b!!.name()) }
        return Tuple2(list, lerr)
    } finally {
        file!!.close()
    }
}

/** `fs.Sub(fsys, dir)`. */
fun sub(fsys: FS?, dir: String): Tuple2<FS?, GoError?> {
    if (!validPath(dir)) return Tuple2(null, PathError("sub", dir, errInvalid))
    if (dir == ".") return Tuple2(fsys, null)
    if (fsys is SubFS) return fsys.sub(dir)
    return Tuple2(SubFSImpl(fsys!!, dir), null)
}

/** `fs.subFS` (Glob, ReadLink and Lstat are not carried: the port's callers never ask). */
private class SubFSImpl(private val fsys: FS, private val dir: String) : ReadDirFS, ReadFileFS, SubFS {
    private fun fullName(op: String, name: String): Tuple2<String, GoError?> {
        if (!validPath(name)) return Tuple2("", PathError(op, name, errInvalid))
        return Tuple2(com.xemantic.typescript.tsgo.go.path.join(dir, name), null)
    }

    private fun shorten(name: String): Tuple2<String, Boolean> {
        if (name == dir) return Tuple2(".", true)
        if (name.length >= dir.length + 2 && name[dir.length] == '/' && name.substring(0, dir.length) == dir) {
            return Tuple2(name.substring(dir.length + 1), true)
        }
        return Tuple2("", false)
    }

    private fun fixErr(err: GoError?): GoError? {
        if (err is PathError) {
            val (short, ok) = shorten(err.path)
            if (ok) err.path = short
        }
        return err
    }

    override fun open(name: String): Tuple2<File?, GoError?> {
        val (full, err) = fullName("open", name)
        if (err != null) return Tuple2(null, err)
        val (file, ferr) = fsys.open(full)
        return Tuple2(file, fixErr(ferr))
    }

    override fun readDir(name: String): Tuple2<GoSlice<DirEntry?>, GoError?> {
        val (full, err) = fullName("read", name)
        if (err != null) return Tuple2(GoElem.ref<DirEntry?>().nilSlice, err)
        val (d, derr) = readDir(fsys, full)
        return Tuple2(d, fixErr(derr))
    }

    override fun readFile(name: String): Tuple2<GoSlice<Int>, GoError?> {
        val (full, err) = fullName("read", name)
        if (err != null) return Tuple2(GoElem.BYTE.nilSlice, err)
        val (d, derr) = readFile(fsys, full)
        return Tuple2(d, fixErr(derr))
    }

    override fun sub(dir: String): Tuple2<FS?, GoError?> {
        if (dir == ".") return Tuple2(this, null)
        val (full, err) = fullName("sub", dir)
        if (err != null) return Tuple2(null, err)
        return Tuple2(SubFSImpl(fsys, full), null)
    }
}

private fun walkDirImpl(fsys: FS?, name: String, d: DirEntry, walkDirFn: WalkDirFunc): GoError? {
    run {
        val err = walkDirFn(name, d, null)
        if (err != null || !d.isDir()) {
            return if (err === skipDir && d.isDir()) null else err
        }
    }
    val (dirs, err0) = readDir(fsys, name)
    if (err0 != null) {
        val err = walkDirFn(name, d, err0)
        if (err != null) {
            return if (err === skipDir && d.isDir()) null else err
        }
    }
    for (i in 0 until dirs.len) {
        val d1 = dirs[i]!!
        val name1 = com.xemantic.typescript.tsgo.go.path.join(name, d1.name())
        val err = walkDirImpl(fsys, name1, d1, walkDirFn)
        if (err != null) {
            if (err === skipDir) break
            return err
        }
    }
    return null
}

/** `fs.WalkDir(fsys, root, fn)`. */
fun walkDir(fsys: FS?, root: String, fn: WalkDirFunc?): GoError? {
    val (info, err0) = stat(fsys, root)
    val err = if (err0 != null) fn!!(root, null, err0) else walkDirImpl(fsys, root, fileInfoToDirEntry(info)!!, fn!!)
    if (err === skipDir || err === skipAll) return null
    return err
}

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

package com.xemantic.typescript.tsgo.go.testing.fstest

// `testing/fstest`'s `MapFS` — the in-memory file system under tsgo's `vfstest.MapFS` (the ported test
// harness's file system, (TSGO.2)). `Open` and its file/dir/info types are Go's; `Glob`, `ReadLink`
// and `Lstat` are not carried (`vfstest` uses only `Open`, and states so).

import com.xemantic.typescript.tsgo.go.io.fs.DirEntry
import com.xemantic.typescript.tsgo.go.io.fs.File
import com.xemantic.typescript.tsgo.go.io.fs.FileInfo
import com.xemantic.typescript.tsgo.go.io.fs.FileMode
import com.xemantic.typescript.tsgo.go.io.fs.ModeDir
import com.xemantic.typescript.tsgo.go.io.fs.ModeSymlink
import com.xemantic.typescript.tsgo.go.io.fs.PathError
import com.xemantic.typescript.tsgo.go.io.fs.ReadDirFile
import com.xemantic.typescript.tsgo.go.io.fs.errInvalid
import com.xemantic.typescript.tsgo.go.io.fs.errNotExist
import com.xemantic.typescript.tsgo.go.io.fs.validPath
import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goBytesToString
import com.xemantic.typescript.tsgo.runtime.goCopy

/** `fstest.MapFS`: `map[string]*MapFile`, keyed by slash-separated paths without a leading slash. */
typealias MapFS = GoMap<String, MapFile?>

/** `fstest.MapFile`. */
class MapFile(
    var data: GoSlice<Int> = GoElem.BYTE.nilSlice,
    var mode: FileMode = FileMode(0u),
    var modTime: Time = Time(),
    var sys: Any? = null,
) {
    fun goCopy(): MapFile = MapFile(data, mode, modTime, sys)

    companion object {
        val ELEM: GoElem<MapFile> = GoElem({ MapFile() }, { it.goCopy() })
    }
}

private val DIR_FILE_MODE = FileMode(ModeDir.value or 365u) // fs.ModeDir | 0555

/** `fsys.Open(name)`. */
fun MapFS.open(name: String): Tuple2<File?, GoError?> {
    if (!validPath(name)) return Tuple2(null, PathError("open", name, errNotExist))
    val (realName, ok) = resolveSymlinks(name)
    if (!ok) return Tuple2(null, PathError("open", name, errNotExist))
    var file = this[realName]
    if (file != null && (file.mode.value and ModeDir.value) == 0u) {
        return Tuple2(OpenMapFile(name, MapFileInfo(com.xemantic.typescript.tsgo.go.path.base(name), file), 0), null)
    }
    val list = ArrayList<MapFileInfo>() // Go's `list == nil` ⇔ nothing appended ⇔ empty
    val need = LinkedHashMap<String, Boolean>()
    if (realName == ".") {
        for (fname in keysSnapshot()) {
            val f = this[fname]
            val i = fname.indexOf('/')
            if (i < 0) {
                if (fname != ".") {
                    list += MapFileInfo(fname, f)
                }
            } else {
                need[fname.substring(0, i)] = true
            }
        }
    } else {
        val prefix = "$realName/"
        for (fname in keysSnapshot()) {
            if (fname.startsWith(prefix)) {
                val felem = fname.substring(prefix.length)
                val i = felem.indexOf('/')
                if (i < 0) {
                    list += MapFileInfo(felem, this[fname])
                } else {
                    need[fname.substring(prefix.length, prefix.length + i)] = true
                }
            }
        }
        if (file == null && list.isEmpty() && need.isEmpty()) return Tuple2(null, PathError("open", name, errNotExist))
    }
    for (fi in list) need.remove(fi.name)
    for (n in need.keys) list += MapFileInfo(n, MapFile(mode = DIR_FILE_MODE))
    list.sortWith { a, b -> com.xemantic.typescript.tsgo.go.strings.compare(a.name, b.name) }
    if (file == null) file = MapFile(mode = DIR_FILE_MODE)
    val elem = if (name == ".") "." else name.substring(name.lastIndexOf('/') + 1)
    return Tuple2(MapDir(name, MapFileInfo(elem, file), list, 0), null)
}

/** `fsys.resolveSymlinks(name)`. */
private fun MapFS.resolveSymlinks(name: String): Tuple2<String, Boolean> {
    val file = this[name]
    if (file != null && file.mode.type() == ModeSymlink) {
        val target = goBytesToString(file.data)
        if (com.xemantic.typescript.tsgo.go.path.isAbs(target)) return Tuple2("", false)
        return resolveSymlinks(com.xemantic.typescript.tsgo.go.path.join(com.xemantic.typescript.tsgo.go.path.dir(name), target))
    }
    var i = 0
    while (i < name.length) {
        val j = name.substring(i).indexOf('/')
        val dir: String
        if (j < 0) {
            dir = name
            i = name.length
        } else {
            dir = name.substring(0, i + j)
            i += j
        }
        val f = this[dir]
        if (f != null && f.mode.type() == ModeSymlink) {
            val target = goBytesToString(f.data)
            if (com.xemantic.typescript.tsgo.go.path.isAbs(target)) return Tuple2("", false)
            return resolveSymlinks(com.xemantic.typescript.tsgo.go.path.join(com.xemantic.typescript.tsgo.go.path.dir(dir), target) + name.substring(i))
        }
        i += 1
    }
    return Tuple2(name, validPath(name))
}

/** `fstest.mapFileInfo`. */
private class MapFileInfo(val name: String, val f: MapFile?) : FileInfo, DirEntry {
    override fun name(): String = com.xemantic.typescript.tsgo.go.path.base(name)
    override fun size(): Long = f!!.data.len.toLong()
    override fun mode(): FileMode = f!!.mode
    override fun type(): FileMode = f!!.mode.type()
    override fun modTime(): Time = f!!.modTime
    override fun isDir(): Boolean = (f!!.mode.value and ModeDir.value) != 0u
    override fun sys(): Any? = f!!.sys
    override fun info(): Tuple2<FileInfo?, GoError?> = Tuple2(this, null)
}

/** `fstest.openMapFile`. */
private class OpenMapFile(val path: String, val info: MapFileInfo, var offset: Long) : File {
    override fun stat(): Tuple2<FileInfo?, GoError?> = Tuple2(info, null)
    override fun close(): GoError? = null
    override fun read(p0: GoSlice<Int>): Tuple2<Int, GoError?> {
        val data = info.f!!.data
        if (offset >= data.len.toLong()) return Tuple2(0, com.xemantic.typescript.tsgo.go.io.EOF)
        if (offset < 0) return Tuple2(0, PathError("read", path, errInvalid))
        val n = goCopy(p0, data.slice(offset.toInt()))
        offset += n
        return Tuple2(n, null)
    }
}

/** `fstest.mapDir`. */
private class MapDir(val path: String, val info: MapFileInfo, val entry: List<MapFileInfo>, var offset: Int) : ReadDirFile {
    override fun stat(): Tuple2<FileInfo?, GoError?> = Tuple2(info, null)
    override fun close(): GoError? = null
    override fun read(p0: GoSlice<Int>): Tuple2<Int, GoError?> = Tuple2(0, PathError("read", path, errInvalid))
    override fun readDir(n: Int): Tuple2<GoSlice<DirEntry?>, GoError?> {
        var k = entry.size - offset
        if (k == 0 && n > 0) return Tuple2(GoElem.ref<DirEntry?>().nilSlice, com.xemantic.typescript.tsgo.go.io.EOF)
        if (n > 0 && k > n) k = n
        val list = GoSlice.make(GoElem.ref<DirEntry?>(), k)
        for (i in 0 until k) list[i] = entry[offset + i]
        offset += k
        return Tuple2(list, null)
    }
}

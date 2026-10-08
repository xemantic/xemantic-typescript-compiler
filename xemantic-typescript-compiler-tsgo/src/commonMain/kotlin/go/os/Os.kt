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

/**
 * `os.Getenv(key)`. The port reads no process environment (commonMain has no portable API for
 * it, and tsgo's only use in the spike closure is a debug toggle): always "".
 */
@Suppress("UNUSED_PARAMETER")
fun getenv(key: String): String = ""

/**
 * `os.DirFS(dir)`: a read-only `fs.FS` over a real directory — reached only from the ported test
 * harness's `/.lib` loader ((TSGO.2), `harnessutil.testLibFolderMap`). The host file access is the
 * platform `actual` (`Os.jvm.kt`); like Go's, names are validated with `fs.ValidPath`.
 */
fun dirFS(dir: String): com.xemantic.typescript.tsgo.go.io.fs.FS? = DirFS(dir)

private class DirFS(private val dir: String) :
    com.xemantic.typescript.tsgo.go.io.fs.ReadFileFS,
    com.xemantic.typescript.tsgo.go.io.fs.ReadDirFS,
    com.xemantic.typescript.tsgo.go.io.fs.StatFS {

    private fun join(op: String, name: String): Pair<String?, com.xemantic.typescript.tsgo.runtime.GoError?> {
        if (!com.xemantic.typescript.tsgo.go.io.fs.validPath(name)) {
            return null to com.xemantic.typescript.tsgo.go.io.fs.PathError(op, name, com.xemantic.typescript.tsgo.go.io.fs.errInvalid)
        }
        return (if (name == ".") dir else "$dir/$name") to null
    }

    private fun notExist(op: String, name: String) =
        com.xemantic.typescript.tsgo.go.io.fs.PathError(op, name, com.xemantic.typescript.tsgo.go.io.fs.errNotExist)

    override fun open(name: String): com.xemantic.typescript.tsgo.runtime.Tuple2<com.xemantic.typescript.tsgo.go.io.fs.File?, com.xemantic.typescript.tsgo.runtime.GoError?> {
        val (full, err) = join("open", name)
        if (err != null) return com.xemantic.typescript.tsgo.runtime.Tuple2(null, err)
        val isDir = platformIsDir(full!!) ?: return com.xemantic.typescript.tsgo.runtime.Tuple2(null, notExist("open", name))
        return com.xemantic.typescript.tsgo.runtime.Tuple2(HostFile(this, name, full, isDir), null)
    }

    override fun readFile(name: String): com.xemantic.typescript.tsgo.runtime.Tuple2<com.xemantic.typescript.tsgo.runtime.GoSlice<Int>, com.xemantic.typescript.tsgo.runtime.GoError?> {
        val (full, err) = join("open", name)
        if (err != null) return com.xemantic.typescript.tsgo.runtime.Tuple2(com.xemantic.typescript.tsgo.runtime.GoElem.INT.nilSlice, err)
        val bytes = platformReadFile(full!!)
            ?: return com.xemantic.typescript.tsgo.runtime.Tuple2(com.xemantic.typescript.tsgo.runtime.GoElem.INT.nilSlice, notExist("open", name))
        val out = com.xemantic.typescript.tsgo.runtime.GoSlice.make(com.xemantic.typescript.tsgo.runtime.GoElem.INT, bytes.size)
        for (i in bytes.indices) out[i] = bytes[i].toInt() and 0xFF
        return com.xemantic.typescript.tsgo.runtime.Tuple2(out, null)
    }

    override fun readDir(name: String): com.xemantic.typescript.tsgo.runtime.Tuple2<com.xemantic.typescript.tsgo.runtime.GoSlice<com.xemantic.typescript.tsgo.go.io.fs.DirEntry?>, com.xemantic.typescript.tsgo.runtime.GoError?> {
        val (full, err) = join("readdir", name)
        val elem = com.xemantic.typescript.tsgo.runtime.GoElem.ref<com.xemantic.typescript.tsgo.go.io.fs.DirEntry?>()
        if (err != null) return com.xemantic.typescript.tsgo.runtime.Tuple2(elem.nilSlice, err)
        val entries = platformListDir(full!!) ?: return com.xemantic.typescript.tsgo.runtime.Tuple2(elem.nilSlice, notExist("open", name))
        val sorted = entries.sortedWith { a, b -> com.xemantic.typescript.tsgo.go.strings.compare(a.first, b.first) }
        val out = com.xemantic.typescript.tsgo.runtime.GoSlice.make(elem, sorted.size)
        for ((i, e) in sorted.withIndex()) out[i] = com.xemantic.typescript.tsgo.go.io.fs.fileInfoToDirEntry(HostInfo(e.first, e.second, platformSize("$full/${e.first}")))
        return com.xemantic.typescript.tsgo.runtime.Tuple2(out, null)
    }

    override fun stat(name: String): com.xemantic.typescript.tsgo.runtime.Tuple2<com.xemantic.typescript.tsgo.go.io.fs.FileInfo?, com.xemantic.typescript.tsgo.runtime.GoError?> {
        val (full, err) = join("stat", name)
        if (err != null) return com.xemantic.typescript.tsgo.runtime.Tuple2(null, err)
        val isDir = platformIsDir(full!!) ?: return com.xemantic.typescript.tsgo.runtime.Tuple2(null, notExist("stat", name))
        return com.xemantic.typescript.tsgo.runtime.Tuple2(HostInfo(com.xemantic.typescript.tsgo.go.path.base(name), isDir, platformSize(full)), null)
    }

    /** An opened host file: Stat and (for a directory) ReadDir; Read serves the whole content. */
    private class HostFile(private val fs: DirFS, private val name: String, private val full: String, private val isDir: Boolean) :
        com.xemantic.typescript.tsgo.go.io.fs.ReadDirFile {
        private var content: com.xemantic.typescript.tsgo.runtime.GoSlice<Int>? = null
        private var offset = 0
        override fun stat() = fs.stat(name)
        override fun close(): com.xemantic.typescript.tsgo.runtime.GoError? = null
        override fun read(p0: com.xemantic.typescript.tsgo.runtime.GoSlice<Int>): com.xemantic.typescript.tsgo.runtime.Tuple2<Int, com.xemantic.typescript.tsgo.runtime.GoError?> {
            val data = content ?: fs.readFile(name).first.also { content = it }
            if (offset >= data.len) return com.xemantic.typescript.tsgo.runtime.Tuple2(0, com.xemantic.typescript.tsgo.go.io.EOF)
            val n = com.xemantic.typescript.tsgo.runtime.goCopy(p0, data.slice(offset))
            offset += n
            return com.xemantic.typescript.tsgo.runtime.Tuple2(n, null)
        }
        override fun readDir(n: Int) = if (isDir) fs.readDir(name) else
            com.xemantic.typescript.tsgo.runtime.Tuple2(
                com.xemantic.typescript.tsgo.runtime.GoElem.ref<com.xemantic.typescript.tsgo.go.io.fs.DirEntry?>().nilSlice,
                com.xemantic.typescript.tsgo.go.io.fs.PathError("readdirent", full, com.xemantic.typescript.tsgo.go.io.fs.errInvalid),
            )
    }

    private class HostInfo(private val name: String, private val dir: Boolean, private val size: Long) : com.xemantic.typescript.tsgo.go.io.fs.FileInfo {
        override fun name(): String = name
        override fun size(): Long = size
        override fun mode(): com.xemantic.typescript.tsgo.go.io.fs.FileMode =
            if (dir) com.xemantic.typescript.tsgo.go.io.fs.FileMode(com.xemantic.typescript.tsgo.go.io.fs.ModeDir.value or 493u) else com.xemantic.typescript.tsgo.go.io.fs.FileMode(420u)
        override fun modTime(): com.xemantic.typescript.tsgo.go.time.Time = com.xemantic.typescript.tsgo.go.time.Time()
        override fun isDir(): Boolean = dir
        override fun sys(): Any? = null
    }
}

/** Whether [path] is a directory; null when it does not exist. */
internal expect fun platformIsDir(path: String): Boolean?

/** The entries of directory [path] as (name, isDirectory); null when it is not a readable directory. */
internal expect fun platformListDir(path: String): List<Pair<String, Boolean>>?

/** The bytes of file [path]; null when it cannot be read. */
internal expect fun platformReadFile(path: String): ByteArray?

/** The size of [path] in bytes (0 when unknown). */
internal expect fun platformSize(path: String): Long

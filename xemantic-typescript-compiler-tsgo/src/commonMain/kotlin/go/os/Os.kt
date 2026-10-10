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
    com.xemantic.typescript.tsgo.go.io.fs.StatFS, StringFileFS {

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
        val isDir = syscall(GoSyscall.OP_STAT) { platformIsDir(host(full!!)) } ?: return com.xemantic.typescript.tsgo.runtime.Tuple2(null, notExist("open", name))
        return com.xemantic.typescript.tsgo.runtime.Tuple2(HostFile(this, name, full!!, isDir), null)
    }

    override fun readFileString(name: String): com.xemantic.typescript.tsgo.runtime.Tuple2<String, com.xemantic.typescript.tsgo.runtime.GoError?> {
        val (full, err) = join("open", name)
        if (err != null) return com.xemantic.typescript.tsgo.runtime.Tuple2("", err)
        val bytes = syscall(GoSyscall.OP_READ) { platformReadFile(host(full!!)) }?.also { GoSyscall.recordBytes(it.size) }
            ?: return com.xemantic.typescript.tsgo.runtime.Tuple2("", notExist("open", name))
        return com.xemantic.typescript.tsgo.runtime.Tuple2(goLatin1String(bytes), null)
    }

    override fun readFile(name: String): com.xemantic.typescript.tsgo.runtime.Tuple2<com.xemantic.typescript.tsgo.runtime.GoSlice<Int>, com.xemantic.typescript.tsgo.runtime.GoError?> {
        val (full, err) = join("open", name)
        if (err != null) return com.xemantic.typescript.tsgo.runtime.Tuple2(com.xemantic.typescript.tsgo.runtime.GoElem.BYTE.nilSlice, err)
        val bytes = syscall(GoSyscall.OP_READ) { platformReadFile(host(full!!)) }?.also { GoSyscall.recordBytes(it.size) }
            ?: return com.xemantic.typescript.tsgo.runtime.Tuple2(com.xemantic.typescript.tsgo.runtime.GoElem.BYTE.nilSlice, notExist("open", name))
        val out = com.xemantic.typescript.tsgo.runtime.GoSlice.make(com.xemantic.typescript.tsgo.runtime.GoElem.BYTE, bytes.size)
        for (i in bytes.indices) out[i] = bytes[i].toInt() and 0xFF
        return com.xemantic.typescript.tsgo.runtime.Tuple2(out, null)
    }

    override fun readDir(name: String): com.xemantic.typescript.tsgo.runtime.Tuple2<com.xemantic.typescript.tsgo.runtime.GoSlice<com.xemantic.typescript.tsgo.go.io.fs.DirEntry?>, com.xemantic.typescript.tsgo.runtime.GoError?> {
        val (full, err) = join("readdir", name)
        val elem = com.xemantic.typescript.tsgo.runtime.GoElem.ref<com.xemantic.typescript.tsgo.go.io.fs.DirEntry?>()
        if (err != null) return com.xemantic.typescript.tsgo.runtime.Tuple2(elem.nilSlice, err)
        val entries = syscall(GoSyscall.OP_LIST) { platformListDir(host(full!!)) } ?: return com.xemantic.typescript.tsgo.runtime.Tuple2(elem.nilSlice, notExist("open", name))
        // Go's os.DirFS: entry names as bytes, sorted by name, a symbolic link typed ModeSymlink (lstat).
        val named = entries.map { com.xemantic.typescript.tsgo.runtime.GoString.fromUtf16(it.first) to it.second }
        val sorted = named.sortedWith { a, b -> com.xemantic.typescript.tsgo.go.strings.compare(a.first, b.first) }
        val out = com.xemantic.typescript.tsgo.runtime.GoSlice.make(elem, sorted.size)
        for ((i, e) in sorted.withIndex()) {
            val kind = e.second
            out[i] = com.xemantic.typescript.tsgo.go.io.fs.fileInfoToDirEntry(
                HostInfo(e.first, kind == 'd', { if (kind == 'f') syscall(GoSyscall.OP_STAT) { platformSize(host("$full/${e.first}")) } else 0L }, kind),
            )
        }
        return com.xemantic.typescript.tsgo.runtime.Tuple2(out, null)
    }

    override fun stat(name: String): com.xemantic.typescript.tsgo.runtime.Tuple2<com.xemantic.typescript.tsgo.go.io.fs.FileInfo?, com.xemantic.typescript.tsgo.runtime.GoError?> {
        val (full, err) = join("stat", name)
        if (err != null) return com.xemantic.typescript.tsgo.runtime.Tuple2(null, err)
        val isDir = syscall(GoSyscall.OP_STAT) { platformIsDir(host(full!!)) } ?: return com.xemantic.typescript.tsgo.runtime.Tuple2(null, notExist("stat", name))
        val size = syscall(GoSyscall.OP_STAT) { platformSize(host(full!!)) }
        return com.xemantic.typescript.tsgo.runtime.Tuple2(HostInfo(com.xemantic.typescript.tsgo.go.path.base(name), isDir, { size }), null)
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

    /** [kind]: 'd' directory, 'f' regular file, 'l' symbolic link (as lstat reports it), 'o' anything else. */
    /**
     * A host file's info. Its size is asked for on first use: Go's `DirEntry.Info()` stats lazily too, and directory
     * walks (the config phase's include matching) never read it — an eager `stat` per listed file was a second syscall
     * per entry (docs/goport-perf.md § 16).
     */
    private class HostInfo(private val name: String, private val dir: Boolean, private val sizeOf: () -> Long, private val kind: Char = if (dir) 'd' else 'f') :
        com.xemantic.typescript.tsgo.go.io.fs.FileInfo {
        private var size = -1L
        override fun name(): String = name
        override fun size(): Long {
            if (size < 0) size = sizeOf()
            return size
        }
        override fun mode(): com.xemantic.typescript.tsgo.go.io.fs.FileMode = when (kind) {
            'd' -> com.xemantic.typescript.tsgo.go.io.fs.FileMode(com.xemantic.typescript.tsgo.go.io.fs.ModeDir.value or 493u)
            'l' -> com.xemantic.typescript.tsgo.go.io.fs.FileMode(com.xemantic.typescript.tsgo.go.io.fs.ModeSymlink.value or 511u)
            'o' -> com.xemantic.typescript.tsgo.go.io.fs.FileMode(com.xemantic.typescript.tsgo.go.io.fs.ModeIrregular.value or 420u)
            else -> com.xemantic.typescript.tsgo.go.io.fs.FileMode(420u)
        }
        override fun modTime(): com.xemantic.typescript.tsgo.go.time.Time = com.xemantic.typescript.tsgo.go.time.Time()
        override fun isDir(): Boolean = dir
        override fun sys(): Any? = null
    }
}

/**
 * NOT Go API — `string(b)` of `fs.ReadFile(fsys, name)` in one step, for a file system that can read a file straight
 * into a byte string (the host [DirFS]). `vfs/internal.Common.ReadFile` (an override, docs/goport-perf.md § 17) uses it
 * where the generated code built a `[]byte` slice element by element and then a string from it again.
 */
interface StringFileFS {
    fun readFileString(name: String): com.xemantic.typescript.tsgo.runtime.Tuple2<String, com.xemantic.typescript.tsgo.runtime.GoError?>
}

/** A byte string (one `Char` per byte, docs/goport-design.md § 3) of [bytes]. */
internal expect fun goLatin1String(bytes: ByteArray): String

/** Whether [path] is a directory; null when it does not exist. */
internal expect fun platformIsDir(path: String): Boolean?

/**
 * The entries of directory [path] as (name, kind) — kind 'd' directory, 'f' regular file, 'l' symbolic link,
 * 'o' anything else (the link itself, not its target: Go's ReadDir lstat); null when it is not a readable directory.
 */
internal expect fun platformListDir(path: String): List<Pair<String, Char>>?

/** The bytes of file [path]; null when it cannot be read. */
internal expect fun platformReadFile(path: String): ByteArray?

/** The size of [path] in bytes (0 when unknown). */
internal expect fun platformSize(path: String): Long

// ---- (TSGO.5) the OS file system the command line reads and writes (tsgo's `vfs/osvfs`, docs/goport-cli.md).
// Every name a ported caller passes is a byte string (UTF-8 bytes, docs/goport-design.md § 3); the platform
// `actual`s take and return host (UTF-16) paths, converted here.

private fun host(path: String): String = com.xemantic.typescript.tsgo.runtime.GoString.toUtf16(path)

private fun pathError(op: String, path: String, message: String): com.xemantic.typescript.tsgo.runtime.GoError =
    com.xemantic.typescript.tsgo.go.io.fs.PathError(op, path, com.xemantic.typescript.tsgo.runtime.GoPlainError(message))

/** `os.O_*` open flags (Linux values; the ported callers combine them with `|`). */
const val O_RDONLY: Int = 0x0
const val O_WRONLY: Int = 0x1
const val O_RDWR: Int = 0x2
const val O_CREATE: Int = 0x40
const val O_EXCL: Int = 0x80
const val O_TRUNC: Int = 0x200
const val O_APPEND: Int = 0x400

/** `*os.File` opened for writing by [openFile]: `WriteString` writes to the host file, `Close` releases it. */
class File internal constructor(private val name: String, private val handle: Any) {
    fun writeString(s: String): com.xemantic.typescript.tsgo.runtime.Tuple2<Int, com.xemantic.typescript.tsgo.runtime.GoError?> {
        val bytes = ByteArray(s.length) { s[it].code.toByte() }
        val err = syscall(GoSyscall.OP_WRITE) { platformWrite(handle, bytes) }
        return if (err == null) com.xemantic.typescript.tsgo.runtime.Tuple2(s.length, null)
        else com.xemantic.typescript.tsgo.runtime.Tuple2(0, pathError("write", name, err))
    }

    fun close(): com.xemantic.typescript.tsgo.runtime.GoError? = syscall(GoSyscall.OP_WRITE) { platformClose(handle) }?.let { pathError("close", name, it) }
}

/** `os.OpenFile(name, flag, perm)`: write-only opens (O_CREATE, O_TRUNC, O_APPEND); the permission bits are the host's default. */
@Suppress("UNUSED_PARAMETER")
fun openFile(name: String, flag: Int, perm: com.xemantic.typescript.tsgo.go.io.fs.FileMode): com.xemantic.typescript.tsgo.runtime.Tuple2<File?, com.xemantic.typescript.tsgo.runtime.GoError?> {
    val (handle, err) = syscall(GoSyscall.OP_WRITE) { platformOpenWrite(host(name), create = flag and O_CREATE != 0, append = flag and O_APPEND != 0, truncate = flag and O_TRUNC != 0) }
    if (handle == null) {
        val e = if (err == "not exist") com.xemantic.typescript.tsgo.go.io.fs.PathError("open", name, com.xemantic.typescript.tsgo.go.io.fs.errNotExist)
        else pathError("open", name, err ?: "open failed")
        return com.xemantic.typescript.tsgo.runtime.Tuple2(null, e)
    }
    return com.xemantic.typescript.tsgo.runtime.Tuple2(File(name, handle), null)
}

/** `os.MkdirAll(path, perm)`. */
@Suppress("UNUSED_PARAMETER")
fun mkdirAll(path: String, perm: com.xemantic.typescript.tsgo.go.io.fs.FileMode): com.xemantic.typescript.tsgo.runtime.GoError? =
    syscall(GoSyscall.OP_WRITE) { platformMkdirAll(host(path)) }?.let { pathError("mkdir", path, it) }

/** `os.RemoveAll(path)`: nil when [path] does not exist. */
fun removeAll(path: String): com.xemantic.typescript.tsgo.runtime.GoError? =
    syscall(GoSyscall.OP_WRITE) { platformRemoveAll(host(path)) }?.let { pathError("unlinkat", path, it) }

/** `os.Chtimes(name, atime, mtime)`: the modification time (the access time is left to the host). */
@Suppress("UNUSED_PARAMETER")
fun chtimes(name: String, atime: com.xemantic.typescript.tsgo.go.time.Time, mtime: com.xemantic.typescript.tsgo.go.time.Time): com.xemantic.typescript.tsgo.runtime.GoError? =
    syscall(GoSyscall.OP_WRITE) { platformSetModTime(host(name), mtime.unixMilli()) }?.let { pathError("chtimes", name, it) }

/** `os.Executable()`: the running program's path (the JVM's `java`, or the native image). */
fun executable(): com.xemantic.typescript.tsgo.runtime.Tuple2<String, com.xemantic.typescript.tsgo.runtime.GoError?> {
    val p = platformExecutable() ?: return com.xemantic.typescript.tsgo.runtime.Tuple2("", com.xemantic.typescript.tsgo.runtime.GoPlainError("executable not found"))
    return com.xemantic.typescript.tsgo.runtime.Tuple2(com.xemantic.typescript.tsgo.runtime.GoString.fromUtf16(p), null)
}

/** `os.Stat(name)` (following symbolic links). */
fun stat(name: String): com.xemantic.typescript.tsgo.runtime.Tuple2<com.xemantic.typescript.tsgo.go.io.fs.FileInfo?, com.xemantic.typescript.tsgo.runtime.GoError?> {
    val isDir = syscall(GoSyscall.OP_STAT) { platformIsDir(host(name)) }
        ?: return com.xemantic.typescript.tsgo.runtime.Tuple2(null, com.xemantic.typescript.tsgo.go.io.fs.PathError("stat", name, com.xemantic.typescript.tsgo.go.io.fs.errNotExist))
    return com.xemantic.typescript.tsgo.runtime.Tuple2(syscall(GoSyscall.OP_STAT) { StatInfo(com.xemantic.typescript.tsgo.go.path.base(name), isDir, platformSize(host(name)), platformModTime(host(name))) }, null)
}

/** `os.IsNotExist(err)`. */
fun isNotExist(err: com.xemantic.typescript.tsgo.runtime.GoError?): Boolean {
    var e = err
    while (e != null) {
        if (e === com.xemantic.typescript.tsgo.go.io.fs.errNotExist) return true
        e = (e as? com.xemantic.typescript.tsgo.runtime.GoUnwrapper)?.unwrap()
    }
    return false
}

/** `os.UserCacheDir()` (Linux: `$XDG_CACHE_HOME`, else `$HOME/.cache`). */
fun userCacheDir(): com.xemantic.typescript.tsgo.runtime.Tuple2<String, com.xemantic.typescript.tsgo.runtime.GoError?> {
    val dir = platformUserCacheDir()
        ?: return com.xemantic.typescript.tsgo.runtime.Tuple2("", com.xemantic.typescript.tsgo.runtime.GoPlainError("neither \$XDG_CACHE_HOME nor \$HOME are defined"))
    return com.xemantic.typescript.tsgo.runtime.Tuple2(com.xemantic.typescript.tsgo.runtime.GoString.fromUtf16(dir), null)
}

/** `os.TempDir()`. */
fun tempDir(): String = com.xemantic.typescript.tsgo.runtime.GoString.fromUtf16(platformTempDir())

private class StatInfo(private val name: String, private val dir: Boolean, private val size: Long, private val modMillis: Long) :
    com.xemantic.typescript.tsgo.go.io.fs.FileInfo {
    override fun name(): String = name
    override fun size(): Long = size
    override fun mode(): com.xemantic.typescript.tsgo.go.io.fs.FileMode =
        if (dir) com.xemantic.typescript.tsgo.go.io.fs.FileMode(com.xemantic.typescript.tsgo.go.io.fs.ModeDir.value or 493u) else com.xemantic.typescript.tsgo.go.io.fs.FileMode(420u)
    override fun modTime(): com.xemantic.typescript.tsgo.go.time.Time = com.xemantic.typescript.tsgo.go.time.Time(modMillis * 1_000_000L)
    override fun isDir(): Boolean = dir
    override fun sys(): Any? = null
}

/** Opens host file [path] for writing: (handle, null) or (null, "not exist" / a message). */
internal expect fun platformOpenWrite(path: String, create: Boolean, append: Boolean, truncate: Boolean): Pair<Any?, String?>

/** Writes [bytes] to a handle of [platformOpenWrite]; null or an error message. */
internal expect fun platformWrite(handle: Any, bytes: ByteArray): String?

/** Closes a handle of [platformOpenWrite]; null or an error message. */
internal expect fun platformClose(handle: Any): String?

/** Creates directory [path] and its parents; null or an error message. */
internal expect fun platformMkdirAll(path: String): String?

/** Removes [path] and everything below it; null (also when absent) or an error message. */
internal expect fun platformRemoveAll(path: String): String?

/** Sets the modification time of [path]; null or an error message. */
internal expect fun platformSetModTime(path: String, epochMillis: Long): String?

/** The modification time of [path] in epoch milliseconds (0 when unknown). */
internal expect fun platformModTime(path: String): Long

/** The running executable's path; null when unknown. */
internal expect fun platformExecutable(): String?

/** The user cache directory; null when it cannot be determined. */
internal expect fun platformUserCacheDir(): String?

/** The temporary directory. */
internal expect fun platformTempDir(): String

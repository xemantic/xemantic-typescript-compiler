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

package com.xemantic.typescript.compiler.lsp

import com.xemantic.typescript.tsgo.go.io.fs.FileInfo
import com.xemantic.typescript.tsgo.go.time.Time
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoMap
import com.xemantic.typescript.tsgo.runtime.GoPlainError
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.runtime.goUnitElem
import com.xemantic.typescript.tsgo.tspath.CaseSensitive
import com.xemantic.typescript.tsgo.tspath.CaseSensitivity
import com.xemantic.typescript.tsgo.tspath.RootedDirectoryPath
import com.xemantic.typescript.tsgo.tspath.RootedFilePath
import com.xemantic.typescript.tsgo.tspath.RootedPath
import com.xemantic.typescript.tsgo.vfs.Entries
import com.xemantic.typescript.tsgo.vfs.FS

/**
 * The server's file system (tsgo's project system keeps the same split, `project.overlayFS`): the
 * client's open buffers OVER a [base] file system (the disk; null = nothing but the buffers). A buffer
 * is the truth for its file, saved or not; every other read reaches the base. Paths are absolute and
 * normalized (`/`), as tsgo asks for them. Read-only: a language server writes nothing.
 */
public class OverlayFS(private val base: FS? = null) : FS {

    /** Go byte-string path -> Go byte-string content. */
    private val overlays = HashMap<String, String>()

    /** Sets the buffer of [path] (a UTF-16 path) to [text]. */
    public fun put(path: String, text: String) {
        overlays[GoString.fromUtf16(path)] = GoString.fromUtf16(text)
    }

    /** Drops the buffer of [path]: its reads reach the base again. */
    public fun drop(path: String) {
        overlays.remove(GoString.fromUtf16(path))
    }

    override fun caseSensitivity(): CaseSensitivity = base?.caseSensitivity() ?: CaseSensitive

    override fun fileExists(p0: RootedFilePath): Boolean = p0.value in overlays || base?.fileExists(p0) == true

    override fun readFile(p0: RootedFilePath): Tuple2<String, Boolean> =
        overlays[p0.value]?.let { Tuple2(it, true) } ?: base?.readFile(p0) ?: Tuple2("", false)

    override fun directoryExists(p0: RootedDirectoryPath): Boolean {
        if (base?.directoryExists(p0) == true) return true
        val prefix = prefix(p0)
        return overlays.keys.any { it.startsWith(prefix) }
    }

    override fun getAccessibleEntries(p0: RootedDirectoryPath): Entries {
        val fromBase = base?.getAccessibleEntries(p0)
        val prefix = prefix(p0)
        val files = LinkedHashSet<String>()
        val dirs = LinkedHashSet<String>()
        fromBase?.files?.let { s -> for (i in 0 until s.len) files += s[i] }
        fromBase?.directories?.let { s -> for (i in 0 until s.len) dirs += s[i] }
        for (k in overlays.keys) {
            if (!k.startsWith(prefix)) continue
            val rest = k.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) files += rest else dirs += rest.substring(0, slash)
        }
        return Entries(
            files = GoSlice.of(GoElem.STRING, *files.sorted().toTypedArray()),
            directories = GoSlice.of(GoElem.STRING, *dirs.sorted().toTypedArray()),
            symlinks = fromBase?.symlinks ?: GoMap.make(goUnitElem),
        )
    }

    override fun realpath(p0: RootedPath): RootedPath = if (p0.value in overlays) p0 else base?.realpath(p0) ?: p0

    override fun stat(p0: RootedPath): FileInfo? = base?.stat(p0)

    override fun writeFile(p0: RootedFilePath, p1: String): GoError? = READ_ONLY
    override fun appendFile(p0: RootedFilePath, p1: String): GoError? = READ_ONLY
    override fun remove(p0: RootedPath): GoError? = READ_ONLY
    override fun chtimes(p0: RootedPath, p1: Time, p2: Time): GoError? = READ_ONLY

    private fun prefix(dir: RootedDirectoryPath): String = if (dir.value.endsWith("/")) dir.value else "${dir.value}/"

    private companion object {
        val READ_ONLY: GoError = GoPlainError("the language server's file system is read-only")
    }
}

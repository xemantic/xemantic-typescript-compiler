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

package com.xemantic.typescript.compiler.project

import com.xemantic.typescript.compiler.PathUtil
import com.xemantic.typescript.compiler.Vfs

/**
 * An in-memory [Vfs] for deterministic tests, so nothing here touches a real
 * filesystem and no test can accidentally emit into the repository.
 *
 * A near-copy of the core module's test fixture of the same shape: `commonTest`
 * source sets are not visible across module boundaries, so the alternative would
 * have been publishing a test fixture from `-core`, which would make an internal
 * test helper part of that module's contract.
 */
internal class InMemoryVfs(initial: Map<String, String> = emptyMap()) : Vfs {

    private val files = HashMap<String, String>()

    init {
        for ((k, v) in initial) files[PathUtil.normalize(k)] = v
    }

    /** Every directory implied by a file key, plus the root. */
    private fun dirs(): Set<String> {
        val s = HashSet<String>()
        s.add("/")
        for (f in files.keys) {
            var d = PathUtil.dirname(f)
            while (d.isNotEmpty()) {
                s.add(d)
                if (d == "/") break
                val p = PathUtil.dirname(d)
                if (p == d) break
                d = p
            }
        }
        return s
    }

    override fun exists(path: String): Boolean {
        val n = PathUtil.normalize(path)
        return n in files || n in dirs()
    }

    override fun isDirectory(path: String): Boolean = PathUtil.normalize(path) in dirs()

    override fun readText(path: String): String? = files[PathUtil.normalize(path)]

    override fun writeText(path: String, content: String) {
        files[PathUtil.normalize(path)] = content
    }

    /**
     * Removes [path] from the backing store, as a file deleted behind the compiler's
     * back — the half of "the filesystem moved" that [writeText] cannot express.
     */
    fun delete(path: String) {
        files.remove(PathUtil.normalize(path))
    }

    override fun list(path: String): List<String> {
        val dir = PathUtil.normalize(path)
        val prefix = if (dir == "/") "/" else "$dir/"
        val children = LinkedHashSet<String>()
        for (entry in files.keys + dirs()) {
            if (entry == dir || !entry.startsWith(prefix) || entry == prefix) continue
            val rest = entry.substring(prefix.length)
            children.add(PathUtil.normalize(prefix + rest.substringBefore('/')))
        }
        children.remove(dir)
        return children.toList()
    }
}

/**
 * A [Vfs] that counts the calls that reach it, so a test can assert that a second
 * query did NOT rebuild.
 *
 * A build is not observable from its result — two builds of the same state return
 * equal values — so the only sharp signal for "the cache was used" is that the
 * layer BELOW it went untouched. That is why this counts reads rather than timing
 * anything: a timed assertion over a compile is a coin flip (CLAUDE.md, round 868).
 */
internal class CountingVfs(private val delegate: Vfs) : Vfs {

    var reads: Int = 0
        private set

    /**
     * Reads per path, so a test can calibrate on ONE file rather than on the sum.
     *
     * (API.3c) The sum is a fine unit for "did this rebuild" and a bad one for "how
     * many times": a crawl reads a project's files a number of times that is a
     * property of the crawl, and a per-path count is a property of the build.
     */
    private val readsByPath = HashMap<String, Int>()

    /** How many times [path] has been read through this. */
    fun readsOf(path: String): Int = readsByPath[PathUtil.normalize(path)] ?: 0
    var lists: Int = 0
        private set

    /** Reads plus directory listings — any evidence the backing store was consulted. */
    val touches: Int get() = reads + lists

    override fun exists(path: String): Boolean = delegate.exists(path)

    override fun isDirectory(path: String): Boolean = delegate.isDirectory(path)

    override fun readText(path: String): String? {
        reads++
        val key = PathUtil.normalize(path)
        readsByPath[key] = (readsByPath[key] ?: 0) + 1
        return delegate.readText(path)
    }

    override fun writeText(path: String, content: String) {
        delegate.writeText(path, content)
    }

    override fun list(path: String): List<String> {
        lists++
        return delegate.list(path)
    }

    override fun resolveAbsolute(path: String): String = delegate.resolveAbsolute(path)
}

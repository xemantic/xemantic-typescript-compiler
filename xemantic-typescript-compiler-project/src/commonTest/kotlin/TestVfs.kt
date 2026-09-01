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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import com.xemantic.typescript.compiler.VfsEntry

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
 *
 * (TEST.1) EVERY COUNTER HERE IS ATOMIC, AND THE PER-PATH TABLE IS COPY-ON-WRITE,
 * because the crawl reads a program's files from **sixteen concurrent workers**
 * (`ProjectCompiler.drainConcurrently`: `flatMapMerge(FRONTEND_CONCURRENCY)` around
 * `withContext(pipelineIoDispatcher) { vfs.readText(path) }`). The first cut kept a
 * plain `reads++` and a `HashMap` put, i.e. round 825's data race one layer up — two
 * workers inserting two paths into one bucket of a fresh table lose one of them
 * outright, and the symptom was `ProjectTrustedFilesystemTest`'s negative control
 * reading `afterFirst == 0` ONCE in a filtered run and green everywhere else: a race,
 * not the order-sensitivity it was queued as. `CountingVfsConcurrencyTest` (jvmTest)
 * drives the wrapper from real threads and is what would have caught it.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CountingVfs(private val delegate: Vfs) : Vfs {

    private val readCount = AtomicInt(0)

    val reads: Int get() = readCount.load()

    /**
     * Reads per path, so a test can calibrate on ONE file rather than on the sum.
     *
     * (API.3c) The sum is a fine unit for "did this rebuild" and a bad one for "how
     * many times": a crawl reads a project's files a number of times that is a
     * property of the crawl, and a per-path count is a property of the build.
     *
     * An immutable map swapped by CAS: a lost race here RETRIES rather than losing
     * an entry, which is the whole difference from a `HashMap` under the crawl.
     */
    private val readsByPath = AtomicReference<Map<String, Int>>(emptyMap())

    /** How many times [path] has been read through this. */
    fun readsOf(path: String): Int = readsByPath.load()[PathUtil.normalize(path)] ?: 0

    private val listCount = AtomicInt(0)

    val lists: Int get() = listCount.load()

    /** Reads plus directory listings — any evidence the backing store was consulted. */
    val touches: Int get() = reads + lists

    override fun exists(path: String): Boolean = delegate.exists(path)

    override fun isDirectory(path: String): Boolean {
        isDirectoryCount.incrementAndFetch()
        return delegate.isDirectory(path)
    }

    override fun readText(path: String): String? {
        readCount.incrementAndFetch()
        val key = PathUtil.normalize(path)
        while (true) {
            val current = readsByPath.load()
            val next = current + (key to ((current[key] ?: 0) + 1))
            if (readsByPath.compareAndSet(current, next)) break
        }
        return delegate.readText(path)
    }

    override fun writeText(path: String, content: String) {
        delegate.writeText(path, content)
    }

    override fun list(path: String): List<String> {
        listCount.incrementAndFetch()
        return delegate.list(path)
    }

    private val isDirectoryCount = AtomicInt(0)

    /**
     * (INC.76) How many times [isDirectory] reached this — the instrument for "did the
     * layer above ask its kind question per ENTRY, or take it from the listing".
     */
    val isDirectoryCalls: Int get() = isDirectoryCount.load()

    private val listEntriesCount = AtomicInt(0)

    /** How many times [listEntries] reached this. */
    val listEntriesCalls: Int get() = listEntriesCount.load()

    /**
     * (INC.76) OVERRIDDEN, and that is not tidiness: `Vfs.listEntries`'s default body is
     * `list(path).map { VfsEntry(it, isDirectory(it)) }`, so a counting Vfs that does not
     * override it reports its own default's calls rather than the delegate's — CLAUDE.md
     * says so in as many words, and a pin about who asks `isDirectory` would be vacuous
     * against every binary without this.
     */
    override fun listEntries(path: String): List<VfsEntry> {
        listEntriesCount.incrementAndFetch()
        return delegate.listEntries(path)
    }

    override fun resolveAbsolute(path: String): String = delegate.resolveAbsolute(path)
}

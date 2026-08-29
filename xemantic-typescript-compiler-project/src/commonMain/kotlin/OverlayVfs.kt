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
 * A [Vfs] that answers from an in-memory overlay first and falls through to
 * [delegate] for everything the overlay does not describe.
 *
 * This is the whole mechanism behind [Project.updateFile] / [Project.deleteFile]:
 * an unsaved editor buffer is a file the compiler must see and the filesystem must
 * not, and [Vfs] is the ONE seam every path in the compile pipeline goes through —
 * the glob crawl, `tsconfig.json` loading and module resolution all read through
 * it (`ProjectCompiler`, `TsConfigLoader`, `ModuleResolver`), so an overlay here
 * reaches all three without any of them knowing an overlay exists.
 *
 * ## Why an overlay edit cannot be served a stale parse
 *
 * The compiler keeps a process-global parse cache (`CrawlParseCache`) across
 * builds, which is what makes a re-query after an edit cost a warm rebuild rather
 * than a cold one. It is keyed by path but VALIDATED against the exact content and
 * the exact `ParserFlags` the tree was parsed from, so a lookup for a path whose
 * overlay text differs from the cached text misses by construction — there is no
 * timestamp, size or `stat` anywhere in that decision, which is precisely why an
 * in-memory edit is expressible at all (CLAUDE.md, round 871: cross-request reuse
 * must be keyed on CONTENT, never on an mtime).
 *
 * ## Why each rule below is load-bearing
 *
 * The crawl does not ask for files by name — it enumerates them. For an
 * overlay-ADDED file to become part of the program it must survive three
 * different questions, asked by different layers:
 *
 *  - `ModuleResolver` probes candidate paths with [exists] before [readText], so
 *    an added file that answers `exists = false` is an unresolved import (TS2307)
 *    however readable its text is.
 *  - `ProjectCompiler.walk` asks [isDirectory] for every entry it lists and only
 *    descends into the ones that say yes, so a file added under a directory that
 *    exists only in the overlay is invisible unless that directory answers yes
 *    too.
 *  - the same walk discovers root files through [list] alone, so an added file
 *    that no other file imports exists for the glob only if [list] reports it.
 *
 * Get any one of those wrong and a whole class of edits silently does nothing —
 * which reads as "the overlay is not consulted" and is why `ProjectTest` pins each
 * of the three separately, with a negative control for the resolution one.
 *
 * Not thread-safe; [Project] owns the single instance and the compile it feeds is
 * driven from the caller's thread.
 */
internal class OverlayVfs(private val delegate: Vfs) : Vfs {

    /** Normalized path -> its in-memory text. Never intersects [deleted]. */
    private val contents = HashMap<String, String>()

    /**
     * Normalized paths shadowed as ABSENT (tombstones).
     *
     * A separate set rather than a null value in [contents] because the two states
     * are read by different rules — a tombstone must make [exists] false, which is
     * not the same question as "is there overlay text".
     */
    private val deleted = HashSet<String>()

    /**
     * Records [text] as the content of [path], clearing any tombstone on it.
     *
     * [path] is expected already normalized and absolute — [Project] is the only
     * caller and does that once, at its API boundary, so that a key written here
     * and a key the crawl later asks about are the same string.
     */
    fun put(path: String, text: String) {
        contents[path] = text
        deleted.remove(path)
    }

    /** Shadows [path] as absent, discarding any overlay text it had. */
    fun remove(path: String) {
        contents.remove(path)
        deleted.add(path)
    }

    /** Drops every overlay entry, so this Vfs becomes a transparent [delegate]. */
    fun clear() {
        contents.clear()
        deleted.clear()
    }

    /** True while nothing is overlaid — used only by tests and diagnostics. */
    val isEmpty: Boolean get() = contents.isEmpty() && deleted.isEmpty()

    /**
     * Normalizes an incoming query so it can be compared against overlay keys.
     *
     * Deliberately NOT `delegate.resolveAbsolute`: every path the compile pipeline
     * asks about is already absolute (`ProjectCompiler.build` absolutizes the
     * project path before anything derives from it), and running the delegate's
     * CWD-relative resolution over an already-absolute non-POSIX path (a Windows
     * drive letter) would corrupt it.
     */
    private fun key(path: String): String = PathUtil.normalize(path)

    /** True if the overlay holds a live entry strictly below directory [dir]. */
    private fun hasOverlayChildren(dir: String): Boolean {
        val prefix = if (dir == "/") "/" else "$dir/"
        return contents.keys.any { it.length > prefix.length && it.startsWith(prefix) }
    }

    override fun exists(path: String): Boolean {
        val k = key(path)
        if (k in deleted) return false
        if (k in contents) return true
        // An overlay-added file makes its ancestor directories exist too: the
        // delegate has never heard of them, and module resolution walks directory
        // candidates on its way to a file.
        return hasOverlayChildren(k) || delegate.exists(k)
    }

    override fun isDirectory(path: String): Boolean {
        val k = key(path)
        // A tombstone is a FILE tombstone (only Project.deleteFile creates one), so
        // it says nothing about directory-ness and is not consulted here.
        return delegate.isDirectory(k) || hasOverlayChildren(k)
    }

    /**
     * (INC.48) Every `.json` this Vfs was asked to read since [clearJsonReads] — the
     * configuration inputs of the build in flight.
     *
     * Recorded here rather than derived, because which `.json` files a build depends on
     * is not a function of the project path: a `tsconfig` may `extends` another, and
     * under `nodenext` a file's module format comes from the nearest enclosing
     * `package.json` ((CHK.29)). A snapshot that hashed only `tsconfig.json` would be
     * validated against a fraction of what decided its answer. Mirrors what
     * `TsBuildInfo`'s `RecordingVfs` does for the CLI's `.xtsbuildinfo`.
     */
    val jsonReads: MutableSet<String> = LinkedHashSet()

    /** Drops [jsonReads], so the next build records its own inputs and not the last one's. */
    fun clearJsonReads() {
        jsonReads.clear()
    }

    override fun readText(path: String): String? {
        val k = key(path)
        if (k.endsWith(".json")) jsonReads.add(k)
        if (k in deleted) return null
        return contents[k] ?: delegate.readText(k)
    }

    /**
     * Writes straight through to [delegate].
     *
     * [Project] always builds with `noEmit = true`, so in practice nothing on this
     * path writes; keeping it transparent rather than swallowing the write means a
     * caller sharing this Vfs with something that DOES write gets an honest
     * filesystem rather than a silent no-op. The one caveat: an overlay entry for
     * the same path keeps shadowing the bytes just written, so a writer must drop
     * its overlay entry (via [Project.updateFile] with the new text) if it wants to
     * see them.
     */
    override fun writeText(path: String, content: String) {
        delegate.writeText(path, content)
    }

    /**
     * The delegate's children of [path], plus overlay-only immediate children,
     * minus tombstones, SORTED.
     *
     * The sort is not cosmetic: program order decides which file first touches a
     * shared type node, and `ProjectCompiler.walk` sorts for exactly that reason
     * (CLAUDE.md — an unsorted crawl makes the COST.1 counters a property of the
     * filesystem). Sorting here as well makes the union deterministic regardless of
     * what order the delegate hands back, which is what keeps two builds of the
     * same overlay state identical.
     */
    override fun list(path: String): List<String> {
        val dir = key(path)
        val prefix = if (dir == "/") "/" else "$dir/"
        val children = HashSet<String>()
        for (entry in delegate.list(dir)) {
            val n = PathUtil.normalize(entry)
            if (n in deleted) continue
            children.add(n)
        }
        for (k in contents.keys) {
            if (k.length <= prefix.length || !k.startsWith(prefix)) continue
            // The IMMEDIATE child on the way to k — a file directly in `dir`, or the
            // first directory segment below it.
            val rest = k.substring(prefix.length)
            children.add(prefix + rest.substringBefore('/'))
        }
        return children.sorted()
    }

    override fun resolveAbsolute(path: String): String = delegate.resolveAbsolute(path)
}

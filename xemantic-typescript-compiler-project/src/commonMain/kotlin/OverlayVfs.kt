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
        // (INC.56) An overlaid path is decided by [contents], so a retained entry for it
        // is dead weight — and, if the overlay were ever dropped, a stale answer.
        retained.remove(path)
    }

    /** Shadows [path] as absent, discarding any overlay text it had. */
    fun remove(path: String) {
        contents.remove(path)
        deleted.add(path)
        retained.remove(path)
    }

    /** Drops every overlay entry, so this Vfs becomes a transparent [delegate]. */
    fun clear() {
        contents.clear()
        deleted.clear()
        retained.clear()
    }

    // ---- (INC.56) the host's filesystem promise ------------------------------

    /**
     * (INC.56) Path -> the text a build read through [delegate], retained ONLY while
     * [trustFilesystem] is on.
     *
     * Never intersects [contents] or [deleted]: [put] and [remove] drop the entry for
     * their path and [retainRead] refuses to make one, so an overlaid or tombstoned
     * path is decided by those two and this map is never consulted for it.
     *
     * ## Threading — the reason [retainRead] exists at all
     *
     * The crawl reads its files from N concurrent workers, so this map is READ from
     * several threads at once and may be WRITTEN from exactly one place: the crawl's
     * single-threaded fold, after the flow that produced the text has been drained.
     * That is `CrawlParseCache`'s discipline and it is not optional — a plain
     * `HashMap` write from those workers is round 825's data race, with no exception
     * to find it by. Populating it from [readText] would be the natural and wrong
     * design.
     *
     * ## Why it costs (almost) no memory
     *
     * The value is the very `String` instance the delegate handed back, which the
     * compiler's own `CrawlParseCache` is already retaining inside the `PreParsedFile`
     * it parsed from it — so what is added per file is a map entry and a reference, not
     * a copy of the source. That identity is also what keeps the parse cache's hit
     * condition O(1): its gate is `e.content != source`, and `String.equals` returns on
     * the reference compare when the two are the same object.
     */
    private val retained = HashMap<String, String>()

    /**
     * (INC.56) The HOST'S PROMISE: nothing changes the bytes of a file behind this
     * [Vfs] without telling this project about it. Off by default.
     *
     * ## What it buys, and why it is opt-in rather than a default
     *
     * Every build re-reads and re-decodes every non-overlaid file, although the PARSE
     * is already fully content-cached — the bytes are read only to compute the content
     * key. That is O(PROJECT) work on every keystroke: measured on a 2,401-file
     * application-shaped project, the crawl is 32-36 ms of an ~92 ms incremental floor
     * and its concurrent read+decode half is most of what is left once the sequential
     * specifier resolution ((INC.65)) is taken out.
     *
     * A compiler cannot promise this to itself — a `Vfs` whose backing store changes
     * underneath is exactly what the promise excludes — but a HOST can. On the IntelliJ
     * platform the IDE's own VFS is authoritative and guarantees change notification, so
     * a plugin that routes every such event to [Project.updateFile] /
     * [Project.deleteFile] / [Project.reloadFile] is already holding up its end.
     *
     * ## The exact scope of the promise, which is narrower than it first looks
     *
     * Only the CONTENT of a file that exists in both builds is taken on trust.
     * ADDITIONS and DELETIONS are still discovered from the backing store on every
     * build, because nothing here caches them: the root-file glob still lists
     * directories through [listEntries], and `ModuleResolver` still probes candidate
     * paths with [exists] before [readText]. So a file that appears or disappears
     * behind the promise is still seen — what is missed, and missed silently, is a file
     * whose bytes change with no notification.
     *
     * `.json` is deliberately EXCLUDED (see [readText]).
     */
    var trustFilesystem: Boolean = false
        set(value) {
            field = value
            if (!value) retained.clear()
        }

    /**
     * Drops EVERY overlay record of [path] — its text, its tombstone and anything
     * retained for it alike — so the next read goes to [delegate] and answers whatever
     * is there now.
     *
     * This is "what is on disk is the truth again", which is neither [put] (which
     * shadows disk with text) nor [remove] (which shadows it with absence).
     */
    fun revert(path: String) {
        val k = key(path)
        contents.remove(k)
        deleted.remove(k)
        retained.remove(k)
    }

    /** (INC.56) Census — reads answered from [retained] rather than from [delegate]. */
    var retainedServes: Long = 0
        private set

    /**
     * (INC.56) Census — reads answered by [readTextIfResident], i.e. WITHOUT the
     * crawl's per-file thread handoff.
     *
     * Separate from [retainedServes] because they answer different questions and only
     * this one can see the core wiring: [readText] serves the retention too, so a
     * build whose crawl stopped consulting [readTextIfResident] altogether would keep
     * every delegate-read count green and pay the handoff again, silently.
     */
    var residentServes: Long = 0
        private set

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
        // (INC.56) `.json` is never retained OR served from [retained]. A `tsconfig`'s
        // `extends` target and a `package.json`'s `type` decide what the program IS
        // ((INC.48)), they are read a handful of times per build rather than once per
        // program file, and getting one of them stale is not a wrong diagnostic but a
        // wrong PROGRAM. The saving is per SOURCE file; the risk is not worth the
        // rounding error.
        val json = k.endsWith(".json")
        if (json) jsonReads.add(k)
        if (k in deleted) return null
        contents[k]?.let { return it }
        if (!trustFilesystem || json) return delegate.readText(k)
        retained[k]?.let {
            retainedServes++
            return it
        }
        return delegate.readText(k)
    }

    /**
     * (INC.56) An overlaid buffer or a retained read, both of which are in memory here
     * and cost nothing to hand back — so the crawl need not pay a thread handoff for
     * them. Null for everything else, which is the safe answer and the only one an
     * ordinary [Vfs] gives.
     *
     * READ-ONLY, and that is a threading requirement rather than tidiness: the crawl
     * calls this from its concurrent workers, so the maps it consults may be mutated
     * only from [retainRead] and from [Project]'s own API — neither of which runs while
     * a build is in flight (round 825).
     *
     * `.json` is never answered here: [readText] records it for (INC.48)'s snapshot,
     * and a resident answer that skipped that record would make the snapshot's input
     * list depend on which files happened to be in memory.
     */
    override fun readTextIfResident(path: String): String? {
        val k = key(path)
        if (k.endsWith(".json") || k in deleted) return null
        contents[k]?.let {
            residentServes++
            return it
        }
        if (!trustFilesystem) return null
        return retained[k]?.also {
            retainedServes++
            residentServes++
        }
    }

    /**
     * (INC.56) Retains [text] as [path]'s content while [trustFilesystem] is on.
     *
     * The ONLY writer of [retained], called from the crawl's single-threaded fold, so
     * every read of that map — here, in [readText] and in [readTextIfResident] — is a
     * read of a map nothing is writing concurrently.
     *
     * A path that is overlaid or tombstoned is decided by [contents]/[deleted] and is
     * not retained: keeping a second answer for it would be dead weight at best and,
     * if the overlay were ever dropped, stale.
     */
    override fun retainRead(path: String, text: String) {
        if (!trustFilesystem) return
        val k = key(path)
        if (k.endsWith(".json") || k in deleted || k in contents) return
        retained[k] = text
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

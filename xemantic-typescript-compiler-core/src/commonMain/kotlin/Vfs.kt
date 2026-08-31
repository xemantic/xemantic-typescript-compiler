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

package com.xemantic.typescript.compiler

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * A minimal filesystem abstraction used by the whole-project build driver
 * ([ProjectCompiler], [TsConfigLoader], [ModuleResolver]).
 *
 * Paths are always `/`-separated absolute or relative strings (see [PathUtil]).
 * The default production implementation is [SystemVfs] (kotlinx-io backed, so it
 * works on JVM/Native/WASI); tests can supply an in-memory implementation to keep
 * resolution and tsconfig logic deterministic and FS-independent.
 */
class VfsEntry(
    /** Absolute, normalized path of the entry. */
    val path: String,
    /** Whether the entry is a directory. */
    val isDirectory: Boolean,
)

interface Vfs {
    /** True if a file OR directory exists at [path]. */
    fun exists(path: String): Boolean
    /** True if [path] exists and is a directory. */
    fun isDirectory(path: String): Boolean
    /** Reads the UTF-8 text of [path], or `null` if it is missing / unreadable. */
    fun readText(path: String): String?
    /** Writes [content] to [path], creating parent directories as needed. */
    fun writeText(path: String, content: String)
    /** Lists immediate child entries of directory [path] as absolute paths (empty if not a dir). */
    fun list(path: String): List<String>
    /**
     * [path] made absolute against the implementation's working directory. Identity by
     * default — in-memory implementations have no CWD and treat their keys as already
     * absolute. [ProjectCompiler] absolutizes the project path through this so relative
     * CLI invocations (`xtsc .`) produce the absolute paths glob matching requires.
     */
    fun resolveAbsolute(path: String): String = path

    /**
     * (INC.60) [list], with the ONE further question every caller of it asks —
     * "is this entry a directory?" — already answered.
     *
     * The default implementation is exactly the two calls it replaces, so an
     * implementation that does not override it is unchanged; [SystemVfs]
     * overrides it because on a real filesystem the pair costs a directory
     * listing PLUS a metadata probe per entry, and the listing already went to
     * the filesystem for the same directory.
     *
     * Order is NOT specified — [ProjectCompiler]'s root-file walk sorts, and
     * that sort is load-bearing (round 776: an unsorted crawl makes program
     * order a property of the filesystem rather than of the project).
     */
    fun listEntries(path: String): List<VfsEntry> =
        list(path).map { VfsEntry(it, isDirectory(it)) }

    /**
     * (INC.56) [path]'s text if answering CANNOT BLOCK — it is already resident in
     * this Vfs's own memory — or null when the caller must perform a real read.
     *
     * The default is null: an ordinary filesystem has nothing resident, and an
     * implementation that does not override this is unchanged.
     *
     * ## What it is for, which is NOT saving the read
     *
     * The crawl reads its files on an IO dispatcher so a blocking read cannot starve
     * the CPU-sized pool the parses run on, and that thread handoff — not the read —
     * is what a per-file read costs on an application-shaped project: measured over
     * 2,401 small files, serving EVERY read from memory moved the crawl's wall by
     * nothing, because 2,401 reads of ~1 KB spread over 16 workers are ~1-2 ms of it.
     * What the hop costs is (INC.64)'s measurement one hop later. So this exists to
     * let a caller skip the HANDOFF for content that is already in hand — an unsaved
     * editor buffer, or a file a host has promised has not changed
     * ([the `-project` module's overlay][Vfs]).
     *
     * ## The contract an implementation must keep
     *
     * It must answer exactly what [readText] would answer, or null. Null is always a
     * legal answer and always safe — the caller then performs the real read — so an
     * implementation may be as conservative as it likes. It must NOT record, mutate or
     * lazily populate anything: the crawl calls this from its concurrent workers
     * (round 825), which is also why it may not be used to serve a `.json`, whose read
     * the overlay records for (INC.48)'s snapshot.
     */
    fun readTextIfResident(path: String): String? = null

    /**
     * (INC.56) Offers [text] as what [readTextIfResident] may answer for [path] from
     * now on. Default: a no-op.
     *
     * Called from the crawl's SINGLE-THREADED fold, after the concurrent flow that
     * produced [text] has been fully drained — the same point, and for the same
     * reason, as `CrawlParseCache.store` (round 825: a plain `HashMap` write from N
     * workers is a data race with no exception to find it by). An implementation that
     * retains anything here must therefore mutate ONLY here, so that its
     * [readTextIfResident] and [readText] stay read-only.
     *
     * An implementation is free to ignore the offer, and every one that has not been
     * given a host's promise does.
     */
    fun retainRead(path: String, text: String) {}
}

/**
 * (INC.60) The platform's own directory listing, with each entry's kind.
 *
 * It exists because kotlinx-io has no such call and the pair it forces is
 * measurably expensive: `SystemFileSystem.metadataOrNull` is compiled (0.9.1,
 * `FileSystemJvmKt${'$'}SystemFileSystem${'$'}1.metadataOrNull`) to
 * `File.exists()` + `File.isFile()` + `File.isDirectory()` + `File.isFile()` +
 * `File.length()` — up to FIVE `stat` syscalls plus a `FileMetadata` allocation
 * to answer one boolean — and the root-file glob asks it once per entry. Measured
 * on a 2,401-file project: **18-21 ms over 2,451 entries (7.3-8.6 us each), 60-70%
 * of the whole glob and the largest single component of the incremental FLOOR's
 * `config load + @types + root glob` row.**
 */
internal expect fun systemListEntries(path: String): List<VfsEntry>

/**
 * Production [Vfs] backed by kotlinx-io's [SystemFileSystem]. Multiplatform-safe
 * (no `java.*`), so the whole-project compiler can stay in `commonMain`.
 */
object SystemVfs : Vfs {

    override fun exists(path: String): Boolean =
        SystemFileSystem.exists(Path(path))

    override fun isDirectory(path: String): Boolean =
        SystemFileSystem.metadataOrNull(Path(path))?.isDirectory == true

    override fun readText(path: String): String? = try {
        SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
    } catch (_: Exception) {
        null
    }

    override fun writeText(path: String, content: String) {
        val parent = PathUtil.dirname(path)
        if (parent.isNotEmpty() && !SystemFileSystem.exists(Path(parent))) {
            SystemFileSystem.createDirectories(Path(parent))
        }
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(content) }
    }

    override fun list(path: String): List<String> = try {
        SystemFileSystem.list(Path(path)).map { PathUtil.normalize(it.toString()) }
    } catch (_: Exception) {
        emptyList()
    }

    override fun listEntries(path: String): List<VfsEntry> = systemListEntries(path)

    /**
     * (SERVE.2) round 873 — the directory relative paths resolve against, when
     * it is NOT this process's own.
     *
     * A compile server runs in a directory that has nothing to do with the one
     * the user typed the command in, and a JVM cannot change its own cwd, so
     * `--serve` installs the requesting client's directory here for the duration
     * of one request. This is THE funnel: `ProjectCompiler.build` absolutizes
     * both the project path and a `--outDir` override through
     * [resolveAbsolute] before doing anything with either, so one variable makes
     * a served request resolve exactly as the same command line would have
     * resolved locally — rather than each client guessing which of the
     * compiler's arguments is a path, which is what it used to do and could not
     * get right.
     *
     * Process-global and therefore install-and-restore, on the ONE compile
     * thread (`CompileServer` invariant 1), exactly like the round-848 mode
     * ledger. Null means this process's own working directory.
     */
    var workingDirectory: String? = null

    override fun resolveAbsolute(path: String): String {
        val p = PathUtil.normalize(path)
        if (p.startsWith("/")) return p
        workingDirectory?.let { return PathUtil.join(PathUtil.normalize(it), p) }
        // SystemFileSystem.resolve throws for paths that don't exist, so resolve the
        // CWD (".", always present) and join the relative part onto it instead of
        // resolving [path] directly.
        return try {
            PathUtil.join(PathUtil.normalize(SystemFileSystem.resolve(Path(".")).toString()), p)
        } catch (_: Exception) {
            p
        }
    }
}

/**
 * Pure, platform-independent path arithmetic over `/`-separated paths. Mirrors the
 * subset of Node's `path.posix` semantics the resolver and globber need. Windows
 * `\` separators are normalized to `/` on input so callers can pass native paths.
 */
object PathUtil {

    /** Collapses `.`/`..` segments and duplicate slashes; preserves a leading `/`. */
    fun normalize(path: String): String {
        FrontEnd.pathNormalizeCalls++
        if (isNormalized(path)) {
            FrontEnd.pathNormalizeFast++
            return path
        }
        val p = path.replace('\\', '/')
        val isAbs = p.startsWith("/")
        val out = ArrayDeque<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    if (out.isNotEmpty() && out.last() != "..") out.removeLast()
                    else if (!isAbs) out.addLast("..")
                    // ".." at an absolute root is dropped (cannot escape root)
                }
                else -> out.addLast(seg)
            }
        }
        val joined = out.joinToString("/")
        return if (isAbs) "/$joined" else joined.ifEmpty { "." }
    }

    /**
     * (INC.68) True when [normalize] would answer [path] UNCHANGED — checked in one
     * pass with no allocation, where the general path allocates roughly ten objects
     * (a `replace`, a `split` list plus a `String` per segment, an `ArrayDeque`, and
     * a `joinToString` builder).
     *
     * It is worth having because almost every path this compiler normalizes is
     * already normalized: `systemListEntries` normalizes a child path built by the
     * platform from an already-normalized parent, and [join] normalizes
     * `"<normalized base>/<plain name>"`. Measured on a 2,401-file project, the
     * root-file glob alone spends 2.5-3.5 ms of its 12-13 ms doing this for no
     * change at all, and the census counters ([FrontEnd.pathNormalizeCalls] /
     * [FrontEnd.pathNormalizeFast]) are what say how much of the population is
     * really in this shape rather than assumed to be.
     *
     * DELIBERATELY CONSERVATIVE — it answers false for anything it is not certain
     * about, including the `..`-only path (`".."`, a genuine fixed point) and the
     * empty string (which normalizes to `"."`). A false negative costs the old
     * behaviour; a false positive would return an UN-normalized path, so the two
     * directions are not symmetric and the pin population is the fixed points.
     */
    internal fun isNormalized(path: String): Boolean {
        val n = path.length
        if (n == 0) return false
        var i = if (path[0] == '/') 1 else 0
        if (i == n) return true // exactly "/"
        var segStart = i
        while (i <= n) {
            val c = if (i == n) '/' else path[i]
            if (c == '\\') return false
            if (c == '/') {
                val len = i - segStart
                // an empty segment is "//" or a trailing "/"
                if (len == 0) return false
                if (len == 1 && path[segStart] == '.') {
                    // a "." segment survives only as the WHOLE relative path
                    if (segStart != 0 || n != 1) return false
                }
                if (len == 2 && path[segStart] == '.' && path[segStart + 1] == '.') return false
                segStart = i + 1
            }
            i++
        }
        return true
    }

    /** Joins [base] with [part]; an absolute [part] replaces [base]. Result is normalized. */
    fun join(base: String, part: String): String =
        if (part.startsWith("/")) normalize(part) else normalize("$base/$part")

    /** The parent directory of [path] (normalized), or "" / "/" at the root. */
    fun dirname(path: String): String {
        val n = normalize(path)
        if (n == "/") return "/"
        val idx = n.lastIndexOf('/')
        return when {
            idx < 0 -> ""
            idx == 0 -> "/"
            else -> n.substring(0, idx)
        }
    }

    /** The final path segment of [path]. */
    fun basename(path: String): String =
        normalize(path).substringAfterLast('/')

    /** The extension of [path] including the leading dot (e.g. ".ts"), or "" if none. */
    fun extname(path: String): String {
        val base = basename(path)
        val idx = base.lastIndexOf('.')
        return if (idx > 0) base.substring(idx) else ""
    }

    /** A TypeScript-relative specifier: ".", "..", "./x", or "../x". */
    fun isRelative(specifier: String): Boolean =
        specifier == "." || specifier == ".." ||
            specifier.startsWith("./") || specifier.startsWith("../")

    /** An absolute (`/`-rooted) specifier. */
    fun isAbsolute(specifier: String): Boolean = specifier.startsWith("/")

    /** A bare/non-relative module specifier (resolved through `node_modules`). */
    fun isBare(specifier: String): Boolean =
        specifier.isNotEmpty() && !isRelative(specifier) && !isAbsolute(specifier)

    /** [path] made relative to [fromDir] (both normalized); falls back to [path] if not under it. */
    fun relativeTo(fromDir: String, path: String): String {
        val from = normalize(fromDir).removeSuffix("/")
        val to = normalize(path)
        if (to == from) return ""
        val prefix = "$from/"
        return if (to.startsWith(prefix)) to.substring(prefix.length) else to
    }
}

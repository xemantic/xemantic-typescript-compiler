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

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * INV.7(d3): cross-process `.xtsbuildinfo` persistence — the cold-start
 * counterpart of the in-memory watch-mode incremental protocol (INV.7(d1)/(d2),
 * [WatchIncremental]).
 *
 * After a build, `{buildId, per-file content hashes, program graph, diagnostics}`
 * is persisted next to the tsconfig (`tsconfig.xtsbuildinfo`). On the next
 * cold start the stored info is validated — SAME compiler build id (the
 * generated [XTSC_BUILD_ID]; a stale-compiler reuse of kept diagnostics is
 * otherwise silent, which is why tsc embeds its version), then per-file hash
 * comparison yields the changed set — and the exact (7d1) closure protocol
 * runs: eligibility → reverse-dependency-closure partition recheck → outcome
 * validation → diagnostic merge, with a full rebuild on any bail.
 *
 * Config inputs (tsconfig/package.json — every `.json` the build reads outside
 * the program) are hashed too via [RecordingVfs]; a changed/deleted config file
 * lands in the changed set and [WatchIncremental.incrementalEligible] bails it
 * to a full rebuild. NEW files that globs would newly pick up are caught by the
 * post-build [WatchIncremental.incrementalOutcomeValid] program-shape check
 * (the partition build re-crawls the file graph from disk).
 *
 * Reuse is refused for an `unknown` or `.dirty`-suffixed build id — only a
 * clean, identical compiler build may reuse persisted diagnostics (dirty dev
 * trees silently differ under one id). Persistence failures never fail the
 * build.
 */
internal object TsBuildInfo {

    // -- stored model -----------------------------------------------------------

    /**
     * (INC.48) Shared with [ProjectStateSnapshot], deliberately: a diagnostic's
     * `relatedInformation` and `messageChain` are easy to drop in a second mapping, and
     * a dropped chain is a diagnostic that reads differently after a restore than it did
     * before it. One mapping, two persistence formats.
     */
    @Serializable
    internal data class StoredDiagnostic(
        val message: String,
        val category: String,
        val code: Int,
        val fileName: String? = null,
        val line: Int? = null,
        val character: Int? = null,
        val start: Int? = null,
        val length: Int? = null,
        val related: List<StoredDiagnostic> = emptyList(),
        val chain: List<String> = emptyList(),
    )

    @Serializable
    internal data class StoredEdge(val importer: String, val imported: String)

    @Serializable
    internal data class Stored(
        val buildId: String,
        /** Content hashes of every build input: program files + `.json` config reads. */
        val fileHashes: Map<String, String> = emptyMap(),
        val programFiles: List<String> = emptyList(),
        val moduleFiles: List<String> = emptyList(),
        val sharedNameFiles: List<String> = emptyList(),
        val importEdges: List<StoredEdge> = emptyList(),
        val diagnostics: List<StoredDiagnostic> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    // -- identity + hashing -----------------------------------------------------

    /** Only a clean, known compiler build may reuse persisted diagnostics. */
    fun buildIdReusable(id: String): Boolean =
        id != "unknown" && !id.endsWith(".dirty")

    /** FNV-1a 64-bit over the text — change detection, not adversarial integrity. */
    fun contentHash(text: String): String {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325uL, the FNV-1a offset basis
        for (c in text) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16)
    }

    /**
     * The buildinfo sibling of [configPath] (`tsconfig.json` →
     * `tsconfig.xtsbuildinfo`), or null when the build has no `.json` config to
     * sit next to (bare-source-file invocations are never persisted).
     */
    fun infoPath(configPath: String): String? =
        if (configPath.endsWith(".json")) configPath.removeSuffix(".json") + ".xtsbuildinfo" else null

    /**
     * The config path [ProjectCompiler.build] will resolve for [projectPath] —
     * needed BEFORE the build so the stored info can steer it. Mirrors the
     * build's own resolution (directory → `tsconfig.json`; a non-`.json` file
     * argument is a bare-source build → null).
     */
    fun predictConfigPath(vfs: Vfs, projectPath: String): String? {
        val p = PathUtil.normalize(vfs.resolveAbsolute(projectPath))
        val config = if (vfs.isDirectory(p)) "$p/tsconfig.json" else p
        return if (config.endsWith(".json")) config else null
    }

    // -- conversions ------------------------------------------------------------

    internal fun toStored(d: Diagnostic): StoredDiagnostic = StoredDiagnostic(
        message = d.message, category = d.category.name, code = d.code,
        fileName = d.fileName, line = d.line, character = d.character,
        start = d.start, length = d.length,
        related = d.relatedInformation.map(::toStored), chain = d.messageChain,
    )

    internal fun toDiagnostic(s: StoredDiagnostic): Diagnostic = Diagnostic(
        message = s.message,
        category = try { DiagnosticCategory.valueOf(s.category) } catch (_: Exception) { DiagnosticCategory.Error },
        code = s.code, fileName = s.fileName, line = s.line, character = s.character,
        start = s.start, length = s.length,
        relatedInformation = s.related.map(::toDiagnostic), messageChain = s.chain,
    )

    /** A synthetic previous [ProjectCompiler.Result] carrying exactly the fields the (7d1) protocol consults. */
    private fun toResultShell(stored: Stored, configPath: String): ProjectCompiler.Result =
        ProjectCompiler.Result(
            configPath = configPath,
            rootFiles = emptyList(),
            programFiles = stored.programFiles,
            diagnostics = stored.diagnostics.map(::toDiagnostic),
            unresolved = emptyList(),
            importEdges = stored.importEdges.map { it.importer to it.imported },
            moduleFiles = stored.moduleFiles.toSet(),
            sharedNameFiles = stored.sharedNameFiles.toSet(),
            written = emptyList(),
        )

    // -- persistence ------------------------------------------------------------

    fun read(vfs: Vfs, infoPath: String): Stored? = try {
        vfs.readText(infoPath)?.let { json.decodeFromString<Stored>(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Persists [result] (+ the [configReads] `.json` inputs) — failures are swallowed, never build-fatal. */
    fun write(vfs: Vfs, infoPath: String, buildId: String, result: ProjectCompiler.Result, configReads: Set<String>) {
        try {
            val hashes = LinkedHashMap<String, String>()
            for (path in result.programFiles + configReads.filter { it !in result.programFiles }) {
                val content = vfs.readText(path) ?: continue // unreadable → no hash → treated as changed next start
                hashes[path] = contentHash(content)
            }
            val stored = Stored(
                buildId = buildId,
                fileHashes = hashes,
                programFiles = result.programFiles,
                moduleFiles = result.moduleFiles.toList(),
                sharedNameFiles = result.sharedNameFiles.toList(),
                importEdges = result.importEdges.map { StoredEdge(it.first, it.second) },
                diagnostics = result.diagnostics.map(::toStored),
            )
            vfs.writeText(infoPath, json.encodeToString(stored))
        } catch (_: Exception) {
            // Persistence must never fail the build.
        }
    }

    // -- the cold-start protocol --------------------------------------------------

    /**
     * Builds [projectPath], reusing a persisted `.xtsbuildinfo` when valid:
     * unchanged inputs skip the check entirely (empty partition), a local
     * change rechecks its reverse-dependency closure, anything non-local falls
     * back to a full build. Always re-persists the outcome (under a reusable
     * [buildId]). [log] receives one human-readable line describing the path
     * taken.
     */
    fun build(
        vfs: Vfs,
        projectPath: String,
        noEmit: Boolean,
        buildId: String,
        log: (String) -> Unit = {},
    ): ProjectCompiler.Result {
        val recording = RecordingVfs(vfs)
        val configPath = predictConfigPath(vfs, projectPath)
        val infoPath = configPath?.let(::infoPath)
        val stored = infoPath?.let { read(vfs, it) }

        val result: ProjectCompiler.Result = run {
            if (configPath == null || stored == null || !noEmit) {
                log(
                    if (stored == null) "buildinfo: none — full build"
                    else "buildinfo: present but --incremental needs --noEmit — full build"
                )
                return@run ProjectCompiler(recording).build(projectPath, noEmit)
            }
            if (!buildIdReusable(buildId) || stored.buildId != buildId) {
                log("buildinfo: compiler build id mismatch (stored ${stored.buildId}, running $buildId) — full build")
                return@run ProjectCompiler(recording).build(projectPath, noEmit)
            }
            // The changed set: every stored input whose current content hash
            // differs (deleted/unreadable counts as changed), plus any program
            // file the last write could not hash.
            val changed = HashSet<String>()
            for ((path, hash) in stored.fileHashes) {
                val current = vfs.readText(path)
                if (current == null || contentHash(current) != hash) changed.add(path)
            }
            for (path in stored.programFiles) if (path !in stored.fileHashes) changed.add(path)

            val prev = toResultShell(stored, configPath)
            val eligible = changed.isEmpty() ||
                WatchIncremental.incrementalEligible(changed, prev) { vfs.readText(it) }
            if (!eligible) {
                log("buildinfo: non-local change (${changed.size} file(s)) — full build")
                return@run ProjectCompiler(recording).build(projectPath, noEmit)
            }
            val closure =
                if (changed.isEmpty()) emptySet()
                else WatchIncremental.recheckClosure(changed, prev.importEdges)
            val fresh = ProjectCompiler(recording).build(projectPath, noEmit, recheckOnly = closure)
            if (!WatchIncremental.incrementalOutcomeValid(changed, prev, fresh)) {
                log("buildinfo: program shape changed — full build")
                return@run ProjectCompiler(recording).build(projectPath, noEmit)
            }
            log("buildinfo: incremental recheck of ${closure.size}/${stored.programFiles.size} file(s)")
            fresh.copy(diagnostics = WatchIncremental.mergeDiagnostics(prev, fresh.diagnostics, closure))
        }

        if (infoPath != null && buildIdReusable(buildId)) {
            write(vfs, infoPath, buildId, result, recording.jsonReads)
        }
        return result
    }
}

/**
 * A [Vfs] wrapper recording every `.json` read — the build's config-class
 * inputs (tsconfig + extends chain, package.json, resolved `.json` modules).
 * [TsBuildInfo] hashes them alongside the program files so a config change is
 * detected on the next cold start.
 */
internal class RecordingVfs(private val delegate: Vfs) : Vfs by delegate {
    val jsonReads = LinkedHashSet<String>()
    override fun readText(path: String): String? {
        if (path.endsWith(".json")) jsonReads.add(path)
        return delegate.readText(path)
    }
}

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
 * (INC.48) A project's incremental state in a form that OUTLIVES THE PROCESS —
 * what an editor integration needs so that reopening a project does not pay for a
 * whole-program build of a tree nobody touched.
 *
 * ## Why this exists
 *
 * (INC.46) made project-wide diagnostics incremental WITHIN a process: an edit that
 * moves no exported signature is answered from the previous build's rows plus one
 * narrowed build, 108-113 ms against ~5 s. All of that state is in memory, so an IDE
 * restart — or a plugin reload, or a daemon recycle — throws it away and the first
 * query pays the full build again. tsgo does not: it writes a `.tsbuildinfo` and
 * reads it back, which is what makes its post-restart no-op cheap.
 *
 * This is the state that has to survive: the export SIGNATURES the gate compares, the
 * ESCAPES it refuses on, the program's FILE LIST, that build's DIAGNOSTICS, and a
 * content hash per input so the next process can tell what moved while it was gone.
 *
 * ## What it deliberately does NOT do
 *
 * It does not touch the filesystem. [encode] answers a string and [decode] takes one,
 * so the host decides where — and whether — the state lives: an IDE keeps such caches
 * in its own directory, a build tool next to its outputs, a test in memory. Writing a
 * file into somebody's source tree is a side effect an embedding API should not
 * perform unasked, and the compiler's own CLI already offers the other convention
 * (`tsconfig.xtsbuildinfo`, `--incremental`, INV.7(d3)) for callers who want it.
 *
 * ## The validation contract, and why each part of it is not optional
 *
 * A snapshot is a claim about a compiler, a configuration and a set of file contents.
 * `Project.restoreState` checks every part of that claim, and each check exists
 * because skipping it produces a STALE ANSWER — the one failure this whole arc is
 * built to prevent:
 *
 *  - **[buildId]** — a different compiler may report different diagnostics for the
 *    same text, and reusing the old rows would attribute one compiler's answer to
 *    another. A `.dirty` or `unknown` id is refused outright ([isReusableBuildId]):
 *    two dirty dev trees share one id and do not share their behaviour.
 *  - **[configPath]** — the state describes one project.
 *  - **[fileHashes] by CONTENT, never by mtime or size.** A stat-based key is round
 *    871's trap: a rewrite that preserves an mtime is invisible to it, and a checkout
 *    that changes one produces a spurious rebuild. The crawl reads every file anyway,
 *    so the bytes are in hand and staleness is not expressible.
 *  - **The `.json` inputs are hashed too**, not just the program's sources. A changed
 *    `tsconfig.json` (or an `extends` target, or a `package.json` whose `type` decides
 *    a file's module format) changes what the program IS and which options apply, so
 *    the stored rows are not merely out of date for the edited file — they are wrong
 *    everywhere. Any such change refuses the restore rather than narrowing it.
 *  - **[programFiles]** — a file ADDED or REMOVED while the process was down changes
 *    what every importer resolves. Content hashes cannot see an addition, which is why
 *    the restored state is not trusted until a build has re-crawled and produced the
 *    same list.
 */
class ProjectStateSnapshot internal constructor(
    /** The compiler build this state was produced by — see the class KDoc. */
    val buildId: String,
    /** The `tsconfig.json` the project was checked against, absolute. */
    val configPath: String,
    /**
     * `path -> content hash` for every program file AND every `.json` the build read
     * outside the program. A path present here and unreadable now counts as changed.
     */
    val fileHashes: Map<String, String>,
    /** The program the build found, in crawl order. */
    val programFiles: List<String>,
    /** (INC.46) `file -> exported-signature fingerprint`. */
    val exportSignatures: Map<String, Long>,
    /** (INC.46) Files that may not be proved stable however they are edited. */
    val exportEscapes: Set<String>,
    /** That build's whole-program diagnostics, row for row and in order. */
    val diagnostics: List<Diagnostic>,
) {

    /** This state as text, for a host to store wherever it keeps its caches. */
    fun encode(): String = json.encodeToString(
        Stored(
            version = FORMAT_VERSION,
            buildId = buildId,
            configPath = configPath,
            fileHashes = fileHashes,
            programFiles = programFiles,
            exportSignatures = exportSignatures.mapValues { it.value.toString() },
            exportEscapes = exportEscapes.toList(),
            diagnostics = diagnostics.map(TsBuildInfo::toStored),
        ),
    )

    @Serializable
    private data class Stored(
        val version: Int,
        val buildId: String,
        val configPath: String,
        val fileHashes: Map<String, String> = emptyMap(),
        val programFiles: List<String> = emptyList(),
        /**
         * Fingerprints as STRINGS. A `Long` round-trips through JSON as a number, and a
         * number is where a host's own tooling (a JS-based IDE bridge re-serialising the
         * blob, a `jq` filter) silently loses the low bits of a 64-bit value — which
         * would make two different surfaces compare equal, i.e. a stale answer.
         */
        val exportSignatures: Map<String, String> = emptyMap(),
        val exportEscapes: List<String> = emptyList(),
        val diagnostics: List<TsBuildInfo.StoredDiagnostic> = emptyList(),
    )

    companion object {

        /**
         * The stored format's version. Bump it for any change that alters what a field
         * MEANS; a snapshot at another version is refused rather than read, since an
         * absent field decoded as its default is exactly how a validation silently
         * stops validating.
         */
        private const val FORMAT_VERSION: Int = 1

        private val json = Json { ignoreUnknownKeys = true }

        /** The compiler build that is running now — see [buildId]. */
        val compilerBuildId: String get() = XTSC_BUILD_ID

        /**
         * TEST SEAM — treat an unstable build id as reusable anyway. OFF in every
         * shipped path, installed and RESTORED by the pins that need it.
         *
         * It exists because the rule below would otherwise make its own tests vacuous
         * in one environment and meaningful in another: a development tree's id always
         * ends in `.dirty`, so locally every save answers null and every restore
         * refuses, while CI builds from a clean checkout and exercises the real path.
         * A pin that passes for opposite reasons in two environments is worse than no
         * pin (round 902's dead arm, one layer up).
         */
        var allowUnstableBuildIdForTesting: Boolean = false

        /**
         * Whether state produced by [id] may be reused at all. False for `unknown` and
         * for any `.dirty` id: those name a tree with local changes, and two such trees
         * share the id without sharing the behaviour.
         */
        fun isReusableBuildId(id: String): Boolean =
            TsBuildInfo.buildIdReusable(id) || allowUnstableBuildIdForTesting

        /** The content hash a snapshot's [fileHashes] are built from (FNV-1a 64). */
        fun contentHash(text: String): String = TsBuildInfo.contentHash(text)

        /** Stamps the running compiler's [compilerBuildId] onto a fresh snapshot. */
        fun of(
            configPath: String,
            fileHashes: Map<String, String>,
            programFiles: List<String>,
            exportSignatures: Map<String, Long>,
            exportEscapes: Set<String>,
            diagnostics: List<Diagnostic>,
        ): ProjectStateSnapshot = ProjectStateSnapshot(
            buildId = XTSC_BUILD_ID,
            configPath = configPath,
            fileHashes = fileHashes,
            programFiles = programFiles,
            exportSignatures = exportSignatures,
            exportEscapes = exportEscapes,
            diagnostics = diagnostics,
        )

        /**
         * [text] as a snapshot, or null when it is not one this compiler can read —
         * malformed, or written at another [FORMAT_VERSION]. Null is the only failure
         * signal deliberately: every caller's answer to "I cannot read this" is to
         * build, which is what it would have done anyway.
         */
        fun decode(text: String): ProjectStateSnapshot? {
            val stored = try {
                json.decodeFromString<Stored>(text)
            } catch (_: SerializationException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (stored.version != FORMAT_VERSION) return null
            val signatures = LinkedHashMap<String, Long>(stored.exportSignatures.size)
            for ((file, value) in stored.exportSignatures) {
                signatures[file] = value.toLongOrNull() ?: return null
            }
            return ProjectStateSnapshot(
                buildId = stored.buildId,
                configPath = stored.configPath,
                fileHashes = stored.fileHashes,
                programFiles = stored.programFiles,
                exportSignatures = signatures,
                exportEscapes = stored.exportEscapes.toSet(),
                diagnostics = stored.diagnostics.map(TsBuildInfo::toDiagnostic),
            )
        }
    }
}

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

import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.PathUtil
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SystemVfs
import com.xemantic.typescript.compiler.Vfs

/**
 * An open TypeScript project a host application can query for diagnostics and
 * edit IN MEMORY.
 *
 * This is the embedding API: a build tool, an IDE plugin or a test harness opens a
 * project once, asks it what is wrong, applies the buffers its user is typing into,
 * and asks again — without the edits ever reaching the filesystem.
 *
 * ```kotlin
 * val project = Project.open("/path/to/project")
 * project.diagnostics()                       // as the project is on disk
 * project.updateFile("/path/to/project/src/a.ts", editorBuffer)
 * project.diagnostics("/path/to/project/src/a.ts")   // as the user is typing it
 * project.close()
 * ```
 *
 * ## What this class is NOT
 *
 * It is not a language service: there are no completions, no hovers, no
 * find-references, and no incremental reuse of a previous build's internal state.
 * A query on a dirty project is a FULL rebuild, and that is a property of the
 * compiler rather than a shortcut taken here — `ProjectCompiler.Result` is a flat
 * value (paths, diagnostics, an import graph) that retains no AST, no binder
 * output and no checker; the checker's construction IS the compilation
 * (`docs/ARCHITECTURE-RETHINK.md`). What makes a re-query cheap anyway is the
 * compiler's process-global, CONTENT-keyed parse cache, which every unedited file
 * hits — so the second build of an N-file project re-parses only what changed. Do
 * not add "incremental" reuse on top of this class; the seam for it does not exist
 * yet.
 *
 * ## Emit
 *
 * Every build this class performs passes `noEmit = true`. A tool that opens a
 * project to ask questions about it must never scatter JavaScript through the
 * user's tree as a side effect of a query, and an editor overlay makes that worse
 * still — the emitted output would correspond to unsaved buffers. Emitting is
 * `ProjectCompiler`'s job and stays there.
 *
 * ## Paths
 *
 * Every path that crosses this API — the project path, the argument of
 * [updateFile] / [deleteFile] / [diagnostics] — is normalized and made absolute
 * through the backing [Vfs] before it is used as a key, because the crawl works in
 * absolute paths and a relative key would silently never match anything. That
 * failure mode has bitten this repo before at a different layer: a client guessing
 * which of its arguments were paths and resolving them against the wrong directory
 * (CLAUDE.md, round 873). The keys are the absolute paths, and the conversion
 * happens once, here.
 *
 * This class does NOT touch `SystemVfs.workingDirectory`: that variable is
 * process-global and install-and-restore, owned by the compile server for the
 * duration of one served request. A host that needs relative paths resolved
 * against something other than its own working directory should pass a [Vfs] whose
 * [Vfs.resolveAbsolute] says so.
 *
 * Not thread-safe: one [Project] belongs to one thread at a time. Builds are
 * driven synchronously on the calling thread (the compiler runs its pipeline on
 * its own deep-stack thread internally and joins it before returning).
 */
public class Project private constructor(
    /** The project path as given to [open], normalized and absolute. */
    private val projectPath: String,
    /** The overlay wrapping the caller's [Vfs] — the compiler only ever sees this. */
    private val overlay: OverlayVfs,
) {

    public companion object {

        /**
         * Opens the project at [projectPath], reading through [vfs].
         *
         * [projectPath] is either a directory containing `tsconfig.json` or a path
         * to a config file — the same argument `ProjectCompiler.build` and the `xtsc`
         * command line take.
         *
         * NOTHING IS COMPILED HERE. Opening only resolves and validates the path;
         * the first query compiles. That is deliberate and is what lets a caller
         * stage its editor buffers with [updateFile] BEFORE the first build, so a
         * host that already has unsaved state does not pay for a build of the
         * on-disk truth it is about to discard.
         *
         * @throws IllegalArgumentException if [projectPath] does not exist. This is a
         *   guard, not politeness: a project path that does not exist used to make
         *   the crawl walk upwards from `/` (CLAUDE.md, round 873 — `dirname` of a
         *   config nobody confirmed is `/`), and inside a long-lived host that is
         *   not a slow query but a wedged process.
         */
        public fun open(projectPath: String, vfs: Vfs = SystemVfs): Project {
            val absolute = vfs.resolveAbsolute(PathUtil.normalize(projectPath))
            require(vfs.exists(absolute)) {
                "project path does not exist: $absolute"
            }
            return Project(absolute, OverlayVfs(vfs))
        }
    }

    /**
     * The `tsconfig.json` this project is checked against, as an absolute path.
     *
     * Resolved exactly as `ProjectCompiler` resolves it — a directory argument gets
     * `/tsconfig.json` appended, any other argument is the config itself — and
     * derived without compiling, so reading it is free. It is reported even when the
     * file is absent, in which case the build runs on default options and includes
     * nothing; a caller that wants to distinguish the two cases can stage a config
     * through [updateFile] before its first query.
     */
    public val configPath: String =
        if (overlay.isDirectory(projectPath)) "$projectPath/tsconfig.json" else projectPath

    /**
     * The most recent build, or null when this project is dirty (never built, or
     * edited since the last build).
     */
    private var cached: ProjectCompiler.Result? = null

    private var closed: Boolean = false

    /**
     * Every file in the current program: the config's root files plus everything
     * reachable from them through imports, plus the declaration files, in crawl
     * order.
     *
     * Reading this BUILDS if the project is dirty — it is a question about a
     * program, and the program is what a build computes.
     */
    public val files: List<String> get() = build().programFiles

    /**
     * Every diagnostic the whole program produces — errors, warnings and
     * suggestions alike, in the compiler's own order.
     *
     * Builds only when dirty: two calls with no intervening edit return the same
     * result and perform no filesystem reads and no checking the second time. That
     * is not an optimization detail a caller may ignore — a query is a full compile,
     * so a host that re-asks per keystroke without editing must not pay for it.
     */
    public fun diagnostics(): List<Diagnostic> = build().diagnostics

    /**
     * The diagnostics whose [Diagnostic.fileName] is [fileName].
     *
     * [fileName] is normalized and absolutized exactly as [updateFile]'s argument
     * is, so a caller may pass it in whichever of the two forms it holds. A file
     * that is not part of the program — or has no diagnostics — yields an empty
     * list rather than an error: "which errors are in this buffer" has an answer for
     * any buffer, and an exception there would force every caller to pre-check
     * membership.
     */
    public fun diagnostics(fileName: String): List<Diagnostic> {
        val key = keyOf(fileName)
        // `?.let` rather than a null check and a smart cast: `Diagnostic.fileName` is
        // a public property of another module, where Kotlin refuses to smart-cast.
        return build().diagnostics.filter { d ->
            d.fileName?.let { PathUtil.normalize(it) } == key
        }
    }

    /**
     * Overlays [text] as the content of [path], as an unsaved editor buffer.
     *
     * Nothing is written to disk — this is the whole point of the class — and the
     * path need not exist there: overlaying a new file makes the next build discover
     * it through the glob and resolve imports to it, including in a directory that
     * exists nowhere but in the overlay (see [OverlayVfs] for why those are three
     * separate mechanisms).
     *
     * Marks the project dirty unconditionally, even when [text] equals what the
     * previous build saw. Skipping the invalidation would be a cheap optimization
     * and a bad contract: it would make "did my edit take effect" depend on a string
     * comparison the caller cannot see.
     */
    public fun updateFile(path: String, text: String) {
        checkOpen()
        overlay.put(keyOf(path), text)
        cached = null
    }

    /**
     * Overlays [path] as ABSENT, as a file closed-and-deleted in an editor.
     *
     * Again nothing touches disk: the file stays where it is and the next build
     * behaves as though it were gone — an import of it becomes unresolved, and it
     * drops out of the program. Undo by [updateFile]-ing its content back.
     */
    public fun deleteFile(path: String) {
        checkOpen()
        overlay.remove(keyOf(path))
        cached = null
    }

    /**
     * Releases the overlay and the cached build.
     *
     * Idempotent, so a host may close on every teardown path without tracking
     * whether it already has. Any query or edit afterwards throws
     * [IllegalStateException]: a closed project has no state to answer from, and
     * silently reopening it would hand back the on-disk truth as though the caller's
     * edits were still applied.
     *
     * The compiler's process-global parse cache is deliberately NOT cleared —
     * it is shared by every project in the process and keyed by content, so
     * dropping it here would slow down unrelated work to free memory the next build
     * would immediately re-earn.
     */
    public fun close() {
        if (closed) return
        closed = true
        overlay.clear()
        cached = null
    }

    /**
     * The current build, computing it if this project is dirty.
     *
     * `noEmit = true` and `outDir = null`: see the class KDoc. `recheckOnly` is
     * likewise null — it is the watch mode's per-file narrowing, and narrowing a
     * rebuild to a file set is only sound with a dependency closure this class does
     * not maintain.
     */
    private fun build(): ProjectCompiler.Result {
        checkOpen()
        cached?.let { return it }
        val result = ProjectCompiler(overlay).build(projectPath, noEmit = true)
        cached = result
        return result
    }

    /** [path] as this project keys it: normalized and absolute. */
    private fun keyOf(path: String): String =
        overlay.resolveAbsolute(PathUtil.normalize(path))

    private fun checkOpen() {
        check(!closed) { "this Project is closed: $projectPath" }
    }
}

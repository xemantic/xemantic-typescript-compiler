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

package com.xemantic.typescript.tsgo.project

import com.xemantic.typescript.tsgo.api.XtscCheckerPool
import com.xemantic.typescript.tsgo.api.getGlobalDiagnostics
import com.xemantic.typescript.tsgo.ast.Diagnostic
import com.xemantic.typescript.tsgo.compiler.Program
import com.xemantic.typescript.tsgo.compiler.sortAndDeduplicateDiagnostics
import com.xemantic.typescript.tsgo.go.context.Context
import com.xemantic.typescript.tsgo.runtime.GoElem
import com.xemantic.typescript.tsgo.runtime.GoSlice
import com.xemantic.typescript.tsgo.tsoptions.ParseConfigHost
import com.xemantic.typescript.tsgo.tspath.Path
import com.xemantic.typescript.tsgo.vfs.FS

// tsgo's `internal/project` (the language server's project system: snapshots, file watching, configured
// and inferred projects, checker pools, ATA), HAND-WRITTEN as the few members the ported API session
// reads ((TSGO.3-b), docs/goport-api.md). The in-process session does not run the project system: its
// host builds ONE program per tsconfig (`api.XtscOpenProgram`, the way `Project.CreateProgram` does) and
// hands the session a snapshot of it. Everything below is the Go member's contract over that program.

/** `project.Session`: the session's file system and current directory (a `tsoptions.ParseConfigHost`). */
class Session(private val fs: FS?, private val currentDirectory: String) : ParseConfigHost {
    override fun fs(): FS? = fs
    override fun getCurrentDirectory(): String = currentDirectory
}

/** `project.Snapshot`: an immutable set of projects, identified by [id]. */
class Snapshot(private val id: ULong, var projectCollection: ProjectCollection?) {
    fun id(): ULong = id
}

/** `project.ProjectCollection`: the snapshot's projects by config path. */
class ProjectCollection(private val projects: Map<String, Project>) {
    fun getProjectByPath(projectPath: Path): Project? = projects[projectPath.value]
    fun projects(): Collection<Project> = projects.values
}

/**
 * `project.Project` of a configured project: [id] is the tsconfig's path (`Project.ID`), [program] its
 * program and [checkerPool] the program's pool (`GetProjectDiagnostics` reads its global diagnostics).
 */
class Project(private val id: Path, private val program: Program?, private val checkerPool: XtscCheckerPool?) {
    fun id(): Path = id
    fun getProgram(): Program? = program

    /** `Project.GetProjectDiagnostics`: config-file, program and global diagnostics, sorted and deduplicated. */
    fun getProjectDiagnostics(ctx: Context?): GoSlice<Diagnostic?> {
        val p = program ?: return GoElem.ref<Diagnostic?>().nilSlice
        val all = ArrayList<Diagnostic?>()
        p.getConfigFileParsingDiagnostics().let { s -> for (i in 0 until s.len) all += s[i] }
        p.getProgramDiagnostics().let { s -> for (i in 0 until s.len) all += s[i] }
        checkerPool?.getGlobalDiagnostics()?.let { s -> for (i in 0 until s.len) all += s[i] }
        val out = GoSlice.make(GoElem.ref<Diagnostic?>(), all.size)
        for ((i, d) in all.withIndex()) out[i] = d
        return sortAndDeduplicateDiagnostics(out)
    }
}

/** `project.FileChangeSummary`: named only by the stubbed `Session.toFileChangeSummary`. */
class FileChangeSummary

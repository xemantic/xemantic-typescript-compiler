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
import com.xemantic.typescript.tsgo.core.computeECMALineStarts
import com.xemantic.typescript.tsgo.go.sync.Mutex
import com.xemantic.typescript.tsgo.ls.Host
import com.xemantic.typescript.tsgo.ls.autoimport.Registry
import com.xemantic.typescript.tsgo.ls.lsconv.Converters
import com.xemantic.typescript.tsgo.ls.lsconv.LSPLineMap
import com.xemantic.typescript.tsgo.ls.lsconv.computeLSPLineStarts
import com.xemantic.typescript.tsgo.ls.lsconv.newConverters
import com.xemantic.typescript.tsgo.ls.lsutil.UserPreferences
import com.xemantic.typescript.tsgo.ls.lsutil.newDefaultUserPreferences
import com.xemantic.typescript.tsgo.lsp.lsproto.PositionEncodingKind
import com.xemantic.typescript.tsgo.lsp.lsproto.PositionEncodingKindUTF16
import com.xemantic.typescript.tsgo.runtime.Tuple2
import com.xemantic.typescript.tsgo.sourcemap.ECMALineInfo
import com.xemantic.typescript.tsgo.sourcemap.createECMALineInfo
import com.xemantic.typescript.tsgo.vfs.vfsmatch.readDirectory

// tsgo's `internal/project` (the language server's project system: snapshots, file watching, configured
// and inferred projects, checker pools, ATA), HAND-WRITTEN as the few members the ported API session
// and language service read ((TSGO.3-b) docs/goport-api.md, (TSGO.4-a) docs/goport-ls.md). The in-process session does not run the project system: its
// host builds ONE program per tsconfig (`api.XtscOpenProgram`, the way `Project.CreateProgram` does) and
// hands the session a snapshot of it. Everything below is the Go member's contract over that program.

/** `project.Session`: the session's file system and current directory (a `tsoptions.ParseConfigHost`). */
class Session(private val fs: FS?, private val currentDirectory: String) : ParseConfigHost {
    override fun fs(): FS? = fs
    override fun getCurrentDirectory(): String = currentDirectory
}

/**
 * `project.Snapshot`: an immutable set of projects, identified by [id] — and, as in tsgo, the language
 * service's [Host] ((TSGO.4-a), docs/goport-ls.md): its files come from [fs] (the project system's
 * overlay-over-disk view, here the disk alone), each file's LSP line map and ECMAScript line info computed
 * once ([fileBase] in overlayfs.go), its [converters] speak [positionEncoding], and its preferences are the
 * session's [userPreferences] for every file (`Snapshot.GetPreferences` ignores the active file).
 */
class Snapshot(
    private val id: ULong,
    var projectCollection: ProjectCollection?,
    private val fs: FS? = null,
    private val userPreferences: UserPreferences = newDefaultUserPreferences(),
    positionEncoding: PositionEncodingKind = PositionEncodingKindUTF16,
    private val autoImports: Registry? = null,
) : Host {
    fun id(): ULong = id

    private class FileHandle(val content: String) {
        val lspLineMap: LSPLineMap? by lazy { computeLSPLineStarts(content) }
        val ecmaLineInfo: ECMALineInfo? by lazy { createECMALineInfo(content, computeECMALineStarts(content)) }
    }

    private val files = HashMap<String, FileHandle?>()
    private val filesLock = Mutex()

    /** `Snapshot.GetFile`: the file's content, read once per snapshot (a snapshot is immutable). */
    private fun getFile(fileName: String): FileHandle? {
        val fs = fs ?: return null
        filesLock.lock()
        try {
            if (fileName in files) return files[fileName]
        } finally {
            filesLock.unlock()
        }
        val (content, ok) = fs.readFile(fileName)
        val handle = if (ok) FileHandle(content) else null
        filesLock.lock()
        try {
            return files.getOrPut(fileName) { handle }
        } finally {
            filesLock.unlock()
        }
    }

    /** `Snapshot.LSPLineMap`. */
    fun lspLineMap(fileName: String): LSPLineMap? = getFile(fileName)?.lspLineMap

    private val converters: Converters? = newConverters(positionEncoding) { lspLineMap(it) }

    override fun getECMALineInfo(p0: String): ECMALineInfo? = getFile(p0)?.ecmaLineInfo
    override fun getPreferences(p0: String): UserPreferences = userPreferences
    fun userPreferences(): UserPreferences = userPreferences
    override fun converters(): Converters? = converters
    override fun autoImportRegistry(): Registry? = autoImports
    override fun useCaseSensitiveFileNames(): Boolean = fs?.useCaseSensitiveFileNames() ?: true
    override fun readFile(p0: String): Tuple2<String, Boolean> =
        getFile(p0)?.let { Tuple2(it.content, true) } ?: Tuple2("", false)
    override fun directoryExists(p0: String): Boolean = fs?.directoryExists(p0) ?: false
    override fun fileExists(p0: String): Boolean = fs?.fileExists(p0) ?: false
    override fun getDirectories(p0: String): GoSlice<String> =
        fs?.getAccessibleEntries(p0)?.directories ?: GoElem.STRING.nilSlice
    override fun readDirectory(p0: String, p1: String, p2: GoSlice<String>, p3: GoSlice<String>, p4: GoSlice<String>, p5: Int): GoSlice<String> =
        readDirectory(fs, p0, p1, p2, p3, p4, p5)
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
class Project(private val id: Path, private val program: Program?, private val checkerPool: XtscCheckerPool?) :
    com.xemantic.typescript.tsgo.ls.Project {
    /** `Project.ID` and `Project.Id` (the language service's `ls.Project`): the tsconfig's path. */
    override fun id(): Path = id
    override fun getProgram(): Program? = program

    /** `Project.HasFile`: the program has a source file of [p0]'s path. */
    override fun hasFile(p0: String): Boolean = program?.getSourceFile(p0) != null

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

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

package com.xemantic.typescript.tsgo

import com.xemantic.typescript.tsgo.ast.ExternalModuleIndicatorOptions
import com.xemantic.typescript.tsgo.ast.SourceFile
import com.xemantic.typescript.tsgo.ast.SourceFileParseOptions

/**
 * NOT tsgo — the bundled `lib.*.d.ts` files parsed AHEAD of the run (docs/goport-perf.md § 21).
 *
 * A declaration file's parse depends only on its name, its path and its text: its `ExternalModuleIndicatorOptions`
 * are always empty (`ast.GetExternalModuleIndicatorOptions`). So the trees of the bundled libs are the same in every
 * program, and tsgo's own language server and test harness reuse them across programs. The GraalVM image is built with
 * `-Dxtsc.tsgo.libSnapshot=true` and this object initialized at BUILD time, which parses AND binds every bundled lib
 * into the image heap; a run then starts with them parsed and bound. `compilerHost.GetSourceFile` (an override) asks [lookup] after
 * reading a file's text, and a tree is answered only for the SAME parse options and the SAME text — anything else
 * (another lib directory, a case-insensitive file system's path, a JVM run with the property unset) parses as before.
 */
object TsgoLibSnapshot {

    private class Entry(val opts: SourceFileParseOptions, val file: SourceFile)

    private val files: Map<String, Entry> = if (goLibSnapshotRequested()) build() else emptyMap()

    /** How many bundled libs were parsed ahead (0 unless the image was built with the snapshot). */
    val size: Int get() = files.size

    private fun build(): Map<String, Entry> {
        TsgoProject.init()
        val fs = com.xemantic.typescript.tsgo.bundled.wrapFS(com.xemantic.typescript.tsgo.vfs.osvfs.fs())!!
        val libDir = com.xemantic.typescript.tsgo.bundled.libPath()
        val out = HashMap<String, Entry>()
        val names = com.xemantic.typescript.tsgo.bundled.libNames
        for (i in 0 until names.len) {
            val fileName = com.xemantic.typescript.tsgo.tspath.combinePaths(libDir, com.xemantic.typescript.tsgo.runtime.GoSlice.of(com.xemantic.typescript.tsgo.runtime.GoElem.STRING, names[i]))
            val read = fs.readFile(fileName)
            if (!read.second) continue
            val opts = SourceFileParseOptions(
                fileName = fileName,
                path = com.xemantic.typescript.tsgo.tspath.toPath(fileName, "", true),
                externalModuleIndicatorOptions = ExternalModuleIndicatorOptions(),
            )
            val file = com.xemantic.typescript.tsgo.parser.parseSourceFile(opts.goCopy(), read.first, com.xemantic.typescript.tsgo.core.getScriptKindFromFileName(fileName)) ?: continue
            // Bound ahead too: binding reads nothing but the file (`binder.BindSourceFile`), tsgo shares a bound file
            // between programs and between its concurrent checkers, and a run's `BindSourceFile` finds it bound.
            com.xemantic.typescript.tsgo.binder.bindSourceFile(file)
            out[fileName] = Entry(opts, file)
        }
        return out
    }

    /** The tree parsed ahead for exactly these options and this text, or null (parse it). */
    fun lookup(opts: SourceFileParseOptions, text: String): SourceFile? {
        val e = files[opts.fileName] ?: return null
        if (!e.opts.goEquals(opts) || e.file.text() != text) return null
        return e.file
    }
}

/** Whether this process builds the lib snapshot (true only inside the GraalVM image builder, by a system property). */
internal expect fun goLibSnapshotRequested(): Boolean

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

import com.xemantic.kotlin.test.assert
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.Test

/**
 * (INC.60) [SystemVfs.listEntries] answers exactly what [SystemVfs.list] plus
 * [SystemVfs.isDirectory] answered.
 *
 * It is a SEPARATE implementation — the JVM actual goes to `java.io.File.listFiles`
 * and `File.isDirectory` (one `readdir` and one `stat` per entry) rather than to
 * kotlinx-io's `SystemFileSystem.list` and `metadataOrNull` (a listing plus up to
 * five `stat`s per entry) — and a divergence between the two would be SILENT: the
 * glob would simply take a different branch per entry, so a file could be dropped
 * from the program, or a directory adopted as a root file, with no diagnostic
 * anywhere saying so.
 *
 * The path SPELLING matters as much as the kind: the walk pushes these strings back
 * in as directories and hands them to `onFile`, and every downstream key
 * (`fileResults`, module resolution, the emit mapping) is that string.
 */
class SystemVfsListEntriesTest {

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun withTree(block: (dir: Path) -> Unit) {
        val dir = Files.createTempDirectory("xtsc-listentries")
        try {
            Files.createDirectories(dir.resolve("src/nested"))
            Files.createDirectories(dir.resolve("empty"))
            Files.writeString(dir.resolve("tsconfig.json"), "{}")
            Files.writeString(dir.resolve("src/a.ts"), "export const a = 1;\n")
            Files.writeString(dir.resolve("src/nested/b.ts"), "export const b = 2;\n")
            // A DIRECTORY whose name looks like a source file — the one shape a
            // name-based shortcut would get wrong, and the reason the kind is read
            // from the filesystem rather than guessed from the extension.
            Files.createDirectories(dir.resolve("src/looks-like.ts"))
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `listEntries agrees with list plus isDirectory`() {
        withTree { dir ->
            for (d in listOf(dir.toString(), "$dir/src", "$dir/src/nested", "$dir/empty")) {
                val viaPair = SystemVfs.list(d).sorted().map { it to SystemVfs.isDirectory(it) }
                val viaEntries = SystemVfs.listEntries(d).sortedBy { it.path }.map { it.path to it.isDirectory }
                assert(viaEntries == viaPair)
            }
            // Control: the tree is not empty, so an implementation answering
            // `emptyList()` for everything could not pass the comparison above.
            assert(SystemVfs.listEntries("$dir/src").size == 3)
            assert(SystemVfs.listEntries("$dir/empty").isEmpty())
        }
    }

    @Test
    fun `a directory named like a source file is still a directory`() {
        withTree { dir ->
            val entry = SystemVfs.listEntries("$dir/src").single { it.path.endsWith("looks-like.ts") }
            assert(entry.isDirectory)
        }
    }

    @Test
    fun `a path that is not a readable directory answers empty`() {
        withTree { dir ->
            assert(SystemVfs.listEntries("$dir/src/a.ts").isEmpty())
            assert(SystemVfs.listEntries("$dir/no/such/place").isEmpty())
        }
    }
}

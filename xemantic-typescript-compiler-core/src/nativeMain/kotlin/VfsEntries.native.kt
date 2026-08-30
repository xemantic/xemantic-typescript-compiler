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

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * (INC.60) The portable pair, which is what the [Vfs.listEntries] default does.
 *
 * Kotlin/Native has no cheaper listing than kotlinx-io's, so this actual exists to
 * keep the `expect` satisfiable rather than to be fast; the JVM actual is where the
 * measured saving is.
 */
internal actual fun systemListEntries(path: String): List<VfsEntry> = try {
    SystemFileSystem.list(Path(path)).map {
        val p = PathUtil.normalize(it.toString())
        VfsEntry(p, SystemFileSystem.metadataOrNull(it)?.isDirectory == true)
    }
} catch (_: Exception) {
    emptyList()
}

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

import java.io.File

/**
 * (INC.60) One `readdir` plus ONE `stat` per entry.
 *
 * `File.listFiles()` performs the directory read and constructs the children
 * without touching the filesystem again, and `File.isDirectory` is a single
 * `UnixFileSystem.getBooleanAttributes` — against the five `File` probes
 * kotlinx-io's `metadataOrNull` performs to answer the same boolean.
 *
 * Answers the empty list for a path that is not a readable directory, which is
 * what [SystemVfs.list]'s own `catch` does — a missing directory is not an error
 * here (the glob walks whatever the config names).
 */
internal actual fun systemListEntries(path: String): List<VfsEntry> {
    val children = File(path).listFiles() ?: return emptyList()
    val result = ArrayList<VfsEntry>(children.size)
    for (child in children) result.add(VfsEntry(PathUtil.normalize(child.path), child.isDirectory))
    return result
}

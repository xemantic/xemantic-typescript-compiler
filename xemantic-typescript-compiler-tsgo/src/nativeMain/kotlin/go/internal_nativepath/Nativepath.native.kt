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

@file:OptIn(ExperimentalForeignApi::class)

package com.xemantic.typescript.tsgo.nativepath

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.free
import platform.posix.lstat
import platform.posix.stat

/** POSIX `realpath(3)`: every symbolic link resolved; null when [path] does not exist. */
internal actual fun platformRealpath(path: String): String? {
    val p = platform.posix.realpath(path, null) ?: return null
    try {
        return p.toKString()
    } finally {
        free(p)
    }
}

internal actual fun platformIsSymlink(path: String): Boolean = memScoped {
    val st = alloc<stat>()
    lstat(path, st.ptr) == 0 && (st.st_mode and S_IFMT.toUInt()) == S_IFLNK.toUInt()
}

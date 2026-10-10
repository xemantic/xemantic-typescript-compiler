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

package com.xemantic.typescript.tsgo.nativepath

import com.xemantic.typescript.tsgo.runtime.GoError
import com.xemantic.typescript.tsgo.runtime.GoString
import com.xemantic.typescript.tsgo.runtime.Tuple2

// tsgo's `internal/nativepath` ((TSGO.5), docs/goport-cli.md): the OS-specific canonical path and symlink
// test `vfs/osvfs` uses. tsgo's Linux Realpath is open(O_PATH) + readlink(/proc/self/fd/N) — the kernel's
// canonical path with every symbolic link resolved; the port asks the host for the same answer. Paths are
// byte strings (docs/goport-design.md § 3) converted at this boundary.

/** `nativepath.Realpath(path)`: the canonical absolute path with every symbolic link resolved; an error when [path] does not exist. */
fun realpath(path: String): Tuple2<String, GoError?> {
    val resolved = com.xemantic.typescript.tsgo.go.os.syscall(com.xemantic.typescript.tsgo.go.os.GoSyscall.OP_REALPATH) { platformRealpath(GoString.toUtf16(path)) }
        ?: return Tuple2("", com.xemantic.typescript.tsgo.go.io.fs.PathError("open", path, com.xemantic.typescript.tsgo.go.io.fs.errNotExist))
    return Tuple2(GoString.fromUtf16(resolved), null)
}

/** `nativepath.IsSymlinkOrReparsePoint(path)` (non-Windows: lstat reports a symbolic link). */
fun isSymlinkOrReparsePoint(path: String): Boolean = com.xemantic.typescript.tsgo.go.os.syscall(com.xemantic.typescript.tsgo.go.os.GoSyscall.OP_STAT) { platformIsSymlink(GoString.toUtf16(path)) }

internal expect fun platformRealpath(path: String): String?

internal expect fun platformIsSymlink(path: String): Boolean

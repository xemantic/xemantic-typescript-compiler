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

package com.xemantic.typescript.tsgo.repo

// tsgo's `internal/repo` (paths of the typescript-go checkout), HAND-WRITTEN rather than ported: it
// finds the repository through `runtime.Caller`'s source path, which a ported binary does not have.
// Only what the ported test harness reaches ((TSGO.2), `harnessutil.XtscDerive`) is here: the
// `_submodules/TypeScript` checkout whose `tests/lib` holds the `/.lib` test libraries. The host sets
// [typeScriptSubmodule] (the diag oracle's `XTSC_TS_SUBMODULE`, docs/goport-diag-oracle.md).

/** The TypeScript submodule directory (`repo.TypeScriptSubmodulePath()`); "" when there is none. */
var typeScriptSubmodule: String = ""

/** `repo.TypeScriptSubmodulePath()`. */
fun typeScriptSubmodulePath(): String = typeScriptSubmodule

/** `repo.TypeScriptSubmoduleExists()`: the directory is configured (the host checked it exists). */
fun typeScriptSubmoduleExists(): Boolean = typeScriptSubmodule.isNotEmpty()

/** `repo.SkippableTest`. */
interface SkippableTest {
    fun helper()
    fun skipf(format: String, vararg args: Any?)
}

/** `repo.SkipIfNoTypeScriptSubmodule(t)`. */
fun skipIfNoTypeScriptSubmodule(t: SkippableTest?) {
    t!!.helper()
    if (!typeScriptSubmoduleExists()) t.skipf("TypeScript submodule does not exist")
}

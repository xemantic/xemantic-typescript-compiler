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

// tsgo's `internal/repo` (paths of the tsgo checkout), HAND-WRITTEN rather than ported: it finds the
// repository through `runtime.Caller`'s source path, which a ported binary does not have. TypeScript 7.1 has no
// `_submodules/TypeScript`: the test cases and the `/.lib` test libraries live in-tree under `testdata/`
// (`tests/cases`, `tests/lib`). The host sets [testData] (the diag oracle's checkout: `typescript-go-repo/testdata`,
// docs/goport-diag-oracle.md).

/** The checkout's `testdata` directory (`repo.TestDataPath()`); "" when the host set none. */
var testData: String = ""

/** `repo.TestDataPath()`. */
fun testDataPath(): String = testData.ifEmpty { com.xemantic.typescript.tsgo.runtime.goPanic("repo: test data path not set (repo.testData)") }

/** `repo.RootPath()`: the directory holding `testdata`. */
fun rootPath(): String = testDataPath().removeSuffix("/").substringBeforeLast('/')

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

package com.xemantic.typescript.tsgo.go.gotest_tools.v3.assert

// `gotest.tools/v3/assert`, the one function tsgo's error baseline reaches (`tsbaseline.iterateErrorBaseline`
// checks its own error counts, (TSGO.3)): `assert.Check` — a failed comparison marks the test FAILED and
// the test CONTINUES (it is not `assert.Assert`, which stops it). The failure message is not part of
// any baseline; what matters is the verdict and that the run goes on.

import com.xemantic.typescript.tsgo.go.gotest_tools.v3.assert.cmp.Comparison
import com.xemantic.typescript.tsgo.go.testing.T

/** `assert.Check(t, comparison, msgAndArgs...)`: a `bool` or a [Comparison]. */
fun check(t: Any?, comparison: Any?, vararg msgAndArgs: Any?): Boolean {
    @Suppress("UNCHECKED_CAST")
    val ok = when (comparison) {
        is Boolean -> comparison
        is Function0<*> -> ((comparison as Comparison)()!!).success()
        else -> error("assert.Check: unsupported comparison ${comparison?.let { it::class }}")
    }
    if (!ok) (t as T).common.errorf("assert.Check failed: %v", msgAndArgs.joinToString(" "))
    return ok
}

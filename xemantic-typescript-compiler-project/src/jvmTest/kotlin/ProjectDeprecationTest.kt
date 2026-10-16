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

package com.xemantic.typescript.compiler.project

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (TSGO.8-d) `Project` is deprecated in favour of the tsgo port's API (owner decision
 * 2026-10-16), and a caller is told where to go. `kotlin.Deprecated` is retained at run
 * time, so the annotation itself is observable here.
 */
class ProjectDeprecationTest {

    @Test
    fun `Project is deprecated and names its successors`() {
        val deprecated = Project::class.java.getAnnotation(Deprecated::class.java)
        val message = deprecated?.message ?: ""
        assert(deprecated != null)
        assert(deprecated?.level == DeprecationLevel.WARNING)
        assert("com.xemantic.typescript.tsgo.TsgoProject" in message)
        assert("TsgoLanguageService" in message)
        assert("com.xemantic.typescript:xemantic-typescript-compiler" in message)
    }
}

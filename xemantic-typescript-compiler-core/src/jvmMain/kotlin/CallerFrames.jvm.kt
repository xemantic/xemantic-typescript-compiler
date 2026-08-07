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

/**
 * `StackWalker` rather than `Throwable().stackTrace`: the checker's stacks are
 * hundreds of frames deep and filling in a whole trace is O(depth), while a
 * walk with a `limit` is lazy. Retained as a singleton — `getInstance()` is
 * documented as cheap but not free, and this runs ~10^5 times per attribution
 * run.
 *
 * Frames are reported LOGICALLY, so a JIT-inlined `getTypeOfExpression` still
 * appears and is still skipped.
 */
private val walker: StackWalker = StackWalker.getInstance()

internal actual fun captureCallerFrames(skipMethods: Set<String>): String =
    walker.walk { frames ->
        val out = StringBuilder()
        var taken = 0
        val it = frames.limit(24).iterator()
        while (it.hasNext() && taken < 2) {
            val f = it.next()
            val m = f.methodName
            if (m in skipMethods || m.startsWith("access\$")) continue
            if (taken > 0) out.append('|')
            out.append(m).append(':').append(f.lineNumber)
            taken++
        }
        out.toString()
    }

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

private const val DEEP_STACK_THREAD_NAME = "xtsc-deep-stack"

/**
 * 256 MB of stack for the compile thread. The size is RESERVED virtual memory,
 * committed page-by-page on first touch (Linux/glibc pthread stacks), so an
 * ordinary compile costs the same physical memory as before — only genuinely
 * deep recursion pays for the pages it actually uses.
 */
private const val DEEP_STACK_SIZE_BYTES = 256L * 1024 * 1024

actual fun <T> runWithDeepStack(block: () -> T): T {
    if (Thread.currentThread().name == DEEP_STACK_THREAD_NAME) return block()
    var outcome: Result<T>? = null
    val thread = Thread(null, { outcome = runCatching(block) }, DEEP_STACK_THREAD_NAME, DEEP_STACK_SIZE_BYTES)
    thread.start()
    thread.join()
    return outcome!!.getOrThrow()
}

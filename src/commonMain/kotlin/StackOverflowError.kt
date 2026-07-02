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
 * The checker's single init boundary guard catches `StackOverflowError` and
 * surfaces it via `reportCheckerStackOverflow` (TS2589) — recursion cycles are
 * otherwise prevented by in-progress sentinels, so the guard never fires on the
 * test corpus. `java.lang.StackOverflowError` is JVM-only, so common code
 * declares it `expect`: the JVM `actual` is a typealias to the real thing,
 * while Kotlin/Native cannot catch stack overflows at all (they are a hard
 * process crash), so its `actual` is a plain never-thrown [Error] subclass and
 * the boundary guard is inert dead code there.
 */
expect open class StackOverflowError : Error

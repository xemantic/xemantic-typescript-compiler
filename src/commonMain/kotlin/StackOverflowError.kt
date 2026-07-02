/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
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

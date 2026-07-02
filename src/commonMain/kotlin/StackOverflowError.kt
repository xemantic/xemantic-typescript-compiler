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
 * The checker guards deep-recursion sites with `catch (e: StackOverflowError)`
 * routed through `reportCheckerStackOverflow` (TS2589). `java.lang.StackOverflowError`
 * is JVM-only, so common code declares it `expect`: the JVM `actual` is a typealias
 * to the real thing, while Kotlin/Native cannot catch stack overflows at all (they
 * are a hard process crash), so its `actual` is a plain never-thrown [Error]
 * subclass and those catch blocks are inert dead code there.
 */
expect open class StackOverflowError : Error

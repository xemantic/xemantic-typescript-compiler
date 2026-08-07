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
 * Runs [block] on an execution context with a LARGE call stack where the platform
 * supports one.
 *
 * The compile pipeline ([TypeScriptCompiler.compileParsed]) runs under this wrapper
 * so that deeply nested source shapes cannot overflow a default ~1 MB thread stack:
 * a multi-thousand-term `a = a = a = …` chain recurses per level in
 * `parseAssignmentExpression` (right-associativity IS recursion in the parser), and
 * pathological nesting can still drive legitimately-recursive checker paths deep.
 * tsgo gets the same protection for free from goroutine stack growth; the JVM needs
 * a dedicated thread.
 *
 * This is a SAFETY MARGIN, not a license for naive recursion: full-tree walkers must
 * still expand binary-expression spines iteratively (the CLAUDE.md checker-walker
 * rule), because linear stack use on linear input is an algorithmic bug regardless
 * of the stack size. The end-to-end guarantee is pinned by `DeepExpressionChainTest`.
 *
 * Re-entrant: a nested call on an already-deep-stack thread runs [block] inline.
 */
expect fun <T> runWithDeepStack(block: () -> T): T

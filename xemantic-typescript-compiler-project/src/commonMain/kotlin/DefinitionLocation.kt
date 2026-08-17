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

/**
 * Where something is declared: the go-to-definition answer.
 *
 * A VALUE, like [NodeInfo] and [QuickInfo] and for the same reason — no `Node`, no
 * `Symbol`, no `Type`.
 *
 * ## The span is the NAME, and it is exact
 *
 * [start] `until` [start] `+` [length] covers the declaration's NAME where it has a
 * single-token one — `foo`, not the whole class body it heads — which is what tsc's
 * own go-to-definition navigates to and what an editor wants to highlight. A
 * declaration with no single-token name (a binding pattern, a computed member name)
 * falls back to the whole declaration, so the answer is coarser but never wrong.
 *
 * [length] is a real length, not `end - pos` over a `Node`: our parser's `Node.end`
 * is the end of the token FOLLOWING the node, so subtracting would overshoot (see
 * [SourceIndex] for the whole story). It is computed inside the compiler, from the
 * declaring file's own text, because a declaration is usually in a file the caller
 * never asked about and may not be able to read at all — a `lib.*.d.ts` has no path
 * on disk.
 *
 * @property fileName the file the declaration is in, as the program names it. A
 *   declaration in a library file names that library, so a host must be prepared
 *   for a path it cannot open.
 * @property start the 0-based offset of the span's first character.
 * @property length the span's extent, half-open with [start].
 * @property kind the `SyntaxKind` name of the node the span covers — `Identifier`
 *   for a normal name, the declaration's own kind for the coarse fallback. Reported
 *   because an editor showing a list of definitions has to label them.
 */
public data class DefinitionLocation(
    val fileName: String,
    val start: Int,
    val length: Int,
    val kind: String,
)

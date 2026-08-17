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
 * What the compiler knows about the expression at a position: the hover answer.
 *
 * A VALUE, like [NodeInfo] and for the same reason — no `Type`, no `Symbol`, no
 * `Node`. The type is given as the text the compiler's own diagnostics would name
 * it by, which is what a hover shows and is also the only rendering that stays
 * stable while the type system's internals are rewritten underneath it.
 *
 * @property kind the `SyntaxKind` name of the node that was typed.
 * @property displayString the type, rendered as the compiler renders it in a
 *   message — `string`, `number`, `string | number`, `Foo<Bar>`.
 * @property start the 0-based offset of the node's first character.
 * @property end the offset one past its last character — the REAL end, snapped
 *   back to the token stream, not the raw `Node.end` (see [SourceIndex]). Half-open
 *   like every other span this API speaks: `start until end`.
 */
public data class QuickInfo(
    val kind: String,
    val displayString: String,
    val start: Int,
    val end: Int,
)

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
 * (API.3c) Everything a compile knows about ONE span: the hover answer and the
 * go-to-definition answer together.
 *
 * A VALUE, like [NodeInfo], [QuickInfo] and [DefinitionLocation] and for the same
 * reason — no `Node`, no `Symbol`, no `Type`.
 *
 * ## Why the two answers travel together
 *
 * They are recorded by ONE hook in the checker, at one node, during one walk, and
 * both are functions of state that exists only while that walk runs. Handing them
 * back separately would have made "describe this caret" cost two compiles, which is
 * the thing this batch exists to stop; so the request carries spans and the answer
 * carries, per span, everything the walk saw there.
 *
 * ## Absent is not an error
 *
 * [quickInfo] is null wherever the checker typed nothing — a span the caller named
 * that no expression carries, or a node kind that is not an expression at all — and
 * [definitions] is empty wherever nothing was resolved: a member name (refused on
 * purpose, see [DefinitionLocation]), a label, a keyword. An entry with neither is
 * still returned, because "there is a node here and the compiler had nothing to say
 * about it" is a different answer from "there is nothing here", and only the caller
 * knows which of the two its UI should show.
 *
 * @property start the 0-based offset of the span's first character.
 * @property end the offset one past its last character — the REAL end, snapped back
 *   to the token stream (see [SourceIndex]), not the raw `Node.end`. Half-open with
 *   [start], like every other span this API speaks.
 * @property kind the `SyntaxKind` name of the node the span covers. Reported
 *   separately from [QuickInfo.kind] because it is a SYNTACTIC fact and is therefore
 *   known even where [quickInfo] is null — which is what a semantic highlighter
 *   needs in order to draw something for every span it asked about.
 * @property quickInfo the type the checker computed there, or null.
 * @property definitions where the name at this span is declared, or empty. More
 *   than one is normal — declaration merging — as [Project.definitionsAt] documents.
 */
public data class SemanticInfo(
    val start: Int,
    val end: Int,
    val kind: String,
    val quickInfo: QuickInfo?,
    val definitions: List<DefinitionLocation>,
)

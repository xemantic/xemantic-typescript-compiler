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
 * (API.5) One place that refers to the same thing as the caret — a find-references
 * or document-highlight hit.
 *
 * A VALUE, like every other answer this module publishes, and for the same reason:
 * no `Node`, no `Symbol`, no `Type` crosses the boundary
 * ([Project.referencesAt] carries the argument).
 *
 * ## The span, and why it is `start`/`end` where [DefinitionLocation] is
 * `start`/`length`
 *
 * [start] `until` [end] is HALF-OPEN and EXACT — it covers the identifier's own
 * text and nothing following it. It is not a raw `Node.end`, which in this parser
 * is the end of the token AFTER the node ([SourceIndex] has the whole story).
 *
 * [DefinitionLocation] reports a length because it names a declaration in a file
 * the caller may never have asked about and may not be able to read (a
 * `lib.*.d.ts` has no path on disk), so only the compiler can compute its extent.
 * Every span HERE is either an occurrence in a file this API parsed itself, or a
 * declaration whose exact extent the compiler already computed — so an exact end
 * is in hand either way, and half-open `start`/`end` is what [NodeInfo],
 * [QuickInfo] and [SemanticInfo] all report at a position.
 *
 * @property fileName the file the occurrence is in, as the program names it. Every
 *   occurrence is in a file of the PROGRAM; a [isDeclaration] entry may
 *   additionally name a library file, because the declaration a reference resolves
 *   to is reported wherever it lives.
 * @property start the 0-based offset of the occurrence's first character.
 * @property end one past its last character.
 * @property isDeclaration true when this span is one of the DECLARATIONS the caret's
 *   symbol has, rather than a use of it. tsc reports the same flag and for the same
 *   reason: an editor renders the declaration differently, and a host that wants
 *   uses only filters on it. It is EXACT rather than syntactic — the declaration
 *   set comes out of the compiler's own resolution, not out of a guess about which
 *   parent kinds declare a name.
 *
 *   NOT reported, deliberately: whether a use is a READ or a WRITE. See
 *   [Project.referencesAt] for why a partial answer there is worse than none.
 */
public data class ReferenceLocation(
    val fileName: String,
    val start: Int,
    val end: Int,
    val isDeclaration: Boolean,
)

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

import com.xemantic.typescript.compiler.Node

/**
 * (API.4a) What a caret is asking for — the thing every other query in this module
 * gets for free and completions do not.
 *
 * ## Why completions need an anchor at all
 *
 * Every other semantic query here starts from A NODE THAT EXISTS AT THE CARET:
 * `quickInfoAt` and `definitionsAt` resolve the offset with [SourceIndex.pathAt] and
 * hand the node's span to the compiler. A completion request has no such node by
 * construction — the user is mid-identifier, or sitting immediately after a `.` with
 * nothing typed yet, so the text under the caret is not a name the program contains
 * and may not even parse. The question is therefore not "what is here" but "what
 * KIND of thing may be written here, and what is it attached to", and that is a
 * TOKEN-level question: it is answered from the token stream and the parse, both of
 * which [SourceIndex] already owns, and it is answered ONCE here rather than
 * approximated again inside the checker (which has no token index — see
 * `TypeCaptureSpan`).
 *
 * ## The prefix and the replacement span are different quantities
 *
 * [prefix] is what the user has TYPED, and it is what a host filters by; this module
 * never filters, because ranking and fuzzy matching are host policy and a list that
 * has already been cut cannot be re-ranked. [replacementStart] `until`
 * [replacementEnd] is what accepting an item must REPLACE, and it covers the WHOLE
 * word the caret is in rather than only the typed prefix — so completing in the
 * middle of `o.fo|o` leaves no `o` behind. With no word under the caret the two are
 * equal and accepting an item is a pure insertion.
 *
 * @property kind what may be completed here.
 * @property prefix the already-typed text, `""` when the caret is not inside a word.
 * @property replacementStart the first offset an accepted item replaces.
 * @property replacementEnd one past the last, half-open like every span in this API.
 * @property receiver for [CompletionKind.MEMBER], the expression to the left of the
 *   dot — the node whose type is to be enumerated. Null for every other kind, and
 *   also null for a `.` the parse did not turn into a member access (see
 *   [SourceIndex.completionAnchorAt] for that recovery rule), which is the one case
 *   where a MEMBER anchor answers no items.
 */
internal class CompletionAnchor(
    val kind: CompletionKind,
    val prefix: String,
    val replacementStart: Int,
    val replacementEnd: Int,
    val receiver: Node?,
) {

    companion object {

        /** The answer for a caret nothing may be completed at. */
        fun none(offset: Int): CompletionAnchor =
            CompletionAnchor(CompletionKind.NONE, "", offset, offset, null)
    }
}

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
 * (API.4a) What sort of completion a caret is asking for.
 *
 * Reported even when no items come back, because the two facts are independent: a
 * [MEMBER] caret on a receiver with no members and a [FREE_NAME] caret this version
 * does not answer are both empty lists, and a host that greys out a menu wants to
 * tell them apart.
 */
public enum class CompletionKind {

    /**
     * The caret follows a `.` or a `?.`, so the candidates are the members of the
     * expression to its left. ANSWERED.
     */
    MEMBER,

    /**
     * The caret is at a free position, so the candidates are everything the lexical
     * scope chain binds there, plus the keywords legal at that position. NOT
     * ANSWERED yet — see [CompletionRefusal.FREE_NAMES_NOT_IMPLEMENTED].
     */
    FREE_NAME,

    /**
     * Nothing may be completed here: the caret is inside a string, a comment or a
     * numeric literal, or outside the file.
     */
    NONE,
}

/**
 * (API.4a) Why a [CompletionList] came back empty, when the emptiness is a stated
 * refusal rather than an answer.
 *
 * A refusal is published rather than left as a silent empty list for the reason
 * (API.3b) published its own: a host cannot tell "there is nothing here" from "this
 * compiler does not do that yet" by looking at an empty list, and the difference
 * decides whether it falls back to a word-based completer.
 */
public enum class CompletionRefusal {

    /**
     * (API.4b) Free-name completion — enumerating the lexical scope chain and the
     * keywords legal at the position — is not implemented. The anchor is correct and
     * is reported ([CompletionList.prefix] and the replacement span are usable); only
     * the candidate list is missing.
     */
    FREE_NAMES_NOT_IMPLEMENTED,

    /**
     * The position admits no completion at all: inside a string, a template, a
     * regular expression, a numeric literal or a comment, or outside the file's text.
     */
    NO_COMPLETION_CONTEXT,
}

/**
 * (API.4a) ONE candidate a host may offer, with everything it needs to render the
 * item and nothing it would have to ask a second question for.
 *
 * @property name the text to insert, exactly as it must be written after the dot.
 * @property kind the `SyntaxKind` name of the member's own declaration —
 *   `PropertyDeclaration` for a property (interface members are class elements in
 *   this parser, so a property signature is one too), `MethodDeclaration` for a
 *   method, `GetAccessor` / `SetAccessor` for an accessor, `Parameter` for a
 *   constructor parameter property, `EnumMember` for an enum member, `"Unknown"`
 *   for a synthesized member carrying no declaration. THIS is how a method is told
 *   from a property; there is no separate flag.
 * @property typeText the member's type, rendered as the compiler renders it in a
 *   diagnostic. Through a UNION receiver it is the distinct types the member has
 *   across the constituents, joined by `" | "`.
 * @property optional the member is declared `p?`, or is optional on any constituent
 *   of a union receiver.
 * @property readonly any contributing declaration carries `readonly`.
 * @property accessibility `"public"`, `"protected"` or `"private"`. REPORTED AND NOT
 *   ACTED ON — see [Project.completionsAt].
 */
public data class CompletionItem(
    val name: String,
    val kind: String,
    val typeText: String,
    val optional: Boolean,
    val readonly: Boolean,
    val accessibility: String,
)

/**
 * (API.4a) The answer to "what may I write at this caret".
 *
 * ## Filtering is the host's job, and this is why
 *
 * [items] is NOT filtered by [prefix]. Ranking a completion list — prefix versus
 * substring versus fuzzy match, case sensitivity, recency, how a `_`-prefixed member
 * sorts — is editor policy that differs per host and per user setting, and a list
 * that has already been cut cannot be re-ranked. So the full candidate set comes
 * back and the prefix comes back beside it.
 *
 * ## The two spans
 *
 * [prefix] is what the user has TYPED and is what to filter by. [replacementStart]
 * `until` [replacementEnd] is what accepting an item must REPLACE, and it covers the
 * whole word the caret sits in rather than only the typed prefix — so accepting in
 * the middle of `o.fo|o` leaves no `o` behind. Where there is no word under the
 * caret the two offsets are equal and an accepted item is inserted.
 *
 * @property kind what may be completed here, reported whether or not [items] is
 *   empty.
 * @property prefix the already-typed text, `""` when the caret is not inside a word.
 * @property replacementStart the first offset an accepted item replaces.
 * @property replacementEnd one past the last — half-open, like every span in this
 *   API.
 * @property items the candidates, deduplicated by name and sorted by name ascending.
 *   The order is imposed rather than inherited: a member table's iteration order is
 *   an implementation property and a list a user reads must not reorder under a
 *   checker change.
 * @property refusal non-null only when [items] is empty FOR A STATED REASON. An
 *   empty [items] with a null refusal is a real answer — that receiver has no
 *   members.
 */
public data class CompletionList(
    val kind: CompletionKind,
    val prefix: String,
    val replacementStart: Int,
    val replacementEnd: Int,
    val items: List<CompletionItem>,
    val refusal: CompletionRefusal?,
)

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
 * (API.8) The plan a rename produces, or the reason there is none.
 *
 * A VALUE, like every other answer this module publishes: the host owns its buffers,
 * so nothing here is applied for you. What IS promised is that the plan is *directly*
 * applicable — the edits of one file are non-overlapping and sorted, so applying them
 * back to front needs no interpretation and no re-derivation of offsets.
 *
 * ## The contract, in one line
 *
 * **[refusal] `!= null` if and only if [files] is empty.** A refusal never comes with
 * a partial plan, because a partial rename produces code that does not compile and is
 * worse than no rename at all; and a plan never comes with a refusal attached as a
 * warning the host may ignore.
 *
 * [conflicts] is the EVIDENCE for a refusal, not a separate severity: when the
 * refusal is one the search discovered rather than one it decided a priori
 * ([RenameRefusal.OCCURRENCES_INCOMPLETE], [RenameRefusal.WOULD_NOT_COMPILE],
 * [RenameRefusal.WOULD_CHANGE_MEANING]), every place that caused it is listed, so a
 * host can say *where* rather than only *no*. A successful plan carries an empty
 * conflict list.
 *
 * @property oldName the spelling being replaced, or `""` when the caret named nothing.
 * @property newName the spelling requested, echoed back so a plan is self-describing.
 * @property files the edits, grouped by file and sorted by file name; empty on a
 *   refusal.
 * @property refusal why there is no plan, or null when there is one.
 * @property conflicts the places that caused a discovered refusal, sorted by
 *   `(fileName, start)`. Empty for a successful plan and for a refusal decided
 *   without a search.
 */
public data class RenamePlan(
    val oldName: String,
    val newName: String,
    val files: List<FileRename>,
    val refusal: RenameRefusal?,
    val conflicts: List<RenameConflict>,
) {

    /** True when there is a plan to apply. The mirror of `refusal == null`. */
    public val isApplicable: Boolean get() = refusal == null && files.isNotEmpty()
}

/**
 * (API.8) One file's share of a [RenamePlan].
 *
 * @property fileName the file to edit, as the program names it — always a file of the
 *   PROGRAM. A rename whose symbol is declared anywhere else is refused
 *   ([RenameRefusal.DECLARED_IN_A_LIBRARY]) rather than planned around.
 * @property edits non-overlapping and sorted by [RenameEdit.start] ASCENDING. Apply
 *   them back to front (`edits.asReversed()`) and no offset needs adjusting; apply
 *   them forwards and you must carry a running delta yourself.
 */
public data class FileRename(
    val fileName: String,
    val edits: List<RenameEdit>,
)

/**
 * (API.8) One replacement: the text in `[start, end)` becomes [newText].
 *
 * ## Why this is not always "the identifier, replaced by the new name"
 *
 * Two shapes in TypeScript spell a binding and a property with ONE identifier, and
 * rewriting them as a plain occurrence silently changes what the program means while
 * passing every "all occurrences were renamed" assertion:
 *
 * | source | renaming | becomes |
 * |---|---|---|
 * | `const o = { p }` — an object literal's shorthand | the local `p` | `{ p: newName }` |
 * | `const { p } = o` — a binding pattern's shorthand | the local `p` | `{ p: newName }` |
 *
 * That is the discriminator this feature is tested against: `{ newName }` compiles, and
 * it has renamed the object's KEY.
 *
 * A bare `export { p }` and a bare `import { p }` are replaced PLAINLY, where tsc
 * expands both to preserve the module's public name — see `SyntaxRoles.renameRewrite`
 * for why our one-symbol identity makes the plain form the consistent one.
 *
 * [start] `until` [end] is half-open and EXACT — the identifier's own text, snapped to
 * the token stream, never a raw `Node.end` (which in this parser is the end of the
 * FOLLOWING token; `SourceIndex` has the whole story).
 */
public data class RenameEdit(
    val start: Int,
    val end: Int,
    val newText: String,
)

/**
 * (API.8) Why a rename produced no plan.
 *
 * Every member here is a REFUSAL rather than a gap: the alternative in each case is an
 * edit set that leaves the program not compiling, or compiling and meaning something
 * else, and both are worse than an answer of "no".
 */
public enum class RenameRefusal {

    /**
     * The caret is not on an identifier — a keyword, a literal, punctuation, trivia,
     * an unknown file, an offset outside the file. Nothing is renameable there and
     * NOTHING IS COMPILED to find out.
     */
    NOT_AN_IDENTIFIER,

    /**
     * The requested name is not a single identifier token. Checked by SCANNING it,
     * not by a character predicate, so the answer is the one the compiler's own lexer
     * would give — including for a name with a Unicode letter or an escape.
     */
    NEW_NAME_IS_NOT_AN_IDENTIFIER,

    /**
     * The requested name is a reserved word. `class`, `return`, `let`, `yield` and
     * their kin are not bindings, so the rename would produce a syntax error. tsc's
     * own language server does not check this and happily writes `const class = 1`;
     * this does.
     */
    NEW_NAME_IS_RESERVED,

    /** The requested name is the one already there. A no-op is not a plan. */
    NEW_NAME_UNCHANGED,

    /**
     * The identifier at the caret resolves to nothing this search can name, so there
     * is no occurrence set. The positions that land here are exactly the ones
     * `Project.definitionsAt` and `Project.referencesAt` already refuse: an object
     * literal's own key, an `import { p as q }` / `export { p as q }` alias half, a
     * member declared and never used.
     */
    NO_SYMBOL,

    /**
     * Some declaration of the symbol is in a file this rename may not edit — a
     * `lib.*.d.ts`, which has no path on disk. **THE SAFETY REFUSAL**: renaming
     * `"abc".length` would rewrite your uses and leave the declaration alone, which
     * is a program that does not compile. tsc refuses the same thing in the same
     * words ("You cannot rename elements that are defined in the standard TypeScript
     * library").
     */
    DECLARED_IN_A_LIBRARY,

    /**
     * The occurrence set spells the symbol more than one way, because an
     * `import { a as b }` / `export { a as b }` was crossed. Our identity is a
     * DECLARATION SET, so the alias and the original are ONE symbol — which is what
     * makes find-references answer across the hop — and one new name cannot be
     * applied to two spellings without deciding which side of the `as` the user meant.
     * tsc decides it by which name the caret is on, having two symbols to decide
     * between; we have one, so we refuse rather than guess.
     */
    ALIASED_SYMBOL,

    /**
     * A declaration of the symbol IS the import binding, which means the module was
     * not resolved. Renaming the local would leave the export it names untouched.
     */
    UNRESOLVED_IMPORT,

    /**
     * The occurrence set cannot be shown to be complete: some identifier spelling the
     * old name, somewhere in the program, could be an occurrence and could not be
     * resolved — an implementor's member, a member declared on a second type, an
     * `o["p"]` whose member is named by a string literal rather than by an identifier,
     * an object-literal key supplied contextually. [RenamePlan.conflicts] lists every
     * one. **This is where a member rename usually lands**, and it is the honest
     * answer: a member rename that misses an implementor produces a class that no
     * longer implements its interface.
     */
    OCCURRENCES_INCOMPLETE,

    /**
     * The plan was built, applied to a scratch copy of the program, AND THE PROGRAM
     * WAS RE-CHECKED — and the rename introduced diagnostics. The commonest cause is
     * a collision: the new name is already declared in a scope the rename reaches.
     * [RenamePlan.conflicts] carries one entry per new diagnostic.
     */
    WOULD_NOT_COMPILE,

    /**
     * The plan was applied to a scratch copy and re-checked, and something now
     * resolves somewhere else: either a renamed occurrence no longer names the symbol
     * it did, or an identifier that already spelled the NEW name stopped naming what
     * it named. That is a CAPTURE — a rename that compiles and means something else —
     * and it is the failure a diagnostic count cannot see.
     */
    WOULD_CHANGE_MEANING,
}

/**
 * (API.8) One place that caused a discovered refusal — the *evidence*, so a host can
 * point at it instead of only reporting failure.
 *
 * @property kind what sort of obstacle it is.
 * @property fileName the file it is in.
 * @property start the 0-based offset of its first character.
 * @property end one past its last character. For a
 *   [RenameConflictKind.NEW_DIAGNOSTIC] this is the diagnostic's own span.
 * @property detail a short human-readable note — the diagnostic's message, or the
 *   text at the span. Never parsed by anything here; it exists to be shown.
 */
public data class RenameConflict(
    val kind: RenameConflictKind,
    val fileName: String,
    val start: Int,
    val end: Int,
    val detail: String,
)

/** (API.8) What sort of obstacle a [RenameConflict] is. */
public enum class RenameConflictKind {

    /**
     * An identifier spelling the old name that the whole-program search could not
     * resolve, in a position where it COULD have been an occurrence of the symbol
     * being renamed. Unresolved is not the same as unrelated, so it counts against
     * the rename.
     */
    UNRESOLVED_OCCURRENCE,

    /**
     * An element access naming a member with a string literal — `o["p"]`. Its member
     * is not an identifier, so it is outside the population this API can find at all
     * (`Project.referencesAt` draws the same boundary). tsc rewrites these; this
     * refuses, because finding them would need a mechanism that does not exist here
     * and missing one breaks the program.
     */
    ELEMENT_ACCESS,

    /**
     * An object-literal shorthand (`{ p }`) spelling the old name, met while renaming
     * a MEMBER. Such a shorthand names a local AND supplies a property whose identity
     * comes from the object literal's CONTEXTUAL type — which is the third resolution
     * mechanism this API does not have (`Project.definitionsAt` refuses object-literal
     * keys for the same reason).
     */
    CONTEXTUAL_SHORTHAND,

    /** A diagnostic the renamed program has and the original did not. */
    NEW_DIAGNOSTIC,

    /**
     * A span whose meaning moved: it resolved to one declaration set before the
     * rename and to another after it.
     */
    RESOLUTION_CHANGED,
}

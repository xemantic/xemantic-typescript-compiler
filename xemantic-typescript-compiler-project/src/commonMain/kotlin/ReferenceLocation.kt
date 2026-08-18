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
 * (API.7) How an occurrence USES what it names — read, written, or neither.
 *
 * Round 919 refused this distinction outright, and the refusal was right at the time:
 * `x = 1` and `x++` are trivially writes while `[x] = pair`, `({ x } = o)` and
 * `for (x of xs)` are writes whose identifier sits under an array literal, an object
 * literal and a `for` head — so a rule built from the easy positions reports the
 * destructuring ones as READS, and a host cannot tell a complete answer from an
 * incomplete one. What makes the answer expressible now is `SyntaxRoles`, a
 * pull-based ascent of the parent chain in which a destructuring pattern of any depth
 * is a run of pass-through steps ending at one assignment test.
 *
 * [UNCLASSIFIED] exists so that the gap round 919 refused to hide stays visible: an
 * occurrence this classifier does not place is reported as unplaced, never defaulted
 * to [READ].
 */
public enum class ReferenceUse {

    /** The occurrence reads the value. The ordinary case, and the default of nothing. */
    READ,

    /**
     * The occurrence stores a value and does not read one first: the left of a simple
     * `=` (including a member, `o.p = 1`), a destructuring target in either bracket
     * form at any depth — with defaults, renaming, shorthand and rest — the head of a
     * `for (x of/in …)`, a parameter's own name, and a variable or binding-element
     * declaration's own name.
     */
    WRITE,

    /**
     * The occurrence reads the old value and stores a new one: a compound assignment
     * (`+=` and the rest) and the update operators `++` / `--`, prefix and postfix.
     */
    READ_WRITE,

    /**
     * The occurrence is not a value use at all, so neither answer would be true: a
     * TYPE-position name, a declaration name that binds no storage (a function, class,
     * interface, type alias, enum, namespace, type parameter, import or export
     * specifier, or class-member name), an object-literal key being declared, a
     * binding element's source property name, and a label.
     */
    UNCLASSIFIED,
}

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
 * @property use (API.7) whether the occurrence READS or WRITES what it names, or is
 *   not a value use at all. Round 919 refused this and round 921's mechanism cashed
 *   it; [ReferenceUse] states the complete write set and names what stays
 *   unclassified rather than defaulting it to a read.
 */
public data class ReferenceLocation(
    val fileName: String,
    val start: Int,
    val end: Int,
    val isDeclaration: Boolean,
    val use: ReferenceUse,
)

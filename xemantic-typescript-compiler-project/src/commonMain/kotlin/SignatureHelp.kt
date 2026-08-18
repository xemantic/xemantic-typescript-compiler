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
 * (API.6) ONE parameter of a signature, and where it sits in that signature's own
 * [SignatureInfo.label].
 *
 * [labelStart] `until` [labelEnd] index the LABEL, not the source file — they are
 * what an editor bolds while the user types that argument, and they are half-open
 * like every span in this API. They are recorded as the label is built rather than
 * searched for afterwards, because searching for `name: type` finds the wrong
 * occurrence the moment one parameter's type mentions another's spelling.
 *
 * @property name the parameter's name, or its destructuring pattern rendered as
 *   source (`{ a, b }`). Without the `...` of a rest parameter — that is [isRest].
 * @property typeText the parameter's type as the compiler renders it. The SAME
 *   renderer [Project.quickInfoAt] uses, deliberately: a host must never have to
 *   reconcile two spellings of one type.
 * @property optional true when a caller may omit it — declared `p?`, or carrying a
 *   default value. A REST parameter is not reported optional; the two facts mean
 *   different things to a widget and are reported separately.
 * @property isRest true for `...args: T[]`.
 */
public class ParameterInfo(
    public val name: String,
    public val typeText: String,
    public val optional: Boolean,
    public val isRest: Boolean,
    public val labelStart: Int,
    public val labelEnd: Int,
) {
    override fun toString(): String = "ParameterInfo($name, $typeText, $labelStart..$labelEnd)"
}

/**
 * (API.6) ONE signature of the callee at the caret — usually the only one, and one
 * of several when the callee is OVERLOADED.
 *
 * @property label the whole signature on one line: the callee's name where it has a
 *   syntactic one, its type parameters where it declares any, the parameter list and
 *   the return type — `pick<T>(xs: T[], i: number): T`. A construct signature is
 *   prefixed `new `. A callee with no syntactic name (`(fs[i])(…)`) contributes no
 *   name rather than an invented one.
 * @property parameters in declaration order, each carrying its own range within
 *   [label].
 * @property returnTypeText the return type alone, for a host laying the signature out
 *   itself instead of showing [label].
 * @property activeParameter the index into [parameters] the caret's argument lands
 *   on, or -1 when THIS signature has no parameter for it. It is not simply
 *   [SignatureHelp.activeArgument]: once the caret is past the fixed parameters of a
 *   signature ending in a REST parameter it CLAMPS to that rest parameter, because
 *   every further argument feeds it.
 */
public class SignatureInfo(
    public val label: String,
    public val parameters: List<ParameterInfo>,
    public val returnTypeText: String,
    public val activeParameter: Int,
) {
    override fun toString(): String = "SignatureInfo($label, active=$activeParameter)"
}

/**
 * (API.6) What may be passed at the caret — the signature-help answer.
 *
 * ## Every overload comes back
 *
 * [signatures] holds every signature the callee has, in DECLARATION ORDER, which is
 * overload resolution order and is what an editor's "2 of 3" counts through. Showing
 * one of three is the failure this feature exists to avoid, so a single-signature
 * answer means the callee genuinely has one.
 *
 * An EMPTY [signatures] is a real answer and not an error: the caret is in an
 * argument list whose callee has no call signatures — it is `any`, or unresolvable,
 * or not callable at all. `Project.signatureHelpAt` returns null for the different
 * fact that the caret is in no argument list.
 *
 * @property activeSignature the index into [signatures] a host should show first, and
 *   0 when nothing applies. See `Project.signatureHelpAt` for the rule.
 * @property activeArgument which argument slot the caret is in, counted from 0. A
 *   fact about the TEXT — the number of this argument list's commas before the caret
 *   — so it is independent of which signature is shown and may exceed every
 *   signature's parameter count. What it means for one signature is that signature's
 *   [SignatureInfo.activeParameter].
 */
public class SignatureHelp(
    public val signatures: List<SignatureInfo>,
    public val activeSignature: Int,
    public val activeArgument: Int,
) {
    override fun toString(): String =
        "SignatureHelp(${signatures.size} signature(s), " +
            "active=$activeSignature, argument=$activeArgument)"
}

/**
 * (API.6) The call a caret is inside, and which argument slot it occupies.
 *
 * INTERNAL, exactly as [CompletionAnchor] is and for the same reason: it carries a
 * `Node`, and whether this API publishes the AST is a decision no feature gets to
 * take by accident. [SignatureHelp] is what crosses the boundary.
 *
 * @property call the `CallExpression` or `NewExpression` whose argument list contains
 *   the caret — the node whose span the compiler is asked about.
 * @property activeArgument the caret's argument slot, counted in commas.
 * @property argumentListStart one past the `(`.
 * @property argumentListEnd the offset of the token that closes the list, or the end
 *   of the file. Both bounds are INCLUSIVE of the caret: a caret at
 *   [argumentListEnd] is still in the last argument's slot.
 */
internal class SignatureAnchor(
    val call: Node,
    val activeArgument: Int,
    val argumentListStart: Int,
    val argumentListEnd: Int,
)

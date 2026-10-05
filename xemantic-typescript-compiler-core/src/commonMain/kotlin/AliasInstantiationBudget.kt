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
 * (P18.304) The budget that bounds generic type-alias substitution — the checker's analogue
 * of tsgo's `instantiationDepth === 100 || instantiationCount >= 5_000_000` (TS2589) plus
 * `getConditionalType`'s tail-recursion counter (`tailCount`, 1,000).
 *
 * Three independent limits, because they bound three different shapes:
 *
 *  * **depth** ([DEPTH], 100): nested NON-tail alias substitutions. A linear recursion
 *    costs one substitution per level, so the depth alone is affordable.
 *  * **count** ([COUNT], 2,000 per outermost substitution): every substitution performed
 *    below one outermost alias reference. This is what makes the depth affordable: a body
 *    with TWO recursive references whose arguments change per level (a `then` and a
 *    `catch` method each returning the alias, `declarationEmitPrivatePromiseLikeInterface`)
 *    is a full binary tree — `2^depth` substitutions, 1,023 at the old depth of 10, and a
 *    heap exhaustion at 25. The count bails such a fan-out after 2,000 substitutions
 *    however deep the budget is. Measured, no evaluation that completes in the active corpus
 *    exceeds 1,407 (`conditionalTypeDoesntSpinForever`), nor 518 in the eight census libraries.
 *  * **tail** ([TAIL], 1,000): consecutive substitutions in TAIL position — the reference
 *    is the alias body itself or sits in a conditional's branch, so its value IS the
 *    enclosing alias's value (`Trim<S> = S extends ` ${infer T}` ? Trim<T> : S`). tsgo
 *    resolves those in a loop instead of recursing and allows 1,000 of them; here they do
 *    not consume [depth].
 */
internal class AliasInstantiationBudget {
    /** Nested non-tail substitutions currently open. */
    var depth: Int = 0
        private set

    /** Consecutive tail substitutions currently open above the innermost non-tail one. */
    private var tail: Int = 0

    /** Substitutions performed below the current outermost one. */
    private var count: Int = 0

    /** Whether one more substitution — a tail one when [isTail] — is refused. */
    fun exhausted(isTail: Boolean): Boolean =
        (if (isTail) tail >= TAIL else depth >= DEPTH) || (depth > 0 && count >= COUNT)

    /** Opens a substitution; returns the token [leave] needs. */
    fun enter(isTail: Boolean): Int {
        if (depth == 0) count = 0
        count++
        val saved = tail
        if (isTail) tail++ else { tail = 0; depth++ }
        return saved
    }

    /** Closes the substitution [enter] opened with [isTail], which returned [saved]. */
    fun leave(isTail: Boolean, saved: Int) {
        if (!isTail) depth--
        tail = saved
    }

    /**
     * Whether [ref], a reference met while substituting the body of [enclosing], is in TAIL
     * position: it is that body, or reaches it through conditional branches (`trueType` /
     * `falseType`) and parentheses only. Its value is then the enclosing alias's own value, so
     * evaluating it is one more iteration of the same resolution rather than a nested one.
     */
    fun isTailReference(ref: Node, enclosing: TypeAliasDeclaration?): Boolean {
        if (enclosing == null) return false
        var child: Node = ref
        var parent: Node? = (ref as? NodeBase)?.parent
        while (parent != null) {
            when (parent) {
                is ParenthesizedType -> {}
                is ConditionalType -> if (child !== parent.trueType && child !== parent.falseType) return false
                is TypeAliasDeclaration -> return parent === enclosing && child === parent.type
                else -> return false
            }
            child = parent
            parent = (parent as? NodeBase)?.parent
        }
        return false
    }

    companion object {
        const val DEPTH = 100
        const val COUNT = 2_000
        const val TAIL = 1_000
    }
}

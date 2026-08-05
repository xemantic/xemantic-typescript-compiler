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

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (M3.0 / OPTCHAIN.1, round 836) An optional chain may not be a REFERENCE target.
 *
 * tsc runs `checkReferenceExpression(expr, invalidReferenceMessage,
 * invalidOptionalChainMessage)` at every position that writes through an
 * expression, and its second leg rejects `expr.flags & NodeFlags.OptionalChain`.
 * We already had the first leg (TS2357 / TS2364) and none of the second, so
 * `obj?.a = 1`, `obj?.a++`, `for (obj?.a in {})`, `for (obj?.a of [])` and every
 * destructuring-assignment element compiled silently.
 *
 * The five codes are one predicate at five sites:
 *  - TS2777 `++`/`--` operand
 *  - TS2778 object REST target
 *  - TS2779 assignment LHS (plain and compound), array/object destructuring element,
 *           and an ARRAY rest target — tsc reserves TS2778 for the object one
 *  - TS2780 `for..in` LHS
 *  - TS2781 `for..of` LHS
 *
 * We carry no `NodeFlags.OptionalChain` bit, so the predicate is a descent through
 * the chain's own links: a `?.` anywhere BELOW the target makes the whole target a
 * chain (`a?.b.c` is one), while a PARENTHESIS terminates it (`(a?.b).c` is not),
 * and the value-preserving outer wrappers are skipped first exactly as tsc's
 * `skipOuterExpressions(expr, Assertions | Parentheses)` does.
 *
 * Every positive pin below fails on the pre-round-836 binary, which emits nothing
 * at all for these shapes. The controls fail on the two naive predicates: one that
 * skips parentheses inside the chain descent as well as outside (so `(obj?.a).b`
 * would report), and one that answers "the target's subtree contains a `?.`
 * anywhere" (so `obj.a[obj?.b] = 1`, whose chain is in the INDEX, would report).
 */
class OptionalChainReferenceTargetTest {

    /** `declare const obj: any` — the conformance fixtures' own prelude, so no lib shape is involved. */
    private val prelude = """
        declare const obj: any;

    """.trimIndent() + "\n"

    // ---------------------------------------------------------------------
    // TS2777 — the increment / decrement operand.
    // ---------------------------------------------------------------------

    /** Verbatim `propertyAccessChain.3.ts` line 1: the postfix form. */
    @Test
    fun `a postfix increment on an optional property access is an error`() {
        val diagnostics = diagnose(prelude + "obj?.a++;\n")
        diagnostics should {
            have(any {
                it.code == 2777 &&
                    it.message == "The operand of an increment or decrement operator " +
                    "may not be an optional property access."
            })
        }
        // tsc squiggles the operand itself — `obj?.a`, six characters, at column 1.
        val d = diagnostics.first { it.code == 2777 }
        val character = d.character
        val length = d.length
        assert(character == 1)
        assert(length == 6)
    }

    /** The PREFIX form anchors at the operand, not at the operator — tsc's `(8,3)`. */
    @Test
    fun `a prefix decrement on an optional element access is an error`() {
        val diagnostics = diagnose(prelude + """--obj?.["a"];""" + "\n")
        diagnostics should { have(any { it.code == 2777 }) }
        val character = diagnostics.first { it.code == 2777 }.character
        assert(character == 3)
    }

    /**
     * The chain CONTINUES past the `?.`: `obj?.a.b` is one optional chain, so a
     * predicate reading only the target node's own `?.` token misses it.
     */
    @Test
    fun `a chain continued past the question dot is still an optional chain`() {
        diagnose(prelude + "obj?.a.b++;\n") should { have(any { it.code == 2777 }) }
    }

    // ---------------------------------------------------------------------
    // TS2779 — the assignment left-hand side.
    // ---------------------------------------------------------------------

    /** Plain `=`. */
    @Test
    fun `an assignment to an optional property access is an error`() {
        val diagnostics = diagnose(prelude + "obj?.a = 1;\n")
        diagnostics should {
            have(any {
                it.code == 2779 &&
                    it.message == "The left-hand side of an assignment expression " +
                    "may not be an optional property access."
            })
        }
        // The valid-reference leg must stay silent — a chain IS a property access.
        diagnostics should { have(none { it.code == 2364 }) }
    }

    /** A COMPOUND assignment runs the same check; its emitter is a separate branch. */
    @Test
    fun `a compound assignment to an optional element access is an error`() {
        diagnose(prelude + """obj?.["a"] += 1;""" + "\n") should {
            have(any { it.code == 2779 })
            have(none { it.code == 2364 })
        }
    }

    // ---------------------------------------------------------------------
    // TS2780 / TS2781 — the loop-header left-hand sides.
    // ---------------------------------------------------------------------

    @Test
    fun `a for-in left-hand side that is an optional chain is an error`() {
        val diagnostics = diagnose(prelude + "for (obj?.a in {});\n")
        diagnostics should {
            have(any {
                it.code == 2780 &&
                    it.message == "The left-hand side of a 'for...in' statement " +
                    "may not be an optional property access."
            })
        }
        val character = diagnostics.first { it.code == 2780 }.character
        assert(character == 6)
    }

    @Test
    fun `a for-of left-hand side that is an optional chain is an error`() {
        diagnose(prelude + "for (obj?.a of []);\n") should {
            have(any {
                it.code == 2781 &&
                    it.message == "The left-hand side of a 'for...of' statement " +
                    "may not be an optional property access."
            })
        }
    }

    /** A declaration initializer is not an expression, so the loop check must not fire. */
    @Test
    fun `a for-of over a declaration is silent`() {
        diagnose(prelude + "for (const item of []) { obj?.a; }\n") should {
            have(none { it.code == 2781 })
            have(none { it.code == 2780 })
        }
    }

    // ---------------------------------------------------------------------
    // The destructuring-assignment elements.
    // ---------------------------------------------------------------------

    /** An object-pattern PROPERTY target is an ordinary assignment LHS: TS2779. */
    @Test
    fun `an object destructuring property target that is an optional chain is an error`() {
        val diagnostics = diagnose(prelude + "({ a: obj?.a } = { a: 1 });\n")
        diagnostics should { have(any { it.code == 2779 }) }
        val character = diagnostics.first { it.code == 2779 }.character
        assert(character == 7)
    }

    /** The object REST target is the one position tsc gives its own code. */
    @Test
    fun `an object rest target that is an optional chain is a distinct code`() {
        diagnose(prelude + "({ ...obj?.a } = { a: 1 });\n") should {
            have(any {
                it.code == 2778 &&
                    it.message == "The target of an object rest assignment " +
                    "may not be an optional property access."
            })
            have(none { it.code == 2779 })
        }
    }

    /** An ARRAY rest target is NOT the object-rest code — it stays TS2779. */
    @Test
    fun `an array rest target that is an optional chain stays the assignment code`() {
        diagnose(prelude + "[...obj?.a] = [];\n") should {
            have(any { it.code == 2779 })
            have(none { it.code == 2778 })
        }
    }

    // ---------------------------------------------------------------------
    // Controls.
    // ---------------------------------------------------------------------

    /**
     * A PARENTHESIS terminates the chain, so `(obj?.a).b` is a plain property
     * access and writing through it is legal. Fails against a predicate whose
     * chain descent skips parentheses as its outer-wrapper loop does.
     */
    @Test
    fun `negative control - a parenthesis terminates the chain`() {
        diagnose(prelude + "(obj?.a).b = 1;\n(obj?.a).b++;\n") should {
            have(none { it.code == 2777 })
            have(none { it.code == 2779 })
        }
    }

    /**
     * The chain is in the INDEX, not in the target's own link path. Fails against a
     * predicate that answers "this subtree contains a `?.` somewhere".
     */
    @Test
    fun `negative control - an optional chain inside the index is not a chained target`() {
        diagnose(prelude + "obj.a[obj?.b] = 1;\n") should {
            have(none { it.code == 2779 })
            have(none { it.code == 2780 })
        }
    }

    /** Reading through a chain is the whole point of the operator. */
    @Test
    fun `negative control - an optional chain in a read position is silent`() {
        diagnose(prelude + "const y = obj?.a;\nconst z = obj?.a.b;\n") should {
            have(none { it.code == 2777 })
            have(none { it.code == 2778 })
            have(none { it.code == 2779 })
        }
    }

    /** An ordinary write target must stay silent in every one of the five positions. */
    @Test
    fun `negative control - a plain reference target is silent everywhere`() {
        diagnose(
            prelude +
                "obj.a = 1;\nobj.a++;\nfor (obj.a in {});\nfor (obj.a of []);\n" +
                "({ a: obj.a } = { a: 1 });\n({ ...obj.a } = { a: 1 });\n",
        ) should {
            have(none { it.code == 2777 })
            have(none { it.code == 2778 })
            have(none { it.code == 2779 })
            have(none { it.code == 2780 })
            have(none { it.code == 2781 })
        }
    }
}

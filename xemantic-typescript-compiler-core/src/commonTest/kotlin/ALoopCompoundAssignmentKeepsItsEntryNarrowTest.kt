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
import kotlin.test.Test

/**
 * (CHK.70)(a): a loop whose back edges only ever COMPOUND-ASSIGN the reference has a
 * fixpoint bounded by `entry union nonNullish(declaredType)` — and that bound is a
 * function of the DECLARATION alone, so it costs no back-edge traversal.
 *
 * **The reader every positive here names is the RETURN one with a UNION target**
 * (round 784's gate admits `Type.Union` targets to the flow walk on the shipped
 * binary, which is why these pins need no (CHK.63) gate to discriminate). The subject
 * is an IDENTIFIER — deliberately, and it is NOT the vacuous case (CHK.69) warns
 * about: that warning is about the DECLARATION reader with a PRIMITIVE target, which
 * answers from [Checker.currentLocalTypes] and is loop-blind. Vacuity is ruled out
 * per pin by measurement rather than by argument — every positive below is RED on the
 * parent binary `dcaf1594` and GREEN here, and the two controls are RED on BOTH.
 *
 * **Why an identifier and not a property path.** A property-path compound assignment
 * (`o.p += 1`) is claimed by neither [Checker.flowAssignmentTargetsName] nor
 * [Checker.flowAssignmentMightNarrow]'s `BinaryExpression` arm, so it never reaches
 * this decision at all — it is already pass-through. The rule is identifier-only by
 * construction, so an identifier fixture is the ONLY fixture that can exercise it.
 *
 * **Why it is sound.** `+= -= *= /= %= **= <<= >>= >>>= &= |= ^=` each store the
 * result of a numeric or string operation, which is a primitive by the language's own
 * semantics — never `null`/`undefined`. So each back edge's post-state is bounded by
 * the declaration minus its nullish constituents WHATEVER the loop did before it, and
 * the least fixpoint is bounded by the union of that with the entry state. The three
 * LOGICAL compound operators are excluded: `&&=` stores the LHS unchanged when it is
 * falsy, and `||=`/`??=` store the right operand, whose type this decision does not
 * resolve.
 *
 * Ground truth for every fixture below is `tools/tsgo-7.0.2/lib/tsc` over the same
 * source: silent on all seven positives, and reporting TS2322 on both controls.
 */
class ALoopCompoundAssignmentKeepsItsEntryNarrowTest {

    private val prelude = """
        declare function cond(): boolean;
        declare const s: string;
        declare const n: number;
        declare function maybeS(): string | undefined;
        declare function take(x: string | boolean): void;
    """.trimIndent() + "\n"

    private fun d(body: String) = diagnose(prelude + body.trimIndent())

    // ---- positives: RETURN reader, UNION target -------------------------------

    @Test
    fun `a while whose body only compound-assigns keeps the pre-loop narrow`() {
        val d = d(
            """
            function q1(): string | boolean {
              let r: string | undefined;
              r = s;
              while (cond()) { r += s; }
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `a for with a break and a compound assignment keeps the narrow`() {
        val d = d(
            """
            function q2(): string | boolean {
              let r: string | undefined;
              r = s;
              for (;;) { if (cond()) { break; } r += s; }
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `a numeric compound assignment keeps the narrow too`() {
        val d = d(
            """
            function q3(): number | boolean {
              let r: number | undefined;
              r = n;
              while (cond()) { r -= n; }
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `a do-while whose body only compound-assigns keeps the narrow`() {
        val d = d(
            """
            function q4(): string | boolean {
              let r: string | undefined;
              r = s;
              do { r += s; } while (cond());
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `two compound-assigning back edges are both bounded by the declaration`() {
        val d = d(
            """
            function q5(): string | boolean {
              let r: string | undefined;
              r = s;
              while (cond()) { if (cond()) { r += s; } else { r += "x"; } }
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    // ---- controls: RED on BOTH binaries --------------------------------------

    @Test
    fun `control - a plain maybe-undefined assignment on the back edge still washes`() {
        val d = d(
            """
            function c1(): string | boolean {
              let r: string | undefined;
              r = s;
              while (cond()) { r = maybeS(); }
              return r;
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `control - a compound arm beside a nullish arm still washes`() {
        val d = d(
            """
            function c2(): string | boolean {
              let r: string | undefined;
              r = s;
              while (cond()) { if (cond()) { r += s; } else { r = maybeS(); } }
              return r;
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `control - the same body with the two if-arms written the other way round`() {
        // The ORDER-FREENESS control. The scan pops its stack LIFO, so with the arms
        // written this way the compound assignment is the one it meets FIRST; a rule
        // that decided at the first compound it saw would answer COMPOUND here and
        // `string | boolean` would swallow a real `undefined`. tsc 7.0.2 reports both
        // spellings, and so must we.
        val d = d(
            """
            function c2b(): string | boolean {
              let r: string | undefined;
              r = s;
              while (cond()) { if (cond()) { r = maybeS(); } else { r += s; } }
              return r;
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    @Test
    fun `control - a plain nullish assignment FOLLOWED by a compound one still washes`() {
        // The same order question along ONE path. Stopping the scan at the compound
        // assignment would be sound for the path — it overwrites — but it would put us
        // one step PAST tsc, whose compound arm takes the ANTECEDENT's base type and so
        // reports here. The run-time value really is a string (`undefined + s` is a
        // string), which is exactly why the bound has to be stated as "EVERY assignment
        // is compound" rather than "the LAST one is".
        val d = d(
            """
            function c2d(): string | boolean {
              let r: string | undefined;
              r = s;
              while (cond()) { r = maybeS(); r += s; }
              return r;
            }
            """,
        )
        assert(d.any { it.code == 2322 })
    }

    // ---- controls: GREEN on BOTH binaries ------------------------------------

    @Test
    fun `control - a compound assignment to a DIFFERENT name leaves the narrow alone`() {
        val d = d(
            """
            function c3(): string | boolean {
              let r: string | undefined;
              let other = 0;
              r = s;
              while (cond()) { other += 1; }
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }

    @Test
    fun `control - the same shape with no loop at all`() {
        val d = d(
            """
            function c4(): string | boolean {
              let r: string | undefined;
              r = s;
              return r;
            }
            """,
        )
        assert(d.none { it.code == 2322 })
    }
}

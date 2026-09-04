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
 * (REL.2) round 783 — an OBJECT-LITERAL property value sees the flow-narrowed ENUM,
 * including where the literal has no contextual type at all.
 *
 * The object-literal value path accepts a flow-narrowed value only when the
 * CONTEXTUAL property type accepts it and the raw type does not (rounds 438/462/468).
 * An object literal in a TERNARY branch has no contextual type — `ctxObj` is null
 * there — so that acceptance can never fire and the property kept the whole enum.
 * A non-enum literal union is unaffected, which is why the gap survived: until the
 * (REL.2) relation rule lands, the whole enum relates to a member target vacuously,
 * so nothing downstream could see the loss.
 *
 * The third acceptance is monotone by CONSTRUCTION rather than by relation test: the
 * round-746 owner rule bounds the narrowed value to a sub-union of the very enum it
 * replaces, so every target that accepted the enum accepts the subset.
 *
 * Sites this closes: `importFixes.ts:1127` (a SHORTHAND property under
 * `k === A || k === B` in a ternary) and `formattingScanner.ts:113` (a named property
 * after a guard NEGATION with an early `break`).
 *
 * EVERY POSITIVE PIN DISCRIMINATES BY MESSAGE — a whole enum is still vacuously
 * assignable to a member target, so a silence pin would pass on the broken build.
 *
 * (PARITY.2): the probe is a `never` parameter, not the PRIMITIVE `string` it was until
 * the enum arm of `Checker.baseTypeOfLiteralType` landed. tsc's `reportRelationError`
 * generalizes an enum-member source to its parent enum at any target that cannot hold a
 * singleton, so at a `string` parameter the narrowed `K.A | K.B` and the un-narrowed `K`
 * render the SAME string and every pin here would go BLIND rather than red. Every
 * expectation below is byte-identical to tsgo 7.0.2 AND pristine `typescript@6.0.3`,
 * measured — except `an object-literal property initialised from a member expression is
 * unaffected`, which carries its own note.
 */
class ObjLitEnumNarrowTest {

    private val prelude = """
        enum K { A, B, C, D }
        declare function probe(s: never): number;
        declare function isAB(k: K): k is K.A | K.B;
    """.trimIndent() + "\n"

    /**
     * DISCRIMINATES BY MESSAGE — `'K.A | K.B'` fixed, `'K'` ablated. The
     * `importFixes.ts:1127` shape: a disjunction of member equalities as a TERNARY
     * condition, the object literal in its true branch.
     */
    @Test
    fun `an object literal in a ternary branch sees a disjunctive member narrow`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(k: K): number {
                    return (k === K.A || k === K.B) ? probe({ v: k }.v) : 0;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'K.A | K.B' is not assignable to parameter of type 'never'." })
    }

    /**
     * DISCRIMINATES BY MESSAGE — `'K.A'` fixed, `'K'` ablated. A single equality, so
     * the disjunction above is incidental rather than the cause.
     */
    @Test
    fun `an object literal in a ternary branch sees a single member narrow`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(k: K): number {
                    return k === K.A ? probe({ v: k }.v) : 0;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'K.A' is not assignable to parameter of type 'never'." })
    }

    /**
     * DISCRIMINATES BY MESSAGE — the `formattingScanner.ts:113` shape: a guard
     * NEGATION with an early `break`, then the object literal in the loop body.
     */
    @Test
    fun `an object literal after a guard negation with an early break sees the narrow`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(k: K, n: number): number {
                    let out = 0;
                    while (n-- > 0) {
                        if (!isAB(k)) {
                            break;
                        }
                        out += probe({ v: k }.v);
                    }
                    return out;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'K.A | K.B' is not assignable to parameter of type 'never'." })
    }

    /**
     * DISCRIMINATES BY MESSAGE — a SHORTHAND property, which is a separate branch of
     * the object-literal walker and needs the same acceptance.
     */
    @Test
    fun `a shorthand object-literal property in a ternary branch sees the narrow`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(k: K): number {
                    return (k === K.A || k === K.B) ? probe({ k }.k) : 0;
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'K.A | K.B' is not assignable to parameter of type 'never'." })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — with no narrowing in scope the property must
     * still read the whole enum. Asserted by MESSAGE so it FIRES either way.
     */
    @Test
    fun `an unnarrowed object-literal property still reads the whole enum`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(k: K): number {
                    return probe({ v: k }.v);
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'K' is not assignable to parameter of type 'never'." })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — a member EXPRESSION as the value was already
     * a member type (round 742) and owes nothing to this acceptance.
     *
     * (PARITY.2) MEASURED DIVERGENCE, recorded rather than weakened: at this `never`
     * target tsgo 7.0.2 and pristine `typescript@6.0.3` both read `'K'`, because tsc
     * WIDENS a mutable object-literal property's enum member to its enum exactly as it
     * widens a `let`. We read `'K.A'`. The expectation below is OURS, and it is what
     * makes this pin discriminate the round-742 path at all; a `string` target would
     * agree with tsc (`'K'`) and see nothing.
     */
    @Test
    fun `an object-literal property initialised from a member expression is unaffected`() {
        val diagnostics = diagnose(
            prelude +
                """
                export function f(): number {
                    return probe({ v: K.A }.v);
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'K.A' is not assignable to parameter of type 'never'." })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — the acceptance must never leave the enum's own
     * member domain. A FOREIGN enum's member is not a member of `K`, so the round-746
     * owner rule refuses it and the property keeps whatever it had.
     */
    @Test
    fun `a foreign enum member is not accepted as a narrow of this enum`() {
        val diagnostics = diagnose(
            prelude +
                """
                enum Z { A, B }
                export function f(z: Z): number {
                    return probe({ v: z }.v);
                }
                """.trimIndent()
        )
        assert(diagnostics.any { it.code == 2345 && it.message == "Argument of type 'Z' is not assignable to parameter of type 'never'." })
    }

    /**
     * HOLDS ON BOTH SIDES ON PURPOSE — a NON-enum literal union already narrowed at
     * this position (through the raw type, not the acceptance), and must keep doing so.
     */
    @Test
    fun `a non-enum literal union in a ternary branch still narrows`() {
        val diagnostics = diagnose(
            """
            declare function probeN(s: string): number;
            interface NBox { readonly v: "a" | "b"; }
            export function f(s: "a" | "b" | "c"): NBox | undefined {
                return (s === "a" || s === "b") ? { v: s } : undefined;
            }
            """.trimIndent()
        )
        assert(diagnostics.none { it.code == 2322 })
    }
}

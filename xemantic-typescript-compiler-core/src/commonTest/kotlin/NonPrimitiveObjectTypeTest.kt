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
 * (M3.0 / NONPRIM.1, round 834) The `object` keyword.
 *
 * `object` is a `Type.Intrinsic` carrying [TypeFlags.NonPrimitive], and until this
 * round the whole compiler held exactly ONE rule about it: `isSimpleTypeRelatedTo`'s
 * NonPrimitive leg, written as the NEGATION of tsc's rule — "the source is not
 * flagged primitive" instead of tsc's "the source is flagged Object". Those are not
 * the same predicate, because [TypeFlags.Primitive] omits every LITERAL bit, so a
 * string/number/boolean LITERAL type, a bare `Type.TypeParam`, `unknown`, and the
 * instantiable `keyof`/indexed-access types all satisfied `object` silently.
 *
 * Three further gates then hid the corrected verdict from every emission site: two
 * in `canUseTypeEngine` (an unconstrained type-parameter source, and a union source
 * carrying a nullish member) and one in `isSimpleCheckableType` (which decides
 * whether an ARGUMENT may be judged at all). All four changes are keyed on the
 * target's `NonPrimitive` flag, so nothing that does not write the `object` keyword
 * can reach any of them.
 *
 * Every positive pin below fails on the pre-round-834 binary; every negative
 * control fails on the naive implementation that simply rejects the sources the
 * old rule wrongly accepted (a constrained type parameter, an empty-object
 * constraint, a class instance, a function, an array).
 */
class NonPrimitiveObjectTypeTest {

    // ---------------------------------------------------------------------
    // The relation itself — literal sources.
    // ---------------------------------------------------------------------

    /**
     * The defect that reaches furthest from the `object` keyword: a conditional
     * `T[P] extends V | object` answered TRUE for a numeric-LITERAL `T[P]`, so the
     * mapped type's value became `1` where tsc computes `0`. This is verbatim the
     * `nonPrimitiveAndTypeVariables` conformance case's repro from TypeScript
     * issue #23800, and on the pre-834 binary it emits a TS2322 false positive
     * naming a target of `'1 | 1'`.
     */
    @Test
    fun `a numeric literal does not extend object inside a conditional type`() {
        diagnose(
            """
            type B<T, V> = { [P in keyof T]: T[P] extends V | object ? 1 : 0; };
            let b: B<{ a: 0 | 1 }, 0> = { a: 0 };
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * A STRING literal is a `Type.StringLiteral`, which carries no `String` bit and
     * so read as "not primitive" to the old rule.
     */
    @Test
    fun `a string literal type is not assignable to object`() {
        diagnose(
            """
            type IsObject<T> = T extends object ? "yes" : "no";
            declare const verdict: IsObject<"a">;
            const answer: "no" = verdict;
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /** `unknown` is not an object type — tsc rejects it, the old negation accepted it. */
    @Test
    fun `unknown is not assignable to object`() {
        diagnose(
            """
            declare const u: unknown;
            const o: object = u;
            """,
        ) should {
            have(any { it.code == 2322 && it.message == "Type 'unknown' is not assignable to type 'object'." })
        }
    }

    // ---------------------------------------------------------------------
    // Type-parameter sources — the constraint decides.
    // ---------------------------------------------------------------------

    /**
     * An UNCONSTRAINED type parameter can be instantiated with a primitive, so it
     * is not assignable to `object`. Two gates had to move together for this to
     * surface: the relation's own verdict, and `canUseTypeEngine` — which refused
     * to compare a `Type.TypeParam` source against an intrinsic target unless the
     * parameter carried a constraint.
     */
    @Test
    fun `an unconstrained type parameter is not assignable to object`() {
        diagnose(
            """
            function generic<T>(t: T) {
                var o: object = t;
            }
            """,
        ) should {
            have(any { it.code == 2322 && it.message == "Type 'T' is not assignable to type 'object'." })
        }
    }

    /** Negative control - a naive "no type parameter satisfies object" rule fails here. */
    @Test
    fun `a type parameter constrained to object is assignable to object`() {
        diagnose(
            """
            function bound<T extends object>(t: T) {
                var o: object = t;
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * Negative control - `T extends {}` also satisfies `object`, because the
     * constraint is an Object-flagged anonymous type. This is the arm that makes
     * the constraint consult a real relation call rather than an identity test.
     */
    @Test
    fun `a type parameter constrained to the empty object type is assignable to object`() {
        diagnose(
            """
            function bound3<T extends {}>(t: T) {
                var o: object = t;
            }
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    // ---------------------------------------------------------------------
    // Union sources.
    // ---------------------------------------------------------------------

    /**
     * Negative control - the WIDENING direction is legal, and a union TARGET
     * containing `object` must keep accepting an Object-flagged source. The
     * complementary direction (`object | null` NOT assignable to `object`) is a
     * genuine gap that this round deliberately did not open: see the session note
     * and the comment in `canUseTypeEngine` — the gate that would emit it also
     * fires for a union the flow has narrowed and we have not.
     */
    @Test
    fun `object is assignable to a nullable object union`() {
        diagnose(
            """
            declare let plain: object;
            let nullable: object | null = plain;
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    // ---------------------------------------------------------------------
    // Argument position — isSimpleCheckableType.
    // ---------------------------------------------------------------------

    /**
     * An `object` PARAMETER was not simple-checkable, so `checkArgumentsAgainstSignature`
     * declined every call against it and no argument could ever be rejected.
     */
    @Test
    fun `a primitive argument is rejected against an object parameter`() {
        diagnose(
            """
            declare function takeObject(o: object): void;
            declare const flag: boolean;
            takeObject(flag);
            """,
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message == "Argument of type 'boolean' is not assignable to parameter of type 'object'."
            })
        }
    }

    /**
     * ...and EXACTLY once. The first attempt widened [isSimpleCheckableType] itself
     * rather than this one gate, and B498's dedicated
     * `f<explicitTypeArgs>(args)` walker — whose own firewall is that same predicate —
     * then co-emitted at the identical position. An explicit type argument is what
     * selects that walker, so the shape below is the one that discriminates and the
     * inferred-`T` call above is not.
     */
    @Test
    fun `an explicitly instantiated object parameter is reported exactly once`() {
        val diagnostics = diagnose(
            """
            function generic<T>(t: T) {
            }
            generic<object>(123);
            """,
        )
        assert(diagnostics.count { it.code == 2345 } == 1)
    }

    /**
     * The bare-`T` parameter of `function bound<T extends object>(t: T)` is a
     * DIFFERENT gate: `paramType` is a `Type.TypeParam` there, so the parameter-side
     * firewall never sees `object` at all and only the CONSTRAINT does. It carries
     * its own copy of the simple-checkable test, the first cut of this round widened
     * one and not the other, and the whole-case diff is what noticed — so neither
     * this pin nor the plain-`object`-parameter one above implies the other.
     */
    @Test
    fun `a primitive argument is rejected against a parameter constrained to object`() {
        diagnose(
            """
            function bound<T extends object>(t: T) {
            }
            bound(123);
            """,
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message == "Argument of type 'number' is not assignable to parameter of type 'object'."
            })
        }
    }

    /** Negative control - the same constrained parameter accepts an object literal. */
    @Test
    fun `an object literal is accepted against a parameter constrained to object`() {
        diagnose(
            """
            function bound<T extends object>(t: T) {
            }
            bound({ a: 1 });
            """,
        ) should { have(none { it.code == 2345 }) }
    }

    /** Negative control - an object literal argument is fine. */
    @Test
    fun `an object literal argument is accepted against an object parameter`() {
        diagnose(
            """
            declare function takeObject(o: object): void;
            takeObject({ a: 1 });
            """,
        ) should { have(none { it.code == 2345 }) }
    }

    /**
     * Negative control - a CLASS instance, a FUNCTION and an ARRAY are all
     * Object-flagged and must keep satisfying `object`. These are the sources the
     * corrected positive rule has to keep admitting; a rule written as "only a
     * plain anonymous object type" would fail all three.
     */
    @Test
    fun `class instances functions and arrays are all assignable to object`() {
        diagnose(
            """
            class Widget {}
            declare const widget: Widget;
            declare const callback: () => void;
            declare const numbers: number[];
            const a: object = widget;
            const b: object = callback;
            const c: object = numbers;
            """,
        ) should { have(none { it.code == 2322 }) }
    }

    /**
     * Negative control - an INTERSECTION source reaches `object` only through the
     * structural engine's intersection-source branch, because a `Type.Intersection`
     * carries no Object flag of its own.
     */
    @Test
    fun `an intersection carrying an object constituent is assignable to object`() {
        diagnose(
            """
            interface Named { name: string }
            declare const both: Named & { id: number };
            const o: object = both;
            """,
        ) should { have(none { it.code == 2322 }) }
    }
}

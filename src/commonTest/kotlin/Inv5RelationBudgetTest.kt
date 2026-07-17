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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * INV.5(d1) (round 552): the blanket `relationDepth < 4` cap on
 * generic-property substitution in [getPropertyTypeForRelation] is replaced
 * by a per-top-level-relation INSTANTIATION BUDGET
 * (CheckerState.genericPropInstantiationBudget) — substitution now happens at
 * ANY depth until the budget trips, then falls back to raw `getTypeOfSymbol`
 * exactly like the old over-cap behavior. The perf-bomb breadth explosion
 * (performanceComparisonOfStructurallyIdenticalInterfacesWithGenericSignatures,
 * corpus-pinned) stays bounded by the budget.
 *
 * Paired FP firewall: [tryEmitObjectVsNamedUnionArg] bails when the param
 * union still carries an UN-INFERRED TypeParam — deeper substitution makes
 * the whole-union relation fail on the unbound T (the documented M3.1 masked
 * gap), and an un-inferred generic param is OUR inference gap, never a user
 * error (the round-431 foreign-TP gate rationale on the PARAM side; tsc's own
 * program.ts flatten call FP'd on all 8 profiles without it).
 */
class Inv5RelationBudgetTest {

    @Test
    fun `deep nested generic mismatch beyond the old depth-4 cap now fires`() {
        // Distinct source/target interfaces at every level so the same-target
        // covariant arg shortcut never kicks in — each level needs SUBSTITUTED
        // property types. Under the old cap, levels >= 4 resolved raw (unbound
        // T -> errorType -> trivial pass) and the leaf mismatch was invisible.
        val d = diagnose("""
            interface A1<T> { v: A2<T>; }
            interface A2<T> { v: A3<T>; }
            interface A3<T> { v: A4<T>; }
            interface A4<T> { v: A5<T>; }
            interface A5<T> { v: A6<T>; }
            interface A6<T> { x: T; }
            interface B1<T> { v: B2<T>; }
            interface B2<T> { v: B3<T>; }
            interface B3<T> { v: B4<T>; }
            interface B4<T> { v: B5<T>; }
            interface B5<T> { v: B6<T>; }
            interface B6<T> { x: T; }
            declare const a: A1<string>;
            const b: B1<number> = a;
        """)
        d should { have(any { it.code == 2322 }) }
    }

    @Test
    fun `negative control - deep nested generic MATCH stays silent`() {
        diagnose("""
            interface A1<T> { v: A2<T>; }
            interface A2<T> { v: A3<T>; }
            interface A3<T> { v: A4<T>; }
            interface A4<T> { v: A5<T>; }
            interface A5<T> { v: A6<T>; }
            interface A6<T> { x: T; }
            interface B1<T> { v: B2<T>; }
            interface B2<T> { v: B3<T>; }
            interface B3<T> { v: B4<T>; }
            interface B4<T> { v: B5<T>; }
            interface B5<T> { v: B6<T>; }
            interface B6<T> { x: T; }
            declare const a: A1<string>;
            const b: B1<string> = a;
        """) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - un-inferred generic union param draws no TS2345 (the flatten shape)`() {
        // tsc infers T=string and the call is valid; our engine leaves T
        // unbound, so the whole-union relation fails — the param-side
        // foreign-TP gate must suppress the B561 emitter.
        diagnose("""
            declare function flatten<T>(array: T[][] | readonly (T | readonly T[] | undefined)[]): T[];
            declare const items: (readonly string[] | undefined)[];
            flatten(items);
        """) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `firewall - the named-interface union arg emitter still fires for concrete unions`() {
        // B561's own family (errorsOnUnionsOfOverlappingObjects01 shape) has
        // no TPs in the param union — the new gate must not silence it.
        val d = diagnose("""
            interface Cat { meows: boolean; legs: number; }
            interface Dog { barks: boolean; legs: number; }
            declare function pet(animal: Cat | Dog): void;
            declare const thing: { legs: number };
            pet(thing);
        """)
        d should { have(any { it.code == 2345 }) }
    }
}

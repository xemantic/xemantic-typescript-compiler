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
 * (JIT.1)(d) round 813 — `checkIndexSigInStatement` was 10,928 bytecodes, so
 * HotSpot never JIT-compiled it; it is now an entry plus seven helpers, each one
 * contiguous run of the original body:
 *
 *  * [Checker.cisCheckNumericNamePropsVsNumberIndex] — 17.191's TS2411 for
 *    numeric-named properties against a number index signature;
 *  * [Checker.cisFindStringIndexSig] — the own-then-inherited `[s: string]: T`
 *    lookup, and the split's ONE cross-boundary value;
 *  * [Checker.cisCheckAnonIndexValueConflict] — B98.r20's TS2413 for ANONYMOUS
 *    object index values, reported at the type NAME;
 *  * [Checker.cisCheckNamedInterfaceIndexValueConflict] — B98.r128b's TS2413 for
 *    NAMED-interface index values plus B272's primitive pair, reported at the
 *    index SIGNATURE;
 *  * [Checker.cisCheckNumericMethodsVsNumberIndex] — B272's TS2411 for zero-arg
 *    numeric-named methods;
 *  * [Checker.cisCheckMethodsVsPrimitiveStringIndex] — 16.4ez's TS2411 for
 *    methods when the string index value type is PRIMITIVE;
 *  * [Checker.cisCheckPropsVsStringIndex] — the general TS2411 property loop.
 *
 * **What stays in the entry is what every input pays**: the `TypeAliasDeclaration`
 * and `VariableStatement` branches, the `when` that decides whether the statement
 * has members at all (every other statement kind `return`s there), the
 * `ModuleDeclaration` recursion, the two index-signature lookups and the early
 * `return` for "no usable string index type". Every moved region sits behind one
 * of those guards.
 *
 * `HugeMethodLimitTest` reads the compiled `Code` attribute lengths and guards
 * the SIZE. This class pins what a size check cannot see: that each helper still
 * runs and still says its own distinctive thing, and that the entry still carries
 * the three values that cross a boundary — the returned string index signature,
 * the number index signature handed to the anonymous-value check, and
 * `stringIndexTypeIsPrimitive`, which is what makes the general property loop
 * DEFER methods instead of double-reporting them.
 */
class CisSplitTest {

    // ── cisCheckNumericNamePropsVsNumberIndex ───────────────────────────────

    @Test
    fun `numeric-name arm - a numeric property name is checked against the number index type`() {
        val d = diagnose(
            """
            class C {
                0: string;
                [x: number]: number;
            }
            """
        )
        assert(d.count { it.code == 2411 } == 1)
        assert(d.first { it.code == 2411 }.message ==
            "Property '0' of type 'string' is not assignable to 'number' index type 'number'.")
    }

    @Test
    fun `numeric-name arm - a canonical numeric STRING name is checked and displays quoted`() {
        val d = diagnose(
            """
            class C {
                "1": string;
                [x: number]: number;
            }
            """
        )
        assert(d.count { it.code == 2411 } == 1)
        assert(d.first { it.code == 2411 }.message ==
            "Property '\"1\"' of type 'string' is not assignable to 'number' index type 'number'.")
    }

    // ── cisFindStringIndexSig — the one cross-boundary value ────────────────

    @Test
    fun `lookup arm - the string index signature found on a BASE type reaches the derived members`() {
        // NOT a seam pin: measured (round 813) to stay GREEN when the base-class
        // walk's contribution is dropped, because a sibling pass reports the same
        // TS2411 for a PRIMITIVE inherited index type. The seam pin below is the
        // shape that ablation showed to be uniquely ours.
        val d = diagnose(
            """
            class B { [s: string]: number; }
            interface D extends B { y: string; }
            """
        )
        assert(d.count { it.code == 2411 } == 1)
        assert(d.first { it.code == 2411 }.message ==
            "Property 'y' of type 'string' is not assignable to 'string' index type 'number'.")
    }

    @Test
    fun `seam - an INHERITED CALLABLE string index type reaches the general property loop`() {
        // The one shape the base-class walk is the sole producer for: a method
        // checked against a CALLABLE inherited string index value type. Found by
        // diffing an ablated binary (`cisFindStringIndexSig` returning only the
        // OWN signature) against the committed one over eight inherited-index
        // shapes — the other seven are supplied redundantly by a sibling pass.
        val d = diagnose(
            """
            interface B7 { [s: string]: () => number; }
            interface D7 extends B7 { m(): string; }
            """
        )
        assert(d.count { it.code == 2411 } == 1)
        assert(d.first { it.code == 2411 }.message ==
            "Property 'm' of type '() => string' is not assignable to " +
            "'string' index type '() => number'.")
    }

    @Test
    fun `lookup arm - an OWN string index signature reaches the type's own properties`() {
        val d = diagnose(
            """
            interface M {
                [s: string]: number;
                p: string;
            }
            """
        )
        assert(d.count { it.code == 2411 } == 1)
        assert(d.first { it.code == 2411 }.message ==
            "Property 'p' of type 'string' is not assignable to 'string' index type 'number'.")
    }

    @Test
    fun `negative control - a type with no string index signature reports nothing`() {
        val d = diagnose(
            """
            interface Q {
                p: string;
                r: number;
            }
            """
        )
        assert(d.none { it.code == 2411 })
        assert(d.none { it.code == 2413 })
    }

    // ── cisCheckAnonIndexValueConflict ──────────────────────────────────────

    @Test
    fun `seam - the anonymous index-value check needs the number signature the entry computed`() {
        val d = diagnose(
            """
            interface A {
                [n: number]: { a: string };
                [s: string]: { b: string };
            }
            """
        )
        assert(d.count { it.code == 2413 } == 1)
        assert(d.first { it.code == 2413 }.message ==
            "'number' index type '{ a: string; }' is not assignable to 'string' index type '{ b: string; }'.")
    }

    // ── cisCheckNamedInterfaceIndexValueConflict ────────────────────────────

    @Test
    fun `named-interface arm - a number index value missing a required property is TS2413`() {
        val d = diagnose(
            """
            interface MissP { p: string; }
            interface NoP { }
            interface I {
                [n: number]: NoP;
                [s: string]: MissP;
            }
            """
        )
        assert(d.count { it.code == 2413 } == 1)
        assert(d.first { it.code == 2413 }.message ==
            "'number' index type 'NoP' is not assignable to 'string' index type 'MissP'.")
    }

    @Test
    fun `named-interface arm - a PRIMITIVE index value pair is TS2413`() {
        val d = diagnose(
            """
            interface J {
                [n: number]: string;
                [s: string]: number;
            }
            """
        )
        assert(d.count { it.code == 2413 } == 1)
        assert(d.first { it.code == 2413 }.message ==
            "'number' index type 'string' is not assignable to 'string' index type 'number'.")
    }

    // ── cisCheckNumericMethodsVsNumberIndex ─────────────────────────────────

    @Test
    fun `numeric-method arm - a zero-arg numeric-named method is checked against the number index`() {
        val d = diagnose(
            """
            interface K {
                [n: number]: string;
                5(): string;
            }
            """
        )
        assert(d.count { it.code == 2411 } == 1)
        assert(d.first { it.code == 2411 }.message ==
            "Property '5' of type '() => string' is not assignable to 'number' index type 'string'.")
    }

    // ── cisCheckMethodsVsPrimitiveStringIndex ───────────────────────────────

    @Test
    fun `primitive-method arm - a method against a primitive string index type reports ONCE`() {
        val d = diagnose(
            """
            interface L {
                [s: string]: string;
                m(): string;
            }
            """
        )
        assert(d.count { it.code == 2411 } == 1)
        assert(d.first { it.code == 2411 }.message ==
            "Property 'm' of type '() => string' is not assignable to 'string' index type 'string'.")
    }

    @Test
    fun `seam - a method WITH parameters stays silent while the string index type is primitive`() {
        // The general property loop DEFERS every method to the primitive-method
        // helper, and that helper refuses a method carrying parameters — so this
        // shape reports nothing. Losing `stringIndexTypeIsPrimitive` on the way
        // into the property loop ADDS a TS2411 here rather than removing one,
        // which is why the assertion is a `none`.
        val d = diagnose(
            """
            interface N {
                [s: string]: string;
                q(a: number): string;
            }
            """
        )
        assert(d.none { it.code == 2411 })
    }

    // ── cisCheckPropsVsStringIndex ──────────────────────────────────────────

    @Test
    fun `property-loop arm - a method is checked when the string index type is CALLABLE`() {
        val d = diagnose(
            """
            interface P {
                [s: string]: () => number;
                m(): string;
            }
            """
        )
        assert(d.count { it.code == 2411 } == 1)
        assert(d.first { it.code == 2411 }.message.startsWith("Property 'm' of type "))
    }

    // ── the entry's own dispatch ────────────────────────────────────────────

    @Test
    fun `entry - a type alias's OWN type parameters reach its type-literal index signature`() {
        // The TypeAlias branch is the only caller that passes a non-empty
        // `outerTypeParamNames`, and that set is what distinguishes TS1337 from
        // TS1268. It `return`s before the string-index machinery, so TS2411 is
        // not what this branch produces — 17.159's TS1337 is.
        val d = diagnose("type TC<T> = { [k: T]: number };")
        assert(d.count { it.code == 1337 } == 1)
        assert(d.count { it.code == 1268 } == 0)
        assert(d.first { it.code == 1337 }.message ==
            "An index signature parameter type cannot be a literal type or generic type. " +
            "Consider using a mapped object type instead.")
    }

    @Test
    fun `entry - a variable statement's type-literal annotation is checked`() {
        val d = diagnose(
            """
            declare var v: {
                [s: string]: number;
                p: string;
            };
            """
        )
        assert(d.count { it.code == 2411 } == 1)
        assert(d.first { it.code == 2411 }.message ==
            "Property 'p' of type 'string' is not assignable to 'string' index type 'number'.")
    }

    @Test
    fun `entry - the ModuleDeclaration recursion still reaches a nested interface`() {
        val d = diagnose(
            """
            namespace NS {
                export interface Nested {
                    [s: string]: number;
                    z: string;
                }
            }
            """
        )
        assert(d.count { it.code == 2411 } == 1)
        assert(d.first { it.code == 2411 }.message ==
            "Property 'z' of type 'string' is not assignable to 'string' index type 'number'.")
    }

    @Test
    fun `entry - duplicate index signatures are TS2374 and survive alongside the helpers`() {
        val d = diagnose(
            """
            interface R {
                [s: string]: number;
                [t: string]: number;
            }
            """
        )
        // One per signature — the entry's `emitTs2374DuplicateIndexSigs` call
        // stayed in place and is unaffected by the split.
        assert(d.count { it.code == 2374 } == 2)
    }
}

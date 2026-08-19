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
 * (CHK.14) round 947 — **`abstract new (…) => T` DID NOT PARSE, AND AN `infer` IN A
 * CONSTRUCTOR TYPE'S RETURN POSITION WAS NEVER PUBLISHED INTO THE CONDITIONAL'S SCOPE.**
 *
 * Two independent one-arm gaps, measured together because one probe line carries both.
 *
 * **THE PARSER HALF.** TypeScript 4.2's abstract construct signature type. tsc's
 * `isStartOfFunctionTypeOrConstructorType` admits an `abstract` in type position ONLY
 * when the very next token is `new`, and `parseModifiersForConstructorType` then consumes
 * that one modifier before the ordinary constructor-type production runs. Without the
 * arm, `type X = abstract new () => number` read `abstract` as a type NAME and then
 * cascaded — TS1005 x3 / TS1068 x2 / TS1128 on pristine's own three-line shape, plus the
 * TS2355 / TS2564 / TS2304 for every name the failed parse never bound.
 *
 * **THE CHECKER HALF, AND ITS DIAGNOSIS WAS FILED BACKWARDS.** Round 942 recorded the
 * second defect as *"an `infer` inside a PARENTHESIZED extends clause does not publish
 * its name"*. Parentheses are irrelevant — `collectInferTypeNames` recurses through
 * `ParenthesizedType` and always has. What it had no arm for is `ConstructorType`, so
 * the UNPARENTHESIZED spelling fails identically and the PARENTHESIZED function-type
 * spelling has always worked. Its sibling `collectInferDecls` carries the arm already,
 * with a comment saying it is keeping parity with this walker; the parity only ever went
 * one way.
 *
 * **WHAT IS DELIBERATELY NOT HERE.** The `modifiers` set the parser now records is read
 * by NOTHING in the checker today — tsc's TS2511 (`Cannot create an instance of an
 * abstract class`) through an abstract construct signature value is the named future
 * consumer and is out of scope, so the abstract-ness is carried faithfully on the AST and
 * consulted nowhere. And the conditional's `infer` still does not RESOLVE through a
 * constructor type: `D<new () => K>` where `type D<T> = T extends new () => infer U ? U
 * : never` answers `any`, not `K`. Both are false NEGATIVES; this round closes the false
 * POSITIVES only, which is what the pristine rows were.
 */
class AbstractConstructorTypeTest {

    // ── the parser half: `abstract new` is a constructor type ────────────────

    @Test
    fun `a standalone abstract construct signature type parses`() {
        // The RETURN type is a KEYWORD type on purpose. With `=> K` for a declared class
        // the pre-947 misparse — `abstract` as a type name followed by a NEW expression
        // over a parenthesized arrow — happens to be silent, so the pin read green
        // against its own ablation; `=> number` is the spelling that cascades
        // (TS2693 `'number' only refers to a type, but is being used as a value here.`),
        // which is what pristine's own shape does.
        diagnose(
            """
                type W = abstract new (x: number) => number;
                declare const w: W;
            """
        ) should {
            have(none { it.code == 1005 })
            have(none { it.code == 2693 })
            have(isEmpty())
        }
    }

    @Test
    fun `an abstract construct signature type is a ConstructorType to the union rule`() {
        // TS1386 is emitted by the parser for a ConstructorType member of a union, so
        // its presence is a statement about the NODE the abstract form produced — not
        // merely that the text was consumed without complaint.
        diagnose(
            """
                type Z = string | abstract new () => void;
            """
        ) should {
            have(any { it.code == 1386 })
        }
    }

    @Test
    fun `pristine inferTypes1 line 47 - InstanceType of an abstract construct signature`() {
        diagnose(
            """
                declare type InstanceTypeX<T extends abstract new (...args: any) => any> =
                    T extends abstract new (...args: any) => infer R ? R : any;
                type U17<T extends any[]> = InstanceTypeX<abstract new (x: string, ...args: T) => T[]>;
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `an abstract construct signature type takes its own type parameters`() {
        diagnose(
            """
                type F = abstract new <T>(x: T) => T[];
                declare const f: F;
            """
        ) should {
            have(isEmpty())
        }
    }

    // ── the parser BOUND: the lookahead is what keeps the arm additive ───────

    @Test
    fun `regression guard - abstract alone in type position is still an ordinary type name`() {
        // `abstract` is a plain identifier in type position and the checker exempts it
        // by name; the lookahead must leave every such spelling on the `else` arm.
        diagnose(
            """
                type Named = abstract;
                declare const n: Named;
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `regression guard - abstract as a member name and as an index key`() {
        diagnose(
            """
                type Plain = { abstract: string };
                declare const p: Plain;
                declare const q: Plain["abstract"];
                const bad: number = q;
            """
        ) should {
            have(count { it.code == 2322 } == 1)
            have(any { it.code == 2322 && it.message.contains("'string' is not assignable to type 'number'") })
        }
    }

    @Test
    fun `regression guard - an abstract class declaration still parses`() {
        diagnose(
            """
                abstract class C { abstract m(): void }
                class D extends C { m(): void {} }
                declare const d: D;
            """
        ) should {
            have(isEmpty())
        }
    }

    // ── the checker half: an infer in a constructor type's return ────────────

    @Test
    fun `infer in a constructor type return publishes into the conditional true branch`() {
        diagnose(
            """
                type D1<T> = T extends new () => infer U ? U : never;
            """
        ) should {
            have(none { it.code == 2304 })
            have(isEmpty())
        }
    }

    @Test
    fun `infer in an abstract constructor type return publishes into the true branch`() {
        diagnose(
            """
                type D2<T> = T extends abstract new (...args: any) => infer U ? U : never;
                type D3<T> = T extends (abstract new (...args: any) => infer U) ? U : never;
            """
        ) should {
            have(none { it.code == 2304 })
            have(isEmpty())
        }
    }

    @Test
    fun `infer in a constructor type PARAMETER publishes into the true branch`() {
        diagnose(
            """
                type D4<T> = T extends new (a: infer U) => void ? U : never;
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `positive control - an undeclared name in the same true branch is still TS2304`() {
        // Without this the silence above cannot tell a published `infer` from a true
        // branch nothing checks at all.
        diagnose(
            """
                type D5<T> = T extends new () => infer U ? NoSuchNameHere : never;
            """
        ) should {
            have(count { it.code == 2304 } == 1)
            have(any { it.code == 2304 && it.message == "Cannot find name 'NoSuchNameHere'." })
        }
    }

    @Test
    fun `regression guard - a FUNCTION type return infer was never affected`() {
        diagnose(
            """
                type D6<T> = T extends (a: any) => infer U ? U : never;
                type D7<T> = T extends ((a: any) => infer U) ? U : never;
            """
        ) should {
            have(isEmpty())
        }
    }

    @Test
    fun `pristine inferTypesWithExtends1 lines 33 and 34 - a constrained infer in a constructor return`() {
        diagnose(
            """
                type X1<T> =
                    T extends new (...args: any[]) => (infer U extends { a: string }) ? ["string", U] :
                    T extends new (...args: any[]) => (infer U extends { a: number }) ? ["number", U] :
                    never;
            """
        ) should {
            have(none { it.code == 2304 })
            have(isEmpty())
        }
    }

    @Test
    fun `pristine controlFlowInstanceofWithSymbolHasInstance - the abstract new conditional`() {
        // The whole of that fixture's parser cascade, in the three lines round 942
        // isolated it to.
        diagnose(
            """
                type AbstractConstructor = abstract new (...args: any) => any;
                type InstanceOf<T> = T extends (abstract new (...args: any) => infer U) ? U : never;
                declare function isInstanceOf<T extends AbstractConstructor>(x: unknown, c: T): x is InstanceOf<T>;
            """
        ) should {
            have(isEmpty())
        }
    }

    // ── scoped-out shapes, pinned so a later widening has to move them ───────

    @Test
    fun `scoped out - the infer does not RESOLVE through a constructor type`() {
        // A false NEGATIVE held on purpose: the name is now in scope (no TS2304) but
        // the conditional answers `any`, so the deliberate mis-assignment is silent
        // where tsc reports `Type 'K' is not assignable to type 'string'`.
        diagnose(
            """
                class K { x = 1 }
                type D<T> = T extends new () => infer U ? U : never;
                declare const r: D<new () => K>;
                const bad: string = r;
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `scoped out - an infer constraint is not re-read as the enclosing conditional`() {
        // tsc's `tryParseConstraintOfInferType` parses `extends <type>` with conditional
        // types disallowed and ROLLS BACK when the next token is `?` unless it is already
        // in a disallow-conditional context — so `infer U extends number ? 1 : 0` is a
        // CONDITIONAL, not a constrained infer.  We take the constraint unconditionally
        // and cascade; pristine `inferTypesWithExtends1` lines 95 / 103 / 105.
        val d = diagnose(
            """
                type X10<T> = T extends (infer U extends number ? 1 : 0) ? 1 : 0;
            """
        )
        assert(d.any { it.code == 1005 })
    }
}

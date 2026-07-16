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
 * INV.4(c)(iii) batch 5 (round 528): the checkUnresolvedNames family's TYPE
 * emissions migrated onto the check spine — the recursive checkUnresolvedInType
 * walker is DELETED; its dispatch positions MARK type roots
 * (`spineUResMarkTypeRoot`, always strictly before the marked subtree walks)
 * and each type-node kind with own emissions (TypeReference / IndexedAccessType
 * / TypeQuery / FunctionType / ConstructorType / TypeLiteral) self-emits at its
 * enter when `spineUResTypeChecked` classifies its ancestor chain as reached
 * (DESCEND edges per the deleted walker's recursion arms, up to a marked root).
 * The retained expression walker and the JSX helpers are deleted with it —
 * type-literal computed-name expressions become batch-4 expression ROOTs gated
 * on the containing literal being type-checked. All pins verified against the
 * OLD walkers (pre-migration checker via stash) — a pure reach-preserving
 * migration.
 */
class Inv4SpineBatch19Test {

    // ── root positions (the marking dispatch sites) ─────────────────────────

    @Test
    fun `unresolved name in variable type annotation fires TS2304`() {
        diagnose("let a: UndeclaredT1;") should {
            have(any { it.code == 2304 && it.message.contains("'UndeclaredT1'") })
        }
    }

    @Test
    fun `unresolved name in for-header declaration annotation fires TS2304`() {
        diagnose("for (let i: UndeclaredT2 = 0; ; ) {}") should {
            have(any { it.code == 2304 && it.message.contains("'UndeclaredT2'") })
        }
    }

    @Test
    fun `function signature type positions fire TS2304`() {
        diagnose(
            """
            function f<T extends BadConstraint1>(x: BadParam1): BadReturn1 {
                return x;
            }
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'BadConstraint1'") })
            have(any { it.code == 2304 && it.message.contains("'BadParam1'") })
            have(any { it.code == 2304 && it.message.contains("'BadReturn1'") })
        }
    }

    @Test
    fun `type alias body and constraint fire TS2304`() {
        diagnose("type A1<T extends BadC2> = BadBody1;") should {
            have(any { it.code == 2304 && it.message.contains("'BadC2'") })
            have(any { it.code == 2304 && it.message.contains("'BadBody1'") })
        }
    }

    @Test
    fun `as-cast target type fires TS2304`() {
        diagnose("const v = 1 as UndeclaredCast1;") should {
            have(any { it.code == 2304 && it.message.contains("'UndeclaredCast1'") })
        }
    }

    @Test
    fun `call type argument fires TS2304`() {
        diagnose(
            """
            declare function g<T>(): T;
            g<UndeclaredArg1>();
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'UndeclaredArg1'") })
        }
    }

    @Test
    fun `heritage type argument fires TS2304`() {
        diagnose(
            """
            class Base1<T> {}
            class Derived1 extends Base1<UndeclaredH1> {}
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'UndeclaredH1'") })
        }
    }

    @Test
    fun `interface member type positions fire TS2304`() {
        diagnose(
            """
            interface I1 {
                p: BadProp1;
                m(x: BadMParam1): BadMReturn1;
                set s(v: BadSet1);
            }
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'BadProp1'") })
            have(any { it.code == 2304 && it.message.contains("'BadMParam1'") })
            have(any { it.code == 2304 && it.message.contains("'BadMReturn1'") })
            have(any { it.code == 2304 && it.message.contains("'BadSet1'") })
        }
    }

    @Test
    fun `class property type annotation fires TS2304`() {
        diagnose("class C1 { p: UndeclaredCP1; }") should {
            have(any { it.code == 2304 && it.message.contains("'UndeclaredCP1'") })
        }
    }

    // ── descent through type structure (the deleted recursion arms) ─────────

    @Test
    fun `deeply nested type positions are reached`() {
        diagnose("let d1: Array<[string, (u: DeepBad1) => DeepBad2 | null]>;") should {
            have(any { it.code == 2304 && it.message.contains("'DeepBad1'") })
            have(any { it.code == 2304 && it.message.contains("'DeepBad2'") })
        }
    }

    @Test
    fun `indexed access type checks both sides`() {
        diagnose("type IA1 = BadObj1[BadIdx1];") should {
            have(any { it.code == 2304 && it.message.contains("'BadObj1'") })
            have(any { it.code == 2304 && it.message.contains("'BadIdx1'") })
        }
    }

    @Test
    fun `typeof query subject fires TS2304`() {
        diagnose("let q1: typeof undeclaredVal1;") should {
            have(any { it.code == 2304 && it.message.contains("'undeclaredVal1'") })
        }
    }

    @Test
    fun `generic arity error fires in a nested type position`() {
        diagnose(
            """
            interface Box1<T> { v: T; }
            let n1: { inner: Box1 };
            """,
        ) should {
            have(any { it.code == 2314 && it.message.contains("Box1") })
        }
    }

    @Test
    fun `exactly one TS2304 per unresolved type name - no double emission`() {
        diagnose("let single1: UndeclaredOnce1;") should {
            have(count { it.code == 2304 && it.message.contains("'UndeclaredOnce1'") } == 1)
        }
    }

    // ── scope staging (mapped / conditional-infer / fn-type / TL methods) ───

    @Test
    fun `mapped type parameter is in scope in its body`() {
        diagnose("type M1<T> = { [K in keyof T]: K };") should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `mapped type constraint is checked in the outer scope`() {
        // The TP being introduced is NOT in scope inside its own constraint.
        diagnose("type M2 = { [K in K]: number };") should {
            have(any { it.code == 2304 && it.message.contains("'K'") })
        }
    }

    @Test
    fun `infer name scopes into the true branch only`() {
        diagnose("type F1<T> = T extends Array<infer U> ? U : never;") should {
            have(none { it.code == 2304 })
        }
        diagnose("type F2<T> = T extends Array<infer U> ? U : U;") should {
            have(any { it.code == 2304 && it.message.contains("'U'") })
        }
    }

    @Test
    fun `function type parameters are in scope in its return type`() {
        diagnose("let ft1: <T>(x: T) => T;") should {
            have(none { it.code == 2304 })
        }
        diagnose("let ft2: <T extends BadFtC1>(x: T) => T;") should {
            have(any { it.code == 2304 && it.message.contains("'BadFtC1'") })
        }
    }

    @Test
    fun `type literal method scope covers its own type parameters`() {
        diagnose("let tl1: { m<T>(a: T): T; };") should {
            have(none { it.code == 2304 })
        }
        diagnose("let tl2: { m(a: BadTlP1): void; [k: string]: BadTlIdx1; };") should {
            have(any { it.code == 2304 && it.message.contains("'BadTlP1'") })
            have(any { it.code == 2304 && it.message.contains("'BadTlIdx1'") })
        }
    }

    // ── type-literal computed names (the deleted expression-walker entry) ───

    @Test
    fun `type-only keyword as computed name fires TS2693`() {
        diagnose("type TK1 = { [number]: string; a: string; };") should {
            have(any { it.code == 2693 && it.message.contains("'number'") })
        }
    }

    @Test
    fun `pure-type name as single computed member fires TS2690 with mapped-type hint`() {
        diagnose(
            """
            type Key1 = string;
            type TK2 = { [Key1]: number };
            """,
        ) should {
            have(any { it.code == 2690 && it.message.contains("Did you mean to use 'K in Key1'") })
        }
    }

    @Test
    fun `unbound identifier as computed member name fires TS2304`() {
        diagnose("type TK3 = { [unboundKey1]: number; other: string; };") should {
            have(any { it.code == 2304 && it.message.contains("'unboundKey1'") })
        }
    }

    @Test
    fun `arrow body inside a computed member name is expression territory`() {
        // The computed-name EXPRESSION becomes a batch-4 ROOT gated on the
        // literal being type-checked — its subtree (an arrow's expression
        // body) self-emits through the expression edges.
        diagnose("type TK4 = { [(() => unboundInArrow1)()]: number; other: string; };") should {
            have(any { it.code == 2304 && it.message.contains("'unboundInArrow1'") })
        }
    }

    @Test
    fun `negative control - computed name of an UNCHECKED literal stays silent`() {
        // A type literal nested where the family never dispatched — a
        // for-in header annotation is not a checked type position — must
        // not gain emissions from the migration.
        diagnose("for (const k1: { [unmarked1]: number } in {}) {}") should {
            have(none { it.code == 2304 && it.message.contains("'unmarked1'") })
        }
    }

    // ── region rules (suppression + declare-module filter) ──────────────────

    @Test
    fun `declare function signature types are suppressed`() {
        diagnose("declare function df1(x: NeverChecked1): NeverChecked2;") should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `declare module body keeps TS2304 but filters the arity check`() {
        diagnose(
            """
            declare module "m1" {
                let a: UndeclaredInMod1;
                let b: Array;
            }
            """,
        ) should {
            have(any { it.code == 2304 && it.message.contains("'UndeclaredInMod1'") })
            have(none { it.code == 2314 })
        }
    }

    @Test
    fun `TS1099 empty type argument list still fires`() {
        diagnose(
            """
            interface Box2<T = string> { v: T; }
            let e1: Box2<>;
            """,
        ) should {
            have(any { it.code == 1099 })
        }
    }

    @Test
    fun `negative control - fully resolved types stay silent`() {
        diagnose(
            """
            interface Ok1<T> { v: T; }
            type OkAlias1<T extends string> = { [K in keyof T]: Ok1<T>[] };
            class OkC1<U> extends Array<U> {
                p: Ok1<string> | (new () => OkC1<U>);
                m<W>(x: W, y: typeof OkC1): { [k: string]: W } { return { a: x }; }
            }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }
}

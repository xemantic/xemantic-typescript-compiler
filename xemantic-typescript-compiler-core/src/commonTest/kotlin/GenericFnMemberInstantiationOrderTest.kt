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
 * (CHK.102): a generic interface's FUNCTION-TYPED member is not shared between
 * instantiations, and is not FROZEN by whichever instantiation is touched first.
 *
 * ## The defect
 *
 * `interface Box<T> { f: (x: T) => T }` with a `Box<number>` and a `Box<string>` in
 * one file used to answer `(x: number) => number` for BOTH — a false TS2345 on
 * `bs.f("a")`, a lost TS2345 on `bs.f(1)`, and the wrong type wherever `bs.f` is
 * read. The mirror image holds when the `string` instantiation is written first,
 * which is why **every pin here exists in BOTH declaration orders**: a pin written
 * in one order alone is GREEN on the frozen binary, because in that order the
 * instantiation it asks about is the one that won the race.
 *
 * ## The mechanism, measured
 *
 * `resolveGenericPropertyTypeWorker`'s `PropertyDeclaration` arm resolved the
 * annotation through `getTypeFromTypeNode` and then SUBSTITUTED THE RESULT IN PLACE
 * (`substituteOuterTypeArgsInGenericFnObject`, 17.39), on a KDoc precondition that
 * `getTypeFromTypeNode` cannot cache under a type-param scope. That was true when
 * 17.39 was written; INV.5(c) later added a SECOND, context-keyed cache below the
 * bypass (`getTypeFromTypeNodeBypassed` → `state.mappedNodeTypes`) whose key is
 * `(node identity, ns/tpScope/aliasArgs fingerprint)` — identical for two
 * instantiations of one interface, because the scope holds the TARGET's own
 * `Type.TypeParam` both times. A probe printed `rawId=35 before=(x: T) => T` for the
 * first ask and `rawId=35 before=(x: number) => number` for the second: one object,
 * already substituted.
 *
 * The fix is round 465's discipline (`instantiateTypeFnAware` "mints FRESH objects,
 * never mutates") applied to the one member of the family that had kept the in-place
 * form — the object is minted, and a signature's own type parameters are CLONED when
 * their constraint moves rather than reassigned.
 *
 * ## Why these pins and not the grid
 *
 * On the compiler profile the mechanism runs 6,425 times per compile and **46 of its
 * 59 distinct annotation nodes are asked with two or more DIFFERENT type-argument
 * vectors** — i.e. the exposure condition is met constantly on tsc's own sources (it
 * is mostly `lib.es5.d.ts`'s `Array<T>` members) — and the 8-profile grid is still
 * byte-identical across the fix. So the grid is a control here for a measured reason:
 * the frozen types are reached, they simply never decide a diagnostic on that
 * codebase. These pins are the gate.
 *
 * Every expectation below was read from BOTH references (tsgo 7.0.2 and pristine
 * `typescript@6.0.3`), which agree on every row.
 */
class GenericFnMemberInstantiationOrderTest {

    private val box = """
        interface Box<T> {
          f: (x: T) => T;
          m(x: T): T;
          v: T;
        }
        declare const bn: Box<number>;
        declare const bs: Box<string>;
    """.trimIndent()

    // ---- the fn-typed property, ARGUMENT position, both orders ----------------

    @Test
    fun `a fn-typed member's parameter accepts its own type argument - number touched first`() {
        diagnose(
            box + """

            bn.f(1);
            bs.f("a");
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a fn-typed member's parameter accepts its own type argument - string touched first`() {
        diagnose(
            box + """

            bs.f("a");
            bn.f(1);
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a fn-typed member's parameter rejects the other instantiation's argument - number touched first`() {
        diagnose(
            box + """

            bn.f(1);
            bs.f(1);
            """
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message.contains("Argument of type 'number' is not assignable to parameter of type 'string'")
            })
        }
    }

    @Test
    fun `a fn-typed member's parameter rejects the other instantiation's argument - string touched first`() {
        diagnose(
            box + """

            bs.f("a");
            bn.f("a");
            """
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message.contains("Argument of type 'string' is not assignable to parameter of type 'number'")
            })
        }
    }

    // ---- the fn-typed property, RETURN position, both orders ------------------

    @Test
    fun `a fn-typed member's return type is its own type argument - number touched first`() {
        diagnose(
            box + """

            const a: number = bn.f(1);
            const b: number = bs.f("a");
            """
        ) should {
            // the FIRST is fine; the SECOND must be the RETURN mismatch, at the
            // declaration, never an argument complaint about the call
            have(any {
                it.code == 2322 && it.message.contains("Type 'string' is not assignable to type 'number'")
            })
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a fn-typed member's return type is its own type argument - string touched first`() {
        diagnose(
            box + """

            const a: string = bs.f("a");
            const b: string = bn.f(1);
            """
        ) should {
            have(any {
                it.code == 2322 && it.message.contains("Type 'number' is not assignable to type 'string'")
            })
            have(none { it.code == 2345 })
        }
    }

    // ---- the fn-typed property, plain READ, both orders -----------------------

    @Test
    fun `a fn-typed member reads as its own instantiation - number touched first`() {
        diagnose(
            box + """

            const a: number = bn.f(1);
            const r: number = bs.f;
            """
        ) should {
            have(any {
                it.code == 2322 && it.message.contains("(x: string) => string")
            })
        }
    }

    @Test
    fun `a fn-typed member reads as its own instantiation - string touched first`() {
        diagnose(
            box + """

            const a: string = bs.f("a");
            const r: number = bn.f;
            """
        ) should {
            have(any {
                it.code == 2322 && it.message.contains("(x: number) => number")
            })
        }
    }

    // ---- controls the item names: a METHOD and a plain member ------------------

    @Test
    fun `control - a METHOD member was never frozen - number touched first`() {
        diagnose(
            box + """

            bn.m(1);
            bs.m("a");
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `control - a METHOD member was never frozen - string touched first`() {
        diagnose(
            box + """

            bs.m("a");
            bn.m(1);
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `control - a plain T member was never frozen - both orders`() {
        diagnose(
            box + """

            const a: number = bn.v;
            const b: string = bs.v;
            const c: string = bs.v;
            const d: number = bn.v;
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `control - a lib Map is unaffected by the two instantiations`() {
        diagnose(
            """
            declare const mn: Map<string, number>;
            declare const ms: Map<string, string>;
            mn.set("k", 1);
            ms.set("k", "a");
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    // ---- a CONSTRUCT-signature member, both orders ----------------------------

    @Test
    fun `a construct-signature member is not frozen - number touched first`() {
        diagnose(
            """
            interface Ctor<T> { c: new (x: T) => T; }
            declare const cn: Ctor<number>;
            declare const cs: Ctor<string>;
            new cn.c(1);
            new cs.c(1);
            """
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message.contains("Argument of type 'number' is not assignable to parameter of type 'string'")
            })
        }
    }

    @Test
    fun `a construct-signature member is not frozen - string touched first`() {
        diagnose(
            """
            interface Ctor<T> { c: new (x: T) => T; }
            declare const cn: Ctor<number>;
            declare const cs: Ctor<string>;
            new cs.c("a");
            new cn.c("a");
            """
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message.contains("Argument of type 'string' is not assignable to parameter of type 'number'")
            })
        }
    }

    // ---- the INNER GENERIC signature's constraint, both orders ----------------
    //
    // The second freeze of the same family, and it survives a fix to the first:
    // `substituteOuterTypeArgsInSignature` used to reassign `U.constraint` on the
    // signature's own `Type.TypeParam`, which is shared with the cached object.

    @Test
    fun `an inner generic signature's constraint is not frozen - number touched first`() {
        diagnose(
            """
            interface Gen<T> { g: <U extends T>(x: U) => U; }
            declare const gn: Gen<number>;
            declare const gs: Gen<string>;
            const a: number = gn.g(1);
            const b: number = gs.g("a");
            """
        ) should {
            have(any {
                it.code == 2322 && it.message.contains("Type 'string' is not assignable to type 'number'")
            })
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `an inner generic signature's constraint is not frozen - string touched first`() {
        diagnose(
            """
            interface Gen<T> { g: <U extends T>(x: U) => U; }
            declare const gn: Gen<number>;
            declare const gs: Gen<string>;
            const a: string = gs.g("a");
            const b: string = gn.g(1);
            """
        ) should {
            have(any {
                it.code == 2322 && it.message.contains("Type 'number' is not assignable to type 'string'")
            })
            have(none { it.code == 2345 })
        }
    }

    // ---- a METHOD's fn-typed PARAMETER, both orders ---------------------------
    //
    // The SECOND call site of the same helper (the MethodDeclaration arm's
    // `rawParamType` branch, B81.1d). Measured on the parent binary as TWO false
    // TS2322 inside the callback passed to the second instantiation.

    @Test
    fun `a method's fn-typed parameter is not frozen - number touched first`() {
        diagnose(
            """
            interface Cb<T> { m(cb: (x: T) => T): void; }
            declare const cn: Cb<number>;
            declare const cs: Cb<string>;
            cn.m((x) => { const q: number = x; return 1; });
            cs.m((x) => { const q: string = x; return "a"; });
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a method's fn-typed parameter is not frozen - string touched first`() {
        diagnose(
            """
            interface Cb<T> { m(cb: (x: T) => T): void; }
            declare const cn: Cb<number>;
            declare const cs: Cb<string>;
            cs.m((x) => { const q: string = x; return "a"; });
            cn.m((x) => { const q: number = x; return 1; });
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a method's fn-typed parameter still reports a real mismatch`() {
        diagnose(
            """
            interface Cb<T> { m(cb: (x: T) => T): void; }
            declare const cn: Cb<number>;
            declare const cs: Cb<string>;
            cn.m((x) => { const q: number = x; return 1; });
            cs.m((x) => { const bad: number = x; return "a"; });
            """
        ) should {
            have(any {
                it.code == 2322 && it.message.contains("Type 'string' is not assignable to type 'number'")
            })
        }
    }

    // ---- THREE instantiations: the freeze is first-touch, not last-touch ------

    @Test
    fun `three instantiations each answer their own type argument`() {
        diagnose(
            """
            interface Box<T> { f: (x: T) => T; }
            declare const bn: Box<number>;
            declare const bs: Box<string>;
            declare const bb: Box<boolean>;
            bn.f(1);
            bs.f("a");
            bb.f(true);
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `three instantiations each reject the others' arguments`() {
        diagnose(
            """
            interface Box<T> { f: (x: T) => T; }
            declare const bn: Box<number>;
            declare const bs: Box<string>;
            declare const bb: Box<boolean>;
            bn.f(1);
            bs.f(1);
            bb.f(1);
            """
        ) should {
            have(any {
                it.code == 2345 &&
                    it.message.contains("Argument of type 'number' is not assignable to parameter of type 'string'")
            })
            have(any {
                it.code == 2345 &&
                    it.message.contains("Argument of type 'number' is not assignable to parameter of type 'boolean'")
            })
        }
    }

    // ---- the same instantiation twice must NOT report -------------------------

    @Test
    fun `negative control - two references to the SAME instantiation agree`() {
        diagnose(
            """
            interface Box<T> { f: (x: T) => T; }
            declare const b1: Box<number>;
            declare const b2: Box<number>;
            b1.f(1);
            b2.f(1);
            const r: number = b2.f(1);
            """
        ) should {
            have(none { it.code == 2345 })
            have(none { it.code == 2322 })
        }
    }
}

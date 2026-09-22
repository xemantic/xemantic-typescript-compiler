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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.140): a class's OWN type parameters are in scope inside its INSTANCE members'
 * bodies.
 *
 * ## The defect, and why it was mis-attributed for several rounds
 *
 * `ctaFnBodyFrame` accumulated the enclosing class's type parameters into
 * `fnTpDecls` — the AST map, which is what answers TS2302 — but never into
 * `fnTpScope`, the map that actually TYPES a name. The asymmetry sat on adjacent
 * lines. So in `class C<P> { m(x: P) { ... } }` the body resolved `P` to whatever an
 * OUTER scope had of that name, and to `any` when it had none.
 *
 * That made it look like a member-table or instantiation-cache defect, and (P18.166)
 * recorded it as "round 761's globally-`any` cached type for a type-parameter-typed
 * member". It is neither. The fixture that settles it contains no member access, no
 * element access and no generic instantiation at all — only a name:
 *
 * ```
 * type Q = boolean;
 * class Hg1<Q> { use() { const x: Q = null!; const probe: number = { v: x }; } }
 * ```
 *
 * We answered `boolean` — the OUTER alias — where tsgo answers `Q`. A WRONG type,
 * not a permissive one, and silent in every channel this repo gates on. The `any`
 * everyone had been chasing is only what this degrades to when no outer name exists.
 *
 * The behavioural clincher is one parameter answering two different types in ONE
 * method body: `take(x)` resolved `P` through the ccet reader while `{ v: x }` read
 * `any` through the cta one. No cache can do that; a FRAME can.
 *
 * ## Why these pins and not the grid
 *
 * A census of the eight dashboard profiles counts **84 lines of generic-class body in
 * the whole compiler profile** (3 generic classes in 78 files); only `harness` (3,521)
 * and `server` (3,268) have real reach. So `8x added=0 removed=0` is a CONTROL on six
 * arms and a gate on two, and saying otherwise would be the error this codebase has
 * recorded repeatedly. The corpus IS a real gate here — 281 of its case files declare
 * a generic class whose body references its own type-parameter name — and it reads 0
 * of 8,725 across the change.
 *
 * The DECLARATION-ORDER axis is measured DEAD for this mechanism (unlike (CHK.102)'s,
 * where both orders are load-bearing): two enclosing classes with different type
 * parameters answer independently, because the frame is rebuilt per member body and
 * nothing is cached across them. Hence no both-orders pairs below.
 *
 * STATIC members are deliberately NOT excluded from the scope. The mirror of
 * `ccetEnterClassDeclaration`'s B74.5 exclusion was built, ablated and removed: no arm
 * could discriminate it, and its COST was a static body's `P` degrading to `any` while
 * tsgo reports TS2302 and types the reference as `P`.
 *
 * Every expectation was read from tsgo 7.0.2 before it was written here.
 */
class ClassTypeParamBodyScopeTest {

    private fun readType(d: List<Diagnostic>): String =
        d.single { it.code == 2322 }.message
            .substringAfter("Type '").substringBefore("' is not assignable")

    @Test
    fun `a class type parameter shadows an outer type alias of the same name`() {
        val d = diagnose(
            """
            type Q = boolean;
            class Hg1<Q> { use() { const x: Q = null!; const probe: number = { v: x }; } }
            """.trimIndent()
        )
        assert(readType(d) == "{ v: Q; }")
    }

    @Test
    fun `a class type parameter resolves in a method body rather than degrading to any`() {
        val d = diagnose(
            """
            class Hf1<P> { use() { const x: P = null!; const probe: number = { v: x }; } }
            """.trimIndent()
        )
        assert(readType(d) == "{ v: P; }")
    }

    @Test
    fun `a parameter typed by the class type parameter resolves in the body`() {
        val d = diagnose(
            """
            class Hf3<P> { use(x: P) { const probe: number = { v: x }; } }
            """.trimIndent()
        )
        assert(readType(d) == "{ v: P; }")
    }

    @Test
    fun `one parameter answers the same type through both readers`() {
        // The two-answers-one-parameter asymmetry that located the frame: the call
        // ARGUMENT is read by the ccet path and the object literal by the cta path.
        val d = diagnose(
            """
            declare function take(s: string): void;
            class Hh1<P> {
              m(x: P) {
                take(x);
                const probe: number = { v: x };
              }
            }
            """.trimIndent()
        )
        assert(d.single { it.code == 2345 }.message ==
            "Argument of type 'P' is not assignable to parameter of type 'string'.")
        assert(readType(d) == "{ v: P; }")
    }

    @Test
    fun `a method's own type parameter shadows the class's of the same name`() {
        // Class TPs are folded in FIRST precisely so this overwrite happens.
        val d = diagnose(
            """
            class Hm1<P> { m<P>(x: P) { const probe: number = { v: x }; } }
            """.trimIndent()
        )
        assert(readType(d) == "{ v: P; }")
    }

    @Test
    fun `a static member referencing a class type parameter still reports TS2302`() {
        // TS2302 is decided by `fnTpDecls` (the AST map), NOT by the type-param scope,
        // which is why it fires whether or not the scope reaches a static body.
        diagnose(
            """
            class Hh4<P> { static s(x: P) { return x; } }
            """.trimIndent()
        ) should {
            have(any { it.code == 2302 })
        }
    }

    @Test
    fun `a static body reports TS2302 and still types the reference as the parameter`() {
        // Both rows, as tsgo 7.0.2 emits them. This pin exists because an
        // `inInstanceMember` gate excluding statics was built and then REMOVED: no
        // ablation arm could discriminate it (TS2302 fires either way), and measuring
        // what it COST showed it degraded this second row to `{ v: any; }`. The guard
        // was not merely unpinned, it was lossy — (P18.165)'s lesson, one reader over.
        val d = diagnose(
            """
            class Hs1<P> { static s() { const x: P = null!; const probe: number = { v: x }; } }
            """.trimIndent()
        )
        assert(d.any { it.code == 2302 })
        assert(readType(d) == "{ v: P; }")
    }

    @Test
    fun `negative control - a generic function's own type parameter was always in scope`() {
        // This shape was CORRECT before the change and must not move: it is what made
        // the defect read as class-specific rather than as a general scope failure.
        val d = diagnose(
            """
            function hf2<P>() { const x: P = null!; const probe: number = { v: x }; }
            """.trimIndent()
        )
        assert(readType(d) == "{ v: P; }")
    }

    @Test
    fun `a class type parameter survives into a nested arrow in a method body`() {
        // The nested function opens its own frame and inherits the member's scope, so
        // the fix reaches further than its sizing predicted. This pin was first written
        // asserting `{ v: any; }` on a CLI probe taken after the ablation restored the
        // SOURCE but before it rebuilt — i.e. against the BEFORE binary still sitting in
        // the class dir. The pin caught it; a `md5sum` of the class under test is what
        // settles such a reading, never the probe alone.
        val d = diagnose(
            """
            class Hn1<P> { m(x: P) { const f = () => { const probe: number = { v: x }; }; f(); } }
            """.trimIndent()
        )
        assert(readType(d) == "{ v: P; }")
    }

    @Test
    fun `a property typed by the class type parameter resolves through this`() {
        // `this.p` where `p: P`. Round 761/783's standing rule still holds — do NOT
        // reason about generic member types from `type.members`/`symbolTypes` — and
        // nothing here does: the member's ANNOTATION is resolved under the frame's
        // scope, so putting the name in scope is sufficient and the member table is
        // untouched.
        val d = diagnose(
            """
            class Ha1<P> { p: P = null!; m() { const probe: number = { v: this.p }; } }
            """.trimIndent()
        )
        assert(readType(d) == "{ v: P; }")
    }
}

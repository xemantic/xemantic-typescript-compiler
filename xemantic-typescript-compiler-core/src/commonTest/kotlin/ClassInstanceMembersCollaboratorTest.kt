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
 * (INV.0) (P18.245) The CLASS-INSTANCE MISSING-MEMBER family as it lives in
 * `ClassInstanceMembers` after the extraction: the conservative TS2339 for a member absent
 * from a class's whole resolvable instance side, its TS2551 chain suggestion, the TS2576
 * static-member emitter, and the chain walks they share — (CHK.182)'s merged-interface
 * reader, the parameter-property and computed / late-bound member names, the base-class
 * recursion (including a namespace-local base) and the instance-and-static collision that
 * keeps `this.p` legal. Each is reached from a caller that STAYED on `Checker`.
 *
 * Every expectation is tsgo 7.0.2's row (cells under `build/bench/p18245-agent/matrix/`,
 * 1-based column); all fifteen cells agree on both arms of the move.
 */
class ClassInstanceMembersCollaboratorTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }.sorted()

    @Test
    fun `a member absent from a plain class is TS2339`() {
        val r = rows(
            """
            class C { a = 1 }
            declare const c: C;
            c.b;
            """
        )
        assert(r == listOf("3:3 TS2339 Property 'b' does not exist on type 'C'."))
    }

    @Test
    fun `an inherited member resolves through the extends chain`() {
        val r = rows(
            """
            class A { x = 1 }
            class B extends A {}
            declare const b: B;
            b.x;
            b.y;
            """
        )
        assert(r == listOf("5:3 TS2339 Property 'y' does not exist on type 'B'."))
    }

    @Test
    fun `a misspelt inherited method is TS2551 naming the base member`() {
        val d = diagnose(
            """
            class A { method2() {} }
            class B extends A {}
            declare const b: B;
            b.method1();
            """
        )
        val row = d.single()
        val related = row.relatedInformation.map { "${it.line}:${it.character} ${it.code} ${it.message}" }
        assert(row.code == 2551 && row.line == 4 && row.character == 3)
        assert(row.message == "Property 'method1' does not exist on type 'B'. Did you mean 'method2'?")
        assert(related == listOf("1:11 2728 'method2' is declared here."))
    }

    @Test
    fun `a misspelt this access in a derived class is TS2551`() {
        val r = rows(
            """
            class A { methodX() {} }
            class B extends A { f() { this.methodY(); } }
            """
        )
        assert(r == listOf("2:32 TS2551 Property 'methodY' does not exist on type 'B'. Did you mean 'methodX'?"))
    }

    @Test
    fun `a static member read off an instance is TS2576`() {
        val r = rows(
            """
            class C { static s = 1 }
            declare const c: C;
            c.s;
            """
        )
        assert(r == listOf("3:3 TS2576 Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?"))
    }

    @Test
    fun `an inherited static member read off an instance is TS2576 naming the derived class`() {
        val r = rows(
            """
            class A { static s = 1 }
            class B extends A {}
            declare const b: B;
            b.s;
            """
        )
        assert(r == listOf("4:3 TS2576 Property 's' does not exist on type 'B'. Did you mean to access the static member 'B.s' instead?"))
    }

    @Test
    fun `an element access to a static member is TS2576 with the bracket suggestion`() {
        val r = rows(
            """
            class C { static s = 1 }
            declare const c: C;
            c["s"];
            """
        )
        assert(r == listOf("3:1 TS2576 Property 's' does not exist on type 'C'. Did you mean to access the static member 'C[\"s\"]' instead?"))
    }

    @Test
    fun `a static method called through this is TS2576`() {
        val r = rows(
            """
            class C { static s() {} f() { this.s(); } }
            """
        )
        assert(r == listOf("1:36 TS2576 Property 's' does not exist on type 'C'. Did you mean to access the static member 'C.s' instead?"))
    }

    @Test
    fun `negative control - an instance member shadowing a static one keeps this access legal`() {
        val r = rows(
            """
            class C { static p = 1; p = 2; f() { return this.p; } }
            """
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a merged interface member is a member of the class`() {
        val r = rows(
            """
            interface D { m(): void }
            class D {}
            declare const d: D;
            d.m();
            d.z;
            """
        )
        assert(r == listOf("5:3 TS2339 Property 'z' does not exist on type 'D'."))
    }

    @Test
    fun `a missing member read through this is TS2339`() {
        val r = rows(
            """
            class C { f() { this.nope; } }
            """
        )
        assert(r == listOf("1:22 TS2339 Property 'nope' does not exist on type 'C'."))
    }

    @Test
    fun `a computed literal member name declares the member`() {
        val r = rows(
            """
            class C { ["k"] = 1 }
            declare const c: C;
            c.k;
            c.q;
            """
        )
        assert(r == listOf("4:3 TS2339 Property 'q' does not exist on type 'C'."))
    }

    @Test
    fun `a late-bound member name declares the member`() {
        val r = rows(
            """
            const K = "p";
            class C { [K]: number = 1 }
            declare const c: C;
            c.p;
            c.r;
            """
        )
        assert(r == listOf("5:3 TS2339 Property 'r' does not exist on type 'C'."))
    }

    @Test
    fun `a parameter property is an instance member`() {
        val r = rows(
            """
            class C { constructor(public p: number) {} }
            declare const c: C;
            c.p;
            c.q;
            """
        )
        assert(r == listOf("4:3 TS2339 Property 'q' does not exist on type 'C'."))
    }

    @Test
    fun `a namespace-local base resolves through the namespace exports`() {
        val r = rows(
            """
            namespace N {
                export class A { x = 1 }
                export class B extends A { f() { return this.x + this.y; } }
            }
            """
        )
        assert(r == listOf("3:59 TS2339 Property 'y' does not exist on type 'B'."))
    }
}

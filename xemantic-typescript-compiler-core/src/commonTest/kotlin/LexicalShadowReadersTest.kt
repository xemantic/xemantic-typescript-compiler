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
 * (P18.290) (LIBS.3) SHADOW + F12 — three name-based readers that ignored a lexical
 * shadow: the TS2511 abstract-`new` walker (a parameter / local named like a file-level
 * abstract class), the expando TS2339 walker (a NESTED block's `const`, a `for`-of / `catch`
 * variable named like a file-level function), and the TS2301 / TS2663 constructor-parameter
 * walker (a `let` / `const` in a class-property initializer's function body). Every
 * expectation is `tools/tsgo-7.0.2/lib/tsc`'s row for the same fixture (tsgo coordinates).
 */
class LexicalShadowReadersTest {

    private fun rows(source: String): List<String> =
        diagnose(source).map { "${it.line}:${it.character} TS${it.code} ${it.message}" }

    @Test
    fun `a parameter or local named like an abstract class is what new constructs`() {
        assert(rows("""
            export abstract class Cls { constructor(..._a: any[]) {} }
            type SC<T> = { new (x: number): T }
            export function p1(Cls: SC<{ a: 1 }>) { return [1].map((v) => new Cls(v)) }
            export function p2() { return new Cls(1) }
            export function p3(xs: number[]) { return xs.map((v) => new Cls(v)) }
            export function p4(Cls: SC<{ a: 1 }>) { return new Cls(1) }
            export function p5() { const Cls = class { constructor(_x: number) {} }; return new Cls(1) }
            export function p6(o: { Cls: SC<{ a: 1 }> }) { const { Cls } = o; return new Cls(1) }
            export function p7(Cls: SC<{ a: 1 }> | undefined) { return Cls && [1].map((v) => new Cls(v)) }
        """) == listOf(
            "4:31 TS2511 Cannot create an instance of an abstract class.",
            "5:57 TS2511 Cannot create an instance of an abstract class.",
        ))
    }

    @Test
    fun `a nested block's declaration shadows a file-level function for a member read`() {
        assert(rows("""
            function node(): void {}
            export function n1(c: any[]) { for (const node of c) { node.x } }
            export function n3(c: any) { switch (c) { case 1: { const node = c; node.x } } }
            export function n4(c: any) { { const node = c; node.x } node.x }
            export function n5(path: string[]) {
              const go = () => { let curr: any = {}; while (path.length) { const node = curr[path[0]!]; node._errors.push(1) } }
              return go
            }
        """) == listOf(
            "4:62 TS2339 Property 'x' does not exist on type '() => void'.",
        ))
    }

    @Test
    fun `a catch variable shadows a file-level function`() {
        assert(rows("""
            function node(): void {}
            export function n2(c: any) { try {} catch (node) { node.x } }
        """) == listOf(
            "2:52 TS18046 'node' is of type 'unknown'.",
        ))
    }

    @Test
    fun `a let in a class-property arrow shadows the constructor parameter`() {
        assert(rows("""
            export class C {
              constructor(url: string) { }
              fetch = () => {
                let url = "a"
                url = url + "/"
                return url
              }
              e = function () { const url = 2; return url }
              f = () => { if (this) { const url = 1; return url } return 0 }
            }
        """).isEmpty())
    }

    @Test
    fun `a let in a class-property arrow shadows a parameter property`() {
        assert(rows("""
            export class C {
              constructor(private url: string) {}
              fetch = () => {
                let url = this.url;
                url = url + "/";
                return url;
              };
            }
        """).isEmpty())
    }

    @Test
    fun `control - an unshadowed reference to a parameter property still reports`() {
        assert(rows("""
            export class C2 {
              constructor(private url: string) {}
              b = () => url
              d = () => { { const url = 1 } return url }
            }
        """) == listOf(
            "3:13 TS2663 Cannot find name 'url'. Did you mean the instance member 'this.url'?",
            "4:40 TS2663 Cannot find name 'url'. Did you mean the instance member 'this.url'?",
        ))
    }
}

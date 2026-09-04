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

package com.xemantic.typescript.compiler.externals

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.SourceFileEntry
import kotlin.test.Test

/**
 * (EXT.24) THE CROSS-MODULE HERITAGE GATE: a per-module SET compiled
 * TOGETHER, as metadata AND as Kotlin/JS, over shapes whose whole point is
 * that a supertype crosses a Kotlin package boundary.
 *
 * A cross-package supertype is the one thing no single generation can grade
 * for itself: Kotlin's `override`, `open` and heritage-clash rules are
 * decided over the whole chain, and a chain that leaves the file resolves
 * only against another file of the SAME compilation. So the receipt is a
 * compile of the whole set — the `@types/node` measurement (51 modules, 179
 * heritage refusals turned into supertypes, 0 errors in both compilers) is
 * the probe's, and these are the shapes it distils, small enough to live in
 * the suite.
 *
 * Every case is red without (EXT.24): admitting a cross-module base with no
 * model of the owning module measured **184 `hides member of supertype` plus
 * 27 `inherits conflicting members`** on `@types/node`, and the `open`
 * attribution alone is **115 `is final and cannot be overridden`**.
 */
class KotlinExternalsPerModuleGateTest {

    private fun set(source: String, modules: List<String>): Map<String, KotlinExternals> =
        generateKotlinExternalsPerModule(
            listOf(SourceFileEntry("/lib/index.d.ts", source.trimIndent())),
            modules.map { ModuleWiring(it, "/lib/index.d.ts", "node") },
        )

    /** The set compiled as ONE metadata compilation and, where the klib is present, as ONE Kotlin/JS one. */
    private fun compilesTogether(generated: Map<String, KotlinExternals>) {
        val safe = generated.map { (name, result) -> name.replace(Regex("[^A-Za-z0-9]"), "_") to result }
        val metadata = compileCheckAll(safe.map { (name, result) -> name to result.compileCheckSource })
        metadata.errors.forEach { println("  METADATA $it") }
        assert(metadata.errors.isEmpty())
        val stdlib = JsStdlib.locate()
        if (stdlib == null) {
            println("  Kotlin/JS stdlib klib not found - JS arm skipped")
            return
        }
        val js = jsCompileCheckAll(safe.map { (name, result) -> name to result.kotlin }, stdlib)
        js.errors.forEach { println("  JS $it") }
        assert(js.errors.isEmpty())
    }

    @Test
    fun `a class extending another module's class compiles as a set`() {
        val generated = set(
            """
                declare module "alpha" {
                    export class Socket { s: number; }
                    export class Engine {
                        run(s: Socket): void;
                        stop(): void;
                    }
                }
                declare module "beta" {
                    import * as alpha from "alpha";
                    export class Runner extends alpha.Engine {
                        run(s: alpha.Socket): void;
                    }
                }
            """,
            listOf("alpha", "beta"),
        )
        assert(generated.getValue("beta").kotlin.contains("class Runner : node.alpha.Engine"))
        assert(generated.getValue("alpha").kotlin.contains("public open fun run(s: Socket): Unit"))
        compilesTogether(generated)
    }

    @Test
    fun `a chain of three modules compiles as a set`() {
        val generated = set(
            """
                declare module "alpha" {
                    export interface Payload { id: number; }
                    export class Root { seed(p: Payload): void; }
                }
                declare module "beta" {
                    import * as alpha from "alpha";
                    export class Base extends alpha.Root {
                        seed(p: alpha.Payload): void;
                        take(p: alpha.Payload): void;
                    }
                }
                declare module "gamma" {
                    import * as beta from "beta";
                    import * as alpha from "alpha";
                    export class Derived extends beta.Base {
                        take(p: alpha.Payload): void;
                    }
                }
            """,
            listOf("alpha", "beta", "gamma"),
        )
        assert(generated.getValue("gamma").kotlin.contains("class Derived : node.beta.Base"))
        compilesTogether(generated)
    }

    @Test
    fun `a class implementing another module's interface compiles as a set`() {
        val generated = set(
            """
                declare module "alpha" {
                    export interface Sink { write(chunk: string): boolean; }
                }
                declare module "beta" {
                    import * as alpha from "alpha";
                    export class Writer implements alpha.Sink {
                        write(chunk: string): boolean;
                    }
                }
            """,
            listOf("alpha", "beta"),
        )
        assert(generated.getValue("beta").kotlin.contains("class Writer : node.alpha.Sink"))
        compilesTogether(generated)
    }

    @Test
    fun `a package nested under another package's compiles as a set`() {
        val generated = set(
            """
                declare module "alpha" {
                    export namespace Group {
                        interface Item { id: number; }
                        class Base { take(i: Item): void; }
                    }
                }
                declare module "alpha/sub" {
                    import * as alpha from "alpha";
                    export class Derived extends alpha.Group.Base {
                        take(i: alpha.Group.Item): void;
                    }
                }
            """,
            listOf("alpha", "alpha/sub"),
        )
        assert(generated.getValue("alpha/sub").kotlin.contains("take(i: node.alpha.Group.Item)"))
        compilesTogether(generated)
    }

    @Test
    fun `a module whose package nests under the root does not break the set`() {
        val generated = set(
            """
                declare module "node:thing" {
                    export interface Thing { t: number; }
                }
                declare module "alpha" {
                    export class Engine { run(): void; }
                }
                declare module "beta" {
                    import * as alpha from "alpha";
                    export class Runner extends alpha.Engine { run(): void; }
                }
            """,
            listOf("node:thing", "alpha", "beta"),
        )
        assert(generated.getValue("beta").kotlin.contains("class Runner : node.alpha.Engine"))
        compilesTogether(generated)
    }

    @Test
    fun `a generic base in another module compiles as a set`() {
        val generated = set(
            """
                declare module "alpha" {
                    export class Box<T> {
                        put(value: T): void;
                        get(): T;
                    }
                }
                declare module "beta" {
                    import * as alpha from "alpha";
                    export class Strings extends alpha.Box<string> {
                        put(value: string): void;
                    }
                }
            """,
            listOf("alpha", "beta"),
        )
        assert(generated.getValue("beta").kotlin.contains("class Strings : node.alpha.Box<String>"))
        compilesTogether(generated)
    }

}

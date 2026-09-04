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
 * (EXT.21b) Pins for ONE GENERATION PER DECLARING MODULE — the SELECTION
 * (which `declare module "m"` blocks a wiring renders at its top level) and
 * the cross-module SPELLING (a declaration another module owns, named fully
 * qualified into that module's own Kotlin package).
 *
 * Exact full-text renders, one shape per pin, each red by ablation of the
 * rule it names; the rules are in [ExternalsCollector]'s `selectedModules`,
 * `moduleOwnedTypeNames`, `rendersHere` and `foreignSpelling` KDoc.
 *
 * The DEGENERATE case — a program with no ambient module block, or a wiring
 * naming a module none of them declares — is the last two pins: nothing is
 * selected, so every earlier rung's output stands unchanged.
 */
class KotlinExternalsPerModuleTest {

    private fun generate(
        wiring: ModuleWiring,
        source: String,
        fileName: String = "/lib/index.d.ts",
    ): String = generateKotlinExternals(
        listOf(SourceFileEntry(fileName, source.trimIndent())),
        module = wiring,
    ).kotlin

    /** Two modules, the second naming the first's types and extending its class. */
    private val twoModules = """
        declare module "alpha" {
            export interface Widget { id: number; }
            export class Engine { run(): void; }
        }
        declare module "beta" {
            import * as alpha from "alpha";
            export interface Holder { widget: alpha.Widget; }
            export class Runner extends alpha.Engine { go(): void; }
        }
    """

    @Test
    fun `a wiring renders its own module and names another module's declaration fully qualified`() {
        assert(
            generate(ModuleWiring("beta", "/lib/index.d.ts", "node"), twoModules) == """
                @file:JsModule("beta")

                package node.beta

                /* xtsc: module "beta" - the package's own module; members rendered at top level */

                public external interface Holder {
                    public var widget: node.alpha.Widget
                }

                public open external class Runner {
                    /* xtsc: skipped heritage clause extends alpha.Engine - Engine is declared by the module "alpha" - a supertype in another generated package carries no Kotlin override computation here */
                    public fun go(): Unit
                }

            """.trimIndent()
        )
    }

    @Test
    fun `the same files wired to the other module render that module instead`() {
        assert(
            generate(ModuleWiring("alpha", "/lib/index.d.ts", "node"), twoModules) == """
                @file:JsModule("alpha")

                package node.alpha

                /* xtsc: module "alpha" - the package's own module; members rendered at top level */

                public external interface Widget {
                    public var id: Double
                }

                public open external class Engine {
                    public fun run(): Unit
                }

            """.trimIndent()
        )
    }

    @Test
    fun `without a package root the other module's package is its bare specifier`() {
        assert(
            generate(ModuleWiring("beta", "/lib/index.d.ts"), twoModules)
                .contains("public var widget: alpha.Widget")
        )
    }

    @Test
    fun `a declaration of this generation keeps its bare name where another module's same-named one is qualified`() {
        assert(
            generate(
                ModuleWiring("beta", "/lib/index.d.ts", "node"),
                """
                    declare module "alpha" {
                        export interface Widget { id: number; }
                    }
                    declare module "beta" {
                        import * as a from "alpha";
                        export interface Widget { own: string; }
                        export interface Holder { mine: Widget; theirs: a.Widget; }
                    }
                """,
            ) == """
                @file:JsModule("beta")

                package node.beta

                /* xtsc: module "beta" - the package's own module; members rendered at top level */

                public external interface Widget {
                    public var own: String
                }

                public external interface Holder {
                    public var mine: Widget
                    public var theirs: node.alpha.Widget
                }

            """.trimIndent()
        )
    }

    @Test
    fun `a node prefixed twin selects the module its block re-exports`() {
        assert(
            generate(
                ModuleWiring("node:alpha", "/lib/index.d.ts", "node"),
                """
                    declare module "alpha" {
                        export interface Widget { id: number; }
                    }
                    declare module "node:alpha" {
                        export * from "alpha";
                    }
                    declare module "gamma" {
                        export interface G { w: number; }
                    }
                """,
            ) == """
                @file:JsModule("node:alpha")

                package node.node.alpha

                /* xtsc: module "alpha" - re-exported by "node:alpha", which this generation renders; members rendered at top level */

                public external interface Widget {
                    public var id: Double
                }

                /* xtsc: module "node:alpha" - the package's own module; members rendered at top level */

                /* xtsc: skipped re-export * from 'alpha' inside module "node:alpha" - the module's own surface, rendered at top level */

            """.trimIndent()
        )
    }

    @Test
    fun `an export equals of a require alias selects the aliased module`() {
        assert(
            generate(
                ModuleWiring("outer", "/lib/index.d.ts", "node"),
                """
                    declare module "inner" {
                        export interface Widget { id: number; }
                    }
                    declare module "outer" {
                        import inner = require("inner");
                        export = inner;
                    }
                """,
            ) == """
                @file:JsModule("outer")

                package node.outer

                /* xtsc: module "inner" - re-exported by "outer", which this generation renders; members rendered at top level */

                public external interface Widget {
                    public var id: Double
                }

                /* xtsc: module "outer" - the package's own module; members rendered at top level */

                /* xtsc: skipped export = inner inside module "outer" - the module's own surface, rendered at top level */

            """.trimIndent()
        )
    }

    @Test
    fun `a module's own type shadows a same-named global one`() {
        assert(
            generate(
                ModuleWiring("beta", "/lib/index.d.ts", "node"),
                """
                    declare namespace Globals {
                        interface Widget { g: number; }
                    }
                    declare module "beta" {
                        export interface Widget { b: number; }
                        export interface Use { w: Widget; }
                    }
                """,
            ) == """
                @file:JsModule("beta")

                package node.beta

                /* xtsc: namespace Globals - not exported by the package entry; members rendered at top level */

                /* xtsc: module "beta" - the package's own module; members rendered at top level */

                public external interface Widget {
                    public var b: Double
                }

                public external interface Use {
                    public var w: Widget
                }

            """.trimIndent()
        )
    }

    @Test
    fun `negative control - a global type the module does not shadow renders beside it`() {
        assert(
            generate(
                ModuleWiring("beta", "/lib/index.d.ts", "node"),
                """
                    declare namespace Globals {
                        interface Gadget { g: number; }
                    }
                    declare module "beta" {
                        export interface Widget { b: number; }
                    }
                """,
            ).contains("public external interface Gadget")
        )
    }

    @Test
    fun `a target module whose specifier maps to no Kotlin package is a marker naming the module`() {
        assert(
            generate(
                ModuleWiring("beta", "/lib/index.d.ts", "node"),
                """
                    declare module "we~ird" {
                        export interface Widget { id: number; }
                    }
                    declare module "beta" {
                        import * as w from "we~ird";
                        export interface Holder { widget: w.Widget; }
                    }
                """,
            ).contains(
                "unmapped w.Widget - declared by the module \"we~ird\", which maps to no Kotlin package"
            )
        )
    }

    @Test
    fun `a generation with no package of its own names no other module's`() {
        assert(
            generate(
                ModuleWiring("we~ird", "/lib/index.d.ts"),
                """
                    declare module "alpha" {
                        export interface Widget { id: number; }
                    }
                    declare module "we~ird" {
                        import * as a from "alpha";
                        export interface Holder { widget: a.Widget; }
                    }
                """,
            ).contains(
                "unmapped a.Widget - declared by the module \"alpha\" and this generation has no package " +
                    "of its own to name it from"
            )
        )
    }

    @Test
    fun `a package head a declaration of this generation takes refuses the qualified spelling`() {
        assert(
            generate(
                ModuleWiring("beta", "/lib/index.d.ts", "node"),
                """
                    declare module "alpha" {
                        export interface Widget { id: number; }
                    }
                    declare module "beta" {
                        import * as a from "alpha";
                        export namespace node { interface Marker { m: number; } }
                        export interface Holder { widget: a.Widget; }
                    }
                """,
            ).contains(
                "unmapped a.Widget - declared by the module \"alpha\", whose package 'node.alpha' is " +
                    "shadowed by the declaration node of this generation"
            )
        )
    }

    @Test
    fun `negative control - a program with no ambient module block renders every declaration`() {
        assert(
            generate(
                ModuleWiring("pkg", "/pkg/index.d.ts", "root"),
                """
                    export interface Widget { id: number; }
                    export declare function make(): Widget;
                """,
                fileName = "/pkg/index.d.ts",
            ) == """
                @file:JsModule("pkg")

                package root.pkg

                public external interface Widget {
                    public var id: Double
                }

                public external fun make(): Widget

            """.trimIndent()
        )
    }

    @Test
    fun `negative control - a wiring naming no declared block selects nothing and renders every module`() {
        val flat = generate(ModuleWiring("nothing", "/lib/index.d.ts", "node"), twoModules)
        assert(flat.contains("public external interface Widget"))
        assert(flat.contains("public external interface Holder"))
        assert(flat.contains("public open external class Engine"))
    }

}

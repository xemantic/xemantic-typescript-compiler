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
 * (EXT.16) Pins for the MODULE WIRING — the surface graph from the entry
 * and the `@file:JsModule` / `@JsName` / `@file:JsNonModule` rendering —
 * as exact full-text renders, one shape per pin, each red by ablation of
 * the rule it names (the rule is in [ModuleWiring]'s and [ExportPlan]'s
 * KDoc). The no-wiring output of every shape is the global-script mode
 * every earlier rung rendered, pinned once here and by every pin of
 * `KotlinExternalsGeneratorTest`, which passes no wiring.
 */
class KotlinExternalsWiringTest {

    private fun generateWired(
        wiring: ModuleWiring?,
        vararg files: Pair<String, String>,
    ): KotlinExternals = generateKotlinExternals(
        files.map { (name, source) -> SourceFileEntry(name, source.trimIndent()) },
        module = wiring,
    )

    private val pkg = ModuleWiring("pkg", "/pkg/index.d.ts")

    @Test
    fun `an entry file - own names need nothing while another name and a default export are JsNames`() {
        val result = generateWired(
            pkg,
            "/pkg/index.d.ts" to """
                export declare function f(x: string): void;
                export { f as g };
                export default class C { p: number; }
                export declare const v: number;
                export interface I { q: string; }
                export declare enum E { A, B }
                declare const hidden: string;
                export { hidden };
            """,
        )
        val expected = """
            @file:JsModule("pkg")

            /* xtsc: function f is also exported as g - one @JsName per declaration, bound as f */
            public external fun f(x: String): Unit

            @JsName("default")
            public open external class C {
                public var p: Double
            }

            public external val v: Double

            public external interface I {
                public var q: String
            }

            public sealed external interface E {
                public companion object {
                    public val A: E
                    public val B: E
                }
            }

            public external val hidden: String
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a backticked Kotlin name carries its JavaScript spelling`() {
        val result = generateWired(
            pkg,
            "/pkg/index.d.ts" to "export declare const \$: number;\nexport declare function fun(): void;",
        )
        // `fun` is a hard keyword in Kotlin (backticked) and a plain
        // identifier in JavaScript; `$` is legal in both and backtick-only
        // in Kotlin. Both spell their JavaScript name.
        val expected = "@file:JsModule(\"pkg\")\n\n" +
            "@JsName(\"\\$\")\npublic external val `\$`: Double\n\n" +
            "@JsName(\"fun\")\npublic external fun `fun`(): Unit\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `the gate variant renders neither the header nor any JS annotation`() {
        val result = generateWired(
            pkg,
            "/pkg/index.d.ts" to """
                export default function f(x: string): void;
                export as namespace P;
            """,
        )
        val expected = """
            public fun f(x: String): Unit = null!!
        """.trimIndent() + "\n"
        val gate = result.compileCheckSource
        val real = result.kotlin
        assert(gate == expected)
        assert(real.startsWith("@file:JsModule(\"pkg\")\n@file:JsNonModule\n\n@JsName(\"default\")\npublic external fun f("))
    }

    @Test
    fun `the graph - a re-export binds under the exported name and an internal path is a loud marker`() {
        val result = generateWired(
            pkg,
            "/pkg/a.d.ts" to """
                export declare class A { p: number; }
                export declare function b(): void;
                export declare function internal(): void;
                export declare const val: string;
            """,
            "/pkg/index.d.ts" to """
                export { A } from './a.js';
                export { b as c, val } from './a';
                export type { A as AT } from './a';
            """,
        )
        // `.js` resolves to the `.d.ts` sibling; `val` is bound under its
        // own name and backticked in Kotlin; the type-only re-export binds
        // no runtime name, so `A` is not "also exported as AT".
        val expected = """
            @file:JsModule("pkg")

            public open external class A {
                public var p: Double
            }

            @JsName("c")
            public external fun b(): Unit

            /* xtsc: function internal is not exported by the package entry - an internal path a consumer cannot bind */
            public external fun internal(): Unit

            @JsName("val")
            public external val `val`: String
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `an entry at the root directory resolves its relative specifiers`() {
        // The library probe strips the package root, so its entry is
        // `/index.d.ts` — a directory text that is EMPTY and a path that is
        // absolute; `./internal/x` must resolve to `/internal/x.d.ts`. Found
        // by the rxjs 250-file probe: every one of its 170 entry lines
        // missed, and `AsyncSubject` read as an internal path.
        val result = generateWired(
            ModuleWiring("rxjs", "/index.d.ts"),
            "/internal/AsyncSubject.d.ts" to """
                export declare class AsyncSubject<T> { v: T; }
            """,
            "/index.d.ts" to """
                export { AsyncSubject } from './internal/AsyncSubject';
            """,
        )
        val expected = """
            @file:JsModule("rxjs")

            public open external class AsyncSubject<T> {
                public var v: T
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a specifier outside the generation and a name resolving to nothing stay loud`() {
        val result = generateWired(
            pkg,
            "/pkg/a.d.ts" to """
                export declare function b(): void;
            """,
            "/pkg/index.d.ts" to """
                export * from './missing';
                export { nope } from './a';
                export { zzz };
                export { b } from './a';
                export * from 'other-package';
            """,
        )
        val expected = """
            @file:JsModule("pkg")

            public external fun b(): Unit

            /* xtsc: skipped re-export * from './missing' - './missing' resolves to no file in this generation */

            /* xtsc: skipped re-export { nope } from './a' - nope is not exported by '/pkg/a.d.ts' */

            /* xtsc: skipped re-export { zzz } - zzz resolves to no declaration */

            /* xtsc: skipped re-export * from 'other-package' - 'other-package' resolves to no file in this generation */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a namespace export is a loud marker and its members are exported only qualified`() {
        val result = generateWired(
            pkg,
            "/pkg/n.d.ts" to """
                export declare function nf(): void;
                export declare const nv: number;
                export interface NI { x: number; }
            """,
            "/pkg/index.d.ts" to """
                export * as ns from './n';
            """,
        )
        val expected = """
            @file:JsModule("pkg")

            /* xtsc: function nf is exported only as ns.nf - a nested export needs @file:JsQualifier in a file of its own */
            public external fun nf(): Unit

            /* xtsc: value nv is exported only as ns.nv - a nested export needs @file:JsQualifier in a file of its own */
            public external val nv: Double

            public external interface NI {
                public var x: Double
            }

            /* xtsc: skipped namespace export ns from './n' - binds as ns.<name>, which needs @file:JsQualifier("ns") in a file of its own */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `export equals of a function merged with a namespace - the module object itself and no JsName`() {
        val result = generateWired(
            ModuleWiring("m", "/m/index.d.ts"),
            "/m/index.d.ts" to """
                declare function lib(x: number): string;
                declare namespace lib { const version: string; function helper(): void; }
                export = lib;
            """,
        )
        // Neither `lib` nor its namespace carries `export`; both are reached
        // through `export =`. The function is the module object (a consumer
        // binds `@JsModule("m") external fun lib` in a file of its own), and
        // the namespace's members are the module object's members, bound
        // under their own names by the file header.
        val expected = """
            @file:JsModule("m")

            /* xtsc: export = lib - lib is the module object itself: bind it as @JsModule("m") external function lib in a file of its own, no @JsName */
            public external fun lib(x: Double): String

            /* xtsc: namespace lib - the module object (export = lib); members rendered at top level */

            public external val version: String

            public external fun helper(): Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `export equals of a root namespace - members bind under their own names and a nested namespace is an object`() {
        val result = generateWired(
            ModuleWiring("typescript", "/ts/index.d.ts"),
            "/ts/index.d.ts" to """
                declare namespace ts {
                    function create(): Node;
                    interface Node { kind: number; }
                    const version: string;
                    namespace server { function start(): void; }
                }
                export = ts;
            """,
        )
        val expected = """
            @file:JsModule("typescript")

            /* xtsc: namespace ts - the module object (export = ts); members rendered at top level */

            public external fun create(): Node

            public external interface Node {
                public var kind: Double
            }

            public external val version: String

            public external object server {
                public fun start(): Unit
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `an exported root namespace is a module member - the header says so and the members carry no marker`() {
        val result = generateWired(
            ModuleWiring("w", "/w/index.d.ts"),
            "/w/index.d.ts" to """
                export declare namespace W { function go(): void; namespace inner { const x: number; } }
                export declare function top(): void;
            """,
        )
        val expected = """
            @file:JsModule("w")

            /* xtsc: namespace W - the module member W; members rendered at top level and bind as W.<name>, which needs @file:JsQualifier("W") in a file of its own */

            public external fun go(): Unit

            public external object inner {
                public val x: Double
            }

            public external fun top(): Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a root namespace the entry does not reach - the header says so`() {
        val result = generateWired(
            ModuleWiring("w", "/w/index.d.ts"),
            "/w/other.d.ts" to """
                declare namespace W { function go(): void; }
            """,
            "/w/index.d.ts" to """
                export declare function top(): void;
            """,
        )
        val expected = """
            @file:JsModule("w")

            /* xtsc: namespace W - not exported by the package entry; members rendered at top level */

            public external fun go(): Unit

            public external fun top(): Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `a UMD entry renders JsNonModule and the misparsed namespace line is nothing`() {
        val result = generateWired(
            ModuleWiring("u", "/u/index.d.ts"),
            "/u/index.d.ts" to """
                export declare function run(): void;
                export as namespace U;
            """,
        )
        val expected = """
            @file:JsModule("u")
            @file:JsNonModule

            public external fun run(): Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `a local reached through a default export joins the surface as JsName default`() {
        val result = generateWired(
            ModuleWiring("smol", "/smol/index.d.ts"),
            "/smol/parse.d.ts" to """
                export declare function parse(s: string): number;
            """,
            "/smol/index.d.ts" to """
                import { parse } from './parse.js';
                declare const _default: { parse: typeof parse; };
                export default _default;
                export { parse };
            """,
        )
        // `_default` carries no `export` modifier and is what the package's
        // `default` IS; `parse` is an IMPORT re-exported locally, bound under
        // its own name in the file that declares it.
        val expected = """
            @file:JsModule("smol")

            public external fun parse(s: String): Double

            @JsName("default")
            public external val _default: Any? /* xtsc: unmapped { parse: (s: string) => number; } */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes.isEmpty())
    }

    @Test
    fun `export star merges tables - an ambiguous name is dropped and imports re-exported bind their targets`() {
        val result = generateWired(
            ModuleWiring("s", "/s/index.d.ts"),
            "/s/a.d.ts" to """
                export declare function fa(): void;
                declare function dflt(): void;
                export default dflt;
            """,
            "/s/b.d.ts" to """
                export declare function fb(): void;
                export declare function fa(): void;
            """,
            "/s/index.d.ts" to """
                import D from './a';
                import * as all from './b';
                export * from './a';
                export * from './b';
                export { D, all };
            """,
        )
        // `fa` comes from two stars naming different declarations —
        // TypeScript exports neither (TS2308 in the checker) — so a's `fa`
        // is an internal path; b's collapses onto it by the overload rule.
        // `D` is a's default under the name `D`; `all` is b's module object,
        // so `fb` is reachable both bare (the star) and as `all.fb`.
        val expected = """
            @file:JsModule("s")

            /* xtsc: function fa is not exported by the package entry - an internal path a consumer cannot bind */
            public external fun fa(): Unit

            @JsName("D")
            public external fun dflt(): Unit

            public external fun fb(): Unit

            /* xtsc: skipped overload of fa collapsing to a duplicate signature - kept fa() */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        val errorCodes = result.errors.map { it.code }
        assert(rendered == expected)
        assert(errorCodes == listOf(2308))
    }

    @Test
    fun `several names - the first in entry order binds and the others are listed`() {
        val result = generateWired(
            pkg,
            "/pkg/index.d.ts" to """
                declare function f(): void;
                export { f as g };
                export { f as h, f };
            """,
        )
        val expected = """
            @file:JsModule("pkg")

            /* xtsc: function f is also exported as h, f - one @JsName per declaration, bound as g */
            @JsName("g")
            public external fun f(): Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `declare module bodies - the package's own module and another module`() {
        val result = generateWired(
            pkg,
            "/pkg/index.d.ts" to """
                declare module "pkg" { export function own(): void; }
                declare module "other" { export function alien(): void; export = alien; }
                export declare function top(): void;
            """,
        )
        val expected = """
            @file:JsModule("pkg")

            /* xtsc: module "pkg" - the package's own module; members rendered at top level */

            public external fun own(): Unit

            /* xtsc: module "other" - members rendered at top level; bound by @file:JsModule("other"), not "pkg" - a file of its own */

            public external fun alien(): Unit

            /* xtsc: skipped export = alien inside module "other" - outside the package entry's surface */

            public external fun top(): Unit
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `an export of an expression stays loud`() {
        val result = generateWired(
            pkg,
            "/pkg/index.d.ts" to """
                declare const o: { a: number };
                export default o.a;
            """,
        )
        val expected = """
            @file:JsModule("pkg")

            /* xtsc: skipped default export of an expression - nothing a consumer can bind by name */
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
        val equals = generateWired(
            ModuleWiring("m", "/m/index.d.ts"),
            "/m/index.d.ts" to """
                declare const o: { a: number };
                export = o.a;
            """,
        )
        val equalsExpected = """
            @file:JsModule("m")

            /* xtsc: skipped export = an expression - nothing a consumer can bind by name */
        """.trimIndent() + "\n"
        val equalsRendered = equals.kotlin
        assert(equalsRendered == equalsExpected)
    }

    @Test
    fun `a nested export import alias names the limit under a wiring`() {
        val result = generateWired(
            ModuleWiring("typescript", "/ts/index.d.ts"),
            "/ts/index.d.ts" to """
                declare namespace ts {
                    interface Info { x: number; }
                    namespace server { export import Info = ts.Info; }
                }
                export = ts;
            """,
        )
        val expected = """
            @file:JsModule("typescript")

            /* xtsc: namespace ts - the module object (export = ts); members rendered at top level */

            public external interface Info {
                public var x: Double
            }

            public external object server {
                /* xtsc: alias Info = ts.Info - re-exported name, a nested object member cannot carry @JsName */
            }
        """.trimIndent() + "\n"
        val rendered = result.kotlin
        assert(rendered == expected)
    }

    @Test
    fun `without a wiring the output is the global-script mode - the markers of every earlier rung`() {
        val source = """
            export declare function f(x: string): void;
            export { f as g };
            export default class C { p: number; }
        """
        val plain = generateWired(null, "/pkg/index.d.ts" to source)
        val expected = """
            public external fun f(x: String): Unit

            /* xtsc: skipped re-export { f as g } - module wiring is a later rung */

            public open external class C {
                /* xtsc: default export - consumers bind the module's default */
                public var p: Double
            }
        """.trimIndent() + "\n"
        val plainRendered = plain.kotlin
        assert(plainRendered == expected)
        val wired = generateWired(pkg, "/pkg/index.d.ts" to source)
        val wiredExpected = """
            @file:JsModule("pkg")

            /* xtsc: function f is also exported as g - one @JsName per declaration, bound as f */
            public external fun f(x: String): Unit

            @JsName("default")
            public open external class C {
                public var p: Double
            }
        """.trimIndent() + "\n"
        val wiredRendered = wired.kotlin
        assert(wiredRendered == wiredExpected)
    }

    @Test
    fun `an entry that is not among the files is a caller error`() {
        val failure = runCatching {
            generateWired(ModuleWiring("pkg", "/pkg/missing.d.ts"), "/pkg/index.d.ts" to "export declare const v: number;")
        }.exceptionOrNull()
        val isArgumentError = failure is IllegalArgumentException
        val message = failure?.message ?: ""
        assert(isArgumentError)
        assert("/pkg/missing.d.ts" in message)
    }

}

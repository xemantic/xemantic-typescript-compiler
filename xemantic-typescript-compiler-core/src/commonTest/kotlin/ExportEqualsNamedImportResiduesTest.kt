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
 * (CHK.198) The residues of (CHK.197)'s per-specifier `export =` named-import rule
 * (`NamedImportExistence.reportExportEqualsSpecifier`):
 *
 * - (c) a RE-EXPORT `export { x } from "./m"` of an `export =` module follows the same rule
 *   (it was skipped outright);
 * - (b) a TYPE-ONLY specifier or clause, and a declaration-file importer, follow it too —
 *   tsgo reports the target's own name even in `import type { … }`;
 * - (a) the member set is enumerated for an inherited static (along class declarations),
 *   an enum, a function's expandos in its own scope, an object-literal initializer and an
 *   `export = { … }` literal; a namespace's TYPE exports, a base class's merged-namespace
 *   VALUES and a namespace augmentation's names are legal (four of these were false TS2305
 *   rows on legal code before this round);
 * - a misspelt name among a namespace target's exports reads TS2724;
 * - (d) a default import of an ambient module or a `node_modules` declaration file that
 *   declares `__esModule` reads TS1192 / TS2613.
 *
 * Every expected row is tsgo 7.0.2's over the matching cell of
 * `build/bench/p18265-agent/matrix`. A shape whose member set cannot be decided keeps only
 * the self-name row; those are pinned as `residue - …` with tsgo's missing row named.
 */
class ExportEqualsNamedImportResiduesTest {

    private fun rows(source: String, directives: String = "// @strict: true\n// @module: commonjs"): List<String> =
        diagnose(source, directives)
            .filter { it.code != 1203 }
            .map { "${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" }
            .sorted()

    private val value = """
        // @Filename: m.ts
        declare const v: number;
        export = v;

    """.trimIndent() + "\n"

    private val ns = """
        // @Filename: m.ts
        namespace NS { export const a = 1; export interface I { q: number } export type T = number; }
        export = NS;

    """.trimIndent() + "\n"

    // ---- (c) re-exports -------------------------------------------------------------

    @Test
    fun `a re-export of an export equals module follows the per-specifier rule`() {
        val r = rows(value + "// @Filename: main.ts\nexport { x } from \"./m\";\nexport { v } from \"./m\";\nexport { toFixed } from \"./m\";")
        assert(r == listOf(
            "main.ts:1:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
            "main.ts:2:10 TS2616 'v' can only be imported by using 'import v = require(\"./m\")' or a default import.",
        ))
    }

    @Test
    fun `under module esnext a re-exported self-name reads TS2595`() {
        val r = rows(
            value + "// @Filename: main.ts\nexport { x } from \"./m\";\nexport { v } from \"./m\";",
            "// @strict: true\n// @module: esnext",
        )
        assert(r == listOf(
            "main.ts:1:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
            "main.ts:2:10 TS2595 'v' can only be imported by using a default import.",
        ))
    }

    @Test
    fun `a checked JS re-export reads TS2597 for the self-name`() {
        val r = rows(
            value + "// @Filename: main.js\nexport { x } from \"./m\";\nexport { v } from \"./m\";",
            "// @strict: true\n// @module: commonjs\n// @allowJs: true\n// @checkJs: true\n// @noEmit: true",
        )
        assert(r == listOf(
            "main.js:1:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
            "main.js:2:10 TS2597 'v' can only be imported by using a 'require' call or by using a default import.",
        ))
    }

    @Test
    fun `control - an unchecked JS re-export reports nothing`() {
        val r = rows(
            value + "// @Filename: main.js\nexport { x } from \"./m\";\nexport { v } from \"./m\";",
            "// @strict: true\n// @module: commonjs\n// @allowJs: true\n// @noEmit: true",
        )
        assert(r.isEmpty())
    }

    @Test
    fun `a type-only re-export follows the rule and a namespace type is legal`() {
        val r = rows(ns + "// @Filename: main.ts\nexport type { x } from \"./m\";\nexport { type NS } from \"./m\";\nexport type { I } from \"./m\";\nexport { type a } from \"./m\";")
        assert(r == listOf(
            "main.ts:1:15 TS2305 Module '\"./m\"' has no exported member 'x'.",
            "main.ts:2:15 TS2616 'NS' can only be imported by using 'import NS = require(\"./m\")' or a default import.",
        ))
    }

    @Test
    fun `a re-export from a declaration file follows the rule`() {
        val r = rows(value + "// @Filename: main.d.ts\nexport { x } from \"./m\";\nexport { v } from \"./m\";")
        assert(r == listOf(
            "main.d.ts:1:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
            "main.d.ts:2:10 TS2616 'v' can only be imported by using 'import v = require(\"./m\")' or a default import.",
        ))
    }

    @Test
    fun `control - re-exporting the default of an export equals module is legal`() {
        assert(rows(value + "// @Filename: main.ts\nexport { default } from \"./m\";\nexport { default as d } from \"./m\";").isEmpty())
    }

    @Test
    fun `a re-exported namespace value interface and alias are legal`() {
        val r = rows(ns + "// @Filename: main.ts\nexport { a } from \"./m\";\nexport { I } from \"./m\";\nexport { NS } from \"./m\";\nexport { x } from \"./m\";\nexport { T } from \"./m\";")
        assert(r == listOf(
            "main.ts:3:10 TS2616 'NS' can only be imported by using 'import NS = require(\"./m\")' or a default import.",
            "main.ts:4:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    // ---- (b) type-only imports and declaration-file importers ------------------------

    @Test
    fun `a type-only import follows the rule including the self-name`() {
        val r = rows(value + """
            // @Filename: main.ts
            import type { v } from "./m";
            import type { x } from "./m";
            import { type y } from "./m";
            import { type v as w } from "./m";
            import type { toFixed } from "./m";
            export type { v, x, y, w, toFixed };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:1:15 TS2616 'v' can only be imported by using 'import v = require(\"./m\")' or a default import.",
            "main.ts:2:15 TS2305 Module '\"./m\"' has no exported member 'x'.",
            "main.ts:3:15 TS2305 Module '\"./m\"' has no exported member 'y'.",
            "main.ts:4:15 TS2616 'v' can only be imported by using 'import v = require(\"./m\")' or a default import.",
        ))
    }

    @Test
    fun `a namespace type export is importable by a type-only and by a value import`() {
        // Before this round `import { I as K2 }` and `import { T as T2 }` read a false TS2305.
        val r = rows(ns + """
            // @Filename: main.ts
            import type { I } from "./m";
            import type { T } from "./m";
            import type { a } from "./m";
            import type { NS } from "./m";
            import type { x } from "./m";
            import { type I as J } from "./m";
            import { I as K2 } from "./m";
            import { T as T2 } from "./m";
            export type { I, T, a, NS, x, J, K2, T2 };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:4:15 TS2616 'NS' can only be imported by using 'import NS = require(\"./m\")' or a default import.",
            "main.ts:5:15 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `a class or function merged with a namespace exposes its namespace types`() {
        val cls = rows("""
            // @Filename: m.ts
            class K { static s = 1 }
            namespace K { export interface I { q: number } export const z = 1; }
            export = K;

            // @Filename: main.ts
            import type { I } from "./m";
            import { I as J } from "./m";
            import { z } from "./m";
            import type { x } from "./m";
            export type { I, J, z, x };
        """.trimIndent())
        assert(cls == listOf("main.ts:4:15 TS2305 Module '\"./m\"' has no exported member 'x'."))
        val fn = rows("""
            // @Filename: m.ts
            function g() {}
            namespace g { export interface I { q: number } }
            export = g;

            // @Filename: main.ts
            import type { I } from "./m";
            import { I as J } from "./m";
            import type { x } from "./m";
            export type { I, J, x };
        """.trimIndent())
        assert(fn == listOf("main.ts:3:15 TS2305 Module '\"./m\"' has no exported member 'x'."))
    }

    @Test
    fun `a type-only import of a name that is both the target and an interface is the self-name row`() {
        val r = rows("""
            // @Filename: m.ts
            interface o { a: number }
            declare const o: o;
            export = o;

            // @Filename: main.ts
            import type { o } from "./m";
            import type { a } from "./m";
            export type { o, a };
        """.trimIndent())
        assert(r == listOf("main.ts:1:15 TS2616 'o' can only be imported by using 'import o = require(\"./m\")' or a default import."))
    }

    @Test
    fun `a local type that is not the target is TS2305 for both import forms`() {
        val r = rows("""
            // @Filename: m.ts
            type T = number;
            declare const v: T;
            export = v;

            // @Filename: main.ts
            import type { T } from "./m";
            import { T as U } from "./m";
            export type { T, U };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:1:15 TS2305 Module '\"./m\"' has no exported member 'T'.",
            "main.ts:2:10 TS2305 Module '\"./m\"' has no exported member 'T'.",
        ))
    }

    @Test
    fun `a declaration file importer follows the rule for value and type-only imports`() {
        val v = rows(value + "// @Filename: main.d.ts\nimport { v } from \"./m\";\nimport type { x } from \"./m\";\nexport { v, x };")
        assert(v == listOf(
            "main.d.ts:1:10 TS2616 'v' can only be imported by using 'import v = require(\"./m\")' or a default import.",
            "main.d.ts:2:15 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
        // The namespace's own name was a TS2305 here before this round.
        val n = rows(ns + "// @Filename: main.d.ts\nimport { a } from \"./m\";\nimport { NS } from \"./m\";\nimport { x } from \"./m\";\nexport { a, NS, x };")
        assert(n == listOf(
            "main.d.ts:2:10 TS2616 'NS' can only be imported by using 'import NS = require(\"./m\")' or a default import.",
            "main.d.ts:3:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `a variable merged with a namespace exposes its type members and augmentation members`() {
        // The lodash shape: `declare const _: _.LoDashStatic` + `declare namespace _`, with a
        // cross-file augmentation adding `pick` to the interface. Both `pick` and `chunk` read
        // a false TS2305 through a relative specifier before this round.
        val r = rows("""
            // @Filename: lod/index.d.ts
            /// <reference path="./object.d.ts" />
            export = _;
            export as namespace _;
            declare const _: _.LoDashStatic;
            declare namespace _ {
                interface LoDashStatic { chunk(): void; }
                type Many<T> = T | T[];
            }

            // @Filename: lod/object.d.ts
            import _ = require("./index");
            declare module "./index" {
                interface LoDashStatic { pick(): void; }
            }

            // @Filename: main.ts
            import { pick } from "./lod";
            import { chunk } from "./lod";
            import { nope } from "./lod";
            import type { Many } from "./lod";
            export { pick, chunk, nope };
            export type { Many };
        """.trimIndent())
        assert(r == listOf("main.ts:3:10 TS2305 Module '\"./lod\"' has no exported member 'nope'."))
    }

    @Test
    fun `a namespace augmentation adds an importable name`() {
        // `z` read a false TS2305 before this round.
        val r = rows("""
            // @Filename: m.ts
            namespace NS { export const a = 1; }
            export = NS;

            // @Filename: aug.ts
            export {};
            declare module "./m" { export const z: number; }

            // @Filename: main.ts
            import { z } from "./m";
            import { x } from "./m";
            export { z, x };
        """.trimIndent())
        assert(r == listOf("main.ts:2:10 TS2305 Module '\"./m\"' has no exported member 'x'."))
    }

    // ---- (a) the member set ----------------------------------------------------------

    @Test
    fun `an inherited static is importable along a chain of class declarations`() {
        val r = rows("""
            // @Filename: m.ts
            class A { static as1 = 1 }
            class B<T> extends A { static bs = 1; v!: T }
            class K extends B<number> { static s = 1 }
            export = K;

            // @Filename: main.ts
            import { as1, bs, s, prototype, K, x } from "./m";
            export { as1, bs, s, prototype, K, x };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:1:33 TS2616 'K' can only be imported by using 'import K = require(\"./m\")' or a default import.",
            "main.ts:1:36 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `a base class's namespace value is inherited and its namespace type is not`() {
        val r = rows("""
            // @Filename: m.ts
            class B { static bs = 1 }
            namespace B { export const z = 1; export interface I { q: number } }
            class K extends B { static s = 1 }
            export = K;

            // @Filename: main.ts
            import { z } from "./m";
            import { bs } from "./m";
            import { I } from "./m";
            import { x } from "./m";
            export { z, bs, x };
            export type { I };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:3:10 TS2305 Module '\"./m\"' has no exported member 'I'.",
            "main.ts:4:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `an enum export equals target exposes its members and no spelling suggestion`() {
        val r = rows("""
            // @Filename: m.ts
            enum E { Alpha = 'a', Beta = 'b' }
            enum E { Gamma = 'c' }
            export = E;

            // @Filename: main.ts
            import { Alpha, Gamma, E, Alpah, x } from "./m";
            export { Alpha, Gamma, E, Alpah, x };
        """.trimIndent(), "// @strict: true\n// @module: esnext")
        assert(r == listOf(
            "main.ts:1:24 TS2595 'E' can only be imported by using a default import.",
            "main.ts:1:27 TS2305 Module '\"./m\"' has no exported member 'Alpah'.",
            "main.ts:1:34 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `a function's expandos in its own scope are importable and one in a nested function is not`() {
        // Before this round the nested-block `q` and the element-access `r` read a false TS2305.
        val r = rows("""
            // @Filename: m.ts
            function f() {}
            f.p = 1;
            if (Math.random()) { f.q = 2; }
            f["r"] = 3;
            function init() { f.inner = 4; }
            init();
            export = f;

            // @Filename: main.ts
            import { p, q, r, inner, f, x } from "./m";
            export { p, q, r, inner, f, x };
        """.trimIndent()).filter { "main.ts" in it }
        assert(r == listOf(
            "main.ts:1:19 TS2305 Module '\"./m\"' has no exported member 'inner'.",
            "main.ts:1:26 TS2616 'f' can only be imported by using 'import f = require(\"./m\")' or a default import.",
            "main.ts:1:29 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `an object literal initializer exposes its own names and no Object members`() {
        val r = rows("""
            // @Filename: m.ts
            const o = { a: 1, m() {}, get g() { return 1 } } as const;
            export = o;

            // @Filename: main.ts
            import { a, m, g, o, toString, x } from "./m";
            export { a, m, g, o, toString, x };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:1:19 TS2616 'o' can only be imported by using 'import o = require(\"./m\")' or a default import.",
            "main.ts:1:22 TS2305 Module '\"./m\"' has no exported member 'toString'.",
            "main.ts:1:32 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `an export equals object literal exposes its own names`() {
        val r = rows("""
            // @Filename: m.ts
            const a = 1;
            export = { a, m() {}, b: 'x', hasOwnProperty: 0 };

            // @Filename: main.ts
            import { a, m, b, hasOwnProperty, toString, x } from "./m";
            export { a, m, b, hasOwnProperty, toString, x };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:1:35 TS2305 Module '\"./m\"' has no exported member 'toString'.",
            "main.ts:1:45 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `a misspelt name among a namespace target's exports reads TS2724`() {
        val d = diagnose("""
            // @Filename: m.ts
            namespace NS { export const alpha = 1; }
            export = NS;

            // @Filename: main.ts
            export { alpah } from "./m";
        """.trimIndent(), "// @strict: true\n// @module: commonjs").single()
        assert("${d.fileName}:${d.line}:${d.character} TS${d.code} ${d.message}" ==
            "main.ts:1:10 TS2724 '\"./m\"' has no exported member named 'alpah'. Did you mean 'alpha'?")
        val related = d.relatedInformation.map { "${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" }
        assert(related == listOf("m.ts:1:29 TS2728 'alpha' is declared here."))
    }

    @Test
    fun `control - a misspelt static of a class target is TS2305 not TS2724`() {
        val r = rows("""
            // @Filename: m.ts
            class K { static alpha = 1 }
            export = K;

            // @Filename: main.ts
            import { alpah } from "./m";
            export { alpah };
        """.trimIndent())
        assert(r == listOf("main.ts:1:10 TS2305 Module '\"./m\"' has no exported member 'alpah'."))
    }

    @Test
    fun `residue - a base that is not a class declaration keeps only the self-name row`() {
        // tsgo also reports TS2305 for `x` in all three; the base's static side is not
        // enumerated here (a mixin call, a lib `Map`, a const alias of a class).
        for (base in listOf(
            "function mix<T extends new (...a: any[]) => {}>(b: T) { return class extends b { static ms = 1 }; }\nclass B {}\nclass K extends mix(B) { static s = 1 }",
            "class K extends Map<string, number> { static s = 1 }",
            "class B { static bs = 1 }\nconst C = B;\nclass K extends C { static s = 1 }",
        )) {
            val r = rows("// @Filename: m.ts\n$base\nexport = K;\n\n// @Filename: main.ts\nimport { s, x } from \"./m\";\nexport { s, x };")
            assert(r.isEmpty())
        }
    }

    @Test
    fun `residue - an initializer that is not a plain object literal keeps only the self-name row`() {
        // tsgo reports TS2305 for `x` (and for `a` and `z` through `as any` and the arrow).
        for (init in listOf(
            "const base = { b: 1 };\nconst o = { ...base, a: 1 };",
            "function mk() { return { a: 1 }; }\nconst o = mk();",
            "const o = { a: 1 } as any;\no.z = 1;",
            "const o = () => {};\no.p = 1;",
        )) {
            val r = rows("// @Filename: m.ts\n$init\nexport = o;\n\n// @Filename: main.ts\nimport { a, z, x } from \"./m\";\nexport { a, z, x };")
            assert(r.isEmpty())
        }
    }

    // ---- (d) default imports of ambient modules and node_modules packages ------------

    @Test
    fun `a default import of an ambient module declaring the esModule marker reads TS1192 or TS2613`() {
        val r = rows("""
            // @Filename: amb.d.ts
            declare module "amb" { export const __esModule: true; export const a: number; }

            // @Filename: main.ts
            import d from "amb";
            import a from "amb";
            export { d, a };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:1:8 TS1192 Module '\"amb\"' has no default export.",
            "main.ts:2:8 TS2613 Module '\"amb\"' has no default export. Did you mean to use 'import { a } from \"amb\"' instead?",
        ))
    }

    @Test
    fun `control - an ambient module without the marker or with a default has a default`() {
        for (body in listOf(
            "export const a: number;",
            "const v: number; export = v;",
            "export const __esModule: true; const v: number; export default v;",
        )) {
            val r = rows("// @Filename: amb.d.ts\ndeclare module \"amb\" { $body }\n\n// @Filename: main.ts\nimport d from \"amb\";\nexport { d };")
            assert(r.isEmpty())
        }
    }

    @Test
    fun `a default import of a node_modules declaration file declaring the esModule marker reads TS1192 or TS2613`() {
        val r = rows("""
            // @Filename: /proj/node_modules/pkg/index.d.ts
            export declare const __esModule: true;
            export declare const a: number;

            // @Filename: /proj/node_modules/pkg/package.json
            {"name":"pkg","types":"index.d.ts"}

            // @Filename: /proj/main.ts
            import d from "pkg";
            import a from "pkg";
            export { d, a };
        """.trimIndent())
        assert(r == listOf(
            "/proj/main.ts:1:8 TS1192 Module '\"/proj/node_modules/pkg/index\"' has no default export.",
            "/proj/main.ts:2:8 TS2613 Module '\"/proj/node_modules/pkg/index\"' has no default export. Did you mean to use 'import { a } from \"/proj/node_modules/pkg/index\"' instead?",
        ))
    }

    @Test
    fun `control - a node_modules declaration file without the marker has a synthetic default`() {
        val r = rows("""
            // @Filename: /proj/node_modules/pkg/index.d.ts
            export declare const a: number;

            // @Filename: /proj/node_modules/pkg/package.json
            {"name":"pkg","types":"index.d.ts"}

            // @Filename: /proj/main.ts
            import d from "pkg";
            export { d };
        """.trimIndent())
        assert(r.isEmpty())
    }
}

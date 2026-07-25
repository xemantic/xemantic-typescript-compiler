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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Pins the parse-based module-specifier extraction invariant (M0.3): the parser
 * records specifiers ONLY from real import positions — string literals, comments,
 * template literals, and regex literals can NEVER contribute (the defect that made
 * the tsc self-compile report ~120 garbage "unresolved imports" under the previous
 * regex-over-source extraction), while real imports are found at ANY nesting depth.
 */
class ModuleSpecifierExtractionTest {

    private fun specifiersOf(@Language("typescript") source: String, fileName: String = "/proj/test.ts"): Set<String> =
        Parser(source, fileName).parse().moduleSpecifiers.toSet()

    @Test
    fun `collects every real import kind`() {
        val src = """
            /// <reference path="./ref-path.ts" />
            /// <reference types="ref-types" />
            import def from "./static-default";
            import { named } from "./static-named";
            import * as ns from "./static-namespace";
            import "./side-effect";
            import type { T } from "./type-only";
            import eq = require("./import-equals");
            export * from "./export-star";
            export { named } from "./export-named";
            export type { T2 } from "./export-type";
            const lazy = import("./dynamic-top");
            const cjs = require("./require-top");
            type Q = import("./import-type").Foo;
            type QQ = typeof import("./import-typeof");
        """.trimIndent()
        assert(
            specifiersOf(src) == setOf( "./ref-path.ts", "ref-types", "./static-default", "./static-named", "./static-namespace", "./side-effect", "./type-only", "./import-equals", "./export-star", "./export-named", "./export-type", "./dynamic-top", "./require-top", "./import-type", "./import-typeof", )
        )
    }

    @Test
    fun `string literals comments templates and regexes never contribute`() {
        // Every "garbage-N" below sits in a lexical context a text scan would match
        // but the parser must not: plain strings, template literals (including one
        // whose content spans lines and starts a line with a reference directive —
        // the tsc-harness shape), line/block comments, and a regex literal
        // containing quotes (which also derails naive token scans).
        val src = """
            import { real } from "./real";
            const s1 = 'import { x } from "garbage-1"';
            const s2 = "const y = require(\"garbage-2\")";
            const t = `
            import "garbage-3";
            /// <reference path="garbage-4.ts" />
            ${'$'}{import("./real-in-template")}
            `;
            // import commented from "garbage-5";
            /* import blockCommented from "garbage-6"; */
            const re = /from ["']garbage-7["']/;
            const s3 = "from 'garbage-8'";
        """.trimIndent()
        val specs = specifiersOf(src)
        have(specs.none { it.contains("garbage") })
        // Real specifiers still found — including a dynamic import INSIDE a template
        // substitution (real code position) right next to template text that is not.
        assert(specs == setOf("./real", "./real-in-template"))
    }

    @Test
    fun `finds dynamic imports and requires at any depth`() {
        val src = """
            export class C {
                async m() {
                    if (Math.random() > 0.5) {
                        return import("./deep-dynamic");
                    }
                    return () => require("./deep-require");
                }
                prop = { nested: () => import("./deep-prop") };
            }
            function f(): import("./deep-type").T { return null as any; }
            const generic = require<{ x: number }>("./generic-require");
        """.trimIndent()
        assert(
            specifiersOf(src) == setOf("./deep-dynamic", "./deep-require", "./deep-prop", "./deep-type", "./generic-require")
        )
    }

    @Test
    fun `non-literal arguments are ignored`() {
        val src = """
            const name = "./computed";
            const a = import(name);
            const b = require(name);
            const c = import(`./tpl-${'$'}{name}`);
            const d = other("./not-an-import");
            const e = obj.require("./method-not-require");
        """.trimIndent()
        assert(specifiersOf(src).isEmpty())
    }

    @Test
    fun `reference directives survive a block-comment header`() {
        // tsc honors triple-slash directives after a leading block-comment (license
        // header) — they are trivia before the first token. Directives after the
        // first statement are plain comments.
        val src = """
            /*
             * Copyright header.
             */
            /// <reference path="./after-header.ts" />
            import { x } from "./real";
            /// <reference path="./too-late.ts" />
        """.trimIndent()
        assert(specifiersOf(src) == setOf("./after-header.ts", "./real"))
    }

    @Test
    fun `a project build reports no garbage unresolved imports`() {
        // End-to-end wiring: ProjectCompiler's graph walk must consume the parser's
        // specifier set, so import-shaped text in string literals neither shows up in
        // `unresolved` nor pulls files into the program.
        val vfs = InMemoryVfs(
            mapOf(
                "/proj/tsconfig.json" to """{ "compilerOptions": { "outDir": "./dist" }, "include": ["src/**/*.ts"] }""",
                "/proj/src/index.ts" to """
                    import { helper } from "./helper.js";
                    import "./genuinely-missing.js";
                    const doc = 'usage: import { x } from "some-package"';
                    export const r = helper(doc);
                """.trimIndent(),
                "/proj/src/helper.ts" to "export function helper(s: string): string { return s; }",
                // Present on disk, but referenced ONLY from a string literal — must NOT join the program.
                "/proj/node_modules/some-package/index.d.ts" to "export declare const x: number;",
            )
        )
        val result = ProjectCompiler(vfs).build("/proj", noEmit = true)
        assert(result.unresolved == listOf("/proj/src/index.ts" to "./genuinely-missing.js"))
        // a string-literal mention must not pull a file into the program
        assert(result.programFiles.none { it.contains("some-package") })
    }
}

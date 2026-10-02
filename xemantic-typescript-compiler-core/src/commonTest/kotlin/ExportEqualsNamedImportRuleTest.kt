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
 * (CHK.197) A NAMED import of an `export =` module, decided per specifier as tsgo's
 * `getExternalModuleMember` + `reportNonExportedMember` (checker.go:14593 / 14823) decide
 * it: a PROPERTY of the export= target's type is legal; the name that IS the target's own
 * local reads exactly ONE of TS2595 (`module` >= ES2015) / TS2597 (JS importer) / TS2616;
 * every other name is TS2305. Before this round every specifier read TS2616 (or TS2595 AND
 * TS2616 under `module: esnext`), whatever its name.
 *
 * Every row below was measured on `tools/tsgo-7.0.2/lib/tsc` over the matching cell of
 * `build/bench/p18257-agent/matrix` / `matrix2`. (CHK.198) enumerated the member sets this
 * pass left unknown (an inherited static, an enum, a function with expandos, an object
 * literal) — `ExportEqualsNamedImportResiduesTest` pins those and the shapes still unknown.
 */
class ExportEqualsNamedImportRuleTest {

    private fun rows(source: String, directives: String = "// @strict: true\n// @module: commonjs"): List<String> =
        diagnose(source, directives)
            .filter { it.code != 1203 }
            .map { "${it.fileName}:${it.line}:${it.character} TS${it.code} ${it.message}" }
            .sorted()

    private val value = """
        // @Filename: m.ts
        declare const v: number;
        export = v;

        // @Filename: main.ts
    """.trimIndent() + "\n"

    @Test
    fun `the self-name of an export equals value is TS2616 and any other name TS2305`() {
        val r = rows(value + "import { x } from \"./m\";\nimport { v } from \"./m\";\nexport { x, v };")
        assert(r == listOf(
            "main.ts:1:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
            "main.ts:2:10 TS2616 'v' can only be imported by using 'import v = require(\"./m\")' or a default import.",
        ))
    }

    @Test
    fun `a wrapper member of a primitive export equals value is importable`() {
        val r = rows(value + "import { toFixed } from \"./m\";\nimport { prototype } from \"./m\";\nexport { toFixed, prototype };")
        assert(r == listOf("main.ts:2:10 TS2305 Module '\"./m\"' has no exported member 'prototype'."))
    }

    @Test
    fun `under module esnext the self-name reads TS2595 alone`() {
        val r = rows(
            value + "import { x } from \"./m\";\nimport { v } from \"./m\";\nexport { x, v };",
            "// @strict: true\n// @module: esnext",
        )
        assert(r == listOf(
            "main.ts:1:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
            "main.ts:2:10 TS2595 'v' can only be imported by using a default import.",
        ))
    }

    @Test
    fun `the export assignment itself still reads TS1203 under module esnext`() {
        val d = diagnose(value + "import { v } from \"./m\";\nexport { v };", "// @strict: true\n// @module: esnext")
            .filter { it.code == 1203 }
            .map { "${it.fileName}:${it.line}:${it.character}" }
        assert(d == listOf("m.ts:2:1"))
    }

    @Test
    fun `a renamed specifier is judged by its property name`() {
        val r = rows(value + "import { v as w, x as y } from \"./m\";\nexport { w, y };")
        assert(r == listOf(
            "main.ts:1:10 TS2616 'v' can only be imported by using 'import v = require(\"./m\")' or a default import.",
            "main.ts:1:18 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `a JS importer reads TS2597 for the self-name`() {
        val r = rows(
            value.replace("main.ts", "main.js") + "import { x } from \"./m\";\nimport { v } from \"./m\";\nexport { x, v };",
            "// @strict: true\n// @module: commonjs\n// @allowJs: true\n// @checkJs: true\n// @noEmit: true",
        )
        assert(r == listOf(
            "main.js:1:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
            "main.js:2:10 TS2597 'v' can only be imported by using a 'require' call or by using a default import.",
        ))
    }

    @Test
    fun `a class export equals target exposes its static side`() {
        val r = rows("""
            // @Filename: m.ts
            class K { static s = 1 }
            export = K;

            // @Filename: main.ts
            import { s } from "./m";
            import { prototype } from "./m";
            import { K } from "./m";
            import { x } from "./m";
            export { s, prototype, K, x };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:3:10 TS2616 'K' can only be imported by using 'import K = require(\"./m\")' or a default import.",
            "main.ts:4:10 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `a plain function export equals target has no importable member`() {
        val r = rows("""
            // @Filename: m.ts
            function f() {}
            export = f;

            // @Filename: main.ts
            import { f } from "./m";
            import { prototype } from "./m";
            export { f, prototype };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:1:10 TS2616 'f' can only be imported by using 'import f = require(\"./m\")' or a default import.",
            "main.ts:2:10 TS2305 Module '\"./m\"' has no exported member 'prototype'.",
        ))
    }

    @Test
    fun `a namespace export equals target exposes its exports and its own name is TS2616`() {
        val r = rows("""
            // @Filename: m.ts
            namespace NS { export const a = 1; }
            export = NS;

            // @Filename: main.ts
            import { a } from "./m";
            import { NS } from "./m";
            import { b } from "./m";
            export { a, NS, b };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:2:10 TS2616 'NS' can only be imported by using 'import NS = require(\"./m\")' or a default import.",
            "main.ts:3:10 TS2305 Module '\"./m\"' has no exported member 'b'.",
        ))
    }

    @Test
    fun `a class merged with a namespace exposes statics and namespace values`() {
        val r = rows("""
            // @Filename: m.ts
            class K { static s = 1 }
            namespace K { export const n = 1; }
            export = K;

            // @Filename: main.ts
            import { s, n, K, x } from "./m";
            export { s, n, K, x };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:1:16 TS2616 'K' can only be imported by using 'import K = require(\"./m\")' or a default import.",
            "main.ts:1:19 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `an inherited static is importable and any other name is TS2305`() {
        // (CHK.198) was a residue (self-name row only); tsgo's rows, `bs` legal.
        val r = rows("""
            // @Filename: m.ts
            class B { static bs = 1 }
            class K extends B { static s = 1 }
            export = K;

            // @Filename: main.ts
            import { bs, s, K, x } from "./m";
            export { bs, s, K, x };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:1:17 TS2616 'K' can only be imported by using 'import K = require(\"./m\")' or a default import.",
            "main.ts:1:20 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `a function's expando is importable and any other name is TS2305`() {
        // (CHK.198) was a residue; tsgo: `p` legal, `f` TS2616, `x` TS2305.
        val r = rows("""
            // @Filename: m.ts
            function f() {}
            f.p = 1;
            export = f;

            // @Filename: main.ts
            import { p, f, x } from "./m";
            export { p, f, x };
        """.trimIndent())
        assert(r == listOf(
            "main.ts:1:13 TS2616 'f' can only be imported by using 'import f = require(\"./m\")' or a default import.",
            "main.ts:1:16 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `an enum export equals target exposes its members`() {
        // (CHK.198) was a residue; tsgo: `A` legal, `E` TS2595 (esnext), `x` TS2305.
        val r = rows("""
            // @Filename: m.ts
            enum E { A, B }
            export = E;

            // @Filename: main.ts
            import { A, E, x } from "./m";
            export { A, E, x };
        """.trimIndent(), "// @strict: true\n// @module: esnext")
        assert(r == listOf(
            "main.ts:1:13 TS2595 'E' can only be imported by using a default import.",
            "main.ts:1:16 TS2305 Module '\"./m\"' has no exported member 'x'.",
        ))
    }

    @Test
    fun `a default import names the module by its resolved file not its specifier`() {
        val r = rows("""
            // @Filename: m.ts
            export const a = 1;

            // @Filename: sub/main.ts
            import d from "../m";
            import a from "../m";
            export { d, a };
        """.trimIndent())
        assert(r == listOf(
            "sub/main.ts:1:8 TS1192 Module '\"m\"' has no default export.",
            "sub/main.ts:2:8 TS2613 Module '\"m\"' has no default export. Did you mean to use 'import { a } from \"m\"' instead?",
        ))
    }

    @Test
    fun `control - a default import through a nested relative specifier keeps its directory`() {
        val r = rows("""
            // @Filename: sub/m.ts
            export const a = 1;

            // @Filename: main.ts
            import d from "./sub/m";
            export { d };
        """.trimIndent())
        assert(r == listOf("main.ts:1:8 TS1192 Module '\"sub/m\"' has no default export."))
    }

    @Test
    fun `control - a default import of an export equals module is legal`() {
        assert(rows(value + "import d from \"./m\";\nexport { d };").isEmpty())
    }
}

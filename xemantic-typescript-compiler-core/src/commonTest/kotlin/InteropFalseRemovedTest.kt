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
 * (LEGACY.1)(d2) — the harness-path pins, one per deleted arm family: TypeScript 7 has no
 * `esModuleInterop` / `allowSyntheticDefaultImports` option to honour. tsgo 7.0.2 reads
 * both fields in exactly one place (`program.go:862-868`, the TS5108 row); ES-module
 * interop is always on (`checker.go:14478`), the interop helpers are unconditional
 * (`commonjsmodule.go:695-706`, keyed on the import SHAPE alone), and a synthetic default
 * is decided by the target (`canHaveSyntheticDefault`, `checker.go:14744`). Every
 * expectation here is tsgo's, measured on the 45-project matrix behind
 * `ProjectInteropRemovedTest` (the project-path twin, which carries the positions); the
 * harness path is pinned separately because the corpus's `@esModuleInterop: false` /
 * `@allowSyntheticDefaultImports: false` fixtures are all dropped by
 * `usesUnsupportedOption`, so nothing generated ever reaches these arms.
 *
 * The six families: (1) the Transformer's no-interop `else` arms — twelve sites that
 * emitted a plain `require` — (2) the checker's TS1259 emitter and the two
 * `…ExplicitlyFalse` conjuncts in its synthetic-default gate, (3) the TS2617/TS2596/
 * TS2598 emitter, (4) the ambient-module TS2617 arm, (5) `NameResolver`'s interop gate on
 * the `export =` default-import resolution, and (6) the tsc-6 model of
 * `allowSyntheticDefaultImports` itself — an explicit `true` as a blanket skip of TS1192
 * and a `false` default that refused a declaration file's synthetic default.
 */
class InteropFalseRemovedTest {

    private fun compile(directives: String, files: String): CompilationResult =
        TypeScriptCompiler().compile(directives.trimIndent() + "\n" + files.trimIndent(), "main.ts")

    private fun CompilationResult.js(name: String): String =
        jsOutputs.first { it.first.endsWith(name) }.second

    /** A TypeScript `export =` module plus one importer file per import shape. */
    private val exportEqualsProject = """
        // @Filename: cjs.ts
        function f(): void {}
        export = f;
        // @Filename: d.ts
        import d from "./cjs";
        d();
        // @Filename: ns.ts
        import * as ns from "./cjs";
        ns;
        // @Filename: named.ts
        import { f } from "./cjs";
        f;
    """

    // ── (1) + (2) `esModuleInterop: false` ─────────────────────────────────────────

    @Test
    fun `esModuleInterop false - the default import of an export-equals module is legal and emitted through __importDefault`() {
        val r = compile("// @module: commonjs\n// @esModuleInterop: false", exportEqualsProject)
        assert(r.diagnostics.none { it.code == 1259 })
        assert(r.diagnostics.none { it.code == 2594 })
        assert(r.diagnostics.single { it.code == 5107 }.message.contains("esModuleInterop=false"))
        assert(r.js("d.js").contains("const cjs_1 = __importDefault(require(\"./cjs\"));"))
        assert(r.js("d.js").contains("var __importDefault ="))
    }

    @Test
    fun `esModuleInterop false - the namespace import is emitted through __importStar`() {
        val r = compile("// @module: commonjs\n// @esModuleInterop: false", exportEqualsProject)
        assert(r.js("ns.js").contains("const ns = __importStar(require(\"./cjs\"));"))
        assert(r.js("ns.js").contains("var __importStar ="))
    }

    /** (3) tsgo: TS2616 in the `commonjs` cell whatever the flag says; TS2617 does not exist in TypeScript 7's checker. */
    @Test
    fun `esModuleInterop false - a named import of an export-equals function is TS2616 and never TS2617`() {
        val r = compile("// @module: commonjs\n// @esModuleInterop: false", exportEqualsProject)
        val row = r.diagnostics.single { it.code == 2616 }
        assert(row.message == "'f' can only be imported by using 'import f = require(\"./cjs\")' or a default import.")
        assert(r.diagnostics.none { it.code == 2617 || it.code == 2596 || it.code == 2598 })
    }

    /** (3) the ESM-output wording under the removed value: TS2595, never TS2596. */
    @Test
    fun `esModuleInterop false under esnext - the named import is TS2595 and never TS2596`() {
        val r = compile("// @module: esnext\n// @esModuleInterop: false", exportEqualsProject)
        assert(r.diagnostics.single { it.code == 2595 }.message == "'f' can only be imported by using a default import.")
        assert(r.diagnostics.none { it.code == 2596 || it.code == 2617 })
    }

    /** (1) the three remaining Transformer shapes: default+named, `{ default as x }`, `export * as ns`. */
    @Test
    fun `esModuleInterop false - the default-plus-named, default-as and export-star-as shapes keep their helpers`() {
        val r = compile(
            "// @module: commonjs\n// @esModuleInterop: false",
            """
            // @Filename: m.ts
            export const x = 1;
            export default function m(): void {}
            // @Filename: both.ts
            import c, { x } from "./m";
            c; x;
            // @Filename: defas.ts
            import { default as y } from "./m";
            y;
            // @Filename: star.ts
            export * as ns from "./m";
            """,
        )
        assert(r.js("both.js").contains("const m_1 = __importStar(require(\"./m\"));"))
        assert(r.js("defas.js").contains("const m_1 = __importDefault(require(\"./m\"));"))
        assert(r.js("star.js").contains("exports.ns = __importStar(require(\"./m\"));"))
    }

    /** (2) the other conjunct: `allowSyntheticDefaultImports: false` used to be TS1259 too. */
    @Test
    fun `allowSyntheticDefaultImports false - the default import of an export-equals module is legal`() {
        val r = compile("// @module: commonjs\n// @allowSyntheticDefaultImports: false", exportEqualsProject)
        assert(r.diagnostics.none { it.code == 1259 })
        assert(r.diagnostics.single { it.code == 5107 }.message.contains("allowSyntheticDefaultImports=false"))
        assert(r.js("d.js").contains("__importDefault(require(\"./cjs\"))"))
    }

    // ── (5) the resolver's gate: the default binding IS the export-equals value ───

    /**
     * Under the removed value the default import still resolves to the `export =`
     * function — a deliberate mis-assignment prints the resolved type (`() => void`),
     * which an `any` (the resolver refusing under the old gate) never would.
     */
    @Test
    fun `esModuleInterop false - the default binding still resolves to the export-equals value`() {
        val r = compile(
            "// @module: commonjs\n// @esModuleInterop: false",
            """
            // @Filename: cjs.ts
            function f(): void {}
            export = f;
            // @Filename: d.ts
            import d from "./cjs";
            const s: string = d;
            """,
        )
        assert(r.diagnostics.single { it.code == 2322 }.message == "Type '() => void' is not assignable to type 'string'.")
        assert(r.diagnostics.none { it.code == 1259 })
    }

    // ── (6) the synthetic-default rule itself, at the default ─────────────────────

    /** tsgo: a declaration file with named exports and no default is default-importable (no option needed). */
    @Test
    fun `default - a declaration-file target with named exports and no default is default-importable`() {
        val r = compile(
            "// @module: commonjs",
            """
            // @Filename: lib.d.ts
            export declare const q: number;
            // @Filename: d.ts
            import d from "./lib";
            d;
            """,
        )
        assert(r.diagnostics.none { it.code == 1192 })
    }

    /** tsgo: a declaration file that declares the `__esModule` marker is a compiled ES module — no synthetic default. */
    @Test
    fun `default - a declaration file declaring the __esModule marker has no synthetic default`() {
        val r = compile(
            "// @module: commonjs",
            """
            // @Filename: lib.d.ts
            export declare const __esModule: true;
            export declare const q: number;
            // @Filename: d.ts
            import d from "./lib";
            d;
            """,
        )
        assert(r.diagnostics.single { it.code == 1192 }.message == "Module '\"lib\"' has no default export.")
    }

    /** tsgo: a TypeScript module with no `export =` never has a synthetic default — whatever `allowSyntheticDefaultImports` says. */
    @Test
    fun `allowSyntheticDefaultImports true - a TypeScript module with no default is still TS1192`() {
        val r = compile(
            "// @module: commonjs\n// @allowSyntheticDefaultImports: true",
            """
            // @Filename: esm.ts
            export const e = 1;
            // @Filename: d.ts
            import d from "./esm";
            d;
            """,
        )
        assert(r.diagnostics.single { it.code == 1192 }.message == "Module '\"esm\"' has no default export.")
        assert(r.diagnostics.none { it.code == 5107 })
    }

    /** Control: a real `export default` is a real default, and so it was before. */
    @Test
    fun `control - a module with a real default export is default-importable`() {
        val r = compile(
            "// @module: commonjs",
            """
            // @Filename: m.ts
            export default function m(): void {}
            // @Filename: d.ts
            import d from "./m";
            d();
            """,
        )
        assert(r.diagnostics.isEmpty())
        assert(r.js("d.js").contains("__importDefault(require(\"./m\"))"))
    }

    // ── (3)/(4) the TS2595-or-TS2616 choice is the `module` option's ──────────────

    /** tsgo: `nodenext` is at or above ES2015, so even a CommonJS-scoped importer reads TS2595. */
    @Test
    fun `nodenext - a named import of an export-equals file reads TS2595`() {
        val r = compile("// @module: nodenext", exportEqualsProject)
        assert(r.diagnostics.single { it.code == 2595 }.message == "'f' can only be imported by using a default import.")
        assert(r.diagnostics.none { it.code == 2616 })
    }

    private val ambientProject = """
        // @Filename: amb.d.ts
        declare module "amb" {
            function f(): void;
            export = f;
        }
        // @Filename: named.ts
        import { f } from "amb";
        f;
    """

    /** (4) the ambient-module path: TS2616 under `commonjs` — never TS2617 under the removed value. */
    @Test
    fun `ambient export-equals module - the named import is TS2616 under commonjs whatever esModuleInterop says`() {
        for (directives in listOf("// @module: commonjs", "// @module: commonjs\n// @esModuleInterop: false")) {
            val r = compile(directives, ambientProject)
            assert(r.diagnostics.single { it.code == 2616 }.message == "'f' can only be imported by using 'import f = require(\"amb\")' or a default import.")
            assert(r.diagnostics.none { it.code == 2617 })
        }
    }

    /** (4) tsgo: the same ambient import under `esnext` is TS2595 — the module option decides the wording here too. */
    @Test
    fun `ambient export-equals module - the named import is TS2595 under esnext`() {
        val r = compile("// @module: esnext", ambientProject)
        assert(r.diagnostics.single { it.code == 2595 }.message == "'f' can only be imported by using a default import.")
        assert(r.diagnostics.none { it.code == 2616 })
    }
}

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
 * (LEGACY.0b) step 17, 2026-09-16 — TypeScript 7's CommonJS `exports` model for a `.js` file,
 * decided against tsgo 7.0.2 over 29 scratch shapes.
 *
 * **Two rules, both in tsgo's BINDER.**
 *
 * 1. `binder.go:declareCommonJSVariable` synthesizes `exports` (and `module`) as FILE LOCALS
 *    carrying `SymbolFlagsModuleExports`, and only for a `.js` file whose
 *    `CommonJSModuleIndicator` is set — so with no indicator `exports` resolves to NOTHING and
 *    every reference is **TS2304 `Cannot find name 'exports'`**. The indicator is set by a
 *    `module.exports = X`, an `exports.p = X` / `module.exports.p = X` (ONE level, so
 *    `exports.a.b.c = 0` sets nothing), an `Object.defineProperty(exports | module.exports, …)`
 *    call or a `require(…)` CALL, and `setCommonJSModuleIndicator` REFUSES a file that is
 *    already an EXTERNAL module, so an ES-module `.js` file never binds it either.
 *
 * 2. `checker.go:16513` types such a symbol named `exports` as
 *    `getTypeOfSymbol(resolveExternalModuleSymbol(fileSymbol))`, which an `export=` COLLAPSES
 *    onto its target — so after a `module.exports = X` the module's exported surface IS X's
 *    type and every `exports.p` / `module.exports.p` is a PROPERTY ACCESS on X, never a
 *    declaration of a new export member. Measured: the rule is ORDER-INDEPENDENT (an
 *    `exports.p =` written BEFORE the `module.exports =` reports identically), fires for a
 *    WRITE as well as a read, and holds for a function / arrow / object-literal / `require(…)`
 *    right-hand side alike.
 *
 * Both replace tsc-6 pin walkers: B438d (`checkJsCjsExpandoAliasReads`) emitted a TS2303
 * `Circular definition of import alias 'blah'` tsgo NEVER produces and rendered the receiver as
 * the expando shape `{ (): void; blah: any; }`, and B427 (`checkJsModuleExportsDeepReads`)
 * answered `typeof import("a")` for a file where `exports` is not bound at all. Four
 * `tsgoPendingBaselines` rows close: `pushTypeGetTypeOfAlias`,
 * `jsExportAssignmentNonMutableLocation`, `jsExportMemberMergedWithModuleAugmentation3`,
 * `jsFileCompilationBindDeepExportsAssignment`.
 *
 * `diagnose()` STRIPS its directive lines, so every line number below is of the stripped file.
 */
class JsCommonJsExportModelTest {

    private val js = "// @strict: true\n// @allowJs: true\n// @checkJs: true\n// @module: commonjs\n// @target: es2015"

    private fun rows(d: List<Diagnostic>) =
        d.map { "${it.fileName}(${it.line},${it.character}): TS${it.code}: ${it.message}" }.sorted()

    // ── Mechanism 1: the `export=`-collapsed `exports` receiver ───────────────────────

    @Test
    fun `a member written on exports after a function export equals is TS2339 on the function type`() {
        // tsgo: f.js(2,9): error TS2339: Property 'blah' does not exist on type '() => void'.
        val d = diagnose(
            """
            module.exports = function () {};
            exports.blah = 1;
            """,
            js, fileName = "f.js",
        )
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 9 &&
                it.message == "Property 'blah' does not exist on type '() => void'."
        })
    }

    @Test
    fun `both sides of an exports alias assignment read the collapsed function type - pushTypeGetTypeOfAlias`() {
        // tsgo: TS2339 at (2,9) AND (2,24), both on '() => void'; NO TS2303.
        val d = diagnose(
            """
            module.exports = function () {};
            exports.blah = exports.someProp;
            """,
            js, fileName = "bar.js",
        )
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 9 &&
                it.message == "Property 'blah' does not exist on type '() => void'."
        })
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 24 &&
                it.message == "Property 'someProp' does not exist on type '() => void'."
        })
        assert(d.none { it.code == 2303 })
    }

    @Test
    fun `the rule is order-independent - an exports write BEFORE the export equals reports too`() {
        // tsgo: f.js(1,9): error TS2339 … on '() => void'.
        val d = diagnose(
            """
            exports.blah = 1;
            module.exports = function () {};
            """,
            js, fileName = "f.js",
        )
        assert(d.any {
            it.code == 2339 && it.line == 1 && it.character == 9 &&
                it.message == "Property 'blah' does not exist on type '() => void'."
        })
    }

    @Test
    fun `a read of an absent member on an object-literal export equals is TS2339 on the literal type`() {
        // tsgo: f.js(2,19): error TS2339: Property 'nope' does not exist on type '{ aa: number; }'.
        val d = diagnose(
            """
            module.exports = { aa: 1 };
            const z = exports.nope;
            """,
            js, fileName = "f.js",
        )
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 19 &&
                it.message == "Property 'nope' does not exist on type '{ aa: number; }'."
        })
    }

    @Test
    fun `a misspelled member on an object-literal export equals is TS2551 with the suggestion`() {
        // tsgo: file.js(5,9): error TS2551 … Did you mean 'customSymbol'? + related TS2728.
        val d = diagnose(
            """
            const customSymbol = Symbol("custom");
            module.exports = {
                customSymbol,
            };
            exports.customSymbol2 = Symbol("custom");
            """,
            js, fileName = "file.js",
        )
        val row = d.single { it.code == 2551 }
        assert(row.line == 5)
        assert(row.character == 9)
        assert(
            row.message ==
                "Property 'customSymbol2' does not exist on type '{ customSymbol: symbol; }'. " +
                "Did you mean 'customSymbol'?"
        )
        val related = row.relatedInformation.single()
        assert(related.code == 2728)
        assert(related.message == "'customSymbol' is declared here.")
        assert(related.line == 3)
    }

    @Test
    fun `a module dot exports receiver reads the collapsed type too`() {
        // tsgo: f.js(2,16): error TS2339: Property 'blah' does not exist on type '() => void'.
        val d = diagnose(
            """
            module.exports = function () {};
            module.exports.blah = 1;
            """,
            js, fileName = "f.js",
        )
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 16 &&
                it.message == "Property 'blah' does not exist on type '() => void'."
        })
    }

    @Test
    fun `a require export equals re-exports the module namespace - a type-only export is not a member`() {
        // tsgo: /x.js(1,16): error TS2339: Property 'x' does not exist on type 'typeof import("/y")'.
        val d = diagnose(
            """
            // @Filename: /x.js
            module.exports.x = 1;
            module.exports = require("./y.js");
            // @Filename: /y.d.ts
            export declare type x = 1;
            """,
            js,
        )
        assert(d.any {
            it.code == 2339 && it.fileName == "/x.js" && it.line == 1 && it.character == 16 &&
                it.message == "Property 'x' does not exist on type 'typeof import(\"/y\")'."
        })
    }

    @Test
    fun `the collapsed receiver is reported under emitDeclarationOnly too`() {
        // tsgo has no declaration-only CHECKING mode, so the row fires there exactly as in an
        // ordinary check — jsExportAssignmentNonMutableLocation's whole fixture is
        // emitDeclarationOnly and this is the seam only the corpus used to see.
        val d = diagnose(
            """
            module.exports = { aa: 1 };
            exports.bb = 2;
            """,
            "// @strict: true\n// @declaration: true\n// @emitDeclarationOnly: true" +
                "\n// @allowJs: true\n// @checkJs: true\n// @module: commonjs\n// @target: es2015",
            fileName = "f.js",
        )
        assert(d.any {
            it.code == 2339 && it.line == 2 && it.character == 9 &&
                it.message == "Property 'bb' does not exist on type '{ aa: number; }'."
        })
    }

    @Test
    fun `a DEEP read on an object-literal export equals reports ONCE, on the literal type`() {
        // tsgo: f.js(2,9): error TS2339 … on '{ aa: number; }' — exactly one row. The legacy
        // B427 deep-read walker would add a second on `typeof import("f")`; its export=
        // guard is what stops it.
        val d = diagnose(
            """
            module.exports = { aa: 1 };
            exports.bb.cc = 1;
            """,
            js, fileName = "f.js",
        )
        val rows2339 = d.filter { it.code == 2339 }
        assert(rows2339.size == 1)
        assert(rows2339[0].line == 2)
        assert(rows2339[0].character == 9)
        assert(rows2339[0].message == "Property 'bb' does not exist on type '{ aa: number; }'.")
    }

    @Test
    fun `negative control - a member the export equals target HAS is silent`() {
        val d = diagnose(
            """
            module.exports = { aa: 1 };
            const z = exports.aa;
            """,
            js, fileName = "f.js",
        )
        assert(d.none { it.code == 2339 || it.code == 2551 })
    }

    @Test
    fun `negative control - a js file with only exports writes still DECLARES them`() {
        val d = diagnose(
            """
            exports.a = 1;
            const z = exports.a;
            """,
            js, fileName = "f.js",
        )
        assert(rows(d).isEmpty())
    }

    @Test
    fun `negative control - the ts spelling of an export equals module is untouched`() {
        val d = diagnose(
            """
            declare function f(): void;
            export = f;
            """,
            "// @strict: true\n// @module: commonjs\n// @target: es2015",
            fileName = "t.ts",
        )
        assert(d.none { it.code == 2339 || it.code == 2551 || it.code == 2303 })
    }

    // ── Mechanism 2: `exports` is not bound without a CommonJS module indicator ────────

    @Test
    fun `a deep exports assignment sets NO indicator so exports is TS2304 - jsFileCompilationBindDeepExportsAssignment`() {
        // tsgo: a.js(1,1): error TS2304: Cannot find name 'exports'. — and NOTHING else.
        val d = diagnose(
            """
            exports.a.b.c = 0;
            """,
            js, fileName = "a.js",
        )
        assert(d.any {
            it.code == 2304 && it.line == 1 && it.character == 1 &&
                it.message == "Cannot find name 'exports'."
        })
        assert(d.none { it.code == 2339 })
    }

    @Test
    fun `an ES-module js file never binds exports either`() {
        // tsgo: f.js(2,11): error TS2304: Cannot find name 'exports'.
        val d = diagnose(
            """
            export const a = 1;
            const z = exports;
            """,
            js, fileName = "f.js",
        )
        assert(d.any {
            it.code == 2304 && it.line == 2 && it.character == 11 &&
                it.message == "Cannot find name 'exports'."
        })
    }

    @Test
    fun `negative control - a require call alone is a CommonJS indicator so exports is bound`() {
        // tsgo: TS2339 on `typeof import("f")`, NOT TS2304 — `bindCallExpression`.
        val d = diagnose(
            """
            // @Filename: /f.js
            const y = require("./y.js");
            const z = exports;
            // @Filename: /y.d.ts
            export declare const q: 1;
            """,
            js,
        )
        assert(d.none { it.code == 2304 })
    }

    @Test
    fun `negative control - an exports property assignment binds the name`() {
        val d = diagnose(
            """
            exports.a = 1;
            const z = exports;
            """,
            js, fileName = "f.js",
        )
        assert(d.none { it.code == 2304 })
    }

    @Test
    fun `negative control - a local binding named exports wins`() {
        val d = diagnose(
            """
            const exports = { a: 1 };
            const z = exports.a;
            """,
            js, fileName = "f.js",
        )
        assert(d.none { it.code == 2304 })
    }

    @Test
    fun `negative control - the rule is JS-only, a ts file is untouched`() {
        // A recorded DIVERGENCE: tsgo reports TS2304 for `exports` in a `.ts` file too; this
        // round's walkers are gated on `isJsLikeFileName`, so that population is unmoved.
        val d = diagnose(
            """
            exports.a.b.c = 0;
            """,
            "// @strict: true\n// @module: commonjs\n// @target: es2015",
            fileName = "t.ts",
        )
        assert(d.none { it.code == 2304 })
    }
}

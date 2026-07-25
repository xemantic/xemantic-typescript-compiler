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
 * (M0.4, round 649): pins for the checkDeleteOperator (TS1102
 * delete-identifier-in-strict / TS2703 non-property-ref operand / TS2790
 * non-optional operand / TS2704 read-only property / TS2542 read-only index
 * signature) spine migration. The legacy pass computes ONE per-file
 * `isStrict` boolean (target >= ES2015, strict, alwaysStrict, module file —
 * import/export-DECLARATION/export-assignment statements only — or a
 * `"use strict"` first-statement prologue) and walks a frozen statement +
 * expression subset; anchors are DeleteExpression nodes (paren-unwrapped
 * operand). Frozen reach quirks pinned in BOTH directions below: for-head
 * DECLARATION-LIST initializers, case EXPRESSIONS, class-DECLARATION
 * property initializers, object-literal METHOD/accessor bodies, computed
 * property NAMES, and parameter DEFAULTS are all UNREACHED, while
 * class-EXPRESSION property initializers ARE reached. All expectations
 * verified against the pre-migration walker.
 */
class M04DeleteOpSpineMigrationTest {

    private val declX = "declare var x: any;"

    // ── TS1102/TS2703 — the per-file isStrict routing ──────────────────────

    @Test
    fun `strict via the strict directive - delete identifier draws TS1102 and TS2703`() {
        val ds = diagnose(
            """
            $declX
            delete x;
            """
        )
        assert(ds.count { it.code == 1102 } == 1)
        assert(ds.count { it.code == 2703 } == 1)
    }

    @Test
    fun `TS1102 span is the bare identifier`() {
        val ds = diagnose(
            """
            $declX
            delete x;
            """
        )
        val d = ds.single { it.code == 1102 }
        assert(d.length == 1)
    }

    @Test
    fun `non-strict - delete identifier draws TS2703 only`() {
        val ds = diagnose(
            """
            $declX
            delete x;
            """,
            directives = "// @strict: false"
        )
        assert(ds.count { it.code == 1102 } == 0)
        assert(ds.count { it.code == 2703 } == 1)
    }

    @Test
    fun `strict via module file - export declaration makes the file strict`() {
        val ds = diagnose(
            """
            export {};
            $declX
            delete x;
            """,
            directives = "// @strict: false"
        )
        assert(ds.count { it.code == 1102 } == 1)
        assert(ds.count { it.code == 2703 } == 1)
    }

    @Test
    fun `strict via use-strict prologue`() {
        val ds = diagnose(
            """
            "use strict";
            $declX
            delete x;
            """,
            directives = "// @strict: false"
        )
        assert(ds.count { it.code == 1102 } == 1)
    }

    @Test
    fun `strict via target es2015`() {
        val ds = diagnose(
            """
            $declX
            delete x;
            """,
            directives = "// @strict: false\n// @target: es2015"
        )
        assert(ds.count { it.code == 1102 } == 1)
    }

    @Test
    fun `negative control - export-modified var statement does NOT make the file strict for this pass`() {
        // The legacy isModule preamble counts only ImportDeclaration /
        // ExportDeclaration / ExportAssignment statements — an `export const`
        // VariableStatement does NOT trip it (frozen quirk).
        val ds = diagnose(
            """
            export const y = 1;
            $declX
            delete x;
            """,
            directives = "// @strict: false"
        )
        assert(ds.count { it.code == 1102 } == 0)
        assert(ds.count { it.code == 2703 } == 1)
    }

    @Test
    fun `strict - non-identifier non-property operand draws TS2703 without TS1102`() {
        val ds = diagnose(
            """
            $declX
            delete (x, x);
            """
        )
        assert(ds.count { it.code == 1102 } == 0)
        assert(ds.count { it.code == 2703 } == 1)
    }

    @Test
    fun `negative control - valid property deletes draw nothing`() {
        val ds = diagnose(
            """
            declare var obj: any;
            delete obj.a;
            delete obj["a"];
            """
        )
        assert(ds.count { it.code in setOf(1102, 2703, 2790, 2704, 2542) } == 0)
    }

    @Test
    fun `nested delete - both the outer delete-of-delete and the inner identifier fire`() {
        val ds = diagnose(
            """
            $declX
            delete (delete x);
            """,
            directives = "// @strict: false"
        )
        assert(ds.count { it.code == 2703 } == 2)
    }

    // ── TS2790 (strictNullChecks) ──────────────────────────────────────────

    @Test
    fun `TS2790 - required property fires - optional and undefined-union do not`() {
        val ds = diagnose(
            """
            interface I { a: number; b?: number; c: number | undefined }
            declare const i: I;
            delete i.a;
            delete i.b;
            delete i.c;
            """
        )
        assert(ds.count { it.code == 2790 } == 1)
    }

    @Test
    fun `TS2790 - Object prototype member on an object-literal-typed receiver`() {
        val ds = diagnose(
            """
            declare const o: { a?: number };
            delete o.toString;
            """
        )
        assert(ds.count { it.code == 2790 } == 1)
    }

    @Test
    fun `negative control - TS2790 off without strictNullChecks`() {
        val ds = diagnose(
            """
            interface I { a: number }
            declare const i: I;
            delete i.a;
            """,
            directives = "// @strict: false"
        )
        assert(ds.count { it.code == 2790 } == 0)
    }

    // ── TS2704 / TS2542 (readonly, strict-independent) ─────────────────────

    @Test
    fun `TS2704 - readonly property delete under non-strict`() {
        val ds = diagnose(
            """
            interface I { readonly a: number }
            declare const i: I;
            delete i.a;
            """,
            directives = "// @strict: false"
        )
        assert(ds.count { it.code == 2704 } == 1)
    }

    @Test
    fun `TS2704 - optional readonly property draws TS2704 without TS2790`() {
        val ds = diagnose(
            """
            interface I { readonly a?: number }
            declare const i: I;
            delete i.a;
            """
        )
        assert(ds.count { it.code == 2704 } == 1)
        assert(ds.count { it.code == 2790 } == 0)
    }

    @Test
    fun `TS2704 - class constructor name member`() {
        val ds = diagnose(
            """
            class C {}
            delete C.name;
            """,
            directives = "// @strict: false"
        )
        assert(ds.count { it.code == 2704 } == 1)
    }

    @Test
    fun `TS2542 - readonly string index signature via string-literal element access`() {
        val ds = diagnose(
            """
            interface J { readonly [k: string]: number }
            declare const j: J;
            delete j["a"];
            """,
            directives = "// @strict: false"
        )
        assert(ds.count { it.code == 2542 } == 1)
    }

    // ── Reached positions (TS2703 as the reach signal, non-strict) ─────────

    private fun reach(source: String, expected: Int = 1) {
        val ds = diagnose(
            "declare var x: any;\ndeclare var obj: any;\n" + source.trimIndent(),
            directives = "// @strict: false"
        )
        assert(ds.count { it.code == 2703 } == expected)
    }

    @Test
    fun `reach - top-level expression statement`() = reach("delete x;")

    @Test
    fun `reach - variable initializer`() = reach("let a = delete x;")

    @Test
    fun `reach - return statement`() = reach("function f() { return delete x; }")

    @Test
    fun `reach - if condition then and else`() = reach(
        "if (delete x) { delete x; } else { delete x; }", expected = 3
    )

    @Test
    fun `reach - for expression initializer condition and incrementor`() = reach(
        "for (delete x; delete x; delete x) { delete x; }", expected = 4
    )

    @Test
    fun `reach - for-in and for-of head expressions`() = reach(
        """
        for (var k in [delete x]) {}
        for (const v of [delete x]) {}
        """, expected = 2
    )

    @Test
    fun `reach - while and do bodies and conditions`() = reach(
        "while (delete x) { delete x; }\ndo { delete x; } while (delete x);", expected = 4
    )

    @Test
    fun `reach - switch subject and case body`() = reach(
        "switch (delete x) {}\nswitch (1) { case 1: delete x; break; }", expected = 2
    )

    @Test
    fun `reach - try catch finally`() = reach(
        "try { delete x; } catch (e) { delete x; } finally { delete x; }", expected = 3
    )

    @Test
    fun `reach - function declaration and nested function bodies`() = reach(
        "function f() { delete x; function g() { delete x; } }", expected = 2
    )

    @Test
    fun `reach - class method ctor and accessor bodies`() = reach(
        """
        class C {
            constructor() { delete x; }
            m() { delete x; }
            get g() { return delete x; }
            set s(v: any) { delete x; }
        }
        """, expected = 4
    )

    @Test
    fun `reach - namespace module block`() = reach("namespace N { delete x; }")

    @Test
    fun `reach - labeled statement and throw`() = reach(
        "L: delete x;\nthrow delete x;", expected = 2
    )

    @Test
    fun `reach - arrow expression body arrow block body and function expression`() = reach(
        """
        const f1 = () => delete x;
        const f2 = () => { return delete x; };
        const f3 = function () { delete x; };
        """, expected = 3
    )

    @Test
    fun `reach - array element and object-literal property value and spread`() = reach(
        """
        const a = [delete x];
        const o = { p: delete x };
        const o2 = { ...[delete x] };
        """, expected = 3
    )

    @Test
    fun `reach - call and new arguments plus spread element`() = reach(
        """
        declare function f(...a: any[]): void;
        class D { constructor(a: any) {} }
        f(delete x);
        new D(delete x);
        f(...[delete x]);
        """, expected = 3
    )

    @Test
    fun `reach - binary operands and conditional arms`() = reach(
        """
        const b = (delete x) === true;
        const b2 = true === delete x;
        const c = (delete x) ? delete x : delete x;
        """, expected = 5
    )

    @Test
    fun `reach - cast wrappers and non-null`() = reach(
        """
        (delete x) as boolean;
        (delete x) satisfies boolean;
        <boolean>(delete x);
        (delete x)!;
        """, expected = 4
    )

    @Test
    fun `reach - property and element access positions`() = reach(
        """
        (delete x).valueOf;
        obj[delete x];
        [delete x][0];
        """, expected = 3
    )

    @Test
    fun `reach - template span and tagged template`() = reach(
        """
        declare function tag(a: any, ...r: any[]): any;
        const s = `a${'$'}{delete x}b`;
        tag`a${'$'}{delete x}b`;
        """, expected = 2
    )

    @Test
    fun `reach - void typeof await yield and prefix-unary operands`() = reach(
        """
        void delete x;
        typeof delete x;
        !(delete x);
        async function f() { await (delete x); }
        function* g() { yield delete x; }
        """, expected = 5
    )

    @Test
    fun `reach - class-EXPRESSION property initializer IS walked`() = reach(
        "const C = class { p = delete x; };"
    )

    // ── Unreached positions (frozen quirks, both directions) ───────────────

    @Test
    fun `negative control - for-head declaration-list initializer is unreached`() = reach(
        "for (let a = delete x; false;) {}", expected = 0
    )

    @Test
    fun `negative control - case expression is unreached`() = reach(
        "switch (1) { case [delete x].length: break; }", expected = 0
    )

    @Test
    fun `negative control - class-DECLARATION property initializer is unreached`() = reach(
        "class C { p = delete x; }", expected = 0
    )

    @Test
    fun `negative control - object-literal method and accessor bodies are unreached`() = reach(
        """
        const o = {
            m() { delete x; },
            get g() { return delete x; },
        };
        """, expected = 0
    )

    @Test
    fun `negative control - computed property name is unreached`() = reach(
        "const o = { [delete x]: 1 };", expected = 0
    )

    @Test
    fun `negative control - parameter default is unreached`() = reach(
        "function f(a = delete x) {}", expected = 0
    )

    @Test
    fun `negative control - dts files are skipped`() {
        val ds = diagnose(
            """
            declare var x: any;
            delete x;
            """,
            fileName = "t.d.ts"
        )
        assert(ds.count { it.code == 2703 } == 0)
        assert(ds.count { it.code == 1102 } == 0)
    }
}

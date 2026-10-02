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
 * (CHK.195)(a) NAMESPACE-level `export { … }` clauses, every expectation a tsgo 7.0.2 row.
 *
 * A namespace's export table is keyed by the EXPORTED name: the binder
 * ([Binder.bindNamespaceExportClause]) marks a sibling exported under its own name
 * `ExportValue` and declares an alias for every other entry, which
 * [NameResolver.namespaceClauseTarget] resolves in the clause's own scope. The TS2708
 * surveys and the namespace TS2339 suppression ask [namespaceExportClauseCarriesValue] /
 * [ambientNamespaceHasValue] (tsgo's instance state) instead of a declaration-kind list.
 *
 * Each cell's first line is the `// @strict: true` directive, which the harness strips, so a
 * row's line here is the cell's tsgo line minus one.
 */
class NamespaceExportClauseTest {

    private fun rows(source: String): List<String> =
        diagnose(source, fileName = "a.ts")
            .map { "${it.fileName?.substringAfterLast('/')}(${it.line},${it.character}) TS${it.code} ${it.message}" }
            .sorted()

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t01` — ns2: a false TS2708 on every value read before (CHK.195); the TT type alias and the y rename read through the export table keyed by the EXPORTED name. */
    @Test
    fun `a clause-only ambient namespace exporting outer values is a value namespace`() {
        assert(rows(
            """
            declare const x: number;
            declare class C { p: number; }
            type T = { q: number };
            declare namespace N {
                export { x, C, T as TT };
                export { x as y };
            }
            const a1: string = N.x;
            const a2: string = N.y;
            const a3: string = new N.C().p;
            declare const t: N.TT; const a4: string = t.q;
            declare const c: N.C; const a5: string = c.p;
            N.nope;
            export {};
            """,
        ) == listOf(
            "a.ts(10,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(11,30) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(12,29) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(13,3) TS2339 Property 'nope' does not exist on type 'typeof N'.",
            "a.ts(8,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(9,7) TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t02` — ns1: false TS2339 / TS2694 on x, y, C, TT before (CHK.195), and the 10:7 row was missing. */
    @Test
    fun `a mixed namespace exports its siblings and a rename through the clause`() {
        assert(rows(
            """
            namespace N {
                const x = 1;
                class C { p = 1; }
                type T = { q: number };
                export { x, C, T as TT };
                export { x as y };
            }
            const a1: string = N.x;
            const a2: string = N.y;
            const a3: string = new N.C().p;
            declare const t: N.TT; const a4: string = t.q;
            declare const c: N.C; const a5: string = c.p;
            N.nope;
            export {};
            """,
        ) == listOf(
            "a.ts(10,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(11,30) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(12,29) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(13,3) TS2339 Property 'nope' does not exist on type 'typeof N'.",
            "a.ts(5,5) TS1194 Export declarations are not permitted in a namespace.",
            "a.ts(6,5) TS1194 Export declarations are not permitted in a namespace.",
            "a.ts(8,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(9,7) TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t03` — only the exported names are in the table. */
    @Test
    fun `a renamed and a default clause entry are exported and the declared name is not`() {
        assert(rows(
            """
            declare const x: number;
            declare namespace N {
                export { x as default, x as y };
            }
            const a1: string = N.default;
            const a2: string = N.y;
            N.x;
            export {};
            """,
        ) == listOf(
            "a.ts(5,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(6,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(7,3) TS2339 Property 'x' does not exist on type 'typeof N'.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t04` — the corpus case plus a value read of each export; false TS2694 on Q3.B and Q4.default and a false TS2708 on Q2 before. */
    @Test
    fun `the type alias only clause shapes of namespacesWithTypeAliasOnlyExportsMerge type their members`() {
        assert(rows(
            """
            type A = number;
            declare const Q: number;
            declare namespace Q {
                export { A };
            }
            declare const try1: Q.A;
            declare namespace Q2 {
                export { Q }
            }
            declare const try2: Q2.Q.A;
            declare namespace Q3 {
                export {A as B};
            }
            declare const try3: Q3.B;
            declare namespace Q4 {
                export { Q as default };
            }
            declare const try4: Q4.default.A;
            const s1: string = try1;
            const s2: string = try2;
            const s3: string = try3;
            const s4: string = try4;
            const s5: string = Q2.Q;
            export {};
            """,
        ) == listOf(
            "a.ts(19,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(20,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(21,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(22,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(23,7) TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t05` — NS1 exports nothing with a value meaning, so the bare read is still TS2708 - a control for the value survey. */
    @Test
    fun `a circular pair of type only clauses resolves both ways and stays type only`() {
        assert(rows(
            """
            type A = string;
            type B = number;
            declare namespace NS1 {
                export { NS2, A };
            }
            declare namespace NS2 {
                export { NS1, B };
            }
            export {};
            declare const try1: NS1.A;
            declare const try2: NS2.B;
            declare const try3: NS1.NS2.B;
            declare const try5: NS1.NS2.NS1.A;
            const s1: number = try1;
            const s2: string = try2;
            const s3: string = try3;
            const s5: number = try5;
            NS1;
            """,
        ) == listOf(
            "a.ts(14,7) TS2322 Type 'string' is not assignable to type 'number'.",
            "a.ts(15,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(16,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(17,7) TS2322 Type 'string' is not assignable to type 'number'.",
            "a.ts(18,1) TS2708 Cannot use namespace 'NS1' as a value.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t06` — false TS2339 on N.a and TS2694 on N.I before. */
    @Test
    fun `a clause exporting an unexported sibling value and interface makes both reachable`() {
        assert(rows(
            """
            namespace N {
                export const keep = 1;
                const a = 1;
                interface I { i: number }
                export { a, I };
            }
            const a1: string = N.keep;
            const a2: string = N.a;
            declare const i: N.I; const a3: string = i.i;
            export {};
            """,
        ) == listOf(
            "a.ts(5,5) TS1194 Export declarations are not permitted in a namespace.",
            "a.ts(7,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(8,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(9,29) TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t07` — import k = N.b was a false TS2694 once the rename entered the table - the import-equals reader must read ExportValue. */
    @Test
    fun `an import alias of a clause renamed member reads the member`() {
        assert(rows(
            """
            namespace N {
                const a = 1;
                function g() { return 1; }
                export { a as b, g };
            }
            const a1: string = N.b;
            N.a;
            const a2: string = N.g();
            import k = N.b;
            const a3: string = k;
            export {};
            """,
        ) == listOf(
            "a.ts(10,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(4,5) TS1194 Export declarations are not permitted in a namespace.",
            "a.ts(6,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(7,3) TS2339 Property 'a' does not exist on type 'typeof N'.",
            "a.ts(8,7) TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t08` — acceptableAlias1 shape: tsgo prints nothing on the command line; a false TS2694 before. */
    @Test
    fun `an exported import alias member is exported to an import alias`() {
        assert(rows(
            """
            namespace M {
                export namespace N {
                }
                export import X = N;
            }
            import r = M.X;
            """,
        ).isEmpty())
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t09` — control: TS2708 still fires for a namespace whose clause names only a type. */
    @Test
    fun `a type only clause leaves an ambient namespace without a value`() {
        assert(rows(
            """
            type T = { q: number };
            declare namespace N {
                export { T };
            }
            declare const t: N.T; const a1: string = t.q;
            N.x;
            export {};
            """,
        ) == listOf(
            "a.ts(5,29) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(6,1) TS2708 Cannot use namespace 'N' as a value.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t10` — a false TS2708 before; the member read itself is the residue that P.O2 types any. */
    @Test
    fun `an exported import alias gives an ambient namespace a value meaning`() {
        assert(rows(
            """
            namespace O { export const z = 1; }
            declare namespace P { export import O2 = O; }
            P.O2;
            export {};
            """,
        ).isEmpty())
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t11` — tsgo checkExportSpecifier: the declaration container is a global source file. */
    @Test
    fun `a script namespace clause naming a script global is TS2661`() {
        assert(rows(
            """
            declare const x: number;
            declare namespace N {
                export { x as y };
            }
            const b1: string = N.y;
            """,
        ) == listOf(
            "a.ts(3,14) TS2661 Cannot export 'x'. Only local declarations can be exported from a module.",
            "a.ts(5,7) TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t12` — the ns2 N.nope row: the TS2339 suppression was keyed on the binder instance state, which types every declare namespace as uninstantiated. */
    @Test
    fun `an absent member of an ambient value namespace is TS2339`() {
        assert(rows(
            """
            declare namespace N { const z: number; }
            const a1: string = N.z;
            N.nope;
            export {};
            """,
        ) == listOf(
            "a.ts(2,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(3,3) TS2339 Property 'nope' does not exist on type 'typeof N'.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t13` — the binder binds namespace clauses after the block declarations. */
    @Test
    fun `a clause written above the members it exports still exports them`() {
        assert(rows(
            """
            namespace N {
                export { a, C };
                const a = 1;
                class C { p = 1; }
            }
            const a1: string = N.a;
            const a2: string = new N.C().p;
            export {};
            """,
        ) == listOf(
            "a.ts(2,5) TS1194 Export declarations are not permitted in a namespace.",
            "a.ts(6,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(7,7) TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t14` — an import names no statement for the instance-state survey, so the clause is a value. */
    @Test
    fun `a namespace clause re exporting an import types the import`() {
        assert(rows(
            """
            // @Filename: m.ts
            export const v = 1;
            export type U = { u: number };
            // @Filename: a.ts
            import { v, U } from "./m";
            declare namespace N {
                export { v, U };
            }
            const a1: string = N.v;
            declare const u: N.U; const a2: string = u.u;
            export {};
            """,
        ) == listOf(
            "a.ts(5,7) TS2322 Type 'number' is not assignable to type 'string'.",
            "a.ts(6,29) TS2322 Type 'number' is not assignable to type 'string'.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t15` — the lib declaration lives in a script file. */
    @Test
    fun `a module namespace clause naming a lib global is TS2661`() {
        assert(rows(
            """
            declare namespace N {
                export { Array as Arr };
            }
            export {};
            """,
        ) == listOf(
            "a.ts(2,14) TS2661 Cannot export 'Array'. Only local declarations can be exported from a module.",
        ))
    }

    /** tsgo 7.0.2, `build/bench/p18263-agent/tcells/t16` — control: `declare global` is an augmentation, not a value namespace named `global`; the TS2339 widening for ambient value namespaces must not reach it (tsc's harness profile, `sys.ts` `global.gc`). */
    @Test
    fun `a declare global augmentation does not make a bare global read a namespace member access`() {
        assert(rows(
            """
            declare global {
                var marker: number;
            }
            if (global.gc) {
                global.gc();
            }
            export {};
            """,
        ) == listOf(
            "a.ts(4,5) TS2304 Cannot find name 'global'.",
            "a.ts(5,5) TS2304 Cannot find name 'global'.",
        ))
    }
}

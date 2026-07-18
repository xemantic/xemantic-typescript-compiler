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

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * (cpa-m1) round 577: the LEGACY-side audit instrumentation for the g2
 * (checkPropertyAccess) spine migration — under [Checker.cpaAuditEnabled]
 * the statement dispatcher records a per-statement fingerprint of the
 * threaded + ambient context (currentLocalTypes by DISPLAY / param binding
 * names / enum-constrained params / shadowed names / namespace stack /
 * enclosingClassType / contextualType / inStaticClassMethod) keyed by
 * nodeId. The (cpa-m2) spine frame skeleton will record the same and an
 * audit test diffs the two maps. This test validates the recording itself:
 * coverage, per-context fingerprint content, and off-by-default.
 */
class Inv4CpaAuditTest {

    private fun record(source: String): Map<Int, String> {
        val options = CompilerOptions(strict = true)
        val result = Binder(options).bind(Parser(source.trimIndent(), "t.ts").parse())
        Checker.cpaAuditEnabled = true
        try {
            val checker = Checker(options, listOf(result))
            return HashMap(checker.cpaAuditLegacy)
        } finally {
            Checker.cpaAuditEnabled = false
        }
    }

    @Test
    fun `statements are recorded with context-bearing fingerprints`() {
        val audit = record("""
            const a: number = 1;
            function f(x: string): number {
                const b = x;
                return 42;
            }
            { const c = 3; }
        """)
        assertTrue(audit.size >= 5, "expected >=5 recorded statements, got ${audit.size}: $audit")
        val paramTyped = audit.values.filter { "x=string" in it }
        assertTrue(paramTyped.isNotEmpty(),
            "expected fn-body statements carrying the annotated param x=string in lt[], got: ${audit.values}")
    }

    @Test
    fun `class member bodies carry enclosingClassType and the static flag`() {
        val audit = record("""
            class K {
                p: number = 1;
                m(): number { const im = 1; return this.p; }
                static s() { const st = 1; }
            }
        """)
        assertTrue(audit.values.any { "ect=K" in it && it.endsWith("f=0") },
            "expected an instance-method body print with ect=K and f=0, got: ${audit.values}")
        assertTrue(audit.values.any { "ect=K" in it && it.endsWith("f=1") },
            "expected a static-method body print with ect=K and f=1, got: ${audit.values}")
    }

    @Test
    fun `namespace bodies carry the enclosing-namespace stack`() {
        val audit = record("""
            namespace Outer {
                export namespace Inner {
                    const deep: number = 1;
                }
                const mid: string = "m";
            }
        """)
        assertTrue(audit.values.any { "ns[Outer]" in it },
            "expected a print with ns[Outer], got: ${audit.values}")
        assertTrue(audit.values.any { "ns[Outer,Inner]" in it },
            "expected a print with ns[Outer,Inner], got: ${audit.values}")
    }

    // ------------------------------------------------------------------
    // (cpa-m2a) round 579: the spine-side statement-TIER frame skeleton —
    // every SPINE-recorded fingerprint must agree with the legacy one
    // (spine ⊆ legacy agreement); tier-1 fixtures must be FULLY covered
    // (spine key set == legacy key set), tier-2 chains (arrow/fn-expr
    // bodies, dotted-namespace inners) are excluded on BOTH sides or
    // spine-side only.
    // ------------------------------------------------------------------

    private fun diffSpine(source: String): Triple<List<String>, List<String>, Pair<Int, Int>> {
        val options = CompilerOptions(strict = true)
        val sf = Parser(source.trimIndent(), "t.ts").parse()
        val kinds = HashMap<Int, String>()
        fun walk(n: Node) {
            val id = (n as NodeBase).nodeId
            if (id >= 0) kinds[id] = n::class.simpleName ?: "?"
            forEachChild(n) { walk(it) }
        }
        walk(sf)
        val result = Binder(options).bind(sf)
        Checker.cpaAuditEnabled = true
        try {
            val checker = Checker(options, listOf(result))
            val mismatches = mutableListOf<String>()
            for ((id, spinePrint) in checker.cpaAuditSpine) {
                val legacyPrint = checker.cpaAuditLegacy[id]
                if (legacyPrint != spinePrint) {
                    mismatches.add("node $id (${kinds[id]}):\n  legacy: ${legacyPrint ?: "<not visited>"}\n  spine:  $spinePrint")
                }
            }
            val legacyOnly = checker.cpaAuditLegacy.keys.filter { it !in checker.cpaAuditSpine.keys }
                .map { "node $it (${kinds[it]}): ${checker.cpaAuditLegacy[it]}" }
            return Triple(mismatches, legacyOnly,
                checker.cpaAuditSpine.size to checker.cpaAuditLegacy.size)
        } finally {
            Checker.cpaAuditEnabled = false
        }
    }

    @Test
    fun `spine frames agree with the legacy context and fully cover tier-1 chains`() {
        val (mismatches, legacyOnly, counts) = diffSpine("""
            declare const cond: boolean;
            const a: number = 1;
            let b = a;
            { const c: string = "x"; b = 2; }
            function f(x: string, q?: number): number {
                const d = x;
                if (d) { return 1; }
                while (b) { b = 3; break; }
                for (var i in { ia: 1 }) { const s = i; }
                for (const v of [1, 2]) { const vi = v; }
                do { const di = 1; } while (cond);
                function nested(y: number): number { const ny = y; return y; }
                return 42;
            }
            class K {
                p: number = 1;
                m(y: string): string { const im = y; return y; }
                static s() { const st = 1; }
                constructor(z: number) { const cz = z; }
                get gg(): number { const gz = 1; return this.p; }
                set ss(v: number) { const sv = v; }
                tm(this: K, w: number): number { const tw = w; return w; }
            }
            namespace NS {
                const n: boolean = true;
                export namespace Inner { const deep = 1; }
                export class NK { nm(): number { const inK = 1; return 1; } }
            }
            try { const t1 = 1; } catch (e) { const t2 = 2; } finally { const t3 = 3; }
            switch (b) { case 1: { const s1 = 1; break; } default: { const s2 = 2; } }
            label: { const lb: number = 1; }
            var arr = [1, 2, 3];
            var k1 = arr[0];
            var chained = arr.map(function (n) { return n; });
        """)
        assertTrue(mismatches.isEmpty(),
            "expected spine/legacy agreement, got ${mismatches.size} mismatches:\n" +
                mismatches.take(6).joinToString("\n"))
        // (cpa-m2b): FULL bidirectional coverage — every legacy-visited
        // statement is spine-covered (fn-expr/arrow bodies included).
        assertTrue(legacyOnly.isEmpty(),
            "expected full spine coverage of legacy keys, missing ${legacyOnly.size}:\n" +
                legacyOnly.take(6).joinToString("\n"))
        assertTrue(counts.first == counts.second,
            "expected equal key counts, spine ${counts.first} vs legacy ${counts.second}")
    }

    @Test
    fun `tier-2 frames cover arrow fn-expr and class-expression bodies with ctx registration`() {
        val (mismatches, legacyOnly, _) = diffSpine("""
            declare function cb(f: (a: { p: number }) => void): void;
            cb(a => { const q = a; });
            cb(function (a2) { const q2 = a2; });
            const s = "outer";
            const fe = function (s2: string) { const t = s2; return t; };
            const feRemove = function (s3) { const t3 = s3; return 1; };
            const ar = (n: number): number => { const inA = n; return n; };
            const ce = class {
                cp: number = 1;
                cm() { const ic = this.cp; return 1; }
                static cs() { const st = 1; }
            };
            declare function takes(o: { h: (n: number) => void }): void;
            takes({ h: n => { const z = n; } });
            const nested = [1, 2].map(x => { const u = x; return u; });
        """)
        assertTrue(mismatches.isEmpty(),
            "expected tier-2 spine/legacy agreement, got ${mismatches.size}:\n" +
                mismatches.take(6).joinToString("\n"))
        assertTrue(legacyOnly.isEmpty(),
            "expected tier-2 coverage, missing:\n" + legacyOnly.take(6).joinToString("\n"))
    }

    @Test
    fun `legacy-unreached positions stay excluded on both sides`() {
        // Dotted-namespace inners, for-INIT arrows, ForOf-iterable arrows,
        // tagged-template SPAN arrows — the legacy walkers never reach them;
        // the spine edge classifier must fail closed identically (statements
        // there appear in NEITHER map → equality holds with zero mismatches).
        val (mismatches, legacyOnly, _) = diffSpine("""
            namespace A.B { const dotted = 1; }
            for (let ff = (x: number) => { const fx = x; return x; }, i = 0; i < 1; i++) { const li = i; }
            declare function tag(strings: TemplateStringsArray, v: unknown): string;
            const tt = tag`x ${'$'}{(() => { const sp = 1; return sp; })()}`;
        """)
        assertTrue(mismatches.isEmpty(),
            "expected agreement, got:\n" + mismatches.take(6).joinToString("\n"))
        assertTrue(legacyOnly.isEmpty(),
            "expected no legacy-only keys, got:\n" + legacyOnly.take(6).joinToString("\n"))
    }

    @Test
    fun `the audit is off by default`() {
        val options = CompilerOptions(strict = true)
        val result = Binder(options).bind(Parser("const a: number = 1;", "t.ts").parse())
        val checker = Checker(options, listOf(result))
        kotlin.test.assertEquals(0, checker.cpaAuditLegacy.size,
            "expected no recordings with the flag off")
    }
}

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
import kotlin.test.fail

/**
 * INV.4(c)(i): the spine-maintained lexical scope state
 * (`spineCurrentScope`, backed by the INV.2(c) `lexicalScopes` tables).
 *
 * Every test compiles under the Checker's test-only AUDIT mode, which at
 * every spine ENTER compares the incrementally-maintained scope against an
 * independent parent-chain derivation (the sharp equivalence pin — a
 * push/pop pairing bug or a missed/extra scope activation surfaces as a
 * mismatch at the exact node), and records every Identifier's
 * `spineScopeLookup` resolution as `file:pos:name=symbolId` (or `=∅`).
 * Scope-space symbols carry ids ≤ −2 ([Symbol.scopeSymbol]); main-binder
 * symbols ids ≥ 1 — the trace assertions below pin WHICH table resolved.
 */
class Inv4SpineScopeStateTest {

    /** Compile [source] with the audit enabled; fail on any scope mismatch;
     *  return the identifier-resolution trace. */
    private fun auditTrace(
        source: String,
        directives: String = "// @strict: true",
    ): List<String> {
        Checker.spineScopeAuditEnabled = true
        Checker.spineScopeAuditMismatches.clear()
        Checker.spineScopeAuditTrace.clear()
        try {
            diagnose(source, directives)
            if (Checker.spineScopeAuditMismatches.isNotEmpty()) {
                fail(
                    "spine scope state diverged from the parent-chain derivation:\n" +
                        Checker.spineScopeAuditMismatches.joinToString("\n")
                )
            }
            return Checker.spineScopeAuditTrace.toList()
        } finally {
            Checker.spineScopeAuditEnabled = false
            Checker.spineScopeAuditMismatches.clear()
            Checker.spineScopeAuditTrace.clear()
        }
    }

    /** All resolutions of [name], in document order: symbol id, or null for `∅`.
     *  Trace entry format: `file:pos:name=id` (name never contains `:`/`=`). */
    private fun resolutions(trace: List<String>, name: String): List<Int?> =
        trace.filter { it.substringBeforeLast('=').substringAfterLast(':') == name }
            .map { it.substringAfterLast('=').toIntOrNull() }

    @Test
    fun `kitchen sink file walks with zero scope mismatches`() {
        val trace = auditTrace(
            """
            namespace Deep.Nested {
                export const nsv = 1
                export function fn<T extends object>(p: T): T {
                    let local: T = p
                    for (let i = 0; i < 10; i++) { local = p }
                    for (const el of [1, 2]) { el; }
                    for (const key in { a: 1 }) { key; }
                    try { throw local } catch (err) { err; }
                    while (nsv > 1) { break }
                    do { var hoisted = 1 } while (false)
                    hoisted;
                    switch (nsv) {
                        case 1: { let c = 2; c; break }
                        default: break
                    }
                    class Inner<U> { m(q: U): U { return q } }
                    const fe = function self(): number { return fe ? 0 : 1 }
                    const arrow = (a: number): number => a + hoisted
                    enum E { A = 1, B = A }
                    interface I<V> { v: V }
                    type Alias<W> = W | null
                    const anon = class Named<X> { n(y: X): X { return y } }
                    return p
                }
            }
            const top = Deep.Nested.fn({ z: 1 })
            """,
        )
        val sawEntries = trace.isNotEmpty()
        if (!sawEntries) fail("audit trace is empty — the audit hook never ran")
    }

    @Test
    fun `empty file audits clean`() {
        auditTrace("")
    }

    @Test
    fun `block-scoped let shadows a file-level const across positions`() {
        val trace = auditTrace(
            """
            const shad = 1
            function f(): number {
                { let shad = 2; shad; }
                return shad
            }
            """,
        )
        val ids = resolutions(trace, "shad")
        // decl at file level, block decl, block read, fn-body read:
        // [main, scope, scope, main]
        if (ids.size != 4) fail("expected 4 'shad' resolutions, got $ids")
        val fileId = ids[0]
        val blockId = ids[1]
        if (fileId == null || fileId < 1) fail("file-level 'shad' should resolve to a main-binder symbol (id >= 1), got $fileId")
        if (blockId == null || blockId > -2) fail("block-scoped 'shad' should resolve to a scope-space symbol (id <= -2), got $blockId")
        if (ids[2] != blockId) fail("the read inside the block must resolve to the block binding: $ids")
        if (ids[3] != fileId) fail("the read after the block must resolve back to the file-level binding: $ids")
    }

    @Test
    fun `switch expression resolves OUTSIDE the case scope`() {
        val trace = auditTrace(
            """
            function g(): number {
                switch (probe) {
                    case 1:
                        let probe = 5
                        return probe
                    default:
                        return 0
                }
            }
            """,
        )
        val ids = resolutions(trace, "probe")
        if (ids.size != 3) fail("expected 3 'probe' resolutions, got $ids")
        if (ids[0] != null) fail("the switch EXPRESSION must not see the case-clause binding (binder routes it to the outer scope), got ${ids[0]}")
        val caseId = ids[1]
        if (caseId == null || caseId > -2) fail("the clause 'let probe' must bind a scope-space symbol (id <= -2), got $caseId")
        if (ids[2] != caseId) fail("the clause read must resolve to the clause binding: $ids")
    }

    @Test
    fun `function type parameters resolve throughout the signature and body`() {
        val trace = auditTrace(
            """
            function tp<TP1 extends string>(p: TP1): TP1 {
                let v: TP1 = p
                return v
            }
            """,
        )
        val tpIds = resolutions(trace, "TP1").filterNotNull().distinct()
        if (tpIds.size != 1) fail("all 'TP1' positions must resolve to ONE symbol, got ${resolutions(trace, "TP1")}")
        if (tpIds[0] > -2) fail("a type parameter is a scope-space binding (id <= -2), got ${tpIds[0]}")
        val nullCount = resolutions(trace, "TP1").count { it == null }
        if (nullCount != 0) fail("no 'TP1' position may resolve to nothing: ${resolutions(trace, "TP1")}")
        val pIds = resolutions(trace, "p").filterNotNull().distinct()
        if (pIds.size != 1 || pIds[0] > -2) fail("the parameter 'p' must resolve to one scope-space symbol, got ${resolutions(trace, "p")}")
    }

    @Test
    fun `catch variable binds in the catch scope`() {
        val trace = auditTrace(
            """
            function c(): void {
                try { c() } catch (err) { err; }
            }
            """,
        )
        val ids = resolutions(trace, "err").distinct()
        if (ids.size != 1) fail("both 'err' positions must resolve to the same symbol, got ${resolutions(trace, "err")}")
        val id = ids[0]
        if (id == null || id > -2) fail("the catch variable is a scope-space binding (id <= -2), got $id")
    }

    @Test
    fun `enum members resolve bare in sibling initializers through the main exports`() {
        val trace = auditTrace(
            """
            enum En { A = 1, B = A }
            """,
        )
        val ids = resolutions(trace, "A").distinct()
        if (ids.size != 1) fail("member decl name and sibling reference must agree, got ${resolutions(trace, "A")}")
        val id = ids[0]
        // Top-level enum: main-bound — the enum scope ALIASES the main exports.
        if (id == null || id < 1) fail("a main-bound enum member resolves to the main symbol (id >= 1), got $id")
    }

    @Test
    fun `named function expression self-name is visible in its own body`() {
        val trace = auditTrace(
            """
            const fe = function self(): number { return self ? 0 : 1 }
            """,
        )
        val ids = resolutions(trace, "self").distinct()
        if (ids.size != 1) fail("fn-expr name and body reference must agree, got ${resolutions(trace, "self")}")
        val id = ids[0]
        if (id == null || id > -2) fail("a named fn-expression self-name is a scope-space binding (id <= -2), got $id")
    }

    @Test
    fun `named class expression self-name and class type params resolve inside the body`() {
        val trace = auditTrace(
            """
            class Klass<KT> {
                prop: KT
                constructor(x: KT) { this.prop = x }
            }
            const CE = class SelfName<CT> {
                m(v: CT): CT { return SelfName ? v : v }
            }
            """,
        )
        val ktIds = resolutions(trace, "KT").filterNotNull().distinct()
        if (ktIds.size != 1 || ktIds[0] > -2) fail("class type param 'KT' must resolve to one scope-space symbol everywhere, got ${resolutions(trace, "KT")}")
        if (resolutions(trace, "KT").any { it == null }) fail("no 'KT' position may resolve to nothing: ${resolutions(trace, "KT")}")
        val selfIds = resolutions(trace, "SelfName").distinct()
        if (selfIds.size != 1) fail("class-expression self-name positions must agree, got ${resolutions(trace, "SelfName")}")
        val selfId = selfIds[0]
        if (selfId == null || selfId > -2) fail("a named class-expression self-name is a scope-space binding (id <= -2), got $selfId")
        val ctIds = resolutions(trace, "CT").filterNotNull().distinct()
        if (ctIds.size != 1 || ctIds[0] > -2) fail("class-expression type param 'CT' must resolve to one scope-space symbol, got ${resolutions(trace, "CT")}")
    }

    @Test
    fun `var hoists from a nested block to the function scope`() {
        val trace = auditTrace(
            """
            function h(): number {
                { var hoisted = 1 }
                return hoisted
            }
            """,
        )
        val ids = resolutions(trace, "hoisted").distinct()
        if (ids.size != 1) fail("the block decl and the post-block read must resolve to the SAME hoisted symbol, got ${resolutions(trace, "hoisted")}")
        val id = ids[0]
        if (id == null || id > -2) fail("a function-body var is a scope-space binding (id <= -2, B83.5 — the main binder does not bind it), got $id")
    }

    @Test
    fun `namespace exports resolve bare inside the namespace body`() {
        val trace = auditTrace(
            """
            namespace NS {
                export const nsv = 1
                const use = nsv + 1
            }
            """,
        )
        val ids = resolutions(trace, "nsv").distinct()
        if (ids.size != 1) fail("decl and reference must agree, got ${resolutions(trace, "nsv")}")
        val id = ids[0]
        // Namespace members are main-bound into the merged exports the scope aliases.
        if (id == null || id < 1) fail("a namespace export resolves through the aliased main exports (id >= 1), got $id")
    }

    @Test
    fun `dotted namespace body resolves the innermost segment exports`() {
        val trace = auditTrace(
            """
            namespace D1.D2 {
                export const dv = 1
                const du = dv + 1
            }
            """,
        )
        val ids = resolutions(trace, "dv").distinct()
        if (ids.size != 1) fail("decl and reference must agree, got ${resolutions(trace, "dv")}")
        val id = ids[0]
        if (id == null || id < 1) fail("a dotted-namespace export resolves through the innermost segment's main exports (id >= 1), got $id")
    }

    @Test
    fun `for-of header binding resolves in the loop body`() {
        val trace = auditTrace(
            """
            function fo(): void {
                for (const item of [1, 2, 3]) { item; }
            }
            """,
        )
        val ids = resolutions(trace, "item").distinct()
        if (ids.size != 1) fail("header decl and body read must agree, got ${resolutions(trace, "item")}")
        val id = ids[0]
        if (id == null || id > -2) fail("a for-of header binding is a scope-space symbol (id <= -2), got $id")
    }

    @Test
    fun `multi-file program audits clean and resolves import bindings per file`() {
        val trace = auditTrace(
            """
            // @Filename: a.ts
            export const av = 1
            // @Filename: b.ts
            import { av } from "./a"
            const bv = av + 1
            """,
            directives = "// @module: es2015",
        )
        val ids = resolutions(trace, "av")
        if (ids.isEmpty()) fail("expected 'av' resolutions across both files")
        if (ids.any { it == null }) fail("every 'av' position (export decl, import specifier, use) resolves through its OWN file's tables: $ids")
    }

    @Test
    fun `negative control - a name declared nowhere resolves to nothing`() {
        val trace = auditTrace(
            """
            function n(): void {
                zorpUndeclared;
            }
            """,
        )
        val ids = resolutions(trace, "zorpUndeclared")
        if (ids != listOf<Int?>(null)) fail("an undeclared name must trace as unresolved, got $ids")
    }
}

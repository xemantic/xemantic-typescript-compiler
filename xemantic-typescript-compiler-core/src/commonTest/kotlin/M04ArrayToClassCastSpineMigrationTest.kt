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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (M0.4, round 657): pins for the checkArrayToClassCastOverlap (TS2352 — a
 * `<C<X>>arr` angle-bracket cast from an ARRAY-typed source to a different
 * GENERIC CLASS target that has at least one required non-prototype property
 * the array does not provide) spine migration.
 *
 * This pass is the cheapest migration class of all: it does not own a walk —
 * it drives the SHARED `walkTypeAssertionsInStmt`/-`InExpr` recursion with
 * `::emitTS2352IfArrayToClassMismatch` as the callback, exactly like the
 * round-630 cast-overlap siblings already on the spine. So its reach IS
 * `spineCoStatus`/`spineCoEdge` by construction and the migration is a
 * FOLD-IN: one more leaf call in the existing TypeAssertionExpression arm.
 *
 * What these pins therefore protect is not the reach mapping (shared, already
 * gated) but the three things a fold-in can still get wrong:
 *  - the emitter's own conservative gates (Array source, generic-class target,
 *    a genuinely missing required non-prototype property);
 *  - the AMBIENT the legacy driver installed (the file's binder locals AND
 *    `currentCheckFileName`) — the class name must still resolve;
 *  - co-existence with the sibling TS2352 emitters already on that arm (they
 *    must not start double-emitting at the same cast).
 *
 * Written green against the LEGACY pass at its own init slot, then re-run
 * unchanged against the spine fold-in.
 */
class M04ArrayToClassCastSpineMigrationTest {

    private fun ts2352(ds: List<Diagnostic>) = ds.count { it.code == 2352 }

    /** THIS pass's emissions specifically — the sibling cast-overlap emitters
     *  sharing the same anchor produce a TS2352 with a different chain (or
     *  none), so a shape that draws one of theirs is not a counter-example to
     *  this leaf staying silent. */
    private fun ts2352MissingProp(ds: List<Diagnostic>) = ds.count { d ->
        d.code == 2352 && d.messageChain.any { it.contains("is missing in type") }
    }

    private fun run(@Language("typescript") body: String) = diagnose(body.trimIndent(), "// @target: esnext")

    /** A generic class with one required property — the canonical target. */
    private val cls = """
        class C<X> {
          declare item: X;
        }
    """.trimIndent() + "\n"

    // ── Core emission ─────────────────────────────────────────────────────

    @Test
    fun `an array literal cast to a generic class draws TS2352`() {
        val ds = run(cls + "const c = <C<number>>[1, 2, 3];")
        assert(ts2352(ds) == 1)
        val d = ds.single { it.code == 2352 }
        assert(d.message.contains("may be a mistake because neither type sufficiently overlaps"))
    }

    @Test
    fun `the chain names the first missing required property and both display types`() {
        val d = run(cls + "const c = <C<number>>[1, 2, 3];").single { it.code == 2352 }
        assert(
            d.messageChain == listOf("  Property 'item' is missing in type 'number[]' but required in type 'C<number>'.")
        )
    }

    @Test
    fun `the related info points at the missing property declaration`() {
        val d = run(cls + "const c = <C<number>>[1, 2, 3];").single { it.code == 2352 }
        val rel = d.relatedInformation.single()
        assert(rel.code == 2728)
        assert(rel.message == "'item' is declared here.")
    }

    @Test
    fun `an EMPTY array literal is compared as never array`() {
        val d = run(cls + "const c = <C<number>>[];").single { it.code == 2352 }
        assert(
            d.messageChain == listOf("  Property 'item' is missing in type 'never[]' but required in type 'C<number>'.")
        )
    }

    @Test
    fun `an array-typed VARIABLE cast to a generic class draws TS2352`() {
        val ds = run(cls + "declare const arr: string[];\nconst c = <C<string>>arr;")
        assert(ts2352(ds) == 1)
    }

    @Test
    fun `the squiggle spans the cast through the operand`() {
        val src = (cls + "const c = <C<number>>[1, 2, 3];")
        val d = run(src).single { it.code == 2352 }
        val text = src.trimIndent()
        assert(d.start == text.indexOf("<C<number>>"))
        assert(d.length == "<C<number>>[1, 2, 3]".length)
    }

    // ── The emitter's conservative gates (negative controls) ───────────────

    @Test
    fun `negative control - an array cast to an array draws nothing`() {
        assert(ts2352(run("const a = <number[]>[1, 2, 3];")) == 0)
    }

    @Test
    fun `negative control - a NON-generic class target is not a Type Reference`() {
        val ds = run("class D { declare item: number; }\nconst d = <D>[1, 2, 3];")
        assert(ts2352(ds) == 0)
    }

    @Test
    fun `negative control - a generic INTERFACE target is not a class`() {
        val ds = run("interface I<X> { item: X; }\nconst i = <I<number>>[1, 2, 3];")
        assert(ts2352(ds) == 0)
    }

    @Test
    fun `negative control - an all-OPTIONAL generic class provides nothing missing`() {
        val ds = run("class E<X> { declare item?: X; }\nconst e = <E<number>>[1, 2, 3];")
        assert(ts2352(ds) == 0)
    }

    @Test
    fun `negative control - a NON-array source never reaches the array gate`() {
        // A sibling cast-overlap emitter DOES fire for `string` → `C<string>`
        // (a genuine non-overlap), so the assertion is that THIS leaf stays
        // silent — no missing-property chain.
        val ds = run(cls + "declare const s: string;\nconst c = <C<string>>s;")
        assert(ts2352MissingProp(ds) == 0)
    }

    @Test
    fun `negative control - an as-cast is a different arm and draws nothing here`() {
        // The pass drives the shared walker with a TypeAssertionExpression
        // callback only — an `as` cast is owned by the sibling emitters.
        val ds = run(cls + "const c = [1, 2, 3] as unknown as C<number>;")
        assert(ts2352(ds) == 0)
    }

    // ── Reach (the shared walker's positions — the fold-in must keep them) ──

    @Test
    fun `the cast fires inside a function body`() {
        assert(ts2352(run(cls + "function f() { const c = <C<number>>[1]; }")) == 1)
    }

    @Test
    fun `the cast fires inside a class method body`() {
        assert(ts2352(run(cls + "class K { m() { const c = <C<number>>[1]; } }")) == 1)
    }

    @Test
    fun `the cast fires inside a nested arrow expression body`() {
        assert(ts2352(run(cls + "const f = () => () => <C<number>>[1];")) == 1)
    }

    @Test
    fun `the cast fires inside an object-literal property value`() {
        assert(ts2352(run(cls + "const o = { k: <C<number>>[1] };")) == 1)
    }

    @Test
    fun `the cast fires inside a namespace body`() {
        assert(ts2352(run(cls + "namespace N { export const c = <C<number>>[1]; }")) == 1)
    }

    @Test
    fun `the cast fires in a return position and in a call argument`() {
        val ds = run(
            cls + """
            declare function take(x: any): void;
            function g() { return <C<number>>[1]; }
            take(<C<number>>[2]);
            """.trimIndent()
        )
        assert(ts2352(ds) == 2)
    }

    @Test
    fun `two casts in one file both fire once each`() {
        val ds = run(cls + "const a = <C<number>>[1];\nconst b = <C<string>>[\"x\"];")
        assert(ts2352(ds) == 2)
    }

    // ── Co-existence with the sibling TS2352 emitters on the same anchor ───

    @Test
    fun `a nullish cast keeps drawing exactly one TS2352 from its own emitter`() {
        // emitTS2352IfNullCast owns this shape; the array-to-class leaf must
        // stay silent (the source is not an Array reference).
        val ds = run(cls + "const c = <C<number>>null;")
        assert(ts2352(ds) == 1)
        assert(ds.single { it.code == 2352 }.message.contains("'null'"))
    }

    @Test
    fun `the array-to-class cast draws exactly ONE TS2352 - no sibling double-emit`() {
        val ds = run(cls + "const c = <C<number>>[1, 2, 3];")
        assert(ds.count { it.code == 2352 } == 1)
    }

    // ── Ambient (the class name resolves through the file's own locals) ────

    @Test
    fun `a class declared AFTER the cast still resolves`() {
        val ds = run("const c = <C2<number>>[1];\nclass C2<X> { declare item: X; }")
        assert(ts2352(ds) == 1)
    }

    @Test
    fun `a namespace-local generic class target resolves through the qualified name`() {
        val ds = run(
            """
            namespace M { export class C3<X> { declare item: X; } }
            const c = <M.C3<number>>[1];
            """
        )
        assert(ts2352(ds) == 1)
    }
}

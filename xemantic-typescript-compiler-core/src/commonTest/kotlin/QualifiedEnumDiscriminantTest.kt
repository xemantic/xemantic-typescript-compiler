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
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.100): a namespace-QUALIFIED enum member (`NS.K.B`, `Outer.Inner.K.B`, a
 * barrel-imported `FAR.EntryKind.Node`) narrows exactly as a bare `K.B` does.
 *
 * Every AST reader of the `"symId#member"` discriminant key space took the enum's
 * receiver as `pa.expression as? Identifier` / `qn.left as? Identifier`, so a member
 * written with a qualifier answered null on BOTH sides — the value-position readers
 * and the annotation one — and the union never narrowed: a false TS2339 at every use
 * in the guarded branch and the un-narrowed union in every message. The axis is
 * qualification DEPTH >= 2 and not imports: a same-file `LocalNS.K.B` failed
 * identically, while `import AK = NS.K; AK.B` (a single-segment receiver) always
 * worked and is the control here.
 *
 * EVERY PIN READS THE NARROWED TYPE OUT OF A MESSAGE — a `never` probe for a union
 * subject and a same-flavour literal MEMBER for an enum one — because silence cannot
 * tell correct narrowing from no narrowing from a reference washed to `never`, and a
 * PRIMITIVE probe target prints the generalized enum on both sides.
 *
 * Measured against tsgo 7.0.2 and pristine typescript 6.0.3, which agree on every
 * shape below.
 */
class QualifiedEnumDiscriminantTest {

    private fun compile(source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "entry.ts").diagnostics

    private val prelude = """
        namespace NS { export enum K { A, B } }
        namespace Outer { export namespace Inner { export enum K { A, B } } }
        enum K2 { A, B }
        interface SA { kind: NS.K.A; av: number }
        interface SB { kind: NS.K.B; bv: string }
        type U = SA | SB;
        interface FA { kind: K2.A; av: number }
        interface FB { kind: K2.B; bv: string }
        type FU = FA | FB;
    """.trimIndent() + "\n"

    private fun narrowedUnion(diags: List<Diagnostic>): String? =
        diags.singleOrNull { it.code == 2322 }?.message

    // --- the value-position readers -----------------------------------------------------

    @Test
    fun `a qualified enum member narrows a discriminated union under strict equality`() {
        val d = diagnose(
            prelude + """
            function q(u: U) {
                if (u.kind === NS.K.B) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'SB' is not assignable to type 'never'.")
    }

    @Test
    fun `an unqualified enum member still narrows - the control`() {
        val d = diagnose(
            prelude + """
            function q(u: FU) {
                if (u.kind === K2.B) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'FB' is not assignable to type 'never'.")
    }

    @Test
    fun `a doubly-qualified enum member narrows`() {
        val d = diagnose(
            prelude + """
            interface DA { kind: Outer.Inner.K.A; av: number }
            interface DB { kind: Outer.Inner.K.B; bv: string }
            type DU = DA | DB;
            function q(u: DU) {
                if (u.kind === Outer.Inner.K.B) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'DB' is not assignable to type 'never'.")
    }

    @Test
    fun `a qualified enum member narrows under the negated comparison`() {
        val d = diagnose(
            prelude + """
            function q(u: U) {
                if (u.kind !== NS.K.B) { return u.av }
                const zz: never = u; return zz
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'SB' is not assignable to type 'never'.")
    }

    @Test
    fun `a qualified enum member narrows under loose equality`() {
        val d = diagnose(
            prelude + """
            function q(u: U) {
                if (u.kind == NS.K.B) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'SB' is not assignable to type 'never'.")
    }

    @Test
    fun `a qualified enum member narrows in a switch case`() {
        diagnose(
            prelude + """
            function q(u: U): number | string {
                switch (u.kind) {
                    case NS.K.A: return u.av
                    case NS.K.B: return u.bv
                }
                return 0
            }
            """.trimIndent()
        ) should { have(none { it.code == 2339 }) }
    }

    @Test
    fun `a qualified enum member narrows a bare enum reference to that member`() {
        diagnose(
            prelude + """
            function q(k: NS.K): NS.K.A | number {
                if (k === NS.K.A) { const z: NS.K.A = k; return z }
                return 0
            }
            """.trimIndent()
        ) should { have(none { it.code == 2322 }) }
    }

    // --- the annotation reader ----------------------------------------------------------

    @Test
    fun `the annotation half agrees with the value half - a qualified annotation and a single-segment alias receiver`() {
        // The receiver `AK` is ONE segment, so it resolved before this change; the
        // annotation `NS.K.A` is qualified. The two halves must mint the SAME key
        // (round 425's split key space is the one failure mode it cannot survive).
        val d = diagnose(
            prelude + """
            import AK = NS.K;
            function q(u: U) {
                if (u.kind === AK.B) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'SB' is not assignable to type 'never'.")
    }

    @Test
    fun `a switch over a WHOLE qualified enum is exhaustive`() {
        diagnose(
            prelude + """
            function q(k: NS.K): number {
                switch (k) { case NS.K.A: return 1; case NS.K.B: return 2 }
                const zz: never = k; return zz
            }
            """.trimIndent()
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `a switch over a whole UNqualified enum is exhaustive - the control`() {
        diagnose(
            prelude + """
            function q(k: K2): number {
                switch (k) { case K2.A: return 1; case K2.B: return 2 }
                const zz: never = k; return zz
            }
            """.trimIndent()
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `an exhaustive switch over a whole qualified enum needs no ending return`() {
        // The discriminating shape for the whole-enum arm of the SWITCH-keys reader: the
        // `never` probe above is served by the per-case flow narrowing instead, so it is
        // green with that arm ablated. TS2366 is decided by `requiredEnumSwitchKeys`,
        // which reads the parameter's ANNOTATION and nothing else.
        diagnose(
            prelude + """
            function q(k: NS.K): number {
                switch (k) { case NS.K.A: return 1; case NS.K.B: return 2 }
            }
            """.trimIndent()
        ) should { have(none { it.code == 2366 }) }
    }

    @Test
    fun `an exhaustive switch over a qualified-enum-discriminated union needs no ending return`() {
        diagnose(
            prelude + """
            function q(u: U): number {
                switch (u.kind) { case NS.K.A: return u.av; case NS.K.B: return u.bv.length }
            }
            """.trimIndent()
        ) should {
            have(none { it.code == 2366 })
            have(none { it.code == 2339 })
        }
    }

    // --- the `const EK = NS.K` value alias ----------------------------------------------

    @Test
    fun `a const value alias of a qualified enum narrows`() {
        val d = diagnose(
            prelude + """
            const EK = NS.K;
            function q(u: U) {
                if (u.kind === EK.B) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'SB' is not assignable to type 'never'.")
    }

    @Test
    fun `a const value alias of an unqualified enum narrows`() {
        val d = diagnose(
            prelude + """
            const EF = K2;
            function q(u: FU) {
                if (u.kind === EF.B) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'FB' is not assignable to type 'never'.")
    }

    // --- negative controls --------------------------------------------------------------

    @Test
    fun `a qualified path whose tail is not an enum narrows nothing`() {
        // `NS.Inner` is a namespace, not an enum: the union must stay unnarrowed, which
        // is what the un-narrowed `U` in the probe's message says.
        val d = diagnose(
            prelude + """
            namespace NS2 { export namespace Inner { export const B = 1 } }
            function q(u: U) {
                if (u.kind === (NS2.Inner.B as unknown as NS.K.B)) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.any { it.code == 2322 && it.message == "Type 'U' is not assignable to type 'never'." })
    }

    @Test
    fun `a local binding shadowing the namespace name does not resolve the enum`() {
        val d = diagnose(
            prelude + """
            function q(u: U) {
                const NS = { K: { B: 1 } };
                if (u.kind === (NS.K.B as unknown as NS.K.B)) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.any { it.code == 2322 })
    }

    // --- cross-file: barrels and namespace imports --------------------------------------

    private val lib = """
        // @strict: true

        // @Filename: /proj/src/lib.ts
        export namespace FAR {
            export const enum EK { Span, Node }
            export namespace Deep { export enum DK { X, Y } }
        }

        // @Filename: /proj/src/mid.ts
        export * from './lib'
    """.trimIndent()

    @Test
    fun `a barrel-imported namespace qualifier narrows`() {
        val d = compile(
            lib + "\n\n" + """
            // @Filename: /proj/src/use.ts
            import { FAR } from './mid'
            interface SA { kind: FAR.EK.Span; av: number }
            interface SB { kind: FAR.EK.Node; bv: string }
            type U = SA | SB;
            export function q(u: U) {
                if (u.kind === FAR.EK.Node) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'SB' is not assignable to type 'never'.")
    }

    @Test
    fun `a namespace-import qualifier narrows at depth three`() {
        val d = compile(
            lib + "\n\n" + """
            // @Filename: /proj/src/use.ts
            import * as All from './lib'
            interface SA { kind: All.FAR.EK.Span; av: number }
            interface SB { kind: All.FAR.EK.Node; bv: string }
            type U = SA | SB;
            export function q(u: U) {
                if (u.kind === All.FAR.EK.Node) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'SB' is not assignable to type 'never'.")
    }

    @Test
    fun `a namespace-import qualifier narrows at depth four`() {
        val d = compile(
            lib + "\n\n" + """
            // @Filename: /proj/src/use.ts
            import * as All from './lib'
            interface DA { kind: All.FAR.Deep.DK.X; av: number }
            interface DB { kind: All.FAR.Deep.DK.Y; bv: string }
            type D = DA | DB;
            export function q(u: D) {
                if (u.kind === All.FAR.Deep.DK.Y) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'DB' is not assignable to type 'never'.")
    }

    @Test
    fun `a namespace import of an export-star BARREL narrows`() {
        // tsc's own layout: `_namespaces/ts.ts` does `import * as FindAllReferences from
        // "./ts.FindAllReferences.js"` where that file is a pure `export *` barrel, so the
        // container the descent lands on is a FILE whose own locals hold nothing — the
        // star leg is what reaches the enum, and it is the whole production population
        // (`FindAllReferences.EntryKind.Span` x12 on harness / server / services).
        val d = compile(
            lib + "\n\n" + """
            // @Filename: /proj/src/use.ts
            import * as Mid from './mid'
            import { FAR } from './lib'
            interface SA { kind: FAR.EK.Span; av: number }
            interface SB { kind: FAR.EK.Node; bv: string }
            type U = SA | SB;
            export function q(u: U) {
                if (u.kind === Mid.FAR.EK.Node) { const zz: never = u; return zz }
                return u.av
            }
            """.trimIndent()
        )
        assert(d.size == 1)
        assert(narrowedUnion(d) == "Type 'SB' is not assignable to type 'never'.")
    }

    // --- the grid-forced root fix -------------------------------------------------------

    @Test
    fun `a guard-narrowed property path assigned through a property-access target is silent`() {
        // (CHK.100) grid-forced: [checkPropertyAccessAssignment] had NO flow narrowing at
        // all, so `grouped.signature = entry.node.parent` inside its own guard reported the
        // DECLARED type. Invisible while the receiver typed `any`; the qualified-enum fix
        // made it real and unmasked it on harness / server / services.
        diagnose(
            """
            interface Nd { kind: number; parent: Nd }
            interface MS { kind: number; parent: Nd; ms: string }
            interface VM extends MS { vm: number }
            declare function isVM(n: Nd): n is VM;
            declare const e: { node: Nd };
            declare const h: { sig?: VM };
            export function f() { if (isVM(e.node.parent)) { h.sig = e.node.parent } }
            """.trimIndent()
        ) should { have(none { it.code == 2322 }) }
    }

    @Test
    fun `the same write WITHOUT the guard still reports - the control`() {
        diagnose(
            """
            interface Nd { kind: number; parent: Nd }
            interface MS { kind: number; parent: Nd; ms: string }
            interface VM extends MS { vm: number }
            declare const e: { node: Nd };
            declare const h: { sig?: VM };
            export function f() { h.sig = e.node.parent }
            """.trimIndent()
        ) should { have(any { it.code == 2322 }) }
    }
}

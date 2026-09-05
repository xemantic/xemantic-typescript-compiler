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
 * (CHK.84) / (CHK.86) / (PARITY.3) — the three residues (P18.16) measured beside the
 * enum display generalization, each verified against tsgo 7.0.2 AND pristine
 * `typescript@6.0.3`, which agree on every row quoted here.
 *
 * The file is organised by RULE, and every rule carries a negative control, because all
 * three defects were invisible for the same reason: each is a display or a silence that
 * only differs from tsc on a shape no corpus baseline spells.
 *
 * WHAT IS DELIBERATELY NOT PINNED, both measured and both recorded in the queue:
 *
 *  * (CHK.85), the mutable-binding widening — a MEANING gap and a design, not a display
 *    one: `const o = { v: K.A }; const w: K.A = o.v` is an ERROR in both references
 *    (a mutable object-literal property widens to `K`) and is SILENT here, i.e. we lose
 *    a true positive. Fixing it means changing what `getTypeOfObjectLiteral` records for
 *    every literal in every program.
 *
 *  * The `never` NARROW half of (CHK.86). Both references narrow the true branch of an
 *    impossible enum comparison to `never`, so they report the TS2367 and NOTHING else;
 *    we report the TS2367 and still see the un-narrowed type inside the branch. That is
 *    a genuinely separate mechanism — the diagnostic is decided from the two resolved
 *    TYPES in [Checker.checkEqualityComparisonNoOverlap], while narrowing is decided in
 *    `narrowByEquality` from the SYNTAX of the other operand (`literalTypeOfExpression`
 *    / `enumMemberTypeOfExpr`), which answers null for a bare identifier and for a whole
 *    enum, and would additionally have to collapse a NON-union reference to `never`.
 */
class EnumNeverParityTest {

    // ---------------------------------------------------------------------------
    // (CHK.84) `never` is the bottom type at EVERY position, the return one included.
    // ---------------------------------------------------------------------------

    @Test
    fun `a never source is silently assignable at a return position`() {
        val codes = diagnose(
            """
            declare const n: never
            function f(): string { return n }
            """,
        ).map { it.code }
        assert(codes.isEmpty())
    }

    @Test
    fun `a never source is silently assignable at a return position for every target kind`() {
        val codes = diagnose(
            """
            declare const n: never
            function a(): string { return n }
            function b(): string | number { return n }
            function c(): void { return n }
            function d(x: never): string { return x }
            """,
        ).map { it.code }
        assert(codes.isEmpty())
    }

    @Test
    fun `a never source is silently assignable at declaration assignment and argument positions`() {
        val codes = diagnose(
            """
            declare const n: never
            declare function takeStr(x: string): void
            const s: string = n
            let m: string = "a"
            m = n
            takeStr(n)
            const o: { p: string } = { p: n }
            """,
        ).map { it.code }
        assert(codes.isEmpty())
    }

    @Test
    fun `negative control - a genuine return mismatch is still reported through the same string path`() {
        val messages = diagnose(
            """
            declare const u: unknown
            function f(): string { return u }
            """,
        ).filter { it.code == 2322 }.map { it.message }
        assert(messages == listOf("Type 'unknown' is not assignable to type 'string'."))
    }

    @Test
    fun `negative control - an identifier source that is not never still reaches the return string path`() {
        val messages = diagnose(
            """
            declare const b: boolean
            function f(): string { return b }
            """,
        ).filter { it.code == 2322 }.map { it.message }
        assert(messages == listOf("Type 'boolean' is not assignable to type 'string'."))
    }

    // ---------------------------------------------------------------------------
    // (CHK.86) TS2367 for two enum-flavoured operands that cannot overlap.
    // ---------------------------------------------------------------------------

    private val enums = """
        enum K { A, B }
        enum J { X, Y }
        enum S { P = "p", Q = "q" }
        enum T { P = "p" }
        const enum C { M = 1 }
        declare const k: K
        declare const j: J
        declare const ka: K.A
        declare const kb: K.B
        declare const jx: J.X
        declare const s1: S
        declare const t1: T
        declare const c1: C
        declare const num: number
        declare const kab: K.A | K.B
        declare const kj: K.A | J.X

    """

    private fun ts2367(body: String): List<String> =
        diagnose(enums + body).filter { it.code == 2367 }.map { it.message }

    @Test
    fun `two unrelated enums have no overlap`() {
        assert(
            ts2367("if (k === j) { }") == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'J' have no overlap.",
            ),
        )
    }

    @Test
    fun `two members of one enum have no overlap and keep their member displays`() {
        assert(
            ts2367("if (ka === kb) { }") == listOf(
                "This comparison appears to be unintentional because the types 'K.A' and 'K.B' have no overlap.",
            ),
        )
    }

    @Test
    fun `a member compared to a member access of a sibling has no overlap`() {
        assert(
            ts2367("if (ka === K.B) { }") == listOf(
                "This comparison appears to be unintentional because the types 'K.A' and 'K.B' have no overlap.",
            ),
        )
    }

    @Test
    fun `members of two different enums display their enums rather than their members`() {
        // tsc's `getBaseTypesIfUnrelated`: both operands widen to their enums and, when
        // the WIDENED pair is still unrelated, the widened pair is what is printed. This
        // is the row that separates that rule from "always print what you were given" —
        // the sibling-member case above prints members because K and K DO relate.
        assert(
            ts2367("if (ka === jx) { }") == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'J' have no overlap.",
            ),
        )
    }

    @Test
    fun `a member compared to a whole foreign enum displays both enums`() {
        assert(
            ts2367("if (ka === j) { }") == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'J' have no overlap.",
            ),
        )
    }

    @Test
    fun `two string enums of separate declarations have no overlap`() {
        assert(
            ts2367("if (s1 === t1) { }") == listOf(
                "This comparison appears to be unintentional because the types 'S' and 'T' have no overlap.",
            ),
        )
    }

    @Test
    fun `a const enum has no overlap with a member of another enum`() {
        assert(
            ts2367("if (c1 === K.A) { }") == listOf(
                "This comparison appears to be unintentional because the types 'C' and 'K' have no overlap.",
            ),
        )
    }

    @Test
    fun `a union of members has no overlap with a member of another enum`() {
        assert(
            ts2367("if (kab === jx) { }") == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'J' have no overlap.",
            ),
        )
    }

    @Test
    fun `a numeric enum against a string enum member displays the string enum not its member`() {
        // This row already fired before (CHK.86) — through the comparability-CATEGORY
        // rule, which is not tsc's and printed `'K' and 'S.P'`. The enum rule runs above
        // it precisely so the base-type display decides this pair too.
        assert(
            ts2367("if (k === S.P) { }") == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'S' have no overlap.",
            ),
        )
    }

    @Test
    fun `negative control - an enum overlaps its own member in both directions`() {
        assert(ts2367("if (k === ka) { }\nif (ka === k) { }\nif (k === K.A) { }").isEmpty())
    }

    @Test
    fun `negative control - a member overlaps itself`() {
        assert(ts2367("if (ka === K.A) { }").isEmpty())
    }

    @Test
    fun `negative control - a numeric enum overlaps number`() {
        assert(ts2367("if (k === num) { }").isEmpty())
    }

    @Test
    fun `negative control - a union whose constituents include a match overlaps`() {
        assert(ts2367("if (kj === ka) { }\nif (kj === j) { }\nif (ka === kab) { }").isEmpty())
    }

    @Test
    fun `negative control - an enum against a non enum keeps the category rule answer`() {
        assert(
            ts2367("declare const str: string\nif (k === str) { }") == listOf(
                "This comparison appears to be unintentional because the types 'K' and 'string' have no overlap.",
            ),
        )
    }

    @Test
    fun `the no overlap span covers the whole comparison`() {
        val ds = diagnose(enums + "if (k === j) { }").filter { it.code == 2367 }
        assert(ds.size == 1)
        assert(ds[0].length == "k === j".length)
    }

    // ---------------------------------------------------------------------------
    // (PARITY.3) a GENERALIZED enum source prints its namespace path.
    // ---------------------------------------------------------------------------

    private val namespaces = """
        namespace Ns { export namespace Inner { export enum I { A, B } } }
        namespace Outer.Dotted { export enum OE { A } }
        enum TopE { A }
        declare const en: Ns.Inner.I
        declare const em: Ns.Inner.I.A
        declare const od: Outer.Dotted.OE
        declare const te: TopE

    """

    private fun ts2322(body: String): List<String> =
        diagnose(namespaces + body).filter { it.code == 2322 || it.code == 2345 }.map { it.message }

    @Test
    fun `a namespace scoped enum generalized at a string target prints its namespace path`() {
        assert(
            ts2322("const d: string = en") == listOf(
                "Type 'Ns.Inner.I' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `a namespace scoped enum MEMBER generalizes to the qualified enum`() {
        assert(
            ts2322("const d: string = em") == listOf(
                "Type 'Ns.Inner.I' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `a dotted namespace declaration contributes every segment`() {
        assert(
            ts2322("const d: string = od") == listOf(
                "Type 'Outer.Dotted.OE' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `the qualification reaches the argument position`() {
        assert(
            ts2322("declare function take(x: string): void\ntake(en)") == listOf(
                "Argument of type 'Ns.Inner.I' is not assignable to parameter of type 'string'.",
            ),
        )
    }

    @Test
    fun `the qualification reaches the return position`() {
        assert(
            ts2322("function r(): string { return en }") == listOf(
                "Type 'Ns.Inner.I' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `negative control - a never target keeps the source unqualified because it is not generalized`() {
        // tsc's own asymmetry, and the reason the qualification cannot be a property of
        // the TYPE: the same `Ns.Inner.I` prints qualified above and bare here, decided
        // solely by whether `reportRelationError` entered its generalize branch.
        assert(
            ts2322("const d: never = en") == listOf(
                "Type 'I' is not assignable to type 'never'.",
            ),
        )
    }

    @Test
    fun `negative control - a never target keeps an enum MEMBER source unqualified`() {
        assert(
            ts2322("const d: never = em") == listOf(
                "Type 'I.A' is not assignable to type 'never'.",
            ),
        )
    }

    // --- the `declare global` family. Every expectation below is byte-identical on
    // tsgo 7.0.2 AND pristine 6.0.3; the whole family was MISSED by (P18.17)'s first
    // at-risk sweep, which selected hand-written classes by NAME.

    private fun globalAug(body: String): List<String> = diagnose(
        """
        // @Filename: zzzAug.ts
        export {}
        declare global {
          enum ZzzEnum { A, B }
          namespace N { enum NE { A } }
          namespace Deep { namespace Inner { enum DE { A } } }
        }
        // @Filename: zzzUse.ts
        export {}
        declare const ze: ZzzEnum
        declare const zm: ZzzEnum.A
        declare const ne: N.NE
        declare const de: Deep.Inner.DE
        $body
        """,
    ).filter { it.code == 2322 }.map { it.message }

    @Test
    fun `a declare global block is not a chain segment - its own enum stays bare`() {
        // `declare global` is not a container a consumer can SPELL — it IS the global
        // scope, so its members are reachable unqualified and both references render
        // them bare. Before the guard this read `'global.ZzzEnum'`.
        assert(
            globalAug("export const a: string = ze") == listOf(
                "Type 'ZzzEnum' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `a declare global enum MEMBER generalizes to the bare enum`() {
        assert(
            globalAug("export const a: string = zm") == listOf(
                "Type 'ZzzEnum' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `a real namespace nested inside declare global still qualifies`() {
        // The reason the guard STOPS the walk instead of refusing the whole chain: a
        // namespace inside the block is an ordinary container and both references print
        // it. Refusing outright would read `'NE'` here.
        assert(
            globalAug("export const a: string = ne") == listOf(
                "Type 'N.NE' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `a nested namespace chain inside declare global keeps every segment`() {
        assert(
            globalAug("export const a: string = de") == listOf(
                "Type 'Deep.Inner.DE' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `negative control - a never target keeps a declare global enum and its member bare`() {
        assert(
            globalAug("export const a: never = ze\nexport const b: never = zm\nexport const c: never = ne")
                .sorted() == listOf(
                    "Type 'NE' is not assignable to type 'never'.",
                    "Type 'ZzzEnum' is not assignable to type 'never'.",
                    "Type 'ZzzEnum.A' is not assignable to type 'never'.",
                ),
        )
    }

    @Test
    fun `negative control - a plain namespace named global is an ordinary container and qualifies`() {
        // The guard is keyed on the `declare` MODIFIER and not on the NAME, and this is
        // the row that says so: a namespace that merely happens to be called `global`
        // reads `'global.GE'` in both references.
        val messages = diagnose(
            """
            // @Filename: zzzNs.ts
            export {}
            namespace global { export enum GE { A } }
            declare const ge: global.GE
            export const a: string = ge
            export const b: never = ge
            """,
        ).filter { it.code == 2322 }.map { it.message }.sorted()
        assert(
            messages == listOf(
                "Type 'GE' is not assignable to type 'never'.",
                "Type 'global.GE' is not assignable to type 'string'.",
            ),
        )
    }

    @Test
    fun `negative control - an ambient module container is refused rather than joined as a segment`() {
        // An ambient module's symbol NAME is the bare specifier, so joining it would
        // print `amb.AE` — a string that reads as a namespace and is wrong in a NEW way.
        // Both references print `import("amb").AE` here; the bare `AE` is the
        // pre-existing divergence (P18.14) refused for the module family, and this pin
        // is what keeps this rule from silently widening into it.
        val messages = diagnose(
            """
            // @Filename: d.d.ts
            declare module "amb" { export enum AE { A } }
            // @Filename: t.ts
            import { AE } from "amb"
            declare const q: AE
            const y: string = q
            """,
        ).filter { it.code == 2322 }.map { it.message }
        assert(messages == listOf("Type 'AE' is not assignable to type 'string'."))
    }

    @Test
    fun `negative control - a top level enum is untouched`() {
        assert(
            ts2322("const d: string = te") == listOf(
                "Type 'TopE' is not assignable to type 'string'.",
            ),
        )
    }
}

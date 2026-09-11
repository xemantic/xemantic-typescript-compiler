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
 * (CHK.121) AN **ANNOTATED** BODY-LOCAL RECEIVER — AND THE AXIS IS THE
 * **INITIALIZER**, NOT THE ANNOTATION AND NOT THE SITE.
 *
 * The queue item reads this as "an annotated function-body local is never
 * checked, where a file-level one and a parameter are". Measured against
 * `tools/tsgo-7.0.2/lib/tsc` and pristine `typescript@6.0.3` — which AGREE on all
 * 207 cells of the round's matrix — that is wrong twice over:
 *
 *  - `const v: ZzzCfg = zzzCfgV` in a function body **already reported**, and so
 *    do the `let` and `var` spellings, a nested block and a nested function;
 *  - `const v: ZzzCfg = { a: 1 }` did not. Nor `= zzzMk()`, `= x as T`, `= "s"`,
 *    `= new K()`.
 *
 * Instrumented rather than read: `getTypeOfIdentifier` answers `anyType` for
 * EVERY annotated body-local (B83.5 leaves the declaration unbound and
 * `currentLocalTypes` does not carry it in the property-access pass — measured
 * `inCLT=false` on every shape), and the `any` bail's helpers then decide.
 * `Checker.cmamNarrowedAnyReceiverType` is the one that was answering: an
 * assignment of a REFERENCE narrows `any` to that reference's type. An object
 * literal, a call, an assertion and a scalar literal narrow to nothing, and
 * `cmamUnannotatedLocalReceiverType` refuses them at `decl.type != null` while
 * `cmamBlockScopedReceiverType` refuses them at `t !is Type.Union`.
 *
 * `Checker.cmamAnnotatedLocalReceiverType` reads the ANNOTATION at that bail,
 * LAST — below the flow consultation, so wherever the flow can say anything about
 * the reference the declared type is still never substituted.
 *
 * ### THE VACUITY TRAPS THIS CLASS IS BUILT AROUND
 *
 * Every positive here uses a NON-reference initializer, because a reference one
 * passes on the unfixed binary (`cmamNarrowedAnyReceiverType`) and would pin
 * nothing. `an identifier-initialized annotated local reports - the flow route
 * that was already green` and the file-level / parameter / un-annotated controls
 * are in the class deliberately and labelled, so the two populations can never be
 * confused again.
 *
 * ### THE REFUSALS ARE RECORDED AS REFUSALS, NOT AS GUARANTEES
 *
 * Twelve shapes stay silent BY DECISION and their tests say which gate refuses
 * each. They are not controls: every one is a TS2339 both references report. The
 * gate is (CHK.45)'s knip-calibrated `cmamAllMissingTrustedMember`, reused
 * verbatim, so this round adds no trust of its own — see
 * `AllMissingUnionMemberTest` for what that predicate refuses and why.
 *
 * ### WHAT THESE 31 PINS DISCRIMINATE, MEASURED
 *
 * Ablated one mistake at a time, with a comment-only BOTH-GREEN control (0 RED) and
 * a refuse-everything BOTH-RED control (8 RED = every positive): dropping the
 * `in`-guard consult reddens 2, dropping the trust predicate reddens 2 (the class
 * instance and the generic instantiation), dropping the `const`-only gate reddens 2,
 * dropping the array-like refusal reddens 1. Three guards are UNDISCRIMINATED and
 * say so in their own tests. The 8-profile grid is `added=0 removed=0` while a
 * positive-control build counts 78 / 152 / 116 ACCEPTS on the compiler / harness /
 * services profiles — so that grid is a real test that the downstream gates hold,
 * not a control.
 */
class AnnotatedBodyLocalReceiverTest {

    private val prelude = """
        interface ZzzCfg { a: number }
        interface ZzzBase { b: number }
        interface ZzzCfgH extends ZzzBase { a: number }
        interface ZzzOther { o: number }
        interface ZzzBox<T> { v: T }
        type ZzzAl = { a: number };
        class ZzzK { a: number = 1 }
        enum ZzzE { A, B }
        declare namespace zzzNs { const a: number; }
        declare function zzzMk(): ZzzCfg;
        declare const zzzCfgV: ZzzCfg;
        declare const zzzCfgHV: ZzzCfgH;
        declare const zzzBoxV: ZzzBox<number>;
        declare const zzzIsectV: ZzzCfg & ZzzOther;
        declare const zzzOptV: { a: number } | undefined;
    """.trimIndent() + "\n"

    // --- POSITIVES: the population the round closed --------------------------

    /**
     * The shape the queue item leads with. tsgo 7.0.2 and pristine 6.0.3 both say
     * `Property 'zzzNope' does not exist on type '{ a: number; }'.`
     */
    @Test
    fun `an ANONYMOUS object annotation with an object-literal initializer reports`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: { a: number } = { a: 1 }; v.zzzNope; }"
        )
        assert(d.count { it.code == 2339 } == 1)
        assert(
            d.single { it.code == 2339 }.message ==
                "Property 'zzzNope' does not exist on type '{ a: number; }'."
        )
    }

    /** A heritage-free single-declaration user interface — the trust predicate's own arm. */
    @Test
    fun `an INTERFACE annotation with an object-literal initializer reports`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzCfg = { a: 1 }; v.zzzNope; }"
        )
        assert(d.count { it.code == 2339 } == 1)
        assert(
            d.single { it.code == 2339 }.message ==
                "Property 'zzzNope' does not exist on type 'ZzzCfg'."
        )
    }

    /** A type ALIAS to an object literal type — the alias name is what both references print. */
    @Test
    fun `a type ALIAS annotation with an object-literal initializer reports`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzAl = { a: 1 }; v.zzzNope; }"
        )
        assert(d.count { it.code == 2339 } == 1)
        assert(
            d.single { it.code == 2339 }.message ==
                "Property 'zzzNope' does not exist on type 'ZzzAl'."
        )
    }

    /** A CALL initializer: the flow narrows `any` to nothing, so only the annotation can answer. */
    @Test
    fun `a CALL initializer reports`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzCfg = zzzMk(); v.zzzNope; }"
        )
        assert(d.count { it.code == 2339 } == 1)
        assert(
            d.single { it.code == 2339 }.message ==
                "Property 'zzzNope' does not exist on type 'ZzzCfg'."
        )
    }

    /** An `as` ASSERTION initializer — same mechanism, a second initializer form. */
    @Test
    fun `an as-assertion initializer reports`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzCfg = zzzCfgV as ZzzCfg; v.zzzNope; }"
        )
        assert(d.count { it.code == 2339 } == 1)
    }

    /** A NESTED block: the site was never the axis, and this is what proves it. */
    @Test
    fun `the same shape in a NESTED block reports`() {
        val d = diagnose(
            prelude + "export function f(): void { if (1) { const v: ZzzAl = { a: 1 }; v.zzzNope; } }"
        )
        assert(d.count { it.code == 2339 } == 1)
    }

    /** An ARROW body — a third container, for the same reason. */
    @Test
    fun `the same shape in an ARROW body reports`() {
        val d = diagnose(
            prelude + "export const f = (): void => { const v: ZzzCfg = { a: 1 }; v.zzzNope; };"
        )
        assert(d.count { it.code == 2339 } == 1)
    }

    /** A METHOD body — a fourth. */
    @Test
    fun `the same shape in a METHOD body reports`() {
        val d = diagnose(
            prelude + "export class Q { m(): void { const v: ZzzCfg = { a: 1 }; v.zzzNope; } }"
        )
        assert(d.count { it.code == 2339 } == 1)
    }

    // --- NEGATIVE CONTROLS: each fails if its guard is dropped ---------------

    /**
     * A member that EXISTS must stay silent. This is the control that a wrong
     * receiver type — or an emission placed above the member lookup — reddens.
     */
    @Test
    fun `a member that EXISTS on the annotation stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzCfg = { a: 1 }; v.a; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /** The same, for the anonymous and alias annotations. */
    @Test
    fun `a member that EXISTS on an anonymous or alias annotation stays silent`() {
        val d = diagnose(
            prelude +
                "export function f(): void { const v: { a: number } = { a: 1 }; v.a; }\n" +
                "export function g(): void { const w: ZzzAl = { a: 1 }; w.a; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * `if ('zzzNope' in v) { v.zzzNope }` is LEGAL — tsc narrows an object type by
     * `in` to `T & Record<'zzzNope', unknown>` — and `narrowByInOperator`'s
     * non-union arm deliberately answers the UNCHANGED type, so no identity test
     * can see it. `cmamInGuardMayAddProperty` is the consult; dropping it makes
     * this a false positive on idiomatic duck typing. Both references are silent.
     */
    @Test
    fun `an in-guarded read stays silent`() {
        val d = diagnose(
            prelude +
                "export function f(): void { const v: ZzzCfg = { a: 1 }; if ('zzzNope' in v) { v.zzzNope; } }"
        )
        assert(d.none { it.code == 2339 })
    }

    /** The same guard over the anonymous annotation, which reaches a different trust arm. */
    @Test
    fun `an in-guarded read on an anonymous annotation stays silent`() {
        val d = diagnose(
            prelude +
                "export function f(): void { const v: { a: number } = { a: 1 }; if ('zzzNope' in v) { v.zzzNope; } }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * A correct narrow must keep winning: the flow consultation
     * (`cmamNarrowedAnyReceiverType`) runs ABOVE the new helper, so a reference
     * whose flow type is known never reaches the declared type at all.
     */
    @Test
    fun `a member reached after a correct narrow stays silent`() {
        val d = diagnose(
            """
            interface ZzzA { files: string[] }
            interface ZzzB { other: number }
            declare function zzzIsA(x: ZzzA | ZzzB): x is ZzzA;
            declare const zzzAb: ZzzA | ZzzB;
            export function f(): void {
              const v: ZzzA | ZzzB = zzzAb;
              if (zzzIsA(v)) { v.files; }
            }
            """.trimIndent()
        )
        assert(d.none { it.code == 2339 })
    }

    // --- RECORDED REFUSALS: a TS2339 both references report and we do not ----

    /**
     * REFUSAL, not a control. A CLASS instance annotation is refused by
     * `cmamAllMissingTrustedMember`'s Interface arm (`as? InterfaceDeclaration`),
     * which is (CHK.45)'s arm a9 — the class's own member table is not evidence
     * this walker trusts. Both references report
     * `Property 'zzzNope' does not exist on type 'ZzzK'.`
     */
    @Test
    fun `refusal - a CLASS instance annotation stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzK = new ZzzK(); v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * REFUSAL. `number[]` is a `Type.Reference`, refused by the trust predicate's
     * own line — property resolution for an instantiation goes through
     * `resolveGenericPropertyType`, which is not consulted here. Both references
     * report `... on type 'number[]'.`
     */
    @Test
    fun `refusal - an ARRAY annotation stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: number[] = []; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /** REFUSAL. A user GENERIC instantiation, refused by the same `Type.Reference` line. */
    @Test
    fun `refusal - a GENERIC instantiation annotation stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzBox<number> = zzzBoxV; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /** REFUSAL. An INTERSECTION, refused by the trust predicate's `Type.Intersection` line. */
    @Test
    fun `refusal - an INTERSECTION annotation stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzCfg & ZzzOther = zzzIsectV; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * REFUSAL. A heritage-carrying interface is the B153 population — merged /
     * cross-file interface member resolution is incomplete here, and it is exactly
     * where (CHK.45) measured knip's two false positives.
     */
    @Test
    fun `refusal - a HERITAGE-carrying interface annotation stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzCfgH = zzzCfgHV; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * REFUSAL, and the reason is the CALLER rather than the trust predicate: the
     * apparent-type conversion and the display override that make a primitive
     * receiver reportable live in the tail BELOW this bail, so returning `string`
     * here arrives at `cmamCheckResolvedObjectType` as a non-`Type.Object` and is
     * dropped. Admitting it is a display change, not a receiver-typing one.
     *
     * MEASURED: arm a4 drops the helper's own `t !is Type.Object` gate and this pin
     * reads 0 RED — that tail is what refuses it, so this test discriminates the
     * DECISION and not the gate.
     */
    @Test
    fun `refusal - a PRIMITIVE annotation stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: string = \"s\"; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * REFUSAL. A NULLISH union: (CHK.44) measured supplying such a declared type as
     * 11 rows on the compiler profile and 16 on harness that tsgo does not report —
     * a nullish annotation exists in order to be narrowed, and narrowing a
     * body-local reference is not what this round fixes. Both references report
     * TS18048 here as well, which we also do not.
     *
     * MEASURED: like the primitive above, arm a4 reads 0 RED — a `Type.Union` answer
     * is dropped by the caller's tail too, so the helper's `Type.Object` gate is a
     * statement of intent here and not what this pin sees.
     */
    @Test
    fun `refusal - a NULLISH union annotation stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: { a: number } | undefined = zzzOptV; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * REFUSAL. An ENUM-flavoured type has no `members` table at all (CLAUDE.md: an
     * enum's members live on `Symbol.exports` and on no type), so every member
     * reads as absent and the trust predicate refuses it outright.
     */
    @Test
    fun `refusal - an ENUM annotation stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzE = ZzzE.A; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * REFUSAL. An ARRAY-LIKE annotation — a TUPLE is an anonymous object carrying a
     * NUMBER index signature, which the trust predicate deliberately ADMITS, so the
     * explicit `numberIndexInfo` line is what refuses it: such a receiver reaches
     * its `slice`/`map`/`filter` through the global `Array` interface and
     * `getApparentType` does not supply those here, which is
     * `cmamCheckNestedObjectReceiver`'s measured reason. Both references report.
     */
    @Test
    fun `refusal - a TUPLE annotation stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: [number, string] = [1, \"a\"]; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * A body-local that SHADOWS a file-level binding of the same name resolves to
     * the INNER declaration, which is (CHK.47)'s hazard in the direction that
     * matters: `getTypeOfIdentifier` is no safer than the per-file symbol there —
     * it consults the same tables and answers about the OUTER declaration — so a
     * confidently WRONG type in the message would be worse than silence. The
     * helper reads the declaration syntactically through the INV.2(c) lexical
     * scopes, which is why it names the inner one. Both references agree, on both
     * halves: the inner member is reported by name, and the OUTER type's member is
     * reported as absent.
     *
     * The round wrote this first as a refusal, expecting `currentShadowedNames` to
     * refuse it, and MEASURED the opposite — recorded here rather than pinned as a
     * countdown.
     */
    @Test
    fun `a SHADOWING body-local reports against its INNER declaration`() {
        val d = diagnose(
            prelude + "const zzzShadow: ZzzOther = { o: 1 };\n" +
                "export function f(): void { const zzzShadow: ZzzCfg = { a: 1 }; zzzShadow.zzzNope; }\n" +
                "export function g(): void { const zzzShadow: ZzzCfg = { a: 1 }; zzzShadow.o; }"
        )
        assert(d.count { it.code == 2339 } == 2)
        assert(
            d.filter { it.code == 2339 }.map { it.message }.sorted() == listOf(
                "Property 'o' does not exist on type 'ZzzCfg'.",
                "Property 'zzzNope' does not exist on type 'ZzzCfg'.",
            )
        )
    }

    /**
     * REFUSAL. `const` only, for `cmamUnannotatedLocalReceiverType`'s reason: a
     * reassignable binding is the shape (CHK.44) measured as 3 rows on
     * services/server/harness, and immutability removes the reaching-definition
     * question. A `let` whose initializer is a REFERENCE still reports, through
     * the flow route — see the control below.
     */
    @Test
    fun `refusal - a LET with a non-reference initializer stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { let v: ZzzCfg = { a: 1 }; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /** REFUSAL. The `var` spelling of the same reassignable-binding refusal. */
    @Test
    fun `refusal - a VAR with a non-reference initializer stays silent`() {
        val d = diagnose(
            prelude + "export function f(): void { var v: ZzzCfg = { a: 1 }; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    /**
     * REFUSAL — and this pin is BLIND to the new helper, which is recorded rather
     * than glossed. A name declared TWICE in one body merges into a single
     * scope-space symbol that cannot say which declaration a reference means, and
     * the helper carries the two sibling helpers' `declarations.size != 1` rule for
     * that reason; but arm a5 (drop that rule), arm a7 (drop the
     * `currentLocalTypes`/`currentParamBindingNames`/`currentShadowedNames` guards)
     * and arm a10 (BOTH together) each read 0 RED. So the shape is refused
     * somewhere ABOVE this bail and no guard here is what keeps it silent. The test
     * stays as a boundary record; it is not evidence for the gate.
     */
    @Test
    fun `refusal - a name with TWO declarations in one body stays silent - pin is blind`() {
        val d = diagnose(
            prelude + "export function f(): void { var v: ZzzCfg = { a: 1 }; var v: ZzzCfg = { a: 1 }; v.zzzNope; }"
        )
        assert(d.none { it.code == 2339 })
    }

    // --- CONTROLS THAT WERE ALREADY GREEN -----------------------------------

    /**
     * THE CONTROL THAT NAMES THE TRAP. An IDENTIFIER initializer reported before
     * this round, through `cmamNarrowedAnyReceiverType`'s flow recovery — so a
     * fixture written that way pins nothing about the annotation path. It is here
     * so nobody reads a green one as coverage.
     */
    @Test
    fun `an identifier-initialized annotated local reports - the flow route that was already green`() {
        val d = diagnose(
            prelude + "export function f(): void { const v: ZzzCfg = zzzCfgV; v.zzzNope; }"
        )
        assert(d.count { it.code == 2339 } == 1)
    }

    /** And its `let` spelling, which the flow route also serves. */
    @Test
    fun `an identifier-initialized annotated LET reports - the flow route`() {
        val d = diagnose(
            prelude + "export function f(): void { let v: ZzzCfg = zzzCfgV; v.zzzNope; }"
        )
        assert(d.count { it.code == 2339 } == 1)
    }

    /**
     * The UN-ANNOTATED body-local, served by (CHK.46)'s
     * `cmamUnannotatedLocalReceiverType`. A pin here catches a fix that breaks the
     * sibling it is modelled on.
     */
    @Test
    fun `an UN-ANNOTATED body-local reports - the CHK 46 route`() {
        val d = diagnose(
            prelude + "export function f(): void { const v = { a: 1 }; v.zzzNope; }"
        )
        assert(d.count { it.code == 2339 } == 1)
        assert(
            d.single { it.code == 2339 }.message ==
                "Property 'zzzNope' does not exist on type '{ a: number; }'."
        )
    }

    /** A FILE-LEVEL annotated declaration IS bound, and has always been checked. */
    @Test
    fun `a FILE-LEVEL annotated receiver reports - the control that was already green`() {
        val d = diagnose(
            prelude + "const v: ZzzCfg = { a: 1 };\nv.zzzNope;\nexport {};"
        )
        assert(d.count { it.code == 2339 } == 1)
    }

    /** A PARAMETER has always been checked — the other half of the same control. */
    @Test
    fun `a PARAMETER receiver reports - the control that was already green`() {
        val d = diagnose(prelude + "export function f(v: { a: number }): void { v.zzzNope; }")
        assert(d.count { it.code == 2339 } == 1)
    }
}

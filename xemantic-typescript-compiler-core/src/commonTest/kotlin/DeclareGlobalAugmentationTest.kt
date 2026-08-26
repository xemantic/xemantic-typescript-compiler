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
 * (CHK.50) A `declare global { … }` BLOCK'S EXPORTS NEVER REACHED [Checker.globals].
 *
 * ## The carrier merged and the contents did not
 *
 * `declare global` parses as a `ModuleDeclaration` whose name is the Identifier
 * `global`, so the step-1 merge kept the CARRIER symbol
 * ([Checker.moduleLocalContributesGlobally] answers true for that name) and
 * nothing ever merged its `exports` — which is where every augmented name lives.
 *
 * ## The census, measured — the queue item's framing was wrong
 *
 * The item said the `var` form works, "so the value half is fine and the TYPE
 * half is not". Measured against `tools/tsgo-7.0.2/lib/tsc` on byte-identical
 * source, on the (CHK.51) parent binary rebuilt in the same session, over all
 * eight declaration forms in both scopes:
 *
 *  * `var` — correct ONLY in the declaring file (ordinary file-local
 *    resolution); CROSS-FILE it was silently `any`;
 *  * `function`, `namespace`, `class` — `any` in BOTH scopes: no TS2304,
 *    because [Checker.globalAugmentationNames] suppresses it, and no type either;
 *  * `interface`, `type`, `enum` — TS2304 outright in both scopes;
 *  * an `interface Date { zzzDateAug }` AUGMENTATION — TS2339 on the very member
 *    it had just declared.
 *
 * So only one of eight forms was observable at all, and the "value half"
 * failed as silently as the type half. That silence is the dangerous direction:
 * an `any` is legal everywhere.
 *
 * ## What the fix is, and what it is NOT
 *
 * `init:mergeGlobalAugmentations` merges each LEGAL block's exports into
 * `globals`. **Legality is positive evidence, not a name test**: the walk
 * mirrors [Checker.spineCheckGlobalAugmentation]'s TS2669 predicate by
 * descending only through ambient-module bodies, so a top-level block in a
 * global SCRIPT contributes nothing — which is what tsgo does too, and which the
 * refusal pin below asserts.
 *
 * Every expectation here was read out of tsgo 7.0.2 on the same source rather
 * than hand-written. The `@useRealLibs` + explicit `@lib` directives are
 * load-bearing: the embedded lib has no DOM, so a `Date`/`HTMLElement` pin would
 * otherwise pass vacuously ((CHK.51)'s lesson).
 *
 * The probes are ASSIGNMENTS, never member reads — a WRITE probe prints the
 * type the checker actually has, where a read is silent for `any`, for the
 * correct type, and for a missing member on a receiver whose walker declines.
 */
class DeclareGlobalAugmentationTest {

    private val realLibs = "// @strict: true\n// @useRealLibs: true"
    private val realLibsDom = "// @strict: true\n// @useRealLibs: true\n// @lib: es2020,dom"

    /**
     * The AUGMENTING half, same file: `Date` is heritage-free, so (CHK.51)'s
     * relaxation is not involved — before this round the member the block had
     * just declared was reported MISSING (TS2339), and now it types `number`.
     * The `zzzS` line is what makes the pin a measurement rather than a silence:
     * `any` would satisfy it.
     */
    @Test
    fun `a declare global augmentation of a lib interface is applied in the declaring file`() {
        val diagnostics = diagnose(
            """
            export {}
            declare global { interface Date { zzzDateAug: number } }
            declare const zzzD: Date
            const zzzN: number = zzzD.zzzDateAug
            const zzzS: string = zzzD.zzzDateAug
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2339 })
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322.single().message == "Type 'number' is not assignable to type 'string'.")
    }

    /**
     * …and CROSS-FILE, which is the shape every `@types` package and every app
     * that augments `Window` is written in: the augmenting file is imported for
     * its side effect only, and a file that never mentions it sees the member.
     */
    @Test
    fun `a declare global augmentation of a lib interface is visible from another file`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzAug.ts
            export {}
            declare global { interface Date { zzzDateAug: number } }
            // @Filename: zzzUse.ts
            export {}
            declare const zzzD: Date
            export const zzzS: string = zzzD.zzzDateAug
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2339 })
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322.single().message == "Type 'number' is not assignable to type 'string'.")
    }

    /**
     * The INTRODUCING half — a brand-new global TYPE name. Before this round the
     * name was TS2304 in the declaring file and in every other one; the write
     * probe is what separates "resolves" from "resolves to `any`".
     */
    @Test
    fun `a declare global interface introduces a new global type name`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzAug.ts
            export {}
            declare global { interface ZzzBrand { zzzU: number } }
            // @Filename: zzzUse.ts
            export {}
            declare const zzzB: ZzzBrand
            export const zzzS: string = zzzB.zzzU
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2304 })
        val ts2322 = diagnostics.filter { it.code == 2322 }
        assert(ts2322.size == 1)
        assert(ts2322.single().message == "Type 'number' is not assignable to type 'string'.")
    }

    /**
     * The generalisation over declaration FORM. `type`, `enum` and `class` were
     * each broken differently before this round — the first two TS2304, the third
     * silently `any` — and all three are one merge now. Written cross-file
     * because the declaring file resolves some forms out of its own locals and
     * would understate the defect.
     */
    @Test
    fun `every declaration form inside declare global reaches the global scope`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzAug.ts
            export {}
            declare global {
              var zzzVar: number
              function zzzFn(x: number): number
              class ZzzCls { zzzC: number }
              type ZzzAlias = { zzzA: number }
              enum ZzzEnum { A, B }
            }
            // @Filename: zzzUse.ts
            export {}
            declare const zzzC: ZzzCls
            declare const zzzA: ZzzAlias
            declare const zzzE: ZzzEnum
            export const zzzS1: string = zzzVar
            export const zzzS2: string = zzzFn(1)
            export const zzzS3: string = zzzC.zzzC
            export const zzzS4: string = zzzA.zzzA
            export const zzzS5: string = zzzE
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2304 })
        assert(diagnostics.none { it.code == 2339 })
        val messages = diagnostics.filter { it.code == 2322 }.map { it.message }.sorted()
        assert(messages == listOf(
            "Type 'ZzzEnum' is not assignable to type 'string'.",
            "Type 'number' is not assignable to type 'string'.",
            "Type 'number' is not assignable to type 'string'.",
            "Type 'number' is not assignable to type 'string'.",
            "Type 'number' is not assignable to type 'string'.",
        ))
    }

    /**
     * A namespace inside `declare global` is ambient by CONTEXT and therefore
     * implicitly exports its members — `declare global { namespace NodeJS {
     * interface ProcessEnv … } }` is the canonical shape. The binder gives the
     * inner `namespace` no `declare` modifier of its own, so it is typed
     * `ValueModule` and [Checker.isNameExportedFromNamespace]'s flag test missed;
     * before the merge the receiver typed `any` and nothing asked, so this is a
     * REGRESSION THIS ROUND WOULD HAVE INTRODUCED and not a pre-existing defect —
     * which is why it is pinned with the rest.
     */
    @Test
    fun `a namespace inside declare global implicitly exports its members`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzAug.ts
            export {}
            declare global {
              namespace ZzzNs { const zzzK: number; function zzzF(): number }
            }
            // @Filename: zzzUse.ts
            export {}
            export const zzzS1: string = ZzzNs.zzzK
            export const zzzS2: string = ZzzNs.zzzF()
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2339 })
        assert(diagnostics.filter { it.code == 2322 }.size == 2)
    }

    /**
     * A `global { … }` block DIRECTLY inside an ambient `declare module "spec"`
     * is legal ("…or ambient module declarations") and augments the global scope
     * from a SCRIPT file. Both names were TS2304 before this round.
     */
    @Test
    fun `a global block inside an ambient module declaration augments the global scope`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzAmb.d.ts
            declare module "zzzpkg" {
              global {
                interface ZzzNested { zzzN: number }
                var zzzNestedVar: number
              }
              export const zzzX: number
            }
            // @Filename: zzzUse.ts
            export {}
            declare const zzzI: ZzzNested
            export const zzzS1: string = zzzI.zzzN
            export const zzzS2: string = zzzNestedVar
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2304 })
        assert(diagnostics.filter { it.code == 2322 }.size == 2)
    }

    /**
     * (CHK.51)'S NAMED COST, NOW PAID. In a file that augments `HTMLElement`, a
     * genuinely missing member was TS2339 in tsgo and SILENT here, because
     * [Checker.cmamLibHeritageMembersComplete] refused the whole closure via a
     * `globalAugmentedInterfaceNames` name set — which existed only because the
     * block did not merge. That set is deleted; the all-lib-declarations test now
     * admits a `declare global` InterfaceDeclaration
     * ([Checker.cmamIsGlobalAugmentation]) and the true positive is reported,
     * matching tsgo 7.0.2 on code, message and column.
     */
    @Test
    fun `a missing member on an augmented lib interface with heritage is reported`() {
        val diagnostics = diagnose(
            """
            export {}
            declare global { interface HTMLElement { zzzElAug: number } }
            declare const zzzH: HTMLElement
            export const zzzOk: number = zzzH.zzzElAug
            export const zzzBad = zzzH.zzzNotThere
            """,
            directives = realLibsDom,
        )
        val ts2339 = diagnostics.filter { it.code == 2339 }
        assert(ts2339.size == 1)
        assert(ts2339.single().message == "Property 'zzzNotThere' does not exist on type 'HTMLElement'.")
        assert(diagnostics.none { it.code == 2322 })
    }

    /**
     * REFUSAL 1 — a top-level `declare global` in a global SCRIPT file is TS2669
     * and contributes NOTHING. Measured: tsgo reports TS2669 at the block AND
     * TS2304 at every use of the names it holds, so publishing them would be a
     * false negative on an already-diagnosed error. The assertion is on the
     * TYPE name, which is the half this checker reports; the `var` half is a
     * pre-existing, separate suppression ([Checker.globalAugmentationNames])
     * that this round neither causes nor closes.
     */
    @Test
    fun `a declare global block in a global script file contributes nothing`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzScript.d.ts
            declare global {
              interface ZzzScriptIface { zzzS: number }
            }
            // @Filename: zzzUse.ts
            export {}
            declare const zzzI: ZzzScriptIface
            export const zzzS: string = zzzI.zzzS
            """,
            directives = realLibs,
        )
        assert(diagnostics.any { it.code == 2304 && it.message == "Cannot find name 'ZzzScriptIface'." })
    }

    /**
     * REFUSAL 2 — `namespace globalThis { … }` is tsc's syntax for augmenting the
     * GLOBAL SCOPE ITSELF, not a declaration of a namespace called `globalThis`,
     * so it must not be published as an ordinary global symbol. It was: the
     * corpus case `extendGlobalThis` reddened with a TS2339 on
     * `globalThis.tests`, which pristine tsc does not report. Modelling the real
     * semantics (the block's `var`s become bare globals) is queued as (CHK.53).
     */
    @Test
    fun `a namespace globalThis block is not published as a global symbol`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzGt.d.ts
            declare global {
              namespace globalThis { var zzzGtVar: string }
            }
            export {}
            // @Filename: zzzUse.ts
            export {}
            globalThis.zzzUndeclared = "a"
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2339 })
    }

    /**
     * CONTROL — the (CHK.49) refusal must still hold in the presence of this
     * merge, because the two pull in OPPOSITE directions on the same seam: a
     * module's PLAIN top-level `interface Date` stays module-scoped, while its
     * `declare global { interface Date }` reaches `globals`. Same file, same
     * name, one merges and one does not. Green on the parent by construction;
     * it is a control, not coverage — the discriminating pins for the refusal
     * direction live in `LibGlobalNameShadowTest`.
     */
    @Test
    fun `CONTROL - a plain top-level interface of a lib name still does not merge`() {
        val diagnostics = diagnose(
            """
            // @Filename: zzzShadow.ts
            export interface Date { zzzUnique: number }
            // @Filename: zzzOther.ts
            export {}
            declare const zzzD: Date
            export const zzzN: number = zzzD.getTime()
            """,
            directives = realLibs,
        )
        assert(diagnostics.none { it.code == 2339 })
        assert(diagnostics.none { it.code == 2322 })
    }
}

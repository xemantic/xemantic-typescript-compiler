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
 * (CHK.51) A MISSING MEMBER ON A LIB INTERFACE THAT **EXTENDS** SOMETHING WAS NOT
 * REPORTED AT ALL — `Text`, `Node`, `Element`, `HTMLElement`, i.e. most of the DOM.
 *
 * ## The axis is HERITAGE, not "lib"
 *
 * The queue item said "a real lib interface"; measured, that is wrong in both
 * directions. `Date`, `Map`, `Set`, `Promise`, `RegExp`, `Error`, `JSON`, `Math`,
 * `Symbol`, `Iterable`, `ArrayBuffer`, `EventTarget` and every primitive already
 * reported before this round — every one of them declares NO `extends`. A
 * hand-written `interface D extends B` was silent, exactly like `Text`. What
 * refuses is one line in [Checker.cmamCheckResolvedObjectType]:
 * "Skip if class/interface has base types — incomplete inheritance resolution
 * causes FPs".
 *
 * ## Why the firewall could not simply be deleted
 *
 * Removing it outright measures **89 diagnostics on the compiler profile against
 * 46** — 43 new rows, every one of them a NARROWING gap and not a member-table
 * gap (`canHaveSymbol(e) && e.symbol`, `if (!isIdentifier(node.expression))
 * return; … node.expression.escapedText`). The firewall has been standing in for
 * flow narrowing this checker does not do, and tsc's own sources are written in
 * that style throughout.
 *
 * So the relaxation demands POSITIVE evidence ((CHK.45)'s rule): every type in the
 * receiver's transitive base closure must be an interface whose declarations are
 * ALL lib declarations, none of them augmented by a `declare global` block, each
 * with a member table that actually resolved. A lib interface's members are fully
 * declared in files this compiler ships and parses in one piece, so "absent from
 * the resolved table" is a witnessed verdict; a program interface's member table
 * depends on the receiver having been narrowed to the right subtype, which is the
 * failure the firewall exists for.
 *
 * ## What is deliberately NOT closed, and is mapped rather than pinned
 *
 * A PROGRAM interface with heritage (`interface D extends B`), a MIXED closure
 * (`interface Mine extends HTMLElement`), a CLASS instance with a base, an ARRAY
 * or any receiver with a numeric index signature, and a bare function type are
 * all still silent — the last three through mechanisms that are not this firewall
 * at all. tsgo reports all of them. They are recorded in the (CHK.51) session note
 * and NOT pinned here: round 765 — a pin on a known-open gap is a countdown, not a
 * guard.
 *
 * Every expectation below was checked against `tools/tsgo-7.0.2/lib/tsc` on the
 * same source: identical code, message and column.
 */
class LibHeritageMissingMemberTest {

    private val dom = "// @strict: true\n// @useRealLibs: true\n// @lib: es2020,dom"

    /**
     * The running example. `Text extends CharacterData extends Node extends
     * EventTarget` — a four-level all-lib closure. Silent on the (CHK.49) parent
     * binary rebuilt in the same session.
     */
    @Test
    fun `a missing member on a lib interface with heritage is reported`() {
        val diagnostics = diagnose(
            """
            declare const zzzT: Text
            export const zzzW = zzzT.zzzNotThere
            """,
            directives = dom,
        )
        val ts2339 = diagnostics.filter { it.code == 2339 }
        assert(ts2339.size == 1)
        assert(ts2339.single().message == "Property 'zzzNotThere' does not exist on type 'Text'.")
    }

    /**
     * The generalisation over chain DEPTH: `Node` is one level down from
     * `EventTarget` (which reported before this round, having no heritage of its
     * own), `Element` is two and `HTMLElement` is three with a multi-base level in
     * between. All three were silent on the parent.
     */
    @Test
    fun `the whole DOM inheritance chain is reachable`() {
        val diagnostics = diagnose(
            """
            declare const zzzN: Node
            declare const zzzE: Element
            declare const zzzH: HTMLElement
            export const zzzW1 = zzzN.zzzNotThere
            export const zzzW2 = zzzE.zzzNotThere
            export const zzzW3 = zzzH.zzzNotThere
            """,
            directives = dom,
        )
        val messages = diagnostics.filter { it.code == 2339 }.map { it.message }.sorted()
        assert(messages == listOf(
            "Property 'zzzNotThere' does not exist on type 'Element'.",
            "Property 'zzzNotThere' does not exist on type 'HTMLElement'.",
            "Property 'zzzNotThere' does not exist on type 'Node'.",
        ))
    }

    /**
     * The `Type.Reference` leg of the firewall is a SECOND line with its own
     * `target.baseTypes` test, so a generic instantiation needs its own pin:
     * `CustomEvent<T> extends Event`. Silent on the parent.
     */
    @Test
    fun `a generic instantiation of a lib interface with heritage is reported`() {
        val diagnostics = diagnose(
            """
            declare const zzzC: CustomEvent<number>
            export const zzzW = zzzC.zzzNotThere
            """,
            directives = dom,
        )
        val ts2339 = diagnostics.filter { it.code == 2339 }
        assert(ts2339.size == 1)
        assert(ts2339.single().message ==
            "Property 'zzzNotThere' does not exist on type 'CustomEvent<number>'.")
    }

    /**
     * CONTROL — green on every arm, and NOT counted as coverage. Its job is to say
     * that the relaxation did not buy its diagnostic by breaking inheritance: 24
     * members reached across six DOM types, from every level of the chain and
     * through a generic instantiation.
     */
    @Test
    fun `CONTROL - an inherited member of a lib interface stays silent`() {
        val diagnostics = diagnose(
            """
            declare const zzzH: HTMLElement
            declare const zzzT: Text
            declare const zzzC: CustomEvent<number>
            export const zzzA = zzzH.tagName
            export const zzzB = zzzH.style
            export const zzzD = zzzH.nodeType
            export const zzzE = zzzH.addEventListener
            export const zzzF = zzzH.ownerDocument
            export const zzzG = zzzT.wholeText
            export const zzzI = zzzT.nodeValue
            export const zzzJ = zzzT.splitText
            export const zzzK = zzzC.detail
            export const zzzL = zzzC.bubbles
            """,
            directives = dom,
        )
        assert(diagnostics.none { it.code == 2339 })
        assert(diagnostics.none { it.code == 2551 })
    }

    /**
     * THE REFUSAL THE RELAXATION IS BOUGHT WITH, direction 1: a `declare global`
     * interface augmentation of a lib name does NOT reach `globals` in this
     * checker ((CHK.50), open and measured on the (CHK.49) parent), so the lib
     * symbol's declaration list still reads "all lib" and the "every declaration
     * is a lib declaration" test cannot see the augmentation. Without
     * [Checker.globalAugmentedInterfaceNames] this relaxation would turn
     * (CHK.50)'s silent false NEGATIVE into a false POSITIVE on the shape every
     * `@types` package is written in. tsgo is silent here; so are we.
     */
    @Test
    fun `a declare global augmentation of a lib interface is not reported as missing`() {
        val diagnostics = diagnose(
            """
            declare global { interface HTMLElement { zzzAug: number } }
            declare const zzzH: HTMLElement
            export const zzzW = zzzH.zzzAug
            """,
            directives = dom,
        )
        assert(diagnostics.none { it.code == 2339 })
    }

    /**
     * THE REFUSAL, direction 2 — and the one that is worth 43 rows on the compiler
     * profile.
     *
     * THE SHAPE MATTERS AND THE OBVIOUS ONE IS BLIND. A guard whose predicate type
     * is a SUBTYPE of the receiver's (`x is ZzzSub` for an `x: ZzzBase`) narrows
     * correctly here, so a fixture written that way is silent on the ablated binary
     * too — measured, arm a1 read **0 RED with the compiler profile at 89**. What
     * this checker cannot do is the INTERSECTION narrow tsc performs when the
     * predicate names a SIBLING: `canHaveSymbol(node: Node): node is Declaration`
     * applied to an `e: Expression` gives tsc `Expression & Declaration` and gives
     * us `Expression` unchanged, so the member read is judged against the
     * un-narrowed receiver. That is `checker.ts:32231` verbatim, and the shape
     * below is its four-line reduction — tsgo 7.0.2 is silent on it.
     *
     * Written as an FP pin rather than as "a program interface stays silent"
     * DELIBERATELY: the latter is a countdown that a future round closing the
     * narrowing would have to delete, where this one must hold forever.
     */
    @Test
    fun `a member reached only after a sibling type-guard narrow is not reported missing`() {
        val diagnostics = diagnose(
            """
            interface ZzzNode { zzzKind: number }
            interface ZzzExpr extends ZzzNode { zzzExprBrand: number }
            interface ZzzDecl extends ZzzNode { zzzSymbol: number }
            declare function zzzCan(n: ZzzNode): n is ZzzDecl
            export function zzzF(e: ZzzExpr): number {
                if (zzzCan(e) && e.zzzSymbol) { return 1 }
                return 0
            }
            """,
            directives = dom,
        )
        assert(diagnostics.none { it.code == 2339 })
        assert(diagnostics.none { it.code == 2551 })
    }
}

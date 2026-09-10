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
 * (INV.0) step 10a — a TYPE reference resolves a declaration the main binder never
 * bound (B83.5), for the whole TYPE space and not only for `enum`.
 *
 * ## What B83.5 is, and why every fixture here is shaped the way it is
 *
 * `Binder` recurses into statements from exactly two places — a [SourceFile]'s own
 * list and a `ModuleBlock`'s — so it is not "declarations nested in a `Block`" that go
 * unbound but *everything that is not a direct statement of one of those two*. A
 * `class` at the very TOP of a function body, with no block nesting at all, is equally
 * unbound. The fixtures use that shape deliberately: it is the smallest one that
 * exhibits the defect, and a reader who assumes a `Block` is required will write a
 * fixture that measures nothing.
 *
 * Round 748 closed the `enum` half of this in TYPE position. This class is the other
 * three kinds — `interface`, `class`, `type` — plus the ordering question the enum
 * half never had to answer, because [Checker.getTypeFromTypeReference] asks the
 * enclosing-NAMESPACE chain itself and then calls
 * [NameResolver.resolveTypeNameToSymbol] with `enclosingNamespacesDone = true`, i.e.
 * past that function's own lexical-first arm.
 *
 * ## Why the assertions are on MESSAGE TEXT and never on silence
 *
 * The failure mode being closed is SILENT in both directions: a UNIQUE name resolved
 * to nothing, so the annotation degraded to `any` and every check under it went quiet;
 * a SHADOWING name resolved to the OUTER declaration, which is a wrong answer with
 * nothing at all to detect. So a pin that asserts "no error here" passes on a broken
 * binary, and one that asserts "an error here" cannot say WHICH declaration answered.
 * Every pin below reads the resolved type's NAME, or a member name that only one of
 * the two candidate declarations has, out of the diagnostic text.
 *
 * ## The ablations, one per pin, each measured
 *
 *  1. `an interface at the top of a function body types an annotation` — put the GATE
 *     back to the round-748 one: `if (name !in checker.lexicalBlockScopedTypeNames)` →
 *     `if (name !in checker.lexicalBlockScopedEnumNames)` in
 *     [NameResolver.lexicalTypeSymbolForNode]. Everything scope-space that is not an
 *     `enum` disappears from the gate.
 *  2. `a class at the top of a function body types an annotation` — put the FLAG MASK
 *     back: `flags = SymbolFlags.ScopeTypeDeclaration` → `flags = SymbolFlags.Enum` in
 *     the same function. The gate still admits the name and the ascent still finds the
 *     scope; only the kind test refuses it. This is the arm that separates the two
 *     halves of the widening, which one arm cannot.
 *  3. `an inner declaration wins over a same named outer one` — the only pin that
 *     declares one name at TWO levels, so the only one that can see a resolution-ORDER
 *     defect rather than a miss. Arm: make the consult a FALLBACK instead of first, by
 *     moving `lexicalTypeSymbolForNode(node, node.text)?.let { return it }` in
 *     [NameResolver.resolveTypeNameToSymbol] below the `lookupPerFileForNode` call.
 *  4. `an enclosing namespace does not displace a function body declaration` — the
 *     only pin with a namespace. Arm: drop the
 *     `lexicalTypeSymbolForNode(it, it.text) ?:` leg from
 *     [Checker.getTypeFromTypeReference], leaving `lookupInEnclosingNamespaces` first
 *     as it was before this step. Pin 3's arm does NOT redden this one, and this arm
 *     does not redden pin 3 — the two consults are at different sites.
 *  5. `a conventionally bound name is untouched by the scope space consult` — the
 *     CONTAINMENT control, and it is the reason this change cannot move an existing
 *     answer: `declareLexical` refuses any name the main binder already bound in that
 *     container, so `scope.symbols` never holds one. Arm: read
 *     [LexicalScope.existing] as a fallback inside `LexicalScopeResolver.symbolAt`
 *     (round 748's forbidden read) — only this pin has a file-level declaration whose
 *     name a function body re-uses in an INCOMPATIBLE way.
 */
class ScopeSpaceTypeResolutionTest {

    /**
     * The prize, measured against tsgo 7.0.2 and pristine `typescript@6.0.3` on
     * 2026-09-10: both report TS2353 and TS2322 here and this compiler reported
     * NOTHING, because `ZzzShape` resolved to no symbol and the annotation degraded to
     * `any`. Two rows, and the second one NAMES the type, which is what makes this a
     * value pin rather than a count.
     */
    @Test
    fun `an interface at the top of a function body types an annotation`() {
        val d = diagnose(
            """
            export function outerFn(): void {
                interface ZzzShape { a: number }
                const s: ZzzShape = { a: 1, bogus: 2 }
                const probe: string = s
            }
            """,
        )
        val messages = d.map { it.message }
        assert(
            messages == listOf(
                "Object literal may only specify known properties, and 'bogus' does not exist in type 'ZzzShape'.",
                "Type 'ZzzShape' is not assignable to type 'string'.",
            )
        )
    }

    /**
     * The kind the round-748 flag mask ([SymbolFlags.Enum]) could never admit. A class
     * is BOTH a type and a value here, and only the type half is this step's business —
     * the annotation resolves, `new ZzzCls()` does not (the VALUE-space consult is step
     * 10b), which is why the probe is an annotation and not a construction.
     */
    @Test
    fun `a class at the top of a function body types an annotation`() {
        val d = diagnose(
            """
            export function outerFn(): void {
                class ZzzCls { m(): number { return 1 } }
                const c: ZzzCls = { m: () => 1 }
                const probe: string = c
            }
            """,
        )
        assert(d.any { it.message == "Type 'ZzzCls' is not assignable to type 'string'." })
    }

    /**
     * A `type` alias, i.e. the kind whose ARITY round 945 already read out of scope
     * space ([Checker.lexicalTypeAliasArity]) while its RESOLUTION still went to the
     * outer world. The two now come from the same place.
     */
    @Test
    fun `a type alias in a nested block types an annotation`() {
        val d = diagnose(
            """
            export function outerFn(flag: boolean): void {
                if (flag) {
                    type ZzzAl = { a: number }
                    const s: ZzzAl = { a: 1 }
                    const probe: string = s
                }
            }
            """,
        )
        assert(d.any { it.message == "Type 'ZzzAl' is not assignable to type 'string'." })
    }

    /**
     * The resolution-ORDER half, and the only shape whose old answer was WRONG rather
     * than absent: before this step the inner `ZzzShape` was invisible and the
     * annotation was judged against the FILE-LEVEL one, so the object literal carrying
     * `innerOnly` was an error and the one carrying `outerOnly` was not — exactly
     * inverted. Both assertions are needed: either alone is satisfied by a binary that
     * resolves nothing at all.
     */
    @Test
    fun `an inner declaration wins over a same named outer one`() {
        val d = diagnose(
            """
            interface ZzzShape { outerOnly: number }
            export function outerFn(): void {
                interface ZzzShape { innerOnly: number }
                const good: ZzzShape = { innerOnly: 1 }
                const bad: ZzzShape = { outerOnly: 2 }
            }
            """,
        )
        val messages = d.map { it.message }
        assert(
            messages == listOf(
                "Object literal may only specify known properties, and 'outerOnly' does not exist in type 'ZzzShape'.",
            )
        )
    }

    /**
     * [Checker.getTypeFromTypeReference] asks the enclosing-NAMESPACE chain BEFORE
     * [NameResolver.resolveTypeNameToSymbol], and passes `enclosingNamespacesDone =
     * true`, so that function's own lexical-first arm is never reached from here. Left
     * alone, a namespace member would therefore displace a declaration nested INSIDE
     * one of its own functions — the outer declaration winning over the inner one, at a
     * second site. Innermost-first has to hold at both.
     */
    @Test
    fun `an enclosing namespace does not displace a function body declaration`() {
        val d = diagnose(
            """
            namespace ZzzNs {
                export interface ZzzShape { outerOnly: number }
                export function inner(): void {
                    interface ZzzShape { innerOnly: number }
                    const good: ZzzShape = { innerOnly: 1 }
                    const bad: ZzzShape = { outerOnly: 2 }
                }
            }
            """,
        )
        val messages = d.map { it.message }
        assert(
            messages == listOf(
                "Object literal may only specify known properties, and 'outerOnly' does not exist in type 'ZzzShape'.",
            )
        )
    }

    /**
     * (INV.0) step 10a's ONE regression, and why it is a defect this change EXPOSED
     * rather than one it introduced.
     *
     * `keyof` over a type that did not resolve used to answer `string`, under a comment
     * saying its result "is never displayed/checked meaningfully". That was measurably
     * false: it is a CLOSED key domain for an UNKNOWN type, so anything assigning to a
     * binding annotated with it gets a false TS2322. It stayed invisible only because a
     * B83.5-unbound alias resolved to NOTHING — the annotation was `any`, and `keyof any`
     * IS the correct open domain. Making the type real narrowed a correct superset into a
     * wrong subset.
     *
     * The fixture is `keyRemappingKeyofResult`'s shape reduced to its three necessary
     * ingredients (delta-debugged: removing ANY of them makes it silent on both binaries)
     * — a cyclic mapped type that re-enters `keyof Orig` while `Orig` is in flight, which
     * is what makes `Orig` answer `errorType`. Both references are silent here.
     */
    @Test
    fun `keyof over an unresolved type does not manufacture a closed key domain`() {
        val d = diagnose(
            """
            const sym = Symbol("")
            type Orig = { [k: string]: any, str: any, [sym]: any }
            type Remapped = { [K in keyof Orig as {} extends Record<K, any> ? never : K]: any }
            type Oops = Exclude<keyof Remapped, never>
            declare let x: Oops;
            x = sym;
            export function outerFn<T>(): void {
                type Orig = { [k: string]: any, str: any, [sym]: any } & T;
                type Okay = keyof Orig;
                let a: Okay;
                a = sym;
                type Remapped = { [K in keyof Orig as {} extends Record<K, any> ? never : K]: any }
                type Oops = keyof Remapped;
                let y: Oops;
                y = sym;
            }
            """,
            directives = "// @strict: false\n// @target: es6",
        ).filter { it.code == 2322 }
        assert(d.isEmpty())
    }

    /**
     * The CONTAINMENT control, and the whole soundness argument in one fixture:
     * `declareLexical` refuses any name the main binder already bound in that
     * container, so a conventionally-bound name is absent from `scope.symbols`
     * everywhere and keeps resolving exactly as it did. Here the function body declares
     * NOTHING named `ZzzShape`, so the file-level interface must still answer — and it
     * must answer with its OWN member, which is what the mis-assignment prints.
     *
     * Without this pin the change looks indistinguishable from one that lets scope
     * space shadow the world.
     */
    @Test
    fun `a conventionally bound name is untouched by the scope space consult`() {
        val d = diagnose(
            """
            interface ZzzShape { outerOnly: number }
            export function outerFn(): void {
                class ZzzUnrelated {}
                const good: ZzzShape = { outerOnly: 1 }
                const bad: ZzzShape = { nope: 2 }
            }
            """,
        )
        val messages = d.map { it.message }
        assert(
            messages == listOf(
                "Object literal may only specify known properties, and 'nope' does not exist in type 'ZzzShape'.",
            )
        )
    }
}

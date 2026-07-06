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
 * Round 423 — three coupled narrowing fixes for tsc's Jsx/CallLike guard shapes:
 *
 * 1. PARSER: `node is A | B` predicates on the UNION `A | B` (tsc parseTypePredicate →
 *    parseType). The old parseIntersectionOrHigherType truncated the target at `A` and
 *    the union-continuation wrapped the PREDICATE (`(node is A) | B`), so a
 *    union-target guard silently never narrowed (`isCallOrNewExpression`).
 * 2. `checkMemberAccessMissing`'s round-418 narrow-DOWN suppression accepts a narrowed
 *    UNION (a single-type receiver narrowed by a union-target guard) when EVERY member
 *    resolves the property.
 * 3. ALIASED CONDITIONS (tsc `narrowType` inlineLevel): `const isFrag =
 *    isJsxOpeningFragment(node); if (!isFrag) { node.tagName }` narrows `node` as if
 *    the guard were inline — the alias initializer is recovered by a value-preserving
 *    flow back-walk that bails on branches/calls/reassignment of either the alias or
 *    the walked reference. Plus: the predicate union filters consult the round-411
 *    `.kind` discriminant key space — PROVABLY disjoint keys beat the too-lenient
 *    structural relation (enum-member kinds resolve to `any`, so every Jsx member
 *    looked assignable to the property-poorest JsxOpeningFragment → `never` collapse).
 */
class AliasedConditionAndUnionPredicateTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile(
            "// @strict: true\n" + source.trimIndent(), "t.ts",
        ).diagnostics

    private fun assertNo2339(d: List<Diagnostic>, what: String) {
        assertTrue(
            d.none { it.code == 2339 },
            "$what must not fire TS2339; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    private fun assertFires2339(d: List<Diagnostic>, what: String) {
        assertTrue(
            d.any { it.code == 2339 },
            "$what must keep TS2339; got: " + d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    private val decls = """
        const enum K { Call, New, Tagged, JsxSelf, JsxOpen, JsxFrag }
        interface Node { readonly kind: K; readonly flags: number; }
        interface CallExpression extends Node { readonly kind: K.Call; readonly expression: Node; }
        interface NewExpression extends Node { readonly kind: K.New; readonly expression: Node; }
        interface TaggedTemplateExpression extends Node { readonly kind: K.Tagged; readonly tag: Node; }
        interface JsxSelfClosingElement extends Node { readonly kind: K.JsxSelf; readonly tagName: Node; }
        interface JsxOpeningElement extends Node { readonly kind: K.JsxOpen; readonly tagName: Node; }
        interface JsxOpeningFragment extends Node { readonly kind: K.JsxFrag; }
        type JsxCallLike = JsxSelfClosingElement | JsxOpeningElement | JsxOpeningFragment;
        function isCallOrNew(node: Node): node is CallExpression | NewExpression {
            return node.kind === K.Call || node.kind === K.New;
        }
        function isJsxOpeningFragment(node: Node): node is JsxOpeningFragment {
            return node.kind === K.JsxFrag;
        }
    """

    @Test
    fun `union predicate target narrows a union receiver`() {
        val d = diags(
            """
            $decls
            type CallLike = CallExpression | NewExpression | TaggedTemplateExpression;
            export function f(callLike: CallLike): Node {
                if (isCallOrNew(callLike)) {
                    return callLike.expression;
                }
                return callLike;
            }
            """,
        )
        assertNo2339(d, "`x is A | B` guard on a union receiver")
    }

    @Test
    fun `union predicate target narrows a single-type receiver to the union`() {
        val d = diags(
            """
            $decls
            export function f(x: Node): Node {
                if (isCallOrNew(x)) {
                    return x.expression;
                }
                return x;
            }
            """,
        )
        assertNo2339(d, "`x is A | B` guard narrowing an interface receiver down")
    }

    @Test
    fun `union-narrowed member missing on one member keeps TS2339`() {
        val d = diags(
            """
            $decls
            export function f(x: Node): Node {
                if (isCallOrNew(x)) {
                    return x.tag;
                }
                return x;
            }
            """,
        )
        assertFires2339(d, "a property absent from one narrowed-union member")
    }

    @Test
    fun `negative guard keeps disjoint-kind members instead of collapsing to never`() {
        val d = diags(
            """
            $decls
            export function f(node: JsxCallLike): Node {
                if (!isJsxOpeningFragment(node)) {
                    return node.tagName;
                }
                return node;
            }
            """,
        )
        assertNo2339(d, "negative fragment guard keeping the tagName-bearing members")
    }

    @Test
    fun `aliased condition narrows like the inline guard`() {
        val d = diags(
            """
            $decls
            export function f(node: JsxCallLike): Node {
                const isJsxOpenFragment = isJsxOpeningFragment(node);
                if (!isJsxOpenFragment) {
                    return node.tagName;
                }
                return node;
            }
            """,
        )
        assertNo2339(d, "const-stored guard result in a negated if")
    }

    @Test
    fun `aliased condition tolerates value-preserving statements in between`() {
        val d = diags(
            """
            $decls
            export function f(node: JsxCallLike): Node {
                const isJsxOpenFragment = isJsxOpeningFragment(node);
                let other: Node | undefined;
                let parent: Node = node;
                if (!isJsxOpenFragment) {
                    return node.tagName;
                }
                return parent;
            }
            """,
        )
        assertNo2339(d, "alias separated from the test by unrelated declarations")
    }

    @Test
    fun `reassigned alias does not narrow`() {
        val d = diags(
            """
            $decls
            export function f(node: JsxCallLike): Node {
                let isJsxOpenFragment = isJsxOpeningFragment(node);
                isJsxOpenFragment = node.kind === K.JsxFrag;
                if (!isJsxOpenFragment) {
                    return node.tagName;
                }
                return node;
            }
            """,
        )
        // tsc agrees: a reassigned `let` is not a constant alias → no narrowing →
        // tagName is genuinely absent from JsxOpeningFragment.
        assertFires2339(d, "reassigned alias between declaration and test")
    }

    @Test
    fun `walked reference reassigned between alias and test does not narrow`() {
        // The receiver must be a PARAM (a body-local `let` types as `any` in this
        // pass, so nothing fires with or without narrowing) — params are mutable,
        // so the reassignment-between-alias-and-test shape still exists.
        val d = diags(
            """
            $decls
            export function f(node: JsxCallLike, b: JsxCallLike): Node {
                const isJsxOpenFragment = isJsxOpeningFragment(node);
                node = b;
                if (!isJsxOpenFragment) {
                    return node.tagName;
                }
                return node;
            }
            """,
        )
        // The alias captured the OLD value of `node` — inlining would over-narrow.
        assertFires2339(d, "reference reassigned after the alias captured it")
    }

    @Test
    fun `truthy optional-chain call proves the receiver non-nullish`() {
        // builder.ts:1332: `if (state.referencedMap?.size()) { state.referencedMap.keys() }`
        // — a nullish receiver short-circuits the chain to undefined (falsy), so the
        // truthy branch excludes nullish. Positive branch only.
        val d = diags(
            """
            interface RefMap { size(): number; keys(): string[]; }
            interface State { referencedMap?: RefMap; other: number; }
            export function f1(state: State): string[] | undefined {
                if (state.referencedMap?.size()) {
                    return state.referencedMap.keys();
                }
                return undefined;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 18048 },
            "truthy `x.y?.size()` guard must clear the TS18048; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `falsy optional-chain call proves nothing`() {
        val d = diags(
            """
            interface RefMap { size(): number; keys(): string[]; }
            interface State { referencedMap?: RefMap; other: number; }
            export function f3(state: State): string[] {
                if (state.referencedMap?.size()) {
                    return [];
                }
                return state.referencedMap.keys();
            }
            """,
        )
        assertTrue(
            d.any { it.code == 18048 },
            "the falsy branch must keep TS18048 (receiver may be present with size 0); got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `intervening call between alias and test still narrows`() {
        val d = diags(
            """
            $decls
            declare function sideEffect(): void;
            export function f(node: JsxCallLike): Node {
                const isJsxOpenFragment = isJsxOpeningFragment(node);
                sideEffect();
                if (!isJsxOpenFragment) {
                    return node.tagName;
                }
                return node;
            }
            """,
        )
        // Round 424 DELIBERATE flip of the round-423 conservative pin (its note
        // said "relaxing it later should flip this assertion deliberately"):
        // the back-walk now treats a FlowCall as value-preserving — a call
        // cannot rebind an enclosing let/const binding directly, matching tsc,
        // which narrows here (the alias is a const; tsc's isConstantVariable
        // gate likewise ignores closure-mediated rebinding).
        assertNo2339(d, "call between alias declaration and test (value-preserving)")
    }
}

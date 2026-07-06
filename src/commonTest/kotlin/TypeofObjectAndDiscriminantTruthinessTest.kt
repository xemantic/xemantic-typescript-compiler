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
 * M3.4 (round 425): three bounded narrowing slices from the self-compile
 * burn-down —
 *  - `typeof x === "object"` filters a union three-way (object-like + null
 *    match; primitives/undefined/callables don't; any/unknown/TP kept on both
 *    branches) — tsc's `formatGeneratedNamePart`;
 *  - truthiness of a BOOLEAN-LITERAL discriminant property (`isStatic: false`
 *    vs `isStatic: true`) filters the receiver union — tsc classFields'
 *    PrivateIdentifier field infos;
 *  - a DESTRUCTURING read (`const { a } = result`) consults flow narrowing of
 *    its initializer like any other read — tsc's moduleNameResolver.
 */
class TypeofObjectAndDiscriminantTruthinessTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `typeof object narrows away string and undefined members`() {
        val d = diags(
            """
            interface GeneratedNamePart { prefix?: string; node: { g: number }; suffix?: string; }
            declare function fmt(prefix: string | undefined, node: { g: number }, suffix: string | undefined): string;
            export function formatGeneratedNamePart(part: string | GeneratedNamePart | undefined): string {
                return typeof part === "object" ? fmt(part.prefix, part.node, part.suffix) :
                    typeof part === "string" ? part :
                    "";
            }
            """
        )
        assertTrue(d.none { it.code == 2339 }, "typeof-object true branch must keep only the object member, got: $d")
    }

    @Test
    fun `typeof object keeps a callable member out of the true branch`() {
        val d = diags(
            """
            interface Bag { data: number }
            export function f(x: Bag | (() => number)) {
                if (typeof x === "object") {
                    return x.data;
                }
                return x();
            }
            """
        )
        assertTrue(
            d.none { it.code == 2339 || it.code == 2349 },
            "a function member reports typeof 'function', not 'object', got: $d"
        )
    }

    @Test
    fun `boolean-literal discriminant truthiness filters the union`() {
        val d = diags(
            """
            interface Identifier { ident: string }
            interface InstanceFieldInfo { isStatic: false; brand: string; }
            interface StaticFieldInfo { isStatic: true; brand: string; variableName: Identifier; }
            declare function helper(brand: string, v: Identifier | undefined): string;
            export function acc(info: InstanceFieldInfo | StaticFieldInfo) {
                return helper(info.brand, info.isStatic ? info.variableName : undefined);
            }
            """
        )
        assertTrue(d.none { it.code == 2339 }, "isStatic truthiness must select StaticFieldInfo, got: $d")
    }

    @Test
    fun `plain boolean discriminant does not filter`() {
        val d = diags(
            """
            interface P1 { isStatic: boolean; a: number }
            interface P2 { isStatic: boolean; b: number }
            export function neg(x: P1 | P2): number {
                if (x.isStatic) {
                    return x.a;
                }
                return 1;
            }
            """
        )
        // A plain (non-literal) boolean proves nothing: x stays P1 | P2, so
        // x.a on the union is a GENUINE error — the filter must not have
        // removed P2.
        assertTrue(
            d.any { it.code == 2339 && it.message.contains("'a'") },
            "a plain-boolean discriminant must not narrow, got: $d"
        )
    }

    /**
     * The nullish-gate invariant: property OPTIONALITY is a symbol attribute NOT
     * folded into the resolved property type (`body?: Body` resolves to bare
     * `Body`), so `x.body === undefined` proves NOTHING about the receiver —
     * an object-typed discriminant only discriminates against definite VALUE
     * literals. The first cut collapsed tsc's `checkGrammarAccessor`
     * (`accessor.body === undefined && …` then `accessor.end`) to `never`.
     */
    @Test
    fun `optional object prop compared to undefined does not drop the receiver`() {
        val d = diags(
            """
            interface Body { stmts: number }
            interface Accessor { body?: Body; end: number; flags: number; }
            export function checkGrammarAccessor(accessor: Accessor): number {
                if (accessor.body === undefined && accessor.flags > 0) {
                    return accessor.end - 1;
                }
                return 0;
            }
            """
        )
        assertTrue(
            d.none { it.code == 2339 },
            "an optional-prop === undefined comparison must not collapse the receiver, got: $d"
        )
    }

    @Test
    fun `destructuring read consults flow narrowing of the initializer`() {
        val d = diags(
            """
            interface VersionPaths { version: string; paths: object; }
            declare function getPathsFromMap(): VersionPaths | undefined;
            export function f() {
                const result = getPathsFromMap();
                if (!result) {
                    return;
                }
                const { version: bestVersionKey, paths: bestVersionPaths } = result;
                return bestVersionKey + String(bestVersionPaths);
            }
            """
        )
        assertTrue(d.none { it.code == 2339 }, "guarded destructuring must not report nullable-union, got: $d")
    }

    /**
     * Round 425: `instanceof` narrows a SUPERTYPE union member DOWN to the class
     * (tsc getNarrowedType checkDerived maps `m → isDerived(m,c) ? m :
     * isDerived(c,m)/related ? c : drop`) — the old subtype-only filter dropped
     * every member of `SymbolTracker | undefined` on `tracker instanceof
     * SymbolTrackerImpl` (the class implements the interface) → `never` (tsc
     * checker.ts's SymbolTrackerImpl constructor while-loop).
     */
    @Test
    fun `instanceof narrows a supertype union member down to the class`() {
        val d = diags(
            """
            interface SymbolTracker {
                trackSymbol?(sym: object): boolean;
            }
            class SymbolTrackerImpl implements SymbolTracker {
                readonly inner: SymbolTracker | undefined = undefined;
                canTrack: boolean;
                constructor(tracker: SymbolTracker | undefined) {
                    while (tracker instanceof SymbolTrackerImpl) {
                        tracker = tracker.inner;
                    }
                    this.inner = tracker;
                    this.canTrack = !!this.inner?.trackSymbol;
                }
                trackSymbol(sym: object): boolean { return false; }
            }
            """
        )
        assertTrue(d.none { it.code == 2339 }, "instanceof narrow-down must not collapse to never, got: $d")
    }

    /**
     * Round 425: the round-418 single-type narrow-DOWN suppression retries with
     * the loop-entry-following walk — a guard BEFORE a loop narrows a read
     * INSIDE it (the plain walk washes at the FlowLoopLabel).
     */
    @Test
    fun `pre-loop guard narrowing survives a read inside the loop`() {
        val d = diags(
            """
            interface Type { flags: number; }
            interface TupleTypeReference extends Type { target: { fixedLength: number }; }
            declare function isTupleType(t: Type): t is TupleTypeReference;
            export function f(constraint: Type, n: number): number {
                let acc = 0;
                if (isTupleType(constraint)) {
                    for (let i = 0; i < n; i++) {
                        acc += constraint.target.fixedLength;
                    }
                }
                return acc;
            }
            """
        )
        assertTrue(d.none { it.code == 2339 }, "pre-loop guard must survive the loop wash, got: $d")
    }

    @Test
    fun `unguarded nullable destructuring still fires`() {
        val d = diags(
            """
            interface VersionPaths { version: string; }
            declare function getPathsFromMap(): VersionPaths | undefined;
            export function f() {
                const result = getPathsFromMap();
                const { version } = result;
                return version;
            }
            """
        )
        assertTrue(
            d.any { it.code == 2339 && it.message.contains("'version'") },
            "unguarded nullable destructuring is a genuine error, got: $d"
        )
    }
}

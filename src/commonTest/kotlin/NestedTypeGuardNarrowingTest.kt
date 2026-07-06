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
 * M1.12 (round 418): a user type-guard declared as a NESTED function (inside another
 * function's body) must still narrow at its call sites. tsc's giant closures
 * (`createTypeChecker`, …) declare their type guards — `isTupleType`, `isGenericTupleType`,
 * `isArrayOrTupleType`, … — as nested functions, which the binder does not bind (B83.5),
 * so `resolveFlowCalleeDecl` could not resolve the callee and the guard never narrowed
 * (the single biggest TS2339 sub-family on the self-compile dashboard: `.target` on `Type`
 * after `isTupleType(type)`, ×46).
 *
 * The fix resolves a callee name to the UNIQUE FunctionDeclaration anywhere in the program;
 * a colliding name resolves to nothing (→ no narrowing, conservative). Coupled with the
 * single-type narrow-DOWN suppression in `checkMemberAccessMissing` (the receiver
 * narrowing was gated on `Type.Union`, so a non-union narrow-DOWN never reached the
 * property-access consumer).
 */
class NestedTypeGuardNarrowingTest {

    private fun diags(source: String): List<Diagnostic> =
        TypeScriptCompiler().compile("// @strict: true\n" + source.trimIndent(), "t.ts").diagnostics

    @Test
    fun `nested guard narrows a single type DOWN via an if-statement`() {
        val d = diags(
            """
            interface Type { flags: number; }
            interface TupleTypeReference extends Type { target: { readonly: boolean }; }

            export function createTypeChecker() {
                function isTupleType(type: Type): type is TupleTypeReference { return true; }
                function check(target: Type) {
                    if (isTupleType(target)) {
                        return target.target.readonly; // nested guard → narrow to TupleTypeReference
                    }
                    return false;
                }
                return { check };
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 },
            "a nested type-guard must narrow `target` to the subtype; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `nested guard narrows on the RHS of an and-short-circuit`() {
        // The `isGenericTupleType` shape: `return isTupleType(type) && type.target.combinedFlags`.
        val d = diags(
            """
            interface Type { flags: number; }
            interface TupleTypeReference extends Type { target: { combinedFlags: number }; }

            export function createTypeChecker() {
                function isTupleType(type: Type): type is TupleTypeReference { return true; }
                function isGenericTupleType(type: Type): type is TupleTypeReference {
                    return isTupleType(type) && !!(type.target.combinedFlags & 1);
                }
                return { isGenericTupleType };
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 },
            "the `&&` RHS must see `type` narrowed by the left guard; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `nested guard narrows a UNION receiver`() {
        val d = diags(
            """
            interface Cat { meow(): void; }
            interface Dog { bark(): void; }

            export function wrap() {
                function isCat(x: Cat | Dog): x is Cat { return "meow" in x; }
                function f(a: Cat | Dog) {
                    if (isCat(a)) { a.meow(); }
                    else { a.bark(); }
                }
                return f;
            }
            """,
        )
        assertTrue(
            d.none { it.code == 2339 },
            "a nested union guard must narrow both branches; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `FP-safety - a genuinely-missing property is NOT suppressed by narrowing`() {
        // The narrowed subtype `Sub` does NOT declare `missing`, so TS2339 MUST still fire —
        // the suppression only fires when the narrowed subtype HAS the accessed property.
        val d = diags(
            """
            interface Base { flags: number; }
            interface Sub extends Base { extra: number; }

            export function wrap() {
                function isSub(x: Base): x is Sub { return true; }
                function f(x: Base) {
                    if (isSub(x)) {
                        return x.missing; // `missing` exists on neither Base nor Sub → TS2339
                    }
                    return 0;
                }
                return f;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2339 && it.message.contains("missing") },
            "a property missing on the narrowed subtype must still fire TS2339; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }

    @Test
    fun `FP-safety - an ambiguous nested-function name does not resolve as a guard`() {
        // Two distinct nested functions share the name `pick` → the program-wide unique-name
        // map records it as ambiguous (null) → no narrowing. The access on the wide `Base`
        // (which lacks `extra`) therefore still fires TS2339 (the conservative outcome).
        val d = diags(
            """
            interface Base { flags: number; }
            interface Sub extends Base { extra: number; }

            export function wrapA() {
                function pick(x: Base): x is Sub { return true; }
                function f(x: Base) { if (pick(x)) { return x.extra; } return 0; }
                return f;
            }
            export function wrapB() {
                function pick(x: Base): x is Base { return true; }
                return pick;
            }
            """,
        )
        assertTrue(
            d.any { it.code == 2339 && it.message.contains("extra") },
            "an ambiguous guard name must NOT narrow (conservative); TS2339 must still fire; got: " +
                d.joinToString { "TS${it.code}: ${it.message}" },
        )
    }
}

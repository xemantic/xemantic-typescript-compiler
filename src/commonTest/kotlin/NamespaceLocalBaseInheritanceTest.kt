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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * M1.12 (self-compile burn-down): a namespace-local interface/class whose base is ALSO
 * namespace-local (`namespace M { interface Base {...}; interface Derived extends Base {...} }`)
 * did not inherit the base's members — `getTypeFromBaseTypeExpression` resolved a bare-Identifier
 * base via `globals` only, and a namespace-local declaration is not in `globals`. So the inherited
 * members were invisible, which FP'd TS2353 (an inherited member in an object literal looked
 * "excess") on tsc's own source (`builderState.ts`'s
 * `ManyToManyPathMap extends ReadonlyManyToManyPathMap`).
 *
 * Fix (coordinated across the two base-resolution sites): resolve a bare-Identifier base through
 * the enclosing-namespace scope first (falling back to `globals`) in BOTH
 * `getTypeFromBaseTypeExpression` (via the pushed `inferenceNamespaceStack`) AND
 * `lookupInstanceMemberInResolvableChain` (via a threaded `enclosingNs`). The second site is
 * load-bearing: once base resolution populates `type.baseTypes` for a namespace-local base, the
 * `this.X` TS2339 check's "class has base types" branch runs, and it must be able to resolve the
 * base chain to return `false` (member genuinely absent) and still emit the expected TS2339 —
 * otherwise a globals-only lookup returns `null` (uncertain → bail) and the diagnostic is
 * swallowed (genericRecursiveImplicitConstructorErrors3's `this.isArray()` on
 * `PullTypeSymbol extends PullSymbol`, both in `namespace TypeScript`). FP-safe: the chain returns
 * `false` ONLY when it is fully resolvable and the member is absent everywhere (any uncertainty
 * propagates `null`).
 */
class NamespaceLocalBaseInheritanceTest {

    @Test
    fun `inherited member from a namespace-local base is not excess - no TS2353`() {
        diagnose(
            """
            export namespace M {
                export interface Base {
                    getKeys(v: number): number | undefined;
                    size(): number;
                }
                export interface Derived extends Base {
                    setVal(k: number, v: number): void;
                }
                export function make(): Derived {
                    const m: Derived = {
                        getKeys: (v: number) => v,
                        size: () => 0,
                        setVal: (k: number, v: number) => {},
                    };
                    return m;
                }
            }
            """,
        ) should {
            have(none { it.code == 2353 })
            have(
                none { it.code == 2739 || it.code == 2740 || it.code == 2741 },
                "the object literal provides every own+inherited member, so no missing-property error",
            )
        }
    }

    @Test
    fun `base member in an OUTER namespace is inherited across nesting - no TS2353`() {
        diagnose(
            """
            export namespace Outer {
                export interface Base { a(): number; }
                export namespace Inner {
                    export interface Derived extends Base { b(): number; }
                    export const v: Derived = { a: () => 1, b: () => 2 };
                }
            }
            """,
        ) should {
            have(none { it.code == 2353 })
        }
    }

    @Test
    fun `missing a required inherited member STILL fires - negative control`() {
        diagnose(
            """
            export namespace M {
                export interface Base { a(): number; b(): number; }
                export interface Derived extends Base { c(): number; }
                export const v: Derived = { c: () => 3 };
            }
            """,
        ) should {
            have(any { it.code == 2739 || it.code == 2740 || it.code == 2741 })
        }
    }

    @Test
    fun `a genuinely-unknown property is STILL flagged excess - negative control`() {
        diagnose(
            """
            export namespace M {
                export interface Base { a(): number; }
                export interface Derived extends Base { b(): number; }
                export const v: Derived = { a: () => 1, b: () => 2, zzz: 9 };
            }
            """,
        ) should {
            have(any { it.code == 2353 && it.message.contains("zzz") })
        }
    }

    @Test
    fun `module-level base is still resolved - regression control`() {
        diagnose(
            """
            export interface TopBase { a(): number; }
            export namespace M {
                export interface Derived extends TopBase { b(): number; }
                export const v: Derived = { a: () => 1, b: () => 2 };
            }
            """,
        ) should {
            have(none { it.code == 2353 })
        }
    }

    @Test
    fun `this-access of a genuinely-missing member in a namespace-local class chain STILL fires TS2339`() {
        // The load-bearing companion: `PullTypeSymbol extends PullSymbol` (both namespace-local);
        // `this.isArray()` is declared in NEITHER class → tsc emits TS2339. Now that base
        // resolution populates baseTypes for the namespace-local base, the `this.X` check must
        // resolve the base chain namespace-awarely (→ `false`) and still emit TS2339, rather than
        // bail on a globals-only `null`.
        diagnose(
            """
            export namespace TS {
                export class PullSymbol { public kind: number = 0; }
                export class PullTypeSymbol extends PullSymbol {
                    public describe(): number {
                        if (this.isArray()) { return 1; }
                        return 0;
                    }
                }
            }
            """,
        ) should {
            have(any { it.code == 2339 && it.message.contains("isArray") })
        }
    }

    @Test
    fun `this-access of an INHERITED member in a namespace-local class chain does NOT fire TS2339`() {
        // FP-safety: `kind` IS declared in the namespace-local base `PullSymbol`, so `this.kind`
        // in the derived class must NOT fire TS2339 (the chain resolves `kind` → returns true).
        diagnose(
            """
            export namespace TS {
                export class PullSymbol { public kind: number = 0; }
                export class PullTypeSymbol extends PullSymbol {
                    public describe(): number { return this.kind + 1; }
                }
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }
}

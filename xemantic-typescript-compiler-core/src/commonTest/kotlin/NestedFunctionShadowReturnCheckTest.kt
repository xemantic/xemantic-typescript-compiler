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
 * Round 454 (M3.1, self-compile burn-down): a body-NESTED `function NAME(...)` shadows a
 * same-named EXPORTED/global function in the RETURN/argument-assignability pass too (previously
 * only the call-types pass shadowed them). tsc's own builderState.ts nests a
 * `function create(forward, reverse, deleted): ManyToManyPathMap` inside `createManyToManyPathMap`,
 * shadowing the file-level exported `create(newProgram: Program, …): BuilderState`; `return
 * create(new Map, new Map, undefined)` FP-fired TS2740 (return type) + TS2345 (first arg). The
 * assignability pass now applies `shadowNestedFunctionNames` in `checkFunctionBody`, so
 * `getTypeOfIdentifier` (which the return-check consults) resolves the callee to anyType rather
 * than the merged-globals exported function. Suppression-only.
 */
class NestedFunctionShadowReturnCheckTest {

    @Test
    fun `a nested function shadows an exported one in the return check`() {
        diagnose(
            """
            interface PathMap { keys(): string[]; }
            interface State { version: number; }
            declare class Prog { p: number; }
            export function create(newProgram: Prog, oldState: State | undefined, flag: boolean): State {
                return { version: 1 };
            }
            export function createMap(): PathMap {
                function create(
                    forward: Map<string, string>,
                    reverse: Map<string, string>,
                    deleted: Set<string> | undefined,
                ): PathMap {
                    return { keys() { return []; } };
                }
                return create(new Map(), new Map(), undefined);
            }
            """
        ) should {
            have(none { it.code == 2740 })
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a direct return of the exported function result still type-checks - negative control`() {
        // No shadowing nested `create` — the exported `create(newProgram: Prog, …): State` is the
        // callee; returning its `State` result as a `PathMap` must still fire TS2740/TS2322.
        diagnose(
            """
            interface PathMap { keys(): string[]; }
            interface State { version: number; }
            declare class Prog { p: number; }
            declare function create(newProgram: Prog, oldState: State | undefined, flag: boolean): State;
            export function makeMap(p: Prog): PathMap {
                return create(p, undefined, false);
            }
            """
        ) should {
            have(any { it.code == 2740 || it.code == 2322 })
        }
    }
}

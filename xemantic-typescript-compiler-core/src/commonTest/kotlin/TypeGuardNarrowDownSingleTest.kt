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
 * M3.4 (self-compile burn-down): a user type-guard `x is C` narrowing a SINGLE (non-union) type
 * must narrow DOWN to `C` when `C` is a subtype of `x`'s declared type — even when `C` redefines
 * an OPTIONAL base member (`program?: T`) as a required non-undefined one (`program: T`).
 *
 * The single-type path of `narrowByCallPredicate` checked `t <: candidate` FIRST and kept the
 * WIDE `t` when true, but our relation engine over-accepts `t <: candidate` (an optional source
 * property `program?: Program | undefined` satisfies a required target property `program: Program`),
 * so BOTH relations held and the wide type was kept — leaving `state.program` possibly-undefined
 * → FP TS18048/TS2339. tsc's `getNarrowedType`(assumeTrue) checks `candidate <: t` FIRST
 * (`candidate <: t ? candidate : t <: candidate ? t : t&candidate`). This is exactly the
 * `Debug.assert(isBuilderProgramStateWithDefinedProgram(state))` shape in tsc's own builder.ts
 * (7 self-compile FPs) and the moduleNameResolver / program `Debug.assert*` sites.
 */
class TypeGuardNarrowDownSingleTest {

    @Test
    fun `assert guard narrows down to subtype redefining an optional member as required`() {
        diagnose(
            """
            interface Program { emit(): void; }
            interface Reusable { program?: Program | undefined; other: number; }
            interface Full extends Reusable { extra: number; }
            interface WithProgram extends Full { program: Program; }
            declare namespace Dbg { export function assert(cond: unknown): asserts cond; }
            function isDefinedProgram(s: Reusable): s is WithProgram { return s.program !== undefined; }

            export function emitBuildInfo(state: Full): void {
                Dbg.assert(isDefinedProgram(state));
                state.program.emit(); // state narrowed to WithProgram → program: Program (defined)
                state.program.emit();
            }
            """,
        ) should {
            have(none { it.code == 18048 || it.code == 2339 })
        }
    }

    @Test
    fun `plain if-guard narrows down to subtype redefining an optional member as required`() {
        diagnose(
            """
            interface Program { emit(): void; }
            interface Reusable { program?: Program | undefined; other: number; }
            interface WithProgram extends Reusable { program: Program; }
            function isDefinedProgram(s: Reusable): s is WithProgram { return s.program !== undefined; }

            export function use(state: Reusable): void {
                if (isDefinedProgram(state)) {
                    state.program.emit(); // narrowed to WithProgram
                }
            }
            """,
        ) should {
            have(none { it.code == 18048 || it.code == 2339 })
        }
    }

    @Test
    fun `guard narrows captured variable inside a closure`() {
        // The real builder.ts shape: `state` is a captured const, the assert is inside a nested fn.
        diagnose(
            """
            interface Program { emit(): void; }
            interface Reusable { program?: Program | undefined; other: number; }
            interface WithProgram extends Reusable { program: Program; }
            declare namespace Dbg { export function assert(cond: unknown): asserts cond; }
            function isDefinedProgram(s: Reusable): s is WithProgram { return s.program !== undefined; }
            declare function makeState(): Reusable;

            export function createBuilderProgram() {
                const state = makeState();
                const bp: any = {};
                bp.emitBuildInfo = emitBuildInfo;
                return bp;
                function emitBuildInfo() {
                    Dbg.assert(isDefinedProgram(state));
                    state.program.emit();
                }
            }
            """,
        ) should {
            have(none { it.code == 18048 || it.code == 2339 })
        }
    }

    @Test
    fun `negative control - guard to an already-narrower Derived keeps Derived members`() {
        diagnose(
            """
            interface Base { kind: string; }
            interface Derived extends Base { extra: number; }
            declare function isBase(x: Base): x is Base;

            export function ctrl(dd: Derived): void {
                if (isBase(dd)) {
                    const n: number = dd.extra; // dd stays Derived; extra still visible
                }
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - false branch of an unrelated-type guard still narrows`() {
        diagnose(
            """
            interface Cat { meow(): void; }
            interface Dog { bark(): void; }
            declare function isCat(x: Cat | Dog): x is Cat;

            export function ctrl(a: Cat | Dog): void {
                if (!isCat(a)) {
                    a.bark(); // narrowed to Dog in the false branch
                }
            }
            """,
        ) should {
            have(none { it.code == 2339 })
        }
    }
}

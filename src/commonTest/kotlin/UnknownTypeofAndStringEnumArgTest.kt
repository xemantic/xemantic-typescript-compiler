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
 * Round 429d (M3.1/M3.4): two more arg-typing rules.
 *
 *  (a) `typeof x === "string"` narrows a non-union UNKNOWN to `string` (tsc
 *      narrowTypeByTypeof) — `narrowByTypeOfGuard`'s non-union flags path
 *      returned `never` for a positive match on `unknown` (no primitive
 *      flags), so the guard never fed the call-arg path. ×10 self-compile
 *      (tsc moduleNameResolver's `loadEntrypointsFromTargetExports(target:
 *      unknown)` string-handling arm).
 *  (b) An all-string-valued ENUM is assignable to `string`
 *      (`isStringEnumObjectType` in `isSimpleTypeRelatedTo` — the string
 *      sibling of round 428b's numeric-enum→number). ×4 self-compile
 *      (`changeAnyExtension(path, Extension.Dts)`).
 */
class UnknownTypeofAndStringEnumArgTest {

    @Test
    fun `typeof-string-guarded unknown arg to a string param is clean`() {
        diagnose(
            """
            declare function startsWith(s: string, prefix: string): boolean;
            export function f(target: unknown): boolean {
                if (typeof target === "string" && startsWith(target, "./")) {
                    return startsWith(target, "../");
                }
                return false;
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `typeof-number-guarded unknown arg to a number param is clean`() {
        diagnose(
            """
            declare function add(n: number): number;
            export function f(x: unknown): number {
                if (typeof x === "number") {
                    return add(x);
                }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - unguarded unknown arg still fires`() {
        diagnose(
            """
            declare function startsWith(s: string, prefix: string): boolean;
            export function f(target: unknown): boolean {
                return startsWith(target, "./");
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - wrong-typeof-guarded unknown arg still fires`() {
        diagnose(
            """
            declare function startsWith(s: string, prefix: string): boolean;
            export function f(target: unknown): boolean {
                if (typeof target === "number") {
                    return startsWith(target, "./");
                }
                return false;
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `string enum value arg to a string param is clean`() {
        diagnose(
            """
            enum Extension { Ts = ".ts", Dts = ".d.ts", Js = ".js" }
            declare function changeExt(path: string, ext: string): string;
            export function f(path: string, ext: Extension): string {
                return changeExt(path, ext);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `string enum member arg to a string param is clean`() {
        diagnose(
            """
            enum Extension { Ts = ".ts", Dts = ".d.ts" }
            declare function changeExt(path: string, ext: string): string;
            export function f(path: string): string {
                return changeExt(path, Extension.Dts);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `truthy-guarded union arg in a REST position is clean`() {
        // The rest-args helper mirrors B469: a ternary truthy guard narrows the
        // `string | undefined` rest arg (tsc addDeprecatedSuggestionWithSignature).
        diagnose(
            """
            declare function diag(msg: string, ...args: (string | number)[]): object;
            export function f(entity: string | undefined): object {
                return entity ? diag("sig", entity) : diag("plain");
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - unguarded union arg in a REST position still fires`() {
        diagnose(
            """
            declare function diag(msg: string, ...args: (string | number)[]): object;
            export function f(entity: boolean): object {
                return diag("sig", entity);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - NUMERIC enum arg to a string param still fires`() {
        diagnose(
            """
            enum Flags { A, B, C }
            declare function changeExt(path: string, ext: string): string;
            export function f(path: string, flag: Flags): string {
                return changeExt(path, flag);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - MIXED-value enum arg to a string param still fires`() {
        diagnose(
            """
            enum Mixed { A = 1, B = "b" }
            declare function changeExt(path: string, ext: string): string;
            export function f(path: string, m: Mixed): string {
                return changeExt(path, m);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}

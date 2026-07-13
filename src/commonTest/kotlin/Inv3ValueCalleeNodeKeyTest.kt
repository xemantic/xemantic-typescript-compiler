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
 * INV.3(c)(iii) phase 2 (round 507): the bare-Identifier VALUE/receiver/callee
 * resolution cluster keys its merged-globals fallbacks by the name Identifier
 * node's owning file — checkPrivateMemberAccess, getCalleeType,
 * resolveFlowCalleeDecl, resolveNamespaceMemberFnDecl,
 * computeRawTypeOfPropertyAccess's namespace fallback,
 * resolvePropertyAccessToSymbol, propertyAccessChainIsNamespaceQualified,
 * isCalleeResolvable, checkPropertyAccessAssignment's namespace base, the two
 * checkMemberAccessMissing receiver consults, and the protected-constructor
 * heritage walks. A module file referencing a name it never imports no longer
 * resolves it through a foreign module file's leaked local (real tsc sees
 * TS2304 there), while own-file, imported, and script-file resolution is
 * unchanged.
 */
class Inv3ValueCalleeNodeKeyTest {

    @Test
    fun `an UNIMPORTED foreign private-membered var no longer draws TS2341 for a same-named param`() {
        diagnose(
            """
            // @filename: a.ts
            export class Hidden {
                private data: number = 1;
            }
            export declare const c: Hidden;

            // @filename: b.ts
            export function f(c: { data: number }): number {
                return c.data;
            }
            """
        ) should {
            have(none { it.code == 2341 })
        }
    }

    @Test
    fun `an UNIMPORTED foreign function callee no longer draws TS2345`() {
        diagnose(
            """
            // @filename: a.ts
            export function fmt(x: string): void {}

            // @filename: b.ts
            export function g(): void {
                fmt(123);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `an UNIMPORTED ambiguous foreign guard no longer narrows - the union TS2339 fires`() {
        diagnose(
            """
            // @filename: a.ts
            export interface A { kind: string; a: number; }
            export interface B { kind: string; b: number; }
            export function isA(x: unknown): x is A {
                return typeof x === "object";
            }

            // @filename: b.ts
            export function isA(x: unknown): x is boolean {
                return typeof x === "boolean";
            }

            // @filename: c.ts
            import { A, B } from "./a";
            export function f(v: A | B): number {
                if (isA(v)) {
                    return v.a;
                }
                return 0;
            }
            """
        ) should {
            have(any { it.code == 2339 && it.message.contains("'a'") })
        }
    }

    @Test
    fun `an UNIMPORTED foreign namespace guard no longer narrows - the union-arg TS2345 fires`() {
        diagnose(
            """
            // @filename: a.ts
            export namespace Util {
                export function isStr(x: unknown): x is string {
                    return typeof x === "string";
                }
            }

            // @filename: b.ts
            export function takesString(s: string): void {}
            export function f(w: string | number): void {
                if (Util.isStr(w)) {
                    takesString(w);
                }
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a same-file private access keeps drawing TS2341`() {
        diagnose(
            """
            export class Hidden {
                private data: number = 1;
            }
            export declare const c: Hidden;
            export const n = c.data;
            """
        ) should {
            have(any { it.code == 2341 && it.message.contains("'data' is private") })
        }
    }

    @Test
    fun `negative control - an IMPORTED function callee keeps drawing TS2345`() {
        diagnose(
            """
            // @filename: a.ts
            export function fmt(x: string): void {}

            // @filename: b.ts
            import { fmt } from "./a";
            export function g(): void {
                fmt(123);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - a cross-file SCRIPT function callee keeps drawing TS2345`() {
        diagnose(
            """
            // @filename: a.ts
            function fmt(x: string): void {}

            // @filename: b.ts
            fmt(123);
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - an IMPORTED guard keeps narrowing`() {
        diagnose(
            """
            // @filename: a.ts
            export function isStr(x: unknown): x is string {
                return typeof x === "string";
            }

            // @filename: b.ts
            import { isStr } from "./a";
            export function f(v: string | number): number {
                if (isStr(v)) {
                    return v.length;
                }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a same-file namespace guard keeps narrowing`() {
        diagnose(
            """
            export namespace Util {
                export function isStr(x: unknown): x is string {
                    return typeof x === "string";
                }
            }
            export function f(v: string | number): number {
                if (Util.isStr(v)) {
                    return v.length;
                }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }
}

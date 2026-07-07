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
 * Round 428c (M3.4): the optional-member-arg TS2345 emitter
 * (`tryEmitOptionalMemberArgVsRequiredNamedTs2345`) consults flow narrowing before
 * synthesizing its local `propType | undefined` union — a truthy/guard-narrowed
 * access is not undefined at the call site. tsc checker.ts's mergeSymbol idiom (×24
 * self-compile):
 *
 *   if (source.valueDeclaration) {
 *       setValueDeclaration(target, source.valueDeclaration);   // tsc: OK
 *   }
 *
 * Suppression-only: an UNGUARDED optional-member arg still fires.
 */
class GuardedOptionalMemberArgTest {

    private val decls = """
        interface Declaration { kind: number; }
        interface Sym { flags: number; valueDeclaration?: Declaration; }
        declare function setValueDeclaration(symbol: Sym, node: Declaration): void;
        declare function isExpression(node: Declaration): boolean;
    """

    @Test
    fun `if-guarded optional member arg does not fire`() {
        diagnose(
            """
            $decls
            export function mergeSymbol(target: Sym, source: Sym): void {
                if (source.valueDeclaration) {
                    setValueDeclaration(target, source.valueDeclaration);
                }
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `double-bang and-chain guarded optional member arg does not fire`() {
        diagnose(
            """
            $decls
            export function ctxSensitive(symbol: Sym): boolean {
                return !!symbol.valueDeclaration && isExpression(symbol.valueDeclaration);
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `deep-path and-chain guarded optional member arg does not fire`() {
        diagnose(
            """
            $decls
            interface Ty { symbol: Sym; }
            export function deepPath(type: Ty): boolean {
                return !!(type.symbol.valueDeclaration && isExpression(type.symbol.valueDeclaration));
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - unguarded optional member arg still fires`() {
        diagnose(
            """
            $decls
            export function unguarded(target: Sym, source: Sym): void {
                setValueDeclaration(target, source.valueDeclaration);
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }

    @Test
    fun `negative control - wrong-polarity guard still fires`() {
        // The FALSE branch of the truthy guard is exactly where the member IS undefined.
        diagnose(
            """
            $decls
            export function wrongBranch(target: Sym, source: Sym): void {
                if (!source.valueDeclaration) {
                    setValueDeclaration(target, source.valueDeclaration);
                }
            }
            """
        ) should {
            have(any { it.code == 2345 })
        }
    }
}

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
 * Round 471: `x = { ...y, extra, ...(cond ? {} : { extra2 }) }` where y's
 * (flow-narrowed) type relates to the target and every extra key is declared
 * in SOME target union member with a relating value type — our B426 spread
 * merge keeps only the GUARANTEED props, so the relation missed the spread's
 * full member set and FP'd TS2322 (tsc importFixes.ts
 * `fix = { ...fix, ...(addAsTypeOnly === undefined ? {} : { addAsTypeOnly }) }`).
 * objectLiteralSpreadOfTargetSatisfies suppresses at the assignment path; a
 * conditional spread's arm value reads its flow-narrowed type (the ternary
 * condition proves it non-undefined).
 */
class SpreadOfTargetWithDeclaredExtrasTest {

    private val prelude = """
        const enum FixKind { UseNamespace, AddToExisting, AddNew }
        const enum AddAsTypeOnly { Allowed, Required, NotAllowed }
        interface FixBase { moduleSpecifierKind: "relative" | "ambient" | undefined; moduleSpecifier: string; }
        interface FixUseNamespace extends FixBase { kind: FixKind.UseNamespace; namespacePrefix: string; }
        interface FixAddToExisting extends FixBase { kind: FixKind.AddToExisting; addAsTypeOnly: AddAsTypeOnly; propertyName?: string; }
        interface FixAddNew extends FixBase { kind: FixKind.AddNew; addAsTypeOnly: AddAsTypeOnly; propertyName?: string; }
        type FixWithSpecifier = FixUseNamespace | FixAddToExisting | FixAddNew;
    """.trimIndent()

    @Test
    fun `a spread of the target plus conditional declared extras relates`() {
        diagnose(
            prelude + """
            function f(fix0: FixWithSpecifier, addAsTypeOnly: AddAsTypeOnly | undefined, propertyName: string | undefined) {
                let fix = fix0;
                fix = {
                    ...fix,
                    ...(addAsTypeOnly === undefined ? {} : { addAsTypeOnly }),
                    ...(propertyName === undefined ? {} : { propertyName }),
                };
                return fix;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a guard-narrowed spread plus plain declared extras relates`() {
        diagnose(
            prelude + """
            function h(existingFix: FixWithSpecifier | undefined, addAsTypeOnly2: AddAsTypeOnly) {
                let fix: FixWithSpecifier;
                if (existingFix && existingFix.kind !== FixKind.UseNamespace) {
                    fix = { ...existingFix, addAsTypeOnly: addAsTypeOnly2 };
                } else {
                    fix = { kind: FixKind.AddNew, addAsTypeOnly: addAsTypeOnly2, moduleSpecifier: "x", moduleSpecifierKind: undefined };
                }
                return fix;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - an undeclared extra key still fires`() {
        diagnose(
            prelude + """
            function g(fix0: FixWithSpecifier) {
                let fix = fix0;
                fix = { ...fix, bogusKey: 1 };
                return fix;
            }
            """
        ) should {
            have(any { it.code == 2322 || it.code == 2353 })
        }
    }

    @Test
    fun `negative control - a wrong-typed extra value still fires`() {
        diagnose(
            prelude + """
            function k(fix0: FixWithSpecifier, s: string) {
                let fix = fix0;
                fix = { ...fix, addAsTypeOnly: s };
                return fix;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}

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
 * Round 430b (M3.1): a type parameter binds from a PREDICATE-position callback —
 * `getFirstJSDocTag<T extends JSDocTag>(node, predicate: (tag: JSDocTag) => tag
 * is T)` called with a NAMED guard `isJSDocAugmentsTag` infers
 * T = JSDocAugmentsTag from the guard's own predicate target (tsc
 * utilitiesPublic.ts, the `T | undefined` ×41 self-compile bucket). The resolved
 * signature ERASES the predicate (a TypePredicate resolves to booleanType), so
 * the binding reads the param's declared AST via predicateTargetTypeOfGuardExpr.
 */
class PredicatePositionTpInferenceTest {

    @Test
    fun `TP binds from a named guard arg's predicate target`() {
        diagnose(
            """
            interface Tag { kind: number; comment?: string; }
            interface AugmentsTag extends Tag { cls: string; }
            declare function isAugmentsTag(tag: Tag): tag is AugmentsTag;
            declare function getFirstTag<T extends Tag>(name: string, predicate: (tag: Tag) => tag is T): T | undefined;
            export function getAugmentsTag(name: string): AugmentsTag | undefined {
                return getFirstTag(name, isAugmentsTag);
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `TP binding flows to an assignment target`() {
        diagnose(
            """
            interface Tag { kind: number; }
            interface ClassTag extends Tag { cls: string; }
            declare function isClassTag(tag: Tag): tag is ClassTag;
            declare function getFirstTag<T extends Tag>(name: string, predicate: (tag: Tag) => tag is T): T | undefined;
            export function f(name: string) {
                let t: ClassTag | undefined;
                t = getFirstTag(name, isClassTag);
                return t;
            }
            """
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - a WRONG-target guard still fires`() {
        diagnose(
            """
            interface Tag { kind: number; }
            interface ClassTag extends Tag { cls: string; }
            interface OtherTag extends Tag { other: number; }
            declare function isOtherTag(tag: Tag): tag is OtherTag;
            declare function getFirstTag<T extends Tag>(name: string, predicate: (tag: Tag) => tag is T): T | undefined;
            export function f(name: string) {
                let t: ClassTag | undefined;
                t = getFirstTag(name, isOtherTag);
                return t;
            }
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }
}

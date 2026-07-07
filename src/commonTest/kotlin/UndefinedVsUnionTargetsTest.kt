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
 * M1.9 (round 388): `undefined` lost against targets that legitimately include it.
 * Five self-compile FP families, each with a negative control:
 *
 * 1. `return undefined` against a UNION-ALIAS return annotation containing
 *    `undefined` — the engine confirms the relation but the legacy string
 *    fallback re-checked the unexpanded alias NAME and FP'd (B325's
 *    engine-confirmed early return, now applied to the return path).
 * 2. Same via a GENERIC alias substitution (`R<T> = T | Node[]`,
 *    `R<Node | undefined>`).
 * 3. Same where the alias body is an ENUM-MEMBER union (resolves to anyType in
 *    the engine → canUseTypeEngine bails) — the string fallback is skipped when
 *    the alias body syntactically carries the nullish keyword.
 * 4. Assignment inside a `!== undefined`/truthy guard: the walk narrows
 *    `currentLocalTypes` for then-branch READS, but a WRITE target must check
 *    against the DECLARED type (tsc semantics) — `narrowedDeclaredTypes`.
 * 5. Arg-position: `undefined` to an optional PRIMITIVE param (main
 *    simple-checkable path) and to a signature's OWN inferable bare type param
 *    (`g<T>(x: T)` → T = undefined).
 */
class UndefinedVsUnionTargetsTest {

    private fun assertClean(source: String, what: String) {
        diagnose(source) should {
            have(none { it.code == 2322 || it.code == 2345 }, "$what must not error")
        }
    }

    private fun assertRejects(source: String, code: Int, what: String) {
        diagnose(source) should {
            have(any { it.code == code }, "$what must still reject with TS$code")
        }
    }

    // -- family 1: return undefined vs union alias ---------------------------

    @Test fun returnUndefinedAgainstLiteralUnionAliasWithUndefined() {
        assertClean(
            """
            type Mode = 1 | 2 | undefined;
            function f(): Mode {
                return undefined;
            }
            """.trimIndent(),
            "return undefined vs `type Mode = 1 | 2 | undefined`",
        )
    }

    @Test fun returnNullAgainstUnionAliasWithoutNullStillRejects() {
        assertRejects(
            """
            type Mode = 1 | 2;
            function f(): Mode {
                return undefined;
            }
            """.trimIndent(),
            2322,
            "return undefined vs `type Mode = 1 | 2`",
        )
    }

    // -- family 2: generic alias substitution --------------------------------

    @Test fun returnUndefinedAgainstGenericAliasSubstitutedUnion() {
        assertClean(
            """
            interface Node { kind: number; }
            type R<T> = T | readonly Node[];
            function v(): R<Node | undefined> {
                return undefined;
            }
            """.trimIndent(),
            "return undefined vs `R<Node | undefined>` (R<T> = T | readonly Node[])",
        )
    }

    @Test fun returnNullAgainstGenericAliasSubstitutedNullUnion() {
        assertClean(
            """
            interface Node { kind: number; }
            type R<T> = T | Node[];
            function v(): R<Node | null> {
                return null;
            }
            """.trimIndent(),
            "return null vs `R<Node | null>`",
        )
    }

    @Test fun returnUndefinedAgainstGenericAliasWithoutUndefinedStillRejects() {
        assertRejects(
            """
            interface Node { kind: number; }
            type R<T> = T | Node[];
            function v(): R<Node> {
                return undefined;
            }
            """.trimIndent(),
            2322,
            "return undefined vs `R<Node>` (no undefined member)",
        )
    }

    // -- family 3: enum-member union alias (engine-unresolvable) -------------

    @Test fun returnUndefinedAgainstEnumMemberUnionAliasWithUndefined() {
        assertClean(
            """
            enum MK { ESNext = 99, CommonJS = 1 }
            type Mode = MK.ESNext | MK.CommonJS | undefined;
            function f(mode: string | undefined): Mode {
                if (!mode) {
                    return undefined;
                }
                return MK.ESNext;
            }
            """.trimIndent(),
            "return undefined vs enum-member union alias carrying `| undefined`",
        )
    }

    // -- family 4: assignment target uses the DECLARED type ------------------

    @Test fun assignUndefinedInsideNotUndefinedGuardIsLegal() {
        assertClean(
            """
            function h(cb: (a: number) => void) {
                let start: number | undefined;
                start = 1;
                if (start !== undefined) {
                    cb(start);
                    start = undefined;
                }
            }
            """.trimIndent(),
            "write of undefined to a `number | undefined` local inside its own guard",
        )
    }

    @Test fun narrowedReadInsideGuardStillNarrows() {
        // The guard's narrowing must survive for READS: `cb(start)` needs `number`.
        assertClean(
            """
            function h(cb: (a: number) => void) {
                let start: number | undefined;
                if (start !== undefined) {
                    cb(start);
                }
            }
            """.trimIndent(),
            "narrowed read passed to a number-param callback",
        )
    }

    @Test fun wrongTypeWriteInsideGuardStillRejects() {
        assertRejects(
            """
            function h() {
                let start: number | undefined;
                if (start !== undefined) {
                    start = "nope";
                }
            }
            """.trimIndent(),
            2322,
            "write of a string to a `number | undefined` local (even inside a guard)",
        )
    }

    // -- family 5: args — optional primitive + own inferable bare TP ---------

    @Test fun undefinedToOptionalPrimitiveParamIsLegal() {
        assertClean(
            """
            declare function f(a: number, b?: string): void;
            f(1, undefined);
            """.trimIndent(),
            "explicit undefined to an optional primitive param",
        )
    }

    @Test fun undefinedToRequiredPrimitiveParamStillRejects() {
        assertRejects(
            """
            declare function f(a: number, b: string): void;
            f(1, undefined);
            """.trimIndent(),
            2345,
            "explicit undefined to a REQUIRED primitive param",
        )
    }

    @Test fun undefinedToOwnInferableBareTypeParamIsLegal() {
        assertClean(
            """
            declare function g<T, U>(state: T, initial: U): U;
            g(undefined, undefined);
            """.trimIndent(),
            "undefined to a sig's own bare type params (T infers to undefined)",
        )
    }

    @Test fun undefinedToOptionalRefParamInNamespaceNestedFnIsLegal() {
        assertClean(
            """
            namespace P {
                interface DM { k: string; }
                function ci(a: boolean, dm?: DM): void {}
                export function use() { ci(true, undefined); }
            }
            """.trimIndent(),
            "undefined to an optional Reference param of a namespace-nested fn",
        )
    }
}

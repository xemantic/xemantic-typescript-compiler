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
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * Round 427: two tsc-faithful `assumeInitialized` rules for TS2454
 * (checker.ts checkIdentifier):
 *
 * 1. A LOGICAL assignment (`??=`/`||=`/`&&=`) is a DEFINITE assignment
 *    (getAssignmentTargetKind → AssignmentKind.Definite, same as plain `=`),
 *    so `isNeverInitialized` is false and a CAPTURED (cross-closure) read
 *    assumes initialized — tsc's own checker.ts does
 *    `let sourceStack: Type[]; … (sourceStack ??= []).push(source)` inside a
 *    nested function with zero errors. Compound assignments (`|=`, `+=`,
 *    `++`) are AssignmentKind.Compound, NOT definite — a captured read still
 *    fires (unusedLocalsInMethod4's `enabledSubstitutions |= …`).
 *
 * 2. A read whose DIRECT parent is a non-null assertion (`x!`) assumes
 *    initialized — the literal `node.parent.kind === SyntaxKind.NonNullExpression`
 *    disjunct; tsc's own core.ts does `let lastResult: U; … return lastResult!`
 *    with zero errors.
 *
 * Each rule is pinned with a negative control proving the emitter still fires
 * on the adjacent non-exempt shape.
 */
class Ts2454AssumeInitializedTest {

    private fun compile(@Language("typescript") source: String) =
        TypeScriptCompiler().compile("// @strict: true\n" + source, "t.ts")

    private fun ts2454s(@Language("typescript") source: String) =
        compile(source).diagnostics.filter { it.code == 2454 }

    /** tsc's `(sourceStack ??= []).push(…)` in a nested closure: the `??=` is a
     *  DEFINITE assignment, so neither its own read nor later captured reads fire. */
    @Test fun coalescingAssignInNestedClosureSuppressesCapturedReads() {
        ts2454s(
            """
            export function outer() {
                let stack: number[];
                function inner() {
                    (stack ??= []).push(1);
                }
                function other() {
                    return stack.length;
                }
                inner();
                return other();
            }
            """.trimIndent()
        ) should {
            have(isEmpty())
        }
    }

    /** `||=` and `&&=` are likewise definite. */
    @Test fun logicalAssignsAreDefinite() {
        ts2454s(
            """
            export function outer() {
                let a: number;
                let b: number;
                function inner() {
                    a ||= 1;
                    b &&= 2;
                }
                function reader() {
                    return a + b;
                }
                inner();
                return reader();
            }
            """.trimIndent()
        ) should {
            have(isEmpty())
        }
    }

    /** tsc checker.ts getSignaturesOfType: the definite assignment sits in a COMMA
     *  expression inside a ternary inside an arrow —
     *  `everyType(type, t => … && (!memberName ? (memberName = X, true) : …))` —
     *  and the later same-container uses are `memberName!` (`!`-asserted, as in the
     *  real source: a PLAIN same-container read would still legitimately fire, since
     *  the in-arrow assignment is invisible to the outer control flow). A comma
     *  expression nests the assignment on the LEFT spine of a BinaryExpression,
     *  which an outer-only target check silently skips. */
    @Test fun commaNestedAssignInArrowIsDefinite() {
        ts2454s(
            """
            declare function every(f: (t: number) => boolean): boolean;
            export function outer() {
                let memberName: string;
                if (every(t => (!memberName ? (memberName = "m" + t, true) : memberName === "m" + t))) {
                    return memberName!.length;
                }
                return 0;
            }
            """.trimIndent()
        ) should {
            have(isEmpty())
        }
    }

    /** The REAL checker.ts:15956 shape adds an ENCLOSING if-block around the `let` —
     *  which routes the arrow's expression body through the flow-based walker's
     *  inUncheckedBody path (nested-if conditions are walked flagged), a DIFFERENT
     *  emitter than the top-level variant above. Both must honor the
     *  definitely-assigned-in-arrow exemption. */
    @Test fun commaNestedAssignInArrowInsideIfBlockIsDefinite() {
        ts2454s(
            """
            declare function every(f: (t: number) => boolean): boolean;
            export function outer(kind: number) {
                if (kind === 0) {
                    let memberName: string;
                    if (every(t => (!memberName ? (memberName = "m" + t, true) : memberName === "m" + t))) {
                        return memberName!.length;
                    }
                }
                return 0;
            }
            """.trimIndent()
        ) should {
            have(isEmpty())
        }
    }

    /** NEGATIVE control (unusedLocalsInMethod4's transformClassFields): a compound
     *  `|=` is read-modify-write, NOT definite — the captured read still fires. */
    @Test fun compoundAssignDoesNotSuppressCapturedRead() {
        ts2454s(
            """
            export function outer() {
                let flags: number;
                function inner() {
                    flags |= 1;
                    return flags.toString();
                }
                return inner();
            }
            """.trimIndent()
        ) should {
            have(isNotEmpty(), "a compound |= must NOT count as the first assignment — tsc still emits TS2454")
        }
    }

    /** NEGATIVE control (B78.2 base behavior): no assignment anywhere — the
     *  captured read fires. */
    @Test fun neverAssignedCapturedReadStillFires() {
        ts2454s(
            """
            export function outer() {
                let x: number[];
                function foo() {
                    return x.length;
                }
                return foo();
            }
            """.trimIndent()
        ) should {
            have(isNotEmpty())
        }
    }

    /** tsc core.ts `or()`: `let lastResult: U;` conditionally assigned in a loop,
     *  then `return lastResult!` — the `!` assumes initialized. */
    @Test fun nonNullAssertedReadAssumesInitialized() {
        ts2454s(
            """
            export function orFn(fs: (() => number)[]) {
                let lastResult: number;
                for (const f of fs) {
                    lastResult = f();
                    if (lastResult) return lastResult;
                }
                return lastResult!;
            }
            """.trimIndent()
        ) should {
            have(isEmpty())
        }
    }

    /** `x!.prop` — the identifier's DIRECT parent is the assertion, exempt too. */
    @Test fun nonNullAssertedReceiverAssumesInitialized() {
        ts2454s(
            """
            export function f(cond: boolean) {
                let obj: { p: number };
                if (cond) { obj = { p: 1 }; }
                return obj!.p;
            }
            """.trimIndent()
        ) should {
            have(isEmpty())
        }
    }

    /** NEGATIVE control: the same shapes WITHOUT `!` still fire. */
    @Test fun plainReadWithoutAssertionStillFires() {
        ts2454s(
            """
            export function f() {
                let v: number;
                return v;
            }
            """.trimIndent()
        ) should {
            have(isNotEmpty())
        }
    }
}

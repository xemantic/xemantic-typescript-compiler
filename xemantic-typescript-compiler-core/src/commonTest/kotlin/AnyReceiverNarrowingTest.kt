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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (NARROW.2)(c), round 852 — an `any` subject NARROWS, and the property-access
 * walker READS the narrowed type.
 *
 * Three separable rules land here and each pin below names which one it fails
 * against:
 *
 *  1. **the exemption** — tsc's `isTypeAny(type) && (predicate.type ===
 *     globalObjectType || globalFunctionType)`: a guard onto the global `Object`
 *     or `Function` leaves an `any` subject `any`, in the type-predicate branch
 *     AND in the `instanceof` branch;
 *  2. **`instanceof` narrows an `any` subject** — tsc's `getNarrowedTypeWorker`
 *     opens `if (type.flags & AnyOrUnknown) return candidate;`, where ours
 *     answered the subject unchanged;
 *  3. **`checkMemberAccessMissing` reads a narrowed `any` receiver** — it bailed
 *     on `anyType` by construction, which is also its FP firewall.
 *
 * TWO PIN DISCIPLINES ARE LOAD-BEARING HERE AND BOTH COST EARLIER ROUNDS A PIN.
 * A narrowing pin that asserts a TS2339 APPEARS discriminates nothing (round
 * 751): it passes on a working build and on a build that never narrowed, for
 * opposite reasons — so every emission pin below asserts the **exact message**,
 * which NAMES the narrowed type and can only be produced by a build that both
 * narrowed and read the narrow. And a probe that must read the narrowed type
 * without involving rule 3 assigns to a **primitive** (rounds 760/762): `any` is
 * assignable to `string`, so `const s: string = x` is silent while the subject is
 * still `any` and reports the narrowed type by name once it is not.
 *
 * `@useRealLibs` throughout: `Object`, `Function`, `Error` and `Date` are LIB
 * types, and the embedded lib the corpus uses is not the one this rule is about
 * (round 806 — a shape validated through the project CLI is not automatically a
 * valid pin).
 */
class AnyReceiverNarrowingTest {

    private val prelude = """
        // @useRealLibs: true
        // @strict: false
        // @target: es2015
        declare var x: any;
        declare function isFunction(v: any): v is Function;
        declare function isObject(v: any): v is Object;
        declare function isAnything(v: any): v is {};
        declare function isError(v: any): v is Error;
    """.trimIndent() + "\n"

    private fun check(@Language("typescript") source: String): List<Diagnostic> =
        diagnose(prelude + source.trimIndent(), directives = "")

    // ---------------------------------------------------------------- rule 1

    @Test
    fun `a type-guard onto Function leaves an any subject any - so a property read stays legal`() {
        // Fails against rule 1 alone: without the exemption the guard hands back
        // `Function`, and rule 3 then reports `prop` missing on it.
        check(
            """
            if (isFunction(x)) {
                x.prop;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
            have(none { it.code == 2551 })
        }
    }

    @Test
    fun `a type-guard onto Object leaves an any subject any - so a property read stays legal`() {
        check(
            """
            if (isObject(x)) {
                x.method;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
            have(none { it.code == 2551 })
        }
    }

    @Test
    fun `the Function exemption is visible at a primitive probe with no property access`() {
        // The rule-1-only pin: reads the narrowed type through an ASSIGNMENT, so
        // it fails against the exemption without depending on rule 3 at all.
        // Un-exempt, the subject would be `Function` and this would be TS2322.
        check(
            """
            if (isFunction(x)) {
                const s: string = x;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a guard onto the empty object type is NOT exempt - it narrows and the read fails`() {
        // The other half of the exemption, in the same fixture family: `{}` is
        // not `Object`, so tsc narrows to it and `method` is genuinely missing.
        check(
            """
            if (isAnything(x)) {
                x.method;
            }
            """,
        ) should {
            have(
                any {
                    it.code == 2339 &&
                        it.message == "Property 'method' does not exist on type '{}'."
                },
            )
        }
    }

    // ---------------------------------------------------------------- rule 2

    @Test
    fun `instanceof narrows an any subject - seen at a primitive probe`() {
        // The rule-2-only pin: no property access, so rule 3 cannot carry it.
        check(
            """
            if (x instanceof Error) {
                const s: string = x;
            }
            """,
        ) should {
            have(
                any {
                    it.code == 2322 &&
                        it.message == "Type 'Error' is not assignable to type 'string'."
                },
            )
        }
    }

    @Test
    fun `instanceof onto Function does not narrow an any subject`() {
        check(
            """
            if (x instanceof Function) {
                const s: string = x;
                x.prop;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `instanceof onto Object does not narrow an any subject`() {
        check(
            """
            if (x instanceof Object) {
                const s: string = x;
                x.method;
            }
            """,
        ) should {
            have(none { it.code == 2322 })
            have(none { it.code == 2339 })
        }
    }

    // ---------------------------------------------------------------- rule 3

    @Test
    fun `a misspelled member of a guard-narrowed any receiver reports the narrowed type`() {
        check(
            """
            if (isError(x)) {
                x.message;
                x.mesage;
            }
            """,
        ) should {
            have(
                any {
                    it.code == 2551 &&
                        it.message ==
                        "Property 'mesage' does not exist on type 'Error'. Did you mean 'message'?"
                },
            )
        }
    }

    @Test
    fun `a misspelled member of an instanceof-narrowed any receiver reports the narrowed type`() {
        check(
            """
            if (x instanceof Error) {
                x.message;
                x.mesage;
            }
            """,
        ) should {
            have(
                any {
                    it.code == 2551 &&
                        it.message ==
                        "Property 'mesage' does not exist on type 'Error'. Did you mean 'message'?"
                },
            )
        }
    }

    @Test
    fun `a narrowed catch parameter is read through the function-local branch`() {
        // The other of the two `any` bails in cmamGeneralReceiverType: a catch
        // parameter has no globals symbol, so it types through
        // `getTypeOfIdentifier`. The conformance case is
        // narrowExceptionVariableInCatchClause.
        check(
            """
            function tryCatch() {
                try {
                } catch (err) {
                    if (err instanceof Error) {
                        err.message;
                        err.massage;
                    }
                }
            }
            """,
        ) should {
            have(
                any {
                    it.code == 2551 &&
                        it.message ==
                        "Property 'massage' does not exist on type 'Error'. Did you mean 'message'?"
                },
            )
        }
    }

    @Test
    fun `a guard onto an anonymous shape narrows a catch parameter and names that shape`() {
        check(
            """
            declare function isFooError(v: any): v is { type: 'foo'; dontPanic(); };
            function tryCatch() {
                try {
                } catch (err) {
                    if (isFooError(err)) {
                        err.dontPanic();
                        err.doPanic();
                    }
                }
            }
            """,
        ) should {
            have(
                any {
                    it.code == 2551 && it.message.startsWith("Property 'doPanic' does not exist") &&
                        it.message.endsWith("Did you mean 'dontPanic'?")
                },
            )
        }
    }

    // ------------------------------------------------------- the FP firewall

    @Test
    fun `negative control - an un-narrowed any receiver stays silent`() {
        // The property-access walker's silence for a plain `any` receiver is the
        // firewall this item opens a hole in; the hole must stay exactly the size
        // of "a narrow happened".
        check(
            """
            x.whateverIsNotThere;
            x.norThis.norThat;
            """,
        ) should {
            have(none { it.code == 2339 })
            have(none { it.code == 2551 })
        }
    }

    @Test
    fun `negative control - an any subject survives the negative branch and stays silent`() {
        // (NARROW.2)(b), round 838: the else branch of a guard on an `any`
        // subject must not wash to `never` — and must not acquire the guard
        // target either, which the accumulation bug used to make it do.
        check(
            """
            if (isError(x)) {
            } else {
                x.anythingAtAll;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
            have(none { it.code == 2551 })
        }
    }

    @Test
    fun `negative control - a narrow that resolves the member emits nothing`() {
        check(
            """
            if (x instanceof Error) {
                x.message;
                x.stack;
                x.name;
            }
            """,
        ) should {
            have(none { it.code == 2339 })
            have(none { it.code == 2551 })
        }
    }

    @Test
    fun `negative control - the narrow does not outlive its if block`() {
        check(
            """
            if (x instanceof Error) {
                x.message;
            }
            x.anythingAtAll;
            """,
        ) should {
            have(none { it.code == 2339 })
            have(none { it.code == 2551 })
        }
    }
}

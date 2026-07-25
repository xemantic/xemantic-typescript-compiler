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
 * Self-compile burn-down: a user type-guard whose PARAMETER is named with a type keyword
 * (`function isTransientSymbol(symbol: Symbol): symbol is TransientSymbol`) never narrowed,
 * so `if (isTransientSymbol(sym)) sym.links` FP-fired TS2339. Root cause is in the parser:
 * a type-predicate SUBJECT is grammatically a parameter NAME, but the parser reaches it via
 * `parseType`, which turns a keyword-like name (`symbol`/`string`/`object`/…) into a
 * `KeywordTypeNode` rather than an Identifier — so the checker's `predicateParamName`
 * extraction returned null and the whole guard was silently ignored. The fix recovers the
 * subject Identifier from the keyword text when `is` follows. tsc's own compiler tripped this
 * ~5 times in `services` (`isTransientSymbol(symbol): symbol is TransientSymbol`).
 */
class TypePredicateKeywordSubjectTest {

    private val prelude = """
        interface Base { flags: number; }
        interface Links { target?: Derived; }
        interface Derived extends Base { links: Links; }
    """.trimIndent() + "\n"

    @Test
    fun `guard param named 'symbol' narrows down and gives access to the subtype member`() {
        diagnose(
            prelude +
                """
                declare function isDerived(symbol: Base): symbol is Derived;
                export function f(x: Base) {
                    if (isDerived(x)) { return x.links; }
                    return undefined;
                }
                """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `chained 'AND' re-narrowing with a 'symbol'-param guard - no TS2339`() {
        diagnose(
            prelude +
                """
                declare function isDerived(symbol: Base): symbol is Derived;
                export function f(x: Base) {
                    if (isDerived(x) && x.links.target && isDerived(x.links.target) && x.links.target.links) {
                        return x.links.target.links;
                    }
                    return undefined;
                }
                """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `guard params named 'string' and 'object' also narrow - no TS2339`() {
        diagnose(
            prelude +
                """
                declare function isDerivedS(string: Base): string is Derived;
                declare function isDerivedO(object: Base): object is Derived;
                export function f(x: Base) {
                    if (isDerivedS(x)) return x.links;
                    if (isDerivedO(x)) return x.links;
                    return undefined;
                }
                """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a plain-identifier guard param still narrows - regression control`() {
        diagnose(
            prelude +
                """
                declare function isDerived(s: Base): s is Derived;
                export function f(x: Base) {
                    if (isDerived(x)) { return x.links; }
                    return undefined;
                }
                """,
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `a keyword-subject predicate still fires TS2339 when the member is genuinely absent - negative control`() {
        // FP-safety: the fix only makes the guard narrow; a member that exists on neither the
        // base nor the narrowed subtype must still error.
        diagnose(
            prelude +
                """
                declare function isDerived(symbol: Base): symbol is Derived;
                export function f(x: Base) {
                    if (isDerived(x)) { return x.nonexistent; }
                    return undefined;
                }
                """,
        ) should {
            have(any { it.code == 2339 })
        }
    }
}

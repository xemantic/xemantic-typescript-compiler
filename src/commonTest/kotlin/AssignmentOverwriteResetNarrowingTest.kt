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
 * Round 424 — an assignment that targets the walked reference RESETS the flow
 * narrowing (tsc getTypeAtFlowAssignment), even when the RHS is unclassifiable;
 * and a VariableDeclaration resolves ITS OWN type at the flow position (the
 * flat name-keyed local map is block-unaware/first-decl-wins, so the reader's
 * "declared type" may belong to an OUTER shadowed binding).
 *
 * The pinned shape (tsc moduleNameResolver `nodeModuleNameResolverWorker`):
 *
 *     const resolved = tryLoad(name);            // Resolved | undefined
 *     if (resolved) { return …; }
 *     {
 *         const resolved = loadFromImports(name); // SearchResult<Resolved>
 *         if (resolved) { resolved.value; }       // ← was TS2339 on `never`
 *     }
 *
 * The walk crossed the OUTER falsy branch (→ `undefined`), passed the inner
 * shadowing declaration UNCHANGED (unclassifiable call RHS kept the stale
 * antecedent), and the inner truthy guard narrowed `undefined` → `never`.
 */
class AssignmentOverwriteResetNarrowingTest {

    private val prelude = """
        interface Resolved { path: string; extension: string; }
        type SearchResult<T> = { value: T | undefined; } | undefined;
        declare function tryLoad(name: string): Resolved | undefined;
        declare function loadFromImports(name: string): SearchResult<Resolved>;
    """

    @Test
    fun shadowingRedeclarationAfterFalsyGuardDoesNotCollapseToNever() {
        diagnose(
            prelude + """
            export function f(name: string, features: number) {
                const resolved = tryLoad(name);
                if (resolved) {
                    return resolved.path;
                }
                if (features & 2) {
                    const resolved = loadFromImports(name);
                    if (resolved) {
                        return resolved.value && resolved.value.path;
                    }
                }
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun reassignmentFromCallResetsToTheCallReturnType() {
        // Same-variable `let` reassignment from a call: the post-assignment
        // state is the RHS call's resolved return type — a preceding guard's
        // narrowing must not leak past the overwrite (here it would wrongly
        // keep `undefined` and the truthy guard would collapse to `never`).
        // Deliberately NOT the reader's flat-map declared type: that injects
        // an outer shadowed binding's type (3 measured new FPs).
        diagnose(
            prelude + """
            export function g(name: string) {
                let r = tryLoad(name);
                if (!r) {
                    r = tryLoad(name + "2");
                    if (r) {
                        return r.path;
                    }
                }
                return undefined;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun genuineMissingMemberOnUnionStillFires() {
        // Negative control: the overwrite reset must not suppress a genuine
        // missing-member TS2339 on the declaration's own (union) type.
        diagnose(
            prelude + """
            interface Failed { reason: string; }
            declare function loadOther(name: string): Resolved | Failed;
            export function k(name: string) {
                const resolved = loadOther(name);
                return resolved.path;
            }
            """
        ) should {
            have(any { it.code == 2339 && it.message.contains("path") })
        }
    }
}

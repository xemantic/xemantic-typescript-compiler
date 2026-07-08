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
 * Round 445 (self-compile burn-down, services TS2416 11 → 0): the class-property-override
 * check (TS2416) had three false-positive families, all exercised by services.ts's
 * NodeObject/TokenOrIdentifierObject/SourceFileObject implementors of the compiler's core
 * interfaces:
 *
 *  (A) An OPTIONAL base member `p?: T` has effective type `T | undefined` under
 *      strictNullChecks. A derived override declared `p: T | undefined` (non-optional but
 *      nullish-including) is legal — the raw base declared type dropped the optional
 *      `| undefined`, so the relation compared `T | undefined` (derived) against `T` (base).
 *      Fixed by widening the base target with `| undefined` for the relation (source-nullish
 *      gated → a non-nullish derived override still compares against the bare base type).
 *
 *  (B) A derived override typed as a CONSTRAINED type parameter (`kind: TKind` where
 *      `TKind extends SyntaxKind`) overriding a base member typed as the constraint
 *      (`kind: SyntaxKind`) is a valid override — every `TKind` value is a `SyntaxKind`.
 *      Our relation engine has no general TypeParam-source rule, so a per-site constraint
 *      bail was added.
 *
 *  (C) tsc compares METHOD signatures with BIVARIANT parameters (only function-type
 *      PROPERTIES get strict contravariance). A base method `getWidth(sf?: SourceFileLike)`
 *      overridden by `getWidth(sf?: SourceFile)` (SourceFile <: SourceFileLike) is legal
 *      bivariantly. A per-site bivariant retry was added for method members.
 */
class PropertyOverrideOptionalAndVarianceTest {

    // ---- (A) optional base member widening --------------------------------------------

    @Test
    fun `class property overriding an optional base member as T-or-undefined - no TS2416`() {
        diagnose(
            """
            interface CheckJsDirective { enabled: boolean; }
            interface SourceFile {
                checkJsDirective?: CheckJsDirective;
            }
            class SourceFileObject implements SourceFile {
                public checkJsDirective: CheckJsDirective | undefined;
                constructor() { this.checkJsDirective = undefined; }
            }
            """,
        ) should {
            have(none { it.code == 2416 })
        }
    }

    @Test
    fun `optional base member widening works for a union base member type - no TS2416`() {
        // localJsxFactory?: EntityName vs derived EntityName | undefined.
        diagnose(
            """
            interface Identifier { i: number; }
            interface QualifiedName { q: number; }
            type EntityName = Identifier | QualifiedName;
            interface SourceFile {
                localJsxFactory?: EntityName;
            }
            class SourceFileObject implements SourceFile {
                public localJsxFactory: EntityName | undefined;
            }
            """,
        ) should {
            have(none { it.code == 2416 })
        }
    }

    @Test
    fun `negative control - overriding an optional base member with a WRONG concrete type still fires TS2416`() {
        diagnose(
            """
            interface CheckJsDirective { enabled: boolean; }
            interface SourceFile {
                checkJsDirective?: CheckJsDirective;
            }
            class SourceFileObject implements SourceFile {
                public checkJsDirective: number = 0;
            }
            """,
        ) should {
            have(any { it.code == 2416 })
        }
    }

    // ---- (B) constrained-type-parameter override --------------------------------------

    @Test
    fun `generic class property typed as a constrained TP overriding the constraint - no TS2416`() {
        diagnose(
            """
            declare const enum SyntaxKind { A, B, C }
            interface Node { kind: SyntaxKind; }
            class NodeObject<TKind extends SyntaxKind> implements Node {
                public kind: TKind;
                constructor(k: TKind) { this.kind = k; }
            }
            """,
        ) should {
            have(none { it.code == 2416 })
        }
    }

    @Test
    fun `negative control - unconstrained TP override of a concrete base member still fires TS2416`() {
        diagnose(
            """
            declare const enum SyntaxKind { A, B, C }
            interface Node { kind: SyntaxKind; }
            class NodeObject<T> implements Node {
                public kind: T;
                constructor(k: T) { this.kind = k; }
            }
            """,
        ) should {
            have(any { it.code == 2416 })
        }
    }

    // ---- (C) method-parameter bivariance ----------------------------------------------

    @Test
    fun `method override widening a parameter to a subtype is bivariant - no TS2416`() {
        // Base getWidth(sf?: SourceFileLike); derived getWidth(sf?: SourceFile) where
        // SourceFile extends SourceFileLike (base param is WIDER — fails contravariance,
        // passes bivariance the way tsc compares methods).
        diagnose(
            """
            interface SourceFileLike { text: string; }
            interface SourceFile extends SourceFileLike { fileName: string; }
            interface Node {
                getWidth(sourceFile?: SourceFileLike): number;
            }
            class NodeObject implements Node {
                public getWidth(sourceFile?: SourceFile): number { return 0; }
            }
            """,
        ) should {
            have(none { it.code == 2416 })
        }
    }

    @Test
    fun `negative control - method override with an UNRELATED parameter type still fires TS2416`() {
        diagnose(
            """
            interface Node {
                getWidth(sourceFile?: string): number;
            }
            class NodeObject implements Node {
                public getWidth(sourceFile?: number): number { return 0; }
            }
            """,
        ) should {
            have(any { it.code == 2416 })
        }
    }
}

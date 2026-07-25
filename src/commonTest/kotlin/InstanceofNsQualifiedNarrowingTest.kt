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
 * Round 513 (INV.3(d)(v)): two narrowing gaps unmasked by deleting the round-442
 * `moduleFileLocalVarNames` bail (which had been over-suppressing every member
 * access on a `sys`-rooted chain because compiler/sys.ts exports a module-level
 * `let sys`). The tsc shape is fakesHosts.ts's ctor:
 * `constructor(sys: System | vfs.FileSystem) { if (sys instanceof vfs.FileSystem)
 * sys = new System(sys); … sys.vfs … }`.
 *
 * (1) `resolveInstanceOfRhsType` resolves a NAMESPACE-IMPORT-qualified class
 * (`vfs.FileSystem`) through [namespaceAliasMemberSymbol] — both instanceof
 * branches narrow (the general resolveAlias cannot follow NamespaceImports).
 * (2) `narrowByAssignmentRhs` applies tsc's assignment reduction to a
 * NEW-EXPRESSION RHS: the post-assignment type keeps only declared union members
 * the constructed instance relates to, instead of resetting to the full declared
 * union via the blanket non-nullish overwrite.
 * Plus the dir-relative resolver leg in [namespaceAliasMemberSymbol] (the third
 * instance of the round-511 lesson class — path-shaped extensionless imports).
 */
class InstanceofNsQualifiedNarrowingTest {

    private fun compile(@Language("typescript") source: String) =
        TypeScriptCompiler().compile(source.trimIndent(), "entry.ts").diagnostics

    private val vfsModule = """
        // @strict: true

        // @Filename: /proj/src/vfsmod.ts
        export class FileSystem {
            public readonly meta: Map<string, string> = new Map();
            public fsOnly(): number { return 1; }
        }
    """

    @Test
    fun `qualified ns-import instanceof narrows the positive branch`() {
        compile(
            vfsModule + """

            // @Filename: /proj/src/hosts.ts
            import * as vfs from "./vfsmod";
            export class System { public sysOnly(): number { return 2; } }
            export function f(sys: System | vfs.FileSystem): number {
                if (sys instanceof vfs.FileSystem) {
                    return sys.fsOnly();
                }
                return 0;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `qualified ns-import instanceof narrows the negative branch`() {
        compile(
            vfsModule + """

            // @Filename: /proj/src/hosts.ts
            import * as vfs from "./vfsmod";
            export class System { public sysOnly(): number { return 2; } }
            export function f(sys: System | vfs.FileSystem): number {
                if (sys instanceof vfs.FileSystem) { return 0; }
                return sys.sysOnly();
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `new-expression assignment reduces the declared union - the fakesHosts ctor shape`() {
        compile(
            vfsModule + """

            // @Filename: /proj/src/hosts.ts
            import * as vfs from "./vfsmod";
            export class System {
                public readonly vfs: vfs.FileSystem;
                constructor(fs: vfs.FileSystem) { this.vfs = fs; }
            }
            export class Host {
                public loc: string;
                constructor(sys: System | vfs.FileSystem) {
                    if (sys instanceof vfs.FileSystem) sys = new System(sys);
                    this.loc = sys.vfs.meta.get("x") || "";
                }
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a genuinely missing member on the reduced type still fires`() {
        compile(
            vfsModule + """

            // @Filename: /proj/src/hosts.ts
            import * as vfs from "./vfsmod";
            export class System { public sysOnly(): number { return 2; } }
            export function f(sys: System | vfs.FileSystem): number {
                if (sys instanceof vfs.FileSystem) sys = new System();
                return sys.doesNotExist();
            }
            """
        ) should {
            have(any { it.code == 2339 })
        }
    }

    @Test
    fun `new-expression reduction keeps the conservative union for an unrelated class`() {
        // `new Unrelated()` relates to NEITHER declared member — the kept set is
        // empty, so the reduction falls through (no narrowing) and a member access
        // present on only one member still fires on the union.
        compile(
            vfsModule + """

            // @Filename: /proj/src/hosts.ts
            import * as vfs from "./vfsmod";
            export class System { public sysOnly(): number { return 2; } }
            export class Unrelated { public other(): string { return ""; } }
            export function f(sys: System | vfs.FileSystem): number {
                if (sys instanceof vfs.FileSystem) sys = new Unrelated();
                return sys.sysOnly();
            }
            """
        ) should {
            have(any { it.code == 2339 })
        }
    }
}

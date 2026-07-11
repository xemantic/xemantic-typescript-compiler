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
 * Round 475 (server project.ts:2764 / editorServices.ts:2897/3428): a class instance type
 * carries constructSignatures (own ctor + INHERITED-FIRST base ctors) since the
 * resolveInterfaceMembers ctor-sig population, so `getReturnTypeOfNewExpression`'s
 * constructor-interface branch (16.4gi, designed for `declare var X: XConstructor`) took
 * `sigs[0].resolvedReturnType` = the BASE class's ctor return — `new ConfiguredProject(...)`
 * typed as `Project`. The branch is now gated to NON-class callee types; a class callee's
 * new-expr type is the instance type itself.
 */
class NewExprSubclassInstanceTest {

    private val prelude = """
        // @strict: true
        abstract class Project {
            private readonly seq: number = 0;
            constructor(
                readonly projectName: string,
                readonly kind: number,
            ) {}
            getName(): string { return this.projectName; }
        }
        class ConfiguredProject extends Project {
            pendingUpdateLevel: number = 0;
            pendingUpdateReason: string | undefined;
            constructor(
                configFileName: string,
                readonly canonicalConfigFilePath: string,
                pendingUpdateReason: string,
            ) {
                super(configFileName, 2);
                this.pendingUpdateReason = pendingUpdateReason;
            }
        }
    """

    @Test
    fun `new subclass instance types as the subclass, not the base - no TS2739 on return`() {
        diagnose(
            prelude + """
            class ProjectService {
                readonly configuredProjects: Map<string, ConfiguredProject> = new Map();
                create(a: string, b: string, c: string): ConfiguredProject {
                    const project = new ConfiguredProject(a, b, c);
                    this.configuredProjects.set(b, project);
                    return project;
                }
            }
            """,
            directives = "",
        ) should {
            have(none { it.code == 2739 })
            have(none { it.code == 2740 })
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `negative control - new base instance still fails a subclass-typed return`() {
        diagnose(
            prelude + """
            class Concrete extends Project {
                constructor() { super("c", 1); }
            }
            function make(): ConfiguredProject {
                return new Concrete();
            }
            """,
            directives = "",
        ) should {
            have(any { it.code == 2739 || it.code == 2740 || it.code == 2741 || it.code == 2322 })
        }
    }

    @Test
    fun `constructor-interface pattern still resolves the construct signature return`() {
        // `declare var Thing: ThingConstructor` — a NON-class callee keeps resolving
        // the instance from the construct signature's return type, so instance members
        // resolve (no TS2339 on the constructor interface).
        diagnose(
            """
            interface Thing { value: number; }
            interface ThingConstructor { new (v: number): Thing; }
            declare var Thing: ThingConstructor;
            const t = new Thing(1);
            const n: number = t.value;
            """,
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2339 })
            have(none { it.code == 2322 })
        }
    }
}

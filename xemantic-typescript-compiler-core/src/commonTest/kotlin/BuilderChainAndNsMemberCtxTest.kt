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
 * Round 472 (the tsc-cli/deprecatedCompat zero-real-FP batch): three TS7006/TS2339
 * contextual-typing/lib gaps.
 * 1. An arrow's EXPRESSION body inherits the contextual signature's RETURN type
 *    (`overload: overloads => ({ bind: binder => ({ … }) })` against a builder
 *    interface chain — tsc deprecations.ts buildOverload).
 * 2. An object literal with fn-shaped members consumes an assignment-target fn
 *    context, and a `ns.Sub.member = {…}` target resolves its declared annotation
 *    through the merged globals when the root is a namespace import
 *    (`ts.Debug.loggingHost = { log(_level, s) {…} }` — tsc tsc.ts:7).
 * 3. The embedded lib's ObjectConstructor carries getOwnPropertyDescriptor(s)
 *    (consulted even under real libs via libGlobals — deprecations.ts:82).
 */
class BuilderChainAndNsMemberCtxTest {

    @Test
    fun `a builder chain's nested returned objlits contextually type their fn members`() {
        diagnose(
            """
            interface Defs { [key: number]: (...args: any[]) => any; }
            interface Binders<T> { bindIt(t: T): void; }
            interface OverloadBuilder {
                overload<T extends Defs>(overloads: T): BindableOverloadBuilder<T>;
            }
            interface BindableOverloadBuilder<T extends Defs> {
                bind(binder: Binders<T>): BoundOverloadBuilder<T>;
            }
            interface BoundOverloadBuilder<T extends Defs> {
                finish(): number;
                deprecate(deprecations: string): number;
            }
            declare function createOverload<T extends Defs>(name: string, overloads: T, binder: Binders<T>, deprecations?: string): number;
            export function buildOverload(name: string): OverloadBuilder {
                return {
                    overload: overloads => ({
                        bind: binder => ({
                            finish: () => createOverload(name, overloads, binder),
                            deprecate: deprecations => createOverload(name, overloads, binder, deprecations),
                        }),
                    }),
                };
            }
            """.trimIndent(),
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `a namespace-member objlit assignment contextually types its method params`() {
        diagnose(
            """
            // @filename: debug.ts
            export interface LoggingHost {
                log(level: number, s: string): void;
            }
            export namespace Debug {
                export let loggingHost: LoggingHost | undefined;
            }
            // @filename: barrel.ts
            export * from "./debug";
            // @filename: tsc.ts
            import * as ts from "./barrel";
            ts.Debug.loggingHost = {
                log(_level, s) {
                    void s;
                },
            };
            """.trimIndent(),
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - an unannotated arrow param without any context still fires`() {
        diagnose(
            """
            const f = x => x + 1;
            """.trimIndent(),
            directives = "// @strict: true\n// @noImplicitAny: true",
        ) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `ObjectConstructor descriptor members resolve`() {
        diagnose(
            """
            const d = Object.getOwnPropertyDescriptor({ a: 1 }, "a");
            const ds = Object.getOwnPropertyDescriptors({ a: 1 });
            """.trimIndent(),
            directives = "// @strict: true\n// @target: es2020",
        ) should {
            have(none { it.code == 2339 })
        }
    }
}

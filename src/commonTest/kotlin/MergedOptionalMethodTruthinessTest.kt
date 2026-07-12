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
 * Round 479: the TS2774 uncalled-function nullish detection counts ANY optional
 * declaration of the accessed member — a cross-file class+interface merge
 * (harness fakesHosts `class System { realpath() {} }` vs compiler sys.ts
 * `interface System { realpath?(): string }`) pollutes the merged prop's
 * declarations, and the first-declaration-only isOptionalProperty read the
 * non-optional one; tsc resolves the un-merged interface member, which is
 * optional, so presence-testing it is legal.
 */
class MergedOptionalMethodTruthinessTest {

    @Test
    fun `optional method with a merged non-optional class decl is presence-testable`() {
        diagnose(
            """
            // @module: nodenext
            // @filename: sys.ts
            export interface System {
                write(s: string): void;
                realpath?(path: string): string;
            }
            // @filename: fakes.ts
            export class System {
                public realpath(path: string): string { return path; }
            }
            // @filename: svc.ts
            import { System } from "./sys.js";
            export interface ServerHost extends System {
                setTimeout(callback: (...args: unknown[]) => void, ms: number): unknown;
            }
            export class ProjectService {
                public readonly host: ServerHost;
                private infos: string[] | undefined;
                constructor(host: ServerHost) {
                    this.host = host;
                    if (this.host.realpath) {
                        this.infos = [];
                    }
                }
            }
            """,
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2774 })
        }
    }

    @Test
    fun `negative control - a required method truthiness still fires`() {
        diagnose(
            """
            interface Host {
                write(s: string): void;
            }
            export function f(host: Host): void {
                if (host.write) {
                    void 0;
                }
            }
            """,
        ) should {
            have(any { it.code == 2774 })
        }
    }
}

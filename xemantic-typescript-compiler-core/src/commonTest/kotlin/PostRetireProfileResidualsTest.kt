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
 * INV.3(d)(iv) round 512 — the three post-retire profile residual families:
 *  - a body-local shadowing a same-named IMPORT must silence member-access
 *    checks on the outer binding (deprecate.ts's `const version` vs the
 *    barrel-imported corePublic `const version: string` — the anyType shadow
 *    registration now BAILS mam instead of falling through to the import);
 *  - two instances of the SAME clean interface must relate (server session.ts's
 *    protocol.Diagnostic — the retired round-473 per-file-view DISPATCH minted
 *    context-mixed instances whose nested members diverged);
 *  - the string-relation's array-vs-named-union-member rule resolves the
 *    member alias through import hops (fourslashImpl's `expected = [expected]`
 *    vs `ArrayOrSingle<T> | …`).
 */
class PostRetireProfileResidualsTest {

    @Test
    fun `body-local shadowing an imported string const is not typed as the import`() {
        diagnose(
            """
            // @module: commonjs
            // @filename: core.ts
            export const version = "5.9";
            export class Version {
                compareTo(other: Version): number { return 0; }
            }

            // @filename: deprecate.ts
            import { version, Version } from "./core";
            export function createDeprecation(v: Version | string | undefined) {
                const version = typeof v === "string" ? new Version() : v ?? new Version();
                return version.compareTo(new Version()) >= 0;
            }
            """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2339 && it.message.contains("compareTo") })
        }
    }

    @Test
    fun `negative control - a genuinely-string param still fires on a bogus member`() {
        diagnose(
            """
            // @module: commonjs
            // @filename: main.ts
            export function f(s: string) {
                return s.compareToz;
            }
            """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(any { it.code == 2339 || it.code == 2551 })
        }
    }

    @Test
    fun `two references to the same imported interface relate - no TS2322`() {
        // The server session.ts shape: an interface with a NESTED same-file
        // interface member, referenced both via a qualified name (through a
        // namespace import) and via the member annotation — both must resolve
        // to the SAME clean declaration post-retire.
        diagnose(
            """
            // @module: commonjs
            // @filename: protocol.ts
            export interface RelatedInfo { category: string; }
            export interface Diagnostic {
                text: string;
                relatedInformation?: RelatedInfo[];
            }
            export interface EventBody { diagnostics: Diagnostic[]; }

            // @filename: types.ts
            export interface RelatedInfo { category: number; }
            export interface Diagnostic { code: number; relatedInformation?: RelatedInfo[]; }

            // @filename: session.ts
            import * as protocol from "./protocol";
            import { Diagnostic } from "./types";
            declare function formatDiag(d: Diagnostic): protocol.Diagnostic;
            export function send(diagnostics: Diagnostic[]) {
                const body: protocol.EventBody = {
                    diagnostics: diagnostics.map(d => formatDiag(d)),
                };
                return body;
            }
            """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `array literal reassigned to an imported array-ish union alias param stays clean`() {
        diagnose(
            """
            // @module: commonjs
            // @filename: iface.ts
            export type ArrayOrSingle<T> = T | readonly T[];
            export interface Entry { name: string; }

            // @filename: impl.ts
            import { ArrayOrSingle, Entry } from "./iface";
            declare function isArray(x: unknown): x is readonly unknown[];
            export function verify(expected: ArrayOrSingle<Entry> | string) {
                if (!isArray(expected)) {
                    expected = [expected as Entry];
                }
                return expected;
            }
            """.trimIndent(),
            directives = "// @strict: true",
        ) should {
            have(none { it.code == 2322 })
        }
    }
}

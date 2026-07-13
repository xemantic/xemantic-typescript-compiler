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
 * INV.3(d) (round 510): the `mergeSymbolTable(globals, locals)` conflation is
 * RETIRED for a MODULE file's MODULE-ONLY top-level locals — they are
 * module-scoped in real tsc, served per-file by `lookupPerFile`/`globalsForFile`.
 * Still merging: script-file locals (they ARE the global namespace), SHARED
 * names (a module local colliding with a lib/script global — e.g.
 * compiler/types.ts's `interface Symbol` riding the lib `Symbol`), the
 * `declare global` namespace, ambient `declare module "X"` carriers, and UMD
 * `export as namespace X` names.
 */
class Inv3MergeRetireTest {

    @Test
    fun `a foreign module-local type no longer resolves - the leaked TS2741 dies`() {
        // Pre-retire: b.ts resolved `Conf` through the merged globals and
        // manufactured TS2741 about a type the file never imports (tsc sees
        // only TS2304 on the name).
        diagnose(
            """
            // @filename: a.ts
            export const anchor = 1;
            interface Conf { host: string; }

            // @filename: b.ts
            export const other = 2;
            const c: Conf = {};
            """
        ) should {
            have(none { it.code == 2741 })
        }
    }

    @Test
    fun `negative control - an IMPORTED module-local type keeps resolving - TS2741 fires`() {
        diagnose(
            """
            // @filename: a.ts
            export interface Conf { host: string; }

            // @filename: b.ts
            import { Conf } from "./a";
            const c: Conf = {};
            """
        ) should {
            have(any { it.code == 2741 })
        }
    }

    @Test
    fun `negative control - a SCRIPT file's locals stay global - TS2741 fires`() {
        diagnose(
            """
            // @filename: a.ts
            interface Conf { host: string; }

            // @filename: b.ts
            const c: Conf = {};
            """
        ) should {
            have(any { it.code == 2741 })
        }
    }

    @Test
    fun `negative control - an ambient declare module in a MODULE file stays importable`() {
        diagnose(
            """
            // @filename: decls.d.ts
            export const anchor = 1;
            declare module "phantom" {
                export function f(): number;
            }

            // @filename: b.ts
            import { f } from "phantom";
            const n: string = f();
            """
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `augmentation members merge into the target module per-file - no TS2339 on base OR added members`() {
        // The services-profile flood pin (round 510): the augmentation must merge
        // into the TARGET FILE's local symbol (the .js-aware resolution), never
        // into `globals` as an augmentation-only stub — the stub made every
        // annotation of the name resolve WITHOUT the base members (TS2339×5355).
        diagnose(
            """
            // @filename: a.ts
            export interface SF { path: string; }

            // @filename: b.ts
            import { SF } from "./a.js";
            declare module "./a.js" {
                interface SF { extra(): void; }
            }
            export const anchor = 2;

            // @filename: c.ts
            import { SF } from "./a.js";
            export function g(sf: SF): string {
                sf.extra;
                return sf.path;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `type-space import shadowed by a same-named local function value still resolves the type`() {
        // The utilities.ts SourceMapSource shape: `import { SMS }` (a type) + a
        // local `function SMS(...)` (a value) — binder last-wins keeps only the
        // value in file locals; the TYPE space must recover the import (the
        // ImportSpecifier's alias symbol is still node-recorded).
        diagnose(
            """
            // @filename: a.ts
            export interface SMS { y: number; }

            // @filename: b.ts
            import { SMS } from "./a";
            function SMS(this: SMS, y: number): void {
                this.y = y;
            }
            const v: SMS = {};
            """
        ) should {
            have(any { it.code == 2741 })
        }
    }

    @Test
    fun `a body-local shadowing an IMPORTED function stays suppressed in value positions`() {
        // The checker.ts `symbolName` shape: the shadow-ecology collision
        // question now includes currentFileLocals (imports no longer sit in the
        // merged globals) — without it the body-local resolved to the imported
        // function's type and manufactured TS2345.
        diagnose(
            """
            // @filename: a.ts
            export function tag(s: string): string { return s; }

            // @filename: b.ts
            import { tag } from "./a";
            declare function unesc(s: string): unknown;
            declare function consume(s: string): void;
            export function g(raw: string): void {
                function inner(): void {
                    const tag = unesc(raw);
                    consume(tag);
                }
                inner();
            }
            """
        ) should {
            have(none { it.code == 2345 })
        }
    }

    @Test
    fun `a namespace-qualified heritage base through an import star resolves per-file`() {
        // The RefactorContext shape: `interface R extends NS.Base` where NS is a
        // namespace import — the retired merge no longer leaks the last-segment
        // name, so the base resolves through the target module's exports.
        diagnose(
            """
            // @filename: chg.ts
            export interface Base { host: number; }

            // @filename: b.ts
            import * as chg from "./chg";
            export interface R extends chg.Base {
                own: string;
            }
            export function g(r: R): number {
                return r.host;
            }
            """
        ) should {
            have(none { it.code == 2339 })
        }
    }
}

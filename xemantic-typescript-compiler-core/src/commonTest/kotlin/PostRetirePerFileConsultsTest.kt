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
 * INV.3(d) round 512 — the last four (d)(ii) per-consult flips. Each pins a
 * cross-file emitter whose `globals[name]` consult silently died when the
 * merge retired module-only names:
 *  - TS2749 value-used-as-type for a default import
 *    ([Checker.isValueOnlyTypeRef] — file-keyed via globalsForFile);
 *  - the B585 contextual alias DISPLAY for a JS object-literal method's
 *    `this.X = v` mismatch ([Checker.objLitArgCalleeParamTypeNode] /
 *    [Checker.objLitCtxMemberTypeNode] — node-keyed hops, incl. the
 *    dir-relative resolveAlias ImportSpecifier leg for path-shaped layouts);
 *  - the JSDoc `@type {import("./a").Foo}` export-default excess check
 *    ([Checker.resolveJsDocExportType] — resolves through the ImportType's
 *    own specifier);
 *  - TS2415 private-conflict against an IMPORTED base class
 *    ([Checker.checkClassPropertyOverrides]'s heritage Identifier arm —
 *    node-keyed, hopping the import alias).
 */
class PostRetirePerFileConsultsTest {

    @Test
    fun `default import of a plain value used as a type fires TS2749`() {
        diagnose(
            """
            // @module: commonjs
            // @filename: b.ts
            export const zzz = 123;
            export default zzz;

            // @filename: index.ts
            import originalZZZ from "./b";
            const y: originalZZZ = 1;
            """.trimIndent(),
            directives = "",
        ) should {
            have(any { it.code == 2749 && it.message.startsWith("'originalZZZ' refers to a value") })
        }
    }

    @Test
    fun `negative control - type-eligible default import used as a type stays clean`() {
        diagnose(
            """
            // @module: commonjs
            // @filename: a.ts
            export default interface zzz { x: string; }

            // @filename: index.ts
            import zzz from "./a";
            const x: zzz = { x: "" };
            """.trimIndent(),
            directives = "",
        ) should {
            have(none { it.code == 2749 })
        }
    }

    @Test
    fun `JS objlit method this-write mismatch displays the contextual alias name`() {
        diagnose(
            """
            // @module: commonjs
            // @checkJs: true
            // @allowJs: true
            // @filename: func.ts
            interface ComponentOptions {
                watch: Record<string, WatchHandler<any>>;
            }
            type WatchHandler<T> = (val: T) => void;
            declare function extend(options: ComponentOptions): void;
            export var vextend = extend;

            // @filename: app.js
            import {vextend} from './func';
            export var a = vextend({
              watch: {
                data1(val) {
                  this.data2 = 1;
                },
                data2(val) { },
              }
            });
            """.trimIndent(),
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'WatchHandler<any>'." })
        }
    }

    @Test
    fun `path-shaped layout keeps the contextual alias display via the dir-relative alias leg`() {
        diagnose(
            """
            // @module: commonjs
            // @checkJs: true
            // @allowJs: true
            // @filename: /proj/src/func.ts
            interface ComponentOptions {
                watch: Record<string, WatchHandler<any>>;
            }
            type WatchHandler<T> = (val: T) => void;
            declare function extend(options: ComponentOptions): void;
            export var vextend = extend;

            // @filename: /proj/src/app.js
            import {vextend} from './func';
            export var a = vextend({
              watch: {
                data1(val) {
                  this.data2 = 1;
                },
                data2(val) { },
              }
            });
            """.trimIndent(),
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2322 && it.message == "Type 'number' is not assignable to type 'WatchHandler<any>'." })
        }
    }

    @Test
    fun `JSDoc type-tag import excess property on export default fires TS2353`() {
        diagnose(
            """
            // @module: commonjs
            // @allowJs: true
            // @checkJs: true
            // @filename: a.ts
            export interface Foo {
                a: number;
                b: number;
            }

            // @filename: b.js
            /** @type {import("./a").Foo} */
            export default { c: false };
            """.trimIndent(),
            directives = "",
        ) should {
            have(any { it.code == 2353 && it.message.contains("'c' does not exist in type 'Foo'") })
        }
    }

    @Test
    fun `private conflict against an imported base class fires TS2415`() {
        diagnose(
            """
            // @module: commonjs
            // @filename: b.ts
            export class C {
              private p: number = 1;
            }

            // @filename: c.ts
            import { C } from "./b";
            export class D extends C {
              private p: number = 2;
            }
            """.trimIndent(),
            directives = "",
        ) should {
            have(any { it.code == 2415 && it.message == "Class 'D' incorrectly extends base class 'C'." })
        }
    }

    @Test
    fun `negative control - clean override of an imported base stays silent`() {
        diagnose(
            """
            // @module: commonjs
            // @filename: b.ts
            export class C {
              p: number = 1;
            }

            // @filename: c.ts
            import { C } from "./b";
            export class D extends C {
              p: number = 2;
            }
            """.trimIndent(),
            directives = "",
        ) should {
            have(none { it.code == 2415 })
            have(none { it.code == 2416 })
        }
    }
}

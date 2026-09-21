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
 * (CHK.73): an EXTERNAL MODULE symbol has a VALUE TYPE — tsc's
 * `typeof import("...")`, the object `import * as ns` denotes.
 *
 * Before this, `getTypeOfSymbolWorker` had no `SymbolFlags.Module` arm, so every
 * namespace import typed `anyType` and every member read under it answered `any` too.
 * The failure is SILENT in every channel this repo gates on — `any` is legal
 * everywhere, so no diagnostic moves — which is why the whole family survived: the
 * 8-profile grid reads `added=0 removed=0` for a binary that types nothing here.
 *
 * So these pins assert the SHARP signal: a deliberately WRONG assignment that must
 * report, at each position the type flows to. A silence-asserting pin would pass on
 * the un-fixed binary too.
 *
 * ## The ablation
 *
 * Removing the Module arm reddens every positive below; removing the AMBIENT leg in
 * `NameResolver.resolveAlias` reddens only the `declare module` ones; removing the
 * class-CONSTRUCTOR half reddens `a class export contributes its constructor side`
 * (and moves six ACTIVE corpus baselines — the `aliasUsageIn*` family).
 */
class NamespaceImportValueTypeTest {

    private val mod = """
        // @filename: mod.ts
        export const num: number = 1;
        export function fn(a: string): string { return a; }
        export class Cls { m(): number { return 1; } }
        const modulePrivate: number = 7;
    """.trimIndent() + "\n"

    @Test
    fun `a namespace import member read is typed`() {
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            export const bad: string = rel.num;
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message })
        }
    }

    @Test
    fun `a namespace import BINDING itself is typed`() {
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            export const bad: number = rel;
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any { it.code == 2322 })
        }
    }

    @Test
    fun `a namespace import member CALL return is typed`() {
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            export const bad: number = rel.fn("x");
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any { it.code == 2322 && "Type 'string' is not assignable to type 'number'" in it.message })
        }
    }

    @Test
    fun `a class export contributes its constructor side`() {
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            interface Holder { Cls: typeof rel.Cls }
            export const ok: Holder = rel;
            export const bad: number = new rel.Cls().m();
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            // the class member is the CONSTRUCTOR side, so the module object still
            // satisfies a `typeof Cls` holder — an INSTANCE-typed member would not.
            have(none { it.code == 2322 && "Holder" in it.message })
        }
    }

    @Test
    fun `negative control - a correctly typed namespace import member read is silent`() {
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            export const good: number = rel.num;
            export const alsoGood: string = rel.fn("x");
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    // ── the enumeration is the IMPORTER's view, not the target file's `locals` ──

    @Test
    fun `a star re-export barrel contributes its members to the module object`() {
        diagnose(
            mod + """
            // @filename: barrel.ts
            export * from "./mod";
            // @filename: main.ts
            import * as b from "./barrel";
            export const bad: string = b.num;
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message })
        }
    }

    @Test
    fun `a renaming export specifier is keyed by the name the importer sees`() {
        diagnose(
            """
            // @filename: ren.ts
            const inner: number = 1;
            export { inner as outer };
            // @filename: main.ts
            import * as r from "./ren";
            export const bad: string = r.outer;
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message })
        }
    }

    // ── the AMBIENT half: `declare module "spec"`, i.e. how @types publishes ──

    private val amb = """
        // @filename: amb.d.ts
        declare module "ambpkg" {
            export const anum: number;
            export function afn(a: string): string;
        }
    """.trimIndent() + "\n"

    @Test
    fun `a namespace import of an AMBIENT module is typed`() {
        diagnose(
            amb + """
            // @filename: main.ts
            import * as amb from "ambpkg";
            export const bad: string = amb.anum;
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any { it.code == 2322 && "Type 'number' is not assignable to type 'string'" in it.message })
        }
    }

    @Test
    fun `a namespace import of an AMBIENT module types a member call return`() {
        diagnose(
            amb + """
            // @filename: main.ts
            import * as amb from "ambpkg";
            export const bad: number = amb.afn("x");
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any { it.code == 2322 && "Type 'string' is not assignable to type 'number'" in it.message })
        }
    }

    @Test
    fun `negative control - an AMBIENT namespace import used correctly is silent`() {
        diagnose(
            amb + """
            // @filename: main.ts
            import * as amb from "ambpkg";
            export const good: number = amb.anum;
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    // ── the module type may not be answered for a SHADOWED spelling ──

    @Test
    fun `an any-annotated parameter shadowing the alias refuses the module type`() {
        // tsgo answers TS7006 here and nothing else. The conventional ladder resolves a
        // name registered in no walk-scoped table to the FILE-LEVEL declaration, and an
        // `any`-annotated parameter is registered in none — so before the guard this
        // typed the read from the MODULE and reported a TS2322 that does not exist.
        // Before the module arm the same wrong resolution answered `any`, i.e. the right
        // answer by accident, which is why the guard is part of THIS change.
        //
        // The target is deliberately `string` against the module's `number`: written the
        // other way round (`const x: number = rel.num`) the assertion is satisfied by
        // BOTH binaries — the wrongly resolved module answers a type that happens to fit
        // — and the ablation reads 0 RED. Measured: that is exactly what the first
        // version of this pin did.
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            export function shadowed(rel: any) { const x: string = rel.num; return x; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `residue - the same shadowing defect for a file-level const is unchanged`() {
        // The defect is GENERAL and PRE-EXISTING, measured on the parent binary: a
        // file-level `const` shadowed by an `any`-annotated parameter resolves to the
        // file-level one and reports the identical false TS2322, with no module symbol
        // anywhere. This round contains its own arm and deliberately does not widen the
        // syntactic test to every identifier, which would change how every shadowed read
        // in the program resolves.
        diagnose(
            """
            // @filename: main.ts
            export const zloc = { num: 1 };
            export function shadowed(zloc: any) { const x: string = zloc.num; return x; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any { it.code == 2322 })
        }
    }

    // ── CONTAINMENT: a `namespace` symbol is deliberately NOT admitted ──

    @Test
    fun `residue - a namespace value read through an intermediate binding stays any`() {
        // tsgo reports TS2322 here. Admitting every `SymbolFlags.Module` symbol closes
        // it and moves TWENTY active corpus baselines (the internal-module family);
        // this arm is contained to EXTERNAL MODULES and moves none. Pinned so the
        // containment is a recorded decision rather than an accident.
        diagnose(
            """
            // @filename: main.ts
            export namespace N { export const num: number = 1; }
            const nn = N;
            export const stillSilent: string = nn.num;
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }
}

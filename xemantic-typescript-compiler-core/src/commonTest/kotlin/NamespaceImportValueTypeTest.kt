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
    fun `the same shadowing defect for a file-level const is CLOSED too`() {
        // Pinned as a residue when the guard landed and closed the same day by
        // (P18.160), which widened (CHK.42)'s parameter pre-pass to cover an
        // `any`-ANNOTATED parameter — the general form of what the guard handles for a
        // module symbol. `AnyAnnotatedParameterShadowTest` owns that mechanism; this row
        // holds the boundary, since no module symbol appears in it at all.
        diagnose(
            """
            // @filename: main.ts
            export const zloc = { num: 1 };
            export function shadowed(zloc: any) { const x: string = zloc.num; return x; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    @Test
    fun `a catch variable shadowing the alias refuses the module type`() {
        // THE SHAPE THAT KEEPS THE GUARD LOAD-BEARING after (P18.160) widened (CHK.42)'s
        // parameter pre-pass. That pre-pass covers PARAMETERS; `nameBoundByEnclosingScope`
        // covers the wider binder population, and measured on a five-shape matrix the
        // module type otherwise leaks into a `catch` variable and a block-scoped `class`
        // — ours reporting `Type 'zns'` where tsgo reports `unknown` and `typeof zns`.
        // The guard is SUPPRESSION-only, so it also costs one true row there (a
        // block-scoped `function zns`), which is recorded in the residue below.
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            export function f() { try { } catch (rel) { const a: number = rel; } }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2322 && "'rel'" in it.message })
        }
    }

    @Test
    fun `residue - the shadow guard is suppression-only and costs a true row`() {
        // tsgo reports `Type '() => number' is not assignable to type 'number'` for a
        // BLOCK-SCOPED function shadowing the alias; the guard answers `any` there and we
        // are silent. Measured 2026-09-21. Closing it needs the shadowing name to resolve
        // to its own declaration ((INV.0) step 10b's scope-space consult), not a wider
        // suppression.
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            export function f() { { function rel() { return 1; } const a: number = rel; } }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2322 })
        }
    }

    // ── (CHK.73)(A) a member ABSENT from a module object is tsgo's TS2339 ──
    //
    // Every access below sits in a FUNCTION BODY on purpose. A BARE top-level
    // `ns.nope;` has an emitter of its own — [Checker.checkAmbientModuleNamespaceImportMembers],
    // and only for an AMBIENT specifier — so an expression-statement fixture reads as
    // healthy for the ambient half whatever the receiver gate does, and the first
    // version of these pins would have been vacuous for exactly that reason.
    //
    // The messages are tsgo 7.0.2's, measured 2026-09-21, with ONE stated divergence:
    // tsgo names the resolved target by its full path sans extension
    // (`typeof import("/abs/dir/mod")`) where every `typeof import(...)` site in this
    // compiler names it by its BASENAME. On a flat harness the two coincide, which is
    // also why the corpus cannot see the difference — see
    // [Checker.moduleObjectTypeDisplay].

    @Test
    fun `a member absent from a RELATIVE module object is reported`() {
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            export function f() { return rel.nope; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any {
                it.code == 2339 &&
                    it.message == "Property 'nope' does not exist on type 'typeof import(\"mod\")'."
            })
        }
    }

    @Test
    fun `a member absent from an AMBIENT module object is reported`() {
        // BYTE-IDENTICAL to tsgo here: an ambient module is named by its specifier,
        // which carries no path for the basename rule to shorten.
        diagnose(
            amb + """
            // @filename: main.ts
            import * as amb from "ambpkg";
            export function f() { return amb.nope; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any {
                it.code == 2339 &&
                    it.message == "Property 'nope' does not exist on type 'typeof import(\"ambpkg\")'."
            })
        }
    }

    @Test
    fun `a module-PRIVATE name is not on the module object`() {
        // The enumeration is the IMPORTER's view ([Checker.exportedSymbolsThroughStars]),
        // not the target file's `locals` — which is the table the module symbol carries
        // and which (P18.125) measured wrong for an enumeration three ways.
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            export function f() { return rel.modulePrivate; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any { it.code == 2339 && "'modulePrivate'" in it.message })
        }
    }

    @Test
    fun `negative control - a member the module DOES export is silent`() {
        diagnose(
            mod + amb + """
            // @filename: main.ts
            import * as rel from "./mod";
            import * as amb from "ambpkg";
            export function f() { return [rel.num, amb.anum]; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a star re-exporting ambient block is refused`() {
        // (P18.125)'s "a barrel that also declares its own exports": the carrier's RAW
        // `exports` hold `own` ALONE, so trusting them manufactures a false TS2339 on
        // the very `@types` shape this arc exists for. tsgo reports `starred` as present.
        diagnose(
            """
            // @filename: amb.d.ts
            declare module "source" { export const starred: number; }
            declare module "barrelpkg" {
                export * from "source";
                export const own: number;
            }
            // @filename: main.ts
            import * as b from "barrelpkg";
            export function f() { return b.starred; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `residue - a star re-export target that does not resolve is refused too`() {
        // tsgo REPORTS here: an unresolvable `export * from` contributes nothing, so the
        // surface really is `own` alone. The refusal above cannot tell the two apart
        // without a star-following enumeration for an ambient BLOCK, which does not
        // exist — [Checker.collectExportedSymbolsFollowingStars] takes a `SourceFile`.
        // Failing SILENT is the safe direction; measured 2026-09-21.
        diagnose(
            """
            // @filename: amb.d.ts
            declare module "barrelpkg" {
                export * from "nowhere";
                export const own: number;
            }
            // @filename: main.ts
            import * as b from "barrelpkg";
            export function f() { return b.whatever; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a SHORTHAND ambient module is any`() {
        // `declare module "spec";` with NO body makes the module `any` in tsc, so
        // nothing is missing from it — `esModuleInteropTslibHelpers` is the corpus
        // baseline that says so, and it is what the first cut of this arm broke.
        diagnose(
            """
            // @filename: amb.d.ts
            declare module "shorty";
            // @filename: main.ts
            import * as s from "shorty";
            export function f() { return s.whatever; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - an export-equals ambient block is refused`() {
        // `export = X` re-points the surface at a VALUE whose members live in a TYPE and
        // not in a symbol table. `aliasOnMergedModuleInterface` is the corpus baseline:
        // tsgo answers TS2708 at the RECEIVER there and no TS2339 at all.
        diagnose(
            """
            // @filename: amb.d.ts
            declare module "eqpkg" {
                namespace B { export interface A { } }
                interface B { bar: string; }
                export = B;
            }
            // @filename: main.ts
            import * as e from "eqpkg";
            export function f() { return e.bar; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a name a MODULE AUGMENTATION adds is refused`() {
        // A `declare module "./x"` block contributes names the target file does not
        // declare at all, so absence from its export table is not evidence. tsgo is
        // silent here. [Checker.augmentationDeclaredExportNames] answers it — its own
        // contract is that a name it adds can only SUPPRESS, which is the direction this
        // consumer needs. `exportAsNamespace_augment` is the corpus baseline, but that
        // fixture is ALSO a UMD global and so is closed by the sibling refusal; this
        // shape is not, which is what makes the two separable.
        diagnose(
            """
            // @filename: augt.d.ts
            export const declared: number;
            // @filename: main.ts
            import * as a from "./augt";
            declare module "./augt" { export const added: number; }
            export function f() { return a.added; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a UMD-global module can be extended by declare global`() {
        // `export as namespace u` makes the module's surface extendable through
        // `declare global { namespace u { … } }`, a channel no walk over the FILE's own
        // statements can see ([Checker.umdGlobalFiles]). tsgo is silent for `u2.uy`;
        // `exportAsNamespace_augment` is the corpus baseline.
        diagnose(
            """
            // @filename: umdm.d.ts
            export as namespace umdm;
            export const ux: number;
            // @filename: main.ts
            import * as u2 from "./umdm";
            declare global { namespace umdm { export const uy: number; } }
            export function f() { return u2.uy; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    @Test
    fun `negative control - a LOCAL re-export clause names a member of the ambient surface`() {
        // `export { Internal as Public }` with NO module specifier is the `@types/node`
        // idiom, and the binder's `exports` for the block do not hold the EXPORTED
        // spelling — [NameResolver.ambientModuleSurfaceMember] is what walks it, and it
        // is the same leg the TS2305 walker already takes. tsgo is silent here.
        diagnose(
            """
            // @filename: repkg.d.ts
            declare module "repkg" {
                class Internal { zz: number; }
                export { Internal as Public };
            }
            // @filename: main.ts
            import * as rp from "repkg";
            export function f() { return rp.Public; }
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(none { it.code == 2339 })
        }
    }

    // ── (CHK.73)(B) the DISPLAY of a module object ──

    @Test
    fun `a RELATIVE module object renders as typeof import`() {
        // Before this it rendered `Type 'rel'` — the IMPORT ALIAS's local name, because
        // [Checker.createModuleSymbol] is called with it and `typeToString`'s last
        // `Type.Object` clause read `sym.name`.
        diagnose(
            mod + """
            // @filename: main.ts
            import * as rel from "./mod";
            export const bad: number = rel;
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'typeof import(\"mod\")' is not assignable to type 'number'."
            })
        }
    }

    @Test
    fun `an AMBIENT module object renders as typeof import`() {
        // Before this it rendered `Type 'ambpkg'` — the SPECIFIER, which is the ambient
        // carrier's symbol name. BYTE-IDENTICAL to tsgo.
        diagnose(
            amb + """
            // @filename: main.ts
            import * as amb from "ambpkg";
            export const bad: number = amb;
            """.trimIndent(),
            "// @module: commonjs",
        ) should {
            have(any {
                it.code == 2322 &&
                    it.message == "Type 'typeof import(\"ambpkg\")' is not assignable to type 'number'."
            })
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

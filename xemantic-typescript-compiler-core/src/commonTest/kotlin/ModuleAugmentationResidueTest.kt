/*
 * Copyright 2025-2026 Kazimierz Pogoda / Xemantic
 *
 * Licensed under the GNU Affero General Public License, Version 3 (AGPL-3.0-only)
 * WITH LicenseRef-xtsc-output-exception, see LICENSE.md.
 *
 * SPDX-License-Identifier: AGPL-3.0-only WITH LicenseRef-xtsc-output-exception
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.82) Three of the four augmentation residues (CHK.78) measured and left,
 * each reproduced on a scratch project against tsgo 7.0.2 and re-measured after
 * the fix (2026-09-04):
 *
 *  (1) A name declared by the AUGMENTATION BLOCK ITSELF that the target does not
 *      export typed `any`. tsc checks an augmentation's body in the AUGMENTED
 *      module's context, where the block's own declarations are in scope; here
 *      [Checker.augmentationContextSymbol] answered only for a name the target
 *      EXPORTS and everything else fell through to the per-file consult, which
 *      cannot see the block's `exports` table. The narrowing — ask the block only
 *      for a name the target does NOT export — is what keeps (CHK.76)'s measured
 *      +43 rows away: a block's PARTIAL re-declaration is by definition of a name
 *      the target exports, so it can never win.
 *
 *  (2) A name the augmentation DECLARES is not in the module's exports, so
 *      `import { Brand } from "./types.js"` was a false TS2305 — while the TYPE
 *      resolved correctly, which is what makes this purely an absence-check
 *      defect. [Checker.getModuleExportsFollowingStars] is AST-derived from the
 *      TARGET file alone; [Checker.augmentationDeclaredExportNames] adds the
 *      blocks' contributions, filtered by the SAME predicate the merge uses.
 *
 *  (3) A NON-RELATIVE PACKAGE augmentation (`declare module "some-pkg"` with the
 *      package installed) was a false **TS2664** plus a false TS2339 on the
 *      package's OWN member. One cause for both: no resolver leg could name a
 *      bare specifier's target — that is a `package.json` `types`/`main` entry,
 *      not a string transformation of the specifier — so `targetFile` was null
 *      and [Checker.collectModuleAugmentations] took the fileless-ambient branch,
 *      publishing the block's PARTIAL interface into `globals` as a stub (the
 *      round-510 mechanism) while never merging into the real target's locals.
 *      [Checker.augmentationTargetFile] is the one home for the ladder.
 *
 * Residue (4) — tsgo and pristine tsc 6.0.3 render a module-declared ENUM as
 * `import("<path>").E` where we render `E` — is DELIBERATELY not fixed here and
 * is not an augmentation residue at all: it reproduces with no `declare module`
 * anywhere in the program, so it is a whole-program display question. See the
 * session note.
 *
 * Every consumer pin reads the resolved type OUT OF A MESSAGE — a type that
 * degraded to `any` is silent everywhere.
 */
class ModuleAugmentationResidueTest {

    private fun messages(diagnostics: List<Diagnostic>, code: Int): List<String> =
        diagnostics.filter { it.code == code }.map { it.message }

    /**
     * (CHK.78)'s harness: the project path is the only one that reproduces a
     * BARE-specifier shape, because [CompilerOptions.moduleResolutions] — the
     * crawl's own `(importer, specifier)` answers ((CHK.30)) — is empty under
     * [diagnose] BY CONSTRUCTION, so a `diagnose()` pin for residue (3) would be
     * green against both binaries.
     */
    private fun projectDiagnostics(
        vararg files: Pair<String, String>,
        moduleResolutions: Map<String, Map<String, String>> = emptyMap(),
    ): List<Diagnostic> {
        val options = CompilerOptions()
        val binder = Binder(options)
        val results = files.map { (name, text) -> binder.bind(Parser(text.trimIndent(), name).parse()) }
        return Checker(
            options, results,
            isMultiFileSource = true,
            moduleResolutions = moduleResolutions,
        ).getDiagnostics()
    }

    // --- (1) a name the BLOCK declares and the target does not export -----------------

    private val target = "types.ts" to """
        export interface SourceFile { kind: number; }
        export interface Exported { e: number; }
    """

    private val blockDeclarations = "aug.ts" to """
        import "./types";
        declare module "./types" {
          interface ZzzLocal { q: number; }
          type ZzzAl = { z: number; };
          interface SourceFile {
            p1: ZzzLocal;
            p2: ZzzAl;
            p3: Exported;
            p4: number;
          }
        }
        export const marker = 1;
    """

    @Test
    fun `an augmented member typed by an INTERFACE the block itself declares resolves to it`() {
        val d = projectDiagnostics(
            target, blockDeclarations,
            "use.ts" to """
                import { SourceFile } from "./types";
                declare const sf: SourceFile;
                const probe: string = sf.p1;
            """,
        )
        assert(messages(d, 2322) == listOf("Type 'ZzzLocal' is not assignable to type 'string'."))
    }

    @Test
    fun `an augmented member typed by a TYPE ALIAS the block itself declares resolves to it`() {
        val d = projectDiagnostics(
            target, blockDeclarations,
            "use.ts" to """
                import { SourceFile } from "./types";
                declare const sf: SourceFile;
                const probe: string = sf.p2;
            """,
        )
        assert(messages(d, 2322) == listOf("Type 'ZzzAl' is not assignable to type 'string'."))
    }

    /**
     * The B86.4 namespace-qualification ascent walks `Symbol.parent` while the
     * parent is a module symbol — and a STRING-named module's `Symbol.name` is the
     * SPECIFIER, so the alias above rendered `'./types.ZzzAl'`, a spelling tsc
     * never produces. The assertion above is the value pin; this one names the
     * mistake, because a specifier-qualified name reads as plausible.
     */
    @Test
    fun `a block-declared alias is not qualified by the block's specifier`() {
        val d = projectDiagnostics(
            target, blockDeclarations,
            "use.ts" to """
                import { SourceFile } from "./types";
                declare const sf: SourceFile;
                const probe: string = sf.p2;
            """,
        )
        assert(messages(d, 2322).none { "./types." in it })
    }

    /**
     * NEGATIVE CONTROL for the narrowing, and the pin that (CHK.76)'s +43 rows are
     * kept away: a name the target DOES export must keep resolving to the TARGET's
     * declaration, never to the block's PARTIAL `interface SourceFile` — which
     * declares no `kind` at all, so a wrong answer here is a TS2339 rather than a
     * wrong type.
     */
    @Test
    fun `a name the target exports still resolves to the target - not to the block's partial interface`() {
        val d = projectDiagnostics(
            target, blockDeclarations,
            "use.ts" to """
                import { SourceFile } from "./types";
                declare const sf: SourceFile;
                const probe: string = sf.kind;
                const probe2: string = sf.p3;
            """,
        )
        assert(messages(d, 2339).isEmpty())
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'string'.",
            "Type 'Exported' is not assignable to type 'string'.",
        ))
    }

    // --- (2) an augmentation's own declarations are exports of the module -------------

    private val exportingAugmentation = "aug.ts" to """
        import "./types";
        declare module "./types" {
          export interface Brand { b: string; }
          interface Implicit { i: string; }
        }
        export const marker = 1;
    """

    @Test
    fun `importing a name the augmentation declares does not report TS2305`() {
        val d = projectDiagnostics(
            target, exportingAugmentation,
            "use.ts" to """
                import { Brand, Implicit } from "./types";
                declare const b: Brand;
                declare const i: Implicit;
                const p1: string = b;
                const p2: string = i;
            """,
        )
        assert(messages(d, 2305).isEmpty())
        assert(messages(d, 2322) == listOf(
            "Type 'Brand' is not assignable to type 'string'.",
            "Type 'Implicit' is not assignable to type 'string'.",
        ))
    }

    /** The re-export branch of the same walker asks the same question. */
    @Test
    fun `re-exporting a name the augmentation declares does not report TS2305`() {
        val d = projectDiagnostics(
            target, exportingAugmentation,
            "use.ts" to """
                export { Brand } from "./types";
            """,
        )
        assert(messages(d, 2305).isEmpty())
    }

    /** NEGATIVE CONTROL: a name NO augmentation declares still reports. */
    @Test
    fun `importing a name no augmentation declares still reports TS2305`() {
        val d = projectDiagnostics(
            target, exportingAugmentation,
            "use.ts" to """
                import { Absent } from "./types";
                declare const a: Absent;
            """,
        )
        assert(messages(d, 2305) == listOf("Module '\"./types\"' has no exported member 'Absent'."))
    }

    /**
     * NEGATIVE CONTROL for the shared predicate: a block carrying an export
     * STATEMENT is a strict "module augmentation", where only a declaration with
     * an explicit `export` modifier merges — measured against tsgo 7.0.2, which
     * reports the missing one (as TS2724, since a near-miss exists). Without the
     * filter the suppression would swallow that row.
     */
    @Test
    fun `a block with module syntax exports only its explicitly exported declarations`() {
        val d = projectDiagnostics(
            target,
            "aug.ts" to """
                import "./types";
                declare module "./types" {
                  interface NotExported { q: number; }
                  export interface Exported2 { r: number; }
                  export {};
                }
                export const marker = 1;
            """,
            "use.ts" to """
                import { NotExported, Exported2 } from "./types";
                declare const b: Exported2;
                const p: string = b;
            """,
        )
        assert(messages(d, 2305) + messages(d, 2724) == listOf(
            "'\"./types\"' has no exported member named 'NotExported'. Did you mean 'Exported'?",
        ))
        assert(messages(d, 2322) == listOf("Type 'Exported2' is not assignable to type 'string'."))
    }

    // --- (3) a NON-RELATIVE package augmentation --------------------------------------

    private val pkg = "/proj/node_modules/some-pkg/index.d.ts" to """
        export interface Widget { base: number; }
        export declare function make(): Widget;
    """

    private val pkgAugmentation = "/proj/src/aug.ts" to """
        declare module "some-pkg" {
          interface Widget { extra: string; }
        }
        export const marker = 1;
    """

    private val pkgConsumer = "/proj/src/use.ts" to """
        import { make } from "some-pkg";
        const w = make();
        const p1: string = w.base;
        const p2: number = w.extra;
    """

    /** The crawl resolves `"some-pkg"` for the file that IMPORTS it. */
    private val pkgResolutions = mapOf(
        "/proj/src/use.ts" to mapOf("some-pkg" to "/proj/node_modules/some-pkg/index.d.ts"),
    )

    @Test
    fun `augmenting an installed package does not report TS2664`() {
        val d = projectDiagnostics(
            pkg, pkgAugmentation, pkgConsumer,
            moduleResolutions = pkgResolutions,
        )
        assert(messages(d, 2664).isEmpty())
    }

    /**
     * The other half of the same cause: with no target file the block's PARTIAL
     * `interface Widget` was published into `globals` as a fileless-ambient stub,
     * so the PACKAGE's own `base` read TS2339 — and the augmented `extra` still
     * typed, which is why a probe on the augmented member alone cannot see it.
     */
    @Test
    fun `augmenting an installed package keeps the package's own members and adds its own`() {
        val d = projectDiagnostics(
            pkg, pkgAugmentation, pkgConsumer,
            moduleResolutions = pkgResolutions,
        )
        assert(messages(d, 2339).isEmpty())
        assert(messages(d, 2322) == listOf(
            "Type 'number' is not assignable to type 'string'.",
            "Type 'string' is not assignable to type 'number'.",
        ))
    }

    /**
     * NEGATIVE CONTROL: a bare specifier NOTHING in the program resolves is still
     * TS2664. Without it the fix reads as "we stopped reporting TS2664", which is
     * a different and much worse change.
     */
    @Test
    fun `augmenting a package no file resolves still reports TS2664`() {
        val d = projectDiagnostics(
            pkg,
            "/proj/src/aug.ts" to """
                declare module "no-such-pkg" {
                  interface Widget { extra: string; }
                }
                export const marker = 1;
            """,
            moduleResolutions = pkgResolutions,
        )
        assert(messages(d, 2664) == listOf(
            "Invalid module name in augmentation, module 'no-such-pkg' cannot be found.",
        ))
    }

    /**
     * The last leg's guard: node resolves a bare specifier from the IMPORTER's
     * directory upward, so two files may legitimately name two packages. The
     * augmenting file has no entry of its own here, and the two that do DISAGREE
     * — the ladder must then answer null and leave the resolution it had, because
     * a wrong target merges an augmentation into another package's module and
     * (CFG.1) says nothing in this repo would print that.
     */
    @Test
    fun `a bare specifier two files resolve differently is refused`() {
        val d = projectDiagnostics(
            pkg,
            "/proj/other/node_modules/some-pkg/index.d.ts" to """
                export interface Widget { other: number; }
            """,
            pkgAugmentation,
            pkgConsumer,
            moduleResolutions = pkgResolutions + mapOf(
                "/proj/other/x.ts" to mapOf("some-pkg" to "/proj/other/node_modules/some-pkg/index.d.ts"),
            ),
        )
        assert(messages(d, 2664) == listOf(
            "Invalid module name in augmentation, module 'some-pkg' cannot be found.",
        ))
    }
}

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
 * (CHK.78) The three augmentation divergences measured on (CHK.77)'s negative
 * control `r1n` — a `types.ts` beside `declare module "./types.js" { interface
 * SourceFile { … } }` — each reproduced on a scratch project against tsgo 7.0.2
 * (2026-09-04):
 *
 *  (a) a FALSE **TS2882** on the side-effect `import "./types.js"`. Measured, the
 *      augmentation is not the cause and nothing about the shape is: the emitter
 *      asked [Checker.resolveModuleSpecifier], which matches the specifier
 *      against `fileResults` KEYS and is not directory-aware, so on a REAL
 *      project — whose keys are ABSOLUTE paths — **every** relative side-effect
 *      import read TS2882, extensionless and ESM-`.js` alike. The corpus cannot
 *      see it (its file names are flat, so `./types` matches `types.ts`
 *      directly) and tsc's own 78 sources carry no relative side-effect import,
 *      so neither the ~13k baselines nor the 8-profile grid ever moved.
 *
 *  (b) a bare `node: Node` written INSIDE the augmentation block typed `any`
 *      where tsgo types it by the AUGMENTED file's own `Node`. The axis is
 *      LIB COLLISION, not the annotation: INV.3(c)(iv)'s augmentation-visibility
 *      leg sat BELOW [Checker.lookupPerFileForNode]'s `!moduleOnly` fast path,
 *      and a name a lib also declares is not module-only — so the leg was
 *      unreachable for exactly the names tsc's own `types.ts` exports (`Node`,
 *      `Symbol`, `Text`, `Event`). With `lib: es2020` the same fixture resolves,
 *      which is what isolates it. Hoisting the leg also fixed a PRECEDENCE
 *      divergence found beside it: the augmented module's export must beat the
 *      augmenting file's OWN import of the same name (tsgo measured; the block
 *      is the inner scope, the augmenting file the enclosing one).
 *
 *  (c) the lens's `typeReferenceSymbol` asked the WALK-scoped chain FIRST, which
 *      in-walk answers the augmentation block's own PARTIAL interface rather
 *      than the merged one — a LENS-ONLY divergence, recorded as such by
 *      `NamespaceResolutionResidueTest`'s own negative control (its heritage
 *      instrument never had it, because `heritageBaseSymbol` asks no
 *      walk-scoped chain).
 *
 * Every consumer pin reads the resolved type OUT OF A MESSAGE — resolving to
 * `any` is silent — and the lens pins read declaration IDENTITY.
 */
class ModuleAugmentationResolutionTest {

    private fun messages(diagnostics: List<Diagnostic>, code: Int): List<String> =
        diagnostics.filter { it.code == code }.map { it.message }

    // --- (a) the false TS2882 on a relative side-effect import -------------------------

    /**
     * The defect lives on the PROJECT path and only there, so the pin does too:
     * [diagnose]'s harness materialises no directory and keys `fileResults` by
     * FLAT names, where the old resolver already answered — a `diagnose()` pin
     * would be green against both binaries. The project crawl's own
     * `(importer, specifier)` answers are what [CompilerOptions.moduleResolutions]
     * carries ((CHK.30)), so handing the checker that map beside absolute-path
     * files reproduces the shape exactly.
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

    private val absTypes = "/proj/src/types.ts" to """
        export interface SourceFile { name: string; }
    """

    @Test
    fun `a relative side-effect import the crawl resolved does not report TS2882`() {
        val d = projectDiagnostics(
            absTypes,
            "/proj/src/aug.ts" to """
                import "./types.js";
                export const marker = 1;
            """,
            moduleResolutions = mapOf(
                "/proj/src/aug.ts" to mapOf("./types.js" to "/proj/src/types.ts"),
            ),
        )
        assert(messages(d, 2882).isEmpty())
    }

    @Test
    fun `an extensionless relative side-effect import the crawl resolved does not report TS2882`() {
        val d = projectDiagnostics(
            absTypes,
            "/proj/src/aug.ts" to """
                import "./types";
                export const marker = 1;
            """,
            moduleResolutions = mapOf(
                "/proj/src/aug.ts" to mapOf("./types" to "/proj/src/types.ts"),
            ),
        )
        assert(messages(d, 2882).isEmpty())
    }

    @Test
    fun `a relative side-effect import beside its own augmentation does not report TS2882`() {
        val d = projectDiagnostics(
            absTypes,
            "/proj/src/aug.ts" to """
                import "./types.js";
                declare module "./types.js" {
                    interface SourceFile { extra: number; }
                }
            """,
            moduleResolutions = mapOf(
                "/proj/src/aug.ts" to mapOf("./types.js" to "/proj/src/types.ts"),
            ),
        )
        assert(messages(d, 2882).isEmpty())
    }

    @Test
    fun `negative control - a relative side-effect import the crawl did NOT resolve still reports TS2882`() {
        val d = projectDiagnostics(
            absTypes,
            "/proj/src/aug.ts" to """
                import "./nosuch.js";
                import "./alsomissing";
                export const marker = 1;
            """,
            moduleResolutions = mapOf(
                "/proj/src/aug.ts" to mapOf("./types.js" to "/proj/src/types.ts"),
            ),
        )
        assert(messages(d, 2882) == listOf(
            "Cannot find module or type declarations for side-effect import of './nosuch.js'.",
            "Cannot find module or type declarations for side-effect import of './alsomissing'.",
        ))
    }

    @Test
    fun `negative control - the BARE side-effect import arm is untouched by the crawl leg`() {
        // A non-relative specifier takes the other branch entirely — the crawl map
        // names it and it must STILL report, because that arm asks
        // `ambientModuleNames`/`dtsFileBaseNames`/`hasNodeModulesPackage`, none of
        // which the (CHK.78)(a) leg is wired into.
        val d = projectDiagnostics(
            absTypes,
            "/proj/src/aug.ts" to """
                import "no-such-pkg";
                export const marker = 1;
            """,
            moduleResolutions = mapOf(
                "/proj/src/aug.ts" to mapOf("no-such-pkg" to "/proj/src/types.ts"),
            ),
        )
        assert(messages(d, 2882) == listOf(
            "Cannot find module or type declarations for side-effect import of 'no-such-pkg'.",
        ))
    }

    // --- (b) a LIB-COLLIDING name inside the augmentation block ------------------------

    /**
     * `Node` is a DOM global, so `@useRealLibs` is what makes the name SHARED and
     * the fixture non-vacuous — with `lib: es2020` (the second pin below) the same
     * source resolves on the parent binary too. Both are needed: the pair is what
     * says the axis is the collision and not the annotation.
     */
    @Test
    fun `a LIB-colliding name inside an augmentation block resolves in the augmented module`() {
        val d = diagnose(
            """
            // @Filename: types.ts
            export interface SourceFile { name: string; }
            export interface Node { zzzOwnMember: string; }

            // @Filename: aug.ts
            import "./types";
            declare module "./types" {
                interface SourceFile { pNode: Node; }
            }

            // @Filename: use.ts
            import { SourceFile } from "./types";
            declare const s: SourceFile;
            export const q1: boolean = s.pNode.zzzOwnMember;
            """,
            directives = "// @strict: true\n// @useRealLibs: true",
        )
        assert(messages(d, 2322) == listOf("Type 'string' is not assignable to type 'boolean'."))
    }

    @Test
    fun `control - the same shape with no LIB collision resolved before and after`() {
        val d = diagnose(
            """
            // @Filename: types.ts
            export interface SourceFile { name: string; }
            export interface ZzzNode { zzzOwnMember: string; }

            // @Filename: aug.ts
            import "./types";
            declare module "./types" {
                interface SourceFile { pNode: ZzzNode; }
            }

            // @Filename: use.ts
            import { SourceFile } from "./types";
            declare const s: SourceFile;
            export const q1: boolean = s.pNode.zzzOwnMember;
            """,
            directives = "// @strict: true\n// @useRealLibs: true",
        )
        assert(messages(d, 2322) == listOf("Type 'string' is not assignable to type 'boolean'."))
    }

    @Test
    fun `the augmented module's export beats the augmenting file's own import of that name`() {
        // tsgo 7.0.2 measured: inside the block `Zzz` is `./types`'s, so
        // `.fromTarget` reads `string` and `.fromOther` is a TS2339. The block is
        // the INNER scope; the augmenting file's own import is the enclosing one.
        val d = diagnose(
            """
            // @Filename: types.ts
            export interface SourceFile { name: string; }
            export interface Zzz { fromTarget: string; }

            // @Filename: other.ts
            export interface Zzz { fromOther: number; }

            // @Filename: aug.ts
            import "./types";
            import { Zzz } from "./other";
            declare module "./types" {
                interface SourceFile { pZ: Zzz; }
            }
            export type Keep = Zzz;

            // @Filename: use.ts
            import { SourceFile } from "./types";
            declare const s: SourceFile;
            export const q1: boolean = s.pZ.fromTarget;
            export const q2: boolean = s.pZ.fromOther;
            """
        )
        assert(messages(d, 2322) == listOf("Type 'string' is not assignable to type 'boolean'."))
        assert(messages(d, 2339) == listOf("Property 'fromOther' does not exist on type 'Zzz'."))
    }

    @Test
    fun `negative control - a name the augmented module does not export stays unresolved`() {
        val d = diagnose(
            """
            // @Filename: types.ts
            export interface SourceFile { name: string; }
            interface ZzzNotExported { hidden: boolean; }
            export interface Wrap { w: ZzzNotExported; }

            // @Filename: aug.ts
            import "./types";
            declare module "./types" {
                interface SourceFile { pMissing: ZzzNoSuchName; pHidden: ZzzNotExported; }
            }
            """
        )
        assert(messages(d, 2304) == listOf(
            "Cannot find name 'ZzzNoSuchName'.",
            "Cannot find name 'ZzzNotExported'.",
        ))
    }

    @Test
    fun `negative control - a name OUTSIDE any augmentation block keeps the lib meaning`() {
        // The hoisted leg is reached only from inside a `declare module` block —
        // the same `Node` written at file level in the augmenting file must still
        // be the DOM one, whose `zzzOwnMember` does not exist.
        val d = diagnose(
            """
            // @Filename: types.ts
            export interface SourceFile { name: string; }
            export interface Node { zzzOwnMember: string; }

            // @Filename: aug.ts
            import "./types";
            declare const outside: Node;
            export const q1: boolean = outside.zzzOwnMember;
            declare module "./types" {
                interface SourceFile { pNode: Node; }
            }
            """,
            directives = "// @strict: true\n// @useRealLibs: true",
        )
        assert(messages(d, 2339) == listOf(
            "Property 'zzzOwnMember' does not exist on type 'Node'.",
        ))
    }

    // --- (c) the lens's `typeReferenceSymbol` inside an augmentation block --------------

    /** Records what the lens answers for every variable annotation, keyed by `pos`. */
    private class Recorder : CheckedNodeSink {
        val typeReferenceDecl = HashMap<Int, Node?>()

        override fun expression(node: Expression, lens: CheckedLens) {}

        override fun declaration(node: Node, lens: CheckedLens) {
            if (node !is VariableDeclaration) return
            val type = node.type
            if (type is TypeReference) {
                typeReferenceDecl[node.pos] = lens.typeReferenceSymbol(type)?.declarations?.firstOrNull()
            }
        }
    }

    private fun runLens(vararg files: Pair<String, String>): Pair<Recorder, List<SourceFile>> {
        val options = CompilerOptions()
        val binder = Binder(options)
        val sourceFiles = files.map { (name, text) -> Parser(text.trimIndent(), name).parse() }
        val results = sourceFiles.map { binder.bind(it) }
        val recorder = Recorder()
        Checker(options, results, isMultiFileSource = true, checkedSink = recorder)
        return recorder to sourceFiles
    }

    private fun declarationNamed(file: SourceFile, name: String): Node {
        var found: Node? = null
        fun walk(node: Node) {
            when (node) {
                is InterfaceDeclaration -> if (node.name.text == name) found = node
                else -> {}
            }
            forEachChild(node) { walk(it) }
        }
        walk(file)
        return found!!
    }

    private fun allDeclarationsNamed(file: SourceFile, name: String): List<Node> {
        val out = mutableListOf<Node>()
        fun walk(node: Node) {
            if (node is InterfaceDeclaration && node.name.text == name) out.add(node)
            forEachChild(node) { walk(it) }
        }
        walk(file)
        return out
    }

    private fun posOf(file: SourceFile, needle: String): Int {
        val at = file.text.indexOf(needle)
        assert(at >= 0)
        return at
    }

    @Test
    fun `the lens names a bare type reference inside an augmentation block by the augmented module's declaration`() {
        val (recorder, files) = runLens(
            "/proj/types.ts" to """
                export interface SourceFile { name: string; }
            """,
            "/proj/aug.ts" to """
                import "./types";
                declare module "./types" {
                    interface SourceFile { extra: number; }
                    const inBlock: SourceFile;
                }
            """,
        )
        val target = declarationNamed(files[0], "SourceFile")
        val partial = declarationNamed(files[1], "SourceFile")
        val resolved = recorder.typeReferenceDecl[posOf(files[1], "inBlock: SourceFile")]
        val isTarget = resolved === target
        val isPartial = resolved === partial
        assert(isTarget)
        assert(!isPartial)
    }

    @Test
    fun `negative control - the lens keeps the block's own sibling when the augmented module does not export it`() {
        // `ZzzLocal` is declared by the BLOCK and by nothing in `./types`, so the
        // (CHK.78)(c) consult answers null and the walk-scoped chain still decides.
        val (recorder, files) = runLens(
            "/proj/types.ts" to """
                export interface SourceFile { name: string; }
            """,
            "/proj/aug.ts" to """
                import "./types";
                declare module "./types" {
                    interface ZzzLocal { lk: string; }
                    const inBlock: ZzzLocal;
                }
            """,
        )
        val local = declarationNamed(files[1], "ZzzLocal")
        val resolved = recorder.typeReferenceDecl[posOf(files[1], "inBlock: ZzzLocal")]
        val isLocal = resolved === local
        assert(isLocal)
    }

    @Test
    fun `negative control - the lens keeps its answer for an annotation OUTSIDE the block`() {
        val (recorder, files) = runLens(
            "/proj/types.ts" to """
                export interface SourceFile { name: string; }
            """,
            "/proj/aug.ts" to """
                import { SourceFile } from "./types";
                declare const outside: SourceFile;
                declare module "./types" {
                    interface SourceFile { extra: number; }
                }
                export type Keep = SourceFile;
            """,
        )
        val target = declarationNamed(files[0], "SourceFile")
        val resolved = recorder.typeReferenceDecl[posOf(files[1], "outside: SourceFile")]
        val isTarget = resolved === target
        assert(isTarget)
        assert(allDeclarationsNamed(files[1], "SourceFile").size == 1)
    }
}

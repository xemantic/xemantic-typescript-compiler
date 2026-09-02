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
 * (CHK.77) The four namespace-resolution residues (EXT.14) measured after (CHK.76),
 * each reproduced on a scratch project against tsgo 7.0.2 (2026-09-02):
 *
 *  1. a `declare module "m"` BODY — (CHK.76)'s consult skipped every string-named
 *     block (an augmentation's partial interface is a separate symbol here,
 *     INV.3(c)(iv)); now a GENUINE ambient module — one whose specifier resolves
 *     to no program file — is consulted through its merged `globals` carrier, and a
 *     file-backed augmentation block stays skipped (the negative controls);
 *  2. a QUALIFIED heritage base whose head is a TYPE-ONLY namespace (`extends
 *     JsTyping.TypingResolutionHost`) — the head is asked for the qualified-left
 *     meaning, and a member of a namespace NESTED in an ambient one (or declared in
 *     a `.d.ts`) is implicitly exported, as tsc's inherited `NodeFlags.Ambient`
 *     makes it (`extends ts.server.A` was skipped while `a: ts.server.A` resolved);
 *  3. the lens's `typeReferenceSymbol` answers a QUALIFIED name through the
 *     checker's qualified-name resolver;
 *  4. CROSS-FILE namespace merging — `declare namespace ts { interface A }` in one
 *     file and `interface B extends A { x: A }` in another file's `ts` block: the
 *     consult read the per-file symbol where the merged instance is `globals["ts"]`
 *     (`init:mergeFileLocalsIntoGlobals` adopts the first file's symbol and folds
 *     every later file's declarations and exports into it, recursively).
 *
 * Every consumer-path pin reads the resolved type OUT OF A MESSAGE (resolving to
 * `any` is silent); the lens pins read declaration IDENTITY. Before the fix, on
 * the compiler-profile-shaped fixtures: residue 2 read `Property 'zzHost' does not
 * exist on type 'InstallTypingHost'` (a false TS2339 where tsgo reads `number`),
 * residue 4 read three false TS2339 rows and four silent `any` annotations.
 */
class NamespaceResolutionResidueTest {

    private fun messages(diagnostics: List<Diagnostic>, code: Int): List<String> =
        diagnostics.filter { it.code == code }.map { it.message }

    // --- the lens instrument ----------------------------------------------------------

    /**
     * Records, from inside the walk, what the lens answers for every heritage base
     * (keyed `<name>:extends` / `<name>:implements`, first type of the clause) and
     * for every variable annotation (keyed by the declaration's `pos`).
     */
    private class Recorder : CheckedNodeSink {
        val heritageBaseDecl = HashMap<String, Node?>()
        val typeReferenceDecl = HashMap<Int, Node?>()
        val annotationTypeText = HashMap<Int, String>()

        override fun expression(node: Expression, lens: CheckedLens) {}

        private fun recordHeritage(name: String, clauses: List<HeritageClause>?, lens: CheckedLens) {
            for (clause in clauses ?: return) {
                val base = clause.types.firstOrNull()?.expression ?: continue
                val kind = if (clause.token == SyntaxKind.ImplementsKeyword) "implements" else "extends"
                heritageBaseDecl["$name:$kind"] = lens.heritageBaseSymbol(base)?.declarations?.firstOrNull()
            }
        }

        override fun declaration(node: Node, lens: CheckedLens) {
            when (node) {
                is InterfaceDeclaration -> recordHeritage(node.name.text, node.heritageClauses, lens)
                is ClassDeclaration -> node.name?.text?.let { recordHeritage(it, node.heritageClauses, lens) }
                is VariableDeclaration -> {
                    val type = node.type ?: return
                    if (type is TypeReference) {
                        typeReferenceDecl[node.pos] = lens.typeReferenceSymbol(type)?.declarations?.firstOrNull()
                    }
                    annotationTypeText[node.pos] = lens.render(lens.typeOfTypeNode(type))
                }
                else -> {}
            }
        }
    }

    /** One shared [Binder] for every file, as the production multi-file path binds. */
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
                is ClassDeclaration -> if (node.name?.text == name) found = node
                is TypeAliasDeclaration -> if (node.name.text == name) found = node
                else -> {}
            }
            forEachChild(node) { walk(it) }
        }
        walk(file)
        return found!!
    }

    private fun posOf(file: SourceFile, needle: String): Int {
        val at = file.text.indexOf(needle)
        assert(at >= 0)
        return at
    }

    // --- (1) a genuine ambient module body --------------------------------------------

    private val widgetsDts = """
        declare module "widgets" {
            interface Base { zzBase: number; }
            class Widget implements Base { zzBase: number; zzW: string; }
            class Fancy extends Widget { zzF: boolean; }
            const one: Widget;
            const list: Widget[];
        }
    """

    @Test
    fun `the lens answers a bare heritage base inside a genuine ambient module body`() {
        val (recorder, files) = runLens("/proj/widgets.d.ts" to widgetsDts)
        val widget = declarationNamed(files[0], "Widget")
        val base = declarationNamed(files[0], "Base")
        val fancyExtendsWidget = recorder.heritageBaseDecl["Fancy:extends"] === widget
        val widgetImplementsBase = recorder.heritageBaseDecl["Widget:implements"] === base
        assert(fancyExtendsWidget)
        assert(widgetImplementsBase)
    }

    @Test
    fun `the lens names a bare type reference inside a genuine ambient module body`() {
        val (recorder, files) = runLens("/proj/widgets.d.ts" to widgetsDts)
        val widget = declarationNamed(files[0], "Widget")
        val onePos = posOf(files[0], "one: Widget")
        val listPos = posOf(files[0], "list: Widget[]")
        val oneIsWidget = recorder.typeReferenceDecl[onePos] === widget
        assert(oneIsWidget)
        assert(recorder.annotationTypeText[onePos] == "Widget")
        assert(recorder.annotationTypeText[listPos] == "Widget[]")
    }

    @Test
    fun `control - a consumer of a genuine ambient module reads its members by the lazy path as before`() {
        val d = diagnose(
            """
            // @Filename: widgets.d.ts
            declare module "widgets" {
                export interface Base { zzBase: number; }
                export class Widget implements Base { zzBase: number; zzW: string; }
                export class Fancy extends Widget { zzF: boolean; }
                export const one: Widget;
                export const list: Widget[];
                export function make(): Base;
            }

            // @Filename: use.ts
            import { one, list, make, Fancy } from "widgets";
            export const p1: boolean = one;
            export const p2: boolean = list;
            export const p3: boolean = make();
            export const p4: boolean = new Fancy().zzW;
            export const p5: boolean = new Fancy().zzBase;
            """
        )
        val m = messages(d, 2322)
        assert(m == listOf(
            "Type 'Widget' is not assignable to type 'boolean'.",
            "Type 'Widget[]' is not assignable to type 'boolean'.",
            "Type 'Base' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
    }

    @Test
    fun `negative control - the lens resolves a bare heritage base inside an augmentation block in the augmented module`() {
        // `declare module "./types"` beside a real `./types.ts` is an AUGMENTATION:
        // its partial `interface SourceFile` is a separate symbol here, and the bare
        // `SourceFile` written in the block must keep resolving to the AUGMENTED
        // module's declaration (INV.3(c)(iv)), never to the partial. Consulting the
        // block's own exports answers the partial — the mistake that cost 43 rows on
        // three profiles in (CHK.76). The heritage resolver is the instrument: the
        // lens's `typeReferenceSymbol` asks the WALK-scoped chain first, which
        // answers the block's own binder table in-walk (pre-existing, not this
        // round's), so only the position-derived path below it can be pinned here.
        val (recorder, files) = runLens(
            "/proj/types.ts" to """
                export interface SourceFile { name: string; }
            """,
            "/proj/aug.ts" to """
                import "./types";
                declare module "./types" {
                    interface SourceFile { extra: number; }
                    interface Extra extends SourceFile { more: boolean; }
                }
            """,
        )
        val target = declarationNamed(files[0], "SourceFile")
        val partial = declarationNamed(files[1], "SourceFile")
        val resolved = recorder.heritageBaseDecl["Extra:extends"]
        val isTarget = resolved === target
        val isPartial = resolved === partial
        assert(isTarget)
        assert(!isPartial)
    }

    @Test
    fun `negative control - an augmentation of a program file keeps every consumer row it had`() {
        val d = diagnose(
            """
            // @Filename: types.ts
            export interface SourceFile { name: string; }

            // @Filename: aug.ts
            import "./types";
            declare module "./types" {
                interface SourceFile { extra: number; self: SourceFile; }
            }

            // @Filename: use2.ts
            import { SourceFile } from "./types";
            declare const s: SourceFile;
            export const q1: boolean = s.extra;
            export const q2: boolean = s.name;
            export const q3: boolean = s.self.name;
            export const q5: boolean = s.self;
            """
        )
        val m = messages(d, 2322)
        assert(m == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
            "Type 'SourceFile' is not assignable to type 'boolean'.",
        ))
    }

    // --- (2) a qualified heritage base with a type-only head / an implicit export -----

    private val qualifiedHeritageDts = """
        declare namespace ts {
            namespace JsTyping { interface TypingResolutionHost { zzHost: number; } }
            interface InstallTypingHost extends JsTyping.TypingResolutionHost { zzInstall: string; }
            namespace server {
                interface A { zzA: number; }
                interface C extends ts.server.A { zzC: string; }
            }
            interface D extends server.A { zzD: string; }
        }
    """

    @Test
    fun `a dotted heritage base whose head is a type-only namespace inherits its members`() {
        val d = diagnose(
            """
            // @Filename: a.d.ts
            $qualifiedHeritageDts

            // @Filename: use.ts
            declare const h: ts.InstallTypingHost;
            export const p1: boolean = h.zzHost;
            export const p2: boolean = h.zzInstall;
            declare const c: ts.server.C;
            export const p3: boolean = c.zzA;
            declare const d: ts.D;
            export const p4: boolean = d.zzA;
            """
        )
        // Before: p1, p3 and p4 were `Property 'zzHost' does not exist on type
        // 'InstallTypingHost'` and its siblings — the clause was skipped.
        val m = messages(d, 2322)
        assert(m == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'string' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2339).isEmpty())
    }

    @Test
    fun `the lens answers a dotted heritage base through a type-only head and a nested ambient namespace`() {
        val (recorder, files) = runLens("/proj/a.d.ts" to qualifiedHeritageDts)
        val host = declarationNamed(files[0], "TypingResolutionHost")
        val a = declarationNamed(files[0], "A")
        val installExtendsHost = recorder.heritageBaseDecl["InstallTypingHost:extends"] === host
        val cExtendsA = recorder.heritageBaseDecl["C:extends"] === a
        val dExtendsA = recorder.heritageBaseDecl["D:extends"] === a
        assert(installExtendsHost)
        assert(cExtendsA)
        assert(dExtendsA)
    }

    @Test
    fun `control - a non-ambient namespace member without export is not implicitly exported to a heritage clause`() {
        val (recorder, files) = runLens(
            "/proj/t.ts" to """
                namespace N { interface Hidden { a: number; } export interface Shown { b: string; } }
                interface X extends N.Hidden { }
                interface Y extends N.Shown { }
            """,
        )
        val shown = declarationNamed(files[0], "Shown")
        val xBase = recorder.heritageBaseDecl["X:extends"]
        val yIsShown = recorder.heritageBaseDecl["Y:extends"] === shown
        assert(xBase == null)
        assert(yIsShown)
    }

    // --- (3) the lens on a qualified type reference -----------------------------------

    @Test
    fun `the lens names a qualified type reference through the qualified-name resolver`() {
        val source = """
            declare namespace ts {
                type Cb = () => void;
                namespace server {
                    type Gen<T> = (e: T) => void;
                    const cb: ts.Cb;
                    const nope: ts.Zz;
                }
                const g: server.Gen<number>;
            }
        """
        val (recorder, files) = runLens("/proj/a.d.ts" to source)
        val cb = declarationNamed(files[0], "Cb")
        val gen = declarationNamed(files[0], "Gen")
        val cbPos = posOf(files[0], "cb: ts.Cb")
        val gPos = posOf(files[0], "g: server.Gen<number>")
        val nopePos = posOf(files[0], "nope: ts.Zz")
        val cbIsAlias = recorder.typeReferenceDecl[cbPos] === cb
        val gIsGen = recorder.typeReferenceDecl[gPos] === gen
        val nopeIsNull = recorder.typeReferenceDecl[nopePos] == null
        assert(cbIsAlias)
        assert(gIsGen)
        assert(nopeIsNull)
    }

    // --- (4) cross-file namespace merging ---------------------------------------------

    private val mergedA = """
        declare namespace ts {
            interface A { zzA: number; }
            namespace server { interface SA { zzSA: number; } }
        }
    """
    private val mergedB = """
        declare namespace ts {
            interface B extends A { x: A; }
            const bA: A;
            namespace server {
                interface SB extends SA { y: SA; }
                interface SC extends ts.server.SA { z: ts.server.SA; }
                const sbA: SA;
            }
        }
    """
    private val mergedC = """
        declare namespace ts.server {
            interface SD extends SA { w: SA; }
            const sdA: SA;
        }
    """

    @Test
    fun `a namespace declared in two declaration files is one namespace to its second file's bare names`() {
        val d = diagnose(
            """
            // @Filename: a.d.ts
            $mergedA

            // @Filename: b.d.ts
            $mergedB

            // @Filename: c.d.ts
            $mergedC

            // @Filename: use.ts
            declare const b: ts.B;
            export const p1: boolean = b.zzA;
            export const p2: boolean = b.x;
            export const p3: boolean = ts.bA;
            declare const sb: ts.server.SB;
            export const p4: boolean = sb.zzSA;
            export const p5: boolean = sb.y;
            export const p6: boolean = ts.server.sbA;
            declare const sc: ts.server.SC;
            export const p7: boolean = sc.zzSA;
            export const p8: boolean = sc.z;
            declare const sd: ts.server.SD;
            export const p9: boolean = sd.zzSA;
            export const p10: boolean = sd.w;
            export const p11: boolean = ts.server.sdA;
            """
        )
        // Before: p1/p4/p7/p9 were false TS2339 rows (the clause was skipped) and
        // p2/p3/p5/p6/p10/p11 were silent (`any`).
        val m = messages(d, 2322)
        assert(m == listOf(
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'A' is not assignable to type 'boolean'.",
            "Type 'A' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'SA' is not assignable to type 'boolean'.",
            "Type 'SA' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'SA' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
            "Type 'SA' is not assignable to type 'boolean'.",
            "Type 'SA' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2339).isEmpty())
    }

    @Test
    fun `the lens answers the second file's bare names from the merged namespace`() {
        val (recorder, files) = runLens(
            "/proj/a.d.ts" to mergedA,
            "/proj/b.d.ts" to mergedB,
            "/proj/c.d.ts" to mergedC,
        )
        val a = declarationNamed(files[0], "A")
        val sa = declarationNamed(files[0], "SA")
        val bExtendsA = recorder.heritageBaseDecl["B:extends"] === a
        val sbExtendsSa = recorder.heritageBaseDecl["SB:extends"] === sa
        val scExtendsSa = recorder.heritageBaseDecl["SC:extends"] === sa
        val sdExtendsSa = recorder.heritageBaseDecl["SD:extends"] === sa
        val bAIsA = recorder.typeReferenceDecl[posOf(files[1], "bA: A")] === a
        val sbAIsSa = recorder.typeReferenceDecl[posOf(files[1], "sbA: SA")] === sa
        val sdAIsSa = recorder.typeReferenceDecl[posOf(files[2], "sdA: SA")] === sa
        assert(bExtendsA)
        assert(sbExtendsSa)
        assert(scExtendsSa)
        assert(sdExtendsSa)
        assert(bAIsA)
        assert(sbAIsSa)
        assert(sdAIsSa)
    }

    @Test
    fun `negative control - same-named namespaces in two module files stay separate`() {
        // INV.3(d): a MODULE file's namespace never merges into `globals`; tsgo
        // reports `Cannot find name 'A'` at every use in the second file and so do we.
        val d = diagnose(
            """
            // @Filename: a.ts
            export namespace ts { export interface A { zzA: number; } }

            // @Filename: b.ts
            export namespace ts { export interface B extends A { x: A; } }
            """
        )
        val m = messages(d, 2304)
        assert(m == listOf("Cannot find name 'A'.", "Cannot find name 'A'."))
    }
}

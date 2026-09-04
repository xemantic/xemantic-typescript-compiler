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
 * (CHK.76) Name resolution INSIDE a namespace body — tsc's `resolveName`
 * `ModuleDeclaration` arm: a name written inside `namespace N { namespace M { … } }`
 * is looked up in `M`'s exports first, then `N`'s, then the file's, so a member
 * declared beside the use resolves, and one declared in an enclosing namespace
 * SHADOWS the outer (or a lib) declaration of the same name.
 *
 * Measured by (EXT.13) on `typescript.d.ts` and reproduced here on four-line
 * fixtures against tsgo 7.0.2 (`scripts` scratch loop, 2026-09-02): before the fix
 * a bare sibling in a nested namespace resolved to `any`, a bare `Node` inside a
 * nested namespace that declares its own resolved to the ROOT's `Node` (a false
 * TS2353 on the correct literal, silence on the wrong one), a qualified `M.D`
 * written inside the root body resolved to `any`, and a bare heritage base inside
 * the namespace resolved to nothing. The checker's three frame families resolved
 * a nested namespace's NAME through file locals / `globals`, so their ambient
 * stack only ever held the outermost namespace; the fix is a position-derived
 * consult (`Checker.lookupInEnclosingNamespaces`) at the resolvers.
 *
 * Every pin reads the resolved type OUT OF A MESSAGE: resolving to `any` is
 * silent, so a probe that reports nothing measures nothing. The `.d.ts` shapes
 * are read through a consumer file, because an ambient initializer is refused
 * before it is checked and a `.d.ts` body reports no TS2304 (a separate gate,
 * unchanged here).
 *
 * ABLATED against the HEAD binary (round 807's protocol, one mistake at a time —
 * here the whole consult removed): the pins named `control - …` stayed GREEN,
 * because the shape they read was already served by the lazy-inference path
 * (`pushInferenceNamespaceFor` walks a namespace SYMBOL's parent chain, which is
 * complete) or by the walk-scoped lens chain; they are kept as equivalence
 * controls for those paths, which the reordering must not move. Every other pin
 * went RED.
 */
class NamespaceBodyResolutionTest {

    private fun messages(diagnostics: List<Diagnostic>, code: Int): List<String> =
        diagnostics.filter { it.code == code }.map { it.message }

    // --- (a) a bare sibling declared in the same nested namespace ------------------

    @Test
    fun `a bare class declared beside its use in a nested namespace resolves to the class`() {
        val d = diagnose(
            """
            export namespace N {
                export namespace M {
                    export class D { zzD: number = 1; }
                    export const bad: D = 1;
                }
            }
            """
        )
        val m = messages(d, 2322)
        assert(m == listOf("Type 'number' is not assignable to type 'D'."))
    }

    @Test
    fun `control - a consumer of a declaration file reads a nested namespace member by the lazy path as before`() {
        val d = diagnose(
            """
            // @Filename: a.d.ts
            declare namespace N {
                namespace M {
                    class D { zzD: number; }
                    const d: D;
                    const d2: N.M.D;
                }
            }

            // @Filename: use.ts
            export const p1: boolean = N.M.d;
            export const p2: boolean = N.M.d2;
            """
        )
        val m = messages(d, 2322)
        assert(m == listOf(
            "Type 'D' is not assignable to type 'boolean'.",
            "Type 'D' is not assignable to type 'boolean'.",
        ))
    }

    // --- (b) a nested declaration shadows the root's --------------------------------

    private val shadowing = """
        export namespace N {
            export interface Node { rootOnly: number; }
            export namespace M {
                export interface Node { nestedOnly: string; }
                export const good: Node = { nestedOnly: "" };
                export const bad: Node = { rootOnly: 1 };
            }
            export const rootGood: Node = { rootOnly: 1 };
            export const rootBad: Node = { nestedOnly: "" };
        }
    """

    @Test
    fun `inside the nested namespace a bare Node is the nested Node - the literal with its member is accepted`() {
        val d = diagnose(shadowing)
        // The excess-property rows name the member that does NOT exist on the
        // resolved type: `rootOnly` inside M (the nested Node has no such member),
        // `nestedOnly` at the root (the root Node has none). Before the fix the
        // rows were `nestedOnly` twice — inside M the wrong literal was silent and
        // the right one was refused.
        val m = messages(d, 2353)
        assert(m == listOf(
            "Object literal may only specify known properties, and 'rootOnly' does not exist in type 'Node'.",
            "Object literal may only specify known properties, and 'nestedOnly' does not exist in type 'Node'.",
        ))
    }

    @Test
    fun `control - a consumer of a declaration file sees the nested Node shadow the root's as before`() {
        val d = diagnose(
            """
            // @Filename: a.d.ts
            declare namespace N {
                interface Node { rootOnly: number; }
                namespace M {
                    interface Node { nestedOnly: string; }
                    const n: Node;
                }
                const r: Node;
            }

            // @Filename: use.ts
            export const p1: boolean = N.M.n.nestedOnly;
            export const p2: boolean = N.r.rootOnly;
            export const p3: boolean = N.M.n.rootOnly;
            """
        )
        assert(messages(d, 2322) == listOf(
            "Type 'string' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
        assert(messages(d, 2339) == listOf("Property 'rootOnly' does not exist on type 'Node'."))
    }

    // --- (c) a qualified name written inside a namespace body -----------------------

    @Test
    fun `a qualified M dot D written inside the root namespace body resolves through the enclosing namespace`() {
        val d = diagnose(
            """
            export namespace N {
                export namespace M {
                    export class D { zzD: number = 1; }
                }
                export const bad: M.D = 1;
            }
            """
        )
        assert(messages(d, 2322) == listOf("Type 'number' is not assignable to type 'D'."))
    }

    @Test
    fun `a qualified name written in a sibling namespace resolves to the identical declaration`() {
        val d = diagnose(
            """
            // @Filename: a.d.ts
            declare namespace N {
                interface Node { rootOnly: number; }
                namespace M { interface Node { nestedOnly: string; } }
                namespace S { const q: M.Node; const q2: N.M.Node; }
            }

            // @Filename: use.ts
            export const p1: boolean = N.S.q;
            export const p2: boolean = N.S.q2;
            const same1: N.M.Node = N.S.q;
            const same2: N.M.Node = N.S.q2;
            const other: N.Node = N.S.q;
            """
        )
        assert(messages(d, 2322) == listOf(
            "Type 'Node' is not assignable to type 'boolean'.",
            "Type 'Node' is not assignable to type 'boolean'.",
        ))
        // Identity: `N.S.q` IS `N.M.Node` (no row at `same1`/`same2`), and it is
        // NOT `N.Node` — the only TS2741 names the root member the nested type lacks.
        assert(messages(d, 2741) == listOf(
            "Property 'rootOnly' is missing in type 'Node' but required in type 'Node'.",
        ))
    }

    // --- (d) a bare heritage base inside the namespace ------------------------------

    @Test
    fun `control - a bare heritage base at the root namespace level already resolved through the lazy path`() {
        val d = diagnose(
            """
            export namespace N {
                export interface Node { rootOnly: number; }
                export interface X extends Node { more: boolean; }
                export const ok: X = { rootOnly: 1, more: true };
                export const bad: X = { more: true };
            }
            """
        )
        assert(messages(d, 2741) == listOf(
            "Property 'rootOnly' is missing in type '{ more: true; }' but required in type 'X'.",
        ))
    }

    @Test
    fun `control - a consumer of a declaration file sees inherited members of a namespace-declared base as before`() {
        val d = diagnose(
            """
            // @Filename: a.d.ts
            declare namespace N {
                class C { zzC: number; }
                interface I extends C { zzI: string; }
                const v: I;
            }

            // @Filename: use.ts
            export const p1: boolean = N.v;
            export const p2: boolean = N.v.zzC;
            """
        )
        assert(messages(d, 2322) == listOf(
            "Type 'I' is not assignable to type 'boolean'.",
            "Type 'number' is not assignable to type 'boolean'.",
        ))
    }

    // --- (CHK.75) an enum member reached by a bare qualified name in its own namespace

    @Test
    fun `control - a bare LE dot Q in a checked namespace body was already the enum member through the frame stack`() {
        val d = diagnose(
            """
            export namespace N {
                export enum LE { Q = 1, R = 2 }
                export const k: LE.Q = LE.Q;
                export const bad: never = LE.Q;
            }
            """
        )
        // (PARITY.2): a `never` annotation, not a `string` one — since the enum arm of
        // `Checker.baseTypeOfLiteralType` landed, a `string` target generalizes the
        // member to `LE` and this resolution pin would no longer name the member.
        // `never` is one of the three targets tsc suppresses the generalization for, and
        // the row is byte-identical to tsgo 7.0.2 and pristine `typescript@6.0.3`.
        assert(messages(d, 2322) == listOf("Type 'LE.Q' is not assignable to type 'never'."))
    }

    @Test
    fun `an ambient namespace member annotated with its own enum member type carries that type`() {
        val d = diagnose(
            """
            // @Filename: a.d.ts
            declare namespace N {
                enum LE { Q = 1, R = 2 }
                const m: LE.Q;
            }

            // @Filename: use.ts
            export const p: never = N.m;
            """
        )
        // (PARITY.2): see the note on the pin above — a `boolean` target would now
        // generalize the member to `LE`.
        assert(messages(d, 2322) == listOf("Type 'LE.Q' is not assignable to type 'never'."))
    }

    // --- negative controls ------------------------------------------------------------

    @Test
    fun `negative control - a name declared in no enclosing namespace is still unresolved`() {
        val d = diagnose(
            """
            export namespace N {
                export namespace M {
                    export const u: Undeclared = 1;
                }
            }
            """
        )
        assert(messages(d, 2304) == listOf("Cannot find name 'Undeclared'."))
    }

    @Test
    fun `negative control - a sibling namespace's member is not visible by its bare name`() {
        val d = diagnose(
            """
            export namespace N {
                export namespace M { export class D { zzD: number = 1; } }
                export namespace S { export const z: D = 1; }
            }
            """
        )
        assert(messages(d, 2304) == listOf("Cannot find name 'D'."))
    }

    @Test
    fun `negative control - a root name is still reachable from a nested namespace that does not shadow it`() {
        val d = diagnose(
            """
            export namespace N {
                export interface Root { rootOnly2: number; }
                export namespace M {
                    export const bad: Root = { rootOnly2: "x" };
                }
            }
            """
        )
        assert(messages(d, 2322) == listOf("Type 'string' is not assignable to type 'number'."))
    }

    @Test
    fun `negative control - a file-level name resolves inside a namespace body exactly as before`() {
        val d = diagnose(
            """
            interface Top { t: number; }
            export namespace N {
                export namespace M {
                    export const bad: Top = 1;
                }
            }
            """
        )
        assert(messages(d, 2322) == listOf("Type 'number' is not assignable to type 'Top'."))
    }

    // --- the lens (the (EXT.13) instrument) -------------------------------------------

    /**
     * Records, from inside the walk, what the lens answers for every heritage base
     * and every bare type reference written inside the namespace bodies.
     */
    private class Recorder : CheckedNodeSink {
        /** `interface name` -> the declaration node the base symbol carries, by identity index. */
        val heritageBaseDecl = HashMap<String, Node?>()
        val typeReferenceDecl = HashMap<Int, Node?>()

        /** `lens.typeOfTypeNode` of each variable annotation, keyed by the declaration's `pos`. */
        val annotationTypeDecl = HashMap<Int, Node?>()
        val annotationTypeText = HashMap<Int, String>()

        /** `lens.typeOf` of each `X.Y` property access, rendered, keyed by the node's `pos`. */
        val propertyAccessTypes = HashMap<Int, String>()

        override fun expression(node: Expression, lens: CheckedLens) {
            if (node is PropertyAccessExpression) {
                propertyAccessTypes[node.pos] = lens.render(lens.typeOf(node))
            }
        }

        override fun declaration(node: Node, lens: CheckedLens) {
            if (node is InterfaceDeclaration) {
                val base = node.heritageClauses?.firstOrNull()?.types?.firstOrNull()?.expression
                if (base != null) {
                    heritageBaseDecl[node.name.text] = lens.heritageBaseSymbol(base)?.declarations?.firstOrNull()
                }
            }
            if (node is VariableDeclaration) {
                val ref = node.type as? TypeReference
                if (ref != null) {
                    typeReferenceDecl[node.pos] = lens.typeReferenceSymbol(ref)?.declarations?.firstOrNull()
                    val type = lens.typeOfTypeNode(ref)
                    annotationTypeDecl[node.pos] = (type as? Type.Object)?.symbol?.declarations?.firstOrNull()
                    annotationTypeText[node.pos] = lens.render(type)
                }
            }
        }
    }

    private val lensSource = """
        declare namespace N {
            interface Node { rootOnly: number; }
            interface X extends Node { more: boolean; }
            namespace M {
                interface Node { nestedOnly: string; }
                interface Y extends Node { more: boolean; }
                class Project { p: number; }
                const proj: Project;
                const n: Node;
                enum LE { Q = 1, R = 2 }
                const q = LE.Q;
            }
            const r: Node;
        }
    """.trimIndent()

    private fun runLens(): Pair<Recorder, SourceFile> {
        val options = CompilerOptions()
        val sourceFile = Parser(lensSource, "/proj/a.d.ts").parse()
        val binderResult = Binder(options).bind(sourceFile)
        val recorder = Recorder()
        Checker(options, listOf(binderResult), isMultiFileSource = true, checkedSink = recorder)
        return recorder to sourceFile
    }

    /** The n-th `interface <name>` declaration node of [file], searched in preorder. */
    private fun interfaceNamed(file: SourceFile, name: String, occurrence: Int): InterfaceDeclaration {
        val found = ArrayList<InterfaceDeclaration>()
        fun walk(node: Node) {
            if (node is InterfaceDeclaration && node.name.text == name) found.add(node)
            forEachChild(node) { walk(it) }
        }
        walk(file)
        return found[occurrence]
    }

    private fun classNamed(file: SourceFile, name: String): ClassDeclaration {
        var found: ClassDeclaration? = null
        fun walk(node: Node) {
            if (node is ClassDeclaration && node.name?.text == name) found = node
            forEachChild(node) { walk(it) }
        }
        walk(file)
        return found!!
    }

    @Test
    fun `the lens answers a bare heritage base inside a namespace body with that namespace's declaration`() {
        val (recorder, file) = runLens()
        val rootNode = interfaceNamed(file, "Node", 0)
        val nestedNode = interfaceNamed(file, "Node", 1)
        val xBaseIsRoot = recorder.heritageBaseDecl["X"] === rootNode
        val yBaseIsNested = recorder.heritageBaseDecl["Y"] === nestedNode
        assert(xBaseIsRoot)
        assert(yBaseIsNested)
    }

    @Test
    fun `the lens types a bare annotation inside a nested namespace by the sibling and by the shadowing declaration`() {
        // The (EXT.13) instrument: `typeOfTypeNode` + `render`, which is what the
        // externals generator reads for an annotation. Before the fix `proj: Project`
        // rendered `any` and `n: Node` was the ROOT's `Node`.
        val (recorder, file) = runLens()
        val project = classNamed(file, "Project")
        val nestedNode = interfaceNamed(file, "Node", 1)
        val rootNode = interfaceNamed(file, "Node", 0)
        val projPos = lensSource.indexOf("proj: Project")
        val nPos = lensSource.indexOf("n: Node")
        val rPos = lensSource.indexOf("r: Node")
        assert(recorder.annotationTypeText[projPos] == "Project")
        val projIsClass = recorder.annotationTypeDecl[projPos] === project
        val nIsNested = recorder.annotationTypeDecl[nPos] === nestedNode
        val rIsRoot = recorder.annotationTypeDecl[rPos] === rootNode
        assert(projIsClass)
        assert(nIsNested)
        assert(rIsRoot)
    }

    @Test
    fun `the lens types a bare LE dot Q written inside the ambient namespace declaring LE as the enum member`() {
        // (CHK.75)'s row: `getTypeOfExpression` of the bare `LE.Q` answered `any`
        // inside a `declare namespace` body, where no frame stack is installed.
        val (recorder, _) = runLens()
        val qPos = lensSource.indexOf("LE.Q")
        assert(recorder.propertyAccessTypes[qPos] == "LE.Q")
    }

    @Test
    fun `control - the walk-scoped lens chain already named a bare type reference inside a nested namespace`() {
        val (recorder, file) = runLens()
        val project = classNamed(file, "Project")
        val nestedNode = interfaceNamed(file, "Node", 1)
        val rootNode = interfaceNamed(file, "Node", 0)
        val projPos = lensSource.indexOf("proj: Project")
        val nPos = lensSource.indexOf("n: Node")
        val rPos = lensSource.indexOf("r: Node")
        val projIsClass = recorder.typeReferenceDecl[projPos] === project
        val nIsNested = recorder.typeReferenceDecl[nPos] === nestedNode
        val rIsRoot = recorder.typeReferenceDecl[rPos] === rootNode
        assert(projIsClass)
        assert(nIsNested)
        assert(rIsRoot)
    }
}

/**
 * (CHK.76) The two corpus regressions the first cut of the consult produced, pinned
 * as the shapes that must NOT move: a class VALUE inside a namespace body is its
 * STATIC side to a property access (`statics.ts`), and a nested-namespace
 * assignment mismatch the engine now decides is reported ONCE (`qualify.ts`: the
 * corpus pin walker `checkQualify` runs after `checkSpine` and replaces the row).
 */
class NamespaceBodyResolutionRegressionTest {

    private fun rows(diagnostics: List<Diagnostic>, code: Int): List<String> =
        diagnostics.filter { it.code == code }.map { it.message }

    @Test
    fun `a static member read through the class name inside its namespace is not TS2576`() {
        val d = diagnose(
            """
            namespace M {
                export class C {
                    static y: number = 1;
                    x: number = 2;
                    constructor() { this.x = C.y; }
                }
                const v = C.y;
                const p: boolean = C.y;
            }
            """
        )
        assert(rows(d, 2576).isEmpty())
        // The read still types: `C.y` is a `number`.
        assert(rows(d, 2322) == listOf("Type 'number' is not assignable to type 'boolean'."))
    }

    @Test
    fun `a missing member on the class name inside its namespace reports the static side as tsgo does`() {
        val d = diagnose(
            """
            namespace M {
                export class C { static y: number = 1; }
                const w = C.nope;
            }
            """
        )
        assert(rows(d, 2339) == listOf("Property 'nope' does not exist on type 'typeof C'."))
        assert(rows(d, 2576).isEmpty())
    }

    @Test
    fun `control - an instance member read as a static one is still TS2576 inside a namespace`() {
        val d = diagnose(
            """
            namespace M {
                export class C { static y: number = 1; }
                const inst = new C();
                const i = inst.y;
            }
            """
        )
        assert(rows(d, 2576) == listOf(
            "Property 'y' does not exist on type 'C'. Did you mean to access the static member 'C.y' instead?",
        ))
    }

    @Test
    fun `a nested-namespace assignment mismatch through a qualified name is reported exactly once`() {
        val d = diagnose(
            """
            namespace Everest {
                export namespace K1 {
                    export interface I3 { zeep; }
                }
                export namespace K2 {
                    export interface I4 { z; }
                    var v1: I4 = undefined as any;
                    var v2: K1.I3 = v1;
                }
            }
            """,
            directives = "// @strict: false",
        )
        assert(rows(d, 2741) == listOf("Property 'zeep' is missing in type 'I4' but required in type 'I3'."))
    }
}

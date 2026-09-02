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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (INV.2) Stage 2 of `docs/INVERSION-DESIGN.md`: the post-hoc [TypeOracle].
 *
 * Every walk-scoped row is pinned on a VALUE the post-hoc path gets wrong —
 * a body local that shadows a global, a parameter, a narrowed reference, a
 * member name — because a "two arms agree" pin cannot see a defect present in
 * both ((INC.28)'s law). The refusals are pinned as refusals (a null there
 * would read as "nothing here"), the handle table on its three ways of
 * refusing, and the project entry on the file set and one recorded answer.
 */
class TypeOracleTest {

    private val lib = """
        export interface Shape { p: string; q?: number }
        export function take(s: Shape): void {}
        export function over(x: string): string;
        export function over(x: number): number;
        export function over(x: any): any { return x; }
        export class Base {
            constructor(readonly v: number) {}
            b(): number { return this.v; }
        }
        export class Derived extends Base {}
        export enum Color { Red, Green }
        export type Pair = Shape | { z: number };
    """.trimIndent()

    private val main = """
        import { Shape, take, over, Base, Color, Pair } from "./lib";
        declare const collide: string;
        function f(u: string | number, p: number) {
            const collide: number = 1;
            const useLocal = collide;
            if (typeof u === "string") {
                const useNarrow = u;
            }
            const useParam = p;
            take({ p: "x" });
            const r = over(p);
            const s: Shape = { p: "y" };
            const read = s.p;
            const n = new Base(1);
            const m = n.b();
            const c = Color.Green;
            return r;
        }
        declare const pair: Pair;
    """.trimIndent()

    private val libName = "/proj/lib.ts"
    private val mainName = "/proj/main.ts"

    private class Built(val build: TypeOracleBuild) {
        val oracle: TypeOracle get() = build.oracle
        val mainFile: SourceFile get() = oracle.files.first { it.fileName == "/proj/main.ts" }
        val libFile: SourceFile get() = oracle.files.first { it.fileName == "/proj/lib.ts" }
    }

    private fun build(): Built =
        Built(typeOracleOf(mapOf(libName to lib, mainName to main), CompilerOptions()))

    private fun offsetOf(text: String, needle: String, occurrence: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = text.indexOf(needle, at + 1) }
        assert(at >= 0)
        return at
    }

    /** The DEEPEST node of class [T] starting at [offset] — iterative, like every walk here. */
    private inline fun <reified T : Node> nodeAt(file: SourceFile, offset: Int): T {
        val stack = ArrayList<Node>()
        stack.add(file)
        var found: T? = null
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node is T && node.pos == offset) found = node
            forEachChild(node) { child -> stack.add(child) }
        }
        val result = found
        assert(result != null)
        return result
    }

    private fun fileNameOf(node: Node): String? {
        var current: Node? = node
        while (current != null) {
            if (current is SourceFile) return current.fileName
            current = (current as NodeBase).parent
        }
        return null
    }

    private fun Built.mainIdentifier(needle: String, occurrence: Int = 0, skip: Int = 0): Identifier =
        nodeAt(mainFile, offsetOf(main, needle, occurrence) + skip)

    // ---------------------------------------------------------------------
    // The recorded rows — each on a value the post-hoc path answers wrongly.
    // ---------------------------------------------------------------------

    @Test
    fun `typeAt answers the body local's own declaration and not the global it shadows`() {
        val b = build()
        // `useLocal = collide` — the THIRD `collide`: the body's `number`, not the
        // file-level `declare const collide: string` (round 911's positive control).
        val reference = b.mainIdentifier("collide", 2)
        val type = b.oracle.typeAt(reference)
        val text = type?.let { b.oracle.typeToString(it) }
        assert(text == "number")
    }

    @Test
    fun `symbolAt on the body local resolves to the declaration inside the function`() {
        val b = build()
        val reference = b.mainIdentifier("collide", 2)
        val symbol = b.oracle.symbolAt(reference)
        assert(symbol != null)
        val declaration = symbol.declarations.firstOrNull()
        assert(declaration != null)
        val functionStart = offsetOf(main, "function f(")
        val declaredInsideF = declaration.pos > functionStart
        assert(declaredInsideF)
        // …and the FILE-LEVEL declaration name answers the global one.
        val globalName = b.mainIdentifier("collide", 0)
        val globalSymbol = b.oracle.symbolAt(globalName)
        assert(globalSymbol != null)
        val globalDeclaredBeforeF = (globalSymbol.declarations.firstOrNull()?.pos ?: Int.MAX_VALUE) < functionStart
        assert(globalDeclaredBeforeF)
        assert(globalSymbol !== symbol)
    }

    @Test
    fun `symbolAt on a parameter reference answers the parameter`() {
        val b = build()
        val reference = b.mainIdentifier("useParam = p", skip = "useParam = ".length)
        val symbol = b.oracle.symbolAt(reference)
        assert(symbol != null)
        val declaredAsParameter = symbol.declarations.firstOrNull() is Parameter
        assert(declaredAsParameter)
        assert(symbol.name == "p")
    }

    @Test
    fun `symbolAt on a member name answers the property declared in the other file`() {
        val b = build()
        // the `p` of `s.p`
        val member = b.mainIdentifier("s.p", skip = 2)
        val symbols = b.oracle.symbolsAt(member)
        assert(symbols.size == 1)
        val declaredIn = symbols[0].declarations.firstOrNull()?.let { fileNameOf(it) }
        assert(declaredIn == libName)
        assert(symbols[0].name == "p")
        // and the type AT the member name is the ACCESS's type (BUG.4)
        val text = b.oracle.typeAt(member)?.let { b.oracle.typeToString(it) }
        assert(text == "string")
    }

    @Test
    fun `symbolAt on an import answers the alias and aliasedSymbol the declaration`() {
        val b = build()
        val callee = b.mainIdentifier("over(p)")
        val alias = b.oracle.symbolAt(callee)
        assert(alias != null)
        val isAlias = alias.flags.hasAny(SymbolFlags.Alias)
        assert(isAlias)
        val target = b.oracle.aliasedSymbol(alias)
        assert(target != null)
        assert(target !== alias)
        val targetDeclaredIn = target.declarations.firstOrNull()?.let { fileNameOf(it) }
        assert(targetDeclaredIn == libName)
        // three declarations: two overloads and the implementation
        assert(target.declarations.size == 3)
        // a non-alias answers null
        val notAlias = b.oracle.aliasedSymbol(target)
        assert(notAlias == null)
    }

    @Test
    fun `resolvedCallAt picks the overload declared second for a number argument`() {
        val b = build()
        val call: CallExpression = nodeAt(b.mainFile, offsetOf(main, "over(p)"))
        val resolved = b.oracle.resolvedCallAt(call)
        assert(resolved != null)
        assert(resolved.candidates >= 2)
        val signature = resolved.signature
        assert(signature != null)
        // Declared SECOND, so `arityMatches[0]` cannot answer it by accident.
        val secondOverload = offsetOf(lib, "function over(x: number)")
        val picked = signature.declaration?.pos
        assert(picked == secondOverload)
        val returned = b.oracle.typeToString(b.oracle.returnTypeOfSignature(signature))
        assert(returned == "number")
        // the same signature is what the call's own type reports
        val callText = b.oracle.typeAt(call)?.let { b.oracle.typeToString(it) }
        assert(callText == "number")
    }

    @Test
    fun `resolvedCallAt answers a construct signature for new and null for a non-call`() {
        val b = build()
        val construction: NewExpression = nodeAt(b.mainFile, offsetOf(main, "new Base(1)"))
        val resolved = b.oracle.resolvedCallAt(construction)
        assert(resolved != null)
        assert(resolved.candidates == 1)
        val declaredAsConstructor = resolved.signature?.declaration is Constructor
        assert(declaredAsConstructor)
        val notACall = b.oracle.resolvedCallAt(b.mainIdentifier("useParam = p", skip = "useParam = ".length))
        assert(notACall == null)
        val single: CallExpression = nodeAt(b.mainFile, offsetOf(main, "take({"))
        val singleResolved = b.oracle.resolvedCallAt(single)
        assert(singleResolved != null)
        assert(singleResolved.candidates == 1)
        assert(singleResolved.signature != null)
    }

    @Test
    fun `contextualTypeAt answers the parameter type for an argument and the annotation for an initializer`() {
        val b = build()
        val argument: ObjectLiteralExpression = nodeAt(b.mainFile, offsetOf(main, "{ p: \"x\" }"))
        val argumentContext = b.oracle.contextualTypeAt(argument)?.let { b.oracle.typeToString(it) }
        assert(argumentContext == "Shape")
        val initializer: ObjectLiteralExpression = nodeAt(b.mainFile, offsetOf(main, "{ p: \"y\" }"))
        val initializerContext = b.oracle.contextualTypeAt(initializer)?.let { b.oracle.typeToString(it) }
        assert(initializerContext == "Shape")
        // an un-annotated initializer's reference has none
        val bare = b.mainIdentifier("useParam = p", skip = "useParam = ".length)
        val none = b.oracle.contextualTypeAt(bare)
        assert(none == null)
    }

    @Test
    fun `typeOfSymbolAt answers the narrowed type at the reference and the declared one elsewhere`() {
        val b = build()
        val narrowed = b.mainIdentifier("useNarrow = u", skip = "useNarrow = ".length)
        val symbol = b.oracle.symbolAt(narrowed)
        assert(symbol != null)
        val atReference = b.oracle.typeToString(b.oracle.typeOfSymbolAt(symbol, narrowed))
        assert(atReference == "string")
        val declared = b.oracle.typeToString(b.oracle.typeOfSymbol(symbol))
        assert(declared == "string | number")
        // at a node that does NOT resolve to the symbol, the declared type answers
        val elsewhere = b.mainIdentifier("useParam = p", skip = "useParam = ".length)
        val notNarrowed = b.oracle.typeToString(b.oracle.typeOfSymbolAt(symbol, elsewhere))
        assert(notNarrowed == "string | number")
    }

    @Test
    fun `symbolAt on an object literal key answers the literal's own property`() {
        val b = build()
        // the `p` key of `{ p: "y" }`
        val key = b.mainIdentifier("{ p: \"y\" }", skip = 2)
        val symbol = b.oracle.symbolAt(key)
        assert(symbol != null)
        assert(symbol.name == "p")
        val declaredInMain = symbol.declarations.firstOrNull()?.let { fileNameOf(it) }
        assert(declaredInMain == mainName)
    }

    @Test
    fun `symbolAt on a member declaration name answers the member and not a same-named binding`() {
        val b = build()
        // the `p` of `interface Shape { p: string; … }` — a free-name lookup of `p`
        // would find the parameter `p` of nothing here, and in `main.ts` the
        // parameter; the member leg answers the property.
        val declaration: Identifier = nodeAt(b.libFile, offsetOf(lib, "{ p: string") + 2)
        val symbol = b.oracle.symbolAt(declaration)
        assert(symbol != null)
        val declaredAsMember = symbol.declarations.firstOrNull() is PropertyDeclaration
        assert(declaredAsMember)
        val text = b.oracle.typeAt(declaration)?.let { b.oracle.typeToString(it) }
        assert(text == "string")
    }

    @Test
    fun `constantValue answers an enum member through the recorded symbol`() {
        val b = build()
        // `Color.Green` in main: the member symbol's declaration is the EnumMember
        val member = b.mainIdentifier("Color.Green", skip = "Color.".length)
        val symbol = b.oracle.symbolAt(member)
        assert(symbol != null)
        val declaration = symbol.declarations.firstOrNull()
        assert(declaration != null)
        val value = b.oracle.constantValue(declaration)
        assert(value == ConstantValue.NumberValue(1.0))
    }

    // ---------------------------------------------------------------------
    // Bin A — the retained graph.
    // ---------------------------------------------------------------------

    @Test
    fun `propertyOfType collects a member present on one union constituent`() {
        val b = build()
        val pairSymbol = b.oracle.symbolAt(b.mainIdentifier("pair: Pair"))
        assert(pairSymbol != null)
        val pairType = b.oracle.typeOfSymbol(pairSymbol)
        val isUnion = pairType is Type.Union
        assert(isUnion)
        // `p` is on `Shape` and not on `{ z: number }`: the assignability helper
        // answers null here (round 916); the collection answers the one member.
        val p = b.oracle.propertyOfType(pairType, "p")
        assert(p.size == 1)
        assert(p[0].name == "p")
        val z = b.oracle.propertyOfType(pairType, "z")
        assert(z.size == 1)
        // and the COMMON properties of the union are none
        val common = b.oracle.propertiesOfType(pairType)
        assert(common.isEmpty())
        val shape = b.oracle.declaredTypeOfSymbol(b.oracle.symbolAt(b.mainIdentifier("s: Shape", skip = 3))!!)
        val names = b.oracle.propertiesOfType(shape).map { it.name }
        assert(names == listOf("p", "q"))
    }

    @Test
    fun `signatures base types assignability and rendering answer from the graph`() {
        val b = build()
        val overAlias = b.oracle.symbolAt(b.mainIdentifier("over(p)"))!!
        val over = b.oracle.aliasedSymbol(overAlias)!!
        val signatures = b.oracle.callSignaturesOfType(b.oracle.typeOfSymbol(over))
        assert(signatures.size >= 2)
        val declaredParameters = b.oracle.parameterDeclarationsOfSignature(signatures[0])
        assert(declaredParameters?.size == 1)
        val derivedName: Identifier = nodeAt(b.libFile, offsetOf(lib, "Derived"))
        val derived = b.oracle.symbolAt(derivedName)!!
        val bases = b.oracle.baseTypes(b.oracle.declaredTypeOfSymbol(derived)).map { b.oracle.typeToString(it) }
        assert(bases == listOf("Base"))
        val shape = b.oracle.declaredTypeOfSymbol(b.oracle.symbolAt(b.mainIdentifier("s: Shape", skip = 3))!!)
        val literalAssignable = b.oracle.isAssignableTo(b.oracle.typeAt(nodeAt<ObjectLiteralExpression>(b.mainFile, offsetOf(main, "{ p: \"y\" }")))!!, shape)
        assert(literalAssignable)
        val numberAssignable = b.oracle.isAssignableTo(numberType, shape)
        assert(!numberAssignable)
        val annotation: TypeNode = nodeAt<TypeReference>(b.mainFile, offsetOf(main, "s: Shape") + 3)
        val fromNode = b.oracle.typeToString(b.oracle.typeFromTypeNode(annotation))
        assert(fromNode == "Shape")
        val string = b.oracle.intrinsicType("string")
        assert(string === stringType)
        val constituents = b.oracle.typesOfType(b.oracle.typeOfSymbol(b.oracle.symbolAt(b.mainIdentifier("pair: Pair"))!!))
        assert(constituents.size == 2)
    }

    // ---------------------------------------------------------------------
    // Refusals, closing, handles.
    // ---------------------------------------------------------------------

    @Test
    fun `resolveName and symbolsInScope are refused with the Stage 3 reason`() {
        val b = build()
        val location = b.mainIdentifier("useParam = p", skip = "useParam = ".length)
        var refusal: OracleRefusal? = null
        try {
            b.oracle.resolveName("collide", location)
        } catch (e: OracleRefusal) {
            refusal = e
        }
        assert(refusal != null)
        val namesStage3 = refusal.message?.contains("Stage 3") == true
        assert(namesStage3)
        var scopeRefusal: OracleRefusal? = null
        try {
            b.oracle.symbolsInScope(location)
        } catch (e: OracleRefusal) {
            scopeRefusal = e
        }
        assert(scopeRefusal != null)
    }

    @Test
    fun `a closed oracle refuses every question`() {
        val b = build()
        val reference = b.mainIdentifier("collide", 2)
        val before = b.oracle.typeAt(reference)
        assert(before != null)
        val handle = b.oracle.handles.pin(before)
        b.oracle.close()
        assert(b.oracle.isClosed)
        var refused = false
        try {
            b.oracle.typeAt(reference)
        } catch (_: OracleRefusal) {
            refused = true
        }
        assert(refused)
        var handleRefused = false
        try {
            b.oracle.handles.type(handle)
        } catch (_: OracleRefusal) {
            handleRefused = true
        }
        assert(handleRefused)
        assert(b.oracle.handles.pinned == 0)
    }

    @Test
    fun `handles resolve back to the pinned object and refuse release and foreign generations`() {
        val a = build()
        val b = build()
        assert(a.oracle.generation != b.oracle.generation)
        val reference = a.mainIdentifier("collide", 2)
        val type = a.oracle.typeAt(reference)!!
        val symbol = a.oracle.symbolAt(reference)!!
        val call: CallExpression = nodeAt(a.mainFile, offsetOf(main, "over(p)"))
        val signature = a.oracle.resolvedSignatureAt(call)!!
        val th = a.oracle.handles.pin(type)
        val sh = a.oracle.handles.pin(symbol)
        val gh = a.oracle.handles.pin(signature)
        assert(th.generation == a.oracle.generation)
        assert(a.oracle.handles.pinned == 3)
        assert(a.oracle.handles.type(th) === type)
        assert(a.oracle.handles.symbol(sh) === symbol)
        assert(a.oracle.handles.signature(gh) === signature)
        // released: refused
        a.oracle.handles.release(th)
        var released = false
        try {
            a.oracle.handles.type(th)
        } catch (_: OracleRefusal) {
            released = true
        }
        assert(released)
        // a handle of another generation: refused, whatever its id
        var foreign = false
        try {
            b.oracle.handles.symbol(sh)
        } catch (_: OracleRefusal) {
            foreign = true
        }
        assert(foreign)
    }

    @Test
    fun `files are the walked program files in check order and an unwalked node answers nothing`() {
        val b = build()
        val names = b.oracle.files.map { it.fileName }
        assert(names == listOf(libName, mainName))
        // a copy is unindexed (INV.2(a)): no slot to read
        val copied = b.mainIdentifier("collide", 2).copy()
        val nothing = b.oracle.typeAt(copied)
        assert(nothing == null)
        assert(b.oracle.symbolsAt(copied).isEmpty())
        assert(b.build.diagnostics.none { it.category == DiagnosticCategory.Error })
    }

    // ---------------------------------------------------------------------
    // The project entry.
    // ---------------------------------------------------------------------

    private fun project() = InMemoryVfs(
        mapOf(
            "/proj/tsconfig.json" to """
                { "compilerOptions": { "strict": true, "module": "esnext" }, "include": ["src/**/*.ts"] }
            """.trimIndent(),
            "/proj/src/lib.ts" to lib,
            "/proj/src/main.ts" to main,
        )
    )

    @Test
    fun `a project build hands back an oracle over every program file`() {
        val holder = OracleHolder()
        val result = ProjectCompiler(project()).build("/proj", noEmit = true, oracleHolder = holder)
        val oracle = holder.oracle
        assert(oracle != null)
        val names = oracle.files.map { it.fileName }.toSet()
        assert(names == setOf("/proj/src/lib.ts", "/proj/src/main.ts"))
        val mainFile = oracle.files.first { it.fileName == "/proj/src/main.ts" }
        val reference: Identifier = nodeAt(mainFile, offsetOf(main, "collide", 2))
        val text = oracle.typeAt(reference)?.let { oracle.typeToString(it) }
        assert(text == "number")
        assert(result.errorCount == 0)
    }

    @Test
    fun `a project build refuses an oracle over a partition`() {
        val holder = OracleHolder()
        var refused = false
        try {
            ProjectCompiler(project()).build(
                "/proj", noEmit = true, recheckOnly = setOf("/proj/src/main.ts"), oracleHolder = holder,
            )
        } catch (_: IllegalArgumentException) {
            refused = true
        }
        assert(refused)
        assert(holder.oracle == null)
    }

    @Test
    fun `a partial channel mask records the type alone and refuses an oracle`() {
        val options = CompilerOptions()
        val file = Parser(main, mainName).parse()
        val checker = Checker(
            options, listOf(Binder(options).bind(file)), isMultiFileSource = true,
            recordNodeAnswers = true, nodeAnswerChannels = 0,
        )
        val store = checker.nodeAnswers[mainName]
        assert(store != null)
        assert(store.recorded > 20)
        assert(store.symbolsRecorded == 0)
        assert(store.callsRecorded == 0)
        assert(store.contextualRecorded == 0)
        var refused = false
        try {
            checker.typeOracle()
        } catch (_: IllegalArgumentException) {
            refused = true
        }
        assert(refused)
        // and the shipped default is every channel
        assert(NodeAnswers.channels == NodeAnswers.ALL)
        val full = Checker(
            options, listOf(Binder(options).bind(Parser(main, mainName).parse())), isMultiFileSource = true,
            recordNodeAnswers = true,
        )
        val fullStore = full.nodeAnswers[mainName]
        assert(fullStore != null)
        assert(fullStore.symbolsRecorded > 0)
        assert(fullStore.callsRecorded > 0)
        assert(fullStore.contextualRecorded > 0)
    }

    // ---------------------------------------------------------------------
    // The store's companion channels.
    // ---------------------------------------------------------------------

    @Test
    fun `unit - the companion channels are first-wins and refuse an unindexed node`() {
        val file = Parser(main, mainName).parse()
        val node: Identifier = nodeAt(file, offsetOf(main, "collide", 2))
        val store = NodeAnswerStore(file)
        val s1 = Symbol(SymbolFlags.Variable, "a")
        val s2 = Symbol(SymbolFlags.Variable, "b")
        assert(!store.recordSymbols(node, emptyList()))
        assert(store.symbolsAt(node).isEmpty())
        assert(store.recordSymbols(node, listOf(s1)))
        assert(!store.recordSymbols(node, listOf(s2)))
        assert(store.symbolsAt(node) == listOf(s1))
        assert(store.symbolsRecorded == 1)
        val other: Identifier = nodeAt(file, offsetOf(main, "useLocal"))
        assert(store.recordSymbols(other, listOf(s1, s2)))
        assert(store.symbolsAt(other) == listOf(s1, s2))
        val call = ResolvedCall(null, 0)
        assert(store.recordCall(node, call))
        assert(!store.recordCall(node, ResolvedCall(null, 3)))
        assert(store.callAt(node) === call)
        assert(store.callAt(other) == null)
        assert(store.callsRecorded == 1)
        assert(store.recordContextual(node, stringType))
        assert(!store.recordContextual(node, numberType))
        assert(store.contextualAt(node) === stringType)
        assert(store.contextualRecorded == 1)
        val copied = node.copy()
        assert(!store.recordSymbols(copied, listOf(s1)))
        assert(!store.recordCall(copied, call))
        assert(!store.recordContextual(copied, stringType))
        assert(store.symbolsAt(copied).isEmpty())
        assert(store.callAt(copied) == null)
        assert(store.contextualAt(copied) == null)
    }
}

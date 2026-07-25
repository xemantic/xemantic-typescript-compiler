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
import com.xemantic.kotlin.test.have
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * INV.2(c) phase (i) invariants for the Binder's ADDITIVE lexical-binding pass:
 * function-like containers get per-nodeId [LexicalScope]s holding type params,
 * params, body-top-level declarations, and block-hoisted `var`s; scope symbols
 * come from the SEPARATE negative id space (never the global sequence — the
 * ~350-test reshuffle guard); the main binder's `locals`/`nodeToSymbol` stay
 * untouched; container scopes ALIAS (never copy, never mutate) the existing
 * tables. Phase (ii) — nested block/for/catch/case scopes and class scopes —
 * is deliberately NOT yet bound; the negative controls here pin that boundary
 * so phase (ii) flips them consciously.
 */
class Inv2LexicalScopeTest {

    private fun bind(source: String, fileName: String = "t.ts"): BinderResult {
        val sourceFile = Parser(source.trimIndent(), fileName).parse()
        return Binder(CompilerOptions()).bind(sourceFile)
    }

    /** Preorder node list (mirrors indexSourceFile's iterative walk). */
    private fun descendants(root: Node): List<Node> {
        val out = ArrayList<Node>()
        val stack = ArrayList<Node>()
        stack.add(root)
        val buf = ArrayList<Node>()
        val collect: (Node) -> Unit = { buf.add(it) }
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            out.add(node)
            buf.clear()
            forEachChild(node, collect)
            for (i in buf.indices.reversed()) stack.add(buf[i])
        }
        return out
    }

    private fun BinderResult.scopeOf(node: Node): LexicalScope? =
        lexicalScopes[(node as NodeBase).nodeId]

    private fun BinderResult.functionScope(name: String): LexicalScope {
        val fn = descendants(sourceFile).filterIsInstance<FunctionDeclaration>()
            .first { it.name?.text == name }
        return assertNotNull(scopeOf(fn), "no lexical scope for function $name")
    }

    @Test
    fun `function body top-level declarations bind with the right flags`() {
        val result = bind(
            """
            function f(a: number) {
              var v = 1;
              let l = 2;
              const c = 3;
              function g() {}
              class K {}
              interface I { p: string }
              type T = number;
              enum E { A }
              const enum CE { B }
            }
            """
        )
        val scope = result.functionScope("f")
        val flagsByName = scope.symbols.mapValues { it.value.flags }
        val paramOk = flagsByName["a"]?.hasAny(SymbolFlags.FunctionScopedVariable) == true
        val varOk = flagsByName["v"]?.hasAny(SymbolFlags.FunctionScopedVariable) == true
        val letOk = flagsByName["l"]?.hasAny(SymbolFlags.BlockScopedVariable) == true
        val constOk = flagsByName["c"]?.hasAny(SymbolFlags.BlockScopedVariable) == true
        val fnOk = flagsByName["g"]?.hasAny(SymbolFlags.Function) == true
        val classOk = flagsByName["K"]?.hasAny(SymbolFlags.Class) == true
        val ifaceOk = flagsByName["I"]?.hasAny(SymbolFlags.Interface) == true
        val aliasOk = flagsByName["T"]?.hasAny(SymbolFlags.TypeAlias) == true
        val enumOk = flagsByName["E"]?.hasAny(SymbolFlags.RegularEnum) == true
        val constEnumOk = flagsByName["CE"]?.hasAny(SymbolFlags.ConstEnum) == true
        have(paramOk && varOk && letOk && constOk && fnOk && classOk && ifaceOk && aliasOk && enumOk && constEnumOk)
        // Sharp B83.5 signal: none of these leak into the main binder's tables.
        val localsHasBodyDecl = listOf("a", "v", "l", "g", "K", "I", "T", "E", "CE").any { it in result.locals }
        have(!localsHasBodyDecl)
        // Declarations point at the AST decl nodes (consumable by INV.2(d) pilots).
        val classDeclIsAst = scope.symbols["K"]?.declarations?.singleOrNull() is ClassDeclaration
        have(classDeclIsAst)
    }

    @Test
    fun `nested-block var hoists into the function scope while block-scoped declarations bind into their blocks`() {
        val result = bind(
            """
            function f(cond: boolean) {
              if (cond) {
                var hoisted = 1;
                let blockLet = 2;
                class BlockClass {}
                function blockFn() {}
              }
              for (var i = 0; i < 1; i++) {}
              for (let j = 0; j < 1; j++) {}
              try {} catch (err) { var inCatch = 1; }
            }
            """
        )
        val scope = result.functionScope("f")
        val hoistedOk = scope.symbols["hoisted"]?.flags?.hasAny(SymbolFlags.FunctionScopedVariable) == true
        val forVarOk = scope.symbols["i"]?.flags?.hasAny(SymbolFlags.FunctionScopedVariable) == true
        val catchVarOk = scope.symbols["inCatch"]?.flags?.hasAny(SymbolFlags.FunctionScopedVariable) == true
        have(hoistedOk && forVarOk && catchVarOk)
        // Phase (ii): block-scoped declarations bind into their OWN containers,
        // never leaking into the function scope.
        val blockScopedNames = listOf("blockLet", "BlockClass", "blockFn", "j", "err")
        val leakedIntoFn = blockScopedNames.filter { it in scope.symbols }
        assert(leakedIntoFn.isEmpty())
        val nodes = descendants(result.sourceFile)
        val ifBlock = nodes.filterIsInstance<IfStatement>().single().thenStatement
        val ifScope = assertNotNull(result.scopeOf(ifBlock), "if-body block must own a scope")
        assert(ifScope.symbols.keys == setOf("blockLet", "BlockClass", "blockFn"))
        val letIsBlockScoped = ifScope.symbols["blockLet"]?.flags?.hasAny(SymbolFlags.BlockScopedVariable) == true
        val fnIsFunction = ifScope.symbols["blockFn"]?.flags?.hasAny(SymbolFlags.Function) == true
        have(letIsBlockScoped && fnIsFunction)
        val ifScopeChainsToFn = ifScope.parent === scope
        have(ifScopeChainsToFn)
        // For-header: `var i` hoisted to the fn scope, its FOR scope stays empty;
        // `let j` binds into ITS for scope.
        val forScopes = nodes.filterIsInstance<ForStatement>().map { assertNotNull(result.scopeOf(it)) }
        assertEquals(2, forScopes.size)
        assertEquals(emptySet<String>(), forScopes[0].symbols.keys)
        assertEquals(setOf("j"), forScopes[1].symbols.keys)
        // The catch variable binds into the catch scope (block-scoped).
        val catchScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<CatchClause>().single()))
        assertEquals(setOf("err"), catchScope.symbols.keys)
        val errIsBlockScoped = catchScope.symbols["err"]?.flags?.hasAny(SymbolFlags.BlockScopedVariable) == true
        have(errIsBlockScoped)
    }

    @Test
    fun `case clauses bind into the switch scope and nested blocks chain`() {
        val result = bind(
            """
            function f(x: number) {
              switch (x) {
                case 1:
                  let caseLet = 1;
                  function caseFn() {}
                  break;
                default:
                  const caseConst = 2;
              }
              {
                let outer = 1;
                {
                  let inner = 2;
                }
              }
            }
            """
        )
        val fnScope = result.functionScope("f")
        val nodes = descendants(result.sourceFile)
        val switchScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<SwitchStatement>().single()))
        assertEquals(setOf("caseLet", "caseFn", "caseConst"), switchScope.symbols.keys)
        val switchChainsToFn = switchScope.parent === fnScope
        have(switchChainsToFn)
        // Nested bare blocks: each owns a scope, chained inner → outer → fn.
        val fnBody = nodes.filterIsInstance<FunctionDeclaration>().first { it.name?.text == "f" }.body
        val bareBlocks = nodes.filterIsInstance<Block>().filter { it !== fnBody && result.scopeOf(it) != null }
        val outerBlock = bareBlocks.first { result.scopeOf(it)!!.symbols.containsKey("outer") }
        val innerBlock = bareBlocks.first { result.scopeOf(it)!!.symbols.containsKey("inner") }
        val innerChainsThroughOuter = result.scopeOf(innerBlock)!!.parent === result.scopeOf(outerBlock)
        val outerChainsToFn = result.scopeOf(outerBlock)!!.parent === fnScope
        have(innerChainsThroughOuter && outerChainsToFn)
    }

    @Test
    fun `negative control - function body blocks and module blocks own no separate scope`() {
        val result = bind(
            """
            namespace N {
              export function f() { let x = 1; }
            }
            """
        )
        val nodes = descendants(result.sourceFile)
        val fn = nodes.filterIsInstance<FunctionDeclaration>().single()
        val bodyScopeIsNull = result.scopeOf(assertNotNull(fn.body)) == null
        have(bodyScopeIsNull)
        val moduleBlockScopeIsNull = result.scopeOf(nodes.filterIsInstance<ModuleBlock>().single()) == null
        have(moduleBlockScopeIsNull)
        // x binds into the FUNCTION scope (the body block shares it).
        val xInFnScope = "x" in result.functionScope("f").symbols
        have(xInFnScope)
    }

    @Test
    fun `class scopes hold type params and the class expression self-name with members chaining through`() {
        val result = bind(
            """
            class C<T> {
              m(x: T) { let mv = x; }
            }
            const E = class Named<U> { n() { return Named; } };
            """
        )
        val nodes = descendants(result.sourceFile)
        val classScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<ClassDeclaration>().single()))
        assertEquals(setOf("T"), classScope.symbols.keys)
        val classChainsToRoot = classScope.parent === result.scopeOf(result.sourceFile)
        have(classChainsToRoot)
        val methodM = nodes.filterIsInstance<MethodDeclaration>().first { (it.name as? Identifier)?.text == "m" }
        val mChainsThroughClass = assertNotNull(result.scopeOf(methodM)).parent === classScope
        have(mChainsThroughClass)
        val exprScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<ClassExpression>().single()))
        assertEquals(setOf("Named", "U"), exprScope.symbols.keys)
        val selfIsClass = exprScope.symbols["Named"]?.flags?.hasAny(SymbolFlags.Class) == true
        val uIsTypeParam = exprScope.symbols["U"]?.flags?.hasAny(SymbolFlags.TypeParameter) == true
        have(selfIsClass && uIsTypeParam)
        val namedLeaks = "Named" in result.locals || "Named" in assertNotNull(result.scopeOf(result.sourceFile)).symbols
        have(!namedLeaks)
    }

    @Test
    fun `interface and type alias scopes hold their type params`() {
        val result = bind(
            """
            interface I<T extends object> { p: T }
            type A<K, V = K> = Map<K, V>;
            """
        )
        val nodes = descendants(result.sourceFile)
        val ifaceScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<InterfaceDeclaration>().single()))
        assertEquals(setOf("T"), ifaceScope.symbols.keys)
        val aliasScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<TypeAliasDeclaration>().single()))
        assertEquals(setOf("K", "V"), aliasScope.symbols.keys)
        val allTypeParams = (ifaceScope.symbols.values + aliasScope.symbols.values)
            .all { it.flags.hasAny(SymbolFlags.TypeParameter) }
        have(allTypeParams)
    }

    @Test
    fun `enum scopes expose member names - aliased for main-bound enums and scope-space for nested ones`() {
        val result = bind(
            """
            enum Top { A = 1, B = A }
            function f() {
              enum Nested { X = 1, Y = X }
            }
            """
        )
        val nodes = descendants(result.sourceFile)
        val enums = nodes.filterIsInstance<EnumDeclaration>()
        val topDecl = enums.first { it.name.text == "Top" }
        val topScope = result.scopeOf(topDecl)
        assert(topScope != null)
        // Main-bound enum: the scope ALIASES the main symbol's exports (identity).
        val topAliasesMainExports = topScope.existing === assertNotNull(result.locals["Top"]).exports
        have(topAliasesMainExports)
        assert(topScope.symbols.keys == emptySet<String>())
        // Nested enum: scope-space members, published on the scope symbol's exports.
        val nestedDecl = enums.first { it.name.text == "Nested" }
        val nestedScope = result.scopeOf(nestedDecl)
        assert(nestedScope != null)
        assert(nestedScope.symbols.keys == setOf("X", "Y"))
        val fnScope = result.functionScope("f")
        val nestedSymbol = fnScope.symbols["Nested"]
        assert(nestedSymbol != null)
        val nestedIdIsScopeSpace = nestedSymbol.id <= -2
        have(nestedIdIsScopeSpace)
        val nestedExportKeys: Set<String> = assertNotNull(nestedSymbol.exports).keys
        assert(nestedExportKeys == setOf("X", "Y"))
        val memberParentIsEnum = nestedScope.symbols["X"]?.parent === nestedSymbol
        have(memberParentIsEnum)
        val membersAreScopeSpace = nestedScope.symbols.values.all { it.id <= -2 }
        have(membersAreScopeSpace)
    }

    @Test
    fun `catch destructuring and for-of headers bind block-scoped into their own scopes`() {
        val result = bind(
            """
            function f(pairs: [string, number][]) {
              try {} catch ({ message, stack: [s] }) {}
              for (const [k, v] of pairs) { let bodyLocal = k; }
            }
            """
        )
        val nodes = descendants(result.sourceFile)
        val catchScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<CatchClause>().single()))
        assertEquals(setOf("message", "s"), catchScope.symbols.keys)
        val forOf = nodes.filterIsInstance<ForOfStatement>().single()
        val forScope = assertNotNull(result.scopeOf(forOf))
        assert(forScope.symbols.keys == setOf("k", "v"))
        // The loop BODY block owns a child scope under the for scope.
        val bodyScope = assertNotNull(result.scopeOf(forOf.statement))
        assert(bodyScope.symbols.keys == setOf("bodyLocal"))
        val bodyChainsThroughFor = bodyScope.parent === forScope
        have(bodyChainsThroughFor)
    }

    @Test
    fun `file-level block-nested var binds into the root scope without touching the main binder locals`() {
        val result = bind(
            """
            var top = 1;
            if (top) {
              var nested = 2;
              var top = 3;
            }
            """
        )
        val root = assertNotNull(result.scopeOf(result.sourceFile), "missing root scope")
        val aliasesLocals = root.existing === result.locals
        have(aliasesLocals)
        val nestedBound = root.symbols["nested"]?.flags?.hasAny(SymbolFlags.FunctionScopedVariable) == true
        have(nestedBound)
        // `top` already has a file-level binding — the existing symbol stands untouched:
        // no scope-space duplicate, no declaration appended to the main binder's symbol.
        assert(root.symbols["top"] == null)
        val topDeclCount = result.locals["top"]?.declarations?.size
        assert(topDeclCount == 1)
        val localsHasNested = "nested" in result.locals
        have(!localsHasNested)
    }

    @Test
    fun `parameters bind including binding patterns and rest while the this parameter stays out`() {
        val result = bind(
            """
            function f(this: Window, a: number, { b, c: [d] = [1] }: any, ...rest: string[]) {}
            """
        )
        val scope = result.functionScope("f")
        val names = scope.symbols.keys
        assert(names == setOf("a", "b", "d", "rest"))
        val allParamsFunctionScoped = scope.symbols.values.all { it.flags.hasAny(SymbolFlags.FunctionScopedVariable) }
        have(allParamsFunctionScoped)
    }

    @Test
    fun `type parameters bind with the TypeParameter flag`() {
        val result = bind(
            """
            function f<T extends object, U = T>(x: T): U { return x as any; }
            """
        )
        val scope = result.functionScope("f")
        val tOk = scope.symbols["T"]?.flags?.hasAny(SymbolFlags.TypeParameter) == true
        val uOk = scope.symbols["U"]?.flags?.hasAny(SymbolFlags.TypeParameter) == true
        have(tOk && uOk)
        val tHasNoValueDecl = scope.symbols["T"]?.valueDeclaration == null
        have(tHasNoValueDecl)
    }

    @Test
    fun `scope symbols use the negative id space and binding consumes zero global symbol ids`() {
        // Identical top-level shape; only one variant has function-body declarations.
        // If lexical binding minted global-sequence symbols, the deltas would differ
        // — the sharp signal for the ~350-test id-reshuffle hazard.
        val withBodies = """
            function f(a: number) { var v = 1; let l = 2; function g() {} class K {} }
            var top = 1;
        """
        val withoutBodies = """
            function f(a: number) { }
            var top = 1;
        """
        val probeBefore = Symbol(SymbolFlags.None, "probe").id
        val rich = bind(withBodies)
        val probeMid = Symbol(SymbolFlags.None, "probe").id
        bind(withoutBodies)
        val probeAfter = Symbol(SymbolFlags.None, "probe").id
        assert(probeMid - probeBefore == probeAfter - probeMid)
        val scope = rich.functionScope("f")
        val allScopeIdsNegative = scope.symbols.values.all { it.id <= -2 }
        have(allScopeIdsNegative)
        val allLocalIdsPositive = rich.locals.values.all { it.id >= 1 }
        have(allLocalIdsPositive)
    }

    @Test
    fun `namespace scopes chain per dotted segment and alias the merged exports`() {
        val result = bind(
            """
            namespace A.B {
              export function f() { var v = 1; }
              if (true) { var hoistedInNs = 2; }
            }
            """
        )
        val moduleDecl = result.sourceFile.statements.filterIsInstance<ModuleDeclaration>().single()
        val inner = assertNotNull(result.scopeOf(moduleDecl), "missing namespace scope")
        val symbolA = result.locals["A"]
        assert(symbolA != null)
        val symbolB = symbolA.exports?.get("B")
        assert(symbolB != null)
        val innerAliasesB = inner.existing === symbolB.exports
        have(innerAliasesB)
        val outer = inner.parent
        assert(outer != null)
        val outerAliasesA = outer.existing === symbolA.exports
        have(outerAliasesA)
        val outerParentIsRoot = outer.parent === result.scopeOf(result.sourceFile)
        have(outerParentIsRoot)
        // f is the main binder's (in B.exports) — not re-bound; the block-hoisted var is ours.
        assert(inner.symbols["f"] == null)
        val hoistedOk = inner.symbols["hoistedInNs"]?.flags?.hasAny(SymbolFlags.FunctionScopedVariable) == true
        have(hoistedOk)
        // The function scope chains to the innermost namespace level.
        val fnScope = result.functionScope("f")
        val fnParentIsInner = fnScope.parent === inner
        have(fnParentIsInner)
        val vOk = fnScope.symbols["v"]?.flags?.hasAny(SymbolFlags.FunctionScopedVariable) == true
        have(vOk)
    }

    @Test
    fun `function expressions arrows and object-literal methods get their own scopes`() {
        val result = bind(
            """
            const g = function named(x: number) { return named; };
            const h = (y: number) => { let w = y; };
            const o = { m(z: number) { var mv = z; } };
            """
        )
        val nodes = descendants(result.sourceFile)
        val fnExpr = nodes.filterIsInstance<FunctionExpression>().single()
        val fnScope = assertNotNull(result.scopeOf(fnExpr))
        // The self-name is visible only inside the expression's own body.
        val selfNameOk = fnScope.symbols["named"]?.flags?.hasAny(SymbolFlags.Function) == true
        val xOk = fnScope.symbols["x"]?.flags?.hasAny(SymbolFlags.FunctionScopedVariable) == true
        have(selfNameOk && xOk)
        val namedLeaks = "named" in result.locals
        have(!namedLeaks)
        val arrow = nodes.filterIsInstance<ArrowFunction>().single()
        val arrowScope = assertNotNull(result.scopeOf(arrow))
        assert(arrowScope.symbols.keys == setOf("y", "w"))
        val method = nodes.filterIsInstance<MethodDeclaration>().single()
        val methodScope = assertNotNull(result.scopeOf(method))
        assertEquals(setOf("z", "mv"), methodScope.symbols.keys)
        // All three chain to the file root.
        val root = result.scopeOf(result.sourceFile)
        val allChainToRoot = fnScope.parent === root && arrowScope.parent === root && methodScope.parent === root
        have(allChainToRoot)
    }

    @Test
    fun `class member bodies get scopes constructor accessors static block and generic method`() {
        val result = bind(
            """
            class C {
              constructor(pub: number) { var cv = 1; }
              get g() { var gv = 1; return gv; }
              set g(v: number) {}
              static { var sv = 1; }
              m<T>(x: T) { let mv = x; }
            }
            """
        )
        val nodes = descendants(result.sourceFile)
        val ctorScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<Constructor>().single()))
        assertEquals(setOf("pub", "cv"), ctorScope.symbols.keys)
        val getScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<GetAccessor>().single()))
        assertEquals(setOf("gv"), getScope.symbols.keys)
        val setScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<SetAccessor>().single()))
        assertEquals(setOf("v"), setScope.symbols.keys)
        val staticScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<ClassStaticBlockDeclaration>().single()))
        assertEquals(setOf("sv"), staticScope.symbols.keys)
        val methodScope = assertNotNull(result.scopeOf(nodes.filterIsInstance<MethodDeclaration>().single()))
        assertEquals(setOf("T", "x", "mv"), methodScope.symbols.keys)
    }

    @Test
    fun `a parameter and a same-named body var merge into one symbol with both declarations`() {
        val result = bind(
            """
            function f(x: number) { var x = 1; }
            """
        )
        val scope = result.functionScope("f")
        val sym = scope.symbols["x"]
        assert(sym != null)
        assert(sym.declarations.size == 2)
        val firstIsParam = sym.declarations.first() is Parameter
        have(firstIsParam)
        val valueDeclIsParam = sym.valueDeclaration is Parameter
        have(valueDeclIsParam)
    }

    @Test
    fun `nested function overloads merge into one symbol`() {
        val result = bind(
            """
            function outer() {
              function g(a: number): void;
              function g(a: any) {}
            }
            """
        )
        val scope = result.functionScope("outer")
        val sym = scope.symbols["g"]
        assert(sym != null)
        assert(sym.declarations.size == 2)
    }

    @Test
    fun `a 30k-term binary chain binds on a plain thread without overflow`() {
        // No runWithDeepStack wrapper here — pins the ITERATIVE lexical walk
        // (a recursive one would overflow exactly where the INV.1 crawl runs).
        val terms = 30_000
        val source = "function f() { var r = " + "1 + ".repeat(terms - 1) + "1; }\n"
        val result = bind(source)
        val scope = result.functionScope("f")
        val rBound = "r" in scope.symbols
        have(rBound)
    }

    @Test
    fun `negative control - an unindexed hand-built tree yields no lexical scopes but binds locals normally`() {
        val statement = VariableStatement(
            declarationList = VariableDeclarationList(
                declarations = listOf(VariableDeclaration(name = Identifier(text = "x"))),
                flags = SyntaxKind.VarKeyword,
            ),
        )
        val sourceFile = SourceFile(fileName = "hand.ts", statements = listOf(statement), text = "")
        assertEquals(0, sourceFile.nodeCount, "hand-built tree must be unindexed")
        val result = Binder(CompilerOptions()).bind(sourceFile)
        assert(result.lexicalScopes.isEmpty())
        val xBound = "x" in result.locals
        have(xBound)
    }

    @Test
    fun `the rich fixture binds without crashing and spot-checks hold`() {
        val result = bind(INV2_RICH_FIXTURE, "rich.ts")
        val root = assertNotNull(result.scopeOf(result.sourceFile))
        val rootAliasesLocals = root.existing === result.locals
        have(rootAliasesLocals)
        val gen = descendants(result.sourceFile).filterIsInstance<MethodDeclaration>()
            .first { (it.name as? Identifier)?.text == "gen" }
        val genScope = assertNotNull(result.scopeOf(gen))
        val genParamNames = listOf("a", "d1", "d2", "e1", "e2")
        val genParamsBound = genParamNames.all { it in genScope.symbols }
        have(genParamsBound)
        val fnScope = result.functionScope("fn")
        // fn<T>(a, b, ...rest) with body locals o/arr/t/... and nested fn `inner`.
        val fnNames = listOf("T", "a", "b", "rest", "o", "arr", "inner")
        val fnNamesBound = fnNames.all { it in fnScope.symbols }
        have(fnNamesBound)
    }
}

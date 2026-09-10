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
import org.intellij.lang.annotations.Language
import kotlin.test.Test

/**
 * (INV.0) step 9 — the four AXES of [LexicalScopeResolver.symbolAt], pinned directly
 * rather than through a compile, because that is the level the class is a function at:
 * given the tables and a parent chain it is pure, so every pin here is a value.
 *
 * WHY THE AXES ARE THE SUBJECT. The ascent had been hand-copied five times and the
 * copies had DRIFTED on exactly these four things — which table, start at the node or
 * its parent, which `SymbolFlags`, hop-capped or not. Consolidating them is inert only
 * if each axis still behaves as the caller that needs it expects, and "the corpus is
 * green" cannot say that: three of the five callers are name-gated, so a wrong axis
 * shows up as a name resolving to an OUTER binding, which CLAUDE.md records as silent
 * in every diagnostic channel here.
 *
 * WHAT B83.5 ACTUALLY IS, measured for this round and NOT what its name suggests:
 * `Binder` recurses into statements from two places only — a `SourceFile`'s own list
 * and a `ModuleBlock`'s — so a declaration at the very TOP of a function body, with no
 * block nesting at all, is as unbound as one inside an `if`. The fixtures below use
 * that shape deliberately.
 *
 * THE ABLATIONS, one per pin, each inside `symbolAt`:
 *
 *  1. `an inner declaration shadows a same named outer one` — make the ascent start at
 *     the SourceFile instead (`var cur: Node? = node` → `= owningSourceFile(node)`).
 *     Only this pin has two levels declaring one name.
 *  2. `the flag filter does not stop the ascent at a wrong kinded hit` — change
 *     `if (sym != null && (flags == null || sym.flags.hasAny(flags))) return sym` to
 *     `if (sym != null) return if (flags == null || sym.flags.hasAny(flags)) sym else null`.
 *     Only this pin puts a differently-kinded same-named binding INSIDE a matching one.
 *  3. `startAtParent skips the scope the node itself opens` — drop the `startAtParent`
 *     branch (`if (startAtParent) (node as NodeBase).parent else node` → `node`). Only
 *     this pin asks from a node that IS a scope owner.
 *  4. `an uncapped walk reaches a deeply nested reference` — replace `hopCap == 0 ||`
 *     with `false ||`, i.e. honour the cap always, and pass a cap of 1 from the pin's
 *     own call. Only this pin varies the cap.
 *  5. `only the lexical bindings are visible and never the aliased existing table` —
 *     read `existing` as a fallback (`scopes[id]?.symbols?.get(name)` →
 *     `scopes[id]?.let { it.symbols[name] ?: it.existing?.get(name) }`). This is round
 *     748's forbidden read and only this pin has a conventionally-bound name to catch
 *     it with.
 */
class LexicalScopeResolverTest {

    private fun tablesOf(
        @Language("typescript") source: String,
        fileName: String = "t.ts",
    ): Pair<SourceFile, LexicalScopeResolver> {
        val sourceFile = Parser(source.trimIndent(), fileName).parse()
        val result = Binder(CompilerOptions()).bind(sourceFile)
        return sourceFile to LexicalScopeResolver(mapOf(fileName to result))
    }

    /** Every descendant of [root] in preorder, so a pin can name its own reference node. */
    private fun nodes(root: Node): List<Node> {
        val out = ArrayList<Node>()
        val stack = ArrayList<Node>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            out.add(node)
            val kids = ArrayList<Node>()
            forEachChild(node) { kids.add(it) }
            for (i in kids.indices.reversed()) stack.add(kids[i])
        }
        return out
    }

    private fun firstIdentifier(root: Node, text: String): Node =
        nodes(root).first { it is Identifier && it.text == text && (it as NodeBase).nodeId >= 0 }

    /**
     * The B83.5 shape at its sharpest: `Inner` is declared at the top of BOTH function
     * bodies, so the ascent must answer the one whose body encloses the reference. A
     * resolver that starts from the file root answers neither (the file root binds
     * nothing named `Inner`), which is why the pin asserts the DECLARING function.
     */
    @Test
    fun `an inner declaration shadows a same named outer one`() {
        val (file, resolver) = tablesOf(
            """
            function outer() {
                interface Inner { o: number }
                function nested() {
                    interface Inner { n: string }
                    const useInner = 1
                }
                const useOuter = 1
            }
            """,
        )
        val scopes = resolver.scopesOfOwningFile(file)!!
        val fromNested = resolver.symbolAt(firstIdentifier(file, "useInner"), "Inner", scopes)
        val fromOuter = resolver.symbolAt(firstIdentifier(file, "useOuter"), "Inner", scopes)
        val nestedMember = memberNameOf(fromNested)
        val outerMember = memberNameOf(fromOuter)
        assert(nestedMember == "n")
        assert(outerMember == "o")
    }

    /** The single property signature name of an interface symbol, or null. */
    private fun memberNameOf(symbol: Symbol?): String? {
        val decl = symbol?.declarations?.firstOrNull() as? InterfaceDeclaration ?: return null
        val member = decl.members.firstOrNull() as? PropertyDeclaration ?: return null
        return (member.name as? Identifier)?.text
    }

    /**
     * A `const` named `Thing` sits INSIDE the function whose body declares
     * `interface Thing`. An Enum/TypeAlias-filtered caller — three of the five — must
     * walk PAST the variable and keep going, not stop and answer null: stopping is how
     * a name-gated consult silently falls back to the conventional tables and resolves
     * a shadowed outer binding.
     */
    @Test
    fun `the flag filter does not stop the ascent at a wrong kinded hit`() {
        val (file, resolver) = tablesOf(
            """
            function holder() {
                type Thing = { t: number }
                function inner() {
                    const Thing = 1
                    const useThing = 2
                }
            }
            """,
        )
        val scopes = resolver.scopesOfOwningFile(file)!!
        val at = firstIdentifier(file, "useThing")
        val alias = resolver.symbolAt(at, "Thing", scopes, flags = SymbolFlags.TypeAlias)
        val unfiltered = resolver.symbolAt(at, "Thing", scopes)
        val aliasIsAlias = alias != null && alias.flags.hasAny(SymbolFlags.TypeAlias)
        val unfilteredIsVariable = unfiltered != null && unfiltered.flags.hasAny(SymbolFlags.Variable)
        assert(aliasIsAlias)
        assert(unfilteredIsVariable)
    }

    /**
     * Asked from the FUNCTION NODE itself, `startAtParent = false` sees the scope that
     * node owns and `true` does not. The caller that needs `true` holds a node whose
     * own scope would wrongly claim the name ((CHK.85)(b)'s discriminant carry and the
     * INV.2(d) consult both do).
     */
    @Test
    fun `startAtParent skips the scope the node itself opens`() {
        val (file, resolver) = tablesOf(
            """
            function owner() {
                interface Claimed { c: number }
            }
            """,
        )
        val scopes = resolver.scopesOfOwningFile(file)!!
        val fn = nodes(file).first { it is FunctionDeclaration }
        val fromSelf = resolver.symbolAt(fn, "Claimed", scopes)
        val fromParent = resolver.symbolAt(fn, "Claimed", scopes, startAtParent = true)
        assert(memberNameOf(fromSelf) == "c")
        assert(fromParent == null)
    }

    /**
     * The two callers that predate the cap pass `hopCap = 0`, and they need it: a
     * reference nested several scopes below its declaration is more than one hop away.
     * A cap of 1 must miss the same name the uncapped walk finds.
     */
    @Test
    fun `an uncapped walk reaches a deeply nested reference`() {
        val (file, resolver) = tablesOf(
            """
            function top() {
                interface Deep { d: number }
                function a() {
                    function b() {
                        const useDeep = 1
                    }
                }
            }
            """,
        )
        val scopes = resolver.scopesOfOwningFile(file)!!
        val at = firstIdentifier(file, "useDeep")
        val uncapped = resolver.symbolAt(at, "Deep", scopes, hopCap = 0)
        val capped = resolver.symbolAt(at, "Deep", scopes, hopCap = 1)
        assert(memberNameOf(uncapped) == "d")
        assert(capped == null)
    }

    /**
     * Round 748's rule, as a value. `Bound` is a FILE-LEVEL interface, so the main
     * binder bound it and `declareLexical` refused it — it is absent from `symbols`
     * and present in the SourceFile scope's aliased `existing`. The ascent must answer
     * null for it: reading `existing` would put every INV.3 name back in play, and the
     * whole soundness argument for consulting the scope space is that a hit there
     * cannot be a name the conventional tables already have.
     */
    @Test
    fun `only the lexical bindings are visible and never the aliased existing table`() {
        val (file, resolver) = tablesOf(
            """
            interface Bound { b: number }
            function holder() {
                interface Free { f: number }
                const use = 1
            }
            """,
        )
        val scopes = resolver.scopesOfOwningFile(file)!!
        val at = firstIdentifier(file, "use")
        val free = resolver.symbolAt(at, "Free", scopes)
        val bound = resolver.symbolAt(at, "Bound", scopes)
        val rootScope = scopes.values.first { it.owner is SourceFile }
        val boundIsInExisting = rootScope.existing?.get("Bound") != null
        assert(memberNameOf(free) == "f")
        assert(bound == null)
        assert(boundIsInExisting)
    }
}

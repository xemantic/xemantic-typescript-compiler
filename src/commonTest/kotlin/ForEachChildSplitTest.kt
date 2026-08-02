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
 * (JIT.1)(a) round 803: `forEachChild` was split into three range-keyed functions
 * so it falls below HotSpot's 8,000-bytecode `HugeMethodLimit` and can be
 * JIT-compiled at all (`HugeMethodLimitTest` pins the sizes). The split moved
 * every arm VERBATIM, so what needs pinning here is what a range typo or a
 * mis-moved arm would break and no size check would notice: the ENUMERATION
 * ORDER, which is load-bearing (decorators are visited LAST, and
 * [indexSourceFile] turns this order into the dense preorder `nodeId` sequence
 * every later phase keys on), and the two SEAMS between the parts.
 *
 * `ForEachChildOracleTest` (jvmTest) already pins the child SET against the
 * data-class properties by reflection — it does not pin their order, and a
 * boundary typo in the split's continuation dispatch would send a whole range
 * to the loud `else` rather than drop a child.
 *
 * Children are compared as STRING labels, never as nodes: power-assert renders
 * every subexpression on failure and an AST subexpression dumps whole subtrees.
 */
class ForEachChildSplitTest {

    private fun label(node: Node): String =
        if (node is Identifier) "Identifier:${node.text}" else (node::class.simpleName ?: "?")

    private fun children(node: Node): List<String> {
        val out = ArrayList<String>()
        forEachChild(node) { out.add(label(it)) }
        return out
    }

    private fun id(text: String) = Identifier(text = text)
    private fun stringType() = KeywordTypeNode(kind = SyntaxKind.StringKeyword)
    private fun decorator(text: String) = Decorator(expression = id(text))

    // ── part 1: NodeKind.SOURCE_FILE .. NodeKind.COMMA_LIST_EXPRESSION ──

    @Test
    fun `part 1 - a call expression enumerates callee then type arguments then arguments`() {
        val call = CallExpression(
            expression = id("f"),
            typeArguments = listOf(TypeReference(typeName = id("T"))),
            arguments = listOf(id("a"), id("b")),
        )
        assert(children(call) == listOf("Identifier:f", "TypeReference", "Identifier:a", "Identifier:b"))
    }

    @Test
    fun `part 1 - a class declaration enumerates its decorators LAST`() {
        val decl = ClassDeclaration(
            name = id("C"),
            typeParameters = listOf(TypeParameter(name = id("T"))),
            heritageClauses = listOf(
                HeritageClause(
                    token = SyntaxKind.ExtendsKeyword,
                    types = listOf(ExpressionWithTypeArguments(expression = id("B"))),
                )
            ),
            members = listOf(PropertyDeclaration(name = id("p"))),
            decorators = listOf(decorator("dec")),
        )
        assert(
            children(decl) == listOf(
                "Identifier:C", "TypeParameter", "HeritageClause", "PropertyDeclaration", "Decorator",
            )
        )
    }

    // ── part 2: NodeKind.PROPERTY_DECLARATION .. NodeKind.KEYWORD_TYPE_NODE ──

    @Test
    fun `part 2 - a method declaration enumerates name type-params params type body then decorators`() {
        val method = MethodDeclaration(
            name = id("m"),
            typeParameters = listOf(TypeParameter(name = id("T"))),
            parameters = listOf(Parameter(name = id("p"))),
            type = stringType(),
            body = Block(statements = emptyList()),
            decorators = listOf(decorator("dec")),
        )
        assert(
            children(method) == listOf(
                "Identifier:m", "TypeParameter", "Parameter", "KeywordTypeNode", "Block", "Decorator",
            )
        )
    }

    @Test
    fun `part 2 - a mapped type enumerates its type parameter then nameType then value type`() {
        val mapped = MappedType(
            typeParameter = TypeParameter(name = id("K"), constraint = TypeReference(typeName = id("Keys"))),
            nameType = TypeReference(typeName = id("N")),
            type = stringType(),
        )
        assert(children(mapped) == listOf("TypeParameter", "TypeReference", "KeywordTypeNode"))
        // The MappedType gotcha: the CONSTRAINT is reached generically, through the
        // TypeParameter's own arm — which now lives in a different part.
        assert(children(mapped.typeParameter) == listOf("Identifier:K", "TypeReference"))
    }

    // ── part 3: NodeKind.PARAMETER .. NodeKind.JSX_FRAGMENT ──

    @Test
    fun `part 3 - a parameter enumerates name type initializer then decorators LAST`() {
        val parameter = Parameter(
            name = id("p"),
            type = stringType(),
            initializer = id("init"),
            decorators = listOf(decorator("dec")),
        )
        assert(
            children(parameter) == listOf(
                "Identifier:p", "KeywordTypeNode", "Identifier:init", "Decorator",
            )
        )
    }

    @Test
    fun `part 3 - JSX elements enumerate opening tag children then closing tag`() {
        val element = JsxElement(
            openingElement = JsxOpeningElement(tagName = id("div"), attributes = emptyList()),
            children = listOf(JsxText(text = "hi")),
            closingElement = JsxClosingElement(tagName = id("div")),
        )
        assert(children(element) == listOf("JsxOpeningElement", "JsxText", "JsxClosingElement"))
    }

    // ── the seams: the two kinds on each side of each continuation boundary ──

    @Test
    fun `the seam between parts 1 and 2 enumerates on both sides`() {
        // COMMA_LIST_EXPRESSION is the last kind of part 1, PROPERTY_DECLARATION the
        // first of part 2 — an off-by-one in the continuation dispatch sends one of
        // them to the loud `else` instead.
        assert(children(CommaListExpression(elements = listOf(id("a"), id("b")))) ==
            listOf("Identifier:a", "Identifier:b"))
        assert(
            children(PropertyDeclaration(name = id("p"), type = stringType(), initializer = id("v"))) ==
                listOf("Identifier:p", "KeywordTypeNode", "Identifier:v")
        )
    }

    @Test
    fun `the seam between parts 2 and 3 enumerates on both sides`() {
        // KEYWORD_TYPE_NODE is the last kind of part 2 (childless — so the pin is that
        // it does not THROW), PARAMETER the first of part 3.
        assert(children(stringType()).isEmpty())
        assert(children(Parameter(name = id("q"))) == listOf("Identifier:q"))
    }

    // ── all three parts are reached from the one entry point ──

    @Test
    fun `a parsed tree reaches every one of the three parts`() {
        val source = """
            @dec class C<T> extends B { p: string = "v"; m(@d q?: number): T { return this.p as any; } }
            type M<K extends string> = { [P in K]?: Array<K> };
            function f(a: number, ...rest: string[]) { for (const x of rest) { if (x) f(a); } }
        """.trimIndent()
        val sourceFile = Parser(source, "t.ts").parse()
        var visited = 0
        var lowKinds = 0
        var midKinds = 0
        var highKinds = 0
        val stack = ArrayList<Node>()
        stack.add(sourceFile)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            visited++
            val kind = (node as NodeBase).kindId
            when {
                kind < NodeKind.PROPERTY_DECLARATION -> lowKinds++
                kind < NodeKind.PARAMETER -> midKinds++
                else -> highKinds++
            }
            forEachChild(node) { stack.add(it) }
        }
        // Non-vacuity control: a real tree, not an empty walk (measured 66).
        assert(visited > 50)
        assert(lowKinds > 0)
        assert(midKinds > 0)
        assert(highKinds > 0)
        // The indexer's own preorder is a function of this enumeration: a dense id
        // per node, so the walk and the stamped count must agree.
        assert(visited == sourceFile.nodeCount)
    }
}

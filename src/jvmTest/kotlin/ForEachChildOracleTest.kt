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
import java.io.File
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * INV.2(a) reflection oracle: [forEachChild]'s hand-written child enumeration must
 * reach EXACTLY the nodes stored in each node class's primary-constructor
 * properties. The `when` in [forEachChild] is compiler-enforced exhaustive over the
 * sealed hierarchy (a NEW node CLASS cannot be missed), but a new/missed node-typed
 * PROPERTY on an existing class would silently exempt a whole subtree from
 * indexing/walking — this oracle pins that. JVM-only because it scans data-class
 * `componentN()` methods via Java reflection (base-class vars like
 * `nodeId`/`parent` have no componentN, so they are excluded by construction).
 *
 * Runs on the kind-dense fixtures shared with `Inv2NodeIndexTest`, on
 * parser-unreachable node classes constructed directly, and — when the bench
 * project is materialized — on every real tsc compiler source file.
 */
class ForEachChildOracleTest {

    private val componentMethodsCache = HashMap<Class<*>, List<Method>>()

    private fun componentMethods(cls: Class<*>): List<Method> =
        componentMethodsCache.getOrPut(cls) {
            cls.methods
                .filter { it.parameterCount == 0 && it.name.matches(Regex("component\\d+")) }
                .sortedBy { it.name.removePrefix("component").toInt() }
        }

    private fun collectNodes(value: Any?, out: MutableList<Node>) {
        when (value) {
            null -> {}
            is Node -> out.add(value)
            is Iterable<*> -> for (element in value) collectNodes(element, out)
            else -> {}
        }
    }

    /** The oracle's ground truth: every Node held in a primary-constructor property. */
    private fun reflectionChildren(node: Node): List<Node> {
        val out = ArrayList<Node>()
        for (method in componentMethods(node.javaClass)) collectNodes(method.invoke(node), out)
        return out
    }

    private fun identitySet(nodes: List<Node>): MutableSet<Node> {
        val set: MutableSet<Node> = Collections.newSetFromMap(IdentityHashMap())
        set.addAll(nodes)
        return set
    }

    /** All nodes reachable via REFLECTION — deliberately independent of [forEachChild],
     *  so a forEachChild gap cannot hide nodes from the oracle's universe. */
    private fun allNodesViaReflection(root: Node): List<Node> {
        val out = ArrayList<Node>()
        val stack = ArrayList<Node>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            out.add(node)
            val children = reflectionChildren(node)
            for (i in children.indices.reversed()) stack.add(children[i])
        }
        return out
    }

    private fun assertOracleAgrees(node: Node, label: String) {
        val expected = reflectionChildren(node)
        val actual = ArrayList<Node>()
        forEachChild(node) { actual.add(it) }
        val expectedSet = identitySet(expected)
        val actualSet = identitySet(actual)
        val missing = expected.filter { it !in actualSet }
        val extra = actual.filter { it !in expectedSet }
        // Plain fail(), NOT assert(): power-assert renders every subexpression's toString on
        // failure, and a Node/List<Node> subexpression dumps (or overflows on) whole subtrees.
        if (missing.isNotEmpty() || extra.isNotEmpty() || actual.size != expected.size) {
            fail(
                "$label: forEachChild(${node::class.simpleName} at pos ${node.pos}) disagrees with the " +
                    "data-class properties — MISSING ${missing.map { it::class.simpleName }} " +
                    "EXTRA ${extra.map { it::class.simpleName }} " +
                    "(visited ${actual.size}, properties hold ${expected.size})"
            )
        }
    }

    private fun assertOracleAgreesOnTree(root: Node, label: String): Int {
        val nodes = allNodesViaReflection(root)
        for (node in nodes) assertOracleAgrees(node, label)
        return nodes.size
    }

    @Test
    fun `forEachChild matches data-class node properties across the rich fixture`() {
        val checked = assertOracleAgreesOnTree(Parser(INV2_RICH_FIXTURE, "rich.ts").parse(), "rich.ts")
        assert(checked > 400)
    }

    @Test
    fun `forEachChild matches data-class node properties across the jsx fixture`() {
        val checked = assertOracleAgreesOnTree(Parser(INV2_JSX_FIXTURE, "t.tsx").parse(), "t.tsx")
        assert(checked > 30)
    }

    @Test
    fun `forEachChild matches on directly constructed parser-unreachable nodes`() {
        val ident = Identifier(text = "x")
        val stringType = KeywordTypeNode(kind = SyntaxKind.StringKeyword)
        // Kinds the parser never (or only via transforms) produces — see the
        // NamedTupleMember/OptionalType and CommaListExpression/RawStatement notes in CLAUDE.md.
        val synthetic: List<Node> = listOf(
            NamedTupleMember(name = ident, type = stringType, dotDotDotToken = true, questionToken = true),
            OptionalType(type = stringType),
            RestType(type = stringType),
            CommaListExpression(elements = listOf(ident, Identifier(text = "y"))),
            RawStatement(code = "__helper();"),
            NotEmittedStatement(),
            OmittedExpression(),
            SemicolonClassElement(),
            EmptyStatement(),
            DebuggerStatement(),
            ThisType(),
            NoSubstitutionTemplateLiteralNode(text = "t"),
            BigIntLiteralNode(text = "1n"),
            MetaProperty(keywordToken = SyntaxKind.ImportKeyword, name = Identifier(text = "meta")),
            ExternalModuleReference(expression = StringLiteralNode(text = "m")),
            JsxClosingElement(tagName = ident),
        )
        for (node in synthetic) assertOracleAgrees(node, "synthetic")
    }

    @Test
    fun `forEachChild matches across every real tsc compiler source when the bench project is present`() {
        val sourceDir = File("build/bench").listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("tsc-project-") }
            ?.resolve("src")
        if (sourceDir == null || !sourceDir.isDirectory) {
            println("ForEachChildOracleTest: bench project not materialized — skipping the real-source sweep")
            return
        }
        val files = sourceDir.walkTopDown().filter { it.isFile && it.extension == "ts" }.toList()
        assert(files.isNotEmpty())
        var checked = 0
        for (file in files) {
            checked += assertOracleAgreesOnTree(Parser(file.readText(), file.name).parse(), file.name)
        }
        assert(checked > 100_000)
    }

    @Test
    fun `indexing is dense preorder with parent chains on a real tsc source`() {
        val sourceDir = File("build/bench").listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("tsc-project-") }
            ?.resolve("src")
        val file = sourceDir?.walkTopDown()?.filter { it.isFile && it.extension == "ts" }
            ?.maxByOrNull { it.length() }
        if (file == null) {
            println("ForEachChildOracleTest: bench project not materialized — skipping the real-source index check")
            return
        }
        val sourceFile = Parser(file.readText(), file.name).parse()
        // Walk in the indexer's preorder (children reversed onto a stack) and assert each
        // visit's nodeId equals its preorder index — also proves no node instance appears
        // at two tree positions (a re-stamped duplicate would break the sequence).
        val stack = ArrayList<Node>()
        stack.add(sourceFile)
        var index = 0
        val buf = ArrayList<Node>()
        val collect: (Node) -> Unit = { buf.add(it) }
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            assertEquals(
                index, (node as NodeBase).nodeId,
                "${file.name}: ${node::class.simpleName} at pos ${node.pos} " +
                    "breaks the dense preorder id sequence"
            )
            index++
            buf.clear()
            forEachChild(node, collect)
            for (i in buf.indices.reversed()) stack.add(buf[i])
        }
        assertEquals(index, sourceFile.nodeCount, "${file.name}: nodeCount mismatch")
        assert(index > 10_000)
    }
}

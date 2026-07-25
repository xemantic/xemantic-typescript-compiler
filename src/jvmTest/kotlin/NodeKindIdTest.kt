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
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.fail

/**
 * M0.2 oracle: the stamped [NodeBase.kindId] must agree with the
 * compile-enforced bijection [nodeKindIdOf] on EVERY node instance — the
 * `init { kindId = ... }` stamps in Ast.kt are per-class hand-maintained and a
 * wrong/missing stamp would silently mis-dispatch (or crash) every tableswitch
 * consumer ([forEachChild], the checkSpine dispatchers). Also pins:
 * - id DENSITY (0..N-1, unique) — density is what buys the tableswitch;
 * - `copy()` re-stamping — Transformer-synthesized nodes are walked by
 *   [forEachChild], so the stamp MUST survive `copy()` (unlike nodeId/parent,
 *   which deliberately do not).
 */
class NodeKindIdTest {

    private fun assertStampsOnTree(root: Node, label: String): Int {
        var checked = 0
        val stack = ArrayList<Node>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            val stamped = (node as NodeBase).kindId
            val expected = nodeKindIdOf(node)
            // Plain fail(), NOT assert(): power-assert would toString whole node subtrees.
            if (stamped != expected) {
                fail(
                    "$label: ${node::class.simpleName} at pos ${node.pos} has stamped " +
                        "kindId=$stamped but nodeKindIdOf says $expected — the class's " +
                        "init { kindId = ... } stamp is missing or wrong"
                )
            }
            checked++
            forEachChild(node) { stack.add(it) }
        }
        return checked
    }

    @Test
    fun `NodeKind ids are dense and unique from 0`() {
        val values = NodeKind::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .map { it.also { f -> f.isAccessible = true }.getInt(null) }
        assert(values.isNotEmpty())
        val expected = (0 until values.size).toSet()
        val actual = values.toSet()
        assert(actual == expected)
    }

    @Test
    fun `stamped kindId equals nodeKindIdOf across the rich fixture`() {
        val checked = assertStampsOnTree(Parser(INV2_RICH_FIXTURE, "rich.ts").parse(), "rich.ts")
        assert(checked > 400)
    }

    @Test
    fun `stamped kindId equals nodeKindIdOf across the jsx fixture`() {
        val checked = assertStampsOnTree(Parser(INV2_JSX_FIXTURE, "t.tsx").parse(), "t.tsx")
        assert(checked > 30)
    }

    @Test
    fun `stamped kindId equals nodeKindIdOf on parser-unreachable nodes`() {
        val ident = Identifier(text = "x")
        val stringType = KeywordTypeNode(kind = SyntaxKind.StringKeyword)
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
        for (node in synthetic) assertStampsOnTree(node, "synthetic")
    }

    @Test
    fun `copy re-stamps kindId - the Transformer-synthesized-node invariant`() {
        val ident = Identifier(text = "x")
        val copied = ident.copy(text = "y")
        assert(copied.kindId == NodeKind.IDENTIFIER)
        val block = Block(statements = listOf(ExpressionStatement(expression = ident)))
        assert(block.copy(multiLine = false).kindId == NodeKind.BLOCK)
        // And forEachChild dispatches the copy correctly (would hit the loud else
        // if copy() lost the stamp).
        val visited = ArrayList<Node>()
        forEachChild(block.copy()) { visited.add(it) }
        assert(visited.size == 1)
    }

    @Test
    fun `stamped kindId equals nodeKindIdOf across every real tsc compiler source when present`() {
        val sourceDir = File("build/bench").listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("tsc-project-") }
            ?.resolve("src")
        if (sourceDir == null || !sourceDir.isDirectory) {
            println("NodeKindIdTest: bench project not materialized — skipping the real-source sweep")
            return
        }
        var checked = 0
        for (file in sourceDir.walkTopDown().filter { it.isFile && it.extension == "ts" }) {
            checked += assertStampsOnTree(Parser(file.readText(), file.name).parse(), file.name)
        }
        assert(checked > 100_000)
    }
}

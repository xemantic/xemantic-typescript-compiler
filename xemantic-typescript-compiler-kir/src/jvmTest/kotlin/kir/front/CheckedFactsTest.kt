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

package com.xemantic.typescript.compiler.kir.front

import com.xemantic.kotlin.test.assert
import com.xemantic.typescript.compiler.CallExpression
import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.forEachChild
import kotlin.test.Test

/**
 * The pins for the backend's half of the seam: that the facts collected DURING
 * the checker's walk are the walk's own answers, and that the routes around
 * `-core`'s measured gaps answer where the obvious route does not.
 *
 * Every one of these was found by running the checker rather than by reading
 * it, and each is a fact the lowering depends on — so a `-core` change that
 * moves one should redden here rather than in a corpus program's stdout.
 *
 * Assertions compute a `Boolean`/`String` local first wherever an AST node
 * would otherwise end up inside a power-assert expression: the diagram renders
 * every captured subexpression, and an AST data class renders its whole
 * subtree.
 */
class CheckedFactsTest {

    private val source = """
        function describe(x: string | number): string {
          if (typeof x === "string") {
            return "str:" + x;
          }
          return "num:";
        }
        class Counter {
          private value: number;
          constructor(start: number) { this.value = start; }
          increment(by: number): number { return this.value + by; }
        }
        const said = describe("a");
    """.trimIndent()

    private val checked = checkTypeScript("/p/facts.ts", source)

    private fun <T : Node> nodes(type: Class<T>): List<T> {
        val found = mutableListOf<T>()
        fun visit(node: Node) {
            if (type.isInstance(node)) found.add(type.cast(node))
            forEachChild(node) { visit(it) }
        }
        visit(checked.sourceFile)
        return found
    }

    /** The type the facts recorded at the `n`-th occurrence of an identifier. */
    private fun identifierTypeAt(text: String, occurrence: Int): String? {
        val matching = nodes(Identifier::class.java).filter { it.text == text }
        val node = matching.getOrNull(occurrence) ?: return null
        return checked.facts.typeOf(node as Expression)?.let { checked.facts.render(it) }
    }

    @Test
    fun `the fixture type-checks so every fact below is about a real program`() {
        val errorCount = checked.errors.size
        assert(errorCount == 0)
    }

    @Test
    fun `a guard-narrowed reference reports the narrowed type at its own position`() {
        // Occurrence 0 is the parameter's declaration name, 1 is the `typeof`
        // operand — still the declared union — and 2 is the use inside the
        // guarded branch, which is the one that must be narrowed. Asking the
        // same checker afterwards answers `any` here.
        assert(identifierTypeAt("x", 1) == "string | number")
        assert(identifierTypeAt("x", 2) == "string")
    }

    @Test
    fun `a parameter reports its declared type through the position's lexical chain`() {
        val parameters = nodes(Parameter::class.java)
            .associate { (it.name as Identifier).text to checked.facts.typeOf(it) }
        assert(parameters["x"]?.let { checked.facts.render(it) } == "string | number")
        assert(parameters["by"]?.let { checked.facts.render(it) } == "number")
    }

    @Test
    fun `overload selection is recorded at the call site and names the declaration`() {
        val call = nodes(CallExpression::class.java)
            .first { (it.expression as? Identifier)?.text == "describe" }
        val fact = checked.facts.callAt(call)
        assert(fact != null)
        val declaration = nodes(FunctionDeclaration::class.java).single()
        // Node IDENTITY: the selected signature points at the very declaration
        // the checker walked, not at a structurally equal copy.
        val selectedIsDescribe = fact.signature?.declaration === declaration
        assert(selectedIsDescribe)
    }

    @Test
    fun `a library call records the receiver type and member that name it`() {
        val checkedLog = checkTypeScript("/p/log.ts", "console.log(1);")
        val call = mutableListOf<CallExpression>()
        fun visit(node: Node) {
            if (node is CallExpression) call.add(node)
            forEachChild(node) { visit(it) }
        }
        visit(checkedLog.sourceFile)
        val fact = checkedLog.facts.callAt(call.first())
        assert(fact != null)
        assert(fact.receiverTypeText == "Console")
        assert(fact.memberName == "log")
    }

    /**
     * The route around a measured `-core` gap, pinned as such.
     *
     * `this` types as `any` here, so `this.value` types as `any` and resolves to
     * no member at all — which is why the property's type is taken on the
     * CLASS's own type instead. If `-core` ever types `this` properly this pin
     * still passes; if the class-type route breaks, this reddens before any
     * corpus program's arithmetic silently moves to the runtime helper.
     */
    @Test
    fun `a class property reports its type through the class type and not the access`() {
        val property = nodes(PropertyDeclaration::class.java).single()
        val type = checked.facts.memberTypeOf(property)?.let { checked.facts.render(it) }
        assert(type == "number")
    }

    @Test
    fun `a method reports its declared return type through the class type`() {
        val method = nodes(MethodDeclaration::class.java).single()
        val returnType = checked.facts.signatureOf(method)?.resolvedReturnType
            ?.let { checked.facts.render(it) }
        assert(returnType == "number")
    }

    @Test
    fun `a class declaration is reachable so the lowering can key its members`() {
        val classes = nodes(ClassDeclaration::class.java).map { it.name?.text }
        assert(classes == listOf("Counter"))
    }

}

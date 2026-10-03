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

/**
 * (CHK.220) TS2536 "Type 'K' cannot be used to index type 'T'." on an ELEMENT ACCESS whose index
 * is a TYPE PARAMETER — tsgo `checkIndexedAccessIndexType` (checker.go:8197), reached because
 * `getIndexedAccessType` defers `O[K]` to an indexed-access type for every generic index, and the
 * check then asks whether every constituent of `K` is assignable to `keyof O`.
 *
 * This checker has no deferred indexed-access type and no generic `keyof`, so the rule is decided
 * only where the answer needs neither — the verdict is three-valued and anything undecidable is
 * SILENT (a false positive on legal code outranks a missing row):
 *
 *  - `K` UNCONSTRAINED: its constraint is `unknown`, which is assignable to no `keyof O` — rejected
 *    for every receiver that is not `any` / `unknown` (tsgo: `t[k]` and the write `t[k] = v`).
 *  - `K`'s DECLARED constraint is SYNTACTICALLY concrete — `string`, `number`, string / number
 *    literal types and unions of them, nothing that names a type (an alias may hide `keyof T`; this
 *    checker degrades `Extract<keyof T, string>` to `string`, which would invent a row) — and the
 *    receiver is either a type parameter with NO constraint (`keyof unknown` is `never`) or an
 *    anonymous object type written as a type literal (its keys are its member names plus what its
 *    `string` / `number` index signatures admit), or a union of those.
 *
 * Everything else — a constrained receiver type parameter, a named interface (member resolution
 * of merged / inherited members is incomplete), a mapped / `Record` receiver, `K extends keyof T`,
 * a symbol-typed key — is left silent. Measured against tsgo 7.0.2 in `build/bench/p18276-agent/m220`.
 */
internal class GenericIndexAccess(private val checker: Checker) {

    fun check(expr: ElementAccessExpression, source: String, fileName: String) {
        val arg = checker.unwrapParensExpr(expr.argumentExpression)
        if (arg is StringLiteralNode || arg is NumericLiteralNode) return
        val indexType = checker.getTypeOfExpression(arg) as? Type.TypeParam ?: return
        val indexName = indexType.symbol?.name ?: return
        val indexDecl = typeParameterDeclaration(expr, indexName) ?: return
        val objectType = checker.getTypeOfExpression(expr.expression)
        if (objectType === anyType || objectType === errorType || objectType === unknownType) return
        val constraint = indexDecl.constraint
        if (constraint != null) {
            val parts = concreteKeyParts(constraint) ?: return
            if (covers(objectType, parts, expr) != false) return
        }
        val argEnd = checker.expressionTrueEnd(arg)
        var close = argEnd
        while (close < source.length && source[close] != ']') close++
        val accessEnd = if (close < source.length) close + 1 else argEnd
        val start = expr.expression.pos
        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
        checker.diagnostics.add(Diagnostic(
            message = "Type '${checker.typeToString(indexType)}' cannot be used to index type '${checker.typeToString(objectType)}'.",
            category = DiagnosticCategory.Error, code = 2536, fileName = fileName,
            line = line, character = character, start = start, length = (accessEnd - start).coerceAtLeast(1),
        ))
    }

    /** One decidable key of a concrete constraint: a `string` / `number` keyword or a literal. */
    private sealed class KeyPart {
        object AnyString : KeyPart()
        object AnyNumber : KeyPart()
        class Literal(val name: String, val numeric: Boolean) : KeyPart()
    }

    /** The keys a syntactically concrete constraint admits, or null when it is not one. */
    private fun concreteKeyParts(node: TypeNode): List<KeyPart>? = when (node) {
        is ParenthesizedType -> concreteKeyParts(node.type)
        is UnionType -> node.types.flatMap { concreteKeyParts(it) ?: return null }
        is KeywordTypeNode -> when (node.kind) {
            SyntaxKind.StringKeyword -> listOf(KeyPart.AnyString)
            SyntaxKind.NumberKeyword -> listOf(KeyPart.AnyNumber)
            else -> null
        }
        is LiteralType -> when (val lit = node.literal) {
            is StringLiteralNode -> listOf(KeyPart.Literal(lit.text, numeric = false))
            is NumericLiteralNode ->
                if (lit.text.isNotEmpty() && lit.text.all { it.isDigit() } &&
                    (lit.text == "0" || lit.text[0] != '0')) listOf(KeyPart.Literal(lit.text, numeric = true))
                else null
            else -> null
        }
        else -> null
    }

    /** true = every part is a key of [objectType]; false = some part provably is not; null = undecidable. */
    private fun covers(objectType: Type, parts: List<KeyPart>, at: Node): Boolean? = when (objectType) {
        is Type.Union -> {
            var verdict: Boolean? = true
            for (member in objectType.types) {
                when (covers(member, parts, at)) {
                    false -> return false
                    null -> verdict = null
                    true -> {}
                }
            }
            verdict
        }
        is Type.TypeParam -> {
            val name = objectType.symbol?.name
            val decl = if (name != null) typeParameterDeclaration(at, name) else null
            // `keyof unknown` is `never`: no non-empty constraint is assignable to it.
            if (decl != null && decl.constraint == null && objectType.constraint == null) parts.isEmpty() else null
        }
        is Type.Interface, is Type.Reference -> null
        is Type.Object -> literalObjectCovers(objectType, parts)
        else -> null
    }

    /**
     * A type written as a TYPE LITERAL: its keys are its member names plus whatever its
     * `string` / `number` index signatures admit. Any other member shape (a computed name, a
     * mapped-type placeholder, a call signature, an index signature over another key type)
     * makes it undecidable.
     */
    private fun literalObjectCovers(objectType: Type.Object, parts: List<KeyPart>): Boolean? {
        val literal = objectType.declaredAt as? TypeLiteral ?: return null
        for (member in literal.members) {
            val ok = when (member) {
                is PropertyDeclaration -> (member.name as? Identifier)?.text?.isNotEmpty() == true
                is MethodDeclaration -> (member.name as? Identifier)?.text?.isNotEmpty() == true
                is IndexSignature -> member.parameters.singleOrNull()?.type.let {
                    it is KeywordTypeNode && (it.kind == SyntaxKind.StringKeyword || it.kind == SyntaxKind.NumberKeyword)
                }
                else -> false
            }
            if (!ok) return null
        }
        checker.resolveStructuredTypeMembers(objectType)
        val members = objectType.members ?: return null
        val stringIndex = objectType.stringIndexInfo != null
        val numberIndex = objectType.numberIndexInfo != null
        return parts.all {
            when (it) {
                KeyPart.AnyString -> stringIndex
                KeyPart.AnyNumber -> stringIndex || numberIndex
                is KeyPart.Literal -> members[it.name] != null || stringIndex || (it.numeric && numberIndex)
            }
        }
    }

    /** The innermost enclosing declaration of a type parameter named [name], or null. */
    private fun typeParameterDeclaration(from: Node, name: String): TypeParameter? {
        var node: Node? = (from as? NodeBase)?.parent
        while (node != null) {
            val list = when (node) {
                is FunctionDeclaration -> node.typeParameters
                is FunctionExpression -> node.typeParameters
                is ArrowFunction -> node.typeParameters
                is MethodDeclaration -> node.typeParameters
                is ClassDeclaration -> node.typeParameters
                is ClassExpression -> node.typeParameters
                else -> null
            }
            list?.firstOrNull { it.name.text == name }?.let { return it }
            node = (node as? NodeBase)?.parent
        }
        return null
    }
}

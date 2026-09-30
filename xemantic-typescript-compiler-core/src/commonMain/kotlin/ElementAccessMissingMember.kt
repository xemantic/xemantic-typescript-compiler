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
 * (CHK.179)(a) — the missing-member row of a LITERAL-key element access `r["p"]` / `r[0]`.
 *
 * `checkSingleElementAccess` routes a literal key through the shared property-access funnel
 * `checkMemberAccessMissing`, which reports TS2339 at the KEY. tsgo never does that for an
 * element access (`getPropertyTypeForIndexType`, checker.go:26867): a literal key is
 * string-/number-like, so a receiver without the property and without an applicable index
 * signature takes the `noImplicitAny` block — SILENT without the flag, and with it TS2576 (a
 * static member of that name), TS7015 at the key (the receiver has a number index), TS2551 at
 * the key (a spelling suggestion) or TS7053 at the WHOLE access with the chain line
 * `Property 'p' does not exist on type 'R'.`. The funnel's own firewalls decide WHETHER a
 * member is missing; this class only restates what it emitted in tsgo's element-access terms.
 *
 * Left untouched, because tsgo reports TS2339 there too: a numeric key on a receiver whose
 * every constituent is a tuple (checker.go's `everyType(objectType, isTupleType)` arm), a
 * const-enum object (`isConstEnumObjectType` falls through to the key-anchored TS2339) and a
 * `globalThis` receiver (its block-scoped TS2339 arm).
 */
internal class ElementAccessMissingMember(
    private val checker: Checker,
    private val options: CompilerOptions,
) {

    private val noImplicitAny: Boolean
        get() = options.noImplicitAny ||
            (!options.noImplicitAnyExplicitlyFalse && !options.strictExplicitlyFalse)

    /**
     * Rewrites the rows `checkMemberAccessMissing` added to [Checker.diagnostics] from index
     * [from] for the access [expr] whose key starts at [keyStart] and whose whole span is
     * [fullStart]..[fullStart]+[fullLength].
     */
    fun restate(
        expr: ElementAccessExpression, propName: String, from: Int,
        keyStart: Int, keyLength: Int, fullStart: Int, fullLength: Int,
        source: String, fileName: String,
    ) {
        val diags = checker.diagnostics
        if (diags.size <= from) return
        val recvExpr = expr.expression
        if (recvExpr is Identifier && recvExpr.text == "globalThis") return
        val declaredRecv = checker.getTypeOfExpression(recvExpr)
        // tsgo indexes the APPARENT type: a type parameter answers its constraint (`unknown`
        // when it has none), and a string-like one the `String` wrapper with its number index.
        val recvType = if (declaredRecv is Type.TypeParam) checker.getApparentType(declaredRecv) else declaredRecv
        if (isConstEnumObject(recvType)) return
        val arg = expr.argumentExpression
        if (arg is NumericLiteralNode && everyTuple(recvType)) return
        val own = (from until diags.size).filter { i ->
            val d = diags[i]
            d.fileName == fileName && when (d.code) {
                2339, 2551 -> d.start == keyStart
                2576 -> d.start == fullStart
                else -> false
            }
        }
        if (own.isEmpty()) return
        if (!noImplicitAny) {
            for (i in own.asReversed()) diags.removeAt(i)
            return
        }
        for (i in own) {
            val d = diags[i]
            if (d.code != 2339) continue
            val recvDisplay = when {
                declaredRecv !is Type.TypeParam -> receiverDisplay(d.message) ?: continue
                recvType === anyType -> "unknown"
                else -> checker.typeToString(recvType)
            }
            if (hasNumberIndex(recvType)) {
                diags[i] = d.copy(
                    message = "Element implicitly has an 'any' type because index expression is not of type 'number'.",
                    code = 7015, start = keyStart, length = keyLength,
                )
                continue
            }
            val accessor = accessorSuggestion(expr, recvType, fullStart + fullLength, source)
            if (accessor != null) {
                diags[i] = d.copy(
                    message = "Element implicitly has an 'any' type because type '$recvDisplay' has no index signature. Did you mean to call '$accessor'?",
                    code = 7052, start = fullStart, length = fullLength,
                    line = checker.getLineAndCharacterOfPosition(source, fullStart).first,
                    character = checker.getLineAndCharacterOfPosition(source, fullStart).second,
                )
                continue
            }
            val indexDisplay = when (arg) {
                is StringLiteralNode -> "\"${arg.text}\""
                else -> propName
            }
            val (line, character) = checker.getLineAndCharacterOfPosition(source, fullStart)
            diags[i] = Diagnostic(
                message = "Element implicitly has an 'any' type because expression of type '$indexDisplay' can't be used to index type '$recvDisplay'.",
                category = DiagnosticCategory.Error, code = 7053,
                messageChain = listOf("  Property '$propName' does not exist on type '$recvDisplay'."),
                fileName = fileName, line = line, character = character,
                start = fullStart, length = fullLength,
            )
        }
    }

    /**
     * tsgo `getSuggestionForNonexistentIndexSignature`: the receiver has a `get` (read) or
     * `set` (write) member with ONE call signature taking at least one argument, and the
     * key's literal type is assignable to its first parameter — `recv.get` (the dotted
     * receiver path when it is an entity name, else the bare method name).
     */
    private fun accessorSuggestion(expr: ElementAccessExpression, recvType: Type, accessEnd: Int, source: String): String? {
        val method = if (checker.elementAccessIsWriteContext(source, accessEnd, expr.expression.pos)) "set" else "get"
        val obj = members(recvType).singleOrNull() as? Type.Object ?: return null
        val prop = checker.getPropertyOfType(obj, method) ?: return null
        val propType = checker.resolveGenericPropertyType(obj, prop) ?: checker.getTypeOfSymbol(prop)
        val fnType = propType as? Type.Object ?: return null
        checker.resolveStructuredTypeMembers(fnType)
        val sig = fnType.callSignatures?.singleOrNull() ?: return null
        if (sig.minArgumentCount < 1) return null
        val p0 = sig.parameters.firstOrNull() ?: return null
        val keyType = checker.literalTypeOfExpression(expr.argumentExpression) ?: return null
        if (!checker.isTypeAssignableTo(keyType, checker.getTypeOfSymbol(p0))) return null
        val path = checker.entityPathOf(expr.expression)
        return if (path != null) "$path.$method" else method
    }

    /** The `'R'` of a TS2339 `Property 'p' does not exist on type 'R'.` — the funnel's own display. */
    private fun receiverDisplay(message: String): String? {
        val marker = " does not exist on type '"
        val at = message.indexOf(marker)
        if (at < 0 || !message.endsWith("'.")) return null
        return message.substring(at + marker.length, message.length - 2)
    }

    private fun members(t: Type): List<Type> =
        if (t is Type.Union) t.types.filter { it !== nullType && it !== undefinedType } else listOf(t)

    private fun everyTuple(t: Type): Boolean {
        val ms = members(t)
        return ms.isNotEmpty() && ms.all { (it as? Type.Object)?.tupleElementTypes != null }
    }

    private fun isConstEnumObject(t: Type): Boolean =
        members(t).any { (it as? Type.Object)?.symbol?.flags?.hasAny(SymbolFlags.ConstEnum) == true }

    private fun hasNumberIndex(t: Type): Boolean {
        val ms = members(t)
        return ms.isNotEmpty() && ms.all { m ->
            when {
                m !is Type.Object -> false
                m.tupleElementTypes != null -> true
                m is Type.Reference && m.target.symbol?.name.let { it == "Array" || it == "ReadonlyArray" } -> true
                m is Type.Interface && m.declaredNumberIndexInfo != null -> true
                else -> {
                    checker.resolveStructuredTypeMembers(m)
                    m.numberIndexInfo != null
                }
            }
        }
    }
}

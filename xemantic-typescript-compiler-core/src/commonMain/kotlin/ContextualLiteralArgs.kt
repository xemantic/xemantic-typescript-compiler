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
 * (LIBS.3) GENLIT — an object-literal ARGUMENT typed against a type parameter keeps a property's LITERAL
 * type where the contextual type asks for one, as tsgo's `checkObjectLiteral` does through
 * `getWidenedLiteralLikeTypeForContextualType` / `isLiteralOfContextualType`.
 *
 * `getTypeOfExpression` types `{ mode: 'spread' }` with no contextual type, so `mode` is widened to
 * `string`; against `<O extends Opts>(o: O)` with `Opts = { mode?: 'replace' | 'spread' }` the inferred `O`
 * then failed its own constraint (a false TS2322 at `mode`). tsgo keeps `"spread"` because the property's
 * contextual type — the constraint's `mode` — contains a string LITERAL. A contextual type that is plain
 * `string` widens as before (`<O extends { mode?: string }>` infers `{ mode: string }`), and so does a
 * property with no contextual counterpart.
 *
 * Only literal-shaped initializers are re-typed (string / number / bigint literals, `true` / `false`, a
 * negated number), recursing into a nested object literal and an array literal of literals. Answers
 * [argType] itself when nothing changes.
 */
internal class ContextualLiteralArgs(private val checker: Checker) {

    /** [argType] with literal properties of [arg] kept where [ctx] (the contextual type) asks for them. */
    fun retype(arg: Expression, argType: Type, ctx: Type, depth: Int = 0): Type {
        if (depth > 6 || ctx === anyType || ctx === errorType || ctx === unknownType) return argType
        var a = arg
        while (a is ParenthesizedExpression) a = a.expression
        return when (a) {
            is ObjectLiteralExpression -> retypeObject(a, argType, ctx, depth)
            is ArrayLiteralExpression -> retypeArray(a, argType, ctx, depth)
            else -> {
                val lit = checker.literalTypeOfExpression(a) ?: return argType
                if (lit === argType || lit === nullType || lit === undefinedType) return argType
                if (keepsLiteral(lit, ctx, 0)) lit else argType
            }
        }
    }

    private fun retypeObject(lit: ObjectLiteralExpression, argType: Type, ctx: Type, depth: Int): Type {
        val obj = argType as? Type.Object ?: return argType
        if (obj is Type.Reference || obj is Type.Interface || obj.symbol != null) return argType
        val members = obj.members ?: return argType
        var changed: MutableMap<String, Type>? = null
        for (p in lit.properties) {
            val pa = p as? PropertyAssignment ?: continue
            val name = when (val n = pa.name) {
                is Identifier -> n.text
                is StringLiteralNode -> n.text
                else -> continue
            }
            val sym = members[name] ?: continue
            val cur = checker.getTypeOfSymbol(sym)
            val propCtx = propertyContext(ctx, name) ?: continue
            val next = retype(pa.initializer, cur, propCtx, depth + 1)
            if (next !== cur) (changed ?: HashMap<String, Type>().also { changed = it })[name] = next
        }
        val ch = changed ?: return argType
        val newMembers: SymbolTable = mutableMapOf()
        val newProps = ArrayList<Symbol>()
        for ((name, sym) in members) {
            val t = ch[name]
            val s = if (t == null) sym else Symbol(sym.flags, sym.name).also { n ->
                n.declarations.addAll(sym.declarations)
                n.valueDeclaration = sym.valueDeclaration
                checker.symbolTypes[n.id] = t
            }
            newMembers[name] = s
            newProps.add(s)
        }
        return Type.Object().also { o ->
            o.members = newMembers
            o.properties = newProps
            o.declaredAt = obj.declaredAt
            o.jsLiteral = obj.jsLiteral
            o.stringIndexInfo = obj.stringIndexInfo
            o.numberIndexInfo = obj.numberIndexInfo
        }
    }

    private fun retypeArray(lit: ArrayLiteralExpression, argType: Type, ctx: Type, depth: Int): Type {
        val ref = argType as? Type.Reference ?: return argType
        if (ref.target !== checker.globalArrayType || lit.elements.isEmpty()) return argType
        val elemCtx = arrayElementContext(ctx) ?: return argType
        val elems = ArrayList<Type>(lit.elements.size)
        for (e in lit.elements) {
            if (e is SpreadElement) return argType
            val l = checker.literalTypeOfExpression(e) ?: return argType
            if (!keepsLiteral(l, elemCtx, 0)) return argType
            elems.add(l)
        }
        return checker.getOrInternReference(checker.globalArrayType, listOf(checker.getUnionType(elems)))
    }

    /** The contextual type of property [name] of [ctx] (a union contributes each member's), or null. */
    private fun propertyContext(ctx: Type, name: String): Type? {
        val c = constraintOf(ctx)
        if (c is Type.Union) {
            val parts = c.types.mapNotNull { if (it === undefinedType || it === nullType) null else propertyContext(it, name) }
            return if (parts.isEmpty()) null else checker.getUnionType(parts)
        }
        if (c !is Type.Object) return null
        val prop = checker.getPropertyOfType(c, name) ?: return null
        val t = checker.getTypeOfSymbol(prop)
        return if (t === anyType || t === errorType) null else t
    }

    private fun arrayElementContext(ctx: Type): Type? {
        val c = constraintOf(ctx)
        if (c is Type.Union) {
            val parts = c.types.mapNotNull { if (it === undefinedType || it === nullType) null else arrayElementContext(it) }
            return if (parts.isEmpty()) null else checker.getUnionType(parts)
        }
        val ref = c as? Type.Reference ?: return null
        if (ref.target !== checker.globalArrayType) return null
        return ref.resolvedTypeArguments?.singleOrNull()
    }

    private fun constraintOf(t: Type): Type = if (t is Type.TypeParam) t.constraint ?: unknownType else t

    /** tsgo `isLiteralOfContextualType`: does [ctx] contain a literal of [lit]'s kind (a type variable: its constraint a primitive of it)? */
    private fun keepsLiteral(lit: Type, ctx: Type, depth: Int): Boolean {
        if (depth > 4) return false
        if (ctx is Type.Union) return ctx.types.any { keepsLiteral(lit, it, depth + 1) }
        if (ctx is Type.TypeParam) {
            val c = ctx.constraint ?: return false
            return primitiveOfKind(lit, c) || keepsLiteral(lit, c, depth + 1)
        }
        return when (lit) {
            is Type.StringLiteral -> ctx is Type.StringLiteral || ctx is Type.TemplateLiteral
            is Type.NumberLiteral -> ctx is Type.NumberLiteral
            is Type.BigIntLiteral -> ctx is Type.BigIntLiteral
            else -> lit.flags.hasAny(TypeFlags.BooleanLiteral) && ctx.flags.hasAny(TypeFlags.BooleanLike)
        }
    }

    private fun primitiveOfKind(lit: Type, c: Type): Boolean {
        if (c is Type.Union) return c.types.any { primitiveOfKind(lit, it) }
        return when (lit) {
            is Type.StringLiteral -> c.flags.hasAny(TypeFlags.String)
            is Type.NumberLiteral -> c.flags.hasAny(TypeFlags.Number)
            is Type.BigIntLiteral -> c.flags.hasAny(TypeFlags.BigInt)
            else -> c.flags.hasAny(TypeFlags.Boolean)
        }
    }
}

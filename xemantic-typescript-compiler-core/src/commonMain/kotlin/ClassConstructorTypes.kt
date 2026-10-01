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
 * (CHK.196) stages 1-2 — the CONSTRUCTOR SIDE of a class value ((CHK.73)), tsgo's
 * `resolveAnonymousTypeMembers` for a class symbol: an anonymous [Type.Object] whose
 * [Type.Object.symbol] is the class, carrying
 *
 *  - the class's STATICS, inherited ones included (the instance interface's
 *    [Type.Interface.staticMembers], which [MemberResolver] fills over the extends chain),
 *  - the VALUE exports of a merged namespace (`class A {}` + `namespace A { export const k }`),
 *  - the class's construct signatures re-returned to the class: its own visible constructors
 *    (MemberResolver's overload rule), else the base's (already heritage-instantiated there),
 *    else ONE zero-argument default — abstract when the class is.
 *
 * plus, since stage 2, tsgo's `prototype` (the instance; `any` type arguments for a generic) —
 * safe only once identifier SOURCES are constructor-typed too (stage 1 measured a false TS2741
 * on `classSideInheritance3` while they were not).
 *
 * Built ONCE per class symbol. Readers: `typeof A` ([Checker] `getTypeOfSymbolForTypeQuery`,
 * which therefore also feeds the module-object class carrier `m.Cls` and the object-literal
 * class-value source); since stage 2 every VALUE read of a class — an identifier
 * ([valueReadType]), `N.C` ([qualifiedValueReadType]), `return A`, a class expression
 * ([classExpressionType]). A direct `new` callee, a heritage expression and an `instanceof`
 * right operand keep the instance. `typeToString` renders a type minted here as
 * `typeof Name` ([isConstructorType]).
 */
internal class ClassConstructorTypes(
    private val checker: Checker,
) {

    private val bySymbol = HashMap<Symbol, Type.Object>()
    private val minted = HashMap<Type.Object, Type.Interface>()
    private val building = HashSet<Symbol>()

    private val classExpressionSymbols = HashMap<String, Symbol>()

    /**
     * Stage 2: the value of a class EXPRESSION — the constructor side of a class symbol
     * minted once per expression node and named as tsgo names it (its own name, else the
     * variable it initializes). Null keeps today's `any` (no resolvable instance side).
     */
    fun classExpressionType(expr: ClassExpression): Type? {
        // A MIXIN (`class extends base` over a type-parameter / computed base) is tsgo's
        // intersection with the base type variable, which this does not model: keep `any`
        // unless the base is a plain class (measured: two corpus false TS2322 otherwise).
        val ext = expr.heritageClauses?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword }?.types?.firstOrNull()
        if (ext != null) {
            val base = checker.getTypeOfExpression(ext.expression) as? Type.Object ?: return null
            if (base !is Type.Interface || base.symbol?.flags?.hasAny(SymbolFlags.Class) != true) return null
        }
        var root: Node = expr
        while (true) root = (root as NodeBase).parent ?: break
        val file = (root as? SourceFile)?.fileName ?: return null
        val key = "$file:${expr.pos}:${expr.end}"
        val sym = classExpressionSymbols.getOrPut(key) {
            val name = expr.name?.text
                ?: ((expr as NodeBase).parent as? VariableDeclaration)?.let { (it.name as? Identifier)?.text }
                ?: "(Anonymous class)"
            Symbol(SymbolFlags.Class, name).also { it.declarations.add(expr) }
        }
        return constructorTypeOfClass(sym)
    }

    /** True for a type minted by [constructorTypeOfClass] (identity). */
    fun isConstructorType(type: Type): Boolean = type is Type.Object && type in minted

    /**
     * The instance interface behind a type minted here, or null. A constructor-typed value
     * that is the DIRECT callee of a `new` reads as that instance in stage 1, because
     * `new`-expression typing (explicit type arguments, inference, the class exclusions) is
     * keyed on a [Type.Interface] callee — measured: without it `new t(1).v` through
     * `t: typeof Box` loses its inferred `number`. Stage 2 ports the `new` typing instead.
     */
    fun instanceOf(type: Type): Type.Interface? = (type as? Type.Object)?.let { minted[it] }

    /** [id] is (through parentheses and `!`) the callee expression of a `new`. */
    fun isDirectNewCallee(id: Identifier): Boolean {
        var self: Node = id
        var p = id.parent
        while (p is ParenthesizedExpression || p is NonNullExpression) { self = p; p = (p as NodeBase).parent }
        return p is NewExpression && p.expression === self
    }

    /**
     * (CHK.196) stage 2 — the type of an identifier READ of a class: when [t], what the
     * identifier typer answered for [id], is exactly the declared instance type of the class
     * [id] spells, and [id] sits in a value-read position, answer the class's constructor side.
     * Never for a heritage expression (`extends A` reads the instance), the right operand of
     * `instanceof` (the narrowing reads the instance), or a direct `new` callee — whose typing
     * is still keyed on the instance (see [instanceOf]). Null keeps [t].
     */
    fun valueReadType(id: Identifier, t: Type): Type? {
        val sym = classOfInstance(t, id.text) ?: importedClassOfInstance(id, t) ?: return null
        if (!isValueUse(id) || !checker.isValueReadPosition(id)) return null
        // A walk-scoped binding of the same name (`function f(A: A)`) is the instance its
        // annotation says, not the class.
        if (checker.currentLocalTypes[id.text] === t || id.text in checker.currentParamBindingNames) return null
        return constructorTypeOfClass(sym)
    }

    /**
     * Stage 2, qualified half: `N.C` where `N` is a namespace (or module object) whose
     * export `C` is the class itself — not an ordinary property that merely has the class's
     * instance type (`o.A` with `A: A`), which is why the receiver's export table is asked.
     */
    fun qualifiedValueReadType(expr: PropertyAccessExpression, t: Type): Type? {
        val sym = classOfInstance(t, expr.name.text) ?: return null
        if (!isValueUse(expr)) return null
        // The class is an export of a namespace spelled as the receiver's last name. (A
        // namespace receiver types as `any` here, so its type cannot be asked.)
        val ns = sym.parent ?: return null
        if (!ns.flags.hasAny(SymbolFlags.Module) || ns.exports?.get(sym.name) !== sym) return null
        val recvName = when (val r = expr.expression) {
            is Identifier -> r.text
            is PropertyAccessExpression -> r.name.text
            else -> return null
        }
        if (recvName != ns.name) return null
        return constructorTypeOfClass(sym)
    }

    /**
     * A class read through an IMPORT that renames it — `import D from './a'` of an
     * `export default class` (symbol name `default`), `import { A as B }`: the file-local
     * alias [id] spells resolves to the class whose declared instance type [t] is.
     */
    private fun importedClassOfInstance(id: Identifier, t: Type): Symbol? {
        if (t !is Type.Interface) return null
        val sym = t.symbol ?: return null
        if (!sym.flags.hasAny(SymbolFlags.Class)) return null
        val local = checker.currentFileLocal(id.text) ?: return null
        if (!local.flags.hasAny(SymbolFlags.Alias) || checker.resolveAlias(local) !== sym) return null
        if (checker.getDeclaredTypeOfSymbol(sym) !== t) return null
        return sym
    }

    /** The class whose declared instance type [t] is, when that class is spelled [name]. */
    private fun classOfInstance(t: Type, name: String): Symbol? {
        if (t !is Type.Interface) return null
        val sym = t.symbol ?: return null
        if (!sym.flags.hasAny(SymbolFlags.Class) || sym.name != name) return null
        if (checker.getDeclaredTypeOfSymbol(sym) !== t) return null
        return sym
    }

    /** Not a heritage expression, a direct `new` callee or the right operand of `instanceof`. */
    private fun isValueUse(node: Node): Boolean {
        var self: Node = node
        var p = (node as NodeBase).parent
        while (p is ParenthesizedExpression || p is NonNullExpression) { self = p; p = (p as NodeBase).parent }
        if (p is NewExpression && p.expression === self) return false
        if (p is ExpressionWithTypeArguments || p is HeritageClause) return false
        if (p is BinaryExpression && p.right === self && p.operator == SyntaxKind.InstanceOfKeyword) return false
        return true
    }

    /**
     * The constructor-side type of class [symbol], or null when its instance side is not a
     * resolvable [Type.Interface] (the caller keeps its previous answer) or while the same
     * class is already being built (a static whose type mentions `typeof` its own class).
     */
    fun constructorTypeOfClass(symbol: Symbol): Type.Object? {
        bySymbol[symbol]?.let { return it }
        if (!building.add(symbol)) return null
        try {
            val iface = checker.getDeclaredTypeOfSymbol(symbol) as? Type.Interface ?: return null
            checker.resolveStructuredTypeMembers(iface)
            val isAbstract = symbol.declarations.any {
                it is ClassDeclaration && ModifierFlag.Abstract in it.modifiers
            }
            val sigs = mutableListOf<Signature>()
            iface.constructSignatures?.forEach { s ->
                val decl = s.declaration
                // A merged interface's own `new()` member is not a constructor of the class.
                if (decl != null && decl !is Constructor) return@forEach
                sigs += Signature(
                    declaration = decl,
                    typeParameters = s.typeParameters,
                    parameters = s.parameters,
                    resolvedReturnType = iface,
                    minArgumentCount = s.minArgumentCount,
                    isAbstract = isAbstract,
                    thisType = s.thisType,
                )
            }
            if (sigs.isEmpty()) {
                sigs += Signature(resolvedReturnType = iface, isAbstract = isAbstract)
            }
            val members = symbolTable()
            iface.staticMembers?.let { members.putAll(it) }
            symbol.exports?.forEach { (name, export) ->
                if (export.flags.hasAny(SymbolFlags.Value) && name !in members) members[name] = export
            }
            // Stage 2: tsgo's binder-made `prototype` (binder.go 968), typed as the instance —
            // with `any` type arguments for a generic class.
            if ("prototype" !in members) {
                val proto = Symbol.scopeSymbol(SymbolFlags.Property, "prototype")
                val tps = iface.typeParameters
                checker.symbolTypes[proto.id] =
                    if (tps.isNullOrEmpty()) iface else checker.getOrInternReference(iface, tps.map { anyType })
                members["prototype"] = proto
            }
            val ctorType = Type.Object()
            ctorType.symbol = symbol
            ctorType.members = members
            ctorType.properties = members.values.toList()
            ctorType.constructSignatures = sigs
            bySymbol[symbol] = ctorType
            minted[ctorType] = iface
            return ctorType
        } finally {
            building.remove(symbol)
        }
    }
}


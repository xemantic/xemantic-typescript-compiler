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
 * (CHK.196) stage 1 — the CONSTRUCTOR SIDE of a class value ((CHK.73)), tsgo's
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
 * It deliberately has NO `prototype` member: measured by the (P18.252) census, `prototype` on
 * a `typeof A` TARGET is a false TS2741 while an identifier SOURCE is still instance-typed
 * (`classSideInheritance3`); it arrives with stage 2's identifier reads.
 *
 * Built ONCE per class symbol. Readers in stage 1: `typeof A` ([Checker] `getTypeOfSymbolForTypeQuery`,
 * which therefore also feeds the module-object class carrier `m.Cls` and the object-literal
 * class-value source). `typeToString` renders a type minted here as `typeof Name`
 * ([isConstructorType]).
 */
internal class ClassConstructorTypes(
    private val checker: Checker,
) {

    private val bySymbol = HashMap<Symbol, Type.Object>()
    private val minted = HashMap<Type.Object, Type.Interface>()
    private val building = HashSet<Symbol>()

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

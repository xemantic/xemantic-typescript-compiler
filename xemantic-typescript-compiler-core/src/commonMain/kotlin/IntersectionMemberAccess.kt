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
 * (CHK.234) round (P18.309) — whether a member read on an INTERSECTION-typed receiver names a
 * property no constituent has: tsgo's `getPropertyOfType` over an intersection (a property
 * exists when ANY constituent has it — on its apparent type for a primitive or a constrained
 * type parameter, through a string index signature, a numeric index signature for a numeric
 * name, the `Function` members for a callable constituent, the `Object` prototype members for
 * every object).
 *
 * The answer is three-valued and deliberately CONSERVATIVE: [missing] answers null — do not
 * report — for any constituent this model cannot decide (`any` / the error type, a generic or
 * deferred type it does not resolve, a nested union, a JavaScript literal, an unresolved member
 * table). This checker answers `any` for what it cannot resolve, so a constituent it cannot read
 * is never evidence that a member is absent; a false TS2339 on legal code outranks a missing one.
 */
internal class IntersectionMemberAccess(
    private val checker: Checker,
) {

    /** True: no constituent has [name]; false: one has; null: undecidable here. */
    fun missing(type: Type.Intersection, name: String): Boolean? {
        if (name.isEmpty() || name in Checker.OBJECT_PROTOTYPE_PROPERTIES) return false
        var callable = false
        for (c in type.types) {
            when (constituentHas(c, name)) {
                null -> return null
                HAS -> return false
                CALLABLE -> callable = true
                else -> {}
            }
        }
        if (callable) {
            val fn = checker.globals["Function"] ?: return null
            if (checker.getPropertyOfType(checker.getDeclaredTypeOfSymbol(fn), name) != null) return false
        }
        return true
    }

    /** Whether a constituent (or a constrained type parameter's constraint) has a numeric index signature. */
    fun hasNumberIndex(type: Type.Intersection): Boolean = type.types.any { c ->
        val o = (if (c is Type.TypeParam) c.constraint else c) as? Type.Object
        o != null && run { checker.resolveStructuredTypeMembers(o); o.numberIndexInfo != null }
    }

    /**
     * The names a spelling suggestion may pick from: the members the object constituents
     * DECLARE (never a primitive wrapper's, `Function`'s or `Object`'s — tsgo's suggestion pool
     * is the intersection's own properties).
     */
    fun ownMemberNames(type: Type.Intersection): Set<String> {
        val out = LinkedHashSet<String>()
        for (c in type.types) {
            val o = (if (c is Type.TypeParam) c.constraint else c) as? Type.Object ?: continue
            checker.resolveStructuredTypeMembers(o)
            o.properties?.forEach { out.add(it.name) }
        }
        return out
    }

    /** The declaration of [name] in the first object constituent that declares it. */
    fun ownMemberDeclaration(type: Type.Intersection, name: String): Node? {
        for (c in type.types) {
            val o = (if (c is Type.TypeParam) c.constraint else c) as? Type.Object ?: continue
            checker.resolveStructuredTypeMembers(o)
            val sym = o.properties?.firstOrNull { it.name == name } ?: continue
            return sym.valueDeclaration ?: sym.declarations.firstOrNull()
        }
        return null
    }

    private fun constituentHas(c0: Type, name: String): Int? {
        var c = c0
        if (c is Type.TypeParam) {
            // tsgo's apparent type of an unconstrained type parameter is `unknown`'s, i.e. the
            // empty object: it contributes nothing. A constrained one answers through its
            // constraint (one hop; a chain or a cycle is left undecided).
            c = c.constraint ?: return NONE
            if (c is Type.TypeParam) return null
        }
        if (c === anyType || c === errorType) return null
        if (c is Type.Union || c is Type.Intersection || c is Type.TypeParam) return null
        if (c is Type.Intrinsic || c is Type.StringLiteral || c is Type.NumberLiteral || c is Type.BigIntLiteral) {
            if (c === unknownType || c.flags.hasAny(TypeFlags.NonPrimitive)) return NONE
            val wrapper = checker.primitiveApparentWrapper(c) ?: return null
            return if (checker.getPropertyOfType(wrapper, name) != null) HAS else NONE
        }
        val o = c as? Type.Object ?: return null
        if (o.jsLiteral || o.unnamedUniqueSymbolMember) return null
        if (!trusted(o, 0)) return null
        if (checker.getPropertyOfType(o, name) != null) return HAS
        if (o.members == null && o.callSignatures.isNullOrEmpty() && o.constructSignatures.isNullOrEmpty()) return null
        if (o.stringIndexInfo != null) return HAS
        if (o.numberIndexInfo != null && name.toDoubleOrNull() != null) return HAS
        if (!o.callSignatures.isNullOrEmpty() || !o.constructSignatures.isNullOrEmpty()) return CALLABLE
        return NONE
    }

    /**
     * Whether [o]'s member table is complete enough that an ABSENT member is evidence: a
     * declared class / interface (and its instantiations) whose bases are all trusted too, a
     * type literal / function type / tuple written in source. A type a mapped or utility type
     * produced (`Omit<Options, 'x'>` — whose materialization is known to drop members, measured
     * on `ky`'s `InternalOptions`) or anything else this model builds is not.
     */
    private fun trusted(o: Type.Object, depth: Int): Boolean {
        if (depth > 8) return false
        if (o.tupleElementTypes != null) return true
        val decl = (if (o is Type.Reference) o.target else o)
        if (decl is Type.Interface) {
            val decls = decl.symbol?.declarations ?: return false
            // A value or namespace merged with the class / interface (`interface Array<T>` +
            // `declare var Array`) adds no INSTANCE member; anything else might.
            if (decls.none { it is ClassDeclaration || it is InterfaceDeclaration }) return false
            if (decls.any { it !is ClassDeclaration && it !is InterfaceDeclaration &&
                    it !is VariableDeclaration && it !is ModuleDeclaration }) return false
            if (decl.heritageIncomplete) return false
            checker.resolveStructuredTypeMembers(decl)
            if (!decl.baseTypes.orEmpty().all { b -> b is Type.Object && trusted(b, depth + 1) }) return false
            return decls.all { d ->
                when (d) {
                    is ClassDeclaration -> declaredNamesResolve(o, d.members, skipStatic = true)
                    is InterfaceDeclaration -> declaredNamesResolve(o, d.members, skipStatic = false)
                    else -> true
                }
            }
        }
        return when (val at = o.declaredAt) {
            is TypeLiteral -> declaredNamesResolve(o, at.members, skipStatic = false)
            is FunctionType, is ConstructorType -> true
            else -> false
        }
    }

    /**
     * The member table holds every member the declaration WRITES under a plain name — the
     * completeness check that makes an absent name evidence. Measured gap it guards: a
     * type literal's `set a(v)` accessor was missing from its table, so `{ get a() } &
     * { set a(v) }` read `a` as absent.
     */
    private fun declaredNamesResolve(o: Type.Object, members: List<ClassElement>, skipStatic: Boolean): Boolean {
        for (m in members) {
            val (name, mods) = when (m) {
                is PropertyDeclaration -> m.name to m.modifiers
                is MethodDeclaration -> m.name to m.modifiers
                is GetAccessor -> m.name to m.modifiers
                is SetAccessor -> m.name to m.modifiers
                else -> continue
            }
            if (skipStatic && ModifierFlag.Static in mods) continue
            val text = when (name) {
                is Identifier -> name.text
                is StringLiteralNode -> name.text
                is NumericLiteralNode -> name.text
                is ComputedPropertyName -> when (val e = name.expression) {
                    is StringLiteralNode -> e.text
                    is NumericLiteralNode -> e.text
                    else -> continue
                }
                else -> return false
            }
            if (checker.getPropertyOfType(o, text) == null) return false
        }
        return true
    }

    private companion object {
        const val NONE = 0
        const val HAS = 1
        const val CALLABLE = 2
    }
}

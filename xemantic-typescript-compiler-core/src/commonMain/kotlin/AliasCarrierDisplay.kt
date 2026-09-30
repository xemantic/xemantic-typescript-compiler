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
 * (CHK.177) round S1 — the ALIAS NAME a relation-error or TS2339 SOURCE display takes when
 * the source is a reference whose declaration is annotated with a union alias.
 *
 * tsgo keeps an alias as part of a union's identity (`getUnionTypeEx` keys on it), returns the
 * DECLARED union from a flow join whose member list equals it (`flow.go`,
 * `getUnionOrEvolvingArrayType`) and keeps an origin for `U | undefined`. Composed, it prints
 * the alias exactly when the displayed type has the declared union's MEMBER SET — `'U'` for a
 * whole or re-joined `u: U`, `'A | B'` for a narrowed subset, `'U | undefined'` for the alias
 * plus a nullish part the annotation (or an optional parameter's `?`) supplies. This checker
 * interns unions by member list alone, so the name cannot live on the type (round 545's
 * refusal, INV.5(a)); it is recovered here per READ, from the reference's own declaration.
 *
 * The declaration is found by [Checker.lexicalReturnIdentifierDecl], the SYNTACTIC parent-chain
 * walker — never a text lookup through file locals, which is what named the wrong alias under
 * shadowing in the old TS2339 carrier. DISPLAY ONLY: answers a string or null; never a type.
 *
 * Scope (S1): an Identifier source (through parens and `!`), a `VariableDeclaration` or
 * `Parameter` annotated with a bare, non-generic `type` alias reference that resolves to a
 * union, optionally unioned with top-level `null` / `undefined`. Everything else answers null
 * and the caller's rendering stands. Property / call / element / destructured / unannotated
 * sources are round S2.
 */
internal class AliasCarrierDisplay(private val checker: Checker) {

    /** The alias display for [expr]'s [display] type, or null. */
    fun display(expr: Expression?, display: Type): String? =
        display(expr, if (display is Type.Union) display.types else listOf(display))

    /** As above, over an explicit member list (the TS2339 receiver's non-nullish part). */
    fun display(expr: Expression?, members: List<Type>): String? {
        var e = expr
        while (true) {
            e = when (e) {
                is ParenthesizedExpression -> e.expression
                is NonNullExpression -> e.expression
                else -> break
            }
        }
        // (CHK.178)(b) a CAST carries its own annotation: `(u as U)` / `<U>u` is typed by the
        // written `U`, so tsgo names the alias exactly as for a `u: U` declaration.
        val (annotation, optional) = when (e) {
            is AsExpression -> e.type to false
            is TypeAssertionExpression -> e.type to false
            else -> declaredAnnotation(e as? Identifier ?: return null) ?: return null
        }
        if (annotation == null) return null
        val ann = unparen(annotation)
        val parts = if (ann is UnionType) ann.types else listOf(ann)
        var aliasRef: TypeReference? = null
        var allowNull = false
        var allowUndefined = optional
        for (p in parts) {
            val part = unparen(p)
            if (part is TypeReference) {
                if (aliasRef != null) return null
                aliasRef = part
                continue
            }
            val t = checker.getTypeFromTypeNode(part)
            when {
                t.flags.hasAny(TypeFlags.Null) && t !is Type.Union -> allowNull = true
                t.flags.hasAny(TypeFlags.Undefined) && t !is Type.Union -> allowUndefined = true
                else -> return null
            }
        }
        val ref = aliasRef ?: return null
        if (ref.typeArguments != null) return null
        val nameId = ref.typeName as? Identifier ?: return null
        val sym = checker.resolveTypeNameToSymbol(nameId) ?: return null
        val aliasDecl = sym.declarations.firstOrNull { it is TypeAliasDeclaration } as? TypeAliasDeclaration
            ?: return null
        if (!aliasDecl.typeParameters.isNullOrEmpty()) return null
        val aliasType = checker.getTypeFromTypeNode(ref) as? Type.Union ?: return null
        val aliasIds = aliasType.types.mapTo(HashSet()) { it.id }
        var sawNull = false
        var sawUndefined = false
        val shown = HashSet<Int>()
        for (m in members) {
            if (m.id in aliasIds) { shown.add(m.id); continue }
            when {
                m !is Type.Union && m.flags.hasAny(TypeFlags.Null) && allowNull -> sawNull = true
                m !is Type.Union && m.flags.hasAny(TypeFlags.Undefined) && allowUndefined -> sawUndefined = true
                else -> return null
            }
        }
        if (shown.size != aliasIds.size) return null
        val name = nameId.text
        return when {
            sawNull && sawUndefined -> "$name | null | undefined"
            sawNull -> "$name | null"
            sawUndefined -> "$name | undefined"
            else -> name
        }
    }

    /** The annotation (and optionality) of [id]'s declaration, or null when not annotatable. */
    private fun declaredAnnotation(id: Identifier): Pair<TypeNode?, Boolean>? {
        val decl = when (val local = checker.lexicalReturnIdentifierDecl(id)) {
            // No binding in scope, or an import: the name is a file-level or cross-file
            // symbol (a global script declaration, or an import's target) — the lexical walk
            // already proved nothing closer shadows it, so a symbol lookup is shadow-correct.
            null, is ImportDeclaration -> crossFileDeclaration(id) ?: return null
            else -> local
        }
        return when (decl) {
            is VariableDeclaration -> decl.type to false
            is Parameter -> decl.type to decl.questionToken
            else -> null
        }
    }

    private fun crossFileDeclaration(id: Identifier): Node? {
        val sym = checker.lookupPerFileForNode(id, id.text) ?: checker.globals[id.text] ?: return null
        val target = checker.resolveAlias(sym)
        return target.declarations.firstOrNull { it is VariableDeclaration || it is Parameter }
    }

    private fun unparen(t: TypeNode): TypeNode {
        var n = t
        while (n is ParenthesizedType) n = n.type
        return n
    }
}

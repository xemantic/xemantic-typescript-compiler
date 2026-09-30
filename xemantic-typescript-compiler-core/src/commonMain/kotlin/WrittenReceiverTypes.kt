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
 */


package com.xemantic.typescript.compiler

/**
 * (CHK.180) stage 1 — the WRITTEN type of a property / element access chain's root, for
 * the one access the property-access walk is about to check.
 *
 * The property-access frame types `this` and every block-scoped body local as `any`
 * (B83.5 leaves a body local unbound, and the frame records one only for an un-annotated
 * call / element-access initializer), so each TS2339 / TS7053 / TS18048 reader works only
 * where it carries its own receiver ladder. This reader answers two roots, and only these:
 *
 *  - `this` — [Checker.enclosingInstanceThisTypeForFlow]: an instance member of a class
 *    DECLARATION, arrows transparent; a static member, a `this:` parameter, a class
 *    expression and a `function` boundary answer nothing.
 *  - a body local declared ONCE by a `const` whose type is WRITTEN — an annotation, or an
 *    `as T` / `<T>` initializer (never `as const`) — refused when the read sits in an
 *    object-literal / class-expression method this checker gives no outer flow
 *    ([BodyLocalAssignments.readCrossesUnmodeledContainer]) and, for a nullish written
 *    type, when B6's veto says the reaching assignments rule the nullish part out
 *    (marked `Instance.ts:77`: `const e: T['x'] = this.d.x || {…}`).
 *
 * Installed by the caller for THAT ONE access and removed again — never for a frame's
 * lifetime: typing `this` into the frame woke a false TS2341 and a duplicate TS2339
 * (`noUnusedLocals_destructuringAssignment`, `destructuringUnspreadableIntoRest`), and a
 * frame-level receiver write reached the TS18048 emitters and B136
 * (`discriminateWithOptionalProperty4`, `narrowingPastLastAssignment`).
 *
 * Refused by measurement ((P18.237) census, every other arm added ONLY ours-only rows):
 * `let` / `var` (assignment-in-condition narrowing gaps), un-annotated initializers
 * (no subtype reduction on `||` / `?:`, generic inference, guard narrowing over an
 * intersection), a function expando, object-literal and static `this`.
 */
internal class WrittenReceiverTypes(
    private val checker: Checker,
) {

    /**
     * Installs the written type of [access]'s root into `currentLocalTypes` when that root
     * reads `any` there; returns the installed NAME for the caller to remove, or null.
     */
    fun install(access: Expression): String? {
        var r: Expression = when (access) {
            is PropertyAccessExpression -> access.expression
            is ElementAccessExpression -> access.expression
            else -> return null
        }
        var hops = 0
        while (hops++ < 64) {
            r = when (r) {
                is PropertyAccessExpression -> r.expression
                is ElementAccessExpression -> r.expression
                is CallExpression -> r.expression
                is ParenthesizedExpression -> r.expression
                is NonNullExpression -> r.expression
                else -> break
            }
        }
        val id = r as? Identifier ?: return null
        val name = id.text
        val locals = checker.currentLocalTypes
        if (name == "this") {
            if ("this" in locals || checker.getTypeOfIdentifier(id) !== anyType) return null
            locals["this"] = checker.enclosingInstanceThisTypeForFlow(access) ?: return null
            return "this"
        }
        if (name == "undefined" || name == "super" || name == "arguments") return null
        if (name in locals || name in checker.currentParamBindingNames || name in checker.currentShadowedNames) return null
        val sym = checker.lexicalScopeSymbol(id, name) ?: return null
        val decl = sym.valueDeclaration as? VariableDeclaration ?: return null
        if (sym.declarations.size != 1) return null
        if ((decl.parent as? VariableDeclarationList)?.flags != SyntaxKind.ConstKeyword) return null
        val typeNode = writtenTypeNode(decl) ?: return null
        if (checker.getTypeOfIdentifier(id) !== anyType) return null
        val t = checker.getTypeFromTypeNode(typeNode)
        if (t === anyType || t === errorType || t === unknownType) return null
        if (BodyLocalAssignments.readCrossesUnmodeledContainer(id, decl)) return null
        if ((checker.typeIncludesNull(t) || checker.typeIncludesExplicitUndefined(t)) &&
            checker.nullishReceivers.bodyLocalAssignmentsVeto(id, name, t)
        ) return null
        locals[name] = t
        return name
    }

    /** The annotation, else an `as T` / `<T>` initializer's `T` (not `as const`), else null. */
    private fun writtenTypeNode(decl: VariableDeclaration): TypeNode? {
        decl.type?.let { return it }
        var init = decl.initializer
        while (init is ParenthesizedExpression) init = init.expression
        val t = when (init) {
            is AsExpression -> init.type
            is TypeAssertionExpression -> init.type
            else -> return null
        }
        return if (isConstTypeRef(t)) null else t
    }

    companion object {
        /** `const` as a type reference — the `as const` / `<const>` assertion. */
        fun isConstTypeRef(t: TypeNode): Boolean =
            t is TypeReference && t.typeArguments == null && (t.typeName as? Identifier)?.text == "const"
    }
}

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

import com.xemantic.typescript.compiler.CallExpression
import com.xemantic.typescript.compiler.CheckedLens
import com.xemantic.typescript.compiler.CheckedNodeSink
import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.NewExpression
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.Signature
import com.xemantic.typescript.compiler.Symbol
import com.xemantic.typescript.compiler.Type
import java.util.IdentityHashMap

/**
 * What overload resolution picked at one call site, and what the callee IS.
 *
 * [receiverTypeText] and [memberName] are recorded for the intrinsic table:
 * `console.log` resolves to a signature declared in a lib `.d.ts`, i.e. to a
 * declaration this backend never lowers, and the pair `Console` + `log` is what
 * names it. They are the checker's own rendering, taken at the call site, not a
 * re-derivation from syntax.
 */
public class CallFact internal constructor(
    /** The signature overload resolution chose, or null when it chose none. */
    public val signature: Signature?,
    /** How many signatures were on offer — 0 means the callee is not callable. */
    public val signatureCount: Int,
    /** The rendered type of a property-access callee's receiver, else null. */
    public val receiverTypeText: String?,
    /** A property-access callee's member name, else null. */
    public val memberName: String?,
)

/**
 * Everything the lowering will ask about, extracted DURING the checker's walk.
 *
 * The single most important property of this class is that it asks the lens and
 * throws it away: [CheckedLens] is valid only for the duration of the callback
 * that received it, because the ambient it reads — `currentLocalTypes`, the cta
 * frame stack, `currentFlowGraph` — is installed around the call and restored
 * afterwards. Asking later does not give a coarser answer, it gives a
 * confidently wrong one. The [Type] and [Symbol] objects it RETURNS outlive the
 * walk, which is what makes eager extraction possible at all.
 *
 * Keyed by NODE IDENTITY throughout, via [IdentityHashMap]. Two reasons, and
 * both are load-bearing here: an AST node is a Kotlin `data class`, so a
 * `HashMap` keyed by one deep-recurses `hashCode()` over its whole subtree; and
 * `nodeId` restarts at 0 in every `SourceFile`, so it is not a program-wide key
 * either.
 *
 * FIRST WINS for every fact. The spine may visit a node more than once, and the
 * first visit is the one under the tightest ambient it will ever have there —
 * which is `Checker.typeCaptureRecord`'s rule, adopted for the same reason.
 */
public class CheckedFacts internal constructor() : CheckedNodeSink {

    private val expressionTypes = IdentityHashMap<Expression, Type>()
    private val calls = IdentityHashMap<CallExpression, CallFact>()
    private val constructions = IdentityHashMap<NewExpression, Signature?>()
    private val members = IdentityHashMap<PropertyAccessExpression, Symbol>()
    private val names = IdentityHashMap<Identifier, Symbol>()
    private val parameterTypes = IdentityHashMap<Parameter, Type>()
    private val declaredSignatures = IdentityHashMap<Node, Signature>()
    private val declaredMemberTypes = IdentityHashMap<Node, Type>()

    /** The checker's own rendering of a type, keyed by `Type.id`. */
    private val renderings = HashMap<Int, String>()

    /** The type of [node] at its own position — narrowed, body-local-correct. */
    public fun typeOf(node: Expression): Type? = expressionTypes[node]

    /** What overload resolution picked at [node]. */
    public fun callAt(node: CallExpression): CallFact? = calls[node]

    /** The construct signature `new` selected at [node]. */
    public fun constructionAt(node: NewExpression): Signature? = constructions[node]

    /** The member symbol [node]'s name resolved to on its receiver's type. */
    public fun memberAt(node: PropertyAccessExpression): Symbol? = members[node]

    /** What the free name [node] refers to at its own position. */
    public fun nameAt(node: Identifier): Symbol? = names[node]

    /** A parameter's type, taken through `typeOfSymbol` and not `declaredTypeOf`. */
    public fun typeOf(parameter: Parameter): Type? = parameterTypes[parameter]

    /**
     * The signature of a function or method DECLARATION, which is where its
     * declared RETURN type comes from.
     *
     * The backend cannot read a return type off the AST: a `TypeNode` is syntax
     * and the lens exposes no way to resolve one. What it does expose is the
     * signatures of a callee EXPRESSION — and a declaration's own name node is
     * such an expression, typed by the checker as the function type.
     */
    public fun signatureOf(declaration: Node): Signature? = declaredSignatures[declaration]

    /**
     * The type of a class PROPERTY, taken on the class's own type.
     *
     * Asking the access expression instead does not work and the reason is
     * worth stating: `this` types as `any` here, so `this.value` types as `any`
     * too and resolves to no member at all. The class's declared type answers
     * correctly, and it is the same oracle.
     */
    public fun memberTypeOf(declaration: Node): Type? = declaredMemberTypes[declaration]

    /** The checker's rendering of [type], if it was ever asked about. */
    public fun render(type: Type): String = renderings[type.id] ?: type.toString()

    override fun expression(node: Expression, lens: CheckedLens) {
        if (node !in expressionTypes) remember(lens.typeOf(node), lens).also {
            expressionTypes[node] = it
        }
        when (node) {
            is CallExpression -> if (node !in calls) calls[node] = callFact(node, lens)
            is NewExpression -> if (node !in constructions) {
                val signatures = lens.constructSignatures(node)
                constructions[node] =
                    lens.selectOverload(signatures, node.arguments ?: emptyList())
                        ?: signatures.singleOrNull()
            }
            is PropertyAccessExpression -> if (node !in members) {
                val receiverType = remember(lens.typeOf(node.expression), lens)
                lens.membersOf(receiverType, node.name.text).firstOrNull()
                    ?.let { members[node] = it }
            }
            is Identifier -> if (node !in names) {
                lens.resolveName(node.text)?.let { names[node] = it }
            }
            else -> {}
        }
    }

    override fun declaration(node: Node, lens: CheckedLens) {
        when (node) {
            is Parameter -> recordParameter(node, lens)
            is FunctionDeclaration -> recordSignature(node, node.name, lens)
            is MethodDeclaration -> recordMethodSignature(node, lens)
            is PropertyDeclaration -> recordPropertyType(node, lens)
            else -> {}
        }
    }

    private fun remember(type: Type, lens: CheckedLens): Type {
        renderings.getOrPut(type.id) { lens.render(type) }
        return type
    }

    private fun recordParameter(node: Parameter, lens: CheckedLens) {
        if (node in parameterTypes) return
        val name = node.name as? Identifier ?: return
        // A parameter is deliberately absent from the binder's `nodeToSymbol` —
        // `declareLexical` records into the SEPARATE scope-symbol id space — so the
        // position's own lexical chain is what names it. And `declaredTypeOfSymbol`
        // answers `any` for one: its worker has arms for the type-declaration kinds
        // and an `else -> anyType` a value symbol falls through.
        val symbol = lens.resolveName(name.text) ?: return
        parameterTypes[node] = remember(lens.typeOfSymbol(symbol), lens)
    }

    private fun recordSignature(node: Node, name: Identifier?, lens: CheckedLens) {
        if (node in declaredSignatures || name == null) return
        lens.callSignatures(name).firstOrNull()?.let { declaredSignatures[node] = it }
    }

    /**
     * A method's signature, reached through the CLASS's type.
     *
     * The obvious route — `callSignatures` of the method's own name node — is
     * measured NOT to work: that asks what a free name `increment` is callable
     * as, and a member name is bound by no scope, so it answers nothing. The
     * class's member table is where a method lives, and its symbol's type is
     * the function type whose one call signature carries the return type.
     */
    private fun recordMethodSignature(node: MethodDeclaration, lens: CheckedLens) {
        if (node in declaredSignatures) return
        val name = node.name as? Identifier ?: return
        val member = classMember(node.parent, name.text, lens) ?: return
        val memberType = remember(lens.typeOfSymbol(member), lens)
        (memberType as? Type.Object)?.callSignatures?.firstOrNull()
            ?.let { declaredSignatures[node] = it }
    }

    /**
     * The symbol a class member's name resolves to on the enclosing class's own
     * type — the one question about a member that has an honest answer here.
     */
    private fun classMember(container: Node?, name: String, lens: CheckedLens): Symbol? {
        val owner = container as? ClassDeclaration ?: return null
        val ownerName = owner.name ?: return null
        val classSymbol = lens.resolveName(ownerName.text) ?: return null
        return lens.membersOf(lens.declaredTypeOfSymbol(classSymbol), name).firstOrNull()
    }

    /**
     * Records a property's type by asking the ENCLOSING CLASS's type for the
     * member, rather than asking the property's own name node.
     *
     * The name node route looks equivalent and is not: a member name is bound by
     * no scope, so typing it resolves the SPELLING — it answers about whatever
     * unrelated binding happens to share the name, and `any` only where nothing
     * does. Here the class type is available and is the honest question.
     */
    private fun recordPropertyType(node: PropertyDeclaration, lens: CheckedLens) {
        if (node in declaredMemberTypes) return
        val name = node.name as? Identifier ?: return
        val member = classMember(node.parent, name.text, lens) ?: return
        declaredMemberTypes[node] = remember(lens.typeOfSymbol(member), lens)
    }

    /**
     * The call fact, including the SELECTED overload.
     *
     * Asked here and nowhere else. Overload selection is neither memoised nor
     * pure — it derives argument types through walk-scoped state and takes a
     * second chance against the narrowed type — so the same signatures and the
     * same argument nodes can select differently at a different point in the
     * walk. This is the single reason the sink seam exists in `-core` at all.
     *
     * The `singleOrNull` fallback covers the shape where selection declines but
     * there is nothing to choose between: a lone signature IS the answer, and
     * refusing there would reject every ordinary call whose arguments needed a
     * coercion selection does not model.
     */
    private fun callFact(node: CallExpression, lens: CheckedLens): CallFact {
        val callee = node.expression
        val signatures = lens.callSignatures(callee)
        val selected = lens.selectOverload(signatures, node.arguments)
            ?: signatures.singleOrNull()
        var receiverTypeText: String? = null
        var memberName: String? = null
        if (callee is PropertyAccessExpression) {
            memberName = callee.name.text
            receiverTypeText = render(remember(lens.typeOf(callee.expression), lens))
        }
        return CallFact(selected, signatures.size, receiverTypeText, memberName)
    }

}

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
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.NewExpression
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.kir.model.Signature
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.kir.model.Symbol
import com.xemantic.typescript.compiler.kir.model.Type
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
 * Everything the lowering will ask about, answered by tsgo's checker after the
 * check (`TsgoFacts`) and filed by node.
 *
 * Keyed by NODE IDENTITY throughout, via [IdentityHashMap]: an AST node is a
 * Kotlin `data class`, so a `HashMap` keyed by one deep-recurses `hashCode()`
 * over its whole subtree.
 */
public class CheckedFacts internal constructor() {

    private val checkedFiles = mutableListOf<SourceFile>()
    private val expressionTypes = IdentityHashMap<Expression, Type>()
    private val calls = IdentityHashMap<CallExpression, CallFact>()
    private val constructions = IdentityHashMap<NewExpression, Signature?>()
    private val members = IdentityHashMap<PropertyAccessExpression, Symbol>()
    private val names = IdentityHashMap<Identifier, Symbol>()
    private val namespaceExports = IdentityHashMap<Identifier, Map<String, Symbol>>()
    private val parameterTypes = IdentityHashMap<Parameter, Type>()
    private val declaredSignatures = IdentityHashMap<Node, Signature>()
    private val declaredMemberTypes = IdentityHashMap<Node, Type>()

    /** The checker's own rendering of a type, keyed by `Type.id`. */
    private val renderings = HashMap<Int, String>()

    /**
     * Every program file the check walked, in check order.
     *
     * DEDUPED by identity: a `declarationOnly` pre-pass walks the same trees,
     * and a file lowered twice would declare every symbol twice.
     */
    public val files: List<SourceFile> get() = checkedFiles


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

    /**
     * (LIB.8) Every name a NAMESPACE IMPORT alias makes visible, keyed by the name
     * an IMPORTER sees — stars followed, a renaming `export { x as y }` re-keyed.
     *
     * Asked of the lens rather than read off the module symbol's own `exports`,
     * because that table IS the target file's `locals`: a star re-export
     * contributes nothing to it, a renaming specifier is keyed by the DECLARED
     * name, and a module-private `const` is in it. Null both for a name that is
     * not a namespace alias and for a module whose export set is UNKNOWABLE, and
     * the consumer must refuse on the second rather than build an empty object.
     */
    public fun namespaceExportsAt(node: Identifier): Map<String, Symbol>? = namespaceExports[node]

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

    // ---- the writers the tsgo front end fills the tables through ----
    //
    // The ported tsgo checker answers post hoc, so `TsgoFacts` walks the program
    // once and writes every answer here. (Before (TSGO.7-c) `-core`'s checker
    // could fill them from inside its own walk, through its `CheckedNodeSink`.)

    internal fun putFile(node: SourceFile) {
        if (checkedFiles.none { it === node }) checkedFiles.add(node)
    }

    internal fun putType(node: Expression, type: Type) { expressionTypes[node] = type }

    internal fun putCall(node: CallExpression, fact: CallFact) { calls[node] = fact }

    internal fun putConstruction(node: NewExpression, signature: Signature?) {
        constructions[node] = signature
    }

    internal fun putMember(node: PropertyAccessExpression, symbol: Symbol) { members[node] = symbol }

    internal fun putName(node: Identifier, symbol: Symbol) { names[node] = symbol }

    internal fun putNamespaceExports(node: Identifier, exports: Map<String, Symbol>) {
        namespaceExports[node] = exports
    }

    internal fun putParameterType(node: Parameter, type: Type) { parameterTypes[node] = type }

    internal fun putSignature(node: Node, signature: Signature) { declaredSignatures[node] = signature }

    internal fun putMemberType(node: Node, type: Type) { declaredMemberTypes[node] = type }

    internal fun putRendering(type: Type, text: String) { renderings[type.id] = text }

}

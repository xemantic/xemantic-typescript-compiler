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
 * TypeMapper — maps type parameters to concrete types during generic instantiation.
 *
 * (INV.0) step 3: hoisted out of `Checker` with the instantiation family below; the
 * six ad-hoc `TypeMapper { tp -> … }` sites the checker still constructs (inference
 * pinning, literal/infer layering, signature-TP erasure) are unchanged — the type
 * merely became file-level so both files can name it.
 */
internal fun interface TypeMapper {
    fun map(typeParam: Type.TypeParam): Type?
}

/**
 * The positional mapper: the i-th of [typeParams] maps to the i-th of [typeArgs]
 * (by IDENTITY — a `Type.TypeParam` is compared as an instance); a parameter past
 * the argument list, or one not in the list at all, answers null and is left as
 * itself by every instantiator. A pure function, so it lives at file level and is
 * pinned without a checker (`TypeInstantiatorTest`).
 */
internal fun createTypeMapper(typeParams: List<Type.TypeParam>, typeArgs: List<Type>): TypeMapper {
    return TypeMapper { tp ->
        val index = typeParams.indexOf(tp)
        if (index >= 0 && index < typeArgs.size) typeArgs[index] else null
    }
}

/**
 * (INV.0) step 3 — the INSTANTIATION seam, tsgo's `instantiate*` family of
 * `internal/checker`: substituting type parameters through a [TypeMapper] over
 * types and signatures. Extracted VERBATIM from `Checker.kt` (design § 6 Stage 0,
 * "instantiation" in the core order), as a final class constructed once per
 * `Checker` — no semantic change, every call site a one-line delegation.
 *
 * ## Ambient surface (the ledger row, `docs/inversion-ambient-ledger.md` § 3)
 *
 * NOT empty, and stated rather than hidden: the family RESOLVES member and
 * parameter types (`checker.getTypeOfSymbol`), NORMALIZES rebuilt unions and
 * intersections (`checker.getUnionType` / `getIntersectionType` — the reduction
 * rules live with the checker, only identity moved in step 1), interns rebuilt
 * references ([interner]) and WRITES the instantiated parameter/member symbols'
 * types into [symbolTypes] (the id-keyed type table, handed in as the object it
 * is). Those three checker methods are the reads a later stage must make explicit;
 * they are reached through the FINAL [Checker] class — a direct call, no interface,
 * no captured lambda (contract § 10).
 *
 * ## What is deliberately preserved
 *
 * [instantiateType] returns a function-shaped anonymous `Type.Object` UNCHANGED
 * (the CLAUDE.md "instantiateType for Type.Object" gotcha); the fn-aware and the
 * contextual variants exist precisely because callers rely on that, and the split
 * keeps all three exactly as they were, including which one each caller uses.
 */
internal class TypeInstantiator(
    private val checker: Checker,
    /** The checker's id-keyed symbol-type table: written for every rebuilt symbol. */
    private val symbolTypes: IntKeyMap<Type>,
    private val interner: TypeInterner,
) {

    /**
     * TypeMapper — maps type parameters to concrete types during generic instantiation.
     */
    /**
     * Create a TypeMapper from parallel lists of type parameters and type arguments.
     */
    /**
     * Recursively substitute type parameters in a type according to the given mapper.
     * Returns the same type if no substitution occurs.
     */
    fun instantiateType(type: Type, mapper: TypeMapper): Type {
        return when (type) {
            is Type.TypeParam -> mapper.map(type) ?: type
            is Type.Union -> {
                val mapped = type.types.map { instantiateType(it, mapper) }
                if (mapped.zip(type.types).all { (a, b) -> a === b }) type
                else checker.getUnionType(mapped)
            }
            is Type.Intersection -> {
                val mapped = type.types.map { instantiateType(it, mapper) }
                if (mapped.zip(type.types).all { (a, b) -> a === b }) type
                else checker.getIntersectionType(mapped)
            }
            is Type.Reference -> {
                val args = type.resolvedTypeArguments ?: return type
                val mapped = args.map { instantiateType(it, mapper) }
                if (mapped.zip(args).all { (a, b) -> a === b }) type
                else interner.reference(type.target, mapped)
            }
            is Type.Object -> {
                // B52.3: For anonymous Type.Object (no Interface/Reference subclass,
                // no symbol, no call/construct signatures), walk members and substitute
                // TypeParam-typed property types. This unlocks per-property TS2322 at
                // object-literal args under explicit type args (e.g. `foo<number>({x:3, y:""})`
                // against `(n: {x:T, y:T})`). Narrow gate: only PURE PROPERTY-BAG anonymous
                // objects — function-shaped objects (call/construct sigs) and named types
                // are still returned as-is to avoid broad regressions (see CLAUDE.md
                // "instantiateType for Type.Object" gotcha).
                if (type is Type.Interface || type is Type.Reference) return type
                if (type.symbol != null) return type
                if (!type.callSignatures.isNullOrEmpty()) return type
                if (!type.constructSignatures.isNullOrEmpty()) return type
                val origMembers = type.members ?: return type
                if (origMembers.isEmpty()) return type
                var anyChanged = false
                val newMembers: SymbolTable = mutableMapOf()
                val newProps = mutableListOf<Symbol>()
                for ((name, memberSym) in origMembers) {
                    val memberType = checker.getTypeOfSymbol(memberSym)
                    val instMemberType = instantiateType(memberType, mapper)
                    if (instMemberType === memberType) {
                        newMembers[name] = memberSym
                        newProps.add(memberSym)
                    } else {
                        anyChanged = true
                        val newSym = Symbol(memberSym.flags, memberSym.name)
                        newSym.declarations.addAll(memberSym.declarations)
                        newSym.valueDeclaration = memberSym.valueDeclaration
                        symbolTypes[newSym.id] = instMemberType
                        newMembers[name] = newSym
                        newProps.add(newSym)
                    }
                }
                if (!anyChanged) return type
                val newObj = Type.Object()
                newObj.members = newMembers
                newObj.properties = newProps
                newObj
            }
            // Intrinsic, literal types don't contain type parameters
            else -> type
        }
    }

    /**
     * B86.1b (activation, 2026-05-28): instantiate a CONTEXTUAL parameter type through an
     * inference mapper. Unlike [instantiateType] (which deliberately returns
     * function-shaped Type.Object UNCHANGED — see CLAUDE.md "instantiateType for
     * Type.Object" gotcha), this helper DOES descend into a function-shaped
     * Type.Object's call signatures so a contextual callback type like `(x: T) => U`
     * becomes `(x: <mapped T>) => <mapped U>`. This is what lets the un-annotated lambda
     * param `x` resolve to the concrete inferred type during the diagnostic walk
     * (`checkPropertyAccessInExpr`'s ArrowFunction / FunctionExpression branches push
     * `currentLocalTypes[x] = <contextual sig param type>`). Used ONLY at the
     * checkPropertyAccessInExpr CallExpression arg-context computation — narrowly scoped
     * so the existing instantiateType no-op behavior (relied on elsewhere) is untouched.
     */
    fun instantiateContextualParamType(type: Type, mapper: TypeMapper): Type {
        if (type is Type.Object && type !is Type.Interface && type !is Type.Reference &&
            type.symbol == null && !type.callSignatures.isNullOrEmpty() &&
            type.constructSignatures.isNullOrEmpty()
        ) {
            val newSigs = type.callSignatures!!.map { instantiateContextualSignature(it, mapper) }
            // Avoid allocating a fresh object when nothing changed (identity preserved
            // per-signature is not guaranteed, so compare element-wise on the sigs).
            if (newSigs.zip(type.callSignatures!!).all { (a, b) -> a === b }) return type
            val newObj = Type.Object()
            newObj.callSignatures = newSigs
            newObj.properties = type.properties
            newObj.members = type.members
            return newObj
        }
        return instantiateType(type, mapper)
    }

    /**
     * B83.4d: like [instantiateSignature] but uses [instantiateContextualParamType]
     * (rather than the function-shape-no-op [instantiateType]) for BOTH parameter
     * types AND the return type, so a callback-returning-a-callback contextual type
     * `() => (a: T) => void` substitutes its inner `(a: T)` to `(a: <mapped>)`.
     * Used only by the contextual-param substitution path in [checkPropertyAccessInExpr];
     * preserves [instantiateSignature]'s behavior for non-function-shaped members.
     */
    fun instantiateContextualSignature(sig: Signature, mapper: TypeMapper): Signature {
        val newReturnType = sig.resolvedReturnType?.let { instantiateContextualParamType(it, mapper) }
        val newParams = sig.parameters.map { param ->
            val paramType = checker.getTypeOfSymbol(param)
            val instantiated = instantiateContextualParamType(paramType, mapper)
            if (instantiated !== paramType) {
                val newParam = Symbol(param.flags, param.name)
                newParam.declarations.addAll(param.declarations)
                newParam.valueDeclaration = param.valueDeclaration
                symbolTypes[newParam.id] = instantiated
                newParam
            } else param
        }
        return Signature(
            declaration = sig.declaration,
            typeParameters = null,
            parameters = newParams,
            resolvedReturnType = newReturnType ?: sig.resolvedReturnType,
            minArgumentCount = sig.minArgumentCount,
        )
    }

    /**
     * Instantiate a signature with type arguments — substitute type params in parameter types
     * and return type.
     */
    fun instantiateSignature(sig: Signature, mapper: TypeMapper): Signature {
        val newReturnType = sig.resolvedReturnType?.let { instantiateType(it, mapper) }
        // Instantiate parameter types — create new symbols with mapped types
        val newParams = sig.parameters.map { param ->
            val paramType = checker.getTypeOfSymbol(param)
            val instantiated = instantiateType(paramType, mapper)
            if (instantiated !== paramType) {
                val newParam = Symbol(param.flags, param.name)
                newParam.declarations.addAll(param.declarations)
                newParam.valueDeclaration = param.valueDeclaration
                symbolTypes[newParam.id] = instantiated
                newParam
            } else param
        }
        return Signature(
            declaration = sig.declaration,
            typeParameters = null, // instantiated signature has no type parameters
            parameters = newParams,
            resolvedReturnType = newReturnType ?: sig.resolvedReturnType,
            minArgumentCount = sig.minArgumentCount,
        )
    }

    /**
     * 17.39: Substitute outer typeArgs into a freshly-resolved function-typed property's
     * inner generic signatures — typeParam constraints/defaults, param types, return
     * type. Mutates [rawType]'s callSignatures/constructSignatures lists in place; the
     * inner Signatures' typeParameters are also mutated in place (their `constraint` /
     * `default` fields reassigned). Caller must guarantee [rawType] is freshly allocated
     * (e.g. resolved through `getTypeFromTypeNode` while `currentTypeParamScope != null`)
     * — this is currently the case in `resolveGenericPropertyType`'s PropertyDeclaration
     * branch. Preserves the inner sig's typeParameter list (T stays generic) so call-site
     * inference + constraint check via 16.4ds / 16.4i still fires.
     */
    fun substituteOuterTypeArgsInGenericFnObject(rawType: Type.Object, mapper: TypeMapper) {
        rawType.callSignatures = rawType.callSignatures?.map { substituteOuterTypeArgsInSignature(it, mapper) }
        rawType.constructSignatures = rawType.constructSignatures?.map { substituteOuterTypeArgsInSignature(it, mapper) }
    }

    /**
     * Round 465: like [instantiateType] but DESCENDS into anonymous FUNCTION-SHAPED
     * `Type.Object`s (call/construct signatures) and unions containing them —
     * [instantiateType] deliberately no-ops those (see the CLAUDE.md gotcha), which
     * left a generic interface member's fn-typed RETURN carrying the raw outer
     * TypeParam through the relation: `interface Sel<T> { select(index: number):
     * ((node: T) => T) | undefined }` instantiated as `Sel<TypeNode>` kept `T` in the
     * method's return, failing a conforming object literal (tsc emitter.ts
     * OrdinalParentheizerRuleSelector). Mints FRESH objects (never mutates), preserves
     * identity when nothing changes, and preserves signature type parameters.
     */
    fun instantiateTypeFnAware(type: Type, mapper: TypeMapper): Type {
        return when {
            type is Type.Union -> {
                val mapped = type.types.map { instantiateTypeFnAware(it, mapper) }
                if (mapped.zip(type.types).all { (a, b) -> a === b }) type else checker.getUnionType(mapped)
            }
            type is Type.Object && type !is Type.Interface && type !is Type.Reference &&
                type.symbol == null && type.tupleElementTypes == null &&
                (!type.callSignatures.isNullOrEmpty() || !type.constructSignatures.isNullOrEmpty()) -> {
                val newCall = type.callSignatures?.map { instantiateSignatureFnAware(it, mapper) }
                val newCtor = type.constructSignatures?.map { instantiateSignatureFnAware(it, mapper) }
                val unchanged =
                    (newCall == null || newCall.zip(type.callSignatures!!).all { (a, b) -> a === b }) &&
                        (newCtor == null || newCtor.zip(type.constructSignatures!!).all { (a, b) -> a === b })
                if (unchanged) type
                else Type.Object().also { o ->
                    o.callSignatures = newCall
                    o.constructSignatures = newCtor
                    o.members = type.members
                    o.properties = type.properties
                    o.stringIndexInfo = type.stringIndexInfo
                    o.numberIndexInfo = type.numberIndexInfo
                }
            }
            else -> instantiateType(type, mapper)
        }
    }

    /** Companion of [instantiateTypeFnAware]: [instantiateSignature] with fn-aware
     *  param/return instantiation and signature type parameters PRESERVED (the sig
     *  stays generic at the use site); returns the SAME instance when nothing maps. */
    fun instantiateSignatureFnAware(sig: Signature, mapper: TypeMapper): Signature {
        val newReturnType = sig.resolvedReturnType?.let { instantiateTypeFnAware(it, mapper) }
        val newParams = sig.parameters.map { param ->
            val paramType = checker.getTypeOfSymbol(param)
            val instantiated = instantiateTypeFnAware(paramType, mapper)
            if (instantiated !== paramType) {
                val newParam = Symbol(param.flags, param.name)
                newParam.declarations.addAll(param.declarations)
                newParam.valueDeclaration = param.valueDeclaration
                symbolTypes[newParam.id] = instantiated
                newParam
            } else param
        }
        val paramsChanged = newParams.zip(sig.parameters).any { (a, b) -> a !== b }
        if (!paramsChanged && newReturnType === sig.resolvedReturnType) return sig
        return Signature(
            declaration = sig.declaration,
            typeParameters = sig.typeParameters,
            parameters = newParams,
            resolvedReturnType = newReturnType ?: sig.resolvedReturnType,
            minArgumentCount = sig.minArgumentCount,
        )
    }

    fun substituteOuterTypeArgsInSignature(sig: Signature, mapper: TypeMapper): Signature {
        // Mutate sig's typeParameter constraints/defaults in place — they reference the
        // outer interface's TypeParams (e.g. `<T extends S>` where S is the outer one),
        // and we want substituted forms visible at the call site without erasing T's
        // generic identity. TPs are fresh per-call (see uncached getTypeFromTypeNode).
        sig.typeParameters?.forEach { tp ->
            tp.constraint = tp.constraint?.let { instantiateType(it, mapper) }
            tp.default = tp.default?.let { instantiateType(it, mapper) }
        }
        // Round 465: fn-AWARE return/param instantiation — a NESTED fn type inside the
        // sig (`get: (index: number) => ((node: T) => T) | undefined`) otherwise keeps
        // the raw outer TypeParam (instantiateType no-ops fn-shaped objects).
        val newReturnType = sig.resolvedReturnType?.let { instantiateTypeFnAware(it, mapper) }
        val newParams = sig.parameters.map { param ->
            val paramType = checker.getTypeOfSymbol(param)
            val instantiated = instantiateTypeFnAware(paramType, mapper)
            if (instantiated !== paramType) {
                val newParam = Symbol(param.flags, param.name)
                newParam.declarations.addAll(param.declarations)
                newParam.valueDeclaration = param.valueDeclaration
                symbolTypes[newParam.id] = instantiated
                newParam
            } else param
        }
        val paramsChanged = newParams.zip(sig.parameters).any { (a, b) -> a !== b }
        val returnChanged = newReturnType !== sig.resolvedReturnType
        if (!paramsChanged && !returnChanged) return sig
        return Signature(
            declaration = sig.declaration,
            typeParameters = sig.typeParameters, // preserve — still generic at call site
            parameters = newParams,
            resolvedReturnType = newReturnType ?: sig.resolvedReturnType,
            minArgumentCount = sig.minArgumentCount,
        )
    }

}

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
                // (CHK.96) stage 2: a TUPLE is rebuilt as a tuple — its slots mapped, its
                // flags kept — never as the member walk's plain `{ 0: T; length: N; }`.
                type.tupleElementTypes?.let { elems ->
                    val mapped = elems.map { instantiateType(it, mapper) }
                    return if (mapped.zip(elems).all { (a, b) -> a === b }) type
                    else checker.instantiateTupleElements(type, mapped)
                }
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
     * 17.39: Substitute outer typeArgs into a function-typed property's inner generic
     * signatures — typeParam constraints/defaults, param types, return type. Preserves
     * the inner sig's typeParameter list (T stays generic) so call-site inference + the
     * constraint check via 16.4ds / 16.4i still fires. Answers [rawType] ITSELF when
     * nothing moved, so identity is preserved for the non-generic majority.
     *
     * ## (CHK.102) — this used to MUTATE [rawType] in place, and that was a shipped
     * first-touch freeze
     *
     * The pre-(CHK.102) body assigned the substituted signature lists back onto
     * [rawType]'s own fields, with a KDoc precondition that the caller had freshly
     * allocated it ("`getTypeFromTypeNode` bypasses its cache when
     * `currentTypeParamScope != null`"). That precondition was TRUE when 17.39 was
     * written and INV.5(c) later made it false: `getTypeFromTypeNodeCore`'s `cacheable`
     * gate does refuse the plain `nodeTypes` map under a type-param scope, but the
     * bypassed path then consults a SECOND, context-KEYED cache
     * (`getTypeFromTypeNodeBypassed` → `state.mappedNodeTypes`, keyed by
     * `(node identity, ns/tpScope/aliasArgs fingerprint)`). Two instantiations of one
     * generic interface resolve the SAME annotation node under the SAME tpScope — the
     * target's own `Type.TypeParam` is in scope both times — so the fingerprints are
     * equal and the second ask is served the first ask's object.
     *
     * Measured on `interface Box<T> { f: (x: T) => T }` with a `Box<number>` and a
     * `Box<string>` in one file: a probe printed `rawId=35 before=(x: T) => T` for the
     * first and `rawId=35 before=(x: number) => number` for the second — one object,
     * already substituted, and the second substitution then a no-op. The observable is
     * a FALSE TS2345 on `bs.f("a")`, a LOST one on `bs.f(1)`, and the wrong type
     * everywhere `bs.f` is displayed; declaration order decides which instantiation is
     * right, so a pin written in one order is green on the frozen binary.
     *
     * Minting instead of mutating is the round-465 discipline
     * ([instantiateTypeFnAware] "mints FRESH objects, never mutates") applied to the
     * one member of this family that had kept the in-place form. It is strictly safer
     * than restoring the precondition by suppressing the cache: a shared *input* is now
     * fine, which is the property a cache may not silently take away.
     */
    fun substituteOuterTypeArgsInGenericFnObject(rawType: Type.Object, mapper: TypeMapper): Type.Object {
        val oldCall = rawType.callSignatures
        val oldCtor = rawType.constructSignatures
        val newCall = oldCall?.map { substituteOuterTypeArgsInSignature(it, mapper) }
        val newCtor = oldCtor?.map { substituteOuterTypeArgsInSignature(it, mapper) }
        val callSame = oldCall == null || newCall!!.zip(oldCall).all { (a, b) -> a === b }
        val ctorSame = oldCtor == null || newCtor!!.zip(oldCtor).all { (a, b) -> a === b }
        if (callSame && ctorSame) return rawType
        return Type.Object(rawType.flags).also { o ->
            o.symbol = rawType.symbol
            o.callSignatures = newCall
            o.constructSignatures = newCtor
            o.members = rawType.members
            o.properties = rawType.properties
            o.stringIndexInfo = rawType.stringIndexInfo
            o.numberIndexInfo = rawType.numberIndexInfo
            o.tupleElementTypes = rawType.tupleElementTypes
            o.readonlyTuple = rawType.readonlyTuple
            o.tupleRestIndex = rawType.tupleRestIndex
        }
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

    /**
     * 17.39's per-signature half: the outer type arguments substituted into one inner
     * signature, with the signature's OWN type parameters preserved as parameters (so
     * `f: <U extends T>(x: U) => U` stays generic at the call site and only its
     * CONSTRAINT is substituted).
     *
     * ## (CHK.102) — the type parameters are CLONED, not reassigned
     *
     * The pre-(CHK.102) body wrote `tp.constraint = instantiateType(tp.constraint, …)`
     * straight onto the signature's own `Type.TypeParam` objects, on the same "the
     * caller freshly allocated this" precondition INV.5(c) had already invalidated (see
     * [substituteOuterTypeArgsInGenericFnObject]'s KDoc for the measurement). That is
     * the SECOND freeze of the same family and it survives a fix to the first: with
     * `interface Box<T> { gen: <U extends T>(x: U) => U }` the object can be minted
     * fresh and `U.constraint` is still whatever the first instantiation left behind,
     * so `bn.gen(1)` reports `Argument of type 'number' is not assignable to parameter
     * of type 'string'` after a `Box<string>` was touched first.
     *
     * A moved constraint/default therefore mints a CLONE of the type parameter, and the
     * clones are composed onto [mapper] so the signature's params and return follow the
     * clone rather than keeping a reference to the original — otherwise the returned
     * signature would list a type parameter that appears nowhere in its own shape, and
     * call-site inference would have nothing to bind. A parameter whose constraint and
     * default are both unmoved is answered as ITSELF, which is the whole population for
     * a non-generic inner signature (`f: (x: T) => T`), so nothing is allocated there.
     */
    fun substituteOuterTypeArgsInSignature(sig: Signature, mapper: TypeMapper): Signature {
        val oldTps = sig.typeParameters
        var tpClones: MutableMap<Type.TypeParam, Type.TypeParam>? = null
        val newTps = oldTps?.map { tp ->
            val c = tp.constraint?.let { instantiateType(it, mapper) }
            val d = tp.default?.let { instantiateType(it, mapper) }
            if (c === tp.constraint && d === tp.default) tp
            else Type.TypeParam(c, d).also { clone ->
                clone.symbol = tp.symbol
                val m = tpClones ?: HashMap<Type.TypeParam, Type.TypeParam>().also { tpClones = it }
                m[tp] = clone
            }
        }
        // Compose the clone map UNDER the outer mapper so the signature's own shape
        // follows its cloned parameters. `Type.TypeParam` is a plain class, so the map
        // is identity-keyed (round 471's deep-hashCode hazard is about AST data classes).
        val clones = tpClones
        val effective = if (clones == null) mapper
            else TypeMapper { tp -> clones[tp] ?: mapper.map(tp) }
        // Round 465: fn-AWARE return/param instantiation — a NESTED fn type inside the
        // sig (`get: (index: number) => ((node: T) => T) | undefined`) otherwise keeps
        // the raw outer TypeParam (instantiateType no-ops fn-shaped objects).
        val newReturnType = sig.resolvedReturnType?.let { instantiateTypeFnAware(it, effective) }
        val newParams = sig.parameters.map { param ->
            val paramType = checker.getTypeOfSymbol(param)
            val instantiated = instantiateTypeFnAware(paramType, effective)
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
        val tpsChanged = clones != null
        if (!paramsChanged && !returnChanged && !tpsChanged) return sig
        return Signature(
            declaration = sig.declaration,
            typeParameters = newTps ?: sig.typeParameters, // preserve — still generic at call site
            parameters = newParams,
            resolvedReturnType = newReturnType ?: sig.resolvedReturnType,
            minArgumentCount = sig.minArgumentCount,
        )
    }

}

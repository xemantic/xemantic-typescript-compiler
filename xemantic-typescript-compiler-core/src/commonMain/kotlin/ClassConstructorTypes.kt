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
 * ([classExpressionType]) and, since (P18.256), a direct `new` callee, whose readers take the
 * constructed class back through [constructedClass]. A heritage expression keeps the
 * instance. `typeToString` renders a type minted here as
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

    /**
     * (P18.258) The construct signatures a constructor-less class INHERITS, with the
     * parameter types instantiated through the heritage type arguments — tsgo's
     * `getDefaultConstructSignatures` (the base constructor type's signatures, instantiated
     * with the base type arguments). [MemberResolver] copies the base REFERENCE's signatures,
     * but a generic class's own constructor parameter `v: T` was resolved with no class scope
     * (`errorType`), so `class H extends G<number> {}` checked no argument of `new H("s")`.
     * Here the declaring class's constructor parameters are re-resolved under its own type
     * parameters (`Checker.reresolveSigParamsUnderClassScope`) and instantiated with the
     * mapper composed down the extends chain. Expressed in [cls]'s own type parameters.
     * Null when [cls] declares a constructor, the chain carries no type argument, the
     * declaring base is not a resolvable class, or its constructor is overloaded.
     */
    fun inheritedConstructSignatures(cls: Type.Interface): List<Signature>? {
        if (ownConstructorClassDecl(cls) != null) return null
        var current = cls
        var mapper: TypeMapper? = null
        repeat(32) {
            val base = current.baseTypes?.firstOrNull() ?: return null
            val target: Type.Interface
            if (base is Type.Reference) {
                target = base.target
                val tps = target.typeParameters ?: return null
                val args = base.resolvedTypeArguments ?: return null
                if (tps.size != args.size) return null
                val m = mapper
                mapper = createTypeMapper(tps, if (m == null) args else args.map { checker.instantiateType(it, m) })
            } else {
                target = base as? Type.Interface ?: return null
            }
            if (target.symbol?.flags?.hasAny(SymbolFlags.Class) != true) return null
            if (ownConstructorClassDecl(target) != null) {
                val m = mapper ?: return null
                checker.resolveStructuredTypeMembers(target)
                val tps = target.typeParameters
                val sigs = target.constructSignatures?.filter { it.declaration is Constructor } ?: return null
                // An OVERLOADED inherited constructor stays with B264
                // (`checkInheritedOverloadedCtorArgs`), whose TS2769 the overload path does
                // not reproduce (`inheritedConstructorWithRestParams2`; both together double-emit).
                if (sigs.size != 1) return null
                return sigs.map { s ->
                    val r = if (tps.isNullOrEmpty()) s else checker.reresolveSigParamsUnderClassScope(s, tps)
                    checker.instantiateSignature(r, m)
                }
            }
            current = target
        }
        return null
    }

    /**
     * (P18.258) The signatures a `new` of [calleeType] checks its arguments against when the
     * constructed class declares no constructor: [inheritedConstructSignatures], further
     * instantiated with the call's explicit type arguments [typeArgs] for a generic class
     * (which without them is not inferred here, so null). Null keeps the caller's list.
     */
    fun inheritedNewSignatures(calleeType: Type, typeArgs: List<Type>?): List<Signature>? {
        val cls = constructedClass(calleeType) ?: return null
        val tps = cls.typeParameters
        if (!tps.isNullOrEmpty() && tps.size != typeArgs?.size) return null
        val inherited = inheritedConstructSignatures(cls) ?: return null
        if (tps.isNullOrEmpty() || typeArgs == null) return inherited
        val m = createTypeMapper(tps, typeArgs)
        return inherited.map { checker.instantiateSignature(it, m) }
    }

    /** The class declaration of [cls] when it declares a constructor, else null. */
    private fun ownConstructorClassDecl(cls: Type.Interface): Node? =
        cls.symbol?.declarations?.firstOrNull { d ->
            (d is ClassDeclaration && d.members.any { it is Constructor }) ||
                (d is ClassExpression && d.members.any { it is Constructor })
        }

    /**
     * (P18.258) tsgo's `getDefaultConstructSignatures` for a constructor-less class whose
     * base constructor type has NO construct signature gives ONE zero-parameter signature:
     * measured for `declare const Base: any; class D extends Base {}` (`new D(1, 2)` is
     * TS2554 "Expected 0 arguments"). Trusted here only where the base is decidable from the
     * syntax and the checker agrees — an identifier naming a FILE-LEVEL variable annotated
     * with the `any` keyword (an `any` from this checker's own gaps is never a reason to
     * count arguments), or a class whose own constructor side is the trusted default
     * (`class G extends B0 {}` over a constructor-less `B0`).
     */
    private fun baseConstructsWithNoArguments(decl: Node, base: Expression): Boolean {
        val id = base as? Identifier ?: return false
        val baseType = checker.getTypeOfExpression(id)
        if (baseType === anyType) {
            // The class sits at file level (a declaration, or a `const e = class …` initializer),
            // so no inner binding can shadow the file-level variable.
            var root: Node = (decl as NodeBase).parent ?: return false
            while (root !is SourceFile) {
                if (root !is VariableDeclaration && root !is VariableDeclarationList &&
                    root !is VariableStatement && root !is ParenthesizedExpression
                ) return false
                root = (root as NodeBase).parent ?: return false
            }
            val file: SourceFile = root
            val v = file.statements.asSequence().filterIsInstance<VariableStatement>()
                .flatMap { it.declarationList.declarations }
                .filter { (it.name as? Identifier)?.text == id.text }
                .singleOrNull() ?: return false
            return (v.type as? KeywordTypeNode)?.kind == SyntaxKind.AnyKeyword
        }
        val baseSym = (baseType as? Type.Interface)?.symbol ?: return false
        if (!baseSym.flags.hasAny(SymbolFlags.Class) || checker.getDeclaredTypeOfSymbol(baseSym) !== baseType) return false
        val sig = constructorTypeOfClass(baseSym)?.constructSignatures?.singleOrNull() ?: return false
        return sig.defaultConstructorOf != null
    }

    /**
     * (P18.258) Does `new` of [calleeType] construct an ABSTRACT class — tsgo's
     * `someSignature(constructSignatures, isAbstract)`, where a union's signatures carry
     * a constituent's abstract flag: measured TS2511 for `typeof ConcreteA | typeof AbstractA`
     * (`abstractClassUnionInstantiation`). A union counts only when every constituent is
     * constructable at all (otherwise the call is not constructable, a different row).
     */
    fun constructsAbstract(calleeType: Type): Boolean {
        if (calleeType is Type.Union) {
            if (calleeType.types.any { checker.getConstructSignaturesOfType(it).isEmpty() }) return false
            return calleeType.types.any { t -> checker.getConstructSignaturesOfType(t).any { it.isAbstract } }
        }
        return checker.getConstructSignaturesOfType(calleeType).any { it.isAbstract }
    }

    /** (P18.258) The `new` expressions (file, start) the name-based TS2511 walker reported;
     *  it runs at the expression's ENTER, before the type-based check at its LEAVE. */
    private val walkerAbstractRows = HashSet<Long>()

    private fun rowKey(fileName: String, start: Int): Long =
        (fileName.hashCode().toLong() shl 32) or (start.toLong() and 0xFFFFFFFFL)

    fun noteWalkerAbstractRow(fileName: String, start: Int) {
        walkerAbstractRows.add(rowKey(fileName, start))
    }

    fun walkerAbstractRowDrawn(fileName: String, start: Int): Boolean =
        walkerAbstractRows.isNotEmpty() && rowKey(fileName, start) in walkerAbstractRows

    /**
     * (CHK.196) stage 3 — tsgo's `removeSubtypes` (an array literal's element union is
     * `UnionReduction.Subtype`) restricted to the CONSTRUCTOR types among [members], which
     * are in union order: walking from the end, a constructor type that is a strict subtype
     * of another remaining one is dropped. `[Co, Ab]` over two empty classes is
     * `(typeof Ab)[]` and `[C, A]` with `C extends A` is `(typeof A)[]`, measured. Every
     * other member is untouched (this checker models no general subtype reduction).
     * Answers [members] itself when nothing is dropped.
     */
    fun reduceConstructorSubtypes(members: List<Type>): List<Type> {
        if (members.count { isConstructorType(it) } < 2) return members
        val kept = members.toMutableList()
        var i = kept.size - 1
        while (i >= 0) {
            val source = kept[i]
            if (isConstructorType(source) && kept.any { it !== source && isConstructorType(it) && strictlySubsumedBy(source, it) }) {
                kept.removeAt(i)
            }
            i--
        }
        return if (kept.size == members.size) members else kept
    }

    /**
     * (P18.262) [reduceConstructorSubtypes] applied to a whole union [t] — the shape the
     * array-literal element type and the conditional expression share (tsgo builds both
     * with `UnionReductionSubtype`: `cond ? C : A` with `C extends A` is `typeof A`). Any
     * other type, or a union with nothing dropped, is answered unchanged.
     */
    fun reduceConstructorSubtypesOf(t: Type): Type {
        if (t !is Type.Union) return t
        val reduced = reduceConstructorSubtypes(t.types)
        return if (reduced === t.types) t else if (reduced.size == 1) reduced[0] else checker.getUnionType(reduced)
    }

    /**
     * tsgo's `strictSubtypeRelation` between two constructor types, approximated as
     * assignability plus its STRICT ARITY rule (`compareSignaturesRelated`: a source
     * signature with more parameters than the target's is not a strict subtype), which is
     * what keeps `typeof O` (`constructor(x?: number)`) over `typeof Co` (no constructor) in
     * both orders.
     */
    private fun strictlySubsumedBy(source: Type, target: Type): Boolean {
        val s = source as Type.Object
        val t = target as Type.Object
        val tSigs = t.constructSignatures.orEmpty()
        val sSigs = s.constructSignatures.orEmpty()
        val arityOk = tSigs.all { ts ->
            val targetRest = (ts.parameters.lastOrNull()?.valueDeclaration as? Parameter)?.dotDotDotToken == true
            targetRest || sSigs.any { it.parameters.size <= ts.parameters.size }
        }
        return arityOk && checker.isTypeAssignableTo(source, target)
    }

    /**
     * (CHK.196) stage 3: the first REQUIRED static of constructor type [target] that
     * constructor type [source] lacks — tsgo's `propertiesRelatedTo` reports a missing member
     * before it compares any construct signature, as a TS2741 head (`Property 'sa' is missing
     * in type 'typeof B' but required in type 'typeof A'.`). Null unless both are construct
     * sources ([isConstructSource]) and a static is missing. (P18.262) widened from class
     * constructor types to any construct-only pair (`new () => St` against `typeof St`, cells
     * d03 / d06; `{ new (): C; s: number }` targets) — for such a pair only ONE missing member
     * is answered (two or more is tsgo's TS2739, which this does not model).
     */
    fun missingRequiredStatic(source: Type, target: Type): Symbol? {
        if (!isConstructSource(source) || !isConstructSource(target)) return null
        source as Type.Object; target as Type.Object
        if (isConstructorType(source) && isConstructorType(target)) {
            val members = source.members
            return target.properties.orEmpty().firstOrNull { p ->
                p.name != "prototype" && !checker.isOptionalProperty(p) && members?.get(p.name) == null
            }
        }
        checker.resolveStructuredTypeMembers(source)
        checker.resolveStructuredTypeMembers(target)
        val members = source.members
        val required = target.properties.orEmpty().filter { p ->
            p.name != "prototype" && !checker.isOptionalProperty(p) && members?.get(p.name) == null
        }
        // (P18.262) A construct-only source that is NOT a class constructor type (`new () =>
        // St`) has `Function`'s apparent members (`Relater.propertiesRelatedTo`); a required
        // target member spelled like one is a type question, never a missing member — refuse
        // rather than guess which row tsgo reports first.
        if (!isConstructorType(source) && required.any { functionApparentName(it.name) }) return null
        return required.singleOrNull()
    }

    private fun functionApparentName(name: String): Boolean =
        name in Checker.FUNCTION_PROTOTYPE_METHODS || name in Checker.FUNCTION_RUNTIME_PROPERTIES ||
            name in Checker.OBJECT_PROTOTYPE_PROPERTIES || name == "length" || name == "name" ||
            name.contains("hasInstance")

    /**
     * (P18.262) A CONSTRUCT-ONLY source — a class constructor type ([isConstructorType]) or an
     * anonymous constructor type spelled by a type node (`abstract new () => T`, `{ new (): A }`,
     * also after instantiation): an anonymous `Type.Object` carrying construct signatures and no
     * call signature. tsgo relates both through the same constructor side, so the argument gate
     * admits either against a constructor-typed parameter (`take(h.c)`, cell t05). A GENERIC
     * construct signature (`new <T>() => T`) is refused: tsgo instantiates it against the
     * target's signature first, which this relation does not, so admitting it is a false
     * TS2345 on legal code.
     */
    fun isConstructSource(type: Type): Boolean = isConstructorType(type) ||
        type is Type.Object && type !is Type.Interface && type !is Type.Reference &&
        !type.constructSignatures.isNullOrEmpty() && type.callSignatures.isNullOrEmpty() &&
        type.constructSignatures!!.all { it.typeParameters.isNullOrEmpty() }

    /** True for a type minted by [constructorTypeOfClass] (identity). */
    fun isConstructorType(type: Type): Boolean = type is Type.Object && type in minted

    /**
     * The class a constructor type CONSTRUCTS — the instance interface every construct
     * signature of a type minted here returns — or null for any other type. Since (P18.256)
     * a `new` callee is an ordinary value read (a class identifier answers its constructor
     * side like any other read), and the `new`-expression readers take the class from the
     * constructor type through this: explicit type arguments, constructor-argument inference
     * and the uninferred-default rule are keyed on the class's own type parameters, which
     * live on the instance (`Checker.getReturnTypeOfNewExpression`,
     * `constructSignaturesForNewCtx`, `inferSimpleReturnTypeFromBody`).
     */
    fun constructedClass(type: Type): Type.Interface? = (type as? Type.Object)?.let { minted[it] }

    /**
     * (P18.256) The constructor side of a `new` callee whose conventional callee type is the
     * class INSTANCE — a class identifier, `new (A)()`, `new A!()`, `new N.C()`: the same
     * answer a value read of that expression gives ([valueReadType] /
     * [qualifiedValueReadType]). Null keeps [t] (any other callee, or a callee already typed
     * as a constructor).
     */
    fun newCalleeConstructorSide(callee: Expression, t: Type): Type? {
        var e = callee
        while (e is ParenthesizedExpression || e is NonNullExpression) {
            e = if (e is ParenthesizedExpression) e.expression else (e as NonNullExpression).expression
        }
        return when (e) {
            is Identifier -> valueReadType(e, t)
            is PropertyAccessExpression -> qualifiedValueReadType(e, t)
            else -> null
        }
    }

    /**
     * (CHK.196) stage 2 — the type of an identifier READ of a class: when [t], what the
     * identifier typer answered for [id], is exactly the declared instance type of the class
     * [id] spells, and [id] sits in a value-read position, answer the class's constructor side.
     * Never for a heritage expression (`extends A` reads the instance); the right operand of
     * `instanceof` reads the constructor side since (P18.258). A direct `new` callee IS a value read
     * since (P18.256): the `new` readers take the class back through [constructedClass].
     * Null keeps [t].
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

    /**
     * Not a heritage expression. (P18.258) The right operand of `instanceof` IS a value use:
     * tsgo types it `typeof A`, and the exclusion stage 2 kept was dead — removing it moved
     * no corpus row and no `instanceof` narrowing cell (measured over class, abstract,
     * generic, `unknown` and type-parameter left operands).
     */
    private fun isValueUse(node: Node): Boolean {
        var p = (node as NodeBase).parent
        while (p is ParenthesizedExpression || p is NonNullExpression) p = (p as NodeBase).parent
        return p !is ExpressionWithTypeArguments && p !is HeritageClause
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
                sigs += Signature(resolvedReturnType = iface, isAbstract = isAbstract).also { sig ->
                    // A class with an `extends` clause and no constructor inherits the base's
                    // (an unresolvable base gives none here, which is not "zero parameters"),
                    // so a heritage-free class's default carries a trustworthy arity, and an
                    // extending one's only when the base is known to construct with none
                    // ([baseConstructsWithNoArguments]).
                    val decl = symbol.declarations.firstOrNull { it is ClassDeclaration || it is ClassExpression }
                    val heritage = when (decl) {
                        is ClassDeclaration -> decl.heritageClauses
                        is ClassExpression -> decl.heritageClauses
                        else -> null
                    }
                    val ext = heritage?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword }?.types?.firstOrNull()
                    if (decl != null && (ext == null || baseConstructsWithNoArguments(decl, ext.expression))) {
                        sig.defaultConstructorOf = decl
                    }
                }
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


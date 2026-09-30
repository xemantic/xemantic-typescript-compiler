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
 * (CHK.179)(a) — the missing-member row of a LITERAL-key element access `r["p"]` / `r[0]`.
 *
 * `checkSingleElementAccess` routes a literal key through the shared property-access funnel
 * `checkMemberAccessMissing`, which reports TS2339 at the KEY. tsgo never does that for an
 * element access (`getPropertyTypeForIndexType`, checker.go:26867): a literal key is
 * string-/number-like, so a receiver without the property and without an applicable index
 * signature takes the `noImplicitAny` block — SILENT without the flag, and with it TS2576 (a
 * static member of that name), TS7015 at the key (the receiver has a number index), TS2551 at
 * the key (a spelling suggestion) or TS7053 at the WHOLE access with the chain line
 * `Property 'p' does not exist on type 'R'.`. The funnel's own firewalls decide WHETHER a
 * member is missing; this class only restates what it emitted in tsgo's element-access terms.
 *
 * Left untouched, because tsgo reports TS2339 there too: a numeric key on a receiver whose
 * every constituent is a tuple (checker.go's `everyType(objectType, isTupleType)` arm), a
 * const-enum object (`isConstEnumObjectType` falls through to the key-anchored TS2339) and a
 * `globalThis` receiver (its block-scoped TS2339 arm).
 */
internal class ElementAccessMissingMember(
    private val checker: Checker,
    private val options: CompilerOptions,
) {

    private val noImplicitAny: Boolean
        get() = options.noImplicitAny ||
            (!options.noImplicitAnyExplicitlyFalse && !options.strictExplicitlyFalse)

    /**
     * Rewrites the rows `checkMemberAccessMissing` added to [Checker.diagnostics] from index
     * [from] for the access [expr] whose key starts at [keyStart] and whose whole span is
     * [fullStart]..[fullStart]+[fullLength].
     */
    fun restate(
        expr: ElementAccessExpression, propName: String, from: Int,
        keyStart: Int, keyLength: Int, fullStart: Int, fullLength: Int,
        source: String, fileName: String,
    ) {
        val diags = checker.diagnostics
        if (diags.size <= from) return
        val recvExpr = expr.expression
        if (recvExpr is Identifier && recvExpr.text == "globalThis") return
        val declaredRecv = checker.getTypeOfExpression(recvExpr)
        // tsgo indexes the APPARENT type: a type parameter answers its constraint (`unknown`
        // when it has none), and a string-like one the `String` wrapper with its number index.
        val recvType = if (declaredRecv is Type.TypeParam) checker.getApparentType(declaredRecv) else declaredRecv
        if (isConstEnumObject(recvType)) return
        val arg = expr.argumentExpression
        if (arg is NumericLiteralNode && everyTuple(recvType)) return
        val own = (from until diags.size).filter { i ->
            val d = diags[i]
            d.fileName == fileName && when (d.code) {
                2339, 2551 -> d.start == keyStart
                2576 -> d.start == fullStart
                else -> false
            }
        }
        if (own.isEmpty()) return
        if (!noImplicitAny) {
            for (i in own.asReversed()) diags.removeAt(i)
            return
        }
        for (i in own) {
            val d = diags[i]
            if (d.code != 2339) continue
            val recvDisplay = when {
                declaredRecv !is Type.TypeParam -> receiverDisplay(d.message) ?: continue
                recvType === anyType -> "unknown"
                else -> checker.typeToString(recvType)
            }
            if (hasNumberIndex(recvType)) {
                diags[i] = d.copy(
                    message = "Element implicitly has an 'any' type because index expression is not of type 'number'.",
                    code = 7015, start = keyStart, length = keyLength,
                )
                continue
            }
            val accessor = accessorSuggestion(expr, recvType, fullStart + fullLength, source)
            if (accessor != null) {
                diags[i] = d.copy(
                    message = "Element implicitly has an 'any' type because type '$recvDisplay' has no index signature. Did you mean to call '$accessor'?",
                    code = 7052, start = fullStart, length = fullLength,
                    line = checker.getLineAndCharacterOfPosition(source, fullStart).first,
                    character = checker.getLineAndCharacterOfPosition(source, fullStart).second,
                )
                continue
            }
            val indexDisplay = when (arg) {
                is StringLiteralNode -> "\"${arg.text}\""
                else -> propName
            }
            val (line, character) = checker.getLineAndCharacterOfPosition(source, fullStart)
            diags[i] = Diagnostic(
                message = "Element implicitly has an 'any' type because expression of type '$indexDisplay' can't be used to index type '$recvDisplay'.",
                category = DiagnosticCategory.Error, code = 7053,
                messageChain = listOf("  Property '$propName' does not exist on type '$recvDisplay'."),
                fileName = fileName, line = line, character = character,
                start = fullStart, length = fullLength,
            )
        }
    }

    private val aliasDisplay = AliasCarrierDisplay(checker)

    /**
     * (CHK.179)(a2) — a NON-literal key `r[k]` whose type is `string` or `number` on a receiver
     * with no applicable index signature. tsgo's `getPropertyTypeForIndexType` has no property
     * name for such a key and falls into the same `noImplicitAny` block as a missing literal
     * key: TS7015 at the key when the receiver has a number index (an array, a tuple, a
     * string), else TS7053 at the whole access with the chain `No index signature with a
     * parameter of type 'string' was found on type 'R'.` — SILENT without the flag.
     *
     * Deliberately narrow, because every guess here is a false positive on real code: the key
     * must be a reference (or a call / `+` / template) whose flow type equals its declared
     * type and whose declaration is not an unannotated `const` (tsgo keeps a literal there);
     * the receiver must be a user-declared object, class instance, union of those, or an
     * unconstrained type parameter — never a `.d.ts` / lib type (their index structure is the
     * part this checker models least), a mapped / intersection / enum / callable type, or a
     * nullish union outside an optional chain. Returns true when it emitted.
     */
    fun nonLiteralKey(expr: ElementAccessExpression, source: String, fileName: String): Boolean {
        if (!noImplicitAny) return false
        val arg = expr.argumentExpression
        val keyType = keyTypeOf(arg) ?: return false
        val recvExpr = expr.expression
        if (!annotatedReceiver(recvExpr)) return false
        val declared = checker.getTypeOfExpression(recvExpr)
        var recv = declared
        if (checker.getReferencePath(recvExpr) != null && checker.getNarrowedTypeForReference(declared, recvExpr) !== declared) return false
        val unconstrained = declared is Type.TypeParam && checker.getApparentType(declared) === anyType
        if (declared is Type.TypeParam) {
            if (!unconstrained) {
                val c = checker.getApparentType(declared)
                if (c is Type.TypeParam) return false
                recv = c
            }
        }
        var members = if (recv is Type.Union) recv.types else listOf(recv)
        if (members.any { it === nullType || it === undefinedType }) {
            if (!expr.questionDotToken) return false
            members = members.filter { it !== nullType && it !== undefinedType }
            if (members.isEmpty()) return false
        }
        val keyStart = arg.pos
        val keyEnd = checker.expressionTrueEnd(arg)
        var cb = keyEnd
        while (cb < source.length && source[cb] != ']') cb++
        if (cb >= source.length) return false
        val fullStart = recvExpr.pos
        val fullEnd = cb + 1
        if (!unconstrained && members.all { numberIndexed(it) }) {
            if (keyType !== stringType) return false
            val (line, character) = checker.getLineAndCharacterOfPosition(source, keyStart)
            checker.diagnostics.add(Diagnostic(
                message = "Element implicitly has an 'any' type because index expression is not of type 'number'.",
                category = DiagnosticCategory.Error, code = 7015, fileName = fileName,
                line = line, character = character, start = keyStart, length = keyEnd - keyStart,
            ))
            return true
        }
        if (!unconstrained && members.size == 1 && !numberIndexed(members[0])) {
            val m = members[0]
            if (m is Type.Object && (plainIndexless(m, allowSignatures = true) || m is Type.Reference && fromLib(m.target.symbol))) {
                val accessor = accessorSuggestion(expr, m, fullEnd, source, keyType)
                if (accessor != null) {
                    val shown = aliasDisplay.display(recvExpr, members) ?: checker.typeToString(m)
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, fullStart)
                    checker.diagnostics.add(Diagnostic(
                        message = "Element implicitly has an 'any' type because type '$shown' has no index signature. Did you mean to call '$accessor'?",
                        category = DiagnosticCategory.Error, code = 7052, fileName = fileName,
                        line = line, character = character, start = fullStart, length = fullEnd - fullStart,
                    ))
                    return true
                }
            }
        }
        if (!unconstrained && !members.all { plainIndexless(it) }) return false
        val display = when {
            unconstrained -> "unknown"
            recv !== declared -> checker.typeToString(recv)
            else -> aliasDisplay.display(recvExpr, members) ?: checker.typeToString(
                if (members.size == 1) members[0] else checker.getUnionType(members))
        }
        val keyDisplay = checker.typeToString(keyType)
        val chainKey = if (keyType is Type.Union) "string" else keyDisplay
        val (line, character) = checker.getLineAndCharacterOfPosition(source, fullStart)
        checker.diagnostics.add(Diagnostic(
            message = "Element implicitly has an 'any' type because expression of type '$keyDisplay' can't be used to index type '$display'.",
            category = DiagnosticCategory.Error, code = 7053,
            messageChain = listOf("  No index signature with a parameter of type '$chainKey' was found on type '$display'."),
            fileName = fileName, line = line, character = character,
            start = fullStart, length = fullEnd - fullStart,
        ))
        return true
    }

    /**
     * The receiver's type must come from something WRITTEN — an annotated parameter or
     * variable, a cast, or `this` — because an inferred type (a generic call's return, an
     * overload pick) is where this checker's answer most often differs from tsgo's, and a
     * wrong object type here is a false TS7053 on legal code.
     */
    private fun annotatedReceiver(e0: Expression): Boolean {
        var e = e0
        while (e is ParenthesizedExpression) e = e.expression
        return when (e) {
            is AsExpression, is TypeAssertionExpression -> true
            is Identifier -> {
                if (e.text == "this") return true
                if (e.text == "globalThis" || e.text == "undefined") return false
                when (val d = checker.lexicalReturnIdentifierDecl(e)) {
                    is Parameter -> d.type != null
                    is VariableDeclaration -> d.type != null || castInitializer(d.initializer)
                    else -> false
                }
            }
            else -> false
        }
    }

    /** (CHK.180) An `as T` / `<T>` initializer — a WRITTEN type — but not `as const`. */
    private fun castInitializer(init0: Expression?): Boolean {
        var init = init0
        while (init is ParenthesizedExpression) init = init.expression
        val t = when (init) {
            is AsExpression -> init.type
            is TypeAssertionExpression -> init.type
            else -> return false
        }
        return !WrittenReceiverTypes.isConstTypeRef(t)
    }

    /** `string`, `number` or `string | number` — else null (literal, `any`, template, enum, ...). */
    private fun keyTypeOf(arg: Expression): Type? {
        var e = arg
        while (e is ParenthesizedExpression) e = e.expression
        val t = when (e) {
            is Identifier -> identifierKeyType(e) ?: return null
            is BinaryExpression -> {
                // String concatenation with a string literal / template operand is `string`
                // whatever the other operand is.
                if (e.operator != SyntaxKind.Plus) return null
                if (!isStringText(e.left) && !isStringText(e.right)) return null
                stringType
            }
            is CallExpression -> if (isStringCall(e)) stringType else return null
            else -> return null
        }
        return t
    }

    private fun isStringText(e: Expression): Boolean =
        e is StringLiteralNode || e is NoSubstitutionTemplateLiteralNode || e is TemplateExpression

    /** `String(x)`, or a `String.prototype` method returning a string called on a `string`. */
    private fun isStringCall(call: CallExpression): Boolean {
        val callee = call.expression
        if (callee is Identifier) return callee.text == "String" && checker.lexicalReturnIdentifierDecl(callee) == null
        val pa = callee as? PropertyAccessExpression ?: return false
        if (pa.name.text !in STRING_METHODS) return false
        return checker.getTypeOfExpression(pa.expression) === stringType
    }

    /**
     * A key IDENTIFIER's type, only where the declaration WRITES it: a parameter or variable
     * annotated with the keyword `string` / `number` (or their union) — never a type that
     * merely resolves to `string` here (`key: keyof S` does, and tsgo keeps `keyof S`) — or a
     * for-in variable over a plain index-less object, and no flow narrowing moved it.
     */
    private fun identifierKeyType(e: Identifier): Type? {
        if (e.text == "undefined" || e.text == "arguments" || e.text == "this") return null
        val decl = checker.lexicalReturnIdentifierDecl(e) ?: return null
        val written: Type = when (decl) {
            is Parameter -> keywordKeyType(decl.type ?: return null) ?: return null
            is VariableDeclaration -> {
                val ann = decl.type
                if (ann != null) keywordKeyType(ann) ?: return null
                else {
                    // tsgo types a for-in variable `Extract<keyof T, string>` over a generic
                    // object and treats it as NUMERIC over an array-like
                    // (`isForInVariableForNumericPropertyNames`): only a for-in over a plain
                    // index-less object is a `string` key there.
                    val list = (decl as NodeBase).parent as? VariableDeclarationList ?: return null
                    val loop = (list as NodeBase).parent as? ForInStatement ?: return null
                    if (!annotatedReceiver(loop.expression)) return null
                    val over = checker.getTypeOfExpression(loop.expression)
                    val overMembers = if (over is Type.Union) over.types else listOf(over)
                    if (!overMembers.all { plainIndexless(it) }) return null
                    stringType
                }
            }
            else -> return null
        }
        val t = checker.getTypeOfExpression(e)
        if (t !== written && !(written is Type.Union && t is Type.Union && t.types.toSet() == written.types.toSet())) return null
        if (checker.getNarrowedTypeForReference(t, e) !== t) return null
        return t
    }

    private fun keywordKeyType(n: TypeNode): Type? {
        fun kw(x: TypeNode): Type? = when {
            x is KeywordTypeNode && x.kind == SyntaxKind.StringKeyword -> stringType
            x is KeywordTypeNode && x.kind == SyntaxKind.NumberKeyword -> numberType
            else -> null
        }
        var x = n
        while (x is ParenthesizedType) x = x.type
        if (x is UnionType) {
            val parts = x.types.map { kw(it) ?: return null }.toSet()
            return if (parts == setOf(stringType, numberType)) checker.getUnionType(listOf(stringType, numberType)) else parts.singleOrNull()
        }
        return kw(x)
    }

    /** Array, `ReadonlyArray`, a tuple or a string: tsgo answers TS7015 for a string key. */
    private fun numberIndexed(m: Type): Boolean = when {
        m === stringType -> true
        m !is Type.Object -> false
        m.tupleElementTypes != null -> true
        m is Type.Reference && m.target.symbol?.name.let { it == "Array" || it == "ReadonlyArray" } && fromLib(m.target.symbol) -> true
        m is Type.Reference -> false
        // A user-declared type whose only index signature is a NUMBER one.
        userDeclared(m) -> {
            checker.resolveStructuredTypeMembers(m)
            m.stringIndexInfo == null && m.numberIndexInfo != null
        }
        else -> false
    }

    private fun userDeclared(m: Type.Object): Boolean {
        val sym = m.symbol
        return if (sym != null) {
            sym.flags.hasAny(SymbolFlags.Interface or SymbolFlags.Class) && !fromLib(sym) &&
                !sym.flags.hasAny(SymbolFlags.Enum or SymbolFlags.EnumMember or SymbolFlags.ValueModule or SymbolFlags.NamespaceModule or SymbolFlags.Function)
        } else m.declaredAt is TypeLiteral
    }

    /** A user-declared object / interface / class instance with no index signature and no signatures. */
    private fun plainIndexless(m: Type, allowSignatures: Boolean = false): Boolean {
        if (m !is Type.Object) return false
        if (m.jsLiteral || m.tupleElementTypes != null) return false
        if (m is Type.Reference) return false
        if (!userDeclared(m)) return false
        checker.resolveStructuredTypeMembers(m)
        if (m.stringIndexInfo != null || m.numberIndexInfo != null) return false
        if (m is Type.Interface && (m.declaredStringIndexInfo != null || m.declaredNumberIndexInfo != null)) return false
        if (!allowSignatures && (!m.callSignatures.isNullOrEmpty() || !m.constructSignatures.isNullOrEmpty())) return false
        return true
    }

    /** Declared in a `.d.ts` file (a lib, `@types`, an ambient declaration file). */
    private fun fromLib(sym: Symbol?): Boolean {
        if (sym == null) return true
        return sym.declarations.any { d ->
            var n: Node? = d
            var hops = 0
            while (n != null && n !is SourceFile && hops++ < 256) n = (n as? NodeBase)?.parent
            val f = (n as? SourceFile)?.fileName ?: return@any true
            f.endsWith(".d.ts") || f.endsWith(".d.mts") || f.endsWith(".d.cts")
        }
    }

    /**
     * tsgo `getSuggestionForNonexistentIndexSignature`: the receiver has a `get` (read) or
     * `set` (write) member with ONE call signature taking at least one argument, and the
     * key's literal type is assignable to its first parameter — `recv.get` (the dotted
     * receiver path when it is an entity name, else the bare method name).
     */
    private fun accessorSuggestion(
        expr: ElementAccessExpression, recvType: Type, accessEnd: Int, source: String,
        keyTypeIn: Type? = null,
    ): String? {
        val method = if (checker.elementAccessIsWriteContext(source, accessEnd, expr.expression.pos)) "set" else "get"
        val obj = members(recvType).singleOrNull() as? Type.Object ?: return null
        val prop = checker.getPropertyOfType(obj, method) ?: return null
        val propType = checker.resolveGenericPropertyType(obj, prop) ?: checker.getTypeOfSymbol(prop)
        val fnType = propType as? Type.Object ?: return null
        checker.resolveStructuredTypeMembers(fnType)
        val sig = fnType.callSignatures?.singleOrNull() ?: return null
        if (sig.minArgumentCount < 1) return null
        val p0 = sig.parameters.firstOrNull() ?: return null
        val keyType = keyTypeIn ?: checker.literalTypeOfExpression(expr.argumentExpression) ?: return null
        if (!checker.isTypeAssignableTo(keyType, checker.getTypeOfSymbol(p0))) return null
        val path = checker.entityPathOf(expr.expression)
        return if (path != null) "$path.$method" else method
    }

    /** The `'R'` of a TS2339 `Property 'p' does not exist on type 'R'.` — the funnel's own display. */
    private fun receiverDisplay(message: String): String? {
        val marker = " does not exist on type '"
        val at = message.indexOf(marker)
        if (at < 0 || !message.endsWith("'.")) return null
        return message.substring(at + marker.length, message.length - 2)
    }

    private fun members(t: Type): List<Type> =
        if (t is Type.Union) t.types.filter { it !== nullType && it !== undefinedType } else listOf(t)

    private fun everyTuple(t: Type): Boolean {
        val ms = members(t)
        return ms.isNotEmpty() && ms.all { (it as? Type.Object)?.tupleElementTypes != null }
    }

    private fun isConstEnumObject(t: Type): Boolean =
        members(t).any { (it as? Type.Object)?.symbol?.flags?.hasAny(SymbolFlags.ConstEnum) == true }

    private fun hasNumberIndex(t: Type): Boolean {
        val ms = members(t)
        return ms.isNotEmpty() && ms.all { m ->
            when {
                m !is Type.Object -> false
                m.tupleElementTypes != null -> true
                m is Type.Reference && m.target.symbol?.name.let { it == "Array" || it == "ReadonlyArray" } -> true
                m is Type.Interface && m.declaredNumberIndexInfo != null -> true
                else -> {
                    checker.resolveStructuredTypeMembers(m)
                    m.numberIndexInfo != null
                }
            }
        }
    }

    private companion object {
        val STRING_METHODS = setOf(
            "toLowerCase", "toUpperCase", "toLocaleLowerCase", "toLocaleUpperCase", "trim", "trimStart",
            "trimEnd", "slice", "substring", "substr", "charAt", "replace", "replaceAll", "padStart",
            "padEnd", "concat", "normalize", "repeat", "toString",
        )
    }
}

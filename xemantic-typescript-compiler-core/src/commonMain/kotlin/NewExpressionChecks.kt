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
 * (INV.0) (P18.261) — the `new`-EXPRESSION CHECK family: the per-`new` checker
 * [checkSingleNewExpressionTypes] (TS2347 / TS2350 / TS2351 / TS2673 / TS2674 / TS2511 / TS7009
 * and the argument checks against the construct signature(s) the callee resolves to) with its
 * helpers (`newCalleeVarHoldsInstance`, `emitPrivateConstructorTs2673`,
 * `newExprAbstractConstructorTs2511`, `newCalleeNonNullType`, `typeofClassValueDisplay`,
 * `classExtendsOrIs`), and B264's inherited-overloaded-constructor check
 * [checkInheritedOverloadedCtorNew] (a pass until (CHK.199)). Extracted VERBATIM from `Checker.kt` (five spans:
 * 7224-7231, 164745-165501, 166602-166627, 166837-166855, 183589-183774); the options come in
 * through the constructor and every other Checker member it reads is reached through [checker].
 * Ambient reads: `docs/inversion-ambient-ledger.md` row 18.
 */
internal class NewExpressionChecks(
    private val checker: Checker,
    private val options: CompilerOptions,
) {

    /**
     * (CHK.137) round (P18.128) — the alias-hop budget of [newCalleeVarHoldsInstance].
     *
     * Four is `MemberNames.LATE_BIND_ALIAS_HOPS`' shape for the same job one seam over: it
     * terminates a `const a = b; const b = a` cycle, which is what the budget is FOR, and a
     * real alias chain is one or two hops.
     */
    private val NEW_CALLEE_CLASS_VALUE_HOPS = 4

    /**
     * Check argument types for a NewExpression against the construct signature.
     */
    /**
     * (CHK.137) round (P18.128) — does every declaration of this variable symbol hold an
     * INSTANCE (`new X()`, or an alias chain ending in one)? Asked by
     * [checkSingleNewExpressionTypes] for `const i = new Cls(); new i()`: a class that declares
     * a constructor used to register a construct signature on its instance type, so the
     * construct-signature read was silent where both references report TS2351.
     *
     * (P18.256) The CLASS-VALUE half is retired: since (CHK.196) stage 2 a variable holding a
     * class (`const c = Cls`) types as the class's constructor side, so the callee type is no
     * longer the instance this branch was gated on — measured unreached on the corpus, the
     * census matrix and the (CHK.137) pins before deletion.
     *
     * `false` whenever anything is unclear (an annotated declaration, any other initializer,
     * a cycle past [NEW_CALLEE_CLASS_VALUE_HOPS]), which falls through to the pre-existing
     * behaviour. A `let` reassigned between instances is decided from its declarations.
     */
    private fun newCalleeVarHoldsInstance(sym: Symbol, hops: Int = NEW_CALLEE_CLASS_VALUE_HOPS): Boolean {
        if (hops <= 0) return false
        val decls = sym.declarations.filterIsInstance<VariableDeclaration>()
        if (decls.isEmpty() || decls.size != sym.declarations.size) return false
        for (decl in decls) {
            if (decl.type != null) return false
            when (val init = decl.initializer) {
                is NewExpression -> {}
                is Identifier -> {
                    val target = checker.globals[init.text] ?: return false
                    if (!target.flags.hasAny(SymbolFlags.Variable) ||
                        target.flags.hasAny(SymbolFlags.Function or SymbolFlags.Module or SymbolFlags.Enum or SymbolFlags.Alias or SymbolFlags.Class)
                    ) return false
                    if (!newCalleeVarHoldsInstance(target, hops - 1)) return false
                }
                else -> return false
            }
        }
        return true
    }

    /** (P18.256) TS2673 — tsgo `isConstructorAccessible` for a PRIVATE constructor used outside
     *  its declaring class [declaring]: the whole `new` expression. */
    private fun emitPrivateConstructorTs2673(expr: NewExpression, declaring: Symbol, source: String, fileName: String) {
        val (line, character) = checker.getLineAndCharacterOfPosition(source, expr.pos)
        checker.diagnostics.add(Diagnostic(
            message = "Constructor of class '${declaring.name}' is private and only accessible within the class declaration.",
            category = DiagnosticCategory.Error, code = 2673,
            fileName = fileName, line = line, character = character,
            start = expr.pos, length = checker.expressionTrueEnd(expr) - expr.pos,
        ))
    }

    /**
     * (P18.256) TS2511 off the constructor type — tsgo `resolveNewExpression`'s
     * `someSignature(constructSignatures, isAbstract)`. True when reported (or already
     * reported); tsgo stops there (`resolveErrorCall`), so the caller does not check the
     * arguments. (P18.258) Every callee, a bare identifier included, and a union of
     * constructor types; the name-based walker ([spineAiEnterNode], at the node's ENTER) is
     * kept only as the fallback for callees no type reaches — a block-scoped class (B83.5)
     * and an anonymous `export default abstract class` import — and a row it drew is not
     * redrawn here.
     */
    private fun newExprAbstractConstructorTs2511(expr: NewExpression, calleeType: Type, source: String, fileName: String): Boolean {
        if (checker.classConstructorTypes.walkerAbstractRowDrawn(fileName, expr.pos)) return true
        if (!checker.classConstructorTypes.constructsAbstract(calleeType)) return false
        val (line, character) = checker.getLineAndCharacterOfPosition(source, expr.pos)
        checker.diagnostics.add(Diagnostic(
            message = "Cannot create an instance of an abstract class.",
            category = DiagnosticCategory.Error, code = 2511,
            fileName = fileName, line = line, character = character,
            start = expr.pos, length = checker.expressionTrueEnd(expr) - expr.pos,
        ))
        return true
    }

    fun checkSingleNewExpressionTypes(expr: NewExpression, source: String, fileName: String) {
        val prevArityCall = checker.arityCall
        checker.arityCall = expr
        checkSingleNewExpressionTypesCore(expr, source, fileName)
        checkInheritedOverloadedCtorNew(expr, source, fileName)
        checker.arityCall = prevArityCall
    }

    private fun checkSingleNewExpressionTypesCore(expr: NewExpression, source: String, fileName: String) {
        // (M3.0/ANY.1) round 837 — TS2347 "Untyped function calls may not accept type
        // arguments." tsc's `resolveUntypedCall` is reached from `resolveNewExpression` as
        // well as `resolveCallExpression`, so `new x<any>(x)` on an `any` callee is exactly
        // as much an error as `x<any>(x)` is (`anyAsConstructor`). The gate is the SAME pair
        // of predicates the CallExpression emitter uses — a definitively implicit/explicit
        // `any` var chain, or a `this.<any-member>` access — deliberately NOT the broad
        // `calleeType === anyType`, which our incomplete inference reaches far too often.
        if (!expr.typeArguments.isNullOrEmpty() &&
            (checker.isImplicitAnyVarChain(expr.expression) || checker.isImplicitAnyThisMember(expr.expression))
        ) {
            val start = expr.pos
            val length = checker.expressionTrueEnd(expr) - start
            if (length > 0) {
                val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                checker.diagnostics.add(Diagnostic(
                    message = "Untyped function calls may not accept type arguments.",
                    category = DiagnosticCategory.Error,
                    code = 2347,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
        }
        // 16.4fd: `new []` — empty array literal is not constructable.
        // Narrow to empty array literal — non-empty would need element-type
        // resolution for the chain display.
        if (expr.expression is ArrayLiteralExpression && (expr.expression).elements.isEmpty()) {
            val arr = expr.expression
            val close = source.indexOf(']', arr.pos)
            val length = if (close >= arr.pos) close + 1 - arr.pos else 2
            val (line, character) = checker.getLineAndCharacterOfPosition(source, arr.pos)
            checker.diagnostics.add(Diagnostic(
                message = "This expression is not constructable.",
                category = DiagnosticCategory.Error,
                code = 2351,
                fileName = fileName,
                line = line,
                character = character,
                start = arr.pos,
                length = length,
                messageChain = listOf("  Type 'never[]' has no construct signatures."),
            ))
            return
        }
        // B171: `new DataView(new <TypedArray>(...))` — a typed array is NOT an ArrayBuffer
        // (DataView's parameter is `ArrayBuffer & { BYTES_PER_ELEMENT?: undefined; }`, which
        // a typed array fails via its `[Symbol.toStringTag]` literal). The embedded lib has
        // no DataViewConstructor, so the general path resolves nothing — this is a dedicated
        // AST-gated emission (dataViewConstructor). Fires ONLY for a DIRECT `new TypedArray`
        // argument (an ArrayBuffer-typed value never matches) and only when `DataView` is not
        // shadowed by a user class.
        run {
            val callee = expr.expression as? Identifier ?: return@run
            if (callee.text != "DataView") return@run
            if (checker.globals["DataView"]?.declarations?.any { it is ClassDeclaration } == true) return@run
            val arg = expr.arguments?.firstOrNull() as? NewExpression ?: return@run
            val argCallee = (arg.expression as? Identifier)?.text ?: return@run
            if (argCallee !in checker.TYPED_ARRAY_NAMES) return@run
            if (checker.globals[argCallee]?.declarations?.any { it is ClassDeclaration } == true) return@run
            val start = arg.pos
            val length = checker.expressionTrueEnd(arg) - start
            val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
            checker.diagnostics.add(Diagnostic(
                message = "Argument of type '$argCallee<ArrayBuffer>' is not assignable to parameter of type 'ArrayBuffer & { BYTES_PER_ELEMENT?: undefined; }'.",
                category = DiagnosticCategory.Error,
                code = 2345,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
                messageChain = listOf(
                    "  Type '$argCallee<ArrayBuffer>' is not assignable to type 'ArrayBuffer'.",
                    "    Types of property '[Symbol.toStringTag]' are incompatible.",
                    "      Type '\"$argCallee\"' is not assignable to type '\"ArrayBuffer\"'.",
                ),
            ))
            return
        }
        // 17.221: TS2674 — protected constructor accessibility. When `new ClassName(...)`
        // resolves to a class whose effective constructor (own or inherited) is declared
        // `protected`, the call must be inside the declaring class or one of its subclasses.
        // Squiggle covers the full `new` expression. Only fires for bare Identifier callees
        // with a resolvable class symbol.
        if (expr.expression is Identifier) {
            val ident = expr.expression
            // (P18.256) the class a variable / parameter / module-local class callee constructs
            // is read off its constructor side when it is not a script global class.
            val classSym = checker.globals[ident.text]?.takeIf { s -> s.declarations.any { it is ClassDeclaration } }
                ?: checker.getCalleeType(ident).let { ct ->
                    checker.classConstructorTypes.constructedClass(checker.classConstructorTypes.newCalleeConstructorSide(ident, ct) ?: ct)?.symbol
                }
            if (classSym != null && classSym.declarations.any { it is ClassDeclaration }) {
                val ctorInfo = checker.findEffectiveConstructorVisibility(classSym)
                // (CHK.154)(b): tsgo `resolveNewExpression` returns `resolveErrorCall` when
                // `isConstructorAccessible` fails, so an inaccessible constructor's
                // ARGUMENTS are never checked. (P18.256) A PRIVATE one used outside its
                // class is TS2673 over the whole `new` (tsgo `isConstructorAccessible`).
                if (ctorInfo != null && ctorInfo.first == ModifierFlag.Private &&
                    checker.callWalkerClassStack.none { it === ctorInfo.second }
                ) {
                    emitPrivateConstructorTs2673(expr, ctorInfo.second, source, fileName)
                    return
                }
                if (ctorInfo != null && ctorInfo.first == ModifierFlag.Protected) {
                    val declaringClass = ctorInfo.second
                    val accessible = checker.callWalkerClassStack.any { enclosing ->
                        classExtendsOrIs(enclosing, declaringClass)
                    }
                    if (!accessible) {
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, expr.pos)
                        val length = checker.expressionTrueEnd(expr) - expr.pos
                        checker.diagnostics.add(Diagnostic(
                            message = "Constructor of class '${declaringClass.name}' is protected and only accessible within the class declaration.",
                            category = DiagnosticCategory.Error,
                            code = 2674,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = expr.pos,
                            length = length,
                        ))
                        return
                    }
                }
            }
        }
        // B295: `new X()` where X is a ctor-less class whose DIRECT extends-base is a
        // generic class referenced WITHOUT type arguments (the heritage carries
        // TS2314) — the inherited base constructor is an error type, so `typeof X`
        // has no construct signatures (tsc resolves the implicit ctor through the
        // errored base). Chain mirrors tsc.
        run {
            val callee = expr.expression as? Identifier ?: return@run
            val sym = checker.currentFileLocals?.get(callee.text) ?: checker.globals[callee.text] ?: return@run
            val cls = sym.declarations.singleOrNull() as? ClassDeclaration ?: return@run
            if (cls.members.any { it is Constructor }) return@run
            val ext = cls.heritageClauses?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword }
                ?.types?.singleOrNull() ?: return@run
            if (ext.typeArguments != null) return@run
            val baseName = (ext.expression as? Identifier)?.text ?: return@run
            val baseSym = checker.currentFileLocals?.get(baseName) ?: checker.globals[baseName] ?: return@run
            val baseCls = baseSym.declarations.singleOrNull() as? ClassDeclaration ?: return@run
            if (baseCls in checker.builtinLibDecls) return@run
            val tps = baseCls.typeParameters ?: return@run
            if (tps.isEmpty() || tps.any { it.default != null }) return@run
            val (line, character) = checker.getLineAndCharacterOfPosition(source, callee.pos)
            checker.diagnostics.add(Diagnostic(
                message = "This expression is not constructable.",
                category = DiagnosticCategory.Error, code = 2351,
                fileName = fileName, line = line, character = character,
                start = callee.pos, length = callee.text.length,
                messageChain = listOf("  Type 'typeof ${callee.text}' has no construct signatures."),
            ))
            return
        }
        // B497: `new <recv>[]` where the empty `[]` element access (OmittedExpression
        // argument, the TS1011 shape) has a receiver resolving to a value-position CLASS
        // (`typeof X`). The omitted index is `any`-typed; `typeof X` has no index signature
        // → TS7053. Matches tsc's `new M.T[]` recovery. noImplicitAny-gated (default-on).
        run {
            val ce = expr.expression as? ElementAccessExpression ?: return@run
            if (ce.argumentExpression !is OmittedExpression) return@run
            if (options.strictExplicitlyFalse && !options.noImplicitAny && !options.strict) return@run
            val display = typeofClassValueDisplay(ce.expression) ?: return@run
            val start = ce.expression.pos
            val end = ce.argumentExpression.pos + 1  // OmittedExpression.pos = the `]`
            if (end <= start) return@run
            val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
            checker.diagnostics.add(Diagnostic(
                message = "Element implicitly has an 'any' type because expression of type 'any' can't be used to index type '$display'.",
                category = DiagnosticCategory.Error, code = 7053,
                fileName = fileName, line = line, character = character,
                start = start, length = end - start,
            ))
            return
        }
        // B497: `new <non-constructable-literal-or-instance>` — a primitive-literal callee
        // (`new 53()`→Number, `new ''()`→String) or a `new`-expression callee whose returned
        // INSTANCE type has no construct signatures (`new new Date`→Date) is not constructable.
        // These non-Identifier callees fall through getCalleeType (anyType) / the args-required
        // early return below, so handle them here. FP-safe: `new <primitive-literal>` and
        // `new <non-constructable-instance>` are ALWAYS tsc errors.
        run {
            val ce = expr.expression
            val typeName: String = when (ce) {
                is NumericLiteralNode -> "Number"
                is StringLiteralNode -> "String"
                is NewExpression -> {
                    val inst = checker.getReturnTypeOfNewExpression(ce)
                    if (inst is Type.Interface && checker.getConstructSignaturesOfType(inst).isEmpty())
                        inst.symbol?.name ?: return@run
                    else return@run
                }
                else -> return@run
            }
            val (line, character) = checker.getLineAndCharacterOfPosition(source, ce.pos)
            checker.diagnostics.add(Diagnostic(
                message = "This expression is not constructable.",
                category = DiagnosticCategory.Error, code = 2351,
                fileName = fileName, line = line, character = character,
                start = ce.pos, length = checker.expressionTrueEnd(ce) - ce.pos,
                messageChain = listOf("  Type '$typeName' has no construct signatures."),
            ))
            return
        }
        // intTypeCheck: `new {}` (no argument list) — an empty object literal has no
        // construct signatures. FP-safe: tsc ALWAYS errors on `new {}`. Squiggle = the
        // `{}` literal. Gated to arguments == null so the with-parens path (which flows
        // through the signatures.isEmpty() handling below) is untouched.
        if (expr.arguments == null && expr.expression is ObjectLiteralExpression &&
            (expr.expression).properties.isEmpty()) {
            val ol = expr.expression
            val close = source.indexOf('}', ol.pos)
            val length = if (close >= ol.pos) close + 1 - ol.pos else 2
            val (line, character) = checker.getLineAndCharacterOfPosition(source, ol.pos)
            checker.diagnostics.add(Diagnostic(
                message = "This expression is not constructable.",
                category = DiagnosticCategory.Error, code = 2351,
                fileName = fileName, line = line, character = character,
                start = ol.pos, length = length,
                messageChain = listOf("  Type '{}' has no construct signatures."),
            ))
            return
        }
        // (P18.256) a class callee is read as its CONSTRUCTOR side, as every value read of it
        // is: the construct signatures below are the class's own (else the base's, else the
        // zero-argument default), not the instance's hybrid list.
        val calleeType = newCalleeNonNullType(expr, checker.getCalleeType(expr.expression), source, fileName)
            ?.let { checker.classConstructorTypes.newCalleeConstructorSide(expr.expression, it) ?: it } ?: return
        if (calleeType === anyType || calleeType === errorType) return
        if (newExprAbstractConstructorTs2511(expr, calleeType, source, fileName)) return
        // (LEGACY.0b) TS7009 for a callee that is NOT a bare identifier. tsc decides it
        // from the RESOLVED SIGNATURE's declaration - `checkCallExpression`'s
        // `declaration.kind !== Constructor && !== ConstructSignature && !== ConstructorType`
        // - i.e. a `new` that resolved a CALL signature yields `any` and reports under
        // noImplicitAny. Measured against tsgo 7.0.2 before it was written: `new O.m()`,
        // `new N.f()`, `new arr[0]()`, `new h.g()` and `new Base.make()` all report,
        // `new C.K()` and a construct-signature-typed property do not, and an `any` callee
        // is SILENT (tsc's `resolveUntypedCall` returns before the check) - which the
        // guard above already gives us. The sibling comment below records the same rule
        // from the other side ("under noImplicitAny tsc reports TS7009 instead").
        //
        // THE EVIDENCE IS POSITIVE IN BOTH DIRECTIONS - call signatures present AND
        // construct signatures absent - which is what makes it safe to run program-wide:
        // a callee this checker cannot type answers `anyType` and is refused above, so the
        // rule can never fire on a type we failed to compute. (CHK.73) is why the class
        // case is silent here for a DIFFERENT reason than in tsc: a class VALUE types as
        // its INSTANCE type, which has neither signature kind, where tsc sees a construct
        // signature. Same verdict, and recorded because the day (CHK.73) is fixed this
        // arm must stay silent for the tsc reason instead.
        //
        // An IDENTIFIER callee is deliberately left to [checkNewExprImplicitAny], which is
        // symbol-based and reaches two shapes no type can: a named function EXPRESSION's
        // self-reference, in no symbol table at all (B83.5), and `super` (parsed as an
        // Identifier here), whose `new super(...)` owns the TS2351 + TS17011 pair at
        // 16.4cw. Running both paths would double-emit.
        //
        // MEASURED REDUNDANT, KEPT: with the arm restricted to a property access, the
        // `getConstructSignaturesOfType(calleeType).isEmpty()` conjunct is subsumed by the
        // property-SYMBOL consult in the refusal — ablation arm b3 drops it and reads
        // 0 RED of 13 with the screen and the grid clean. It is kept because it is the
        // only guard that survives if the refusal's receiver lookup ever fails to find
        // the property, and because it states the rule the way tsc states it. Recorded
        // rather than claimed (round 807).
        //
        // A UNION callee is excluded so B60.15's three-case constituent report keeps it.
        //
        // A PROPERTY access and an ELEMENT access are both admitted, and the pair
        // `{ (): void; new (): object }` vs `(() => void)[]` is pinned at BOTH — the
        // construct-signature conjunct is what separates them, and it reads the same
        // answer through `getTypeOfPropertyAccess` and `getTypeOfElementAccess`.
        // (An earlier cut excluded element access and added a second, symbol-based
        // construct consult, both in response to a false positive that did not exist:
        // the CLI probe that "found" it had run against the PREVIOUS ablation arm's class
        // directory — the round-851 trap — i.e. against a binary with this very conjunct
        // removed. Both defences are gone; the shape they were built for is measured
        // silent without them.)
        //
        // A MERGED class+function symbol is refused for the reason the identifier path
        // refuses it (`sym.declarations.any { it is ClassDeclaration }`, round 79i): its
        // type here is the FUNCTION side alone, so the construct signature the class side
        // carries is invisible to `getConstructSignaturesOfType`. Measured on
        // `constructorOverloads4` — `declare namespace M { export class Function …;
        // export function Function(…) … }` — where tsgo is silent and this arm reported.
        if (checker.spineNaRunActive &&
            expr.expression !is Identifier &&
            calleeType !is Type.Union &&
            !checker.newCalleeTypeSymbolDeclaresClass(expr.expression, calleeType) &&
            checker.getConstructSignaturesOfType(calleeType).isEmpty() &&
            checker.getCallSignaturesOfType(calleeType).isNotEmpty()
        ) {
            checker.emitNewExprImplicitAny(expr, source, fileName)
            return
        }
        // intTypeCheck: `new <var>` (no argument list) where the var's annotated type is a
        // PURE user interface. tsc resolveNewExpression: construct sigs (own or inherited —
        // resolveStructuredTypeMembers merges base sigs) → constructable, nothing; else call
        // sigs whose resolved return isn't `void` → TS2350 "Only a void function can be
        // called with the 'new' keyword." at the whole `new x` (noImplicitAny OFF only —
        // under noImplicitAny tsc reports TS7009 instead); else → TS2351 at the CALLEE with
        // "Type 'X' has no construct signatures.". Gated to arguments == null (the
        // with-parens path is owned by the 17.170 branch below).
        if (expr.arguments == null && expr.expression is Identifier) {
            val ce = expr.expression
            val sym = checker.currentFileLocals?.get(ce.text) ?: checker.globals[ce.text]
            val isVarOnly = sym != null && sym.flags.hasAny(SymbolFlags.Variable) &&
                !sym.flags.hasAny(SymbolFlags.Class or SymbolFlags.Function or SymbolFlags.Module or SymbolFlags.Enum or SymbolFlags.Alias)
            val ti = calleeType as? Type.Interface
            if (isVarOnly && ti != null && ti.symbol != null &&
                ti.symbol!!.flags.hasAny(SymbolFlags.Interface) && !ti.symbol!!.flags.hasAny(SymbolFlags.Class) &&
                ti.symbol!!.declarations.none { it in checker.builtinLibDecls }) {
                checker.resolveStructuredTypeMembers(ti)
                val ctorSigs = ti.constructSignatures ?: emptyList()
                if (ctorSigs.isEmpty()) {
                    val callSigs = ti.callSignatures ?: emptyList()
                    if (callSigs.isNotEmpty()) {
                        if (!options.noImplicitAny && !options.strict) {
                            val resolved = callSigs.firstOrNull { it.minArgumentCount == 0 } ?: callSigs.first()
                            val ret = resolved.resolvedReturnType
                            if (ret !== voidType) {
                                val end = checker.expressionTrueEnd(ce)
                                val (line, character) = checker.getLineAndCharacterOfPosition(source, expr.pos)
                                checker.diagnostics.add(Diagnostic(
                                    message = "Only a void function can be called with the 'new' keyword.",
                                    category = DiagnosticCategory.Error, code = 2350,
                                    fileName = fileName, line = line, character = character,
                                    start = expr.pos, length = end - expr.pos,
                                ))
                                return
                            }
                        }
                    } else {
                        val typeName = ti.symbol?.name ?: checker.typeToString(ti)
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, ce.pos)
                        checker.diagnostics.add(Diagnostic(
                            message = "This expression is not constructable.",
                            category = DiagnosticCategory.Error, code = 2351,
                            fileName = fileName, line = line, character = character,
                            start = ce.pos, length = ce.text.length,
                            messageChain = listOf("  Type '$typeName' has no construct signatures."),
                        ))
                        return
                    }
                }
            }
        }
        if (newUnionCalleeNotConstructable(expr, calleeType, source, fileName)) return
        // (CHK.137) round (P18.128) — a variable that HOLDS A CLASS is constructable, and a
        // variable that holds an INSTANCE is not, and this checker's type cannot tell them
        // apart. Both directions are decided HERE, above the construct-signature read, and
        // the read is what is wrong for both of them. (P18.256): only the INSTANCE half is
        // left — a class-holding variable now types as the constructor side ((CHK.196)), whose
        // construct signatures the read below takes, so the class half was unreachable and is
        // deleted. The history below is kept for the instance half's reasoning.
        //
        // The cause is (CHK.73): **a class VALUE types as its INSTANCE type**. So for
        // `class Cls {}; const c = Cls`, `c`'s type is the instance interface, which has no
        // construct signatures — and the 17.170 emitter below read that as "not
        // constructable" and reported an ours-only TS2351 on legal code (tsgo 7.0.2: clean).
        // The SAME artifact hides the mirror defect: a class that DECLARES a constructor
        // registers a construct signature on that instance type, so `const i = new Cls(); new i()`
        // for such a class reached `signatures.isEmpty() == false` and was SILENT where both
        // references report TS2351. One modelling quirk, a false positive and a false
        // negative, and a fixture that varies only the class's constructor swaps which one
        // you see — which is why the two are closed in one place.
        //
        // **Decided from the DECLARATION, syntactically, because the TYPE is the thing that
        // cannot be trusted here** — the same move (KIR.LOWER.6) had to make in the backend
        // for the same reason ((P18.127): `variableType` declines the checker's answer for a
        // class-value initializer). [newCalleeVarHoldsInstance] answers false wherever it
        // cannot tell, and a false falls through to exactly the pre-existing behaviour, so the
        // change is confined to the population the artifact damages.
        //
        // **The population is narrower than it looks and the narrowing is load-bearing.**
        // `globals[...]` is read rather than `currentFileLocals` because that is the gate the
        // 17.170 emitter already uses: a MODULE file's locals are not in `globals`
        // (INV.3(d)), so the false positive was SCRIPT-FILE-ONLY — a bare `export {}` makes
        // it vanish, measured — and widening to per-file locals here would put a NEW
        // diagnostic into every module file, which is a different change. The
        // `symbol.flags.hasAny(Class)` test on the callee TYPE keeps a genuine
        // `interface Ctor { new (): X }` variable out: that one has a real construct
        // signature and is not a (CHK.73) casualty at all.
        run {
            val ce = expr.expression as? Identifier ?: return@run
            val sym = checker.globals[ce.text] ?: return@run
            // (CHK.186) a MODULE file's own declaration shadows that script global.
            if (checker.nameResolver.fileShadowsGlobal(fileName, ce.text)) return@run
            if (!sym.flags.hasAny(SymbolFlags.Variable)) return@run
            if (sym.flags.hasAny(
                    SymbolFlags.Class or SymbolFlags.Function or SymbolFlags.Module or
                        SymbolFlags.Enum or SymbolFlags.Alias
                )
            ) return@run
            val ti = calleeType as? Type.Interface ?: return@run
            if (ti.symbol?.flags?.hasAny(SymbolFlags.Class) != true) return@run
            if (newCalleeVarHoldsInstance(sym)) {
                val typeName = ti.symbol?.name ?: checker.typeToString(ti)
                val (line, character) = checker.getLineAndCharacterOfPosition(source, ce.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "This expression is not constructable.",
                    category = DiagnosticCategory.Error, code = 2351,
                    fileName = fileName, line = line, character = character,
                    start = ce.pos, length = ce.text.length,
                    messageChain = listOf("  Type '$typeName' has no construct signatures."),
                ))
                return
            }
        }
        if (newInstanceCalleeNotConstructable(expr, calleeType, source, fileName)) return
        // B497: the args-required early return was moved BELOW the union-callee branch
        // (above) so a NO-ARGS union callee (`new union;`) is still constructability-checked.
        val args = expr.arguments ?: return
        // Get construct signatures
        val signatures = checker.getConstructSignaturesOfType(calleeType)
        if (signatures.isEmpty()) {
            // 17.170: TS2351 — `new x()` where x is a plain instance variable (not
            // a class identifier). Class-instance Type.Interface has no construct
            // signatures (those live on the static side). Squiggle on the callee.
            // Conservative gate: only fire when the callee is a bare Identifier
            // resolving to a Variable symbol whose type is a Type.Interface with a
            // class symbol — definitively a "new on instance" case.
            val ce = expr.expression
            if (ce is Identifier) {
                // (CHK.186) not the script global a module's own declaration shadows.
                val sym = if (checker.nameResolver.fileShadowsGlobal(fileName, ce.text)) null else checker.globals[ce.text]
                val isVarOrParam = sym != null && sym.flags.hasAny(SymbolFlags.Variable) &&
                    !sym.flags.hasAny(SymbolFlags.Class or SymbolFlags.Function)
                if (isVarOrParam && calleeType is Type.Interface) {
                    val typeName = calleeType.symbol?.name ?: checker.typeToString(calleeType)
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, ce.pos)
                    checker.diagnostics.add(Diagnostic(
                        message = "This expression is not constructable.",
                        category = DiagnosticCategory.Error,
                        code = 2351,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = ce.pos,
                        length = ce.text.length,
                        messageChain = listOf("  Type '$typeName' has no construct signatures."),
                    ))
                }
            }
            // staticMemberExportAccess: `new $.sammy()` — a chained PropertyAccess callee
            // resolving to a class-INSTANCE Type.Interface (no construct signatures). LANDMINE:
            // `new ns.ClassA()` is VALID but our checker resolves a namespace-member class's
            // value position to its INSTANCE type (no construct sigs) — so bail when the accessed
            // property itself resolves to a CLASS symbol (the constructable static side).
            if (ce is PropertyAccessExpression && calleeType is Type.Interface &&
                !checker.propertyAccessChainIsNamespaceQualified(ce)) {
                val tsym = calleeType.symbol
                if (tsym != null && tsym.flags.hasAny(SymbolFlags.Class)) {
                    val accessedSym = checker.resolvePropertyAccessToSymbol(ce)
                    if (accessedSym == null || !accessedSym.flags.hasAny(SymbolFlags.Class)) {
                        val typeName = tsym.name
                        val start = ce.pos
                        val length = checker.expressionTrueEnd(ce) - start
                        if (length > 0) {
                            val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                            checker.diagnostics.add(Diagnostic(
                                message = "This expression is not constructable.",
                                category = DiagnosticCategory.Error, code = 2351,
                                fileName = fileName, line = line, character = character,
                                start = start, length = length,
                                messageChain = listOf("  Type '$typeName' has no construct signatures."),
                            ))
                        }
                    }
                }
            }
            return
        }
        checkNewArgsAgainstConstructSignatures(expr, args, calleeType, signatures, source, fileName)
    }

    /**
     * (CHK.199)(a) TS2351 for `new i()` where `i` holds a class INSTANCE — tsgo's
     * `resolveNewExpression` finds no construct signature on an instance type and reports
     * "Type 'C' has no construct signatures." at the callee. The two older emitters (the
     * (CHK.137) block and 17.170) gate on `globals`, which never holds a MODULE file's locals
     * (INV.3(d)), and read the construct-signature list, which a class DECLARING a
     * constructor registers on its instance type here — so a module file, an annotated
     * declaration and a parameter were silent. Since (CHK.196) a class VALUE types as its
     * constructor side, so an identifier callee whose type is still the class's DECLARED
     * INSTANCE type really is an instance; decided from that type, not from a symbol table.
     *
     * Refused (falls through to the older paths): a class merged with an interface or
     * anything else (an interface may declare a construct signature) and every generic
     * instance (tsgo reports `new g()` on a `G<number>` too — a missing row, never a false one).
     * A class identifier never reaches here — it reads as its constructor side, which
     * [ClassConstructorTypes.constructedClass] recognises — and three further refusals (the
     * callee spelling the class's name, a block-scoped class binding, the declared-type
     * identity) were built, ablated to 0 RED on the pins and 0 on the corpus screen, and
     * deleted: the first one was suppressing tsgo's row for `function g(D: D) { new D() }`.
     */
    private fun newInstanceCalleeNotConstructable(expr: NewExpression, calleeType: Type, source: String, fileName: String): Boolean {
        val ce = expr.expression as? Identifier ?: return false
        val ti = calleeType as? Type.Interface ?: return false
        if (checker.classConstructorTypes.constructedClass(calleeType) != null) return false
        val cls = ti.symbol ?: return false
        if (!cls.flags.hasAny(SymbolFlags.Class)) return false
        if (cls.declarations.isEmpty() || cls.declarations.any { it !is ClassDeclaration && it !is ClassExpression }) return false
        if (!ti.typeParameters.isNullOrEmpty()) return false
        val (line, character) = checker.getLineAndCharacterOfPosition(source, ce.pos)
        checker.diagnostics.add(Diagnostic(
            message = "This expression is not constructable.",
            category = DiagnosticCategory.Error, code = 2351,
            fileName = fileName, line = line, character = character,
            start = ce.pos, length = ce.text.length,
            messageChain = listOf("  Type '${cls.name}' has no construct signatures."),
        ))
        return true
    }

    /**
     * (CHK.199) split out of [checkSingleNewExpressionTypesCore] VERBATIM: the B60.15
     * union-callee constructability report. Answers true when a row was reported (the
     * caller then returns, as the inline region's three bare `return`s did).
     */
    private fun newUnionCalleeNotConstructable(expr: NewExpression, calleeType: Type, source: String, fileName: String): Boolean {
        // B60.15: union callee for `new` — mirror of B60.14 for TS2349 with three cases:
        //   (a) all constituents non-constructable → "No constituent ... is constructable."
        //   (b) some non-constructable → "Not all constituents ... are constructable." + first missing display
        //   (c) all constructable but sigs differ structurally → "Each member ... has construct signatures, but none ... compatible..."
        // (CHK.98)(a)'s class-instance refusal is retired (P18.256): it existed because a
        // class VALUE typed as its INSTANCE, so `[ConcreteA, AbstractA].map(cls => new cls())`
        // read a union of instances. A class value is now its constructor side ((CHK.196)), so
        // a union of class INSTANCES at a `new` really is unconstructable — `declare const
        // u: A | B; new u()` is TS2351 in tsgo, and the refusal was suppressing it.
        if (calleeType is Type.Union) {
            val constituents = calleeType.types
            val nonCtor = constituents.filter { checker.getConstructSignaturesOfType(it).isEmpty() }
            val unionDisplay = checker.typeToString(calleeType)
            val ce = expr.expression
            val (start, length) = run {
                val s = ce.pos
                Pair(s, checker.expressionTrueEnd(ce) - s)
            }
            if (length > 0) {
                val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                if (nonCtor.isNotEmpty() && nonCtor.size == constituents.size) {
                    checker.diagnostics.add(Diagnostic(
                        message = "This expression is not constructable.",
                        category = DiagnosticCategory.Error, code = 2351,
                        fileName = fileName, line = line, character = character,
                        start = start, length = length,
                        messageChain = listOf("  No constituent of type '$unionDisplay' is constructable."),
                    ))
                    return true
                }
                if (nonCtor.isNotEmpty() && nonCtor.size != constituents.size) {
                    val missingDisplay = checker.typeToString(nonCtor[0])
                    checker.diagnostics.add(Diagnostic(
                        message = "This expression is not constructable.",
                        category = DiagnosticCategory.Error, code = 2351,
                        fileName = fileName, line = line, character = character,
                        start = start, length = length,
                        messageChain = listOf(
                            "  Not all constituents of type '$unionDisplay' are constructable.",
                            "    Type '$missingDisplay' has no construct signatures.",
                        ),
                    ))
                    return true
                }
                if (nonCtor.isEmpty() && constituents.size >= 2) {
                    // All constructable; check pairwise sig compat heuristic
                    val sigsList = constituents.map { checker.getConstructSignaturesOfType(it).firstOrNull() }
                    val hasNullSig = sigsList.any { it == null }
                    if (!hasNullSig) {
                        val sigs = sigsList.map { it!! }
                        // (CHK.97) tsc runs `getUnionSignatures` over the CONSTRUCT lists
                        // too, so a union whose members' construct signatures COMBINE is
                        // constructable — this branch's only verdict was `differ → TS2351`,
                        // i.e. every `new (typeof A | typeof B)(…)` with differing ctor
                        // parameters was a false positive. Fall through to the ordinary
                        // construct resolution below, which now reads the combined list.
                        val ctorCombined = checker.combineUnionSignatures(calleeType, construct = true)
                        val differ = if (ctorCombined != null) false else run {
                            for (i in sigs.indices) for (j in i + 1 until sigs.size) {
                                val s1 = sigs[i]; val s2 = sigs[j]
                                if ((s1.typeParameters?.size ?: 0) != (s2.typeParameters?.size ?: 0)) return@run true
                                if (s1.parameters.size != s2.parameters.size) return@run true
                                for (k in s1.parameters.indices) {
                                    val t1 = checker.getTypeOfSymbol(s1.parameters[k])
                                    val t2 = checker.getTypeOfSymbol(s2.parameters[k])
                                    if (t1 !== t2) return@run true
                                }
                            }
                            false
                        }
                        if (differ) {
                            checker.diagnostics.add(Diagnostic(
                                message = "This expression is not constructable.",
                                category = DiagnosticCategory.Error, code = 2351,
                                fileName = fileName, line = line, character = character,
                                start = start, length = length,
                                messageChain = listOf(
                                    "  Each member of the union type '$unionDisplay' has construct signatures, but none of those signatures are compatible with each other.",
                                ),
                            ))
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    /**
     * (CHK.199) split out of [checkSingleNewExpressionTypesCore] VERBATIM (it was at 7,191 of
     * the 8,000-bytecode JIT limit): the argument check against the construct signature(s)
     * the callee resolves to, after the 17.21 class-scope re-resolution.
     */
    private fun checkNewArgsAgainstConstructSignatures(
        expr: NewExpression, args: List<Expression>, calleeType: Type, signatures: List<Signature>,
        source: String, fileName: String,
    ) {
        // 17.21: When the class has its own TypeParameters (e.g. `class List<T>`)
        // AND the call site has explicit type arguments (e.g. `new List<T>(...)`),
        // build a fresh signature with each param's type re-resolved under the
        // class TypeParam scope. The original sig's params may have been cached
        // as `errorType` from an earlier eager-pass resolution that ran with no
        // scope — re-resolving here lets the 17.20 null-vs-unconstrained-TypeParam
        // path fire on the bare `T` parameter. We mint fresh Symbols + cache
        // entries so the original cache isn't disturbed (which would break
        // `instantiateSignature`-produced sigs in `handleSuperMethodCall`).
        //
        // The explicit-type-args gate is critical: without it, `new D(null)`
        // where `class D<T> { constructor(x: T) }` would FP-emit TS2345 because
        // we don't perform generic argument inference (TypeScript would infer
        // T = null and silently accept). With explicit type args, TypeScript
        // bypasses inference so a bare `<T>` (unconstrained) → null arg fires.
        //
        // Also skip when any explicit type arg resolves to errorType — that
        // means the call site can't actually access the type (e.g. inside a
        // static method, a class-level T is out of scope and TypeScript already
        // emits TS2302; emitting TS2345 too would double-fault).
        val classTypeParams = (checker.classConstructorTypes.constructedClass(calleeType) ?: calleeType as? Type.Interface)?.typeParameters
        val hasExplicitTypeArgs = !expr.typeArguments.isNullOrEmpty()
        val resolvedTypeArgs: List<Type>? = if (hasExplicitTypeArgs) {
            expr.typeArguments.map { tn ->
                checker.getTypeFromTypeNode(tn)
            }
        } else null
        val explicitArgsAllResolve = resolvedTypeArgs != null && resolvedTypeArgs.none { it === errorType }
        // (CHK.199)(c) TS2344 / TS2559 — tsgo's `checkTypeArguments` runs for a `new` as for a
        // call: an explicit type argument violating the class type parameter's (instantiated)
        // constraint is reported at the type argument, and the candidate is then rejected, so
        // the arguments are NOT checked (measured: `new G<number>("x")` is TS2344 alone).
        // Shares the call site's emitter; the arity gate is the call site's (defaults may be
        // omitted), and a TS2558 arity error elsewhere keeps this silent.
        if (explicitArgsAllResolve && !classTypeParams.isNullOrEmpty() &&
            resolvedTypeArgs.size in classTypeParams.count { it.default == null }..classTypeParams.size
        ) {
            val padded = if (resolvedTypeArgs.size < classTypeParams.size)
                resolvedTypeArgs + (resolvedTypeArgs.size until classTypeParams.size).map { classTypeParams[it].default ?: errorType }
            else resolvedTypeArgs
            val before = checker.diagnostics.size
            checker.checkCallTypeArgConstraints(
                classTypeParams, resolvedTypeArgs, expr.typeArguments.orEmpty(), createTypeMapper(classTypeParams, padded), source, fileName,
            )
            if (checker.diagnostics.size > before) return
        }
        // (P18.258) a constructor-less class's inherited signatures, instantiated through the
        // heritage type arguments (a non-generic class always; a generic one under explicit ones).
        val inherited = checker.classConstructorTypes.inheritedNewSignatures(calleeType, resolvedTypeArgs?.takeIf { explicitArgsAllResolve })
        val effectiveSigs: List<Signature> = if (inherited != null) inherited
        else if (!classTypeParams.isNullOrEmpty() && explicitArgsAllResolve) {
            val reresolved = signatures.map { sig -> checker.reresolveSigParamsUnderClassScope(sig, classTypeParams) }
            // B74.5: After re-resolving params under class scope, substitute the class
            // TypeParams with the explicit type arguments. Without this, a static method
            // `MakeHead3<U>()` calling `new List<U>(...)` displays the param type as the
            // class's `T` (the un-substituted classT) instead of the supplied `U`. The
            // class TPs and explicit type args have matching arity (guaranteed by the
            // type-arg parser/resolver; if mismatched, we'd have emitted TS2558 earlier).
            if (classTypeParams.size == resolvedTypeArgs.size) {
                val mapper = createTypeMapper(classTypeParams, resolvedTypeArgs)
                reresolved.map { sig -> checker.instantiateSignature(sig, mapper) }
            } else reresolved
        } else signatures
        if (effectiveSigs.size == 1) {
            checker.checkArgumentsAgainstSignature(args, effectiveSigs[0], source, fileName)
        } else {
            checker.checkArgumentsAgainstOverloads(args, effectiveSigs, source, fileName, expr.expression)
        }
    }


    /**
     * (CHK.173) B5f (N1) — tsgo's `resolveNewExpression` reads its callee through
     * `checkNonNullExpression`: a `null` / `undefined` member of the callee type is reported
     * (TS18047/8/9 for an entity name, TS2531/2/3 otherwise — [NullishReceiverChecks.reportNullishReceiver]) and
     * the resolution continues on the NON-NULL type. This emitter read the raw union, so
     * `new W()` on a `(new () => S) | undefined` was TS2351 "Not all constituents …" — also
     * after `if (W)`, since [getCalleeType] does not flow-narrow (the call path's
     * [ccetUnionCalleeChecks] re-narrows the same way). The report is limited to a callee
     * the flow walk can narrow (an identifier / property path); any other callee (`arr[0]`,
     * a call result) is stripped SILENTLY rather than risk reporting a guarded one — a
     * missing TS2532 where tsgo has one, never a false row. `new W!()` arrives stripped
     * already ([getCalleeType]'s `!` arm).
     *
     * Also tsgo's `invocationError` for a PRIMITIVE callee: `new n()` / `new n!()` with `n`
     * a number is TS2351 "Type 'Number' has no construct signatures." at the callee; answers
     * null (the caller returns) once reported.
     */
    private fun newCalleeNonNullType(expr: NewExpression, raw: Type, source: String, fileName: String): Type? {
        var t = raw
        val ce = expr.expression
        if (t is Type.Union && checker.strictNullChecks && t.types.any { checker.isNullishConstituent(it) }) {
            if (ce is Identifier || ce is PropertyAccessExpression) t = checker.getNarrowedTypeForReference(t, ce)
            if (t is Type.Union) {
                val nullish = t.types.filter { checker.isNullishConstituent(it) }
                val rest = t.types.filter { !checker.isNullishConstituent(it) }
                if (nullish.isNotEmpty() && rest.isNotEmpty()) {
                    if (ce is Identifier || ce is PropertyAccessExpression) {
                        val start = ce.pos
                        val length = checker.expressionTrueEnd(ce) - start
                        if (length > 0) checker.nullishReceivers.reportNullishReceiver(
                            ce, nullish.any { it.flags.hasAny(TypeFlags.Null) },
                            nullish.any { it.flags.hasAny(TypeFlags.Undefined) }, start, length, source, fileName,
                        )
                    }
                    t = if (rest.size == 1) rest[0] else checker.getUnionType(rest)
                }
            }
        }
        val apparent = when {
            t.flags.hasAny(TypeFlags.NumberLike) -> "Number"
            t.flags.hasAny(TypeFlags.StringLike) -> "String"
            t.flags.hasAny(TypeFlags.BooleanLike) -> "Boolean"
            t.flags.hasAny(TypeFlags.BigIntLike) -> "BigInt"
            else -> null
        }
        if (apparent != null && t !is Type.Union && t !is Type.Intersection && !t.flags.hasAny(TypeFlags.EnumLike)) {
            val start = ce.pos
            val length = checker.expressionTrueEnd(ce) - start
            if (length > 0) {
                val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                checker.diagnostics.add(Diagnostic(
                    message = "This expression is not constructable.",
                    category = DiagnosticCategory.Error, code = 2351,
                    fileName = fileName, line = line, character = character,
                    start = start, length = length,
                    messageChain = listOf("  Type '$apparent' has no construct signatures."),
                ))
            }
            return null
        }
        return t
    }

    /**
     * B497: resolve a value-position reference to a CLASS to its `typeof <Name>` display.
     * `Foo` (Identifier→class) → "typeof Foo"; `M.T` (namespace.exported-class) → "typeof T"
     * (tsc renders the LAST segment, not the qualified name). Returns null otherwise.
     */
    private fun typeofClassValueDisplay(recv: Expression): String? {
        return when (recv) {
            is Identifier -> {
                val sym = checker.currentFileLocals?.get(recv.text) ?: checker.globals[recv.text] ?: return null
                if (sym.declarations.any { it is ClassDeclaration }) "typeof ${recv.text}" else null
            }
            is PropertyAccessExpression -> {
                val nsName = (recv.expression as? Identifier)?.text ?: return null
                val nsSym = checker.currentFileLocals?.get(nsName) ?: checker.globals[nsName] ?: return null
                // The binder puts ALL namespace members in `exports` (not just exported
                // ones), so require the class decl to carry the `export` modifier — a
                // NON-exported `M.ClassA` is itself a TS2339 error (no typeof-class), and
                // tsc emits no TS7053 there (cannotInvokeNewOnErrorExpression).
                val member = nsSym.exports?.get(recv.name.text) ?: return null
                val classDecl = member.declarations.firstOrNull { it is ClassDeclaration } as? ClassDeclaration
                    ?: return null
                if (ModifierFlag.Export in classDecl.modifiers) "typeof ${recv.name.text}" else null
            }
            else -> null
        }
    }

    /**
     * 17.221: Returns true when `subClassSym` is `targetSym` itself, or extends it
     * (directly or transitively). Used for TS2674 protected-constructor accessibility.
     */
    private fun classExtendsOrIs(subClassSym: Symbol, targetSym: Symbol): Boolean {
        val visited = mutableSetOf<Int>()
        var current: Symbol? = subClassSym
        while (current != null && visited.add(current.id)) {
            if (current.id == targetSym.id) return true
            val classDecl = current.declarations.firstOrNull { it is ClassDeclaration } as? ClassDeclaration
                ?: return false
            val extendsClause = classDecl.heritageClauses?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword }
            val baseExpr = extendsClause?.types?.firstOrNull()?.expression
            val baseIdent = baseExpr as? Identifier ?: return false
            // INV.3(c)(iii) round 507: node-keyed (see findEffectiveConstructorVisibility).
            current = checker.lookupPerFileForNode(baseIdent, baseIdent.text)
        }
        return false
    }

    /**
     * B264: TS2345 / TS2769 for `new Derived(...)` where the constructor is INHERITED
     * from an overloaded generic `declare class` instantiated through the heritage
     * chain (inheritedConstructorWithRestParams2: `Derived extends Base extends
     * BaseBase<string, number>`). The main new-expression path skips overloaded
     * generic ctors entirely. AST-only model: ctor params must be bare in-scope-TP
     * refs / primitive keywords / rest arrays thereof, substituted by PRIMITIVE
     * heritage type args; call args must be literals. One arity-applicable overload
     * failing → TS2345 at the first mismatching arg; several applicable all failing →
     * TS2769 at the CALLEE name with the per-overload "Overload i of N,
     * '(<params>): <Class>', gave the following error." chain (overload indices are
     * 1-based over ALL overloads; only arity-applicable ones are listed). Any
     * unrecognized shape bails (FN, never FP).
     *
     * (CHK.199) Asked per `new` from [checkSingleNewExpressionTypes] (it was a program-wide
     * pass over top-level EXPRESSION STATEMENTS only, so `const d = new Derived(1)`, an
     * assignment, a return and every nested position were silent where tsgo reports). The
     * callee class and its whole extends chain must still be top-level classes of the
     * same file — the AST-only model's `classes` table.
     */
    private fun checkInheritedOverloadedCtorNew(ne: NewExpression, source: String, fileName: String) {
        if (checker.isDtsFile(fileName) || checker.isJsLikeFileName(fileName)) return
        val calleeId = ne.expression as? Identifier ?: return
        val callArgs0 = ne.arguments ?: return
        if (callArgs0.any { argPrim(it) == null }) return
        var root: Node = ne
        while (root !is SourceFile) root = (root as NodeBase).parent ?: return
        val classes = topLevelClassesOf(root)
        if (!ne.typeArguments.isNullOrEmpty() || !ne.leadingTypeArguments.isNullOrEmpty()) return
        val cls = classes[calleeId.text] ?: return
        // (CHK.199) the callee must RESOLVE to that top-level class here, not to a shadowing
        // binding (a parameter, a local `const`, a block-scoped class — all measured; a
        // separate B83.5 lexical-table refusal ablated to 0 RED and was not kept).
        if ((checker.getCalleeType(calleeId) as? Type.Interface)?.symbol?.declarations?.contains(cls) != true) return
        if (cls.members.any { it is Constructor }) return  // own ctor → main path owns it
        if (!cls.typeParameters.isNullOrEmpty()) return
        val info = resolveCtor(cls, classes) ?: return
        val callArgs = callArgs0
        val argTypesOrNull = callArgs.map { argPrim(it) }
        if (argTypesOrNull.any { it == null }) return
        val argTypes = argTypesOrNull.filterNotNull()
        val applicable = info.overloads.withIndex().filter { arityFits(it.value, argTypes.size) }
        if (applicable.isEmpty()) return
        val mismatches = applicable.map { it to firstMismatch(it.value, argTypes) }
        if (mismatches.any { it.second == null }) return  // some overload matches
        if (applicable.size == 1) {
            val (idx, argT, paramT) = mismatches[0].second!!
            val argNode = callArgs[idx]
            val len = when (argNode) {
                is StringLiteralNode -> (argNode.rawText?.length ?: argNode.text.length) + 2
                is NumericLiteralNode -> argNode.text.length
                is Identifier -> argNode.text.length
                else -> 1
            }
            // (P18.258) the ordinary argument check already drew this row when the
            // mismatching parameter needs no heritage substitution (`b: number`).
            if (checker.diagnostics.any { it.code == 2345 && it.start == argNode.pos && it.fileName == fileName }) return
            val (line, ch) = checker.getLineAndCharacterOfPosition(source, argNode.pos)
            checker.diagnostics.add(Diagnostic(
                message = "Argument of type '$argT' is not assignable to parameter of type '$paramT'.",
                category = DiagnosticCategory.Error, code = 2345,
                fileName = fileName, line = line, character = ch,
                start = argNode.pos, length = len,
            ))
        } else {
            // (LEGACY.0b) F3: TypeScript 7 reports the LAST arity-applicable failing
            // candidate only, anchored at ITS own mismatching argument (tsc 6 listed
            // every candidate and anchored at the callee), with `The last overload is
            // declared here.` (TS2771) at that candidate's declaration.
            val (lastIv, lastMm) = mismatches.last()
            val (argIdx, argT, paramT) = lastMm!!
            val chain = listOf(
                "  The last overload gave the following error.",
                "    Argument of type '$argT' is not assignable to parameter of type '$paramT'.",
            )
            val argNode = callArgs[argIdx]
            val len = when (argNode) {
                is StringLiteralNode -> (argNode.rawText?.length ?: argNode.text.length) + 2
                is NumericLiteralNode -> argNode.text.length
                is Identifier -> argNode.text.length
                else -> 1
            }
            val related = listOfNotNull(
                info.decls.getOrNull(lastIv.index)
                    ?.let { checker.lastOverloadDeclaredHereAt(it, source, fileName) }
            )
            val (line, ch) = checker.getLineAndCharacterOfPosition(source, argNode.pos)
            checker.diagnostics.add(Diagnostic(
                message = "No overload matches this call.",
                category = DiagnosticCategory.Error, code = 2769,
                fileName = fileName, line = line, character = ch,
                start = argNode.pos, length = len,
                messageChain = chain, relatedInformation = related,
            ))
        }
    }

    private var topLevelClassesFile: SourceFile? = null
    private var topLevelClasses: Map<String, ClassDeclaration> = emptyMap()

    /** The named top-level classes of [file], memoized for the last file asked. */
    private fun topLevelClassesOf(file: SourceFile): Map<String, ClassDeclaration> {
        if (topLevelClassesFile !== file) {
            topLevelClasses = file.statements
                .filterIsInstance<ClassDeclaration>()
                .filter { it.name != null }
                .associateBy { it.name!!.text }
            topLevelClassesFile = file
        }
        return topLevelClasses
    }

    private val primKw = mapOf(
        SyntaxKind.StringKeyword to "string", SyntaxKind.NumberKeyword to "number",
        SyntaxKind.BooleanKeyword to "boolean", SyntaxKind.BigIntKeyword to "bigint",
    )

    private class ParamSpec(val name: String, val type: String, val isRest: Boolean, val isOptional: Boolean)
    private class CtorInfo(val overloads: List<List<ParamSpec>>, val total: Int, val decls: List<Constructor>)

    // resolve the ctor-owning class through the extends chain, substituting TPs
    private fun resolveCtor(cls0: ClassDeclaration, classes: Map<String, ClassDeclaration>): CtorInfo? {
        var cls = cls0
        var hops = 0
        var subst = emptyMap<String, String>()
        while (cls.members.none { it is Constructor } && hops < 6) {
            val ext = cls.heritageClauses
                ?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword }
                ?.types?.singleOrNull() ?: return null
            val baseName = (ext.expression as? Identifier)?.text ?: return null
            val base = classes[baseName] ?: return null
            val args = ext.typeArguments
            subst = if (args != null) {
                val tps = base.typeParameters ?: return null
                if (tps.size != args.size) return null
                tps.indices.associate { i ->
                    tps[i].name.text to (primKw[(args[i] as? KeywordTypeNode)?.kind] ?: return null)
                }
            } else {
                if (!base.typeParameters.isNullOrEmpty()) return null
                emptyMap()
            }
            cls = base
            hops++
        }
        val ctors = cls.members.filterIsInstance<Constructor>()
        if (ctors.isEmpty()) return null
        // OVERLOADED signatures only — the single-signature inherited-ctor case is
        // already handled by the existing rest-arg path (double-emit otherwise:
        // inheritedConstructorWithRestParams regressed on the first cut)
        val sigs = ctors.filter { it.body == null }
        if (sigs.size < 2) return null
        val overloads = sigs.map { c ->
            c.parameters.map { p ->
                val pn = (p.name as? Identifier)?.text ?: return null
                val t = p.type ?: return null
                val typeName: String
                val rest = p.dotDotDotToken
                if (rest) {
                    val at = t as? ArrayType ?: return null
                    typeName = when (val et = at.elementType) {
                        is KeywordTypeNode -> primKw[et.kind] ?: return null
                        is TypeReference -> subst[(et.typeName as? Identifier)?.text] ?: return null
                        else -> return null
                    }
                } else {
                    typeName = when (t) {
                        is KeywordTypeNode -> primKw[t.kind] ?: return null
                        is TypeReference -> subst[(t.typeName as? Identifier)?.text] ?: return null
                        else -> return null
                    }
                }
                ParamSpec(pn, typeName, rest, p.questionToken)
            }
        }
        return CtorInfo(overloads, overloads.size, sigs)
    }

    private fun argPrim(e: Expression): String? = when (e) {
        is StringLiteralNode -> "string"
        is NumericLiteralNode -> "number"
        is Identifier -> when (e.text) { "true", "false" -> "boolean"; else -> null }
        else -> null
    }
    private fun arityFits(ps: List<ParamSpec>, n: Int): Boolean {
        val restIdx = ps.indexOfFirst { it.isRest }
        val minCount = ps.count { !it.isRest && !it.isOptional }
        return if (restIdx >= 0) n >= minCount else n in minCount..ps.size
    }
    // first (argIndex, argType, paramType) mismatch or null
    private fun firstMismatch(ps: List<ParamSpec>, args: List<String>): Triple<Int, String, String>? {
        val restIdx = ps.indexOfFirst { it.isRest }
        for ((i, a) in args.withIndex()) {
            val pt = if (restIdx >= 0 && i >= restIdx) ps[restIdx].type
            else ps.getOrNull(i)?.type ?: return null
            if (a != pt) return Triple(i, a, pt)
        }
        return null
    }
    private fun paramsDisplay(ps: List<ParamSpec>): String = ps.joinToString(", ") { p ->
        val prefix = if (p.isRest) "..." else ""
        val opt = if (p.isOptional) "?" else ""
        val t = if (p.isRest) "${p.type}[]" else p.type
        "$prefix${p.name}$opt: $t"
    }
}

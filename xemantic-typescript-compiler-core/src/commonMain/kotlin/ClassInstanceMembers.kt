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
 * (INV.0) (P18.245) — the CLASS-INSTANCE MISSING-MEMBER family: the conservative TS2339 for a
 * member genuinely absent from a class's resolvable instance side
 * ([tryEmitClassInstanceMissingTs2339]), its TS2551 spelling suggestion over the extends chain
 * ([emitClassChainTs2551Suggestion]), the TS2576 "did you mean the static member" emitter
 * ([tryEmitStaticAccessTs2576]), and the chain walks they share
 * ([lookupInstanceMemberInResolvableChain] with (CHK.182)'s [mergedInterfaceHasMember],
 * [hasInstanceMemberNamed], [isStaticMemberOfClass], [classMemberNameText]). Extracted VERBATIM
 * from `Checker.kt` (one contiguous span, 161122-161500); every Checker member it reads is
 * reached through [checker]. Ambient reads: `docs/inversion-ambient-ledger.md` row 16.
 */
internal class ClassInstanceMembers(
    private val checker: Checker,
) {

    /** Check if `name` is a static member of `classDecl` (including inherited). */
    /**
     * Returns true if [classDecl] (or a base class) declares an INSTANCE member named [name].
     * Instance members include:
     *  - non-static PropertyDeclaration / MethodDeclaration / GetAccessor / SetAccessor
     *  - Constructor parameter properties (parameters with an access modifier or readonly)
     * Used to suppress TS2576 "did you mean static" for `this.X` when the class has
     * BOTH an instance member X and a static member X. `this.X` in an instance method
     * legitimately resolves to the instance member.
     */
    /** Walk [classDecl]'s instance-side member set + extends chain looking for a
     *  member named [propName]. Returns:
     *
     *  - `true` — member found in this class or any safely-resolvable base.
     *  - `false` — chain fully resolved (terminates at a class with no extends or
     *    a cycle) without finding the member.
     *  - `null` — chain has un-resolvable parts (non-Identifier extends like
     *    `Foo.Bar` or `q<T>()`, an Identifier base whose symbol isn't a
     *    [ClassDeclaration] in [globals], an `IndexSignature` member that would
     *    accept any property name, or a `declare class` whose lib augmentations
     *    we can't see). Caller MUST treat `null` as "unsafe to emit" and bail.
     *
     *  Used by TS2339 emitters that need to ask "is this property genuinely
     *  missing from the entire instance-side view?" without re-walking the
     *  chain manually. Implements clauses are intentionally NOT followed —
     *  per [resolveBaseTypesLazy], implements is a structural constraint, not
     *  a source of inherited members. */
    /** (CHK.182) What the [InterfaceDeclaration]s merged into a class's OWN symbol say
     *  about [propName]: `true` = one of them declares it, `false` = none does (or the
     *  symbol carries no interface at all), `null` = a merged interface this walk cannot
     *  read in full — a lib declaration, an `extends` list (its bases would contribute
     *  members), or a member that is not a plain named property / method / accessor
     *  (an index, call or construct signature, a computed name).
     *
     *  This replaced a program-wide NAME set ("a class named like ANY interface in ANY
     *  file"), which made every class named like an interface in some OTHER module file
     *  report nothing at all — module-scoped names never merge (INV.3(d)), so asking the
     *  symbol is both the sound question and the complete one. It also reads a genuine
     *  same-scope merge (`interface D` + `class D` in one scope — the binder does merge
     *  them into one symbol) instead of refusing it, which is what tsgo reports. */
    private fun mergedInterfaceHasMember(classSym: Symbol, propName: String): Boolean? {
        var found = false
        val visited = HashSet<Int>()
        for (d in classSym.declarations) {
            if (d !is InterfaceDeclaration) continue
            when (interfaceChainHasMember(d, propName, visited)) {
                null -> return null
                true -> found = true
                false -> {}
            }
        }
        return found
    }

    /** (CHK.187) One [InterfaceDeclaration] of [mergedInterfaceHasMember], with its
     *  `extends` list FOLLOWED rather than refused: each base must be an Identifier naming
     *  a symbol whose declarations are all program interfaces (resolved where the clause
     *  is written, as a class base is), and each is read the same way. Anything else — a
     *  lib interface, a qualified or computed base, a type alias, a class — is `null`. */
    private fun interfaceChainHasMember(d: InterfaceDeclaration, propName: String, visited: MutableSet<Int>): Boolean? {
        if (d in checker.builtinLibDecls) return null
        var found = false
        for (m in d.members) {
            val name = when (m) {
                is PropertyDeclaration -> classMemberNameText(m.name)
                is MethodDeclaration -> classMemberNameText(m.name)
                is GetAccessor -> classMemberNameText(m.name)
                is SetAccessor -> classMemberNameText(m.name)
                is SemicolonClassElement -> continue
                is IndexSignature -> if (numberIndexCannotName(m, propName)) continue else return null
                else -> return null
            } ?: return null
            if (name.isEmpty()) return null
            if (name == propName) found = true
        }
        if (found) return true
        for (clause in d.heritageClauses.orEmpty()) {
            if (clause.token != SyntaxKind.ExtendsKeyword) return null
            for (t in clause.types) {
                val baseId = t.expression as? Identifier ?: return null
                val baseSym = resolveBaseClassSymbol(baseId) ?: return null
                if (!visited.add(baseSym.id)) continue
                if (baseSym.declarations.isEmpty() || baseSym.declarations.any { it !is InterfaceDeclaration }) return null
                for (bd in baseSym.declarations) {
                    when (interfaceChainHasMember(bd as InterfaceDeclaration, propName, visited)) {
                        null -> return null
                        true -> return true
                        false -> {}
                    }
                }
            }
        }
        return false
    }

    /** (CHK.187) A `[k: number]: T` index signature cannot supply a property whose name is
     *  an identifier — such a name is never a numeric literal name, bar the two spellings
     *  `NaN` / `Infinity` (`String(Number(s)) === s`), which stay refused. Every other index
     *  signature (string, template, symbol, a union key) is still a refusal. */
    private fun numberIndexCannotName(m: IndexSignature, propName: String): Boolean {
        val param = m.parameters.singleOrNull() ?: return false
        if ((param.type as? KeywordTypeNode)?.kind != SyntaxKind.NumberKeyword) return false
        val first = propName.firstOrNull() ?: return false
        if (!(first.isLetter() || first == '_' || first == '$')) return false
        return propName != "NaN" && propName != "Infinity"
    }

    /**
     * (CHK.187) The symbol an `extends` clause's Identifier base names, resolved where the
     * clause is WRITTEN: [enclosingNs]'s exports first (the namespace-aware walk's own
     * leg), then [Checker.resolveHeritageBaseSymbol] — the scope-space consult (B83.5), the
     * enclosing namespaces and the DECLARING file's per-file scope, which is where a
     * module-local or imported base lives (INV.3(d) keeps both out of `globals`, so the
     * old `globals[name]` consult answered null for them and every chain walk refused). An
     * import alias the per-file probe leaves unresolved (a default import) is followed.
     * Callers still demand a [ClassDeclaration] among the answer's declarations, so a base
     * that is a variable, a call or a class expression stays refused.
     */
    private fun resolveBaseClassSymbol(baseExpr: Identifier, enclosingNs: Symbol? = null): Symbol? {
        val raw = enclosingNs?.exports?.get(baseExpr.text)
            ?: checker.resolveHeritageBaseSymbol(baseExpr) ?: return null
        return if (raw.flags.hasAny(SymbolFlags.Alias)) checker.resolveAlias(raw) else raw
    }

    /**
     * (CHK.187) The class a `new <ctor>()` receiver constructs, for the `new` branch of the
     * missing-member check when `globals` has no class of that name — a module-local or
     * imported class, or a block-scoped one (B83.5). The name is resolved where it is
     * WRITTEN (the scope-space value consult, then the per-file scope), and the answer is
     * ADOPTED ONLY WHEN THE EXPRESSION'S OWN TYPE IS THAT CLASS'S INSTANCE: a parameter or
     * a local variable shadowing the class name (which neither consult sees) types the
     * receiver differently, and a guessed class there is a false TS2339 on legal code.
     */
    fun newExpressionClassSymbol(newExpr: NewExpression, ctor: Identifier): Symbol? {
        val raw = checker.lexicalValueSymbolForNode(ctor, ctor.text)
            ?: checker.lookupPerFileForNode(ctor, ctor.text) ?: return null
        val sym = if (raw.flags.hasAny(SymbolFlags.Alias)) checker.resolveAlias(raw) else raw
        if (!sym.flags.hasAny(SymbolFlags.Class)) return null
        val instanceSym = when (val t = checker.getTypeOfExpression(newExpr)) {
            is Type.Reference -> t.target.symbol
            is Type.Interface -> t.symbol
            else -> null
        }
        return sym.takeIf { instanceSym === it }
    }

    fun lookupInstanceMemberInResolvableChain(
        classDecl: ClassDeclaration, classSym: Symbol?, propName: String, visited: MutableSet<String>? = null,
        enclosingNs: Symbol? = null,
    ): Boolean? {
        val v = visited ?: mutableSetOf()
        val className = classDecl.name?.text ?: return null
        if (!v.add(className)) return false
        // (CHK.182) the class's OWN merged interfaces, read off its symbol. A caller
        // without one cannot rule a merge out, so it gets the old refusal.
        if (classSym == null) return null
        when (mergedInterfaceHasMember(classSym, propName)) {
            null -> return null
            true -> return true
            false -> {}
        }
        if (classDecl.members.any { it is IndexSignature && !numberIndexCannotName(it, propName) }) return null
        if (ModifierFlag.Declare in classDecl.modifiers) return null
        for (m in classDecl.members) {
            when (m) {
                is PropertyDeclaration -> {
                    if (ModifierFlag.Static !in m.modifiers && classMemberNameText(m.name) == propName) return true
                }
                is MethodDeclaration -> {
                    if (ModifierFlag.Static !in m.modifiers && classMemberNameText(m.name) == propName) return true
                }
                is GetAccessor -> {
                    if (ModifierFlag.Static !in m.modifiers && classMemberNameText(m.name) == propName) return true
                }
                is SetAccessor -> {
                    if (ModifierFlag.Static !in m.modifiers && classMemberNameText(m.name) == propName) return true
                }
                is Constructor -> {
                    for (p in m.parameters) {
                        if (p.modifiers.isEmpty()) continue
                        if ((p.name as? Identifier)?.text == propName) return true
                    }
                }
                else -> {}
            }
        }
        val baseExpr = classDecl.heritageClauses
            ?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword }
            ?.types?.firstOrNull()?.expression ?: return false
        if (baseExpr !is Identifier) return null
        // Resolve the base via the enclosing namespace's exports first (a namespace-local
        // base is not in `globals`), falling back to `globals`. Without this a namespace-local
        // base returns `null` (uncertain → the caller bails), which — now that
        // getTypeFromBaseTypeExpression populates baseTypes for namespace-local bases — would
        // swallow a genuinely-missing-member TS2339. `false` still propagates ONLY through a
        // fully-resolvable chain, so this stays FP-safe (uncertainty → null → bail). `enclosingNs`
        // is null for the non-`this` callers → globals-only (unchanged).
        val baseSym = resolveBaseClassSymbol(baseExpr, enclosingNs) ?: return null
        // (CHK.182) the CLASS among the base's declarations — a merged interface may be
        // declared first, and the recursion reads it off [baseSym] anyway.
        val baseDecl = baseSym.declarations.firstOrNull { it is ClassDeclaration } as? ClassDeclaration ?: return null
        // Recurse in the base's OWN namespace (a sibling class shares this one; a base pulled
        // from globals resets to null).
        val baseNs = baseSym.parent?.takeIf { it.flags.hasAny(SymbolFlags.Module) } ?: enclosingNs
        return lookupInstanceMemberInResolvableChain(baseDecl, baseSym, propName, v, baseNs)
    }

    fun hasInstanceMemberNamed(classDecl: ClassDeclaration, name: String, visited: MutableSet<String>? = null): Boolean {
        val v = visited ?: mutableSetOf()
        val className = classDecl.name?.text ?: return false
        if (!v.add(className)) return false
        for (m in classDecl.members) {
            when (m) {
                is PropertyDeclaration -> {
                    if (ModifierFlag.Static !in m.modifiers && classMemberNameText(m.name) == name) return true
                }
                is MethodDeclaration -> {
                    if (ModifierFlag.Static !in m.modifiers && classMemberNameText(m.name) == name) return true
                }
                is GetAccessor -> {
                    if (ModifierFlag.Static !in m.modifiers && classMemberNameText(m.name) == name) return true
                }
                is SetAccessor -> {
                    if (ModifierFlag.Static !in m.modifiers && classMemberNameText(m.name) == name) return true
                }
                is Constructor -> {
                    for (p in m.parameters) {
                        if (p.modifiers.isEmpty()) continue
                        if ((p.name as? Identifier)?.text == name) return true
                    }
                }
                else -> {}
            }
        }
        val baseExpr = classDecl.heritageClauses
            ?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword }
            ?.types?.firstOrNull()?.expression
        if (baseExpr is Identifier) {
            val baseSym = resolveBaseClassSymbol(baseExpr)
            if (baseSym != null) {
                val baseDecl = baseSym.declarations.firstOrNull() as? ClassDeclaration
                if (baseDecl != null && hasInstanceMemberNamed(baseDecl, name, v)) return true
            }
        }
        return false
    }

    /** TS2576 when `instance.X` / `instance["X"]` accesses a STATIC-only member of the class.
     *  Returns true if emitted (caller should return early).
     *
     *  Only fires for INSTANCE-side access: skip when the receiver type already carries the
     *  property (e.g. `const k2: typeof K; k2.bar` — k2's type is the constructor side, so
     *  `bar` resolves there and `k2.bar` is legitimate). */
    /** TS2339 when a class-instance receiver accesses a property that is genuinely
     *  absent from the class hierarchy.
     *
     *  Conservative gates defend against false positives:
     *   - propName not in [RUNTIME_PROPERTIES];
     *   - symbol has exactly 1 class / type-alias / namespace declaration (a merged
     *     INTERFACE is read by the chain walk — (CHK.182));
     *   - class is non-generic (TypeParam constraints could supply members we
     *     don't yet check structurally);
     *   - chain walk via [lookupInstanceMemberInResolvableChain] returns `false`
     *     (own + entire extends chain resolved cleanly, no member found). The
     *     helper itself bails (`null`) on hazardous shapes — non-Identifier
     *     extends (`Foo.Bar`, `q<T>()`), unresolvable Identifier bases, ambient
     *     bases (`declare class`), index signatures, or a merged
     *     [InterfaceDeclaration] on the class's OWN symbol it cannot read in full
     *     ([mergedInterfaceHasMember], (CHK.182));
     *   - flow narrowing yields the same type (e.g., `if (c instanceof D) c.bar()`
     *     narrows `c` from C to D which may have `bar` — the narrowed type
     *     differs and we bail).
     *
     *  Implements clauses are NOT inherited members per [resolveBaseTypesLazy]'s
     *  skip-implements rule, so this fires correctly for
     *  `class C implements A {}; let c: C; c.bar()` where A has only `static bar()`. */
    fun tryEmitClassInstanceMissingTs2339(
        typeSym: Symbol, rawType: Type, propName: String, objectExpr: Expression,
        diagStart: Int, diagLength: Int, source: String, fileName: String,
    ) {
        if (propName.isEmpty()) return
        if (propName in Checker.RUNTIME_PROPERTIES) return
        // 17.130: Count only "shape-defining" declarations (Class/Interface/TypeAlias/Module).
        // Import-specifier and alias declarations are appended via `mergeSymbolTable` at init's
        // global merge step (see CLAUDE.md "ALL file locals merged into globals" gotcha), but
        // they don't contribute members to the class shape. Pre-fix: `declarations.size != 1`
        // bailed whenever a class was imported into any file, over-suppressing TS2339.
        // (CHK.182) merged INTERFACES are not counted: the chain walk below reads them
        // off [typeSym] ([mergedInterfaceHasMember]) and refuses the ones it cannot read.
        val shapeDecls = typeSym.declarations.count { d ->
            d is ClassDeclaration || d is TypeAliasDeclaration || d is ModuleDeclaration
        }
        if (shapeDecls != 1) return
        val classDecl = typeSym.declarations.firstOrNull { it is ClassDeclaration } as? ClassDeclaration ?: return
        if (!classDecl.typeParameters.isNullOrEmpty()) return
        // Walk own + extends chain. The helper itself bails on IndexSignature
        // and `declare class` (in this class OR any base), so those gates need
        // not be repeated at this level. `null` means chain isn't safely
        // resolvable (complex extends like `Foo.Bar`, unresolved Identifier,
        // ambient base, etc.); `true` means a base in the chain declares the
        // property; only `false` means "genuinely missing across the entire
        // resolvable instance side".
        if (lookupInstanceMemberInResolvableChain(classDecl, typeSym, propName) != false) return
        if (isStaticMemberOfClass(classDecl, propName)) return
        val narrowed = checker.getNarrowedTypeForReference(rawType, objectExpr)
        if (narrowed !== rawType) return
        val typeName = classDecl.name?.text ?: typeSym.name
        val (line, character) = checker.getLineAndCharacterOfPosition(source, diagStart)
        if (emitClassChainTs2551Suggestion(classDecl, typeSym, propName, typeName, diagStart, diagLength, source, fileName)) return
        checker.diagnostics.add(Diagnostic(
            message = "Property '$propName' does not exist on type '$typeName'.",
            category = DiagnosticCategory.Error, code = 2339,
            fileName = fileName, line = line, character = character,
            start = diagStart, length = diagLength,
        ))
    }

    /** Spelling suggestion over a class's resolvable extends chain's instance member names —
     *  tsc emits TS2551 "… Did you mean 'method2'?" + TS2728 related at the member
     *  (`this.method1(2)` on B whose base A parsed empty,
     *  constructorWithIncompleteTypeAnnotation). Returns true when the TS2551 was emitted. */
    fun emitClassChainTs2551Suggestion(
        classDecl: ClassDeclaration, classSym: Symbol?, propName: String, typeName: String,
        diagStart: Int, diagLength: Int, source: String, fileName: String,
    ): Boolean {
        val pool = mutableMapOf<String, Identifier>()
        var cur: ClassDeclaration? = classDecl
        var curSym: Symbol? = classSym
        var hops = 0
        while (cur != null && hops++ < 10) {
            // (CHK.182) an interface merged into this hop's class contributes candidates
            // too, in the symbol's declaration order ([mergedInterfaceHasMember] has
            // already refused every merge it cannot read in full).
            val shapes: List<Node> = curSym?.declarations
                ?.filter { it === cur || (it is InterfaceDeclaration && it !in checker.builtinLibDecls) }
                ?.takeIf { cur in it } ?: listOf(cur)
            for (shape in shapes) {
                val members = when (shape) {
                    is ClassDeclaration -> shape.members
                    is InterfaceDeclaration -> shape.members
                    else -> continue
                }
                for (m in members) {
                    val nameId = when (m) {
                        is MethodDeclaration -> if (ModifierFlag.Static !in m.modifiers) m.name as? Identifier else null
                        is PropertyDeclaration -> if (ModifierFlag.Static !in m.modifiers) m.name as? Identifier else null
                        is GetAccessor -> if (ModifierFlag.Static !in m.modifiers) m.name as? Identifier else null
                        is SetAccessor -> if (ModifierFlag.Static !in m.modifiers) m.name as? Identifier else null
                        else -> null
                    }
                    if (nameId != null && nameId.text.isNotEmpty() && nameId.text !in pool) pool[nameId.text] = nameId
                }
            }
            val baseId = cur.heritageClauses
                ?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword }
                ?.types?.firstOrNull()?.expression as? Identifier
            curSym = baseId?.let { resolveBaseClassSymbol(it) }
            cur = curSym?.declarations?.firstOrNull { d -> d is ClassDeclaration } as? ClassDeclaration
        }
        val suggestion = checker.getSpellingSuggestionFromNames(propName, pool.keys) ?: return false
        val suggNode = pool[suggestion] ?: return false
        val (declFile, declSource) = checker.resolveDeclarationSourceFile(suggNode.pos)
        val relFile = declFile ?: fileName
        val relSource = declSource ?: source
        val (relLine, relChar) = checker.getLineAndCharacterOfPosition(relSource, suggNode.pos)
        val (line, character) = checker.getLineAndCharacterOfPosition(source, diagStart)
        checker.diagnostics.add(Diagnostic(
            message = "Property '$propName' does not exist on type '$typeName'. Did you mean '$suggestion'?",
            category = DiagnosticCategory.Error, code = 2551,
            fileName = fileName, line = line, character = character,
            start = diagStart, length = diagLength,
            relatedInformation = listOf(Diagnostic(
                message = "'$suggestion' is declared here.",
                category = DiagnosticCategory.Message, code = 2728,
                fileName = relFile, line = relLine, character = relChar,
                start = suggNode.pos, length = suggestion.length,
            )),
        ))
        return true
    }

    fun tryEmitStaticAccessTs2576(
        typeSym: Symbol, propName: String, diagStart: Int, diagLength: Int,
        suggestionKey: String, source: String, fileName: String,
        receiverType: Type? = null,
    ): Boolean {
        if (propName.isEmpty()) return false
        if (propName in Checker.RUNTIME_PROPERTIES) return false
        // Only fire for INSTANCE-side access (Type.Interface). Constructor-side receivers
        // (`typeof C`, Type.Object built by `getTypeOfSymbolForTypeQuery`) carry static
        // members as actual properties and should fall through to normal TS2339 checking.
        // NOTE: Type.Interface extends Type.Object so `is Type.Object` alone matches both.
        if (receiverType != null && receiverType !is Type.Interface && receiverType is Type.Object) return false
        val classDecl = typeSym.declarations.firstOrNull() as? ClassDeclaration ?: return false
        if (!isStaticMemberOfClass(classDecl, propName)) return false
        if (hasInstanceMemberNamed(classDecl, propName)) return false
        val baseName = classDecl.name?.text ?: typeSym.name
        val (line, character) = checker.getLineAndCharacterOfPosition(source, diagStart)
        checker.diagnostics.add(Diagnostic(
            message = "Property '$propName' does not exist on type '$baseName'. Did you mean to access the static member '$baseName$suggestionKey' instead?",
            category = DiagnosticCategory.Error, code = 2576,
            fileName = fileName, line = line, character = character,
            start = diagStart, length = diagLength,
        ))
        return true
    }

    private fun classMemberNameText(nameNode: Node?): String? = when (nameNode) {
        is Identifier -> nameNode.text
        is StringLiteralNode -> nameNode.text
        is NumericLiteralNode -> nameNode.text
        // B451: a computed member name `[2]`/`["4"]` with a literal inner is a STATIC key,
        // so `z[2]` resolves against the instance member rather than FP'ing TS2339.
        // Round 933: DELEGATED to [computedLiteralKey] rather than re-spelling its `when`.
        // The two copies had drifted — this one still refused a backtick-quoted key after
        // the type-building site accepted it, so a class's own `` [`cp`] `` member resolved
        // for TS2322 and simultaneously FP'd TS2339 from this walker, in ONE compile.
        // The archive's B451 entry is explicit that this family has >= 5 independent
        // extraction sites; one shared definition is the only thing that keeps them level.
        // Round 937 — (CHK.5)(a): and LATE-BOUND keys for the same reason, one round on.
        // `class C { [K]: number }` with `const K = "p"` declares `p` at the type-building
        // site now, so a walker that still refused the key would answer "definitely no such
        // member" for a member the type HAS — which is precisely the TS2339 false positive
        // this stage exists to close (`c.p`, measured against tsc 7.0.2, which reads it as
        // `number`). [lookupInstanceMemberInResolvableChain] is that firewall's entry.
        is ComputedPropertyName -> checker.computedLiteralKey(nameNode) ?: checker.lateBoundComputedKeyName(nameNode)
        else -> null
    }

    fun isStaticMemberOfClass(classDecl: ClassDeclaration, name: String, visited: MutableSet<String>? = null): Boolean {
        val v = visited ?: mutableSetOf()
        val className = classDecl.name?.text ?: return false
        if (!v.add(className)) return false
        for (m in classDecl.members) {
            val memberName = when (m) {
                is PropertyDeclaration -> classMemberNameText(m.name)
                is MethodDeclaration -> classMemberNameText(m.name)
                is GetAccessor -> classMemberNameText(m.name)
                is SetAccessor -> classMemberNameText(m.name)
                else -> null
            }
            if (memberName != name) continue
            val isStatic = when (m) {
                is PropertyDeclaration -> ModifierFlag.Static in m.modifiers
                is MethodDeclaration -> ModifierFlag.Static in m.modifiers
                is GetAccessor -> ModifierFlag.Static in m.modifiers
                is SetAccessor -> ModifierFlag.Static in m.modifiers
                else -> false
            }
            if (isStatic) return true
        }
        val baseExpr = classDecl.heritageClauses
            ?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword }
            ?.types?.firstOrNull()?.expression
        if (baseExpr is Identifier) {
            val baseSym = resolveBaseClassSymbol(baseExpr)
            if (baseSym != null) {
                val baseDecl = baseSym.declarations.firstOrNull() as? ClassDeclaration
                if (baseDecl != null && isStaticMemberOfClass(baseDecl, name, v)) return true
            }
        }
        return false
    }
}

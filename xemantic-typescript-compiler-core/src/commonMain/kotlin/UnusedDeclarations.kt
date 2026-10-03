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
 * (INV.0) (P18.275) — the UNUSED-DECLARATION family: the three passes `checkUnusedDeclarations`
 * (TS6133 / TS6192 / TS6196 / TS6198 / TS6199 / TS6205, the statement-list walk, the declaration
 * and reference collectors under it, the class / interface / alias type-parameter checkers and the
 * private-member check), `checkUnusedParameterProperties` (TS6138) and `checkUnusedInferParameters`
 * (an `infer U` never read), with the scope model [UnusedScope] / [UnusedDecl], the per-class
 * computed-key memo `unusedComputedKeys`, and the binding-pattern span helpers. The passes are
 * registered in `Checker.initCheckPasses1`; `collectTypeReferenceNames` and
 * `computeBindingPatternSpan` keep one outside caller each.
 * Extracted VERBATIM from `Checker.kt` (four spans: 803-807, 16801-17339, 17438-20118,
 * 20180-20321); every Checker member it reads is reached through [checker]. Ambient reads:
 * `docs/inversion-ambient-ledger.md` row 20.
 */
internal class UnusedDeclarations(
    private val checker: Checker,
    private val options: CompilerOptions,
) {

    /** Match keys of computed-name private members (`x` for `private [x]: T`) for the
     *  current class being checked by `checkUnusedPrivateMembers`. Set per-class before
     *  reference collection; empty for classes with no computed-name members so the
     *  TS6133 reference collector's behaviour is unchanged in the common case. */
    private var unusedComputedKeys: Set<String> = emptySet()

    // -----------------------------------------------------------------------
    // Unused declaration checking (TS6133/TS6196)
    // -----------------------------------------------------------------------

    /**
     * Tracks declarations and references within a scope for unused checking.
     */
    private class UnusedScope {
        /** All declarations in this scope: name → list of (declaration node, name node, kind). */
        val declarations = mutableListOf<UnusedDecl>()
        /** Names referenced in this scope or any nested scope. */
        val referencedNames = mutableSetOf<String>()
    }

    private data class UnusedDecl(
        val name: String,
        val nameNode: Node,       // Node whose pos gives the error position
        val declNode: Node,       // The full declaration node
        val spanLength: Int = 0,  // Explicit squiggle length (0 = use name.length)
        val isExported: Boolean,
        val isParameter: Boolean,
        val isTypeOnly: Boolean,  // interface, type alias
        val stmtIndex: Int = -1,  // index in parent statement list (for self-reference detection)
        val parentVarStmt: VariableStatement? = null, // parent statement for TS6199 grouping
        val parentBindingPattern: Node? = null, // parent ObjectBindingPattern/ArrayBindingPattern for TS6198 grouping
        val rootBindingPattern: Node? = null, // the OUTERMOST pattern this leaf sits in — tsgo groups from the root
        val parentImportDecl: ImportDeclaration? = null, // parent import for TS6192 grouping
    )

    /**
     * TS6138 "Property 'X' is declared but its value is never read." — tsgo `checkUnusedClassMembers`'s
     * `KindConstructor` arm: a constructor parameter carrying the `private` SYNTACTIC modifier whose
     * symbol is never referenced, reported as `UnusedKindLocal` (so under `noUnusedLocals` ONLY).
     * (CHK.221) `public` / `protected` / bare `readonly` / `override` parameter properties are part of
     * the class's API and are never reported; every class in the file is checked (nested ones and
     * class expressions included), and a reference is any non-write-only access of the name inside
     * the class — through `this`, another instance (`other.p`), a string-literal element access or a
     * destructuring pattern. A plain `x.p = v` is write-only (tsgo `isWriteOnlyAccess`) and does not count.
     */
    fun checkUnusedParameterProperties() {
        if (!options.noUnusedLocals) return
        for (result in checker.checkedResults) {
            if (checker.isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
            val stack = ArrayDeque<Node>()
            stack.addLast(result.sourceFile)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                val members = when (node) {
                    is ClassDeclaration -> node.members
                    is ClassExpression -> node.members
                    else -> null
                }
                if (members != null) checkUnusedParamPropsInClass(node, members, source, fileName)
                forEachChild(node) { stack.addLast(it) }
            }
        }
    }

    private fun checkUnusedParamPropsInClass(classNode: Node, members: List<ClassElement>, source: String, fileName: String) {
        val ctor = members.firstOrNull { it is Constructor && it.body != null } as Constructor? ?: return
        val paramProps = ctor.parameters.filter { ModifierFlag.Private in it.modifiers && it.name is Identifier }
        if (paramProps.isEmpty()) return
        val referenced = collectClassMemberReferences(classNode)
        for (param in paramProps) {
            val name = param.name as Identifier
            val propName = name.text
            if (propName.isEmpty() || propName in referenced) continue
            val start = name.pos
            val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
            checker.diagnostics.add(Diagnostic(
                message = "Property '$propName' is declared but its value is never read.",
                category = DiagnosticCategory.Error,
                code = 6138,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = propName.length,
            ))
        }
    }

    /**
     * Every member name a class body READS: `x.p` / `x?.p` (except the write-only target of a plain
     * `=`), `x["p"]`, and every binding-pattern / assignment-pattern property name (a destructuring
     * of `this` or of another instance). Syntactic and deliberately over-approximate — a spurious
     * reference costs a MISSING row, never a false positive.
     */
    private fun collectClassMemberReferences(classNode: Node): Set<String> {
        val names = HashSet<String>()
        val stack = ArrayDeque<Node>()
        stack.addLast(classNode)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            when (node) {
                is BinaryExpression -> if (node.operator == SyntaxKind.Equals) {
                    val left = node.left
                    if (left is PropertyAccessExpression) {
                        stack.addLast(left.expression)
                        stack.addLast(node.right)
                        continue
                    }
                    // `({ a, b: c } = this)` — an assignment pattern reads `a` and `b` off the right side.
                    if (left is ObjectLiteralExpression) for (prop in left.properties) when (prop) {
                        is ShorthandPropertyAssignment -> names.add(prop.name.text)
                        is PropertyAssignment -> (prop.name as? Identifier)?.let { names.add(it.text) }
                        else -> {}
                    }
                }
                is PropertyAccessExpression -> names.add(node.name.text)
                is ElementAccessExpression -> (node.argumentExpression as? StringLiteralNode)?.let { names.add(it.text) }
                is BindingElement -> when (val pn = node.propertyName ?: node.name) {
                    is Identifier -> names.add(pn.text)
                    is StringLiteralNode -> names.add(pn.text)
                    else -> {}
                }
                else -> {}
            }
            forEachChild(node) { stack.addLast(it) }
        }
        return names
    }

    /**
     * 17.189: TS6133 for unused `infer T` parameters in conditional types.
     * E.g. `type Length<T> = T extends ArrayLike<infer U> ? number : never`
     * — `U` is declared by `infer U` but never used in the true branch.
     * Squiggle covers `infer U` (length = `infer ` + name).
     */
    fun checkUnusedInferParameters() {
        for (result in checker.checkedResults) {
            if (checker.isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            val fileName = result.sourceFile.fileName
            walkUnusedInferInStmts(result.sourceFile.statements, source, fileName)
        }
    }

    private fun walkUnusedInferInStmts(stmts: List<Statement>, source: String, fileName: String) {
        for (stmt in stmts) if (!isAmbientUnusedRoot(stmt)) when (stmt) {
            is TypeAliasDeclaration -> walkUnusedInferInTypeNode(stmt.type, source, fileName)
            is InterfaceDeclaration -> {
                for (m in stmt.members) when (m) {
                    is PropertyDeclaration -> m.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                    is MethodDeclaration -> {
                        m.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                        for (p in m.parameters) p.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                    }
                    is IndexSignature -> m.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                    else -> {}
                }
            }
            is ClassDeclaration -> {
                for (m in stmt.members) when (m) {
                    is PropertyDeclaration -> m.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                    is MethodDeclaration -> {
                        m.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                        for (p in m.parameters) p.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                        m.body?.let { walkUnusedInferInStmts(it.statements, source, fileName) }
                    }
                    is GetAccessor -> {
                        m.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                        m.body?.let { walkUnusedInferInStmts(it.statements, source, fileName) }
                    }
                    is SetAccessor -> {
                        for (p in m.parameters) p.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                        m.body?.let { walkUnusedInferInStmts(it.statements, source, fileName) }
                    }
                    is Constructor -> {
                        for (p in m.parameters) p.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                        m.body?.let { walkUnusedInferInStmts(it.statements, source, fileName) }
                    }
                    else -> {}
                }
            }
            is FunctionDeclaration -> {
                stmt.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                for (p in stmt.parameters) p.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                stmt.body?.let { walkUnusedInferInStmts(it.statements, source, fileName) }
            }
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                }
            }
            is Block -> walkUnusedInferInStmts(stmt.statements, source, fileName)
            is IfStatement -> {
                walkUnusedInferInStmts(listOf(stmt.thenStatement), source, fileName)
                stmt.elseStatement?.let { walkUnusedInferInStmts(listOf(it), source, fileName) }
            }
            is ForStatement -> {
                (stmt.initializer as? VariableDeclarationList)?.declarations?.forEach { d ->
                    d.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                }
                walkUnusedInferInStmts(listOf(stmt.statement), source, fileName)
            }
            is ForInStatement -> walkUnusedInferInStmts(listOf(stmt.statement), source, fileName)
            is ForOfStatement -> walkUnusedInferInStmts(listOf(stmt.statement), source, fileName)
            is WhileStatement -> walkUnusedInferInStmts(listOf(stmt.statement), source, fileName)
            is DoStatement -> walkUnusedInferInStmts(listOf(stmt.statement), source, fileName)
            is SwitchStatement -> {
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> walkUnusedInferInStmts(clause.statements, source, fileName)
                        is DefaultClause -> walkUnusedInferInStmts(clause.statements, source, fileName)
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                walkUnusedInferInStmts(stmt.tryBlock.statements, source, fileName)
                stmt.catchClause?.block?.let { walkUnusedInferInStmts(it.statements, source, fileName) }
                stmt.finallyBlock?.let { walkUnusedInferInStmts(it.statements, source, fileName) }
            }
            is LabeledStatement -> walkUnusedInferInStmts(listOf(stmt.statement), source, fileName)
            is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let {
                walkUnusedInferInStmts(it.statements, source, fileName)
            }
            else -> {}
        }
    }

    private fun walkUnusedInferInTypeNode(type: TypeNode, source: String, fileName: String) {
        when (type) {
            is ConditionalType -> {
                // Collect infer-declared names from extendsType, then check
                // whether each is referenced as a TypeReference in trueType.
                val inferNames = mutableMapOf<String, InferType>()
                collectInferDecls(type.extendsType, inferNames)
                if (inferNames.isNotEmpty()) {
                    val refs = mutableSetOf<String>()
                    collectTypeReferenceNames(type.trueType, refs)
                    for ((name, inferNode) in inferNames) {
                        if (name in refs) continue
                        // (P18.271) a `_`-prefixed type parameter is never unused (tsgo
                        // `isUnreferencedTypeParameter`) — `infer _` included.
                        if (name.startsWith("_")) continue
                        // (LEGACY.0b) TypeScript 7 reports an unused `infer U` on the
                        // type parameter's NAME (tsgo `checkUnusedInferTypeParameter`:
                        // `NewDiagnosticForNode(typeParameter.Name(), …)`), where tsc 6
                        // squiggled the whole `infer U`.
                        val start = inferNode.typeParameter.name.pos
                        val length = inferNode.typeParameter.name.text.length.coerceAtLeast(1)
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                        checker.diagnostics.add(Diagnostic(
                            message = "'$name' is declared but never used.",
                            category = DiagnosticCategory.Error,
                            code = 6196,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = start,
                            length = length,
                        ))
                    }
                }
                // Recurse into nested conditionals
                walkUnusedInferInTypeNode(type.checkType, source, fileName)
                walkUnusedInferInTypeNode(type.extendsType, source, fileName)
                walkUnusedInferInTypeNode(type.trueType, source, fileName)
                walkUnusedInferInTypeNode(type.falseType, source, fileName)
            }
            is UnionType -> type.types.forEach { walkUnusedInferInTypeNode(it, source, fileName) }
            is IntersectionType -> type.types.forEach { walkUnusedInferInTypeNode(it, source, fileName) }
            is ParenthesizedType -> walkUnusedInferInTypeNode(type.type, source, fileName)
            is ArrayType -> walkUnusedInferInTypeNode(type.elementType, source, fileName)
            is TupleType -> type.elements.forEach { walkUnusedInferInTypeNode(it, source, fileName) }
            is TypeOperator -> walkUnusedInferInTypeNode(type.type, source, fileName)
            is IndexedAccessType -> {
                walkUnusedInferInTypeNode(type.objectType, source, fileName)
                walkUnusedInferInTypeNode(type.indexType, source, fileName)
            }
            is FunctionType -> {
                type.parameters.forEach { p -> p.type?.let { walkUnusedInferInTypeNode(it, source, fileName) } }
                walkUnusedInferInTypeNode(type.type, source, fileName)
            }
            is ConstructorType -> {
                type.parameters.forEach { p -> p.type?.let { walkUnusedInferInTypeNode(it, source, fileName) } }
                walkUnusedInferInTypeNode(type.type, source, fileName)
            }
            is TypeLiteral -> {
                for (m in type.members) when (m) {
                    is PropertyDeclaration -> m.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                    is MethodDeclaration -> {
                        m.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                        for (p in m.parameters) p.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                    }
                    is IndexSignature -> m.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                    else -> {}
                }
            }
            is MappedType -> {
                type.type?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                type.nameType?.let { walkUnusedInferInTypeNode(it, source, fileName) }
                type.typeParameter.constraint?.let { walkUnusedInferInTypeNode(it, source, fileName) }
            }
            is RestType -> walkUnusedInferInTypeNode(type.type, source, fileName)
            is OptionalType -> walkUnusedInferInTypeNode(type.type, source, fileName)
            is TypeReference -> type.typeArguments?.forEach { walkUnusedInferInTypeNode(it, source, fileName) }
            else -> {}
        }
    }

    private fun collectInferDecls(type: TypeNode, out: MutableMap<String, InferType>) {
        when (type) {
            is InferType -> out[type.typeParameter.name.text] = type
            is UnionType -> type.types.forEach { collectInferDecls(it, out) }
            is IntersectionType -> type.types.forEach { collectInferDecls(it, out) }
            is ParenthesizedType -> collectInferDecls(type.type, out)
            is ArrayType -> collectInferDecls(type.elementType, out)
            is TupleType -> type.elements.forEach { collectInferDecls(it, out) }
            is TypeReference -> type.typeArguments?.forEach { collectInferDecls(it, out) }
            is IndexedAccessType -> {
                collectInferDecls(type.objectType, out)
                collectInferDecls(type.indexType, out)
            }
            is FunctionType -> {
                type.parameters.forEach { p -> p.type?.let { collectInferDecls(it, out) } }
                collectInferDecls(type.type, out)
            }
            // Iter9 walker parity: mirror sibling walkers (walkUnusedInferInTypeNode,
            // collectTypeReferenceNames) to cover constructor types, type literals,
            // mapped types, type operators, rest/optional tuple elements, and
            // conditional types. Without these branches an `infer X` inside e.g.
            // `infer R extends new (...args) => infer U` would be missed during
            // declaration collection, silently dropping TS6133 candidates.
            is ConstructorType -> {
                type.parameters.forEach { p -> p.type?.let { collectInferDecls(it, out) } }
                collectInferDecls(type.type, out)
            }
            is TypeLiteral -> {
                for (m in type.members) when (m) {
                    is PropertyDeclaration -> m.type?.let { collectInferDecls(it, out) }
                    is MethodDeclaration -> {
                        m.type?.let { collectInferDecls(it, out) }
                        for (p in m.parameters) p.type?.let { collectInferDecls(it, out) }
                    }
                    is IndexSignature -> m.type?.let { collectInferDecls(it, out) }
                    else -> {}
                }
            }
            is MappedType -> {
                type.type?.let { collectInferDecls(it, out) }
                type.nameType?.let { collectInferDecls(it, out) }
                type.typeParameter.constraint?.let { collectInferDecls(it, out) }
            }
            is TypeOperator -> collectInferDecls(type.type, out)
            is RestType -> collectInferDecls(type.type, out)
            is OptionalType -> collectInferDecls(type.type, out)
            // Intentionally NOT walking ConditionalType here: nested conditionals
            // introduce their own infer-scope (each conditional's extends clause is
            // an isolated scope). Outer walker handles top-level conditional via
            // [walkUnusedInferInTypeNode]; if a nested conditional appears inside an
            // extendsType, its own `infer X` declarations belong to that inner
            // conditional, not the outer one we're collecting for.
            else -> {}
        }
    }

    fun collectTypeReferenceNames(type: TypeNode, out: MutableSet<String>) {
        when (type) {
            is TypeReference -> {
                val n = type.typeName
                if (n is Identifier) out.add(n.text)
                type.typeArguments?.forEach { collectTypeReferenceNames(it, out) }
            }
            is UnionType -> type.types.forEach { collectTypeReferenceNames(it, out) }
            is IntersectionType -> type.types.forEach { collectTypeReferenceNames(it, out) }
            is ParenthesizedType -> collectTypeReferenceNames(type.type, out)
            is ArrayType -> collectTypeReferenceNames(type.elementType, out)
            is TupleType -> type.elements.forEach { collectTypeReferenceNames(it, out) }
            is TypeOperator -> collectTypeReferenceNames(type.type, out)
            is IndexedAccessType -> {
                collectTypeReferenceNames(type.objectType, out)
                collectTypeReferenceNames(type.indexType, out)
            }
            is ConditionalType -> {
                collectTypeReferenceNames(type.checkType, out)
                collectTypeReferenceNames(type.extendsType, out)
                collectTypeReferenceNames(type.trueType, out)
                collectTypeReferenceNames(type.falseType, out)
            }
            is FunctionType -> {
                type.parameters.forEach { p -> p.type?.let { collectTypeReferenceNames(it, out) } }
                collectTypeReferenceNames(type.type, out)
            }
            is MappedType -> {
                type.type?.let { collectTypeReferenceNames(it, out) }
                type.nameType?.let { collectTypeReferenceNames(it, out) }
                type.typeParameter.constraint?.let { collectTypeReferenceNames(it, out) }
            }
            // Iter9 walker parity: cover constructor types, type literals, rest /
            // optional tuple elements. Reference-name collection for TS6133 must
            // see ALL references in the trueType — an `infer R` used inside e.g.
            // `infer R extends new (...args) => R` or `{ value: R }` would
            // otherwise be miscounted as unused.
            is ConstructorType -> {
                type.parameters.forEach { p -> p.type?.let { collectTypeReferenceNames(it, out) } }
                collectTypeReferenceNames(type.type, out)
            }
            is TypeLiteral -> {
                for (m in type.members) when (m) {
                    is PropertyDeclaration -> m.type?.let { collectTypeReferenceNames(it, out) }
                    is MethodDeclaration -> {
                        m.type?.let { collectTypeReferenceNames(it, out) }
                        for (p in m.parameters) p.type?.let { collectTypeReferenceNames(it, out) }
                    }
                    is IndexSignature -> m.type?.let { collectTypeReferenceNames(it, out) }
                    else -> {}
                }
            }
            is RestType -> collectTypeReferenceNames(type.type, out)
            is OptionalType -> collectTypeReferenceNames(type.type, out)
            // (P18.271) a template span and a type predicate reference names too.
            is TemplateLiteralType -> templateTypeReferenceNames(type.head.rawText ?: "").forEach { out.add(it.name) }
            is TypePredicate -> type.type?.let { collectTypeReferenceNames(it, out) }
            else -> {}
        }
    }

    fun checkUnusedDeclarations() {
        for (result in checker.checkedResults) {
            if (checker.isDtsFile(result.sourceFile.fileName)) continue
            val source = result.sourceFile.text
            // B98.r15: a checkJs `.js`/`.cjs` file with CommonJS exports (`exports.x = …`,
            // `module.exports = …`) is a MODULE — its file-level locals are module-scoped (not
            // global), so unused locals ARE checked (TS6133). `isModuleFile` only recognizes ES
            // import/export, so JS CommonJS modules were wrongly treated as scripts and skipped.
            val isModule = checker.isModuleFile(result.sourceFile.statements) ||
                (checker.isJsLikeFileName(result.sourceFile.fileName) &&
                    checker.hasCommonJsExportAssignment(result.sourceFile.statements))
            checkUnusedInStatements(
                result.sourceFile.statements,
                source,
                result.sourceFile.fileName,
                isTopLevel = true,
                isModuleScope = isModule,
            )
        }
    }

    private fun checkUnusedInStatements(
        statements: List<Statement>,
        source: String,
        fileName: String,
        isTopLevel: Boolean,
        isModuleScope: Boolean = false,
    ) {
        // Skip file-level declarations in non-module files (they're global)
        if (isTopLevel && !isModuleScope) {
            // Still recurse into nested scopes (namespace bodies, function bodies)
            for (stmt in statements) {
                checkUnusedInNestedScopes(stmt, source, fileName, siblingStatements = statements)
            }
            return
        }

        val scope = UnusedScope()

        // 1. Collect declarations (with statement index for self-reference detection)
        for ((idx, stmt) in statements.withIndex()) {
            collectUnusedDeclarations(stmt, scope, isTopLevel, stmtIndex = idx)
        }

        // 2. Collect references per statement (for self-reference detection)
        val refsPerStmt = Array(statements.size) { mutableSetOf<String>() }
        for ((idx, stmt) in statements.withIndex()) {
            val stmtScope = UnusedScope()
            collectUnusedReferences(stmt, stmtScope)
            refsPerStmt[idx] = stmtScope.referencedNames
            scope.referencedNames.addAll(stmtScope.referencedNames)
        }

        // 3. Report unreferenced declarations
        // A declaration is considered unused if:
        // - It's not referenced at all, OR
        // - It's only referenced from within its own declaration (self-reference)
        val unusedDecls = mutableListOf<UnusedDecl>()
        for (decl in scope.declarations) {
            val isExternallyReferenced = if (decl.stmtIndex >= 0) {
                refsPerStmt.withIndex().any { (idx, refs) ->
                    idx != decl.stmtIndex && decl.name in refs
                }
            } else {
                decl.name in scope.referencedNames
            }
            if (isExternallyReferenced) continue
            // Shorthand underscore elements in ObjectBindingPattern still contribute to TS6198
            val isShorthandUnderscore = decl.name.startsWith("_") &&
                decl.parentBindingPattern is ObjectBindingPattern &&
                (decl.declNode as? BindingElement)?.propertyName == null
            // B98.r122: a `_`-prefixed unused name is exempt from TS6133 ONLY for parameters,
            // imports, and destructuring binding-elements (TypeScript's
            // isValidUnusedLocalDeclaration). Namespaces, plain var/let/const, functions,
            // classes, enums, type-aliases and interfaces are still reported. Shorthand-`_`
            // destructuring stays in the list for TS6198 grouping (the 5283 gate below
            // suppresses its individual TS6133).
            val underscoreExempt = decl.name.startsWith("_") &&
                (decl.isParameter || decl.parentImportDecl != null ||
                    (decl.parentBindingPattern != null && !isShorthandUnderscore))
            if (underscoreExempt) continue
            if (decl.isExported) continue

            if (decl.isParameter) {
                if (!options.noUnusedParameters) continue
            } else {
                if (!options.noUnusedLocals) continue
            }

            unusedDecls.add(decl)
        }

        // Check for TS6199: if ALL declarations from a VariableStatement are unused,
        // emit a single "All variables are unused" instead of individual TS6133
        val ts6199Stmts = mutableSetOf<VariableStatement>()
        val declsByVarStmt = unusedDecls.filter { it.parentVarStmt != null }
            .groupBy { it.parentVarStmt!! }
        for ((varStmt, decls) in declsByVarStmt) {
            val totalDeclCount = varStmt.declarationList.declarations.size
            if (decls.size == totalDeclCount && totalDeclCount > 1) {
                ts6199Stmts.add(varStmt)
                // (LEGACY.0b step 3) TypeScript 7 anchors TS6199 on the VariableDeclaration
                // LIST (tsgo's `reportUnusedVariables` builds the diagnostic for the list node),
                // so the trailing `;` of the enclosing statement is OUTSIDE the squiggle where
                // tsc 6 included it.
                val stmtStart = varStmt.pos
                val lineEnd = source.indexOf('\n', stmtStart).let { if (it < 0) source.length else it }
                val spanLength = source.substring(stmtStart, lineEnd).trimEnd()
                    .removeSuffix(";").length
                val (line, character) = checker.getLineAndCharacterOfPosition(source, stmtStart)
                checker.diagnostics.add(Diagnostic(
                    message = "All variables are unused.",
                    category = DiagnosticCategory.Error,
                    code = 6199,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = stmtStart,
                    length = spanLength,
                ))
            }
        }

        // (LEGACY.0b step 13) TS6198 grouping — tsgo's `reportUnusedBindingElements`,
        // recursive from the ROOT pattern, array and object patterns alike; see
        // [reportUnusedBindingPatterns]. A leaf is "unreferenced" exactly when it survived
        // the filters above (a `_`-exempt or object-rest-extraction leaf reads as USED).
        val ts6198Suppressed = reportUnusedBindingPatterns(unusedDecls, source, fileName)

        // Check for TS6192: if ALL bindings from an import declaration are unused,
        // emit a single "All imports in import declaration are unused." instead of individual TS6133.
        val ts6192Imports = mutableSetOf<ImportDeclaration>()
        val declsByImport = unusedDecls.filter { it.parentImportDecl != null }
            .groupBy { it.parentImportDecl!! }
        for ((importDecl, decls) in declsByImport) {
            val clause = importDecl.importClause ?: continue
            // Count total non-type-only bindings
            var totalBindings = 0
            if (clause.name != null) totalBindings++ // default import
            when (val nb = clause.namedBindings) {
                is NamedImports -> totalBindings += nb.elements.count { !it.isTypeOnly }
                is NamespaceImport -> totalBindings++
                else -> {}
            }
            if (totalBindings > 1 && decls.size == totalBindings) {
                ts6192Imports.add(importDecl)
                // Emit TS6192 for the entire import statement
                val stmtStart = importDecl.pos
                val stmtLineEnd = source.indexOf('\n', stmtStart).let { if (it < 0) source.length else it }
                val spanLength = source.substring(stmtStart, stmtLineEnd).trimEnd().length
                val (line, character) = checker.getLineAndCharacterOfPosition(source, stmtStart)
                checker.diagnostics.add(Diagnostic(
                    message = "All imports in import declaration are unused.",
                    category = DiagnosticCategory.Error,
                    code = 6192,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = stmtStart,
                    length = spanLength,
                ))
            }
        }

        for (decl in unusedDecls) {
            // Skip declarations already handled by TS6199
            if (decl.parentVarStmt != null && decl.parentVarStmt in ts6199Stmts) continue
            // Skip declarations already handled by TS6198
            if (decl.nameNode.pos in ts6198Suppressed) continue
            // Skip declarations already handled by TS6192
            if (decl.parentImportDecl != null && decl.parentImportDecl in ts6192Imports) continue
            // (LEGACY.0b step 13) a `_`-prefixed destructuring element still in the list is an
            // object-pattern SHORTHAND (`{ _a, b }`), which tsgo's `isUnreferencedVariableDeclaration`
            // does NOT exempt — it is reported individually when its pattern is not grouped
            // (tsgo-verified: `const { _h, i } = o; i;` → `'_h' is declared but its value is
            // never read`). Every other `_` element was filtered out above.

            val nameNode = decl.nameNode
            // (LEGACY.0b step 3) TypeScript 7 anchors an ungrouped destructuring element's
            // TS6133 on the element NAME whatever the pattern's size: tsgo's
            // `reportUnusedBindingElements` groups into TS6198 only for MORE THAN ONE element
            // and otherwise falls through to `reportUnusedVariableDeclarations`, which anchors
            // on `declaration.Name()`. tsc 6 rerouted a one-element pattern in a variable
            // declaration through `unusedVariables`, which anchored on the PATTERN; that
            // special case is gone.
            // For import specifiers with aliases (e.g. `test2 as t2`), point to the local name `t2`
            val start = when {
                nameNode is ImportSpecifier && nameNode.propertyName != null -> nameNode.name.pos
                else -> nameNode.pos
            }
            val length = when {
                decl.spanLength > 0 -> decl.spanLength
                else -> decl.name.length
            }
            val (line, character) = checker.getLineAndCharacterOfPosition(source, start)

            // Classes, interfaces, type aliases, enums use TS6196 "declared but never used"
            // Variables, functions, parameters use TS6133 "declared but its value is never read"
            val isTypeDecl = decl.declNode is ClassDeclaration || decl.declNode is InterfaceDeclaration
                    || decl.declNode is TypeAliasDeclaration || decl.declNode is EnumDeclaration
            val code = if (isTypeDecl) 6196 else 6133
            val message = if (isTypeDecl) {
                "'${decl.name}' is declared but never used."
            } else {
                "'${decl.name}' is declared but its value is never read."
            }

            checker.diagnostics.add(Diagnostic(
                message = message,
                category = DiagnosticCategory.Error,
                code = code,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }

        // 4. Recurse into nested scopes (function bodies, class bodies, etc.)
        for (stmt in statements) {
            checkUnusedInNestedScopes(stmt, source, fileName, siblingStatements = statements)
        }
    }

    private fun collectUnusedDeclarations(
        stmt: Statement,
        scope: UnusedScope,
        isTopLevel: Boolean,
        stmtIndex: Int = -1,
    ) {
        when (stmt) {
            is VariableStatement -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val isExported = ModifierFlag.Export in stmt.modifiers
                for (decl in stmt.declarationList.declarations) {
                    collectVarDeclNames(decl.name, decl, isExported, scope, stmtIndex, parentVarStmt = stmt)
                }
            }
            is FunctionDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val name = stmt.name ?: return
                val isExported = ModifierFlag.Export in stmt.modifiers ||
                    ModifierFlag.Default in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = name.text,
                    nameNode = name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = false,
                    stmtIndex = stmtIndex,
                ))
            }
            is ClassDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val name = stmt.name ?: return
                val isExported = ModifierFlag.Export in stmt.modifiers ||
                    ModifierFlag.Default in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = name.text,
                    nameNode = name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = false,
                    stmtIndex = stmtIndex,
                ))
            }
            is InterfaceDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val isExported = ModifierFlag.Export in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = stmt.name.text,
                    nameNode = stmt.name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = true,
                    stmtIndex = stmtIndex,
                ))
            }
            is TypeAliasDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val isExported = ModifierFlag.Export in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = stmt.name.text,
                    nameNode = stmt.name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = true,
                    stmtIndex = stmtIndex,
                ))
            }
            is EnumDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val isExported = ModifierFlag.Export in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = stmt.name.text,
                    nameNode = stmt.name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = false,
                    stmtIndex = stmtIndex,
                ))
            }
            is ModuleDeclaration -> {
                if (ModifierFlag.Declare in stmt.modifiers) return
                val nameNode = stmt.name
                if (nameNode is Identifier) {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    scope.declarations.add(UnusedDecl(
                        name = nameNode.text,
                        nameNode = nameNode,
                        declNode = stmt,
                        isExported = isExported,
                        isParameter = false,
                        isTypeOnly = false,
                        stmtIndex = stmtIndex,
                    ))
                }
            }
            is ImportDeclaration -> {
                if (stmt.importClause?.isTypeOnly == true) return
                val clause = stmt.importClause ?: return
                val bindings = clause.namedBindings
                // (LEGACY.0b step 3) TypeScript 7 anchors an unused import's TS6133 on the
                // imported NAME at every binding count: tsgo's `reportUnusedImports` groups
                // into TS6192 only when the clause declares MORE THAN ONE binding and all of
                // them are unused, and otherwise calls `reportUnusedLocal`, which anchors on
                // `node.Name()`. tsc 6 additionally anchored a SINGLE unused binding on the
                // whole import statement; that special case is gone.
                when (bindings) {
                    is NamedImports -> {
                        for (spec in bindings.elements) {
                            if (spec.isTypeOnly) continue
                            scope.declarations.add(UnusedDecl(
                                name = spec.name.text,
                                nameNode = spec,
                                declNode = stmt,
                                isExported = false,
                                isParameter = false,
                                isTypeOnly = false,
                                stmtIndex = stmtIndex,
                                parentImportDecl = stmt,
                            ))
                        }
                    }
                    is NamespaceImport -> {
                        scope.declarations.add(UnusedDecl(
                            name = bindings.name.text,
                            nameNode = bindings.name,
                            declNode = stmt,
                            isExported = false,
                            isParameter = false,
                            isTypeOnly = false,
                            stmtIndex = stmtIndex,
                            parentImportDecl = stmt,
                        ))
                    }
                    else -> {}
                }
                // Default import
                if (clause.name != null) {
                    scope.declarations.add(UnusedDecl(
                        name = clause.name.text,
                        nameNode = clause.name,
                        declNode = stmt,
                        isExported = false,
                        isParameter = false,
                        isTypeOnly = false,
                        stmtIndex = stmtIndex,
                        parentImportDecl = stmt,
                    ))
                }
            }
            is ImportEqualsDeclaration -> {
                val isExported = ModifierFlag.Export in stmt.modifiers
                scope.declarations.add(UnusedDecl(
                    name = stmt.name.text,
                    nameNode = stmt.name,
                    declNode = stmt,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = false,
                    stmtIndex = stmtIndex,
                ))
            }
            else -> {}
        }
    }

    private fun collectVarDeclNames(
        name: Expression,
        declNode: Node,
        isExported: Boolean,
        scope: UnusedScope,
        stmtIndex: Int = -1,
        parentVarStmt: VariableStatement? = null,
        parentBindingPattern: Node? = null,
        rootBindingPattern: Node? = null,
    ) {
        when (name) {
            is Identifier -> {
                scope.declarations.add(UnusedDecl(
                    name = name.text,
                    nameNode = name,
                    declNode = declNode,
                    isExported = isExported,
                    isParameter = false,
                    isTypeOnly = false,
                    stmtIndex = stmtIndex,
                    parentVarStmt = parentVarStmt,
                    parentBindingPattern = parentBindingPattern,
                    rootBindingPattern = rootBindingPattern,
                ))
            }
            is ObjectBindingPattern -> {
                // tsgo `isUnreferencedVariableDeclaration`: in `{ a, ...b }` every non-rest
                // element is USED (it removes a property from `b`); `b` may still be unused.
                val hasRest = name.elements.any { it.dotDotDotToken }
                val root = rootBindingPattern ?: name
                for (element in name.elements) {
                    if (hasRest && !element.dotDotDotToken) continue // skip extraction vars
                    // Rest elements get identifier span (no parentBindingPattern), non-rest get pattern span
                    collectVarDeclNames(element.name, element, isExported, scope, stmtIndex, parentVarStmt,
                        parentBindingPattern = if (element.dotDotDotToken) null else name,
                        rootBindingPattern = root)
                }
            }
            is ArrayBindingPattern -> {
                // (LEGACY.0b step 13) an ARRAY pattern's rest exempts nothing: tsgo's "removes a
                // property from the rest" rule is object-pattern only, so `const [n, ...rest] = o`
                // with both unused is ONE TS6198 over the pattern (tsgo-verified). Nested patterns
                // recurse with the same ROOT — see [reportUnusedBindingPatterns].
                val root = rootBindingPattern ?: name
                for (element in name.elements) {
                    if (element is BindingElement) {
                        collectVarDeclNames(element.name, element, isExported, scope, stmtIndex, parentVarStmt,
                            parentBindingPattern = name, rootBindingPattern = root)
                    }
                }
            }
            else -> {}
        }
    }

    /**
     * Collect destructuring parameter binding element names for unused checking, RECURSING
     * into nested patterns with the same [root] (tsgo's `reportUnusedParameters` →
     * `reportUnusedVariableDeclarations` → `reportUnusedBindingElements`, checker.go).
     *
     * The leaves collected here are exactly the ones tsgo's `isUnreferencedVariableDeclaration`
     * can answer true for: a `_`-prefixed binding element is USED unless it is an
     * object-pattern SHORTHAND (`{ _a }`), and in `{ a, ...rest }` every non-rest element is
     * used — so neither is collected, and a pattern holding one of them can never be "all
     * unused" (tsgo-verified: `([_e, _f]: any)` reports nothing, `({ _g, _h }: any)` groups).
     */
    private fun collectDestructuringParamNames(
        pattern: Expression,
        param: Parameter,
        scope: UnusedScope,
        root: Node = pattern,
    ) {
        val elements: List<Node> = when (pattern) {
            is ArrayBindingPattern -> pattern.elements
            is ObjectBindingPattern -> pattern.elements
            else -> return
        }
        val objectRest = pattern is ObjectBindingPattern &&
            pattern.elements.any { it.dotDotDotToken }
        for (element in elements) {
            if (element !is BindingElement) continue
            if (objectRest && !element.dotDotDotToken) continue
            when (val name = element.name) {
                is Identifier -> {
                    val shorthand = pattern is ObjectBindingPattern && element.propertyName == null
                    if (name.text.startsWith("_") && !shorthand) continue
                    scope.declarations.add(UnusedDecl(
                        name = name.text,
                        nameNode = name,
                        declNode = param,
                        isExported = false,
                        isParameter = true,
                        isTypeOnly = false,
                        parentBindingPattern = pattern,
                        rootBindingPattern = root,
                    ))
                }
                is ArrayBindingPattern, is ObjectBindingPattern ->
                    collectDestructuringParamNames(name, param, scope, root)
                else -> {}
            }
        }
    }

    /**
     * Collect all name references from a statement (including nested expressions).
     * This is used for unused declaration checking — it marks names as "referenced"
     * when they appear in value or type positions.
     */
    private fun collectUnusedReferences(stmt: Statement, scope: UnusedScope) {
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { collectRefsFromExpr(it, scope) }
                    decl.type?.let { collectRefsFromType(it, scope) }
                }
            }
            is ExpressionStatement -> collectRefsFromExpr(stmt.expression, scope)
            is ReturnStatement -> stmt.expression?.let { collectRefsFromExpr(it, scope) }
            is IfStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                collectUnusedReferences(stmt.thenStatement, scope)
                stmt.elseStatement?.let { collectUnusedReferences(it, scope) }
            }
            is Block -> stmt.statements.forEach { collectUnusedReferences(it, scope) }
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        for (decl in init.declarations) {
                            decl.initializer?.let { collectRefsFromExpr(it, scope) }
                        }
                    }
                    is Expression -> collectRefsFromExpr(init, scope)
                    else -> {}
                }
                stmt.condition?.let { collectRefsFromExpr(it, scope) }
                stmt.incrementor?.let { collectRefsFromExpr(it, scope) }
                collectUnusedReferences(stmt.statement, scope)
            }
            is ForInStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                collectUnusedReferences(stmt.statement, scope)
            }
            is ForOfStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                collectUnusedReferences(stmt.statement, scope)
            }
            is WhileStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                collectUnusedReferences(stmt.statement, scope)
            }
            is DoStatement -> {
                collectUnusedReferences(stmt.statement, scope)
                collectRefsFromExpr(stmt.expression, scope)
            }
            is SwitchStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> {
                            collectRefsFromExpr(clause.expression, scope)
                            clause.statements.forEach { collectUnusedReferences(it, scope) }
                        }
                        is DefaultClause -> {
                            clause.statements.forEach { collectUnusedReferences(it, scope) }
                        }
                        else -> {}
                    }
                }
            }
            is ThrowStatement -> stmt.expression?.let { collectRefsFromExpr(it, scope) }
            is TryStatement -> {
                stmt.tryBlock.statements.forEach { collectUnusedReferences(it, scope) }
                stmt.catchClause?.block?.statements?.forEach { collectUnusedReferences(it, scope) }
                stmt.finallyBlock?.statements?.forEach { collectUnusedReferences(it, scope) }
            }
            is LabeledStatement -> collectUnusedReferences(stmt.statement, scope)
            is WithStatement -> {
                collectRefsFromExpr(stmt.expression, scope)
                collectUnusedReferences(stmt.statement, scope)
            }
            is FunctionDeclaration -> {
                // References inside function bodies count as usage of outer scope names
                // Type parameters shadow outer names, so collect into inner scope first
                val innerScope = if (stmt.typeParameters?.isNotEmpty() == true) UnusedScope() else scope
                stmt.body?.statements?.forEach { collectUnusedReferences(it, innerScope) }
                for (param in stmt.parameters) {
                    param.initializer?.let { collectRefsFromExpr(it, innerScope) }
                    param.type?.let { collectRefsFromType(it, innerScope) }
                    param.decorators?.forEach { collectRefsFromExpr(it.expression, innerScope) }
                }
                stmt.type?.let { collectRefsFromType(it, innerScope) }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, innerScope) }
                    tp.default?.let { collectRefsFromType(it, innerScope) }
                }
                if (innerScope !== scope) {
                    val tpNames = stmt.typeParameters?.map { it.name.text }?.toSet() ?: emptySet()
                    scope.referencedNames.addAll(innerScope.referencedNames - tpNames)
                }
            }
            is ClassDeclaration -> {
                val innerScope = if (stmt.typeParameters?.isNotEmpty() == true) UnusedScope() else scope
                stmt.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        collectRefsFromExpr(type.expression, innerScope)
                        type.typeArguments?.forEach { collectRefsFromType(it, innerScope) }
                    }
                }
                stmt.decorators?.forEach { collectRefsFromExpr(it.expression, innerScope) }
                for (member in stmt.members) {
                    collectRefsFromClassElement(member, innerScope)
                }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, innerScope) }
                    tp.default?.let { collectRefsFromType(it, innerScope) }
                }
                if (innerScope !== scope) {
                    val tpNames = stmt.typeParameters?.map { it.name.text }?.toSet() ?: emptySet()
                    scope.referencedNames.addAll(innerScope.referencedNames - tpNames)
                }
            }
            is InterfaceDeclaration -> {
                // Collect references from extends clause and members
                // Type parameters shadow outer names
                val innerScope = if (stmt.typeParameters?.isNotEmpty() == true) UnusedScope() else scope
                stmt.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        collectRefsFromExpr(type.expression, innerScope)
                        type.typeArguments?.forEach { collectRefsFromType(it, innerScope) }
                    }
                }
                for (member in stmt.members) {
                    collectRefsFromClassElement(member, innerScope)
                }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, innerScope) }
                    tp.default?.let { collectRefsFromType(it, innerScope) }
                }
                if (innerScope !== scope) {
                    val tpNames = stmt.typeParameters?.map { it.name.text }?.toSet() ?: emptySet()
                    scope.referencedNames.addAll(innerScope.referencedNames - tpNames)
                }
            }
            is TypeAliasDeclaration -> {
                // Type parameters shadow outer names
                val innerScope = if (stmt.typeParameters?.isNotEmpty() == true) UnusedScope() else scope
                stmt.type.let { collectRefsFromType(it, innerScope) }
                stmt.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, innerScope) }
                    tp.default?.let { collectRefsFromType(it, innerScope) }
                }
                if (innerScope !== scope) {
                    val tpNames = stmt.typeParameters?.map { it.name.text }?.toSet() ?: emptySet()
                    scope.referencedNames.addAll(innerScope.referencedNames - tpNames)
                }
            }
            is EnumDeclaration -> {
                for (member in stmt.members) {
                    member.initializer?.let { collectRefsFromExpr(it, scope) }
                }
            }
            is ModuleDeclaration -> {
                when (val body = stmt.body) {
                    is ModuleBlock -> body.statements.forEach { collectUnusedReferences(it, scope) }
                    is ModuleDeclaration -> collectUnusedReferences(body, scope)
                    else -> {}
                }
            }
            is ExportDeclaration -> {
                // export { X } — X is a reference
                when (val clause = stmt.exportClause) {
                    is NamedExports -> {
                        for (spec in clause.elements) {
                            val name = spec.propertyName?.text ?: spec.name.text
                            scope.referencedNames.add(name)
                        }
                    }
                    else -> {}
                }
            }
            is ExportAssignment -> {
                collectRefsFromExpr(stmt.expression, scope)
            }
            else -> {}
        }
    }

    /** (P18.271) A generic function EXPRESSION's own type parameters shadow outer names: its
     *  references are collected into a fresh scope (with the parameters' constraints and
     *  defaults, which tsgo counts as uses — `<M, U extends Tgt<M>>`) and handed to [outer]
     *  minus the type-parameter names by [unusedTypeParamScopeClose]. No type parameters: the
     *  outer scope itself, exactly as before. */
    private fun unusedTypeParamInnerScope(tps: List<TypeParameter>?, outer: UnusedScope): UnusedScope {
        if (tps.isNullOrEmpty()) return outer
        val inner = UnusedScope()
        for (tp in tps) {
            tp.constraint?.let { collectRefsFromType(it, inner) }
            tp.default?.let { collectRefsFromType(it, inner) }
        }
        return inner
    }

    private fun unusedTypeParamScopeClose(tps: List<TypeParameter>?, inner: UnusedScope, outer: UnusedScope) {
        if (inner === outer || tps.isNullOrEmpty()) return
        val names = tps.mapTo(HashSet()) { it.name.text }
        for (n in inner.referencedNames) if (n !in names) outer.referencedNames.add(n)
    }

    private fun collectRefsFromExpr(expr: Expression, scope: UnusedScope) {
        when (expr) {
            is Identifier -> scope.referencedNames.add(expr.text)
            is PropertyAccessExpression -> {
                collectRefsFromExpr(expr.expression, scope)
            }
            is ElementAccessExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                collectRefsFromExpr(expr.argumentExpression, scope)
            }
            is CallExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                expr.arguments.forEach { collectRefsFromExpr(it, scope) }
                expr.typeArguments?.forEach { collectRefsFromType(it, scope) }
            }
            is NewExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                expr.arguments?.forEach { collectRefsFromExpr(it, scope) }
                expr.typeArguments?.forEach { collectRefsFromType(it, scope) }
            }
            is BinaryExpression -> {
                if (expr.operator == SyntaxKind.Equals) {
                    // Simple assignment: left side is write-only (not a read)
                    collectRefsFromExpr(expr.right, scope)
                    collectWriteTargetRefs(expr.left, scope)
                } else {
                    // Non-assignment or compound assignment: iterative traversal
                    var current: Expression = expr
                    while (current is BinaryExpression) {
                        collectRefsFromExpr(current.right, scope)
                        current = current.left
                    }
                    collectRefsFromExpr(current, scope)
                }
            }
            is ConditionalExpression -> {
                collectRefsFromExpr(expr.condition, scope)
                collectRefsFromExpr(expr.whenTrue, scope)
                collectRefsFromExpr(expr.whenFalse, scope)
            }
            is PrefixUnaryExpression -> collectRefsFromExpr(expr.operand, scope)
            is PostfixUnaryExpression -> collectRefsFromExpr(expr.operand, scope)
            is ParenthesizedExpression -> collectRefsFromExpr(expr.expression, scope)
            is ArrayLiteralExpression -> {
                expr.elements.forEach { collectRefsFromExpr(it, scope) }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> {
                            collectRefsFromExpr(prop.initializer, scope)
                            val propName = prop.name
                            if (propName is ComputedPropertyName) {
                                collectRefsFromExpr(propName.expression, scope)
                            }
                        }
                        is ShorthandPropertyAssignment -> {
                            scope.referencedNames.add(prop.name.text)
                        }
                        is SpreadAssignment -> collectRefsFromExpr(prop.expression, scope)
                        is MethodDeclaration -> {
                            (prop.name as? ComputedPropertyName)?.let { collectRefsFromExpr(it.expression, scope) }
                            prop.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                            prop.parameters.forEach { param ->
                                param.initializer?.let { collectRefsFromExpr(it, scope) }
                            }
                        }
                        is GetAccessor -> {
                            (prop.name as? ComputedPropertyName)?.let { collectRefsFromExpr(it.expression, scope) }
                            prop.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                        }
                        is SetAccessor -> {
                            (prop.name as? ComputedPropertyName)?.let { collectRefsFromExpr(it.expression, scope) }
                            prop.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                        }
                        else -> {}
                    }
                }
            }
            is ArrowFunction -> {
                val inner = unusedTypeParamInnerScope(expr.typeParameters, scope)
                when (val body = expr.body) {
                    is Block -> body.statements.forEach { collectUnusedReferences(it, inner) }
                    is Expression -> collectRefsFromExpr(body, inner)
                    else -> {}
                }
                expr.parameters.forEach { param ->
                    param.initializer?.let { collectRefsFromExpr(it, inner) }
                    param.type?.let { collectRefsFromType(it, inner) }
                }
                expr.type?.let { collectRefsFromType(it, inner) }
                unusedTypeParamScopeClose(expr.typeParameters, inner, scope)
            }
            is FunctionExpression -> {
                val inner = unusedTypeParamInnerScope(expr.typeParameters, scope)
                expr.body.statements.forEach { collectUnusedReferences(it, inner) }
                expr.parameters.forEach { param ->
                    param.initializer?.let { collectRefsFromExpr(it, inner) }
                    param.type?.let { collectRefsFromType(it, inner) }
                }
                expr.type?.let { collectRefsFromType(it, inner) }
                unusedTypeParamScopeClose(expr.typeParameters, inner, scope)
            }
            is ClassExpression -> {
                expr.heritageClauses?.forEach { clause ->
                    for (type in clause.types) {
                        collectRefsFromExpr(type.expression, scope)
                        type.typeArguments?.forEach { collectRefsFromType(it, scope) }
                    }
                }
                for (member in expr.members) {
                    collectRefsFromClassElement(member, scope)
                }
            }
            is TemplateExpression -> {
                expr.templateSpans.forEach { collectRefsFromExpr(it.expression, scope) }
            }
            is TaggedTemplateExpression -> {
                collectRefsFromExpr(expr.tag, scope)
                when (val template = expr.template) {
                    is TemplateExpression -> {
                        template.templateSpans.forEach { collectRefsFromExpr(it.expression, scope) }
                    }
                    else -> {}
                }
            }
            is SpreadElement -> collectRefsFromExpr(expr.expression, scope)
            is AwaitExpression -> collectRefsFromExpr(expr.expression, scope)
            is YieldExpression -> expr.expression?.let { collectRefsFromExpr(it, scope) }
            is DeleteExpression -> collectRefsFromExpr(expr.expression, scope)
            is TypeOfExpression -> collectRefsFromExpr(expr.expression, scope)
            is VoidExpression -> collectRefsFromExpr(expr.expression, scope)
            is AsExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                collectRefsFromType(expr.type, scope)
            }
            is SatisfiesExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                collectRefsFromType(expr.type, scope)
            }
            is NonNullExpression -> collectRefsFromExpr(expr.expression, scope)
            is TypeAssertionExpression -> {
                collectRefsFromExpr(expr.expression, scope)
                collectRefsFromType(expr.type, scope)
            }
            is CommaListExpression -> expr.elements.forEach { collectRefsFromExpr(it, scope) }
            is JsxElement -> {
                collectRefsFromExpr(expr.openingElement.tagName, scope)
                for (child in expr.children) {
                    if (child is JsxExpressionContainer) {
                        child.expression?.let { collectRefsFromExpr(it, scope) }
                    } else if (child is Expression) {
                        collectRefsFromExpr(child, scope)
                    }
                }
            }
            is JsxSelfClosingElement -> {
                collectRefsFromExpr(expr.tagName, scope)
                for (attr in expr.attributes) {
                    when (attr) {
                        is JsxAttribute -> {
                            val v = attr.value
                            if (v is JsxExpressionContainer) {
                                v.expression?.let { collectRefsFromExpr(it, scope) }
                            } else if (v is Expression) {
                                collectRefsFromExpr(v, scope)
                            }
                        }
                        is JsxSpreadAttribute -> collectRefsFromExpr(attr.expression, scope)
                        else -> {}
                    }
                }
            }
            is JsxFragment -> {
                for (child in expr.children) {
                    if (child is JsxExpressionContainer) {
                        child.expression?.let { collectRefsFromExpr(it, scope) }
                    } else if (child is Expression) {
                        collectRefsFromExpr(child, scope)
                    }
                }
            }
            else -> {} // literals, omitted expressions, binding patterns, etc.
        }
    }

    /**
     * For write-only targets (left side of `=`), only collect references from
     * property access bases and element access arguments, not the target identifier itself.
     */
    private fun collectWriteTargetRefs(expr: Expression, scope: UnusedScope) {
        when (expr) {
            is Identifier -> {} // Don't add — this is a write target, not a read
            is PropertyAccessExpression -> {
                // obj.prop = value — obj IS read
                collectRefsFromExpr(expr.expression, scope)
            }
            is ElementAccessExpression -> {
                // obj[key] = value — both obj and key are read
                collectRefsFromExpr(expr.expression, scope)
                collectRefsFromExpr(expr.argumentExpression, scope)
            }
            is ArrayLiteralExpression -> {
                // [x, y] = [1, 2] — destructuring write: elements are write targets
                for (element in expr.elements) {
                    when (element) {
                        is SpreadElement -> collectWriteTargetRefs(element.expression, scope)
                        is BinaryExpression -> {
                            // [x = default] — x is write target, default IS a read
                            if (element.operator == SyntaxKind.Equals) {
                                collectWriteTargetRefs(element.left, scope)
                                collectRefsFromExpr(element.right, scope)
                            } else {
                                collectRefsFromExpr(element, scope)
                            }
                        }
                        is OmittedExpression -> {} // skip holes
                        else -> collectWriteTargetRefs(element, scope)
                    }
                }
            }
            is ObjectLiteralExpression -> {
                // { x, y } = { x: 1, y: 2 } — destructuring write: properties are write targets
                for (prop in expr.properties) {
                    when (prop) {
                        is ShorthandPropertyAssignment -> {
                            // { x } = obj — x is a write target
                            if (prop.objectAssignmentInitializer != null) {
                                // { x = default } = obj — x is write target, default IS a read
                                collectRefsFromExpr(prop.objectAssignmentInitializer, scope)
                            }
                            // Don't add prop.name to referencedNames
                        }
                        is PropertyAssignment -> {
                            // { key: target } = obj — key is a read (if computed), target is write
                            if (prop.name is ComputedPropertyName) {
                                collectRefsFromExpr((prop.name).expression, scope)
                            }
                            collectWriteTargetRefs(prop.initializer, scope)
                        }
                        is SpreadAssignment -> collectWriteTargetRefs(prop.expression, scope)
                        else -> {}
                    }
                }
            }
            is ParenthesizedExpression -> collectWriteTargetRefs(expr.expression, scope)
            else -> collectRefsFromExpr(expr, scope) // fallback: treat as read
        }
    }

    private fun collectRefsFromClassElement(element: ClassElement, scope: UnusedScope) {
        // (P18.271) a computed member name is a value read (`get [KEY]()`, `[KEY]: number`).
        val computedName = when (element) {
            is PropertyDeclaration -> element.name
            is MethodDeclaration -> element.name
            is GetAccessor -> element.name
            is SetAccessor -> element.name
            else -> null
        }
        (computedName as? ComputedPropertyName)?.let { collectRefsFromExpr(it.expression, scope) }
        when (element) {
            // (P18.271) an index signature's parameter and value types reference names.
            is IndexSignature -> {
                element.parameters.forEach { param -> param.type?.let { collectRefsFromType(it, scope) } }
                element.type?.let { collectRefsFromType(it, scope) }
            }
            is PropertyDeclaration -> {
                element.initializer?.let { collectRefsFromExpr(it, scope) }
                element.type?.let { collectRefsFromType(it, scope) }
                element.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
            }
            is MethodDeclaration -> {
                element.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                element.parameters.forEach { param ->
                    param.initializer?.let { collectRefsFromExpr(it, scope) }
                    param.type?.let { collectRefsFromType(it, scope) }
                    param.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
                }
                element.type?.let { collectRefsFromType(it, scope) }
                element.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
                element.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, scope) }
                    tp.default?.let { collectRefsFromType(it, scope) }
                }
            }
            is Constructor -> {
                element.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                element.parameters.forEach { param ->
                    param.initializer?.let { collectRefsFromExpr(it, scope) }
                    param.type?.let { collectRefsFromType(it, scope) }
                    param.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
                }
            }
            is GetAccessor -> {
                element.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                element.type?.let { collectRefsFromType(it, scope) }
                element.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
            }
            is SetAccessor -> {
                element.body?.statements?.forEach { collectUnusedReferences(it, scope) }
                element.parameters.forEach { param ->
                    param.type?.let { collectRefsFromType(it, scope) }
                }
                element.decorators?.forEach { collectRefsFromExpr(it.expression, scope) }
            }
            is ClassStaticBlockDeclaration -> {
                element.body.statements.forEach { collectUnusedReferences(it, scope) }
            }
            else -> {}
        }
    }

    /**
     * Collect name references from type nodes. Type references count as usage
     * for unused declaration checking — `let x: MyType` counts as using `MyType`.
     */
    private fun collectRefsFromType(type: TypeNode, scope: UnusedScope) {
        when (type) {
            is TypeReference -> {
                when (val name = type.typeName) {
                    is Identifier -> scope.referencedNames.add(name.text)
                    is QualifiedName -> {
                        // For A.B.C, only the leftmost name is a scope reference
                        var current: Node = name
                        while (current is QualifiedName) current = current.left
                        if (current is Identifier) scope.referencedNames.add(current.text)
                    }
                    else -> {}
                }
                type.typeArguments?.forEach { collectRefsFromType(it, scope) }
            }
            is ArrayType -> collectRefsFromType(type.elementType, scope)
            is TupleType -> type.elements.forEach { collectRefsFromType(it, scope) }
            is UnionType -> type.types.forEach { collectRefsFromType(it, scope) }
            is IntersectionType -> type.types.forEach { collectRefsFromType(it, scope) }
            is ParenthesizedType -> collectRefsFromType(type.type, scope)
            is FunctionType -> {
                type.parameters.forEach { param ->
                    param.type?.let { collectRefsFromType(it, scope) }
                }
                collectRefsFromType(type.type, scope)
                type.typeParameters?.forEach { tp ->
                    tp.constraint?.let { collectRefsFromType(it, scope) }
                    tp.default?.let { collectRefsFromType(it, scope) }
                }
            }
            is ConstructorType -> {
                type.parameters.forEach { param ->
                    param.type?.let { collectRefsFromType(it, scope) }
                }
                collectRefsFromType(type.type, scope)
            }
            is TypeQuery -> {
                when (val name = type.exprName) {
                    is Identifier -> scope.referencedNames.add(name.text)
                    is QualifiedName -> {
                        var current: Node = name
                        while (current is QualifiedName) current = current.left
                        if (current is Identifier) scope.referencedNames.add(current.text)
                    }
                    else -> {}
                }
            }
            is TypeLiteral -> {
                for (member in type.members) {
                    when (member) {
                        is PropertyDeclaration -> {
                            (member.name as? ComputedPropertyName)?.let { collectRefsFromExpr(it.expression, scope) }
                            member.type?.let { collectRefsFromType(it, scope) }
                        }
                        is MethodDeclaration -> {
                            member.parameters.forEach { param ->
                                param.type?.let { collectRefsFromType(it, scope) }
                            }
                            member.type?.let { collectRefsFromType(it, scope) }
                        }
                        is IndexSignature -> {
                            member.parameters.forEach { param ->
                                param.type?.let { collectRefsFromType(it, scope) }
                            }
                            member.type?.let { collectRefsFromType(it, scope) }
                        }
                        else -> {}
                    }
                }
            }
            is ConditionalType -> {
                collectRefsFromType(type.checkType, scope)
                collectRefsFromType(type.extendsType, scope)
                collectRefsFromType(type.trueType, scope)
                collectRefsFromType(type.falseType, scope)
            }
            is MappedType -> {
                type.type?.let { collectRefsFromType(it, scope) }
                type.nameType?.let { collectRefsFromType(it, scope) }
                type.typeParameter.constraint?.let { collectRefsFromType(it, scope) }
            }
            is IndexedAccessType -> {
                collectRefsFromType(type.objectType, scope)
                collectRefsFromType(type.indexType, scope)
            }
            is TypeOperator -> collectRefsFromType(type.type, scope)
            is InferType -> {} // infer T — doesn't reference existing names
            is TemplateLiteralType -> {
                type.templateSpans.forEach { span ->
                    collectRefsFromType(span.type, scope)
                }
                // (P18.271) the spans are never parsed — read the raw slice.
                templateTypeReferenceNames(type.head.rawText ?: "").forEach { scope.referencedNames.add(it.name) }
            }
            is TypePredicate -> type.type?.let { collectRefsFromType(it, scope) }
            is RestType -> collectRefsFromType(type.type, scope)
            is NamedTupleMember -> collectRefsFromType(type.type, scope)
            is OptionalType -> collectRefsFromType(type.type, scope)
            is ImportType -> {
                type.typeArguments?.forEach { collectRefsFromType(it, scope) }
            }
            else -> {} // keyword types, literal types, this type, etc.
        }
    }

    /**
     * Recurse into nested scopes to check for unused declarations within them.
     */
    private fun checkUnusedInNestedScopes(stmt: Statement, source: String, fileName: String, siblingStatements: List<Statement>? = null) {
        // (P18.271) tsgo's `reportUnused` drops every row on a node in an AMBIENT context
        // (`NodeFlagsAmbient`): nothing inside `declare module "x" { … }` (a module augmentation
        // included), `declare namespace`, `declare class C<T>`, `declare interface`, `declare type`.
        if (isAmbientUnusedRoot(stmt)) return
        when (stmt) {
            is FunctionDeclaration -> {
                stmt.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, stmt.parameters, source, fileName,
                        typeParameters = stmt.typeParameters,
                        returnType = stmt.type,
                    )
                }
            }
            is ClassDeclaration -> {
                for (member in stmt.members) {
                    checkUnusedInClassElement(member, source, fileName)
                }
                // Check class-level type parameters
                checkUnusedClassTypeParams(stmt, source, fileName, siblingStatements)
                // Check unused private members
                checkUnusedPrivateMembers(stmt.members, source, fileName)
            }
            is InterfaceDeclaration -> {
                checkUnusedInterfaceTypeParams(stmt, source, fileName, siblingStatements)
            }
            is TypeAliasDeclaration -> {
                checkUnusedTypeAliasTypeParams(stmt, source, fileName)
            }
            is ModuleDeclaration -> {
                // 17.217: `declare global { ... }` adds AMBIENT globals — they're
                // intentionally exposed to other code, never "locally unused".
                // TypeScript suppresses TS6133 for declarations inside such blocks.
                // Detect by: identifier name "global" + Declare modifier on the
                // outer module decl (or any ancestor — this branch is also reached
                // when the global block is nested inside a `declare module "X" { ... }`
                // augmentation). Skip the unused check entirely for the body.
                val isGlobalAug = (stmt.name as? Identifier)?.text == "global" &&
                    ModifierFlag.Declare in stmt.modifiers
                if (isGlobalAug) return
                when (val body = stmt.body) {
                    is ModuleBlock -> checkUnusedInStatements(
                        body.statements, source, fileName, isTopLevel = false,
                    )
                    is ModuleDeclaration -> checkUnusedInNestedScopes(
                        body, source, fileName,
                    )
                    else -> {}
                }
            }
            is VariableStatement -> {
                // Check initializer expressions for nested function-likes
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkUnusedInExpr(it, source, fileName) }
                    // Check unused type params in type annotations (ConstructorType, FunctionType, TypeLiteral)
                    decl.type?.let { checkUnusedTypeParamsInType(it, source, fileName) }
                }
            }
            is ExpressionStatement -> {
                checkUnusedInExpr(stmt.expression, source, fileName)
            }
            is ReturnStatement -> {
                stmt.expression?.let { checkUnusedInExpr(it, source, fileName) }
            }
            is Block -> checkUnusedInStatements(
                stmt.statements, source, fileName, isTopLevel = false,
            )
            is IfStatement -> {
                checkUnusedInExpr(stmt.expression, source, fileName)
                checkUnusedInNestedScopes(stmt.thenStatement, source, fileName)
                stmt.elseStatement?.let { checkUnusedInNestedScopes(it, source, fileName) }
            }
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> {
                        // Check for unused variables declared in the for-initializer
                        checkForStatementVariable(init, stmt, source, fileName)
                        for (decl in init.declarations) {
                            decl.initializer?.let { checkUnusedInExpr(it, source, fileName) }
                        }
                    }
                    is Expression -> checkUnusedInExpr(init, source, fileName)
                    else -> {}
                }
                checkUnusedInNestedScopes(stmt.statement, source, fileName)
            }
            is ForInStatement -> {
                checkForLoopVariable(stmt.initializer, stmt.statement, source, fileName)
                checkUnusedInNestedScopes(stmt.statement, source, fileName)
            }
            is ForOfStatement -> {
                checkForLoopVariable(stmt.initializer, stmt.statement, source, fileName)
                checkUnusedInNestedScopes(stmt.statement, source, fileName)
            }
            is WhileStatement -> checkUnusedInNestedScopes(stmt.statement, source, fileName)
            is DoStatement -> checkUnusedInNestedScopes(stmt.statement, source, fileName)
            is SwitchStatement -> {
                checkUnusedInExpr(stmt.expression, source, fileName)
                for (clause in stmt.caseBlock) {
                    val clauseStmts = when (clause) {
                        is CaseClause -> clause.statements
                        is DefaultClause -> clause.statements
                        else -> emptyList()
                    }
                    // Check unused declarations within each case/default clause
                    checkUnusedInStatements(clauseStmts, source, fileName, isTopLevel = false)
                    clauseStmts.forEach { checkUnusedInNestedScopes(it, source, fileName) }
                }
            }
            is TryStatement -> {
                stmt.tryBlock.statements.forEach {
                    checkUnusedInNestedScopes(it, source, fileName)
                }
                stmt.catchClause?.block?.statements?.forEach {
                    checkUnusedInNestedScopes(it, source, fileName)
                }
                stmt.finallyBlock?.statements?.forEach {
                    checkUnusedInNestedScopes(it, source, fileName)
                }
            }
            is LabeledStatement -> checkUnusedInNestedScopes(stmt.statement, source, fileName)
            else -> {}
        }
    }

    /**
     * Check for unused declarations inside expression-level function-likes
     * (function expressions, arrow functions, class expressions).
     */
    private fun checkUnusedInExpr(expr: Expression, source: String, fileName: String) {
        when (expr) {
            is FunctionExpression -> {
                checkUnusedInFunctionLike(
                    expr.body.statements, expr.parameters, source, fileName,
                    typeParameters = expr.typeParameters,
                    returnType = expr.type,
                )
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> checkUnusedInFunctionLike(
                        body.statements, expr.parameters, source, fileName,
                        typeParameters = expr.typeParameters,
                        returnType = expr.type,
                    )
                    is Expression -> {
                        // Arrow with expression body — still check parameters
                        if (options.noUnusedParameters) {
                            val scope = UnusedScope()
                            for (param in expr.parameters) {
                                val name = param.name
                                if (name is Identifier && !name.text.startsWith("_")) {
                                    scope.declarations.add(UnusedDecl(
                                        name = name.text,
                                        nameNode = name,
                                        declNode = param,
                                        isExported = false,
                                        isParameter = true,
                                        isTypeOnly = false,
                                    ))
                                }
                            }
                            collectRefsFromExpr(body, scope)
                            for (decl in scope.declarations) {
                                if (decl.name in scope.referencedNames) continue
                                val start = decl.nameNode.pos
                                val length = decl.name.length
                                val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                                checker.diagnostics.add(Diagnostic(
                                    message = "'${decl.name}' is declared but its value is never read.",
                                    category = DiagnosticCategory.Error,
                                    code = 6133,
                                    fileName = fileName,
                                    line = line,
                                    character = character,
                                    start = start,
                                    length = length,
                                ))
                            }
                        }
                        checkUnusedInExpr(body, source, fileName)
                    }
                    else -> {}
                }
            }
            is ClassExpression -> {
                for (member in expr.members) {
                    checkUnusedInClassElement(member, source, fileName)
                }
            }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is MethodDeclaration -> {
                            prop.body?.let { body ->
                                checkUnusedInFunctionLike(
                                    body.statements, prop.parameters, source, fileName,
                                    typeParameters = prop.typeParameters,
                                    returnType = prop.type,
                                )
                            }
                        }
                        is GetAccessor -> {
                            prop.body?.let { body ->
                                checkUnusedInFunctionLike(
                                    body.statements, prop.parameters, source, fileName,
                                )
                            }
                        }
                        is SetAccessor -> {
                            prop.body?.let { body ->
                                checkUnusedInFunctionLike(
                                    body.statements, prop.parameters, source, fileName,
                                )
                            }
                        }
                        is PropertyAssignment -> {
                            checkUnusedInExpr(prop.initializer, source, fileName)
                        }
                        is SpreadAssignment -> {
                            checkUnusedInExpr(prop.expression, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is ParenthesizedExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is BinaryExpression -> {
                var current: Expression = expr
                while (current is BinaryExpression) {
                    checkUnusedInExpr(current.right, source, fileName)
                    current = current.left
                }
                checkUnusedInExpr(current, source, fileName)
            }
            is ConditionalExpression -> {
                checkUnusedInExpr(expr.condition, source, fileName)
                checkUnusedInExpr(expr.whenTrue, source, fileName)
                checkUnusedInExpr(expr.whenFalse, source, fileName)
            }
            is CallExpression -> {
                checkUnusedInExpr(expr.expression, source, fileName)
                expr.arguments.forEach { checkUnusedInExpr(it, source, fileName) }
            }
            is NewExpression -> {
                checkUnusedInExpr(expr.expression, source, fileName)
                expr.arguments?.forEach { checkUnusedInExpr(it, source, fileName) }
            }
            is ArrayLiteralExpression -> {
                expr.elements.forEach { checkUnusedInExpr(it, source, fileName) }
            }
            is AsExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is NonNullExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is SatisfiesExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is TypeAssertionExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is DeleteExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is VoidExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is TypeOfExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is CommaListExpression -> expr.elements.forEach { checkUnusedInExpr(it, source, fileName) }
            is PropertyAccessExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is ElementAccessExpression -> {
                checkUnusedInExpr(expr.expression, source, fileName)
                checkUnusedInExpr(expr.argumentExpression, source, fileName)
            }
            is TemplateExpression -> {
                expr.templateSpans.forEach { checkUnusedInExpr(it.expression, source, fileName) }
            }
            is TaggedTemplateExpression -> {
                checkUnusedInExpr(expr.tag, source, fileName)
                (expr.template as? TemplateExpression)?.templateSpans?.forEach {
                    checkUnusedInExpr(it.expression, source, fileName)
                }
            }
            is AwaitExpression -> checkUnusedInExpr(expr.expression, source, fileName)
            is YieldExpression -> expr.expression?.let { checkUnusedInExpr(it, source, fileName) }
            is SpreadElement -> checkUnusedInExpr(expr.expression, source, fileName)
            is PrefixUnaryExpression -> checkUnusedInExpr(expr.operand, source, fileName)
            is PostfixUnaryExpression -> checkUnusedInExpr(expr.operand, source, fileName)
            else -> {} // Literals, identifiers, etc. — no nested function-likes
        }
    }

    /**
     * Check for unused for-in/for-of loop variables.
     */
    /**
     * Check for unused variables declared in a C-style for-loop initializer:
     * `for(var i = 0; condition; increment) { body }`
     * Collects references from condition, incrementor, and body.
     */
    private fun checkForStatementVariable(
        declList: VariableDeclarationList,
        forStmt: ForStatement,
        source: String,
        fileName: String,
    ) {
        if (!options.noUnusedLocals) return
        val scope = UnusedScope()
        for (decl in declList.declarations) {
            collectVarDeclNames(decl.name, decl, isExported = false, scope)
        }
        // Collect references from condition, incrementor, and body
        forStmt.condition?.let { collectRefsFromExpr(it, scope) }
        forStmt.incrementor?.let { collectRefsFromExpr(it, scope) }
        collectUnusedReferences(forStmt.statement, scope)
        reportUnusedLoopBindings(scope, source, fileName)
    }

    private fun checkForLoopVariable(
        initializer: Node?,
        body: Statement,
        source: String,
        fileName: String,
    ) {
        if (!options.noUnusedLocals) return
        val declList = initializer as? VariableDeclarationList ?: return
        val scope = UnusedScope()
        for (decl in declList.declarations) {
            collectVarDeclNames(decl.name, decl, isExported = false, scope)
        }
        // Collect references from the body
        collectUnusedReferences(body, scope)
        reportUnusedLoopBindings(scope, source, fileName)
    }

    private fun checkUnusedInClassElement(
        element: ClassElement,
        source: String,
        fileName: String,
    ) {
        when (element) {
            is MethodDeclaration -> {
                element.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, element.parameters, source, fileName,
                        typeParameters = element.typeParameters,
                        returnType = element.type,
                    )
                }
            }
            is Constructor -> {
                element.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, element.parameters, source, fileName,
                    )
                }
            }
            is GetAccessor -> {
                element.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, element.parameters, source, fileName,
                    )
                }
            }
            is SetAccessor -> {
                element.body?.let { body ->
                    checkUnusedInFunctionLike(
                        body.statements, element.parameters, source, fileName,
                    )
                }
            }
            is ClassStaticBlockDeclaration -> {
                checkUnusedInStatements(
                    element.body.statements, source, fileName, isTopLevel = false,
                )
            }
            is PropertyDeclaration -> {
                element.initializer?.let { checkUnusedInExpr(it, source, fileName) }
            }
            else -> {}
        }
    }

    /**
     * Check for unused type parameters on a class declaration.
     */
    /**
     * Check for unused private class members (TS6133).
     * Private properties and methods that are never accessed within the class are unused.
     */
    private fun checkUnusedPrivateMembers(
        members: List<ClassElement>,
        source: String,
        fileName: String,
    ) {
        if (!options.noUnusedLocals) return

        // Collect private members
        // `name` is the match key (used to detect references); `display` is the
        // text shown in the TS6133 message and used for the squiggle length. For
        // a computed-name member `private [x]: T` the match key is the bracket
        // identifier `x` and the display is `[x]`.
        data class PrivateMember(val name: String, val display: String, val nameNode: Node)
        val privateMembers = mutableListOf<PrivateMember>()
        val getterSetterNames = mutableSetOf<String>() // track getter/setter pairs
        // Match keys of computed-name private members (e.g. `x` for `private [x]: T`).
        // Used to recognize `this[x]` element-access reads as uses of `[x]` while
        // excluding `this[x] = 0` write-only assignments. Empty for the common case
        // (no computed members) so reference collection is unchanged there.
        val computedKeys = mutableSetOf<String>()

        for (member in members) {
            val isPrivate = when (member) {
                is PropertyDeclaration -> ModifierFlag.Private in member.modifiers
                is MethodDeclaration -> ModifierFlag.Private in member.modifiers
                is GetAccessor -> ModifierFlag.Private in member.modifiers
                is SetAccessor -> ModifierFlag.Private in member.modifiers
                else -> false
            }
            if (!isPrivate) continue

            val memberName: Node? = when (member) {
                is PropertyDeclaration -> member.name
                is MethodDeclaration -> member.name
                is GetAccessor -> member.name
                is SetAccessor -> member.name
                else -> null
            }
            // Identifier-named member → match key = display = the identifier text.
            // Computed-name member `[x]` → match key = `x`, display = `[x]`.
            val (name, display, nameNode) = when (memberName) {
                is Identifier -> Triple(memberName.text, memberName.text, memberName as Node)
                is ComputedPropertyName -> {
                    val inner = memberName.expression
                    if (inner is Identifier) {
                        computedKeys.add(inner.text)
                        Triple(inner.text, "[${inner.text}]", memberName as Node)
                    } else continue
                }
                else -> continue
            }

            // Track getter/setter pairs — don't duplicate
            if (member is GetAccessor || member is SetAccessor) {
                if (name in getterSetterNames) continue
                getterSetterNames.add(name)
            }

            privateMembers.add(PrivateMember(name, display, nameNode))
        }

        if (privateMembers.isEmpty()) return

        // Expose computed-name match keys to the reference collector for this class
        // (enables `this[x]` read recognition + `this[x] = 0` write exclusion).
        unusedComputedKeys = computedKeys

        // Collect property access names per member (for self-reference detection)
        data class MemberRefs(val memberName: String?, val refs: MutableSet<String>)
        val refsPerMember = mutableListOf<MemberRefs>()
        for (member in members) {
            val memberName = when (member) {
                is MethodDeclaration -> (member.name as? Identifier)?.text
                is GetAccessor -> (member.name as? Identifier)?.text
                is SetAccessor -> (member.name as? Identifier)?.text
                is Constructor -> null
                is PropertyDeclaration -> (member.name as? Identifier)?.text
                else -> null
            }
            val refs = mutableSetOf<String>()
            // Build param name → string literal values map for this[param] resolution
            val paramLiterals = buildParamLiteralMap(member)
            when (member) {
                is MethodDeclaration -> member.body?.let { collectPropertyAccessNames(it, refs, paramLiterals) }
                is Constructor -> member.body?.let { collectPropertyAccessNames(it, refs, paramLiterals) }
                is GetAccessor -> member.body?.let { collectPropertyAccessNames(it, refs, paramLiterals) }
                is SetAccessor -> member.body?.let { collectPropertyAccessNames(it, refs, paramLiterals) }
                is PropertyDeclaration -> member.initializer?.let { collectPropertyAccessNamesInExpr(it, refs) }
                else -> {}
            }
            refsPerMember.add(MemberRefs(memberName, refs))
        }

        // Report unused private members
        // A member is unused if it's only accessed from its own body (self-reference)
        for (pm in privateMembers) {
            val isExternallyAccessed = refsPerMember.any { mr ->
                mr.memberName != pm.name && pm.name in mr.refs
            }
            if (isExternallyAccessed) continue
            val start = pm.nameNode.pos
            val length = pm.display.length
            val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
            checker.diagnostics.add(Diagnostic(
                message = "'${pm.display}' is declared but its value is never read.",
                category = DiagnosticCategory.Error,
                code = 6133,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
    }

    private fun collectPropertyAccessNames(block: Block, names: MutableSet<String>, paramLiterals: Map<String, Set<String>> = emptyMap()) {
        for (stmt in block.statements) {
            collectPropertyAccessNamesInStmt(stmt, names, paramLiterals)
        }
    }

    private fun collectPropertyAccessNamesInStmt(stmt: Statement, names: MutableSet<String>, paramLiterals: Map<String, Set<String>> = emptyMap()) {
        when (stmt) {
            is ExpressionStatement -> collectPropertyAccessNamesInExpr(stmt.expression, names, paramLiterals)
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
                }
            }
            is ReturnStatement -> stmt.expression?.let { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
            is IfStatement -> {
                collectPropertyAccessNamesInExpr(stmt.expression, names, paramLiterals)
                collectPropertyAccessNamesInStmt(stmt.thenStatement, names, paramLiterals)
                stmt.elseStatement?.let { collectPropertyAccessNamesInStmt(it, names, paramLiterals) }
            }
            is Block -> stmt.statements.forEach { collectPropertyAccessNamesInStmt(it, names, paramLiterals) }
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is Expression -> collectPropertyAccessNamesInExpr(init, names, paramLiterals)
                    else -> {}
                }
                stmt.condition?.let { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
                stmt.incrementor?.let { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
                collectPropertyAccessNamesInStmt(stmt.statement, names, paramLiterals)
            }
            is WhileStatement -> {
                collectPropertyAccessNamesInExpr(stmt.expression, names, paramLiterals)
                collectPropertyAccessNamesInStmt(stmt.statement, names, paramLiterals)
            }
            is DoStatement -> {
                collectPropertyAccessNamesInStmt(stmt.statement, names, paramLiterals)
                collectPropertyAccessNamesInExpr(stmt.expression, names, paramLiterals)
            }
            is SwitchStatement -> {
                collectPropertyAccessNamesInExpr(stmt.expression, names, paramLiterals)
                for (clause in stmt.caseBlock) {
                    when (clause) {
                        is CaseClause -> {
                            collectPropertyAccessNamesInExpr(clause.expression, names, paramLiterals)
                            clause.statements.forEach { collectPropertyAccessNamesInStmt(it, names, paramLiterals) }
                        }
                        is DefaultClause -> clause.statements.forEach { collectPropertyAccessNamesInStmt(it, names, paramLiterals) }
                        else -> {}
                    }
                }
            }
            is TryStatement -> {
                stmt.tryBlock.statements.forEach { collectPropertyAccessNamesInStmt(it, names, paramLiterals) }
                stmt.catchClause?.block?.statements?.forEach { collectPropertyAccessNamesInStmt(it, names, paramLiterals) }
                stmt.finallyBlock?.statements?.forEach { collectPropertyAccessNamesInStmt(it, names, paramLiterals) }
            }
            is ThrowStatement -> stmt.expression?.let { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
            else -> {}
        }
    }

    private fun collectPropertyAccessNamesInExpr(expr: Expression, names: MutableSet<String>, paramLiterals: Map<String, Set<String>> = emptyMap()) {
        when (expr) {
            is PropertyAccessExpression -> {
                names.add(expr.name.text)
                collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            }
            is CallExpression -> {
                collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
                expr.arguments.forEach { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
            }
            is BinaryExpression -> {
                if (expr.operator == SyntaxKind.Equals) {
                    // Simple assignment: left side is write-only, don't count as read
                    // But still recurse into sub-expressions of the left side
                    // (e.g. for `this.a[this.b] = 0`, this.b IS a read)
                    val left = expr.left
                    val right = expr.right
                    if (left is ObjectLiteralExpression && right is Identifier && right.text == "this") {
                        // Destructuring from this: ({ x, y } = this) reads this.x, this.y
                        for (prop in left.properties) {
                            when (prop) {
                                is ShorthandPropertyAssignment -> names.add(prop.name.text)
                                is PropertyAssignment -> {
                                    val propName = prop.name
                                    if (propName is Identifier) names.add(propName.text)
                                }
                                else -> {}
                            }
                        }
                    } else if (left is PropertyAccessExpression) {
                        // this.x = ... → only recurse into the base (this), not the property name
                        collectPropertyAccessNamesInExpr(left.expression, names, paramLiterals)
                    } else if (unusedComputedKeys.isNotEmpty() && left is ElementAccessExpression) {
                        // this[x] = ... → write-only via a computed key; recurse into the base
                        // and into a NON-bare-key argument (e.g. `this[this.b] = 0` reads this.b),
                        // but do not count the write target `x` as a read.
                        collectPropertyAccessNamesInExpr(left.expression, names, paramLiterals)
                        val larg = left.argumentExpression
                        if (larg !is Identifier && larg !is StringLiteralNode) {
                            collectPropertyAccessNamesInExpr(larg, names, paramLiterals)
                        }
                    } else {
                        collectPropertyAccessNamesInExpr(left, names, paramLiterals)
                    }
                } else {
                    collectPropertyAccessNamesInExpr(expr.left, names, paramLiterals)
                }
                collectPropertyAccessNamesInExpr(expr.right, names, paramLiterals)
            }
            is PrefixUnaryExpression -> collectPropertyAccessNamesInExpr(expr.operand, names, paramLiterals)
            is PostfixUnaryExpression -> collectPropertyAccessNamesInExpr(expr.operand, names, paramLiterals)
            is ParenthesizedExpression -> collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            is ConditionalExpression -> {
                collectPropertyAccessNamesInExpr(expr.condition, names, paramLiterals)
                collectPropertyAccessNamesInExpr(expr.whenTrue, names, paramLiterals)
                collectPropertyAccessNamesInExpr(expr.whenFalse, names, paramLiterals)
            }
            is NewExpression -> {
                collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
                expr.arguments?.forEach { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
            }
            is ElementAccessExpression -> {
                collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
                collectPropertyAccessNamesInExpr(expr.argumentExpression, names, paramLiterals)
                // String literal element access like obj["name"] counts as accessing "name"
                val arg = expr.argumentExpression
                if (arg is StringLiteralNode) {
                    names.add(arg.text)
                } else if (arg is Identifier && paramLiterals.isNotEmpty()) {
                    // this[param] where param has string literal union type
                    paramLiterals[arg.text]?.let { names.addAll(it) }
                } else if (arg is Identifier && arg.text in unusedComputedKeys) {
                    // this[x] (read) where `x` is the bracket identifier of a computed-name
                    // private member `private [x]: T`. Counts as a use of `[x]`. Writes
                    // (`this[x] = 0`) are excluded by the assignment-LHS handling above.
                    val recv = expr.expression
                    if (recv is Identifier && recv.text == "this") names.add(arg.text)
                }
            }
            is TemplateExpression -> {
                expr.templateSpans.forEach { collectPropertyAccessNamesInExpr(it.expression, names, paramLiterals) }
            }
            is ArrowFunction -> {
                when (val body = expr.body) {
                    is Block -> body.statements.forEach { collectPropertyAccessNamesInStmt(it, names, paramLiterals) }
                    is Expression -> collectPropertyAccessNamesInExpr(body, names, paramLiterals)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                expr.body.statements.forEach { collectPropertyAccessNamesInStmt(it, names, paramLiterals) }
            }
            is ArrayLiteralExpression -> expr.elements.forEach { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
            is ObjectLiteralExpression -> {
                for (prop in expr.properties) {
                    when (prop) {
                        is PropertyAssignment -> collectPropertyAccessNamesInExpr(prop.initializer, names, paramLiterals)
                        is ShorthandPropertyAssignment -> {}
                        is SpreadAssignment -> collectPropertyAccessNamesInExpr(prop.expression, names, paramLiterals)
                        else -> {}
                    }
                }
            }
            is AsExpression -> collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            is NonNullExpression -> collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            is TypeAssertionExpression -> collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            is SatisfiesExpression -> collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            is AwaitExpression -> collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            is YieldExpression -> expr.expression?.let { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
            is SpreadElement -> collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            is DeleteExpression -> collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            is VoidExpression -> collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            is TypeOfExpression -> collectPropertyAccessNamesInExpr(expr.expression, names, paramLiterals)
            is CommaListExpression -> expr.elements.forEach { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
            is TaggedTemplateExpression -> {
                collectPropertyAccessNamesInExpr(expr.tag, names, paramLiterals)
                val t = expr.template
                if (t is TemplateExpression) {
                    for (span in t.templateSpans) collectPropertyAccessNamesInExpr(span.expression, names, paramLiterals)
                }
            }
            is ClassExpression -> for (m in expr.members) {
                when (m) {
                    is MethodDeclaration -> m.body?.let { for (s in it.statements) collectPropertyAccessNamesInStmt(s, names, paramLiterals) }
                    is Constructor -> m.body?.let { for (s in it.statements) collectPropertyAccessNamesInStmt(s, names, paramLiterals) }
                    is GetAccessor -> m.body?.let { for (s in it.statements) collectPropertyAccessNamesInStmt(s, names, paramLiterals) }
                    is SetAccessor -> m.body?.let { for (s in it.statements) collectPropertyAccessNamesInStmt(s, names, paramLiterals) }
                    is PropertyDeclaration -> m.initializer?.let { collectPropertyAccessNamesInExpr(it, names, paramLiterals) }
                    else -> {}
                }
            }
            else -> {}
        }
    }

    /**
     * Build a map of parameter name → string literal values from type annotations.
     * Used to resolve `this[param]` as reads of the named properties.
     */
    private fun buildParamLiteralMap(member: ClassElement): Map<String, Set<String>> {
        val params = when (member) {
            is MethodDeclaration -> member.parameters
            is Constructor -> member.parameters
            is GetAccessor -> member.parameters
            is SetAccessor -> member.parameters
            else -> return emptyMap()
        }
        val result = mutableMapOf<String, Set<String>>()
        for (param in params) {
            val paramName = (param.name as? Identifier)?.text ?: continue
            val type = param.type ?: continue
            val literals = extractStringLiteralsFromType(type)
            if (literals.isNotEmpty()) result[paramName] = literals
        }
        return result
    }

    private fun extractStringLiteralsFromType(type: TypeNode): Set<String> {
        return when (type) {
            is LiteralType -> {
                val lit = type.literal
                if (lit is StringLiteralNode) setOf(lit.text) else emptySet()
            }
            is UnionType -> type.types.flatMapTo(mutableSetOf()) { extractStringLiteralsFromType(it) }
            is ParenthesizedType -> extractStringLiteralsFromType(type.type)
            else -> emptySet()
        }
    }

    private fun checkUnusedClassTypeParams(
        cls: ClassDeclaration,
        source: String,
        fileName: String,
        siblingStatements: List<Statement>? = null,
    ) {
        val typeParams = cls.typeParameters
        // Class type params are only checked with noUnusedParameters (not noUnusedLocals alone)
        if (typeParams.isNullOrEmpty() || !options.noUnusedParameters) return
        // Skip if another declaration merges with this class (interface/namespace) — same file
        val className = cls.name?.text
        if (className != null && siblingStatements != null) {
            val hasMerge = siblingStatements.any { stmt ->
                stmt !== cls && when (stmt) {
                    is InterfaceDeclaration -> stmt.name.text == className
                    is ModuleDeclaration -> {
                        val n = stmt.name
                        n is Identifier && n.text == className
                    }
                    else -> false
                }
            }
            if (hasMerge) return
        }
        // Skip if another declaration merges with this class across files (via globals)
        if (className != null) {
            val globalSymbol = checker.globals[className]
            if (globalSymbol != null && globalSymbol.declarations.any { it.kind == SyntaxKind.InterfaceDeclaration || it.kind == SyntaxKind.ModuleDeclaration }) return
        }

        val tpScope = UnusedScope()
        for (tp in typeParams) {
            if (!tp.name.text.startsWith("_")) {
                tpScope.declarations.add(UnusedDecl(
                    name = tp.name.text,
                    nameNode = tp.name,
                    declNode = tp,
                    isExported = false,
                    isParameter = false,
                    isTypeOnly = true,
                ))
            }
        }

        // (P18.271) a reference in any type parameter's constraint or default (its own
        // included) is a use — tsgo resolves it like any other.
        collectTypeParamListRefs(typeParams, tpScope)
        // Collect type refs from: heritage clauses, member types, constructor params
        cls.heritageClauses?.forEach { clause ->
            for (type in clause.types) {
                type.typeArguments?.forEach { collectTypeRefs(it, tpScope) }
                // The extends expression itself might reference a type param
                if (type.expression is Identifier) {
                    tpScope.referencedNames.add((type.expression).text)
                }
            }
        }
        for (member in cls.members) {
            collectTypeRefsFromJSDoc(member.leadingComments, tpScope)
            when (member) {
                is PropertyDeclaration -> member.type?.let { collectTypeRefs(it, tpScope) }
                is MethodDeclaration -> {
                    collectTypeParamListRefs(member.typeParameters, tpScope)
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                    member.type?.let { collectTypeRefs(it, tpScope) }
                    member.body?.let { body ->
                        for (stmt in body.statements) collectTypeRefsInStatement(stmt, tpScope)
                    }
                }
                is Constructor -> {
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                    member.body?.let { body ->
                        for (stmt in body.statements) collectTypeRefsInStatement(stmt, tpScope)
                    }
                }
                is GetAccessor -> member.type?.let { collectTypeRefs(it, tpScope) }
                is SetAccessor -> {
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                }
                is IndexSignature -> member.type?.let { collectTypeRefs(it, tpScope) }
                else -> {}
            }
        }

        reportUnusedTypeParams(tpScope, typeParams, source, fileName)
    }

    /** Check if a class declaration uses any of the named type parameters in its members. */
    private fun classUsesTypeParams(cls: ClassDeclaration, typeParamNames: Set<String>): Boolean {
        val tpScope = UnusedScope()
        // Add all type param names as "declarations" — we just want to see which ones are referenced
        for (name in typeParamNames) {
            tpScope.declarations.add(UnusedDecl(name = name, nameNode = Identifier(text = name), declNode = Identifier(text = name), isExported = false, isParameter = false, isTypeOnly = true))
        }
        for (member in cls.members) {
            when (member) {
                is PropertyDeclaration -> member.type?.let { collectTypeRefs(it, tpScope) }
                is MethodDeclaration -> {
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                    member.type?.let { collectTypeRefs(it, tpScope) }
                }
                is Constructor -> {
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                }
                is GetAccessor -> member.type?.let { collectTypeRefs(it, tpScope) }
                is SetAccessor -> {
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                }
                else -> {}
            }
        }
        return tpScope.referencedNames.any { it in typeParamNames }
    }

    private fun checkUnusedInterfaceTypeParams(
        iface: InterfaceDeclaration,
        source: String,
        fileName: String,
        siblingStatements: List<Statement>? = null,
    ) {
        val typeParams = iface.typeParameters
        if (typeParams.isNullOrEmpty() || !(options.noUnusedLocals || options.noUnusedParameters)) return
        val ifaceName = iface.name.text
        // Same-file merge check: if class/interface/namespace with same name in same file, skip
        if (siblingStatements != null) {
            val hasMerge = siblingStatements.any { stmt ->
                stmt !== iface && when (stmt) {
                    is InterfaceDeclaration -> stmt.name.text == ifaceName
                    is ClassDeclaration -> stmt.name?.text == ifaceName
                    is ModuleDeclaration -> {
                        val n = stmt.name
                        n is Identifier && n.text == ifaceName
                    }
                    else -> false
                }
            }
            if (hasMerge) return
        }
        // Cross-file merge check
        val globalSymbol = checker.globals[ifaceName]
        if (globalSymbol != null) {
            // Skip if multiple interface/namespace declarations (normal interface merging)
            val nonClassDecls = globalSymbol.declarations.count {
                it.kind == SyntaxKind.InterfaceDeclaration || it.kind == SyntaxKind.ModuleDeclaration
            }
            if (nonClassDecls > 1) return
            // For class+interface cross-file merge: skip if the class uses any of the type params
            val typeParamNames = typeParams.map { it.name.text }.toSet()
            val classDecls = globalSymbol.declarations.filterIsInstance<ClassDeclaration>()
            for (classDecl in classDecls) {
                if (classUsesTypeParams(classDecl, typeParamNames)) return
            }
        }

        val tpScope = UnusedScope()
        for (tp in typeParams) {
            if (!tp.name.text.startsWith("_")) {
                tpScope.declarations.add(UnusedDecl(
                    name = tp.name.text, nameNode = tp.name, declNode = tp,
                    isExported = false, isParameter = false, isTypeOnly = true,
                ))
            }
        }

        collectTypeParamListRefs(typeParams, tpScope)
        // Collect refs from heritage clauses and members
        iface.heritageClauses?.forEach { clause ->
            for (type in clause.types) {
                type.typeArguments?.forEach { collectTypeRefs(it, tpScope) }
            }
        }
        for (member in iface.members) {
            when (member) {
                is PropertyDeclaration -> member.type?.let { collectTypeRefs(it, tpScope) }
                is MethodDeclaration -> {
                    collectTypeParamListRefs(member.typeParameters, tpScope)
                    member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, tpScope) } }
                    member.type?.let { collectTypeRefs(it, tpScope) }
                }
                is IndexSignature -> member.type?.let { collectTypeRefs(it, tpScope) }
                else -> {}
            }
        }

        reportUnusedTypeParams(tpScope, typeParams, source, fileName)
    }

    private fun checkUnusedTypeAliasTypeParams(
        alias: TypeAliasDeclaration,
        source: String,
        fileName: String,
    ) {
        val typeParams = alias.typeParameters
        if (typeParams.isNullOrEmpty() || !(options.noUnusedLocals || options.noUnusedParameters)) return

        val tpScope = UnusedScope()
        for (tp in typeParams) {
            if (!tp.name.text.startsWith("_")) {
                tpScope.declarations.add(UnusedDecl(
                    name = tp.name.text, nameNode = tp.name, declNode = tp,
                    isExported = false, isParameter = false, isTypeOnly = true,
                ))
            }
        }

        collectTypeParamListRefs(typeParams, tpScope)
        collectTypeRefs(alias.type, tpScope)
        reportUnusedTypeParams(tpScope, typeParams, source, fileName)
    }

    /**
     * The source span of a type-parameter NODE, which is what TypeScript 7 squiggles for an
     * unused type parameter (`in out T`, `T extends string`, `T = number` — modifiers,
     * constraint and default included).
     *
     * [TypeParameter.end] cannot be used directly: this parser records a node's `end` as the
     * scanner position AFTER the following token (CLAUDE.md's `Node.end` rule), so it
     * overshoots by the `,` or `>` that closes the parameter. Exactly one such token is
     * trimmed — `>>` is scanned as one token when a constraint itself ends in `>`, and
     * removing a single `>` is right in both spellings.
     */
    private fun typeParamNodeSpan(source: String, tp: TypeParameter): Pair<Int, Int> {
        val start = tp.pos.coerceIn(0, source.length)
        val nameEnd = (tp.name.pos + tp.name.text.length).coerceIn(start, source.length)
        var end = tp.end.coerceIn(start, source.length)
        while (end > start && source[end - 1].isWhitespace()) end--
        if (end > start && (source[end - 1] == ',' || source[end - 1] == '>')) end--
        while (end > start && source[end - 1].isWhitespace()) end--
        if (end < nameEnd) end = nameEnd
        return start to end
    }

    /**
     * The whole type-parameter list's span, TypeScript 7's anchor for TS6205 — tsgo
     * `rangeOfTypeParameters` (`utilities.go:1531`):
     * `[list.Pos() - 1, skipTrivia(text, list.End()) + 1)`.
     *
     * For a written `<…>` list that is exactly the angle brackets, which is why the
     * first branch walks back to `<` and forward to `>`.
     *
     * (LEGACY.0b) For a JSDoc `@template` list the same formula applies to the REPARSED
     * list, whose `Pos` is the first `@template` tag's `@` and whose `End` is the last
     * declared name — so the span starts one character BEFORE the `@` and ends one
     * character past the first non-whitespace after the last name, which for a
     * conventional JSDoc block is the ` *` of the closing line. It spans several source
     * lines and several tags: tsgo aggregates over the DECLARATION's whole list, never
     * per tag (measured — `@template T,V` used plus `@template X,Y` unused is two
     * per-parameter TS6196 rows, not one TS6205).
     */
    private fun typeParameterListSpan(source: String, typeParams: List<TypeParameter>): Pair<Int, Int>? {
        val first = typeParams.firstOrNull() ?: return null
        if (first.fromJSDoc) {
            val tagPos = typeParams.minOf { if (it.jsDocTagPos >= 0) it.jsDocTagPos else Int.MAX_VALUE }
            if (tagPos == Int.MAX_VALUE || tagPos <= 0) return null
            var after = typeParams.maxOf { it.end }.coerceIn(0, source.length)
            while (after < source.length && (source[after] == ' ' || source[after] == '\t' ||
                    source[after] == '\r' || source[after] == '\n')) after++
            val close = (after + 1).coerceAtMost(source.length)
            val open = tagPos - 1
            return if (close > open) open to close else null
        }
        var open = first.pos.coerceIn(0, source.length)
        while (open > 0 && source[open - 1] != '<') open--
        if (open == 0) return null
        open--
        var close = typeParamNodeSpan(source, typeParams.last()).second
        while (close < source.length && source[close] != '>') close++
        if (close >= source.length) return null
        return open to close + 1
    }

    private fun reportUnusedTypeParams(
        scope: UnusedScope,
        typeParams: List<TypeParameter>,
        source: String,
        fileName: String,
    ) {
        // (LEGACY.0b) TypeScript 7's `checkUnusedTypeParameters`: when the declaration has
        // MORE THAN ONE type parameter and every one of them is unreferenced, ONE TS6205
        // "All type parameters are unused." covers the whole `<…>` list and no per-parameter
        // row is emitted. A `_`-prefixed parameter counts as referenced (tsgo
        // `isUnreferencedTypeParameter`), which is why the test is membership of
        // [scope]'s declarations — the collectors never record one.
        val reportable = typeParams.filter { tp ->
            scope.declarations.any { it.declNode === tp } && tp.name.text !in scope.referencedNames
        }
        if (typeParams.size > 1 && reportable.size == typeParams.size) {
            val listSpan = typeParameterListSpan(source, typeParams)
            if (listSpan != null) {
                val (line, character) = checker.getLineAndCharacterOfPosition(source, listSpan.first)
                checker.diagnostics.add(Diagnostic(
                    message = "All type parameters are unused.",
                    category = DiagnosticCategory.Error,
                    code = 6205,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = listSpan.first,
                    length = (listSpan.second - listSpan.first).coerceAtLeast(1),
                ))
                return
            }
        }
        // (LEGACY.0b) There is no PER-TAG aggregation: tsgo's `checkUnusedTypeParameters`
        // aggregates over the declaration's whole type-parameter list and otherwise emits
        // one row per unused parameter, so a `@template X,Y` whose siblings on another tag
        // are used is two TS6196 rows (measured against tsgo 7.0.2).
        for (decl in scope.declarations) {
            if (decl.name in scope.referencedNames) continue
            val tp = decl.declNode as TypeParameter
            // (LEGACY.0b) TypeScript 7 reports EVERY unused type parameter on the type
            // parameter NODE itself (tsgo `checkUnusedTypeParameters`:
            // `NewDiagnosticForNode(typeParameter, …)`) — never on the enclosing `<…>`
            // list and never on the whole `@template` tag, both of which tsc 6 used.
            // The node span includes any `const`/`in`/`out` modifier and any constraint
            // or default, which is why it is not simply the name.
            val (start, nodeEnd) = typeParamNodeSpan(source, tp)
            val length = (nodeEnd - start).coerceAtLeast(1)
            val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
            checker.diagnostics.add(Diagnostic(
                message = "'${decl.name}' is declared but never used.",
                category = DiagnosticCategory.Error,
                code = 6196,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
    }

    /**
     * Walk JSDoc `@type {T}` (and similar) comments and add identifiers
     * found inside the brace expression to the unused-scope's referencedNames set.
     * Used to track type-param usage through JSDoc body annotations in JS-like files
     * (e.g. an `@type {T}` comment on `this.p;` inside a generic class constructor).
     */
    private fun collectTypeRefsFromJSDoc(comments: List<Comment>?, scope: UnusedScope) {
        if (comments.isNullOrEmpty()) return
        for (comment in comments) {
            if (comment.kind != SyntaxKind.MultiLineComment) continue
            val ct = comment.text
            if (!ct.startsWith("/**")) continue
            var idx = 0
            while (idx < ct.length) {
                val tagIdx = ct.indexOf("@type", idx)
                if (tagIdx < 0) break
                val afterTag = if (tagIdx + 5 < ct.length) ct[tagIdx + 5] else ' '
                if (afterTag.isLetterOrDigit() || afterTag == '_') {
                    idx = tagIdx + 5
                    continue
                }
                var i = tagIdx + 5
                while (i < ct.length && (ct[i] == ' ' || ct[i] == '\t')) i++
                if (i >= ct.length || ct[i] != '{') {
                    idx = i
                    continue
                }
                i++
                var depth = 1
                while (i < ct.length && depth > 0) {
                    val c = ct[i]
                    when {
                        c == '{' -> { depth++; i++ }
                        c == '}' -> { depth--; if (depth > 0) i++ }
                        c.isLetter() || c == '_' || c == '$' -> {
                            val nameStart = i
                            while (i < ct.length && (ct[i].isLetterOrDigit() || ct[i] == '_' || ct[i] == '$')) i++
                            scope.referencedNames.add(ct.substring(nameStart, i))
                        }
                        else -> i++
                    }
                }
                idx = if (i < ct.length) i + 1 else ct.length
            }
        }
    }

    private fun checkUnusedInFunctionLike(
        bodyStatements: List<Statement>,
        parameters: List<Parameter>,
        source: String,
        fileName: String,
        typeParameters: List<TypeParameter>? = null,
        returnType: TypeNode? = null,
    ) {
        // Check parameters (if noUnusedParameters is enabled)
        if (options.noUnusedParameters) {
            val scope = UnusedScope()
            // Collect parameter declarations
            for (param in parameters) {
                if (param.isCommentPlaceholder) continue
                val name = param.name
                if (name is Identifier) {
                    // Skip if underscore-prefixed or if it has access modifiers (constructor params).
                    // (P18.271) a `this` parameter is never reported (tsgo `IsThisParameter`).
                    // (CHK.221) nor is any parameter PROPERTY — tsgo `isParameterPropertyDeclaration`
                    // covers `readonly` and `override` as well as the three accessibility modifiers.
                    if (!name.text.startsWith("_") && name.text != "this" &&
                        param.modifiers.none { checker.isParameterPropertyModifier(it) }) {
                        scope.declarations.add(UnusedDecl(
                            name = name.text,
                            nameNode = name,
                            declNode = param,
                            isExported = false,
                            isParameter = true,
                            isTypeOnly = false,
                        ))
                    }
                } else {
                    // Destructuring parameters: collect individual binding element names
                    collectDestructuringParamNames(name, param, scope)
                }
            }
            // Collect references from body
            for (stmt in bodyStatements) {
                collectUnusedReferences(stmt, scope)
            }
            // Also collect references from parameter defaults and types.
            // For VALUE param scope, type annotations only contribute `typeof X` refs
            // (value namespace) — bare `T` in type position is a TYPE namespace ref and
            // does NOT count as a use of a value param with the same name.
            for (param in parameters) {
                param.initializer?.let { collectRefsFromExpr(it, scope) }
                param.type?.let { collectTypeQueryValueRefs(it, scope) }
            }
            // Scan return type for typeof references
            returnType?.let { collectTypeQueryValueRefs(it, scope) }
            // Report unused parameters. (LEGACY.0b step 13) TS6198 grouping is tsgo's
            // `reportUnusedBindingElements`, recursive from the root pattern — see
            // [reportUnusedBindingPatterns]; a `_` leaf reaches this list only as an
            // object-pattern shorthand (see [collectDestructuringParamNames]).
            val unusedParams = scope.declarations.filter { it.name !in scope.referencedNames }
            val paramTs6198Suppressed = reportUnusedBindingPatterns(unusedParams, source, fileName)
            for (decl in unusedParams) {
                if (decl.nameNode.pos in paramTs6198Suppressed) continue
                val nameNode = decl.nameNode
                // (LEGACY.0b step 3) TypeScript 7 anchors on the element NAME for a
                // one-element destructuring parameter too — see the sibling comment in
                // `checkUnusedInStatements`.
                val start = nameNode.pos
                val length = if (decl.spanLength > 0) decl.spanLength else decl.name.length
                val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                checker.diagnostics.add(Diagnostic(
                    message = "'${decl.name}' is declared but its value is never read.",
                    category = DiagnosticCategory.Error,
                    code = 6133,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
        }

        // Check unused type parameters (TS6133)
        if (typeParameters != null && typeParameters.isNotEmpty() && (options.noUnusedLocals || options.noUnusedParameters)) {
            val tpScope = UnusedScope()
            for (tp in typeParameters) {
                if (!tp.name.text.startsWith("_")) {
                    tpScope.declarations.add(UnusedDecl(
                        name = tp.name.text,
                        nameNode = tp.name,
                        declNode = tp,
                        isExported = false,
                        isParameter = false,
                        isTypeOnly = true,
                    ))
                }
            }
            // Collect type references from: type param constraints, parameter types,
            // return type, and body statements (types in variable declarations etc.)
            for (tp in typeParameters) {
                tp.constraint?.let { collectTypeRefs(it, tpScope) }
                tp.default?.let { collectTypeRefs(it, tpScope) }
            }
            for (param in parameters) {
                param.type?.let { collectTypeRefs(it, tpScope) }
                // (P18.271) a type written in a parameter DEFAULT (`m = (i) => i as U`).
                param.initializer?.let { collectTypeRefsInExpr(it, tpScope) }
            }
            returnType?.let { collectTypeRefs(it, tpScope) }
            for (stmt in bodyStatements) {
                collectTypeRefsInStatement(stmt, tpScope)
            }
            reportUnusedTypeParams(tpScope, typeParameters, source, fileName)
        }

        // Check local declarations in the body
        checkUnusedInStatements(bodyStatements, source, fileName, isTopLevel = false)
    }

    /**
     * Check for unused type parameters defined within type annotations.
     * Handles: ConstructorType `new <T,U>(a:T)=>void`, FunctionType `<T,U>(a:T)=>void`,
     * and TypeLiteral members with ConstructSignature/CallSignature.
     */
    private fun checkUnusedTypeParamsInType(type: TypeNode, source: String, fileName: String) {
        if (!options.noUnusedLocals && !options.noUnusedParameters) return
        when (type) {
            is ConstructorType -> {
                checkTypeParamsInSignature(type.typeParameters, type.parameters, type.type, source, fileName)
            }
            is FunctionType -> {
                checkTypeParamsInSignature(type.typeParameters, type.parameters, type.type, source, fileName)
            }
            is TypeLiteral -> {
                for (member in type.members) {
                    when (member) {
                        is MethodDeclaration -> {
                            // Call signatures (name=""), construct signatures (name="new"), and methods
                            checkTypeParamsInSignature(member.typeParameters, member.parameters, member.type, source, fileName)
                        }
                        else -> {}
                    }
                }
            }
            is UnionType -> type.types.forEach { checkUnusedTypeParamsInType(it, source, fileName) }
            is IntersectionType -> type.types.forEach { checkUnusedTypeParamsInType(it, source, fileName) }
            is ParenthesizedType -> checkUnusedTypeParamsInType(type.type, source, fileName)
            else -> {}
        }
    }

    /** Check unused type params for a single signature (construct/call/method). */
    private fun checkTypeParamsInSignature(
        typeParameters: List<TypeParameter>?,
        parameters: List<Parameter>,
        returnType: TypeNode?,
        source: String,
        fileName: String,
    ) {
        if (typeParameters.isNullOrEmpty()) return
        val scope = UnusedScope()
        for (tp in typeParameters) {
            if (!tp.name.text.startsWith("_")) {
                scope.declarations.add(UnusedDecl(
                    name = tp.name.text,
                    nameNode = tp.name,
                    declNode = tp,
                    isExported = false,
                    isParameter = false,
                    isTypeOnly = true,
                ))
            }
        }
        // Collect refs from constraints, param types, return type
        for (tp in typeParameters) {
            tp.constraint?.let { collectTypeRefs(it, scope) }
            tp.default?.let { collectTypeRefs(it, scope) }
        }
        for (param in parameters) {
            param.type?.let { collectTypeRefs(it, scope) }
        }
        returnType?.let { collectTypeRefs(it, scope) }
        reportUnusedTypeParams(scope, typeParameters, source, fileName)
    }

    /** (P18.271) Every constraint and default of a type-parameter list, as type-parameter-scope
     *  references (tsgo counts `_V extends [T, U]`, `<E2 = E>` and a parameter's own
     *  `T extends Array<T>` as uses). */
    private fun collectTypeParamListRefs(tps: List<TypeParameter>?, scope: UnusedScope) {
        tps?.forEach { tp ->
            tp.constraint?.let { collectTypeRefs(it, scope) }
            tp.default?.let { collectTypeRefs(it, scope) }
        }
    }

    /** Collect type identifier references from a type node. */
    private fun collectTypeRefs(type: TypeNode, scope: UnusedScope) {
        when (type) {
            is TypeReference -> {
                val name = type.typeName
                if (name is Identifier) scope.referencedNames.add(name.text)
                type.typeArguments?.forEach { collectTypeRefs(it, scope) }
            }
            is KeywordTypeNode -> {} // any, number, string, etc. — no references
            is ArrayType -> collectTypeRefs(type.elementType, scope)
            is TupleType -> type.elements.forEach {
                collectTypeRefs(it, scope)
            }
            is UnionType -> type.types.forEach { collectTypeRefs(it, scope) }
            is IntersectionType -> type.types.forEach { collectTypeRefs(it, scope) }
            is ParenthesizedType -> collectTypeRefs(type.type, scope)
            is FunctionType -> {
                collectTypeParamListRefs(type.typeParameters, scope)
                type.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, scope) } }
                type.type.let { collectTypeRefs(it, scope) }
            }
            is ConstructorType -> {
                collectTypeParamListRefs(type.typeParameters, scope)
                type.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, scope) } }
                type.type.let { collectTypeRefs(it, scope) }
            }
            is TypeLiteral -> {
                for (member in type.members) {
                    when (member) {
                        is PropertyDeclaration -> member.type?.let { collectTypeRefs(it, scope) }
                        is MethodDeclaration -> {
                            collectTypeParamListRefs(member.typeParameters, scope)
                            member.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, scope) } }
                            member.type?.let { collectTypeRefs(it, scope) }
                        }
                        is IndexSignature -> member.type?.let { collectTypeRefs(it, scope) }
                        else -> {}
                    }
                }
            }
            is ConditionalType -> {
                collectTypeRefs(type.checkType, scope)
                collectTypeRefs(type.extendsType, scope)
                collectTypeRefs(type.trueType, scope)
                collectTypeRefs(type.falseType, scope)
            }
            is MappedType -> {
                type.type?.let { collectTypeRefs(it, scope) }
                type.nameType?.let { collectTypeRefs(it, scope) }
                type.typeParameter.constraint?.let { collectTypeRefs(it, scope) }
            }
            is TypeQuery -> {
                val name = type.exprName
                if (name is Identifier) scope.referencedNames.add(name.text)
            }
            is IndexedAccessType -> {
                collectTypeRefs(type.objectType, scope)
                collectTypeRefs(type.indexType, scope)
            }
            is TypeOperator -> collectTypeRefs(type.type, scope)
            is RestType -> collectTypeRefs(type.type, scope)
            is OptionalType -> collectTypeRefs(type.type, scope)
            is InferType -> {} // infer T — declares, doesn't reference
            is LiteralType -> {} // string/number literals
            is TemplateLiteralType -> {
                type.templateSpans.forEach { span ->
                    collectTypeRefs(span.type, scope)
                }
                // (P18.271) the spans are never parsed — read the raw slice.
                templateTypeReferenceNames(type.head.rawText ?: "").forEach { scope.referencedNames.add(it.name) }
            }
            is TypePredicate -> type.type?.let { collectTypeRefs(it, scope) }
            else -> {}
        }
    }

    /**
     * Collect ONLY `typeof X` references from within a TypeNode tree. Unlike [collectTypeRefs],
     * bare TypeReference identifiers are NOT added — they belong to the TYPE namespace and do
     * not count as value-param uses for TS6133 purposes. Needed for cases like
     * `function f<T>(T: T)` where the second `T` in type position references the type param,
     * not the value param of the same name.
     */
    private fun collectTypeQueryValueRefs(type: TypeNode, scope: UnusedScope) {
        when (type) {
            is TypeQuery -> {
                when (val name = type.exprName) {
                    is Identifier -> scope.referencedNames.add(name.text)
                    is QualifiedName -> {
                        var current: Node = name
                        while (current is QualifiedName) current = current.left
                        if (current is Identifier) scope.referencedNames.add(current.text)
                    }
                    else -> {}
                }
            }
            is ArrayType -> collectTypeQueryValueRefs(type.elementType, scope)
            is TupleType -> type.elements.forEach {
                collectTypeQueryValueRefs(it, scope)
            }
            is UnionType -> type.types.forEach { collectTypeQueryValueRefs(it, scope) }
            is IntersectionType -> type.types.forEach { collectTypeQueryValueRefs(it, scope) }
            is ParenthesizedType -> collectTypeQueryValueRefs(type.type, scope)
            is FunctionType -> {
                type.parameters.forEach { p -> p.type?.let { collectTypeQueryValueRefs(it, scope) } }
                type.type.let { collectTypeQueryValueRefs(it, scope) }
            }
            is ConstructorType -> {
                type.parameters.forEach { p -> p.type?.let { collectTypeQueryValueRefs(it, scope) } }
                type.type.let { collectTypeQueryValueRefs(it, scope) }
            }
            is TypeLiteral -> {
                for (member in type.members) {
                    when (member) {
                        is PropertyDeclaration -> member.type?.let { collectTypeQueryValueRefs(it, scope) }
                        is MethodDeclaration -> {
                            member.parameters.forEach { p -> p.type?.let { collectTypeQueryValueRefs(it, scope) } }
                            member.type?.let { collectTypeQueryValueRefs(it, scope) }
                        }
                        is IndexSignature -> member.type?.let { collectTypeQueryValueRefs(it, scope) }
                        else -> {}
                    }
                }
            }
            is ConditionalType -> {
                collectTypeQueryValueRefs(type.checkType, scope)
                collectTypeQueryValueRefs(type.extendsType, scope)
                collectTypeQueryValueRefs(type.trueType, scope)
                collectTypeQueryValueRefs(type.falseType, scope)
            }
            is MappedType -> {
                type.type?.let { collectTypeQueryValueRefs(it, scope) }
                type.nameType?.let { collectTypeQueryValueRefs(it, scope) }
                type.typeParameter.constraint?.let { collectTypeQueryValueRefs(it, scope) }
            }
            is IndexedAccessType -> {
                collectTypeQueryValueRefs(type.objectType, scope)
                collectTypeQueryValueRefs(type.indexType, scope)
            }
            is TypeOperator -> collectTypeQueryValueRefs(type.type, scope)
            is RestType -> collectTypeQueryValueRefs(type.type, scope)
            is OptionalType -> collectTypeQueryValueRefs(type.type, scope)
            is TypeReference -> {
                // Bare type reference is TYPE namespace, not value; skip.
                // But type arguments can still contain nested TypeQuery.
                type.typeArguments?.forEach { collectTypeQueryValueRefs(it, scope) }
            }
            is TemplateLiteralType -> {
                type.templateSpans.forEach { span -> collectTypeQueryValueRefs(span.type, scope) }
                templateTypeReferenceNames(type.head.rawText ?: "").forEach {
                    if (it.afterTypeof) scope.referencedNames.add(it.name)
                }
            }
            // A predicate's parameter NAME is not a read of that parameter (tsgo reports it).
            is TypePredicate -> type.type?.let { collectTypeQueryValueRefs(it, scope) }
            else -> {}
        }
    }

    /**
     * True when a JSDoc `@type` tag written above [stmt] is REPARSED into a real type
     * annotation, i.e. when it can reference a type parameter at all.
     *
     * (LEGACY.0b) TypeScript 7's reparser (`reparser.go:369`) attaches a `@type` tag to an
     * ExpressionStatement host ONLY when the expression is a BinaryExpression whose
     * `GetAssignmentDeclarationKind` is not None — an `=` whose left is an access
     * expression (`utilities.go:1526`). On any other expression statement — a bare
     * `this.p;`, a call, a compound assignment — the tag is dropped, so a JSDoc `@type {T}`
     * comment written above `this.p;` does NOT make `T` referenced. Every other statement
     * kind keeps its tag (a VariableStatement declarator, a `return`, a parenthesized
     * expression).
     */
    private fun jsDocTypeTagIsReparsed(stmt: Statement): Boolean {
        if (stmt !is ExpressionStatement) return true
        val bin = stmt.expression as? BinaryExpression ?: return false
        if (bin.operator != SyntaxKind.Equals) return false
        return bin.left is PropertyAccessExpression || bin.left is ElementAccessExpression
    }

    /** Recursively collect type refs from statements (for unused type param detection). */
    private fun collectTypeRefsInStatement(stmt: Statement, scope: UnusedScope) {
        if (jsDocTypeTagIsReparsed(stmt)) collectTypeRefsFromJSDoc(stmt.leadingComments, scope)
        when (stmt) {
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.type?.let { collectTypeRefs(it, scope) }
                    decl.initializer?.let { collectTypeRefsInExpr(it, scope) }
                }
            }
            is ExpressionStatement -> collectTypeRefsInExpr(stmt.expression, scope)
            is ReturnStatement -> stmt.expression?.let { collectTypeRefsInExpr(it, scope) }
            is IfStatement -> {
                collectTypeRefsInExpr(stmt.expression, scope)
                collectTypeRefsInStatement(stmt.thenStatement, scope)
                stmt.elseStatement?.let { collectTypeRefsInStatement(it, scope) }
            }
            is Block -> stmt.statements.forEach { collectTypeRefsInStatement(it, scope) }
            is ForStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is ForInStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is ForOfStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is WhileStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is DoStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is TryStatement -> {
                stmt.tryBlock.statements.forEach { collectTypeRefsInStatement(it, scope) }
                stmt.catchClause?.block?.statements?.forEach { collectTypeRefsInStatement(it, scope) }
                stmt.finallyBlock?.statements?.forEach { collectTypeRefsInStatement(it, scope) }
            }
            is SwitchStatement -> {
                collectTypeRefsInExpr(stmt.expression, scope)
                for (clause in stmt.caseBlock) when (clause) {
                    is CaseClause -> {
                        collectTypeRefsInExpr(clause.expression, scope)
                        clause.statements.forEach { collectTypeRefsInStatement(it, scope) }
                    }
                    is DefaultClause -> clause.statements.forEach { collectTypeRefsInStatement(it, scope) }
                    else -> {}
                }
            }
            is LabeledStatement -> collectTypeRefsInStatement(stmt.statement, scope)
            is ThrowStatement -> stmt.expression?.let { collectTypeRefsInExpr(it, scope) }
            is ExportAssignment -> collectTypeRefsInExpr(stmt.expression, scope)
            else -> {}
        }
    }

    /** Collect type refs from expressions (type assertions, as expressions, etc.). */
    private fun collectTypeRefsInExpr(expr: Expression, scope: UnusedScope) {
        when (expr) {
            is AsExpression -> {
                collectTypeRefs(expr.type, scope)
                collectTypeRefsInExpr(expr.expression, scope)
            }
            is TypeAssertionExpression -> {
                collectTypeRefs(expr.type, scope)
                collectTypeRefsInExpr(expr.expression, scope)
            }
            is CallExpression -> {
                expr.typeArguments?.forEach { collectTypeRefs(it, scope) }
                collectTypeRefsInExpr(expr.expression, scope)
                expr.arguments.forEach { collectTypeRefsInExpr(it, scope) }
            }
            is NewExpression -> {
                expr.typeArguments?.forEach { collectTypeRefs(it, scope) }
            }
            is ParenthesizedExpression -> collectTypeRefsInExpr(expr.expression, scope)
            is BinaryExpression -> {
                var current: Expression = expr
                while (current is BinaryExpression) {
                    collectTypeRefsInExpr(current.right, scope)
                    current = current.left
                }
                collectTypeRefsInExpr(current, scope)
            }
            is ConditionalExpression -> {
                collectTypeRefsInExpr(expr.condition, scope)
                collectTypeRefsInExpr(expr.whenTrue, scope)
                collectTypeRefsInExpr(expr.whenFalse, scope)
            }
            is ArrowFunction -> {
                collectTypeParamListRefs(expr.typeParameters, scope)
                expr.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, scope) } }
                expr.type?.let { collectTypeRefs(it, scope) }
                // (P18.271) the body: an expression body's `as U`, a block body's statements.
                when (val body = expr.body) {
                    is Block -> body.statements.forEach { collectTypeRefsInStatement(it, scope) }
                    is Expression -> collectTypeRefsInExpr(body, scope)
                    else -> {}
                }
            }
            is FunctionExpression -> {
                collectTypeParamListRefs(expr.typeParameters, scope)
                expr.parameters.forEach { p -> p.type?.let { collectTypeRefs(it, scope) } }
                expr.type?.let { collectTypeRefs(it, scope) }
                expr.body.statements.forEach { collectTypeRefsInStatement(it, scope) }
            }
            else -> {}
        }
    }

    /**
     * (LEGACY.0b step 13) TS6198 *All destructured elements are unused.* — tsgo's
     * `reportUnusedBindingElements` (checker.go), which TypeScript 7 runs for ARRAY patterns
     * as well as object patterns and RECURSIVELY: starting at the ROOT pattern, a pattern
     * with MORE THAN ONE element every one of which is unreferenced gets one TS6198 over the
     * whole pattern; otherwise each element is visited on its own — a nested pattern recurses,
     * a leaf falls through to its ordinary TS6133. A nested pattern is "unreferenced" iff
     * every leaf under it is (`isUnreferencedVariableDeclaration`), and an OMITTED array slot
     * (`[, a]`) has no name and counts as unreferenced — so `const [, a] = o` with `a` unused
     * is one TS6198, which tsgo prints and tsc 6 did not.
     *
     * The pre-13 emitter grouped by the INNERMOST pattern, object patterns only, so an
     * all-unused `{ a, b: { c, d } }` was TS6133 `a` + TS6198 on `{ c, d }` where tsgo groups
     * the outer pattern, and `const [a, b]` was two TS6133s where tsgo groups
     * (`unusedVariablesWithUnderscoreInBindingElement`, `…InForOfLoop`, tsgo-verified over
     * 20 more shapes in the round note).
     *
     * [unusedLeaves] are the leaf declarations that ARE unreferenced and reportable — the
     * output of the caller's own filters; any leaf not in it (a `_`-exempt element, an
     * object-rest extraction, an exported or referenced name) reads as USED, which is exactly
     * tsgo's classification. Returns the positions of every leaf covered by an emitted TS6198
     * so the caller's per-leaf loop skips them.
     */
    private fun reportUnusedBindingPatterns(
        unusedLeaves: List<UnusedDecl>,
        source: String,
        fileName: String,
    ): Set<Int> {
        val unusedLeafPositions = HashSet<Int>()
        val roots = LinkedHashMap<Int, Node>()
        for (d in unusedLeaves) {
            val root = d.rootBindingPattern ?: continue
            unusedLeafPositions.add(d.nameNode.pos)
            roots.getOrPut(root.pos) { root }
        }
        if (roots.isEmpty()) return emptySet()
        val suppressed = HashSet<Int>()
        fun elementsOf(pattern: Node): List<Node> = when (pattern) {
            is ObjectBindingPattern -> pattern.elements
            is ArrayBindingPattern -> pattern.elements
            else -> emptyList()
        }
        fun isUnreferenced(element: Node): Boolean {
            if (element !is BindingElement) return true // an omitted slot has no name
            return when (val n = element.name) {
                is ObjectBindingPattern, is ArrayBindingPattern -> elementsOf(n).all { isUnreferenced(it) }
                is Identifier -> n.pos in unusedLeafPositions
                else -> true
            }
        }
        fun collectLeaves(pattern: Node) {
            for (e in elementsOf(pattern)) {
                val n = (e as? BindingElement)?.name ?: continue
                when (n) {
                    is ObjectBindingPattern, is ArrayBindingPattern -> collectLeaves(n)
                    is Identifier -> suppressed.add(n.pos)
                    else -> {}
                }
            }
        }
        fun report(pattern: Node) {
            val elements = elementsOf(pattern)
            if (elements.size > 1 && elements.all { isUnreferenced(it) }) {
                val patStart = pattern.pos
                val spanLength = computeBindingPatternSpan(source, patStart, pattern)
                val (line, character) = checker.getLineAndCharacterOfPosition(source, patStart)
                checker.diagnostics.add(Diagnostic(
                    message = "All destructured elements are unused.",
                    category = DiagnosticCategory.Error,
                    code = 6198,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = patStart,
                    length = spanLength,
                ))
                collectLeaves(pattern)
            } else {
                for (e in elements) {
                    val n = (e as? BindingElement)?.name ?: continue
                    if (n is ObjectBindingPattern || n is ArrayBindingPattern) report(n)
                }
            }
        }
        for (root in roots.values) report(root)
        return suppressed
    }

    /**
     * The per-leaf half of a `for (const … of/in …)` / `for (…;;)` head — shared by
     * [checkForLoopVariable] and [checkForStatementVariable]. tsgo
     * `isUnreferencedVariableDeclaration`: a `_`-prefixed name is USED (a plain loop
     * variable, or any binding element that is not an object-pattern shorthand); the survivors
     * are grouped by [reportUnusedBindingPatterns] and the rest reported one by one.
     */
    private fun reportUnusedLoopBindings(scope: UnusedScope, source: String, fileName: String) {
        val unused = scope.declarations.filter { d ->
            d.name !in scope.referencedNames && (!d.name.startsWith("_") || d.isShorthandObjectElement())
        }
        val suppressed = reportUnusedBindingPatterns(unused, source, fileName)
        for (decl in unused) {
            if (decl.nameNode.pos in suppressed) continue
            val start = decl.nameNode.pos
            val length = decl.name.length
            val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
            checker.diagnostics.add(Diagnostic(
                message = "'${decl.name}' is declared but its value is never read.",
                category = DiagnosticCategory.Error,
                code = 6133,
                fileName = fileName,
                line = line,
                character = character,
                start = start,
                length = length,
            ))
        }
    }

    /** `{ _a }` — an object-pattern element with no `propertyName`, the one `_` shape tsgo reports. */
    private fun UnusedDecl.isShorthandObjectElement(): Boolean =
        parentBindingPattern is ObjectBindingPattern && (declNode as? BindingElement)?.propertyName == null

    /**
     * Compute the span length for a binding pattern by finding the closing
     * brace/bracket in the source. Our parser's `end` position is set after
     * nextToken() so it extends past the closing delimiter.
     */
    fun computeBindingPatternSpan(source: String, start: Int, pattern: Node): Int {
        val closeChar = if (pattern is ObjectBindingPattern) '}' else ']'
        var depth = 0
        var i = start
        while (i < source.length) {
            val ch = source[i]
            if (ch == '{' || ch == '[') depth++
            else if (ch == '}' || ch == ']') {
                depth--
                if (depth == 0 && ch == closeChar) return i - start + 1
            }
            i++
        }
        return pattern.end - start
    }
}

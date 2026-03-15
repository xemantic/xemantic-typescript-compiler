/*
 * TypeScript to JavaScript transpiler in Kotlin multiplatform
 * Copyright 2026 Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.typescript.compiler

import kotlin.math.pow

/**
 * Transforms a TypeScript AST into a JavaScript-compatible AST suitable for emission.
 *
 * Handles type erasure, class property-to-constructor movement, enum IIFE generation,
 * namespace IIFE generation, and module transforms.
 */
class Transformer(
    private val options: CompilerOptions,
    private val checker: Checker? = null,
) {

    // Modifiers that are TypeScript-only and should be stripped from class members
    private val typeOnlyMemberModifiers = setOf(
        ModifierFlag.Public,
        ModifierFlag.Private,
        ModifierFlag.Protected,
        ModifierFlag.Abstract,
        ModifierFlag.Readonly,
        ModifierFlag.Override,
    )

    // Modifiers that indicate constructor parameter properties
    private val parameterPropertyModifiers = setOf(
        ModifierFlag.Public,
        ModifierFlag.Private,
        ModifierFlag.Protected,
        ModifierFlag.Readonly,
    )

    /**
     * Whether to use `define` semantics for class fields (ES2022+ default).
     * When true, property declarations with initializers stay as class fields.
     * When false (default for older targets), they are moved into the constructor.
     */
    private val useDefineForClassFields: Boolean
        get() = options.useDefineForClassFields
            ?: (options.effectiveTarget >= ScriptTarget.ES2022)

    // -----------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------

    // Class/function names declared BEFORE the current statement (order-tracked).
    // Used so that a namespace whose class/function was declared before it can skip var N;
    private val declaredNames = mutableSetOf<String>()
    // Names that already have a var declaration emitted (by a prior enum or namespace IIFE).
    // Used so that a second enum/namespace with the same name doesn't emit var X; again.
    private val emittedVarNames = mutableSetOf<String>()

    // Source text of the file being transformed (set at the start of transform()).
    // Used in orphanedComments() to detect blank-line-separated comments.
    private var sourceText = ""
    private var currentFileName = ""

    // Maps const enum name → (member name → value).
    // Value is Double for numeric members, String for string members, or null if non-constant.
    // Populated in a pre-pass before transformation so all uses can be inlined.
    private val constEnumValues = mutableMapOf<String, Map<String, Any?>>()

    // Counter for namespace IIFE parameter renaming (e.g. M_1, M_2, M_3)
    private val nsRenameSuffix = mutableMapOf<String, Int>()

    // Stack of outer namespace contexts for cross-scope qualification.
    // When inside a nested namespace, inner code can reference outer namespace exports.
    // Each entry: Triple(iifeParamName, originalName, exportedNames) for an outer namespace level.
    private val outerNamespaceStack = mutableListOf<Triple<String, String, Set<String>>>()

    // Maps namespace name → set of ALL exported member names across ALL merged blocks.
    // Pre-collected so that later blocks can qualify references to members exported from earlier blocks.
    private val mergedNamespaceExports = mutableMapOf<String, MutableSet<String>>()

    // Maps enum name → (member name → numeric value) for all enums (const and non-const).
    // Populated during transformation so cross-enum references like `Foo.a` can be folded.
    private val allEnumMemberValues = mutableMapOf<String, MutableMap<String, Long>>()

    // True once we've seen a runtime (non-erased) statement at the top level.
    // Orphaned comments from erased declarations are only preserved before any runtime code.
    private var hasSeenRuntimeStatement = false

    // True once we've processed ANY top-level statement (including erased ones).
    // Triple-slash reference directives are only preserved at the very top of the file
    // (before any statement has been processed).
    private var hasSeenAnyTopLevelStatement = false

    // Stack of "scope hoisted vars" — each entry is a mutable list for a function/module scope.
    // When entering a new function body (or top-level), push a new list.
    // Class expression temp vars are added to the INNERMOST list (last entry).
    // When the scope exits, the collected vars are prepended as `var _a;` declarations.
    private val hoistedVarScopes = mutableListOf<MutableList<String>>()

    // Depth of function scopes entered. Used to decide whether enum/namespace var declarations
    // should be `var` (module level, depth=0) or `let` (inside a function body, depth>0).
    private var functionScopeDepth = 0

    // Counter for generating unique temp variable names (_a, _b, _c, ...).
    private var tempVarCounter = 0

    // Whether we are currently transforming inside an async function body being rewritten to
    // __awaiter form. When true, AwaitExpression nodes are converted to YieldExpression.
    private var inAsyncBody = false

    // Whether we are inside an async generator body (async function*) being rewritten to
    // __asyncGenerator form. When true, await→yield __await(...) and yield→yield yield __await(...).
    private var inAsyncGeneratorBody = false

    // Set to true when any async function/arrow is transformed to __awaiter form.
    // Causes the __awaiter helper to be prepended to the output statements.
    private var needsAwaiterHelper = false

    // Set to true when any async generator (async function*) is transformed.
    // Causes the __await and __asyncGenerator helpers to be prepended to the output statements.
    private var needsAsyncGeneratorHelper = false

    // Set to true when object destructuring with rest elements is transformed.
    // Causes the __rest helper to be prepended to the output statements.
    private var needsRestHelper = false

    // Set to true when any legacy decorator transform emits __decorate calls.
    private var needsDecorateHelper = false
    // Set to true when any parameter decorator emits __param calls.
    private var needsParamHelper = false
    // Set to true when decorator metadata (`emitDecoratorMetadata`) emits __metadata calls.
    private var needsMetadataHelper = false

    // Set to true when a tagged template with invalid escape sequences is transformed.
    // Causes the __makeTemplateObject helper to be prepended to the output statements.
    private var needsMakeTemplateObjectHelper = false

    // Counter for anonymous export default classes/functions (default_1, default_2, ...)
    private var anonDefaultCounter = 0


    // Top-level names that are declared ONLY as interfaces or type aliases (no runtime value).
    // Used to filter export specifiers that refer to type-only names, e.g. `export { A, B }`
    // where A and B are interfaces — TypeScript erases these and emits `export {}` instead.
    // Populated at the start of transform() from the original source file statements.
    private val topLevelTypeOnlyNames = mutableSetOf<String>()

    // Source file top-level statements, stored for qualified-path type-only namespace checks.
    private var topLevelStatements: List<Statement> = emptyList()

    // Whether the current file is a module (has import/export syntax). In script files,
    // import aliases to type-only names must be kept (they may be globally referenced).
    private var isCurrentFileModule: Boolean = false

    private fun nextTempVarName(): String {
        val n = tempVarCounter++
        // TypeScript skips both `_i` and `_n` in temp variable names.
        // The sequence is: _a, _b, ..., _h, _j, _k, _l, _m, _o, _p, ..., _z, _aa, _ab, ... (skipping 'i' and 'n').
        // 24 single-letter names total: a-h (8) + j-m (4) + o-z (12)
        fun letterAt(idx: Int): Char = when {
            idx < 8 -> 'a' + idx          // a-h (0-7)
            idx < 12 -> 'a' + idx + 1     // j-m (8-11), skip 'i'
            else -> 'a' + idx + 2         // o-z (12-23), skip 'i' and 'n'
        }
        return if (n < 24) {  // 24 single-letter names: a-h (8) + j-m (4) + o-z (12)
            "_${letterAt(n)}"
        } else {
            val m = n - 24  // 0-based index into two-letter names
            "_${letterAt(m / 24)}${letterAt(m % 24)}"
        }
    }

    /** Executes [block] with a fresh temp-var counter (per-function-scope), then restores. */
    private fun <T> withFreshTempVarCounter(block: () -> T): T {
        val saved = tempVarCounter
        tempVarCounter = 0
        return try { block() } finally { tempVarCounter = saved }
    }

    fun transform(sourceFile: SourceFile): SourceFile {
        sourceText = sourceFile.text
        currentFileName = sourceFile.fileName
        hasSeenRuntimeStatement = false
        hasSeenAnyTopLevelStatement = false
        inAsyncBody = false
        inAsyncGeneratorBody = false
        needsAwaiterHelper = false
        needsAsyncGeneratorHelper = false
        needsRestHelper = false
        needsDecorateHelper = false
        needsParamHelper = false
        needsMetadataHelper = false
        needsMakeTemplateObjectHelper = false
        topLevelStatements = sourceFile.statements
        // Pre-pass: collect top-level type-only names (interfaces/type aliases with no runtime counterpart).
        // Used to erase export specifiers that only refer to types, e.g. `export { A, B }` where A, B
        // are interfaces — TypeScript erases them and may produce `export {}` to preserve module semantics.
        topLevelTypeOnlyNames.clear()
        val topLevelRuntimeNames = mutableSetOf<String>()
        for (stmt in sourceFile.statements) {
            when (stmt) {
                is InterfaceDeclaration -> topLevelTypeOnlyNames.add(stmt.name.text)
                is TypeAliasDeclaration -> topLevelTypeOnlyNames.add(stmt.name.text)
                is ClassDeclaration -> stmt.name?.text?.let { topLevelRuntimeNames.add(it) }
                is FunctionDeclaration -> stmt.name?.text?.let { topLevelRuntimeNames.add(it) }
                is VariableStatement -> stmt.declarationList.declarations.forEach { decl ->
                    collectBoundNames(decl.name).forEach { n -> topLevelRuntimeNames.add(n) }
                }
                is EnumDeclaration -> topLevelRuntimeNames.add(stmt.name.text)
                is ModuleDeclaration -> {
                    if (isTypeOnlyNamespace(stmt)) {
                        // Namespaces with only type members (interfaces, type aliases) produce no runtime value
                        extractIdentifierName(stmt.name)?.let { topLevelTypeOnlyNames.add(it) }
                    } else {
                        extractIdentifierName(stmt.name)?.let { topLevelRuntimeNames.add(it) }
                    }
                }
                is ImportEqualsDeclaration -> {
                    // import x = M.N introduces a runtime binding unless type-only
                    if (stmt.isTypeOnly) {
                        topLevelTypeOnlyNames.add(stmt.name.text)
                    } else {
                        topLevelRuntimeNames.add(stmt.name.text)
                    }
                }
                is ImportDeclaration -> {
                    // Import bindings are runtime values — they must not be treated as type-only
                    // even if the same name is declared as an interface/type in this file.
                    // E.g.: `export default interface Foo {}; import Foo from "./b"` — Foo is runtime.
                    val clause = stmt.importClause
                    if (clause != null) {
                        if (clause.isTypeOnly) {
                            // Entire import clause is type-only: add all bindings to type-only names
                            clause.name?.text?.let { topLevelTypeOnlyNames.add(it) }
                            when (val bindings = clause.namedBindings) {
                                is NamedImports -> bindings.elements.forEach { spec ->
                                    topLevelTypeOnlyNames.add(spec.name.text)
                                }
                                else -> {}
                            }
                        } else {
                            clause.name?.text?.let { topLevelRuntimeNames.add(it) }
                            val bindings = clause.namedBindings
                            when (bindings) {
                                is NamespaceImport -> topLevelRuntimeNames.add(bindings.name.text)
                                is NamedImports -> bindings.elements.forEach { spec ->
                                    if (spec.isTypeOnly) {
                                        topLevelTypeOnlyNames.add(spec.name.text)
                                    } else {
                                        topLevelRuntimeNames.add((spec.propertyName ?: spec.name).text)
                                        topLevelRuntimeNames.add(spec.name.text)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        // Remove any type-only names that also have a runtime declaration
        topLevelTypeOnlyNames -= topLevelRuntimeNames
        // Determine if the current file has module-level syntax.
        // In script files (no imports/exports), import aliases to type-only names must be kept
        // since they may be referenced from other script files in the same compilation.
        isCurrentFileModule = isModuleFile(sourceFile)
        // Pre-pass: collect all exported names for each namespace across merged blocks.
        // This lets later blocks qualify references to members exported from earlier blocks.
        collectMergedNamespaceExports(sourceFile.statements)

        // Pre-pass: collect const enum values for inlining at use sites.
        // Values are inlined even when preserveConstEnums is true (the enum body is kept,
        // but references are still replaced with literal values).
        // With isolatedModules, const enums can't be inlined (no cross-file info available).
        if (!options.isolatedModules) {
            collectConstEnumValues(sourceFile.statements)
        }
        val transformed = transformStatements(sourceFile.statements, atTopLevel = true)

        // Collect helpers to inject at top of file (order matters: __makeTemplateObject, __rest, __decorate, __param, __awaiter, __await, __asyncGenerator).
        val helpers = mutableListOf<RawStatement>()
        if (needsMakeTemplateObjectHelper) helpers.add(RawStatement(code = MAKE_TEMPLATE_OBJECT_HELPER))
        if (needsRestHelper) helpers.add(RawStatement(code = REST_HELPER))
        if (needsDecorateHelper && !options.noEmitHelpers) helpers.add(RawStatement(code = DECORATE_HELPER))
        if (needsMetadataHelper && !options.noEmitHelpers) helpers.add(RawStatement(code = METADATA_HELPER))
        if (needsParamHelper && !options.noEmitHelpers) helpers.add(RawStatement(code = PARAM_HELPER))
        if (needsAwaiterHelper && !options.importHelpers) helpers.add(RawStatement(code = AWAITER_HELPER))
        if (needsAsyncGeneratorHelper) {
            helpers.add(RawStatement(code = AWAIT_HELPER))
            helpers.add(RawStatement(code = ASYNC_GENERATOR_HELPER))
        }

        // When helpers are present, lift leading comments from the first transformed statement
        // to appear BEFORE the helpers (TypeScript emits: comment → helpers → first stmt).
        // Only lift when the first original statement itself has comments (meaning the comment
        // belongs to the first original statement, not a later one). If the first original
        // statement was erased (e.g. `declare function`) and had no comments, the first
        // transformed statement's comments came from a later original statement — don't lift.
        // Exception: FunctionDeclaration keeps its comments after helpers when __awaiter is the only helper.
        // Only lift DETACHED comments (blank line ≥2 newlines between comment end and statement pos).
        // Adjacent comments (no blank line) stay with the statement, after helpers.
        val withHelpers = if (helpers.isNotEmpty()) {
            val firstOrigStmt = sourceFile.statements.firstOrNull()
            val onlyAwaiter = needsAwaiterHelper && !needsRestHelper
            val firstStmt = transformed.firstOrNull()
            val firstComments = firstStmt?.leadingComments
            // Only lift if the comment(s) are detached from the first original statement by a blank line
            val firstOrigComments = firstOrigStmt?.leadingComments
            val hasDetachedComment = firstOrigComments != null && firstOrigStmt.pos >= 0 &&
                firstOrigComments.any { comment ->
                    comment.end >= 0 &&
                        sourceFile.text.substring(comment.end, firstOrigStmt.pos).count { it == '\n' } >= 2
                }
            val shouldLiftComments = hasDetachedComment &&
                !(onlyAwaiter && firstOrigStmt is FunctionDeclaration)
            if (shouldLiftComments && !firstComments.isNullOrEmpty()) {
                val commentHolder = NotEmittedStatement(leadingComments = firstComments)
                val firstStripped: Statement? = when (firstStmt) {
                    is VariableStatement -> firstStmt.copy(leadingComments = null)
                    is ExpressionStatement -> firstStmt.copy(leadingComments = null)
                    is FunctionDeclaration -> firstStmt.copy(leadingComments = null)
                    is ClassDeclaration -> firstStmt.copy(leadingComments = null)
                    // NotEmittedStatement is already a comment holder — lift it whole
                    is NotEmittedStatement -> firstStmt
                    else -> null
                }
                if (firstStripped != null) {
                    val rest = if (firstStmt is NotEmittedStatement) transformed.drop(1) else listOf(firstStripped) + transformed.drop(1)
                    listOf(commentHolder) + helpers + rest
                } else {
                    helpers + transformed
                }
            } else {
                helpers + transformed
            }
        } else transformed

        // CommonJS module transform (also for Node16/NodeNext and .cts/.cjs files)
        val effectiveModule = options.effectiveModule
        val fileName = sourceFile.fileName
        val useCJS = !isESModuleFormat(effectiveModule, fileName) &&
                (effectiveModule == ModuleKind.CommonJS ||
                (effectiveModule == ModuleKind.Node16 || effectiveModule == ModuleKind.NodeNext) ||
                fileName.endsWith(".cts") || fileName.endsWith(".cjs"))
        if (useCJS && isModuleFile(sourceFile)) {
            // When importHelpers: true, inject `const tslib_1 = require("tslib")` instead of inlining helpers.
            val statementsForCJS = if (options.importHelpers && needsAwaiterHelper) {
                val tslibStmt = makeRequireConst("tslib_1", StringLiteralNode(text = "tslib", pos = -1, end = -1))
                listOf(tslibStmt) + withHelpers
            } else withHelpers
            val cjsStatements = transformToCommonJS(statementsForCJS, sourceFile)
            return sourceFile.copy(statements = cjsStatements)
        }

        // AMD module transform — only for module files (files with imports/exports).
        // Non-module files in AMD format are emitted as-is (no define wrapper).
        if (options.effectiveModule == ModuleKind.AMD && isModuleFile(sourceFile)) {
            val amdStatements = transformToAMD(withHelpers, sourceFile)
            return sourceFile.copy(statements = amdStatements)
        }

        // UMD module transform — reuses AMD body with UMD wrapper.
        if (options.effectiveModule == ModuleKind.UMD && isModuleFile(sourceFile)) {
            val umdStatements = transformToUMD(withHelpers, sourceFile)
            return sourceFile.copy(statements = umdStatements)
        }

        // System module transform — only for module files.
        if (options.effectiveModule == ModuleKind.System && isModuleFile(sourceFile)) {
            val sysStatements = transformToSystem(withHelpers, sourceFile)
            return sourceFile.copy(statements = sysStatements)
        }

        // ES module import elision: erase imports whose bindings are unused in value positions
        val elided = elideUnusedESModuleImports(withHelpers)

        // Internal import alias elision: erase `var x = M.N` from `import x = M.N`
        // when x is explicitly type-only, OR when x resolves to a const enum
        // (whose values are inlined, so the runtime import is unnecessary).
        val unusedAliasNames = sourceFile.statements
            .filterIsInstance<ImportEqualsDeclaration>()
            .filter { decl ->
                decl.moduleReference !is ExternalModuleReference && (
                    decl.isTypeOnly ||
                    // Elide import aliases to const enums (values are inlined at use sites)
                    // Skip when isolatedModules is set (no cross-file const enum inlining)
                    (!options.isolatedModules && !options.preserveConstEnums &&
                        checker?.isConstEnumAlias(decl.name.text, currentFileName) == true)
                )
            }
            .mapNotNull { decl -> decl.name.text.ifEmpty { null } }
            .toSet()
        val finalStatements = if (unusedAliasNames.isNotEmpty()) {
            elided.filter { stmt ->
                if (stmt !is VariableStatement) return@filter true
                if (stmt.declarationList.declarations.size != 1) return@filter true
                val name = extractIdentifierName(stmt.declarationList.declarations[0].name)
                name !in unusedAliasNames
            }
        } else elided

        return sourceFile.copy(statements = finalStatements)
    }

    /**
     * Erases ES module `import` declarations whose bindings are never referenced in value positions
     * after type erasure. This mirrors TypeScript's import elision behavior.
     * Side-effect imports (`import "mod"`) are always kept.
     */
    private fun elideUnusedESModuleImports(statements: List<Statement>): List<Statement> {
        // verbatimModuleSyntax: imports must be kept as-is, no elision
        if (options.verbatimModuleSyntax) return statements
        val imports = statements.filterIsInstance<ImportDeclaration>()
        if (imports.isEmpty()) return statements

        // Collect value-position references from all non-import statements
        val nonImportStmts = statements.filter { it !is ImportDeclaration }
        val referenced = collectValueReferences(nonImportStmts)

        val result = mutableListOf<Statement>()
        for (stmt in statements) {
            if (stmt !is ImportDeclaration) {
                result.add(stmt)
                continue
            }
            val clause = stmt.importClause
            if (clause == null) {
                // Side-effect import: always keep
                result.add(stmt)
                continue
            }

            // Type-only imports are always elided
            if (clause.isTypeOnly) continue

            // Check default import name
            val defaultName = clause.name?.text
            val defaultUsed = defaultName != null && defaultName in referenced
            // Check namespace import
            val nsName = (clause.namedBindings as? NamespaceImport)?.name?.text
            val nsUsed = nsName != null && nsName in referenced
            // Check named imports
            val namedImports = clause.namedBindings as? NamedImports
            val usedNamedElements = namedImports?.elements?.filter { spec ->
                // Type-only import specifiers are always elided
                if (spec.isTypeOnly) return@filter false
                val localName = spec.name.text
                localName in referenced
            }
            val hasUsedNamedImports = usedNamedElements != null && usedNamedElements.isNotEmpty()

            // If nothing is used, drop this import entirely
            if (!defaultUsed && !nsUsed && !hasUsedNamedImports) continue

            // If namespace is unused but default is used, strip the namespace binding
            if (nsName != null && !nsUsed && defaultUsed) {
                result.add(stmt.copy(importClause = clause.copy(namedBindings = null)))
                continue
            }
            // If default is unused but namespace or named imports are used, strip the default
            if (defaultName != null && !defaultUsed && (nsUsed || hasUsedNamedImports)) {
                val newClause = clause.copy(name = null)
                result.add(stmt.copy(importClause = newClause))
                continue
            }

            // If all named imports are used (and there are some), keep as-is.
            // If namedImports is null, or all elements are used and non-empty, keep as-is.
            if (namedImports == null || (usedNamedElements == namedImports.elements && namedImports.elements.isNotEmpty())) {
                result.add(stmt)
            } else {
                // Keep only the used named imports; if none used (or originally empty), drop bindings.
                val newBindings = if (usedNamedElements!!.isEmpty()) null
                else namedImports.copy(elements = usedNamedElements)
                result.add(stmt.copy(importClause = clause.copy(namedBindings = newBindings)))
            }
        }
        return result
    }

    private fun isModuleFile(
        sourceFile: SourceFile
    ): Boolean {
        if (options.moduleDetection == "force") return true
        // .mts/.mjs/.cts/.cjs files are always module files
        val fn = sourceFile.fileName
        if (fn.endsWith(".mts") || fn.endsWith(".mjs") || fn.endsWith(".cts") || fn.endsWith(".cjs")) return true
        return sourceFile.statements.any { stmt ->
            stmt is ImportDeclaration || stmt is ExportDeclaration ||
                    (stmt is ImportEqualsDeclaration && stmt.moduleReference is ExternalModuleReference) ||
                    (stmt is ImportEqualsDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    stmt is ExportAssignment ||
                    (stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is FunctionDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is ClassDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is EnumDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is InterfaceDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is TypeAliasDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is ModuleDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    // dynamic import() calls also make a file an external module
                    stmtContainsDynamicImport(stmt)
        }
    }

    /** Returns true if [stmt] contains a dynamic `import(...)` call anywhere in its subtree. */
    private fun stmtContainsDynamicImport(stmt: Statement): Boolean = when (stmt) {
        is ExpressionStatement -> exprContainsDynamicImport(stmt.expression)
        is VariableStatement -> stmt.declarationList.declarations.any {
            it.initializer?.let { init -> exprContainsDynamicImport(init) } == true
        }
        is ReturnStatement -> stmt.expression?.let { exprContainsDynamicImport(it) } == true
        is Block -> stmt.statements.any { stmtContainsDynamicImport(it) }
        is IfStatement -> exprContainsDynamicImport(stmt.expression) ||
                stmtContainsDynamicImport(stmt.thenStatement) ||
                (stmt.elseStatement?.let { stmtContainsDynamicImport(it) } == true)
        is FunctionDeclaration -> stmt.body?.statements?.any { stmtContainsDynamicImport(it) } == true
        else -> false
    }

    private fun exprContainsDynamicImport(expr: Expression): Boolean = when {
        isDynamicImportCall(expr) -> true
        expr is CallExpression -> exprContainsDynamicImport(expr.expression) ||
                expr.arguments.any { exprContainsDynamicImport(it) }
        expr is ArrowFunction -> when (val body = expr.body) {
            is Expression -> exprContainsDynamicImport(body)
            is Block -> body.statements.any { stmtContainsDynamicImport(it) }
            else -> false
        }
        expr is FunctionExpression -> expr.body.statements.any { stmtContainsDynamicImport(it) }
        expr is ObjectLiteralExpression -> expr.properties.any { prop ->
            when (prop) {
                is PropertyAssignment -> exprContainsDynamicImport(prop.initializer)
                is MethodDeclaration -> prop.body?.statements?.any { stmtContainsDynamicImport(it) } == true
                else -> false
            }
        }
        expr is AwaitExpression -> exprContainsDynamicImport(expr.expression)
        expr is ParenthesizedExpression -> exprContainsDynamicImport(expr.expression)
        expr is BinaryExpression -> {
            // Iterative traversal to avoid StackOverflow on deeply nested binaries
            var current: Expression = expr
            while (current is BinaryExpression) {
                if (exprContainsDynamicImport(current.right)) return true
                current = current.left
            }
            exprContainsDynamicImport(current)
        }
        else -> false
    }

    // -----------------------------------------------------------------
    // CommonJS module transform
    // -----------------------------------------------------------------

    private fun transformToCommonJS(
        statements: List<Statement>,
        originalSourceFile: SourceFile,
    ): List<Statement> {
        val result = mutableListOf<Statement>()

        // Files using `export = X` style (namespace export) don't get __esModule preamble.
        // They use `module.exports = X` directly and are incompatible with ES module syntax.
        // Exception: if the exported name is a pure type (interface/type alias), the export is
        // erased and the file still gets the __esModule preamble.
        val earlyTypeOnlyNames = mutableSetOf<String>()
        val earlyRuntimeNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when (stmt) {
                is TypeAliasDeclaration -> earlyTypeOnlyNames.add(stmt.name.text)
                is InterfaceDeclaration -> earlyTypeOnlyNames.add(stmt.name.text)
                is ClassDeclaration -> stmt.name?.text?.let { earlyRuntimeNames.add(it) }
                is FunctionDeclaration -> stmt.name?.text?.let { earlyRuntimeNames.add(it) }
                is VariableStatement -> stmt.declarationList.declarations.forEach { decl ->
                    collectBoundNames(decl.name).forEach { n -> earlyRuntimeNames.add(n) }
                }
                is EnumDeclaration -> earlyRuntimeNames.add(stmt.name.text)
                is ModuleDeclaration -> {
                    val n = extractIdentifierName(stmt.name)
                    if (n != null) {
                        if (isTypeOnlyNamespace(stmt)) earlyTypeOnlyNames.add(n)
                        else earlyRuntimeNames.add(n)
                    }
                }
                is ImportEqualsDeclaration -> {
                    // `export import a = X.Y` creates a runtime binding to namespace X
                    if (!stmt.isTypeOnly && ModifierFlag.Export in stmt.modifiers &&
                        stmt.moduleReference !is ExternalModuleReference) {
                        namespaceAliasRoot(stmt.moduleReference)?.let { earlyRuntimeNames.add(it) }
                    }
                }
                else -> {}
            }
        }
        val earlyPureTypeNames = earlyTypeOnlyNames - earlyRuntimeNames
        val hasExportEquals = originalSourceFile.statements.any { stmt ->
            stmt is ExportAssignment && stmt.isExportEquals &&
                (stmt.expression as? Identifier)?.text?.let { it !in earlyPureTypeNames } != false
        }
        // Files that only have dynamic import() calls (no static imports/exports) do NOT get
        // Object.defineProperty(exports, "__esModule"). Only add it when there are actual static
        // module declarations (import/export keywords) OR when the file extension forces module
        // treatment (.cts/.cjs) or moduleDetection: "force".
        val fn = originalSourceFile.fileName
        val forcedModule = fn.endsWith(".cts") || fn.endsWith(".cjs") ||
                options.moduleDetection == "force"
        val hasStaticModuleDeclarations = forcedModule || originalSourceFile.statements.any { stmt ->
            stmt is ImportDeclaration || stmt is ExportDeclaration || stmt is ExportAssignment ||
                    (stmt is ImportEqualsDeclaration && stmt.moduleReference is ExternalModuleReference) ||
                    (stmt is ImportEqualsDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is FunctionDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is ClassDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is EnumDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is InterfaceDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is TypeAliasDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                    (stmt is ModuleDeclaration && ModifierFlag.Export in stmt.modifiers)
        }

        // Add Object.defineProperty(exports, "__esModule", { value: true });
        if (!hasExportEquals && hasStaticModuleDeclarations) {
            result.add(makeEsModulePreamble())
        }

        // Track whether helper functions are needed
        var needsImportStar = false
        var needsImportDefault = false
        var needsExportStar = false

        // Counter for generating unique temp names per module base name (e.g. b_1, b_2)
        val moduleNameCounter = mutableMapOf<String, Int>()

        // Map from original import name → CJS replacement expression
        // e.g. "Namespace" → b_1.default, "a" (from {a}) → y_1.a
        val renameMap = mutableMapOf<String, Expression>()

        // Collect exported names for hoisting (exports.x = void 0)
        val exportedVarNames = mutableListOf<String>()
        // Track names emitted via "Direct" path (exports.x = value, no local var kept).
        // References to these names in export-related expressions must become exports.name.
        val directExportedVarNames = mutableSetOf<String>()
        // Collect export assignments to emit at the end
        val deferredExportAssignments = mutableListOf<Statement>()
        // Track export names already emitted as function stubs (for deduplication)
        val functionStubExportedNames = mutableSetOf<String>()
        // Track: imported local name → the import const statement (for re-export positioning)
        val importStmtForLocalName = mutableMapOf<String, Statement>()
        // Track: declared local name → the declaration statement (for export positioning)
        val declarationStmtForName = mutableMapOf<String, Statement>()
        // Track: stmt → export assignments to insert immediately after it (imports and declarations)
        val exportAssignmentsAfterImport = mutableMapOf<Statement, MutableList<Statement>>()
        // Collect function export stubs (exports.fn = fn) to insert after void0 hoists.
        // Function declarations are JS-hoisted, so their export stubs must appear BEFORE
        // any variable initializer assignments that might override the same name.
        val functionExportStubs = mutableListOf<Statement>()
        // Track exported names that conflict with imports (same name imported and exported).
        // References to these names in function/arrow bodies must use (0, exports.name)().
        val conflictingExportedNames = mutableSetOf<String>()
        // Track export assignments from keep-declaration path — must be excluded from rewriting.
        val keepDeclExportAssignments = mutableSetOf<Statement>()
        // Track `export default class X` names — their static initializers must appear
        // BEFORE the `exports.default = X` assignment (TypeScript's ordering).
        val defaultExportedClassNames = mutableSetOf<String>()
        // Collect module-level leading comments to place before the preamble.
        // TypeScript emits these BEFORE Object.defineProperty (after "use strict").
        val prePreambleStatements = mutableListOf<Statement>()

        // Pre-scan original source for function declaration names.
        // Function declarations are JS-hoisted — when re-exported via `export { name }`,
        // they use a function export stub placed before other code (the function is already available).
        // Class declarations are NOT JS-hoisted — they use the same approach as variables
        // (void0 hoist + assignment after the class body).
        val functionAndClassNames = mutableSetOf<String>()  // kept for backward compat checks
        val functionOnlyNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when (stmt) {
                is FunctionDeclaration -> stmt.name?.text?.let {
                    functionAndClassNames.add(it)
                    functionOnlyNames.add(it)
                }
                is ClassDeclaration -> stmt.name?.text?.let { functionAndClassNames.add(it) }
                else -> {}
            }
        }

        // Pre-scan original source for type-only names. When `export default Bar` appears but
        // `Bar` is declared only as a type alias or interface (not as a class/function/var/enum),
        // TypeScript erases the export assignment entirely.
        val runtimeDeclaredNames = mutableSetOf<String>()
        val typeOnlyDeclaredNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when (stmt) {
                is TypeAliasDeclaration -> typeOnlyDeclaredNames.add(stmt.name.text)
                is InterfaceDeclaration -> typeOnlyDeclaredNames.add(stmt.name.text)
                is ClassDeclaration -> stmt.name?.text?.let { runtimeDeclaredNames.add(it) }
                is FunctionDeclaration -> stmt.name?.text?.let { runtimeDeclaredNames.add(it) }
                is VariableStatement -> stmt.declarationList.declarations.forEach { decl ->
                    collectBoundNames(decl.name).forEach { n -> runtimeDeclaredNames.add(n) }
                }
                is EnumDeclaration -> runtimeDeclaredNames.add(stmt.name.text)
                is ModuleDeclaration -> {
                    val n = extractIdentifierName(stmt.name)
                        ?: stmt.name.let { flattenDottedNamespaceName(it).firstOrNull() }
                    if (n != null) {
                        if (isTypeOnlyNamespace(stmt)) typeOnlyDeclaredNames.add(n)
                        else runtimeDeclaredNames.add(n)
                    }
                }
                // Imports are runtime values — include them so local `export { name }` can distinguish
                // imported runtime values from undeclared type-only globals.
                is ImportDeclaration -> {
                    val clause = stmt.importClause
                    if (clause != null && !clause.isTypeOnly) {
                        clause.name?.text?.let { runtimeDeclaredNames.add(it) }
                        when (val bindings = clause.namedBindings) {
                            is NamespaceImport -> runtimeDeclaredNames.add(bindings.name.text)
                            is NamedImports -> bindings.elements.filter { !it.isTypeOnly }.forEach { spec ->
                                runtimeDeclaredNames.add(spec.name.text)
                            }
                            else -> {}
                        }
                    }
                }
                is ImportEqualsDeclaration -> {
                    if (!stmt.isTypeOnly) {
                        runtimeDeclaredNames.add(stmt.name.text)
                        // `export import a = X.Y` creates a runtime binding to namespace X
                        if (ModifierFlag.Export in stmt.modifiers &&
                            stmt.moduleReference !is ExternalModuleReference) {
                            namespaceAliasRoot(stmt.moduleReference)?.let { runtimeDeclaredNames.add(it) }
                        }
                    }
                }
                else -> {}
            }
        }
        val pureTypeNames = typeOnlyDeclaredNames - runtimeDeclaredNames

        // Pre-scan original source for exported namespace/enum names. Their var declarations
        // have no export modifier in the transformed code (it was stripped during namespace/enum
        // transform), so we need to track them separately for hoisting and IIFE arg rewriting.
        // Skip `declare` declarations — they produce no runtime output.
        val exportedNsEnumNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when {
                stmt is ModuleDeclaration && ModifierFlag.Export in stmt.modifiers &&
                        !hasDeclareModifier(stmt) && !isTypeOnlyNamespace(stmt) -> {
                    val n = extractIdentifierName(stmt.name)
                        ?: stmt.name.let { flattenDottedNamespaceName(it).firstOrNull() }
                    n?.let { exportedNsEnumNames.add(it) }
                }
                stmt is EnumDeclaration && ModifierFlag.Export in stmt.modifiers &&
                        !hasDeclareModifier(stmt) &&
                        (options.preserveConstEnums || options.isolatedModules || ModifierFlag.Const !in stmt.modifiers) ->
                    exportedNsEnumNames.add(stmt.name.text)
            }
        }
        // Note: exportedNsEnumNames are NOT pre-added to exportedVarNames here.
        // Instead, they are added lazily in the IIFE detection branch below, so that
        // their void0 hoists appear in source declaration order (matching TypeScript output).

        // Pre-scan: determine which exported var names will go through the "Direct" path,
        // i.e. exported variables whose initializer is NOT a FunctionExpression/ArrowFunction/ClassExpression.
        // These names lose their local binding (no `var x` is emitted), so any reference
        // to them in export clauses must be rewritten to `exports.x`.
        for (stmt in originalSourceFile.statements) {
            if (stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers) {
                val hasComplexPattern = stmt.declarationList.declarations.any {
                    extractIdentifierName(it.name) == null
                }
                val needsKeepDeclaration = hasComplexPattern ||
                        stmt.declarationList.declarations.any { decl ->
                            decl.initializer is FunctionExpression ||
                                    decl.initializer is ArrowFunction ||
                                    decl.initializer is ClassExpression
                        }
                if (!needsKeepDeclaration) {
                    for (decl in stmt.declarationList.declarations) {
                        val n = extractIdentifierName(decl.name)
                        // For `declare` statements (erased, no local binding) also track names without
                        // initializers for identifier substitution. Exclude CJS reserved names
                        // (exports/require/module) which must never be rewritten to exports.x.
                        if (n != null && (decl.initializer != null ||
                                    (ModifierFlag.Declare in stmt.modifiers && n != "exports" && n != "require" && n != "module"))) {
                            directExportedVarNames.add(n)
                        }
                    }
                }
            }
        }

        // Detect and strip "use strict" prologue directives so they can be re-inserted at the
        // very top of the transformed output (before helpers and preamble).
        var hasUseStrictPrologue = false
        val statementsRaw = statements.filter { stmt ->
            if (stmt is ExpressionStatement) {
                val expr = stmt.expression
                if (expr is StringLiteralNode && expr.text == "use strict") {
                    hasUseStrictPrologue = true
                    false // strip from body
                } else true
            } else true
        }

        // Extract leading RawStatement helpers (e.g. __awaiter) so they can be placed BEFORE
        // the __esModule preamble, matching TypeScript's output ordering.
        val leadingHelpers = statementsRaw.takeWhile { it is RawStatement }
        val statementsToProcess = statementsRaw.drop(leadingHelpers.size)

        for (stmt in statementsToProcess) {
            when (stmt) {
                // import x = require("y") → already transformed to var x = require("y")
                // but it needs to use 'const' not 'var' for CommonJS
                is VariableStatement -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val strippedModifiers = stmt.modifiers - ModifierFlag.Export

                    // Local variable declaration shadows any imported name with the same name.
                    // Remove shadowed names from renameMap so references use the local binding.
                    // When an exported name conflicts with an import, track it for (0, exports.name) rewriting.
                    for (decl in stmt.declarationList.declarations) {
                        for (n in collectBoundNames(decl.name)) {
                            if (isExported && (n in renameMap || n in importStmtForLocalName)) {
                                conflictingExportedNames.add(n)
                            }
                            renameMap.remove(n)
                        }
                    }

                    if (isExported) {
                        // Check if it's a require() call (from import = require transform)
                        val isRequire = stmt.declarationList.declarations.size == 1 &&
                                stmt.declarationList.declarations[0].initializer is CallExpression &&
                                ((stmt.declarationList.declarations[0].initializer as? CallExpression)
                                    ?.expression as? Identifier)?.text == "require"

                        if (isRequire) {
                            // const x = require("y") — keep as-is but remove export modifier
                            result.add(stmt.copy(modifiers = strippedModifiers))
                        } else {
                            // Exported variable: hoist exports.x = void 0 via exportedVarNames.
                            // TypeScript uses two different strategies depending on the initializer:
                            //   FunctionExpression/ArrowFunction/ClassExpression → keep-declaration +
                            //     emit exports.x = x; right after (so the named function is preserved)
                            //   Other initializers → emit exports.x = value; directly
                            //   No initializer → just the void0 hoist, no declaration emitted
                            for (decl in stmt.declarationList.declarations) {
                                for (name in collectBoundNames(decl.name)) if (name !in exportedVarNames) exportedVarNames.add(name)
                            }
                            val hasComplexPattern = stmt.declarationList.declarations.any {
                                extractIdentifierName(it.name) == null
                            }
                            // Keep-declaration mode if any initializer is a function/arrow/class
                            val needsKeepDeclaration = hasComplexPattern ||
                                    stmt.declarationList.declarations.any { decl ->
                                        decl.initializer is FunctionExpression ||
                                                decl.initializer is ArrowFunction ||
                                                decl.initializer is ClassExpression
                                    }
                            if (needsKeepDeclaration) {
                                // Try to flatten ObjectBindingPattern to direct exports.prop = expr.prop
                                val hasFunctionInit = stmt.declarationList.declarations.any { d ->
                                    d.initializer is FunctionExpression ||
                                            d.initializer is ArrowFunction ||
                                            d.initializer is ClassExpression
                                }
                                val flattenPairs = if (!hasFunctionInit) {
                                    stmt.declarationList.declarations.map { tryExpandObjectBinding(it) }
                                } else null
                                if (flattenPairs != null && flattenPairs.all { it != null }) {
                                    // All decls can be flattened — emit exports.prop = expr.prop directly
                                    var isFirst = true
                                    for (pairs in flattenPairs) {
                                        for ((localName, valueExpr) in pairs!!) {
                                            directExportedVarNames.add(localName)
                                            val leadingComments = if (isFirst) stmt.leadingComments else null
                                            result.add(ExpressionStatement(
                                                expression = BinaryExpression(
                                                    left = PropertyAccessExpression(
                                                        expression = syntheticId("exports"),
                                                        name = Identifier(text = localName, pos = -1, end = -1),
                                                        pos = -1, end = -1,
                                                    ),
                                                    operator = Equals,
                                                    right = valueExpr,
                                                    pos = -1, end = -1,
                                                ),
                                                leadingComments = leadingComments,
                                                pos = -1, end = -1,
                                            ))
                                            isFirst = false
                                        }
                                    }
                                } else {
                                    // Keep declaration + emit exports.x = x; right after
                                    result.add(stmt.copy(modifiers = strippedModifiers))
                                    for (decl in stmt.declarationList.declarations) {
                                        val names = collectBoundNames(decl.name)
                                        if (names.isNotEmpty() && decl.initializer != null) {
                                            for (name in names) {
                                                val assignStmt = makeExportAssignment(name)
                                                result.add(assignStmt)
                                                // Track for excluding from identifier rewriting
                                                if (name in conflictingExportedNames) {
                                                    keepDeclExportAssignments.add(assignStmt)
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Direct: emit exports.x = value; for each declarator (no local var kept).
                                // Record the name so later export-expression references can be rewritten.
                                var isFirst = true
                                val decls = stmt.declarationList.declarations
                                for ((dIdx, decl) in decls.withIndex()) {
                                    val name = extractIdentifierName(decl.name)
                                    if (name != null) {
                                        // Always track: even no-initializer exports need identifier substitution.
                                        directExportedVarNames.add(name)
                                    }
                                    if (name != null && decl.initializer != null) {
                                        val leadingComments = if (isFirst) stmt.leadingComments else null
                                        val isLastDecl = dIdx == decls.size - 1
                                        // Preserve trailing comments from the declarator or the statement (for last declarator)
                                        val trailingComments = decl.trailingComments
                                            ?: if (isLastDecl) stmt.trailingComments else null
                                        result.add(
                                            ExpressionStatement(
                                                expression = BinaryExpression(
                                                    left = PropertyAccessExpression(
                                                        expression = syntheticId("exports"),
                                                        name = Identifier(text = name, pos = -1, end = -1),
                                                        pos = -1, end = -1,
                                                    ),
                                                    operator = Equals,
                                                    right = decl.initializer,
                                                    pos = -1, end = -1,
                                                ),
                                                leadingComments = leadingComments,
                                                trailingComments = trailingComments,
                                                pos = -1, end = -1,
                                            )
                                        )
                                        isFirst = false
                                    }
                                }
                            }
                            // If no initializer and simple names: only void0 hoist, no declaration
                        }
                    } else {
                        result.add(stmt)
                        // Track for positioning non-exported var export assignments (from `export { foo }`)
                        val addedStmt = result.last()
                        for (decl in stmt.declarationList.declarations) {
                            for (name in collectBoundNames(decl.name)) declarationStmtForName[name] = addedStmt
                        }
                    }
                }

                is FunctionDeclaration -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val isDefault = ModifierFlag.Default in stmt.modifiers

                    // Local function declaration shadows any imported name with the same name.
                    // Remove it from renameMap so references to this name use the local binding.
                    stmt.name?.text?.let { renameMap.remove(it) }

                    if (isExported) {
                        val strippedModifiers = stmt.modifiers - ModifierFlag.Export - ModifierFlag.Default
                        val name = stmt.name?.text
                        if (isDefault) {
                            if (name != null) {
                                if (!hasExportEquals) {
                                    // exports.default = foo goes in stubs (before function declaration),
                                    // since function declarations are JS-hoisted and available early.
                                    functionExportStubs.add(makeExportAssignment("default", syntheticId(name)))
                                }
                                result.add(stmt.copy(modifiers = strippedModifiers))
                            } else {
                                // Anonymous export default function: assign synthetic name "default_N"
                                val anonName = "default_${++anonDefaultCounter}"
                                if (!hasExportEquals) {
                                    functionExportStubs.add(makeExportAssignment("default", syntheticId(anonName)))
                                }
                                result.add(stmt.copy(modifiers = strippedModifiers, name = syntheticId(anonName)))
                            }
                        } else {
                            val emitted = stmt.copy(modifiers = strippedModifiers)
                            if (name != null && !hasExportEquals) {
                                // Collect stub to insert after void0 hoists (before var initializers),
                                // since function declarations are hoisted and can be referenced early.
                                functionExportStubs.add(makeExportsProperty(name))
                            }
                            result.add(emitted)
                        }
                    } else {
                        // Drop Default modifier for non-exported functions (default without export is a TypeScript error)
                        result.add(if (isDefault) stmt.copy(modifiers = stmt.modifiers - ModifierFlag.Default) else stmt)
                    }
                }

                is ClassDeclaration -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val isDefault = ModifierFlag.Default in stmt.modifiers

                    if (isExported) {
                        val strippedModifiers = stmt.modifiers - ModifierFlag.Export - ModifierFlag.Default
                        val name = stmt.name?.text
                        if (name == null) {
                            // Anonymous exported class: assign synthetic name "default_N"
                            val anonName = "default_${++anonDefaultCounter}"
                            result.add(stmt.copy(modifiers = strippedModifiers, name = syntheticId(anonName)))
                            val exportName = if (isDefault) "default" else anonName
                            if (!hasExportEquals) result.add(makeExportAssignment(exportName, syntheticId(anonName)))
                        } else {
                            val emitted = stmt.copy(modifiers = strippedModifiers)
                            result.add(emitted)
                            if (isDefault) {
                                defaultExportedClassNames.add(name)
                                if (!hasExportEquals) result.add(makeExportAssignment("default", syntheticId(name)))
                            } else {
                                // Hoist exports.ClassName = void 0.
                                // When hasExportEquals, skip the exports.C = C; assignment
                                // (module.exports = X replaces all named exports).
                                if (name !in exportedVarNames) exportedVarNames.add(name)
                                if (!hasExportEquals) result.add(makeExportAssignment(name))
                            }
                        }
                    } else {
                        result.add(stmt)
                        // Track for positioning non-exported class export assignments (from `export { Foo }`)
                        stmt.name?.text?.let { declarationStmtForName[it] = result.last() }
                    }
                }

                is ExportAssignment -> {
                    if (stmt.isExportEquals) {
                        // export = expr → module.exports = expr; (deferred to end so all declarations come first)
                        // But erase if the expression refers to a pure type (interface/type alias).
                        val exprName = (stmt.expression as? Identifier)?.text
                        if (exprName == null || exprName !in pureTypeNames) {
                            // If the expression is an exported var name that has no local binding
                            // (exported without initializer → only exists as exports.x), use exports.x.
                            val exportedExpr = if (exprName != null && exprName in exportedVarNames) {
                                PropertyAccessExpression(
                                    expression = syntheticId("exports"),
                                    name = syntheticId(exprName),
                                    pos = -1, end = -1,
                                )
                            } else stmt.expression
                            deferredExportAssignments.add(
                                ExpressionStatement(
                                    expression = BinaryExpression(
                                        left = PropertyAccessExpression(
                                            expression = syntheticId("module"),
                                            name = syntheticId("exports"),
                                            pos = -1, end = -1,
                                        ),
                                        operator = Equals,
                                        right = exportedExpr,
                                        pos = -1, end = -1,
                                    ),
                                    pos = -1, end = -1,
                                )
                            )
                        }
                    } else {
                        // export default expr → exports.default = expr;
                        // But erase if the expression refers to a type-only declaration.
                        val exprName = (stmt.expression as? Identifier)?.text
                        if (exprName == null || exprName !in pureTypeNames) {
                            // If the expression is an identifier that lost its local binding
                            // (went through "Direct" path), rewrite it to exports.name.
                            val rewrittenExpr = if (exprName != null && exprName in directExportedVarNames) {
                                PropertyAccessExpression(
                                    expression = syntheticId("exports"),
                                    name = syntheticId(exprName),
                                    pos = -1, end = -1,
                                )
                            } else stmt.expression
                            result.add(makeExportAssignment("default", rewrittenExpr, leadingComments = stmt.leadingComments, pos = stmt.pos))
                        }
                    }
                }

                is ImportDeclaration -> {
                    // Transform ES imports to require calls
                    val clause = stmt.importClause
                    val moduleSpecifier = stmt.moduleSpecifier

                    if (clause == null) {
                        // Side-effect import: require("mod")
                        result.add(
                            ExpressionStatement(
                                expression = CallExpression(
                                    expression = syntheticId("require"),
                                    arguments = listOf(normalizeModuleSpecifier(moduleSpecifier)),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                                leadingComments = stmt.leadingComments,
                            )
                        )
                    } else {
                        val bindings = clause.namedBindings
                        if (clause.name != null && bindings == null) {
                            // Default import only: const b_1 = __importDefault(require("./b"))
                            // TypeScript uses the module specifier basename as temp name, not the local name
                            needsImportDefault = true
                            val localName = clause.name.text
                            val tempName = generateModuleTempName(moduleSpecifier, moduleNameCounter)
                            result.add(makeImportHelperConst(tempName, "__importDefault", moduleSpecifier, stmt.leadingComments))
                            importStmtForLocalName[localName] = result.last()
                            // Rename: Namespace → b_1.default
                            renameMap[localName] = PropertyAccessExpression(
                                expression = syntheticId(tempName),
                                name = syntheticId("default"),
                                pos = -1, end = -1,
                            )
                        } else if (bindings is NamespaceImport) {
                            // import * as x from "y" → const x = __importStar(require("y"))
                            needsImportStar = true
                            val localName = bindings.name.text
                            result.add(makeImportHelperConst(localName, "__importStar", moduleSpecifier, stmt.leadingComments))
                            importStmtForLocalName[localName] = result.last()
                            // Namespace keeps its name, no rename needed
                        } else if (clause.name != null && bindings is NamedImports) {
                            // Combined default + named: import c, { x, y } from "m"
                            // If all named bindings are "default", use __importDefault.
                            // If there are non-default named bindings, use __importStar.
                            val hasNonDefaultNamedElement = bindings.elements.any { (it.propertyName ?: it.name).text != "default" }
                            val localName = clause.name.text
                            val tempName = generateModuleTempName(moduleSpecifier, moduleNameCounter)
                            if (hasNonDefaultNamedElement) {
                                needsImportStar = true
                                result.add(makeImportHelperConst(tempName, "__importStar", moduleSpecifier, stmt.leadingComments))
                            } else {
                                needsImportDefault = true
                                result.add(makeImportHelperConst(tempName, "__importDefault", moduleSpecifier, stmt.leadingComments))
                            }
                            val importConstStmt = result.last()
                            importStmtForLocalName[localName] = importConstStmt
                            // Rename default import: c → tempName.default
                            renameMap[localName] = PropertyAccessExpression(
                                expression = syntheticId(tempName),
                                name = syntheticId("default"),
                                pos = -1, end = -1,
                            )
                            // Rename named bindings: x → tempName.x, etc.
                            for (element in bindings.elements) {
                                val importedName = (element.propertyName ?: element.name).text
                                val localAlias = element.name.text
                                importStmtForLocalName[localAlias] = importConstStmt
                                renameMap[localAlias] = PropertyAccessExpression(
                                    expression = syntheticId(tempName),
                                    name = syntheticId(importedName),
                                    pos = -1, end = -1,
                                )
                            }
                        } else if (bindings is NamedImports) {
                            // import { a, b as c } from "y" → const y_1 = require("y")
                            // import { default as x } → treated like a default import
                            val hasDefaultElement = bindings.elements.any { (it.propertyName ?: it.name).text == "default" }
                            val hasNonDefaultElement = bindings.elements.any { (it.propertyName ?: it.name).text != "default" }
                            val tempName = generateModuleTempName(moduleSpecifier, moduleNameCounter)
                            if (hasDefaultElement && hasNonDefaultElement) {
                                // Both default and named: need __importStar to preserve all exports
                                needsImportStar = true
                                result.add(makeImportHelperConst(tempName, "__importStar", moduleSpecifier, stmt.leadingComments))
                            } else if (hasDefaultElement) {
                                needsImportDefault = true
                                result.add(makeImportHelperConst(tempName, "__importDefault", moduleSpecifier, stmt.leadingComments))
                            } else {
                                result.add(makeRequireConst(tempName, moduleSpecifier, stmt.leadingComments))
                            }
                            val importConstStmt = result.last()
                            // Rename: a → y_1.a, c → y_1.b
                            for (element in bindings.elements) {
                                val importedName = (element.propertyName ?: element.name).text
                                val localAlias = element.name.text
                                importStmtForLocalName[localAlias] = importConstStmt
                                renameMap[localAlias] = PropertyAccessExpression(
                                    expression = syntheticId(tempName),
                                    name = syntheticId(importedName),
                                    pos = -1, end = -1,
                                )
                            }
                        }
                    }
                }

                is ExportDeclaration -> {
                    if (stmt.isTypeOnly) {
                        // Type-only re-export: completely erased
                    } else if (stmt.moduleSpecifier != null) {
                        when (val clause = stmt.exportClause) {
                            null -> {
                                // export * from "x" → __exportStar(require("x"), exports)
                                needsExportStar = true
                                // Note: __exportStar needs __createBinding (emitted via CREATE_BINDING_HELPER)
                                // but does NOT need the full __importStar/__setModuleDefault helpers
                                val normalizedSpec = normalizeModuleSpecifier(stmt.moduleSpecifier)
                                result.add(ExpressionStatement(
                                    expression = CallExpression(
                                        expression = syntheticId("__exportStar"),
                                        arguments = listOf(
                                            CallExpression(
                                                expression = syntheticId("require"),
                                                arguments = listOf(normalizedSpec),
                                                pos = -1, end = -1,
                                            ),
                                            syntheticId("exports"),
                                        ),
                                        pos = -1, end = -1,
                                    ),
                                    pos = -1, end = -1,
                                    leadingComments = stmt.leadingComments,
                                ))
                            }
                            is NamedExports -> {
                                // export { x as y } from "m" → Object.defineProperty for each
                                val tempName = generateModuleTempName(stmt.moduleSpecifier, moduleNameCounter)
                                val nonTypeSpecs = clause.elements.filter { !it.isTypeOnly }
                                if (nonTypeSpecs.isNotEmpty()) {
                                    // Hoist exports.name = void 0 for re-exported names
                                    for (spec in nonTypeSpecs) {
                                        val exportName = spec.name.text
                                        if (exportName !in exportedVarNames) exportedVarNames.add(exportName)
                                    }
                                    result.add(makeRequireConst(tempName, stmt.moduleSpecifier, stmt.leadingComments, useVar = true))
                                    for (spec in nonTypeSpecs) {
                                        val importedName = (spec.propertyName ?: spec.name).text
                                        val exportName = spec.name.text
                                        if (importedName == "default") {
                                            needsImportDefault = true
                                            result.add(makeReExportGetter(exportName, tempName, importedName, useImportDefault = true))
                                        } else {
                                            result.add(makeReExportGetter(exportName, tempName, importedName))
                                        }
                                    }
                                }
                            }
                            is NamespaceExport -> {
                                // export * as ns from "m" → exports.ns = __importStar(require("m"))
                                needsImportStar = true
                                val exportName = clause.name.text
                                if (exportName !in exportedVarNames) exportedVarNames.add(exportName)
                                val normalizedSpec = normalizeModuleSpecifier(stmt.moduleSpecifier)
                                result.add(
                                    ExpressionStatement(
                                        expression = BinaryExpression(
                                            left = PropertyAccessExpression(
                                                expression = syntheticId("exports"),
                                                name = syntheticId(exportName),
                                                pos = -1, end = -1,
                                            ),
                                            operator = Equals,
                                            right = CallExpression(
                                                expression = syntheticId("__importStar"),
                                                arguments = listOf(
                                                    CallExpression(
                                                        expression = syntheticId("require"),
                                                        arguments = listOf(normalizedSpec),
                                                        pos = -1, end = -1,
                                                    ),
                                                ),
                                                pos = -1, end = -1,
                                            ),
                                            pos = -1, end = -1,
                                        ),
                                        pos = -1, end = -1,
                                        leadingComments = stmt.leadingComments,
                                    )
                                )
                            }
                            else -> result.add(stmt)
                        }
                    } else if (stmt.exportClause is NamedExports) {
                        // export { x, y } — emit exports.x = x
                        // For function/class declarations (JS-hoisted), use a function export stub
                        // (placed before other code). For variables, add void0 hoist via exportedVarNames.
                        for (spec in stmt.exportClause.elements) {
                            if (spec.isTypeOnly) continue
                            val exportName = spec.name.text
                            val localName = (spec.propertyName ?: spec.name).text
                            // Skip if the local name is not declared or imported in this file.
                            // Undeclared names are type-only globals (e.g. `declare namespace X` in another file).
                            if (localName != "undefined" && localName !in runtimeDeclaredNames) continue
                            if (localName in functionOnlyNames) {
                                // Function declaration (JS-hoisted): use stub placed before other code.
                                // No void0 hoist needed (function is available immediately). Deduplicate.
                                if (exportName !in functionStubExportedNames) {
                                    functionStubExportedNames.add(exportName)
                                    functionExportStubs.add(makeExportAssignment(exportName, syntheticId(localName)))
                                }
                            } else {
                                // Variable: void0 hoist + assignment.
                                // Skip assignment for `export { undefined }` — `undefined` is a global;
                                // the void 0 hoist already provides `exports.undefined = void 0`.
                                if (localName == "undefined") {
                                    if (exportName !in exportedVarNames) exportedVarNames.add(exportName)
                                } else {
                                    // If the local var lost its binding (Direct path), reference via exports.
                                    val localExpr: Expression = if (localName in directExportedVarNames) {
                                        PropertyAccessExpression(
                                            expression = syntheticId("exports"),
                                            name = syntheticId(localName),
                                            pos = -1, end = -1,
                                        )
                                    } else syntheticId(localName)
                                    val isNewExport = exportName !in exportedVarNames
                                    if (isNewExport) exportedVarNames.add(exportName)
                                    val exportAssignment = makeExportAssignment(exportName, localExpr)
                                    if (isNewExport) {
                                        val anchorStmt = importStmtForLocalName[localName]
                                            ?: declarationStmtForName[localName]
                                        if (anchorStmt != null) {
                                            // Re-export of an imported/declared binding: insert right after it
                                            exportAssignmentsAfterImport.getOrPut(anchorStmt) { mutableListOf() }.add(exportAssignment)
                                        } else {
                                            result.add(exportAssignment)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    // Check if this is a namespace/enum IIFE for an exported name.
                    // If so, rewrite the IIFE arg from N || (N = {}) to N || (exports.N = N = {}).
                    val iifeNameForExport = extractSimpleIifeName(stmt)
                    if (iifeNameForExport != null && iifeNameForExport in exportedNsEnumNames) {
                        // Add the name to exportedVarNames here (in source declaration order)
                        // so the void0 hoist ordering matches the source order.
                        if (iifeNameForExport !in exportedVarNames) exportedVarNames.add(iifeNameForExport)
                        result.add(rewriteIifeArgForCjsExport(stmt as ExpressionStatement, iifeNameForExport))
                    } else {
                        // Recursively rewrite any nested `export var x = v` to `exports.x = v`.
                        // TypeScript handles this as error recovery (export inside block is invalid JS).
                        result.add(cjsRewriteNestedExportVars(stmt))
                    }
                }
            }
        }

        // Rewrite dynamic import() calls to CJS Promise.resolve()... form.
        // This covers all statements including nested expressions in object literals, arrow
        // functions, async functions, etc. needsImportStar is set to true if any are found.
        val dynImportFlag = booleanArrayOf(false)
        val rewritten = result.map { rewriteCjsDynStmt(it, dynImportFlag) }
        if (dynImportFlag[0]) {
            needsImportStar = true
            result.clear()
            result.addAll(rewritten)
        }

        // Insert re-export assignments immediately after their corresponding import statements.
        // e.g. `export { zzz as default }` where `zzz` came from `import zzz from "./b"` →
        // `exports.default = b_1.default` must appear right after `const b_1 = __importDefault(...)`.
        if (exportAssignmentsAfterImport.isNotEmpty()) {
            val expanded = mutableListOf<Statement>()
            for (stmt in result) {
                expanded.add(stmt)
                exportAssignmentsAfterImport[stmt]?.let { expanded.addAll(it) }
            }
            result.clear()
            result.addAll(expanded)
        }

        // Fix ordering for `export default class X { static prop = ... }`:
        // TypeScript emits static initializers (X.prop = ...) BEFORE exports.default = X.
        // Our main loop adds exports.default = X immediately after the class; reorder here.
        if (defaultExportedClassNames.isNotEmpty()) {
            val adjusted = mutableListOf<Statement>()
            var pendingDefaultExport: Statement? = null
            var pendingClassName: String? = null
            for (stmt in result) {
                val defaultTarget = extractExportsDefaultTarget(stmt)
                if (defaultTarget != null && defaultTarget in defaultExportedClassNames) {
                    pendingDefaultExport = stmt
                    pendingClassName = defaultTarget
                } else if (pendingClassName != null && isClassStaticInit(stmt, pendingClassName)) {
                    adjusted.add(stmt)
                } else {
                    if (pendingDefaultExport != null) {
                        adjusted.add(pendingDefaultExport)
                        pendingDefaultExport = null
                        pendingClassName = null
                    }
                    adjusted.add(stmt)
                }
            }
            pendingDefaultExport?.let { adjusted.add(it) }
            result.clear()
            result.addAll(adjusted)
        }

        // Early pre-preamble extraction: handle NotEmittedStatement at result[1] BEFORE
        // function stub / void0 hoist insertions change its position.
        // result[0] is the esModule preamble (synthetic); result[1] is the first real stmt.
        // Early pre-preamble extraction: check result[1] for detached leading comments
        // BEFORE void0/stub insertions change positions.
        // result[0] is the esModule preamble (synthetic); result[1] is the first real stmt.
        // Only handle NotEmittedStatement, FunctionDeclaration, ClassDeclaration here.
        // VariableStatement/ExpressionStatement are handled by the post-elision extraction below.
        if (!hasExportEquals && result.size > 1 &&
            (result[1] is NotEmittedStatement || result[1] is FunctionDeclaration || result[1] is ClassDeclaration)) {
            val firstReal = result[1]
            val allComments = firstReal.leadingComments
            if (!allComments.isNullOrEmpty() && firstReal.pos >= 0) {
                val source = originalSourceFile.text
                val stmtPos = firstReal.pos
                val detached = allComments.filter { c ->
                    c.pos >= 0 && source.substring(c.end, stmtPos).count { it == '\n' } >= 2
                }
                if (detached.isNotEmpty()) {
                    val attached = allComments - detached.toSet()
                    val attachedOrNull = attached.ifEmpty { null }
                    prePreambleStatements.add(NotEmittedStatement(leadingComments = detached))
                    when (firstReal) {
                        is NotEmittedStatement -> {
                            if (attached.isEmpty()) result.removeAt(1)
                            else result[1] = firstReal.copy(leadingComments = attachedOrNull)
                        }
                        is FunctionDeclaration -> result[1] = firstReal.copy(leadingComments = attachedOrNull)
                        is ClassDeclaration -> result[1] = firstReal.copy(leadingComments = attachedOrNull)
                        else -> {}
                    }
                }
            }
        }

        // Emit hoisted exports: TypeScript chains them as a single assignment expression.
        // e.g. exports.z = exports.y = exports.x = void 0;
        // The chain is built in declaration order so the last-declared name is leftmost.
        // TypeScript batches into groups of 50 to avoid deep expression trees (manyConstExports).
        if (exportedVarNames.isNotEmpty()) {
            val insertPos = if (hasExportEquals) 0 else 1
            val batches = exportedVarNames.chunked(50)
            for ((batchIdx, batch) in batches.withIndex()) {
                val void0 = VoidExpression(
                    expression = NumericLiteralNode(text = "0", pos = -1, end = -1),
                    pos = -1, end = -1,
                )
                val hoistExpr: Expression = batch.fold(void0 as Expression) { acc, name ->
                    BinaryExpression(
                        left = PropertyAccessExpression(
                            expression = syntheticId("exports"),
                            name = syntheticId(name),
                            pos = -1, end = -1,
                        ),
                        operator = Equals,
                        right = acc,
                        pos = -1, end = -1,
                    )
                }
                val hoistStmt = ExpressionStatement(expression = hoistExpr, pos = -1, end = -1)
                result.add(insertPos + batchIdx, hoistStmt)
            }
        }

        // Insert function export stubs after void0 hoists and Object.defineProperty preamble.
        // They must come before variable initializer assignments so the hoisted function
        // reference is captured before any same-name var overrides it.
        if (functionExportStubs.isNotEmpty()) {
            val hoistCount = if (exportedVarNames.isEmpty()) 0 else ((exportedVarNames.size + 49) / 50)
            val insertPos = when {
                hasExportEquals -> 0
                else -> 1 + hoistCount // after Object.defineProperty + all void0 hoists
            }
            result.addAll(insertPos, functionExportStubs)
        }

        // Append deferred export assignments
        result.addAll(deferredExportAssignments)

        // Step 1: Apply import identifier renaming to non-import statements FIRST.
        // (Renaming must happen before elision so elision can see the renamed references.)
        val requireImportStmts = result.filterIsInstance<VariableStatement>().filter { stmt ->
            stmt.declarationList.declarations.size == 1 && isRequireImport(stmt.declarationList.declarations[0].initializer)
        }
        if (renameMap.isNotEmpty() && requireImportStmts.isNotEmpty()) {
            val requireImportSet = requireImportStmts.toHashSet()
            val rewritten = result.map { stmt ->
                if (stmt !in requireImportSet) rewriteIdInStatement(stmt, renameMap) else stmt
            }
            result.clear()
            result.addAll(rewritten)
        }

        // Rewrite references to "direct" exported vars (exports.x = value, no local var kept)
        // and conflicting exported names (import/export same name — need (0, exports.name)() form).
        // References to these names throughout the body must become exports.name.
        val allExportRewriteNames = directExportedVarNames + conflictingExportedNames
        if (allExportRewriteNames.isNotEmpty()) {
            val exportRewriteMap: Map<String, Expression> = allExportRewriteNames.associateWith { name ->
                PropertyAccessExpression(
                    expression = syntheticId("exports"),
                    name = syntheticId(name),
                    pos = -1, end = -1,
                ) as Expression
            }
            val excludeFromRewrite = functionExportStubs.toHashSet() + keepDeclExportAssignments
            val rewritten = result.map { stmt ->
                if (stmt in excludeFromRewrite) stmt else rewriteIdInStatement(stmt, exportRewriteMap)
            }
            result.clear()
            result.addAll(rewritten)
        }

        // Collect internal import alias names (from `import x = M.N`) for elision.
        // Only erase when the module reference root is a namespace declared in this file,
        // AND the alias name never appears elsewhere in the source (including type positions).
        val topLevelNsNamesCJS = originalSourceFile.statements
            .filterIsInstance<ModuleDeclaration>()
            .mapNotNull { (it.name as? Identifier)?.text }
            .toSet()
        // Only erase unused internal aliases in module files (files with static import/export syntax).
        // In script files (global scope), aliases may be referenced by other files and must be kept.
        val unusedInternalAliasNames = if (!hasStaticModuleDeclarations) emptySet() else originalSourceFile.statements
            .filterIsInstance<ImportEqualsDeclaration>()
            .filter { !it.isTypeOnly && it.moduleReference !is ExternalModuleReference }
            .filter { decl -> namespaceAliasRoot(decl.moduleReference) in topLevelNsNamesCJS }
            .mapNotNull { decl -> decl.name.text.ifEmpty { null } }
            .filter { name ->
                Regex("\\b${Regex.escape(name)}\\b").findAll(originalSourceFile.text).count() <= 1
            }
            .toSet()
        val internalAliasNames = originalSourceFile.statements
            .filterIsInstance<ImportEqualsDeclaration>()
            .filter { !it.isTypeOnly && it.moduleReference !is ExternalModuleReference }
            .mapNotNull { it.name.text.ifEmpty { null } }
            .toSet()

        // Step 2: Import elision — drop require imports whose bound name is never
        // referenced in value positions, and unused internal module aliases.
        val requireSet = requireImportStmts.toHashSet()
        val internalAliasStmts = if (internalAliasNames.isNotEmpty()) {
            result.filterIsInstance<VariableStatement>().filter { stmt ->
                stmt.declarationList.declarations.size == 1 &&
                        extractIdentifierName(stmt.declarationList.declarations[0].name) in internalAliasNames
            }.toHashSet()
        } else emptySet()
        val importLikeStmts = requireSet + internalAliasStmts
        if (importLikeStmts.isNotEmpty() && !options.verbatimModuleSyntax) {
            // Collect references from all statements (including other import-like stmts so that
            // e.g. `var y = x` counts as a reference to `x` in the require import `const x = require(...)`)
            val referenced = collectValueReferences(result)
            val toElide = mutableSetOf<VariableStatement>()
            for (stmt in importLikeStmts) {
                val name = extractIdentifierName(stmt.declarationList.declarations[0].name) ?: continue
                if (stmt in requireSet) {
                    // Require imports: erase if name unused in value positions
                    if (name !in referenced) toElide.add(stmt)
                } else {
                    // Internal alias: erase only if eligible (namespace root known) and unused
                    if (name in unusedInternalAliasNames) toElide.add(stmt)
                }
            }
            if (toElide.isNotEmpty()) {
                // If we're eliding all __importStar uses, we don't need the helper
                val allStarElided = requireImportStmts.filter { isImportStarWrapper(it) }.all { it in toElide }
                if (allStarElided) needsImportStar = false
                val allDefaultElided = requireImportStmts.filter { isImportDefaultWrapper(it) }.all { it in toElide }
                if (allDefaultElided) needsImportDefault = false
                // Preserve detached (blank-line-separated) leading comments from elided imports.
                // TypeScript keeps copyright/header blocks before Object.defineProperty even
                // when the import they preceded is erased.
                // Note: triple-slash directives (/// <reference>, etc.) are NEVER emitted in JS output.
                val source = originalSourceFile.text
                for (stmt in toElide) {
                    val allComments = stmt.leadingComments
                    if (!allComments.isNullOrEmpty()) {
                        // Preserve detached (blank-line-separated) non-triple-slash comments when pos is valid
                        if (stmt.pos >= 0) {
                            val detached = allComments.filter { c ->
                                !c.text.startsWith("/// <") &&
                                c.pos >= 0 && source.substring(c.end, stmt.pos).count { it == '\n' } >= 2
                            }
                            if (detached.isNotEmpty()) {
                                prePreambleStatements.add(NotEmittedStatement(leadingComments = detached))
                            }
                        }
                    }
                }
                result.removeAll(toElide)
            }
        }

        // Post-elision: move "detached" header comments from the first require import to
        // before the Object.defineProperty preamble. TypeScript emits copyright blocks and
        // ///amd-dependency between "use strict" and the preamble — but ONLY when there is
        // a blank line (≥2 newlines) separating the LAST comment from the import statement.
        // (NotEmittedStatement case is handled earlier, before function stubs are inserted.)
        //
        // The blank-line check uses the LAST comment's end relative to the statement,
        // not each comment individually. This ensures a contiguous block of comments
        // (like multiple /// reference directives) is not partially moved.
        val firstRealIdx = if (hasExportEquals) -1 else (1..<result.size).firstOrNull { result[it] !is NotEmittedStatement } ?: -1
        if (firstRealIdx > 0 && (result[firstRealIdx] is VariableStatement || result[firstRealIdx] is ExpressionStatement || result[firstRealIdx] is FunctionDeclaration || result[firstRealIdx] is ClassDeclaration)) {
            val firstReal = result[firstRealIdx]
            val allComments = firstReal.leadingComments
            if (!allComments.isNullOrEmpty()) {
                val source = originalSourceFile.text
                val stmtPos = firstReal.pos
                // Find the split point: the last comment that is blank-line-separated from the
                // next item (next comment or the statement itself). Only comments BEFORE that
                // split point are pre-preamble; comments in the contiguous block before the
                // statement stay attached.
                var splitIdx = -1  // index after which all comments are attached to stmt
                for (i in allComments.indices) {
                    val c = allComments[i]
                    if (c.pos < 0 || stmtPos < 0) continue
                    val nextStart = if (i + 1 < allComments.size) allComments[i + 1].pos else stmtPos
                    if (nextStart >= 0 && c.end >= 0 &&
                        source.substring(c.end, nextStart).count { it == '\n' } >= 2) {
                        splitIdx = i
                    }
                }
                if (splitIdx >= 0) {
                    val detached = allComments.subList(0, splitIdx + 1)
                    val attached = allComments.subList(splitIdx + 1, allComments.size).ifEmpty { null }
                    prePreambleStatements.add(NotEmittedStatement(leadingComments = detached))
                    result[firstRealIdx] = when (firstReal) {
                        is VariableStatement -> firstReal.copy(leadingComments = attached)
                        is ExpressionStatement -> firstReal.copy(leadingComments = attached)
                        is FunctionDeclaration -> firstReal.copy(leadingComments = attached)
                        is ClassDeclaration -> firstReal.copy(leadingComments = attached)
                        else -> firstReal
                    }
                }
            }
        }

        // Insert runtime helpers at the start (before Object.defineProperty preamble)
        // These must be at position 0 (before everything else)
        if (needsImportStar || needsImportDefault || needsExportStar) {
            val helpers = buildString {
                // __createBinding is needed by both __importStar and __exportStar
                if (needsImportStar || needsExportStar) append(CREATE_BINDING_HELPER)
                // __setModuleDefault needed for __importStar
                if (needsImportStar) append(SET_MODULE_DEFAULT_HELPER)
                // __exportStar comes before __importStar in TypeScript's emit order
                if (needsExportStar) append(EXPORT_STAR_HELPER)
                // __importStar for namespace imports
                if (needsImportStar) append(IMPORT_STAR_FUNC_HELPER)
                if (needsImportDefault) append(IMPORT_DEFAULT_HELPER)
            }
            result.add(0, RawStatement(code = helpers))
        }

        // Insert passed-in leading helpers (e.g. __awaiter, __rest) after CJS import helpers.
        // TypeScript's ordering: "use strict" → CJS helpers (__createBinding/__importStar) →
        // other helpers (__awaiter, __await, __asyncGenerator) → preamble → statements.
        if (leadingHelpers.isNotEmpty()) {
            val insertPos = if (needsImportStar || needsImportDefault || needsExportStar) 1 else 0
            result.addAll(insertPos, leadingHelpers)
        }

        // Re-insert "use strict" at the very top (before helpers and preamble).
        // emitUseStrict sees this in the original source and skips, so the transformed
        // statements must emit it themselves at the correct position.
        if (hasUseStrictPrologue) {
            result.add(0, ExpressionStatement(
                expression = StringLiteralNode(text = "use strict"),
            ))
        }

        // Insert pre-preamble statements (orphaned module-level comments) between
        // "use strict" and Object.defineProperty, or at the start if no "use strict".
        if (prePreambleStatements.isNotEmpty() && !hasExportEquals) {
            val insertIdx = if (hasUseStrictPrologue) 1 else 0
            result.addAll(insertIdx, prePreambleStatements)
        }

        return result
    }

    /**
     * Recursively rewrites nested `export var x = v` declarations to `exports.x = v`
     * assignment expressions. Used for error-recovery cases where TypeScript allows
     * `export` inside blocks (e.g. `if (false) { export var x = 0; }`).
     */
    private fun cjsRewriteNestedExportVars(stmt: Statement): Statement = when (stmt) {
        is Block -> stmt.copy(statements = cjsRewriteNestedExportVarsInList(stmt.statements))
        is IfStatement -> stmt.copy(
            thenStatement = cjsRewriteNestedExportVarsSingle(stmt.thenStatement),
            elseStatement = stmt.elseStatement?.let { cjsRewriteNestedExportVarsSingle(it) },
        )
        is ForStatement -> stmt.copy(statement = cjsRewriteNestedExportVarsSingle(stmt.statement))
        is ForInStatement -> stmt.copy(statement = cjsRewriteNestedExportVarsSingle(stmt.statement))
        is ForOfStatement -> stmt.copy(statement = cjsRewriteNestedExportVarsSingle(stmt.statement))
        is WhileStatement -> stmt.copy(statement = cjsRewriteNestedExportVarsSingle(stmt.statement))
        is DoStatement -> stmt.copy(statement = cjsRewriteNestedExportVarsSingle(stmt.statement))
        else -> stmt
    }

    private fun cjsRewriteNestedExportVarsSingle(stmt: Statement): Statement {
        val results = cjsRewriteNestedExportVarsInList(listOf(stmt))
        return if (results.size == 1) results[0]
        else Block(statements = results, multiLine = true, pos = -1, end = -1)
    }

    private fun cjsRewriteNestedExportVarsInList(stmts: List<Statement>): List<Statement> =
        stmts.flatMap { stmt ->
            if (stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers) {
                // export var x = value → exports.x = value
                val result = mutableListOf<Statement>()
                for (decl in stmt.declarationList.declarations) {
                    val name = extractIdentifierName(decl.name)
                    if (name != null && decl.initializer != null) {
                        result.add(ExpressionStatement(
                            expression = BinaryExpression(
                                left = PropertyAccessExpression(
                                    expression = syntheticId("exports"),
                                    name = syntheticId(name),
                                    pos = -1, end = -1,
                                ),
                                operator = Equals,
                                right = decl.initializer,
                                pos = -1, end = -1,
                            ),
                            leadingComments = if (result.isEmpty()) stmt.leadingComments else null,
                            pos = -1, end = -1,
                        ))
                    } else {
                        // No initializer or complex pattern: just strip export keyword
                        result.add(stmt.copy(modifiers = stmt.modifiers - ModifierFlag.Export))
                    }
                }
                result
            } else {
                listOf(cjsRewriteNestedExportVars(stmt))
            }
        }

    // -----------------------------------------------------------------
    // AMD module transform
    // -----------------------------------------------------------------

    /**
     * Resolve a relative module specifier to an AMD module name for outFile bundling.
     * E.g., "./Configurable" imported from "Class.ts" → "Configurable"
     */
    private fun resolveAmdModuleName(specifier: String, importingFile: String): String {
        val dir = importingFile.substringBeforeLast('/', "").substringBeforeLast('\\', "")
        val parts = mutableListOf<String>()
        if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (segment in specifier.split('/')) {
            when (segment) {
                "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    /**
     * Parses `/// <amd-dependency path='p' name='n'/>` and `/// <amd-module name='n'/>`
     * directives from the source text. Returns triple of:
     * - amdModuleName: last `<amd-module name=...>` value (or null)
     * - namedAmdDeps: list of (path, name) for `<amd-dependency ... name=...>`
     * - unnamedAmdDeps: list of path strings for `<amd-dependency ... />` without name
     */
    private data class AmdDirectives(
        val moduleName: String?,
        val namedDeps: List<Pair<String, String>>, // path to name
        val unnamedDeps: List<String>,             // just path
    )

    private fun parseAmdDirectives(sourceText: String): AmdDirectives {
        var moduleName: String? = null
        val namedDeps = mutableListOf<Pair<String, String>>()
        val unnamedDeps = mutableListOf<String>()

        val lines = sourceText.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("///")) break // directives only at top of file

            // <amd-module name='...'/> or <amd-module name="..."/>
            val moduleMatch = Regex("""<amd-module\s+name=['"]([^'"]+)['"]""").find(trimmed)
            if (moduleMatch != null) {
                moduleName = moduleMatch.groupValues[1]
                continue
            }

            // <amd-dependency path='...' name='...'/>
            val depPath = Regex("""<amd-dependency\s[^>]*path=['"]([^'"]+)['"]""").find(trimmed)
                ?.groupValues?.get(1)
            if (depPath != null) {
                val depName = Regex("""name=['"]([^'"]+)['"]""").find(trimmed)?.groupValues?.get(1)
                if (depName != null) {
                    namedDeps.add(depPath to depName)
                } else {
                    unnamedDeps.add(depPath)
                }
            }
        }
        return AmdDirectives(moduleName, namedDeps, unnamedDeps)
    }

    /**
     * Transforms statements to AMD format: `define([deps], function(require, exports, ...) { body })`
     *
     * AMD module structure:
     * - Each import creates a separate dependency entry (not deduplicated)
     * - `import X = require("mod")` → dep "mod", param X
     * - `import * as ns from "mod"` → dep "mod", param ns; body: ns = __importStar(ns)
     * - `import { a } from "mod"` → dep "mod", param mod_1; body references become mod_1.a
     * - `import d from "mod"` → dep "mod", param mod_1; body: mod_1 = __importDefault(mod_1)
     * - `import "mod"` → dep "mod", no param (side-effect)
     * - `/// <amd-dependency path='p' name='n'/>` → dep p, param n
     * - `/// <amd-dependency path='p'/>` → dep p, no param
     * - `/// <amd-module name='n'/>` → first arg to define: "n"
     */
    private fun transformToAMD(
        statements: List<Statement>,
        originalSourceFile: SourceFile,
    ): List<Statement> {
        val amdDirectives = parseAmdDirectives(originalSourceFile.text)

        // Check for export = X (namespace export style)
        val earlyTypeOnlyNames = mutableSetOf<String>()
        val earlyRuntimeNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when (stmt) {
                is TypeAliasDeclaration -> earlyTypeOnlyNames.add(stmt.name.text)
                is InterfaceDeclaration -> earlyTypeOnlyNames.add(stmt.name.text)
                is ClassDeclaration -> stmt.name?.text?.let { earlyRuntimeNames.add(it) }
                is FunctionDeclaration -> stmt.name?.text?.let { earlyRuntimeNames.add(it) }
                is VariableStatement -> stmt.declarationList.declarations.forEach { decl ->
                    collectBoundNames(decl.name).forEach { n -> earlyRuntimeNames.add(n) }
                }
                is EnumDeclaration -> earlyRuntimeNames.add(stmt.name.text)
                is ModuleDeclaration -> {
                    val n = extractIdentifierName(stmt.name)
                    if (n != null) {
                        if (isTypeOnlyNamespace(stmt)) earlyTypeOnlyNames.add(n)
                        else earlyRuntimeNames.add(n)
                    }
                }
                else -> {}
            }
        }
        val earlyPureTypeNames = earlyTypeOnlyNames - earlyRuntimeNames
        val hasExportEquals = originalSourceFile.statements.any { stmt ->
            stmt is ExportAssignment && stmt.isExportEquals &&
                (stmt.expression as? Identifier)?.text?.let { it !in earlyPureTypeNames } != false
        }

        // Track helper needs
        var needsImportStar = false
        var needsImportDefault = false
        var needsExportStar = false

        // Counter for generating unique temp names
        val moduleNameCounter = mutableMapOf<String, Int>()

        // For identifier renaming (named imports like import { a } from "mod" → mod_1.a)
        val renameMap = mutableMapOf<String, Expression>()

        // Strip "use strict" from transformed statements (will go inside the function body)
        val statementsWithoutStrict = statements.filter { stmt ->
            !(stmt is ExpressionStatement &&
                stmt.expression is StringLiteralNode &&
                stmt.expression.text == "use strict")
        }

        // Dependency and parameter lists
        // Named AMD deps: (path, paramName)
        val namedAmdDeps = amdDirectives.namedDeps.toMutableList()
        // Unnamed AMD deps: path strings
        val unnamedAmdDeps = amdDirectives.unnamedDeps.toMutableList()

        // Named module imports: list of (path, paramName) for function parameters
        val namedModuleImports = mutableListOf<Pair<String, String>>()
        // Unnamed module imports: path strings (side effects, no param)
        val unnamedModuleImports = mutableListOf<String>()

        // Collect exported names for hoisting (exports.x = void 0)
        val exportedVarNames = mutableListOf<String>()
        // Deferred export assignments (export =)
        val deferredExportAssignments = mutableListOf<Statement>()
        // Function export stubs
        val functionExportStubs = mutableListOf<Statement>()

        // Pre-scan for function/class names
        val functionAndClassNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when (stmt) {
                is FunctionDeclaration -> stmt.name?.text?.let { functionAndClassNames.add(it) }
                is ClassDeclaration -> stmt.name?.text?.let { functionAndClassNames.add(it) }
                else -> {}
            }
        }

        // Pre-scan for type-only names
        val runtimeDeclaredNames = mutableSetOf<String>()
        val typeOnlyDeclaredNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when (stmt) {
                is TypeAliasDeclaration -> typeOnlyDeclaredNames.add(stmt.name.text)
                is InterfaceDeclaration -> typeOnlyDeclaredNames.add(stmt.name.text)
                is ClassDeclaration -> stmt.name?.text?.let { runtimeDeclaredNames.add(it) }
                is FunctionDeclaration -> stmt.name?.text?.let { runtimeDeclaredNames.add(it) }
                is VariableStatement -> stmt.declarationList.declarations.forEach { decl ->
                    collectBoundNames(decl.name).forEach { n -> runtimeDeclaredNames.add(n) }
                }
                is EnumDeclaration -> runtimeDeclaredNames.add(stmt.name.text)
                is ModuleDeclaration -> extractIdentifierName(stmt.name)?.let { runtimeDeclaredNames.add(it) }
                else -> {}
            }
        }
        val pureTypeNames = typeOnlyDeclaredNames - runtimeDeclaredNames

        // Pre-scan for exported namespace/enum names
        val exportedNsEnumNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when {
                stmt is ModuleDeclaration && ModifierFlag.Export in stmt.modifiers &&
                        !hasDeclareModifier(stmt) && !isTypeOnlyNamespace(stmt) ->
                    extractIdentifierName(stmt.name)?.let { exportedNsEnumNames.add(it) }
                stmt is EnumDeclaration && ModifierFlag.Export in stmt.modifiers &&
                        !hasDeclareModifier(stmt) &&
                        (options.preserveConstEnums || options.isolatedModules || ModifierFlag.Const !in stmt.modifiers) ->
                    exportedNsEnumNames.add(stmt.name.text)
            }
        }
        // Note: exportedNsEnumNames are NOT pre-added to exportedVarNames here.
        // Instead, they are added lazily in the IIFE detection branch below, so that
        // their void0 hoists appear in source declaration order (matching TypeScript output).

        // Pre-scan: determine which exported var names will go through the "Direct" path.
        // References to these names anywhere in the module body must become exports.name.
        val directExportedVarNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            if (stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers) {
                val hasComplexPattern = stmt.declarationList.declarations.any {
                    extractIdentifierName(it.name) == null
                }
                val needsKeepDeclaration = hasComplexPattern ||
                        stmt.declarationList.declarations.any { decl ->
                            decl.initializer is FunctionExpression ||
                                    decl.initializer is ArrowFunction ||
                                    decl.initializer is ClassExpression
                        }
                if (!needsKeepDeclaration) {
                    for (decl in stmt.declarationList.declarations) {
                        val n = extractIdentifierName(decl.name)
                        if (n != null) directExportedVarNames.add(n)
                    }
                }
            }
        }

        // Internal alias names (from `import X = SomeNamespace`, non-exported, non-external)
        // Used for post-build elision of unused aliases.
        val internalAliasNamesAMD = originalSourceFile.statements
            .filterIsInstance<ImportEqualsDeclaration>()
            .filter { !it.isTypeOnly && it.moduleReference !is ExternalModuleReference && ModifierFlag.Export !in it.modifiers }
            .mapNotNull { it.name.text.ifEmpty { null } }
            .toSet()

        // Body statements (non-import content)
        val bodyStatements = mutableListOf<Statement>()
        // Import reassignment statements (ns = __importStar(ns), mod = __importDefault(mod))
        // These must appear at the TOP of the body (after "use strict" + preamble)
        val importReassignments = mutableListOf<Statement>()
        // Export assignments for `export import X = require(...)` — these must NOT be rewritten
        // by the directExportedVarNames substitution (exports.X = X must stay, not → exports.X = exports.X).
        // Tracked separately and inserted into the body after the identifier rewrite step.
        val protectedExportAssignments = mutableListOf<Pair<String, Statement>>() // (name → statement)

        // Pass 1: collect imports to build deps/params, build body for non-imports
        for (stmt in statementsWithoutStrict) {
            when (stmt) {
                is ImportDeclaration -> {
                    val clause = stmt.importClause
                    val moduleSpecifier = stmt.moduleSpecifier
                    var specStr = (normalizeModuleSpecifier(moduleSpecifier) as? StringLiteralNode)?.text
                        ?: (moduleSpecifier as? StringLiteralNode)?.text ?: ""
                    // When outFile is set (AMD bundling), resolve relative specifiers to module names
                    if (options.outFile != null && (specStr.startsWith("./") || specStr.startsWith("../"))) {
                        specStr = resolveAmdModuleName(specStr, originalSourceFile.fileName)
                    }

                    if (clause == null) {
                        // Side-effect import: dep only, no param
                        unnamedModuleImports.add(specStr)
                    } else {
                        val bindings = clause.namedBindings
                        when {
                            clause.name != null && bindings == null -> {
                                // Default import: import d from "mod" → dep "mod", param mod_1
                                // Body: mod_1 = __importDefault(mod_1)
                                needsImportDefault = true
                                val localName = clause.name.text
                                val tempName = generateModuleTempName(moduleSpecifier, moduleNameCounter)
                                namedModuleImports.add(specStr to tempName)
                                // Hoisted reassignment: mod_1 = __importDefault(mod_1)
                                importReassignments.add(
                                    ExpressionStatement(
                                        expression = BinaryExpression(
                                            left = syntheticId(tempName),
                                            operator = Equals,
                                            right = CallExpression(
                                                expression = syntheticId("__importDefault"),
                                                arguments = listOf(syntheticId(tempName)),
                                                pos = -1, end = -1,
                                            ),
                                            pos = -1, end = -1,
                                        ),
                                        pos = -1, end = -1,
                                    )
                                )
                                // Rename: localName → tempName.default
                                renameMap[localName] = PropertyAccessExpression(
                                    expression = syntheticId(tempName),
                                    name = syntheticId("default"),
                                    pos = -1, end = -1,
                                )
                            }
                            bindings is NamespaceImport -> {
                                // import * as ns from "mod" → dep "mod", param ns
                                // Body: ns = __importStar(ns)
                                needsImportStar = true
                                val paramName = bindings.name.text
                                namedModuleImports.add(specStr to paramName)
                                importReassignments.add(
                                    ExpressionStatement(
                                        expression = BinaryExpression(
                                            left = syntheticId(paramName),
                                            operator = Equals,
                                            right = CallExpression(
                                                expression = syntheticId("__importStar"),
                                                arguments = listOf(syntheticId(paramName)),
                                                pos = -1, end = -1,
                                            ),
                                            pos = -1, end = -1,
                                        ),
                                        pos = -1, end = -1,
                                    )
                                )
                            }
                            bindings is NamedImports -> {
                                // import { a, b as c } from "mod" → dep "mod", param mod_1
                                val hasDefaultElement = bindings.elements.any {
                                    (it.propertyName ?: it.name).text == "default"
                                }
                                val tempName = generateModuleTempName(moduleSpecifier, moduleNameCounter)
                                if (hasDefaultElement) {
                                    needsImportDefault = true
                                }
                                namedModuleImports.add(specStr to tempName)
                                // Rename each binding: a → mod_1.a, c → mod_1.b
                                for (element in bindings.elements) {
                                    val importedName = (element.propertyName ?: element.name).text
                                    val localAlias = element.name.text
                                    renameMap[localAlias] = PropertyAccessExpression(
                                        expression = syntheticId(tempName),
                                        name = syntheticId(importedName),
                                        pos = -1, end = -1,
                                    )
                                }
                            }
                            clause.name != null && bindings != null -> {
                                // Combined default + named: import d, { a } from "mod"
                                needsImportDefault = true
                                val localName = clause.name.text
                                val tempName = generateModuleTempName(moduleSpecifier, moduleNameCounter)
                                namedModuleImports.add(specStr to tempName)
                                importReassignments.add(
                                    ExpressionStatement(
                                        expression = BinaryExpression(
                                            left = syntheticId(tempName),
                                            operator = Equals,
                                            right = CallExpression(
                                                expression = syntheticId("__importDefault"),
                                                arguments = listOf(syntheticId(tempName)),
                                                pos = -1, end = -1,
                                            ),
                                            pos = -1, end = -1,
                                        ),
                                        pos = -1, end = -1,
                                    )
                                )
                                renameMap[localName] = PropertyAccessExpression(
                                    expression = syntheticId(tempName),
                                    name = syntheticId("default"),
                                    pos = -1, end = -1,
                                )
                            }
                        }
                    }
                }

                // import X = require("mod") → already transformed to var X = require("mod")
                // In AMD: convert to dep+param (factory receives the module value).
                // In UMD: keep as const X = require("mod") in the factory body (add dep for pre-loading, no param).
                is VariableStatement -> {
                    // Check if this is a require import (from ImportEqualsDeclaration transform)
                    val decl = stmt.declarationList.declarations
                    if (decl.size == 1) {
                        val init = decl[0].initializer
                        val name = extractIdentifierName(decl[0].name)
                        if (name != null && isRequireCall(init)) {
                            val requireArg = (init as CallExpression).arguments.firstOrNull()
                            val specStr2 = (normalizeModuleSpecifier(requireArg ?: syntheticId("")) as? StringLiteralNode)?.text
                                ?: (requireArg as? StringLiteralNode)?.text ?: ""
                            if (options.effectiveModule == ModuleKind.UMD) {
                                // UMD: keep const X = require("mod") in the factory body (strip source-file leading comments)
                                unnamedModuleImports.add(specStr2)
                                bodyStatements.add(stmt.copy(leadingComments = null))
                            } else {
                                // AMD: make it a factory dep+param
                                namedModuleImports.add(specStr2 to name)
                            }
                            // If this was an `export import x = require(...)`, also re-export it.
                            // Track the export assignment separately so it isn't rewritten by
                            // the directExportedVarNames substitution (exports.x = x must stay).
                            if (ModifierFlag.Export in stmt.modifiers) {
                                directExportedVarNames.add(name)
                                protectedExportAssignments.add(name to makeExportAssignment(name))
                            }
                            continue
                        }
                    }

                    // Regular variable statement — handle exports
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val strippedModifiers = stmt.modifiers - ModifierFlag.Export

                    if (isExported) {
                        for (d in stmt.declarationList.declarations) {
                            for (n in collectBoundNames(d.name)) exportedVarNames.add(n)
                        }
                        val hasComplexPattern = stmt.declarationList.declarations.any {
                            extractIdentifierName(it.name) == null
                        }
                        val needsKeepDeclaration = hasComplexPattern ||
                                stmt.declarationList.declarations.any { d ->
                                    d.initializer is FunctionExpression ||
                                            d.initializer is ArrowFunction ||
                                            d.initializer is ClassExpression
                                }
                        if (needsKeepDeclaration) {
                            // Try to flatten ObjectBindingPattern to direct exports.prop = expr.prop
                            val hasFunctionInit = stmt.declarationList.declarations.any { d ->
                                d.initializer is FunctionExpression ||
                                        d.initializer is ArrowFunction ||
                                        d.initializer is ClassExpression
                            }
                            val flattenPairs = if (!hasFunctionInit) {
                                stmt.declarationList.declarations.map { tryExpandObjectBinding(it) }
                            } else null
                            if (flattenPairs != null && flattenPairs.all { it != null }) {
                                var isFirst = true
                                for (pairs in flattenPairs) {
                                    for ((localName, valueExpr) in pairs!!) {
                                        val leadingComments = if (isFirst) stmt.leadingComments else null
                                        bodyStatements.add(ExpressionStatement(
                                            expression = BinaryExpression(
                                                left = PropertyAccessExpression(
                                                    expression = syntheticId("exports"),
                                                    name = Identifier(text = localName, pos = -1, end = -1),
                                                    pos = -1, end = -1,
                                                ),
                                                operator = Equals,
                                                right = valueExpr,
                                                pos = -1, end = -1,
                                            ),
                                            leadingComments = leadingComments,
                                            pos = -1, end = -1,
                                        ))
                                        isFirst = false
                                    }
                                }
                            } else {
                                bodyStatements.add(stmt.copy(modifiers = strippedModifiers))
                                for (d in stmt.declarationList.declarations) {
                                    val names = collectBoundNames(d.name)
                                    if (names.isNotEmpty() && d.initializer != null) {
                                        for (n in names) bodyStatements.add(makeExportAssignment(n))
                                    }
                                }
                            }
                        } else {
                            for (d in stmt.declarationList.declarations) {
                                val n = extractIdentifierName(d.name)
                                if (n != null && d.initializer != null) {
                                    bodyStatements.add(
                                        ExpressionStatement(
                                            expression = BinaryExpression(
                                                left = PropertyAccessExpression(
                                                    expression = syntheticId("exports"),
                                                    name = Identifier(text = n, pos = -1, end = -1),
                                                    pos = -1, end = -1,
                                                ),
                                                operator = Equals,
                                                right = d.initializer,
                                                pos = -1, end = -1,
                                            ),
                                            leadingComments = stmt.leadingComments,
                                            pos = -1, end = -1,
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        bodyStatements.add(stmt)
                    }
                }

                is FunctionDeclaration -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val isDefault = ModifierFlag.Default in stmt.modifiers
                    if (isExported) {
                        val stripped = stmt.copy(modifiers = stmt.modifiers - ModifierFlag.Export - ModifierFlag.Default)
                        val name = stmt.name?.text
                        if (isDefault) {
                            if (name != null && !hasExportEquals) {
                                functionExportStubs.add(makeExportAssignment("default", syntheticId(name)))
                            }
                            bodyStatements.add(stripped)
                        } else {
                            if (name != null && !hasExportEquals) {
                                functionExportStubs.add(makeExportsProperty(name))
                            }
                            bodyStatements.add(stripped)
                        }
                    } else {
                        bodyStatements.add(stmt)
                    }
                }

                is ClassDeclaration -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val isDefault = ModifierFlag.Default in stmt.modifiers
                    if (isExported) {
                        val name = stmt.name?.text
                        if (name == null && isDefault) {
                            // Anonymous export default class: assign synthetic name "default_N"
                            val anonName = "default_${++anonDefaultCounter}"
                            val stripped = stmt.copy(modifiers = stmt.modifiers - ModifierFlag.Export - ModifierFlag.Default, name = syntheticId(anonName))
                            bodyStatements.add(stripped)
                            if (!hasExportEquals) bodyStatements.add(makeExportAssignment("default", syntheticId(anonName)))
                        } else {
                            val stripped = stmt.copy(modifiers = stmt.modifiers - ModifierFlag.Export - ModifierFlag.Default)
                            bodyStatements.add(stripped)
                            if (name != null) {
                                if (isDefault) {
                                    if (!hasExportEquals) bodyStatements.add(makeExportAssignment("default", syntheticId(name)))
                                } else {
                                    exportedVarNames.add(name)
                                    if (!hasExportEquals) bodyStatements.add(makeExportAssignment(name))
                                }
                            }
                        }
                    } else {
                        bodyStatements.add(stmt)
                    }
                }

                is ExportAssignment -> {
                    if (stmt.isExportEquals) {
                        val exprName = (stmt.expression as? Identifier)?.text
                        if (exprName == null || exprName !in pureTypeNames) {
                            // export = X → return X;
                            deferredExportAssignments.add(
                                ReturnStatement(expression = stmt.expression, pos = -1, end = -1)
                            )
                        }
                    } else {
                        val exprName = (stmt.expression as? Identifier)?.text
                        if (exprName == null || exprName !in pureTypeNames) {
                            bodyStatements.add(makeExportAssignment("default", stmt.expression))
                        }
                    }
                }

                is ExportDeclaration -> {
                    if (stmt.isTypeOnly) {
                        // Type-only: erased
                    } else if (stmt.exportClause is NamedExports) {
                        // export { x, y }
                        for (spec in stmt.exportClause.elements) {
                            if (spec.isTypeOnly) continue
                            val exportName = spec.name.text
                            val localName = (spec.propertyName ?: spec.name).text
                            if (localName in functionAndClassNames) {
                                functionExportStubs.add(makeExportAssignment(exportName, syntheticId(localName)))
                            } else {
                                if (exportName !in exportedVarNames) exportedVarNames.add(exportName)
                                bodyStatements.add(makeExportAssignment(exportName, syntheticId(localName)))
                            }
                        }
                    } else if (stmt.exportClause == null && stmt.moduleSpecifier != null) {
                        // export * from "mod"
                        needsExportStar = true
                        val modSpec = normalizeModuleSpecifier(stmt.moduleSpecifier)
                        val modPath = (modSpec as? StringLiteralNode)?.text ?: "unknown"
                        val tempName = generateModuleTempName(modSpec, moduleNameCounter)
                        namedModuleImports.add(modPath to tempName)
                        bodyStatements.add(ExpressionStatement(
                            expression = CallExpression(
                                expression = syntheticId("__exportStar"),
                                arguments = listOf(
                                    syntheticId(tempName),
                                    syntheticId("exports"),
                                ),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        ))
                    }
                }

                else -> {
                    // Check if this is a namespace/enum IIFE for an exported name
                    val iifeNameForExport = extractSimpleIifeName(stmt)
                    if (iifeNameForExport != null && iifeNameForExport in exportedNsEnumNames) {
                        // Add the name to exportedVarNames here (in source declaration order)
                        // so the void0 hoist ordering matches the source order.
                        if (iifeNameForExport !in exportedVarNames) exportedVarNames.add(iifeNameForExport)
                        bodyStatements.add(rewriteIifeArgForCjsExport(stmt as ExpressionStatement, iifeNameForExport))
                    } else {
                        bodyStatements.add(stmt)
                    }
                }
            }
        }

        // Apply import identifier renaming in body statements
        val renamedBody0 = if (renameMap.isNotEmpty()) {
            bodyStatements.map { rewriteIdInStatement(it, renameMap) }
        } else bodyStatements.toList()

        // Rewrite references to "direct" exported vars in the body.
        // Export var names emitted via "Direct" path (no local var kept) must be
        // referenced as exports.name instead of the bare name throughout the body.
        val exportedVarRewriteMap: Map<String, Expression> = if (directExportedVarNames.isNotEmpty()) {
            directExportedVarNames.associateWith { name ->
                PropertyAccessExpression(
                    expression = syntheticId("exports"),
                    name = syntheticId(name),
                    pos = -1, end = -1,
                ) as Expression
            }
        } else emptyMap()
        val renamedBody = if (exportedVarRewriteMap.isNotEmpty()) {
            renamedBody0.map { rewriteIdInStatement(it, exportedVarRewriteMap) }
        } else renamedBody0

        // Import elision: determine which namedModuleImports are actually used.
        // Collect refs from body + function stubs + deferred + protected export assignments,
        // but NOT importReassignments (otherwise every reassigned import would count as "used").
        val protectedAssignStmts = protectedExportAssignments.map { it.second }
        val allRefs = collectValueReferences(renamedBody + functionExportStubs + deferredExportAssignments + protectedAssignStmts)

        // Filter namedModuleImports to keep only used ones
        val usedNamedModuleImports = namedModuleImports.filter { (_, paramName) ->
            paramName in allRefs
        }

        // Elide unused internal alias statements (`import X = N` → `var X = N` where X not referenced)
        val finalRenamedBody = if (internalAliasNamesAMD.isNotEmpty()) {
            renamedBody.filter { stmt ->
                if (stmt is VariableStatement && stmt.declarationList.declarations.size == 1) {
                    val name = extractIdentifierName(stmt.declarationList.declarations[0].name)
                    if (name != null && name in internalAliasNamesAMD && name !in allRefs) return@filter false
                }
                true
            }
        } else renamedBody

        // Elide importReassignments for unused imports
        val unusedParamNames = namedModuleImports
            .filter { (_, paramName) -> paramName !in allRefs }
            .map { it.second }
            .toSet()
        val filteredReassignments = importReassignments.filter { stmt ->
            if (stmt is ExpressionStatement && stmt.expression is BinaryExpression) {
                val paramName = (stmt.expression.left as? Identifier)?.text
                if (paramName != null && paramName in unusedParamNames) return@filter false
            }
            true
        }
        // Recheck: if we elided ALL importStar reassignments, don't need the star helper
        if (filteredReassignments.none { s ->
            s is ExpressionStatement && s.expression is BinaryExpression &&
                (s.expression.right as? CallExpression)?.let {
                    (it.expression as? Identifier)?.text == "__importStar"
                } == true
        }) {
            needsImportStar = false
        }
        // Recheck: if we elided all importDefault reassignments, don't need the helper
        if (filteredReassignments.none { s ->
            s is ExpressionStatement && s.expression is BinaryExpression &&
                (s.expression.right as? CallExpression)?.let {
                    (it.expression as? Identifier)?.text == "__importDefault"
                } == true
        }) {
            needsImportDefault = false
        }

        // Build body: "use strict" + optional __esModule preamble + void0 hoists + function stubs +
        //             importReassignments + body + deferred
        val fullBody = mutableListOf<Statement>()
        fullBody.add(ExpressionStatement(expression = StringLiteralNode(text = "use strict"), pos = -1, end = -1))

        if (!hasExportEquals) {
            fullBody.add(makeEsModulePreamble())
        }

        // Void0 hoists — batch into groups of 50 to avoid deep expression trees
        if (exportedVarNames.isNotEmpty()) {
            for (batch in exportedVarNames.chunked(50)) {
                val void0 = VoidExpression(
                    expression = NumericLiteralNode(text = "0", pos = -1, end = -1),
                    pos = -1, end = -1,
                )
                val hoistExpr: Expression = batch.fold(void0 as Expression) { acc, name ->
                    BinaryExpression(
                        left = PropertyAccessExpression(
                            expression = syntheticId("exports"),
                            name = syntheticId(name),
                            pos = -1, end = -1,
                        ),
                        operator = Equals,
                        right = acc,
                        pos = -1, end = -1,
                    )
                }
                fullBody.add(ExpressionStatement(expression = hoistExpr, pos = -1, end = -1))
            }
        }

        // Function export stubs (after void0 hoists)
        fullBody.addAll(functionExportStubs)

        // Import reassignments (hoisted to top of body, after preamble)
        fullBody.addAll(filteredReassignments)

        // Protected export assignments for `export import X = require(...)`.
        // These must not be rewritten by the directExportedVarNames substitution
        // (exports.X = X must stay as-is, not become exports.X = exports.X).
        // Insert them before the main body so they appear right after the preamble.
        for ((_, exportAssignStmt) in protectedExportAssignments) {
            fullBody.add(exportAssignStmt)
        }

        // Main body
        fullBody.addAll(finalRenamedBody)

        // Deferred (export =, i.e. return X)
        fullBody.addAll(deferredExportAssignments)

        // Build dependency array: ["require", "exports", namedAmdDeps..., usedNamedModuleImports..., unnamedAmdDeps..., unnamedModuleImports...]
        val depPaths = mutableListOf<String>()
        depPaths.add("require")
        depPaths.add("exports")
        namedAmdDeps.forEach { (path, _) -> depPaths.add(path) }
        usedNamedModuleImports.forEach { (path, _) -> depPaths.add(path) }
        unnamedAmdDeps.forEach { path -> depPaths.add(path) }
        unnamedModuleImports.forEach { path -> depPaths.add(path) }

        val depArray = ArrayLiteralExpression(
            elements = depPaths.map { StringLiteralNode(text = it, pos = -1, end = -1) },
            pos = -1, end = -1,
        )

        // Build parameter list: require, exports, namedAmdParams..., usedNamedModuleParams...
        val paramNames = mutableListOf<String>()
        paramNames.add("require")
        paramNames.add("exports")
        namedAmdDeps.forEach { (_, name) -> paramNames.add(name) }
        usedNamedModuleImports.forEach { (_, name) -> paramNames.add(name) }

        val parameters = paramNames.map { name ->
            Parameter(name = syntheticId(name), pos = -1, end = -1)
        }

        // Build the function body block (multiLine = true to get proper indentation)
        val funcBody = Block(
            statements = fullBody,
            multiLine = true,
            pos = -1, end = -1,
        )

        val funcExpr = FunctionExpression(
            parameters = parameters,
            body = funcBody,
            pos = -1, end = -1,
        )

        // Build the define() call args
        // Module name: explicit <amd-module name='n'/> takes priority;
        // when outFile is set, derive from source file path (AMD bundling convention).
        val amdModuleName = amdDirectives.moduleName ?: if (options.outFile != null) {
            originalSourceFile.fileName
                .removePrefix("./")
                .substringBeforeLast('.')
        } else null
        val defineArgs = mutableListOf<Expression>()
        if (amdModuleName != null) {
            defineArgs.add(StringLiteralNode(text = amdModuleName, pos = -1, end = -1))
        }
        defineArgs.add(depArray)
        defineArgs.add(funcExpr)

        val defineCall = ExpressionStatement(
            expression = CallExpression(
                expression = syntheticId("define"),
                arguments = defineArgs,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        // Build result: [amdDepComments?, helpers?, defineCall]
        val result = mutableListOf<Statement>()

        // Extract ONLY <amd-dependency> comments to emit FIRST (before helpers and define).
        // <amd-module> comments stay on their statements and appear inside the body.
        val amdDepComments = originalSourceFile.statements.flatMap { s ->
            s.leadingComments?.filter { c ->
                c.text.startsWith("///") && c.text.contains("<amd-dependency")
            } ?: emptyList()
        }
        if (amdDepComments.isNotEmpty()) {
            result.add(NotEmittedStatement(leadingComments = amdDepComments, pos = -1, end = -1))
        }

        // Runtime helpers go OUTSIDE the define wrapper (after amd comments, before define)
        if (needsImportStar || needsImportDefault || needsExportStar) {
            val helpers = buildString {
                if (needsImportStar || needsExportStar) append(CREATE_BINDING_HELPER)
                if (needsImportStar) append(SET_MODULE_DEFAULT_HELPER)
                if (needsExportStar) append(EXPORT_STAR_HELPER)
                if (needsImportStar) append(IMPORT_STAR_FUNC_HELPER)
                if (needsImportDefault) append(IMPORT_DEFAULT_HELPER)
            }
            result.add(RawStatement(code = helpers))
        }

        result.add(defineCall)
        return result
    }

    /**
     * Transforms to UMD module format by reusing the AMD transform body
     * and wrapping it in a UMD factory pattern:
     * ```
     * (function (factory) {
     *     if (typeof module === "object" && typeof module.exports === "object") {
     *         var v = factory(require, exports);
     *         if (v !== undefined) module.exports = v;
     *     }
     *     else if (typeof define === "function" && define.amd) {
     *         define(depArray, factory);
     *     }
     * })(function (require, exports, ...) { body });
     * ```
     */
    private fun transformToUMD(
        statements: List<Statement>,
        originalSourceFile: SourceFile,
    ): List<Statement> {
        val amdStatements = transformToAMD(statements, originalSourceFile)

        // Find the define() call in the AMD output and extract its parts
        val result = mutableListOf<Statement>()
        var defineFound = false

        for (stmt in amdStatements) {
            if (!defineFound && stmt is ExpressionStatement) {
                val call = stmt.expression as? CallExpression
                val callee = call?.expression as? Identifier
                if (callee?.text == "define") {
                    defineFound = true
                    // Extract dependency array and factory function from define(depArray, factory) or define(name, depArray, factory)
                    val args = call.arguments
                    val depArray: Expression
                    val factoryFunc: Expression
                    val amdModuleName: StringLiteralNode?
                    if (args.size >= 3 && args[0] is StringLiteralNode) {
                        // define("name", [...], function(...) {...})
                        amdModuleName = args[0] as StringLiteralNode
                        depArray = args[1]
                        factoryFunc = args[2]
                    } else if (args.size >= 2) {
                        // define([...], function(...) {...})
                        amdModuleName = null
                        depArray = args[0]
                        factoryFunc = args[1]
                    } else {
                        result.add(stmt)
                        continue
                    }

                    // Build UMD wrapper
                    val umdWrapper = buildUMDWrapper(depArray, factoryFunc, amdModuleName)
                    result.add(umdWrapper)
                    continue
                }
            }
            result.add(stmt)
        }

        return result
    }

    private fun buildUMDWrapper(depArray: Expression, factoryFunc: Expression, amdModuleName: StringLiteralNode? = null): ExpressionStatement {
        val factoryParam = Parameter(name = syntheticId("factory"), pos = -1, end = -1)

        // define([name,] depArray, factory) — inside the AMD branch
        val defineArgs = if (amdModuleName != null) {
            listOf(amdModuleName, depArray, syntheticId("factory"))
        } else {
            listOf(depArray, syntheticId("factory"))
        }
        val innerDefineCall = ExpressionStatement(
            expression = CallExpression(
                expression = syntheticId("define"),
                arguments = defineArgs,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        // typeof module === "object" && typeof module.exports === "object"
        val moduleCheck = BinaryExpression(
            left = BinaryExpression(
                left = TypeOfExpression(expression = syntheticId("module"), pos = -1, end = -1),
                operator = EqualsEqualsEquals,
                right = StringLiteralNode(text = "object", pos = -1, end = -1),
                pos = -1, end = -1,
            ),
            operator = AmpersandAmpersand,
            right = BinaryExpression(
                left = TypeOfExpression(
                    expression = PropertyAccessExpression(
                        expression = syntheticId("module"),
                        name = syntheticId("exports"),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                operator = EqualsEqualsEquals,
                right = StringLiteralNode(text = "object", pos = -1, end = -1),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        // var v = factory(require, exports);
        val varV = VariableStatement(
            declarationList = VariableDeclarationList(
                declarations = listOf(
                    VariableDeclaration(
                        name = syntheticId("v"),
                        initializer = CallExpression(
                            expression = syntheticId("factory"),
                            arguments = listOf(syntheticId("require"), syntheticId("exports")),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ),
                ),
                flags = VarKeyword,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        // if (v !== undefined) module.exports = v;
        val innerIf = IfStatement(
            expression = BinaryExpression(
                left = syntheticId("v"),
                operator = ExclamationEqualsEquals,
                right = syntheticId("undefined"),
                pos = -1, end = -1,
            ),
            thenStatement = ExpressionStatement(
                expression = BinaryExpression(
                    left = PropertyAccessExpression(
                        expression = syntheticId("module"),
                        name = syntheticId("exports"),
                        pos = -1, end = -1,
                    ),
                    operator = Equals,
                    right = syntheticId("v"),
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        // typeof define === "function" && define.amd
        val defineCheck = BinaryExpression(
            left = BinaryExpression(
                left = TypeOfExpression(expression = syntheticId("define"), pos = -1, end = -1),
                operator = EqualsEqualsEquals,
                right = StringLiteralNode(text = "function", pos = -1, end = -1),
                pos = -1, end = -1,
            ),
            operator = AmpersandAmpersand,
            right = PropertyAccessExpression(
                expression = syntheticId("define"),
                name = syntheticId("amd"),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        // CJS branch block
        val cjsBranch = Block(
            statements = listOf(varV, innerIf),
            multiLine = true,
            pos = -1, end = -1,
        )

        // AMD branch block
        val amdBranch = Block(
            statements = listOf(innerDefineCall),
            multiLine = true,
            pos = -1, end = -1,
        )

        // if (moduleCheck) { cjs } else if (defineCheck) { amd }
        val outerIf = IfStatement(
            expression = moduleCheck,
            thenStatement = cjsBranch,
            elseStatement = IfStatement(
                expression = defineCheck,
                thenStatement = amdBranch,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        // (function(factory) { outerIf })(factoryFunc)
        return ExpressionStatement(
            expression = CallExpression(
                expression = ParenthesizedExpression(
                    expression = FunctionExpression(
                        parameters = listOf(factoryParam),
                        body = Block(
                            statements = listOf(outerIf),
                            multiLine = true,
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                arguments = listOf(factoryFunc),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    // System module transform
    /**
     * Transforms statements to System module format:
     * `System.register([deps], function(exports_1, context_1) { ... })`
     *
     * System module structure:
     * - Dependencies array contains paths from ImportDeclarations
     * - Function body: "use strict"; var decls; var __moduleName; function hoists; return { setters, execute }
     * - Setters: one per unique dep path, assigns module binding vars
     * - Execute: contains all non-import statements with export calls
     * - exports_1("name", value) instead of exports.name = value
     */
    private fun transformToSystem(
        statements: List<Statement>,
        originalSourceFile: SourceFile,
    ): List<Statement> {
        // Parse /// <amd-module name='X'/> directives (also applies to System format)
        val amdDirectives = parseAmdDirectives(originalSourceFile.text)

        // Strip "use strict" from transformed statements (goes inside the function body)
        val statementsWithoutStrict = statements.filter { stmt ->
            !(stmt is ExpressionStatement &&
                stmt.expression is StringLiteralNode &&
                stmt.expression.text == "use strict")
        }

        // Counter for generating unique temp names
        val moduleNameCounter = mutableMapOf<String, Int>()
        // Counter for side-effect import setter param names (_1, _2, ...)
        var sideEffectCounter = 0

        // Ordered list of (moduleSpecifierPath, tempVarName) pairs for each ImportDeclaration binding.
        // Multiple imports from the same path share a setter; each binding gets its own temp var.
        data class ImportBinding(
            val path: String,          // module path string
            val tempVarName: String,   // the temp var (e.g. "file1_1", "ns")
            val renameTarget: Expression, // what to rewrite usages to (e.g. file2_1.A)
            val localName: String,     // local name in source (for renaming)
        )

        // Ordered list for processing (preserves source order)
        val importBindings = mutableListOf<ImportBinding>()
        // Unique module paths in order of first appearance (for deduplication)
        val uniqueModulePaths = mutableListOf<String>()
        // Map from path → setter param name (the param inside setter function)
        val pathToSetterParam = mutableMapOf<String, String>()
        // Map from path → list of (tempVar) that get assigned in this setter
        val pathToTempVars = mutableMapOf<String, MutableList<String>>()
        // Map from path → namespace import var names (assigned FIRST in setter, before exportStar)
        val pathToNamespaceVars = mutableMapOf<String, MutableList<String>>()
        // Side-effect imports (no bindings) — just added as deps with empty setter
        val sideEffectPaths = mutableListOf<String>()
        // Side-effect import setter param names (in path order)
        val sideEffectSetterParam = mutableMapOf<String, String>()
        // Export star paths — each needs a setter that calls exportStar_N
        val exportStarPaths = mutableListOf<String>()  // list of (path) in order
        // Named re-exports from a module: export {a2, b2, c2 as d2} from "bar"
        // path → list of (exportName, importedOriginalName) pairs
        val pathToNamedReExports = mutableMapOf<String, MutableList<Pair<String, String>>>()
        // Names that are namespace imports (import * as X) — for execute ordering
        val namespaceImportNames = mutableSetOf<String>()

        // Pre-scan for type-only names (to avoid exporting them)
        val runtimeDeclaredNames = mutableSetOf<String>()
        val typeOnlyDeclaredNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when (stmt) {
                is TypeAliasDeclaration -> typeOnlyDeclaredNames.add(stmt.name.text)
                is InterfaceDeclaration -> typeOnlyDeclaredNames.add(stmt.name.text)
                is ClassDeclaration -> stmt.name?.text?.let { runtimeDeclaredNames.add(it) }
                is FunctionDeclaration -> stmt.name?.text?.let { runtimeDeclaredNames.add(it) }
                is VariableStatement -> stmt.declarationList.declarations.forEach { decl ->
                    collectBoundNames(decl.name).forEach { n -> runtimeDeclaredNames.add(n) }
                }
                is EnumDeclaration -> runtimeDeclaredNames.add(stmt.name.text)
                is ModuleDeclaration -> extractIdentifierName(stmt.name)?.let { runtimeDeclaredNames.add(it) }
                else -> {}
            }
        }
        val pureTypeNames = typeOnlyDeclaredNames - runtimeDeclaredNames

        // Pre-scan: find ALL local namespace/enum names (regardless of whether they have `export` modifier).
        // They are considered "IIFE names" if they produce an IIFE (namespace/enum declaration).
        val localNsEnumNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when {
                stmt is ModuleDeclaration && !hasDeclareModifier(stmt) && !isTypeOnlyNamespace(stmt) -> {
                    val n = extractIdentifierName(stmt.name)
                        ?: stmt.name.let { flattenDottedNamespaceName(it).firstOrNull() }
                    n?.let { localNsEnumNames.add(it) }
                }
                stmt is EnumDeclaration && !hasDeclareModifier(stmt) &&
                        (options.preserveConstEnums || options.isolatedModules || ModifierFlag.Const !in stmt.modifiers) ->
                    localNsEnumNames.add(stmt.name.text)
            }
        }
        // Pre-scan: collect all export aliases for namespace/enum local names from ExportDeclarations.
        // e.g. `export { ns, AnEnum, ns as FooBar }` → iifeExportAliases["ns"] = ["ns", "FooBar"]
        // These aliases get chained into the IIFE arg, not emitted as separate statements.
        val iifeExportAliases = mutableMapOf<String, MutableList<String>>() // localName → [exportNames]
        for (stmt in originalSourceFile.statements) {
            if (stmt is ExportDeclaration && stmt.exportClause is NamedExports && stmt.moduleSpecifier == null) {
                for (spec in (stmt.exportClause).elements) {
                    if (spec.isTypeOnly) continue
                    val localName = (spec.propertyName ?: spec.name).text
                    val exportName = spec.name.text
                    // Collect aliases for namespace/enum IIFEs (both explicitly exported and re-exported)
                    if (localName in localNsEnumNames) {
                        iifeExportAliases.getOrPut(localName) { mutableListOf() }.add(exportName)
                    }
                }
            }
        }
        // The set of IIFE names that need System export rewriting:
        // = directly exported ns/enums + locally-declared ns/enums that are re-exported via export {}
        val exportedNsEnumNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when {
                stmt is ModuleDeclaration && ModifierFlag.Export in stmt.modifiers &&
                        !hasDeclareModifier(stmt) && !isTypeOnlyNamespace(stmt) -> {
                    val n = extractIdentifierName(stmt.name)
                        ?: stmt.name.let { flattenDottedNamespaceName(it).firstOrNull() }
                    n?.let { exportedNsEnumNames.add(it) }
                }
                stmt is EnumDeclaration && ModifierFlag.Export in stmt.modifiers &&
                        !hasDeclareModifier(stmt) &&
                        (options.preserveConstEnums || options.isolatedModules || ModifierFlag.Const !in stmt.modifiers) ->
                    exportedNsEnumNames.add(stmt.name.text)
            }
        }
        // Also include locally-declared ns/enums that are re-exported via `export { localName }`
        exportedNsEnumNames.addAll(iifeExportAliases.keys)

        // Pre-scan: collect all explicitly-named exports (for exportedNames_1 when combined with export *)
        // These are the EXPORT names (not local names) from all ExportDeclarations and exported vars.
        // Use LinkedHashSet to preserve source declaration order.
        val localExportNames = linkedSetOf<String>()
        // Also collect which local var names have initializers (for local re-export emit decisions).
        val varsWithInitializers = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when {
                stmt is ExportDeclaration && !stmt.isTypeOnly && stmt.exportClause is NamedExports -> {
                    for (spec in stmt.exportClause.elements) {
                        // Skip "default" — already excluded by n !== "default" check in exportStar
                        if (!spec.isTypeOnly && spec.name.text != "default") localExportNames.add(spec.name.text)
                    }
                }
                stmt is ExportDeclaration && !stmt.isTypeOnly && stmt.exportClause == null &&
                        stmt.moduleSpecifier != null -> {
                    // export * from "mod" — skip (not a named export)
                }
                stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers -> {
                    for (d in stmt.declarationList.declarations) {
                        val n = extractIdentifierName(d.name)
                        if (n != null) {
                            localExportNames.add(n)
                            if (d.initializer != null) varsWithInitializers.add(n)
                        }
                    }
                }
                stmt is VariableStatement -> {
                    for (d in stmt.declarationList.declarations) {
                        val n = extractIdentifierName(d.name)
                        if (n != null && d.initializer != null) varsWithInitializers.add(n)
                    }
                }
                stmt is FunctionDeclaration && ModifierFlag.Export in stmt.modifiers && !hasDeclareModifier(stmt)
                        && ModifierFlag.Default !in stmt.modifiers -> {
                    // export function foo() {} — the function name is a named export (not default)
                    // "default" is already excluded by the n !== "default" check in exportStar, so skip it.
                    stmt.name?.text?.let { localExportNames.add(it) }
                }
                else -> {}
            }
        }

        // For renaming local names in body (import bindings)
        val renameMap = mutableMapOf<String, Expression>()
        // Set of var names that are require-call imports (to skip in Pass 2)
        val requireImportVarNames = mutableSetOf<String>()
        // Set of require-import var names that are also exported (export import X = require("mod"))
        // These need exports_1("X", param) in their setter body
        val exportedRequireImportNames = mutableSetOf<String>()

        // Import elision: collect all value references from non-import statements.
        // Used to elide unused named imports (e.g. `import {value2} from "./file4"` where value2 is never used).
        val nonImportStmts = statementsWithoutStrict.filter { it !is ImportDeclaration }
        val referencedNames = collectValueReferences(nonImportStmts)

        // Pass 1: collect imports, build binding/rename info
        for (stmt in statementsWithoutStrict) {
            when (stmt) {
                is ImportDeclaration -> {
                    val clause = stmt.importClause
                    val moduleSpecifier = stmt.moduleSpecifier
                    var specStr = (normalizeModuleSpecifier(moduleSpecifier) as? StringLiteralNode)?.text
                        ?: (moduleSpecifier as? StringLiteralNode)?.text ?: ""
                    // When outFile is set (System bundling), resolve relative specifiers to module names
                    if (options.outFile != null && (specStr.startsWith("./") || specStr.startsWith("../"))) {
                        specStr = resolveAmdModuleName(specStr, originalSourceFile.fileName)
                    }

                    if (clause == null) {
                        // Side-effect import: import 'mod' — empty setter with param _N
                        if (specStr !in uniqueModulePaths) {
                            uniqueModulePaths.add(specStr)
                            sideEffectPaths.add(specStr)
                            sideEffectCounter++
                            sideEffectSetterParam[specStr] = "_$sideEffectCounter"
                        }
                    } else {
                        val bindings = clause.namedBindings

                        // Import elision: if none of the imported names are referenced in the module body,
                        // elide the entire import (omit from deps and bindings).
                        val defaultName = clause.name?.text
                        val nsName = (bindings as? NamespaceImport)?.name?.text
                        val namedImports = bindings as? NamedImports
                        val defaultUsed = defaultName != null && defaultName in referencedNames
                        val nsUsed = nsName != null && nsName in referencedNames
                        val namedUsed = namedImports?.elements?.any { el ->
                            !el.isTypeOnly && el.name.text in referencedNames
                        } ?: false
                        if (!defaultUsed && !nsUsed && !namedUsed) continue  // skip this import entirely

                        if (specStr !in uniqueModulePaths) {
                            uniqueModulePaths.add(specStr)
                            pathToTempVars[specStr] = mutableListOf()
                        }

                        // Helper: create a new module-level temp var for this import declaration.
                        // Each import declaration (even from the same path) gets its own temp var.
                        // All vars from the same path share ONE setter (assigned to the same param).
                        fun createModuleTempVar(): String {
                            val tv = generateModuleTempName(moduleSpecifier, moduleNameCounter)
                            if (specStr !in pathToSetterParam) {
                                pathToSetterParam[specStr] = "${tv}_1"
                            }
                            pathToTempVars.getOrPut(specStr) { mutableListOf() }.add(tv)
                            return tv
                        }

                        when {
                            clause.name != null && bindings is NamespaceImport -> {
                                // Combined: import e, * as ns2 from "mod"
                                // Use module-based temp var for default access; do NOT rename namespace import.
                                val defaultLocalName = clause.name.text
                                val tempName = createModuleTempVar()
                                val defaultAccess = PropertyAccessExpression(
                                    expression = syntheticId(tempName),
                                    name = syntheticId("default"),
                                    pos = -1, end = -1,
                                )
                                renameMap[defaultLocalName] = defaultAccess
                                importBindings.add(ImportBinding(specStr, tempName, defaultAccess, defaultLocalName))
                                // ns2 is NOT renamed (TypeScript leaves it unrewritten in this combined form)
                            }
                            bindings is NamespaceImport -> {
                                // import * as ns from "mod" → ns becomes the module object directly
                                val nsName = bindings.name.text
                                // Setter param: nsName_1 (based on alias, not module path)
                                if (specStr !in pathToSetterParam) {
                                    pathToSetterParam[specStr] = "${nsName}_1"
                                }
                                // Track as namespace var (assigned first in setter)
                                pathToNamespaceVars.getOrPut(specStr) { mutableListOf() }.add(nsName)
                                // Temp var: nsName (same as alias) — add to hoisted vars
                                if (!pathToTempVars.getOrPut(specStr) { mutableListOf() }.contains(nsName)) {
                                    pathToTempVars[specStr]!!.add(nsName)
                                }
                                // ns → ns (identity; kept in renameMap for re-export lookup)
                                renameMap[nsName] = syntheticId(nsName)
                                namespaceImportNames.add(nsName)
                                importBindings.add(ImportBinding(specStr, nsName, syntheticId(nsName), nsName))
                            }
                            clause.name != null && bindings is NamedImports -> {
                                // Combined default + named: import d, { a } from "mod"
                                val defaultLocalName = clause.name.text
                                val tempName = createModuleTempVar()
                                val defaultAccess = PropertyAccessExpression(
                                    expression = syntheticId(tempName),
                                    name = syntheticId("default"),
                                    pos = -1, end = -1,
                                )
                                renameMap[defaultLocalName] = defaultAccess
                                importBindings.add(ImportBinding(specStr, tempName, defaultAccess, defaultLocalName))
                                for (element in bindings.elements) {
                                    if (element.isTypeOnly) continue
                                    val importedName = (element.propertyName ?: element.name).text
                                    val localAlias = element.name.text
                                    val access = PropertyAccessExpression(
                                        expression = syntheticId(tempName),
                                        name = syntheticId(importedName),
                                        pos = -1, end = -1,
                                    )
                                    renameMap[localAlias] = access
                                    importBindings.add(ImportBinding(specStr, tempName, access, localAlias))
                                }
                            }
                            clause.name != null && bindings == null -> {
                                // Default import only: import d from "mod" → d = tempName.default
                                val localName = clause.name.text
                                val tempName = createModuleTempVar()
                                val defaultAccess = PropertyAccessExpression(
                                    expression = syntheticId(tempName),
                                    name = syntheticId("default"),
                                    pos = -1, end = -1,
                                )
                                renameMap[localName] = defaultAccess
                                importBindings.add(ImportBinding(specStr, tempName, defaultAccess, localName))
                            }
                            bindings is NamedImports -> {
                                // Named imports only: import { A, B as b } from "mod"
                                // All elements share ONE module temp var (e.g. file2_1)
                                val tempName = createModuleTempVar()
                                for (element in bindings.elements) {
                                    if (element.isTypeOnly) continue
                                    val importedName = (element.propertyName ?: element.name).text
                                    val localAlias = element.name.text
                                    val access = PropertyAccessExpression(
                                        expression = syntheticId(tempName),
                                        name = syntheticId(importedName),
                                        pos = -1, end = -1,
                                    )
                                    renameMap[localAlias] = access
                                    importBindings.add(ImportBinding(specStr, tempName, access, localAlias))
                                }
                            }
                        }
                    }
                }
                is ExportDeclaration -> {
                    if (!stmt.isTypeOnly && stmt.moduleSpecifier != null && stmt.exportClause == null) {
                        // export * from "mod"
                        val specStr = (normalizeModuleSpecifier(stmt.moduleSpecifier) as? StringLiteralNode)?.text
                            ?: (stmt.moduleSpecifier as? StringLiteralNode)?.text ?: ""
                        if (specStr !in uniqueModulePaths) {
                            uniqueModulePaths.add(specStr)
                        }
                        exportStarPaths.add(specStr)
                    } else if (!stmt.isTypeOnly && stmt.moduleSpecifier != null && stmt.exportClause is NamedExports) {
                        // export {a2, b2, c2 as d2} from "mod" — named re-exports from a module
                        val specStr = (normalizeModuleSpecifier(stmt.moduleSpecifier) as? StringLiteralNode)?.text
                            ?: (stmt.moduleSpecifier as? StringLiteralNode)?.text ?: ""
                        if (specStr !in uniqueModulePaths) {
                            uniqueModulePaths.add(specStr)
                            // Ensure there's a setter param for this path
                            val pathBaseName = specStr.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9_]"), "_")
                            pathToSetterParam[specStr] = "${pathBaseName}_1_1"
                            pathToTempVars[specStr] = mutableListOf()
                        }
                        // Collect named re-exports for this path's setter
                        for (spec in stmt.exportClause.elements) {
                            if (spec.isTypeOnly) continue
                            val exportName = spec.name.text
                            val importedName = (spec.propertyName ?: spec.name).text
                            pathToNamedReExports.getOrPut(specStr) { mutableListOf() }.add(exportName to importedName)
                        }
                    }
                }
                is VariableStatement -> {
                    // import alias = require("foo") — transformed to var alias = require("foo")
                    // Detect this and treat it as a dep with a setter (like AMD does)
                    val decls = stmt.declarationList.declarations
                    if (decls.size == 1) {
                        val init = decls[0].initializer
                        val name = extractIdentifierName(decls[0].name)
                        if (name != null && isRequireCall(init)) {
                            val requireArg = (init as CallExpression).arguments.firstOrNull()
                            val specStr = (normalizeModuleSpecifier(requireArg ?: syntheticId("")) as? StringLiteralNode)?.text
                                ?: (requireArg as? StringLiteralNode)?.text ?: ""
                            if (specStr !in uniqueModulePaths) {
                                uniqueModulePaths.add(specStr)
                                val setterParam = "${name}_1"
                                pathToSetterParam[specStr] = setterParam
                                pathToTempVars[specStr] = mutableListOf(name)
                            } else {
                                // Same path already seen — add this name as another binding
                                pathToTempVars.getOrPut(specStr) { mutableListOf() }.add(name)
                            }
                            // Mark as require import (to skip in Pass 2)
                            requireImportVarNames.add(name)
                            importBindings.add(ImportBinding(specStr, name, syntheticId(name), name))
                            // Add to renameMap so export { n2 } resolves to exports_1("n2", n2)
                            renameMap[name] = syntheticId(name)
                            // Track if this require-import is exported (export import X = require("mod"))
                            if (ModifierFlag.Export in stmt.modifiers) {
                                exportedRequireImportNames.add(name)
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        // Collect all top-level var names for hoisting
        // In System format, all top-level vars are hoisted as `var x, y, z;`
        val hoistedVarNames = mutableListOf<String>()
        val hoistedVarNamesSet = mutableSetOf<String>()
        // Function declarations are hoisted as function declarations (not var), so their names
        // must NOT appear in the var list even when a namespace merges with the same name.
        val functionDeclNames = originalSourceFile.statements
            .filterIsInstance<FunctionDeclaration>()
            .mapNotNullTo(mutableSetOf()) { it.name?.text }

        // Import temp vars — in source order (one per import binding, deduped)
        for (binding in importBindings) {
            if (hoistedVarNamesSet.add(binding.tempVarName)) {
                hoistedVarNames.add(binding.tempVarName)
            }
        }

        // Top-level declarations from non-import statements — vars/lets/consts, classes, namespaces, enums, import aliases
        for (stmt in originalSourceFile.statements) {
            when (stmt) {
                is VariableStatement -> {
                    if (ModifierFlag.Declare !in stmt.modifiers) {
                        val isExported = ModifierFlag.Export in stmt.modifiers
                        for (d in stmt.declarationList.declarations) {
                            // Exported destructuring bindings with >1 element or object binding need temp vars;
                            // they are added to hoisted vars in Pass 2 (temp var first, then bound names).
                            val skipForExportedDestructuring = isExported && when (val pat = d.name) {
                                is ObjectBindingPattern -> true
                                is ArrayBindingPattern -> pat.elements.size > 1
                                else -> false
                            }
                            if (skipForExportedDestructuring) continue
                            for (n in collectBoundNames(d.name)) {
                                if (hoistedVarNamesSet.add(n)) hoistedVarNames.add(n)
                            }
                        }
                    }
                }
                is ClassDeclaration -> {
                    if (!hasDeclareModifier(stmt)) {
                        stmt.name?.text?.let { n ->
                            if (hoistedVarNamesSet.add(n)) hoistedVarNames.add(n)
                        }
                    }
                }
                is ModuleDeclaration -> {
                    if (!hasDeclareModifier(stmt) && !isTypeOnlyNamespace(stmt)) {
                        val n = extractIdentifierName(stmt.name)
                            ?: stmt.name.let { flattenDottedNamespaceName(it).firstOrNull() }
                        n?.let { if (it !in functionDeclNames && hoistedVarNamesSet.add(it)) hoistedVarNames.add(it) }
                    }
                }
                is EnumDeclaration -> {
                    if (!hasDeclareModifier(stmt) &&
                        (options.preserveConstEnums || options.isolatedModules || ModifierFlag.Const !in stmt.modifiers)) {
                        val n = stmt.name.text
                        if (hoistedVarNamesSet.add(n)) hoistedVarNames.add(n)
                    }
                }
                is ImportEqualsDeclaration -> {
                    if (!stmt.isTypeOnly && stmt.moduleReference !is ExternalModuleReference) {
                        val ref = stmt.moduleReference
                        val refIsTypeOnly = (ref is QualifiedName && isQualifiedPathTypeOnly(ref)) ||
                            (ref is Identifier && ref.text in topLevelTypeOnlyNames)
                        if (!refIsTypeOnly) {
                            val n = stmt.name.text
                            if (n.isNotEmpty() && hoistedVarNamesSet.add(n)) hoistedVarNames.add(n)
                        }
                    }
                }
                else -> {}
            }
        }
        // Recursively collect all `var` declarations from the module body (excluding function/class bodies).
        // This handles vars inside loop bodies, if-blocks, try-catch, etc. that are still module-scoped.
        collectVarNamesFromStmts(originalSourceFile.statements, hoistedVarNames, hoistedVarNamesSet)

        // Pass 2: build the execute body and function-level hoists
        // Function declarations are hoisted OUTSIDE execute with exports_1("name", fn)
        val functionHoists = mutableListOf<Statement>()      // before the return statement
        val executeStatements = mutableListOf<Statement>()   // inside execute: function()
        // Names of hoisted functions (for detecting re-exports via export {fnName} that should also be hoisted)
        val hoistedFnNames = mutableSetOf<String>()
        // Leading comments from a hoisted var (no-initializer varDecl) that should be
        // prepended to the next statement added to executeStatements (typically the IIFE).
        val pendingLeadingComments = mutableListOf<Comment>()

        // TypeScript uses ONE exportStar helper function (exportStar_1) for ALL export* paths.
        // The function is only generated if there are any export* paths.
        val exportStarFnName = if (exportStarPaths.isNotEmpty()) "exportStar_1" else null

        // Build import binding order map for sorting export re-exports.
        // TypeScript emits re-export calls in import binding order, not ExportDeclaration order.
        val importBindingOrder = buildMap {
            for ((i, binding) in importBindings.withIndex()) {
                getOrPut(binding.localName) { i }
            }
        }
        // Collect re-export statements (from ExportDeclaration) with their binding order for sorting.
        // These are emitted AFTER all non-ExportDeclaration execute statements.
        val pendingReExports = mutableListOf<Pair<Int, Statement>>() // (bindingOrder, stmt)
        // Namespace import re-exports go BEFORE body statements in execute (re-exports of import * as X)
        val preBodyExports = mutableListOf<Statement>()

        for (stmt in statementsWithoutStrict) {
            when (stmt) {
                is ImportDeclaration -> {
                    // Handled via setters, not in body
                }

                is FunctionDeclaration -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val isDefault = ModifierFlag.Default in stmt.modifiers
                    val stripped = stmt.copy(modifiers = stmt.modifiers - ModifierFlag.Export - ModifierFlag.Default)
                    if (isExported) {
                        val exportName = if (isDefault) "default" else (stmt.name?.text ?: "default")
                        val fnName = stmt.name?.text
                        if (fnName != null) {
                            // Named function: hoist outside execute, export with exports_1
                            functionHoists.add(stripped)
                            functionHoists.add(makeSystemExportCall(exportName, syntheticId(fnName)))
                            hoistedFnNames.add(fnName)
                        } else {
                            // Anonymous default function: create default_1 name, hoist
                            val anonName = "default_1"
                            val namedFn = stripped.copy(name = syntheticId(anonName))
                            functionHoists.add(namedFn)
                            functionHoists.add(makeSystemExportCall("default", syntheticId(anonName)))
                        }
                    } else {
                        val fnName = stmt.name?.text
                        if (fnName != null) hoistedFnNames.add(fnName)
                        functionHoists.add(stripped)
                    }
                }

                is ClassDeclaration -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val isDefault = ModifierFlag.Default in stmt.modifiers
                    val stripped = stmt.copy(modifiers = stmt.modifiers - ModifierFlag.Export - ModifierFlag.Default)
                    if (isExported) {
                        val className = stmt.name?.text
                        if (isDefault && className == null) {
                            // export default class {} → default_1 = class {}; exports_1("default", default_1)
                            val anonName = "default_1"
                            if (hoistedVarNamesSet.add(anonName)) hoistedVarNames.add(anonName)
                            executeStatements.add(
                                ExpressionStatement(
                                    expression = BinaryExpression(
                                        left = syntheticId(anonName),
                                        operator = Equals,
                                        right = ClassExpression(
                                            name = null,
                                            typeParameters = null,
                                            heritageClauses = stripped.heritageClauses,
                                            members = stripped.members,
                                            modifiers = emptySet(),
                                            pos = -1, end = -1,
                                        ),
                                        pos = -1, end = -1,
                                    ),
                                    pos = -1, end = -1,
                                )
                            )
                            executeStatements.add(makeSystemExportCall("default", syntheticId(anonName)))
                        } else if (className != null) {
                            // Named class: ClassName = class ClassName { ... }; exports_1(...)
                            val classExpr = ClassExpression(
                                name = syntheticId(className),
                                typeParameters = null,
                                heritageClauses = stripped.heritageClauses,
                                members = stripped.members,
                                modifiers = emptySet(),
                                pos = -1, end = -1,
                            )
                            executeStatements.add(
                                ExpressionStatement(
                                    expression = BinaryExpression(
                                        left = syntheticId(className),
                                        operator = Equals,
                                        right = classExpr,
                                        pos = -1, end = -1,
                                    ),
                                    leadingComments = stmt.leadingComments,
                                    pos = -1, end = -1,
                                )
                            )
                            val exportName = if (isDefault) "default" else className
                            executeStatements.add(makeSystemExportCall(exportName, syntheticId(className)))
                        }
                    } else {
                        // Non-exported class: ClassName = class ClassName { ... }
                        val className = stmt.name?.text
                        if (className != null) {
                            val classExpr = ClassExpression(
                                name = syntheticId(className),
                                typeParameters = null,
                                heritageClauses = stripped.heritageClauses,
                                members = stripped.members,
                                modifiers = emptySet(),
                                pos = -1, end = -1,
                            )
                            executeStatements.add(
                                ExpressionStatement(
                                    expression = BinaryExpression(
                                        left = syntheticId(className),
                                        operator = Equals,
                                        right = classExpr,
                                        pos = -1, end = -1,
                                    ),
                                    leadingComments = stmt.leadingComments,
                                    pos = -1, end = -1,
                                )
                            )
                        } else {
                            executeStatements.add(stripped)
                        }
                    }
                }

                is VariableStatement -> {
                    // Skip require import vars — they're handled via setters
                    val firstDecl = stmt.declarationList.declarations.firstOrNull()
                    val firstDeclName = if (firstDecl != null) extractIdentifierName(firstDecl.name) else null
                    if (stmt.declarationList.declarations.size == 1 && firstDeclName != null &&
                        firstDeclName in requireImportVarNames) {
                        // Skip — this var is a require import, handled as a setter
                    } else {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val stripped = stmt.copy(modifiers = stmt.modifiers - ModifierFlag.Export)
                    if (isExported) {
                        // Convert: export const/let/var x = val → exports_1("x", x = val)
                        for (d in stripped.declarationList.declarations) {
                            val name = extractIdentifierName(d.name)
                            if (name != null && d.initializer != null) {
                                // exports_1("name", name = val)
                                executeStatements.add(
                                    ExpressionStatement(
                                        expression = makeSystemExportInlineAssign(name, d.initializer),
                                        leadingComments = stmt.leadingComments,
                                        pos = -1, end = -1,
                                    )
                                )
                            } else if (name != null) {
                                // No initializer — just a var declaration (already hoisted)
                                // Transfer leading comments to the next executeStatement (the IIFE follows).
                                if (!stmt.leadingComments.isNullOrEmpty()) {
                                    pendingLeadingComments.addAll(stmt.leadingComments)
                                }
                            } else {
                                // Destructuring export — unroll to temp var + exports_1 calls
                                val init = d.initializer
                                when (val pat = d.name) {
                                    is ArrayBindingPattern -> {
                                        val names = pat.elements.filterIsInstance<BindingElement>()
                                            .map { (it.name as? Identifier)?.text }
                                        if (names.all { it != null } && init != null) {
                                            val nameList = names.filterNotNull()
                                            if (nameList.size == 1) {
                                                // Single element: exports_1("y", y = init[0]) — no temp var
                                                val localName = nameList[0]
                                                if (hoistedVarNamesSet.add(localName)) hoistedVarNames.add(localName)
                                                val accessExpr = ElementAccessExpression(
                                                    expression = init,
                                                    argumentExpression = NumericLiteralNode(text = "0", pos = -1, end = -1),
                                                    pos = -1, end = -1,
                                                )
                                                executeStatements.add(ExpressionStatement(
                                                    expression = makeSystemExportInlineAssign(localName, accessExpr),
                                                    leadingComments = stmt.leadingComments,
                                                    pos = -1, end = -1,
                                                ))
                                            } else {
                                                // Multiple elements: _a = init, exports_1("x", x = _a[0]), ...
                                                val tempVar = nextTempVarName()
                                                if (hoistedVarNamesSet.add(tempVar)) hoistedVarNames.add(tempVar)
                                                for (n in nameList) {
                                                    if (hoistedVarNamesSet.add(n)) hoistedVarNames.add(n)
                                                }
                                                val tempId = syntheticId(tempVar)
                                                // Build comma expression: _a = init, exports_1("x", x = _a[0]), ...
                                                var commaExpr: Expression = BinaryExpression(
                                                    left = syntheticId(tempVar),
                                                    operator = Equals,
                                                    right = init,
                                                    pos = -1, end = -1,
                                                )
                                                for ((i, localName) in nameList.withIndex()) {
                                                    val accessExpr = ElementAccessExpression(
                                                        expression = tempId,
                                                        argumentExpression = NumericLiteralNode(text = i.toString(), pos = -1, end = -1),
                                                        pos = -1, end = -1,
                                                    )
                                                    commaExpr = BinaryExpression(
                                                        left = commaExpr,
                                                        operator = SyntaxKind.Comma,
                                                        right = makeSystemExportInlineAssign(localName, accessExpr),
                                                        pos = -1, end = -1,
                                                    )
                                                }
                                                executeStatements.add(ExpressionStatement(
                                                    expression = commaExpr,
                                                    leadingComments = stmt.leadingComments,
                                                    pos = -1, end = -1,
                                                ))
                                            }
                                        } else {
                                            executeStatements.add(stripped)
                                        }
                                    }
                                    is ObjectBindingPattern -> {
                                        val propChains = flattenObjectBindingPropChains(pat)
                                        if (propChains != null && init != null) {
                                            // Single binding: access property directly without a temp var
                                            // (init only evaluated once since we only access one property)
                                            if (propChains.size == 1) {
                                                val (localName, propChain) = propChains[0]
                                                if (hoistedVarNamesSet.add(localName)) hoistedVarNames.add(localName)
                                                val accessExpr = buildPropertyAccessChain(init, propChain)
                                                executeStatements.add(ExpressionStatement(
                                                    expression = makeSystemExportInlineAssign(localName, accessExpr),
                                                    leadingComments = stmt.leadingComments,
                                                    pos = -1, end = -1,
                                                ))
                                            } else {
                                                // Multiple bindings: use a temp var to avoid re-evaluating init.
                                                // Allocate temp var FIRST (before binding names) to preserve hoisted var order.
                                                val tempVar = nextTempVarName()
                                                if (hoistedVarNamesSet.add(tempVar)) hoistedVarNames.add(tempVar)
                                                for ((localName, _) in propChains) {
                                                    if (hoistedVarNamesSet.add(localName)) hoistedVarNames.add(localName)
                                                }
                                                val tempId = syntheticId(tempVar)
                                                // Build comma expression: _a = init, exports_1("z0", z0 = _a.a), ...
                                                var commaExpr: Expression = BinaryExpression(
                                                    left = syntheticId(tempVar),
                                                    operator = Equals,
                                                    right = init,
                                                    pos = -1, end = -1,
                                                )
                                                for ((localName, propChain) in propChains) {
                                                    val accessExpr = buildPropertyAccessChain(tempId, propChain)
                                                    commaExpr = BinaryExpression(
                                                        left = commaExpr,
                                                        operator = SyntaxKind.Comma,
                                                        right = makeSystemExportInlineAssign(localName, accessExpr),
                                                        pos = -1, end = -1,
                                                    )
                                                }
                                                executeStatements.add(ExpressionStatement(
                                                    expression = commaExpr,
                                                    leadingComments = stmt.leadingComments,
                                                    pos = -1, end = -1,
                                                ))
                                            }
                                        } else {
                                            executeStatements.add(stripped)
                                        }
                                    }
                                    else -> {
                                        // Simple identifier or unknown — fall through (already handled above)
                                        executeStatements.add(stripped)
                                    }
                                }
                            }
                        }
                    } else {
                        // Non-exported: x = val (var already hoisted, just assign)
                        for (d in stripped.declarationList.declarations) {
                            val name = extractIdentifierName(d.name)
                            if (name != null && d.initializer != null) {
                                executeStatements.add(
                                    ExpressionStatement(
                                        expression = BinaryExpression(
                                            left = syntheticId(name),
                                            operator = Equals,
                                            right = d.initializer,
                                            pos = -1, end = -1,
                                        ),
                                        leadingComments = stmt.leadingComments,
                                        pos = -1, end = -1,
                                    )
                                )
                            } else if (name == null) {
                                // Destructuring — try to flatten to individual hoisted var assignments
                                val pairs = tryExpandObjectBinding(d)
                                if (pairs != null && pairs.all { (n, _) -> n in hoistedVarNamesSet }) {
                                    for ((localName, valueExpr) in pairs) {
                                        executeStatements.add(ExpressionStatement(
                                            expression = BinaryExpression(
                                                left = syntheticId(localName),
                                                operator = Equals,
                                                right = valueExpr,
                                                pos = -1, end = -1,
                                            ),
                                            leadingComments = stmt.leadingComments,
                                            pos = -1, end = -1,
                                        ))
                                    }
                                } else {
                                    // Complex binding — keep as-is (with `let` since it's block-scoped)
                                    executeStatements.add(stripped)
                                }
                            }
                            // If name != null and no initializer: already hoisted, nothing to emit.
                            // Transfer leading comments to the next statement (typically the IIFE).
                            else if (!stmt.leadingComments.isNullOrEmpty()) {
                                pendingLeadingComments.addAll(stmt.leadingComments)
                            }
                        }
                    }
                    } // end else (not a require import var)
                }

                is ExportAssignment -> {
                    val exprName = (stmt.expression as? Identifier)?.text
                    if (exprName == null || exprName !in pureTypeNames) {
                        if (!stmt.isExportEquals) {
                            // export default expr — use simple call (not inline-assign; no live binding)
                            executeStatements.add(makeSystemExportCall("default", stmt.expression))
                        }
                        // export = X is not normally used in system modules
                    }
                }

                is ExportDeclaration -> {
                    if (stmt.isTypeOnly) {
                        // Type-only: erased
                    } else if (stmt.exportClause is NamedExports && stmt.moduleSpecifier == null) {
                        // export { x, y as z } (local re-exports — no module specifier)
                        for (spec in stmt.exportClause.elements) {
                            if (spec.isTypeOnly) continue
                            val exportName = spec.name.text
                            val localName = (spec.propertyName ?: spec.name).text
                            if (localName !in pureTypeNames) {
                                val importBinding = renameMap[localName]
                                if (localName in exportedNsEnumNames || localName in iifeExportAliases) {
                                    // Namespace/enum local — handled by IIFE arg rewriting, skip
                                } else if (localName in namespaceImportNames) {
                                    // Re-exporting a namespace import (import * as X): goes BEFORE body
                                    preBodyExports.add(makeSystemExportCall(exportName, syntheticId(localName)))
                                } else if (importBinding != null) {
                                    // Re-exporting a named/default import binding: exports_1("name", moduleObj.prop)
                                    // Use simple call, not inline-assign form. Collect with binding order
                                    // so we can sort re-exports by import binding order later.
                                    val order = importBindingOrder[localName] ?: Int.MAX_VALUE
                                    pendingReExports.add(order to makeSystemExportCall(exportName, importBinding))
                                } else if (localName in hoistedFnNames) {
                                    // Re-exporting a hoisted function: hoist the export call too
                                    functionHoists.add(makeSystemExportCall(exportName, syntheticId(localName)))
                                } else if (localName in varsWithInitializers) {
                                    // Re-exporting a local var that has an initializer:
                                    // exports_1("exportName", localName)  (simple call, not inline-assign)
                                    executeStatements.add(makeSystemExportCall(exportName, syntheticId(localName)))
                                }
                                // If localName has no initializer (var x with no value), skip — nothing to export
                            }
                        }
                    } else if (stmt.exportClause is NamedExports && stmt.moduleSpecifier != null) {
                        // export { a2, b2, c2 as d2 } from "mod" — handled in setter, nothing in execute
                    } else if (stmt.moduleSpecifier != null && stmt.exportClause == null) {
                        // export * from "mod" — handled via setter + exportStar function
                        // Nothing to emit in execute directly
                    }
                }

                is ForInStatement -> {
                    // `for (var key in obj)` → hoist `key`, emit `for (key in obj)` (no var)
                    val init = stmt.initializer
                    if (init is VariableDeclarationList && init.flags == VarKeyword &&
                        init.declarations.size == 1) {
                        val name = extractIdentifierName(init.declarations[0].name)
                        if (name != null) {
                            // Replace var decl with just the identifier
                            executeStatements.add(stmt.copy(initializer = syntheticId(name)))
                        } else {
                            executeStatements.add(stmt)
                        }
                    } else {
                        executeStatements.add(stmt)
                    }
                }

                else -> {
                    // EOF NotEmittedStatements (trailing file comments) are dropped in System format.
                    if (stmt is NotEmittedStatement) {
                        pendingLeadingComments.clear()
                    } else {
                        // Prepend any pending leading comments (from a preceding hoisted varDecl)
                        val stmtToAdd: Statement = if (pendingLeadingComments.isNotEmpty() && stmt is ExpressionStatement) {
                            val combined = pendingLeadingComments.toList() + (stmt.leadingComments ?: emptyList())
                            pendingLeadingComments.clear()
                            stmt.copy(leadingComments = combined)
                        } else {
                            pendingLeadingComments.clear()
                            stmt
                        }
                        // Check for namespace/enum IIFE for exported name
                        val iifeName = extractSimpleIifeName(stmtToAdd)
                        if (iifeName != null && iifeName in exportedNsEnumNames) {
                            // Collect all export names for this IIFE (primary + aliases)
                            val allExportNames = iifeExportAliases[iifeName] ?: listOf(iifeName)
                            executeStatements.add(rewriteIifeArgForSystemExport(stmtToAdd as ExpressionStatement, iifeName, allExportNames))
                        } else {
                            executeStatements.add(stmtToAdd)
                        }
                    }
                }
            }
        }

        // Emit pending re-exports sorted by import binding order (to match TypeScript's output order).
        if (pendingReExports.isNotEmpty()) {
            val sorted = pendingReExports.sortedBy { it.first }
            executeStatements.addAll(sorted.map { it.second })
        }

        // Apply import identifier renaming in execute statements and function hoists.
        // For System format, do NOT wrap bare-identifier calls as (0, expr)() — that's CJS-only.
        // preBodyExports (namespace re-exports) come FIRST, then body, then pending re-exports.
        val allExecuteStatements = preBodyExports + executeStatements
        // Strip `var` from any `var` declarations nested inside execute body (e.g. inside loop bodies).
        // Top-level var declarations are already handled above (converted to assignments or hoisted).
        // This handles `for (let x of []) { var v = x; }` → body becomes `{ v = x; }`.
        val strippedVarsExecute = allExecuteStatements.flatMap { stripVarDeclsFromStatement(it, hoistedVarNamesSet) }
        val renamedExecute = if (renameMap.isNotEmpty()) {
            strippedVarsExecute.map { rewriteIdInStatement(it, renameMap, wrapCallsWithZero = false) }
        } else strippedVarsExecute.toList()
        // Rewrite identifiers inside hoisted function bodies (e.g. references to import bindings)
        if (renameMap.isNotEmpty() && functionHoists.isNotEmpty()) {
            val rewritten = functionHoists.map { rewriteIdInStatement(it, renameMap, wrapCallsWithZero = false) }
            functionHoists.clear()
            functionHoists.addAll(rewritten)
        }

        // Apply live binding export substitution: wrap assignments to exported vars in exports_1 calls.
        // Only needed when there are exported vars that could be assigned to after declaration.
        val exportedVarNamesForSubst = localExportNames.filter { it in hoistedVarNamesSet }.toSet()
        val finalExecute = if (exportedVarNamesForSubst.isNotEmpty()) {
            substituteSystemExportAssignments(renamedExecute, exportedVarNamesForSubst)
        } else renamedExecute
        if (exportedVarNamesForSubst.isNotEmpty() && functionHoists.isNotEmpty()) {
            val substituted = substituteSystemExportAssignments(functionHoists, exportedVarNamesForSubst)
            functionHoists.clear()
            functionHoists.addAll(substituted)
        }

        // Build setters array — one setter per unique module path (in order of first appearance)
        val setterFunctions = mutableListOf<Expression>()
        for (path in uniqueModulePaths) {
            if (path in sideEffectPaths && path !in pathToSetterParam && path !in pathToNamedReExports) {
                // Pure side-effect import: empty setter with param _N
                val seParam = sideEffectSetterParam[path] ?: "_1"
                setterFunctions.add(
                    FunctionExpression(
                        parameters = listOf(Parameter(name = syntheticId(seParam), pos = -1, end = -1)),
                        body = Block(statements = emptyList(), multiLine = true, pos = -1, end = -1),
                        pos = -1, end = -1,
                    )
                )
            } else if (path in exportStarPaths && path !in pathToSetterParam && path !in pathToNamespaceVars && path !in pathToNamedReExports) {
                // export * from "path" only (no other imports from this path)
                // Setter param uses module path basename: file7_1_1
                val pathBaseName = path.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9_]"), "_")
                val setterParam = "${pathBaseName}_1_1"
                val setterBody = listOf(
                    ExpressionStatement(
                        expression = CallExpression(
                            expression = syntheticId(exportStarFnName ?: "exportStar_1"),
                            arguments = listOf(syntheticId(setterParam)),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    )
                )
                setterFunctions.add(
                    FunctionExpression(
                        parameters = listOf(Parameter(name = syntheticId(setterParam), pos = -1, end = -1)),
                        body = Block(statements = setterBody, multiLine = true, pos = -1, end = -1),
                        pos = -1, end = -1,
                    )
                )
            } else {
                val setterParam = pathToSetterParam[path] ?: continue
                val nsVars = pathToNamespaceVars[path] ?: emptyList()
                val setterBodyStmts = mutableListOf<Statement>()

                // 1. Assign namespace import vars FIRST: nsVar = setterParam
                for (nv in nsVars) {
                    setterBodyStmts.add(
                        ExpressionStatement(
                            expression = BinaryExpression(
                                left = syntheticId(nv),
                                operator = Equals,
                                right = syntheticId(setterParam),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    )
                }

                // 2. If this path is an export*, call exportStar function AFTER namespace vars
                if (path in exportStarPaths && exportStarFnName != null) {
                    setterBodyStmts.add(
                        ExpressionStatement(
                            expression = CallExpression(
                                expression = syntheticId(exportStarFnName),
                                arguments = listOf(syntheticId(setterParam)),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    )
                }

                // 3. Assign all remaining temp vars AFTER exportStar
                // This includes: module-level temp var (for named/default imports) and require import vars.
                // Skip any that were already assigned as namespace vars.
                val allTempVars = pathToTempVars[path] ?: emptyList<String>()
                for (tv in allTempVars) {
                    if (tv !in nsVars) {
                        setterBodyStmts.add(
                            ExpressionStatement(
                                expression = BinaryExpression(
                                    left = syntheticId(tv),
                                    operator = Equals,
                                    right = syntheticId(setterParam),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            )
                        )
                    }
                    // Also export require-imports
                    if (tv in exportedRequireImportNames) {
                        setterBodyStmts.add(makeSystemExportCall(tv, syntheticId(setterParam)))
                    }
                }

                // 5. Named re-exports from this module: export { a2, b2, c2 as d2 } from "mod"
                // Emit as: exports_1({ "a2": param["a2"], "b2": param["b2"], "d2": param["c2"] })
                val namedReExports = pathToNamedReExports[path]
                if (!namedReExports.isNullOrEmpty()) {
                    val objProps = namedReExports.map { (exportName, importedName) ->
                        PropertyAssignment(
                            name = StringLiteralNode(text = exportName, pos = -1, end = -1),
                            initializer = ElementAccessExpression(
                                expression = syntheticId(setterParam),
                                argumentExpression = StringLiteralNode(text = importedName, pos = -1, end = -1),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    }
                    setterBodyStmts.add(
                        ExpressionStatement(
                            expression = CallExpression(
                                expression = syntheticId("exports_1"),
                                arguments = listOf(
                                    ObjectLiteralExpression(
                                        properties = objProps,
                                        multiLine = true,
                                        pos = -1, end = -1,
                                    )
                                ),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    )
                }

                setterFunctions.add(
                    FunctionExpression(
                        parameters = listOf(Parameter(name = syntheticId(setterParam), pos = -1, end = -1)),
                        body = Block(statements = setterBodyStmts, multiLine = true, pos = -1, end = -1),
                        pos = -1, end = -1,
                    )
                )
            }
        }

        // Build the outer function body
        val outerBody = mutableListOf<Statement>()

        // "use strict"
        outerBody.add(ExpressionStatement(expression = StringLiteralNode(text = "use strict"), pos = -1, end = -1))

        // var declarations for all hoisted names (temp vars + top-level vars)
        if (hoistedVarNames.isNotEmpty()) {
            outerBody.add(
                VariableStatement(
                    declarationList = VariableDeclarationList(
                        declarations = hoistedVarNames.map { name ->
                            VariableDeclaration(name = syntheticId(name), pos = -1, end = -1)
                        },
                        flags = VarKeyword,
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                )
            )
        }

        // var __moduleName = context_1 && context_1.id;
        outerBody.add(
            VariableStatement(
                declarationList = VariableDeclarationList(
                    declarations = listOf(
                        VariableDeclaration(
                            name = syntheticId("__moduleName"),
                            initializer = BinaryExpression(
                                left = syntheticId("context_1"),
                                operator = AmpersandAmpersand,
                                right = PropertyAccessExpression(
                                    expression = syntheticId("context_1"),
                                    name = syntheticId("id"),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    ),
                    flags = VarKeyword,
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            )
        )

        // Function hoists (function declarations + their exports_1 calls) come BEFORE exportedNames_1 and exportStar.
        outerBody.addAll(functionHoists)

        // exportedNames_1 var — needed when there's both export* and local named exports.
        // It maps each explicitly-named export to `true` so exportStar can skip them.
        val needsExportedNames = exportStarPaths.isNotEmpty() && localExportNames.isNotEmpty()
        if (needsExportedNames) {
            outerBody.add(
                VariableStatement(
                    declarationList = VariableDeclarationList(
                        declarations = listOf(
                            VariableDeclaration(
                                name = syntheticId("exportedNames_1"),
                                initializer = ObjectLiteralExpression(
                                    properties = localExportNames.map { exportName ->
                                        PropertyAssignment(
                                            name = StringLiteralNode(text = exportName, pos = -1, end = -1),
                                            initializer = syntheticId("true"),
                                            pos = -1, end = -1,
                                        )
                                    },
                                    multiLine = true,
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            )
                        ),
                        flags = VarKeyword,
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                )
            )
        }

        // exportStar helper function (ONE shared function for all export* paths, if any)
        if (exportStarFnName != null) {
            // function exportStar_1(m) { var exports = {}; for (var n in m) {
            //   if (n !== "default" [&& !exportedNames_1.hasOwnProperty(n)]) exports[n] = m[n];
            // } exports_1(exports); }
            outerBody.add(buildExportStarHelper(exportStarFnName, if (needsExportedNames) "exportedNames_1" else null))
        }

        // Return object with setters and execute
        val settersArray = ArrayLiteralExpression(
            elements = setterFunctions,
            multiLine = setterFunctions.isNotEmpty(),
            pos = -1, end = -1,
        )

        val executeFn = FunctionExpression(
            parameters = emptyList(),
            body = Block(
                statements = finalExecute,
                multiLine = true,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        val returnObj = ReturnStatement(
            expression = ObjectLiteralExpression(
                properties = listOf(
                    PropertyAssignment(
                        name = syntheticId("setters"),
                        initializer = settersArray,
                        pos = -1, end = -1,
                    ),
                    PropertyAssignment(
                        name = syntheticId("execute"),
                        initializer = executeFn,
                        pos = -1, end = -1,
                    ),
                ),
                multiLine = true,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        outerBody.add(returnObj)

        // Build dep array
        val depArray = ArrayLiteralExpression(
            elements = uniqueModulePaths.map { StringLiteralNode(text = it, pos = -1, end = -1) },
            pos = -1, end = -1,
        )

        // Build outer function: function(exports_1, context_1) { ... }
        val outerFn = FunctionExpression(
            parameters = listOf(
                Parameter(name = syntheticId("exports_1"), pos = -1, end = -1),
                Parameter(name = syntheticId("context_1"), pos = -1, end = -1),
            ),
            body = Block(
                statements = outerBody,
                multiLine = true,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        // System.register([deps], function(exports_1, context_1) { ... });
        val systemRegisterCall = ExpressionStatement(
            expression = CallExpression(
                expression = PropertyAccessExpression(
                    expression = syntheticId("System"),
                    name = syntheticId("register"),
                    pos = -1, end = -1,
                ),
                arguments = buildList {
                    // Module name: explicit <amd-module name='n'/> or derived from file for outFile
                    val systemModuleName = amdDirectives.moduleName ?: if (options.outFile != null) {
                        originalSourceFile.fileName
                            .removePrefix("./")
                            .substringBeforeLast('.')
                    } else null
                    if (systemModuleName != null) {
                        add(StringLiteralNode(text = systemModuleName, pos = -1, end = -1))
                    }
                    add(depArray)
                    add(outerFn)
                },
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

        return listOf(systemRegisterCall)
    }

    /**
     * Builds: `exports_1("name", value)` call expression
     */
    private fun makeSystemExportCall(name: String, value: Expression): Statement =
        ExpressionStatement(
            expression = CallExpression(
                expression = syntheticId("exports_1"),
                arguments = listOf(StringLiteralNode(text = name, pos = -1, end = -1), value),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

    /**
     * Builds: `exports_1("name", name = value)` expression (for inline export+assign)
     */
    private fun makeSystemExportInlineAssign(name: String, value: Expression): Expression =
        CallExpression(
            expression = syntheticId("exports_1"),
            arguments = listOf(
                StringLiteralNode(text = name, pos = -1, end = -1),
                BinaryExpression(
                    left = syntheticId(name),
                    operator = Equals,
                    right = value,
                    pos = -1, end = -1,
                ),
            ),
            pos = -1, end = -1,
        )

    /**
     * Rewrites `N || (N = {})` IIFE arg to `N || (exports_1("N", N = {}))` for System export.
     * When [allExportNames] has multiple entries (e.g. ["ns", "FooBar"]), the exports are chained:
     *   N || (exports_1("FooBar", exports_1("ns", N = {})))
     * The PRIMARY name (with the assignment) is innermost; aliases wrap around it.
     */
    private fun rewriteIifeArgForSystemExport(
        stmt: ExpressionStatement,
        name: String,
        allExportNames: List<String> = listOf(name),
    ): ExpressionStatement {
        val call = stmt.expression as CallExpression
        // Build the innermost assign: name = {}
        val assign = BinaryExpression(
            left = syntheticId(name),
            operator = Equals,
            right = ObjectLiteralExpression(properties = emptyList(), pos = -1, end = -1),
            pos = -1, end = -1,
        )
        // The primary export name is the first in allExportNames that equals `name` (or just use `name`).
        // Build chained exports_1 calls: innermost has `name = {}`, outer ones pass through the value.
        // Order: inner = primary name (with assignment), outer = aliases
        // e.g. allExportNames = ["ns", "FooBar"] → exports_1("FooBar", exports_1("ns", ns = {}))
        val primaryName = name
        val aliasNames = allExportNames.filter { it != primaryName }
        // Build from inside out: start with the primary exports_1 call
        var innerCall: Expression = CallExpression(
            expression = syntheticId("exports_1"),
            arguments = listOf(StringLiteralNode(text = primaryName, pos = -1, end = -1), assign),
            pos = -1, end = -1,
        )
        // Wrap each alias around it
        for (alias in aliasNames) {
            innerCall = CallExpression(
                expression = syntheticId("exports_1"),
                arguments = listOf(StringLiteralNode(text = alias, pos = -1, end = -1), innerCall),
                pos = -1, end = -1,
            )
        }
        val newArg = BinaryExpression(
            left = syntheticId(name),
            operator = BarBar,
            right = ParenthesizedExpression(expression = innerCall, pos = -1, end = -1),
            pos = -1, end = -1,
        )
        return stmt.copy(expression = call.copy(arguments = listOf(newArg)))
    }

    /**
     * Flattens an [ObjectBindingPattern] into a list of (localName, propChain) pairs for System module export.
     * Returns null if the pattern can't be expanded (rest elements, initializers, computed props, etc.).
     * propChain is the list of property names to access from the root (e.g. ["b", "c"] for `_a.b.c`).
     */
    private fun flattenObjectBindingPropChains(
        pat: ObjectBindingPattern,
        prefix: List<String> = emptyList(),
        result: MutableList<Pair<String, List<String>>> = mutableListOf(),
    ): List<Pair<String, List<String>>>? {
        for (elem in pat.elements) {
            if (elem.dotDotDotToken || elem.initializer != null) return null
            val propName = when {
                elem.propertyName != null -> when (val pn = elem.propertyName) {
                    is Identifier -> pn.text
                    is StringLiteralNode -> pn.text
                    else -> return null
                }
                elem.name is Identifier -> elem.name.text
                else -> return null
            }
            val propChain = prefix + propName
            when (val name = elem.name) {
                is Identifier -> result.add(name.text to propChain)
                is ObjectBindingPattern -> flattenObjectBindingPropChains(name, propChain, result) ?: return null
                else -> return null
            }
        }
        return result
    }

    /** Builds `base.prop1.prop2...` property access chain. */
    private fun buildPropertyAccessChain(base: Expression, chain: List<String>): Expression {
        var expr = base
        for (prop in chain) {
            expr = PropertyAccessExpression(
                expression = expr,
                name = Identifier(text = prop, pos = -1, end = -1),
                pos = -1, end = -1,
            )
        }
        return expr
    }

    /**
     * Applies System module live binding substitution to a list of statements:
     * assignments to exported variables are wrapped in `exports_1("name", assignment)`.
     * Handles: `x = val` → `exports_1("x", x = val)`, `++x`/`--x` → `exports_1("x", ++x)`,
     * `x++`/`x--` → `exports_1("x", (x++, x))`, compound assignments `x += val` → `exports_1("x", x += val)`.
     * Applied to ExpressionStatement, ForStatement init/incrementor, and function bodies.
     */
    private fun substituteSystemExportAssignments(
        stmts: List<Statement>,
        exported: Set<String>,
    ): List<Statement> = stmts.map { substituteSystemInStmt(it, exported) }

    private fun substituteSystemInStmt(stmt: Statement, exported: Set<String>): Statement = when (stmt) {
        is ExpressionStatement -> stmt.copy(expression = substituteSystemInExpr(stmt.expression, exported))
        is ForStatement -> {
            val init = stmt.initializer
            val newInit = if (init is Expression) substituteSystemInExpr(init, exported) else init
            stmt.copy(
                initializer = newInit,
                incrementor = stmt.incrementor?.let { substituteSystemInExpr(it, exported) },
                statement = substituteSystemInStmt(stmt.statement, exported),
            )
        }
        is Block -> stmt.copy(statements = stmt.statements.map { substituteSystemInStmt(it, exported) })
        is FunctionDeclaration -> stmt.copy(
            body = stmt.body?.let { b -> b.copy(statements = b.statements.map { substituteSystemInStmt(it, exported) }) }
        )
        is IfStatement -> stmt.copy(
            thenStatement = substituteSystemInStmt(stmt.thenStatement, exported),
            elseStatement = stmt.elseStatement?.let { substituteSystemInStmt(it, exported) },
        )
        is WhileStatement -> stmt.copy(statement = substituteSystemInStmt(stmt.statement, exported))
        is DoStatement -> stmt.copy(statement = substituteSystemInStmt(stmt.statement, exported))
        else -> stmt
    }

    private fun substituteSystemInExpr(expr: Expression, exported: Set<String>): Expression = when (expr) {
        is BinaryExpression -> {
            val lhsIdent = if (isAssignmentOperator(expr.operator)) expr.left as? Identifier else null
            val exportName = lhsIdent?.text?.takeIf { it in exported }
            if (exportName != null) {
                val newExpr = expr.copy(right = substituteSystemInExpr(expr.right, exported))
                CallExpression(
                    expression = syntheticId("exports_1"),
                    arguments = listOf(StringLiteralNode(text = exportName, pos = -1, end = -1), newExpr),
                    pos = -1, end = -1,
                )
            } else {
                expr.copy(
                    left = substituteSystemInExpr(expr.left, exported),
                    right = substituteSystemInExpr(expr.right, exported),
                )
            }
        }
        is PrefixUnaryExpression -> {
            val isIncrDecr = expr.operator == SyntaxKind.PlusPlus || expr.operator == SyntaxKind.MinusMinus
            val exportName = if (isIncrDecr) (expr.operand as? Identifier)?.text?.takeIf { it in exported } else null
            if (exportName != null) {
                // exports_1("x", ++x) or exports_1("x", --x)
                CallExpression(
                    expression = syntheticId("exports_1"),
                    arguments = listOf(StringLiteralNode(text = exportName, pos = -1, end = -1), expr),
                    pos = -1, end = -1,
                )
            } else {
                expr
            }
        }
        is PostfixUnaryExpression -> {
            val isIncrDecr = expr.operator == SyntaxKind.PlusPlus || expr.operator == SyntaxKind.MinusMinus
            val exportName = if (isIncrDecr) (expr.operand as? Identifier)?.text?.takeIf { it in exported } else null
            if (exportName != null) {
                // exports_1("x", (x++, x)) or exports_1("x", (x--, x))
                val commaExpr = BinaryExpression(
                    left = expr,
                    operator = SyntaxKind.Comma,
                    right = syntheticId(exportName),
                    pos = -1, end = -1,
                )
                CallExpression(
                    expression = syntheticId("exports_1"),
                    arguments = listOf(
                        StringLiteralNode(text = exportName, pos = -1, end = -1),
                        ParenthesizedExpression(expression = commaExpr, pos = -1, end = -1),
                    ),
                    pos = -1, end = -1,
                )
            } else {
                expr
            }
        }
        else -> expr
    }

    /**
     * Builds the exportStar helper function:
     * function exportStar_N(m) { var exports = {}; for (var n in m) {
     *   if (n !== "default" [&& !exportedNames_1.hasOwnProperty(n)]) exports[n] = m[n];
     * } exports_1(exports); }
     * @param exportedNamesVar if non-null, the helper excludes names that are in this exclusion object
     */
    private fun buildExportStarHelper(fnName: String, exportedNamesVar: String? = null): Statement {
        // var exports = {}
        val exportsDecl = VariableStatement(
            declarationList = VariableDeclarationList(
                declarations = listOf(
                    VariableDeclaration(
                        name = syntheticId("exports"),
                        initializer = ObjectLiteralExpression(properties = emptyList(), pos = -1, end = -1),
                        pos = -1, end = -1,
                    )
                ),
                flags = VarKeyword,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
        // if (n !== "default") exports[n] = m[n];
        val ifBody = ExpressionStatement(
            expression = BinaryExpression(
                left = ElementAccessExpression(
                    expression = syntheticId("exports"),
                    argumentExpression = syntheticId("n"),
                    pos = -1, end = -1,
                ),
                operator = Equals,
                right = ElementAccessExpression(
                    expression = syntheticId("m"),
                    argumentExpression = syntheticId("n"),
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
        // Base condition: n !== "default"
        val defaultCheck: Expression = BinaryExpression(
            left = syntheticId("n"),
            operator = ExclamationEqualsEquals,
            right = StringLiteralNode(text = "default", pos = -1, end = -1),
            pos = -1, end = -1,
        )
        // If exportedNamesVar is set, also add: !exportedNames_1.hasOwnProperty(n)
        val ifCondition: Expression = if (exportedNamesVar != null) {
            BinaryExpression(
                left = defaultCheck,
                operator = AmpersandAmpersand,
                right = PrefixUnaryExpression(
                    operator = Exclamation,
                    operand = CallExpression(
                        expression = PropertyAccessExpression(
                            expression = syntheticId(exportedNamesVar),
                            name = syntheticId("hasOwnProperty"),
                            pos = -1, end = -1,
                        ),
                        arguments = listOf(syntheticId("n")),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            )
        } else {
            defaultCheck
        }
        val ifStmt = IfStatement(
            expression = ifCondition,
            thenStatement = ifBody,
            pos = -1, end = -1,
        )
        // for (var n in m) { ... }
        val forIn = ForInStatement(
            initializer = VariableDeclarationList(
                declarations = listOf(VariableDeclaration(name = syntheticId("n"), pos = -1, end = -1)),
                flags = VarKeyword,
                pos = -1, end = -1,
            ),
            expression = syntheticId("m"),
            statement = Block(statements = listOf(ifStmt), multiLine = true, pos = -1, end = -1),
            pos = -1, end = -1,
        )
        // exports_1(exports);
        val callExports1 = ExpressionStatement(
            expression = CallExpression(
                expression = syntheticId("exports_1"),
                arguments = listOf(syntheticId("exports")),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
        return FunctionDeclaration(
            name = syntheticId(fnName),
            parameters = listOf(Parameter(name = syntheticId("m"), pos = -1, end = -1)),
            body = Block(
                statements = listOf(exportsDecl, forIn, callExports1),
                multiLine = true,
                pos = -1, end = -1,
            ),
            modifiers = emptySet(),
            typeParameters = null,
            type = null,
            pos = -1, end = -1,
        )
    }

    /**
     * If [stmt] is a namespace/enum IIFE with arg `N || (N = {})`, returns `N`. Otherwise null.
     * Used to detect top-level namespace/enum IIFEs that need CJS export rewriting.
     */
    private fun extractSimpleIifeName(stmt: Statement): String? {
        if (stmt !is ExpressionStatement) return null
        val call = stmt.expression as? CallExpression ?: return null
        val parenFn = call.expression as? ParenthesizedExpression ?: return null
        if (parenFn.expression !is FunctionExpression) return null
        val fn = parenFn.expression
        if (fn.parameters.size != 1) return null
        val paramName = (fn.parameters[0].name as? Identifier)?.text ?: return null
        if (call.arguments.size != 1) return null
        val arg = call.arguments[0]
        // Simple form: N || (N = {})
        if (arg !is BinaryExpression || arg.operator != BarBar) return null
        val leftId = arg.left as? Identifier ?: return null
        if (leftId.text != paramName) return null
        return paramName
    }

    /**
     * Rewrites `N || (N = {})` IIFE arg to `N || (exports.N = N = {})` for CJS export.
     */
    private fun rewriteIifeArgForCjsExport(stmt: ExpressionStatement, name: String): ExpressionStatement {
        val call = stmt.expression as CallExpression
        val nsId = syntheticId(name)
        val exportsProp = PropertyAccessExpression(
            expression = syntheticId("exports"),
            name = Identifier(text = name, pos = -1, end = -1),
            pos = -1, end = -1,
        )
        val newArg = BinaryExpression(
            left = nsId,
            operator = BarBar,
            right = ParenthesizedExpression(
                expression = BinaryExpression(
                    left = exportsProp,
                    operator = Equals,
                    right = BinaryExpression(
                        left = syntheticId(name),
                        operator = Equals,
                        right = ObjectLiteralExpression(properties = emptyList(), pos = -1, end = -1),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
        return stmt.copy(expression = call.copy(arguments = listOf(newArg)))
    }

    private fun makeEsModulePreamble(): Statement {
        return ExpressionStatement(
            expression = CallExpression(
                expression = PropertyAccessExpression(
                    expression = syntheticId("Object"),
                    name = syntheticId("defineProperty"),
                    pos = -1, end = -1,
                ),
                arguments = listOf(
                    syntheticId("exports"),
                    StringLiteralNode(text = "__esModule", pos = -1, end = -1),
                    ObjectLiteralExpression(
                        properties = listOf(
                            PropertyAssignment(
                                name = syntheticId("value"),
                                initializer = syntheticId("true"),
                                pos = -1, end = -1,
                            )
                        ),
                        multiLine = false,
                        pos = -1, end = -1,
                    ),
                ),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    /**
     * Returns the class name if stmt is `exports.default = ClassName`, else null.
     * Used to identify default-export assignments for static-init ordering.
     */
    private fun extractExportsDefaultTarget(stmt: Statement): String? {
        val expr = (stmt as? ExpressionStatement)?.expression as? BinaryExpression ?: return null
        if (expr.operator != Equals) return null
        val left = expr.left as? PropertyAccessExpression ?: return null
        if ((left.expression as? Identifier)?.text != "exports") return null
        if (left.name.text != "default") return null
        return (expr.right as? Identifier)?.text
    }

    /**
     * Returns true if stmt is a static property assignment `className.prop = ...`
     * (i.e. a trailing static initializer produced for an `export default class`).
     */
    private fun isClassStaticInit(stmt: Statement, className: String): Boolean {
        val expr = (stmt as? ExpressionStatement)?.expression as? BinaryExpression ?: return false
        if (expr.operator != Equals) return false
        return when (val left = expr.left) {
            is PropertyAccessExpression -> (left.expression as? Identifier)?.text == className
            is ElementAccessExpression -> (left.expression as? Identifier)?.text == className
            else -> false
        }
    }

    private fun makeExportAssignment(name: String, value: Expression? = null, leadingComments: List<Comment>? = null, pos: Int = -1): Statement {
        return ExpressionStatement(
            expression = BinaryExpression(
                left = PropertyAccessExpression(
                    expression = syntheticId("exports"),
                    name = syntheticId(name),
                    pos = -1, end = -1,
                ),
                operator = Equals,
                right = value ?: syntheticId(name),
                pos = -1, end = -1,
            ),
            pos = pos, end = -1,
            leadingComments = leadingComments,
        )
    }

    private fun makeExportsProperty(name: String): Statement {
        // Emits: exports.name = name;
        return makeExportAssignment(name, syntheticId(name))
    }

    /** Emits: `var _a;` (an uninitialized var declaration for a temp variable). */
    private fun makeHoistedVar(name: String): Statement =
        VariableStatement(
            declarationList = VariableDeclarationList(
                declarations = listOf(VariableDeclaration(name = syntheticId(name), pos = -1, end = -1)),
                flags = VarKeyword,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )

    // TypeScript normalizes module specifier paths to double quotes in require() calls.
    private fun normalizeModuleSpecifier(spec: Expression): Expression =
        if (spec is StringLiteralNode) spec.copy(singleQuote = false) else spec

    private fun makeRequireConst(
        name: String,
        moduleSpecifier: Expression,
        comments: List<Comment>? = null,
        useVar: Boolean = false, // TypeScript uses var for re-export requires, const for regular imports
    ): Statement {
        val normalizedSpecifier = normalizeModuleSpecifier(moduleSpecifier)
        return VariableStatement(
            declarationList = VariableDeclarationList(
                declarations = listOf(
                    VariableDeclaration(
                        name = Identifier(text = name, pos = -1, end = -1),
                        initializer = CallExpression(
                            expression = syntheticId("require"),
                            arguments = listOf(normalizedSpecifier),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    )
                ),
                flags = if (useVar || options.effectiveTarget < ScriptTarget.ES2015) VarKeyword else ConstKeyword,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
            leadingComments = comments,
        )
    }

    /**
     * Creates `const name = helperFn(require("specifier"))` for `__importStar`/`__importDefault`.
     */
    private fun makeImportHelperConst(
        name: String,
        helperName: String,
        moduleSpecifier: Expression,
        comments: List<Comment>? = null
    ): Statement {
        val normalizedSpecifier = normalizeModuleSpecifier(moduleSpecifier)
        return VariableStatement(
            declarationList = VariableDeclarationList(
                declarations = listOf(
                    VariableDeclaration(
                        name = Identifier(text = name, pos = -1, end = -1),
                        initializer = CallExpression(
                            expression = syntheticId(helperName),
                            arguments = listOf(
                                CallExpression(
                                    expression = syntheticId("require"),
                                    arguments = listOf(normalizedSpecifier),
                                    pos = -1, end = -1,
                                )
                            ),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    )
                ),
                flags = if (options.effectiveTarget < ScriptTarget.ES2015) VarKeyword else ConstKeyword,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
            leadingComments = comments,
        )
    }

    /**
     * Creates `Object.defineProperty(exports, "name", { enumerable: true, get: function() { return src.prop; } })`
     * for re-export. If [importedProp] is null, get returns the whole [sourceName].
     */
    private fun makeReExportGetter(exportName: String, sourceName: String, importedProp: String?, useImportDefault: Boolean = false): Statement {
        val returnExpr: Expression = when {
            importedProp != null && useImportDefault -> PropertyAccessExpression(
                expression = CallExpression(
                    expression = syntheticId("__importDefault"),
                    arguments = listOf(syntheticId(sourceName)),
                    pos = -1, end = -1,
                ),
                name = syntheticId(importedProp),
                pos = -1, end = -1,
            )
            importedProp != null -> PropertyAccessExpression(
                expression = syntheticId(sourceName),
                name = syntheticId(importedProp),
                pos = -1, end = -1,
            )
            else -> syntheticId(sourceName)
        }
        return ExpressionStatement(
            expression = CallExpression(
                expression = PropertyAccessExpression(
                    expression = syntheticId("Object"),
                    name = syntheticId("defineProperty"),
                    pos = -1, end = -1,
                ),
                arguments = listOf(
                    syntheticId("exports"),
                    StringLiteralNode(text = exportName, pos = -1, end = -1),
                    ObjectLiteralExpression(
                        properties = listOf(
                            PropertyAssignment(
                                name = syntheticId("enumerable"),
                                initializer = syntheticId("true"),
                                pos = -1, end = -1,
                            ),
                            PropertyAssignment(
                                name = syntheticId("get"),
                                initializer = FunctionExpression(
                                    parameters = emptyList(),
                                    body = Block(
                                        statements = listOf(
                                            ReturnStatement(expression = returnExpr, pos = -1, end = -1)
                                        ),
                                        multiLine = false,
                                        pos = -1, end = -1,
                                    ),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            ),
                        ),
                        pos = -1, end = -1,
                    ),
                ),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    /** Returns true if [expr] is a dynamic `import(spec)` call. */
    private fun isDynamicImportCall(expr: Expression): Boolean =
        expr is CallExpression &&
                (expr.expression as? Identifier)?.text == "import" &&
                expr.arguments.size == 1

    /**
     * Builds the CJS replacement for `import(spec)`:
     * - String literal: `Promise.resolve().then(() => __importStar(require(spec)))`
     * - Other:          `Promise.resolve(`${spec}`).then(s => __importStar(require(s)))`
     */
    private fun buildCjsDynamicImport(spec: Expression): Expression {
        fun importStarCall(arg: Expression) = CallExpression(
            expression = syntheticId("__importStar"),
            arguments = listOf(CallExpression(expression = syntheticId("require"), arguments = listOf(arg), pos = -1, end = -1)),
            pos = -1, end = -1,
        )
        fun promiseResolve(args: List<Expression>) = CallExpression(
            expression = PropertyAccessExpression(
                expression = syntheticId("Promise"),
                name = Identifier("resolve", pos = -1, end = -1),
                pos = -1, end = -1,
            ),
            arguments = args,
            pos = -1, end = -1,
        )
        return if (spec is StringLiteralNode) {
            // Promise.resolve().then(() => __importStar(require('./path')))
            val arrow = ArrowFunction(
                parameters = emptyList(),
                body = importStarCall(spec),
                pos = -1, end = -1,
            )
            CallExpression(
                expression = PropertyAccessExpression(
                    expression = promiseResolve(emptyList()),
                    name = Identifier("then", pos = -1, end = -1),
                    pos = -1, end = -1,
                ),
                arguments = listOf(arrow),
                pos = -1, end = -1,
            )
        } else {
            // Promise.resolve(`${spec}`).then(s => __importStar(require(s)))
            val templateExpr = TemplateExpression(
                head = StringLiteralNode(text = ""),
                templateSpans = listOf(TemplateSpan(expression = spec, literal = StringLiteralNode(text = ""), pos = -1, end = -1)),
                pos = -1, end = -1,
            )
            val sParam = Parameter(name = Identifier("s", pos = -1, end = -1), pos = -1, end = -1)
            val arrow = ArrowFunction(
                parameters = listOf(sParam),
                body = importStarCall(syntheticId("s")),
                hasParenthesizedParameters = false,
                pos = -1, end = -1,
            )
            CallExpression(
                expression = PropertyAccessExpression(
                    expression = promiseResolve(listOf(templateExpr)),
                    name = Identifier("then", pos = -1, end = -1),
                    pos = -1, end = -1,
                ),
                arguments = listOf(arrow),
                pos = -1, end = -1,
            )
        }
    }

    /**
     * Recursively rewrites dynamic `import(spec)` calls in [expr] to CJS Promise.resolve form.
     * Sets `needImportStar[0] = true` when any dynamic import is found.
     */
    private fun rewriteCjsDynExpr(expr: Expression, needImportStar: BooleanArray): Expression {
        if (isDynamicImportCall(expr)) {
            needImportStar[0] = true
            val spec = rewriteCjsDynExpr((expr as CallExpression).arguments[0], needImportStar)
            return buildCjsDynamicImport(spec)
        }
        return when (expr) {
            is CallExpression -> expr.copy(
                expression = rewriteCjsDynExpr(expr.expression, needImportStar),
                arguments = expr.arguments.map { rewriteCjsDynExpr(it, needImportStar) }
            )
            is PropertyAccessExpression -> expr.copy(expression = rewriteCjsDynExpr(expr.expression, needImportStar))
            is ElementAccessExpression -> expr.copy(
                expression = rewriteCjsDynExpr(expr.expression, needImportStar),
                argumentExpression = rewriteCjsDynExpr(expr.argumentExpression, needImportStar)
            )
            is BinaryExpression -> expr.copy(
                left = rewriteCjsDynExpr(expr.left, needImportStar),
                right = rewriteCjsDynExpr(expr.right, needImportStar)
            )
            is ConditionalExpression -> expr.copy(
                condition = rewriteCjsDynExpr(expr.condition, needImportStar),
                whenTrue = rewriteCjsDynExpr(expr.whenTrue, needImportStar),
                whenFalse = rewriteCjsDynExpr(expr.whenFalse, needImportStar)
            )
            is AwaitExpression -> expr.copy(expression = rewriteCjsDynExpr(expr.expression, needImportStar))
            is YieldExpression -> expr.copy(expression = expr.expression?.let { rewriteCjsDynExpr(it, needImportStar) })
            is ParenthesizedExpression -> expr.copy(expression = rewriteCjsDynExpr(expr.expression, needImportStar))
            is PrefixUnaryExpression -> expr.copy(operand = rewriteCjsDynExpr(expr.operand, needImportStar))
            is PostfixUnaryExpression -> expr.copy(operand = rewriteCjsDynExpr(expr.operand, needImportStar))
            is NewExpression -> expr.copy(
                expression = rewriteCjsDynExpr(expr.expression, needImportStar),
                arguments = expr.arguments?.map { rewriteCjsDynExpr(it, needImportStar) }
            )
            is SpreadElement -> expr.copy(expression = rewriteCjsDynExpr(expr.expression, needImportStar))
            is ArrayLiteralExpression -> expr.copy(elements = expr.elements.map { rewriteCjsDynExpr(it, needImportStar) })
            is ObjectLiteralExpression -> expr.copy(properties = expr.properties.map { prop ->
                when (prop) {
                    is PropertyAssignment -> prop.copy(initializer = rewriteCjsDynExpr(prop.initializer, needImportStar))
                    is MethodDeclaration -> prop.copy(body = prop.body?.let { block ->
                        block.copy(statements = block.statements.map { rewriteCjsDynStmt(it, needImportStar) })
                    })
                    else -> prop
                }
            })
            is ArrowFunction -> when (val body = expr.body) {
                is Expression -> expr.copy(body = rewriteCjsDynExpr(body, needImportStar))
                is Block -> expr.copy(body = body.copy(statements = body.statements.map { rewriteCjsDynStmt(it, needImportStar) }))
                else -> expr
            }
            is FunctionExpression -> expr.copy(body = expr.body.let { block ->
                block.copy(statements = block.statements.map { rewriteCjsDynStmt(it, needImportStar) })
            })
            else -> expr
        }
    }

    /** Recursively rewrites dynamic `import(spec)` calls in [stmt] to CJS Promise.resolve form. */
    private fun rewriteCjsDynStmt(stmt: Statement, needImportStar: BooleanArray): Statement {
        return when (stmt) {
            is ExpressionStatement -> stmt.copy(expression = rewriteCjsDynExpr(stmt.expression, needImportStar))
            is ReturnStatement -> stmt.copy(expression = stmt.expression?.let { rewriteCjsDynExpr(it, needImportStar) })
            is ThrowStatement -> stmt.copy(expression = stmt.expression?.let { rewriteCjsDynExpr(it, needImportStar) })
            is VariableStatement -> stmt.copy(declarationList = stmt.declarationList.copy(
                declarations = stmt.declarationList.declarations.map { decl ->
                    decl.copy(initializer = decl.initializer?.let { rewriteCjsDynExpr(it, needImportStar) })
                }
            ))
            is Block -> stmt.copy(statements = stmt.statements.map { rewriteCjsDynStmt(it, needImportStar) })
            is IfStatement -> stmt.copy(
                expression = rewriteCjsDynExpr(stmt.expression, needImportStar),
                thenStatement = rewriteCjsDynStmt(stmt.thenStatement, needImportStar),
                elseStatement = stmt.elseStatement?.let { rewriteCjsDynStmt(it, needImportStar) }
            )
            is WhileStatement -> stmt.copy(
                expression = rewriteCjsDynExpr(stmt.expression, needImportStar),
                statement = rewriteCjsDynStmt(stmt.statement, needImportStar)
            )
            is DoStatement -> stmt.copy(
                expression = rewriteCjsDynExpr(stmt.expression, needImportStar),
                statement = rewriteCjsDynStmt(stmt.statement, needImportStar)
            )
            is ForStatement -> stmt.copy(statement = rewriteCjsDynStmt(stmt.statement, needImportStar))
            is ForOfStatement -> stmt.copy(statement = rewriteCjsDynStmt(stmt.statement, needImportStar))
            is ForInStatement -> stmt.copy(statement = rewriteCjsDynStmt(stmt.statement, needImportStar))
            is FunctionDeclaration -> stmt.copy(body = stmt.body?.let { block ->
                block.copy(statements = block.statements.map { rewriteCjsDynStmt(it, needImportStar) })
            })
            else -> stmt
        }
    }

    /** Returns true if the expression is a `require("...")` call. */
    private fun isRequireCall(expr: Expression?): Boolean =
        expr is CallExpression && (expr.expression as? Identifier)?.text == "require"

    /** Returns true if the expression is `require(...)` or `__importStar/Default(require(...))`. */
    private fun isRequireImport(expr: Expression?): Boolean =
        isRequireCall(expr) || (expr is CallExpression &&
                ((expr.expression as? Identifier)?.text?.let { it == "__importStar" || it == "__importDefault" } == true) &&
                isRequireCall(expr.arguments.firstOrNull()))

    private fun isImportStarWrapper(stmt: VariableStatement): Boolean {
        val init = stmt.declarationList.declarations.firstOrNull()?.initializer as? CallExpression
        return (init?.expression as? Identifier)?.text == "__importStar"
    }

    private fun isImportDefaultWrapper(stmt: VariableStatement): Boolean {
        val init = stmt.declarationList.declarations.firstOrNull()?.initializer as? CallExpression
        return (init?.expression as? Identifier)?.text == "__importDefault"
    }

    private fun generateModuleTempName(moduleSpecifier: Expression, counter: MutableMap<String, Int>? = null): String {
        val specText = (moduleSpecifier as? StringLiteralNode)?.text ?: "module"
        val baseName = specText.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9_]"), "_")
        return if (counter != null) {
            val n = (counter[baseName] ?: 0) + 1
            counter[baseName] = n
            "${baseName}_$n"
        } else {
            "${baseName}_1"
        }
    }

    // -----------------------------------------------------------------
    // Statement transforms
    // -----------------------------------------------------------------

    /**
     * Transforms a list of statements, managing the hoisted-var scope for class expressions.
     * When [isFunctionScope] is true (for function bodies and the top-level module), a new
     * scope is pushed for collecting temp var declarations (`var _a;`).
     */
    private fun transformStatements(
        statements: List<Statement>,
        atTopLevel: Boolean = false,
        isFunctionScope: Boolean = false,
    ): List<Statement> {
        val scopeVars = if (isFunctionScope || atTopLevel) {
            mutableListOf<String>().also { hoistedVarScopes.add(it) }
        } else null

        val result = mutableListOf<Statement>()
        for (stmt in statements) {
            val transformed = transformStatement(stmt)
            result.addAll(transformed)
            // At top level: once we see runtime output, stop preserving orphaned comments.
            if (atTopLevel && transformed.any { it !is NotEmittedStatement }) {
                hasSeenRuntimeStatement = true
            }
            // Track that we've processed at least one top-level statement (even erased ones).
            if (atTopLevel) hasSeenAnyTopLevelStatement = true
            // Track class/function names in declaration order.
            // This allows namespace/module IIFEs to skip var N; when a class or
            // function with the same name was declared BEFORE the namespace.
            if (stmt is Declaration && !hasDeclareModifier(stmt)) {
                when (stmt) {
                    is ClassDeclaration -> stmt.name?.text?.let { declaredNames.add(it) }
                    is FunctionDeclaration -> stmt.name?.text?.let { declaredNames.add(it) }
                    else -> {}
                }
            }
        }

        // Pop the scope and prepend any collected hoisted vars at the start of this block.
        if (scopeVars != null) {
            hoistedVarScopes.removeLast()
            if (scopeVars.isNotEmpty()) {
                // Emit all temp vars in a single `var _a, _b, _c;` declaration (TypeScript style)
                val combined = if (scopeVars.size == 1) {
                    listOf(makeHoistedVar(scopeVars[0]))
                } else {
                    listOf(VariableStatement(
                        declarationList = VariableDeclarationList(
                            declarations = scopeVars.map { VariableDeclaration(name = syntheticId(it), pos = -1, end = -1) },
                            flags = VarKeyword,
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ))
                }
                // Insert hoisted vars AFTER any leading NotEmittedStatements (orphaned comments).
                // This preserves orphaned comment ordering relative to hoisted var declarations.
                val insertAt = result.indexOfFirst { it !is NotEmittedStatement }.takeIf { it >= 0 } ?: 0
                // At top level: if the first real statement has DETACHED leading comments
                // (separated by a blank line), transfer them to the hoisted var declaration.
                // TypeScript's emitter anchors pre-statement comments to the hoisted var when
                // they are detached (blank line ≥2 newlines between comment end and stmt pos).
                // Adjacent comments (no blank line) stay with the statement.
                val firstRealStmt = result.getOrNull(insertAt)
                val firstRealComments = firstRealStmt?.leadingComments
                val hasDetachedComment = atTopLevel &&
                    !firstRealComments.isNullOrEmpty() &&
                    firstRealStmt.pos >= 0 &&
                    firstRealComments.any { c ->
                        c.end >= 0 && sourceText.substring(c.end, firstRealStmt.pos).count { it == '\n' } >= 2
                    }
                if (hasDetachedComment) {
                    val hoisted = combined.single()
                    val hoistedWithComments = (hoisted as? VariableStatement)
                        ?.copy(leadingComments = firstRealComments)
                    val strippedFirst: Statement? = when (firstRealStmt) {
                        is VariableStatement -> firstRealStmt.copy(leadingComments = null)
                        is ExpressionStatement -> firstRealStmt.copy(leadingComments = null)
                        is FunctionDeclaration -> firstRealStmt.copy(leadingComments = null)
                        is ClassDeclaration -> firstRealStmt.copy(leadingComments = null)
                        else -> null
                    }
                    if (hoistedWithComments != null && strippedFirst != null) {
                        result[insertAt] = strippedFirst
                        result.addAll(insertAt, listOf(hoistedWithComments))
                    } else {
                        result.addAll(insertAt, combined)
                    }
                } else {
                    result.addAll(insertAt, combined)
                }
            }
        }

        return result
    }

    /**
     * Transforms a single statement, potentially producing 0, 1, or many output statements.
     */
    private fun transformStatement(statement: Statement): List<Statement> {
        // Declarations with `declare` modifier produce no output.
        // Exception: ImportEqualsDeclaration is handled by transformImportEqualsDeclaration
        // which decides whether to emit it (e.g. `declare export import a = X.Y` still emits).
        if (statement is Declaration && hasDeclareModifier(statement) && statement !is ImportEqualsDeclaration) {
            return orphanedComments(statement)
        }

        return when (statement) {
            // --- Type erasure: remove type-only declarations ---
            is InterfaceDeclaration -> orphanedComments(statement)
            is TypeAliasDeclaration -> orphanedComments(statement)

            // --- Enum transform ---
            is EnumDeclaration -> transformEnum(statement)

            // --- Namespace / module declaration transform ---
            is ModuleDeclaration -> {
                val result = transformModuleDeclaration(statement)
                if (result.isEmpty()) orphanedComments(statement) else result
            }

            // --- Class transform ---
            is ClassDeclaration -> transformClassDeclaration(statement)

            // --- Function declaration ---
            is FunctionDeclaration -> {
                // `default function` without `export` is a TypeScript error; strip the
                // stray `default` modifier so the emitter doesn't emit `default function`.
                val decl = if (ModifierFlag.Default in statement.modifiers && ModifierFlag.Export !in statement.modifiers) {
                    statement.copy(modifiers = statement.modifiers - ModifierFlag.Default)
                } else statement
                transformFunctionDeclaration(decl)
            }

            // --- Variable statement ---
            is VariableStatement -> transformVariableStatement(statement)

            // --- Expression statement ---
            is ExpressionStatement -> listOf(
                statement.copy(expression = transformExpression(statement.expression))
            )

            // --- Import / Export ---
            is ImportDeclaration -> transformImportDeclaration(statement)
            is ImportEqualsDeclaration -> transformImportEqualsDeclaration(statement)
            is ExportDeclaration -> transformExportDeclaration(statement)
            is ExportAssignment -> transformExportAssignment(statement)

            // --- Control flow: recurse into children ---
            is Block -> listOf(statement.copy(statements = transformStatements(statement.statements)))
            is IfStatement -> listOf(transformIfStatement(statement))
            is DoStatement -> listOf(
                statement.copy(
                    statement = transformStatementSingle(statement.statement),
                    expression = transformExpression(statement.expression),
                )
            )

            is WhileStatement -> listOf(
                statement.copy(
                    expression = transformExpression(statement.expression),
                    statement = transformStatementSingle(statement.statement),
                )
            )

            is ForStatement -> listOf(transformForStatement(statement))
            is ForInStatement -> listOf(transformForInStatement(statement))
            is ForOfStatement -> listOf(transformForOfStatement(statement))
            is ReturnStatement -> listOf(
                statement.copy(expression = statement.expression?.let { transformExpression(it) })
            )

            is SwitchStatement -> listOf(transformSwitchStatement(statement))
            is LabeledStatement -> listOf(
                statement.copy(statement = transformStatementSingle(statement.statement))
            )

            is ThrowStatement -> listOf(
                statement.copy(expression = statement.expression?.let { transformExpression(it) })
            )

            is TryStatement -> listOf(transformTryStatement(statement))
            is WithStatement -> listOf(
                statement.copy(
                    expression = transformExpression(statement.expression),
                    statement = transformStatementSingle(statement.statement),
                )
            )

            // --- VariableDeclaration as top-level statement (rare but possible) ---
            is VariableDeclaration -> listOf(transformVariableDeclaration(statement))

            // --- Leaf / pass-through statements ---
            is EmptyStatement -> listOf(statement)
            is BreakStatement -> listOf(statement)
            is ContinueStatement -> listOf(statement)
            is DebuggerStatement -> listOf(statement)
            is NotEmittedStatement -> listOf(statement)
            is RawStatement -> listOf(statement)
        }
    }

    /**
     * Transforms a statement that must produce exactly one statement (e.g. body of while/for).
     * If the input is a block-like compound, it stays as one statement.
     */
    private fun transformStatementSingle(statement: Statement): Statement {
        val result = transformStatement(statement)
        return when {
            result.size == 1 -> result.first()
            result.isEmpty() -> EmptyStatement(pos = -1, end = -1)
            else -> Block(statements = result, pos = -1, end = -1)
        }
    }

    // -----------------------------------------------------------------
    // Declare modifier detection
    // -----------------------------------------------------------------

    private fun hasDeclareModifier(
        statement: Declaration
    ) = when (statement) {
        is FunctionDeclaration -> ModifierFlag.Declare in statement.modifiers
        is ClassDeclaration -> ModifierFlag.Declare in statement.modifiers
        is InterfaceDeclaration -> ModifierFlag.Declare in statement.modifiers
        is TypeAliasDeclaration -> ModifierFlag.Declare in statement.modifiers
        is EnumDeclaration -> ModifierFlag.Declare in statement.modifiers
        is ModuleDeclaration -> ModifierFlag.Declare in statement.modifiers
        is VariableDeclaration -> false // handled via VariableStatement
        is ImportDeclaration -> false
        is ImportEqualsDeclaration -> false
        is ExportDeclaration -> false
        is ExportAssignment -> false
    }

    // -----------------------------------------------------------------
    // Function declaration transform
    // -----------------------------------------------------------------

    private fun transformFunctionDeclaration(
        decl: FunctionDeclaration
    ): List<Statement> {
        // Overload signatures (no body, not declare) are erased.
        // Preserve any detached/file-start comments (orphanedComments handles the rules).
        if (decl.body == null) return orphanedComments(decl)

        val strippedModifiers = stripTypeScriptModifiers(decl.modifiers)
        val isAsync = ModifierFlag.Async in decl.modifiers && options.effectiveTarget < ScriptTarget.ES2017
        val isAsyncGenerator = isAsync && decl.asteriskToken
        val prevInAsyncBody = inAsyncBody
        val prevInAsyncGeneratorBody = inAsyncGeneratorBody
        inAsyncBody = isAsync && !isAsyncGenerator
        inAsyncGeneratorBody = isAsyncGenerator
        return withFreshTempVarCounter {
            val transformedBody = transformBlock(decl.body, isFunctionScope = true)
            inAsyncBody = prevInAsyncBody
            inAsyncGeneratorBody = prevInAsyncGeneratorBody

            if (isAsyncGenerator) {
                needsAsyncGeneratorHelper = true
                functionScopeDepth++
                val transformedParams = transformParameters(decl.parameters)
                functionScopeDepth--
                val innerName = decl.name?.text?.let { "${it}_1" }
                val asyncGenBody = Block(
                    statements = listOf(ReturnStatement(expression = makeAsyncGeneratorCall(
                        syntheticId("this"), syntheticId("arguments"),
                        innerName, transformedParams, transformedBody
                    ))),
                    multiLine = true,
                )
                listOf(decl.copy(
                    typeParameters = null,
                    parameters = transformedParams,
                    type = null,
                    body = asyncGenBody,
                    modifiers = strippedModifiers - ModifierFlag.Async,
                    asteriskToken = false,
                ))
            } else if (isAsync) {
                needsAwaiterHelper = true
                // Transform parameters inside the function scope so async arrows in
                // default parameters see the correct functionScopeDepth for `this` binding.
                functionScopeDepth++
                val transformedParams = transformParameters(decl.parameters)
                functionScopeDepth--
                // When any parameter has a default value, move all params to the generator
                // and pass `arguments` as the 2nd arg to __awaiter so defaults can be re-evaluated.
                // Otherwise keep params in the outer function and pass `void 0`.
                val hasDefaultParams = decl.parameters.any { it.initializer != null }
                val (outerParams, generatorParams, secondArg) = if (hasDefaultParams) {
                    Triple(emptyList<Parameter>(), transformedParams, syntheticId("arguments") as Expression)
                } else {
                    val void0 = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1)
                    Triple(transformedParams, emptyList<Parameter>(), void0 as Expression)
                }
                val awaiterBody = Block(
                    statements = listOf(ReturnStatement(expression = makeAwaiterCall(syntheticId("this"), secondArg, transformedBody, generatorParams))),
                    multiLine = true,
                )
                listOf(decl.copy(
                    typeParameters = null,
                    parameters = outerParams,
                    type = null,
                    body = awaiterBody,
                    modifiers = strippedModifiers - ModifierFlag.Async,
                    asteriskToken = false,
                ))
            } else {
                // Transform parameters inside the function scope so async arrows in
                // default parameters see the correct functionScopeDepth for `this` binding.
                functionScopeDepth++
                val (params, finalBody) = flattenRestParameters(decl.parameters, transformedBody)
                functionScopeDepth--
                listOf(
                    decl.copy(
                        typeParameters = null,
                        parameters = params,
                        type = null,
                        body = finalBody,
                        modifiers = strippedModifiers,
                    )
                )
            }
        }
    }

    /** Builds `__awaiter(thisArg, secondArg, void 0, function* (params...) { body })`. */
    private fun makeAwaiterCall(
        thisArg: Expression,
        secondArg: Expression? = null,
        body: Block,
        generatorParams: List<Parameter> = emptyList(),
    ): CallExpression {
        val void0 = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1)
        val generatorFn = FunctionExpression(
            parameters = generatorParams,
            body = body,
            asteriskToken = true,
            pos = -1, end = -1,
        )
        val awaiterExpr: Expression = if (options.importHelpers) {
            PropertyAccessExpression(
                expression = syntheticId("tslib_1"),
                name = syntheticId("__awaiter"),
                pos = -1, end = -1,
            )
        } else {
            syntheticId("__awaiter")
        }
        return CallExpression(
            expression = awaiterExpr,
            arguments = listOf(thisArg, secondArg ?: void0, void0, generatorFn),
            pos = -1, end = -1,
        )
    }

    /** Builds `__asyncGenerator(thisArg, arguments, function* name_1(params) { body })`. */
    private fun makeAsyncGeneratorCall(
        thisArg: Expression,
        argumentsArg: Expression,
        innerName: String?,
        innerParams: List<Parameter>,
        body: Block,
    ): CallExpression {
        val generatorFn = FunctionExpression(
            name = innerName?.let { Identifier(text = it, pos = -1, end = -1) },
            parameters = innerParams,
            body = body,
            asteriskToken = true,
            pos = -1, end = -1,
        )
        return CallExpression(
            expression = syntheticId("__asyncGenerator"),
            arguments = listOf(thisArg, argumentsArg, generatorFn),
            pos = -1, end = -1,
        )
    }

    /** Builds `__await(expr)`. */
    private fun makeAwaitCall(expr: Expression): CallExpression =
        CallExpression(
            expression = syntheticId("__await"),
            arguments = listOf(expr),
            pos = -1, end = -1,
        )

    // -----------------------------------------------------------------
    // Variable statement transform
    // -----------------------------------------------------------------

    private fun transformVariableStatement(stmt: VariableStatement): List<Statement> {
        // Declare modifier on the variable statement — erase, but preserve detached comments
        if (ModifierFlag.Declare in stmt.modifiers) return orphanedComments(stmt)

        val transformedList = transformVariableDeclarationListWithRest(stmt.declarationList)
        val strippedModifiers = stripTypeScriptModifiers(stmt.modifiers)
        return listOf(
            stmt.copy(
                declarationList = transformedList,
                modifiers = strippedModifiers,
            )
        )
    }

    private fun transformVariableDeclarationList(
        list: VariableDeclarationList
    ): VariableDeclarationList {
        return list.copy(
            declarations = list.declarations.map { transformVariableDeclaration(it) }
        )
    }

    /**
     * Like transformVariableDeclarationList but also handles object rest patterns.
     * `var { a, ...rest } = expr` → `var { a } = expr, rest = __rest(expr, ["a"])`
     */
    private fun transformVariableDeclarationListWithRest(
        list: VariableDeclarationList
    ): VariableDeclarationList {
        val newDecls = mutableListOf<VariableDeclaration>()
        for (decl in list.declarations) {
            val name = decl.name
            if (name is ObjectBindingPattern && name.elements.any { it.dotDotDotToken }
                && options.effectiveTarget < ScriptTarget.ES2018) {
                val restElement = name.elements.last { it.dotDotDotToken }
                val nonRestElements = name.elements.filter { !it.dotDotDotToken }

                // Collect the property names that are excluded from __rest
                val excludedKeys = nonRestElements.map { elem ->
                    val keyName = when {
                        elem.propertyName is Identifier -> elem.propertyName.text
                        elem.propertyName is StringLiteralNode -> elem.propertyName.text
                        elem.propertyName is NumericLiteralNode -> elem.propertyName.text
                        elem.name is Identifier -> elem.name.text
                        else -> return@map null
                    }
                    StringLiteralNode(text = keyName, singleQuote = false, pos = -1, end = -1)
                }.filterNotNull()

                val initExpr = decl.initializer?.let { transformExpression(it) }

                // If initializer is complex (not a simple identifier) AND there are
                // non-rest elements (need to destructure), use a temp var to avoid
                // evaluating the expression twice.
                // Also check the ORIGINAL expression: if it was a type assertion (AsExpression
                // or TypeAssertionExpression), TypeScript caches it even though the cast erases to an identifier.
                val originalIsTypeAssertion = decl.initializer is AsExpression || decl.initializer is TypeAssertionExpression
                val sourceExpr: Expression
                val needsTempVar = initExpr != null && nonRestElements.isNotEmpty() &&
                    (initExpr !is Identifier || initExpr.text == "this" || originalIsTypeAssertion)
                if (needsTempVar) {
                    val tempName = nextTempVarName()
                    // Don't hoist — the temp var is declared inline in the same declaration list
                    sourceExpr = syntheticId(tempName)
                    // Emit: _a = expr, { a } = _a, rest = __rest(_a, ["a"])
                    newDecls.add(VariableDeclaration(
                        name = syntheticId(tempName),
                        initializer = initExpr,
                        pos = -1, end = -1,
                    ))
                    val newPattern = ObjectBindingPattern(
                        elements = nonRestElements.map { transformBindingElement(it) },
                        pos = -1, end = -1,
                    )
                    newDecls.add(VariableDeclaration(
                        name = newPattern,
                        initializer = sourceExpr,
                        pos = -1, end = -1,
                    ))
                } else {
                    sourceExpr = initExpr ?: syntheticId("undefined")
                    // Emit non-rest part (if there are non-rest elements)
                    if (nonRestElements.isNotEmpty()) {
                        // Promote trailing comment of last non-rest element to VariableDeclaration
                        // so it appears between the destructuring decl and the __rest decl.
                        val lastIdx = nonRestElements.size - 1
                        val lastElem = nonRestElements[lastIdx]
                        val promotedComments = lastElem.trailingComments
                        val elementsForPattern = if (promotedComments != null) {
                            nonRestElements.mapIndexed { i, elem ->
                                if (i == lastIdx) elem.copy(trailingComments = null) else elem
                            }
                        } else {
                            nonRestElements
                        }
                        val newPattern = ObjectBindingPattern(
                            elements = elementsForPattern.map { transformBindingElement(it) },
                            pos = -1, end = -1,
                        )
                        newDecls.add(VariableDeclaration(
                            name = newPattern,
                            initializer = sourceExpr,
                            pos = -1, end = -1,
                            leadingComments = decl.leadingComments,
                            trailingComments = promotedComments,
                        ))
                    }
                }

                // Emit rest part: rest = __rest(source, ["a", "b"])
                val restName = transformBindingName(restElement.name)
                newDecls.add(VariableDeclaration(
                    name = restName,
                    initializer = CallExpression(
                        expression = syntheticId("__rest"),
                        arguments = listOf(
                            sourceExpr,
                            ArrayLiteralExpression(
                                elements = excludedKeys,
                                pos = -1, end = -1,
                            ),
                        ),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ))
                needsRestHelper = true
            } else if (name is ObjectBindingPattern && options.effectiveTarget < ScriptTarget.ES2018 &&
                name.elements.any { elem -> !elem.dotDotDotToken && elem.name is ObjectBindingPattern &&
                    elem.name.elements.any { it.dotDotDotToken } }) {
                // Nested object binding with rest: `const { f: { a, ...spread } } = value`
                // → `const _a = value.f, { a } = _a, spread = __rest(_a, ["a"])`
                val initExpr = decl.initializer?.let { transformExpression(it) }
                    ?: syntheticId("undefined")
                val remainingElements = mutableListOf<BindingElement>()
                for (elem in name.elements) {
                    val elemName = elem.name
                    if (!elem.dotDotDotToken && elemName is ObjectBindingPattern &&
                        elemName.elements.any { it.dotDotDotToken }) {
                        // Compute property access: value.f (or value["key"])
                        val keyStr = when {
                            elem.propertyName is Identifier -> elem.propertyName.text
                            elem.propertyName is StringLiteralNode -> null // use bracket access
                            else -> null
                        }
                        val propAccess: Expression = if (elem.propertyName is StringLiteralNode) {
                            ElementAccessExpression(
                                expression = initExpr, argumentExpression = transformExpression(elem.propertyName as Expression),
                                pos = -1, end = -1,
                            )
                        } else if (keyStr != null) {
                            PropertyAccessExpression(expression = initExpr, name = syntheticId(keyStr), pos = -1, end = -1)
                        } else {
                            initExpr
                        }
                        // Create temp var for the property access
                        val nestedTempName = nextTempVarName()
                        newDecls.add(VariableDeclaration(
                            name = syntheticId(nestedTempName), initializer = propAccess, pos = -1, end = -1,
                        ))
                        // Recursively expand the nested binding with rest using the temp var
                        val subList = transformVariableDeclarationListWithRest(
                            VariableDeclarationList(
                                flags = list.flags,
                                declarations = listOf(VariableDeclaration(
                                    name = elemName, initializer = syntheticId(nestedTempName), pos = -1, end = -1,
                                )),
                                pos = -1, end = -1,
                            )
                        )
                        newDecls.addAll(subList.declarations)
                    } else {
                        remainingElements.add(elem)
                    }
                }
                // If there are remaining (non-nested-rest) elements, emit them
                if (remainingElements.isNotEmpty()) {
                    newDecls.add(VariableDeclaration(
                        name = ObjectBindingPattern(
                            elements = remainingElements.map { transformBindingElement(it) }, pos = -1, end = -1,
                        ),
                        initializer = initExpr,
                        pos = -1, end = -1,
                    ))
                }
            } else {
                newDecls.add(transformVariableDeclaration(decl))
            }
        }
        return list.copy(declarations = newDecls)
    }

    private fun transformVariableDeclaration(decl: VariableDeclaration): VariableDeclaration {
        return decl.copy(
            name = transformBindingName(decl.name),
            type = null,
            initializer = decl.initializer?.let { transformExpression(it) },
            exclamationToken = false,
        )
    }

    private fun transformBindingName(name: Expression): Expression {
        return when (name) {
            is ObjectBindingPattern -> name.copy(
                elements = name.elements.map { transformBindingElement(it) }
            )

            is ArrayBindingPattern -> name.copy(
                elements = name.elements.map { node ->
                    when (node) {
                        is BindingElement -> transformBindingElement(node)
                        else -> node
                    }
                }
            )

            else -> transformExpression(name)
        }
    }

    private fun transformBindingElement(element: BindingElement): BindingElement {
        return element.copy(
            name = transformBindingName(element.name),
            initializer = element.initializer?.let { transformExpression(it) },
        )
    }

    // -----------------------------------------------------------------
    // Expression transforms
    // -----------------------------------------------------------------

    /**
     * Iteratively transforms a left-recursive chain of BinaryExpressions
     * to avoid StackOverflow on deeply nested trees (e.g. binderBinaryExpressionStress).
     */
    /**
     * Transforms `{ a, ...rest } = expr` (destructuring assignment with object rest) to
     * `__rest` form for targets below ES2018.
     *
     * Simple (rest-only): `{ ...rest } = expr` → `rest = __rest(expr, [])`
     * With properties: `{ a, ...rest } = expr` → `({ a } = _a = expr, rest = __rest(_a, ["a"]))`
     */
    private fun transformObjectRestDestructuringAssignment(
        root: BinaryExpression,
        objLit: ObjectLiteralExpression,
    ): Expression {
        val rhs = transformExpression(root.right)
        val spreadProp = objLit.properties.filterIsInstance<SpreadAssignment>().lastOrNull()
            ?: return root.copy(left = transformExpression(root.left), right = rhs)
        val nonSpreadProps = objLit.properties.filter { it !is SpreadAssignment }

        // Collect excluded keys for __rest(expr, ["a", "b"])
        val excludedKeys = nonSpreadProps.mapNotNull { prop ->
            val keyName = when (prop) {
                is ShorthandPropertyAssignment -> prop.name.text
                is PropertyAssignment -> when (val n = prop.name) {
                    is Identifier -> n.text
                    is StringLiteralNode -> n.text
                    is NumericLiteralNode -> n.text
                    else -> null
                }
                else -> null
            }
            keyName?.let { StringLiteralNode(text = it, singleQuote = false, pos = -1, end = -1) }
        }

        val restTarget = transformExpression(spreadProp.expression)

        // Determine source expression (use temp var if rhs is complex and there are non-rest props)
        val needsTempVar = nonSpreadProps.isNotEmpty() && rhs !is Identifier
        val sourceExpr: Expression
        val parts = mutableListOf<Expression>()

        if (needsTempVar) {
            val tempName = nextTempVarName()
            hoistedVarScopes.lastOrNull()?.add(tempName)
            val tempId = syntheticId(tempName)
            parts.add(BinaryExpression(left = tempId, operator = Equals, right = rhs, pos = -1, end = -1))
            sourceExpr = tempId
        } else {
            sourceExpr = rhs
        }

        // Emit non-rest destructuring: { a, b } = source
        if (nonSpreadProps.isNotEmpty()) {
            val nonSpreadObjLit = ObjectLiteralExpression(
                properties = nonSpreadProps.map { prop ->
                    when (prop) {
                        is ShorthandPropertyAssignment -> prop.copy(
                            objectAssignmentInitializer = prop.objectAssignmentInitializer?.let { transformExpression(it) }
                        )
                        is PropertyAssignment -> prop.copy(initializer = transformExpression(prop.initializer))
                        else -> prop
                    }
                },
                pos = -1, end = -1,
            )
            parts.add(BinaryExpression(left = nonSpreadObjLit, operator = Equals, right = sourceExpr, pos = -1, end = -1))
        }

        // Emit rest: rest = __rest(source, ["a", "b"])
        val restCall = CallExpression(
            expression = syntheticId("__rest"),
            arguments = listOf(
                sourceExpr,
                ArrayLiteralExpression(elements = excludedKeys, pos = -1, end = -1),
            ),
            pos = -1, end = -1,
        )
        parts.add(BinaryExpression(left = restTarget, operator = Equals, right = restCall, pos = -1, end = -1))
        needsRestHelper = true

        return when {
            parts.size == 1 -> parts[0]
            else -> parts.drop(1).fold(parts[0]) { acc, expr ->
                BinaryExpression(left = acc, operator = Comma, right = expr, pos = -1, end = -1)
            }
        }
    }

    private fun transformBinaryExpression(root: BinaryExpression): Expression {
        // Handle object destructuring assignment with rest: { a, ...rest } = expr
        // e.g., ({ ...bar } = {}) → (bar = __rest({}, []))
        // Only applies when target < ES2018 (ES2018 supports object rest natively)
        if (root.operator == Equals && options.effectiveTarget < ScriptTarget.ES2018) {
            val rawLeft = root.left
            val objLit = rawLeft as? ObjectLiteralExpression
            if (objLit != null && objLit.properties.any { it is SpreadAssignment }) {
                return transformObjectRestDestructuringAssignment(root, objLit)
            }
        }

        // Collect the left-spine: walk down .left while it's a BinaryExpression
        // that doesn't need special downlevel handling (**, **=, ??).
        // For special operators we stop flattening so they get proper treatment.
        data class SpineEntry(val node: BinaryExpression, val right: Expression)
        val spine = mutableListOf<SpineEntry>()
        var current: Expression = root
        while (current is BinaryExpression) {
            val needsSpecial = when (current.operator) {
                AsteriskAsterisk, AsteriskAsteriskEquals ->
                    options.effectiveTarget < ScriptTarget.ES2016
                QuestionQuestion ->
                    options.effectiveTarget < ScriptTarget.ES2020
                else -> false
            }
            if (needsSpecial) break
            spine.add(SpineEntry(current, current.right))
            current = current.left
        }
        if (spine.isEmpty()) {
            // Root itself needs special handling — use the original recursive path
            return transformBinaryExpressionSpecial(root)
        }
        // Transform the leftmost expression (may itself be a binary with special ops)
        var result = transformExpression(current)
        // Rebuild from bottom (leftmost) to top (root)
        for (i in spine.indices.reversed()) {
            val entry = spine[i]
            val right = transformExpression(entry.right)
            result = entry.node.copy(left = result, right = right)
        }
        return result
    }

    /**
     * Handles BinaryExpression with special operators that need downleveling.
     */
    private fun transformBinaryExpressionSpecial(expr: BinaryExpression): Expression {
        val left = transformExpression(expr.left)
        val right = transformExpression(expr.right)
        // Downlevel `**` to `Math.pow(left, right)` for targets below ES2016
        if (expr.operator == AsteriskAsterisk &&
            options.effectiveTarget < ScriptTarget.ES2016) {
            return CallExpression(
                expression = PropertyAccessExpression(
                    expression = syntheticId("Math"),
                    name = syntheticId("pow"),
                    pos = -1, end = -1,
                ),
                arguments = listOf(left, right),
                pos = -1, end = -1,
            )
        }
        // Downlevel `**=` to `x = Math.pow(x, right)` for targets below ES2016
        if (expr.operator == AsteriskAsteriskEquals &&
            options.effectiveTarget < ScriptTarget.ES2016) {
            return BinaryExpression(
                left = left,
                operator = Equals,
                right = CallExpression(
                    expression = PropertyAccessExpression(
                        expression = syntheticId("Math"),
                        name = syntheticId("pow"),
                        pos = -1, end = -1,
                    ),
                    arguments = listOf(left, right),
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            )
        }
        // Downlevel `??` to `!== null && !== void 0 ? :` for targets below ES2020
        if (expr.operator == QuestionQuestion &&
            options.effectiveTarget < ScriptTarget.ES2020) {
            val leftRef: Expression
            val nullCheck: Expression
            // Literals and identifiers are never null/undefined by their nature — no temp var needed
            val isNeverNullish = left is Identifier ||
                    left is StringLiteralNode || left is NumericLiteralNode ||
                    left is BigIntLiteralNode || left is NoSubstitutionTemplateLiteralNode
            if (isNeverNullish) {
                leftRef = left
                nullCheck = left
            } else {
                val tempName = nextTempVarName()
                hoistedVarScopes.lastOrNull()?.add(tempName)
                val tempId = syntheticId(tempName)
                val assign = ParenthesizedExpression(
                    expression = BinaryExpression(left = tempId, operator = Equals, right = left, pos = -1, end = -1),
                    pos = -1, end = -1,
                )
                nullCheck = assign
                leftRef = tempId
            }
            val notNull = BinaryExpression(
                left = nullCheck,
                operator = ExclamationEqualsEquals,
                right = syntheticId("null"),
                pos = -1, end = -1,
            )
            val notUndefined = BinaryExpression(
                left = leftRef,
                operator = ExclamationEqualsEquals,
                right = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1),
                pos = -1, end = -1,
            )
            val condition = BinaryExpression(
                left = notNull,
                operator = AmpersandAmpersand,
                right = notUndefined,
                pos = -1, end = -1,
            )
            return ConditionalExpression(
                condition = condition,
                whenTrue = leftRef,
                whenFalse = right,
                pos = -1, end = -1,
            )
        }
        return expr.copy(left = left, right = right)
    }

    /**
     * Transforms an expression, stripping TypeScript-only constructs.
     * Always returns exactly one expression.
     */
    private fun transformExpression(expr: Expression): Expression {
        return when (expr) {
            // Type assertion / cast expressions: unwrap, preserving leading comments
            is AsExpression -> transferErasureComments(expr, transformExpression(expr.expression))
            is NonNullExpression -> transferErasureComments(expr, transformExpression(expr.expression))
            is SatisfiesExpression -> transferErasureComments(expr, transformExpression(expr.expression))
            is TypeAssertionExpression -> transferErasureComments(expr, transformExpression(expr.expression))

            // Parenthesized: if the inner was a type-erasure node (e.g., (<T>expr)),
            // the outer () were just syntax for the assertion — drop them unless the
            // resulting expression really needs parens (object literals, function exprs…)
            // Also handle ((<T>expr)) where an extra layer of parens wraps the assertion.
            is ParenthesizedExpression -> {
                val inner = expr.expression
                val wasTypeErasure = isTypeErasureNode(inner)
                        || (inner is ParenthesizedExpression && isTypeErasureNode(inner.expression))
                val transformed = transformExpression(inner)
                if (wasTypeErasure && !typeAssertionResultNeedsParens(transformed)) {
                    // Keep parens only if there are comments DEEPLY NESTED inside the expression
                    // (not on the top-level `leadingComments`). Top-level comments can safely move
                    // outside the parens when dropped; deeply nested ones (e.g. on `this` inside a
                    // call chain) were written between ( and ) and need the parens to stay.
                    if (transformed.leadingComments.isNullOrEmpty() && hasCommentsInTree(transformed)) {
                        expr.copy(expression = transformed)
                    } else {
                        transformed
                    }
                } else if (transformed is CommaListExpression) {
                    // CommaListExpression already includes its own parens; drop the outer ones.
                    transformed
                } else {
                    expr.copy(expression = transformed)
                }
            }

            // Binary: flatten left-spine iteratively to avoid StackOverflow on deep chains
            is BinaryExpression -> {
                transformBinaryExpression(expr)
            }

            // Call: strip type arguments, recurse
            is CallExpression -> {
                val transformedExpr = transformExpression(expr.expression)
                val transformedArgs = expr.arguments.map { transformExpression(it) }
                // Downlevel `?.()` optional call for targets below ES2020
                if (expr.questionDotToken && options.effectiveTarget < ScriptTarget.ES2020) {
                    val objRef: Expression
                    val nullCheck: Expression
                    if (transformedExpr is Identifier) {
                        objRef = transformedExpr; nullCheck = transformedExpr
                    } else {
                        val tempName = nextTempVarName()
                        hoistedVarScopes.lastOrNull()?.add(tempName)
                        val tempId = syntheticId(tempName)
                        nullCheck = ParenthesizedExpression(expression = BinaryExpression(left = tempId, operator = Equals, right = transformedExpr, pos = -1, end = -1), pos = -1, end = -1)
                        objRef = tempId
                    }
                    val isNull = BinaryExpression(left = nullCheck, operator = EqualsEqualsEquals, right = syntheticId("null"), pos = -1, end = -1)
                    val isUndefined = BinaryExpression(left = objRef, operator = EqualsEqualsEquals, right = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1), pos = -1, end = -1)
                    val condition = BinaryExpression(left = isNull, operator = BarBar, right = isUndefined, pos = -1, end = -1)
                    val call = expr.copy(expression = objRef, typeArguments = null, arguments = transformedArgs, questionDotToken = false)
                    ConditionalExpression(condition = condition, whenTrue = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1), whenFalse = call, pos = -1, end = -1)
                } else {
                    expr.copy(expression = transformedExpr, typeArguments = null, arguments = transformedArgs)
                }
            }

            // New: strip type arguments, recurse
            is NewExpression -> {
                val constructorExpr = expr.expression
                val transformedConstructor = transformExpression(constructorExpr)
                // When `new` has no explicit arg list and the original constructor was
                // (<T>callExpr), the () are semantically significant:
                //   new (A())  ≠  new A()   (the former calls A first, then newing the result)
                // Re-wrap in () if the type erasure dropped them around a CallExpression.
                val finalConstructor = if (expr.arguments == null
                    && constructorExpr is ParenthesizedExpression
                    && isTypeErasureNode(constructorExpr.expression)
                    && transformedConstructor is CallExpression
                ) {
                    ParenthesizedExpression(
                        expression = transformedConstructor,
                        pos = transformedConstructor.pos,
                        end = transformedConstructor.end,
                    )
                } else {
                    transformedConstructor
                }
                expr.copy(
                    expression = finalConstructor,
                    typeArguments = null,
                    arguments = expr.arguments?.map { transformExpression(it) },
                )
            }

            // Property access: check for const enum inlining first
            is PropertyAccessExpression -> {
                // Downlevel `?.` optional chaining for targets below ES2020
                if (expr.questionDotToken && options.effectiveTarget < ScriptTarget.ES2020) {
                    val obj = transformExpression(expr.expression)
                    // For simple identifier: `a?.b` → `a === null || a === void 0 ? void 0 : a.b`
                    // For complex LHS: use temp var
                    val objRef: Expression
                    val nullCheck: Expression
                    if (obj is Identifier) {
                        objRef = obj
                        nullCheck = obj
                    } else {
                        val tempName = nextTempVarName()
                        hoistedVarScopes.lastOrNull()?.add(tempName)
                        val tempId = syntheticId(tempName)
                        nullCheck = ParenthesizedExpression(
                            expression = BinaryExpression(left = tempId, operator = Equals, right = obj, pos = -1, end = -1),
                            pos = -1, end = -1,
                        )
                        objRef = tempId
                    }
                    // Build: `obj === null || obj === void 0 ? void 0 : obj.member`
                    val isNull = BinaryExpression(left = nullCheck, operator = EqualsEqualsEquals, right = syntheticId("null"), pos = -1, end = -1)
                    val isUndefined = BinaryExpression(
                        left = objRef, operator = EqualsEqualsEquals,
                        right = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1),
                        pos = -1, end = -1,
                    )
                    val condition = BinaryExpression(left = isNull, operator = BarBar, right = isUndefined, pos = -1, end = -1)
                    val access = expr.copy(expression = objRef, questionDotToken = false)
                    return ConditionalExpression(
                        condition = condition,
                        whenTrue = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1),
                        whenFalse = access,
                        pos = -1, end = -1,
                    )
                }
                val baseName = (expr.expression as? Identifier)?.text
                val memberName = expr.name.text
                // Build comment label for the full access chain
                fun accessChainText(e: Expression): String = when (e) {
                    is Identifier -> e.text
                    is PropertyAccessExpression -> "${accessChainText(e.expression)}.${e.name.text}"
                    else -> ""
                }
                if (baseName != null) {
                    val commentLabel = "$baseName.$memberName"
                    val inlined = tryInlineConstEnumMember(baseName, memberName, commentLabel)
                        ?: if (!options.isolatedModules) {
                            checker?.resolveConstEnumMemberAccess(baseName, memberName, currentFileName)
                                ?.let { constValueToExpression(it, commentLabel) }
                        } else null
                    inlined ?: expr.copy(expression = parenthesizeForAccess(transformExpression(expr.expression)))
                } else {
                    // Check for namespaced const enum: M.SomeConstEnum.X or A.B.C.E.V1
                    val commentLabel = "${accessChainText(expr.expression)}.$memberName"
                    // Try checker first for accurate namespace resolution (avoids name collisions)
                    val result = if (!options.isolatedModules) {
                        checker?.resolveConstEnumMemberAccess(
                            accessChainText(expr.expression), memberName, currentFileName
                        )?.let { constValueToExpression(it, commentLabel) }
                    } else null
                    val finalResult = result ?: run {
                        // Fall back to local nested enum lookup
                        val nestedEnum = expr.expression as? PropertyAccessExpression
                        val nestedEnumName = nestedEnum?.name?.text
                        if (nestedEnumName != null && nestedEnumName in constEnumValues)
                            tryInlineConstEnumMember(nestedEnumName, memberName, commentLabel)
                        else null
                    }
                    finalResult ?: expr.copy(expression = parenthesizeForAccess(transformExpression(expr.expression)))
                }
            }

            // Element access: check for const enum inlining (e.g. Foo["X"], Foo[`X`])
            is ElementAccessExpression -> {
                // Downlevel `?.` optional element access for targets below ES2020
                if (expr.questionDotToken && options.effectiveTarget < ScriptTarget.ES2020) {
                    val obj = transformExpression(expr.expression)
                    val key = transformExpression(expr.argumentExpression)
                    val objRef: Expression
                    val nullCheck: Expression
                    if (obj is Identifier) {
                        objRef = obj; nullCheck = obj
                    } else {
                        val tempName = nextTempVarName()
                        hoistedVarScopes.lastOrNull()?.add(tempName)
                        val tempId = syntheticId(tempName)
                        nullCheck = ParenthesizedExpression(expression = BinaryExpression(left = tempId, operator = Equals, right = obj, pos = -1, end = -1), pos = -1, end = -1)
                        objRef = tempId
                    }
                    val isNull = BinaryExpression(left = nullCheck, operator = EqualsEqualsEquals, right = syntheticId("null"), pos = -1, end = -1)
                    val isUndefined = BinaryExpression(left = objRef, operator = EqualsEqualsEquals, right = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1), pos = -1, end = -1)
                    val condition = BinaryExpression(left = isNull, operator = BarBar, right = isUndefined, pos = -1, end = -1)
                    val access = expr.copy(expression = objRef, argumentExpression = key, questionDotToken = false)
                    return ConditionalExpression(condition = condition, whenTrue = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1), whenFalse = access, pos = -1, end = -1)
                }
                val baseName = (expr.expression as? Identifier)?.text
                val keyStr = when (val k = expr.argumentExpression) {
                    is StringLiteralNode -> k.text
                    is NoSubstitutionTemplateLiteralNode -> k.text
                    else -> null
                }
                if (baseName != null && keyStr != null) {
                    // Use original source text for comment label to preserve escapes/syntax.
                    // argumentExpression.end = pos_after_] (includes the `]` due to how nextToken works)
                    val argEnd = expr.argumentExpression.end
                    val commentLabel = if (expr.pos >= 0 && argEnd > expr.pos)
                        sourceText.substring(expr.pos, argEnd)
                    else """$baseName["$keyStr"]"""
                    val inlined = tryInlineConstEnumMember(baseName, keyStr, commentLabel)
                        ?: if (!options.isolatedModules) {
                            checker?.resolveConstEnumMemberAccess(baseName, keyStr, currentFileName)
                                ?.let { constValueToExpression(it, commentLabel) }
                        } else null
                    inlined ?: expr.copy(
                        expression = parenthesizeForAccess(transformExpression(expr.expression)),
                        argumentExpression = transformExpression(expr.argumentExpression),
                    )
                } else {
                    expr.copy(
                        expression = parenthesizeForAccess(transformExpression(expr.expression)),
                        argumentExpression = transformExpression(expr.argumentExpression),
                    )
                }
            }

            // Arrow function: strip types (and downlevel async if target < ES2017)
            is ArrowFunction -> withFreshTempVarCounter {
                val strippedModifiers = stripTypeScriptModifiers(expr.modifiers)
                val isAsync = ModifierFlag.Async in expr.modifiers && options.effectiveTarget < ScriptTarget.ES2017
                val prevInAsyncBody = inAsyncBody
                inAsyncBody = isAsync
                val transformedBody: Node = when (val b = expr.body) {
                    is Block -> transformBlock(b, isFunctionScope = true)
                    is Expression -> transformExpression(b)
                    else -> b
                }
                inAsyncBody = prevInAsyncBody
                if (isAsync) {
                    needsAwaiterHelper = true
                    val void0 = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1)
                    // Arrow functions inside a regular function scope capture `this` from the enclosing function;
                    // at the top level (functionScopeDepth == 0) there's no meaningful `this`, so use void 0.
                    val thisArg: Expression = if (functionScopeDepth > 0) syntheticId("this") else void0
                    val generatorBody: Block = when (transformedBody) {
                        is Block -> transformedBody
                        is Expression -> Block(statements = listOf(ReturnStatement(expression = transformedBody)), multiLine = false)
                        else -> Block(statements = emptyList(), multiLine = false)
                    }
                    expr.copy(
                        typeParameters = null,
                        parameters = transformParameters(expr.parameters),
                        type = null,
                        body = makeAwaiterCall(thisArg, body = generatorBody),
                        modifiers = strippedModifiers - ModifierFlag.Async,
                        hasParenthesizedParameters = true,
                    )
                } else {
                    val hasRestParam = options.effectiveTarget < ScriptTarget.ES2018 &&
                        expr.parameters.any { p -> p.name is ObjectBindingPattern && p.name.elements.any { it.dotDotDotToken } }
                    if (hasRestParam) {
                        val blockBody: Block = when (transformedBody) {
                            is Block -> transformedBody
                            is Expression -> Block(statements = listOf(ReturnStatement(expression = transformedBody, pos = -1, end = -1)), multiLine = false, pos = -1, end = -1)
                            else -> Block(statements = emptyList(), multiLine = false, pos = -1, end = -1)
                        }
                        val (newParams, newBody) = flattenRestParameters(expr.parameters, blockBody)
                        expr.copy(
                            typeParameters = null,
                            parameters = newParams,
                            type = null,
                            body = newBody,
                            modifiers = strippedModifiers,
                            hasParenthesizedParameters = true,
                        )
                    } else {
                        expr.copy(
                            typeParameters = null,
                            parameters = transformParameters(expr.parameters),
                            type = null,
                            body = transformedBody,
                            modifiers = strippedModifiers,
                        )
                    }
                }
            }

            // Function expression: strip types (and downlevel async if target < ES2017)
            is FunctionExpression -> withFreshTempVarCounter {
                val strippedModifiers = stripTypeScriptModifiers(expr.modifiers)
                val isAsync = ModifierFlag.Async in expr.modifiers && options.effectiveTarget < ScriptTarget.ES2017
                val isAsyncGenerator = isAsync && expr.asteriskToken
                val prevInAsyncBody = inAsyncBody
                val prevInAsyncGenBody = inAsyncGeneratorBody
                inAsyncBody = isAsync && !isAsyncGenerator
                inAsyncGeneratorBody = isAsyncGenerator
                val transformedBody = transformBlock(expr.body, isFunctionScope = true)
                inAsyncBody = prevInAsyncBody
                inAsyncGeneratorBody = prevInAsyncGenBody
                if (isAsyncGenerator) {
                    needsAsyncGeneratorHelper = true
                    val innerName = expr.name?.text?.let { "${it}_1" }
                    val transformedParams = transformParameters(expr.parameters)
                    val asyncGenBody = Block(
                        statements = listOf(ReturnStatement(expression = makeAsyncGeneratorCall(
                            syntheticId("this"), syntheticId("arguments"),
                            innerName, transformedParams, transformedBody
                        ))),
                        multiLine = true,
                    )
                    expr.copy(
                        typeParameters = null,
                        parameters = transformedParams,
                        type = null,
                        body = asyncGenBody,
                        modifiers = strippedModifiers - ModifierFlag.Async,
                        asteriskToken = false,
                    )
                } else if (isAsync) {
                    needsAwaiterHelper = true
                    val awaiterBody = Block(
                        statements = listOf(ReturnStatement(expression = makeAwaiterCall(syntheticId("this"), body = transformedBody))),
                        multiLine = true,
                    )
                    expr.copy(
                        typeParameters = null,
                        parameters = transformParameters(expr.parameters),
                        type = null,
                        body = awaiterBody,
                        modifiers = strippedModifiers - ModifierFlag.Async,
                        asteriskToken = false,
                    )
                } else {
                    val (newParams, newBody) = flattenRestParameters(expr.parameters, transformedBody)
                    expr.copy(
                        typeParameters = null,
                        parameters = newParams,
                        type = null,
                        body = newBody,
                        modifiers = strippedModifiers,
                    )
                }
            }

            // Class expression: apply class transforms
            is ClassExpression -> transformClassExpression(expr)

            // Unary
            is PrefixUnaryExpression -> expr.copy(
                operand = transformExpression(expr.operand),
            )

            is PostfixUnaryExpression -> expr.copy(
                operand = transformExpression(expr.operand),
            )

            // Conditional (ternary)
            is ConditionalExpression -> expr.copy(
                condition = transformExpression(expr.condition),
                whenTrue = transformExpression(expr.whenTrue),
                whenFalse = transformExpression(expr.whenFalse),
            )

            // Spread
            is SpreadElement -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Template expression
            is TemplateExpression -> expr.copy(
                templateSpans = expr.templateSpans.map { span ->
                    span.copy(expression = transformExpression(span.expression))
                }
            )

            // Tagged template
            is TaggedTemplateExpression -> transformTaggedTemplate(expr)

            // Yield: in async generator body, `yield expr` → `yield yield __await(expr)`
            is YieldExpression -> {
                if (inAsyncGeneratorBody && !expr.asteriskToken) {
                    val void0 = VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1)
                    val innerExpr = expr.expression?.let { transformExpression(it) } ?: void0
                    YieldExpression(
                        expression = YieldExpression(
                            expression = makeAwaitCall(innerExpr),
                            pos = -1, end = -1,
                        ),
                        leadingComments = expr.leadingComments,
                        trailingComments = expr.trailingComments,
                        pos = -1, end = -1,
                    )
                } else {
                    expr.copy(expression = expr.expression?.let { transformExpression(it) })
                }
            }

            // Delete
            is DeleteExpression -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Typeof
            is TypeOfExpression -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Void
            is VoidExpression -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Await: in async generator body → yield __await(expr);
            // in regular async body → yield expr;
            // outside async at old target → yield expr.
            is AwaitExpression -> {
                val transformedInner = transformExpression(expr.expression)
                if (inAsyncGeneratorBody) {
                    // async function* context: await expr → yield __await(expr)
                    YieldExpression(
                        expression = makeAwaitCall(transformedInner),
                        leadingComments = expr.leadingComments,
                        trailingComments = expr.trailingComments,
                        pos = -1, end = -1,
                    )
                } else if (inAsyncBody || (!expr.inAsyncContext && options.effectiveTarget < ScriptTarget.ES2017)) {
                    YieldExpression(
                        expression = transformedInner,
                        leadingComments = expr.leadingComments,
                        trailingComments = expr.trailingComments,
                        pos = -1, end = -1,
                    )
                } else {
                    expr.copy(expression = transformedInner)
                }
            }

            // Array literal
            is ArrayLiteralExpression -> expr.copy(
                elements = expr.elements.map { transformExpression(it) }
            )

            // Object literal
            is ObjectLiteralExpression -> transformObjectLiteral(expr)

            // Computed property name
            is ComputedPropertyName -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Binding patterns (when used as expressions)
            is ObjectBindingPattern -> expr.copy(
                elements = expr.elements.map { transformBindingElement(it) }
            )

            is ArrayBindingPattern -> expr.copy(
                elements = expr.elements.map { node ->
                    when (node) {
                        is BindingElement -> transformBindingElement(node)
                        else -> node
                    }
                }
            )

            // JSX: transform to factory calls (jsx: react) or pass through (jsx: preserve)
            is JsxElement -> transformJsxOrPreserve(expr) { transformJsxElement(expr) }
            is JsxSelfClosingElement -> transformJsxOrPreserve(expr) { transformJsxSelfClosingElement(expr) }
            is JsxFragment -> transformJsxOrPreserve(expr) { transformJsxFragment(expr) }

            // Leaf expressions: pass through
            is Identifier -> expr
            is StringLiteralNode -> expr
            is NumericLiteralNode -> expr
            is BigIntLiteralNode -> expr
            is RegularExpressionLiteralNode -> expr
            is NoSubstitutionTemplateLiteralNode -> expr
            is OmittedExpression -> expr
            is MetaProperty -> expr
            is CommaListExpression -> expr.copy(elements = expr.elements.map { transformExpression(it) })
        }
    }

    // ── JSX Transform ────────────────────────────────────────────────────────

    /**
     * Either preserve JSX structure (jsx: preserve, react-native, or null) or apply the transformer.
     * When preserving, sub-expressions (identifiers, attribute values) are still transformed.
     */
    private inline fun transformJsxOrPreserve(expr: Expression, transform: () -> Expression): Expression {
        val result = if (options.jsx == null || options.jsx == "preserve" || options.jsx == "react-native") {
            transformJsxPreserve(expr)
        } else {
            transform()
        }
        // Transfer leading/trailing comments from original JSX node to the transformed result.
        // This preserves comments like `// note` that appear after JSX elements.
        val withLeading = if (!expr.leadingComments.isNullOrEmpty() && result.leadingComments.isNullOrEmpty()) {
            copyExpressionWithLeadingComments(result, expr.leadingComments!!)
        } else result
        return if (!expr.trailingComments.isNullOrEmpty() && withLeading.trailingComments.isNullOrEmpty()) {
            copyExpressionWithTrailingComments(withLeading, expr.trailingComments)
        } else withLeading
    }

    /**
     * Preserves JSX structure while transforming sub-expressions (identifiers, attribute values, children).
     * This handles cases like module-qualified names (Foo → foo_1.default) and type-stripped expressions.
     */
    private fun transformJsxPreserve(expr: Expression): Expression = when (expr) {
        is JsxElement -> {
            val openTransformed = JsxOpeningElement(
                tagName = transformExpression(expr.openingElement.tagName),
                attributes = transformJsxAttributeList(expr.openingElement.attributes),
                pos = expr.openingElement.pos,
                end = expr.openingElement.end,
            )
            val closingTransformed = JsxClosingElement(
                tagName = transformExpression(expr.closingElement.tagName),
                pos = expr.closingElement.pos,
                end = expr.closingElement.end,
            )
            expr.copy(
                openingElement = openTransformed,
                children = transformJsxChildren(expr.children),
                closingElement = closingTransformed,
            )
        }
        is JsxSelfClosingElement -> expr.copy(
            tagName = transformExpression(expr.tagName),
            attributes = transformJsxAttributeList(expr.attributes),
        )
        is JsxFragment -> expr.copy(
            children = transformJsxChildren(expr.children),
        )
        else -> expr
    }

    private fun transformJsxAttributeList(attrs: List<Node>): List<Node> = attrs.map { attr ->
        when (attr) {
            is JsxAttribute -> {
                val newValue = when (val v = attr.value) {
                    is JsxExpressionContainer -> JsxExpressionContainer(
                        expression = v.expression?.let { transformExpression(it) },
                        pos = v.pos, end = v.end,
                    )
                    is Expression -> transformExpression(v)
                    else -> v
                }
                attr.copy(value = newValue)
            }
            is JsxSpreadAttribute -> attr.copy(expression = transformExpression(attr.expression))
            else -> attr
        }
    }

    private fun transformJsxChildren(children: List<Node>): List<Node> = children.map { child ->
        when (child) {
            is JsxText -> child
            is JsxExpressionContainer -> JsxExpressionContainer(
                expression = child.expression?.let { transformExpression(it) },
                pos = child.pos, end = child.end,
            )
            is JsxElement -> transformJsxPreserve(child)
            is JsxSelfClosingElement -> transformJsxPreserve(child)
            is JsxFragment -> transformJsxPreserve(child)
            else -> child
        }
    }

    /**
     * Returns the JSX factory expression (e.g. `React.createElement` or a custom factory).
     * Reads from `jsxFactory` option, then `reactNamespace` option, then defaults to `React.createElement`.
     * If the factory name is invalid (not an identifier or dotted name), falls back to `React.createElement`.
     */
    private fun getJsxFactory(): Expression {
        val raw = options.jsxFactory
            ?: options.reactNamespace?.let { "$it.createElement" }
            ?: "React.createElement"
        // Validate: must be identifier chars and dots only (e.g. "React.createElement", "h")
        val factory = if (isValidJsxFactory(raw)) raw else "React.createElement"
        return buildQualifiedId(factory)
    }

    /** Checks if a jsxFactory string is a valid identifier or dotted qualified name. */
    private fun isValidJsxFactory(name: String): Boolean {
        if (name.isEmpty()) return false
        val parts = name.split('.')
        return parts.all { part -> part.isNotEmpty() && part.all { c -> c.isLetterOrDigit() || c == '_' || c == '$' } &&
                !part[0].isDigit() }
    }

    /**
     * Returns the JSX fragment factory expression (e.g. `React.Fragment`).
     */
    private fun getJsxFragmentFactory(): Expression {
        val raw = options.jsxFragmentFactory
            ?: options.reactNamespace?.let { "$it.Fragment" }
            ?: "React.Fragment"
        val factory = if (isValidJsxFactory(raw)) raw else "React.Fragment"
        return buildQualifiedId(factory)
    }

    /** Builds an Expression from a dotted name like "React.createElement". */
    private fun buildQualifiedId(name: String): Expression {
        val parts = name.split('.')
        var expr: Expression = syntheticId(parts[0])
        for (i in 1 until parts.size) {
            expr = PropertyAccessExpression(expression = expr, name = syntheticId(parts[i]), pos = -1, end = -1)
        }
        return expr
    }

    private fun transformJsxElement(node: JsxElement): Expression =
        buildJsxCall(node.openingElement.tagName, node.openingElement.attributes, node.children,
            openingEnd = node.openingElement.end)

    private fun transformJsxSelfClosingElement(node: JsxSelfClosingElement): Expression =
        buildJsxCall(node.tagName, node.attributes, emptyList())

    private fun transformJsxFragment(node: JsxFragment): Expression =
        buildJsxCall(getJsxFragmentFactory(), emptyList(), node.children, tagIsExpr = true,
            openingEnd = node.pos)

    private fun buildJsxCall(
        tagName: Expression,
        attributes: List<Node>,
        children: List<Node>,
        tagIsExpr: Boolean = false,
        openingEnd: Int = -1,
    ): Expression {
        val factory = getJsxFactory()

        // Tag: lowercase = string literal (intrinsic element), uppercase/qualified = identifier
        val tagExpr: Expression = when {
            tagIsExpr -> tagName
            tagName is Identifier && tagName.text[0].isLowerCase() ->
                StringLiteralNode(text = tagName.text, pos = -1, end = -1)
            else -> transformExpression(tagName)
        }

        // Props: null if no attributes, else object literal
        val propsExpr: Expression = when {
            attributes.isEmpty() -> Identifier(text = "null", pos = -1, end = -1)
            else -> {
                val props = attributes.mapNotNull { attr ->
                    when (attr) {
                        is JsxAttribute -> {
                            val attrValue = attr.value
                            val value: Expression = when {
                                attrValue == null -> Identifier(text = "true", pos = -1, end = -1)
                                attrValue is JsxExpressionContainer -> attrValue.expression?.let { transformExpression(it) }
                                    ?: Identifier(text = "undefined", pos = -1, end = -1)
                                attrValue is Expression -> transformExpression(attrValue)
                                else -> Identifier(text = "undefined", pos = -1, end = -1)
                            }
                            PropertyAssignment(
                                name = Identifier(text = attr.name, pos = -1, end = -1),
                                initializer = value,
                                pos = -1, end = -1,
                            )
                        }
                        is JsxSpreadAttribute -> SpreadAssignment(
                            expression = transformExpression(attr.expression),
                            pos = -1, end = -1,
                        )
                        else -> null
                    }
                }
                val objLit = ObjectLiteralExpression(properties = props, pos = -1, end = -1)
                // Convert spread attributes to Object.assign() for targets below ES2018
                transformObjectLiteral(objLit)
            }
        }

        // Children: filter out empty-ish JSX text, transform remaining
        val childArgs: List<Expression> = children.mapNotNull { child ->
            when (child) {
                is JsxText -> {
                    // Trim and check: TypeScript trims JSX text and discards blank lines
                    val trimmed = child.text.trim()
                    if (trimmed.isEmpty()) null
                    else StringLiteralNode(text = trimmed, pos = -1, end = -1)
                }
                is JsxExpressionContainer -> child.expression?.let { transformExpression(it) }
                is JsxElement -> transformJsxElement(child)
                is JsxSelfClosingElement -> transformJsxSelfClosingElement(child)
                is JsxFragment -> transformJsxFragment(child)
                else -> null
            }
        }

        // Detect if the JSX element spans multiple lines — if so, emit createElement multi-line.
        // Check if any non-trivial child's source position comes after a newline relative
        // to the opening element end.
        // Detect if the JSX element spans multiple lines.
        // If any JsxText child contains a newline, the element is multi-line.
        val isMultiLine = childArgs.isNotEmpty() &&
            children.any { it is JsxText && it.text.contains('\n') }

        return CallExpression(
            expression = factory,
            arguments = listOf(tagExpr, propsExpr) + childArgs,
            multiLine = isMultiLine,
            pos = -1, end = -1,
        )
    }

    /**
     * Transforms an ObjectLiteralExpression, converting object spreads to Object.assign()
     * for targets below ES2018.
     */
    private fun transformObjectLiteral(node: ObjectLiteralExpression): Expression {
        val transformedProps = node.properties.map { transformObjectLiteralElement(it) }
        // Only transform if target < ES2018 and there are spread elements
        if (options.effectiveTarget >= ScriptTarget.ES2018 ||
            transformedProps.none { it is SpreadAssignment }
        ) {
            return node.copy(properties = transformedProps)
        }
        // Build nested Object.assign() calls using left-fold:
        //   { a, ...x, b } → Object.assign({ a }, x, { b }) — leading props as first arg
        //   { ...x, b }    → Object.assign(Object.assign({}, x), { b }) — nested when trailing props
        //   { ...x }       → Object.assign(x) — single spread, no other props
        // Collect leading non-spread props as the initial accumulator object.
        val leadingProps = mutableListOf<Node>()
        var i = 0
        while (i < transformedProps.size && transformedProps[i] !is SpreadAssignment) {
            leadingProps.add(transformedProps[i])
            i++
        }
        val remaining = transformedProps.drop(i)

        // Special case: single spread of an object literal with no leading/trailing props
        // → Object.assign({ a: "a" }) (no empty {} needed since the spread is already an object)
        if (leadingProps.isEmpty() && remaining.size == 1 && remaining[0] is SpreadAssignment) {
            val spreadExpr = (remaining[0] as SpreadAssignment).expression
            if (spreadExpr is ObjectLiteralExpression) {
                return makeObjectAssignCall(listOf(spreadExpr))
            }
        }

        var accumulator: Expression = ObjectLiteralExpression(
            properties = leadingProps.toList(),
            multiLine = false,
            pos = -1, end = -1,
        )
        val pendingProps = mutableListOf<Node>()
        var isFirst = true

        fun flushPending(trailingComma: Boolean = false) {
            if (pendingProps.isEmpty()) return
            val obj = ObjectLiteralExpression(
                properties = pendingProps.toList(),
                multiLine = false,
                hasTrailingComma = trailingComma,
                pos = -1, end = -1,
            )
            accumulator = makeObjectAssignCall(listOf(accumulator, obj))
            pendingProps.clear()
            isFirst = false
        }

        for (prop in remaining) {
            if (prop is SpreadAssignment) {
                flushPending()
                // Transfer trailing comment from SpreadAssignment to its expression argument
                val spreadExpr = prop.expression.withTrailingComments(prop.trailingComments)
                if (isFirst) {
                    // First spread: start the chain — accumulator already holds leading props (or {})
                    accumulator = makeObjectAssignCall(listOf(accumulator, spreadExpr))
                    isFirst = false
                } else {
                    accumulator = makeObjectAssignCall(listOf(accumulator, spreadExpr))
                }
            } else {
                pendingProps.add(prop)
            }
        }
        flushPending(trailingComma = node.hasTrailingComma)
        return accumulator
    }

    private fun makeObjectAssignCall(args: List<Expression>): CallExpression {
        return CallExpression(
            expression = PropertyAccessExpression(
                expression = syntheticId("Object"),
                name = syntheticId("assign"),
            ),
            arguments = args,
        )
    }

    private fun transformObjectLiteralElement(node: Node): Node {
        return when (node) {
            is PropertyAssignment -> node.copy(
                initializer = transformExpression(node.initializer),
            )

            is ShorthandPropertyAssignment -> node.copy(
                objectAssignmentInitializer = node.objectAssignmentInitializer?.let {
                    transformExpression(it)
                }
            )

            is SpreadAssignment -> node.copy(
                expression = transformExpression(node.expression),
            )

            is MethodDeclaration -> transformMethodDeclarationElement(node)
            is GetAccessor -> transformGetAccessorElement(node)
            is SetAccessor -> transformSetAccessorElement(node)
            else -> node
        }
    }

    private fun transformMethodDeclarationElement(method: MethodDeclaration): MethodDeclaration {
        val strippedModifiers = stripMemberModifiers(method.modifiers)
        val isAsync = ModifierFlag.Async in method.modifiers && options.effectiveTarget < ScriptTarget.ES2017
        val isAsyncGenerator = isAsync && method.asteriskToken
        val prevInAsyncBody = inAsyncBody
        val prevInAsyncGenBody = inAsyncGeneratorBody
        inAsyncBody = isAsync && !isAsyncGenerator
        inAsyncGeneratorBody = isAsyncGenerator
        val transformedBody = method.body?.let { transformBlock(it, isFunctionScope = true) }
        inAsyncBody = prevInAsyncBody
        inAsyncGeneratorBody = prevInAsyncGenBody
        if (isAsyncGenerator && transformedBody != null) {
            needsAsyncGeneratorHelper = true
            val methodName = (method.name as? Identifier)?.text
            val innerName = methodName?.let { "${it}_1" }
            val transformedParams = transformParameters(method.parameters)
            val asyncGenBody = Block(
                statements = listOf(ReturnStatement(expression = makeAsyncGeneratorCall(
                    syntheticId("this"), syntheticId("arguments"),
                    innerName, transformedParams, transformedBody
                ))),
                multiLine = true,
            )
            return method.copy(
                typeParameters = null,
                parameters = transformedParams,
                type = null,
                body = asyncGenBody,
                modifiers = strippedModifiers - ModifierFlag.Async,
                asteriskToken = false,
            )
        }
        if (isAsync && transformedBody != null) {
            needsAwaiterHelper = true
            val awaiterBody = Block(
                statements = listOf(ReturnStatement(expression = makeAwaiterCall(syntheticId("this"), body = transformedBody))),
                multiLine = true,
            )
            return method.copy(
                typeParameters = null,
                parameters = transformParameters(method.parameters),
                type = null,
                body = awaiterBody,
                modifiers = strippedModifiers - ModifierFlag.Async,
                asteriskToken = false,
            )
        }
        return method.copy(
            typeParameters = null,
            parameters = transformParameters(method.parameters),
            type = null,
            body = transformedBody,
            modifiers = strippedModifiers,
        )
    }

    private fun transformGetAccessorElement(accessor: GetAccessor): GetAccessor {
        return accessor.copy(
            parameters = transformParameters(accessor.parameters),
            type = null,
            body = accessor.body?.let { transformBlock(it, isFunctionScope = true) },
            modifiers = stripMemberModifiers(accessor.modifiers),
        )
    }

    private fun transformSetAccessorElement(accessor: SetAccessor): SetAccessor {
        return accessor.copy(
            parameters = transformParameters(accessor.parameters),
            body = accessor.body?.let { transformBlock(it, isFunctionScope = true) },
            modifiers = stripMemberModifiers(accessor.modifiers),
        )
    }

    private fun transformArrowBody(body: Node): Node {
        return when (body) {
            is Block -> transformBlock(body, isFunctionScope = true)
            is Expression -> transformExpression(body)
            else -> body
        }
    }

    // -----------------------------------------------------------------
    // Parameter transforms
    // -----------------------------------------------------------------

    private fun transformParameters(params: List<Parameter>): List<Parameter> {
        return params.map { param ->
            param.copy(
                name = transformBindingName(param.name),
                type = null,
                initializer = param.initializer?.let { transformExpression(it) },
                questionToken = false,
                modifiers = emptySet(),
            )
        }
    }

    /**
     * Checks if a template span raw text contains an invalid escape sequence
     * (ES2018 template literal cooked value = undefined).
     */
    private fun hasInvalidTemplateEscape(raw: String): Boolean {
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '\\') { i++; continue }
            i++ // past backslash
            if (i >= raw.length) return false
            when (raw[i]) {
                'u' -> {
                    i++
                    if (i < raw.length && raw[i] == '{') {
                        i++ // past {
                        val hexStart = i
                        while (i < raw.length && raw[i] != '}') i++
                        if (i >= raw.length) return true // no closing }
                        val hexStr = raw.substring(hexStart, i)
                        if (hexStr.isEmpty()) return true // \u{}
                        for (c in hexStr) if (c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') return true
                        if (hexStr.toLong(16) > 0x10FFFFL) return true
                        i++ // past }
                    } else {
                        // Need exactly 4 hex digits
                        var count = 0
                        while (count < 4 && i < raw.length &&
                            (raw[i] in '0'..'9' || raw[i] in 'a'..'f' || raw[i] in 'A'..'F')
                        ) { i++; count++ }
                        if (count < 4) return true
                    }
                }
                'x' -> {
                    i++
                    var count = 0
                    while (count < 2 && i < raw.length &&
                        (raw[i] in '0'..'9' || raw[i] in 'a'..'f' || raw[i] in 'A'..'F')
                    ) { i++; count++ }
                    if (count < 2) return true
                }
                '0' -> {
                    i++ // past '0'
                    if (i < raw.length && raw[i].isDigit()) return true // \0 followed by digit
                }
                in '1'..'9' -> return true // legacy octal / non-octal decimal escape
                else -> i++ // valid simple escape (\n, \t, \\, \`, etc.)
            }
        }
        return false
    }

    /**
     * Decodes a valid template span raw text to its cooked string value.
     * Call only when hasInvalidTemplateEscape returned false.
     */
    private fun cookTemplateSpanText(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '\\') { sb.append(raw[i]); i++; continue }
            i++ // past backslash
            if (i >= raw.length) break
            when (val esc = raw[i]) {
                'n' -> { sb.append('\n'); i++ }
                'r' -> { sb.append('\r'); i++ }
                't' -> { sb.append('\t'); i++ }
                'b' -> { sb.append('\b'); i++ }
                'f' -> { sb.append('\u000C'); i++ }
                'v' -> { sb.append('\u000B'); i++ }
                '0' -> { sb.append('\u0000'); i++ }
                'u' -> {
                    i++
                    if (i < raw.length && raw[i] == '{') {
                        i++
                        val hexStart = i
                        while (i < raw.length && raw[i] != '}') i++
                        val codePoint = raw.substring(hexStart, i).toLong(16)
                        val cp = codePoint.toInt()
                        if (cp <= 0xFFFF) {
                            sb.append(cp.toChar())
                        } else {
                            // Supplementary code point → surrogate pair
                            sb.append(Char.MIN_HIGH_SURROGATE + ((cp - 0x10000) shr 10))
                            sb.append(Char.MIN_LOW_SURROGATE + ((cp - 0x10000) and 0x3FF))
                        }
                        i++ // past }
                    } else {
                        val hex = raw.substring(i, i + 4)
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }
                }
                'x' -> {
                    i++
                    val hex = raw.substring(i, i + 2)
                    sb.append(hex.toInt(16).toChar())
                    i += 2
                }
                '\r' -> { i++; if (i < raw.length && raw[i] == '\n') i++ }
                '\n' -> i++
                else -> { sb.append(esc); i++ }
            }
        }
        return sb.toString()
    }

    /**
     * Transforms a tagged template expression with invalid escape sequences to use __makeTemplateObject.
     * Tagged templates with invalid escapes must pass raw strings to the tag function since the
     * cooked value would be undefined.
     */
    private fun transformTaggedTemplate(expr: TaggedTemplateExpression): Expression {
        val template = expr.template
        // Get the raw text of each template span (as stored by scanner, backslashes preserved)
        val rawTexts: List<String> = when (template) {
            is NoSubstitutionTemplateLiteralNode -> listOf(template.text)
            is TemplateExpression -> {
                val raws = mutableListOf(template.head.text)
                template.templateSpans.forEach { raws.add((it.literal as? StringLiteralNode)?.text ?: "") }
                raws
            }
            else -> return expr.copy(tag = transformExpression(expr.tag), typeArguments = null)
        }

        val hasInvalid = rawTexts.any { hasInvalidTemplateEscape(it) }
        if (!hasInvalid) {
            // All spans valid: transform expressions inside but keep the tagged template form
            return when (template) {
                is NoSubstitutionTemplateLiteralNode ->
                    expr.copy(tag = transformExpression(expr.tag), typeArguments = null)
                is TemplateExpression ->
                    expr.copy(
                        tag = transformExpression(expr.tag),
                        typeArguments = null,
                        template = template.copy(
                            templateSpans = template.templateSpans.map { span ->
                                span.copy(expression = transformExpression(span.expression))
                            }
                        )
                    )
            }
        }

        // Has invalid escapes: transform to tag(__makeTemplateObject([cooked...], [raw...]), ...exprs)
        needsMakeTemplateObjectHelper = true

        val void0 = VoidExpression(
            expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1
        )

        // Build cooked array: void 0 for invalid spans, string literal for valid spans
        val cookedElements: List<Expression> = rawTexts.map { raw ->
            if (hasInvalidTemplateEscape(raw)) {
                void0
            } else {
                StringLiteralNode(text = cookTemplateSpanText(raw), pos = -1, end = -1)
            }
        }

        // Build raw array: the raw source text of each span (escapeString will double backslashes)
        val rawElements: List<Expression> = rawTexts.map { raw ->
            StringLiteralNode(text = raw, pos = -1, end = -1)
        }

        val makeTemplateCall = CallExpression(
            expression = syntheticId("__makeTemplateObject"),
            arguments = listOf(
                ArrayLiteralExpression(elements = cookedElements, pos = -1, end = -1),
                ArrayLiteralExpression(elements = rawElements, pos = -1, end = -1)
            ),
            pos = -1, end = -1
        )

        val expressions: List<Expression> = when (template) {
            is TemplateExpression -> template.templateSpans.map { transformExpression(it.expression) }
            else -> emptyList()
        }

        return CallExpression(
            expression = transformExpression(expr.tag),
            typeArguments = null,
            arguments = listOf(makeTemplateCall) + expressions,
            pos = expr.pos, end = expr.end,
            leadingComments = expr.leadingComments,
            trailingComments = expr.trailingComments
        )
    }

    /**
     * When any parameter has ObjectBindingPattern with rest elements (e.g. `{ a, ...rest } = {}`),
     * replace it with a temp var and prepend a `var { a } = _temp, rest = __rest(_temp, ["a"])`
     * statement to the function body (after prologue directives).
     *
     * Returns the new (params, body) pair. Also sets needsRestHelper if applicable.
     */
    private fun flattenRestParameters(
        params: List<Parameter>,
        body: Block,
    ): Pair<List<Parameter>, Block> {
        if (options.effectiveTarget >= ScriptTarget.ES2018) return Pair(params, body)
        val newParams = mutableListOf<Parameter>()
        val restStmts = mutableListOf<VariableStatement>()
        for (param in params) {
            val name = param.name
            if (name is ObjectBindingPattern && name.elements.any { it.dotDotDotToken }) {
                val tempName = nextTempVarName()
                val tempId = syntheticId(tempName)
                // Replace parameter with temp var keeping the default initializer
                newParams.add(param.copy(
                    name = tempId,
                    type = null,
                    initializer = param.initializer?.let { transformExpression(it) },
                    questionToken = false,
                    modifiers = emptySet(),
                ))
                // Build `var { a } = tempId, rest = __rest(tempId, ["a"])`
                val restDecl = transformVariableDeclarationListWithRest(
                    VariableDeclarationList(
                        flags = VarKeyword,
                        declarations = listOf(VariableDeclaration(name = name, initializer = tempId, pos = -1, end = -1)),
                        pos = -1, end = -1,
                    )
                )
                restStmts.add(VariableStatement(declarationList = restDecl, pos = -1, end = -1))
                needsRestHelper = true
            } else {
                newParams.add(param.copy(
                    name = transformBindingName(name),
                    type = null,
                    initializer = param.initializer?.let { transformExpression(it) },
                    questionToken = false,
                    modifiers = emptySet(),
                ))
            }
        }
        if (restStmts.isEmpty()) return Pair(params.map { param ->
            param.copy(
                name = transformBindingName(param.name),
                type = null,
                initializer = param.initializer?.let { transformExpression(it) },
                questionToken = false,
                modifiers = emptySet(),
            )
        }, body)
        // Insert after prologue directives
        val bodyStmts = body.statements.toMutableList()
        val insertAt = bodyStmts.indexOfFirst { stmt ->
            !(stmt is ExpressionStatement && stmt.expression is StringLiteralNode)
        }.let { if (it < 0) bodyStmts.size else it }
        bodyStmts.addAll(insertAt, restStmts)
        // Preserve multiLine = false for originally-empty single-line bodies (e.g. `{ }`),
        // so the result is emitted as `{ var ...; }` on one line like TypeScript does.
        val newMultiLine = body.multiLine || body.statements.isNotEmpty()
        return Pair(newParams, body.copy(statements = bodyStmts, multiLine = newMultiLine))
    }

    // -----------------------------------------------------------------
    // Block transform
    // -----------------------------------------------------------------

    private fun transformBlock(block: Block, isFunctionScope: Boolean = false): Block {
        if (isFunctionScope) functionScopeDepth++
        val result = block.copy(statements = transformStatements(block.statements, isFunctionScope = isFunctionScope))
        if (isFunctionScope) functionScopeDepth--
        return result
    }

    // -----------------------------------------------------------------
    // Control flow transforms
    // -----------------------------------------------------------------

    private fun transformIfStatement(stmt: IfStatement): Statement {
        return stmt.copy(
            expression = transformExpression(stmt.expression),
            thenStatement = transformStatementSingle(stmt.thenStatement),
            elseStatement = stmt.elseStatement?.let { transformStatementSingle(it) },
        )
    }

    private fun transformForStatement(stmt: ForStatement): Statement {
        val init = when (val i = stmt.initializer) {
            is VariableDeclarationList -> transformVariableDeclarationList(i)
            is Expression -> transformExpression(i)
            null -> null
            else -> i
        }
        return stmt.copy(
            initializer = init,
            condition = stmt.condition?.let { transformExpression(it) },
            incrementor = stmt.incrementor?.let { transformExpression(it) },
            statement = transformStatementSingle(stmt.statement),
        )
    }

    private fun transformForInStatement(stmt: ForInStatement): Statement {
        val init = when (val i = stmt.initializer) {
            is VariableDeclarationList -> transformVariableDeclarationList(i)
            is Expression -> transformExpression(i)
            else -> i
        }
        return stmt.copy(
            initializer = init,
            expression = transformExpression(stmt.expression),
            statement = transformStatementSingle(stmt.statement),
        )
    }

    private fun transformForOfStatement(stmt: ForOfStatement): Statement {
        val i = stmt.initializer
        // Handle `for (let/const/var { a, ...rest } of expr)` when target < ES2018:
        // Replace the destructuring variable with a temp var and expand inside the loop body.
        if (i is VariableDeclarationList && i.declarations.size == 1 &&
            options.effectiveTarget < ScriptTarget.ES2018) {
            val decl = i.declarations[0]
            val name = decl.name
            if (name is ObjectBindingPattern && name.elements.any { it.dotDotDotToken }) {
                val tempName = nextTempVarName()
                val tempId = syntheticId(tempName)
                // New for-of initializer: same keyword, single temp var (no init)
                val newInit = i.copy(declarations = listOf(VariableDeclaration(
                    name = tempId, pos = -1, end = -1,
                )))
                // Build the destructuring statement from the temp var:
                // `let { a } = tempId, rest = __rest(tempId, ["a"])`
                val restDecl = transformVariableDeclarationListWithRest(
                    i.copy(declarations = listOf(decl.copy(name = name, initializer = tempId)))
                )
                val restStmt = VariableStatement(declarationList = restDecl, pos = -1, end = -1)
                needsRestHelper = true
                // Wrap original body to also execute the destructuring first
                val origBody = transformStatementSingle(stmt.statement)
                val newBodyStmts = mutableListOf<Statement>(restStmt)
                when (origBody) {
                    is Block -> newBodyStmts.addAll(origBody.statements)
                    else -> newBodyStmts.add(origBody)
                }
                return stmt.copy(
                    initializer = newInit,
                    expression = transformExpression(stmt.expression),
                    statement = Block(statements = newBodyStmts, multiLine = true, pos = -1, end = -1),
                )
            }
        }
        val init = when (i) {
            is VariableDeclarationList -> transformVariableDeclarationList(i)
            is Expression -> transformExpression(i)
            else -> i
        }
        return stmt.copy(
            initializer = init,
            expression = transformExpression(stmt.expression),
            statement = transformStatementSingle(stmt.statement),
        )
    }

    private fun transformSwitchStatement(stmt: SwitchStatement): Statement {
        return stmt.copy(
            expression = transformExpression(stmt.expression),
            caseBlock = stmt.caseBlock.map { clause ->
                when (clause) {
                    is CaseClause -> clause.copy(
                        expression = transformExpression(clause.expression),
                        statements = transformStatements(clause.statements),
                    )

                    is DefaultClause -> clause.copy(
                        statements = transformStatements(clause.statements),
                    )

                    else -> clause
                }
            }
        )
    }

    private fun transformTryStatement(stmt: TryStatement): Statement {
        return stmt.copy(
            tryBlock = transformBlock(stmt.tryBlock),
            catchClause = stmt.catchClause?.let { cc ->
                val varDecl = cc.variableDeclaration?.let { transformVariableDeclaration(it) }
                    ?: if (options.effectiveTarget < ScriptTarget.ES2019) {
                        // Optional catch binding (ES2019+) needs a dummy `_a` for older targets
                        VariableDeclaration(name = syntheticId("_a"))
                    } else null
                cc.copy(variableDeclaration = varDecl, block = transformBlock(cc.block))
            },
            finallyBlock = stmt.finallyBlock?.let { transformBlock(it) },
        )
    }

    // -----------------------------------------------------------------
    // Import / Export transforms
    // -----------------------------------------------------------------

    private fun transformImportDeclaration(decl: ImportDeclaration): List<Statement> {
        // Type-only import: remove entirely
        if (decl.importClause?.isTypeOnly == true) return emptyList()

        // Strip type-only specifiers from named imports
        val clause = decl.importClause
        if (clause != null) {
            val bindings = clause.namedBindings
            if (bindings is NamedImports) {
                val filtered = bindings.elements.filter { !it.isTypeOnly }
                if (filtered.isEmpty() && clause.name == null) {
                    // All specifiers were type-only and there's no default import
                    return emptyList()
                }
                val newBindings = if (filtered.size == bindings.elements.size) {
                    bindings
                } else if (filtered.isEmpty()) {
                    null
                } else {
                    bindings.copy(elements = filtered)
                }
                return listOf(
                    decl.copy(
                        importClause = clause.copy(namedBindings = newBindings),
                    )
                )
            }
        }

        return listOf(decl)
    }

    private fun transformImportEqualsDeclaration(
        decl: ImportEqualsDeclaration
    ): List<Statement> {
        if (decl.isTypeOnly) return emptyList()

        // Strict-mode reserved words cannot be used as variable names — erase the declaration.
        // E.g. `import public = require("1")` in a strict-mode file produces no output.
        if (decl.name.text in strictModeReservedWords) return emptyList()

        // Erase import if the referenced name is type-only (interface, type alias, or type-only namespace).
        // Exception: exported import aliases (export import a = X.Y) are always emitted since
        // they create explicitly exported bindings that other modules may depend on.
        val ref = decl.moduleReference
        val isExported = ModifierFlag.Export in decl.modifiers
        if (!isExported) {
            if (ref is Identifier && ref.text in topLevelTypeOnlyNames) {
                return emptyList()
            }
            if (ref is QualifiedName && isQualifiedPathTypeOnly(ref)) {
                return emptyList()
            }
        }

        // `import x = M.N` → `var x = M.N;`
        // `import x = require("mod")` → `const x = require("mod")`
        // `import x = 5` (invalid, literal RHS) → `5;` (expression statement, TypeScript's error-recovery output)
        val isRequire = ref is ExternalModuleReference

        // In ESM mode, `import x = require("mod")` is not valid — drop it entirely
        if (isRequire && isESModuleFormat(options.effectiveModule, currentFileName)) {
            return emptyList()
        }

        // Literal module references (numbers, strings, null keyword) are invalid and produce expression statements.
        // Since these are parsed via parseIdentifierName(), they appear as Identifier nodes:
        //   - numeric literal: ref.text starts with a digit (e.g. "5")
        //   - string literal: ref.rawText starts with a quote (e.g. rawText = "\"s\"")
        //   - null keyword: ref.text == "null"
        if (ref is Identifier) {
            val isLiteralRef = ref.text == "null" ||
                    (ref.text.isNotEmpty() && ref.text[0].isDigit()) ||
                    (ref.rawText != null && (ref.rawText.startsWith("\"") || ref.rawText.startsWith("'")))
            if (isLiteralRef) {
                return listOf(
                    ExpressionStatement(
                        expression = ref,
                        pos = decl.pos, end = decl.end,
                        leadingComments = decl.leadingComments,
                        trailingComments = decl.trailingComments,
                    )
                )
            }
        }

        val initializer: Expression = when (ref) {
            is ExternalModuleReference -> {
                CallExpression(
                    expression = syntheticId("require"),
                    arguments = listOf(normalizeModuleSpecifier(transformExpression(ref.expression))),
                    pos = -1, end = -1,
                )
            }

            is Expression -> transformExpression(ref)
            is QualifiedName -> qualifiedNameToPropertyAccess(ref)
            else -> syntheticId(decl.name.text)
        }

        return listOf(
            VariableStatement(
                declarationList = VariableDeclarationList(
                    declarations = listOf(
                        VariableDeclaration(
                            name = decl.name,
                            initializer = initializer,
                            pos = -1, end = -1,
                        )
                    ),
                    flags = if (isRequire) ConstKeyword else VarKeyword,
                    pos = -1, end = -1,
                ),
                modifiers = stripTypeScriptModifiers(decl.modifiers),
                pos = decl.pos, end = decl.end,
                leadingComments = decl.leadingComments,
                trailingComments = decl.trailingComments,
            )
        )
    }

    private fun transformExportDeclaration(decl: ExportDeclaration): List<Statement> {
        if (decl.isTypeOnly) return orphanedComments(decl)

        // Strip type-only export specifiers. This includes both:
        // 1. Specifiers explicitly marked as type-only: `export { type A }`
        // 2. Specifiers (without moduleSpecifier) whose local name refers only to a top-level
        //    interface or type alias — e.g. `export { A, B }` where A and B are interfaces.
        //    TypeScript erases these since interfaces produce no runtime value.
        val clause = decl.exportClause
        if (clause is NamedExports) {
            val filtered = clause.elements.filter { spec ->
                if (spec.isTypeOnly) return@filter false
                // For re-exports (with moduleSpecifier), check target module types via checker
                val moduleSpec = decl.moduleSpecifier
                if (moduleSpec != null) {
                    val specifier = (moduleSpec as? StringLiteralNode)?.text ?: return@filter true
                    val exportName = (spec.propertyName ?: spec.name).text
                    return@filter checker?.isValueExport(exportName, specifier, currentFileName) ?: true
                }
                // For local exports, filter out names that are declared only as types in this file
                val localName = (spec.propertyName ?: spec.name).text
                localName !in topLevelTypeOnlyNames
            }
            if (filtered.isEmpty()) return orphanedComments(decl)
            if (filtered.size != clause.elements.size) {
                return listOf(
                    decl.copy(exportClause = clause.copy(elements = filtered))
                )
            }
        }

        return listOf(decl)
    }

    /**
     * Transforms an `export default X` or `export = X` statement.
     * When the expression is a simple identifier that refers only to a top-level type declaration
     * (interface or type-only namespace), the entire export is erased — TypeScript produces no
     * runtime output for such "non-instantiated" exports.
     */
    private fun transformExportAssignment(stmt: ExportAssignment): List<Statement> {
        // Erase `export default X` when X is declared only as a type (no runtime value)
        if (!stmt.isExportEquals) {
            val expr = stmt.expression
            if (expr is Identifier && expr.text in topLevelTypeOnlyNames) {
                return orphanedComments(stmt)
            }
        }
        return listOf(stmt.copy(expression = transformExpression(stmt.expression)))
    }

    // -----------------------------------------------------------------
    // Class declaration transform
    // -----------------------------------------------------------------

    private fun transformClassDeclaration(decl: ClassDeclaration): List<Statement> {
        val result = transformClassBody(
            name = decl.name,
            typeParameters = decl.typeParameters,
            heritageClauses = decl.heritageClauses,
            members = decl.members,
            modifiers = decl.modifiers,
        )

        val hasClassDecorators = options.experimentalDecorators && !decl.decorators.isNullOrEmpty()
        val hasAnyDecorators = options.experimentalDecorators && (
            hasClassDecorators || classHasMemberDecorators(decl)
        )

        if (!hasAnyDecorators) {
            // No decorators — emit normally
            val transformedClass = decl.copy(
                typeParameters = null,
                heritageClauses = result.heritageClauses,
                members = result.members,
                modifiers = stripTypeScriptModifiers(decl.modifiers) - ModifierFlag.Abstract,
                decorators = null,
            )
            return listOf(transformedClass) + result.trailingStatements
        }

        // --- Legacy decorator transform ---
        needsDecorateHelper = true
        val isDefault = ModifierFlag.Default in decl.modifiers
        val className = decl.name?.text
            ?: if (isDefault) "default_${++anonDefaultCounter}" else null
        if (className == null) return listOf(decl.copy(
            typeParameters = null,
            heritageClauses = result.heritageClauses,
            members = result.members,
            modifiers = stripTypeScriptModifiers(decl.modifiers) - ModifierFlag.Abstract,
            decorators = null,
        )) + result.trailingStatements
        val syntheticName = if (decl.name == null) syntheticId(className) else null

        val strippedModifiers = stripTypeScriptModifiers(decl.modifiers) - ModifierFlag.Abstract

        val statements = mutableListOf<Statement>()

        if (hasClassDecorators) {
            // Change: `class Foo { ... }` → `let Foo = class Foo { ... };`
            val classExpr = ClassExpression(
                name = decl.name,
                typeParameters = null,
                heritageClauses = result.heritageClauses,
                members = result.members,
                modifiers = strippedModifiers - ModifierFlag.Export - ModifierFlag.Default,
                pos = decl.pos,
                end = decl.end,
            )
            val varDecl = VariableStatement(
                declarationList = VariableDeclarationList(
                    declarations = listOf(
                        VariableDeclaration(
                            name = syntheticId(className),
                            initializer = classExpr,
                            pos = -1, end = -1,
                        )
                    ),
                    flags = SyntaxKind.LetKeyword,
                    pos = -1, end = -1,
                ),
                modifiers = if (ModifierFlag.Export in strippedModifiers) setOf(ModifierFlag.Export) else emptySet(),
                pos = decl.pos, end = decl.end,
                leadingComments = decl.leadingComments,
            )
            statements.add(varDecl)
        } else {
            // Class itself has no decorators, emit as normal class declaration
            val transformedClass = decl.copy(
                typeParameters = null,
                heritageClauses = result.heritageClauses,
                members = result.members,
                modifiers = strippedModifiers,
                decorators = null,
            )
            statements.add(transformedClass)
        }

        // Add trailing statements from class body transform (static property assignments, etc.)
        statements.addAll(result.trailingStatements)

        // Emit __decorate calls for decorated members
        val classTypeParams = decl.typeParameters?.map { it.name.text }?.toSet() ?: emptySet()
        statements.addAll(generateMemberDecorateStatements(className, decl.members, classTypeParams))

        // Emit class-level __decorate call
        if (hasClassDecorators) {
            statements.add(generateClassDecorateStatement(className, decl.decorators))
        }

        return statements
    }

    /** Check if any member of a class has decorators (including parameter decorators). */
    private fun classHasMemberDecorators(decl: ClassDeclaration): Boolean {
        return decl.members.any { member ->
            when (member) {
                is PropertyDeclaration -> !member.decorators.isNullOrEmpty()
                is MethodDeclaration -> !member.decorators.isNullOrEmpty() ||
                    member.parameters.any { !it.decorators.isNullOrEmpty() }
                is GetAccessor -> !member.decorators.isNullOrEmpty()
                is SetAccessor -> !member.decorators.isNullOrEmpty()
                is Constructor -> member.parameters.any { !it.decorators.isNullOrEmpty() }
                else -> false
            }
        }
    }

    /** Generate `__decorate([...], ClassName.prototype, "memberName", null/void 0)` for each decorated member. */
    private fun generateMemberDecorateStatements(className: String, members: List<ClassElement>, classTypeParams: Set<String> = emptySet()): List<Statement> {
        val stmts = mutableListOf<Statement>()
        for (member in members) {
            val decorators: List<Decorator>?
            val memberName: String?
            val isStatic: Boolean
            val isProperty: Boolean
            val paramDecorators: List<Pair<Int, List<Decorator>>>
            // Type info for emitDecoratorMetadata
            val propertyTypeNode: TypeNode?       // for properties
            val methodReturnTypeNode: TypeNode?   // for methods
            val methodParamTypeNodes: List<TypeNode?> // for methods
            val methodIsAsync: Boolean            // for inferring Promise return type

            when (member) {
                is PropertyDeclaration -> {
                    decorators = member.decorators
                    memberName = getMemberNameText(member.name)
                    isStatic = ModifierFlag.Static in member.modifiers
                    isProperty = true
                    paramDecorators = emptyList()
                    propertyTypeNode = member.type
                    methodReturnTypeNode = null
                    methodParamTypeNodes = emptyList()
                    methodIsAsync = false
                }
                is MethodDeclaration -> {
                    decorators = member.decorators
                    memberName = getMemberNameText(member.name)
                    isStatic = ModifierFlag.Static in member.modifiers
                    isProperty = false
                    paramDecorators = member.parameters.mapIndexedNotNull { idx, param ->
                        if (!param.decorators.isNullOrEmpty()) idx to param.decorators else null
                    }
                    propertyTypeNode = null
                    methodReturnTypeNode = member.type
                    methodParamTypeNodes = member.parameters.map { param ->
                        // For rest parameters (...x: T[]), serialize element type T not Array
                        if (param.dotDotDotToken) (param.type as? ArrayType)?.elementType ?: param.type
                        else param.type
                    }
                    methodIsAsync = ModifierFlag.Async in member.modifiers
                }
                is GetAccessor -> {
                    decorators = member.decorators
                    memberName = getMemberNameText(member.name)
                    isStatic = ModifierFlag.Static in member.modifiers
                    isProperty = false
                    paramDecorators = emptyList()
                    propertyTypeNode = null
                    methodReturnTypeNode = member.type
                    methodParamTypeNodes = emptyList()
                    methodIsAsync = false
                }
                is SetAccessor -> {
                    decorators = member.decorators
                    memberName = getMemberNameText(member.name)
                    isStatic = ModifierFlag.Static in member.modifiers
                    isProperty = false
                    paramDecorators = emptyList()
                    propertyTypeNode = null
                    methodReturnTypeNode = null
                    methodParamTypeNodes = member.parameters.map { it.type }
                    methodIsAsync = false
                }
                is Constructor -> {
                    // Constructor parameter decorators
                    val ctorParamDecs = member.parameters.mapIndexedNotNull { idx, param ->
                        if (!param.decorators.isNullOrEmpty()) idx to param.decorators else null
                    }
                    if (ctorParamDecs.isEmpty()) continue
                    // Constructor parameter decorators go as __decorate on the class constructor
                    // They are emitted as the class-level __decorate call, not here
                    // Actually, constructor param decorators are included in the class __decorate call
                    // Skip for now — handled in class decorator generation
                    continue
                }
                else -> continue
            }

            val hasMethodDecorators = !decorators.isNullOrEmpty()
            val hasParamDecorators = paramDecorators.isNotEmpty()

            if (!hasMethodDecorators && !hasParamDecorators) continue
            if (memberName == null) continue

            // Build the decorator array
            val decoratorExprs = mutableListOf<Expression>()

            // Add method/property decorators first
            if (hasMethodDecorators) {
                for (dec in decorators) {
                    val transformed = transformExpression(dec.expression)
                    // Preserve trailing comments from the Decorator node (e.g. // comment after @dec(expr))
                    val withComments = if (!dec.trailingComments.isNullOrEmpty()) {
                        val merged = (transformed.trailingComments.orEmpty() + dec.trailingComments).ifEmpty { null }
                        copyExpressionWithTrailingComments(transformed, merged)
                    } else transformed
                    decoratorExprs.add(withComments)
                }
            }

            // Add __param entries for parameter decorators
            if (hasParamDecorators) {
                needsParamHelper = true
                for ((paramIndex, paramDecs) in paramDecorators) {
                    for (dec in paramDecs) {
                        decoratorExprs.add(
                            CallExpression(
                                expression = syntheticId("__param"),
                                arguments = listOf(
                                    NumericLiteralNode(text = paramIndex.toString(), pos = -1, end = -1),
                                    transformExpression(dec.expression),
                                ),
                                pos = -1, end = -1,
                            )
                        )
                    }
                }
            }

            // Add __metadata entries for emitDecoratorMetadata
            if (options.emitDecoratorMetadata && (hasMethodDecorators || hasParamDecorators)) {
                needsMetadataHelper = true
                if (isProperty) {
                    // design:type for properties
                    decoratorExprs.add(makeMetadataCall("design:type",
                        serializeTypeNode(propertyTypeNode, classTypeParams)))
                } else {
                    // design:type = Function for methods/accessors
                    decoratorExprs.add(makeMetadataCall("design:type", syntheticId("Function")))
                    // design:paramtypes
                    val paramTypes = ArrayLiteralExpression(
                        elements = methodParamTypeNodes.map { serializeTypeNode(it, classTypeParams) },
                        multiLine = false,
                        pos = -1, end = -1,
                    )
                    decoratorExprs.add(makeMetadataCall("design:paramtypes", paramTypes))
                    // design:returntype: emit for non-void annotated types, Promise for async, void 0 otherwise
                    val retNode = methodReturnTypeNode
                    when {
                        retNode != null && !isVoidTypeNode(retNode) ->
                            decoratorExprs.add(makeMetadataCall("design:returntype",
                                serializeTypeNode(retNode, classTypeParams)))
                        retNode == null && methodIsAsync ->
                            // async methods implicitly return Promise<T>
                            decoratorExprs.add(makeMetadataCall("design:returntype",
                                syntheticId("Promise")))
                        else ->
                            // no annotation or void annotation → emit void 0
                            decoratorExprs.add(makeMetadataCall("design:returntype",
                                VoidExpression(expression = NumericLiteralNode(text = "0", pos = -1, end = -1), pos = -1, end = -1)))
                    }
                }
            }

            // Build: __decorate([...decorators], ClassName.prototype, "memberName", null/void 0)
            val target = if (isStatic) {
                syntheticId(className)
            } else {
                PropertyAccessExpression(
                    expression = syntheticId(className),
                    name = Identifier(text = "prototype", pos = -1, end = -1),
                    pos = -1, end = -1,
                )
            }

            val fourthArg: Expression = if (isProperty) {
                VoidExpression(
                    expression = NumericLiteralNode(text = "0", pos = -1, end = -1),
                    pos = -1, end = -1,
                )
            } else {
                syntheticId("null")
            }

            val call = CallExpression(
                expression = syntheticId("__decorate"),
                arguments = listOf(
                    ArrayLiteralExpression(
                        elements = decoratorExprs,
                        multiLine = true,
                        pos = -1, end = -1,
                    ),
                    target,
                    StringLiteralNode(text = memberName, pos = -1, end = -1),
                    fourthArg,
                ),
                pos = -1, end = -1,
            )

            stmts.add(ExpressionStatement(expression = call, pos = -1, end = -1))
        }
        return stmts
    }

    /** Build `__metadata("key", value)` call expression. */
    private fun makeMetadataCall(key: String, value: Expression): Expression =
        CallExpression(
            expression = syntheticId("__metadata"),
            arguments = listOf(StringLiteralNode(text = key, pos = -1, end = -1), value),
            pos = -1, end = -1,
        )

    /**
     * Serialize a TypeNode to a runtime type expression for `emitDecoratorMetadata`.
     *
     * Rules (syntactic, no type checker):
     * - null (no annotation) → Object
     * - string/number/boolean/symbol → String/Number/Boolean/Symbol
     * - string/number/boolean literal type → String/Number/Boolean
     * - Template literal type → String
     * - any/unknown/object/void/never/undefined/null keyword → Object
     * - Union/intersection: filter out null/undefined, normalize; if all same → that, else Object
     * - TypeReference: if name is a type parameter → Object, else use base name identifier
     * - ImportType → Object (type-only import, no runtime value)
     * - ArrayType/TupleType → Array
     * - FunctionType/ConstructorType → Function
     * - TypeLiteral/TypeQuery/MappedType/IndexedAccessType/TypeOperator → Object
     * - ConditionalType → check both branches; if both same → that, else Object
     * - InferType → Object
     * - TypePredicate → Boolean
     * - ParenthesizedType → recurse
     */
    private fun serializeTypeNode(typeNode: TypeNode?, typeParams: Set<String>): Expression {
        if (typeNode == null) return syntheticId("Object")
        return when (typeNode) {
            is KeywordTypeNode -> when (typeNode.kind) {
                SyntaxKind.StringKeyword -> syntheticId("String")
                SyntaxKind.NumberKeyword -> syntheticId("Number")
                SyntaxKind.BooleanKeyword -> syntheticId("Boolean")
                SyntaxKind.SymbolKeyword -> syntheticId("Symbol")
                SyntaxKind.BigIntKeyword -> syntheticId("BigInt")
                else -> syntheticId("Object") // any, unknown, void, undefined, never, object, etc.
            }
            is LiteralType -> when (typeNode.literal) {
                is StringLiteralNode -> syntheticId("String")
                is NoSubstitutionTemplateLiteralNode -> syntheticId("String")
                is NumericLiteralNode -> syntheticId("Number")
                is Identifier -> when ((typeNode.literal).text) {
                    "true", "false" -> syntheticId("Boolean")
                    "null" -> syntheticId("Object")
                    else -> syntheticId("Object")
                }
                else -> syntheticId("Object")
            }
            is TemplateLiteralType -> syntheticId("String") // template literal types → String
            is TypeReference -> {
                val name = extractTypeRefName(typeNode.typeName)
                when {
                    name != null && name in typeParams -> syntheticId("Object")
                    name != null && name in topLevelTypeOnlyNames -> syntheticId("Object")
                    name != null -> syntheticId(name)
                    else -> syntheticId("Object")
                }
            }
            is ImportType -> syntheticId("Object")
            is ArrayType, is TupleType -> syntheticId("Array")
            is FunctionType, is ConstructorType -> syntheticId("Function")
            is TypeLiteral, is TypeQuery, is MappedType, is IndexedAccessType, is TypeOperator,
            is InferType, is RestType, is OptionalType, is ThisType -> syntheticId("Object")
            is TypePredicate -> syntheticId("Boolean")
            is ParenthesizedType -> serializeTypeNode(typeNode.type, typeParams)
            is UnionType -> {
                // Filter out null/undefined from union, then unify
                val filtered = typeNode.types.filter { !isNullishTypeNode(it) }
                when {
                    filtered.isEmpty() -> syntheticId("Object")
                    filtered.size == 1 -> serializeTypeNode(filtered[0], typeParams)
                    else -> unifyTypeSerializations(filtered, typeParams)
                }
            }
            is IntersectionType -> {
                val filtered = typeNode.types.filter { !isNullishTypeNode(it) }
                when {
                    filtered.isEmpty() -> syntheticId("Object")
                    filtered.size == 1 -> serializeTypeNode(filtered[0], typeParams)
                    else -> unifyTypeSerializations(filtered, typeParams)
                }
            }
            is ConditionalType -> {
                // Heuristic: check if both branches serialize to the same type
                val trueType = serializeTypeNode(typeNode.trueType, typeParams)
                val falseType = serializeTypeNode(typeNode.falseType, typeParams)
                val trueId = (trueType as? Identifier)?.text
                val falseId = (falseType as? Identifier)?.text
                if (trueId != null && trueId == falseId) syntheticId(trueId)
                else syntheticId("Object")
            }
            is NamedTupleMember -> serializeTypeNode(typeNode.type, typeParams)
            else -> syntheticId("Object")
        }
    }

    /** Get the simple name from a TypeReference's typeName (Identifier or first part of QualifiedName). */
    private fun extractTypeRefName(typeName: Node): String? = when (typeName) {
        is Identifier -> typeName.text
        is QualifiedName -> null // qualified names like E.A — can't serialize without type checker
        else -> null
    }

    /** Return true if a type node represents null or undefined (so it's stripped from unions). */
    private fun isNullishTypeNode(typeNode: TypeNode): Boolean = when (typeNode) {
        is KeywordTypeNode -> typeNode.kind == SyntaxKind.NullKeyword ||
            typeNode.kind == SyntaxKind.UndefinedKeyword
        is LiteralType -> typeNode.literal is Identifier &&
            (typeNode.literal).text == "null"
        else -> false
    }

    /** Try to unify multiple TypeNode serializations to a single identifier; returns Object if mixed. */
    private fun unifyTypeSerializations(types: List<TypeNode>, typeParams: Set<String>): Expression {
        val serialized = types.map { (serializeTypeNode(it, typeParams) as? Identifier)?.text }
        val first = serialized.first()
        return if (first != null && serialized.all { it == first }) syntheticId(first)
        else syntheticId("Object")
    }

    /** Return true if the TypeNode represents a void/undefined return type (for suppressing design:returntype). */
    private fun isVoidTypeNode(typeNode: TypeNode): Boolean = when (typeNode) {
        is KeywordTypeNode -> typeNode.kind == SyntaxKind.VoidKeyword ||
            typeNode.kind == SyntaxKind.UndefinedKeyword ||
            typeNode.kind == SyntaxKind.NeverKeyword
        else -> false
    }

    /** Generate `ClassName = __decorate([...], ClassName)` for class-level decorators. */
    private fun generateClassDecorateStatement(className: String, decorators: List<Decorator>): Statement {
        val decoratorExprs = decorators.map { dec ->
            val transformed = transformExpression(dec.expression)
            if (!dec.trailingComments.isNullOrEmpty()) {
                val merged = (transformed.trailingComments.orEmpty() + dec.trailingComments).ifEmpty { null }
                copyExpressionWithTrailingComments(transformed, merged)
            } else transformed
        }

        val call = CallExpression(
            expression = syntheticId("__decorate"),
            arguments = listOf(
                ArrayLiteralExpression(
                    elements = decoratorExprs,
                    multiLine = true,
                    pos = -1, end = -1,
                ),
                syntheticId(className),
            ),
            pos = -1, end = -1,
        )

        return ExpressionStatement(
            expression = BinaryExpression(
                left = syntheticId(className),
                operator = Equals,
                right = call,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    /** Extract the member name text from a NameNode, or null for computed names. */
    private fun getMemberNameText(name: NameNode): String? = when (name) {
        is Identifier -> name.text
        is StringLiteralNode -> name.text
        is NumericLiteralNode -> name.text
        else -> null // computed names not supported yet
    }

    /** Copy an expression with updated trailing comments. */
    private fun copyExpressionWithTrailingComments(expr: Expression, comments: List<Comment>?): Expression = when (expr) {
        is CallExpression -> expr.copy(trailingComments = comments)
        is Identifier -> expr.copy(trailingComments = comments)
        is PropertyAccessExpression -> expr.copy(trailingComments = comments)
        else -> expr // fallback — can't copy comments
    }

    private fun transformClassExpression(expr: ClassExpression): Expression {
        // Check if there are static properties/blocks that would need a temp var.
        val hasStaticInitializers = !useDefineForClassFields && (
            expr.members.any { it is PropertyDeclaration && ModifierFlag.Static in it.modifiers && it.initializer != null } ||
            (options.effectiveTarget < ScriptTarget.ES2022 && expr.members.any { it is ClassStaticBlockDeclaration })
        )

        if (!hasStaticInitializers) {
            val result = transformClassBody(
                name = expr.name,
                typeParameters = expr.typeParameters,
                heritageClauses = expr.heritageClauses,
                members = expr.members,
                modifiers = expr.modifiers,
            )
            return expr.copy(
                typeParameters = null,
                heritageClauses = result.heritageClauses,
                members = result.members,
                modifiers = stripTypeScriptModifiers(expr.modifiers) - ModifierFlag.Abstract,
            )
        }

        // Allocate a temp var upfront so we can pass it to transformClassBody for use in
        // static initializers and trailing statements.
        val tempName = nextTempVarName()
        hoistedVarScopes.lastOrNull()?.add(tempName)
        val className = expr.name?.text

        val result = transformClassBody(
            name = expr.name,
            typeParameters = expr.typeParameters,
            heritageClauses = expr.heritageClauses,
            members = expr.members,
            modifiers = expr.modifiers,
            trailingVarName = tempName,
        )

        // Replace className references in member bodies (method/accessor bodies reference the class by name)
        val finalMembers = if (className != null) {
            result.members.map { replaceIdentifierInClassElement(it, className, tempName) }
        } else result.members

        val transformedExpr = expr.copy(
            typeParameters = null,
            heritageClauses = result.heritageClauses,
            members = finalMembers,
            modifiers = stripTypeScriptModifiers(expr.modifiers) - ModifierFlag.Abstract,
        )

        // Build comma list: (_a = class C {...}, _a.x = 1, ..., _a)
        // The trailing statements already use tempName for the LHS (via trailingVarName).
        // Still need to replace class name references in the RHS (e.g. `C.x → _a.x`).
        val elements = mutableListOf<Expression>()
        elements.add(BinaryExpression(
            left = syntheticId(tempName),
            operator = Equals,
            right = transformedExpr,
            pos = -1, end = -1,
        ))
        for (stmt in result.trailingStatements) {
            val exprStmt = stmt as? ExpressionStatement ?: continue
            // The LHS is already tempName (from trailingVarName). Replace class name in RHS.
            val replaceExpr = if (className != null) {
                val binExpr = exprStmt.expression as? BinaryExpression
                if (binExpr != null) {
                    binExpr.copy(right = replaceIdentifierInExpr(binExpr.right, className, tempName))
                } else exprStmt.expression
            } else exprStmt.expression
            elements.add(replaceExpr)
        }
        elements.add(syntheticId(tempName))

        return CommaListExpression(elements = elements, pos = expr.pos, end = expr.end)
    }

    /** Replaces all occurrences of identifier [from] with [to] in an expression tree. */
    private fun replaceIdentifierInExpr(expr: Expression, from: String, to: String): Expression = when (expr) {
        is Identifier -> if (expr.text == from) expr.copy(text = to) else expr
        is PropertyAccessExpression -> expr.copy(expression = replaceIdentifierInExpr(expr.expression, from, to))
        is ElementAccessExpression -> expr.copy(
            expression = replaceIdentifierInExpr(expr.expression, from, to),
            argumentExpression = replaceIdentifierInExpr(expr.argumentExpression, from, to),
        )
        is CallExpression -> expr.copy(
            expression = replaceIdentifierInExpr(expr.expression, from, to),
            arguments = expr.arguments.map { replaceIdentifierInExpr(it, from, to) },
        )
        is BinaryExpression -> expr.copy(
            left = replaceIdentifierInExpr(expr.left, from, to),
            right = replaceIdentifierInExpr(expr.right, from, to),
        )
        is PrefixUnaryExpression -> expr.copy(operand = replaceIdentifierInExpr(expr.operand, from, to))
        is PostfixUnaryExpression -> expr.copy(operand = replaceIdentifierInExpr(expr.operand, from, to))
        is ConditionalExpression -> expr.copy(
            condition = replaceIdentifierInExpr(expr.condition, from, to),
            whenTrue = replaceIdentifierInExpr(expr.whenTrue, from, to),
            whenFalse = replaceIdentifierInExpr(expr.whenFalse, from, to),
        )
        is ParenthesizedExpression -> expr.copy(expression = replaceIdentifierInExpr(expr.expression, from, to))
        is AsExpression -> expr.copy(expression = replaceIdentifierInExpr(expr.expression, from, to))
        is ArrowFunction -> expr.copy(
            body = when (val b = expr.body) {
                is Expression -> replaceIdentifierInExpr(b, from, to)
                is Block -> b.copy(statements = b.statements.map { replaceIdentifierInStmt(it, from, to) })
                else -> b
            }
        )
        is FunctionExpression -> expr.copy(
            body = expr.body.copy(statements = expr.body.statements.map { replaceIdentifierInStmt(it, from, to) })
        )
        is NewExpression -> expr.copy(
            expression = replaceIdentifierInExpr(expr.expression, from, to),
            arguments = expr.arguments?.map { replaceIdentifierInExpr(it, from, to) },
        )
        is SpreadElement -> expr.copy(expression = replaceIdentifierInExpr(expr.expression, from, to))
        is ArrayLiteralExpression -> expr.copy(elements = expr.elements.map { replaceIdentifierInExpr(it, from, to) })
        is ObjectLiteralExpression -> expr // skip for now
        else -> expr
    }

    private fun replaceIdentifierInStmt(stmt: Statement, from: String, to: String): Statement = when (stmt) {
        is ExpressionStatement -> stmt.copy(expression = replaceIdentifierInExpr(stmt.expression, from, to))
        is ReturnStatement -> stmt.copy(expression = stmt.expression?.let { replaceIdentifierInExpr(it, from, to) })
        is VariableStatement -> stmt.copy(
            declarationList = stmt.declarationList.copy(
                declarations = stmt.declarationList.declarations.map { decl ->
                    decl.copy(initializer = decl.initializer?.let { replaceIdentifierInExpr(it, from, to) })
                }
            )
        )
        is IfStatement -> stmt.copy(
            expression = replaceIdentifierInExpr(stmt.expression, from, to),
            thenStatement = replaceIdentifierInStmt(stmt.thenStatement, from, to),
            elseStatement = stmt.elseStatement?.let { replaceIdentifierInStmt(it, from, to) },
        )
        is Block -> stmt.copy(statements = stmt.statements.map { replaceIdentifierInStmt(it, from, to) })
        else -> stmt
    }

    private fun replaceIdentifierInBlock(block: Block, from: String, to: String): Block =
        block.copy(statements = block.statements.map { replaceIdentifierInStmt(it, from, to) })

    private fun replaceIdentifierInClassElement(element: ClassElement, from: String, to: String): ClassElement =
        when (element) {
            is MethodDeclaration -> element.copy(body = element.body?.let { replaceIdentifierInBlock(it, from, to) })
            is Constructor -> element.copy(body = element.body?.let { replaceIdentifierInBlock(it, from, to) })
            is GetAccessor -> element.copy(body = element.body?.let { replaceIdentifierInBlock(it, from, to) })
            is SetAccessor -> element.copy(body = element.body?.let { replaceIdentifierInBlock(it, from, to) })
            else -> element
        }

    /**
     * Returns true if [expr] contains a `this` expression that refers to the
     * static initializer context — i.e., `this` not rebounded by a nested
     * non-arrow function body.  Arrow functions capture `this` lexically,
     * so we descend into them; regular `function` bodies rebind `this`,
     * so we stop there.
     */
    private fun containsThisInExpr(expr: Expression): Boolean = when (expr) {
        is Identifier -> expr.text == "this"
        is ArrowFunction -> when (val b = expr.body) {
            is Expression -> containsThisInExpr(b)
            is Block -> b.statements.any { containsThisInStmt(it) }
            else -> false
        }
        is FunctionExpression -> false // rebinds `this`
        is PropertyAccessExpression -> containsThisInExpr(expr.expression)
        is ElementAccessExpression -> containsThisInExpr(expr.expression) || containsThisInExpr(expr.argumentExpression)
        is CallExpression -> containsThisInExpr(expr.expression) || expr.arguments.any { containsThisInExpr(it) }
        is BinaryExpression -> containsThisInExpr(expr.left) || containsThisInExpr(expr.right)
        is PrefixUnaryExpression -> containsThisInExpr(expr.operand)
        is PostfixUnaryExpression -> containsThisInExpr(expr.operand)
        is ConditionalExpression -> containsThisInExpr(expr.condition) || containsThisInExpr(expr.whenTrue) || containsThisInExpr(expr.whenFalse)
        is ParenthesizedExpression -> containsThisInExpr(expr.expression)
        is NewExpression -> containsThisInExpr(expr.expression) || expr.arguments?.any { containsThisInExpr(it) } == true
        is SpreadElement -> containsThisInExpr(expr.expression)
        is ArrayLiteralExpression -> expr.elements.any { containsThisInExpr(it) }
        is ObjectLiteralExpression -> expr.properties.any { prop ->
            when (prop) {
                is PropertyAssignment -> prop.initializer.let { containsThisInExpr(it) } == true
                is ShorthandPropertyAssignment -> false
                else -> false
            }
        }
        else -> false
    }

    private fun containsThisInStmt(stmt: Statement): Boolean = when (stmt) {
        is ExpressionStatement -> containsThisInExpr(stmt.expression)
        is ReturnStatement -> stmt.expression?.let { containsThisInExpr(it) } == true
        is VariableStatement -> stmt.declarationList.declarations.any { it.initializer?.let { i -> containsThisInExpr(i) } == true }
        is IfStatement -> containsThisInExpr(stmt.expression) ||
            containsThisInStmt(stmt.thenStatement) ||
            (stmt.elseStatement?.let { containsThisInStmt(it) } == true)
        is Block -> stmt.statements.any { containsThisInStmt(it) }
        else -> false
    }

    /**
     * Replaces `this` expressions in [expr] with an identifier [withName].
     * Descends into arrow functions (which capture `this` lexically) but
     * stops at non-arrow function bodies (which rebind `this`).
     */
    private fun replaceThisInExpr(expr: Expression, withName: String): Expression = when (expr) {
        is Identifier -> if (expr.text == "this") syntheticId(withName) else expr
        is ArrowFunction -> expr.copy(
            body = when (val b = expr.body) {
                is Expression -> replaceThisInExpr(b, withName)
                is Block -> b.copy(statements = b.statements.map { replaceThisInStmt(it, withName) })
                else -> b
            }
        )
        is FunctionExpression -> expr // don't descend — function rebinds `this`
        is PropertyAccessExpression -> expr.copy(expression = replaceThisInExpr(expr.expression, withName))
        is ElementAccessExpression -> expr.copy(
            expression = replaceThisInExpr(expr.expression, withName),
            argumentExpression = replaceThisInExpr(expr.argumentExpression, withName),
        )
        is CallExpression -> expr.copy(
            expression = replaceThisInExpr(expr.expression, withName),
            arguments = expr.arguments.map { replaceThisInExpr(it, withName) },
        )
        is BinaryExpression -> expr.copy(
            left = replaceThisInExpr(expr.left, withName),
            right = replaceThisInExpr(expr.right, withName),
        )
        is PrefixUnaryExpression -> expr.copy(operand = replaceThisInExpr(expr.operand, withName))
        is PostfixUnaryExpression -> expr.copy(operand = replaceThisInExpr(expr.operand, withName))
        is ConditionalExpression -> expr.copy(
            condition = replaceThisInExpr(expr.condition, withName),
            whenTrue = replaceThisInExpr(expr.whenTrue, withName),
            whenFalse = replaceThisInExpr(expr.whenFalse, withName),
        )
        is ParenthesizedExpression -> expr.copy(expression = replaceThisInExpr(expr.expression, withName))
        is NewExpression -> expr.copy(
            expression = replaceThisInExpr(expr.expression, withName),
            arguments = expr.arguments?.map { replaceThisInExpr(it, withName) },
        )
        is SpreadElement -> expr.copy(expression = replaceThisInExpr(expr.expression, withName))
        is ArrayLiteralExpression -> expr.copy(elements = expr.elements.map { replaceThisInExpr(it, withName) })
        else -> expr
    }

    private fun replaceThisInStmt(stmt: Statement, withName: String): Statement = when (stmt) {
        is ExpressionStatement -> stmt.copy(expression = replaceThisInExpr(stmt.expression, withName))
        is ReturnStatement -> stmt.copy(expression = stmt.expression?.let { replaceThisInExpr(it, withName) })
        is VariableStatement -> stmt.copy(
            declarationList = stmt.declarationList.copy(
                declarations = stmt.declarationList.declarations.map { decl ->
                    decl.copy(initializer = decl.initializer?.let { replaceThisInExpr(it, withName) })
                }
            )
        )
        is IfStatement -> stmt.copy(
            expression = replaceThisInExpr(stmt.expression, withName),
            thenStatement = replaceThisInStmt(stmt.thenStatement, withName),
            elseStatement = stmt.elseStatement?.let { replaceThisInStmt(it, withName) },
        )
        is Block -> stmt.copy(statements = stmt.statements.map { replaceThisInStmt(it, withName) })
        else -> stmt
    }

    private data class ClassTransformResult(
        val heritageClauses: List<HeritageClause>?,
        val members: List<ClassElement>,
        val trailingStatements: List<Statement>,
    )

    private fun transformClassBody(
        name: Identifier?,
        typeParameters: List<TypeParameter>?,
        heritageClauses: List<HeritageClause>?,
        members: List<ClassElement>,
        modifiers: Set<ModifierFlag>,
        trailingVarName: String? = null, // override for trailing-statement LHS (class expression temp var)
    ): ClassTransformResult {

        val isDerived = heritageClauses?.any {
            it.token == ExtendsKeyword
        } == true

        // Strip `implements` clauses, keep only `extends`; strip type arguments from extends.
        // Filter out trailing object literal expressions from extends clause — these are
        // parser error-recovery artifacts (e.g. `extends C, {}` where `{}` is the class body
        // misinterpreted as an empty object literal in the heritage clause).
        val transformedHeritage = heritageClauses
            ?.filter { it.token == ExtendsKeyword }
            ?.map { clause ->
                clause.copy(
                    types = clause.types
                        .filter { it.expression !is ObjectLiteralExpression }
                        .map { ewta ->
                            var expr = transformExpression(ewta.expression)
                            // In extends position, ClassExpression doesn't need parens
                            // (e.g. `extends (class {} as Foo)` → `extends class {}`)
                            if (expr is ParenthesizedExpression && expr.expression is ClassExpression) {
                                expr = expr.expression
                            }
                            ewta.copy(
                                expression = expr,
                                typeArguments = null,
                            )
                        }
                )
            }
            ?.ifEmpty { null }

        // Separate members by category
        val instanceProperties = mutableListOf<PropertyDeclaration>()
        val staticProperties = mutableListOf<PropertyDeclaration>()
        val staticBlocks = mutableListOf<ClassStaticBlockDeclaration>()
        val otherMembers = mutableListOf<ClassElement>()
        var existingConstructor: Constructor? = null

        for (member in members) {
            when {
                member is PropertyDeclaration && ModifierFlag.Declare in member.modifiers -> {
                    // `declare` property: remove entirely
                }

                member is PropertyDeclaration && ModifierFlag.Static in member.modifiers -> {
                    staticProperties.add(member)
                }

                member is PropertyDeclaration && ModifierFlag.Static !in member.modifiers -> {
                    instanceProperties.add(member)
                }

                member is Constructor && member.body != null -> {
                    // Only use constructors with a body; overload signatures (body == null) are erased
                    existingConstructor = member
                }

                member is Constructor && member.body == null -> {
                    // Constructor overload signature: erase (type-only)
                }

                member is IndexSignature -> {
                    // Index signatures are type-only, remove
                }

                // Static blocks below ES2022 target: lower to IIFE trailing statements
                member is ClassStaticBlockDeclaration && options.effectiveTarget < ScriptTarget.ES2022 -> {
                    staticBlocks.add(member)
                }

                else -> otherMembers.add(member)
            }
        }

        // Determine constructor parameter properties
        val paramProperties = existingConstructor?.parameters
            ?.filter { p -> p.modifiers.any { it in parameterPropertyModifiers } }
            ?: emptyList()

        // Build constructor body statements for property initializers
        val propInitStatements = mutableListOf<Statement>()

        // Parameter properties: this.x = x
        for (param in paramProperties) {
            val paramId = param.name as? Identifier ?: continue
            val syntheticParamId = paramId.copy(pos = -1, end = -1, leadingComments = null, trailingComments = null)
            propInitStatements.add(
                ExpressionStatement(
                    expression = BinaryExpression(
                        left = PropertyAccessExpression(
                            expression = syntheticId("this"),
                            name = syntheticParamId,
                            pos = -1, end = -1,
                        ),
                        operator = Equals,
                        right = syntheticParamId,
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                )
            )
        }

        // Instance property initializers (only when not using define semantics)
        if (!useDefineForClassFields) {
            for (prop in instanceProperties) {
                // Skip private fields (#field) — they stay in the class body with their initializer
                val nameNode = prop.name
                if (nameNode is Identifier && nameNode.text.startsWith("#")) continue
                if (prop.initializer != null) {
                    val propId = prop.name as? Identifier
                    val lhs: Expression? = when {
                        propId != null -> PropertyAccessExpression(
                            expression = syntheticId("this"),
                            name = propId.copy(pos = -1, end = -1, leadingComments = null, trailingComments = null),
                            pos = -1, end = -1,
                        )
                        prop.name is StringLiteralNode -> ElementAccessExpression(
                            expression = syntheticId("this"),
                            argumentExpression = prop.name,
                            pos = -1, end = -1,
                        )
                        prop.name is NumericLiteralNode -> ElementAccessExpression(
                            expression = syntheticId("this"),
                            argumentExpression = prop.name,
                            pos = -1, end = -1,
                        )
                        prop.name is ComputedPropertyName -> ElementAccessExpression(
                            expression = syntheticId("this"),
                            argumentExpression = transformExpression(prop.name.expression),
                            pos = -1, end = -1,
                        )
                        else -> null
                    }
                    if (lhs != null) {
                        propInitStatements.add(
                            ExpressionStatement(
                                expression = BinaryExpression(
                                    left = lhs,
                                    operator = Equals,
                                    right = transformExpression(prop.initializer),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                                leadingComments = prop.leadingComments,
                                trailingComments = prop.trailingComments,
                            )
                        )
                    }
                }
            }
        }

        // Build the transformed constructor
        val needsConstructor = propInitStatements.isNotEmpty() || existingConstructor != null
        val transformedConstructor: Constructor? = if (needsConstructor) {
            if (existingConstructor != null) {
                val (transformedParams, existingBody) = withFreshTempVarCounter {
                    val rawBody = existingConstructor.body?.let { transformBlock(it, isFunctionScope = true) }
                        ?: Block(statements = emptyList(), pos = -1, end = -1)
                    flattenRestParameters(existingConstructor.parameters, rawBody)
                }

                // Find the position of super() call in existing body
                val bodyStatements = existingBody.statements.toMutableList()
                val superIndex = bodyStatements.indexOfFirst { isSuperCallStatement(it) }

                if (superIndex >= 0) {
                    // Insert after super() call
                    bodyStatements.addAll(superIndex + 1, propInitStatements)
                } else {
                    // Find insertion point: after prologue directives (= after "use strict" etc.)
                    val firstRealIdx = bodyStatements.indexOfFirst { stmt ->
                        !(stmt is ExpressionStatement && stmt.expression is StringLiteralNode)
                    }.let { if (it < 0) bodyStatements.size else it }
                    // If the first real statement has leading comments AND the constructor
                    // body also assigns to one of the same properties as the prop inits,
                    // insert prop inits at END to avoid displacing those comments.
                    val propInitPropNames = propInitStatements.mapNotNull { stmt ->
                        val binExpr = (stmt as? ExpressionStatement)?.expression as? BinaryExpression
                        (binExpr?.left as? PropertyAccessExpression)?.name?.text
                    }.toSet()
                    val firstHasComments = firstRealIdx < bodyStatements.size &&
                        bodyStatements[firstRealIdx].leadingComments?.isNotEmpty() == true
                    val bodyAssignsSameProp = bodyStatements.drop(firstRealIdx).any { stmt ->
                        val binExpr = (stmt as? ExpressionStatement)?.expression as? BinaryExpression
                        val lhsProp = binExpr?.left as? PropertyAccessExpression
                        lhsProp != null && (lhsProp.expression as? Identifier)?.text == "this" &&
                            lhsProp.name.text in propInitPropNames
                    }
                    val insertAt = if (firstHasComments && bodyAssignsSameProp) bodyStatements.size else firstRealIdx
                    bodyStatements.addAll(insertAt, propInitStatements)
                }

                Constructor(
                    parameters = transformedParams,
                    body = existingBody.copy(
                        statements = bodyStatements,
                        multiLine = propInitStatements.isNotEmpty() || existingBody.multiLine,
                    ),
                    modifiers = stripMemberModifiers(existingConstructor.modifiers),
                    pos = existingConstructor.pos, end = existingConstructor.end,
                    leadingComments = existingConstructor.leadingComments,
                    trailingComments = existingConstructor.trailingComments,
                )
            } else {
                // Synthesize constructor
                val bodyStatements = mutableListOf<Statement>()
                if (isDerived) {
                    // super(...arguments)
                    bodyStatements.add(
                        ExpressionStatement(
                            expression = CallExpression(
                                expression = syntheticId("super"),
                                arguments = listOf(
                                    SpreadElement(
                                        expression = syntheticId("arguments"),
                                        pos = -1, end = -1,
                                    )
                                ),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    )
                }
                bodyStatements.addAll(propInitStatements)

                Constructor(
                    parameters = emptyList(),
                    body = Block(statements = bodyStatements, pos = -1, end = -1),
                    pos = -1, end = -1,
                )
            }
        } else {
            null
        }

        // Build the output members list preserving source order.
        // - `declare` properties and constructor overload signatures are erased.
        // - Instance/static properties are handled based on useDefineForClassFields:
        //   * useDefine: kept as class fields (stripped of TS-specific modifiers)
        //   * !useDefine: instance props moved to constructor body; static become trailing stmts
        // - The constructor (transformed or synthesized) is placed at its original source position.
        //   If it was synthesized (no source constructor), it is prepended at position 0.
        val outputMembers = mutableListOf<ClassElement>()
        var constructorAdded = false

        for (member in members) {
            when {
                member is PropertyDeclaration && ModifierFlag.Declare in member.modifiers -> {
                    // erase declare properties
                }
                member is PropertyDeclaration && !useDefineForClassFields -> {
                    val nm = member.name
                    if (nm is Identifier && nm.text.startsWith("#")) {
                        // Private JS fields (#field) need explicit class body declaration even when
                        // useDefineForClassFields=false — they can't be assigned in the constructor
                        outputMembers.add(member.copy(
                            type = null,
                            initializer = member.initializer?.let { transformExpression(it) },
                            modifiers = stripMemberModifiers(member.modifiers),
                            questionToken = false,
                            exclamationToken = false,
                        ))
                    } else if (ModifierFlag.Static in member.modifiers &&
                        member.initializer != null &&
                        options.effectiveTarget >= ScriptTarget.ES2022) {
                        // ES2022+ with useDefineForClassFields=false: use static initializer block
                        // instead of trailing ClassName.prop = val statement
                        val thisRef = syntheticId("this")
                        val lhsExpr: Expression? = when (nm) {
                            is Identifier -> PropertyAccessExpression(
                                expression = thisRef,
                                name = nm.copy(pos = -1, end = -1, leadingComments = null, trailingComments = null),
                                pos = -1, end = -1,
                            )
                            is StringLiteralNode -> ElementAccessExpression(
                                expression = thisRef,
                                argumentExpression = nm,
                                pos = -1, end = -1,
                            )
                            is ComputedPropertyName -> ElementAccessExpression(
                                expression = thisRef,
                                argumentExpression = transformExpression(nm.expression),
                                pos = -1, end = -1,
                            )
                            else -> null
                        }
                        if (lhsExpr != null) {
                            outputMembers.add(ClassStaticBlockDeclaration(
                                body = Block(
                                    statements = listOf(ExpressionStatement(
                                        expression = BinaryExpression(
                                            left = lhsExpr,
                                            operator = Equals,
                                            right = transformExpression(member.initializer),
                                            pos = -1, end = -1,
                                        ),
                                        pos = -1, end = -1,
                                    )),
                                    multiLine = false,
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                                leadingComments = member.leadingComments,
                            ))
                        }
                    }
                    // else: moved to constructor body or trailing statements — skip here
                }
                member is PropertyDeclaration && useDefineForClassFields -> {
                    outputMembers.add(
                        member.copy(
                            type = null,
                            initializer = member.initializer?.let { transformExpression(it) },
                            modifiers = stripMemberModifiers(member.modifiers),
                            questionToken = false,
                            exclamationToken = false,
                        )
                    )
                }
                member is Constructor && member.body != null -> {
                    if (member === existingConstructor && transformedConstructor != null) {
                        outputMembers.add(transformedConstructor)
                        constructorAdded = true
                    } else {
                        // Extra constructors with bodies (illegal in TS, but still emitted)
                        outputMembers.add(Constructor(
                            parameters = transformParameters(member.parameters),
                            body = member.body.let { transformBlock(it, isFunctionScope = true) },
                            modifiers = stripMemberModifiers(member.modifiers),
                            pos = member.pos, end = member.end,
                            leadingComments = member.leadingComments,
                            trailingComments = member.trailingComments,
                        ))
                    }
                }
                member is Constructor && member.body == null -> {
                    // constructor overload signature — erase
                }
                member is IndexSignature -> {
                    // index signatures are type-only — erase
                }
                member is ClassStaticBlockDeclaration && options.effectiveTarget < ScriptTarget.ES2022 -> {
                    // Skip — collected in staticBlocks, emitted as IIFE trailing statements
                }
                else -> {
                    val transformed = transformClassElement(member)
                    if (transformed != null) outputMembers.add(transformed)
                }
            }
        }

        // Synthesized constructor (no existing constructor in source): prepend at top
        if (transformedConstructor != null && !constructorAdded) {
            outputMembers.add(0, transformedConstructor)
        }

        val trailingStatements = mutableListOf<Statement>()

        // Static properties without useDefineForClassFields → trailing statements
        // Use trailingVarName (class expression temp var) if provided, otherwise use class name.
        // Exception: ES2022+ targets use static { this.prop = val } inside the class body (already added above).
        val effectiveName = trailingVarName ?: name?.text
        if (!useDefineForClassFields && effectiveName != null) {
            // Check if any static property initializer contains `this` (lexically, across arrow fns).
            // If so, we must capture the class in a temp var before the initializers run.
            val staticPropsWithThis = staticProperties.filter { prop ->
                prop.initializer != null &&
                    options.effectiveTarget < ScriptTarget.ES2022 &&
                    containsThisInExpr(prop.initializer)
            }
            val classTempVar: String? = if (staticPropsWithThis.isNotEmpty()) {
                val tv = nextTempVarName()
                hoistedVarScopes.lastOrNull()?.add(tv)
                tv
            } else null

            // If we need a class temp var, emit `_a = ClassName` as the first trailing statement.
            if (classTempVar != null) {
                trailingStatements.add(
                    ExpressionStatement(
                        expression = BinaryExpression(
                            left = syntheticId(classTempVar),
                            operator = Equals,
                            right = syntheticId(effectiveName),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    )
                )
            }

            for (prop in staticProperties) {
                if (prop.initializer != null && options.effectiveTarget < ScriptTarget.ES2022) {
                    val classId = Identifier(text = effectiveName, pos = -1, end = -1)
                    val lhs: Expression? = when (val nm = prop.name) {
                        is Identifier -> PropertyAccessExpression(
                            expression = classId,
                            name = nm.copy(pos = -1, end = -1, leadingComments = null, trailingComments = null),
                            pos = -1, end = -1,
                        )
                        is StringLiteralNode -> ElementAccessExpression(
                            expression = classId,
                            argumentExpression = nm,
                            pos = -1, end = -1,
                        )
                        is NumericLiteralNode -> ElementAccessExpression(
                            expression = classId,
                            argumentExpression = nm,
                            pos = -1, end = -1,
                        )
                        is ComputedPropertyName -> ElementAccessExpression(
                            expression = classId,
                            argumentExpression = transformExpression(nm.expression),
                            pos = -1, end = -1,
                        )
                        else -> null
                    }
                    if (lhs != null) {
                        val transformedInit = transformExpression(prop.initializer)
                        val finalInit = if (classTempVar != null) replaceThisInExpr(transformedInit, classTempVar)
                                        else transformedInit
                        trailingStatements.add(
                            ExpressionStatement(
                                expression = BinaryExpression(
                                    left = lhs,
                                    operator = Equals,
                                    right = finalInit,
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                                leadingComments = prop.leadingComments,
                                trailingComments = prop.trailingComments,
                            )
                        )
                    }
                }
            }
        }

        // Computed-key properties with no initializer (!useDefineForClassFields) are dropped from the
        // class body and not assigned in the constructor, but their key expression must still be
        // evaluated at class-definition time for side effects (e.g. Symbol() registration).
        // Emit them as trailing ExpressionStatements after the class declaration.
        // Skip constant expressions (numeric/string literals) — they have no side effects.
        if (!useDefineForClassFields) {
            for (member in members) {
                if (member is PropertyDeclaration &&
                    member.name is ComputedPropertyName &&
                    member.initializer == null &&
                    ModifierFlag.Declare !in member.modifiers
                ) {
                    val keyExpr = (member.name).expression
                    // Only emit side effects for non-trivial expressions: skip simple identifiers and literals.
                    // TypeScript only evaluates computed keys that are more than a bare variable reference.
                    if (keyExpr !is Identifier &&
                        keyExpr !is NumericLiteralNode &&
                        keyExpr !is StringLiteralNode) {
                        trailingStatements.add(
                            ExpressionStatement(
                                expression = transformExpression(keyExpr),
                                pos = -1, end = -1,
                            )
                        )
                    }
                }
            }
        }

        // Static blocks below ES2022 target: emit as IIFE trailing statements
        for (block in staticBlocks) {
            val transformedBlock = transformBlock(block.body, isFunctionScope = true)
            trailingStatements.add(
                ExpressionStatement(
                    expression = CallExpression(
                        expression = ParenthesizedExpression(
                            expression = ArrowFunction(
                                parameters = emptyList(),
                                body = transformedBlock,
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        ),
                        arguments = emptyList(),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                )
            )
        }

        return ClassTransformResult(
            heritageClauses = transformedHeritage,
            members = outputMembers,
            trailingStatements = trailingStatements,
        )
    }

    /**
     * Transforms a single class element (method, accessor, etc.).
     * Returns null if the element should be removed.
     */
    private fun transformClassElement(element: ClassElement): ClassElement? {
        return when (element) {
            is MethodDeclaration -> {
                // Overload signatures (no body) are removed
                if (element.body == null) return null
                transformMethodDeclarationElement(element)
            }

            is GetAccessor -> {
                // Abstract accessors and overload signatures (no body) are removed
                if (element.body == null || ModifierFlag.Abstract in element.modifiers) return null
                element.copy(
                    parameters = transformParameters(element.parameters),
                    type = null,
                    body = transformBlock(element.body, isFunctionScope = true),
                    modifiers = stripMemberModifiers(element.modifiers),
                )
            }

            is SetAccessor -> {
                // Abstract accessors and overload signatures (no body) are removed
                if (element.body == null || ModifierFlag.Abstract in element.modifiers) return null
                element.copy(
                    parameters = transformParameters(element.parameters),
                    body = transformBlock(element.body, isFunctionScope = true),
                    modifiers = stripMemberModifiers(element.modifiers),
                )
            }

            is PropertyDeclaration -> {
                // Abstract/ambient property declarations (no initializer, type-only) are removed
                if (element.initializer == null && ModifierFlag.Abstract in element.modifiers) return null
                element.copy(
                    type = null,
                    initializer = element.initializer?.let { transformExpression(it) },
                    modifiers = stripMemberModifiers(element.modifiers),
                    questionToken = false,
                    exclamationToken = false,
                )
            }

            is Constructor -> {
                // Already handled; shouldn't reach here
                element
            }

            is SemicolonClassElement -> element
            is ClassStaticBlockDeclaration -> element.copy(
                body = transformBlock(element.body, isFunctionScope = true),
            )

            is IndexSignature -> null // type-only
        }
    }

    private fun isSuperCallStatement(stmt: Statement): Boolean {
        if (stmt !is ExpressionStatement) return false
        val expr = stmt.expression
        if (expr !is CallExpression) return false
        val callee = expr.expression
        return callee is Identifier && callee.text == "super"
    }

    // -----------------------------------------------------------------
    // Enum transform
    // -----------------------------------------------------------------

    /**
     * Transforms an enum declaration into a var declaration + IIFE.
     *
     * ```typescript
     * enum E { A, B = "hello", C }
     * ```
     * becomes:
     * ```javascript
     * var E;
     * (function (E) {
     *     E[E["A"] = 0] = "A";
     *     E["B"] = "hello";
     *     E[E["C"] = 1] = "C";
     * })(E || (E = {}));
     * ```
     */
    private fun transformEnum(
        decl: EnumDeclaration,
        nested: Boolean = false,
        parentNsName: String? = null,
    ): List<Statement> {
        // const enum without preserveConstEnums (and not isolatedModules) → remove (it's inlined)
        // With isolatedModules, const enums can't be inlined, so keep them as runtime objects.
        if (ModifierFlag.Const in decl.modifiers && !options.preserveConstEnums && !options.isolatedModules) {
            return emptyList()
        }

        val enumName = decl.name.text
        val enumId = syntheticId(enumName)

        // Build IIFE body statements
        val iifeBody = mutableListOf<Statement>()
        var nextAutoValue = 0L
        var autoIncrementValid = true // becomes false after non-constant computed member
        // Track folded values for cross-member references (e.g. B = A + 1)
        // Initialize from previous declaration of same enum (merged enums)
        val previousMembers = allEnumMemberValues[enumName]
        val memberValues = previousMembers?.toMutableMap() ?: mutableMapOf()
        // Track known member names for qualifying bare identifiers in initializers.
        // Collect ALL member names upfront — TypeScript qualifies references to any member,
        // not just previously-declared ones (e.g., `A = A` → `E.A`).
        val knownMemberNames = (previousMembers?.keys?.toMutableSet() ?: mutableSetOf()).apply {
            for (m in decl.members) {
                add(extractEnumMemberName(m.name))
            }
        }

        // Track which members have been processed (even if non-constant)
        val processedMembers = (previousMembers?.keys?.toMutableSet() ?: mutableSetOf())
        for (member in decl.members) {
            val memberName = extractEnumMemberName(member.name)
            val memberNameExpr = memberNameToString(member.name)

            val stmt: ExpressionStatement = when {
                member.initializer != null -> {
                    val initExpr = transformExpression(member.initializer)
                    val constStringVal = evaluateConstantStringExpression(initExpr)
                    if (constStringVal != null) {
                        // String enum member: E["B"] = "hello" (no reverse mapping)
                        // After a string member, auto-increment is disrupted; next numeric
                        // member must have explicit initializer. We don't track further.
                        autoIncrementValid = false
                        // Always emit the folded string value as a double-quoted literal.
                        val stringExpr = StringLiteralNode(
                            text = constStringVal, singleQuote = false, rawText = null,
                            pos = -1, end = -1,
                        )
                        ExpressionStatement(
                            expression = BinaryExpression(
                                left = ElementAccessExpression(
                                    expression = syntheticId(enumName),
                                    argumentExpression = memberNameExpr,
                                    pos = -1, end = -1,
                                ),
                                operator = Equals,
                                right = stringExpr,
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    } else {
                        // Numeric / expression initializer: E[E["X"] = expr] = "X"
                        // Try to fold constant expressions (e.g. 1 << 1 → 2).
                        // Evaluate the ORIGINAL initializer (pre-type-erasure) so that type-only
                        // operators like `!` (non-null assertion), `satisfies`, and `as` prevent
                        // constant folding — matching TypeScript's emit behavior.
                        var foldedValue = evaluateConstantExpression(member.initializer, memberValues)
                        // Forward references to not-yet-defined members resolve to 0
                        // (TypeScript emits 0 for forward enum member references)
                        if (foldedValue == null && isForwardEnumRef(initExpr, enumName, memberName, knownMemberNames, processedMembers)) {
                            foldedValue = 0L
                        }
                        val emitExpr = if (foldedValue != null) {
                            NumericLiteralNode(text = foldedValue.toString(), pos = -1, end = -1)
                        } else {
                            // Qualify bare identifiers that refer to earlier enum members
                            qualifyEnumMemberRefs(initExpr, enumName, knownMemberNames)
                        }
                        val numericValue = foldedValue ?: tryEvaluateNumericLiteral(initExpr)?.toLong()
                        if (numericValue != null) {
                            nextAutoValue = numericValue + 1L
                            autoIncrementValid = true
                            memberValues[memberName] = numericValue
                            allEnumMemberValues.getOrPut(enumName) { mutableMapOf() }[memberName] = numericValue
                        } else {
                            // Non-constant computed value: auto-increment is now invalid
                            autoIncrementValid = false
                        }
                        makeReverseMapStatement(enumName, memberNameExpr, emitExpr)
                    }
                }

                else -> {
                    if (autoIncrementValid) {
                        // Auto-increment numeric member
                        val valueExpr = NumericLiteralNode(
                            text = nextAutoValue.toString(),
                            pos = -1, end = -1,
                        )
                        memberValues[memberName] = nextAutoValue
                        allEnumMemberValues.getOrPut(enumName) { mutableMapOf() }[memberName] = nextAutoValue
                        nextAutoValue++
                        makeReverseMapStatement(enumName, memberNameExpr, valueExpr)
                    } else {
                        // Auto-increment disrupted by non-constant computed member: emit void 0
                        val voidExpr = VoidExpression(
                            expression = NumericLiteralNode(text = "0", pos = -1, end = -1),
                            pos = -1, end = -1,
                        )
                        makeReverseMapStatement(enumName, memberNameExpr, voidExpr)
                    }
                }
            }
            // Track this member name for qualifying later members' initializers
            knownMemberNames.add(memberName)
            processedMembers.add(memberName)
            // Copy leading/trailing comments from the enum member to the generated statement
            iifeBody.add(
                if (member.leadingComments != null || member.trailingComments != null) {
                    stmt.copy(
                        leadingComments = member.leadingComments,
                        trailingComments = member.trailingComments,
                    )
                } else {
                    stmt
                }
            )
        }

        // Emit var E; unless a class/function declared this name before the enum
        // (in source order), or a prior enum/namespace already emitted var E;
        // Inside function scopes, always emit `let` since each function has its own scope
        val needsVarDecl = functionScopeDepth > 0 ||
                (enumName !in declaredNames && enumName !in emittedVarNames)
        if (functionScopeDepth == 0) {
            emittedVarNames.add(enumName)
            declaredNames.add(enumName)
        }
        // In ES module format, preserve the `export` modifier so the file is still recognized
        // as a module file (e.g. `export enum E {}` → `export var E; IIFE`).
        // In CommonJS format the CommonJS transform handles exports separately, so no modifier.
        val isEsModuleFormat = options.effectiveModule in setOf(
            ModuleKind.ES2015, ModuleKind.ES2020, ModuleKind.ES2022, ModuleKind.ESNext
        )
        val varModifiers = if (isEsModuleFormat && ModifierFlag.Export in decl.modifiers) {
            setOf(ModifierFlag.Export)
        } else {
            emptySet()
        }
        val varDecl = if (needsVarDecl) VariableStatement(
            declarationList = VariableDeclarationList(
                declarations = listOf(
                    VariableDeclaration(
                        name = Identifier(text = enumName, pos = -1, end = -1),
                        pos = -1, end = -1,
                    )
                ),
                flags = if (nested || functionScopeDepth > 0) LetKeyword else VarKeyword,
                pos = -1, end = -1,
            ),
            modifiers = varModifiers,
            pos = -1, end = -1,
            leadingComments = decl.leadingComments,
        ) else null

        // (function (E) { ... })(E || (E = {}));
        val iife = ExpressionStatement(
            expression = CallExpression(
                expression = ParenthesizedExpression(
                    expression = FunctionExpression(
                        parameters = listOf(
                            Parameter(
                                name = Identifier(text = enumName, pos = -1, end = -1),
                                pos = -1, end = -1,
                            )
                        ),
                        body = Block(
                            statements = iifeBody,
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                arguments = listOf(
                    if (parentNsName != null) {
                        // E = Parent.E || (Parent.E = {})
                        val parentProp = PropertyAccessExpression(
                            expression = syntheticId(parentNsName),
                            name = Identifier(text = enumName, pos = -1, end = -1),
                            pos = -1, end = -1,
                        )
                        BinaryExpression(
                            left = enumId,
                            operator = Equals,
                            right = BinaryExpression(
                                left = parentProp,
                                operator = BarBar,
                                right = ParenthesizedExpression(
                                    expression = BinaryExpression(
                                        left = PropertyAccessExpression(
                                            expression = syntheticId(parentNsName),
                                            name = Identifier(text = enumName, pos = -1, end = -1),
                                            pos = -1, end = -1,
                                        ),
                                        operator = Equals,
                                        right = ObjectLiteralExpression(
                                            properties = emptyList(),
                                            pos = -1, end = -1,
                                        ),
                                        pos = -1, end = -1,
                                    ),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    } else {
                        // E || (E = {})
                        BinaryExpression(
                            left = enumId,
                            operator = BarBar,
                            right = ParenthesizedExpression(
                                expression = BinaryExpression(
                                    left = syntheticId(enumName),
                                    operator = Equals,
                                    right = ObjectLiteralExpression(
                                        properties = emptyList(),
                                        pos = -1, end = -1,
                                    ),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    }
                ),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
            trailingComments = decl.trailingComments,
        )

        return if (varDecl != null) listOf(varDecl, iife) else {
            if (decl.leadingComments != null) {
                listOf(iife.copy(leadingComments = decl.leadingComments))
            } else {
                listOf(iife)
            }
        }
    }

    /**
     * Creates `E[E["name"] = value] = "name";` for numeric reverse mapping.
     */
    private fun makeReverseMapStatement(
        enumName: String,
        memberNameExpr: Expression,
        valueExpr: Expression,
    ): ExpressionStatement {
        return ExpressionStatement(
            expression = BinaryExpression(
                left = ElementAccessExpression(
                    expression = syntheticId(enumName),
                    argumentExpression = BinaryExpression(
                        left = ElementAccessExpression(
                            expression = syntheticId(enumName),
                            argumentExpression = memberNameExpr,
                            pos = -1, end = -1,
                        ),
                        operator = Equals,
                        right = valueExpr,
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                operator = Equals,
                right = memberNameExpr,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    /**
     * Qualifies bare identifiers in enum initializer expressions with the enum name.
     * e.g., inside `enum Foo { a=2, x=a.b }`, `a` → `Foo.a` producing `Foo.a.b`.
     * Only qualifies identifiers that match known enum member names.
     */
    /**
     * Checks if an initializer expression is a forward reference to a not-yet-defined
     * enum member (NOT the same member — self-references keep their qualified form).
     * Forward references resolve to 0 in TypeScript.
     * Matches patterns: `Y` (bare identifier), `E.Y`, `E["Y"]`
     */
    private fun isForwardEnumRef(
        expr: Expression,
        enumName: String,
        currentMemberName: String,
        knownMembers: Set<String>,
        processedMembers: Set<String>,
    ): Boolean {
        // A forward reference is to a member that is known but NOT yet processed.
        // Members that were processed but had non-constant values are NOT forward refs.
        fun isForwardRef(refName: String): Boolean =
            refName != currentMemberName && refName in knownMembers && refName !in processedMembers
        // For qualified access E1.Z on the current enum, also treat as forward ref when
        // Z is not yet processed — covers forward refs to members in later merged enum blocks.
        fun isForwardRefOnCurrentEnum(refName: String): Boolean =
            refName != currentMemberName && refName !in processedMembers

        // Bare identifier: X = Y where Y is a known member but not yet resolved
        if (expr is Identifier && isForwardRef(expr.text)) return true
        // E.Y property access
        if (expr is PropertyAccessExpression && expr.expression is Identifier) {
            val obj = expr.expression
            if (obj.text == enumName && isForwardRefOnCurrentEnum(expr.name.text)) return true
        }
        // E["Y"] element access
        if (expr is ElementAccessExpression && expr.expression is Identifier) {
            val obj = expr.expression
            val arg = expr.argumentExpression
            if (obj.text == enumName && arg is StringLiteralNode && isForwardRefOnCurrentEnum(arg.text)) return true
        }
        return false
    }

    private fun qualifyEnumMemberRefs(
        expr: Expression,
        enumName: String,
        knownMembers: Set<String>,
    ): Expression {
        return when (expr) {
            is Identifier -> {
                if (expr.text in knownMembers) {
                    PropertyAccessExpression(
                        expression = syntheticId(enumName),
                        name = expr,
                        pos = -1, end = -1,
                    )
                } else expr
            }
            is PropertyAccessExpression -> {
                // Only qualify the leftmost identifier in a chain
                expr.copy(expression = qualifyEnumMemberRefs(expr.expression, enumName, knownMembers))
            }
            is ElementAccessExpression -> {
                expr.copy(
                    expression = qualifyEnumMemberRefs(expr.expression, enumName, knownMembers),
                    argumentExpression = qualifyEnumMemberRefs(expr.argumentExpression, enumName, knownMembers),
                )
            }
            is BinaryExpression -> {
                expr.copy(
                    left = qualifyEnumMemberRefs(expr.left, enumName, knownMembers),
                    right = qualifyEnumMemberRefs(expr.right, enumName, knownMembers),
                )
            }
            is PrefixUnaryExpression -> {
                expr.copy(operand = qualifyEnumMemberRefs(expr.operand, enumName, knownMembers))
            }
            is ParenthesizedExpression -> {
                expr.copy(expression = qualifyEnumMemberRefs(expr.expression, enumName, knownMembers))
            }
            is ConditionalExpression -> {
                expr.copy(
                    condition = qualifyEnumMemberRefs(expr.condition, enumName, knownMembers),
                    whenTrue = qualifyEnumMemberRefs(expr.whenTrue, enumName, knownMembers),
                    whenFalse = qualifyEnumMemberRefs(expr.whenFalse, enumName, knownMembers),
                )
            }
            is CallExpression -> {
                expr.copy(
                    expression = qualifyEnumMemberRefs(expr.expression, enumName, knownMembers),
                    arguments = expr.arguments.map { qualifyEnumMemberRefs(it, enumName, knownMembers) },
                )
            }
            else -> expr
        }
    }

    private fun extractEnumMemberName(name: NameNode): String {
        return when (name) {
            is Identifier -> name.text
            is StringLiteralNode -> name.text
            is NumericLiteralNode -> name.text
            is BigIntLiteralNode -> name.text
            is ComputedPropertyName -> "[computed]"
            else -> "unknown"
        }
    }

    private fun memberNameToString(name: NameNode): Expression {
        return when (name) {
            is Identifier -> StringLiteralNode(text = name.text, pos = -1, end = -1)
            is StringLiteralNode -> {
                // TypeScript always emits enum member string names with double quotes
                if (name.singleQuote) {
                    // Convert rawText escaping from single-quote context to double-quote context
                    val adjustedRaw = name.rawText?.let { adjustRawTextToDoubleQuote(it) }
                    name.copy(singleQuote = false, rawText = adjustedRaw)
                } else {
                    name.copy(singleQuote = false)
                }
            }
            is NumericLiteralNode -> {
                // Normalize numeric literal to canonical decimal form (e.g. 1.0 → 1, 11e-1 → 1.1)
                val normalized = normalizeNumericLiteral(name.text)
                NumericLiteralNode(text = normalized, pos = -1, end = -1)
            }
            is BigIntLiteralNode -> name
            is ComputedPropertyName -> transformExpression(name.expression)
            else -> StringLiteralNode(text = "unknown", pos = -1, end = -1)
        }
    }

    /**
     * Adjusts rawText content from single-quote escaping context to double-quote escaping context:
     * - `\'` → `'` (single quote no longer needs escaping)
     * - unescaped `"` → `\"` (double quote now needs escaping)
     */
    private fun adjustRawTextToDoubleQuote(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == '\'') {
                    sb.append('\'') // \' → ' (unescape)
                    i += 2
                } else {
                    sb.append(ch)
                    sb.append(next)
                    i += 2
                }
            } else if (ch == '"') {
                sb.append("\\\"") // " → \" (escape for double-quote context)
                i++
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun isStringLiteral(expr: Expression): Boolean {
        return expr is StringLiteralNode
    }

    /**
     * Tries to evaluate [expr] as a constant string value.
     * Returns the string value if successful, null otherwise.
     * Handles:
     * - String literals
     * - Parenthesized string literals: ("foo") → "foo"
     * - String + numeric literal: "" + 2 → "2"
     * - Numeric literal + string: 2 + "" → "2"
     * - NoSubstitutionTemplateLiteral (plain template with no expressions): `foo` → "foo"
     */
    private fun evaluateConstantStringExpression(expr: Expression): String? {
        return when (expr) {
            is StringLiteralNode -> expr.text
            is NoSubstitutionTemplateLiteralNode -> expr.text
            is ParenthesizedExpression -> evaluateConstantStringExpression(expr.expression)
            is BinaryExpression -> {
                if (expr.operator != SyntaxKind.Plus) return null
                // "" + numericLiteral → string
                val leftStr = evaluateConstantStringExpression(expr.left)
                val rightStr = evaluateConstantStringExpression(expr.right)
                if (leftStr != null && rightStr != null) return leftStr + rightStr
                val leftNum = tryEvaluateNumericLiteral(expr.left)
                val rightNum = tryEvaluateNumericLiteral(expr.right)
                if (leftStr != null && rightNum != null) {
                    return leftStr + rightNum.toString()
                }
                if (leftNum != null && rightStr != null) {
                    return leftNum.toString() + rightStr
                }
                null
            }
            else -> null
        }
    }

    /**
     * Pre-pass: scan all const enum declarations in [stmts] and populate [constEnumValues].
     * For numeric auto-increment members, evaluates values in sequence.
     * For string members, stores the string value.
     * For non-constant expressions (runtime), stores null (will not inline).
     */
    private fun collectMergedNamespaceExports(stmts: List<Statement>) {
        mergedNamespaceExports.clear()
        for (stmt in stmts) {
            if (stmt !is ModuleDeclaration) continue
            if (hasDeclareModifier(stmt)) continue
            if (isTypeOnlyNamespace(stmt)) continue
            // Recursively collect from all nesting levels of dotted namespaces
            collectMergedNamespaceExportsFromModule(stmt)
        }
    }

    private fun collectMergedNamespaceExportsFromModule(module: ModuleDeclaration) {
        // For dotted namespaces (namespace A.B.C), the parser stores the name as a
        // PropertyAccessExpression, not nested ModuleDeclarations. Flatten the dotted
        // name and treat each level as a nested namespace.
        if (module.name is PropertyAccessExpression) {
            val parts = flattenDottedNamespaceName(module.name)
            if (parts.isEmpty()) return
            // Each part (except the last) exports the next part as a child namespace
            for (i in 0 until parts.size - 1) {
                mergedNamespaceExports.getOrPut(parts[i]) { mutableSetOf() }.add(parts[i + 1])
            }
            // The last part owns the actual body exports
            val lastPart = parts.last()
            val bodyStmts = when (val body = module.body) {
                is ModuleBlock -> body.statements
                else -> return
            }
            collectExportsFromBody(lastPart, bodyStmts)
            return
        }

        val nsName = extractIdentifierName(module.name) ?: return
        val body = module.body
        // For nested ModuleDeclarations (e.g. from manual nesting, not dotted syntax)
        if (body is ModuleDeclaration) {
            mergedNamespaceExports.getOrPut(nsName) { mutableSetOf() }
                .add(extractIdentifierName(body.name) ?: return)
            collectMergedNamespaceExportsFromModule(body)
            return
        }
        val bodyStmts = when (body) {
            is ModuleBlock -> body.statements
            else -> return
        }
        collectExportsFromBody(nsName, bodyStmts)
    }

    private fun collectExportsFromBody(nsName: String, bodyStmts: List<Statement>) {
        val exports = mergedNamespaceExports.getOrPut(nsName) { mutableSetOf() }
        for (bodyStmt in bodyStmts) {
            val isExported = when (bodyStmt) {
                is VariableStatement -> ModifierFlag.Export in bodyStmt.modifiers
                is FunctionDeclaration -> ModifierFlag.Export in bodyStmt.modifiers
                is ClassDeclaration -> ModifierFlag.Export in bodyStmt.modifiers
                is EnumDeclaration -> ModifierFlag.Export in bodyStmt.modifiers
                is ModuleDeclaration -> ModifierFlag.Export in bodyStmt.modifiers
                is ImportEqualsDeclaration -> ModifierFlag.Export in bodyStmt.modifiers
                else -> false
            }
            if (!isExported) continue
            when (bodyStmt) {
                is VariableStatement -> for (decl in bodyStmt.declarationList.declarations) {
                    extractIdentifierName(decl.name)?.let { exports.add(it) }
                }
                is FunctionDeclaration -> bodyStmt.name?.text?.let { exports.add(it) }
                is ClassDeclaration -> bodyStmt.name?.text?.let { exports.add(it) }
                is EnumDeclaration -> exports.add(bodyStmt.name.text)
                is ModuleDeclaration -> {
                    // For dotted namespace names (X.Y.Z), use the first part (X)
                    val nsExportName = extractIdentifierName(bodyStmt.name)
                        ?: flattenDottedNamespaceName(bodyStmt.name).firstOrNull()
                    nsExportName?.let { exports.add(it) }
                    // Recurse into nested namespaces to collect their exports too
                    if (!hasDeclareModifier(bodyStmt) && !isTypeOnlyNamespace(bodyStmt)) {
                        collectMergedNamespaceExportsFromModule(bodyStmt)
                    }
                }
                is ImportEqualsDeclaration -> exports.add(bodyStmt.name.text)
                else -> {}
            }
        }
    }

    private fun collectConstEnumValues(stmts: List<Statement>) {
        for (stmt in stmts) {
            val decl = when {
                stmt is EnumDeclaration && ModifierFlag.Const in stmt.modifiers
                    && !hasDeclareModifier(stmt) -> stmt
                stmt is ExportDeclaration -> null // skip
                stmt is ModuleDeclaration -> {
                    // Recurse into namespace bodies to find nested const enums
                    val body = stmt.body
                    if (body is ModuleBlock) collectConstEnumValues(body.statements)
                    null
                }
                else -> null
            } ?: continue

            val members = mutableMapOf<String, Any?>()
            var nextValue = 0.0
            for (member in decl.members) {
                val name = extractEnumMemberName(member.name)
                val value: Any? = if (member.initializer == null) {
                    val v = nextValue
                    nextValue++
                    v
                } else {
                    val init = member.initializer
                    when {
                        init is NumericLiteralNode -> {
                            val v = init.text.toDoubleOrNull()
                            nextValue = (v ?: nextValue) + 1.0
                            v
                        }
                        init is PrefixUnaryExpression && init.operator == Minus -> {
                            val inner = (init.operand as? NumericLiteralNode)?.text?.toDoubleOrNull()
                            if (inner != null) {
                                nextValue = -inner + 1.0
                                -inner
                            } else null
                        }
                        init is StringLiteralNode -> {
                            // After a string member, auto-increment is disrupted
                            init.text
                        }
                        else -> null // non-constant, don't inline
                    }
                }
                members[name] = value
            }
            constEnumValues[decl.name.text] = members
        }
    }

    /**
     * Attempts to inline a const enum member reference.
     * [commentLabel] is the text inside the `/* */` comment (e.g. "E.X" or `E["X"]`).
     * Returns null if the enum or member is not found, or the value is non-constant (null stored).
     */
    private fun tryInlineConstEnumMember(enumName: String, memberName: String, commentLabel: String): Expression? {
        val enumMembers = constEnumValues[enumName] ?: return null
        if (!enumMembers.containsKey(memberName)) return null
        val value = enumMembers[memberName] ?: return null  // null = non-constant, can't inline
        val safeLabel = commentLabel.replace("*/", "*_/")
        val comment = Comment(
            text = "/* $safeLabel */",
            hasTrailingNewLine = false,
            kind = MultiLineComment,
            pos = -1, end = -1,
        )
        return when (value) {
            is Double -> {
                val isNegative = value < 0
                val absText = formatConstEnumDouble(if (isNegative) -value else value)
                val literal = NumericLiteralNode(
                    text = absText,
                    pos = -1, end = -1,
                    trailingComments = listOf(comment),
                )
                if (isNegative) {
                    PrefixUnaryExpression(
                        operator = Minus,
                        operand = literal,
                        pos = -1, end = -1,
                    )
                } else literal
            }
            is String -> StringLiteralNode(
                text = value,
                pos = -1, end = -1,
                trailingComments = listOf(comment),
            )
            else -> null
        }
    }

    /**
     * Convert a [ConstantValue] from the checker to an inlined expression with a trailing comment.
     */
    private fun constValueToExpression(value: ConstantValue, commentLabel: String): Expression {
        val safeLabel = commentLabel.replace("*/", "*_/")
        val comment = Comment(
            text = "/* $safeLabel */",
            hasTrailingNewLine = false,
            kind = MultiLineComment,
            pos = -1, end = -1,
        )
        return when (value) {
            is ConstantValue.NumberValue -> {
                val isNegative = value.value < 0
                val absText = formatConstEnumDouble(if (isNegative) -value.value else value.value)
                val literal = NumericLiteralNode(
                    text = absText,
                    pos = -1, end = -1,
                    trailingComments = listOf(comment),
                )
                if (isNegative) {
                    PrefixUnaryExpression(
                        operator = Minus,
                        operand = literal,
                        pos = -1, end = -1,
                    )
                } else literal
            }
            is ConstantValue.StringValue -> StringLiteralNode(
                text = value.value,
                pos = -1, end = -1,
                trailingComments = listOf(comment),
            )
        }
    }

    /** Formats a non-negative double to its TypeScript const-enum inline form. */
    private fun formatConstEnumDouble(value: Double): String {
        // If the value is a whole number, emit as integer string
        return if (value == kotlin.math.floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            // Emit with minimal decimal representation
            value.toString()
        }
    }

    private fun tryEvaluateNumericLiteral(expr: Expression): Int? {
        return when (expr) {
            is NumericLiteralNode -> expr.text.toIntOrNull() ?: run {
                // Handle hex/binary/octal literals
                val text = expr.text
                when {
                    text.startsWith("0x") || text.startsWith("0X") -> text.substring(2).toLongOrNull(16)?.toInt()
                    text.startsWith("0b") || text.startsWith("0B") -> text.substring(2).toLongOrNull(2)?.toInt()
                    text.startsWith("0o") || text.startsWith("0O") -> text.substring(2).toLongOrNull(8)?.toInt()
                    else -> null
                }
            }
            is PrefixUnaryExpression -> {
                val inner = tryEvaluateNumericLiteral(expr.operand)
                when (expr.operator) {
                    Minus -> inner?.let { -it }
                    Plus -> inner
                    Tilde -> inner?.let { it.inv() }
                    else -> null
                }
            }
            is ParenthesizedExpression -> tryEvaluateNumericLiteral(expr.expression)
            is BinaryExpression -> {
                val left = tryEvaluateNumericLiteral(expr.left)
                val right = tryEvaluateNumericLiteral(expr.right)
                if (left == null || right == null) null
                else when (expr.operator) {
                    Plus -> left + right
                    Minus -> left - right
                    Asterisk -> left * right
                    Slash -> if (right != 0) left / right else null
                    Percent -> if (right != 0) left % right else null
                    AsteriskAsterisk -> {
                        if (right >= 0) left.toDouble().pow(right).toInt() else null
                    }
                    LessThanLessThan -> left shl right
                    GreaterThanGreaterThan -> left shr right
                    GreaterThanGreaterThanGreaterThan -> left ushr right
                    Ampersand -> left and right
                    Bar -> left or right
                    Caret -> left xor right
                    else -> null
                }
            }

            else -> null
        }
    }

    /**
     * Evaluates a constant expression in an enum member initializer.
     * Handles numeric literals, prefix unary (-/+/~), binary arithmetic and bitwise ops,
     * parenthesized expressions, and references to previously-defined enum members.
     * Returns null if the expression cannot be statically evaluated.
     */
    private fun evaluateConstantExpression(expr: Expression, memberValues: Map<String, Long>): Long? {
        return when (expr) {
            is NumericLiteralNode -> {
                val text = expr.text.trim().replace("_", "")
                when {
                    text.startsWith("0x") || text.startsWith("0X") ->
                        text.substring(2).toLongOrNull(16)
                    text.startsWith("0b") || text.startsWith("0B") ->
                        text.substring(2).toLongOrNull(2)
                    text.startsWith("0o") || text.startsWith("0O") ->
                        text.substring(2).toLongOrNull(8)
                    else -> text.toLongOrNull()
                }
            }
            is PrefixUnaryExpression -> {
                val operand = evaluateConstantExpression(expr.operand, memberValues) ?: return null
                when (expr.operator) {
                    Minus -> -operand
                    Plus -> operand
                    Tilde -> operand.toInt().inv().toLong()
                    else -> null
                }
            }
            is BinaryExpression -> {
                val left = evaluateConstantExpression(expr.left, memberValues) ?: return null
                val right = evaluateConstantExpression(expr.right, memberValues) ?: return null
                // JS bitwise/shift ops work on 32-bit integers
                when (expr.operator) {
                    Plus -> left + right
                    Minus -> left - right
                    Asterisk -> left * right
                    Slash -> if (right == 0L) null else left / right
                    Percent -> if (right == 0L) null else left % right
                    LessThanLessThan -> (left.toInt() shl (right.toInt() and 31)).toLong()
                    GreaterThanGreaterThan -> (left.toInt() shr (right.toInt() and 31)).toLong()
                    GreaterThanGreaterThanGreaterThan -> (left.toInt() ushr (right.toInt() and 31)).toUInt().toLong()
                    Bar -> (left.toInt() or right.toInt()).toLong()
                    Ampersand -> (left.toInt() and right.toInt()).toLong()
                    Caret -> (left.toInt() xor right.toInt()).toLong()
                    else -> null
                }
            }
            is Identifier -> memberValues[expr.text]
            is PropertyAccessExpression -> {
                // Handle cross-enum references like Foo.a or M.N.Foo.a where Foo is a previously-defined enum.
                // For qualified paths (M.N.E1.a), extract the enum name (second-to-last segment) and member name.
                val obj = expr.expression
                val memberName = expr.name.text
                when {
                    obj is Identifier -> allEnumMemberValues[obj.text]?.get(memberName)
                        // Fall back to Checker for cross-file enum references through import aliases
                        ?: checker?.resolveEnumMemberValue(obj.text, memberName, currentFileName)
                    obj is PropertyAccessExpression -> allEnumMemberValues[obj.name.text]?.get(memberName)
                    else -> null
                }
            }
            is ElementAccessExpression -> {
                // Handle E["member"] access (e.g. E["__foo"])
                val obj = expr.expression
                val arg = expr.argumentExpression
                if (obj is Identifier && arg is StringLiteralNode) {
                    // Check both current enum members and cross-enum references
                    memberValues[arg.text] ?: allEnumMemberValues[obj.text]?.get(arg.text)
                } else null
            }
            is ParenthesizedExpression -> evaluateConstantExpression(expr.expression, memberValues)
            else -> null
        }
    }

    // -----------------------------------------------------------------
    // Namespace / Module declaration transform
    // -----------------------------------------------------------------

    /**
     * Transforms `namespace N { ... }` or `module N { ... }` into:
     * ```javascript
     * var N;
     * (function (N) {
     *     // transformed body
     * })(N || (N = {}));
     * ```
     */
    private fun transformModuleDeclaration(
        decl: ModuleDeclaration,
        nested: Boolean = false,
        parentNsName: String? = null,
        // True when this level came from a dotted shorthand `namespace A.B { }`.
        // Dotted shorthand levels use `var` for their inner variable (like TypeScript does).
        useDottedVar: Boolean = false,
    ): List<Statement> {
        // Ambient (`declare`) module declarations are already handled
        // Type-only namespaces (only types inside) produce no output
        if (isTypeOnlyNamespace(decl)) return emptyList()

        // Handle dotted namespace names: `namespace A.B.C { ... }` → nested IIFEs.
        // Our Parser stores the dotted name as a PropertyAccessExpression in decl.name.
        // Flatten it into a chain of nested ModuleDeclarations, then process recursively.
        // Dotted shorthand uses `var` for all levels (not `let`).
        if (decl.name is PropertyAccessExpression) {
            val parts = flattenDottedNamespaceName(decl.name)
            if (parts.isEmpty()) return emptyList()
            // Build a chain of nested ModuleDeclarations from the parts
            // e.g. [hello, hi, world] + body → M(hello, body=M(hi, body=M(world, body=block)))
            fun buildNested(idx: Int): ModuleDeclaration {
                return if (idx == parts.lastIndex) {
                    decl.copy(name = syntheticId(parts[idx]))
                } else {
                    decl.copy(name = syntheticId(parts[idx]), body = buildNested(idx + 1))
                }
            }
            return transformModuleDeclaration(buildNested(0), nested, parentNsName, useDottedVar = !nested)
        }

        val moduleName = extractIdentifierName(decl.name)
            ?: return emptyList()

        val body = decl.body
        val bodyStatements: List<Statement> = when (body) {
            is ModuleBlock -> body.statements
            is ModuleDeclaration -> {
                // Nested namespace: recursively transform the inner declaration.
                // The inner declarations live inside a new IIFE function scope, so
                // save/restore emittedVarNames so each IIFE body starts fresh.
                val savedE = emittedVarNames.toMutableSet()
                val savedD = declaredNames.toMutableSet()
                emittedVarNames.clear()
                declaredNames.clear()
                // Detect name collision: if any declaration deep inside the nested module body
                // shadows the namespace name, the IIFE parameter must be renamed.
                val hasCollision = namespaceBodyHasNameCollision(moduleName, listOf(body))
                val iifeParam = if (hasCollision) {
                    val suffix = (nsRenameSuffix[moduleName] ?: 0) + 1
                    nsRenameSuffix[moduleName] = suffix
                    "${moduleName}_$suffix"
                } else moduleName
                // Push merged exports for this namespace level so inner bodies can qualify
                // references to exports from this (parent) namespace via outerNamespaceStack.
                val mergedExports = mergedNamespaceExports[moduleName] ?: emptySet()
                if (mergedExports.isNotEmpty()) {
                    outerNamespaceStack.add(Triple(iifeParam, moduleName, mergedExports))
                }
                val innerStatements = transformModuleDeclaration(body, nested = true, parentNsName = iifeParam, useDottedVar = useDottedVar)
                if (mergedExports.isNotEmpty()) {
                    outerNamespaceStack.removeLastOrNull()
                }
                emittedVarNames.clear(); emittedVarNames.addAll(savedE)
                declaredNames.clear(); declaredNames.addAll(savedD)
                return wrapInNamespaceIife(
                    moduleName = moduleName,
                    innerStatements = innerStatements,
                    outerDecl = decl,
                    nested = nested,
                    parentNsName = parentNsName,
                    useDottedVar = useDottedVar,
                    iifeParamName = iifeParam,
                )
            }

            null -> return emptyList()
            else -> return emptyList()
        }

        // Each namespace body is a separate IIFE scope, so names declared inside
        // one body (e.g. `let Color;`) must not suppress declarations in sibling bodies.
        // Save and restore both sets so inner-scope names don't pollute the outer scope.
        // Also clear before transforming the body so that outer-scope names (e.g. a top-level
        // `namespace Foo`) don't suppress inner `let Foo;` declarations in sibling namespaces.
        val savedEmittedVarNames = emittedVarNames.toMutableSet()
        val savedDeclaredNames = declaredNames.toMutableSet()
        emittedVarNames.clear()
        declaredNames.clear()

        // Detect name collision: if any declaration/parameter in the body shadows the namespace name,
        // the IIFE parameter must be renamed (e.g. m1 → m1_1) to avoid shadowing.
        val hasCollision = namespaceBodyHasNameCollision(moduleName, bodyStatements)
        val iifeParamName = if (hasCollision) {
            val suffix = (nsRenameSuffix[moduleName] ?: 0) + 1
            nsRenameSuffix[moduleName] = suffix
            "${moduleName}_$suffix"
        } else moduleName

        // Transform body statements, rewriting exports using the IIFE parameter name
        val transformedBody = transformNamespaceBody(iifeParamName, bodyStatements, originalName = moduleName)

        emittedVarNames.clear()
        emittedVarNames.addAll(savedEmittedVarNames)
        declaredNames.clear()
        declaredNames.addAll(savedDeclaredNames)

        // If the body transformed to nothing AND there are no runtime-relevant declarations,
        // don't emit the namespace at all — but only if the original body had only types.
        // Namespaces containing ambient function/variable declarations (no body) still get
        // their IIFE wrapper emitted, even though those declarations produce no output.
        if (transformedBody.isEmpty() && bodyStatements.all { isTypeOnlyStatement(it) }) {
            return orphanedComments(decl)
        }

        return wrapInNamespaceIife(moduleName, transformedBody, decl, nested = nested, parentNsName = parentNsName, useDottedVar = useDottedVar, iifeParamName = iifeParamName)
    }

    private fun wrapInNamespaceIife(
        moduleName: String,
        innerStatements: List<Statement>,
        outerDecl: ModuleDeclaration,
        nested: Boolean = false,
        parentNsName: String? = null,
        useDottedVar: Boolean = false,
        iifeParamName: String = moduleName,
    ): List<Statement> {
        val nsId = syntheticId(moduleName)

        // Skip var N; if a class/function with the same name was declared BEFORE this
        // namespace (in source order), or if a prior enum/namespace already emitted var N;
        // Inside function scopes, always emit `let` since each function has its own scope
        val needsVarDecl = functionScopeDepth > 0 ||
                (moduleName !in declaredNames && moduleName !in emittedVarNames)
        // Track that this name now has a runtime var (for subsequent same-name dedup)
        if (functionScopeDepth == 0) {
            emittedVarNames.add(moduleName)
            declaredNames.add(moduleName)
        }

        // Inner namespace declarations use `let` (they're inside a function scope),
        // EXCEPT when the namespace uses dotted shorthand (`namespace A.B { }`), in which
        // case TypeScript always emits `var` for all levels.
        // Also use `let` when inside a function body (functionScopeDepth > 0).
        val varKeyword = if ((nested || functionScopeDepth > 0) && !useDottedVar) SyntaxKind.LetKeyword else VarKeyword

        // In ES module format, preserve the `export` modifier so the file is still recognized
        // as a module file (e.g. `export namespace N {}` → `export var N; IIFE`).
        val isEsModuleFormat = options.effectiveModule in setOf(
            ModuleKind.ES2015, ModuleKind.ES2020, ModuleKind.ES2022, ModuleKind.ESNext
        )
        val varModifiers = if (!nested && isEsModuleFormat && ModifierFlag.Export in outerDecl.modifiers) {
            setOf(ModifierFlag.Export)
        } else {
            emptySet()
        }
        val varDecl = if (needsVarDecl) {
            VariableStatement(
                declarationList = VariableDeclarationList(
                    declarations = listOf(
                        VariableDeclaration(
                            name = Identifier(text = moduleName, pos = -1, end = -1),
                            pos = -1, end = -1,
                        )
                    ),
                    flags = varKeyword,
                    pos = -1, end = -1,
                ),
                modifiers = varModifiers,
                pos = -1, end = -1,
                leadingComments = outerDecl.leadingComments,
            )
        } else null

        // Build the IIFE call argument.
        // When the namespace is exported from a parent namespace (parentNsName != null), use:
        //   (N = Parent.N || (Parent.N = {}))
        // Otherwise use the simple form:
        //   (N || (N = {}))
        val iifeArg: Expression = if (parentNsName != null) {
            // N = Parent.N || (Parent.N = {})
            val parentProp = PropertyAccessExpression(
                expression = syntheticId(parentNsName),
                name = Identifier(text = moduleName, pos = -1, end = -1),
                pos = -1, end = -1,
            )
            BinaryExpression(
                left = nsId,
                operator = Equals,
                right = BinaryExpression(
                    left = parentProp,
                    operator = BarBar,
                    right = ParenthesizedExpression(
                        expression = BinaryExpression(
                            left = PropertyAccessExpression(
                                expression = syntheticId(parentNsName),
                                name = Identifier(text = moduleName, pos = -1, end = -1),
                                pos = -1, end = -1,
                            ),
                            operator = Equals,
                            right = ObjectLiteralExpression(
                                properties = emptyList(),
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            )
        } else {
            // N || (N = {})
            BinaryExpression(
                left = nsId,
                operator = BarBar,
                right = ParenthesizedExpression(
                    expression = BinaryExpression(
                        left = syntheticId(moduleName),
                        operator = Equals,
                        right = ObjectLiteralExpression(
                            properties = emptyList(),
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                pos = -1, end = -1,
            )
        }

        // (function (N) { ... })(iifeArg);
        val iife = ExpressionStatement(
            expression = CallExpression(
                expression = ParenthesizedExpression(
                    expression = FunctionExpression(
                        parameters = listOf(
                            Parameter(
                                name = Identifier(text = iifeParamName, pos = -1, end = -1),
                                pos = -1, end = -1,
                            )
                        ),
                        body = Block(
                            statements = innerStatements,
                            pos = -1, end = -1,
                        ),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                arguments = listOf(iifeArg),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
            trailingComments = outerDecl.trailingComments,
        )

        return if (varDecl != null) listOf(varDecl, iife) else {
            if (outerDecl.leadingComments != null) {
                listOf(iife.copy(leadingComments = outerDecl.leadingComments))
            } else {
                listOf(iife)
            }
        }
    }

    /**
     * Transforms namespace body statements, handling exported members.
     *
     * Exported variables get `N.x = x;` assignments.
     * Exported functions get `N.f = f;` after the declaration.
     * Exported classes get `N.C = C;` after the declaration.
     */
    /**
     * Checks if any binding in the namespace body shadows the namespace name.
     * This includes top-level declarations, function/method parameters, and block-scoped variables.
     */
    /**
     * Checks if any binding in the namespace body shadows the namespace name.
     */
    private fun namespaceBodyHasNameCollision(name: String, statements: List<Statement>): Boolean {
        fun checkParams(params: List<Parameter>): Boolean =
            params.any { (it.name as? Identifier)?.text == name }

        fun checkNode(node: Node): Boolean {
            return when (node) {
                is ClassDeclaration -> {
                    node.name?.text == name ||
                    node.members.any { m ->
                        when (m) {
                            is MethodDeclaration -> checkParams(m.parameters) ||
                                (m.body?.statements?.any { checkNode(it) } == true)
                            is Constructor -> checkParams(m.parameters) ||
                                (m.body?.statements?.any { checkNode(it) } == true)
                            is GetAccessor -> (m.body?.statements?.any { checkNode(it) } == true)
                            is SetAccessor -> checkParams(m.parameters) ||
                                (m.body?.statements?.any { checkNode(it) } == true)
                            else -> false
                        }
                    }
                }
                is FunctionDeclaration -> node.name?.text == name || checkParams(node.parameters) ||
                    (node.body?.statements?.any { checkNode(it) } == true)
                is EnumDeclaration -> node.name.text == name
                is VariableStatement -> node.declarationList.declarations.any { extractIdentifierName(it.name) == name }
                is ModuleDeclaration -> extractIdentifierName(node.name) == name ||
                    // Also check inside inner module bodies — a `var M` inside a child namespace
                    // shadows the outer IIFE parameter since the child IIFE is a nested scope.
                    when (val b = node.body) {
                        is ModuleBlock -> b.statements.any { checkNode(it) }
                        is ModuleDeclaration -> checkNode(b)
                        else -> false
                    }
                is ImportEqualsDeclaration -> {
                    // Non-exported import-equals becomes a local `var M = ...` which shadows
                    // the namespace param. Exported import-equals becomes `M.M = ...` (no collision).
                    node.name.text == name && ModifierFlag.Export !in node.modifiers
                }
                is Block -> node.statements.any { checkNode(it) }
                is IfStatement -> checkNode(node.thenStatement) || (node.elseStatement?.let { checkNode(it) } == true)
                is ForStatement -> {
                    val initHas = when (val init = node.initializer) {
                        is VariableDeclarationList -> init.declarations.any { extractIdentifierName(it.name) == name }
                        else -> false
                    }
                    initHas || checkNode(node.statement)
                }
                is ForInStatement -> checkNode(node.statement)
                is ForOfStatement -> checkNode(node.statement)
                is WhileStatement -> checkNode(node.statement)
                is DoStatement -> checkNode(node.statement)
                is ExpressionStatement -> false
                else -> false
            }
        }
        return statements.any { checkNode(it) }
    }

    private fun transformNamespaceBody(
        nsName: String,
        statements: List<Statement>,
        originalName: String = nsName,
    ): List<Statement> {
        // First pass: collect exported variable names and locally declared names.
        // `exportedNames` is used for body-statement qualification (qualifyNamespaceRefs).
        // `locallyDeclaredNames` is used to exclude names declared IN THIS BLOCK from
        // heritage-clause qualification (since they are local variables in the IIFE).
        val locallyDeclaredNames = mutableSetOf<String>()
        val exportedNames = mutableSetOf<String>()
        for (stmt in statements) {
            val isExported = when (stmt) {
                is VariableStatement -> ModifierFlag.Export in stmt.modifiers
                is FunctionDeclaration -> ModifierFlag.Export in stmt.modifiers && stmt.body != null
                is ClassDeclaration -> ModifierFlag.Export in stmt.modifiers
                is EnumDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ModuleDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ImportEqualsDeclaration -> ModifierFlag.Export in stmt.modifiers
                else -> false
            }
            // Collect all locally declared names (whether exported or not).
            // These are class/function/enum/var names in scope as local variables in this IIFE.
            when (stmt) {
                is VariableStatement -> for (decl in stmt.declarationList.declarations) {
                    extractIdentifierName(decl.name)?.let { locallyDeclaredNames.add(it) }
                }
                is FunctionDeclaration -> stmt.name?.text?.let { locallyDeclaredNames.add(it) }
                is ClassDeclaration -> stmt.name?.text?.let { locallyDeclaredNames.add(it) }
                is EnumDeclaration -> locallyDeclaredNames.add(stmt.name.text)
                is ModuleDeclaration -> extractIdentifierName(stmt.name)?.let { locallyDeclaredNames.add(it) }
                else -> {}
            }
            if (isExported) {
                when (stmt) {
                    is VariableStatement -> for (decl in stmt.declarationList.declarations) {
                        extractIdentifierName(decl.name)?.let { exportedNames.add(it) }
                    }

                    is FunctionDeclaration -> stmt.name?.text?.let { exportedNames.add(it) }
                    is ClassDeclaration -> stmt.name?.text?.let { exportedNames.add(it) }
                    is EnumDeclaration -> exportedNames.add(stmt.name.text)
                    is ModuleDeclaration -> extractIdentifierName(stmt.name)?.let { exportedNames.add(it) }
                    is ImportEqualsDeclaration -> exportedNames.add(stmt.name.text)
                    else -> {}
                }
            }
        }

        // Include exports from merged blocks of the same namespace
        // so that references in this block to members exported from other blocks are qualified.
        // Use the original name (not the IIFE param name which may be renamed, e.g. M_1).
        // Apply at top level and for dotted namespace parts, but NOT for genuinely nested
        // namespaces that happen to share a name with an outer namespace (e.g.,
        // `namespace M { namespace m4 { namespace M {} } }` — the inner M is different from outer M).
        // Detect genuine nesting by checking if any entry on outerNamespaceStack has the same
        // original name — if so, this is a nested namespace shadowing an outer one.
        val isShadowingOuter = outerNamespaceStack.any { it.second == originalName }
        if ((outerNamespaceStack.isEmpty() || originalName in mergedNamespaceExports) && !isShadowingOuter) {
            mergedNamespaceExports[originalName]?.let { exportedNames.addAll(it) }
        }
        // Exported variable names that DON'T have local bindings in the IIFE
        // (classes/functions/enums/modules are declared locally even when exported)
        val exportedVarOnlyNames = exportedNames - locallyDeclaredNames +
            // Re-add exported var names (they were added to locallyDeclaredNames but are actually
            // turned into nsName.x = ... assignments, not local var declarations)
            statements.filterIsInstance<VariableStatement>()
                .filter { ModifierFlag.Export in it.modifiers }
                .flatMap { it.declarationList.declarations.mapNotNull { d -> extractIdentifierName(d.name) } }
                .toSet()

        val result = mutableListOf<Statement>()

        for (stmt in statements) {
            // First, check for type-only statements that should be removed
            when (stmt) {
                is InterfaceDeclaration -> continue
                is TypeAliasDeclaration -> continue
                // ExportDeclaration inside a namespace is not valid JS; TypeScript erases it
                // (members are exported via `Ns.name = value` assignments, not ES export syntax)
                is ExportDeclaration -> continue
                else -> {}
            }

            if (stmt is Declaration && hasDeclareModifier(stmt)) continue
            if (stmt is VariableStatement && ModifierFlag.Declare in stmt.modifiers) continue

            val isExported = when (stmt) {
                is VariableStatement -> ModifierFlag.Export in stmt.modifiers
                is FunctionDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ClassDeclaration -> ModifierFlag.Export in stmt.modifiers
                is EnumDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ModuleDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ImportEqualsDeclaration -> ModifierFlag.Export in stmt.modifiers
                else -> false
            }

            when (stmt) {
                is ImportEqualsDeclaration -> {
                    if (stmt.isTypeOnly) continue
                    // Skip duplicate import alias: if the same alias name was already declared by
                    // a prior import in this namespace body, TypeScript skips emitting the second.
                    val aliasNameDup = stmt.name.text
                    val isDuplicate = statements.takeWhile { it !== stmt }.any {
                        it is ImportEqualsDeclaration && it.name.text == aliasNameDup
                    }
                    if (isDuplicate) continue
                    // Skip aliases to type-only namespaces (they have no runtime value)
                    val ref = stmt.moduleReference
                    if (ref is Identifier) {
                        val refName = ref.text
                        // Check top-level type-only names (e.g. namespaces with only interfaces)
                        if (refName in topLevelTypeOnlyNames) continue
                        val referencedNs = statements.filterIsInstance<ModuleDeclaration>()
                            .firstOrNull { extractIdentifierName(it.name) == refName }
                        if (referencedNs != null && isTypeOnlyNamespace(referencedNs)) continue
                    }
                    if (ref is QualifiedName) {
                        val rootName = generateSequence(ref) { (it.left as? QualifiedName) }.last().left
                        if (rootName is Identifier && rootName.text in topLevelTypeOnlyNames) continue
                    }
                    // For non-exported internal imports (import Y = X), erase if the alias
                    // is not used in any value position in the namespace body.
                    // This handles type aliases (import T = M1.I) and unused value aliases.
                    if (!isExported) {
                        val aliasName = stmt.name.text
                        val otherStmts = statements.filter { it !== stmt }
                        val valueRefs = collectValueReferences(otherStmts.filterIsInstance<Statement>())
                        if (aliasName !in valueRefs) continue
                    }
                    if (isExported) {
                        // `export import X = R` → `nsName.X = R`
                        // Use exportedVarOnlyNames to avoid qualifying locally-declared names
                        // (e.g., IIFE parameters for nested namespaces should stay unqualified)
                        val value: Expression = when (ref) {
                            is Expression -> qualifyNamespaceRefs(nsName, exportedVarOnlyNames, transformExpression(ref))
                            is QualifiedName -> qualifyNamespaceRefs(nsName, exportedVarOnlyNames, qualifiedNameToPropertyAccess(ref))
                            else -> syntheticId(stmt.name.text)
                        }
                        result.add(ExpressionStatement(
                            expression = BinaryExpression(
                                left = PropertyAccessExpression(
                                    expression = Identifier(nsName),
                                    name = stmt.name,
                                ),
                                operator = Equals,
                                right = value,
                            ),
                            leadingComments = stmt.leadingComments,
                            trailingComments = stmt.trailingComments,
                        ))
                    } else {
                        // `import R = N` → `var R = N`
                        result.addAll(transformStatement(stmt))
                    }
                }

                is VariableStatement -> {
                    if (isExported) {
                        // export var x = expr → nsName.x = expr;
                        // Multiple declarations in one statement → comma expression: N.a = 1, N.b = 2
                        val assignments = mutableListOf<Expression>()
                        for (decl in stmt.declarationList.declarations) {
                            val varName = extractIdentifierName(decl.name)
                            if (varName != null && decl.initializer != null) {
                                val init =
                                    qualifyNamespaceRefs(nsName, exportedVarOnlyNames, transformExpression(decl.initializer))
                                assignments.add(
                                    BinaryExpression(
                                        left = PropertyAccessExpression(
                                            expression = Identifier(nsName),
                                            name = Identifier(varName),
                                        ),
                                        operator = Equals,
                                        right = init,
                                        pos = -1, end = -1,
                                    )
                                )
                            }
                        }
                        if (assignments.isNotEmpty()) {
                            // Build a comma expression if there are multiple assignments
                            val expr = assignments.reduce { acc, e ->
                                BinaryExpression(left = acc, operator = Comma, right = e, pos = -1, end = -1)
                            }
                            result.add(ExpressionStatement(
                                expression = expr,
                                leadingComments = stmt.leadingComments,
                                trailingComments = stmt.trailingComments,
                                pos = -1, end = -1,
                            ))
                        }
                    } else {
                        val strippedStmt = stmt.copy(
                            modifiers = stmt.modifiers - ModifierFlag.Export,
                        )
                        val transformed = transformVariableStatement(strippedStmt)
                        if (exportedVarOnlyNames.isNotEmpty()) {
                            result.addAll(transformed.map { qualifyStatementRefs(nsName, exportedVarOnlyNames, it) })
                        } else {
                            result.addAll(transformed)
                        }
                    }
                }

                is FunctionDeclaration -> {
                    val strippedStmt = stmt.copy(
                        modifiers = stmt.modifiers - ModifierFlag.Export,
                    )
                    val transformed = transformFunctionDeclaration(strippedStmt)
                    if (exportedVarOnlyNames.isNotEmpty()) {
                        result.addAll(transformed.map { qualifyStatementRefs(nsName, exportedVarOnlyNames, it) })
                    } else {
                        result.addAll(transformed)
                    }

                    // Track function name so a subsequent same-named namespace/enum
                    // doesn't emit a duplicate var/let declaration (mirrors transformStatements).
                    stmt.name?.text?.let { declaredNames.add(it) }

                    if (isExported && stmt.name != null && stmt.body != null) {
                        result.add(makeNamespaceExportAssignment(nsName, stmt.name.text))
                    } else if (isExported && ModifierFlag.Default in stmt.modifiers && stmt.name == null && stmt.body != null) {
                        // export default function () {} inside namespace: anonymous, add export assignment
                        // with synthetic name (TypeScript's error recovery behavior for TS1113)
                        val anonName = "default_${++anonDefaultCounter}"
                        result.add(makeNamespaceExportAssignment(nsName, anonName))
                    }
                }

                is ClassDeclaration -> {
                    // Heritage clause qualification: names declared in THIS block are local
                    // variables inside the IIFE and should NOT be qualified. Names from OTHER
                    // (merged) namespace blocks are only accessible via the namespace object
                    // and must be qualified (e.g. `extends M.B` not `extends B` when B was
                    // exported from a previous `namespace M { }` block).
                    // Use exportedNames minus locallyDeclaredNames for heritage qualification.
                    val heritageQualifyNames = exportedNames - locallyDeclaredNames
                    val qualifiedHeritage = stmt.heritageClauses?.map { clause ->
                        clause.copy(types = clause.types.map { type ->
                            type.copy(expression = qualifyNamespaceRefs(nsName, heritageQualifyNames, type.expression))
                        })
                    }
                    // Anonymous export default class: assign synthetic name
                    val classStmt = if (stmt.name == null && isExported) {
                        val anonName = "default_${++anonDefaultCounter}"
                        stmt.copy(
                            modifiers = stmt.modifiers - ModifierFlag.Export - ModifierFlag.Default,
                            name = syntheticId(anonName),
                            heritageClauses = qualifiedHeritage,
                        )
                    } else {
                        stmt.copy(
                            modifiers = stmt.modifiers - ModifierFlag.Export,
                            heritageClauses = qualifiedHeritage,
                        )
                    }
                    val transformed = transformClassDeclaration(classStmt)
                    if (exportedVarOnlyNames.isNotEmpty()) {
                        result.addAll(transformed.map { qualifyStatementRefs(nsName, exportedVarOnlyNames, it) })
                    } else {
                        result.addAll(transformed)
                    }

                    val className = classStmt.name?.text
                    // Track class name so a subsequent same-named namespace/enum
                    // doesn't emit a duplicate var/let declaration (mirrors transformStatements).
                    className?.let { declaredNames.add(it) }

                    if (isExported && className != null) {
                        result.add(makeNamespaceExportAssignment(nsName, className))
                    }
                }

                is EnumDeclaration -> {
                    val strippedStmt = stmt.copy(
                        modifiers = stmt.modifiers - ModifierFlag.Export,
                    )
                    // Exported nested enums embed the parent assignment in the IIFE arg
                    // (E = Parent.E || (Parent.E = {})) — no separate export assignment needed.
                    val parentForIife = if (isExported) nsName else null
                    val transformed = transformEnum(strippedStmt, nested = true, parentNsName = parentForIife)
                    result.addAll(transformed)
                }

                is ModuleDeclaration -> {
                    val strippedStmt = stmt.copy(
                        modifiers = stmt.modifiers - ModifierFlag.Export,
                    )
                    // When exported, embed the parent->child assignment in the IIFE arg
                    // (N = parent.N || (parent.N = {})) rather than a separate assignment
                    val parentForIife = if (isExported) nsName else null
                    // Push current namespace context so inner bodies can qualify outer refs.
                    // Use exportedVarOnlyNames: functions/classes/enums are local variables in the
                    // IIFE and don't need qualification, but exported vars become nsName.x = ...
                    // assignments and DO need qualification from inner scopes.
                    outerNamespaceStack.add(Triple(nsName, originalName, exportedVarOnlyNames))
                    val transformed = transformModuleDeclaration(strippedStmt, nested = true, parentNsName = parentForIife)
                    outerNamespaceStack.removeLastOrNull()
                    result.addAll(transformed)
                }

                else -> {
                    val transformed = transformStatement(stmt)
                    if (exportedVarOnlyNames.isNotEmpty()) {
                        result.addAll(transformed.map { qualifyStatementRefs(nsName, exportedVarOnlyNames, it) })
                    } else {
                        result.addAll(transformed)
                    }
                }
            }
        }

        // Apply outer namespace qualification: inner namespace bodies may reference
        // exports from enclosing namespaces (e.g., `x` → `M_1.x` when `x` is exported from M).
        // Exclude names that are locally declared in this scope or that match the IIFE parameter
        // (which is the local variable for this namespace) to avoid over-qualification.
        var qualifiedResult: List<Statement> = result
        val localAndParam = locallyDeclaredNames + nsName + originalName
        for ((outerNsName, _, outerExportedNames) in outerNamespaceStack) {
            val outerNamesNotLocal = outerExportedNames - localAndParam
            if (outerNamesNotLocal.isNotEmpty()) {
                qualifiedResult = qualifiedResult.map { qualifyStatementRefs(outerNsName, outerNamesNotLocal, it) }
            }
        }
        return qualifiedResult
    }

    /**
     * Recursively qualifies exported var refs in all expression positions of a statement.
     */
    private fun qualifyStatementRefs(nsName: String, names: Set<String>, stmt: Statement): Statement {
        fun q(e: Expression) = qualifyNamespaceRefs(nsName, names, e)
        fun qStmt(s: Statement) = qualifyStatementRefs(nsName, names, s)
        return when (stmt) {
            is ExpressionStatement -> stmt.copy(expression = q(stmt.expression))
            is ReturnStatement -> if (stmt.expression != null) stmt.copy(expression = q(stmt.expression)) else stmt
            is IfStatement -> stmt.copy(
                expression = q(stmt.expression),
                thenStatement = qStmt(stmt.thenStatement),
                elseStatement = stmt.elseStatement?.let { qStmt(it) },
            )
            is Block -> stmt.copy(statements = stmt.statements.map { qStmt(it) })
            is ForStatement -> stmt.copy(
                initializer = when (val i = stmt.initializer) {
                    is Expression -> q(i)
                    is VariableDeclarationList -> i.copy(declarations = i.declarations.map { d ->
                        if (d.initializer != null) d.copy(initializer = q(d.initializer)) else d
                    })
                    else -> i
                },
                condition = stmt.condition?.let { q(it) },
                incrementor = stmt.incrementor?.let { q(it) },
                statement = qStmt(stmt.statement),
            )
            is ForInStatement -> stmt.copy(expression = q(stmt.expression), statement = qStmt(stmt.statement))
            is ForOfStatement -> stmt.copy(expression = q(stmt.expression), statement = qStmt(stmt.statement))
            is WhileStatement -> stmt.copy(expression = q(stmt.expression), statement = qStmt(stmt.statement))
            is DoStatement -> stmt.copy(expression = q(stmt.expression), statement = qStmt(stmt.statement))
            is SwitchStatement -> stmt.copy(
                expression = q(stmt.expression),
                caseBlock = stmt.caseBlock.map { clause ->
                    when (clause) {
                        is CaseClause -> clause.copy(expression = q(clause.expression), statements = clause.statements.map { qStmt(it) })
                        is DefaultClause -> clause.copy(statements = clause.statements.map { qStmt(it) })
                        else -> clause
                    }
                }
            )
            is ThrowStatement -> if (stmt.expression != null) stmt.copy(expression = q(stmt.expression)) else stmt
            is VariableStatement -> stmt.copy(declarationList = stmt.declarationList.copy(
                declarations = stmt.declarationList.declarations.map { d ->
                    if (d.initializer != null) d.copy(initializer = q(d.initializer)) else d
                }
            ))
            is TryStatement -> stmt.copy(
                tryBlock = qStmt(stmt.tryBlock) as Block,
                catchClause = stmt.catchClause?.let { it.copy(block = qStmt(it.block) as Block) },
                finallyBlock = stmt.finallyBlock?.let { qStmt(it) as Block },
            )
            is LabeledStatement -> stmt.copy(statement = qStmt(stmt.statement))
            is FunctionDeclaration -> {
                // Qualify parameter defaults, but for the body, exclude parameter names
                // from qualification since they shadow outer namespace exports.
                val paramNames = stmt.parameters.mapNotNull { (it.name as? Identifier)?.text }.toSet()
                val bodyNames = names - paramNames
                fun qBody(s: Statement) = if (bodyNames.isNotEmpty()) qualifyStatementRefs(nsName, bodyNames, s) else s
                stmt.copy(
                    parameters = stmt.parameters.map { p ->
                        if (p.initializer != null) p.copy(initializer = q(p.initializer)) else p
                    },
                    body = stmt.body?.let { b -> b.copy(statements = b.statements.map { qBody(it) }) },
                )
            }
            is ClassDeclaration -> {
                fun qualifyMemberBody(params: List<Parameter>, body: Block?): Block? {
                    if (body == null) return null
                    val pNames = params.mapNotNull { (it.name as? Identifier)?.text }.toSet()
                    val bNames = names - pNames
                    return if (bNames.isNotEmpty()) {
                        body.copy(statements = body.statements.map { qualifyStatementRefs(nsName, bNames, it) })
                    } else body
                }
                stmt.copy(
                    members = stmt.members.map { m ->
                        when (m) {
                            is MethodDeclaration -> m.copy(
                                parameters = m.parameters.map { p ->
                                    if (p.initializer != null) p.copy(initializer = q(p.initializer)) else p
                                },
                                body = qualifyMemberBody(m.parameters, m.body),
                            )
                            is Constructor -> m.copy(
                                parameters = m.parameters.map { p ->
                                    if (p.initializer != null) p.copy(initializer = q(p.initializer)) else p
                                },
                                body = qualifyMemberBody(m.parameters, m.body),
                            )
                            is GetAccessor -> m.copy(
                                body = qualifyMemberBody(emptyList(), m.body),
                            )
                            is SetAccessor -> m.copy(
                                body = qualifyMemberBody(m.parameters, m.body),
                            )
                            is PropertyDeclaration -> if (m.initializer != null) m.copy(initializer = q(m.initializer)) else m
                            else -> m
                        }
                    },
                )
            }
            else -> stmt
        }
    }

    /**
     * Replaces identifier references to exported namespace members with
     * `nsName.memberName` property access expressions.
     */
    private fun qualifyNamespaceRefs(
        nsName: String,
        exportedNames: Set<String>,
        expr: Expression,
    ): Expression {
        if (exportedNames.isEmpty()) return expr
        return when (expr) {
            is Identifier -> {
                if (expr.text in exportedNames) {
                    PropertyAccessExpression(
                        expression = Identifier(nsName, pos = -1, end = -1),
                        name = Identifier(expr.text, pos = -1, end = -1),
                        pos = -1, end = -1,
                    )
                } else expr
            }

            is BinaryExpression -> expr.copy(
                left = qualifyNamespaceRefs(nsName, exportedNames, expr.left),
                right = qualifyNamespaceRefs(nsName, exportedNames, expr.right),
            )

            is CallExpression -> expr.copy(
                expression = qualifyNamespaceRefs(nsName, exportedNames, expr.expression),
                arguments = expr.arguments.map { qualifyNamespaceRefs(nsName, exportedNames, it) },
            )

            is PropertyAccessExpression -> expr.copy(
                expression = qualifyNamespaceRefs(nsName, exportedNames, expr.expression),
            )

            is ElementAccessExpression -> expr.copy(
                expression = qualifyNamespaceRefs(nsName, exportedNames, expr.expression),
                argumentExpression = qualifyNamespaceRefs(nsName, exportedNames, expr.argumentExpression),
            )

            is NewExpression -> expr.copy(
                expression = qualifyNamespaceRefs(nsName, exportedNames, expr.expression),
                arguments = expr.arguments?.map { qualifyNamespaceRefs(nsName, exportedNames, it) },
            )

            is ConditionalExpression -> expr.copy(
                condition = qualifyNamespaceRefs(nsName, exportedNames, expr.condition),
                whenTrue = qualifyNamespaceRefs(nsName, exportedNames, expr.whenTrue),
                whenFalse = qualifyNamespaceRefs(nsName, exportedNames, expr.whenFalse),
            )

            is ParenthesizedExpression -> expr.copy(
                expression = qualifyNamespaceRefs(nsName, exportedNames, expr.expression),
            )

            is PrefixUnaryExpression -> expr.copy(
                operand = qualifyNamespaceRefs(nsName, exportedNames, expr.operand),
            )

            is PostfixUnaryExpression -> expr.copy(
                operand = qualifyNamespaceRefs(nsName, exportedNames, expr.operand),
            )

            is SpreadElement -> expr.copy(
                expression = qualifyNamespaceRefs(nsName, exportedNames, expr.expression),
            )

            is ArrayLiteralExpression -> expr.copy(
                elements = expr.elements.map { qualifyNamespaceRefs(nsName, exportedNames, it) },
            )

            else -> expr
        }
    }

    /**
     * Creates `N.memberName = memberName;`
     */
    private fun makeNamespaceExportAssignment(nsName: String, memberName: String): Statement {
        return ExpressionStatement(
            expression = BinaryExpression(
                left = PropertyAccessExpression(
                    expression = syntheticId(nsName),
                    name = Identifier(text = memberName, pos = -1, end = -1),
                    pos = -1, end = -1,
                ),
                operator = Equals,
                right = syntheticId(memberName),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    /**
     * Checks whether a qualified namespace path (e.g. `Outer.uninstantiated`) refers to a
     * type-only namespace by traversing the top-level source file namespace hierarchy.
     */
    private fun isQualifiedPathTypeOnly(ref: QualifiedName): Boolean {
        // Flatten the qualified name into a list of identifier strings, e.g. [Outer, uninstantiated]
        val parts = mutableListOf<String>()
        var node: Node = ref
        while (node is QualifiedName) {
            parts.add(0, node.right.text)
            node = node.left
        }
        if (node !is Identifier) return false
        parts.add(0, node.text)

        // Navigate the namespace hierarchy starting from top-level statements
        var stmts: List<Statement> = topLevelStatements
        for ((i, part) in parts.withIndex()) {
            // First: look for a ModuleDeclaration with this name
            val ns = stmts.filterIsInstance<ModuleDeclaration>()
                .firstOrNull { extractIdentifierName(it.name) == part }
            if (ns != null) {
                if (i == parts.size - 1) return isTypeOnlyNamespace(ns)
                val body = ns.body ?: return true
                stmts = when (body) {
                    is ModuleBlock -> body.statements
                    is ModuleDeclaration -> listOf(body)
                    else -> return false
                }
                continue
            }
            // Second: look for an ImportEqualsDeclaration alias with this name
            val importAlias = stmts.filterIsInstance<ImportEqualsDeclaration>()
                .firstOrNull { it.name.text == part }
            if (importAlias == null) {
                // Third: check for type-level declarations (interface, type alias) at last segment
                if (i == parts.size - 1) {
                    val hasInterface = stmts.any { stmt ->
                        (stmt is InterfaceDeclaration && stmt.name.text == part) ||
                        (stmt is TypeAliasDeclaration && stmt.name.text == part)
                    }
                    if (!hasInterface) return false
                    // Only type-only if there's no co-existing runtime declaration with the same name
                    val hasRuntime = stmts.any { stmt ->
                        when (stmt) {
                            is VariableStatement -> stmt.declarationList.declarations.any {
                                extractIdentifierName(it.name) == part
                            }
                            is FunctionDeclaration -> stmt.name?.text == part
                            is ClassDeclaration -> stmt.name?.text == part
                            is EnumDeclaration -> stmt.name.text == part
                            is ModuleDeclaration -> extractIdentifierName(stmt.name) == part
                            else -> false
                        }
                    }
                    return !hasRuntime
                }
                return false
            }
            val aliasRef = importAlias.moduleReference
            if (i == parts.size - 1) {
                // At the last part: check if the alias target is type-only
                return when (aliasRef) {
                    is Identifier -> {
                        val targetNs = stmts.filterIsInstance<ModuleDeclaration>()
                            .firstOrNull { extractIdentifierName(it.name) == aliasRef.text }
                        targetNs != null && isTypeOnlyNamespace(targetNs)
                    }
                    is QualifiedName -> isQualifiedPathTypeOnly(aliasRef)
                    else -> false
                }
            } else {
                // Middle of path: resolve alias to the namespace and continue
                when (aliasRef) {
                    is Identifier -> {
                        val targetNs = stmts.filterIsInstance<ModuleDeclaration>()
                            .firstOrNull { extractIdentifierName(it.name) == aliasRef.text }
                            ?: return false
                        val body = targetNs.body ?: return true
                        stmts = when (body) {
                            is ModuleBlock -> body.statements
                            is ModuleDeclaration -> listOf(body)
                            else -> return false
                        }
                    }
                    else -> return false
                }
            }
        }
        return false
    }

    /**
     * Checks whether a namespace contains only type-level declarations
     * (interfaces, type aliases, etc.) and thus produces no runtime output.
     */
    private fun isTypeOnlyNamespace(decl: ModuleDeclaration): Boolean {
        val body = decl.body ?: return true
        return when (body) {
            is ModuleBlock -> body.statements.all { isTypeOnlyStatement(it) }
            is ModuleDeclaration -> isTypeOnlyNamespace(body)
            else -> false
        }
    }

    private fun isTypeOnlyStatement(stmt: Statement): Boolean {
        return when (stmt) {
            is InterfaceDeclaration -> true
            is TypeAliasDeclaration -> true
            is ImportDeclaration -> stmt.importClause?.isTypeOnly == true
            is ExportDeclaration -> stmt.isTypeOnly
            is ImportEqualsDeclaration -> ModifierFlag.Export !in stmt.modifiers
            is ModuleDeclaration -> isTypeOnlyNamespace(stmt)
            // A const enum is inlined and erased — it's type-only at runtime
            // (unless preserveConstEnums or isolatedModules is set, in which case it produces a real object)
            is EnumDeclaration -> ModifierFlag.Const in stmt.modifiers && !options.preserveConstEnums && !options.isolatedModules
            else -> false
        }
    }

    // -----------------------------------------------------------------
    // Modifier utilities
    // -----------------------------------------------------------------

    /**
     * Strips TypeScript-only modifiers from a declaration modifier set,
     * keeping only those that have JavaScript runtime meaning (Export, Default, Static, Async, Const).
     */
    private fun stripTypeScriptModifiers(modifiers: Set<ModifierFlag>): Set<ModifierFlag> {
        return modifiers - setOf(
            ModifierFlag.Declare,
            ModifierFlag.Abstract,
            ModifierFlag.Public,
            ModifierFlag.Private,
            ModifierFlag.Protected,
            ModifierFlag.Readonly,
            ModifierFlag.Override,
            ModifierFlag.In,
            ModifierFlag.Out,
        )
    }

    /**
     * Strips type-only modifiers from class members.
     */
    private fun stripMemberModifiers(modifiers: Set<ModifierFlag>): Set<ModifierFlag> {
        return modifiers - typeOnlyMemberModifiers
    }

    // -----------------------------------------------------------------
    // Helper utilities
    // -----------------------------------------------------------------

    /**
     * Returns a NotEmittedStatement carrying "detached" leading comments from the erased
     * declaration — comments that are separated from the declaration by a blank line.
     * Comments directly adjacent to the declaration (no blank line) are dropped along with it.
     */
    private fun orphanedComments(statement: Statement): List<Statement> {
        val comments = statement.leadingComments ?: return emptyList()
        val stmtPos = statement.pos.coerceIn(0, sourceText.length)
        // Triple-slash directives (/// <reference>, ///<amd-dependency>, etc.) are always
        // emitted in JS output regardless of blank-line separation or runtime code context.
        // Regular comments are only preserved when: (1) no runtime code has appeared yet,
        // and (2) there is a blank line (>=2 newlines) between the comment and the erased decl.

        fun isRealTripleSlash(c: Comment) =
            c.text.startsWith("///") && c.text.drop(3).trimStart(' ').startsWith('<')

        // Find detached comments from the erased declaration.
        // Triple-slash directives are always preserved (before any runtime code).
        // Regular comments: only preserve the FIRST contiguous block, i.e., comments
        // before the first blank-line gap. Comments after the first gap are considered
        // attached to the declaration and are dropped with it.
        val included = mutableSetOf<Int>()

        // First: always include triple-slash directives (before runtime)
        for (index in comments.indices) {
            if (isRealTripleSlash(comments[index]) && !hasSeenAnyTopLevelStatement) {
                included.add(index)
            }
        }

        if (!hasSeenAnyTopLevelStatement) {
            // Find the first blank-line gap scanning forward through non-triple-slash comments.
            // Include all regular comments up to (and including) the one before the first gap.
            var firstGapFound = false
            for (index in comments.indices) {
                val comment = comments[index]
                if (isRealTripleSlash(comment)) continue // already handled
                if (firstGapFound) break // stop after first gap

                val nextStart = if (index + 1 < comments.size) {
                    comments[index + 1].pos.coerceIn(0, sourceText.length)
                } else {
                    stmtPos
                }
                val gap = sourceText.substring(
                    comment.end.coerceIn(0, sourceText.length),
                    nextStart,
                )
                if (gap.count { it == '\n' } >= 2) {
                    included.add(index)
                    firstGapFound = true
                } else {
                    // Part of a contiguous block — include only if block ends with a gap
                    included.add(index)
                }
            }

            // If no gap was found at all, none of the regular comments are detached
            if (!firstGapFound) {
                included.removeAll { !isRealTripleSlash(comments[it]) }
            }
        }

        val detached = comments.filterIndexed { index, _ -> index in included }
        return if (detached.isNotEmpty()) {
            listOf(NotEmittedStatement(leadingComments = detached, pos = statement.pos, end = statement.pos))
        } else {
            emptyList()
        }
    }

    private fun syntheticId(name: String): Identifier {
        return Identifier(text = name, pos = -1, end = -1)
    }

    private fun extractIdentifierName(expr: Node): String? {
        return when (expr) {
            is Identifier -> expr.text
            is StringLiteralNode -> expr.text
            else -> null
        }
    }

    /**
     * Flattens a dotted namespace name like `A.B.C` (stored as nested PropertyAccessExpressions)
     * into a list of name strings `["A", "B", "C"]`.
     */
    private fun flattenDottedNamespaceName(expr: Expression): List<String> {
        return when (expr) {
            is Identifier -> listOf(expr.text)
            is PropertyAccessExpression -> {
                val left = flattenDottedNamespaceName(expr.expression)
                if (left.isEmpty()) emptyList()
                else left + expr.name.text
            }
            else -> emptyList()
        }
    }

    /**
     * Returns the root identifier text of a module reference (Identifier or QualifiedName).
     * e.g. `foo.bar.baz` → "foo", `foo` → "foo", other nodes → null.
     */
    private fun namespaceAliasRoot(ref: Node?): String? = when (ref) {
        is Identifier -> ref.text
        is QualifiedName -> namespaceAliasRoot(ref.left)
        else -> null
    }

    /**
     * Collects all bound identifier names from a binding name (Identifier or binding pattern).
     * e.g. `[a, , b]` → ["a", "b"], `{ x, y: z }` → ["x", "z"], `name` → ["name"]
     */
    /**
     * Recursively strips `var` declarations from nested statements in System module execute body.
     * - `var x = init;` where `x` is in hoistedNames → `x = init;` (assignment)
     * - `var x;` where `x` is in hoistedNames → removed (already declared at top)
     * - Does NOT cross function/class body boundaries (those have their own var scope).
     * Returns 0 or more replacement statements.
     */
    private fun stripVarDeclsFromStatement(stmt: Statement, hoistedNames: Set<String>): List<Statement> {
        return when (stmt) {
            is VariableStatement -> {
                if (stmt.declarationList.flags != VarKeyword) return listOf(stmt)
                // For each var declaration: if hoisted, convert to assignment or remove
                val assignments = mutableListOf<Statement>()
                for (d in stmt.declarationList.declarations) {
                    val name = extractIdentifierName(d.name)
                    if (name != null && name in hoistedNames) {
                        if (d.initializer != null) {
                            assignments.add(ExpressionStatement(
                                expression = BinaryExpression(
                                    left = syntheticId(name), operator = Equals,
                                    right = d.initializer, pos = -1, end = -1,
                                ),
                                leadingComments = if (assignments.isEmpty()) stmt.leadingComments else null,
                                pos = -1, end = -1,
                            ))
                        }
                        // else: no initializer — already hoisted, emit nothing
                    } else {
                        // Not in hoisted set (shouldn't happen but keep it)
                        assignments.add(stmt)
                    }
                }
                assignments
            }
            is Block -> listOf(stmt.copy(statements = stmt.statements.flatMap { stripVarDeclsFromStatement(it, hoistedNames) }))
            is IfStatement -> listOf(stmt.copy(
                thenStatement = stripVarDeclsFromStatement(stmt.thenStatement, hoistedNames).singleOrNull() ?: stmt.thenStatement,
                elseStatement = stmt.elseStatement?.let { stripVarDeclsFromStatement(it, hoistedNames).singleOrNull() ?: it },
            ))
            is ForStatement -> listOf(stmt.copy(
                statement = stripVarDeclsFromStatement(stmt.statement, hoistedNames).singleOrNull() ?: stmt.statement,
            ))
            is ForOfStatement -> listOf(stmt.copy(
                statement = stripVarDeclsFromStatement(stmt.statement, hoistedNames).singleOrNull() ?: stmt.statement,
            ))
            is ForInStatement -> listOf(stmt.copy(
                statement = stripVarDeclsFromStatement(stmt.statement, hoistedNames).singleOrNull() ?: stmt.statement,
            ))
            is WhileStatement -> listOf(stmt.copy(
                statement = stripVarDeclsFromStatement(stmt.statement, hoistedNames).singleOrNull() ?: stmt.statement,
            ))
            is DoStatement -> listOf(stmt.copy(
                statement = stripVarDeclsFromStatement(stmt.statement, hoistedNames).singleOrNull() ?: stmt.statement,
            ))
            is LabeledStatement -> listOf(stmt.copy(
                statement = stripVarDeclsFromStatement(stmt.statement, hoistedNames).singleOrNull() ?: stmt.statement,
            ))
            is TryStatement -> listOf(stmt.copy(
                tryBlock = stmt.tryBlock.copy(statements = stmt.tryBlock.statements.flatMap { stripVarDeclsFromStatement(it, hoistedNames) }),
                catchClause = stmt.catchClause?.let { it.copy(block = it.block.copy(statements = it.block.statements.flatMap { s -> stripVarDeclsFromStatement(s, hoistedNames) })) },
                finallyBlock = stmt.finallyBlock?.copy(statements = stmt.finallyBlock.statements.flatMap { stripVarDeclsFromStatement(it, hoistedNames) }),
            ))
            is SwitchStatement -> listOf(stmt.copy(
                caseBlock = stmt.caseBlock.map { clause ->
                    when (clause) {
                        is CaseClause -> clause.copy(statements = clause.statements.flatMap { stripVarDeclsFromStatement(it, hoistedNames) })
                        is DefaultClause -> clause.copy(statements = clause.statements.flatMap { stripVarDeclsFromStatement(it, hoistedNames) })
                        else -> clause
                    }
                }
            ))
            // FunctionDeclaration, FunctionExpression, ArrowFunction, ClassDeclaration: don't recurse
            else -> listOf(stmt)
        }
    }

    /**
     * Recursively collects all `var` declaration names from a list of statements,
     * NOT crossing function/class body boundaries.
     * Used for System module var hoisting.
     */
    private fun collectVarNamesFromStmts(stmts: List<Statement>, result: MutableList<String>, resultSet: MutableSet<String>) {
        for (stmt in stmts) collectVarNamesFromStmt(stmt, result, resultSet)
    }

    private fun collectVarNamesFromStmt(stmt: Statement, result: MutableList<String>, resultSet: MutableSet<String>) {
        when (stmt) {
            is VariableStatement -> {
                if (stmt.declarationList.flags == VarKeyword && ModifierFlag.Declare !in stmt.modifiers) {
                    for (d in stmt.declarationList.declarations) {
                        for (n in collectBoundNames(d.name)) if (resultSet.add(n)) result.add(n)
                    }
                }
            }
            is Block -> collectVarNamesFromStmts(stmt.statements, result, resultSet)
            is IfStatement -> {
                collectVarNamesFromStmt(stmt.thenStatement, result, resultSet)
                stmt.elseStatement?.let { collectVarNamesFromStmt(it, result, resultSet) }
            }
            is ForStatement -> {
                val init = stmt.initializer
                if (init is VariableDeclarationList && init.flags == VarKeyword) {
                    for (d in init.declarations) for (n in collectBoundNames(d.name)) if (resultSet.add(n)) result.add(n)
                }
                stmt.statement.let { collectVarNamesFromStmt(it, result, resultSet) }
            }
            is ForOfStatement -> {
                val init = stmt.initializer
                if (init is VariableDeclarationList && init.flags == VarKeyword) {
                    for (d in init.declarations) for (n in collectBoundNames(d.name)) if (resultSet.add(n)) result.add(n)
                }
                collectVarNamesFromStmt(stmt.statement, result, resultSet)
            }
            is ForInStatement -> {
                val init = stmt.initializer
                if (init is VariableDeclarationList && init.flags == VarKeyword) {
                    for (d in init.declarations) for (n in collectBoundNames(d.name)) if (resultSet.add(n)) result.add(n)
                }
                collectVarNamesFromStmt(stmt.statement, result, resultSet)
            }
            is WhileStatement -> collectVarNamesFromStmt(stmt.statement, result, resultSet)
            is DoStatement -> collectVarNamesFromStmt(stmt.statement, result, resultSet)
            is LabeledStatement -> collectVarNamesFromStmt(stmt.statement, result, resultSet)
            is TryStatement -> {
                collectVarNamesFromStmts(stmt.tryBlock.statements, result, resultSet)
                stmt.catchClause?.let { collectVarNamesFromStmts(it.block.statements, result, resultSet) }
                stmt.finallyBlock?.let { collectVarNamesFromStmts(it.statements, result, resultSet) }
            }
            is SwitchStatement -> stmt.caseBlock.forEach { clause ->
                val stmts = when (clause) {
                    is CaseClause -> clause.statements
                    is DefaultClause -> clause.statements
                    else -> emptyList()
                }
                collectVarNamesFromStmts(stmts, result, resultSet)
            }
            // FunctionDeclaration, FunctionExpression, ArrowFunction, ClassDeclaration:
            // Do NOT recurse — vars inside are function-scoped, not module-scoped.
            else -> {}
        }
    }

    private fun collectBoundNames(name: Node): List<String> {
        return when (name) {
            is Identifier -> listOf(name.text)
            is ArrayBindingPattern -> name.elements.flatMap { elem ->
                if (elem is BindingElement) collectBoundNames(elem.name) else emptyList()
            }
            is ObjectBindingPattern -> name.elements.flatMap { elem ->
                collectBoundNames(elem.name)
            }
            else -> emptyList()
        }
    }

    /**
     * Tries to expand a [VariableDeclarator] with an [ObjectBindingPattern] into a list of
     * (localName, valueExpr) pairs for direct module export. Each pair maps the local binding
     * name to a [PropertyAccessExpression] of the initializer.
     *
     * Returns `null` if the binding cannot be simply expanded: nested patterns, default values,
     * rest elements, computed property names, or no initializer.
     *
     * Example: `{ toString }` from `1` → `[("toString", 1..toString)]`
     * Example: `{ foo: bar }` from `obj` → `[("bar", obj.foo)]`
     */
    private fun tryExpandObjectBinding(decl: VariableDeclaration): List<Pair<String, Expression>>? {
        val pattern = decl.name as? ObjectBindingPattern ?: return null
        val initializer = decl.initializer ?: return null
        for (elem in pattern.elements) {
            if (elem.dotDotDotToken) return null
            if (elem.initializer != null) return null
            if (elem.name !is Identifier) return null
        }
        return pattern.elements.map { elem ->
            val localName = (elem.name as Identifier).text
            val propName = when (val pn = elem.propertyName) {
                is Identifier -> pn.text
                is StringLiteralNode -> pn.text
                null -> localName
                else -> return null  // computed property name — can't expand
            }
            val valueExpr = PropertyAccessExpression(
                expression = initializer,
                name = Identifier(text = propName, pos = -1, end = -1),
                pos = -1, end = -1,
            )
            localName to valueExpr
        }
    }

    /**
     * Converts a [QualifiedName] AST node to a [PropertyAccessExpression] chain.
     * `A.B.C` (QualifiedName) → `A.B.C` (PropertyAccessExpression).
     */
    private fun qualifiedNameToPropertyAccess(qn: QualifiedName): Expression {
        val left: Expression = when (val l = qn.left) {
            is QualifiedName -> qualifiedNameToPropertyAccess(l)
            is Identifier -> l
            else -> syntheticId("unknown")
        }
        return PropertyAccessExpression(
            expression = left,
            name = qn.right,
            pos = qn.pos, end = qn.end,
        )
    }

    /**
     * Recursively collects all identifier names used in value positions from a list of statements.
     * Used for import elision: if an import name never appears in value positions after type erasure,
     * its `const X = require(...)` can be dropped.
     */
    private fun collectValueReferences(stmts: List<Statement>): Set<String> {
        val result = mutableSetOf<String>()
        stmts.forEach { collectRefsFromNode(it, result) }
        return result
    }

    private fun collectRefsFromNode(node: Any?, refs: MutableSet<String>) {
        when (node) {
            null -> {}
            // Statements
            is Block -> node.statements.forEach { collectRefsFromNode(it, refs) }
            is VariableStatement -> node.declarationList.declarations.forEach { decl ->
                // Recurse into initializer (value position), but NOT the name (it's a binding)
                collectRefsFromNode(decl.initializer, refs)
            }
            is ExpressionStatement -> collectRefsFromNode(node.expression, refs)
            is IfStatement -> {
                collectRefsFromNode(node.expression, refs)
                collectRefsFromNode(node.thenStatement, refs)
                collectRefsFromNode(node.elseStatement, refs)
            }
            is DoStatement -> { collectRefsFromNode(node.statement, refs); collectRefsFromNode(node.expression, refs) }
            is WhileStatement -> { collectRefsFromNode(node.expression, refs); collectRefsFromNode(node.statement, refs) }
            is ForStatement -> {
                collectRefsFromNode(node.initializer, refs)
                collectRefsFromNode(node.condition, refs)
                collectRefsFromNode(node.incrementor, refs)
                collectRefsFromNode(node.statement, refs)
            }
            is ForInStatement -> { collectRefsFromNode(node.expression, refs); collectRefsFromNode(node.statement, refs) }
            is ForOfStatement -> { collectRefsFromNode(node.expression, refs); collectRefsFromNode(node.statement, refs) }
            is ReturnStatement -> collectRefsFromNode(node.expression, refs)
            is ThrowStatement -> { if (node.expression != null) collectRefsFromNode(node.expression, refs) }
            is LabeledStatement -> collectRefsFromNode(node.statement, refs)
            is WithStatement -> { collectRefsFromNode(node.expression, refs); collectRefsFromNode(node.statement, refs) }
            is TryStatement -> {
                collectRefsFromNode(node.tryBlock, refs)
                collectRefsFromNode(node.catchClause?.block, refs)
                collectRefsFromNode(node.finallyBlock, refs)
            }
            is SwitchStatement -> {
                collectRefsFromNode(node.expression, refs)
                node.caseBlock.forEach { clause ->
                    when (clause) {
                        is CaseClause -> { collectRefsFromNode(clause.expression, refs); clause.statements.forEach { collectRefsFromNode(it, refs) } }
                        is DefaultClause -> clause.statements.forEach { collectRefsFromNode(it, refs) }
                        else -> {}
                    }
                }
            }
            is ExportDeclaration -> {
                // Re-export specifiers reference local names: `export { A as B }` means A is referenced
                if (node.moduleSpecifier == null) {
                    val clause = node.exportClause
                    if (clause is NamedExports) {
                        clause.elements.forEach { spec ->
                            // The local name is propertyName if aliased (e.g. `A as B`), else name
                            val localName = (spec.propertyName ?: spec.name).text
                            refs.add(localName)
                        }
                    }
                }
            }
            is ExportAssignment -> collectRefsFromNode(node.expression, refs)
            is FunctionDeclaration -> {
                // Traverse parameters for computed names in binding patterns and default values
                node.parameters.forEach { param ->
                    collectRefsFromNode(param.initializer, refs)
                    if (param.name is ComputedPropertyName) collectRefsFromNode(param.name, refs)
                    if (param.name is ObjectBindingPattern) collectBindingPatternRefs(param.name as ObjectBindingPattern, refs)
                    if (param.name is ArrayBindingPattern) collectArrayBindingPatternRefs(param.name as ArrayBindingPattern, refs)
                }
                node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
            }
            is ClassDeclaration -> {
                // Only `extends` clauses are value positions; `implements` clauses are type-only
                node.heritageClauses?.forEach { hc ->
                    if (hc.token == ExtendsKeyword) {
                        hc.types.forEach { collectRefsFromNode(it.expression, refs) }
                    }
                }
                node.members.forEach { collectRefsFromNode(it, refs) }
            }
            // Expressions
            is Identifier -> refs.add(node.text)
            is BinaryExpression -> { collectRefsFromNode(node.left, refs); collectRefsFromNode(node.right, refs) }
            is CallExpression -> {
                collectRefsFromNode(node.expression, refs)
                node.arguments.forEach { collectRefsFromNode(it, refs) }
            }
            is NewExpression -> {
                collectRefsFromNode(node.expression, refs)
                node.arguments?.forEach { collectRefsFromNode(it, refs) }
            }
            is PropertyAccessExpression -> collectRefsFromNode(node.expression, refs) // NOT node.name
            is ElementAccessExpression -> { collectRefsFromNode(node.expression, refs); collectRefsFromNode(node.argumentExpression, refs) }
            is ArrayLiteralExpression -> node.elements.forEach { collectRefsFromNode(it, refs) }
            is ObjectLiteralExpression -> node.properties.forEach { prop ->
                when (prop) {
                    is PropertyAssignment -> {
                        // Traverse computed property names (e.g. [keys.n]: value)
                        if (prop.name is ComputedPropertyName) collectRefsFromNode(prop.name, refs)
                        collectRefsFromNode(prop.initializer, refs)
                    }
                    is ShorthandPropertyAssignment -> { refs.add(prop.name.text); collectRefsFromNode(prop.objectAssignmentInitializer, refs) }
                    is SpreadAssignment -> collectRefsFromNode(prop.expression, refs)
                    is MethodDeclaration -> {
                        if (prop.name is ComputedPropertyName) collectRefsFromNode(prop.name, refs)
                        prop.body?.statements?.forEach { collectRefsFromNode(it, refs) }
                    }
                    is GetAccessor -> {
                        if (prop.name is ComputedPropertyName) collectRefsFromNode(prop.name, refs)
                        prop.body?.statements?.forEach { collectRefsFromNode(it, refs) }
                    }
                    is SetAccessor -> {
                        if (prop.name is ComputedPropertyName) collectRefsFromNode(prop.name, refs)
                        prop.body?.statements?.forEach { collectRefsFromNode(it, refs) }
                    }
                    else -> {}
                }
            }
            is ArrowFunction -> collectRefsFromNode(node.body, refs)
            is FunctionExpression -> node.body.statements.forEach { collectRefsFromNode(it, refs) }
            is ConditionalExpression -> { collectRefsFromNode(node.condition, refs); collectRefsFromNode(node.whenTrue, refs); collectRefsFromNode(node.whenFalse, refs) }
            is ParenthesizedExpression -> collectRefsFromNode(node.expression, refs)
            is PrefixUnaryExpression -> collectRefsFromNode(node.operand, refs)
            is PostfixUnaryExpression -> collectRefsFromNode(node.operand, refs)
            is DeleteExpression -> collectRefsFromNode(node.expression, refs)
            is TypeOfExpression -> collectRefsFromNode(node.expression, refs)
            is VoidExpression -> collectRefsFromNode(node.expression, refs)
            is AwaitExpression -> collectRefsFromNode(node.expression, refs)
            is YieldExpression -> collectRefsFromNode(node.expression, refs)
            is SpreadElement -> collectRefsFromNode(node.expression, refs)
            is TaggedTemplateExpression -> { collectRefsFromNode(node.tag, refs); collectRefsFromNode(node.template, refs) }
            is TemplateExpression -> node.templateSpans.forEach { collectRefsFromNode(it.expression, refs) }
            is ClassExpression -> {
                node.heritageClauses?.forEach { hc -> hc.types.forEach { collectRefsFromNode(it.expression, refs) } }
                node.members.forEach { collectRefsFromNode(it, refs) }
            }
            is ComputedPropertyName -> collectRefsFromNode(node.expression, refs)
            // Class elements — also collect from computed property names
            is PropertyDeclaration -> {
                if (node.name is ComputedPropertyName) collectRefsFromNode(node.name, refs)
                collectRefsFromNode(node.initializer, refs)
            }
            is MethodDeclaration -> {
                if (node.name is ComputedPropertyName) collectRefsFromNode(node.name, refs)
                node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
            }
            is Constructor -> node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
            is GetAccessor -> {
                if (node.name is ComputedPropertyName) collectRefsFromNode(node.name, refs)
                node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
            }
            is SetAccessor -> {
                if (node.name is ComputedPropertyName) collectRefsFromNode(node.name, refs)
                node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
            }
            is ClassStaticBlockDeclaration -> node.body.statements.forEach { collectRefsFromNode(it, refs) }
            // Variable declaration list (for `for` initializer)
            is VariableDeclarationList -> node.declarations.forEach { collectRefsFromNode(it.initializer, refs) }
            // JSX nodes — traverse tag names and expression containers for identifier references
            is JsxSelfClosingElement -> {
                collectRefsFromNode(node.tagName, refs)
                node.attributes.forEach { collectRefsFromJsxAttr(it, refs) }
            }
            is JsxElement -> {
                collectRefsFromNode(node.openingElement.tagName, refs)
                node.openingElement.attributes.forEach { collectRefsFromJsxAttr(it, refs) }
                node.children.forEach { collectRefsFromNode(it, refs) }
            }
            is JsxFragment -> node.children.forEach { collectRefsFromNode(it, refs) }
            is JsxExpressionContainer -> node.expression?.let { collectRefsFromNode(it, refs) }
            is JsxSpreadAttribute -> collectRefsFromNode(node.expression, refs)
            else -> {} // literals, type nodes, etc. — no identifiers to collect
        }
    }

    private fun collectBindingPatternRefs(pattern: ObjectBindingPattern, refs: MutableSet<String>) {
        for (element in pattern.elements) {
            if (element is BindingElement) {
                if (element.propertyName is ComputedPropertyName)
                    collectRefsFromNode(element.propertyName, refs)
                collectRefsFromNode(element.initializer, refs)
                when (val name = element.name) {
                    is ObjectBindingPattern -> collectBindingPatternRefs(name, refs)
                    is ArrayBindingPattern -> collectArrayBindingPatternRefs(name, refs)
                    else -> {}
                }
            }
        }
    }

    private fun collectArrayBindingPatternRefs(pattern: ArrayBindingPattern, refs: MutableSet<String>) {
        for (element in pattern.elements) {
            if (element is BindingElement) {
                collectRefsFromNode(element.initializer, refs)
                when (val name = element.name) {
                    is ObjectBindingPattern -> collectBindingPatternRefs(name, refs)
                    is ArrayBindingPattern -> collectArrayBindingPatternRefs(name, refs)
                    else -> {}
                }
            }
        }
    }

    private fun collectRefsFromJsxAttr(attr: Node, refs: MutableSet<String>) {
        when (attr) {
            is JsxAttribute -> collectRefsFromNode(attr.value, refs)
            is JsxSpreadAttribute -> collectRefsFromNode(attr.expression, refs)
            else -> {}
        }
    }

    // -----------------------------------------------------------------
    // Import identifier rewriting
    // -----------------------------------------------------------------

    /**
     * Rewrites identifier references in a statement using [renameMap].
     * Only value-position identifiers are rewritten; declaration names, property keys,
     * and member names are left unchanged.
     * @param wrapCallsWithZero If true (default), bare-identifier calls rewritten to property-access
     *   calls are wrapped as (0, expr)() to avoid `this` binding — needed for CJS but not System.
     */
    private fun rewriteIdInStatement(stmt: Statement, map: Map<String, Expression>, wrapCallsWithZero: Boolean = true): Statement = when (stmt) {
        is ExpressionStatement -> stmt.copy(expression = rewriteId(stmt.expression, map, wrapCallsWithZero))
        is VariableStatement -> stmt.copy(
            declarationList = stmt.declarationList.copy(
                declarations = stmt.declarationList.declarations.map { decl ->
                    decl.copy(initializer = decl.initializer?.let { rewriteId(it, map, wrapCallsWithZero) })
                }
            )
        )
        is ReturnStatement -> stmt.copy(expression = stmt.expression?.let { rewriteId(it, map, wrapCallsWithZero) })
        is ThrowStatement -> stmt.copy(expression = stmt.expression?.let { rewriteId(it, map, wrapCallsWithZero) })
        is IfStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map, wrapCallsWithZero),
            thenStatement = rewriteIdInStatement(stmt.thenStatement, map, wrapCallsWithZero),
            elseStatement = stmt.elseStatement?.let { rewriteIdInStatement(it, map, wrapCallsWithZero) },
        )
        is Block -> stmt.copy(statements = stmt.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) })
        is DoStatement -> stmt.copy(
            statement = rewriteIdInStatement(stmt.statement, map, wrapCallsWithZero),
            expression = rewriteId(stmt.expression, map, wrapCallsWithZero),
        )
        is WhileStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map, wrapCallsWithZero),
            statement = rewriteIdInStatement(stmt.statement, map, wrapCallsWithZero),
        )
        is ForStatement -> stmt.copy(
            initializer = stmt.initializer?.let { init ->
                when (init) {
                    is VariableDeclarationList -> init.copy(
                        declarations = init.declarations.map { d -> d.copy(initializer = d.initializer?.let { rewriteId(it, map, wrapCallsWithZero) }) }
                    )
                    is Expression -> rewriteId(init, map, wrapCallsWithZero)
                    else -> init
                }
            },
            condition = stmt.condition?.let { rewriteId(it, map, wrapCallsWithZero) },
            incrementor = stmt.incrementor?.let { rewriteId(it, map, wrapCallsWithZero) },
            statement = rewriteIdInStatement(stmt.statement, map, wrapCallsWithZero),
        )
        is ForInStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map, wrapCallsWithZero),
            statement = rewriteIdInStatement(stmt.statement, map, wrapCallsWithZero),
        )
        is ForOfStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map, wrapCallsWithZero),
            statement = rewriteIdInStatement(stmt.statement, map, wrapCallsWithZero),
        )
        is SwitchStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map, wrapCallsWithZero),
            caseBlock = stmt.caseBlock.map { clause ->
                when (clause) {
                    is CaseClause -> clause.copy(
                        expression = rewriteId(clause.expression, map, wrapCallsWithZero),
                        statements = clause.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) },
                    )
                    is DefaultClause -> clause.copy(
                        statements = clause.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) },
                    )
                    else -> clause
                }
            }
        )
        is LabeledStatement -> stmt.copy(statement = rewriteIdInStatement(stmt.statement, map, wrapCallsWithZero))
        is WithStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map, wrapCallsWithZero),
            statement = rewriteIdInStatement(stmt.statement, map, wrapCallsWithZero),
        )
        is TryStatement -> stmt.copy(
            tryBlock = stmt.tryBlock.copy(statements = stmt.tryBlock.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) }),
            catchClause = stmt.catchClause?.let { cc ->
                cc.copy(block = cc.block.copy(statements = cc.block.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) }))
            },
            finallyBlock = stmt.finallyBlock?.let { fb ->
                fb.copy(statements = fb.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) })
            },
        )
        is ClassDeclaration -> stmt.copy(
            heritageClauses = stmt.heritageClauses?.map { hc ->
                hc.copy(types = hc.types.map { t -> t.copy(expression = rewriteId(t.expression, map, wrapCallsWithZero)) })
            },
            members = stmt.members.map { rewriteIdInClassElement(it, map, wrapCallsWithZero) },
        )
        is FunctionDeclaration -> stmt.copy(
            parameters = stmt.parameters.map { p -> rewriteIdInParameter(p, map, wrapCallsWithZero) },
            body = stmt.body?.copy(statements = stmt.body.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) }),
        )
        else -> stmt
    }

    private fun rewriteIdInClassElement(member: ClassElement, map: Map<String, Expression>, wrapCallsWithZero: Boolean = true): ClassElement = when (member) {
        is Constructor -> member.copy(
            parameters = member.parameters.map { p -> rewriteIdInParameter(p, map, wrapCallsWithZero) },
            body = member.body?.copy(statements = member.body.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) }),
        )
        is MethodDeclaration -> member.copy(
            parameters = member.parameters.map { p -> rewriteIdInParameter(p, map, wrapCallsWithZero) },
            body = member.body?.copy(statements = member.body.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) }),
        )
        is PropertyDeclaration -> member.copy(initializer = member.initializer?.let { rewriteId(it, map, wrapCallsWithZero) })
        is GetAccessor -> member.copy(
            body = member.body?.copy(statements = member.body.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) }),
        )
        is SetAccessor -> member.copy(
            body = member.body?.copy(statements = member.body.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) }),
        )
        is ClassStaticBlockDeclaration -> member.copy(
            body = member.body.copy(statements = member.body.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) }),
        )
        else -> member
    }

    private fun rewriteIdInParameter(p: Parameter, map: Map<String, Expression>, wrapCallsWithZero: Boolean): Parameter =
        p.copy(
            name = rewriteIdInBindingName(p.name, map, wrapCallsWithZero),
            initializer = p.initializer?.let { rewriteId(it, map, wrapCallsWithZero) },
        )

    private fun rewriteIdInBindingName(name: Expression, map: Map<String, Expression>, wrapCallsWithZero: Boolean): Expression = when (name) {
        is ObjectBindingPattern -> name.copy(
            elements = name.elements.map { elem ->
                when (elem) {
                    is BindingElement -> elem.copy(
                        propertyName = when (val pn = elem.propertyName) {
                            is ComputedPropertyName -> pn.copy(expression = rewriteId(pn.expression, map, wrapCallsWithZero))
                            else -> pn
                        },
                        name = rewriteIdInBindingName(elem.name, map, wrapCallsWithZero),
                        initializer = elem.initializer?.let { rewriteId(it, map, wrapCallsWithZero) },
                    )
                    else -> elem
                }
            }
        )
        is ArrayBindingPattern -> name.copy(
            elements = name.elements.map { elem ->
                when (elem) {
                    is BindingElement -> elem.copy(
                        name = rewriteIdInBindingName(elem.name, map, wrapCallsWithZero),
                        initializer = elem.initializer?.let { rewriteId(it, map, wrapCallsWithZero) },
                    )
                    else -> elem
                }
            }
        )
        else -> name
    }

    /**
     * Rewrites identifier references in an expression using [map].
     * Only rewrites identifiers in value position; property names, computed keys, etc. are left alone.
     * @param wrapCallsWithZero If true (default), bare-identifier calls rewritten to property-access
     *   calls are wrapped as (0, expr)() to avoid `this` binding — needed for CJS but not System.
     */
    private fun rewriteId(expr: Expression, map: Map<String, Expression>, wrapCallsWithZero: Boolean = true): Expression = when (expr) {
        is Identifier -> map[expr.text] ?: expr
        is PropertyAccessExpression -> expr.copy(expression = rewriteId(expr.expression, map, wrapCallsWithZero))
        is ElementAccessExpression -> expr.copy(
            expression = rewriteId(expr.expression, map, wrapCallsWithZero),
            argumentExpression = rewriteId(expr.argumentExpression, map, wrapCallsWithZero),
        )
        is CallExpression -> {
            val origCallee = expr.expression
            val newCallee = rewriteId(origCallee, map, wrapCallsWithZero)
            // When a bare identifier call is rewritten to a property access (named import),
            // wrap as (0, expr)() to avoid implicit `this` binding — matches TypeScript CJS emit.
            // For System format, this wrapping is not needed (wrapCallsWithZero = false).
            val wrappedCallee = if (wrapCallsWithZero && origCallee is Identifier && origCallee.text in map && newCallee is PropertyAccessExpression) {
                ParenthesizedExpression(
                    expression = BinaryExpression(
                        left = NumericLiteralNode("0", pos = -1, end = -1),
                        operator = Comma,
                        right = newCallee,
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                )
            } else newCallee
            expr.copy(
                expression = wrappedCallee,
                arguments = expr.arguments.map { rewriteId(it, map, wrapCallsWithZero) },
            )
        }
        is NewExpression -> expr.copy(
            expression = rewriteId(expr.expression, map, wrapCallsWithZero),
            arguments = expr.arguments?.map { rewriteId(it, map, wrapCallsWithZero) },
        )
        is BinaryExpression -> {
            // Iteratively walk right spine to avoid StackOverflow on deep chains
            // (e.g., 5000-export comma chains in CommonJS transform — manyConstExports)
            val spine = ArrayList<BinaryExpression>()
            var cur: Expression = expr
            while (cur is BinaryExpression) { spine.add(cur); cur = cur.right }
            var result = rewriteId(cur, map, wrapCallsWithZero)
            for (i in spine.lastIndex downTo 0) {
                val n = spine[i]
                result = n.copy(left = rewriteId(n.left, map, wrapCallsWithZero), right = result)
            }
            result
        }
        is ConditionalExpression -> expr.copy(
            condition = rewriteId(expr.condition, map, wrapCallsWithZero),
            whenTrue = rewriteId(expr.whenTrue, map, wrapCallsWithZero),
            whenFalse = rewriteId(expr.whenFalse, map, wrapCallsWithZero),
        )
        is ParenthesizedExpression -> expr.copy(expression = rewriteId(expr.expression, map, wrapCallsWithZero))
        is PrefixUnaryExpression -> expr.copy(operand = rewriteId(expr.operand, map, wrapCallsWithZero))
        is PostfixUnaryExpression -> expr.copy(operand = rewriteId(expr.operand, map, wrapCallsWithZero))
        is DeleteExpression -> expr.copy(expression = rewriteId(expr.expression, map, wrapCallsWithZero))
        is TypeOfExpression -> expr.copy(expression = rewriteId(expr.expression, map, wrapCallsWithZero))
        is VoidExpression -> expr.copy(expression = rewriteId(expr.expression, map, wrapCallsWithZero))
        is AwaitExpression -> expr.copy(expression = rewriteId(expr.expression, map, wrapCallsWithZero))
        is YieldExpression -> expr.copy(expression = expr.expression?.let { rewriteId(it, map, wrapCallsWithZero) })
        is SpreadElement -> expr.copy(expression = rewriteId(expr.expression, map, wrapCallsWithZero))
        is ArrayLiteralExpression -> expr.copy(elements = expr.elements.map { rewriteId(it, map, wrapCallsWithZero) })
        is ObjectLiteralExpression -> expr.copy(
            properties = expr.properties.map { prop ->
                when (prop) {
                    is PropertyAssignment -> prop.copy(initializer = rewriteId(prop.initializer, map, wrapCallsWithZero))
                    is ShorthandPropertyAssignment -> {
                        val replacement = map[prop.name.text]
                        if (replacement != null) {
                            // { x } where x → y_1.x: expand to { x: y_1.x }
                            PropertyAssignment(
                                name = prop.name,
                                initializer = replacement,
                                pos = prop.pos, end = prop.end,
                            )
                        } else prop
                    }
                    is SpreadAssignment -> prop.copy(expression = rewriteId(prop.expression, map, wrapCallsWithZero))
                    else -> prop
                }
            }
        )
        is TaggedTemplateExpression -> expr.copy(tag = rewriteId(expr.tag, map, wrapCallsWithZero))
        is TemplateExpression -> expr.copy(
            templateSpans = expr.templateSpans.map { span ->
                span.copy(expression = rewriteId(span.expression, map, wrapCallsWithZero))
            }
        )
        is ArrowFunction -> expr.copy(
            parameters = expr.parameters.map { p -> rewriteIdInParameter(p, map, wrapCallsWithZero) },
            body = when (val b = expr.body) {
                is Block -> b.copy(statements = b.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) })
                is Expression -> rewriteId(b, map, wrapCallsWithZero)
                else -> b
            },
        )
        is FunctionExpression -> {
            // Don't rewrite names that are locally declared as functions or variables in this scope —
            // they shadow any outer binding with the same name. This prevents namespace-IIFE-local
            // function declarations from being incorrectly rewritten to exports.X.
            val localNames = expr.body.statements.flatMapTo(mutableSetOf()) { stmt ->
                when (stmt) {
                    is FunctionDeclaration -> listOfNotNull(stmt.name?.text)
                    is VariableStatement -> stmt.declarationList.declarations.flatMap { collectBoundNames(it.name) }
                    else -> emptyList()
                }
            }
            val localMap = if (localNames.isEmpty()) map else map - localNames
            expr.copy(body = expr.body.copy(statements = expr.body.statements.map { rewriteIdInStatement(it, localMap, wrapCallsWithZero) }))
        }
        is NonNullExpression -> expr.copy(expression = rewriteId(expr.expression, map, wrapCallsWithZero))
        is ClassExpression -> expr.copy(
            heritageClauses = expr.heritageClauses?.map { hc ->
                hc.copy(types = hc.types.map { t -> t.copy(expression = rewriteId(t.expression, map, wrapCallsWithZero)) })
            },
            members = expr.members.map { rewriteIdInClassElement(it, map, wrapCallsWithZero) },
        )
        // JSX nodes — rewrite identifiers in tag names and expression containers
        is JsxSelfClosingElement -> expr.copy(
            tagName = rewriteId(expr.tagName, map, wrapCallsWithZero),
            attributes = expr.attributes.map { rewriteIdInJsxAttr(it, map, wrapCallsWithZero) },
        )
        is JsxElement -> expr.copy(
            openingElement = expr.openingElement.copy(
                tagName = rewriteId(expr.openingElement.tagName, map, wrapCallsWithZero),
                attributes = expr.openingElement.attributes.map { rewriteIdInJsxAttr(it, map, wrapCallsWithZero) },
            ),
            children = expr.children.map { rewriteIdInJsxChild(it, map, wrapCallsWithZero) },
        )
        is JsxFragment -> expr.copy(
            children = expr.children.map { rewriteIdInJsxChild(it, map, wrapCallsWithZero) },
        )
        else -> expr
    }

    private fun rewriteIdInJsxAttr(attr: Node, map: Map<String, Expression>, wrapCallsWithZero: Boolean): Node =
        when (attr) {
            is JsxAttribute -> when (val v = attr.value) {
                is JsxExpressionContainer -> attr.copy(value = v.copy(expression = v.expression?.let { rewriteId(it, map, wrapCallsWithZero) }))
                else -> attr
            }
            is JsxSpreadAttribute -> attr.copy(expression = rewriteId(attr.expression, map, wrapCallsWithZero))
            else -> attr
        }

    private fun rewriteIdInJsxChild(child: Node, map: Map<String, Expression>, wrapCallsWithZero: Boolean): Node =
        when (child) {
            is JsxExpressionContainer -> child.copy(expression = child.expression?.let { rewriteId(it, map, wrapCallsWithZero) })
            is JsxElement -> rewriteId(child, map, wrapCallsWithZero) as Node
            is JsxSelfClosingElement -> rewriteId(child, map, wrapCallsWithZero) as Node
            is JsxFragment -> rewriteId(child, map, wrapCallsWithZero) as Node
            else -> child
        }

    private fun isTypeErasureNode(expr: Expression): Boolean =
        expr is TypeAssertionExpression || expr is AsExpression
                || expr is NonNullExpression || expr is SatisfiesExpression

    /** Transfers leading comments from a type-erasure node to the transformed result. */
    private fun transferErasureComments(erasureNode: Expression, result: Expression): Expression {
        val comments = erasureNode.leadingComments
        if (comments.isNullOrEmpty()) return result
        // Prepend erasure node's comments to the result's existing leading comments
        val merged = comments + (result.leadingComments ?: emptyList())
        return copyExpressionWithLeadingComments(result, merged)
    }

    /** Creates a copy of an Expression with different leadingComments. */
    private fun copyExpressionWithLeadingComments(expr: Expression, comments: List<Comment>): Expression = when (expr) {
        is Identifier -> expr.copy(leadingComments = comments)
        is CallExpression -> expr.copy(leadingComments = comments)
        is PropertyAccessExpression -> expr.copy(leadingComments = comments)
        is BinaryExpression -> expr.copy(leadingComments = comments)
        is ParenthesizedExpression -> expr.copy(leadingComments = comments)
        is NumericLiteralNode -> expr.copy(leadingComments = comments)
        is StringLiteralNode -> expr.copy(leadingComments = comments)
        is PrefixUnaryExpression -> expr.copy(leadingComments = comments)
        is PostfixUnaryExpression -> expr.copy(leadingComments = comments)
        is ConditionalExpression -> expr.copy(leadingComments = comments)
        is ElementAccessExpression -> expr.copy(leadingComments = comments)
        is ObjectLiteralExpression -> expr.copy(leadingComments = comments)
        is ArrayLiteralExpression -> expr.copy(leadingComments = comments)
        is ArrowFunction -> expr.copy(leadingComments = comments)
        is FunctionExpression -> expr.copy(leadingComments = comments)
        is NewExpression -> expr.copy(leadingComments = comments)
        is TemplateExpression -> expr.copy(leadingComments = comments)
        is TaggedTemplateExpression -> expr.copy(leadingComments = comments)
        is SpreadElement -> expr.copy(leadingComments = comments)
        is AwaitExpression -> expr.copy(leadingComments = comments)
        is YieldExpression -> expr.copy(leadingComments = comments)
        is VoidExpression -> expr.copy(leadingComments = comments)
        is TypeOfExpression -> expr.copy(leadingComments = comments)
        is DeleteExpression -> expr.copy(leadingComments = comments)
        is ClassExpression -> expr.copy(leadingComments = comments)
        is CommaListExpression -> expr.copy(leadingComments = comments)
        else -> expr // fallback: can't copy, return as-is
    }

    /** Check if any node in a type-erasure chain (or its leftmost leaf) has leading comments. */
    private fun hasLeadingCommentsInErasureChain(expr: Expression): Boolean {
        var current = expr
        while (true) {
            if (!current.leadingComments.isNullOrEmpty()) return true
            val next = when (current) {
                is AsExpression -> current.expression
                is TypeAssertionExpression -> current.expression
                is NonNullExpression -> current.expression
                is SatisfiesExpression -> current.expression
                is ParenthesizedExpression -> current.expression
                is PropertyAccessExpression -> current.expression
                is ElementAccessExpression -> current.expression
                is CallExpression -> current.expression
                is BinaryExpression -> current.left
                is ConditionalExpression -> current.condition
                is TaggedTemplateExpression -> current.tag
                else -> return false
            }
            current = next
        }
    }

    /**
     * Returns true if [expr] (the result of stripping a type assertion) still needs
     * enclosing parentheses to be unambiguous JavaScript.
     *
     * - ObjectLiteralExpression: needs `()` in statement position to avoid being parsed as a block
     * - FunctionExpression / ClassExpression: needs `()` to avoid being parsed as a declaration
     * - ArrowFunction: needs `()` for immediate invocation `(()=>{})(...)`
     * - NewExpression without arguments: needs `()` for member access — `new A.foo` ≠ `(new A).foo`
     * - Prefix unary / keyword-prefix ops: needs `()` for member access — `(-A).x` ≠ `-A.x`
     */
    private fun typeAssertionResultNeedsParens(expr: Expression): Boolean = when (expr) {
        is ClassExpression -> true
        is ArrowFunction -> true
        is PrefixUnaryExpression -> true
        is TypeOfExpression, is VoidExpression, is DeleteExpression -> true
        is AwaitExpression, is YieldExpression -> true
        else -> false
    }

    /** Returns true if the expression tree has any leading or trailing comments anywhere. */
    private fun hasCommentsInTree(expr: Expression): Boolean {
        if (!expr.leadingComments.isNullOrEmpty() || !expr.trailingComments.isNullOrEmpty()) return true
        return when (expr) {
            is CallExpression -> hasCommentsInTree(expr.expression) || expr.arguments.any { hasCommentsInTree(it) }
            is PropertyAccessExpression -> hasCommentsInTree(expr.expression)
            is ParenthesizedExpression -> hasCommentsInTree(expr.expression)
            is BinaryExpression -> hasCommentsInTree(expr.left) || hasCommentsInTree(expr.right)
            is ConditionalExpression -> hasCommentsInTree(expr.condition) || hasCommentsInTree(expr.whenTrue) || hasCommentsInTree(expr.whenFalse)
            is NewExpression -> hasCommentsInTree(expr.expression) || expr.arguments?.any { hasCommentsInTree(it) } == true
            is PrefixUnaryExpression -> hasCommentsInTree(expr.operand)
            is PostfixUnaryExpression -> hasCommentsInTree(expr.operand)
            is ElementAccessExpression -> hasCommentsInTree(expr.expression) || hasCommentsInTree(expr.argumentExpression)
            else -> false
        }
    }

    /**
     * Normalizes a numeric literal text to its canonical decimal form.
     * E.g. `1.0` → `1`, `11e-1` → `1.1`, `0.12e1` → `1.2`.
     * Preserves hex/octal/binary prefixes and string-quoted values.
     */
    private fun normalizeNumericLiteral(text: String): String {
        val cleaned = text.replace("_", "")
        // Don't normalize bigint literals
        if (cleaned.endsWith("n")) return cleaned
        // Convert hex/octal/binary to decimal
        if (cleaned.startsWith("0x", ignoreCase = true) ||
            cleaned.startsWith("0o", ignoreCase = true) ||
            cleaned.startsWith("0b", ignoreCase = true)) {
            val digits = cleaned.substring(2)
            val base = when {
                cleaned.startsWith("0x", ignoreCase = true) -> 16
                cleaned.startsWith("0o", ignoreCase = true) -> 8
                else -> 2
            }
            return digits.toLongOrNull(base)?.toString() ?: cleaned
        }
        return try {
            val value = cleaned.toDouble()
            if (value == value.toLong().toDouble() && !cleaned.contains("e", ignoreCase = true)) {
                // Integer value — use long representation (strips trailing .0)
                value.toLong().toString()
            } else {
                // Fractional or exponential — normalize via Double.toString()
                // Double.toString() produces shortest representation (e.g. "0.5", "1.5E7")
                val s = value.toString()
                // Convert scientific notation to plain form if needed
                if ('E' in s || 'e' in s) {
                    formatDoublePlain(value)
                } else {
                    s
                }
            }
        } catch (_: NumberFormatException) {
            text
        }
    }

    /**
     * Formats a [Double] value as a plain decimal string without scientific notation.
     * Kotlin multiplatform replacement for `toBigDecimal().stripTrailingZeros().toPlainString()`.
     */
    private fun formatDoublePlain(value: Double): String {
        val s = value.toString()
        val eIdx = s.indexOfFirst { it == 'E' || it == 'e' }
        if (eIdx == -1) return s

        val mantissa = s.substring(0, eIdx)
        val exponent = s.substring(eIdx + 1).toInt()
        val dotIdx = mantissa.indexOf('.')
        val digits = mantissa.replace(".", "")
        // Position of decimal point in digits array
        val intDigits = if (dotIdx == -1) digits.length else dotIdx
        val newDotPos = intDigits + exponent

        return when {
            newDotPos >= digits.length -> {
                // All integer, pad with zeros
                digits + "0".repeat(newDotPos - digits.length)
            }
            newDotPos <= 0 -> {
                // All fractional, prepend zeros
                "0." + "0".repeat(-newDotPos) + digits
            }
            else -> {
                digits.substring(0, newDotPos) + "." + digits.substring(newDotPos)
            }
        }
    }

    /**
     * Wraps [expr] in parentheses if it is a `NewExpression` without argument parens,
     * which would be ambiguous as the LHS of a member-access or element-access
     * (e.g. `new a.b` means `new (a.b)`, not `(new a).b`).
     */
    private fun parenthesizeForAccess(expr: Expression): Expression = when {
        expr is NewExpression && expr.arguments == null ->
            ParenthesizedExpression(expression = expr, pos = expr.pos, end = expr.end)
        // Negative numeric literals need parens for property access: -1.toString() → (-1).toString()
        expr is PrefixUnaryExpression && (expr.operator == Minus || expr.operator == Plus) &&
            expr.operand is NumericLiteralNode ->
            ParenthesizedExpression(expression = expr, pos = expr.pos, end = expr.end)
        else -> expr
    }

    companion object {
        /** Strict-mode future reserved words that cannot be used as variable names. */
        val strictModeReservedWords = setOf(
            "implements", "interface", "let", "package",
            "private", "protected", "public", "static", "yield",
        )

        /** TypeScript `__makeTemplateObject` helper — emitted for tagged templates with invalid escape sequences. */
        val MAKE_TEMPLATE_OBJECT_HELPER = """var __makeTemplateObject = (this && this.__makeTemplateObject) || function (cooked, raw) {
    if (Object.defineProperty) { Object.defineProperty(cooked, "raw", { value: raw }); } else { cooked.raw = raw; }
    return cooked;
};
"""

        /** TypeScript `__rest` helper — emitted when object destructuring uses rest elements. */
        val REST_HELPER = """var __rest = (this && this.__rest) || function (s, e) {
    var t = {};
    for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p) && e.indexOf(p) < 0)
        t[p] = s[p];
    if (s != null && typeof Object.getOwnPropertySymbols === "function")
        for (var i = 0, p = Object.getOwnPropertySymbols(s); i < p.length; i++) {
            if (e.indexOf(p[i]) < 0 && Object.prototype.propertyIsEnumerable.call(s, p[i]))
                t[p[i]] = s[p[i]];
        }
    return t;
};
"""

        /** TypeScript `__decorate` helper — emitted for legacy experimental decorators. */
        val DECORATE_HELPER = """var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
"""

        /** TypeScript `__metadata` helper — emitted when `emitDecoratorMetadata` is true. */
        val METADATA_HELPER = """var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
"""

        /** TypeScript `__param` helper — emitted for parameter decorators. */
        val PARAM_HELPER = """var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
"""

        /** TypeScript `__awaiter` helper — emitted at the top of files with downleveled async functions. */
        val AWAITER_HELPER = """var __awaiter = (this && this.__awaiter) || function (thisArg, _arguments, P, generator) {
    function adopt(value) { return value instanceof P ? value : new P(function (resolve) { resolve(value); }); }
    return new (P || (P = Promise))(function (resolve, reject) {
        function fulfilled(value) { try { step(generator.next(value)); } catch (e) { reject(e); } }
        function rejected(value) { try { step(generator["throw"](value)); } catch (e) { reject(e); } }
        function step(result) { result.done ? resolve(result.value) : adopt(result.value).then(fulfilled, rejected); }
        step((generator = generator.apply(thisArg, _arguments || [])).next());
    });
};
"""

        /** TypeScript CJS `__createBinding` helper — needed by both `__importStar` and `__exportStar`. */
        val CREATE_BINDING_HELPER = """var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
"""

        /** `__setModuleDefault` + `__importStar` helpers — only needed for `import * as X from "module"`. */
        val SET_MODULE_DEFAULT_HELPER = """var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
"""

        val IMPORT_STAR_FUNC_HELPER = """var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
"""

        val IMPORT_STAR_ONLY_HELPERS get() = SET_MODULE_DEFAULT_HELPER + IMPORT_STAR_FUNC_HELPER

        /** Combined helpers for backward-compat use — equals `CREATE_BINDING_HELPER + IMPORT_STAR_ONLY_HELPERS`. */
        val IMPORT_STAR_HELPERS get() = CREATE_BINDING_HELPER + IMPORT_STAR_ONLY_HELPERS

        /** TypeScript CJS helper emitted when `import X from "module"` (default import) is used. */
        val IMPORT_DEFAULT_HELPER = """var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
"""

        val EXPORT_STAR_HELPER = """var __exportStar = (this && this.__exportStar) || function(m, exports) {
    for (var p in m) if (p !== "default" && !Object.prototype.hasOwnProperty.call(exports, p)) __createBinding(exports, m, p);
};
"""

        /** TypeScript `__await` helper — emitted for async generators (`async function*`). */
        val AWAIT_HELPER = """var __await = (this && this.__await) || function (v) { return this instanceof __await ? (this.v = v, this) : new __await(v); }
"""

        /** TypeScript `__asyncGenerator` helper — emitted for async generators (`async function*`). */
        val ASYNC_GENERATOR_HELPER = """var __asyncGenerator = (this && this.__asyncGenerator) || function (thisArg, _arguments, generator) {
    if (!Symbol.asyncIterator) throw new TypeError("Symbol.asyncIterator is not defined.");
    var g = generator.apply(thisArg, _arguments || []), i, q = [];
    return i = Object.create((typeof AsyncIterator === "function" ? AsyncIterator : Object).prototype), verb("next"), verb("throw"), verb("return", awaitReturn), i[Symbol.asyncIterator] = function () { return this; }, i;
    function awaitReturn(f) { return function (v) { return Promise.resolve(v).then(f, reject); }; }
    function verb(n, f) { if (g[n]) { i[n] = function (v) { return new Promise(function (a, b) { q.push([n, v, a, b]) > 1 || resume(n, v); }); }; if (f) i[n] = f(i[n]); } }
    function resume(n, v) { try { step(g[n](v)); } catch (e) { settle(q[0][3], e); } }
    function step(r) { r.value instanceof __await ? Promise.resolve(r.value.v).then(fulfill, reject) : settle(q[0][2], r); }
    function fulfill(value) { resume("next", value); }
    function reject(value) { resume("throw", value); }
    function settle(f, v) { if (f(v), q.shift(), q.length) resume(q[0][0], q[0][1]); }
};
"""
    }
}

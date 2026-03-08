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
class Transformer(private val options: CompilerOptions) {

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
    // Each entry: (iifeParamName, exportedNames) for an outer namespace level.
    private val outerNamespaceStack = mutableListOf<Pair<String, Set<String>>>()

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

    // Set to true when any async function/arrow is transformed to __awaiter form.
    // Causes the __awaiter helper to be prepended to the output statements.
    private var needsAwaiterHelper = false

    // Set to true when object destructuring with rest elements is transformed.
    // Causes the __rest helper to be prepended to the output statements.
    private var needsRestHelper = false

    // Set to true when any legacy decorator transform emits __decorate calls.
    private var needsDecorateHelper = false
    // Set to true when any parameter decorator emits __param calls.
    private var needsParamHelper = false

    // Counter for anonymous export default classes/functions (default_1, default_2, ...)
    private var anonDefaultCounter = 0


    // Top-level names that are declared ONLY as interfaces or type aliases (no runtime value).
    // Used to filter export specifiers that refer to type-only names, e.g. `export { A, B }`
    // where A and B are interfaces — TypeScript erases these and emits `export {}` instead.
    // Populated at the start of transform() from the original source file statements.
    private val topLevelTypeOnlyNames = mutableSetOf<String>()

    private fun nextTempVarName(): String {
        val n = tempVarCounter++
        return if (n < 26) "_" + ('a' + n) else "_${('a' + n / 26)}${('a' + n % 26)}"
    }

    fun transform(sourceFile: SourceFile): SourceFile {
        sourceText = sourceFile.text
        currentFileName = sourceFile.fileName
        hasSeenRuntimeStatement = false
        hasSeenAnyTopLevelStatement = false
        inAsyncBody = false
        needsAwaiterHelper = false
        needsRestHelper = false
        needsDecorateHelper = false
        needsParamHelper = false
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
                else -> {}
            }
        }
        // Remove any type-only names that also have a runtime declaration
        topLevelTypeOnlyNames -= topLevelRuntimeNames
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

        // Collect helpers to inject at top of file (order matters: __rest, __decorate, __param, __awaiter).
        val helpers = mutableListOf<RawStatement>()
        if (needsRestHelper) helpers.add(RawStatement(code = REST_HELPER))
        if (needsDecorateHelper && !options.noEmitHelpers) helpers.add(RawStatement(code = DECORATE_HELPER))
        if (needsParamHelper && !options.noEmitHelpers) helpers.add(RawStatement(code = PARAM_HELPER))
        if (needsAwaiterHelper) helpers.add(RawStatement(code = AWAITER_HELPER))

        // When helpers are present, lift leading comments from the first transformed statement
        // to appear BEFORE the helpers (TypeScript emits: comment → helpers → first stmt).
        // Exception: FunctionDeclaration keeps its comments after helpers when __awaiter is the only helper.
        val withHelpers = if (helpers.isNotEmpty()) {
            val firstOrigStmt = sourceFile.statements.firstOrNull()
            val onlyAwaiter = needsAwaiterHelper && !needsRestHelper
            val shouldLiftComments = firstOrigStmt != null && !(onlyAwaiter && firstOrigStmt is FunctionDeclaration)
            val firstStmt = transformed.firstOrNull()
            val firstComments = firstStmt?.leadingComments
            if (shouldLiftComments && !firstComments.isNullOrEmpty()) {
                val commentHolder = NotEmittedStatement(leadingComments = firstComments)
                val firstStripped: Statement? = when (firstStmt) {
                    is VariableStatement -> firstStmt.copy(leadingComments = null)
                    is ExpressionStatement -> firstStmt.copy(leadingComments = null)
                    is FunctionDeclaration -> firstStmt.copy(leadingComments = null)
                    is ClassDeclaration -> firstStmt.copy(leadingComments = null)
                    else -> null
                }
                if (firstStripped != null) {
                    listOf(commentHolder) + helpers + listOf(firstStripped) + transformed.drop(1)
                } else {
                    helpers + transformed
                }
            } else {
                helpers + transformed
            }
        } else transformed

        // CommonJS module transform (also for Node16/NodeNext with .ts/.cts files)
        val effectiveModule = options.effectiveModule
        val useCJS = effectiveModule == ModuleKind.CommonJS ||
                ((effectiveModule == ModuleKind.Node16 || effectiveModule == ModuleKind.NodeNext) &&
                        !isESModuleFormat(effectiveModule, sourceFile.fileName))
        if (useCJS && isModuleFile(sourceFile)) {
            val cjsStatements = transformToCommonJS(withHelpers, sourceFile)
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
        // only when x is explicitly type-only (import type x = M.N).
        // Without a type checker, we cannot safely determine if a value-position import
        // is unused, so we keep all non-type-only import aliases.
        val unusedAliasNames = sourceFile.statements
            .filterIsInstance<ImportEqualsDeclaration>()
            .filter { it.isTypeOnly && it.moduleReference !is ExternalModuleReference }
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
            // Check default import name
            val defaultName = clause.name?.text
            val defaultUsed = defaultName != null && defaultName in referenced
            // Check namespace import
            val nsName = (clause.namedBindings as? NamespaceImport)?.name?.text
            val nsUsed = nsName != null && nsName in referenced
            // Check named imports
            val namedImports = clause.namedBindings as? NamedImports
            val usedNamedElements = namedImports?.elements?.filter { spec ->
                val localName = spec.name.text
                localName in referenced
            }
            val hasUsedNamedImports = usedNamedElements != null && usedNamedElements.isNotEmpty()

            // If nothing is used, drop this import entirely
            if (!defaultUsed && !nsUsed && !hasUsedNamedImports) continue

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
    ) = sourceFile.statements.any { stmt ->
        stmt is ImportDeclaration || stmt is ExportDeclaration ||
                (stmt is ImportEqualsDeclaration && stmt.moduleReference is ExternalModuleReference) || stmt is ExportAssignment ||
                (stmt is VariableStatement && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is FunctionDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is ClassDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is EnumDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is InterfaceDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is TypeAliasDeclaration && ModifierFlag.Export in stmt.modifiers) ||
                (stmt is ModuleDeclaration && ModifierFlag.Export in stmt.modifiers)
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
                is ModuleDeclaration -> extractIdentifierName(stmt.name)?.let { earlyRuntimeNames.add(it) }
                else -> {}
            }
        }
        val earlyPureTypeNames = earlyTypeOnlyNames - earlyRuntimeNames
        val hasExportEquals = originalSourceFile.statements.any { stmt ->
            stmt is ExportAssignment && stmt.isExportEquals &&
                (stmt.expression as? Identifier)?.text?.let { it !in earlyPureTypeNames } != false
        }

        // Add Object.defineProperty(exports, "__esModule", { value: true });
        if (!hasExportEquals) {
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
        // Collect function export stubs (exports.fn = fn) to insert after void0 hoists.
        // Function declarations are JS-hoisted, so their export stubs must appear BEFORE
        // any variable initializer assignments that might override the same name.
        val functionExportStubs = mutableListOf<Statement>()
        // Collect module-level leading comments to place before the preamble.
        // TypeScript emits these BEFORE Object.defineProperty (after "use strict").
        val prePreambleStatements = mutableListOf<Statement>()

        // Pre-scan original source for function/class declaration names.
        // These names are JS-hoisted and must not get a void0 hoist when re-exported via
        // `export { name }` — they should use a function export stub instead.
        val functionAndClassNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when (stmt) {
                is FunctionDeclaration -> stmt.name?.text?.let { functionAndClassNames.add(it) }
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
                is ModuleDeclaration -> extractIdentifierName(stmt.name)?.let { runtimeDeclaredNames.add(it) }
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
                        if (n != null && decl.initializer != null) directExportedVarNames.add(n)
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

        val statementsToProcess = statementsRaw

        for (stmt in statementsToProcess) {
            when (stmt) {
                // import x = require("y") → already transformed to var x = require("y")
                // but it needs to use 'const' not 'var' for CommonJS
                is VariableStatement -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val strippedModifiers = stmt.modifiers - ModifierFlag.Export

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
                                // Keep declaration + emit exports.x = x; right after
                                result.add(stmt.copy(modifiers = strippedModifiers))
                                for (decl in stmt.declarationList.declarations) {
                                    val names = collectBoundNames(decl.name)
                                    if (names.isNotEmpty() && decl.initializer != null) {
                                        for (name in names) result.add(makeExportAssignment(name))
                                    }
                                }
                            } else {
                                // Direct: emit exports.x = value; for each declarator (no local var kept).
                                // Record the name so later export-expression references can be rewritten.
                                var isFirst = true
                                for (decl in stmt.declarationList.declarations) {
                                    val name = extractIdentifierName(decl.name)
                                    if (name != null && decl.initializer != null) {
                                        directExportedVarNames.add(name)
                                        val leadingComments = if (isFirst) stmt.leadingComments else null
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
                    }
                }

                is FunctionDeclaration -> {
                    val isExported = ModifierFlag.Export in stmt.modifiers
                    val isDefault = ModifierFlag.Default in stmt.modifiers

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
                        result.add(stmt)
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
                    }
                }

                is ExportAssignment -> {
                    if (stmt.isExportEquals) {
                        // export = expr → module.exports = expr; (deferred to end so all declarations come first)
                        // But erase if the expression refers to a pure type (interface/type alias).
                        val exprName = (stmt.expression as? Identifier)?.text
                        if (exprName == null || exprName !in pureTypeNames) {
                            deferredExportAssignments.add(
                                ExpressionStatement(
                                    expression = BinaryExpression(
                                        left = PropertyAccessExpression(
                                            expression = syntheticId("module"),
                                            name = syntheticId("exports"),
                                            pos = -1, end = -1,
                                        ),
                                        operator = Equals,
                                        right = stmt.expression,
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
                            // Rename: Namespace → b_1.default
                            renameMap[localName] = PropertyAccessExpression(
                                expression = syntheticId(tempName),
                                name = syntheticId("default"),
                                pos = -1, end = -1,
                            )
                        } else if (bindings is NamespaceImport) {
                            // import * as x from "y" → const x = __importStar(require("y"))
                            needsImportStar = true
                            result.add(makeImportHelperConst(bindings.name.text, "__importStar", moduleSpecifier, stmt.leadingComments))
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
                            // Rename: a → y_1.a, c → y_1.b
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
                        for (spec in (stmt.exportClause as NamedExports).elements) {
                            if (spec.isTypeOnly) continue
                            val exportName = spec.name.text
                            val localName = (spec.propertyName ?: spec.name).text
                            if (localName in functionAndClassNames) {
                                // Function/class: use stub (no void0 hoist needed)
                                functionExportStubs.add(makeExportAssignment(exportName, syntheticId(localName)))
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
                                    if (exportName !in exportedVarNames) exportedVarNames.add(exportName)
                                    result.add(makeExportAssignment(exportName, localExpr))
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
                        result.add(stmt)
                    }
                }
            }
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
        if (exportedVarNames.isNotEmpty()) {
            val void0 = VoidExpression(
                expression = NumericLiteralNode(text = "0", pos = -1, end = -1),
                pos = -1, end = -1,
            )
            val hoistExpr: Expression = exportedVarNames.fold(void0 as Expression) { acc, name ->
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
            result.add(if (hasExportEquals) 0 else 1, hoistStmt)
        }

        // Insert function export stubs after void0 hoists and Object.defineProperty preamble.
        // They must come before variable initializer assignments so the hoisted function
        // reference is captured before any same-name var overrides it.
        if (functionExportStubs.isNotEmpty()) {
            val insertPos = when {
                hasExportEquals -> 0
                exportedVarNames.isEmpty() -> 1 // after Object.defineProperty only
                else -> 2 // after Object.defineProperty + void0 hoist
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

        // Rewrite references to "direct" exported vars (exports.x = value, no local var kept).
        // References to these names throughout the body must become exports.name.
        if (directExportedVarNames.isNotEmpty()) {
            val exportRewriteMap: Map<String, Expression> = directExportedVarNames.associateWith { name ->
                PropertyAccessExpression(
                    expression = syntheticId("exports"),
                    name = syntheticId(name),
                    pos = -1, end = -1,
                ) as Expression
            }
            val functionExportStubSet = functionExportStubs.toHashSet()
            val rewritten = result.map { stmt ->
                if (stmt in functionExportStubSet) stmt else rewriteIdInStatement(stmt, exportRewriteMap)
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
        val unusedInternalAliasNames = originalSourceFile.statements
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
        if (importLikeStmts.isNotEmpty()) {
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
                // Also always preserve triple-slash directives (/// <reference>, etc.).
                val source = originalSourceFile.text
                for (stmt in toElide) {
                    val allComments = stmt.leadingComments
                    if (!allComments.isNullOrEmpty()) {
                        // Always preserve triple-slash directives
                        val tripleSlash = allComments.filter { it.text.startsWith("/// <") }
                        if (tripleSlash.isNotEmpty()) {
                            prePreambleStatements.addAll(tripleSlash.map { NotEmittedStatement(leadingComments = listOf(it)) })
                        }
                        // Preserve detached (blank-line-separated) comments when pos is valid
                        if (stmt.pos >= 0) {
                            val remaining = allComments - tripleSlash.toSet()
                            val detached = remaining.filter { c ->
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

    // -----------------------------------------------------------------
    // AMD module transform
    // -----------------------------------------------------------------

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
                is ModuleDeclaration -> extractIdentifierName(stmt.name)?.let { earlyRuntimeNames.add(it) }
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
                (stmt.expression as StringLiteralNode).text == "use strict")
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
                        if (n != null && decl.initializer != null) directExportedVarNames.add(n)
                    }
                }
            }
        }

        // Body statements (non-import content)
        val bodyStatements = mutableListOf<Statement>()
        // Import reassignment statements (ns = __importStar(ns), mod = __importDefault(mod))
        // These must appear at the TOP of the body (after "use strict" + preamble)
        val importReassignments = mutableListOf<Statement>()

        // Pass 1: collect imports to build deps/params, build body for non-imports
        for (stmt in statementsWithoutStrict) {
            when (stmt) {
                is ImportDeclaration -> {
                    val clause = stmt.importClause
                    val moduleSpecifier = stmt.moduleSpecifier
                    val specStr = (normalizeModuleSpecifier(moduleSpecifier) as? StringLiteralNode)?.text
                        ?: (moduleSpecifier as? StringLiteralNode)?.text ?: ""

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
                // In AMD, we need to detect this and convert to a dep+param instead
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
                            namedModuleImports.add(specStr2 to name)
                            // If this was an `export import x = require(...)`, also re-export it.
                            // Don't add to exportedVarNames (no void 0 hoist needed for re-exported require).
                            if (ModifierFlag.Export in stmt.modifiers) {
                                bodyStatements.add(makeExportAssignment(name))
                            }
                            // Don't add to body — it's now a parameter
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
                            bodyStatements.add(stmt.copy(modifiers = strippedModifiers))
                            for (d in stmt.declarationList.declarations) {
                                val names = collectBoundNames(d.name)
                                if (names.isNotEmpty() && d.initializer != null) {
                                    for (n in names) bodyStatements.add(makeExportAssignment(n))
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
                        for (spec in (stmt.exportClause as NamedExports).elements) {
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
        // Collect refs from body + function stubs + deferred, but NOT importReassignments
        // (otherwise every reassigned import would count as "used" by its own reassignment).
        val allRefs = collectValueReferences(renamedBody + functionExportStubs + deferredExportAssignments)

        // Filter namedModuleImports to keep only used ones
        val usedNamedModuleImports = namedModuleImports.filter { (_, paramName) ->
            paramName in allRefs
        }

        // Elide importReassignments for unused imports
        val unusedParamNames = namedModuleImports
            .filter { (_, paramName) -> paramName !in allRefs }
            .map { it.second }
            .toSet()
        val filteredReassignments = importReassignments.filter { stmt ->
            if (stmt is ExpressionStatement && stmt.expression is BinaryExpression) {
                val paramName = ((stmt.expression as BinaryExpression).left as? Identifier)?.text
                if (paramName != null && paramName in unusedParamNames) return@filter false
            }
            true
        }
        // Recheck: if we elided ALL importStar reassignments, don't need the star helper
        if (filteredReassignments.none { s ->
            s is ExpressionStatement && s.expression is BinaryExpression &&
                ((s.expression as BinaryExpression).right as? CallExpression)?.let {
                    (it.expression as? Identifier)?.text == "__importStar"
                } == true
        }) {
            needsImportStar = false
        }
        // Recheck: if we elided all importDefault reassignments, don't need the helper
        if (filteredReassignments.none { s ->
            s is ExpressionStatement && s.expression is BinaryExpression &&
                ((s.expression as BinaryExpression).right as? CallExpression)?.let {
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

        // Void0 hoists
        if (exportedVarNames.isNotEmpty()) {
            val void0 = VoidExpression(
                expression = NumericLiteralNode(text = "0", pos = -1, end = -1),
                pos = -1, end = -1,
            )
            val hoistExpr: Expression = exportedVarNames.fold(void0 as Expression) { acc, name ->
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

        // Function export stubs (after void0 hoists)
        fullBody.addAll(functionExportStubs)

        // Import reassignments (hoisted to top of body, after preamble)
        fullBody.addAll(filteredReassignments)

        // Main body
        fullBody.addAll(renamedBody)

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
        val defineArgs = mutableListOf<Expression>()
        if (amdDirectives.moduleName != null) {
            defineArgs.add(StringLiteralNode(text = amdDirectives.moduleName, pos = -1, end = -1))
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
                (stmt.expression as StringLiteralNode).text == "use strict")
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
        // Map from path → module-level temp var name (for named/default imports, one per path)
        val pathToModuleTempVar = mutableMapOf<String, String>()
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
                        ?: (stmt.name as? Expression)?.let { flattenDottedNamespaceName(it).firstOrNull() }
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
                for (spec in (stmt.exportClause as NamedExports).elements) {
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
                        ?: (stmt.name as? Expression)?.let { flattenDottedNamespaceName(it).firstOrNull() }
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
                    for (spec in (stmt.exportClause as NamedExports).elements) {
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
                    val specStr = (normalizeModuleSpecifier(moduleSpecifier) as? StringLiteralNode)?.text
                        ?: (moduleSpecifier as? StringLiteralNode)?.text ?: ""

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
                        for (spec in (stmt.exportClause as NamedExports).elements) {
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

        // Top-level var declarations from non-import statements
        for (stmt in originalSourceFile.statements) {
            when (stmt) {
                is VariableStatement -> {
                    if (ModifierFlag.Declare !in stmt.modifiers) {
                        for (d in stmt.declarationList.declarations) {
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
                        // For simple namespaces, extractIdentifierName gets the name.
                        // For dotted namespaces (A.B.C stored as PropertyAccessExpression), get the root.
                        val n = extractIdentifierName(stmt.name)
                            ?: (stmt.name as? Expression)?.let { flattenDottedNamespaceName(it).firstOrNull() }
                        // Skip if a function declaration with the same name is hoisted as a function
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
                    // import cls = alias.Class or export import cls2 = alias.Class
                    // (non-external module references — internal aliases)
                    // The name gets hoisted as a var
                    if (!stmt.isTypeOnly && stmt.moduleReference !is ExternalModuleReference) {
                        val n = stmt.name.text
                        if (n.isNotEmpty() && hoistedVarNamesSet.add(n)) hoistedVarNames.add(n)
                    }
                }
                is ForInStatement -> {
                    // `for (var key in obj)` — hoist `key` to top-level var list
                    val init = stmt.initializer
                    if (init is VariableDeclarationList && init.flags == VarKeyword) {
                        for (d in init.declarations) {
                            for (n in collectBoundNames(d.name)) {
                                if (hoistedVarNamesSet.add(n)) hoistedVarNames.add(n)
                            }
                        }
                    }
                }
                is ForOfStatement -> {
                    // `for (var x of arr)` — hoist `x`
                    val init = stmt.initializer
                    if (init is VariableDeclarationList && init.flags == VarKeyword) {
                        for (d in init.declarations) {
                            for (n in collectBoundNames(d.name)) {
                                if (hoistedVarNamesSet.add(n)) hoistedVarNames.add(n)
                            }
                        }
                    }
                }
                is ForStatement -> {
                    // `for (var i = 0; ...)` — hoist `i`
                    val init = stmt.initializer
                    if (init is VariableDeclarationList && init.flags == VarKeyword) {
                        for (d in init.declarations) {
                            for (n in collectBoundNames(d.name)) {
                                if (hoistedVarNamesSet.add(n)) hoistedVarNames.add(n)
                            }
                        }
                    }
                }
                else -> {}
            }
        }

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
                                // Destructuring — keep the statement but strip export
                                executeStatements.add(stripped)
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
                                // Complex binding — keep as-is (with `let` since it's block-scoped)
                                executeStatements.add(stripped)
                            }
                            // If name != null and no initializer: already hoisted, nothing to emit.
                            // Transfer leading comments to the next statement (typically the IIFE).
                            else if (name != null && !stmt.leadingComments.isNullOrEmpty()) {
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
                        for (spec in (stmt.exportClause as NamedExports).elements) {
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
        val renamedExecute = if (renameMap.isNotEmpty()) {
            allExecuteStatements.map { rewriteIdInStatement(it, renameMap, wrapCallsWithZero = false) }
        } else allExecuteStatements.toList()
        // Rewrite identifiers inside hoisted function bodies (e.g. references to import bindings)
        if (renameMap.isNotEmpty() && functionHoists.isNotEmpty()) {
            val rewritten = functionHoists.map { rewriteIdInStatement(it, renameMap, wrapCallsWithZero = false) }
            functionHoists.clear()
            functionHoists.addAll(rewritten)
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
                val nsVars = pathToNamespaceVars[path] ?: emptyList<String>()
                val moduleTempVar = pathToModuleTempVar[path]
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
                statements = renamedExecute,
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
                    // If /// <amd-module name='X'/> directive present, add module name as first arg
                    if (amdDirectives.moduleName != null) {
                        add(StringLiteralNode(text = amdDirectives.moduleName, pos = -1, end = -1))
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
        val fn = parenFn.expression as FunctionExpression
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
                flags = if (useVar) VarKeyword else ConstKeyword,
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
                flags = ConstKeyword,
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
                result.addAll(insertAt, combined)
            }
        }

        return result
    }

    /**
     * Transforms a single statement, potentially producing 0, 1, or many output statements.
     */
    private fun transformStatement(statement: Statement): List<Statement> {
        // Declarations with `declare` modifier produce no output
        if (statement is Declaration && hasDeclareModifier(statement)) {
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
            is FunctionDeclaration -> transformFunctionDeclaration(statement)

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
        // Overload signatures (no body, not declare) are erased
        if (decl.body == null) return emptyList()

        val strippedModifiers = stripTypeScriptModifiers(decl.modifiers)
        val isAsync = ModifierFlag.Async in decl.modifiers && options.effectiveTarget < ScriptTarget.ES2017
        val prevInAsyncBody = inAsyncBody
        inAsyncBody = isAsync
        val transformedBody = transformBlock(decl.body, isFunctionScope = true)
        inAsyncBody = prevInAsyncBody

        if (isAsync) {
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
            return listOf(decl.copy(
                typeParameters = null,
                parameters = outerParams,
                type = null,
                body = awaiterBody,
                modifiers = strippedModifiers - ModifierFlag.Async,
                asteriskToken = false,
            ))
        }
        // Transform parameters inside the function scope so async arrows in
        // default parameters see the correct functionScopeDepth for `this` binding.
        functionScopeDepth++
        val params = transformParameters(decl.parameters)
        functionScopeDepth--
        return listOf(
            decl.copy(
                typeParameters = null,
                parameters = params,
                type = null,
                body = transformedBody,
                modifiers = strippedModifiers,
            )
        )
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
        return CallExpression(
            expression = syntheticId("__awaiter"),
            arguments = listOf(thisArg, secondArg ?: void0, void0, generatorFn),
            pos = -1, end = -1,
        )
    }

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
                        elem.name is Identifier -> (elem.name as Identifier).text
                        else -> return@map null
                    }
                    StringLiteralNode(text = keyName, singleQuote = false, pos = -1, end = -1)
                }.filterNotNull()

                val initExpr = decl.initializer?.let { transformExpression(it) }

                // If initializer is complex (not a simple identifier) AND there are
                // non-rest elements (need to destructure), use a temp var to avoid
                // evaluating the expression twice.
                val sourceExpr: Expression
                val needsTempVar = initExpr != null && nonRestElements.isNotEmpty() &&
                    (initExpr !is Identifier || (initExpr as Identifier).text == "this")
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
                        val newPattern = ObjectBindingPattern(
                            elements = nonRestElements.map { transformBindingElement(it) },
                            pos = -1, end = -1,
                        )
                        newDecls.add(VariableDeclaration(
                            name = newPattern,
                            initializer = sourceExpr,
                            pos = -1, end = -1,
                            leadingComments = decl.leadingComments,
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
    private fun transformBinaryExpression(root: BinaryExpression): Expression {
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
            if (left is Identifier) {
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
                    transformed
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
                        nullCheck = ParenthesizedExpression(BinaryExpression(left = tempId, operator = Equals, right = transformedExpr, pos = -1, end = -1), pos = -1, end = -1)
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
                    val memberName = expr.name
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
                if (baseName != null) {
                    val inlined = tryInlineConstEnumMember(baseName, memberName, "$baseName.$memberName")
                    inlined ?: expr.copy(expression = parenthesizeForAccess(transformExpression(expr.expression)))
                } else {
                    // Check for namespaced const enum: M.SomeConstEnum.X
                    // Try the last component of the LHS as the enum name
                    val nestedEnum = expr.expression as? PropertyAccessExpression
                    val nestedEnumName = nestedEnum?.name?.text
                    if (nestedEnumName != null && nestedEnumName in constEnumValues) {
                        // Build comment label by reconstructing the full access chain text
                        fun accessChainText(e: Expression): String = when (e) {
                            is Identifier -> e.text
                            is PropertyAccessExpression -> "${accessChainText(e.expression)}.${e.name.text}"
                            else -> ""
                        }
                        val commentLabel = "${accessChainText(expr.expression)}.$memberName"
                        val inlined = tryInlineConstEnumMember(nestedEnumName, memberName, commentLabel)
                        inlined ?: expr.copy(expression = parenthesizeForAccess(transformExpression(expr.expression)))
                    } else {
                        expr.copy(expression = parenthesizeForAccess(transformExpression(expr.expression)))
                    }
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
                        nullCheck = ParenthesizedExpression(BinaryExpression(left = tempId, operator = Equals, right = obj, pos = -1, end = -1), pos = -1, end = -1)
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
            is ArrowFunction -> {
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
                    expr.copy(
                        typeParameters = null,
                        parameters = transformParameters(expr.parameters),
                        type = null,
                        body = transformedBody,
                        modifiers = strippedModifiers,
                    )
                }
            }

            // Function expression: strip types (and downlevel async if target < ES2017)
            is FunctionExpression -> {
                val strippedModifiers = stripTypeScriptModifiers(expr.modifiers)
                val isAsync = ModifierFlag.Async in expr.modifiers && options.effectiveTarget < ScriptTarget.ES2017
                val prevInAsyncBody = inAsyncBody
                inAsyncBody = isAsync
                val transformedBody = transformBlock(expr.body, isFunctionScope = true)
                inAsyncBody = prevInAsyncBody
                if (isAsync) {
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
                    expr.copy(
                        typeParameters = null,
                        parameters = transformParameters(expr.parameters),
                        type = null,
                        body = transformedBody,
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
            is TaggedTemplateExpression -> expr.copy(
                tag = transformExpression(expr.tag),
                typeArguments = null,
            )

            // Yield
            is YieldExpression -> expr.copy(
                expression = expr.expression?.let { transformExpression(it) }
            )

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

            // Await: convert to yield when inside a downleveled async body,
            // or when await is used outside an async context at an older target
            // (TypeScript emits yield for non-async-context await at targets < ES2017)
            is AwaitExpression -> {
                val transformedInner = transformExpression(expr.expression)
                if (inAsyncBody || (!expr.inAsyncContext && options.effectiveTarget < ScriptTarget.ES2017)) {
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
        )
        val pendingProps = mutableListOf<Node>()
        var isFirst = true

        fun flushPending(trailingComma: Boolean = false) {
            if (pendingProps.isEmpty()) return
            val obj = ObjectLiteralExpression(
                properties = pendingProps.toList(),
                multiLine = false,
                hasTrailingComma = trailingComma,
            )
            accumulator = makeObjectAssignCall(listOf(accumulator, obj))
            pendingProps.clear()
            isFirst = false
        }

        for (prop in remaining) {
            if (prop is SpreadAssignment) {
                flushPending()
                if (isFirst) {
                    // First spread: start the chain — accumulator already holds leading props (or {})
                    accumulator = makeObjectAssignCall(listOf(accumulator, prop.expression))
                    isFirst = false
                } else {
                    accumulator = makeObjectAssignCall(listOf(accumulator, prop.expression))
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
        val prevInAsyncBody = inAsyncBody
        inAsyncBody = isAsync
        val transformedBody = method.body?.let { transformBlock(it, isFunctionScope = true) }
        inAsyncBody = prevInAsyncBody
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

        // Erase import if the referenced name is type-only (interface, type alias, or type-only namespace)
        val ref = decl.moduleReference
        if (ref is Identifier && ref.text in topLevelTypeOnlyNames) {
            return emptyList()
        }
        if (ref is QualifiedName) {
            val rootName = generateSequence(ref) { (it.left as? QualifiedName) }.last().left
            if (rootName is Identifier && rootName.text in topLevelTypeOnlyNames) {
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
                    (ref.rawText != null && (ref.rawText!!.startsWith("\"") || ref.rawText!!.startsWith("'")))
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
                // For re-exports (with moduleSpecifier), we don't know the target file's types
                if (decl.moduleSpecifier != null) return@filter true
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
        val className = decl.name?.text ?: return listOf(decl.copy(
            typeParameters = null,
            heritageClauses = result.heritageClauses,
            members = result.members,
            modifiers = stripTypeScriptModifiers(decl.modifiers) - ModifierFlag.Abstract,
            decorators = null,
        )) + result.trailingStatements

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
        statements.addAll(generateMemberDecorateStatements(className, decl.members))

        // Emit class-level __decorate call
        if (hasClassDecorators) {
            statements.add(generateClassDecorateStatement(className, decl.decorators!!))
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
    private fun generateMemberDecorateStatements(className: String, members: List<ClassElement>): List<Statement> {
        val stmts = mutableListOf<Statement>()
        for (member in members) {
            val decorators: List<Decorator>?
            val memberName: String?
            val isStatic: Boolean
            val isProperty: Boolean
            val paramDecorators: List<Pair<Int, List<Decorator>>>

            when (member) {
                is PropertyDeclaration -> {
                    decorators = member.decorators
                    memberName = getMemberNameText(member.name)
                    isStatic = ModifierFlag.Static in member.modifiers
                    isProperty = true
                    paramDecorators = emptyList()
                }
                is MethodDeclaration -> {
                    decorators = member.decorators
                    memberName = getMemberNameText(member.name)
                    isStatic = ModifierFlag.Static in member.modifiers
                    isProperty = false
                    paramDecorators = member.parameters.mapIndexedNotNull { idx, param ->
                        if (!param.decorators.isNullOrEmpty()) idx to param.decorators else null
                    }
                }
                is GetAccessor -> {
                    decorators = member.decorators
                    memberName = getMemberNameText(member.name)
                    isStatic = ModifierFlag.Static in member.modifiers
                    isProperty = false
                    paramDecorators = emptyList()
                }
                is SetAccessor -> {
                    decorators = member.decorators
                    memberName = getMemberNameText(member.name)
                    isStatic = ModifierFlag.Static in member.modifiers
                    isProperty = false
                    paramDecorators = emptyList()
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
                for (dec in decorators!!) {
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
        // Check if there are static properties that would need a temp var.
        val hasStaticInitializers = !useDefineForClassFields &&
            expr.members.any { it is PropertyDeclaration && ModifierFlag.Static in it.modifiers && it.initializer != null }

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
                            ewta.copy(
                                expression = transformExpression(ewta.expression),
                                typeArguments = null,
                            )
                        }
                )
            }
            ?.ifEmpty { null }

        // Separate members by category
        val instanceProperties = mutableListOf<PropertyDeclaration>()
        val staticProperties = mutableListOf<PropertyDeclaration>()
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
                val transformedParams = transformParameters(existingConstructor.parameters)
                val existingBody = existingConstructor.body?.let { transformBlock(it, isFunctionScope = true) }
                    ?: Block(statements = emptyList(), pos = -1, end = -1)

                // Find the position of super() call in existing body
                val bodyStatements = existingBody.statements.toMutableList()
                val superIndex = bodyStatements.indexOfFirst { isSuperCallStatement(it) }

                if (superIndex >= 0) {
                    // Insert after super() call
                    bodyStatements.addAll(superIndex + 1, propInitStatements)
                } else {
                    // Insert after any prologue directives ("use strict", "ngInject", etc.)
                    val insertAt = bodyStatements.indexOfFirst { stmt ->
                        !(stmt is ExpressionStatement && stmt.expression is StringLiteralNode)
                    }.let { if (it < 0) bodyStatements.size else it }
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
        val effectiveName = trailingVarName ?: name?.text
        if (!useDefineForClassFields && effectiveName != null) {
            for (prop in staticProperties) {
                if (prop.initializer != null) {
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
                        trailingStatements.add(
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
                element.copy(
                    typeParameters = null,
                    parameters = transformParameters(element.parameters),
                    type = null,
                    body = transformBlock(element.body, isFunctionScope = true),
                    modifiers = stripMemberModifiers(element.modifiers),
                )
            }

            is GetAccessor -> {
                element.copy(
                    parameters = transformParameters(element.parameters),
                    type = null,
                    body = element.body?.let { transformBlock(it, isFunctionScope = true) },
                    modifiers = stripMemberModifiers(element.modifiers),
                )
            }

            is SetAccessor -> {
                element.copy(
                    parameters = transformParameters(element.parameters),
                    body = element.body?.let { transformBlock(it, isFunctionScope = true) },
                    modifiers = stripMemberModifiers(element.modifiers),
                )
            }

            is PropertyDeclaration -> {
                // Remaining property declarations (e.g. kept by useDefineForClassFields)
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

        for (member in decl.members) {
            val memberName = extractEnumMemberName(member.name)
            val memberNameExpr = memberNameToString(member.name)

            val stmt: ExpressionStatement = when {
                member.initializer != null -> {
                    val initExpr = transformExpression(member.initializer)
                    if (isStringLiteral(initExpr)) {
                        // String enum member: E["B"] = "hello" (no reverse mapping)
                        // After a string member, auto-increment is disrupted; next numeric
                        // member must have explicit initializer. We don't track further.
                        autoIncrementValid = false
                        // Normalize to double quotes (TypeScript always emits double quotes for enum values)
                        val normalizedExpr = if (initExpr is StringLiteralNode && initExpr.singleQuote) {
                            initExpr.copy(singleQuote = false, rawText = null)
                        } else initExpr
                        ExpressionStatement(
                            expression = BinaryExpression(
                                left = ElementAccessExpression(
                                    expression = syntheticId(enumName),
                                    argumentExpression = memberNameExpr,
                                    pos = -1, end = -1,
                                ),
                                operator = Equals,
                                right = normalizedExpr,
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    } else {
                        // Numeric / expression initializer: E[E["X"] = expr] = "X"
                        // Try to fold constant expressions (e.g. 1 << 1 → 2)
                        val foldedValue = evaluateConstantExpression(initExpr, memberValues)
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
            is NumericLiteralNode -> name
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
            val nsName = extractIdentifierName(stmt.name) ?: continue
            val exports = mergedNamespaceExports.getOrPut(nsName) { mutableSetOf() }
            val body = stmt.body
            val bodyStmts = when (body) {
                is ModuleBlock -> body.statements
                else -> continue
            }
            for (bodyStmt in bodyStmts) {
                val isExported = when (bodyStmt) {
                    is VariableStatement -> ModifierFlag.Export in bodyStmt.modifiers
                    is FunctionDeclaration -> ModifierFlag.Export in bodyStmt.modifiers && bodyStmt.body != null
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
                    is FunctionDeclaration -> if (bodyStmt.body != null) bodyStmt.name?.text?.let { exports.add(it) }
                    is ClassDeclaration -> bodyStmt.name?.text?.let { exports.add(it) }
                    is EnumDeclaration -> exports.add(bodyStmt.name.text)
                    is ModuleDeclaration -> extractIdentifierName(bodyStmt.name)?.let { exports.add(it) }
                    is ImportEqualsDeclaration -> exports.add(bodyStmt.name.text)
                    else -> {}
                }
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
        val comment = Comment(
            text = "/* $commentLabel */",
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
                    ParenthesizedExpression(
                        expression = PrefixUnaryExpression(
                            operator = Minus,
                            operand = literal,
                            pos = -1, end = -1,
                        ),
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
                // Handle cross-enum references like Foo.a where Foo is a previously-defined enum
                val obj = expr.expression
                if (obj is Identifier) {
                    allEnumMemberValues[obj.text]?.get(expr.name.text)
                } else null
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
                val innerStatements = transformModuleDeclaration(body, nested = true, parentNsName = iifeParam, useDottedVar = useDottedVar)
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
        // Only apply at the top level — nested namespaces with the same name as an outer namespace
        // are different scopes and should NOT inherit the outer's merged exports.
        if (outerNamespaceStack.isEmpty()) {
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
                    // Skip aliases to type-only namespaces (they have no runtime value)
                    val ref = stmt.moduleReference
                    if (ref is Identifier) {
                        val refName = ref.text
                        val referencedNs = statements.filterIsInstance<ModuleDeclaration>()
                            .firstOrNull { extractIdentifierName(it.name) == refName }
                        if (referencedNs != null && isTypeOnlyNamespace(referencedNs)) continue
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
                        val value: Expression = when (ref) {
                            is Expression -> qualifyNamespaceRefs(nsName, exportedNames, transformExpression(ref))
                            is QualifiedName -> qualifyNamespaceRefs(nsName, exportedNames, qualifiedNameToPropertyAccess(ref))
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
                    // Push current namespace context so inner bodies can qualify outer refs
                    outerNamespaceStack.add(nsName to exportedNames)
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
        for ((outerNsName, outerExportedNames) in outerNamespaceStack) {
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
            is FunctionDeclaration -> node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
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
            // Class elements
            is PropertyDeclaration -> collectRefsFromNode(node.initializer, refs)
            is MethodDeclaration -> node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
            is Constructor -> node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
            is GetAccessor -> node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
            is SetAccessor -> node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
            is ClassStaticBlockDeclaration -> node.body.statements.forEach { collectRefsFromNode(it, refs) }
            // Variable declaration list (for `for` initializer)
            is VariableDeclarationList -> node.declarations.forEach { collectRefsFromNode(it.initializer, refs) }
            else -> {} // literals, type nodes, etc. — no identifiers to collect
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
            parameters = stmt.parameters.map { p -> p.copy(initializer = p.initializer?.let { rewriteId(it, map, wrapCallsWithZero) }) },
            body = stmt.body?.copy(statements = stmt.body.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) }),
        )
        else -> stmt
    }

    private fun rewriteIdInClassElement(member: ClassElement, map: Map<String, Expression>, wrapCallsWithZero: Boolean = true): ClassElement = when (member) {
        is Constructor -> member.copy(
            parameters = member.parameters.map { p -> p.copy(initializer = p.initializer?.let { rewriteId(it, map, wrapCallsWithZero) }) },
            body = member.body?.copy(statements = member.body.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) }),
        )
        is MethodDeclaration -> member.copy(
            parameters = member.parameters.map { p -> p.copy(initializer = p.initializer?.let { rewriteId(it, map, wrapCallsWithZero) }) },
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
        is BinaryExpression -> expr.copy(
            left = rewriteId(expr.left, map, wrapCallsWithZero),
            right = rewriteId(expr.right, map, wrapCallsWithZero),
        )
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
        is ArrowFunction -> expr.copy(body = when (val b = expr.body) {
            is Block -> b.copy(statements = b.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) })
            is Expression -> rewriteId(b, map, wrapCallsWithZero)
            else -> b
        })
        is FunctionExpression -> expr.copy(
            body = expr.body.copy(statements = expr.body.statements.map { rewriteIdInStatement(it, map, wrapCallsWithZero) })
        )
        is NonNullExpression -> expr.copy(expression = rewriteId(expr.expression, map, wrapCallsWithZero))
        is ClassExpression -> expr.copy(
            heritageClauses = expr.heritageClauses?.map { hc ->
                hc.copy(types = hc.types.map { t -> t.copy(expression = rewriteId(t.expression, map, wrapCallsWithZero)) })
            },
            members = expr.members.map { rewriteIdInClassElement(it, map, wrapCallsWithZero) },
        )
        else -> expr
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

    /**
     * Wraps [expr] in parentheses if it is a `NewExpression` without argument parens,
     * which would be ambiguous as the LHS of a member-access or element-access
     * (e.g. `new a.b` means `new (a.b)`, not `(new a).b`).
     */
    private fun parenthesizeForAccess(expr: Expression): Expression =
        if (expr is NewExpression && expr.arguments == null) {
            ParenthesizedExpression(expression = expr, pos = expr.pos, end = expr.end)
        } else {
            expr
        }

    companion object {
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
    }
}

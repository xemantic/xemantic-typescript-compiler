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

    // True once we've seen a runtime (non-erased) statement at the top level.
    // Orphaned comments from erased declarations are only preserved before any runtime code.
    private var hasSeenRuntimeStatement = false

    fun transform(sourceFile: SourceFile): SourceFile {
        sourceText = sourceFile.text
        hasSeenRuntimeStatement = false
        val transformed = transformStatements(sourceFile.statements, atTopLevel = true)

        // CommonJS module transform
        if (options.effectiveModule == ModuleKind.CommonJS && isModuleFile(sourceFile)) {
            val cjsStatements = transformToCommonJS(transformed, sourceFile)
            return sourceFile.copy(statements = cjsStatements)
        }

        // ES module import elision: erase imports whose bindings are unused in value positions
        val elided = elideUnusedESModuleImports(transformed)
        return sourceFile.copy(statements = elided)
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

            // If all named imports are used (or there are no named imports), keep as-is
            if (namedImports == null || usedNamedElements == namedImports.elements) {
                result.add(stmt)
            } else {
                // Keep only the used named imports
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
        val hasExportEquals = originalSourceFile.statements.any { it is ExportAssignment && it.isExportEquals }

        // Add Object.defineProperty(exports, "__esModule", { value: true });
        if (!hasExportEquals) {
            result.add(makeEsModulePreamble())
        }

        // Track whether helper functions are needed
        var needsImportStar = false
        var needsImportDefault = false
        var needsExportStar = false

        // Map from original import name → CJS replacement expression
        // e.g. "Namespace" → b_1.default, "a" (from {a}) → y_1.a
        val renameMap = mutableMapOf<String, Expression>()

        // Collect exported names for hoisting (exports.x = void 0)
        val exportedVarNames = mutableListOf<String>()
        // Collect export assignments to emit at the end
        val deferredExportAssignments = mutableListOf<Statement>()
        // Collect function export stubs (exports.fn = fn) to insert after void0 hoists.
        // Function declarations are JS-hoisted, so their export stubs must appear BEFORE
        // any variable initializer assignments that might override the same name.
        val functionExportStubs = mutableListOf<Statement>()

        // Pre-scan original source for exported namespace/enum names. Their var declarations
        // have no export modifier in the transformed code (it was stripped during namespace/enum
        // transform), so we need to track them separately for hoisting and IIFE arg rewriting.
        // Skip `declare` declarations — they produce no runtime output.
        val exportedNsEnumNames = mutableSetOf<String>()
        for (stmt in originalSourceFile.statements) {
            when {
                stmt is ModuleDeclaration && ModifierFlag.Export in stmt.modifiers &&
                        !hasDeclareModifier(stmt) ->
                    extractIdentifierName(stmt.name)?.let { exportedNsEnumNames.add(it) }
                stmt is EnumDeclaration && ModifierFlag.Export in stmt.modifiers &&
                        !hasDeclareModifier(stmt) &&
                        (options.preserveConstEnums || ModifierFlag.Const !in stmt.modifiers) ->
                    exportedNsEnumNames.add(stmt.name.text)
            }
        }
        for (name in exportedNsEnumNames) {
            if (name !in exportedVarNames) exportedVarNames.add(name)
        }

        // Detect and strip "use strict" prologue directives so they can be re-inserted at the
        // very top of the transformed output (before helpers and preamble).
        var hasUseStrictPrologue = false
        val statementsToProcess = statements.filter { stmt ->
            if (stmt is ExpressionStatement) {
                val expr = stmt.expression
                if (expr is StringLiteralNode && expr.text == "use strict") {
                    hasUseStrictPrologue = true
                    false // strip from body
                } else true
            } else true
        }

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
                                val name = extractIdentifierName(decl.name)
                                if (name != null) exportedVarNames.add(name)
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
                                    val name = extractIdentifierName(decl.name)
                                    if (name != null && decl.initializer != null) {
                                        result.add(makeExportAssignment(name))
                                    }
                                }
                            } else {
                                // Direct: emit exports.x = value; for each declarator
                                var isFirst = true
                                for (decl in stmt.declarationList.declarations) {
                                    val name = extractIdentifierName(decl.name)
                                    if (name != null && decl.initializer != null) {
                                        val leadingComments = if (isFirst) stmt.leadingComments else null
                                        result.add(
                                            ExpressionStatement(
                                                expression = BinaryExpression(
                                                    left = PropertyAccessExpression(
                                                        expression = syntheticId("exports"),
                                                        name = Identifier(text = name, pos = -1, end = -1),
                                                        pos = -1, end = -1,
                                                    ),
                                                    operator = SyntaxKind.Equals,
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
                        val emitted = stmt.copy(modifiers = strippedModifiers)
                        val name = stmt.name?.text
                        if (isDefault) {
                            if (name != null) {
                                // exports.default = foo goes in stubs (before function declaration),
                                // since function declarations are JS-hoisted and available early.
                                functionExportStubs.add(makeExportAssignment("default", syntheticId(name)))
                            }
                            result.add(emitted)
                        } else {
                            if (name != null) {
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
                        val emitted = stmt.copy(modifiers = strippedModifiers)
                        result.add(emitted)
                        val name = stmt.name?.text
                        if (name != null) {
                            if (isDefault) {
                                result.add(makeExportAssignment("default", syntheticId(name)))
                            } else {
                                // Hoist exports.ClassName = void 0 and defer exports.ClassName = ClassName
                                exportedVarNames.add(name)
                                deferredExportAssignments.add(makeExportAssignment(name))
                            }
                        }
                    } else {
                        result.add(stmt)
                    }
                }

                is ExportAssignment -> {
                    if (stmt.isExportEquals) {
                        // export = expr → module.exports = expr;
                        result.add(
                            ExpressionStatement(
                                expression = BinaryExpression(
                                    left = PropertyAccessExpression(
                                        expression = syntheticId("module"),
                                        name = syntheticId("exports"),
                                        pos = -1, end = -1,
                                    ),
                                    operator = SyntaxKind.Equals,
                                    right = stmt.expression,
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
                            )
                        )
                    } else {
                        // export default expr → exports.default = expr;
                        result.add(makeExportAssignment("default", stmt.expression))
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
                            // Default import: const b_1 = __importDefault(require("./b"))
                            // TypeScript uses the module specifier basename as temp name, not the local name
                            needsImportDefault = true
                            val localName = clause.name.text
                            val tempName = generateModuleTempName(moduleSpecifier)
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
                        } else if (bindings is NamedImports) {
                            // import { a, b as c } from "y" → const y_1 = require("y")
                            val tempName = generateModuleTempName(moduleSpecifier)
                            result.add(makeRequireConst(tempName, moduleSpecifier, stmt.leadingComments))
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
                        } else if (clause.name != null && bindings != null) {
                            // Combined default + named: complex case
                            needsImportDefault = true
                            val localName = clause.name.text
                            val tempName = generateModuleTempName(moduleSpecifier)
                            result.add(makeImportHelperConst(tempName, "__importDefault", moduleSpecifier, stmt.leadingComments))
                            renameMap[localName] = PropertyAccessExpression(
                                expression = syntheticId(tempName),
                                name = syntheticId("default"),
                                pos = -1, end = -1,
                            )
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
                                val tempName = generateModuleTempName(stmt.moduleSpecifier)
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
                                        result.add(makeReExportGetter(exportName, tempName, importedName))
                                    }
                                }
                            }
                            is NamespaceExport -> {
                                // export * as ns from "m" → Object.defineProperty(exports, "ns", ...)
                                needsImportStar = true
                                val tempName = generateModuleTempName(stmt.moduleSpecifier)
                                result.add(makeImportHelperConst(tempName, "__importStar", stmt.moduleSpecifier, stmt.leadingComments))
                                result.add(makeReExportGetter(clause.name.text, tempName, null))
                            }
                            else -> result.add(stmt)
                        }
                    } else if (stmt.exportClause is NamedExports) {
                        // export { x, y } — emit exports.x = x, exports.y = y
                        for (spec in (stmt.exportClause as NamedExports).elements) {
                            if (spec.isTypeOnly) continue
                            val exportName = spec.propertyName?.text ?: spec.name.text
                            val localName = spec.name.text
                            result.add(makeExportAssignment(exportName, syntheticId(localName)))
                        }
                    }
                }

                else -> {
                    // Check if this is a namespace/enum IIFE for an exported name.
                    // If so, rewrite the IIFE arg from N || (N = {}) to N || (exports.N = N = {}).
                    val iifeNameForExport = extractSimpleIifeName(stmt)
                    if (iifeNameForExport != null && iifeNameForExport in exportedNsEnumNames) {
                        result.add(rewriteIifeArgForCjsExport(stmt as ExpressionStatement, iifeNameForExport))
                    } else {
                        result.add(stmt)
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
                    operator = SyntaxKind.Equals,
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

        // Step 2: Import elision — drop require imports whose bound name is never referenced.
        if (requireImportStmts.isNotEmpty()) {
            val requireImportSet = requireImportStmts.toHashSet()
            val nonImportStmts = result.filter { it !in requireImportSet }
            val referenced = collectValueReferences(nonImportStmts)
            val toElide = mutableSetOf<VariableStatement>()
            for (stmt in requireImportStmts) {
                val name = extractIdentifierName(stmt.declarationList.declarations[0].name) ?: continue
                if (name !in referenced) toElide.add(stmt)
            }
            if (toElide.isNotEmpty()) {
                // If we're eliding all __importStar uses, we don't need the helper
                val allStarElided = requireImportStmts.filter { isImportStarWrapper(it) }.all { it in toElide }
                if (allStarElided) needsImportStar = false
                val allDefaultElided = requireImportStmts.filter { isImportDefaultWrapper(it) }.all { it in toElide }
                if (allDefaultElided) needsImportDefault = false
                result.removeAll(toElide)
            }
        }

        // Insert runtime helpers at the start (before Object.defineProperty preamble)
        // These must be at position 0 (before everything else)
        if (needsImportStar || needsImportDefault || needsExportStar) {
            val helpers = buildString {
                // __createBinding is needed by both __importStar and __exportStar
                if (needsImportStar || needsExportStar) append(CREATE_BINDING_HELPER)
                // __setModuleDefault + __importStar only needed for namespace imports
                if (needsImportStar) append(IMPORT_STAR_ONLY_HELPERS)
                if (needsExportStar) append(EXPORT_STAR_HELPER)
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

        return result
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
        if (arg !is BinaryExpression || arg.operator != SyntaxKind.BarBar) return null
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
            operator = SyntaxKind.BarBar,
            right = ParenthesizedExpression(
                expression = BinaryExpression(
                    left = exportsProp,
                    operator = SyntaxKind.Equals,
                    right = BinaryExpression(
                        left = syntheticId(name),
                        operator = SyntaxKind.Equals,
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

    private fun makeExportAssignment(name: String, value: Expression? = null): Statement {
        return ExpressionStatement(
            expression = BinaryExpression(
                left = PropertyAccessExpression(
                    expression = syntheticId("exports"),
                    name = syntheticId(name),
                    pos = -1, end = -1,
                ),
                operator = SyntaxKind.Equals,
                right = value ?: syntheticId(name),
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    private fun makeExportsProperty(name: String): Statement {
        // Emits: exports.name = name;
        return makeExportAssignment(name, syntheticId(name))
    }

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
                flags = if (useVar) SyntaxKind.VarKeyword else SyntaxKind.ConstKeyword,
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
                flags = SyntaxKind.ConstKeyword,
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
    private fun makeReExportGetter(exportName: String, sourceName: String, importedProp: String?): Statement {
        val returnExpr: Expression = if (importedProp != null) {
            PropertyAccessExpression(
                expression = syntheticId(sourceName),
                name = syntheticId(importedProp),
                pos = -1, end = -1,
            )
        } else {
            syntheticId(sourceName)
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

    private fun generateModuleTempName(moduleSpecifier: Expression): String {
        val specText = (moduleSpecifier as? StringLiteralNode)?.text ?: "module"
        val baseName = specText.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9_]"), "_")
        return "${baseName}_1"
    }

    // -----------------------------------------------------------------
    // Statement transforms
    // -----------------------------------------------------------------

    private fun transformStatements(statements: List<Statement>, atTopLevel: Boolean = false): List<Statement> {
        val result = mutableListOf<Statement>()
        for (stmt in statements) {
            val transformed = transformStatement(stmt)
            result.addAll(transformed)
            // At top level: once we see runtime output, stop preserving orphaned comments.
            if (atTopLevel && transformed.any { it !is NotEmittedStatement }) {
                hasSeenRuntimeStatement = true
            }
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
            is ExportAssignment -> listOf(
                statement.copy(expression = transformExpression(statement.expression))
            )

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
                statement.copy(expression = transformExpression(statement.expression))
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
        return listOf(
            decl.copy(
                typeParameters = null,
                parameters = transformParameters(decl.parameters),
                type = null,
                body = transformBlock(decl.body),
                modifiers = strippedModifiers,
            )
        )
    }

    // -----------------------------------------------------------------
    // Variable statement transform
    // -----------------------------------------------------------------

    private fun transformVariableStatement(stmt: VariableStatement): List<Statement> {
        // Declare modifier on the variable statement — erase, but preserve detached comments
        if (ModifierFlag.Declare in stmt.modifiers) return orphanedComments(stmt)

        val transformedList = transformVariableDeclarationList(stmt.declarationList)
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
     * Transforms an expression, stripping TypeScript-only constructs.
     * Always returns exactly one expression.
     */
    private fun transformExpression(expr: Expression): Expression {
        return when (expr) {
            // Type assertion / cast expressions: unwrap
            is AsExpression -> transformExpression(expr.expression)
            is NonNullExpression -> transformExpression(expr.expression)
            is SatisfiesExpression -> transformExpression(expr.expression)
            is TypeAssertionExpression -> transformExpression(expr.expression)

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
                } else {
                    expr.copy(expression = transformed)
                }
            }

            // Binary: recurse
            is BinaryExpression -> expr.copy(
                left = transformExpression(expr.left),
                right = transformExpression(expr.right),
            )

            // Call: strip type arguments, recurse
            is CallExpression -> expr.copy(
                expression = transformExpression(expr.expression),
                typeArguments = null,
                arguments = expr.arguments.map { transformExpression(it) },
            )

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

            // Property access: recurse
            is PropertyAccessExpression -> expr.copy(
                expression = transformExpression(expr.expression),
            )

            // Element access: recurse
            is ElementAccessExpression -> expr.copy(
                expression = transformExpression(expr.expression),
                argumentExpression = transformExpression(expr.argumentExpression),
            )

            // Arrow function: strip types
            is ArrowFunction -> expr.copy(
                typeParameters = null,
                parameters = transformParameters(expr.parameters),
                type = null,
                body = transformArrowBody(expr.body),
                modifiers = stripTypeScriptModifiers(expr.modifiers),
            )

            // Function expression: strip types
            is FunctionExpression -> expr.copy(
                typeParameters = null,
                parameters = transformParameters(expr.parameters),
                type = null,
                body = transformBlock(expr.body),
                modifiers = stripTypeScriptModifiers(expr.modifiers),
            )

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

            // Await
            is AwaitExpression -> expr.copy(
                expression = transformExpression(expr.expression),
            )

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
        // Build Object.assign() arguments
        val args = mutableListOf<Expression>()
        val pendingProps = mutableListOf<Node>()
        var firstArgSet = false

        for (prop in transformedProps) {
            if (prop is SpreadAssignment) {
                // Flush any pending non-spread properties as an object literal
                if (pendingProps.isNotEmpty()) {
                    args.add(ObjectLiteralExpression(
                        properties = pendingProps.toList(),
                        multiLine = pendingProps.size > 1 && node.multiLine,
                        hasTrailingComma = false,
                    ))
                    pendingProps.clear()
                    firstArgSet = true
                }
                val spreadExpr = prop.expression
                if (!firstArgSet) {
                    // First argument: use spread expression directly if it's an object literal,
                    // otherwise wrap in Object.assign({}, spreadExpr)
                    if (spreadExpr is ObjectLiteralExpression) {
                        args.add(spreadExpr)
                    } else {
                        args.add(makeObjectAssignCall(listOf(
                            ObjectLiteralExpression(properties = emptyList()),
                            spreadExpr,
                        )))
                    }
                    firstArgSet = true
                } else {
                    args.add(spreadExpr)
                }
            } else {
                pendingProps.add(prop)
            }
        }
        if (pendingProps.isNotEmpty()) {
            args.add(ObjectLiteralExpression(
                properties = pendingProps.toList(),
                multiLine = pendingProps.size > 1 && node.multiLine,
                hasTrailingComma = node.hasTrailingComma,
            ))
        }
        return makeObjectAssignCall(args)
    }

    private fun makeObjectAssignCall(args: List<Expression>): CallExpression {
        return CallExpression(
            expression = PropertyAccessExpression(
                expression = Identifier("Object"),
                name = Identifier("assign"),
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
        return method.copy(
            typeParameters = null,
            parameters = transformParameters(method.parameters),
            type = null,
            body = method.body?.let { transformBlock(it) },
            modifiers = stripMemberModifiers(method.modifiers),
        )
    }

    private fun transformGetAccessorElement(accessor: GetAccessor): GetAccessor {
        return accessor.copy(
            parameters = transformParameters(accessor.parameters),
            type = null,
            body = accessor.body?.let { transformBlock(it) },
            modifiers = stripMemberModifiers(accessor.modifiers),
        )
    }

    private fun transformSetAccessorElement(accessor: SetAccessor): SetAccessor {
        return accessor.copy(
            parameters = transformParameters(accessor.parameters),
            body = accessor.body?.let { transformBlock(it) },
            modifiers = stripMemberModifiers(accessor.modifiers),
        )
    }

    private fun transformArrowBody(body: Node): Node {
        return when (body) {
            is Block -> transformBlock(body)
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

    private fun transformBlock(block: Block): Block {
        return block.copy(statements = transformStatements(block.statements))
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

        // `import x = M.N` → `var x = M.N;`
        // `import x = require("mod")` → `const x = require("mod")`
        val ref = decl.moduleReference
        val isRequire = ref is ExternalModuleReference
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
                    flags = if (isRequire) SyntaxKind.ConstKeyword else SyntaxKind.VarKeyword,
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
        if (decl.isTypeOnly) return emptyList()

        // Strip type-only export specifiers
        val clause = decl.exportClause
        if (clause is NamedExports) {
            val filtered = clause.elements.filter { !it.isTypeOnly }
            if (filtered.isEmpty()) return emptyList()
            if (filtered.size != clause.elements.size) {
                return listOf(
                    decl.copy(exportClause = clause.copy(elements = filtered))
                )
            }
        }

        return listOf(decl)
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

        val transformedClass = decl.copy(
            typeParameters = null,
            heritageClauses = result.heritageClauses,
            members = result.members,
            modifiers = stripTypeScriptModifiers(decl.modifiers) - ModifierFlag.Abstract,
        )

        return listOf(transformedClass) + result.trailingStatements
    }

    private fun transformClassExpression(expr: ClassExpression): Expression {
        val result = transformClassBody(
            name = expr.name,
            typeParameters = expr.typeParameters,
            heritageClauses = expr.heritageClauses,
            members = expr.members,
            modifiers = expr.modifiers,
        )

        // Class expressions can't have trailing statements; static property initializers
        // would need special wrapping. For now, just transform the class body.
        return expr.copy(
            typeParameters = null,
            heritageClauses = result.heritageClauses,
            members = result.members,
            modifiers = stripTypeScriptModifiers(expr.modifiers) - ModifierFlag.Abstract,
        )
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
    ): ClassTransformResult {

        val isDerived = heritageClauses?.any {
            it.token == SyntaxKind.ExtendsKeyword
        } == true

        // Strip `implements` clauses, keep only `extends`; strip type arguments from extends
        val transformedHeritage = heritageClauses
            ?.filter { it.token == SyntaxKind.ExtendsKeyword }
            ?.map { clause ->
                clause.copy(
                    types = clause.types.map { ewta ->
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
            val paramName = extractIdentifierName(param.name) ?: continue
            propInitStatements.add(
                ExpressionStatement(
                    expression = BinaryExpression(
                        left = PropertyAccessExpression(
                            expression = syntheticId("this"),
                            name = Identifier(text = paramName, pos = -1, end = -1),
                            pos = -1, end = -1,
                        ),
                        operator = SyntaxKind.Equals,
                        right = Identifier(text = paramName, pos = -1, end = -1),
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                )
            )
        }

        // Instance property initializers (only when not using define semantics)
        if (!useDefineForClassFields) {
            for (prop in instanceProperties) {
                if (prop.initializer != null) {
                    val propName = extractIdentifierName(prop.name)
                    if (propName != null) {
                        propInitStatements.add(
                            ExpressionStatement(
                                expression = BinaryExpression(
                                    left = PropertyAccessExpression(
                                        expression = syntheticId("this"),
                                        name = Identifier(text = propName, pos = -1, end = -1),
                                        pos = -1, end = -1,
                                    ),
                                    operator = SyntaxKind.Equals,
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
                val existingBody = existingConstructor.body?.let { transformBlock(it) }
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

        // Build the output members list
        val outputMembers = mutableListOf<ClassElement>()

        if (useDefineForClassFields) {
            // Keep instance property declarations but strip types
            for (prop in instanceProperties) {
                outputMembers.add(
                    prop.copy(
                        type = null,
                        initializer = prop.initializer?.let { transformExpression(it) },
                        modifiers = stripMemberModifiers(prop.modifiers),
                        questionToken = false,
                        exclamationToken = false,
                    )
                )
            }
        }
        // When not using define: instance properties are moved to constructor (already done above)

        if (transformedConstructor != null) {
            outputMembers.add(transformedConstructor)
        }

        // Transform remaining members
        for (member in otherMembers) {
            val transformed = transformClassElement(member)
            if (transformed != null) {
                outputMembers.add(transformed)
            }
        }

        // Static properties with useDefineForClassFields stay as class members
        if (useDefineForClassFields) {
            for (prop in staticProperties) {
                outputMembers.add(
                    prop.copy(
                        type = null,
                        initializer = prop.initializer?.let { transformExpression(it) },
                        modifiers = stripMemberModifiers(prop.modifiers),
                        questionToken = false,
                        exclamationToken = false,
                    )
                )
            }
        }

        // Static properties without useDefineForClassFields → trailing statements
        val trailingStatements = mutableListOf<Statement>()
        if (!useDefineForClassFields && name != null) {
            for (prop in staticProperties) {
                if (prop.initializer != null) {
                    val propName = extractIdentifierName(prop.name)
                    if (propName != null) {
                        trailingStatements.add(
                            ExpressionStatement(
                                expression = BinaryExpression(
                                    left = PropertyAccessExpression(
                                        expression = Identifier(text = name.text, pos = -1, end = -1),
                                        name = Identifier(text = propName, pos = -1, end = -1),
                                        pos = -1, end = -1,
                                    ),
                                    operator = SyntaxKind.Equals,
                                    right = transformExpression(prop.initializer),
                                    pos = -1, end = -1,
                                ),
                                pos = -1, end = -1,
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
                    body = transformBlock(element.body),
                    modifiers = stripMemberModifiers(element.modifiers),
                )
            }

            is GetAccessor -> {
                element.copy(
                    parameters = transformParameters(element.parameters),
                    type = null,
                    body = element.body?.let { transformBlock(it) },
                    modifiers = stripMemberModifiers(element.modifiers),
                )
            }

            is SetAccessor -> {
                element.copy(
                    parameters = transformParameters(element.parameters),
                    body = element.body?.let { transformBlock(it) },
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
                body = transformBlock(element.body),
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
        // const enum without preserveConstEnums → remove
        if (ModifierFlag.Const in decl.modifiers && !options.preserveConstEnums) {
            return emptyList()
        }

        val enumName = decl.name.text
        val enumId = syntheticId(enumName)

        // Build IIFE body statements
        val iifeBody = mutableListOf<Statement>()
        var nextAutoValue = 0

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
                        ExpressionStatement(
                            expression = BinaryExpression(
                                left = ElementAccessExpression(
                                    expression = syntheticId(enumName),
                                    argumentExpression = memberNameExpr,
                                    pos = -1, end = -1,
                                ),
                                operator = SyntaxKind.Equals,
                                right = initExpr,
                                pos = -1, end = -1,
                            ),
                            pos = -1, end = -1,
                        )
                    } else {
                        // Numeric / expression initializer: E[E["X"] = expr] = "X"
                        val numericValue = tryEvaluateNumericLiteral(initExpr)
                        nextAutoValue = if (numericValue != null) numericValue + 1 else nextAutoValue
                        makeReverseMapStatement(enumName, memberNameExpr, initExpr)
                    }
                }

                else -> {
                    // Auto-increment numeric member
                    val valueExpr = NumericLiteralNode(
                        text = nextAutoValue.toString(),
                        pos = -1, end = -1,
                    )
                    nextAutoValue++
                    makeReverseMapStatement(enumName, memberNameExpr, valueExpr)
                }
            }
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
        val needsVarDecl = enumName !in declaredNames && enumName !in emittedVarNames
        emittedVarNames.add(enumName)
        declaredNames.add(enumName)
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
                flags = if (nested) SyntaxKind.LetKeyword else SyntaxKind.VarKeyword,
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
                            operator = SyntaxKind.Equals,
                            right = BinaryExpression(
                                left = parentProp,
                                operator = SyntaxKind.BarBar,
                                right = ParenthesizedExpression(
                                    expression = BinaryExpression(
                                        left = PropertyAccessExpression(
                                            expression = syntheticId(parentNsName),
                                            name = Identifier(text = enumName, pos = -1, end = -1),
                                            pos = -1, end = -1,
                                        ),
                                        operator = SyntaxKind.Equals,
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
                            operator = SyntaxKind.BarBar,
                            right = ParenthesizedExpression(
                                expression = BinaryExpression(
                                    left = syntheticId(enumName),
                                    operator = SyntaxKind.Equals,
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
                        operator = SyntaxKind.Equals,
                        right = valueExpr,
                        pos = -1, end = -1,
                    ),
                    pos = -1, end = -1,
                ),
                operator = SyntaxKind.Equals,
                right = memberNameExpr,
                pos = -1, end = -1,
            ),
            pos = -1, end = -1,
        )
    }

    private fun extractEnumMemberName(name: NameNode): String {
        return when (name) {
            is Identifier -> name.text
            is StringLiteralNode -> name.text
            is ComputedPropertyName -> "[computed]"
            else -> "unknown"
        }
    }

    private fun memberNameToString(name: NameNode): Expression {
        return when (name) {
            is Identifier -> StringLiteralNode(text = name.text, pos = -1, end = -1)
            is StringLiteralNode -> name
            is ComputedPropertyName -> transformExpression(name.expression)
            else -> StringLiteralNode(text = "unknown", pos = -1, end = -1)
        }
    }

    private fun isStringLiteral(expr: Expression): Boolean {
        return expr is StringLiteralNode
    }

    private fun tryEvaluateNumericLiteral(expr: Expression): Int? {
        return when (expr) {
            is NumericLiteralNode -> expr.text.toIntOrNull()
            is PrefixUnaryExpression -> {
                if (expr.operator == SyntaxKind.Minus) {
                    val inner = tryEvaluateNumericLiteral(expr.operand)
                    inner?.let { -it }
                } else null
            }

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
    ): List<Statement> {
        // Ambient (`declare`) module declarations are already handled
        // Type-only namespaces (only types inside) produce no output
        if (isTypeOnlyNamespace(decl)) return emptyList()

        val moduleName = extractIdentifierName(decl.name)
            ?: return emptyList()

        // Handle dotted namespace names: `namespace A.B { ... }` → nested
        // The parser represents A.B as ModuleDeclaration(name="A", body=ModuleDeclaration(name="B", body=...))
        // So we just handle one level at a time

        val body = decl.body
        val bodyStatements: List<Statement> = when (body) {
            is ModuleBlock -> body.statements
            is ModuleDeclaration -> {
                // Nested namespace: recursively transform the inner declaration
                // The inner namespace uses the outer name as its parent (dotted form: A.B)
                return wrapInNamespaceIife(
                    moduleName = moduleName,
                    innerStatements = transformModuleDeclaration(body, nested = true, parentNsName = moduleName),
                    outerDecl = decl,
                    nested = nested,
                    parentNsName = parentNsName,
                )
            }

            null -> return emptyList()
            else -> return emptyList()
        }

        // Each namespace body is a separate IIFE scope, so names declared inside
        // one body (e.g. `let Color;`) must not suppress declarations in sibling bodies.
        // Save and restore both sets so inner-scope names don't pollute the outer scope.
        val savedEmittedVarNames = emittedVarNames.toMutableSet()
        val savedDeclaredNames = declaredNames.toMutableSet()

        // Transform body statements, rewriting exports
        val transformedBody = transformNamespaceBody(moduleName, bodyStatements)

        emittedVarNames.clear()
        emittedVarNames.addAll(savedEmittedVarNames)
        declaredNames.clear()
        declaredNames.addAll(savedDeclaredNames)

        return wrapInNamespaceIife(moduleName, transformedBody, decl, nested = nested, parentNsName = parentNsName)
    }

    private fun wrapInNamespaceIife(
        moduleName: String,
        innerStatements: List<Statement>,
        outerDecl: ModuleDeclaration,
        nested: Boolean = false,
        parentNsName: String? = null,
    ): List<Statement> {
        val nsId = syntheticId(moduleName)

        // Skip var N; if a class/function with the same name was declared BEFORE this
        // namespace (in source order), or if a prior enum/namespace already emitted var N;
        val needsVarDecl = moduleName !in declaredNames && moduleName !in emittedVarNames
        // Track that this name now has a runtime var (for subsequent same-name dedup)
        emittedVarNames.add(moduleName)
        declaredNames.add(moduleName)

        // Inner namespace declarations use `let` (they're inside a function scope).
        // Top-level namespace declarations use `var` (for hoisting compatibility).
        val varKeyword = if (nested) SyntaxKind.LetKeyword else SyntaxKind.VarKeyword

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
                operator = SyntaxKind.Equals,
                right = BinaryExpression(
                    left = parentProp,
                    operator = SyntaxKind.BarBar,
                    right = ParenthesizedExpression(
                        expression = BinaryExpression(
                            left = PropertyAccessExpression(
                                expression = syntheticId(parentNsName),
                                name = Identifier(text = moduleName, pos = -1, end = -1),
                                pos = -1, end = -1,
                            ),
                            operator = SyntaxKind.Equals,
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
                operator = SyntaxKind.BarBar,
                right = ParenthesizedExpression(
                    expression = BinaryExpression(
                        left = syntheticId(moduleName),
                        operator = SyntaxKind.Equals,
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
                                name = Identifier(text = moduleName, pos = -1, end = -1),
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
    private fun transformNamespaceBody(
        nsName: String,
        statements: List<Statement>,
    ): List<Statement> {
        // First pass: collect exported variable names
        val exportedNames = mutableSetOf<String>()
        for (stmt in statements) {
            val isExported = when (stmt) {
                is VariableStatement -> ModifierFlag.Export in stmt.modifiers
                is FunctionDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ClassDeclaration -> ModifierFlag.Export in stmt.modifiers
                is EnumDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ModuleDeclaration -> ModifierFlag.Export in stmt.modifiers
                is ImportEqualsDeclaration -> ModifierFlag.Export in stmt.modifiers
                else -> false
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

        val result = mutableListOf<Statement>()

        for (stmt in statements) {
            // First, check for type-only statements that should be removed
            when (stmt) {
                is InterfaceDeclaration -> continue
                is TypeAliasDeclaration -> continue
                else -> {}
            }

            if (stmt is Declaration && hasDeclareModifier(stmt)) continue

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
                                operator = SyntaxKind.Equals,
                                right = value,
                            ),
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
                                    qualifyNamespaceRefs(nsName, exportedNames, transformExpression(decl.initializer))
                                assignments.add(
                                    BinaryExpression(
                                        left = PropertyAccessExpression(
                                            expression = Identifier(nsName),
                                            name = Identifier(varName),
                                        ),
                                        operator = SyntaxKind.Equals,
                                        right = init,
                                        pos = -1, end = -1,
                                    )
                                )
                            }
                        }
                        if (assignments.isNotEmpty()) {
                            // Build a comma expression if there are multiple assignments
                            val expr = assignments.reduce { acc, e ->
                                BinaryExpression(left = acc, operator = SyntaxKind.Comma, right = e, pos = -1, end = -1)
                            }
                            result.add(ExpressionStatement(expression = expr, pos = -1, end = -1))
                        }
                    } else {
                        val strippedStmt = stmt.copy(
                            modifiers = stmt.modifiers - ModifierFlag.Export,
                        )
                        val transformed = transformVariableStatement(strippedStmt)
                        result.addAll(transformed)
                    }
                }

                is FunctionDeclaration -> {
                    val strippedStmt = stmt.copy(
                        modifiers = stmt.modifiers - ModifierFlag.Export,
                    )
                    val transformed = transformFunctionDeclaration(strippedStmt)
                    result.addAll(transformed)

                    // Track function name so a subsequent same-named namespace/enum
                    // doesn't emit a duplicate var/let declaration (mirrors transformStatements).
                    stmt.name?.text?.let { declaredNames.add(it) }

                    if (isExported && stmt.name != null) {
                        result.add(makeNamespaceExportAssignment(nsName, stmt.name.text))
                    }
                }

                is ClassDeclaration -> {
                    // Qualify heritage clause references to exported members
                    val qualifiedHeritage = stmt.heritageClauses?.map { clause ->
                        clause.copy(types = clause.types.map { type ->
                            type.copy(expression = qualifyNamespaceRefs(nsName, exportedNames, type.expression))
                        })
                    }
                    val strippedStmt = stmt.copy(
                        modifiers = stmt.modifiers - ModifierFlag.Export,
                        heritageClauses = qualifiedHeritage,
                    )
                    val transformed = transformClassDeclaration(strippedStmt)
                    result.addAll(transformed)

                    // Track class name so a subsequent same-named namespace/enum
                    // doesn't emit a duplicate var/let declaration (mirrors transformStatements).
                    stmt.name?.text?.let { declaredNames.add(it) }

                    if (isExported && stmt.name != null) {
                        result.add(makeNamespaceExportAssignment(nsName, stmt.name.text))
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
                    val transformed = transformModuleDeclaration(strippedStmt, nested = true, parentNsName = parentForIife)
                    result.addAll(transformed)
                }

                else -> {
                    result.addAll(transformStatement(stmt))
                }
            }
        }

        return result
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

            is ConditionalExpression -> expr.copy(
                condition = qualifyNamespaceRefs(nsName, exportedNames, expr.condition),
                whenTrue = qualifyNamespaceRefs(nsName, exportedNames, expr.whenTrue),
                whenFalse = qualifyNamespaceRefs(nsName, exportedNames, expr.whenFalse),
            )

            is ParenthesizedExpression -> expr.copy(
                expression = qualifyNamespaceRefs(nsName, exportedNames, expr.expression),
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
                operator = SyntaxKind.Equals,
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
            is ModuleDeclaration -> isTypeOnlyNamespace(stmt)
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
        // If runtime code has already appeared, this comment is not "orphaned" in the
        // TypeScript sense — it belongs to the surrounding code context and should be
        // dropped along with the erased declaration.
        if (hasSeenRuntimeStatement) return emptyList()
        val comments = statement.leadingComments ?: return emptyList()
        // A comment is detached (preserved) if there is a blank line (>=2 newlines) between
        // the comment's end and the erased declaration's start position. This accounts for
        // the case where multiple comments appear before a declaration: only those with
        // a blank line separating them from the declaration are preserved; adjacent ones
        // are considered part of the declaration and are dropped with it.
        val stmtPos = statement.pos.coerceIn(0, sourceText.length)
        val detached = comments.filter { comment ->
            val between = sourceText.substring(
                comment.end.coerceIn(0, sourceText.length),
                stmtPos,
            )
            between.count { it == '\n' } >= 2
        }
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
            is ThrowStatement -> collectRefsFromNode(node.expression, refs)
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
            is FunctionDeclaration -> node.body?.statements?.forEach { collectRefsFromNode(it, refs) }
            is ClassDeclaration -> {
                node.heritageClauses?.forEach { hc -> hc.types.forEach { collectRefsFromNode(it.expression, refs) } }
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
                    is PropertyAssignment -> collectRefsFromNode(prop.initializer, refs)
                    is ShorthandPropertyAssignment -> { refs.add(prop.name.text); collectRefsFromNode(prop.objectAssignmentInitializer, refs) }
                    is SpreadAssignment -> collectRefsFromNode(prop.expression, refs)
                    is MethodDeclaration -> prop.body?.statements?.forEach { collectRefsFromNode(it, refs) }
                    is GetAccessor -> prop.body?.statements?.forEach { collectRefsFromNode(it, refs) }
                    is SetAccessor -> prop.body?.statements?.forEach { collectRefsFromNode(it, refs) }
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
     */
    private fun rewriteIdInStatement(stmt: Statement, map: Map<String, Expression>): Statement = when (stmt) {
        is ExpressionStatement -> stmt.copy(expression = rewriteId(stmt.expression, map))
        is VariableStatement -> stmt.copy(
            declarationList = stmt.declarationList.copy(
                declarations = stmt.declarationList.declarations.map { decl ->
                    decl.copy(initializer = decl.initializer?.let { rewriteId(it, map) })
                }
            )
        )
        is ReturnStatement -> stmt.copy(expression = stmt.expression?.let { rewriteId(it, map) })
        is ThrowStatement -> stmt.copy(expression = rewriteId(stmt.expression, map))
        is IfStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map),
            thenStatement = rewriteIdInStatement(stmt.thenStatement, map),
            elseStatement = stmt.elseStatement?.let { rewriteIdInStatement(it, map) },
        )
        is Block -> stmt.copy(statements = stmt.statements.map { rewriteIdInStatement(it, map) })
        is DoStatement -> stmt.copy(
            statement = rewriteIdInStatement(stmt.statement, map),
            expression = rewriteId(stmt.expression, map),
        )
        is WhileStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map),
            statement = rewriteIdInStatement(stmt.statement, map),
        )
        is ForStatement -> stmt.copy(
            initializer = stmt.initializer?.let { init ->
                when (init) {
                    is VariableDeclarationList -> init.copy(
                        declarations = init.declarations.map { d -> d.copy(initializer = d.initializer?.let { rewriteId(it, map) }) }
                    )
                    is Expression -> rewriteId(init, map)
                    else -> init
                }
            },
            condition = stmt.condition?.let { rewriteId(it, map) },
            incrementor = stmt.incrementor?.let { rewriteId(it, map) },
            statement = rewriteIdInStatement(stmt.statement, map),
        )
        is ForInStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map),
            statement = rewriteIdInStatement(stmt.statement, map),
        )
        is ForOfStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map),
            statement = rewriteIdInStatement(stmt.statement, map),
        )
        is SwitchStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map),
            caseBlock = stmt.caseBlock.map { clause ->
                when (clause) {
                    is CaseClause -> clause.copy(
                        expression = rewriteId(clause.expression, map),
                        statements = clause.statements.map { rewriteIdInStatement(it, map) },
                    )
                    is DefaultClause -> clause.copy(
                        statements = clause.statements.map { rewriteIdInStatement(it, map) },
                    )
                    else -> clause
                }
            }
        )
        is LabeledStatement -> stmt.copy(statement = rewriteIdInStatement(stmt.statement, map))
        is WithStatement -> stmt.copy(
            expression = rewriteId(stmt.expression, map),
            statement = rewriteIdInStatement(stmt.statement, map),
        )
        is TryStatement -> stmt.copy(
            tryBlock = stmt.tryBlock.copy(statements = stmt.tryBlock.statements.map { rewriteIdInStatement(it, map) }),
            catchClause = stmt.catchClause?.let { cc ->
                cc.copy(block = cc.block.copy(statements = cc.block.statements.map { rewriteIdInStatement(it, map) }))
            },
            finallyBlock = stmt.finallyBlock?.let { fb ->
                fb.copy(statements = fb.statements.map { rewriteIdInStatement(it, map) })
            },
        )
        else -> stmt
    }

    /**
     * Rewrites identifier references in an expression using [map].
     * Only rewrites identifiers in value position; property names, computed keys, etc. are left alone.
     */
    private fun rewriteId(expr: Expression, map: Map<String, Expression>): Expression = when (expr) {
        is Identifier -> map[expr.text] ?: expr
        is PropertyAccessExpression -> expr.copy(expression = rewriteId(expr.expression, map))
        is ElementAccessExpression -> expr.copy(
            expression = rewriteId(expr.expression, map),
            argumentExpression = rewriteId(expr.argumentExpression, map),
        )
        is CallExpression -> expr.copy(
            expression = rewriteId(expr.expression, map),
            arguments = expr.arguments.map { rewriteId(it, map) },
        )
        is NewExpression -> expr.copy(
            expression = rewriteId(expr.expression, map),
            arguments = expr.arguments?.map { rewriteId(it, map) },
        )
        is BinaryExpression -> expr.copy(
            left = rewriteId(expr.left, map),
            right = rewriteId(expr.right, map),
        )
        is ConditionalExpression -> expr.copy(
            condition = rewriteId(expr.condition, map),
            whenTrue = rewriteId(expr.whenTrue, map),
            whenFalse = rewriteId(expr.whenFalse, map),
        )
        is ParenthesizedExpression -> expr.copy(expression = rewriteId(expr.expression, map))
        is PrefixUnaryExpression -> expr.copy(operand = rewriteId(expr.operand, map))
        is PostfixUnaryExpression -> expr.copy(operand = rewriteId(expr.operand, map))
        is DeleteExpression -> expr.copy(expression = rewriteId(expr.expression, map))
        is TypeOfExpression -> expr.copy(expression = rewriteId(expr.expression, map))
        is VoidExpression -> expr.copy(expression = rewriteId(expr.expression, map))
        is AwaitExpression -> expr.copy(expression = rewriteId(expr.expression, map))
        is YieldExpression -> expr.copy(expression = expr.expression?.let { rewriteId(it, map) })
        is SpreadElement -> expr.copy(expression = rewriteId(expr.expression, map))
        is ArrayLiteralExpression -> expr.copy(elements = expr.elements.map { rewriteId(it, map) })
        is ObjectLiteralExpression -> expr.copy(
            properties = expr.properties.map { prop ->
                when (prop) {
                    is PropertyAssignment -> prop.copy(initializer = rewriteId(prop.initializer, map))
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
                    is SpreadAssignment -> prop.copy(expression = rewriteId(prop.expression, map))
                    else -> prop
                }
            }
        )
        is TaggedTemplateExpression -> expr.copy(tag = rewriteId(expr.tag, map))
        is TemplateExpression -> expr.copy(
            templateSpans = expr.templateSpans.map { span ->
                span.copy(expression = rewriteId(span.expression, map))
            }
        )
        is ArrowFunction -> expr.copy(body = when (val b = expr.body) {
            is Block -> b.copy(statements = b.statements.map { rewriteIdInStatement(it, map) })
            is Expression -> rewriteId(b, map)
            else -> b
        })
        is FunctionExpression -> expr.copy(
            body = expr.body.copy(statements = expr.body.statements.map { rewriteIdInStatement(it, map) })
        )
        is NonNullExpression -> expr.copy(expression = rewriteId(expr.expression, map))
        else -> expr
    }

    private fun isTypeErasureNode(expr: Expression): Boolean =
        expr is TypeAssertionExpression || expr is AsExpression
                || expr is NonNullExpression || expr is SatisfiesExpression

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
        is ObjectLiteralExpression -> true
        is FunctionExpression, is ClassExpression -> true
        is ArrowFunction -> true
        is NewExpression -> expr.arguments == null
        is PrefixUnaryExpression -> true
        is TypeOfExpression, is VoidExpression, is DeleteExpression -> true
        is AwaitExpression, is YieldExpression -> true
        else -> false
    }

    companion object {
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
        val IMPORT_STAR_ONLY_HELPERS = """var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
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

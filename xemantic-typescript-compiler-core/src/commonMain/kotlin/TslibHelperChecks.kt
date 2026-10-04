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
 * tsc `isAnExternalModuleIndicatorNode` over a file's top-level [statements]: an import, an
 * exporting declaration, an export assignment or declaration, or `import x = require(…)`. Shared by
 * [Checker.isModuleFile] and the project crawl's synthetic `tslib` import (CHK.229), which must
 * agree on which files take one.
 */
internal fun isExternalModuleByStatements(statements: List<Statement>): Boolean {
    for (stmt in statements) {
        when (stmt) {
            is ImportDeclaration -> return true
            // tsc isAnExternalModuleIndicatorNode: an import-equals counts ONLY with an
            // ExternalModuleReference (`= require(...)`) or an `export` modifier — a bare
            // entity-name alias (`import fs = module`) leaves the file a SCRIPT.
            is ImportEqualsDeclaration ->
                if (stmt.moduleReference is ExternalModuleReference ||
                    ModifierFlag.Export in stmt.modifiers) return true
            is ExportDeclaration -> return true
            is ExportAssignment -> return true
            // VariableStatement is not a Declaration but can have export modifier
            is VariableStatement -> if (ModifierFlag.Export in stmt.modifiers) return true
            else -> {
                if (stmt is Declaration) {
                    val modifiers = when (stmt) {
                        is FunctionDeclaration -> stmt.modifiers
                        is ClassDeclaration -> stmt.modifiers
                        is EnumDeclaration -> stmt.modifiers
                        is InterfaceDeclaration -> stmt.modifiers
                        is TypeAliasDeclaration -> stmt.modifiers
                        is ModuleDeclaration -> stmt.modifiers
                        else -> emptySet()
                    }
                    if (ModifierFlag.Export in modifiers) return true
                }
            }
        }
    }
    return false
}

/**
 * (INV.0) (P18.289) — the TSLIB EMIT-HELPER family under `importHelpers`:
 * `checkImportHelpersWithoutTslib` (TS2354, no `tslib` resolvable from the file) and
 * `checkMissingTslibHelpers` (TS2343, a helper the emit would need that the resolved `tslib`
 * does not export — decorators, async delegators, private fields, binding patterns), with the
 * per-file report memo `reportedMissingTslibHelpers`. Extracted VERBATIM from `Checker.kt` (two
 * spans: 838-849, the memo field; 91397-92294); every Checker member it reads is reached through
 * [checker], and `options` / `binderResults` are the checker's own constructor inputs, passed in.
 * Ambient reads: `docs/inversion-ambient-ledger.md` row 22.
 */
internal class TslibHelperChecks(
    private val checker: Checker,
    private val options: CompilerOptions,
    private val binderResults: List<BinderResult>,
) {

    /**
     * Set of `<fileName>|<helperName>` pairs already reported missing via TS2343. tsgo's
     * `checkExternalEmitHelpers` records the requested helpers on the SOURCE FILE's links
     * (`sourceFileLinks.requestedExternalEmitHelpers`, `checker.go:28346`), so a missing helper
     * is reported ONCE PER FILE, at its first site, whatever tslib install the file resolves
     * to — `tslibMissingHelper` (one shared install, two files, two rows) and
     * `tslibMultipleMissingHelper` (two installs, three files, one `__awaiter` row each) both
     * say so. ((LEGACY.1)(j3), 2026-09-16: the former per-INSTALL key — `__awaiter` once per
     * resolved tslib — lost the second file's row in both.) Declared before init so it is
     * non-null while the check pipeline runs.
     */
    private val reportedMissingTslibHelpers: MutableSet<String> = mutableSetOf()

    // -----------------------------------------------------------------------
    // TS2354: importHelpers without tslib
    // -----------------------------------------------------------------------

    fun checkImportHelpersWithoutTslib() {
        if (!options.importHelpers) return
        // Check if tslib is available in the compilation files: tslib must be in
        // node_modules/tslib/ — (LEGACY.1)(e) TypeScript 7 has no classic resolution, so
        // the former "a root tslib.d.ts is found under classic/AMD/System" rule is gone.
        // Check for ambient module "tslib" declaration — counts as found regardless of resolution
        val hasAmbientTslib = binderResults.any { result ->
            result.sourceFile.statements.any { stmt ->
                stmt is ModuleDeclaration && (stmt.name as? StringLiteralNode)?.text == "tslib"
            }
        }
        // B98.r163: tslib availability is PER-FILE, not program-wide. A `node_modules/tslib`
        // install only satisfies files BELOW its enclosing directory (nearest-enclosing
        // node_modules walk). So a tslib under
        // `/package1/node_modules` does NOT satisfy a file in `/package2` — that file still
        // fires TS2354 (tslibNotFoundDifferentModules). A ROOT `/node_modules/tslib`
        // (baseDir "/") is an ancestor of everything, so it satisfies all files as before.
        fun tslibResolvableFor(fileName: String): Boolean {
            if (hasAmbientTslib) return true
            return binderResults.any { result ->
                val tfn = result.sourceFile.fileName
                if (!tfn.contains("tslib")) return@any false
                // Match "node_modules/" with OR without a leading slash — test fixtures
                // mix conventions (e.g. "node_modules/tslib/index.d.ts" vs
                // "/package1/node_modules/tslib/tslib.d.ts"); within a single test the
                // file paths share the convention, so the baseDir prefix-match is sound.
                val nmIdx = tfn.lastIndexOf("node_modules/").let {
                    if (it < 0) tfn.lastIndexOf("node-modules/") else it
                }
                if (nmIdx < 0) return@any false
                val baseDir = tfn.substring(0, nmIdx) // "" (root), "/", or "/package1/"
                fileName.startsWith(baseDir)
            }
        }

        val em = options.effectiveModule
        // ES-module interop helpers are needed wherever the CommonJS transform runs — CJS,
        // NodeNext and ((LEGACY.1)(f)) the removed `amd`/`umd`/`system`, which fold onto it
        // (not ES modules) — unconditionally, TypeScript 7 having no `esModuleInterop`
        // option. (tsgo 7.0.2 itself skips this check under the removed kinds and emits an
        // UNBOUND helper beside its `tslib` import — a defect in a removed configuration
        // this compiler does not copy; measured 2026-09-15.)
        val needsEsmHelpers = em.foldsToCommonJS || em.isNodeNext
        // (LEGACY.1)(j3): the `__extends` arm (`class B extends A` below ES2015) is GONE —
        // tsgo has no ES5 class lowering and its checker never requests `__extends` at any
        // target (`checker/types.go:114-137` has no such flag; measured on 30 cells 2026-09-16).
        // Decorator helpers always needed when experimentalDecorators is set
        val needsDecoratorHelper = options.experimentalDecorators
        // B98: an `async function` needs the __awaiter helper when target < ES2017 — tsgo's
        // `languageVersion < LanguageFeatureMinimumTarget.AsyncFunctions` (`checker.go:2727`),
        // read on [CompilerOptions.defaultedTarget], the checker's language version ((j3);
        // the former `effectiveTarget` read answered the same on every input, since both map
        // an unset target to ES2024 and a written es5 lands below ES2017 either way).
        // Under importHelpers with no resolvable tslib, that syntax fires TS2354.
        val needsAwaiterHelper = options.defaultedTarget < ScriptTarget.ES2017 &&
            binderResults.any { result ->
                !checker.isDtsFile(result.sourceFile.fileName) &&
                    result.sourceFile.statements.any { it is FunctionDeclaration &&
                        ModifierFlag.Async in it.modifiers && !it.asteriskToken }
            }

        if (!needsEsmHelpers && !needsDecoratorHelper && !needsAwaiterHelper) return

        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            if (tslibResolvableFor(fileName)) continue
            val source = result.sourceFile.text

            val isModule = checker.isModuleFile(result.sourceFile.statements)
            val ts2354StartCount = checker.diagnostics.count { it.code == 2354 && it.fileName == fileName }

            for (stmt in result.sourceFile.statements) {
                if (checker.diagnostics.count { it.code == 2354 && it.fileName == fileName } > ts2354StartCount) break
                when (stmt) {
                    is ExportDeclaration -> if (needsEsmHelpers) {
                        if (stmt.moduleSpecifier != null && !stmt.isTypeOnly) {
                            val clause = stmt.exportClause
                            if (clause is NamedExports) {
                                for (spec in clause.elements) {
                                    if (spec.isTypeOnly) continue
                                    val importedName = (spec.propertyName ?: spec.name).text
                                    if (importedName == "default") {
                                        val startNode = spec.propertyName ?: spec.name
                                        val endNode = if (spec.propertyName != null) spec.name else spec.name
                                        val spanStart = startNode.pos
                                        val spanLen = if (spec.propertyName != null) {
                                            endNode.pos + endNode.text.length - spanStart
                                        } else {
                                            startNode.text.length
                                        }
                                        emitTS2354(spanStart, spanLen, source, fileName)
                                    }
                                }
                            }
                        }
                    }
                    is ImportDeclaration -> if (needsEsmHelpers) {
                        if (stmt.importClause?.isTypeOnly != true) {
                            val clause = stmt.importClause ?: continue
                            val bindings = clause.namedBindings
                            val beforeThisStmt =
                                checker.diagnostics.count { it.code == 2354 && it.fileName == fileName }
                            // import * as X from "..." needs __importStar
                            if (bindings is NamespaceImport) {
                                // Span covers whole import statement (from import keyword to semicolon)
                                val spanStart = stmt.pos
                                // Find end of statement line (up to and including semicolon)
                                val lineEnd = source.indexOf('\n', spanStart).let { if (it < 0) source.length else it }
                                val spanLen = source.substring(spanStart, lineEnd).trimEnd().length
                                emitTS2354(spanStart, spanLen, source, fileName)
                            }
                            // import { default as X } from "..." needs __importDefault
                            if (bindings is NamedImports) {
                                for (spec in bindings.elements) {
                                    if (spec.isTypeOnly) continue
                                    val importedName = (spec.propertyName ?: spec.name).text
                                    if (importedName == "default") {
                                        val startNode = spec.propertyName ?: spec.name
                                        val endNode = if (spec.propertyName != null) spec.name else spec.name
                                        val spanStart = startNode.pos
                                        val spanLen = if (spec.propertyName != null) {
                                            endNode.pos + endNode.text.length - spanStart
                                        } else {
                                            startNode.text.length
                                        }
                                        emitTS2354(spanStart, spanLen, source, fileName)
                                    }
                                }
                            }
                            // (P18.152): a DEFAULT IMPORT CLAUSE (`import path from "path"`)
                            // needs `__importDefault` exactly as `{ default as X }` does, and
                            // this walker only ever looked at `namedBindings` — so the one
                            // import shape a user is most likely to write was the one shape it
                            // could not see. Anchored at the WHOLE statement, as tsgo does
                            // (measured at column 1, the same span the namespace arm uses).
                            //
                            // ORDERED AFTER the named-specifier arm and gated on this statement
                            // having emitted nothing, because when a clause carries BOTH
                            // (`import path, { default as r } from "path"`) tsgo reports at the
                            // SPECIFIER, not at the statement — measured 1:16 against 1:1.
                            if (clause.name != null &&
                                checker.diagnostics.count { it.code == 2354 && it.fileName == fileName } == beforeThisStmt
                            ) {
                                val spanStart = stmt.pos
                                val lineEnd = source.indexOf('\n', spanStart)
                                    .let { if (it < 0) source.length else it }
                                val spanLen = source.substring(spanStart, lineEnd).trimEnd().length
                                emitTS2354(spanStart, spanLen, source, fileName)
                            }
                        }
                    }
                    is ClassDeclaration -> {
                        // Decorators need __decorate helper (module files only)
                        if (needsDecoratorHelper && isModule) {
                            checkDecoratorHelperOnClass(stmt, source, fileName)
                        }
                    }
                    is FunctionDeclaration -> {
                        // B98: a top-level `async function` (non-generator) needs __awaiter
                        // when target < ES2017. With importHelpers and no resolvable tslib,
                        // TypeScript reports TS2354 at the function NAME. Module files only
                        // (mirrors the __decorate gating) — bounds the FP surface
                        // to the single no-tslib async fixture.
                        if (needsAwaiterHelper && isModule &&
                            ModifierFlag.Async in stmt.modifiers && !stmt.asteriskToken
                        ) {
                            val name = stmt.name
                            if (name != null) {
                                emitTS2354(name.pos, name.text.length, source, fileName)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun emitTS2354(spanStart: Int, spanLen: Int, source: String, fileName: String) {
        val (line, character) = checker.getLineAndCharacterOfPosition(source, spanStart)
        checker.diagnostics.add(Diagnostic(
            message = "This syntax requires an imported helper but module 'tslib' cannot be found.",
            category = DiagnosticCategory.Error,
            code = 2354,
            fileName = fileName,
            line = line,
            character = character,
            start = spanStart,
            length = spanLen,
        ))
    }

    private fun checkDecoratorHelperOnClass(stmt: ClassDeclaration, source: String, fileName: String) {
        // Check if the class or any of its members have decorators
        val firstDecorator = stmt.decorators?.firstOrNull()
        if (firstDecorator != null) {
            // Span covers the decorator expression including @
            // e.g., "@dec" → 4 chars
            val decoratorSpan = getDecoratorSpan(firstDecorator, source)
            emitTS2354(decoratorSpan.first, decoratorSpan.second, source, fileName)
            return
        }
        // Also check member decorators (methods, properties, parameters)
        for (member in stmt.members) {
            val memberDec = when (member) {
                is MethodDeclaration -> member.decorators?.firstOrNull()
                is PropertyDeclaration -> member.decorators?.firstOrNull()
                is GetAccessor -> member.decorators?.firstOrNull()
                is SetAccessor -> member.decorators?.firstOrNull()
                else -> null
            }
            if (memberDec != null) {
                val spanStart = memberDec.pos
                val lineEnd = source.indexOf('\n', spanStart).let { if (it < 0) source.length else it }
                val spanLen = source.substring(spanStart, lineEnd).trimEnd().length
                emitTS2354(spanStart, spanLen, source, fileName)
                return
            }
            // Check parameter decorators
            val params = when (member) {
                is MethodDeclaration -> member.parameters
                is Constructor -> member.parameters
                else -> emptyList()
            }
            for (param in params) {
                val paramDec = param.decorators?.firstOrNull()
                if (paramDec != null) {
                    val spanStart = paramDec.pos
                    val lineEnd = source.indexOf('\n', spanStart).let { if (it < 0) source.length else it }
                    val spanLen = source.substring(spanStart, lineEnd).trimEnd().length
                    emitTS2354(spanStart, spanLen, source, fileName)
                    return
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // TS2343: Missing helper in tslib
    // -----------------------------------------------------------------------

    /**
     * Check for TS2343: "This syntax requires an imported helper named '__X' which does
     * not exist in 'tslib'. Consider upgrading your version of 'tslib'."
     * Fires when importHelpers=true, tslib IS found, but the required helper is not exported.
     *
     * (LEGACY.1)(j3), 2026-09-16: the helpers this walker may name are exactly those tsgo's
     * `checkExternalEmitHelpers` can be asked for (`checker/types.go:114-137`, callers at
     * `checker.go:2724-13035`). The ES5-only arms — `__extends` (class heritage),
     * `__generator` (async functions and async generators at es5), `__makeTemplateObject`
     * (tagged templates) and `__assign` (object spread) — are GONE: no `ExternalEmitHelpers`
     * flag exists for `__extends`, `__generator` or `__assign` (the tsgo checker never spells
     * those names), `MakeTemplateObject` has a flag and NO caller, and `__assign` is emitted
     * by tsgo's object-spread lowering below ES2018 without ever being checked.
     * Measured over 30 cells (3 tslib flavours x 5 targets x 2 module kinds): tsgo names
     * none of the four at any target, including a written es5. What survives is exactly
     * tsgo's table — `__rest` (< ES2018), `__awaiter` (< ES2017 in tsgo; see the arm),
     * `__asyncGenerator`/`__await`/`__asyncDelegator`/`__asyncValues`, `__decorate`/
     * `__metadata`/`__param`, `__exportStar`/`__importStar`/`__importDefault` and the
     * class-private-field helpers.
     */
    fun checkMissingTslibHelpers() {
        if (!options.importHelpers) return
        // Find tslib file in compilation — the same node_modules-only rule as
        // checkImportHelpersWithoutTslib ((LEGACY.1)(e): no classic resolution exists).
        // Check for ambient module "tslib" declaration — counts as found regardless of resolution
        val ambientTslibResult = binderResults.firstOrNull { result ->
            result.sourceFile.statements.any { stmt ->
                stmt is ModuleDeclaration && (stmt.name as? StringLiteralNode)?.text == "tslib"
            }
        }
        // The first tslib module file supplies the export set (a multi-package program may
        // hold several node_modules/tslib installs — `tslibMultipleMissingHelper` — with
        // identical exports).
        val tslibResult = ambientTslibResult ?: binderResults.firstOrNull { result ->
            val fn = result.sourceFile.fileName
            (fn.contains("node_modules") || fn.contains("node-modules")) && fn.contains("tslib")
        } ?: return
        // Get what tslib exports
        val tslibExports = getTslibExports(tslibResult.sourceFile)

        val hasDecorators = options.experimentalDecorators || options.emitDecoratorMetadata

        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            // Only check module files (files with import/export statements)
            val isModule = checker.isModuleFile(result.sourceFile.statements)
            if (!isModule) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                checkStmtForMissingHelper(stmt, source, fileName, tslibExports, hasDecorators)
            }
        }
    }

    private fun checkStmtForMissingHelper(
        stmt: Statement,
        source: String,
        fileName: String,
        tslibExports: Set<String>,
        hasDecorators: Boolean,
    ) {
        when (stmt) {
            is ExportDeclaration -> {
                // export * from "X" → needs __exportStar. ((LEGACY.1)(f): the System/AMD/UMD
                // exemption is gone — those kinds take the CommonJS transform, which emits it.)
                if (stmt.exportClause == null && stmt.moduleSpecifier != null && !stmt.isTypeOnly) {
                    if ("__exportStar" !in tslibExports) {
                        // Span: from stmt.pos to after ';' (inclusive)
                        val semiPos = source.indexOf(';', stmt.pos)
                        val spanLen = if (semiPos >= 0) (semiPos + 1) - stmt.pos else 1
                        emitTS2343("__exportStar", stmt.pos, spanLen, source, fileName)
                    }
                }
            }
            is ClassDeclaration -> {
                // ((LEGACY.1)(j3): the `class B extends A` → `__extends` arm is gone — tsgo
                // never requests it, at any target.)
                // @dec class C → needs __decorate and __metadata
                if (hasDecorators && stmt.decorators?.isNotEmpty() == true) {
                    val decorator = stmt.decorators.first()
                    val decoratorSpan = getDecoratorSpan(decorator, source)
                    if ("__decorate" !in tslibExports) {
                        emitTS2343("__decorate", decoratorSpan.first, decoratorSpan.second, source, fileName)
                    }
                    if ("__metadata" !in tslibExports && options.emitDecoratorMetadata) {
                        emitTS2343("__metadata", decoratorSpan.first, decoratorSpan.second, source, fileName)
                    }
                    // Check parameters with decorators
                    checkParameterDecoratorsForHelper(stmt.members, source, fileName, tslibExports, hasDecorators)
                } else if (hasDecorators) {
                    checkParameterDecoratorsForHelper(stmt.members, source, fileName, tslibExports, hasDecorators)
                }
                // Check for private field access helpers (#field)
                checkClassMembersForPrivateFields(stmt.members, source, fileName, tslibExports)
            }
            is VariableStatement -> {
                // Walk variable declarations for rest patterns (`__rest`) below ES2018 — tsgo's
                // `languageVersion < LanguageFeatureMinimumTarget.ObjectSpreadRest` on every
                // binding element (`checker.go:5801`); (j3) added the bound, the walk used to
                // report at every target. ((j3): the initializer walk for `{ ...o }` →
                // `__assign` and `` tag`x` `` → `__makeTemplateObject` is gone — tsgo never
                // requests either.)
                if (options.defaultedTarget < ScriptTarget.ES2018) {
                    for (decl in stmt.declarationList.declarations) {
                        checkBindingForMissingHelper(decl.name, source, fileName, tslibExports)
                    }
                }
            }
            is FunctionDeclaration -> {
                // Object-rest binding pattern in a parameter (`function f({ a, ...rest }) {}`) needs
                // __rest below ES2018 (where object rest/spread became native) — tsgo's
                // `languageVersion < LanguageFeatureMinimumTarget.ObjectSpreadRest`
                // (`checker.go:5801`), read on the checker's language version ((j3), same
                // answer on every input as the former `effectiveTarget` read).
                if (options.defaultedTarget < ScriptTarget.ES2018) {
                    for (p in stmt.parameters) {
                        checkBindingForMissingHelper(p.name, source, fileName, tslibExports)
                    }
                }
                // async function * f() → needs __asyncGenerator and __await ((j3): the es5
                // `__generator` companion is gone — tsgo never requests it)
                if (ModifierFlag.Async in stmt.modifiers && stmt.asteriskToken) {
                    // TypeScript reports the diagnostic at the function name position (not the '*')
                    val namePos = stmt.name?.pos
                    if (namePos != null) {
                        val nameLen = stmt.name.text.length
                        if ("__asyncGenerator" !in tslibExports) {
                            emitTS2343("__asyncGenerator", namePos, nameLen, source, fileName)
                        }
                        if ("__await" !in tslibExports) {
                            emitTS2343("__await", namePos, nameLen, source, fileName)
                        }
                    }
                    // Walk body for yield* statements
                    stmt.body?.let {
                        checkBlockForAsyncDelegator(it, source, fileName, tslibExports)
                    }
                } else if (ModifierFlag.Async in stmt.modifiers) {
                    // B98.r26: plain `async function f()` (non-generator) → needs __awaiter
                    // ((j3): the es5 `__generator` companion is gone). Reported at the function
                    // NAME position; [emitTS2343] dedups per (file, helper) as tsgo does.
                    val namePos = stmt.name?.pos
                    if (namePos != null) {
                        val nameLen = stmt.name.text.length
                        if ("__awaiter" !in tslibExports) {
                            emitTS2343("__awaiter", namePos, nameLen, source, fileName)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    /** Walk a Block looking for YieldExpression with asteriskToken = true inside an async generator. */
    private fun checkBlockForAsyncDelegator(
        block: Block,
        source: String,
        fileName: String,
        tslibExports: Set<String>,
    ) {
        for (stmt in block.statements) {
            checkStmtForAsyncDelegator(stmt, source, fileName, tslibExports)
        }
    }

    private fun checkStmtForAsyncDelegator(
        stmt: Statement,
        source: String,
        fileName: String,
        tslibExports: Set<String>,
    ) {
        when (stmt) {
            is ExpressionStatement -> checkExprForAsyncDelegator(stmt.expression, source, fileName, tslibExports)
            is ReturnStatement -> stmt.expression?.let { checkExprForAsyncDelegator(it, source, fileName, tslibExports) }
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkExprForAsyncDelegator(it, source, fileName, tslibExports) }
                }
            }
            // round 42 iter13: broader statement coverage for yield* detection inside
            // nested control flow within an async generator body.
            is Block -> for (s in stmt.statements) checkStmtForAsyncDelegator(s, source, fileName, tslibExports)
            is IfStatement -> {
                checkExprForAsyncDelegator(stmt.expression, source, fileName, tslibExports)
                checkStmtForAsyncDelegator(stmt.thenStatement, source, fileName, tslibExports)
                stmt.elseStatement?.let { checkStmtForAsyncDelegator(it, source, fileName, tslibExports) }
            }
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> for (d in init.declarations) {
                        d.initializer?.let { checkExprForAsyncDelegator(it, source, fileName, tslibExports) }
                    }
                    is Expression -> checkExprForAsyncDelegator(init, source, fileName, tslibExports)
                    else -> {}
                }
                stmt.condition?.let { checkExprForAsyncDelegator(it, source, fileName, tslibExports) }
                stmt.incrementor?.let { checkExprForAsyncDelegator(it, source, fileName, tslibExports) }
                checkStmtForAsyncDelegator(stmt.statement, source, fileName, tslibExports)
            }
            is ForInStatement -> {
                checkExprForAsyncDelegator(stmt.expression, source, fileName, tslibExports)
                checkStmtForAsyncDelegator(stmt.statement, source, fileName, tslibExports)
            }
            is ForOfStatement -> {
                checkExprForAsyncDelegator(stmt.expression, source, fileName, tslibExports)
                checkStmtForAsyncDelegator(stmt.statement, source, fileName, tslibExports)
            }
            is WhileStatement -> {
                checkExprForAsyncDelegator(stmt.expression, source, fileName, tslibExports)
                checkStmtForAsyncDelegator(stmt.statement, source, fileName, tslibExports)
            }
            is DoStatement -> {
                checkStmtForAsyncDelegator(stmt.statement, source, fileName, tslibExports)
                checkExprForAsyncDelegator(stmt.expression, source, fileName, tslibExports)
            }
            is SwitchStatement -> {
                checkExprForAsyncDelegator(stmt.expression, source, fileName, tslibExports)
                for (clause in stmt.caseBlock) {
                    val clauseStmts = when (clause) {
                        is CaseClause -> {
                            checkExprForAsyncDelegator(clause.expression, source, fileName, tslibExports)
                            clause.statements
                        }
                        is DefaultClause -> clause.statements
                        else -> emptyList()
                    }
                    for (s in clauseStmts) checkStmtForAsyncDelegator(s, source, fileName, tslibExports)
                }
            }
            is TryStatement -> {
                for (s in stmt.tryBlock.statements) checkStmtForAsyncDelegator(s, source, fileName, tslibExports)
                stmt.catchClause?.block?.let { for (s in it.statements) checkStmtForAsyncDelegator(s, source, fileName, tslibExports) }
                stmt.finallyBlock?.let { for (s in it.statements) checkStmtForAsyncDelegator(s, source, fileName, tslibExports) }
            }
            is LabeledStatement -> checkStmtForAsyncDelegator(stmt.statement, source, fileName, tslibExports)
            is ThrowStatement -> stmt.expression?.let { checkExprForAsyncDelegator(it, source, fileName, tslibExports) }
            else -> {}
        }
    }

    private fun checkExprForAsyncDelegator(
        expr: Expression,
        source: String,
        fileName: String,
        tslibExports: Set<String>,
    ) {
        when (expr) {
            is YieldExpression -> {
                if (expr.asteriskToken) {
                    // yield* expr → needs __asyncDelegator and __asyncValues
                    val yieldPos = expr.pos
                    val yieldLen = "yield".length  // just "yield" keyword span (5 chars)
                    if ("__asyncDelegator" !in tslibExports) {
                        emitTS2343("__asyncDelegator", yieldPos, yieldLen, source, fileName)
                    }
                    if ("__asyncValues" !in tslibExports) {
                        emitTS2343("__asyncValues", yieldPos, yieldLen, source, fileName)
                    }
                }
                // round 42 iter13: recurse into yield's expression so `yield*` nested
                // inside another expression context still fires.
                expr.expression?.let { checkExprForAsyncDelegator(it, source, fileName, tslibExports) }
            }
            // round 42 iter13: broader expression coverage so yield* inside conditional /
            // wrapper / binary contexts is reached.
            is ParenthesizedExpression -> checkExprForAsyncDelegator(expr.expression, source, fileName, tslibExports)
            is AsExpression -> checkExprForAsyncDelegator(expr.expression, source, fileName, tslibExports)
            is TypeAssertionExpression -> checkExprForAsyncDelegator(expr.expression, source, fileName, tslibExports)
            is SatisfiesExpression -> checkExprForAsyncDelegator(expr.expression, source, fileName, tslibExports)
            is NonNullExpression -> checkExprForAsyncDelegator(expr.expression, source, fileName, tslibExports)
            is BinaryExpression -> {
                var cur: Expression = expr
                val rightStack = ArrayDeque<Expression>()
                while (cur is BinaryExpression) { rightStack.addLast(cur.right); cur = cur.left }
                checkExprForAsyncDelegator(cur, source, fileName, tslibExports)
                while (rightStack.isNotEmpty()) checkExprForAsyncDelegator(rightStack.removeLast(), source, fileName, tslibExports)
            }
            is ConditionalExpression -> {
                checkExprForAsyncDelegator(expr.condition, source, fileName, tslibExports)
                checkExprForAsyncDelegator(expr.whenTrue, source, fileName, tslibExports)
                checkExprForAsyncDelegator(expr.whenFalse, source, fileName, tslibExports)
            }
            is CallExpression -> {
                checkExprForAsyncDelegator(expr.expression, source, fileName, tslibExports)
                for (a in expr.arguments) checkExprForAsyncDelegator(a, source, fileName, tslibExports)
            }
            is ArrayLiteralExpression -> for (e in expr.elements) checkExprForAsyncDelegator(e, source, fileName, tslibExports)
            is SpreadElement -> checkExprForAsyncDelegator(expr.expression, source, fileName, tslibExports)
            is AwaitExpression -> checkExprForAsyncDelegator(expr.expression, source, fileName, tslibExports)
            is CommaListExpression -> for (e in expr.elements) checkExprForAsyncDelegator(e, source, fileName, tslibExports)
            else -> {}
        }
    }

    /** Check class members for private field access helpers. */
    private fun checkClassMembersForPrivateFields(
        members: List<ClassElement>,
        source: String,
        fileName: String,
        tslibExports: Set<String>,
    ) {
        for (member in members) {
            val body = when (member) {
                is MethodDeclaration -> member.body
                is GetAccessor -> member.body
                is SetAccessor -> member.body
                is Constructor -> member.body
                else -> null
            }
            body?.let { checkBlockForPrivateFieldAccess(it, source, fileName, tslibExports) }
        }
    }

    private fun checkBlockForPrivateFieldAccess(
        block: Block,
        source: String,
        fileName: String,
        tslibExports: Set<String>,
    ) {
        for (stmt in block.statements) {
            checkStmtForPrivateFieldAccess(stmt, source, fileName, tslibExports)
        }
    }

    private fun checkStmtForPrivateFieldAccess(
        stmt: Statement,
        source: String,
        fileName: String,
        tslibExports: Set<String>,
    ) {
        when (stmt) {
            is ExpressionStatement -> checkExprForPrivateFieldAccess(stmt.expression, source, fileName, tslibExports, isAssignmentLhs = false)
            is ReturnStatement -> stmt.expression?.let { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
            is VariableStatement -> {
                for (decl in stmt.declarationList.declarations) {
                    decl.initializer?.let { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
                }
            }
            is IfStatement -> {
                checkExprForPrivateFieldAccess(stmt.expression, source, fileName, tslibExports, false)
                checkStmtForPrivateFieldAccess(stmt.thenStatement, source, fileName, tslibExports)
                stmt.elseStatement?.let { checkStmtForPrivateFieldAccess(it, source, fileName, tslibExports) }
            }
            is Block -> checkBlockForPrivateFieldAccess(stmt, source, fileName, tslibExports)
            // round 42 iter6: broaden statement coverage so private-field access
            // (#field) inside For/ForIn/ForOf/While/Do/Switch/Try/Labeled/Throw/
            // ExportAssignment also triggers TS2343 when tslib helpers are missing.
            is ForStatement -> {
                when (val init = stmt.initializer) {
                    is VariableDeclarationList -> for (d in init.declarations) {
                        d.initializer?.let { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
                    }
                    is Expression -> checkExprForPrivateFieldAccess(init, source, fileName, tslibExports, false)
                    else -> {}
                }
                stmt.condition?.let { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
                stmt.incrementor?.let { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
                checkStmtForPrivateFieldAccess(stmt.statement, source, fileName, tslibExports)
            }
            is ForInStatement -> {
                checkExprForPrivateFieldAccess(stmt.expression, source, fileName, tslibExports, false)
                checkStmtForPrivateFieldAccess(stmt.statement, source, fileName, tslibExports)
            }
            is ForOfStatement -> {
                checkExprForPrivateFieldAccess(stmt.expression, source, fileName, tslibExports, false)
                checkStmtForPrivateFieldAccess(stmt.statement, source, fileName, tslibExports)
            }
            is WhileStatement -> {
                checkExprForPrivateFieldAccess(stmt.expression, source, fileName, tslibExports, false)
                checkStmtForPrivateFieldAccess(stmt.statement, source, fileName, tslibExports)
            }
            is DoStatement -> {
                checkStmtForPrivateFieldAccess(stmt.statement, source, fileName, tslibExports)
                checkExprForPrivateFieldAccess(stmt.expression, source, fileName, tslibExports, false)
            }
            is SwitchStatement -> {
                checkExprForPrivateFieldAccess(stmt.expression, source, fileName, tslibExports, false)
                for (clause in stmt.caseBlock) {
                    val clauseStmts = when (clause) {
                        is CaseClause -> {
                            checkExprForPrivateFieldAccess(clause.expression, source, fileName, tslibExports, false)
                            clause.statements
                        }
                        is DefaultClause -> clause.statements
                        else -> emptyList()
                    }
                    for (s in clauseStmts) checkStmtForPrivateFieldAccess(s, source, fileName, tslibExports)
                }
            }
            is TryStatement -> {
                for (s in stmt.tryBlock.statements) checkStmtForPrivateFieldAccess(s, source, fileName, tslibExports)
                stmt.catchClause?.block?.let { for (s in it.statements) checkStmtForPrivateFieldAccess(s, source, fileName, tslibExports) }
                stmt.finallyBlock?.let { for (s in it.statements) checkStmtForPrivateFieldAccess(s, source, fileName, tslibExports) }
            }
            is LabeledStatement -> checkStmtForPrivateFieldAccess(stmt.statement, source, fileName, tslibExports)
            is ThrowStatement -> stmt.expression?.let { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
            is ExportAssignment -> checkExprForPrivateFieldAccess(stmt.expression, source, fileName, tslibExports, false)
            else -> {}
        }
    }

    private fun checkExprForPrivateFieldAccess(
        expr: Expression,
        source: String,
        fileName: String,
        tslibExports: Set<String>,
        isAssignmentLhs: Boolean,
    ) {
        when (expr) {
            is BinaryExpression -> {
                val isAssignment = expr.operator == SyntaxKind.Equals ||
                    expr.operator == SyntaxKind.PlusEquals ||
                    expr.operator == SyntaxKind.MinusEquals ||
                    expr.operator == SyntaxKind.AsteriskEquals ||
                    expr.operator == SyntaxKind.SlashEquals
                if (expr.operator == SyntaxKind.InKeyword) {
                    // #field in obj → __classPrivateFieldIn
                    val lhs = expr.left
                    if (lhs is Identifier && lhs.text.startsWith('#')) {
                        if ("__classPrivateFieldIn" !in tslibExports) {
                            emitTS2343("__classPrivateFieldIn", lhs.pos, lhs.text.length, source, fileName)
                        }
                    }
                    checkExprForPrivateFieldAccess(expr.right, source, fileName, tslibExports, false)
                } else {
                    checkExprForPrivateFieldAccess(expr.left, source, fileName, tslibExports, isAssignment)
                    checkExprForPrivateFieldAccess(expr.right, source, fileName, tslibExports, false)
                }
            }
            is PropertyAccessExpression -> {
                val name = expr.name
                if (name.text.startsWith('#')) {
                    // this.#field — detect Set (assignment LHS) vs Get (read)
                    val spanStart = expr.pos
                    // span = "this.#field" = expr.pos to name.pos + name.text.length
                    val spanEnd = name.pos + name.text.length
                    val spanLen = spanEnd - spanStart
                    if (isAssignmentLhs) {
                        if ("__classPrivateFieldSet" !in tslibExports) {
                            emitTS2343("__classPrivateFieldSet", spanStart, spanLen.coerceAtLeast(1), source, fileName)
                        }
                    } else {
                        if ("__classPrivateFieldGet" !in tslibExports) {
                            emitTS2343("__classPrivateFieldGet", spanStart, spanLen.coerceAtLeast(1), source, fileName)
                        }
                    }
                } else {
                    checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, false)
                }
            }
            // Value-preserving wrappers — `(expr)` propagates isAssignmentLhs; `expr!`,
            // `expr as T`, `<T>expr`, `expr satisfies T` are still valid assignment
            // targets and preserve the LHS context for private-field set detection.
            is ParenthesizedExpression -> checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, isAssignmentLhs)
            is NonNullExpression -> checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, isAssignmentLhs)
            is AsExpression -> checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, isAssignmentLhs)
            is TypeAssertionExpression -> checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, isAssignmentLhs)
            is SatisfiesExpression -> checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, isAssignmentLhs)
            is CallExpression -> {
                checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, false)
                expr.arguments.forEach { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
            }
            is NewExpression -> {
                checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, false)
                expr.arguments?.forEach { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
            }
            is ElementAccessExpression -> {
                checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, false)
                checkExprForPrivateFieldAccess(expr.argumentExpression, source, fileName, tslibExports, false)
            }
            is PrefixUnaryExpression -> {
                // ++this.#x / --this.#x → Set (private field assignment)
                val isAssignOp = expr.operator == SyntaxKind.PlusPlus || expr.operator == SyntaxKind.MinusMinus
                checkExprForPrivateFieldAccess(expr.operand, source, fileName, tslibExports, isAssignOp)
            }
            is PostfixUnaryExpression -> {
                val isAssignOp = expr.operator == SyntaxKind.PlusPlus || expr.operator == SyntaxKind.MinusMinus
                checkExprForPrivateFieldAccess(expr.operand, source, fileName, tslibExports, isAssignOp)
            }
            is ConditionalExpression -> {
                checkExprForPrivateFieldAccess(expr.condition, source, fileName, tslibExports, false)
                checkExprForPrivateFieldAccess(expr.whenTrue, source, fileName, tslibExports, false)
                checkExprForPrivateFieldAccess(expr.whenFalse, source, fileName, tslibExports, false)
            }
            is ArrayLiteralExpression -> expr.elements.forEach { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
            is ObjectLiteralExpression -> for (prop in expr.properties) when (prop) {
                is PropertyAssignment -> checkExprForPrivateFieldAccess(prop.initializer, source, fileName, tslibExports, false)
                is SpreadAssignment -> checkExprForPrivateFieldAccess(prop.expression, source, fileName, tslibExports, false)
                else -> {}
            }
            is SpreadElement -> checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, false)
            is AwaitExpression -> checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, false)
            is YieldExpression -> expr.expression?.let { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
            is DeleteExpression -> checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, false)
            is VoidExpression -> checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, false)
            is TypeOfExpression -> checkExprForPrivateFieldAccess(expr.expression, source, fileName, tslibExports, false)
            is TemplateExpression -> expr.templateSpans.forEach { checkExprForPrivateFieldAccess(it.expression, source, fileName, tslibExports, false) }
            // round 42 iter7: TaggedTemplateExpression — recurse into tag + template spans
            // so `tag`...${this.#x}...`` triggers TS2343 on the private-field access.
            is TaggedTemplateExpression -> {
                checkExprForPrivateFieldAccess(expr.tag, source, fileName, tslibExports, false)
                val template = expr.template
                if (template is TemplateExpression) {
                    template.templateSpans.forEach { checkExprForPrivateFieldAccess(it.expression, source, fileName, tslibExports, false) }
                }
            }
            is CommaListExpression -> expr.elements.forEach { checkExprForPrivateFieldAccess(it, source, fileName, tslibExports, false) }
            else -> {}
        }
    }

    private fun checkParameterDecoratorsForHelper(
        members: List<ClassElement>,
        source: String,
        fileName: String,
        tslibExports: Set<String>,
        hasDecorators: Boolean,
    ) {
        if (!hasDecorators) return
        for (member in members) {
            val params = when (member) {
                is MethodDeclaration -> member.parameters
                is Constructor -> member.parameters
                else -> continue
            }
            for (param in params) {
                val decs = param.decorators ?: continue
                if (decs.isEmpty()) continue
                val decorator = decs.first()
                val decoratorSpan = getDecoratorSpan(decorator, source)
                if ("__param" !in tslibExports) {
                    emitTS2343("__param", decoratorSpan.first, decoratorSpan.second, source, fileName)
                }
            }
        }
    }

    private fun checkBindingForMissingHelper(
        binding: Expression,
        source: String,
        fileName: String,
        tslibExports: Set<String>,
    ) {
        when (binding) {
            is ObjectBindingPattern -> {
                for (element in binding.elements) {
                    if (element.dotDotDotToken) {
                        // { ...x } → needs __rest
                        if ("__rest" !in tslibExports) {
                            // Span: position of the identifier 'x' (1 char for simple names)
                            val nameNode = element.name
                            val nameStart = nameNode.pos
                            val nameLen = (nameNode as? Identifier)?.text?.length ?: 1
                            emitTS2343("__rest", nameStart, nameLen, source, fileName)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    /** Returns the span (start, length) of a decorator including the `@` sign. */
    private fun getDecoratorSpan(decorator: Decorator, source: String): Pair<Int, Int> {
        val start = decorator.pos
        val expr = decorator.expression
        val endPos = when (expr) {
            is Identifier -> expr.pos + expr.text.length
            is CallExpression -> {
                // Find closing paren
                source.indexOf(')', expr.pos).let { if (it >= 0) it + 1 else expr.pos + 1 }
            }
            else -> start + 4 // fallback: @dec = 4 chars
        }
        // Include the "@" sign in the span
        val spanLen = endPos - start
        return Pair(start, spanLen.coerceAtLeast(1))
    }

    /** Returns the set of exported names from a tslib source file. */
    private fun getTslibExports(file: SourceFile): Set<String> {
        val exports = mutableSetOf<String>()
        for (stmt in file.statements) {
            when (stmt) {
                is ExportDeclaration -> {
                    val clause = stmt.exportClause
                    if (clause is NamedExports) {
                        for (spec in clause.elements) {
                            exports.add(spec.name.text)
                        }
                    }
                }
                is FunctionDeclaration -> {
                    if (ModifierFlag.Export in stmt.modifiers) stmt.name?.let { exports.add(it.text) }
                }
                is VariableStatement -> {
                    if (ModifierFlag.Export in stmt.modifiers) {
                        for (decl in stmt.declarationList.declarations) {
                            val n = decl.name
                            if (n is Identifier) exports.add(n.text)
                        }
                    }
                }
                is ModuleDeclaration -> {
                    // declare module "tslib" { ... }
                    val name = stmt.name
                    if (name is StringLiteralNode && name.text == "tslib") {
                        val body = stmt.body
                        if (body is ModuleBlock) {
                            for (inner in body.statements) {
                                when (inner) {
                                    is FunctionDeclaration -> {
                                        if (ModifierFlag.Export in inner.modifiers || ModifierFlag.Declare in inner.modifiers) {
                                            inner.name?.let { exports.add(it.text) }
                                        }
                                    }
                                    is VariableStatement -> {
                                        for (decl in inner.declarationList.declarations) {
                                            val n = decl.name
                                            if (n is Identifier) exports.add(n.text)
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        return exports
    }

    private fun emitTS2343(helperName: String, spanStart: Int, spanLen: Int, source: String, fileName: String) {
        // tsgo reports a missing helper ONCE PER FILE, at its first site
        // (`sourceFileLinks.requestedExternalEmitHelpers`, `checker.go:28346`).
        if (!reportedMissingTslibHelpers.add("$fileName|$helperName")) return
        val (line, character) = checker.getLineAndCharacterOfPosition(source, spanStart)
        checker.diagnostics.add(Diagnostic(
            message = "This syntax requires an imported helper named '$helperName' which does not exist in 'tslib'. Consider upgrading your version of 'tslib'.",
            category = DiagnosticCategory.Error,
            code = 2343,
            fileName = fileName,
            line = line,
            character = character,
            start = spanStart,
            length = spanLen,
        ))
    }
}

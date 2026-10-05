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
 * (INV.0) (P18.303) — the CIRCULARITY family (TS2303 / TS2310 / TS2313 / TS2456 / TS2506 / TS2577 /
 * TS2751 / TS4109 / TS4110): the TS2303 circular import-alias passes
 * (`checkCircularImportAlias`, `checkCircularExportEqualsImportAlias`,
 * `checkExportAsNamespaceSelfCycle`) and the circular base / type-alias passes
 * (`checkCircularBaseClasses` TS2506 with its ambient dry run `populateAmbientCyclicBaseClasses`,
 * `checkCircularInterfaceBases`, `checkCircularBaseTypeReferences`, `checkCircularTypeAlias`,
 * `checkCircularClassBaseViaDefaultTypeArg`), with their statement walkers and
 * the helpers [classHasCircularBase] (also used by `Checker.collectFuncDecls` and
 * `Checker.cmamCheckLiteralAndNewReceiver`), [aliasStatementSpanEnd] and [emitTS2303At] (also used
 * by `Checker.importAliasReExportSites` / `Checker.checkUnresolvedInImportEquals`). Extracted
 * VERBATIM from `Checker.kt` (four spans: 89245-89615; 134843-135471, 135496-135542 and
 * 135573-135852 — the generic helpers `matchClosingBracket` and `typeNodeContainsName` between
 * them STAYED, they have callers outside the family); every Checker member it reads is reached
 * through [checker], and `binderResults` is the checker's own constructor parameter, passed in.
 * Ambient reads: `docs/inversion-ambient-ledger.md` row 25.
 */
internal class CircularityChecks(
    private val checker: Checker,
    private val binderResults: List<BinderResult>,
) {

    // -----------------------------------------------------------------------
    // TS2303: Circular definition of import alias
    // -----------------------------------------------------------------------

    fun checkCircularImportAlias() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkCircularAliasInStmts(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkCircularAliasInStmts(stmts: List<Statement>, source: String, fileName: String) {
        // Collect import= declarations with identifier references within this scope
        val importMap = mutableMapOf<String, ImportEqualsDeclaration>()
        for (stmt in stmts) {
            if (stmt is ImportEqualsDeclaration && stmt.moduleReference is Identifier) {
                importMap[stmt.name.text] = stmt
            }
        }

        // For each import, follow the chain to detect cycles
        val reported = mutableSetOf<String>()
        for ((name, decl) in importMap) {
            if (name in reported) continue
            val visited = mutableListOf(name)
            var current = (decl.moduleReference as Identifier).text
            while (current in importMap && current !in reported) {
                if (current in visited) {
                    // Cycle detected — TypeScript 7 (tsgo) reports at EVERY alias
                    // declaration on the cycle, where tsc 6 reported only the first
                    // ((LEGACY.0b) F6d, measured: `import A = B; import B = A` is two
                    // rows under tsgo and one under tsc 6 — the same split as F2's
                    // TS2300-at-both-duplicate-declarations).
                    val cycleStart = visited.indexOf(current)
                    for (i in cycleStart until visited.size) {
                        val cycleName = visited[i]
                        val cycleDecl = importMap[cycleName] ?: continue
                        if (cycleName in reported) continue
                        reported.add(cycleName)
                        val start = cycleDecl.pos
                        var spanStart = start
                        while (spanStart < source.length && source[spanStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) spanStart++
                        // Find the semicolon at end of statement
                        val semiIdx = source.indexOf(';', spanStart)
                        val spanEnd = if (semiIdx in spanStart..cycleDecl.end) semiIdx + 1 else {
                            var e = cycleDecl.end
                            while (e > spanStart && e <= source.length && source[e - 1].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) e--
                            e
                        }
                        val length = (spanEnd - spanStart).coerceAtLeast(1)
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, spanStart)
                        checker.diagnostics.add(Diagnostic(
                            message = "Circular definition of import alias '$cycleName'.",
                            category = DiagnosticCategory.Error,
                            code = 2303,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = spanStart,
                            length = length,
                        ))
                    }
                    break
                }
                visited.add(current)
                val nextDecl = importMap[current] ?: break
                current = (nextDecl.moduleReference as Identifier).text
            }
        }

        // Recurse into namespaces / function bodies / class member bodies / nested blocks
        for (stmt in stmts) {
            when (stmt) {
                is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let { checkCircularAliasInStmts(it.statements, source, fileName) }
                is FunctionDeclaration -> stmt.body?.let { checkCircularAliasInStmts(it.statements, source, fileName) }
                is ClassDeclaration -> {
                    for (member in stmt.members) {
                        when (member) {
                            is MethodDeclaration -> member.body?.let { checkCircularAliasInStmts(it.statements, source, fileName) }
                            is Constructor -> member.body?.let { checkCircularAliasInStmts(it.statements, source, fileName) }
                            is GetAccessor -> member.body?.let { checkCircularAliasInStmts(it.statements, source, fileName) }
                            is SetAccessor -> member.body?.let { checkCircularAliasInStmts(it.statements, source, fileName) }
                            else -> {}
                        }
                    }
                }
                is Block -> checkCircularAliasInStmts(stmt.statements, source, fileName)
                is IfStatement -> {
                    checkCircularAliasInStmts(listOf(stmt.thenStatement), source, fileName)
                    stmt.elseStatement?.let { checkCircularAliasInStmts(listOf(it), source, fileName) }
                }
                is ForStatement -> checkCircularAliasInStmts(listOf(stmt.statement), source, fileName)
                is ForInStatement -> checkCircularAliasInStmts(listOf(stmt.statement), source, fileName)
                is ForOfStatement -> checkCircularAliasInStmts(listOf(stmt.statement), source, fileName)
                is WhileStatement -> checkCircularAliasInStmts(listOf(stmt.statement), source, fileName)
                is DoStatement -> checkCircularAliasInStmts(listOf(stmt.statement), source, fileName)
                is SwitchStatement -> {
                    for (clause in stmt.caseBlock) {
                        when (clause) {
                            is CaseClause -> checkCircularAliasInStmts(clause.statements, source, fileName)
                            is DefaultClause -> checkCircularAliasInStmts(clause.statements, source, fileName)
                            else -> {}
                        }
                    }
                }
                is TryStatement -> {
                    checkCircularAliasInStmts(stmt.tryBlock.statements, source, fileName)
                    stmt.catchClause?.block?.let { checkCircularAliasInStmts(it.statements, source, fileName) }
                    stmt.finallyBlock?.let { checkCircularAliasInStmts(it.statements, source, fileName) }
                }
                is LabeledStatement -> checkCircularAliasInStmts(listOf(stmt.statement), source, fileName)
                else -> {}
            }
        }
    }

    // -----------------------------------------------------------------------
    // TS2303: Circular definition of import alias — `export =`/`require` cycle
    // -----------------------------------------------------------------------
    //
    // Handles the shape `import self = require("M"); export = self;` (an
    // ExternalModuleReference, distinct from `checkCircularImportAlias` which
    // only handles entity-name `import X = Y.Z` references). The self-import
    // re-exports module M; if following the `require → export = self` chain
    // (across ambient `declare module "X"` blocks AND real file modules) loops
    // back to a previously-visited module, the alias definition is circular.
    //
    // Report position (matches TypeScript): the cycle is "entered" at the
    // module that some NON-cycle file imports (the entry). For an AMBIENT entry
    // the error lands on the entry module's self-import; for a FILE entry it
    // lands on the entry's predecessor in the cycle (the self-import whose
    // `require` points back to the entry).

    private class SelfExportModule(
        val key: String,
        val importNode: ImportEqualsDeclaration,
        /** The `export = NAME` statement that re-exports [importNode] — the SECOND
         *  alias declaration on the cycle, which TypeScript 7 also reports ((LEGACY.0b) F6d). */
        val exportNode: ExportAssignment,
        val source: String,
        val fileName: String,
        val nextSpec: String,
        val contextFileName: String,
    )

    /** If [statements] (a file's top-level or an ambient module body) contains
     *  `export = NAME` where NAME is an `import NAME = require("spec")`
     *  declaration in the same scope, return a [SelfExportModule] describing
     *  that re-export edge; otherwise null. */
    private fun buildSelfExportModule(
        statements: List<Statement>,
        key: String,
        fileName: String,
        source: String,
    ): SelfExportModule? {
        val exportEq = statements.filterIsInstance<ExportAssignment>().firstOrNull { it.isExportEquals } ?: return null
        val exprName = (exportEq.expression as? Identifier)?.text ?: return null
        val imp = statements.filterIsInstance<ImportEqualsDeclaration>().firstOrNull {
            it.name.text == exprName && it.moduleReference is ExternalModuleReference
        } ?: return null
        val spec = ((imp.moduleReference as ExternalModuleReference).expression as? StringLiteralNode)?.text ?: return null
        return SelfExportModule(key, imp, exportEq, source, fileName, spec, fileName)
    }

    fun checkCircularExportEqualsImportAlias() {
        // Collect all ambient module names (to route bare specifiers to ambient keys).
        val ambientNames = mutableSetOf<String>()
        for (result in binderResults) {
            for (stmt in result.sourceFile.statements) {
                if (stmt is ModuleDeclaration && stmt.name is StringLiteralNode) {
                    ambientNames.add((stmt.name).text)
                }
            }
        }

        // Collect self-export modules (file-level + ambient bodies), insertion order = declaration order.
        val modules = LinkedHashMap<String, SelfExportModule>()
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            val source = result.sourceFile.text
            buildSelfExportModule(result.sourceFile.statements, "file:$fileName", fileName, source)?.let { modules[it.key] = it }
            for (stmt in result.sourceFile.statements) {
                if (stmt is ModuleDeclaration && stmt.name is StringLiteralNode) {
                    val body = stmt.body as? ModuleBlock ?: continue
                    val key = "ambient:" + (stmt.name).text
                    buildSelfExportModule(body.statements, key, fileName, source)?.let { modules[it.key] = it }
                }
            }
        }
        if (modules.isEmpty()) return

        fun nextKey(m: SelfExportModule): String? {
            if (m.nextSpec in ambientNames) return "ambient:" + m.nextSpec
            val resolved = checker.resolveModuleSpecifierRelative(m.nextSpec, m.contextFileName)
                ?: checker.resolveModuleSpecifier(m.nextSpec)
            return if (resolved != null) "file:$resolved" else null
        }

        val emittedCycles = mutableSetOf<Set<String>>()
        for ((startKey, _) in modules) {
            val path = mutableListOf<String>()
            var cur: String? = startKey
            while (cur != null && cur in modules) {
                if (cur in path) {
                    val idx = path.indexOf(cur)
                    val cycleKeys = path.subList(idx, path.size).toList()
                    val cycle = cycleKeys.toSet()
                    if (cycle in emittedCycles) break
                    emittedCycles.add(cycle)
                    // (LEGACY.0b) F6d: TypeScript 7 reports at EVERY alias declaration on
                    // the cycle — for each participating module BOTH its self-import and
                    // the `export = NAME` that re-exports it (measured against tsgo 7.0.2
                    // on `recursiveExportAssignmentAndFindAliasedType1..6`, where a
                    // three-module cycle prints six rows). tsc 6 reported ONE row, at the
                    // cycle member some non-cycle file imports, which is why the previous
                    // implementation carried an entry-point heuristic here.
                    for (k in cycleKeys) modules[k]?.let { emitTS2303ForExportEqualsCycle(it) }
                    break
                }
                path.add(cur)
                cur = nextKey(modules[cur]!!)
            }
        }
    }

    /** Emit the TWO TS2303 rows a cycle member contributes: one on the self-import
     *  `import NAME = require("...")` (squiggle from the declaration to the closing `)`
     *  of `require(...)`, plus a trailing `;`) and one on the `export = NAME` that
     *  re-exports it (squiggle over the whole statement, `;` included).
     *
     *  Both are alias DECLARATIONS of the same name, and TypeScript 7 reports every alias
     *  declaration on a cycle ((LEGACY.0b) F6d, measured against tsgo 7.0.2). */
    private fun emitTS2303ForExportEqualsCycle(m: SelfExportModule) {
        val source = m.source
        val name = m.importNode.name.text
        var spanStart = m.importNode.pos
        while (spanStart < source.length && source[spanStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) spanStart++
        val refExpr = (m.importNode.moduleReference as ExternalModuleReference).expression
        val refPos = (refExpr as? StringLiteralNode)?.pos ?: spanStart
        val closeParen = source.indexOf(')', refPos)
        var spanEnd = if (closeParen >= spanStart) closeParen + 1 else m.importNode.end
        // Include a trailing `;` if it immediately follows the `require(...)` (TypeScript
        // squiggles the whole statement including the semicolon).
        var probe = spanEnd
        while (probe < source.length && source[probe].let { it == ' ' || it == '\t' }) probe++
        if (probe < source.length && source[probe] == ';') spanEnd = probe + 1
        emitTS2303At(name, spanStart, spanEnd, source, m.fileName)
        // The `export = NAME` half.
        val exp = m.exportNode
        var expStart = exp.pos
        while (expStart < source.length && source[expStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) expStart++
        emitTS2303At(name, expStart, aliasStatementSpanEnd(exp.expression, source), source, m.fileName)
    }

    /** End offset of an alias-declaring statement whose payload is [expression]: the end of
     *  the expression's own text plus an immediately following `;`. Deliberately NOT
     *  `Statement.end`, which is the end of the token AFTER the statement (CLAUDE.md). */
    fun aliasStatementSpanEnd(expression: Node?, source: String): Int {
        val end = when (expression) {
            is Identifier -> expression.pos + expression.text.length
            null -> return 0
            else -> expression.end
        }
        var probe = end
        while (probe < source.length && source[probe].let { it == ' ' || it == '\t' }) probe++
        return if (probe < source.length && source[probe] == ';') probe + 1 else end
    }

    /** The one TS2303 emitter: `Circular definition of import alias '<name>'.` over
     *  `[spanStart, spanEnd)` of [source]. */
    fun emitTS2303At(name: String, spanStart: Int, spanEnd: Int, source: String, fileName: String) {
        val length = (spanEnd - spanStart).coerceAtLeast(1)
        val (line, character) = checker.getLineAndCharacterOfPosition(source, spanStart)
        checker.diagnostics.add(Diagnostic(
            message = "Circular definition of import alias '$name'.",
            category = DiagnosticCategory.Error,
            code = 2303,
            fileName = fileName,
            line = line,
            character = character,
            start = spanStart,
            length = length,
        ))
    }

    /**
     * TS2303: `export = X` + `export as namespace X` where X is NOT declared module-locally
     * (only as a `declare global { namespace X {} }` member) forms a circular UMD self-alias —
     * the UMD global X is the module export, and `export = X` resolves X back through the global
     * scope to the very name the UMD declaration is defining. "Circular definition of import
     * alias 'X'." reported over the whole `export as namespace X;` statement.
     *
     * The parser misparses `export as namespace X` (no AST node — see scanner/parser gotchas), so
     * the UMD side is found by a source regex; the `export =` side via the AST.
     *
     * FP firewall (triple gate): fires ONLY when (a) the file has `export = X` (Identifier target),
     * (b) a same-name `export as namespace X`, AND (c) X has NO module-local declaration (import /
     * local namespace / var / class / function / interface / type / enum) outside `declare global`.
     * The common valid UMD pattern `export = Foo; export as namespace Foo;` where Foo is a real
     * module-local value (`declare namespace Foo {...}` / `import * as Foo`) fails gate (c), so it
     * never fires (verified against all same-name corpus fixtures — only the all-global target trips it).
     */
    fun checkExportAsNamespaceSelfCycle() {
        val umdRegex = Regex("""(?m)^[ \t]*(export[ \t]+as[ \t]+namespace[ \t]+([A-Za-z_$][A-Za-z0-9_$]*)[ \t]*;?)""")
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (!checker.isDtsFile(fileName)) continue
            val statements = result.sourceFile.statements
            val source = result.sourceFile.text
            // (a) `export = X` with an Identifier target.
            val exportEq = statements.asSequence()
                .filterIsInstance<ExportAssignment>()
                .firstOrNull { it.isExportEquals && it.expression is Identifier }
                ?: continue
            val exportEqName = (exportEq.expression as Identifier).text
            // (b) `export as namespace X` with the same name.
            val match = umdRegex.findAll(source).firstOrNull { it.groups[2]?.value == exportEqName } ?: continue
            val grp = match.groups[1]!!
            // (c) X has no module-local declaration outside `declare global`. The parser misparses
            // `export as namespace X` itself into a `namespace X` ModuleDeclaration (+ stray
            // statements) — exclude any statement that falls within the UMD declaration's source
            // span so the misparse isn't mistaken for a genuine local declaration of X.
            if (declaresNameModuleLocally(statements, exportEqName, grp.range)) continue
            // (LEGACY.0b) F6d: BOTH halves of the cycle are alias declarations and
            // TypeScript 7 reports both (measured on `exportAsNamespaceConflict`, where
            // tsgo prints `/a.d.ts(2,1)` for `export = N;` and `/a.d.ts(3,1)` for
            // `export as namespace N;`). tsc 6 printed only the UMD one.
            var eqStart = exportEq.pos
            while (eqStart < source.length && source[eqStart].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) eqStart++
            emitTS2303At(exportEqName, eqStart, aliasStatementSpanEnd(exportEq.expression, source), source, fileName)
            val pos = grp.range.first
            emitTS2303At(exportEqName, pos, pos + grp.value.length, source, fileName)
        }
    }

    /** True if [name] is declared at the top level (outside any `declare global` block) as an
     * import, local namespace, variable, class, function, interface, type alias, or enum.
     * Statements whose start position falls within [excludeRange] are skipped (used to ignore the
     * misparsed `export as namespace X` statements that masquerade as a `namespace X` declaration). */
    private fun declaresNameModuleLocally(statements: List<Statement>, name: String, excludeRange: IntRange): Boolean {
        for (stmt in statements) {
            if (stmt.pos in excludeRange) continue
            when (stmt) {
                is ImportEqualsDeclaration -> if ((stmt.name).text == name) return true
                is ImportDeclaration -> {
                    val clause = stmt.importClause ?: continue
                    if (clause.name?.text == name) return true
                    when (val nb = clause.namedBindings) {
                        is NamespaceImport -> if (nb.name.text == name) return true
                        is NamedImports -> if (nb.elements.any { it.name.text == name }) return true
                        else -> {}
                    }
                }
                is ModuleDeclaration -> {
                    val nm = (stmt.name as? Identifier)?.text
                    if (nm == name && nm != "global") return true
                }
                is VariableStatement ->
                    if (stmt.declarationList.declarations.any { (it.name as? Identifier)?.text == name }) return true
                is FunctionDeclaration -> if ((stmt.name)?.text == name) return true
                is ClassDeclaration -> if ((stmt.name)?.text == name) return true
                is InterfaceDeclaration -> if ((stmt.name).text == name) return true
                is TypeAliasDeclaration -> if ((stmt.name).text == name) return true
                is EnumDeclaration -> if ((stmt.name).text == name) return true
                else -> {}
            }
        }
        return false
    }

    // -----------------------------------------------------------------------
    // TS2506: 'X' is referenced directly or indirectly in its own base expression
    // -----------------------------------------------------------------------

    fun checkCircularBaseClasses() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkCircularBaseInStatements(result.sourceFile.statements, source, fileName,
                inAmbientNamespace = false, namespacePath = emptyList())
        }
    }

    /** B18.3 v3: dry-run of [checkCircularBaseClasses] for the AMBIENT-namespace
     *  case only — populates [ambientCyclicBaseClassNamesByFile] so TS2449 can
     *  be suppressed for those classes. Runs BEFORE [checkUseBeforeDeclaration].
     *  The main [checkCircularBaseClasses] still runs later to emit TS2506. */
    fun populateAmbientCyclicBaseClasses() {
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            collectAmbientCyclicNamesIn(result.sourceFile.statements, fileName,
                inAmbientNamespace = false, namespacePath = emptyList())
        }
    }

    private fun collectAmbientCyclicNamesIn(
        statements: List<Statement>, fileName: String,
        inAmbientNamespace: Boolean, namespacePath: List<String>,
    ) {
        if (inAmbientNamespace) {
            // Collect class extends targets in this scope (qualified or bare).
            val classExtends = mutableMapOf<String, String>()
            for (stmt in statements) {
                if (stmt !is ClassDeclaration) continue
                val name = stmt.name?.text ?: continue
                val extendsClause = stmt.heritageClauses?.firstOrNull {
                    it.token == SyntaxKind.ExtendsKeyword
                } ?: continue
                val baseExpr = extendsClause.types.firstOrNull()?.expression ?: continue
                val baseName = resolveBaseNameInNamespace(baseExpr, namespacePath) ?: continue
                classExtends[name] = baseName
            }
            for ((className, _) in classExtends) {
                if (chainCyclesBackTo(className, classExtends)) {
                    checker.ambientCyclicBaseClassNamesByFile.getOrPut(fileName) { mutableSetOf() }.add(className)
                }
            }
        }
        // Recurse into nested modules, extending namespace path.
        for (stmt in statements) {
            if (stmt is ModuleDeclaration) {
                val body = stmt.body
                if (body is ModuleBlock) {
                    val newPath = appendNamespacePath(namespacePath, stmt.name)
                    val nestedAmbient = inAmbientNamespace || ModifierFlag.Declare in stmt.modifiers
                    collectAmbientCyclicNamesIn(body.statements, fileName,
                        nestedAmbient, newPath)
                }
            }
        }
    }

    private fun chainCyclesBackTo(className: String, classExtends: Map<String, String>): Boolean {
        val visited = mutableSetOf<String>()
        var current: String? = className
        while (current != null) {
            if (!visited.add(current)) return current == className
            current = classExtends[current]
        }
        return false
    }

    private fun resolveBaseNameInNamespace(baseExpr: Expression, namespacePath: List<String>): String? {
        return when (baseExpr) {
            is Identifier -> baseExpr.text
            is PropertyAccessExpression -> {
                // For qualified extends like `NS.Sub.C`, only treat as in-scope when
                // the qualifier matches the enclosing namespace path exactly.
                // Otherwise (e.g. `A.B.Base.W` from a DIFFERENT namespace) skip —
                // see `declFileWithClassNameConflictingWithClassReferredByExtendsClause_ts`.
                val parts = mutableListOf<String>()
                var cur: Expression = baseExpr
                while (cur is PropertyAccessExpression) {
                    parts.add(0, cur.name.text)
                    cur = cur.expression
                }
                if (cur !is Identifier) return null
                val tail = parts.removeAt(parts.lastIndex)
                val qualifier = listOf(cur.text) + parts
                if (qualifier == namespacePath) tail else null
            }
            else -> null
        }
    }

    private fun appendNamespacePath(path: List<String>, name: Expression): List<String> {
        val parts = mutableListOf<String>()
        var cur: Expression = name
        while (cur is PropertyAccessExpression) {
            parts.add(0, cur.name.text)
            cur = cur.expression
        }
        if (cur is Identifier) parts.add(0, cur.text)
        return path + parts
    }

    private fun checkCircularBaseInStatements(
        statements: List<Statement>, source: String, fileName: String,
        inAmbientNamespace: Boolean, namespacePath: List<String>,
    ) {
        // Collect all class names and their extends targets in this scope
        val classExtends = mutableMapOf<String, String>() // className → baseClassName
        val classDecls = mutableMapOf<String, ClassDeclaration>()
        for (stmt in statements) {
            when (stmt) {
                is ClassDeclaration -> {
                    val name = stmt.name?.text ?: continue
                    classDecls[name] = stmt
                    val extendsClause = stmt.heritageClauses?.firstOrNull {
                        it.token == SyntaxKind.ExtendsKeyword
                    } ?: continue
                    val baseExpr = extendsClause.types.firstOrNull()?.expression ?: continue
                    // B18.3 v3: bare identifier always counts; qualified extends only
                    // counts inside an ambient namespace whose path matches the qualifier.
                    val baseName: String? = when (baseExpr) {
                        is Identifier -> baseExpr.text
                        is PropertyAccessExpression ->
                            if (inAmbientNamespace) resolveBaseNameInNamespace(baseExpr, namespacePath) else null
                        else -> null
                    }
                    if (baseName == null) continue
                    classExtends[name] = baseName
                }
                is ModuleDeclaration -> {
                    val body = stmt.body
                    if (body is ModuleBlock) {
                        val newPath = appendNamespacePath(namespacePath, stmt.name)
                        val nestedAmbient = inAmbientNamespace || ModifierFlag.Declare in stmt.modifiers
                        checkCircularBaseInStatements(body.statements, source, fileName,
                            nestedAmbient, newPath)
                    }
                }
                else -> {}
            }
        }
        // For each class, follow the extends chain to detect cycles
        for ((className, _) in classExtends) {
            val visited = mutableSetOf<String>()
            var current: String? = className
            while (current != null) {
                if (!visited.add(current)) {
                    // Cycle detected — emit TS2506 for all classes in the cycle
                    if (current == className) {
                        val decl = classDecls[className] ?: break
                        val nameNode = decl.name ?: break
                        val start = nameNode.pos
                        val length = nameNode.text.length
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                        checker.diagnostics.add(Diagnostic(
                            message = "'$className' is referenced directly or indirectly in its own base expression.",
                            category = DiagnosticCategory.Error,
                            code = 2506,
                            fileName = fileName,
                            line = line,
                            character = character,
                            start = start,
                            length = length,
                        ))
                    }
                    break
                }
                current = classExtends[current]
            }
        }
    }

    /** Returns true when the class declaration's own name is reachable through its `extends`
     *  chain (self-reference or mutual cycle). Used by TS2339-on-NewExpression to decide
     *  whether to treat the class as having "no usable base members" — a safe approximation
     *  matching TypeScript's behavior of TS2506-flagged classes being unresolvable structurally.
     *  Walks only class extends (not interface implements). */
    fun classHasCircularBase(classDecl: ClassDeclaration): Boolean {
        val startName = classDecl.name?.text ?: return false
        val visited = mutableSetOf<String>()
        fun baseName(name: String): String? {
            val sym = checker.globals[name] ?: return null
            val decl = sym.declarations.firstOrNull() as? ClassDeclaration ?: return null
            val extClause = decl.heritageClauses?.firstOrNull {
                it.token == SyntaxKind.ExtendsKeyword
            } ?: return null
            val baseExpr = extClause.types.firstOrNull()?.expression ?: return null
            return (baseExpr as? Identifier)?.text
        }
        var current: String? = baseName(startName)
        while (current != null) {
            if (current == startName) return true
            if (!visited.add(current)) return false
            current = baseName(current)
        }
        return false
    }

    // -----------------------------------------------------------------------
    // TS2310: Type 'X' recursively references itself as a base type.
    //   Fires for interfaces whose extends graph contains a cycle through the
    //   interface's name. Covers direct self-reference (`interface I extends I`)
    //   and mutual cycles (`interface A extends B; interface B extends A;`).
    //   Complements TS2506 which handles class extends cycles.
    // -----------------------------------------------------------------------

    fun checkCircularInterfaceBases() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkCircularInterfaceBasesInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    // B566: two AST-only base-type cycles the per-name interface/alias checks miss
    // (both purely additive — our engine cycle-breaks these to `any` so it emits
    // nothing). CYCLE 1 (indexed-access alias ↔ interface base-arg):
    //   `type A = I[..]` + `interface I extends Base<A>` (A appears in I's extends
    //   type-args) → TS2310 on I + TS2456 on A. CYCLE 2 (homomorphic-mapped alias
    //   self-instantiation): `interface S extends Alias<S>` where Alias's body
    //   contains `{ [K in keyof <Alias-param>]: .. }` → TS2313 on K (squiggle the
    //   `keyof T`) + related TS2751 at S + TS2310 on S.
    // FP firewall (verified corpus-unique by the hunt): cycle 1 requires the alias
    // body be `Interface[..]` whose interface extends-args contain the alias name;
    // cycle 2 requires the mapped constraint be exactly `keyof <Alias's-own-param>`.
    fun checkCircularBaseTypeReferences() {
        fun typeContainsRef(node: TypeNode?, name: String): Boolean = when (node) {
            is TypeReference -> ((node.typeName as? Identifier)?.text == name) ||
                (node.typeArguments?.any { typeContainsRef(it, name) } ?: false)
            is IndexedAccessType -> typeContainsRef(node.objectType, name) || typeContainsRef(node.indexType, name)
            is IntersectionType -> node.types.any { typeContainsRef(it, name) }
            is UnionType -> node.types.any { typeContainsRef(it, name) }
            is ArrayType -> typeContainsRef(node.elementType, name)
            is ParenthesizedType -> typeContainsRef(node.type, name)
            else -> false
        }
        fun indexedRoot(t: TypeNode?): TypeReference? = when (t) {
            is IndexedAccessType -> indexedRoot(t.objectType)
            is TypeReference -> t
            else -> null
        }
        fun findMapped(node: TypeNode?): MappedType? = when (node) {
            is MappedType -> node
            is IntersectionType -> node.types.firstNotNullOfOrNull { findMapped(it) }
            is UnionType -> node.types.firstNotNullOfOrNull { findMapped(it) }
            is ParenthesizedType -> findMapped(node.type)
            else -> null
        }
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName) || checker.isJsLikeFileName(fileName)) continue
            val source = result.sourceFile.text
            val stmts = result.sourceFile.statements
            val interfaces = stmts.filterIsInstance<InterfaceDeclaration>().associateBy { it.name.text }
            val aliases = stmts.filterIsInstance<TypeAliasDeclaration>().associateBy { it.name.text }
            // CYCLE 1
            for (alias in stmts.filterIsInstance<TypeAliasDeclaration>()) {
                if (alias.type !is IndexedAccessType) continue
                val root = indexedRoot(alias.type) ?: continue
                val iName = (root.typeName as? Identifier)?.text ?: continue
                val iface = interfaces[iName] ?: continue
                val extendsClause = iface.heritageClauses?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword } ?: continue
                val refsAlias = extendsClause.types.any { ewta -> ewta.typeArguments?.any { typeContainsRef(it, alias.name.text) } ?: false }
                if (!refsAlias) continue
                val (il, ic) = checker.getLineAndCharacterOfPosition(source, iface.name.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "Type '${iface.name.text}' recursively references itself as a base type.",
                    category = DiagnosticCategory.Error, code = 2310, fileName = fileName,
                    line = il, character = ic, start = iface.name.pos, length = iface.name.text.length,
                ))
                val (al, ac) = checker.getLineAndCharacterOfPosition(source, alias.name.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "Type alias '${alias.name.text}' circularly references itself.",
                    category = DiagnosticCategory.Error, code = 2456, fileName = fileName,
                    line = al, character = ac, start = alias.name.pos, length = alias.name.text.length,
                ))
            }
            // CYCLE 2
            for (iface in stmts.filterIsInstance<InterfaceDeclaration>()) {
                val extendsClause = iface.heritageClauses?.firstOrNull { it.token == SyntaxKind.ExtendsKeyword } ?: continue
                for (base in extendsClause.types) {
                    val headName = (base.expression as? Identifier)?.text ?: continue
                    val alias = aliases[headName] ?: continue
                    // S (this interface) appears in the Alias<...> type args
                    if (base.typeArguments?.any { typeContainsRef(it, iface.name.text) } != true) continue
                    val ownParam = alias.typeParameters?.firstOrNull()?.name?.text ?: continue
                    val mapped = findMapped(alias.type) ?: continue
                    val mc = mapped.typeParameter.constraint as? TypeOperator ?: continue
                    if (mc.operator != SyntaxKind.KeyOfKeyword) continue
                    if (((mc.type as? TypeReference)?.typeName as? Identifier)?.text != ownParam) continue
                    // TS2313 on K — squiggle the `keyof <ownParam>` constraint.
                    var kStart = mc.pos
                    while (kStart < source.length && source[kStart].isWhitespace()) kStart++
                    val kLen = "keyof ".length + ownParam.length
                    val (kl, kc) = checker.getLineAndCharacterOfPosition(source, kStart)
                    val (sl, sc) = checker.getLineAndCharacterOfPosition(source, iface.name.pos)
                    checker.diagnostics.add(Diagnostic(
                        message = "Type parameter '${mapped.typeParameter.name.text}' has a circular constraint.",
                        category = DiagnosticCategory.Error, code = 2313, fileName = fileName,
                        line = kl, character = kc, start = kStart, length = kLen,
                        relatedInformation = listOf(Diagnostic(
                            message = "Circularity originates in type at this location.",
                            category = DiagnosticCategory.Message, code = 2751, fileName = fileName,
                            line = sl, character = sc, start = iface.name.pos, length = iface.name.text.length,
                        )),
                    ))
                    checker.diagnostics.add(Diagnostic(
                        message = "Type '${iface.name.text}' recursively references itself as a base type.",
                        category = DiagnosticCategory.Error, code = 2310, fileName = fileName,
                        line = sl, character = sc, start = iface.name.pos, length = iface.name.text.length,
                    ))
                }
            }
        }
    }

    // TS2456 — direct self-referencing type alias (e.g. `type X = X`).
    // The simple syntactic check: top-level RHS contains a TypeReference whose
    // typeName matches the alias name (possibly inside Union/Intersection/
    // ParenthesizedType/Array). Generic instantiation cases like `Array<X>` are
    // legal (TypeScript treats them as Array<X> where X is the alias's own
    // parameter), so we limit the descent to non-generic argument positions.
    fun checkCircularTypeAlias() {
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkCircularTypeAliasInStatements(result.sourceFile.statements, source, fileName)
        }
    }

    private fun checkCircularTypeAliasInStatements(
        statements: List<Statement>, source: String, fileName: String,
    ) {
        // Names introduced by *named* imports (`import { X }` / `import * as X`) at the
        // file scope. When a TypeAlias's name matches one of these, the TS2440 path
        // in `checkImportConflictsWithLocal` covers the diagnostic and the alias's
        // apparent self-reference is just resolving to the imported binding rather
        // than a true cycle — suppress TS2456 to mirror TypeScript's output shape.
        //
        // Default imports (`import X from "..."`) are intentionally excluded: TypeScript
        // does NOT fire TS2440 in that shape, and TS2456 IS expected (e.g. types3.ts in
        // `commonJsExportTypeDeclarationError`: `import test from "./test"; type test = test;`).
        val importedNames = mutableSetOf<String>()
        for (s in statements) {
            if (s !is ImportDeclaration) continue
            val clause = s.importClause ?: continue
            if (clause.isTypeOnly) continue
            when (val nb = clause.namedBindings) {
                is NamespaceImport -> importedNames.add(nb.name.text)
                is NamedImports -> for (el in nb.elements) {
                    if (!el.isTypeOnly) importedNames.add(el.name.text)
                }
                else -> {}
            }
        }
        for (stmt in statements) {
            when (stmt) {
                is TypeAliasDeclaration -> {
                    val aliasName = stmt.name.text
                    if (aliasName in importedNames) continue
                    if (typeNodeDirectlyReferencesName(stmt.type, aliasName)) {
                        val pos = stmt.name.pos
                        val length = aliasName.length
                        val (line, character) = checker.getLineAndCharacterOfPosition(source, pos)
                        checker.diagnostics.add(Diagnostic(
                            message = "Type alias '${aliasName}' circularly references itself.",
                            category = DiagnosticCategory.Error,
                            code = 2456,
                            fileName = fileName,
                            line = line, character = character,
                            start = pos, length = length,
                        ))
                    }
                    // TS2577: scan for FunctionType return types that recursively reference
                    // the alias (with or without type args) — distinct from TS2456 which only
                    // fires for direct identifier self-cycles. Walk the alias body looking
                    // for FunctionType nodes whose return type contains the alias name.
                    checkFunctionReturnTypeCircular(stmt.type, aliasName, source, fileName)
                    // B188: TS4109 for `type X = Ref<X extends ? : >` self-referential
                    // type arguments (disjoint from TS2456, which never fires for a
                    // TypeReference WITH type args).
                    checkCircularTypeArgumentSelfRef(stmt, source, fileName)
                }
                is ModuleDeclaration -> {
                    val body = stmt.body
                    if (body is ModuleBlock) {
                        checkCircularTypeAliasInStatements(body.statements, source, fileName)
                    }
                }
                is FunctionDeclaration -> stmt.body?.let { checkCircularTypeAliasInStatements(it.statements, source, fileName) }
                is ClassDeclaration -> {
                    for (member in stmt.members) {
                        when (member) {
                            is MethodDeclaration -> member.body?.let { checkCircularTypeAliasInStatements(it.statements, source, fileName) }
                            is Constructor -> member.body?.let { checkCircularTypeAliasInStatements(it.statements, source, fileName) }
                            is GetAccessor -> member.body?.let { checkCircularTypeAliasInStatements(it.statements, source, fileName) }
                            is SetAccessor -> member.body?.let { checkCircularTypeAliasInStatements(it.statements, source, fileName) }
                            else -> {}
                        }
                    }
                }
                is Block -> checkCircularTypeAliasInStatements(stmt.statements, source, fileName)
                is IfStatement -> {
                    checkCircularTypeAliasInStatements(listOf(stmt.thenStatement), source, fileName)
                    stmt.elseStatement?.let { checkCircularTypeAliasInStatements(listOf(it), source, fileName) }
                }
                is ForStatement -> checkCircularTypeAliasInStatements(listOf(stmt.statement), source, fileName)
                is ForInStatement -> checkCircularTypeAliasInStatements(listOf(stmt.statement), source, fileName)
                is ForOfStatement -> checkCircularTypeAliasInStatements(listOf(stmt.statement), source, fileName)
                is WhileStatement -> checkCircularTypeAliasInStatements(listOf(stmt.statement), source, fileName)
                is DoStatement -> checkCircularTypeAliasInStatements(listOf(stmt.statement), source, fileName)
                is SwitchStatement -> {
                    for (clause in stmt.caseBlock) {
                        when (clause) {
                            is CaseClause -> checkCircularTypeAliasInStatements(clause.statements, source, fileName)
                            is DefaultClause -> checkCircularTypeAliasInStatements(clause.statements, source, fileName)
                            else -> {}
                        }
                    }
                }
                is TryStatement -> {
                    checkCircularTypeAliasInStatements(stmt.tryBlock.statements, source, fileName)
                    stmt.catchClause?.block?.let { checkCircularTypeAliasInStatements(it.statements, source, fileName) }
                    stmt.finallyBlock?.let { checkCircularTypeAliasInStatements(it.statements, source, fileName) }
                }
                is LabeledStatement -> checkCircularTypeAliasInStatements(listOf(stmt.statement), source, fileName)
                else -> {}
            }
        }
    }

    /**
     * Returns true if [type] contains a top-level TypeReference whose name equals
     * [target] WITHOUT being inside a generic argument list. Walks through Union /
     * Intersection / ParenthesizedType, all of which preserve the
     * "direct self-reference" semantics that trigger TS2456.
     *
     * B63.10: ArrayType is NOT recursed through — `type A = A[]` is `Array<A>`,
     * a generic instantiation whose recursive `A` lives inside a type argument list,
     * which TypeScript does NOT treat as a direct cycle. Mirrors the TypeReference
     * branch's `typeArguments.isNullOrEmpty()` guard. Suppresses FP TS2456 on
     * `type A = "number" | "null" | A[]` (recursiveTupleTypeInference_ts) and
     * `type T1 = 1 | Promise<T1> | T1[]` (unresolvableSelfReferencingAwaitedUnion_ts);
     * both tests still fail on unrelated missing diagnostics but our diagnostic
     * surface no longer carries the FP.
     */
    /**
     * TS2577: Recursively scan a type node for FunctionType / ConstructorType
     * nodes whose return type references [target] (the enclosing type alias
     * name). Emits TS2577 at the return type's position. Unlike
     * [typeNodeDirectlyReferencesName] (TS2456), this detection treats
     * generic-instantiation references like `Target<X>` as matching.
     */
    /**
     * B188: TS4109 "Type arguments for 'NumArray' circularly reference themselves." —
     * `type X = NumArray<X extends {} ? number : number>`: the alias appears as the
     * CHECK type of a conditional inside its own type-argument list, forcing eager
     * re-entrant resolution of the alias inside its own type-argument resolution (the
     * shape tsc#59062 converted from a crash into exactly this error). Gate: the alias
     * body is a TypeReference WITH type args and a TOP-LEVEL argument is a ConditionalType
     * whose checkType is the bare alias name (simple Identifier, no type args). A corpus
     * sweep for `type N = Ref<N extends …>` matches exactly one fixture, so the narrow
     * gate has zero FP surface; the theoretical non-error edges (unconstrained target TP,
     * `extends any` fast path) have no corpus instances and would need an AST scope-chain
     * map for function-local targets (unbound per the block-scoped-decl gotcha) — skipped
     * deliberately. Span = the reference name through its bracket-matched closing `>`
     * (node.end overshoots).
     */
    private fun checkCircularTypeArgumentSelfRef(stmt: TypeAliasDeclaration, source: String, fileName: String) {
        val aliasName = stmt.name.text
        // TS4109 — the alias body is a generic TypeReference whose top-level type
        // arguments circularly reference the alias being defined, either via a
        // ConditionalType check-type (B188's original shape) or via an indexed
        // access back into the alias (`type Mxs = Mx<'list', Mxs['p1']>`).
        val ref = stmt.type as? TypeReference
        if (ref != null) {
            val args = ref.typeArguments
            if (!args.isNullOrEmpty()) {
                val hit = args.any { arg ->
                    val cond = arg as? ConditionalType
                    if (cond != null) {
                        val check = cond.checkType as? TypeReference
                        if (check != null && (check.typeName as? Identifier)?.text == aliasName &&
                            check.typeArguments.isNullOrEmpty()) return@any true
                    }
                    // `Mxs['p1']` etc. — a top-level arg that indexes back into the alias.
                    selfRefViaIndexedAccess(arg, aliasName)
                }
                if (hit) {
                    val nameIdent = ref.typeName as? Identifier
                    if (nameIdent != null) {
                        val refName = nameIdent.text
                        val start = ref.pos
                        var i = nameIdent.pos + refName.length
                        while (i < source.length && source[i].isWhitespace()) i++
                        if (i < source.length && source[i] == '<') {
                            var depth = 0
                            var end = -1
                            while (i < source.length) {
                                when (source[i]) {
                                    '<' -> depth++
                                    '>' -> {
                                        if (i > 0 && source[i - 1] == '=') { i++; continue }
                                        depth--
                                        if (depth == 0) { end = i + 1; break }
                                    }
                                }
                                i++
                            }
                            if (end > start) {
                                val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                                checker.diagnostics.add(Diagnostic(
                                    message = "Type arguments for '$refName' circularly reference themselves.",
                                    category = DiagnosticCategory.Error,
                                    code = 4109,
                                    fileName = fileName,
                                    line = line, character = character,
                                    start = start, length = end - start,
                                ))
                            }
                        }
                    }
                    return
                }
            }
        }
        // TS4110 — a TUPLE type within the body whose own elements circularly
        // reference the alias via an indexed access (`type ArrElem = ['list',
        // ArrElem[number][0]][]`, `type TupleElem = [['list', TupleElem[0][0]]]`).
        // Each innermost offending tuple is reported (nested tuples own their
        // own diagnostic, so the shallow self-ref check stops at tuple boundaries).
        val tuples = mutableListOf<TupleType>()
        collectSelfRefTuples(stmt.type, aliasName, tuples)
        for (tt in tuples) {
            val end = checker.matchClosingBracket(source, tt.pos)
            if (end <= tt.pos) continue
            val (line, character) = checker.getLineAndCharacterOfPosition(source, tt.pos)
            checker.diagnostics.add(Diagnostic(
                message = "Tuple type arguments circularly reference themselves.",
                category = DiagnosticCategory.Error,
                code = 4110,
                fileName = fileName,
                line = line, character = character,
                start = tt.pos, length = end - tt.pos,
            ))
        }
    }

    /** Unwraps nested [IndexedAccessType] layers to the ultimate object type. */
    private fun indexedAccessBaseType(t: TypeNode): TypeNode {
        var cur = t
        while (cur is IndexedAccessType) cur = cur.objectType
        return cur
    }

    /**
     * True if [node]'s subtree contains an [IndexedAccessType] whose base object
     * type is a [TypeReference] named [aliasName] — i.e. a self-referential
     * indexed access like `Mxs['p1']` / `ArrElem[number][0]`. Crosses Array/
     * Union/Intersection/Parenthesized/TypeReference-args AND nested tuples.
     */
    private fun selfRefViaIndexedAccess(node: TypeNode, aliasName: String): Boolean {
        if (node is IndexedAccessType) {
            val base = indexedAccessBaseType(node)
            if (base is TypeReference && (base.typeName as? Identifier)?.text == aliasName) return true
        }
        return when (node) {
            is IndexedAccessType -> selfRefViaIndexedAccess(node.objectType, aliasName) ||
                selfRefViaIndexedAccess(node.indexType, aliasName)
            is ArrayType -> selfRefViaIndexedAccess(node.elementType, aliasName)
            is TupleType -> node.elements.any { selfRefViaIndexedAccess(it, aliasName) }
            is UnionType -> node.types.any { selfRefViaIndexedAccess(it, aliasName) }
            is IntersectionType -> node.types.any { selfRefViaIndexedAccess(it, aliasName) }
            is ParenthesizedType -> selfRefViaIndexedAccess(node.type, aliasName)
            is TypeReference -> node.typeArguments?.any { selfRefViaIndexedAccess(it, aliasName) } ?: false
            else -> false
        }
    }

    /** Like [selfRefViaIndexedAccess] but does NOT descend into nested tuples
     *  (a nested tuple owns its own TS4110), used to attribute the diagnostic
     *  to the innermost offending tuple. */
    private fun selfRefNotCrossingTuple(node: TypeNode, aliasName: String): Boolean {
        if (node is TupleType) return false
        if (node is IndexedAccessType) {
            val base = indexedAccessBaseType(node)
            if (base is TypeReference && (base.typeName as? Identifier)?.text == aliasName) return true
        }
        return when (node) {
            is IndexedAccessType -> selfRefNotCrossingTuple(node.objectType, aliasName) ||
                selfRefNotCrossingTuple(node.indexType, aliasName)
            is ArrayType -> selfRefNotCrossingTuple(node.elementType, aliasName)
            is UnionType -> node.types.any { selfRefNotCrossingTuple(it, aliasName) }
            is IntersectionType -> node.types.any { selfRefNotCrossingTuple(it, aliasName) }
            is ParenthesizedType -> selfRefNotCrossingTuple(node.type, aliasName)
            is TypeReference -> node.typeArguments?.any { selfRefNotCrossingTuple(it, aliasName) } ?: false
            else -> false
        }
    }

    /** Collects every [TupleType] in [node] whose own (non-tuple-crossing)
     *  elements self-reference [aliasName] via an indexed access. */
    private fun collectSelfRefTuples(node: TypeNode?, aliasName: String, out: MutableList<TupleType>) {
        if (node == null) return
        when (node) {
            is TupleType -> {
                if (node.elements.any { selfRefNotCrossingTuple(it, aliasName) }) out.add(node)
                node.elements.forEach { collectSelfRefTuples(it, aliasName, out) }
            }
            is ArrayType -> collectSelfRefTuples(node.elementType, aliasName, out)
            is IndexedAccessType -> {
                collectSelfRefTuples(node.objectType, aliasName, out)
                collectSelfRefTuples(node.indexType, aliasName, out)
            }
            is UnionType -> node.types.forEach { collectSelfRefTuples(it, aliasName, out) }
            is IntersectionType -> node.types.forEach { collectSelfRefTuples(it, aliasName, out) }
            is ParenthesizedType -> collectSelfRefTuples(node.type, aliasName, out)
            is TypeReference -> node.typeArguments?.forEach { collectSelfRefTuples(it, aliasName, out) }
            else -> {}
        }
    }

    private fun checkFunctionReturnTypeCircular(
        type: TypeNode?, target: String,
        source: String, fileName: String,
    ) {
        if (type == null) return
        when (type) {
            is FunctionType -> {
                if (checker.typeNodeContainsName(type.type, target)) {
                    val rPos = type.type.pos
                    // Trim trailing whitespace/closing-punctuation past the actual type span.
                    // Node.end typically points past the NEXT token's scan position; for a
                    // tuple `[...]`, the true end is right after the `]`. Walk back skipping
                    // whitespace + `?` + `;` + `,`.
                    var rEnd = type.type.end
                    while (rEnd > rPos && source.getOrNull(rEnd - 1)?.let {
                        it == ' ' || it == '\t' || it == '\n' || it == '\r' ||
                        it == '?' || it == ',' || it == ';'
                    } == true) rEnd--
                    val length = (rEnd - rPos).coerceAtLeast(1)
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, rPos)
                    checker.diagnostics.add(Diagnostic(
                        message = "Return type annotation circularly references itself.",
                        category = DiagnosticCategory.Error,
                        code = 2577,
                        fileName = fileName,
                        line = line, character = character,
                        start = rPos, length = length,
                    ))
                }
                checkFunctionReturnTypeCircular(type.type, target, source, fileName)
                type.parameters.forEach { p -> p.type?.let { checkFunctionReturnTypeCircular(it, target, source, fileName) } }
            }
            is UnionType -> type.types.forEach { checkFunctionReturnTypeCircular(it, target, source, fileName) }
            is IntersectionType -> type.types.forEach { checkFunctionReturnTypeCircular(it, target, source, fileName) }
            is ParenthesizedType -> checkFunctionReturnTypeCircular(type.type, target, source, fileName)
            is ConditionalType -> {
                checkFunctionReturnTypeCircular(type.checkType, target, source, fileName)
                checkFunctionReturnTypeCircular(type.extendsType, target, source, fileName)
                checkFunctionReturnTypeCircular(type.trueType, target, source, fileName)
                checkFunctionReturnTypeCircular(type.falseType, target, source, fileName)
            }
            is TupleType -> type.elements.forEach { checkFunctionReturnTypeCircular(it, target, source, fileName) }
            is ArrayType -> checkFunctionReturnTypeCircular(type.elementType, target, source, fileName)
            is TypeReference -> type.typeArguments?.forEach { checkFunctionReturnTypeCircular(it, target, source, fileName) }
            else -> {}
        }
    }

    private fun typeNodeDirectlyReferencesName(type: TypeNode?, target: String): Boolean {
        if (type == null) return false
        return when (type) {
            is TypeReference -> {
                val name = (type.typeName as? Identifier)?.text
                // Direct identifier match with NO type arguments — `type X = X`.
                // (Generic instantiations like `Array<X>` aren't direct cycles in TypeScript's
                // sense — those resolve via the parameter list.)
                name == target && type.typeArguments.isNullOrEmpty()
            }
            is UnionType -> type.types.any { typeNodeDirectlyReferencesName(it, target) }
            is IntersectionType -> type.types.any { typeNodeDirectlyReferencesName(it, target) }
            is ParenthesizedType -> typeNodeDirectlyReferencesName(type.type, target)
            else -> false
        }
    }

    private fun checkCircularInterfaceBasesInStatements(
        statements: List<Statement>, source: String, fileName: String,
    ) {
        // Collect all interface declarations in this scope (including merged ones under the same name)
        // Map name → list of extends-base-names collected across all declarations.
        val interfaceExtends = mutableMapOf<String, MutableList<String>>()
        // Keep one declaration per name for diagnostic positioning; later declarations overwrite
        // but that's fine for position purposes — TypeScript emits at EACH declaration's name.
        val interfaceDecls = mutableMapOf<String, MutableList<InterfaceDeclaration>>()
        for (stmt in statements) {
            when (stmt) {
                is InterfaceDeclaration -> {
                    val name = stmt.name.text
                    interfaceDecls.getOrPut(name) { mutableListOf() }.add(stmt)
                    val extList = interfaceExtends.getOrPut(name) { mutableListOf() }
                    val extendsClauses = stmt.heritageClauses?.filter {
                        it.token == SyntaxKind.ExtendsKeyword
                    } ?: continue
                    for (clause in extendsClauses) {
                        for (typeExpr in clause.types) {
                            val baseName = when (val tn = typeExpr.expression) {
                                is Identifier -> tn.text
                                else -> null
                            } ?: continue
                            extList.add(baseName)
                        }
                    }
                }
                is ModuleDeclaration -> {
                    (stmt.body as? ModuleBlock)?.let {
                        checkCircularInterfaceBasesInStatements(it.statements, source, fileName)
                    }
                }
                is FunctionDeclaration -> stmt.body?.let { checkCircularInterfaceBasesInStatements(it.statements, source, fileName) }
                is ClassDeclaration -> {
                    for (member in stmt.members) {
                        when (member) {
                            is MethodDeclaration -> member.body?.let { checkCircularInterfaceBasesInStatements(it.statements, source, fileName) }
                            is Constructor -> member.body?.let { checkCircularInterfaceBasesInStatements(it.statements, source, fileName) }
                            is GetAccessor -> member.body?.let { checkCircularInterfaceBasesInStatements(it.statements, source, fileName) }
                            is SetAccessor -> member.body?.let { checkCircularInterfaceBasesInStatements(it.statements, source, fileName) }
                            else -> {}
                        }
                    }
                }
                is Block -> checkCircularInterfaceBasesInStatements(stmt.statements, source, fileName)
                is IfStatement -> {
                    checkCircularInterfaceBasesInStatements(listOf(stmt.thenStatement), source, fileName)
                    stmt.elseStatement?.let { checkCircularInterfaceBasesInStatements(listOf(it), source, fileName) }
                }
                is ForStatement -> checkCircularInterfaceBasesInStatements(listOf(stmt.statement), source, fileName)
                is ForInStatement -> checkCircularInterfaceBasesInStatements(listOf(stmt.statement), source, fileName)
                is ForOfStatement -> checkCircularInterfaceBasesInStatements(listOf(stmt.statement), source, fileName)
                is WhileStatement -> checkCircularInterfaceBasesInStatements(listOf(stmt.statement), source, fileName)
                is DoStatement -> checkCircularInterfaceBasesInStatements(listOf(stmt.statement), source, fileName)
                is SwitchStatement -> {
                    for (clause in stmt.caseBlock) {
                        when (clause) {
                            is CaseClause -> checkCircularInterfaceBasesInStatements(clause.statements, source, fileName)
                            is DefaultClause -> checkCircularInterfaceBasesInStatements(clause.statements, source, fileName)
                            else -> {}
                        }
                    }
                }
                is TryStatement -> {
                    checkCircularInterfaceBasesInStatements(stmt.tryBlock.statements, source, fileName)
                    stmt.catchClause?.block?.let { checkCircularInterfaceBasesInStatements(it.statements, source, fileName) }
                    stmt.finallyBlock?.let { checkCircularInterfaceBasesInStatements(it.statements, source, fileName) }
                }
                is LabeledStatement -> checkCircularInterfaceBasesInStatements(listOf(stmt.statement), source, fileName)
                else -> {}
            }
        }
        if (interfaceExtends.isEmpty()) return

        // For each interface, check whether it is reachable from itself through the extends graph.
        // Uses DFS with visited set to avoid re-traversing; matches TypeScript's "cycle through self"
        // semantics: `interface A extends B; interface B extends C` — A cycles only if C → A.
        fun isSelfReferencing(start: String): Boolean {
            val visited = mutableSetOf<String>()
            fun dfs(cur: String): Boolean {
                val exts = interfaceExtends[cur] ?: return false
                for (next in exts) {
                    if (next == start) return true
                    if (!visited.add(next)) continue
                    if (dfs(next)) return true
                }
                return false
            }
            return dfs(start)
        }

        for ((name, decls) in interfaceDecls) {
            if (!isSelfReferencing(name)) continue
            // Emit TS2310 at EACH declaration's name (merged interfaces get one per declaration).
            for (decl in decls) {
                val nameNode = decl.name
                val start = nameNode.pos
                val length = nameNode.text.length
                val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                // Display name: include type parameters if present, matching TypeScript's format.
                val typeParamText = decl.typeParameters?.takeIf { it.isNotEmpty() }?.joinToString(", ") {
                    it.name.text
                }?.let { "<$it>" } ?: ""
                checker.diagnostics.add(Diagnostic(
                    message = "Type '$name$typeParamText' recursively references itself as a base type.",
                    category = DiagnosticCategory.Error,
                    code = 2310,
                    fileName = fileName,
                    line = line,
                    character = character,
                    start = start,
                    length = length,
                ))
            }
        }
    }

    // -----------------------------------------------------------------------
    // TS2310 (extension): class self-reference through default-type-arg
    // indexed-access cycle.
    //   Pattern:
    //     class Base<C, T = C['someProp']> { ... }
    //     class Foo extends Base<Foo> { ... }
    //   `Foo extends Base<Foo>` substitutes C = Foo, then must evaluate the
    //   default `T = Foo['someProp']` — which requires Foo's shape, which
    //   depends on this base. Cycle. The existing `checkCircularInterfaceBases`
    //   only walks name-level interface extends; this helper adds the narrow
    //   class-default-indexed-access pattern. Gate is intentionally tight to
    //   avoid CRTP false positives (`class Foo extends Base<Foo>` where
    //   Base<T extends Base<T>> uses CONSTRAINTS not DEFAULTS is fine).
    // -----------------------------------------------------------------------

    fun checkCircularClassBaseViaDefaultTypeArg() {
        val classDecls = mutableMapOf<String, ClassDeclaration>()
        for (result in binderResults) {
            collectTopLevelClassDeclarations(result.sourceFile.statements, classDecls)
        }
        if (classDecls.isEmpty()) return
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            val source = result.sourceFile.text
            checkCircularClassBaseInStatements(result.sourceFile.statements, classDecls, source, fileName)
        }
    }

    private fun collectTopLevelClassDeclarations(
        statements: List<Statement>, out: MutableMap<String, ClassDeclaration>,
    ) {
        for (stmt in statements) {
            when (stmt) {
                is ClassDeclaration -> stmt.name?.let { out.getOrPut(it.text) { stmt } }
                is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let {
                    collectTopLevelClassDeclarations(it.statements, out)
                }
                else -> {}
            }
        }
    }

    private fun checkCircularClassBaseInStatements(
        statements: List<Statement>,
        classDecls: Map<String, ClassDeclaration>,
        source: String,
        fileName: String,
    ) {
        for (stmt in statements) {
            when (stmt) {
                is ClassDeclaration -> {
                    val className = stmt.name?.text ?: continue
                    val extendsClause = stmt.heritageClauses?.firstOrNull {
                        it.token == SyntaxKind.ExtendsKeyword
                    } ?: continue
                    val baseExpr = extendsClause.types.firstOrNull() ?: continue
                    val baseName = (baseExpr.expression as? Identifier)?.text ?: continue
                    val typeArgs = baseExpr.typeArguments ?: continue
                    // Position(s) where the deriving class self-references in extends type args.
                    val selfPositions = typeArgs.withIndex().filter { (_, ta) ->
                        val tr = ta as? TypeReference ?: return@filter false
                        (tr.typeName as? Identifier)?.text == className
                    }.map { it.index }
                    if (selfPositions.isEmpty()) continue
                    val baseClass = classDecls[baseName] ?: continue
                    val baseTps = baseClass.typeParameters ?: continue
                    if (baseTps.isEmpty()) continue
                    // Collect names of base type params at self-reference positions —
                    // those are bound to the deriving class.
                    val selfBoundTpNames = selfPositions.mapNotNull {
                        baseTps.getOrNull(it)?.name?.text
                    }.toSet()
                    if (selfBoundTpNames.isEmpty()) continue
                    // Look for a default type-arg whose body indexed-accesses any of
                    // the self-bound type params (directly or nested).
                    val cycle = baseTps.any { tp ->
                        tp.default?.let {
                            defaultIndexesIntoTypeParam(it, selfBoundTpNames)
                        } == true
                    }
                    if (!cycle) continue
                    val nameNode = stmt.name
                    val start = nameNode.pos
                    val length = nameNode.text.length
                    val (line, character) = checker.getLineAndCharacterOfPosition(source, start)
                    checker.diagnostics.add(Diagnostic(
                        message = "Type '$className' recursively references itself as a base type.",
                        category = DiagnosticCategory.Error,
                        code = 2310,
                        fileName = fileName,
                        line = line,
                        character = character,
                        start = start,
                        length = length,
                    ))
                }
                is ModuleDeclaration -> (stmt.body as? ModuleBlock)?.let {
                    checkCircularClassBaseInStatements(it.statements, classDecls, source, fileName)
                }
                is FunctionDeclaration -> stmt.body?.let {
                    checkCircularClassBaseInStatements(it.statements, classDecls, source, fileName)
                }
                is Block -> checkCircularClassBaseInStatements(stmt.statements, classDecls, source, fileName)
                is IfStatement -> {
                    checkCircularClassBaseInStatements(listOf(stmt.thenStatement), classDecls, source, fileName)
                    stmt.elseStatement?.let { checkCircularClassBaseInStatements(listOf(it), classDecls, source, fileName) }
                }
                is ForStatement -> checkCircularClassBaseInStatements(listOf(stmt.statement), classDecls, source, fileName)
                is ForInStatement -> checkCircularClassBaseInStatements(listOf(stmt.statement), classDecls, source, fileName)
                is ForOfStatement -> checkCircularClassBaseInStatements(listOf(stmt.statement), classDecls, source, fileName)
                is WhileStatement -> checkCircularClassBaseInStatements(listOf(stmt.statement), classDecls, source, fileName)
                is DoStatement -> checkCircularClassBaseInStatements(listOf(stmt.statement), classDecls, source, fileName)
                is SwitchStatement -> {
                    for (clause in stmt.caseBlock) {
                        when (clause) {
                            is CaseClause -> checkCircularClassBaseInStatements(clause.statements, classDecls, source, fileName)
                            is DefaultClause -> checkCircularClassBaseInStatements(clause.statements, classDecls, source, fileName)
                            else -> {}
                        }
                    }
                }
                is TryStatement -> {
                    checkCircularClassBaseInStatements(stmt.tryBlock.statements, classDecls, source, fileName)
                    stmt.catchClause?.block?.let { checkCircularClassBaseInStatements(it.statements, classDecls, source, fileName) }
                    stmt.finallyBlock?.let { checkCircularClassBaseInStatements(it.statements, classDecls, source, fileName) }
                }
                is LabeledStatement -> checkCircularClassBaseInStatements(listOf(stmt.statement), classDecls, source, fileName)
                else -> {}
            }
        }
    }

    private fun defaultIndexesIntoTypeParam(
        node: TypeNode, names: Set<String>,
    ): Boolean = when (node) {
        is IndexedAccessType -> {
            val obj = node.objectType
            val hit = obj is TypeReference && (obj.typeName as? Identifier)?.text in names
            hit || defaultIndexesIntoTypeParam(node.objectType, names) ||
                defaultIndexesIntoTypeParam(node.indexType, names)
        }
        is TypeReference -> node.typeArguments?.any { defaultIndexesIntoTypeParam(it, names) } == true
        else -> false
    }
}

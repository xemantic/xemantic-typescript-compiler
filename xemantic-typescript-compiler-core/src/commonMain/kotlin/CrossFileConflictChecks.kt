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
 * (INV.0) (P18.298) — the CROSS-FILE DUPLICATE / CONFLICT family: twelve passes run program-wide
 * after the per-file checks (`checkCrossFileIdentifierConflicts` TS2300 / TS2451 hub model,
 * `checkCrossFileBlockScopedDuplicates`, `checkCrossFileEnumConflicts`,
 * `checkCrossFileInterfaceMemberConflicts`, `checkCrossFileClassConflicts`,
 * `checkUmdGlobalVsDeclareGlobalConst`, `checkCrossFileModuleAugmentationDuplicates`,
 * `checkModuleAugmentationReexportDuplicates`, `checkCjsExportAugmentationConflict`,
 * `checkModuleAugmentationEnumMerge`, `checkGlobalNamespaceMemberConflicts`,
 * `checkCrossFileTypeAliasNamespaceConflict`), the TS6203 / TS6204 related-row builder
 * [duplicateCallRelatedInfos] (also used by `Checker.checkClassShadowsLibType`), the augmentation
 * target resolver [resolveAugmentationTargetFile] (also used by `Checker.augmentationTargetFileJsAware`)
 * and the B93 hand-off set `crossFileIdentifierHandledBlockNames`. Extracted VERBATIM from
 * `Checker.kt` (two spans: 4911-4915, the set; 190932-192311, one contiguous run); every Checker
 * member it reads is reached through [checker], the two companion sets through `Checker.`, and the
 * remaining inputs are the checker's own constructor parameters, passed in. Ambient reads:
 * `docs/inversion-ambient-ledger.md` row 24.
 */
internal class CrossFileConflictChecks(
    private val checker: Checker,
    private val options: CompilerOptions,
    private val binderResults: List<BinderResult>,
) {

    /** B93: names whose cross-file block-scoped VALUE-space conflict is emitted by the
     *  unified [checkCrossFileIdentifierConflicts] (because the name also has a class or
     *  type-alias declaration). [checkCrossFileBlockScopedDuplicates] (73b) skips these
     *  to avoid double-emitting the TS2451. Populated just before 73b runs. */
    private val crossFileIdentifierHandledBlockNames = mutableSetOf<String>()

    // -----------------------------------------------------------------------
    // TS2393: Duplicate function implementation (cross-file)
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // TS2300 / TS2451 cross-file: unified identifier conflicts (B93)
    // -----------------------------------------------------------------------

    /**
     * B93: Unified cross-file duplicate-identifier conflicts for top-level declarations
     * in script (non-module) files that MIX a CLASS / TYPE-ALIAS with CONST/LET (or have
     * multiple type-aliases). These are the cases the per-kind checkers miss:
     *  - [checkCrossFileBlockScopedDuplicates] (73b) handles pure const/let/var but not a
     *    class sharing the name, and emits no TS6204 follow-on for 3+ files.
     *  - [checkCrossFileClassConflicts] (73g) handles pure class-vs-class only.
     *  - No checker handled cross-file type-alias conflicts at all.
     *
     * TypeScript reports these PER SPACE (value-space / type-space) with a HUB model: the
     * FIRST-processed declaration occupying the conflicting space is the hub; its
     * diagnostic accumulates a related TS6203 (first other) then TS6204 (subsequent
     * others), and each other declaration points back at the hub (TS6203). The code is
     * TS2451 when any conflicting value-space decl is block-scoped (const/let), else
     * TS2300; type-space conflicts are always TS2300. (This mirrors TypeScript's
     * `mergeSymbol` amalgamation where the first-merged file's declaration stays the
     * persistent target and `lookupOrIssueError` dedup accumulates the related infos.)
     *
     * Gate is narrow to keep the FP surface minimal:
     *  - every declaration of the name is a class / type-alias / const / let (names that
     *    also have a var / function / interface / namespace / enum decl have merge
     *    semantics and are left to their own checkers);
     *  - at most ONE class decl (≥2 classes → pure class-vs-class is 73g's job);
     *  - the name must involve a type-alias OR a class+block-scoped mix (pure const/let →
     *    73b). Block-scoped names emitted here are recorded in
     *    [crossFileIdentifierHandledBlockNames] so 73b skips them.
     */
    fun checkCrossFileIdentifierConflicts() {
        // One tracked declaration. kind ∈ {"class","typeAlias","const","let"}.
        class IdDecl(
            val name: String, val nameNode: Node, val fileName: String,
            val source: String, val kind: String,
        )
        val byName = mutableMapOf<String, MutableList<IdDecl>>()
        // Names that also have a merge-semantics decl (var/function/interface/namespace/
        // enum) at top level in a script file → bail (leave to their own checkers).
        val bailNames = mutableSetOf<String>()

        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            if (checker.isModuleFile(result.sourceFile.statements)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                when (stmt) {
                    is ClassDeclaration -> stmt.name?.let {
                        if (it.text.isNotEmpty())
                            byName.getOrPut(it.text) { mutableListOf() }
                                .add(IdDecl(it.text, it, fileName, source, "class"))
                    }
                    is TypeAliasDeclaration -> {
                        val n = stmt.name
                        if (n.text.isNotEmpty())
                            byName.getOrPut(n.text) { mutableListOf() }
                                .add(IdDecl(n.text, n, fileName, source, "typeAlias"))
                    }
                    is VariableStatement -> {
                        val kind = when (stmt.declarationList.flags) {
                            SyntaxKind.ConstKeyword -> "const"
                            SyntaxKind.LetKeyword -> "let"
                            else -> null  // var → merge semantics
                        }
                        for (decl in stmt.declarationList.declarations) {
                            val n = decl.name as? Identifier ?: continue
                            if (n.text.isEmpty()) continue
                            if (kind == null) bailNames.add(n.text)
                            else byName.getOrPut(n.text) { mutableListOf() }
                                .add(IdDecl(n.text, n, fileName, source, kind))
                        }
                    }
                    is FunctionDeclaration -> stmt.name?.let { bailNames.add(it.text) }
                    is InterfaceDeclaration -> bailNames.add(stmt.name.text)
                    is EnumDeclaration -> bailNames.add(stmt.name.text)
                    is ModuleDeclaration -> (stmt.name as? Identifier)?.let { bailNames.add(it.text) }
                    else -> {}
                }
            }
        }

        for ((name, decls) in byName) {
            if (name in bailNames) continue
            if (decls.size < 2) continue
            if (decls.map { it.fileName }.distinct().size < 2) continue
            val classCount = decls.count { it.kind == "class" }
            if (classCount >= 2) continue  // pure/partial class-vs-class → 73g
            val typeAliasCount = decls.count { it.kind == "typeAlias" }
            val hasBlock = decls.any { it.kind == "const" || it.kind == "let" }
            // Must involve a type-alias OR a class+block-scoped mix; pure const/let → 73b.
            if (typeAliasCount == 0 && !(classCount > 0 && hasBlock)) continue

            // value-space occupiers: class, const, let.
            val valueDecls = decls.filter { it.kind == "class" || it.kind == "const" || it.kind == "let" }
            // type-space occupiers: class, type-alias.
            val typeDecls = decls.filter { it.kind == "class" || it.kind == "typeAlias" }

            if (valueDecls.size >= 2 && valueDecls.map { it.fileName }.distinct().size >= 2) {
                val anyBlock = valueDecls.any { it.kind == "const" || it.kind == "let" }
                if (anyBlock) crossFileIdentifierHandledBlockNames.add(name)
                emitCrossFileHubDuplicates(
                    valueDecls.map { CrossFileDupDecl(it.name, it.nameNode, it.fileName, it.source) },
                    if (anyBlock) 2451 else 2300,
                    if (anyBlock) "Cannot redeclare block-scoped variable '$name'."
                    else "Duplicate identifier '$name'.",
                )
            }
            if (typeDecls.size >= 2 && typeDecls.map { it.fileName }.distinct().size >= 2) {
                emitCrossFileHubDuplicates(
                    typeDecls.map { CrossFileDupDecl(it.name, it.nameNode, it.fileName, it.source) },
                    2300, "Duplicate identifier '$name'.",
                )
            }
        }
    }

    /**
     * One node a duplicate-declaration diagnostic can point at, for
     * [duplicateCallRelatedInfos].
     */
    class DuplicateRelatedTarget(
        val name: String,
        val fileName: String?,
        val line: Int?,
        val character: Int?,
        val start: Int? = null,
        val length: Int? = null,
    )

    /**
     * (LEGACY.0b) step 19: builds the related-information list of a duplicate-declaration
     * diagnostic from the merge CALLS that contributed to it.
     *
     * tsgo's `addDuplicateDeclarationError` (checker.go:14158) reads
     * `leading = len(err.RelatedInformation()) == 0`, which looks like a per-DIAGNOSTIC
     * index and is not one, because `lookupOrIssueError` finds an existing diagnostic
     * through `ast.CompareDiagnostics` — whose LAST comparison is `compareRelatedInfo`.
     * A probe built for the second call carries an empty related list, so it no longer
     * compares equal to the diagnostic the first call already decorated: the lookup MISSES
     * and a SECOND diagnostic is issued at the same location, again starting empty.
     * `SortAndDeduplicateDiagnostics` -> `compactAndMergeRelatedInfos` (program.go:1444)
     * then folds every diagnostic that is `EqualDiagnosticsNoRelatedInfo` into one and
     * unions their related lists.
     *
     * So the leading-vs-follow-on index is **per CALL, not per diagnostic**: the first
     * related node of each call is TS6203 `'{0}' was also declared here.` and the rest of
     * THAT call's nodes are TS6204 `and here.`. Verified mechanically against every tsgo
     * baseline carrying such a row — 128 files, 317 diagnostics, 0 unexplained.
     *
     * The two witnesses that fix the rule, both tsgo baselines:
     *  - `recursiveComplicatedClasses`: ONE symbol (the merged lib `Symbol`) with three
     *    declarations, so ONE call -> `[6203, 6204, 6204]` (see [checkClassShadowsLibType],
     *    which passes its lib files as a single call);
     *  - `duplicateIdentifierRelatedSpans1`: `Foo` declared in three separate FILES, so the
     *    binder merges pairwise and makes TWO calls of one node each -> `[6203, 6203]`.
     *
     * [calls] is one element per merge call, in call order.
     */
    fun duplicateCallRelatedInfos(
        calls: List<List<DuplicateRelatedTarget>>,
    ): List<Diagnostic> = calls.flatMap { nodes ->
        nodes.mapIndexed { idx, t ->
            Diagnostic(
                message = if (idx == 0) "'${t.name}' was also declared here." else "and here.",
                category = DiagnosticCategory.Message,
                code = if (idx == 0) 6203 else 6204,
                fileName = t.fileName, line = t.line, character = t.character,
                start = t.start, length = t.length,
            )
        }
    }

    /**
     * Emit a cross-file duplicate-identifier conflict with the HUB model (B93): the first
     * declaration in [ordered] (source-processing order) is the hub. The hub's diagnostic
     * relates to each other declaration and each other declaration relates back to the hub;
     * EVERY one of those related rows is TS6203, for the reason [duplicateCallRelatedInfos]
     * records — one merge CALL per other declaration, each carrying a single related node.
     */
    private fun emitCrossFileHubDuplicates(ordered: List<CrossFileDupDecl>, code: Int, message: String) {
        if (ordered.size < 2) return
        fun related(targets: List<CrossFileDupDecl>): List<Diagnostic> =
            duplicateCallRelatedInfos(targets.map { other ->
                val (ol, oc) = checker.getLineAndCharacterOfPosition(other.source, other.nameNode.pos)
                listOf(
                    DuplicateRelatedTarget(
                        name = other.name, fileName = other.fileName,
                        line = ol, character = oc,
                        start = other.nameNode.pos, length = other.name.length,
                    )
                )
            })
        fun emitOne(decl: CrossFileDupDecl, rel: List<Diagnostic>) {
            val (line, ch) = checker.getLineAndCharacterOfPosition(decl.source, decl.nameNode.pos)
            checker.diagnostics.add(Diagnostic(
                message = message,
                category = DiagnosticCategory.Error, code = code,
                fileName = decl.fileName, line = line, character = ch,
                start = decl.nameNode.pos, length = decl.name.length,
                relatedInformation = rel,
            ))
        }
        val hub = ordered.first()
        val others = ordered.drop(1)
        emitOne(hub, related(others))
        for (o in others) emitOne(o, related(listOf(hub)))
    }

    // -----------------------------------------------------------------------
    // TS2451 cross-file: block-scoped variable redeclarations across files
    // -----------------------------------------------------------------------

    /**
     * Check for top-level `let`/`const` redeclarations across non-module files.
     * When multiple non-module files share the global scope, a `let`/`const` in one file
     * conflicts with any same-named `let`, `const`, or `var` in another file.
     * Both files get TS2451 with TS6203 related info pointing to the other file.
     */
    fun checkCrossFileBlockScopedDuplicates() {
        // Collect top-level let/const/var names from non-module files
        // Map: name → list of (fileName, nameStart, nameLength, isBlockScoped)
        data class VarInfo(val fileName: String, val nameStart: Int, val nameLength: Int, val isBlockScoped: Boolean)
        val varDecls = mutableMapOf<String, MutableList<VarInfo>>()

        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            // Skip module files — they have their own scope
            if (checker.isModuleFile(result.sourceFile.statements)) continue
            for (stmt in result.sourceFile.statements) {
                if (stmt !is VariableStatement) continue
                val isBlockScoped = stmt.declarationList.flags == SyntaxKind.LetKeyword ||
                    stmt.declarationList.flags == SyntaxKind.ConstKeyword
                for (decl in stmt.declarationList.declarations) {
                    val name = decl.name as? Identifier ?: continue
                    if (name.text.isEmpty()) continue
                    varDecls.getOrPut(name.text) { mutableListOf() }.add(
                        VarInfo(fileName, name.pos, name.text.length, isBlockScoped)
                    )
                }
            }
        }

        // Emit TS2451 for names that appear in multiple files where at least one is block-scoped
        for ((name, infos) in varDecls) {
            if (infos.size < 2) continue
            // B93: skip names whose value-space conflict the unified checker already emitted
            // (those that also have a cross-file class/type-alias declaration).
            if (name in crossFileIdentifierHandledBlockNames) continue
            val anyBlockScoped = infos.any { it.isBlockScoped }
            if (!anyBlockScoped) continue  // only var+var conflicts are TS2300, not TS2451

            // Check for duplicates: skip if all declarations are in the same file
            val fileNames = infos.map { it.fileName }.toSet()
            if (fileNames.size < 2) continue

            // Emit TS2451 for each declaration, with TS6203 pointing to the other declaration(s)
            for (info in infos) {
                val source = binderResults.first { it.sourceFile.fileName == info.fileName }.sourceFile.text
                val (line, character) = checker.getLineAndCharacterOfPosition(source, info.nameStart)
                // Related info: one TS6203 per OTHER file declaring the same name
                val relatedInfo = infos
                    .filter { it.fileName != info.fileName }
                    .map { other ->
                        val otherSource = binderResults.first { it.sourceFile.fileName == other.fileName }.sourceFile.text
                        val (otherLine, otherChar) = checker.getLineAndCharacterOfPosition(otherSource, other.nameStart)
                        Diagnostic(
                            message = "'$name' was also declared here.",
                            category = DiagnosticCategory.Message,
                            code = 6203,
                            fileName = other.fileName,
                            line = otherLine,
                            character = otherChar,
                            start = other.nameStart,
                            length = other.nameLength,
                        )
                    }
                checker.diagnostics.add(Diagnostic(
                    message = "Cannot redeclare block-scoped variable '$name'.",
                    category = DiagnosticCategory.Error,
                    code = 2451,
                    fileName = info.fileName,
                    line = line,
                    character = character,
                    start = info.nameStart,
                    length = info.nameLength,
                    relatedInformation = relatedInfo,
                ))
            }
        }
    }

    /**
     * 17.108: Cross-file detection of enum-merge conflicts (TS2567).
     *
     * The per-file [checkDuplicateDeclarations] walker only sees declarations
     * within a single file's top-level. When two non-module (script) files
     * share global scope and one declares `enum X` while another declares
     * `class X` / `function X` / `var X` / `interface X`, the merged group
     * is invalid and TS2567 must fire on each non-namespace declaration with
     * TS6203 related info pointing to the cross-file partner(s).
     *
     * Skip:
     *  - module files (separate scope per file)
     *  - .d.ts files (per-file walker also skips them)
     *  - declarations whose own file ALREADY has an enum + non-enum/non-namespace
     *    pair — the per-file walker already emits TS2567 on those positions.
     */
    fun checkCrossFileEnumConflicts() {
        data class XInfo(
            val name: String, val kind: String,
            val nameNode: Node, val fileName: String, val source: String,
        )
        val byName = mutableMapOf<String, MutableList<XInfo>>()

        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            if (checker.isModuleFile(result.sourceFile.statements)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                when (stmt) {
                    is EnumDeclaration -> byName.getOrPut(stmt.name.text) { mutableListOf() }
                        .add(XInfo(stmt.name.text, "enum", stmt.name, fileName, source))
                    is ClassDeclaration -> stmt.name?.let {
                        byName.getOrPut(it.text) { mutableListOf() }
                            .add(XInfo(it.text, "class", it, fileName, source))
                    }
                    is FunctionDeclaration -> stmt.name?.let {
                        byName.getOrPut(it.text) { mutableListOf() }
                            .add(XInfo(it.text, "function", it, fileName, source))
                    }
                    is InterfaceDeclaration -> byName.getOrPut(stmt.name.text) { mutableListOf() }
                        .add(XInfo(stmt.name.text, "interface", stmt.name, fileName, source))
                    is ModuleDeclaration -> {
                        val n = stmt.name as? Identifier ?: continue
                        byName.getOrPut(n.text) { mutableListOf() }
                            .add(XInfo(n.text, "namespace", n, fileName, source))
                    }
                    is VariableStatement -> {
                        if (stmt.declarationList.flags != SyntaxKind.VarKeyword) continue
                        for (decl in stmt.declarationList.declarations) {
                            val n = decl.name as? Identifier ?: continue
                            if (n.text.isEmpty()) continue
                            byName.getOrPut(n.text) { mutableListOf() }
                                .add(XInfo(n.text, "var", n, fileName, source))
                        }
                    }
                    else -> {}
                }
            }
        }

        for ((_, infos) in byName) {
            if (infos.size < 2) continue
            // Need cross-file presence
            if (infos.map { it.fileName }.toSet().size < 2) continue
            // Need an enum + non-enum/non-namespace partner across the global merged group
            val hasEnum = infos.any { it.kind == "enum" }
            val nonEnumKinds = setOf("class", "function", "var", "interface")
            val hasNonEnum = infos.any { it.kind in nonEnumKinds }
            if (!(hasEnum && hasNonEnum)) continue

            for (info in infos) {
                if (info.kind == "namespace") continue
                // Skip if the per-file walker already emits for this position:
                // its own file's local group has both enum and non-enum.
                val sameFile = infos.filter { it.fileName == info.fileName }
                val sameFileHasEnum = sameFile.any { it.kind == "enum" }
                val sameFileHasNonEnum = sameFile.any { it.kind in nonEnumKinds }
                if (sameFileHasEnum && sameFileHasNonEnum) continue

                val others = infos.filter { it.fileName != info.fileName && it.kind != "namespace" }
                if (others.isEmpty()) continue
                val relatedInfo = others.map { other ->
                    val (oLine, oChar) = checker.getLineAndCharacterOfPosition(other.source, other.nameNode.pos)
                    Diagnostic(
                        message = "'${other.name}' was also declared here.",
                        category = DiagnosticCategory.Message,
                        code = 6203,
                        fileName = other.fileName,
                        line = oLine,
                        character = oChar,
                        start = other.nameNode.pos,
                        length = other.name.length,
                    )
                }
                val (line, character) = checker.getLineAndCharacterOfPosition(info.source, info.nameNode.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "Enum declarations can only merge with namespace or other enum declarations.",
                    category = DiagnosticCategory.Error,
                    code = 2567,
                    fileName = info.fileName,
                    line = line,
                    character = character,
                    start = info.nameNode.pos,
                    length = info.name.length,
                    relatedInformation = relatedInfo,
                ))
            }
        }
    }

    /**
     * B92: Cross-file duplicate-identifier conflicts for MERGED interface members.
     *
     * When the same interface name is declared in two different files that share a
     * scope — global script scope, a `declare global { }` block, or a
     * `declare module "X" { }` block — and a member appears as a PROPERTY in one
     * file and a METHOD in another, the merged interface has a duplicate-identifier
     * conflict (property-vs-method is always a hard conflict in TypeScript).
     *
     * TypeScript 7 emits per-member TS2300 "Duplicate identifier 'X'." with a related
     * TS6203 "'X' was also declared here." pointing to the other file's member — for
     * EVERY conflicting name. (tsc 6 collapsed eight-or-more into one TS6200 per file;
     * (LEGACY.0b) step 8 removed that — see [amalgamateAndEmitCrossFileDuplicates].)
     *
     * Gate is narrow (property-vs-method, cross-file, same interface name in a shared
     * scope) so the FP surface is tiny: such a pairing is always a genuine error.
     */
    fun checkCrossFileInterfaceMemberConflicts() {
        // A merged-interface member declaration site.
        data class MemberDecl(
            val memberName: String,
            val nameNode: Node,   // member name node (for the squiggle)
            val isMethod: Boolean,
            val fileName: String,
            val source: String,
        )
        // scopeKey -> (interfaceName -> member decls across files). scopeKey is
        // "global" for script/`declare global` interfaces, "module:<spec>" for
        // ambient-module interfaces. Namespace-scoped interfaces are excluded
        // (they do not merge across files).
        val groups = mutableMapOf<String, MutableMap<String, MutableList<MemberDecl>>>()
        val fileSources = mutableMapOf<String, String>()

        fun addInterface(scopeKey: String, iface: InterfaceDeclaration, fileName: String, source: String) {
            val list = groups.getOrPut(scopeKey) { mutableMapOf() }
                .getOrPut(iface.name.text) { mutableListOf() }
            for (member in iface.members) {
                val (nameNode, isMethod) = when (member) {
                    is PropertyDeclaration -> member.name to false
                    is MethodDeclaration -> member.name to true
                    else -> continue
                }
                val text = checker.getMemberNameText(nameNode) ?: continue
                if (text.isEmpty() || text == "new") continue
                list.add(MemberDecl(text, nameNode, isMethod, fileName, source))
            }
        }

        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            val source = result.sourceFile.text
            fileSources[fileName] = source
            val isModule = checker.isModuleFile(result.sourceFile.statements)
            for (stmt in result.sourceFile.statements) {
                when (stmt) {
                    is InterfaceDeclaration ->
                        // A top-level interface merges into the global scope only in
                        // script files; in module files it is module-local.
                        if (!isModule) addInterface("global", stmt, fileName, source)
                    is ModuleDeclaration -> {
                        val body = stmt.body as? ModuleBlock ?: continue
                        val nm = stmt.name
                        when {
                            nm is Identifier && nm.text == "global" ->
                                for (inner in body.statements)
                                    if (inner is InterfaceDeclaration) addInterface("global", inner, fileName, source)
                            nm is StringLiteralNode ->
                                for (inner in body.statements)
                                    if (inner is InterfaceDeclaration)
                                        addInterface("module:" + nm.text, inner, fileName, source)
                            else -> {}
                        }
                    }
                    else -> {}
                }
            }
        }

        // Build conflicting member declarations (property-vs-method, cross-file).
        val entries = mutableListOf<CrossFileDupDecl>()
        for ((_, byIface) in groups) {
            for ((_, members) in byIface) {
                for ((_, decls) in members.groupBy { it.memberName }) {
                    val distinctFiles = decls.map { it.fileName }.distinct()
                    if (distinctFiles.size != 2) continue   // only the common 2-file case
                    // Cross-file property-vs-method conflict: a property in one file
                    // and a method in another (different) file.
                    val propFiles = decls.filter { !it.isMethod }.map { it.fileName }.toSet()
                    val methFiles = decls.filter { it.isMethod }.map { it.fileName }.toSet()
                    val crossConflict = propFiles.any { pf -> methFiles.any { mf -> pf != mf } }
                    if (!crossConflict) continue
                    for (d in decls) entries.add(CrossFileDupDecl(d.memberName, d.nameNode, d.fileName, d.source))
                }
            }
        }
        amalgamateAndEmitCrossFileDuplicates(entries)
    }

    /** One conflicting declaration site for cross-file duplicate-identifier reporting. */
    private class CrossFileDupDecl(
        val name: String,
        val nameNode: Node,
        val fileName: String,
        val source: String,
    )

    /**
     * Shared amalgamation + emission for cross-file duplicate-identifier conflicts
     * (B92). [entries] are the conflicting declaration sites; the caller has already
     * decided they conflict. Groups by name, restricts to the common 2-distinct-file
     * case, amalgamates per ordered file-pair, and emits per-member TS2300 "Duplicate
     * identifier 'X'." + related TS6203 "'X' was also declared here." for every one.
     *
     * (LEGACY.0b) step 8 removed tsc 6's `conflictingSymbols.size < 8` threshold and the
     * TS6200/TS6201 collapse it selected: TypeScript 7 has no such diagnostic at all.
     */
    private fun amalgamateAndEmitCrossFileDuplicates(entries: List<CrossFileDupDecl>) {
        if (entries.isEmpty()) return
        val fileSources = mutableMapOf<String, String>()
        for (e in entries) fileSources.getOrPut(e.fileName) { e.source }

        class PairConflict(val firstFile: String, val secondFile: String) {
            val names = mutableListOf<String>()
            val firstLocs = mutableMapOf<String, MutableList<CrossFileDupDecl>>()
            val secondLocs = mutableMapOf<String, MutableList<CrossFileDupDecl>>()
        }
        val amalgam = mutableMapOf<String, PairConflict>()

        for ((name, decls) in entries.groupBy { it.name }) {
            val distinctFiles = decls.map { it.fileName }.distinct()
            if (distinctFiles.size != 2) continue
            val (fA, fB) = distinctFiles.sorted()
            val pc = amalgam.getOrPut("$fA|$fB") { PairConflict(fA, fB) }
            if (name !in pc.names) pc.names.add(name)
            pc.firstLocs.getOrPut(name) { mutableListOf() }.addAll(decls.filter { it.fileName == fA })
            pc.secondLocs.getOrPut(name) { mutableListOf() }.addAll(decls.filter { it.fileName == fB })
        }

        fun emit2300(decl: CrossFileDupDecl, related: List<CrossFileDupDecl>) {
            val (line, ch) = checker.getLineAndCharacterOfPosition(decl.source, decl.nameNode.pos)
            val rel = related.map { other ->
                val (ol, oc) = checker.getLineAndCharacterOfPosition(other.source, other.nameNode.pos)
                Diagnostic(
                    message = "'${decl.name}' was also declared here.",
                    category = DiagnosticCategory.Message, code = 6203,
                    fileName = other.fileName, line = ol, character = oc,
                    start = other.nameNode.pos, length = other.name.length,
                )
            }
            checker.diagnostics.add(Diagnostic(
                message = "Duplicate identifier '${decl.name}'.",
                category = DiagnosticCategory.Error, code = 2300,
                fileName = decl.fileName, line = line, character = ch,
                start = decl.nameNode.pos, length = decl.name.length,
                relatedInformation = rel,
            ))
        }

        // (LEGACY.0b) step 8: ALWAYS per-identifier. tsc 6 collapsed a pair of files
        // conflicting in EIGHT OR MORE names into one TS6200 "Definitions of the following
        // identifiers conflict with those in another file: a, b, c" per file, plus a
        // related TS6201 "Conflicts are in this file."; TypeScript 7 has neither
        // diagnostic. Neither message exists anywhere in tsgo's source or in any of its
        // baselines, and the three `duplicateIdentifierRelatedSpans` layers that carried
        // them record their REMOVAL. So the threshold and both emitters are GONE rather
        // than raised: there is no number at which the collapse is right.
        for ((_, pc) in amalgam) {
            for (name in pc.names) {
                val firstNodes = pc.firstLocs[name] ?: emptyList()
                val secondNodes = pc.secondLocs[name] ?: emptyList()
                for (fn in firstNodes) emit2300(fn, secondNodes)
                for (sn in secondNodes) emit2300(sn, firstNodes)
            }
        }
    }

    /**
     * B92d: Cross-file duplicate-identifier conflicts for top-level CLASS declarations
     * in script (non-module) files sharing the global scope. Two same-named class
     * declarations in different script files always conflict (classes never merge with
     * classes), so this is a genuine TS2300 at every declaration. Restricted to script
     * files (module
     * files are scoped separately) and the 2-distinct-file case to keep FP surface
     * minimal. Class-vs-interface / class-vs-namespace MERGES are unaffected (only the
     * class-vs-class pair conflicts; an interface/namespace partner does not rescue it).
     */
    fun checkCrossFileClassConflicts() {
        val byName = mutableMapOf<String, MutableList<CrossFileDupDecl>>()
        for (result in binderResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isModuleFile(result.sourceFile.statements)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                if (stmt is ClassDeclaration) {
                    val nm = stmt.name ?: continue
                    if (nm.text.isEmpty()) continue
                    byName.getOrPut(nm.text) { mutableListOf() }
                        .add(CrossFileDupDecl(nm.text, nm, fileName, source))
                }
            }
        }
        val entries = mutableListOf<CrossFileDupDecl>()
        for ((_, decls) in byName) {
            // Conflict only when the SAME class name is declared in 2 different files.
            if (decls.map { it.fileName }.distinct().size != 2) continue
            entries.addAll(decls)
        }
        amalgamateAndEmitCrossFileDuplicates(entries)
    }

    /**
     * TS2451: a UMD global declared via `export as namespace X` and a `declare global`
     * `const`/`let X` redeclare the same block-scoped variable X. Both are global VALUE
     * bindings named X → "Cannot redeclare block-scoped variable 'X'." reported at each
     * occurrence (UMD name + each const/let name) with a TS6203 related-info pointing at
     * the other(s). The parser misparses `export as namespace X` (no AST node — see the
     * scanner/parser gotchas), so the UMD side is found by a source regex; the const/let
     * side via the `declare global { ... }` AST. FP-safe: fires ONLY when a name has BOTH
     * a UMD occurrence AND a declare-global const/let occurrence — both are unconditional
     * TS2451 in TypeScript, and .d.ts files (where these live) skip the general duplicate
     * pipeline, so there is no double-emit.
     */
    fun checkUmdGlobalVsDeclareGlobalConst() {
        data class Occ(val fileName: String, val source: String, val pos: Int, val len: Int)
        val umdByName = mutableMapOf<String, MutableList<Occ>>()
        val constByName = mutableMapOf<String, MutableList<Occ>>()
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            val source = result.sourceFile.text
            // (WARM.7) round 860: shared with checkCrossFileModuleAugmentationDuplicates.
            for (occ in checker.umdExportAsNamespaceOccurrences(fileName, source)) {
                umdByName.getOrPut(occ.name) { mutableListOf() }
                    .add(Occ(fileName, source, occ.pos, occ.name.length))
            }
            for (stmt in result.sourceFile.statements) {
                if (stmt !is ModuleDeclaration) continue
                if ((stmt.name as? Identifier)?.text != "global") continue
                if (ModifierFlag.Declare !in stmt.modifiers) continue
                val body = stmt.body as? ModuleBlock ?: continue
                for (s in body.statements) {
                    if (s !is VariableStatement) continue
                    val flags = s.declarationList.flags
                    if (flags != SyntaxKind.ConstKeyword && flags != SyntaxKind.LetKeyword) continue
                    for (d in s.declarationList.declarations) {
                        val nm = d.name as? Identifier ?: continue
                        constByName.getOrPut(nm.text) { mutableListOf() }
                            .add(Occ(fileName, source, nm.pos, nm.text.length))
                    }
                }
            }
        }
        for ((name, umd) in umdByName) {
            val cons = constByName[name] ?: continue
            val all = umd + cons
            if (all.size < 2) continue
            for (occ in all) {
                val related = all.filter { it !== occ }.map { other ->
                    val (ol, oc) = checker.getLineAndCharacterOfPosition(other.source, other.pos)
                    Diagnostic(
                        message = "'$name' was also declared here.",
                        category = DiagnosticCategory.Message, code = 6203,
                        fileName = other.fileName, line = ol, character = oc,
                        start = other.pos, length = other.len,
                    )
                }
                val (line, ch) = checker.getLineAndCharacterOfPosition(occ.source, occ.pos)
                checker.diagnostics.add(Diagnostic(
                    message = "Cannot redeclare block-scoped variable '$name'.",
                    category = DiagnosticCategory.Error, code = 2451,
                    fileName = occ.fileName, line = line, character = ch,
                    start = occ.pos, length = occ.len,
                    relatedInformation = related,
                ))
            }
        }
    }

    /**
     * B92d: TS2451 for a block-scoped `export const`/`export let` that is re-declared
     * via `declare module "X"` augmentation(s) of the SAME file-module (which already
     * exports it), or across two augmentations of the same module. Re-declaring an
     * existing block-scoped export through an augmentation is illegal. The original
     * declaration is the "hub": its diagnostic accumulates a related TS6203 (then
     * TS6204 for each subsequent) pointing at each augmentation re-declaration; each
     * augmentation re-declaration points back at the hub (TS6203). The existing
     * `checkCrossFileBlockScopedDuplicates` (step 73b) skips module files, so there is
     * no double-emit. Gate is narrow (augmentation re-declaring an existing
     * block-scoped file-module export) → tiny FP surface.
     */
    fun checkCrossFileModuleAugmentationDuplicates() {
        class Decl(val name: String, val nameNode: Node, val fileName: String, val source: String)
        fun isBlockScopedExport(stmt: VariableStatement): Boolean =
            ModifierFlag.Export in stmt.modifiers &&
                (stmt.declarationList.flags == SyntaxKind.LetKeyword ||
                    stmt.declarationList.flags == SyntaxKind.ConstKeyword)

        // A file-module's OWN top-level block-scoped exports: moduleFile -> (name -> Decl).
        val ownExports = mutableMapOf<String, MutableMap<String, Decl>>()
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (!checker.isModuleFile(result.sourceFile.statements)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                if (stmt is VariableStatement && isBlockScopedExport(stmt)) {
                    for (d in stmt.declarationList.declarations) {
                        val nm = d.name as? Identifier ?: continue
                        ownExports.getOrPut(fileName) { mutableMapOf() }[nm.text] =
                            Decl(nm.text, nm, fileName, source)
                    }
                }
            }
        }

        // Augmentation block-scoped exports grouped by (targetFile, name), in source order.
        val augByTarget = mutableMapOf<Pair<String, String>, MutableList<Decl>>()
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                if (stmt !is ModuleDeclaration) continue
                val spec = (stmt.name as? StringLiteralNode)?.text ?: continue
                val body = stmt.body as? ModuleBlock ?: continue
                val target = checker.resolveModuleSpecifierRelative(spec, fileName) ?: continue
                for (inner in body.statements) {
                    if (inner is VariableStatement && isBlockScopedExport(inner)) {
                        for (d in inner.declarationList.declarations) {
                            val nm = d.name as? Identifier ?: continue
                            augByTarget.getOrPut(target to nm.text) { mutableListOf() }
                                .add(Decl(nm.text, nm, fileName, source))
                        }
                    }
                }
            }
        }

        // B555: a `declare global { namespace X { export const Y } }` member ALSO merges into the
        // module projected by `export as namespace X` (a UMD-global module), so project those onto
        // the module's (targetFile, name) conflict groups (exportAsNamespace_augment).
        val umdToModuleFile = mutableMapOf<String, String>()
        for (result in checker.checkedResults) {
            // (WARM.7) round 860: shared with checkUmdGlobalVsDeclareGlobalConst.
            val umdFileName = result.sourceFile.fileName
            for (occ in checker.umdExportAsNamespaceOccurrences(umdFileName, result.sourceFile.text)) {
                umdToModuleFile.getOrPut(occ.name) { umdFileName }
            }
        }
        if (umdToModuleFile.isNotEmpty()) {
            for (result in checker.checkedResults) {
                val fileName = result.sourceFile.fileName
                val source = result.sourceFile.text
                for (stmt in result.sourceFile.statements) {
                    if (stmt !is ModuleDeclaration) continue
                    if ((stmt.name as? Identifier)?.text != "global") continue
                    if (ModifierFlag.Declare !in stmt.modifiers) continue
                    val gbody = stmt.body as? ModuleBlock ?: continue
                    for (ns in gbody.statements) {
                        if (ns !is ModuleDeclaration) continue
                        val nsName = (ns.name as? Identifier)?.text ?: continue
                        val moduleFile = umdToModuleFile[nsName] ?: continue
                        val nsBody = ns.body as? ModuleBlock ?: continue
                        for (inner in nsBody.statements) {
                            if (inner is VariableStatement && isBlockScopedExport(inner)) {
                                for (d in inner.declarationList.declarations) {
                                    val nm = d.name as? Identifier ?: continue
                                    augByTarget.getOrPut(moduleFile to nm.text) { mutableListOf() }
                                        .add(Decl(nm.text, nm, fileName, source))
                                }
                            }
                        }
                    }
                }
            }
        }

        fun emit2451(decl: Decl, related: List<Decl>) {
            val (line, ch) = checker.getLineAndCharacterOfPosition(decl.source, decl.nameNode.pos)
            // Each augmentation re-declaration is a SEPARATE merge into the target module's
            // symbol, i.e. one call carrying one related node — so every row is TS6203.
            // See [duplicateCallRelatedInfos].
            val rel = duplicateCallRelatedInfos(related.map { other ->
                val (ol, oc) = checker.getLineAndCharacterOfPosition(other.source, other.nameNode.pos)
                listOf(
                    DuplicateRelatedTarget(
                        name = other.name, fileName = other.fileName,
                        line = ol, character = oc,
                        start = other.nameNode.pos, length = other.name.length,
                    )
                )
            })
            checker.diagnostics.add(Diagnostic(
                message = "Cannot redeclare block-scoped variable '${decl.name}'.",
                category = DiagnosticCategory.Error, code = 2451,
                fileName = decl.fileName, line = line, character = ch,
                start = decl.nameNode.pos, length = decl.name.length,
                relatedInformation = rel,
            ))
        }

        for ((key, augs) in augByTarget) {
            val (target, name) = key
            val own = ownExports[target]?.get(name)
            val total = (if (own != null) 1 else 0) + augs.size
            if (total < 2) continue   // single declaration: a valid augmentation, no conflict
            val hub: Decl
            val others: List<Decl>
            if (own != null) {
                hub = own
                others = augs.sortedWith(compareBy({ it.fileName }, { it.nameNode.pos }))
            } else {
                hub = augs.first()
                others = augs.drop(1).sortedWith(compareBy({ it.fileName }, { it.nameNode.pos }))
            }
            emit2451(hub, others)
            for (o in others) emit2451(o, listOf(hub))
        }
    }

    /**
     * B412: TS2300/TS2451 for a name declared in a `declare module "X"` AUGMENTATION that
     * is ALSO re-exported into module X via `export {N} from './other'`. Both contribute N
     * to module X's shape → duplicate. tsc reports at BOTH the augmentation declaration and
     * the re-export specifier, each with a TS6203 "was also declared here" pointing at the
     * other.
     *
     * Code by augmentation-decl kind: a TYPE-ALIAS augmentation decl → TS2300 (type aliases
     * never declaration-merge); a block-scoped const/let → TS2451 (block-scoped values can't
     * merge with a re-exported value). The whole `.d.ts` duplicate-identifier pipeline is
     * skipped, and the B92d augmentation walker only handles the target's OWN exports (not
     * re-exports) with EXPORTED augmentation decls — so this shape (non-exported aug decl vs
     * a re-export) is uncovered. FP-safe: a re-exported name + a non-mergeable augmentation
     * decl (type alias / block-scoped var) is always a genuine duplicate.
     *
     * The `.`/`./` package-index specifier (which `resolveModuleSpecifier` doesn't resolve)
     * is handled locally via [resolveAugmentationTargetFile] — the sibling `index.{ts,d.ts}`
     * in the augmentation file's directory.
     */
    fun checkModuleAugmentationReexportDuplicates() {
        for (result in binderResults) {
            val augFile = result.sourceFile.fileName
            val augSource = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                if (stmt !is ModuleDeclaration) continue
                val spec = (stmt.name as? StringLiteralNode)?.text ?: continue
                val body = stmt.body as? ModuleBlock ?: continue
                val targetFile = resolveAugmentationTargetFile(spec, augFile) ?: continue
                if (targetFile == augFile) continue
                val targetResult = binderResults.firstOrNull { it.sourceFile.fileName == targetFile } ?: continue
                // Re-exported names in the target module (`export {N} from './x'`).
                val reexports = mutableMapOf<String, Identifier>()
                for (ts in targetResult.sourceFile.statements) {
                    if (ts !is ExportDeclaration || ts.moduleSpecifier == null) continue
                    val clause = ts.exportClause as? NamedExports ?: continue
                    for (es in clause.elements) {
                        if (es.name.text !in reexports) reexports[es.name.text] = es.name
                    }
                }
                if (reexports.isEmpty()) continue
                // Augmentation decls that MERGE into the module: when the body has module
                // syntax (export statements) only exported decls merge, otherwise all do.
                val augHasModuleSyntax = body.statements.any { it is ExportDeclaration || it is ExportAssignment }
                for (bs in body.statements) {
                    when (bs) {
                        is TypeAliasDeclaration -> {
                            if (augHasModuleSyntax && ModifierFlag.Export !in bs.modifiers) continue
                            val re = reexports[bs.name.text] ?: continue
                            emitAugReexportDup(bs.name.text, bs.name, augFile, augSource, re,
                                targetFile, targetResult.sourceFile.text, 2300)
                        }
                        is VariableStatement -> {
                            if (augHasModuleSyntax && ModifierFlag.Export !in bs.modifiers) continue
                            val flags = bs.declarationList.flags
                            if (flags != SyntaxKind.ConstKeyword && flags != SyntaxKind.LetKeyword) continue
                            for (d in bs.declarationList.declarations) {
                                val n = d.name as? Identifier ?: continue
                                val re = reexports[n.text] ?: continue
                                emitAugReexportDup(n.text, n, augFile, augSource, re,
                                    targetFile, targetResult.sourceFile.text, 2451)
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    /** B412: resolve a `declare module "spec"` augmentation's target source file. Handles the
     *  `.`/`./` package-index specifier (sibling `index.{ts,tsx,d.ts}` in the augmentation
     *  file's directory) which [resolveModuleSpecifier] does not; otherwise delegates. */
    fun resolveAugmentationTargetFile(spec: String, augFile: String): String? {
        if (spec == "." || spec == "./") {
            val dir = augFile.substringBeforeLast('/', "")
            for (idx in listOf("index.ts", "index.tsx", "index.d.ts")) {
                val cand = if (dir.isEmpty()) idx else "$dir/$idx"
                if (binderResults.any { it.sourceFile.fileName == cand }) return cand
            }
            return null
        }
        return checker.resolveModuleSpecifierRelative(spec, augFile)
    }

    /** B412: emit the symmetric TS2300/TS2451 duplicate pair (augmentation decl ↔ re-export
     *  specifier), each carrying a TS6203 "was also declared here" pointing at the other. */
    private fun emitAugReexportDup(
        name: String, augNode: Node, augFile: String, augSource: String,
        reNode: Identifier, reFile: String, reSource: String, code: Int,
    ) {
        val msg = if (code == 2451) "Cannot redeclare block-scoped variable '$name'."
                  else "Duplicate identifier '$name'."
        fun skipTrivia(src: String, p: Int): Int {
            var i = p
            while (i < src.length && (src[i] == ' ' || src[i] == '\t' || src[i] == '\r' || src[i] == '\n')) i++
            return i
        }
        val augPos = skipTrivia(augSource, augNode.pos)
        val rePos = skipTrivia(reSource, reNode.pos)
        val (al, ac) = checker.getLineAndCharacterOfPosition(augSource, augPos)
        val (rl, rc) = checker.getLineAndCharacterOfPosition(reSource, rePos)
        checker.diagnostics.add(Diagnostic(
            message = msg, category = DiagnosticCategory.Error, code = code,
            fileName = augFile, line = al, character = ac, start = augPos, length = name.length,
            relatedInformation = listOf(Diagnostic(
                message = "'$name' was also declared here.", category = DiagnosticCategory.Message, code = 6203,
                fileName = reFile, line = rl, character = rc, start = rePos, length = name.length)),
        ))
        checker.diagnostics.add(Diagnostic(
            message = msg, category = DiagnosticCategory.Error, code = code,
            fileName = reFile, line = rl, character = rc, start = rePos, length = name.length,
            relatedInformation = listOf(Diagnostic(
                message = "'$name' was also declared here.", category = DiagnosticCategory.Message, code = 6203,
                fileName = augFile, line = al, character = ac, start = augPos, length = name.length)),
        ))
    }

    /**
     * B553: a checkJs CJS `module.exports = { name: "<lit>" }` JS module exports `name` (typed
     * `string` from the literal). Since the JS export wins, an `import { name } from "./X";
     * name.<method>()` where <method> is absent from `string`'s apparent type → TS2551
     * (+ TS2728). Purely ADDITIVE: a CJS import resolves to `any` today (B152/B153 `.js` skip is
     * NOT un-gated), so the general paths emit nothing. FP firewall (corpus-unique,
     * jsExportMemberMergedWithModuleAugmentation2): the augmentation member must be
     * `export const/var` (a VALUE re-declaration — an `interface` legally merges with a JS class
     * export, base sibling excluded) AND the CJS target must be `module.exports = {objLit}` with
     * a matching STRING-LITERAL-valued key.
     *
     * (P18.121) The TS2300 PAIR this walker also used to emit is RETIRED — see the comment at
     * its former site. TypeScript 6 merged the augmentation and reported the collision; tsgo
     * 7.0.2 refuses the augmentation outright with TS2671 and never creates the second
     * declaration. The `conflicted` set it populated is still computed, because it is what
     * selects the imports PIECE 2 reports on.
     */
    fun checkCjsExportAugmentationConflict() {
        if (!options.checkJs) return
        for (result in binderResults) {
            val augFile = result.sourceFile.fileName
            val augSource = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                if (stmt !is ModuleDeclaration) continue
                val spec = (stmt.name as? StringLiteralNode)?.text ?: continue
                val body = stmt.body as? ModuleBlock ?: continue
                // JS-aware resolution (resolveAugmentationTargetFile/resolveModuleSpecifier
                // deliberately skip .js extensions) — the CJS target is a `.js` file.
                val targetFile = checker.resolveRelativeIncludingIndex(spec, augFile)
                    ?: resolveAugmentationTargetFile(spec, augFile) ?: continue
                if (targetFile == augFile || !checker.isJsLikeFileName(targetFile)) continue
                val targetResult = binderResults.firstOrNull { it.sourceFile.fileName == targetFile } ?: continue
                // Collect `module.exports = {objLit}` STRING-literal-valued keys → name node.
                val cjsStringExports = collectCjsObjectLiteralStringExports(targetResult.sourceFile)
                if (cjsStringExports.isEmpty()) continue
                // PIECE 1: augmentation `export const/var name` matching a CJS string key → TS2300.
                val conflicted = HashSet<String>()
                for (bs in body.statements) {
                    if (bs !is VariableStatement) continue
                    if (ModifierFlag.Export !in bs.modifiers) continue
                    val flags = bs.declarationList.flags
                    if (flags != SyntaxKind.ConstKeyword && flags != SyntaxKind.LetKeyword &&
                        flags != SyntaxKind.VarKeyword) continue
                    for (d in bs.declarationList.declarations) {
                        val n = d.name as? Identifier ?: continue
                        if (n.text !in cjsStringExports) continue
                        // (P18.121) The TS2300 PAIR this walker used to emit here is retired:
                        // tsgo reports TS2671 at the augmentation's module name INSTEAD of
                        // merging (`checker.go:1447`), so there is no second declaration left to
                        // collide with. The two conditions coincide by construction — this
                        // walker already requires `module.exports = {objLit}`, which is exactly
                        // a non-module `export =` target — so the row is never merely moved, it
                        // is replaced. PIECE 2 below is UNCHANGED and still correct: the JS
                        // export still wins, so `a` is `string` and `a.toFixed()` is TS2551.
                        conflicted.add(n.text)
                    }
                }
                if (conflicted.isEmpty()) continue
                // PIECE 2: `import { name } from "<spec>"` then `name.<method>()` where the import
                // binds a conflicted CJS string export and <method> ∉ string → TS2551.
                val importedStringNames = HashSet<String>()
                for (s in result.sourceFile.statements) {
                    if (s !is ImportDeclaration) continue
                    if ((s.moduleSpecifier as? StringLiteralNode)?.text != spec) continue
                    val named = (s.importClause?.namedBindings as? NamedImports) ?: continue
                    for (el in named.elements) {
                        val local = el.name.text
                        // imported source name (alias `X as Y` → propertyName=X) must be conflicted
                        val srcName = (el.propertyName?.text) ?: local
                        if (srcName in conflicted) importedStringNames.add(local)
                    }
                }
                if (importedStringNames.isNotEmpty()) {
                    emitCjsStringImportMethodAccess(result.sourceFile, augFile, augSource, importedStringNames)
                }
            }
        }
    }

    /** B553: collect `module.exports = { key: "<string literal>" }` keys → key Identifier node. */
    private fun collectCjsObjectLiteralStringExports(sf: SourceFile): Map<String, Identifier> {
        val out = HashMap<String, Identifier>()
        for (stmt in sf.statements) {
            val bin = (stmt as? ExpressionStatement)?.expression as? BinaryExpression ?: continue
            if (bin.operator != SyntaxKind.Equals) continue
            val lhs = bin.left as? PropertyAccessExpression ?: continue
            if ((lhs.expression as? Identifier)?.text != "module" || lhs.name.text != "exports") continue
            val rhs = bin.right as? ObjectLiteralExpression ?: continue
            for (p in rhs.properties) {
                if (p !is PropertyAssignment) continue
                val nameId = p.name as? Identifier ?: continue
                if (p.initializer is StringLiteralNode) out.getOrPut(nameId.text) { nameId }
            }
        }
        return out
    }

    /** B553: emit TS2551 for `name.<method>()` (name ∈ [names], typed `string`) where <method>
     *  is not a `string` apparent-type member, with a `Did you mean '<sugg>'?` spelling hint. */
    private fun emitCjsStringImportMethodAccess(
        sf: SourceFile, file: String, src: String, names: Set<String>,
    ) {
        val apparent = checker.getApparentType(stringType) as? Type.Object ?: return
        checker.resolveStructuredTypeMembers(apparent)
        val memberNames = (apparent.properties ?: emptyList()).map { it.name }.toSet()
        fun handleAccess(pa: PropertyAccessExpression) {
            if ((pa.expression as? Identifier)?.text !in names) return
            val method = pa.name.text
            if (method in Checker.RUNTIME_PROPERTIES || method in memberNames) return
            val suggestion = checker.getSpellingSuggestionFromNames(method, memberNames)
            val nameNode = pa.name
            val diagStart = nameNode.pos
            val (line, character) = checker.getLineAndCharacterOfPosition(src, diagStart)
            val related = if (suggestion != null) {
                val sp = apparent.properties?.find { it.name == suggestion }
                val decl = sp?.valueDeclaration ?: sp?.declarations?.firstOrNull()
                val declPos = when (val dn = (decl as? PropertyDeclaration)?.name ?: (decl as? MethodDeclaration)?.name) {
                    is Identifier -> dn.pos
                    else -> decl?.pos ?: 0
                }
                // M2.2 (round 394): node-first lib attribution (see libFileOfDecl) — under
                // multi-file real libs a lib member's position false-matches a large user
                // file (`fixed` on the real String interface → /index.ts:8:18528). The map
                // is empty under the embedded lib, so this is byte-identical there.
                val libMapped = checker.libFileOfDecl(decl)
                val (declFile, _) = if (libMapped != null) Pair(libMapped, null)
                    else checker.resolveDeclarationSourceFile(declPos)
                val resolvedFile = declFile ?: file
                val isLib = libMapped != null || checker.isLibFileName(resolvedFile)
                val baselineFile = if (isLib && suggestion in Checker.DEPRECATED_STRING_HTML_HELPERS)
                    "lib.es2015.core.d.ts" else resolvedFile
                listOf(Diagnostic(
                    message = "'$suggestion' is declared here.", category = DiagnosticCategory.Message,
                    code = 2728, fileName = baselineFile,
                    line = if (isLib) null else checker.getLineAndCharacterOfPosition(src, declPos).first,
                    character = if (isLib) null else checker.getLineAndCharacterOfPosition(src, declPos).second,
                    start = declPos, length = suggestion.length))
            } else emptyList()
            val msg = if (suggestion != null)
                "Property '$method' does not exist on type 'string'. Did you mean '$suggestion'?"
            else "Property '$method' does not exist on type 'string'."
            checker.diagnostics.add(Diagnostic(
                message = msg, category = DiagnosticCategory.Error,
                code = if (suggestion != null) 2551 else 2339,
                fileName = file, line = line, character = character,
                start = diagStart, length = method.length, relatedInformation = related))
        }
        for (stmt in sf.statements) {
            val expr = (stmt as? ExpressionStatement)?.expression ?: continue
            val callee = (expr as? CallExpression)?.expression ?: expr
            (callee as? PropertyAccessExpression)?.let { handleAccess(it) }
        }
    }

    /**
     * Resolved declaration of a name in a module file, following `export * from`
     * and `export { X } from` re-export chains.
     */
    private class ReexportedDecl(
        val stmt: Statement, val nameNode: Node, val fileName: String, val source: String,
    )

    /**
     * Find the declaration of [name] exported (possibly re-exported) by [fileName].
     * Follows `export * from "Y"` and `export { name } from "Y"` chains. Returns the
     * concrete declaration (class/function/interface/enum/var) or null.
     */
    private fun findExportedDeclAcrossReexports(
        fileName: String, name: String, visited: MutableSet<String>,
    ): ReexportedDecl? {
        if (!visited.add(fileName)) return null
        val res = checker.fileResults[fileName] ?: return null
        val source = res.sourceFile.text
        // Direct declarations first.
        for (stmt in res.sourceFile.statements) {
            when (stmt) {
                is ClassDeclaration -> stmt.name?.let {
                    if (it.text == name) return ReexportedDecl(stmt, it, fileName, source)
                }
                is FunctionDeclaration -> stmt.name?.let {
                    if (it.text == name) return ReexportedDecl(stmt, it, fileName, source)
                }
                is InterfaceDeclaration ->
                    if (stmt.name.text == name) return ReexportedDecl(stmt, stmt.name, fileName, source)
                is EnumDeclaration ->
                    if (stmt.name.text == name) return ReexportedDecl(stmt, stmt.name, fileName, source)
                is VariableStatement -> for (d in stmt.declarationList.declarations) {
                    val n = d.name as? Identifier ?: continue
                    if (n.text == name) return ReexportedDecl(stmt, n, fileName, source)
                }
                else -> {}
            }
        }
        // Re-export chains.
        for (stmt in res.sourceFile.statements) {
            if (stmt !is ExportDeclaration) continue
            val spec = (stmt.moduleSpecifier as? StringLiteralNode)?.text ?: continue
            val target = checker.resolveModuleSpecifier(spec, stmt) ?: continue
            val clause = stmt.exportClause
            if (clause == null) {
                // export * from "Y" — name passes through unchanged
                findExportedDeclAcrossReexports(target, name, visited)?.let { return it }
            } else if (clause is NamedExports) {
                // export { X as name } from "Y" — look up the original X under name
                for (el in clause.elements) {
                    if (el.name.text != name) continue
                    val original = el.propertyName?.text ?: el.name.text
                    findExportedDeclAcrossReexports(target, original, visited)?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * TS2567 for a `declare module "X" { export enum E { ... } }` augmentation whose
     * target module "X" (possibly via `export * from`) already declares a non-enum,
     * non-namespace `E` (class/function/var/interface). The per-file and cross-file
     * (script) walkers don't see this shape: the augmentation lives in a module file
     * and the conflicting partner is reached only through module-specifier + re-export
     * resolution. Emits TS2567 on BOTH the enum name and the partner declaration name,
     * each with a TS6203 "was also declared here" related info pointing at the other.
     */
    fun checkModuleAugmentationEnumMerge() {
        for (result in checker.checkedResults) {
            val augFile = result.sourceFile.fileName
            if (checker.isDtsFile(augFile)) continue
            val augSource = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                if (stmt !is ModuleDeclaration) continue
                val specName = (stmt.name as? StringLiteralNode)?.text ?: continue
                val body = stmt.body as? ModuleBlock ?: continue
                val targetFile = checker.resolveModuleSpecifier(specName, stmt) ?: continue
                if (targetFile == augFile) continue
                for (inner in body.statements) {
                    val enumDecl = inner as? EnumDeclaration ?: continue
                    val enumName = enumDecl.name.text
                    val partner = findExportedDeclAcrossReexports(targetFile, enumName, mutableSetOf())
                        ?: continue
                    val partnerKind = when (partner.stmt) {
                        is ClassDeclaration, is FunctionDeclaration,
                        is InterfaceDeclaration, is VariableStatement -> true
                        else -> false // enum partner merges fine; anything else: skip
                    }
                    if (!partnerKind) continue

                    val (enumLine, enumChar) = checker.getLineAndCharacterOfPosition(augSource, enumDecl.name.pos)
                    val (pLine, pChar) = checker.getLineAndCharacterOfPosition(partner.source, partner.nameNode.pos)

                    // TS2567 on the enum name, related info -> partner declaration.
                    checker.diagnostics.add(Diagnostic(
                        message = "Enum declarations can only merge with namespace or other enum declarations.",
                        category = DiagnosticCategory.Error,
                        code = 2567,
                        fileName = augFile,
                        line = enumLine,
                        character = enumChar,
                        start = enumDecl.name.pos,
                        length = enumName.length,
                        relatedInformation = listOf(Diagnostic(
                            message = "'$enumName' was also declared here.",
                            category = DiagnosticCategory.Message,
                            code = 6203,
                            fileName = partner.fileName,
                            line = pLine,
                            character = pChar,
                            start = partner.nameNode.pos,
                            length = enumName.length,
                        )),
                    ))
                    // TS2567 on the partner declaration name, related info -> enum.
                    checker.diagnostics.add(Diagnostic(
                        message = "Enum declarations can only merge with namespace or other enum declarations.",
                        category = DiagnosticCategory.Error,
                        code = 2567,
                        fileName = partner.fileName,
                        line = pLine,
                        character = pChar,
                        start = partner.nameNode.pos,
                        length = enumName.length,
                        relatedInformation = listOf(Diagnostic(
                            message = "'$enumName' was also declared here.",
                            category = DiagnosticCategory.Message,
                            code = 6203,
                            fileName = augFile,
                            line = enumLine,
                            character = enumChar,
                            start = enumDecl.name.pos,
                            length = enumName.length,
                        )),
                    ))
                }
            }
        }
    }

    /**
     * B443: cross-file `declare global { namespace NS { … } }` member conflict (TS2300).
     * When two DIFFERENT files each declare a member of the same name inside the SAME
     * global-augmentation namespace path, and the declaration kinds do NOT merge (a
     * type-alias never merges with anything), emit "Duplicate identifier 'X'." at each
     * declaration's name + a cross-referencing TS6203 "'X' was also declared here.".
     * (The cross-file `declare global` namespace merge is not modeled by the binder's
     * symbol tables, so this is AST-based — like the other dedicated cross-file walkers.)
     * FP-safe: requires a type-alias declaration in the mix (type-aliases are the only
     * kind that can never merge); plain interface/namespace/class merges never fire.
     */
    private data class GlobalNsMemberDecl(val isTypeAlias: Boolean, val id: Identifier, val fileName: String, val source: String)

    fun checkGlobalNamespaceMemberConflicts() {
        // (nsName -> memberName -> declarations across all files)
        val byNs = HashMap<String, HashMap<String, MutableList<GlobalNsMemberDecl>>>()
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                if (stmt !is ModuleDeclaration) continue
                if ((stmt.name as? Identifier)?.text != "global") continue
                if (ModifierFlag.Declare !in stmt.modifiers) continue
                val gBody = stmt.body as? ModuleBlock ?: continue
                for (nsStmt in gBody.statements) {
                    if (nsStmt !is ModuleDeclaration) continue
                    val nsName = (nsStmt.name as? Identifier)?.text ?: continue
                    val nsBody = nsStmt.body as? ModuleBlock ?: continue
                    val memberMap = byNs.getOrPut(nsName) { HashMap() }
                    for (member in nsBody.statements) {
                        val (memberName, isTypeAlias) = when (member) {
                            is TypeAliasDeclaration -> member.name.text to true
                            is ImportEqualsDeclaration -> member.name.text to false
                            is InterfaceDeclaration -> member.name.text to false
                            is ClassDeclaration -> (member.name?.text ?: continue) to false
                            is FunctionDeclaration -> (member.name?.text ?: continue) to false
                            is EnumDeclaration -> member.name.text to false
                            is ModuleDeclaration -> ((member.name as? Identifier)?.text ?: continue) to false
                            is VariableStatement -> {
                                for (d in member.declarationList.declarations) {
                                    val idn = d.name as? Identifier ?: continue
                                    memberMap.getOrPut(idn.text) { mutableListOf() }
                                        .add(GlobalNsMemberDecl(false, idn, fileName, source))
                                }
                                continue
                            }
                            else -> continue
                        }
                        val idNode = when (member) {
                            is TypeAliasDeclaration -> member.name
                            is ImportEqualsDeclaration -> member.name
                            is InterfaceDeclaration -> member.name
                            is ClassDeclaration -> member.name
                            is FunctionDeclaration -> member.name
                            is EnumDeclaration -> member.name
                            is ModuleDeclaration -> member.name as? Identifier
                        } ?: continue
                        memberMap.getOrPut(memberName) { mutableListOf() }
                            .add(GlobalNsMemberDecl(isTypeAlias, idNode, fileName, source))
                    }
                }
            }
        }
        for ((_, memberMap) in byNs) {
            for ((_, decls) in memberMap) {
                // Require declarations from ≥2 DIFFERENT files and at least one type-alias
                // (the only kind that can never merge — guarantees a genuine TS2300).
                val distinctFiles = decls.map { it.fileName }.distinct()
                if (distinctFiles.size < 2) continue
                if (decls.none { it.isTypeAlias }) continue
                for (d in decls) {
                    val other = decls.firstOrNull { it !== d } ?: continue
                    val (line, character) = checker.getLineAndCharacterOfPosition(d.source, d.id.pos)
                    val (oLine, oChar) = checker.getLineAndCharacterOfPosition(other.source, other.id.pos)
                    val related = listOf(Diagnostic(
                        message = "'${d.id.text}' was also declared here.", category = DiagnosticCategory.Message,
                        code = 6203, fileName = other.fileName, line = oLine, character = oChar,
                        start = other.id.pos, length = other.id.text.length,
                    ))
                    checker.diagnostics.add(Diagnostic(
                        message = "Duplicate identifier '${d.id.text}'.", category = DiagnosticCategory.Error,
                        code = 2300, fileName = d.fileName, line = line, character = character,
                        start = d.id.pos, length = d.id.text.length, relatedInformation = related,
                    ))
                }
            }
        }
    }

    /**
     * B449 (noSymbolForMergeCrash): a top-level `type N = …` in a SCRIPT file, where N
     * ALSO has a top-level `namespace N`/`module N` declaration in a DIFFERENT SCRIPT
     * file, is a cross-file global-scope merge that tsc rejects with TS2649 "Cannot
     * augment module 'N' with value exports because it resolves to a non-module entity."
     * at the type-alias name (the type alias is the "non-module entity" the namespace
     * cannot merge with). The binder's symbol tables don't model this cross-file global
     * merge, so it's AST-based — like the other dedicated cross-file walkers (B443).
     *
     * FP firewall (corpus-EXHAUSTIVE): BOTH files must be SCRIPT files (no imports/
     * exports) — the only other corpus file sharing the `type X`+`namespace X` shape is
     * `reexportNameAliasedAndHoisted`, whose files are MODULE files (top-level `export`),
     * so the type/namespace are module-scoped (no global conflict) and it is excluded.
     * The conflict must be cross-FILE (same-file `type X`+`namespace X` is TS2300, owned
     * by the duplicate-identifier pipeline).
     */
    fun checkCrossFileTypeAliasNamespaceConflict() {
        // name -> first script-file top-level type-alias name node (+ its file/source)
        val typeAliases = HashMap<String, Triple<Identifier, String, String>>()
        // name -> set of script files declaring a top-level namespace/module of that name
        val namespaces = HashMap<String, MutableSet<String>>()
        for (result in checker.checkedResults) {
            val fileName = result.sourceFile.fileName
            if (checker.isDtsFile(fileName)) continue
            if (checker.isModuleFile(result.sourceFile.statements)) continue
            val source = result.sourceFile.text
            for (stmt in result.sourceFile.statements) {
                when (stmt) {
                    is TypeAliasDeclaration ->
                        typeAliases.getOrPut(stmt.name.text) { Triple(stmt.name, fileName, source) }
                    is ModuleDeclaration -> {
                        val nm = (stmt.name as? Identifier)?.text ?: continue
                        namespaces.getOrPut(nm) { mutableSetOf() }.add(fileName)
                    }
                    else -> {}
                }
            }
        }
        for ((name, ta) in typeAliases) {
            val (idNode, taFile, taSource) = ta
            // Require a namespace of the same name in a DIFFERENT script file.
            val nsFiles = namespaces[name] ?: continue
            if (nsFiles.none { it != taFile }) continue
            val (line, character) = checker.getLineAndCharacterOfPosition(taSource, idNode.pos)
            checker.diagnostics.add(Diagnostic(
                message = "Cannot augment module '$name' with value exports because it resolves to a non-module entity.",
                category = DiagnosticCategory.Error, code = 2649,
                fileName = taFile, line = line, character = character,
                start = idNode.pos, length = idNode.text.length,
            ))
        }
    }
}

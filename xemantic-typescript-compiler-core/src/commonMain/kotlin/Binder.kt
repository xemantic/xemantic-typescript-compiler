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
 * The result of binding a single source file. Contains the symbol tables
 * and node-to-symbol mappings produced by the [Binder].
 */
class BinderResult(
    val sourceFile: SourceFile,
    /** Symbols declared at file/module level. */
    val locals: SymbolTable,
    /** Map from declaration node key (pos/end packed into Long) to its Symbol. */
    val nodeToSymbol: MutableMap<Long, Symbol>,
    /** Module instance states for namespace/module declarations. */
    val moduleInstanceStates: MutableMap<Long, ModuleInstanceState>,
    /**
     * (INC.16) Whether any `enum` in this file reaches a FRESH INV.2(c) scope, i.e.
     * whether `init:computeAllEnumValues`' scope-space census has to BUILD this file's
     * tables at all.
     *
     * The enum half is the only half that needs them: the census does not merely record
     * the name, it calls `computeEnumSymbolValues` on the scope-space SYMBOL, and that
     * symbol exists nowhere else. The type-alias half needs a name only, which
     * [scopeTypeAliasNames] carries — so a file whose block-scoped declarations are all
     * type aliases is censused WITHOUT its tables ever being built.
     *
     * False for 76 of tsc's own 78 sources. Decided from
     * [SourceFile.nestedEnumOrTypeAliasDecls] (a fact about the tree, stamped once per
     * parse) plus the bind's OWN namespace symbols, because the namespace case is not a
     * syntactic one: a `namespace` scope ALIASES the merged `exports`, so
     * `declareLexical` skips every name declared in it — but only when that `exports`
     * table exists.
     */
    val declaresScopeEnum: Boolean,
    /**
     * (INC.52) Whether this file's bind minted any `enum` SYMBOL — the projection that
     * lets `init:computeAllEnumValues` skip a file's whole symbol table instead of
     * walking it to find out.
     *
     * Its second loop visits every file's `locals` and recurses through every namespace's
     * `exports` looking for `SymbolFlags.Enum`, which on tsc's own 78 sources is the
     * single most expensive pass of the incremental FLOOR — a cost every editor query
     * pays whether or not it is checking anything.
     *
     * EXACT rather than syntactic: it is set at `bindEnumDeclaration`, the one site that
     * mints a conventional enum symbol (the scope-space ones are the other loop's
     * business, and [declaresScopeEnum] covers those). So a file this is false for binds
     * no enum symbol, and every enum symbol in the program is minted by SOME file whose
     * flag is therefore true — which is what makes the skip an identity rather than an
     * approximation, since `enumValues` is keyed by symbol ID and a merged symbol is one
     * object shared by both files' tables (round 884). `LexDefer.verifySkip` is the
     * positive control: it keeps walking and counts what the skip would have missed.
     */
    val bindsEnum: Boolean,
    /**
     * (INC.16) The names of this file's `type` declarations that reach a FRESH INV.2(c)
     * scope — exactly the names `declareLexical` will mint a `TypeAlias`-flagged scope
     * symbol for, read off the declarations instead of off the scopes.
     *
     * Deliberately allowed to OVER-approximate in one shape: a same-named later
     * declaration in the same fresh scope can overwrite the alias symbol and drop its
     * `TypeAlias` flag. That only widens `Checker.lexicalBlockScopedTypeAliasNames`,
     * which is a fast-path NAME GATE whose hit is re-verified against the real scope
     * symbol's flags — so a widened gate costs a lookup and can change no answer.
     */
    val scopeTypeAliasNames: Set<String>,
    /**
     * (INC.16) Builds the INV.2(c) tables. Invoked on FIRST ASK (the shipped
     * behaviour), or at the end of `Binder.bind` when [LexDefer.deferred] is false.
     */
    lexicalScopeBuilder: () -> Map<Int, LexicalScope>,
) {

    /**
     * INV.2(c): per-node lexical scopes, keyed by the scope-owner node's
     * `nodeId` (the SourceFile root is key 0). Built by the ADDITIVE
     * lexical-binding pass after conventional binding; empty for unindexed
     * (hand-constructed) trees.
     *
     * (INC.16) Built on FIRST ASK; `LexDefer.deferred = false` restores the eager
     * build at the end of `Binder.bind`, which is the arm the differential runs
     * against. `lazy` rather than a nullable field for the reason [flowGraph]
     * documents: `--shareBind` hands one set of [BinderResult]s to N worker threads
     * at once, so two of them can ask the same file at the same moment.
     */
    val lexicalScopes: Map<Int, LexicalScope> get() {
        if (!lazyLexicalScopes.isInitialized()) {
            val v = lazyLexicalScopes.value
            LexDefer.recordLazy()
            return v
        }
        return lazyLexicalScopes.value
    }

    /** Whether [lexicalScopes] has been asked for yet — the pin's instrument. */
    val lexicalScopesBuilt: Boolean get() = lazyLexicalScopes.isInitialized()

    private val lazyLexicalScopes = lazy(lexicalScopeBuilder)

    /** The eager path: build now, and count it as eager rather than as a first ask. */
    internal fun forceLexicalScopesEagerly() {
        lazyLexicalScopes.value
        LexDefer.eagerBuilds++
    }

    /**
     * (INC.9) The control-flow graph, built on FIRST ASK and never before.
     *
     * Maps reference-position node keys to the [FlowNode] representing the
     * program point just before that reference is evaluated.
     *
     * **Why it is deferred and not omitted.** Round 865 measured that 52.3% of
     * the flow nodes this compiler mints are never read by any consumer, and
     * recorded the asymmetry that governs every change here: a missing side
     * table degrades to a correct fallback, but a missing FLOW NODE makes
     * `flowAt` answer null, nothing narrows, and the compiler emits a FALSE
     * POSITIVE. So laziness here must DEFER-AND-BUILD, never omit — which is
     * exactly what this property does: the first reader gets the same graph the
     * eager build produced, and every later reader gets that same instance.
     *
     * **Why deferring is sound.** [FlowGraphBuilder] is a pure function of the
     * [SourceFile]: a fresh builder per file, every cache it consults is one of
     * its own instance fields, and its `FlowNode` ids restart at 0 per file
     * (round 865), so nothing about the graph depends on WHEN it is built or on
     * which other files were built first. It mints no `Symbol` and no `Type`,
     * so the INV.6(6c0) id sequences cannot move either.
     *
     * **What it buys.** Under a `recheckOnly` partition — the language service's
     * per-file diagnostics query, and every caret-scoped capture query — the
     * checker walks ONE file, so only that file's graph is asked for. The other
     * 77 program files and the ~45 real-lib files (whose `.d.ts` graphs no
     * consumer has ever read) are never built. Measured on tsc's own 78 sources:
     * `FlowGraphBuilder` is 126 ms of a 523 ms floor, i.e. 24% of everything a
     * narrowed query costs.
     *
     * **Publication.** `lazy` (SYNCHRONIZED by default on every target here) is
     * load-bearing, not decoration: `--shareBind` (round 883) hands ONE set of
     * [BinderResult]s to N worker threads, so two workers can ask the same file
     * for its graph at the same moment. A plain nullable field would publish a
     * partially-constructed [FlowGraph] there, and the failure would be a wrong
     * narrowing rather than a crash.
     */
    val flowGraph: FlowGraph get() = lazyFlowGraph.value

    /**
     * Whether [flowGraph] has been asked for yet. Exists so laziness can be
     * pinned as a FACT about this object rather than through a process-global
     * counter (round 900: a probe-gated census is a poor instrument for
     * "did this work happen at all", because the probe's own argument can be
     * what performs it).
     */
    val flowGraphBuilt: Boolean get() = lazyFlowGraph.isInitialized()

    private val lazyFlowGraph = lazy {
        // The span stays [FrontEnd.BIND_FLOW] wherever the build lands, so a
        // cross-round comparison of flow-graph construction still compares the
        // same quantity; what MOVES is the phase it is charged to — a deferred
        // build is charged to [FrontEnd.CHECK], and [FrontEnd.BIND]'s residue
        // against its three sub-rows is what says so.
        val fe = FrontEnd.t()
        val graph = FlowGraphBuilder().build(sourceFile)
        FrontEnd.close(FrontEnd.BIND_FLOW, fe)
        graph
    }
}

/**
 * Walks a [SourceFile] AST and creates [Symbol]s for all declarations,
 * building scope chains and handling declaration merging.
 *
 * The binder produces a [BinderResult] that the [Checker] uses for
 * import reference tracking and const enum value resolution.
 */
class Binder(private val options: CompilerOptions) {

    private companion object {
        /** (INC.16) the answer for the 76-of-78 case, allocated once. */
        val NO_SCOPE_TYPE_DECLARATIONS: Pair<Boolean, Set<String>> = Pair(false, emptySet())
    }

    /**
     * (BIND.1) THIS FILE'S declaration nodes, keyed by `nodeKey(pos, end)` — and it is
     * **per file**, which is the whole point.
     *
     * It used to be ONE map shared by every [BinderResult] this binder produced, and
     * `nodeKey` carries no file identity while positions restart at 0 in every file: two
     * declarations at coincident `(pos, end)` in DIFFERENT files were therefore one key,
     * last-wins in bind order. That is not a theoretical hazard — measured on an ordinary
     * 223-file program (one source file plus `zod` and `@types/node`), **109 keys were
     * written by two or more declaration nodes in different files**, `posix.d.ts` and
     * `win32.d.ts` among them with a `ModuleDeclaration` apiece at `pos=110, end=194`.
     * The observable was a diagnostic that appeared and vanished with the BYTE LENGTH of
     * an unrelated file: two same-length files each declaring a merged `namespace` made
     * `Checker.buildNamespaceScope` build the scope of the OTHER file's namespace, so the
     * file's own exports went missing (a false TS2304) and the foreign file's became
     * visible (a missing one) — in both directions, against tsc, in four lines of
     * TypeScript. `NodeKeyCollisionTest` is the pin.
     *
     * A reader that holds the owning [BinderResult] is now correct by construction; one
     * that has only a [Node] goes through `Checker.owningBinderResult`, which resolves the
     * file from the INV.2(a) parent chain. Do NOT hoist these back to the binder: the
     * per-file map is the fix, and nothing about `nodeKey` prevents the collision.
     */
    private var nodeToSymbol = mutableMapOf<Long, Symbol>()

    /** Module instance states for THIS file's namespace declarations — per file for
     *  [nodeToSymbol]'s reason, and it collided the same way. */
    private var moduleInstanceStates = mutableMapOf<Long, ModuleInstanceState>()

    /**
     * (INC.16) THE FILE'S OWN `ModuleDeclaration` / `EnumDeclaration` symbols, keyed by
     * `nodeId` — the two things [bindLexicalScopes] needs from the conventional bind.
     *
     * It exists because [nodeToSymbol] USED TO BE shared by every [BinderResult] from one
     * [Binder], its `(pos, end)` keys colliding across files last-wins in bind order — so
     * reading it from a scope build that runs at the END of `bind` was right only by
     * accident (the file had just written its own entries), and reading it from a build
     * DEFERRED to first ask would not have been: a later file's declaration at the same
     * offsets would answer instead, aliasing a foreign namespace's `exports` into this
     * file's scope chain. (BIND.1) has since made that table per-file, so the sharing is
     * gone — this one STAYS, because `nodeId` is per-file and dense and so cannot collide
     * at all, which keeps the deferral order-independent BY CONSTRUCTION rather than by a
     * measured zero (round (INC.19)'s lesson: an interned write whose winner depends on
     * dispatch order fails as a plausible answer, never as an error).
     *
     * Only the CURRENT file's is live; each `bind` installs a fresh one and the deferred
     * builder captures it.
     */
    private var lexOwnerSymbols: MutableMap<Int, Symbol> = HashMap()

    /** The current symbol table where new declarations are added. */
    private var currentScope: SymbolTable = symbolTable()

    /** The current container symbol (namespace/module/class) for setting parent references. */
    private var currentContainer: Symbol? = null

    /**
     * (INC.52) Set by [bindEnumDeclaration] for the file in flight — see
     * [BinderResult.bindsEnum]. Reset per `bind`, like every other per-file field here.
     */
    private var bindsEnum: Boolean = false

    fun bind(sourceFile: SourceFile): BinderResult {
        val fileLocals = symbolTable()
        currentScope = fileLocals
        bindsEnum = false
        // (BIND.1) fresh per file — see [nodeToSymbol]. A binder binds many files and
        // `nodeKey` cannot tell them apart, so the tables may not outlive one of them.
        nodeToSymbol = mutableMapOf()
        moduleInstanceStates = mutableMapOf()
        // (INC.16) fresh per file — the deferred scope builder captures THIS one.
        val lexOwners = HashMap<Int, Symbol>()
        lexOwnerSymbols = lexOwners
        // (FRONT.2) round 801 — the three components of a bind, partitioned.
        // Exhaustive by construction (this function is these three statements)
        // and 3 timestamp pairs per FILE, so unlike every per-node partition in
        // this arc the boundary cost needs no calibration: 78 files x 3 pairs.
        val feD = FrontEnd.t()
        bindStatements(sourceFile.statements)
        FrontEnd.close(FrontEnd.BIND_DECL, feD)
        // (INC.9) The flow graph is NOT built here — [BinderResult.flowGraph]
        // builds it on first ask. A `recheckOnly` partition asks for one file's,
        // a full build asks for every checked file's, and nothing has ever asked
        // for a real-lib `.d.ts` file's.
        val scopeTypes = scopeTypeDeclarations(sourceFile, lexOwners)
        val result = BinderResult(
            sourceFile, fileLocals, nodeToSymbol, moduleInstanceStates,
            declaresScopeEnum = scopeTypes.first,
            bindsEnum = bindsEnum,
            scopeTypeAliasNames = scopeTypes.second,
        ) {
            // The span stays [FrontEnd.BIND_LEX] wherever the build lands, so a
            // cross-round comparison of the scope walk still compares the same
            // quantity; what MOVES is the phase it is charged to.
            val feL = FrontEnd.t()
            val scopes = bindLexicalScopes(sourceFile, fileLocals, lexOwners)
            FrontEnd.close(FrontEnd.BIND_LEX, feL)
            // (WARM.28) the denominator for an EAGERLY built per-scope filter.
            if (MapCensus.on) MapCensus.lexBound(scopes)
            scopes
        }
        if (!LexDefer.deferred) result.forceLexicalScopesEagerly()
        return result
    }

    /**
     * (INC.16) See [BinderResult.declaresScopeEnum] / [BinderResult.scopeTypeAliasNames].
     * Runs after [bindStatements], so [lexOwners] already holds every namespace/enum
     * symbol this file's conventional bind produced.
     *
     * A recorded declaration's nearest LEXICAL scope owner is found by ascending through
     * the one node kind that owns no scope on that path, [ModuleBlock]. Reaching a
     * [ModuleDeclaration] whose innermost segment symbol has an `exports` table means the
     * scope ALIASES it and `declareLexical` will skip the name; reaching the [SourceFile]
     * means the root scope, which aliases file locals. Anything else — a block, a
     * function, a class, a namespace the bind never gave an `exports` — is a fresh scope,
     * which is where and only where a scope-space symbol is minted.
     */
    private fun scopeTypeDeclarations(
        sourceFile: SourceFile,
        lexOwners: Map<Int, Symbol>,
    ): Pair<Boolean, Set<String>> {
        val decls = sourceFile.nestedEnumOrTypeAliasDecls
        if (decls.isEmpty()) return NO_SCOPE_TYPE_DECLARATIONS
        var hasEnum = false
        var aliases: MutableSet<String>? = null
        for (decl in decls) {
            if (!reachesFreshLexicalScope(decl, lexOwners)) continue
            when (decl) {
                is EnumDeclaration -> hasEnum = true
                is TypeAliasDeclaration -> {
                    val set = aliases ?: HashSet<String>(4).also { aliases = it }
                    set.add(decl.name.text)
                }
                else -> {}
            }
        }
        return if (!hasEnum && aliases == null) NO_SCOPE_TYPE_DECLARATIONS
        else Pair(hasEnum, aliases ?: emptySet())
    }

    /** See [scopeTypeDeclarations]. */
    private fun reachesFreshLexicalScope(decl: Node, lexOwners: Map<Int, Symbol>): Boolean {
        var cur: Node? = (decl as NodeBase).parent
        var hops = 0
        while (cur != null && hops++ < 4096) {
            when (cur) {
                is SourceFile -> return false
                is ModuleBlock -> cur = cur.parent
                is ModuleDeclaration -> return lexOwners[cur.nodeId]?.exports == null
                else -> return true
            }
        }
        return false
    }

    private fun bindStatements(statements: List<Statement>) {
        for (stmt in statements) {
            bindStatement(stmt)
        }
    }

    private fun bindStatement(stmt: Statement) {
        when (stmt) {
            is VariableStatement -> bindVariableStatement(stmt)
            is FunctionDeclaration -> bindFunctionDeclaration(stmt)
            is ClassDeclaration -> bindClassDeclaration(stmt)
            is InterfaceDeclaration -> bindInterfaceDeclaration(stmt)
            is TypeAliasDeclaration -> bindTypeAliasDeclaration(stmt)
            is EnumDeclaration -> bindEnumDeclaration(stmt)
            is ModuleDeclaration -> bindModuleDeclaration(stmt)
            is ImportDeclaration -> bindImportDeclaration(stmt)
            is ImportEqualsDeclaration -> bindImportEqualsDeclaration(stmt)
            is ExportDeclaration -> bindExportDeclaration(stmt)
            is ExportAssignment -> { /* no symbol binding needed */ }
            else -> { /* statements that don't create symbols */ }
        }
    }

    // -----------------------------------------------------------------------
    // Variable declarations
    // -----------------------------------------------------------------------

    private fun bindVariableStatement(stmt: VariableStatement) {
        val list = stmt.declarationList
        val isVar = list.flags == SyntaxKind.VarKeyword
        val flags = if (isVar) SymbolFlags.FunctionScopedVariable
                    else SymbolFlags.BlockScopedVariable
        for (decl in list.declarations) {
            bindVariableDeclarationName(decl.name, flags, decl)
        }
    }

    private fun bindVariableDeclarationName(
        name: Expression,
        flags: SymbolFlags,
        declarationNode: Node,
    ) {
        when (name) {
            is Identifier -> {
                declareSymbol(currentScope, name.text, flags, declarationNode)
            }
            is ObjectBindingPattern -> {
                for (element in name.elements) {
                    bindVariableDeclarationName(element.name, flags, element)
                }
            }
            is ArrayBindingPattern -> {
                for (element in name.elements) {
                    if (element is BindingElement) {
                        bindVariableDeclarationName(element.name, flags, element)
                    }
                }
            }
            else -> { /* computed property names, etc. — skip */ }
        }
    }

    // -----------------------------------------------------------------------
    // Function declarations
    // -----------------------------------------------------------------------

    private fun bindFunctionDeclaration(decl: FunctionDeclaration) {
        val name = decl.name ?: return
        val flags = if (ModifierFlag.Export in decl.modifiers)
            SymbolFlags.Function or SymbolFlags.ExportValue
        else SymbolFlags.Function
        declareSymbol(currentScope, name.text, flags, decl)
    }

    // -----------------------------------------------------------------------
    // Class declarations
    // -----------------------------------------------------------------------

    private fun bindClassDeclaration(decl: ClassDeclaration) {
        val name = decl.name ?: return
        val flags = if (ModifierFlag.Export in decl.modifiers)
            SymbolFlags.Class or SymbolFlags.ExportValue
        else SymbolFlags.Class
        declareSymbol(currentScope, name.text, flags, decl)
    }

    // -----------------------------------------------------------------------
    // Interface declarations
    // -----------------------------------------------------------------------

    private fun bindInterfaceDeclaration(decl: InterfaceDeclaration) {
        val flags = if (ModifierFlag.Export in decl.modifiers)
            SymbolFlags.Interface or SymbolFlags.ExportValue
        else SymbolFlags.Interface
        declareSymbol(currentScope, decl.name.text, flags, decl)
    }

    // -----------------------------------------------------------------------
    // Type alias declarations
    // -----------------------------------------------------------------------

    private fun bindTypeAliasDeclaration(decl: TypeAliasDeclaration) {
        val flags = if (ModifierFlag.Export in decl.modifiers)
            SymbolFlags.TypeAlias or SymbolFlags.ExportValue
        else SymbolFlags.TypeAlias
        declareSymbol(currentScope, decl.name.text, flags, decl)
    }

    // -----------------------------------------------------------------------
    // Enum declarations
    // -----------------------------------------------------------------------

    private fun bindEnumDeclaration(decl: EnumDeclaration) {
        // (INC.52) The one site that mints a conventional enum symbol — see
        // [BinderResult.bindsEnum].
        bindsEnum = true
        val isConst = ModifierFlag.Const in decl.modifiers
        var flags = if (isConst) SymbolFlags.ConstEnum else SymbolFlags.RegularEnum
        if (ModifierFlag.Export in decl.modifiers) {
            flags = flags or SymbolFlags.ExportValue
        }
        val symbol = declareSymbol(currentScope, decl.name.text, flags, decl)
        recordLexOwner(decl, symbol)

        // Bind enum members into the enum's exports table
        if (symbol.exports == null) symbol.exports = symbolTable()
        val memberScope = symbol.exports!!
        for (member in decl.members) {
            val memberName = when (val n = member.name) {
                is Identifier -> n.text
                is StringLiteralNode -> n.text
                is NumericLiteralNode -> n.text
                // B451: a computed enum member name `["bar"]`/`[2]` whose inner expression
                // is a string/numeric literal is a STATIC member key (so `X["bar"]` resolves
                // and doesn't FP TS7015). Dynamic computed names stay unbound.
                is ComputedPropertyName -> when (val e = n.expression) {
                    is StringLiteralNode -> e.text
                    is NumericLiteralNode -> e.text
                    else -> continue
                }
                else -> continue
            }
            val memberSymbol = declareSymbol(memberScope, memberName, SymbolFlags.EnumMember, member)
            memberSymbol.parent = symbol
        }
    }

    // -----------------------------------------------------------------------
    // Module/namespace declarations
    // -----------------------------------------------------------------------

    private fun bindModuleDeclaration(decl: ModuleDeclaration) {
        // Compute module instance state
        val state = computeModuleInstanceState(decl)
        moduleInstanceStates[nodeKey(decl)] = state

        val flags = when {
            ModifierFlag.Declare in decl.modifiers -> SymbolFlags.NamespaceModule
            state == ModuleInstanceState.NonInstantiated -> SymbolFlags.NamespaceModule
            state == ModuleInstanceState.ConstEnumOnly -> SymbolFlags.ConstEnum or SymbolFlags.NamespaceModule
            else -> SymbolFlags.ValueModule
        }.let { f ->
            if (ModifierFlag.Export in decl.modifiers) f or SymbolFlags.ExportValue else f
        }

        // For dotted namespace names (e.g. A.B.C), collect segments and declare
        // nested symbols: A → A.B → A.B.C, each with exports pointing to the next.
        val segments = mutableListOf<String>()
        var cur: Expression = decl.name
        while (cur is PropertyAccessExpression) {
            segments.add(0, cur.name.text)
            cur = cur.expression
        }
        when (cur) {
            is Identifier -> segments.add(0, cur.text)
            is StringLiteralNode -> segments.add(0, cur.text)
            else -> return
        }

        // Declare/merge symbols for each segment, creating nested exports
        val savedScope = currentScope
        val savedContainer = currentContainer
        var sym: Symbol? = null
        for ((i, seg) in segments.withIndex()) {
            val segFlags = if (i == 0) flags else SymbolFlags.NamespaceModule
            sym = declareSymbol(currentScope, seg, segFlags, decl)
            if (sym.exports == null) sym.exports = symbolTable()
            currentScope = sym.exports!!
            currentContainer = sym
        }

        if (sym != null) recordLexOwner(decl, sym)

        // Bind the body in the innermost namespace's exports scope
        val body = decl.body
        if (body != null && sym != null) {
            when (body) {
                is ModuleBlock -> bindStatements(body.statements)
                is ModuleDeclaration -> bindModuleDeclaration(body)
                else -> { /* empty body */ }
            }
        }
        currentScope = savedScope
        currentContainer = savedContainer
    }

    // -----------------------------------------------------------------------
    // Import declarations
    // -----------------------------------------------------------------------

    private fun bindImportDeclaration(decl: ImportDeclaration) {
        val clause = decl.importClause ?: return

        // Default import: import Foo from "mod"
        if (clause.name != null) {
            val symbol = declareSymbol(currentScope, clause.name.text, SymbolFlags.Alias, decl)
            // Also map the ImportDeclaration node itself to this symbol
            recordNodeSymbol(decl, symbol)
        }

        val bindings = clause.namedBindings
        when (bindings) {
            is NamespaceImport -> {
                // import * as Foo from "mod"
                val symbol = declareSymbol(currentScope, bindings.name.text, SymbolFlags.Alias, decl)
                recordNodeSymbol(decl, symbol)
            }
            is NamedImports -> {
                // import { A, B as C } from "mod"
                for (spec in bindings.elements) {
                    val localName = spec.name.text
                    val symbol = declareSymbol(currentScope, localName, SymbolFlags.Alias, spec)
                    recordNodeSymbol(spec, symbol)
                }
                // Map the ImportDeclaration to all its specifier symbols
                // (for whole-import elision checks)
                if (bindings.elements.isNotEmpty()) {
                    // (BIND.1) `?.let`, not `!!`: the specifier is recorded just above
                    // UNLESS it is synthetic (`recordNodeSymbol` skips those), and before
                    // the per-file split a colliding entry from another file could satisfy
                    // this read by accident. There is nothing to record for such an import.
                    nodeToSymbol[nodeKey(bindings.elements.first())]
                        ?.let { recordNodeSymbol(decl, it) }
                }
            }
            else -> { /* no bindings */ }
        }
    }

    private fun bindImportEqualsDeclaration(decl: ImportEqualsDeclaration) {
        val flags = if (ModifierFlag.Export in decl.modifiers)
            SymbolFlags.Alias or SymbolFlags.ExportValue
        else SymbolFlags.Alias
        val symbol = declareSymbol(currentScope, decl.name.text, flags, decl)
        recordNodeSymbol(decl, symbol)
    }

    // -----------------------------------------------------------------------
    // Export declarations
    // -----------------------------------------------------------------------

    private fun bindExportDeclaration(decl: ExportDeclaration) {
        when (val clause = decl.exportClause) {
            is NamedExports -> {
                for (spec in clause.elements) {
                    val localName = spec.propertyName?.text ?: spec.name.text
                    // For local re-exports (no `from` clause), don't overwrite existing value symbols.
                    // The existing symbol already captures the declaration; we just record the node.
                    if (decl.moduleSpecifier == null) {
                        val existing = currentScope[localName]
                        if (existing != null) {
                            // Already declared as a value — just record the node → existing symbol
                            recordNodeSymbol(spec, existing)
                            continue
                        }
                    }
                    val symbol = declareSymbol(currentScope, localName, SymbolFlags.Alias, spec)
                    recordNodeSymbol(spec, symbol)
                }
            }
            is NamespaceExport -> {
                declareSymbol(currentScope, clause.name.text, SymbolFlags.Alias, decl)
            }
            else -> { /* export * from "mod" — no named symbol */ }
        }
    }

    // -----------------------------------------------------------------------
    // Symbol declaration and merging
    // -----------------------------------------------------------------------

    /**
     * Declare a symbol in the given scope. If a symbol with the same name
     * already exists and the flags are merge-compatible, merge into the
     * existing symbol.
     */
    private fun declareSymbol(
        scope: SymbolTable,
        name: String,
        flags: SymbolFlags,
        declarationNode: Node,
    ): Symbol {
        val existing = scope[name]
        if (existing != null && canMerge(existing.flags, flags)) {
            existing.flags = existing.flags or flags
            existing.declarations.add(declarationNode)
            if (existing.valueDeclaration == null && flags.hasAny(SymbolFlags.Value)) {
                existing.valueDeclaration = declarationNode
            }
            recordNodeSymbol(declarationNode, existing)
            return existing
        }
        val symbol = Symbol(flags, name)
        symbol.declarations.add(declarationNode)
        if (flags.hasAny(SymbolFlags.Value)) {
            symbol.valueDeclaration = declarationNode
        }
        symbol.parent = currentContainer
        // B505: first-wins for a Class+Class duplicate. tsc keeps the FIRST class as the
        // canonical scope binding, so name/member resolution uses its declaration (and a
        // later same-named class's members are NOT contributed). The later class still gets
        // its own symbol (returned + node-recorded) for its own diagnostics, but does NOT
        // overwrite scope[name]. TS2300 duplicate detection is AST-based
        // (checkDuplicateDeclarations) so it is unaffected. Scoped to Class+Class only to
        // avoid disturbing other non-mergeable combos (class+interface, var+class), whose
        // last-wins behavior other tests depend on.
        val classDuplicate = existing != null &&
            existing.flags.hasAny(SymbolFlags.Class) && flags.hasAny(SymbolFlags.Class)
        if (!classDuplicate) {
            scope[name] = symbol
        }
        recordNodeSymbol(declarationNode, symbol)
        return symbol
    }

    /** (INC.16) See [lexOwnerSymbols]. A synthesized node (nodeId < 0) is not recorded. */
    private fun recordLexOwner(node: Node, symbol: Symbol) {
        val id = (node as NodeBase).nodeId
        if (id >= 0) lexOwnerSymbols[id] = symbol
    }

    private fun recordNodeSymbol(node: Node, symbol: Symbol) {
        val key = nodeKey(node)
        if (key != nodeKey(-1, -1)) { // skip synthetic nodes
            nodeToSymbol[key] = symbol
        }
    }

    /**
     * Check if two symbol flag sets can be merged (declaration merging).
     */
    private fun canMerge(existing: SymbolFlags, incoming: SymbolFlags): Boolean {
        // Interface + Interface
        if (existing.hasAny(SymbolFlags.Interface) && incoming.hasAny(SymbolFlags.Interface)) return true
        // Module + Module
        if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Module)) return true
        // Interface + Module
        if (existing.hasAny(SymbolFlags.Interface) && incoming.hasAny(SymbolFlags.Module)) return true
        if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Interface)) return true
        // Class + Module
        if (existing.hasAny(SymbolFlags.Class) && incoming.hasAny(SymbolFlags.Module)) return true
        if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Class)) return true
        // Function + Module
        if (existing.hasAny(SymbolFlags.Function) && incoming.hasAny(SymbolFlags.Module)) return true
        if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Function)) return true
        // Interface + Function (function + interface with same name: constructor pattern)
        if (existing.hasAny(SymbolFlags.Interface) && incoming.hasAny(SymbolFlags.Function)) return true
        if (existing.hasAny(SymbolFlags.Function) && incoming.hasAny(SymbolFlags.Interface)) return true
        // Class + Interface (declaration merging — interface members merge into the class
        // instance type, e.g. `interface Foo { method(): void } class Foo { ... }`).
        if (existing.hasAny(SymbolFlags.Class) && incoming.hasAny(SymbolFlags.Interface)) return true
        if (existing.hasAny(SymbolFlags.Interface) && incoming.hasAny(SymbolFlags.Class)) return true
        // Enum + Module
        if (existing.hasAny(SymbolFlags.Enum) && incoming.hasAny(SymbolFlags.Module)) return true
        if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Enum)) return true
        // Variable + Module (e.g. `declare const b: T; declare namespace b { ... }`)
        // TypeScript allows namespace augmentation of const/let/var declarations.
        if (existing.hasAny(SymbolFlags.Variable) && incoming.hasAny(SymbolFlags.Module)) return true
        if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Variable)) return true
        // Variable + Interface (e.g. `interface Symbol {}` + `declare var Symbol: SymbolConstructor;`).
        // Without this, the second declaration silently overwrites the first symbol — losing the
        // type-position declaration. Affects wrapper types and class+interface constructor patterns
        // (`class B extends A` where A is both interface and constructor var).
        if (existing.hasAny(SymbolFlags.Variable) && incoming.hasAny(SymbolFlags.Interface)) return true
        if (existing.hasAny(SymbolFlags.Interface) && incoming.hasAny(SymbolFlags.Variable)) return true
        // Variable + TypeAlias (round 473): `type ActionSet = "action::set"` + `export const
        // ActionSet: ActionSet = "action::set"` — tsc's jsTyping/shared.ts idiom. Disjoint
        // type/value spaces, same rationale as Variable + Interface: without the merge the
        // const OVERWRITES the alias symbol, `kind: ActionSet` member annotations resolve
        // through a Variable-only symbol to errorType, and discriminated-union narrowing
        // over such members silently dies.
        if (existing.hasAny(SymbolFlags.Variable) && incoming.hasAny(SymbolFlags.TypeAlias)) return true
        if (existing.hasAny(SymbolFlags.TypeAlias) && incoming.hasAny(SymbolFlags.Variable)) return true
        // Enum + Enum (merge across declarations)
        if (existing.hasAny(SymbolFlags.Enum) && incoming.hasAny(SymbolFlags.Enum)) return true
        // var + var (re-declarations allowed)
        if (existing.hasAny(SymbolFlags.FunctionScopedVariable) &&
            incoming.hasAny(SymbolFlags.FunctionScopedVariable)) return true
        // Function + Function (overloads)
        if (existing.hasAny(SymbolFlags.Function) && incoming.hasAny(SymbolFlags.Function)) return true
        // Alias can merge with itself (re-exports)
        if (existing.hasAny(SymbolFlags.Alias) && incoming.hasAny(SymbolFlags.Alias)) return true
        return false
    }

    // -----------------------------------------------------------------------
    // Module instance state computation
    // -----------------------------------------------------------------------

    /**
     * Determine whether a module/namespace declaration produces runtime code.
     */
    private fun computeModuleInstanceState(decl: ModuleDeclaration): ModuleInstanceState {
        if (ModifierFlag.Declare in decl.modifiers) return ModuleInstanceState.NonInstantiated
        val body = decl.body ?: return ModuleInstanceState.Instantiated
        return when (body) {
            is ModuleBlock -> computeModuleBlockState(body)
            is ModuleDeclaration -> computeModuleInstanceState(body)
            else -> ModuleInstanceState.Instantiated
        }
    }

    private fun computeModuleBlockState(block: ModuleBlock): ModuleInstanceState {
        var hasConstEnum = false
        for (stmt in block.statements) {
            val state = computeStatementInstanceState(stmt)
            if (state == ModuleInstanceState.Instantiated) return ModuleInstanceState.Instantiated
            if (state == ModuleInstanceState.ConstEnumOnly) hasConstEnum = true
        }
        return if (hasConstEnum) ModuleInstanceState.ConstEnumOnly
               else ModuleInstanceState.NonInstantiated
    }

    private fun computeStatementInstanceState(stmt: Statement): ModuleInstanceState {
        return when (stmt) {
            is InterfaceDeclaration -> ModuleInstanceState.NonInstantiated
            is TypeAliasDeclaration -> ModuleInstanceState.NonInstantiated
            is ImportDeclaration -> ModuleInstanceState.NonInstantiated
            is ImportEqualsDeclaration -> {
                if (ModifierFlag.Export !in stmt.modifiers) ModuleInstanceState.NonInstantiated
                else ModuleInstanceState.Instantiated
            }
            is ExportDeclaration -> {
                if (stmt.isTypeOnly) ModuleInstanceState.NonInstantiated
                else ModuleInstanceState.Instantiated
            }
            is EnumDeclaration -> {
                if (ModifierFlag.Const in stmt.modifiers && !options.preserveConstEnums)
                    ModuleInstanceState.ConstEnumOnly
                else ModuleInstanceState.Instantiated
            }
            is ModuleDeclaration -> computeModuleInstanceState(stmt)
            else -> ModuleInstanceState.Instantiated
        }
    }

    // -----------------------------------------------------------------------
    // INV.2(c): additive lexical binding — per-node scope tables
    // -----------------------------------------------------------------------

    /**
     * INV.2(c): full-tree lexical binding, run AFTER conventional binding,
     * writing ONLY to new structures — [LexicalScope]s keyed by owner `nodeId`
     * and symbols from the separate negative id space ([Symbol.scopeSymbol]).
     * Conventional binder output (`locals`, [nodeToSymbol], the global
     * symbol-id sequence) stays byte-unchanged; the tables are UNCONSUMED
     * until INV.4.
     *
     * Scope owners — phase (i), tsc `IsContainer`: the [SourceFile] root
     * (aliasing file locals), [ModuleDeclaration] (aliasing the merged
     * namespace `exports`, one chained level per dotted segment — the
     * checker's B512 rule), and the seven function-like kinds plus
     * [ClassStaticBlockDeclaration] (fresh tables: type params, params —
     * `this` params excluded — a named function expression's self-name,
     * body-top-level declarations, and `var`s hoisted to the nearest
     * function/file/module boundary from any block depth). Phase (ii), tsc
     * `IsBlockScopedContainer` + the remaining containers: every [Block] that
     * is NOT a function-like's immediate body (the body shares the function's
     * scope — tsc `getContainerFlags`), `for`/`for-in`/`for-of` headers,
     * [CatchClause] (binding the catch variable), [SwitchStatement] standing
     * in for tsc's CaseBlock (the switch EXPRESSION routes to the OUTER
     * scope), class scopes (type params; a named class EXPRESSION's
     * self-name), interface / type-alias scopes (type params), and enum
     * scopes (member names resolve bare in sibling initializers — aliasing
     * the main-bound `exports`, or scope-space members for nested enums).
     * Block-scoped declarations (`let`/`const`/`class`/`interface`/`type`/
     * `enum`/`function` — function declarations use strict/module semantics,
     * binding to the block) land in the nearest fresh scope; a name the main
     * binder already bound in an aliased container is skipped. Decorators of
     * a function-like/class walk under the OUTER scope (parameter decorators
     * walk under the function scope — a known refinement).
     *
     * ITERATIVE (parallel explicit stacks) by project rule — binary chains far
     * beyond corpus depth exist and [Binder] must work off the deep-stack
     * thread (the local tests bind a 30k-term chain on a plain thread).
     */
    private fun bindLexicalScopes(
        sourceFile: SourceFile,
        fileLocals: SymbolTable,
        lexOwners: Map<Int, Symbol>,
    ): MutableMap<Int, LexicalScope> {
        val scopes = HashMap<Int, LexicalScope>()
        // Unindexed (hand-constructed) tree: INV.2(a) nodeIds are absent, so
        // per-nodeId keying is impossible — conventional binding stands alone.
        if (sourceFile.nodeCount == 0) return scopes
        val root = LexicalScope(sourceFile, parent = null, existing = fileLocals)
        if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_BINDER_SCOPES, sourceFile.nodeId.toLong())
        scopes[sourceFile.nodeId] = root

        val nodeStack = ArrayList<Node>(64)
        val scopeStack = ArrayList<LexicalScope>(64)
        val buf = ArrayList<Node>(16)
        val collect: (Node) -> Unit = { buf.add(it) }

        fun pushChildren(node: Node, scope: LexicalScope, decoratorScope: LexicalScope = scope) {
            buf.clear()
            forEachChild(node, collect)
            for (i in buf.indices.reversed()) {
                val child = buf[i]
                nodeStack.add(child)
                scopeStack.add(if (child is Decorator) decoratorScope else scope)
            }
        }

        pushChildren(sourceFile, root)
        // (FRONT.2) census — a LOCAL counter, so the walk carries one register
        // increment and no probe-mode branch; only the hand-off at the end is
        // conditional. Pops are the walk's real population.
        var pops = 0L
        while (nodeStack.isNotEmpty()) {
            val node = nodeStack.removeAt(nodeStack.size - 1)
            val scope = scopeStack.removeAt(scopeStack.size - 1)
            pops++
            when (node) {
                is FunctionDeclaration -> {
                    // The function's NAME binds into the enclosing fresh scope
                    // (function body, nested block, case clause — strict/module
                    // semantics: block-nested function declarations bind to the
                    // block). File/module level is the main binder's own,
                    // reached via `existing`.
                    if (scope.existing == null && node.name != null) {
                        declareLexical(scope, node.name.text, SymbolFlags.Function, node)
                    }
                    val fnScope = newLexicalScope(node, scope, scopes)
                    bindLexicalTypeParameters(node.typeParameters, fnScope)
                    bindLexicalParameters(node.parameters, fnScope)
                    pushChildren(node, fnScope)
                }
                is FunctionExpression -> {
                    val fnScope = newLexicalScope(node, scope, scopes)
                    // A named function expression's name is visible only inside its own body.
                    node.name?.let { declareLexical(fnScope, it.text, SymbolFlags.Function, node) }
                    bindLexicalTypeParameters(node.typeParameters, fnScope)
                    bindLexicalParameters(node.parameters, fnScope)
                    pushChildren(node, fnScope)
                }
                is ArrowFunction -> {
                    val fnScope = newLexicalScope(node, scope, scopes)
                    bindLexicalTypeParameters(node.typeParameters, fnScope)
                    bindLexicalParameters(node.parameters, fnScope)
                    pushChildren(node, fnScope)
                }
                is MethodDeclaration -> {
                    val fnScope = newLexicalScope(node, scope, scopes)
                    bindLexicalTypeParameters(node.typeParameters, fnScope)
                    bindLexicalParameters(node.parameters, fnScope)
                    pushChildren(node, fnScope, decoratorScope = scope)
                }
                is Constructor -> {
                    val fnScope = newLexicalScope(node, scope, scopes)
                    bindLexicalParameters(node.parameters, fnScope)
                    pushChildren(node, fnScope, decoratorScope = scope)
                }
                is GetAccessor -> {
                    val fnScope = newLexicalScope(node, scope, scopes)
                    bindLexicalParameters(node.parameters, fnScope)
                    pushChildren(node, fnScope, decoratorScope = scope)
                }
                is SetAccessor -> {
                    val fnScope = newLexicalScope(node, scope, scopes)
                    bindLexicalParameters(node.parameters, fnScope)
                    pushChildren(node, fnScope, decoratorScope = scope)
                }
                is ClassStaticBlockDeclaration -> {
                    // A static block is a var-hoisting boundary (tsc: IsContainer).
                    pushChildren(node, newLexicalScope(node, scope, scopes))
                }
                is ModuleDeclaration -> {
                    // The namespace NAME binds into an enclosing FRESH scope (a
                    // block-/function-body-nested namespace is a TS1235 grammar
                    // error, but tsc still binds it — `export = M` in the same
                    // block resolves M). Mirrors the checker's legacy collect:
                    // dotted names bind the LEFTMOST segment; `declare global`
                    // is an augmentation, not a binding (GH#42209); a
                    // StringLiteral-named ambient module has no identifier name.
                    if (scope.existing == null) {
                        when (val mname = node.name) {
                            is Identifier ->
                                if (!(mname.text == "global" && ModifierFlag.Declare in node.modifiers)) {
                                    declareLexical(scope, mname.text, SymbolFlags.ValueModule, node)
                                }
                            is PropertyAccessExpression -> {
                                var cur: Expression = mname
                                while (cur is PropertyAccessExpression) cur = cur.expression
                                (cur as? Identifier)?.let {
                                    declareLexical(scope, it.text, SymbolFlags.ValueModule, node)
                                }
                            }
                            else -> {}
                        }
                    }
                    pushChildren(node, moduleLexicalScope(node, scope, scopes, lexOwners))
                }
                is ImportEqualsDeclaration -> {
                    // Nested imports are grammar errors (TS1232) tsc still binds.
                    if (scope.existing == null) {
                        declareLexical(scope, node.name.text, SymbolFlags.Alias, node)
                    }
                    pushChildren(node, scope)
                }
                is ImportDeclaration -> {
                    if (scope.existing == null) {
                        val clause = node.importClause
                        clause?.name?.let { declareLexical(scope, it.text, SymbolFlags.Alias, node) }
                        when (val bindings = clause?.namedBindings) {
                            is NamedImports -> for (spec in bindings.elements) {
                                declareLexical(scope, spec.name.text, SymbolFlags.Alias, spec)
                            }
                            is NamespaceImport ->
                                declareLexical(scope, bindings.name.text, SymbolFlags.Alias, bindings)
                            else -> {}
                        }
                    }
                    pushChildren(node, scope)
                }
                is VariableDeclarationList -> {
                    bindLexicalVariableList(node, scope)
                    pushChildren(node, scope)
                }
                is Block -> {
                    // A function-like's immediate body shares the function's scope
                    // (tsc getContainerFlags); every other block is a block-scope
                    // container.
                    val parent = node.parent
                    val isFunctionBody = parent is FunctionDeclaration || parent is FunctionExpression ||
                        parent is ArrowFunction || parent is MethodDeclaration || parent is Constructor ||
                        parent is GetAccessor || parent is SetAccessor || parent is ClassStaticBlockDeclaration
                    pushChildren(node, if (isFunctionBody) scope else newLexicalScope(node, scope, scopes))
                }
                is ForStatement -> pushChildren(node, newLexicalScope(node, scope, scopes))
                is ForInStatement -> pushChildren(node, newLexicalScope(node, scope, scopes))
                is ForOfStatement -> pushChildren(node, newLexicalScope(node, scope, scopes))
                is CatchClause -> {
                    val catchScope = newLexicalScope(node, scope, scopes)
                    node.variableDeclaration?.let {
                        bindLexicalBindingName(it.name, SymbolFlags.BlockScopedVariable, it, catchScope)
                    }
                    pushChildren(node, catchScope)
                }
                is SwitchStatement -> {
                    // tsc's block-scope container here is the CaseBlock; our AST has
                    // no CaseBlock node, so the SwitchStatement owns the scope and
                    // the switch EXPRESSION is routed to the OUTER scope by hand
                    // (pushed LAST so it pops FIRST — sibling visit order stays
                    // source order, which the first-wins merge semantics rely on).
                    val caseScope = newLexicalScope(node, scope, scopes)
                    for (i in node.caseBlock.indices.reversed()) {
                        nodeStack.add(node.caseBlock[i])
                        scopeStack.add(caseScope)
                    }
                    nodeStack.add(node.expression)
                    scopeStack.add(scope)
                }
                is ClassDeclaration -> {
                    if (scope.existing == null && node.name != null) {
                        declareLexical(scope, node.name.text, SymbolFlags.Class, node)
                    }
                    val classScope = newLexicalScope(node, scope, scopes)
                    bindLexicalTypeParameters(node.typeParameters, classScope)
                    pushChildren(node, classScope, decoratorScope = scope)
                }
                is ClassExpression -> {
                    val classScope = newLexicalScope(node, scope, scopes)
                    // A named class expression's name is visible only inside its own body.
                    node.name?.let { declareLexical(classScope, it.text, SymbolFlags.Class, node) }
                    bindLexicalTypeParameters(node.typeParameters, classScope)
                    pushChildren(node, classScope, decoratorScope = scope)
                }
                is InterfaceDeclaration -> {
                    if (scope.existing == null) {
                        declareLexical(scope, node.name.text, SymbolFlags.Interface, node)
                    }
                    val ifaceScope = newLexicalScope(node, scope, scopes)
                    bindLexicalTypeParameters(node.typeParameters, ifaceScope)
                    pushChildren(node, ifaceScope)
                }
                is TypeAliasDeclaration -> {
                    if (scope.existing == null) {
                        declareLexical(scope, node.name.text, SymbolFlags.TypeAlias, node)
                    }
                    val aliasScope = newLexicalScope(node, scope, scopes)
                    bindLexicalTypeParameters(node.typeParameters, aliasScope)
                    pushChildren(node, aliasScope)
                }
                is EnumDeclaration -> {
                    var enumSymbol = lexOwners[node.nodeId]
                    if (scope.existing == null) {
                        val flags = if (ModifierFlag.Const in node.modifiers) SymbolFlags.ConstEnum
                                    else SymbolFlags.RegularEnum
                        declareLexical(scope, node.name.text, flags, node)?.let { enumSymbol = it }
                    }
                    // Member names resolve BARE inside sibling member initializers
                    // (`enum E { A = 1, B = A }`) — the enum scope provides them:
                    // aliasing the main binder's exports for a conventionally-bound
                    // enum, scope-space members (also published onto the SCOPE
                    // symbol's exports for `E.A`-style consumers) for a nested one.
                    val mainExports = enumSymbol?.takeIf { it.id >= 1 }?.exports
                    val enumScope = LexicalScope(node, scope, existing = mainExports)
                    if (node.nodeId >= 0) {
                        if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_BINDER_SCOPES, node.nodeId.toLong())
                        scopes[node.nodeId] = enumScope
                    }
                    bindLexicalEnumMembers(node, enumSymbol, enumScope)
                    pushChildren(node, enumScope)
                }
                else -> pushChildren(node, scope)
            }
        }
        FrontEnd.addLexCensus(pops)
        return scopes
    }

    /** Create + register a fresh function-like scope under its owner's nodeId. */
    private fun newLexicalScope(
        owner: Node,
        parent: LexicalScope,
        scopes: MutableMap<Int, LexicalScope>,
    ): LexicalScope {
        val scope = LexicalScope(owner, parent)
        val id = (owner as NodeBase).nodeId
        if (id >= 0) {
            if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_BINDER_SCOPES, id.toLong())
            scopes[id] = scope
        }
        return scope
    }

    /**
     * Build the scope chain for a namespace/module declaration: one level per
     * dotted segment (`namespace A.B.C` → three, outermost first), each ALIASING
     * that segment's merged `exports` — mirroring the checker's dotted-namespace
     * rule (B512). [nodeToSymbol] holds the INNERMOST segment's symbol (the
     * dotted-namespace gotcha); outer segments are recovered via `symbol.parent`.
     * Only the innermost level registers under the declaration's nodeId.
     */
    private fun moduleLexicalScope(
        node: ModuleDeclaration,
        outer: LexicalScope,
        scopes: MutableMap<Int, LexicalScope>,
        lexOwners: Map<Int, Symbol>,
    ): LexicalScope {
        var segCount = 1
        var cur: Expression = node.name
        while (cur is PropertyAccessExpression) {
            segCount++
            cur = cur.expression
        }
        val segSymbols = arrayOfNulls<Symbol>(segCount)
        var sym = lexOwners[node.nodeId]
        for (i in segCount - 1 downTo 0) {
            segSymbols[i] = sym
            sym = sym?.parent
        }
        var scope = outer
        for (i in 0 until segCount) {
            scope = LexicalScope(node, scope, existing = segSymbols[i]?.exports)
        }
        if (node.nodeId >= 0) {
            if (MapCensus.boxedKeyCensus) MapCensus.bk(MapCensus.BK_BINDER_SCOPES, node.nodeId.toLong())
            scopes[node.nodeId] = scope
        }
        return scope
    }

    /**
     * Variable rules: `var` hoists to the nearest function-like / file /
     * module scope ([varHoistTarget]) from ANY block depth — skipping only a
     * VariableStatement DIRECTLY at file/module top level, which the main
     * binder already owns (reached via [LexicalScope.existing]). `let`/`const`
     * bind into the CURRENT scope, which with phase (ii)'s block-scope
     * containers is always the correct nearest block-scope container — a
     * file/module-level declaration (the main binder's own) is skipped via
     * the aliasing `existing` table.
     */
    private fun bindLexicalVariableList(list: VariableDeclarationList, scope: LexicalScope) {
        if (list.flags == SyntaxKind.VarKeyword) {
            val target = varHoistTarget(scope)
            val governing = list.parent
            val mainBinderOwns = governing is VariableStatement &&
                target.existing != null && isDirectBodyChild(governing, target)
            if (mainBinderOwns) return
            for (decl in list.declarations) {
                bindLexicalBindingName(decl.name, SymbolFlags.FunctionScopedVariable, decl, target)
            }
        } else {
            if (scope.existing != null) return
            for (decl in list.declarations) {
                bindLexicalBindingName(decl.name, SymbolFlags.BlockScopedVariable, decl, scope)
            }
        }
    }

    /** The nearest enclosing `var`-hoisting boundary: function-like, file, or module scope. */
    private fun varHoistTarget(scope: LexicalScope): LexicalScope {
        var current = scope
        while (true) {
            when (current.owner) {
                is SourceFile, is ModuleDeclaration, is FunctionDeclaration, is FunctionExpression,
                is ArrowFunction, is MethodDeclaration, is Constructor, is GetAccessor,
                is SetAccessor, is ClassStaticBlockDeclaration -> return current
                else -> current = current.parent ?: return current
            }
        }
    }

    /** Is [stmt] a direct statement of [scope]'s owner body (its immediate statement list)? */
    private fun isDirectBodyChild(stmt: Node, scope: LexicalScope): Boolean {
        val parent = (stmt as NodeBase).parent ?: return false
        return when (val owner = scope.owner) {
            is SourceFile -> parent === owner
            is ModuleDeclaration -> parent is ModuleBlock && parent.parent === owner
            else -> parent is Block && parent.parent === owner
        }
    }

    /**
     * Mirror of the main binder's enum-member binding for the LEXICAL enum
     * scope. For a main-bound enum every member hits the `existing` skip
     * (aliased exports); for a lexically-bound nested enum the scope-space
     * member symbols are ALSO published onto the (scope-space, id ≤ −2)
     * enum symbol's `exports` — a MAIN symbol's exports are never touched.
     */
    private fun bindLexicalEnumMembers(
        decl: EnumDeclaration,
        enumSymbol: Symbol?,
        enumScope: LexicalScope,
    ) {
        for (member in decl.members) {
            val memberName = when (val n = member.name) {
                is Identifier -> n.text
                is StringLiteralNode -> n.text
                is NumericLiteralNode -> n.text
                is ComputedPropertyName -> when (val e = n.expression) {
                    is StringLiteralNode -> e.text
                    is NumericLiteralNode -> e.text
                    else -> continue
                }
                else -> continue
            }
            val memberSymbol = declareLexical(enumScope, memberName, SymbolFlags.EnumMember, member) ?: continue
            if (enumSymbol != null && enumSymbol.id <= -2) {
                memberSymbol.parent = enumSymbol
                val exports = enumSymbol.exports ?: symbolTable().also { enumSymbol.exports = it }
                exports[memberName] = memberSymbol
            }
        }
    }

    /** Mirror of [bindVariableDeclarationName] targeting a lexical scope. */
    private fun bindLexicalBindingName(
        name: Expression,
        flags: SymbolFlags,
        declarationNode: Node,
        scope: LexicalScope,
    ) {
        when (name) {
            is Identifier -> declareLexical(scope, name.text, flags, declarationNode)
            is ObjectBindingPattern -> {
                for (element in name.elements) {
                    bindLexicalBindingName(element.name, flags, element, scope)
                }
            }
            is ArrayBindingPattern -> {
                for (element in name.elements) {
                    if (element is BindingElement) {
                        bindLexicalBindingName(element.name, flags, element, scope)
                    }
                }
            }
            else -> { /* computed property names, error recovery — skip */ }
        }
    }

    private fun bindLexicalParameters(parameters: List<Parameter>, scope: LexicalScope) {
        for (param in parameters) {
            val name = param.name
            // A `this` parameter is a signature annotation, not a binding — tsc
            // resolves `this` specially, never through locals.
            if (name is Identifier && name.text == "this") continue
            bindLexicalBindingName(name, SymbolFlags.FunctionScopedVariable, param, scope)
        }
    }

    private fun bindLexicalTypeParameters(typeParameters: List<TypeParameter>?, scope: LexicalScope) {
        if (typeParameters == null) return
        for (tp in typeParameters) {
            declareLexical(scope, tp.name.text, SymbolFlags.TypeParameter, tp)
        }
    }

    /**
     * Declare into a lexical scope using the SEPARATE scope-symbol id space.
     * Mirrors [declareSymbol]'s merge semantics (including the B505 Class+Class
     * first-wins rule) but never touches shared binder state: no global-id
     * [Symbol] construction, no [recordNodeSymbol], no writes to
     * [LexicalScope.existing]. A name the main binder already bound in this
     * container is skipped — resolution falls through to the complete existing
     * symbol (attaching the extra declaration would mutate shared state).
     */
    private fun declareLexical(
        scope: LexicalScope,
        name: String,
        flags: SymbolFlags,
        declarationNode: Node,
    ): Symbol? {
        if (scope.existing?.containsKey(name) == true) return null
        val existing = scope.symbols[name]
        if (existing != null && canMerge(existing.flags, flags)) {
            existing.flags = existing.flags or flags
            existing.declarations.add(declarationNode)
            if (existing.valueDeclaration == null && flags.hasAny(SymbolFlags.Value)) {
                existing.valueDeclaration = declarationNode
            }
            return existing
        }
        val symbol = Symbol.scopeSymbol(flags, name)
        symbol.declarations.add(declarationNode)
        if (flags.hasAny(SymbolFlags.Value)) {
            symbol.valueDeclaration = declarationNode
        }
        val classDuplicate = existing != null &&
            existing.flags.hasAny(SymbolFlags.Class) && flags.hasAny(SymbolFlags.Class)
        if (!classDuplicate) {
            scope.symbols[name] = symbol
        }
        return symbol
    }
}

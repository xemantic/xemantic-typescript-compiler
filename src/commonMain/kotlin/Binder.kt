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
)

/**
 * Walks a [SourceFile] AST and creates [Symbol]s for all declarations,
 * building scope chains and handling declaration merging.
 *
 * The binder produces a [BinderResult] that the [Checker] uses for
 * import reference tracking and const enum value resolution.
 */
class Binder(private val options: CompilerOptions) {

    private val nodeToSymbol = mutableMapOf<Long, Symbol>()
    private val moduleInstanceStates = mutableMapOf<Long, ModuleInstanceState>()

    /** The current symbol table where new declarations are added. */
    private var currentScope: SymbolTable = symbolTable()

    fun bind(sourceFile: SourceFile): BinderResult {
        val fileLocals = symbolTable()
        currentScope = fileLocals
        bindStatements(sourceFile.statements)
        return BinderResult(sourceFile, fileLocals, nodeToSymbol, moduleInstanceStates)
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
        val isConst = ModifierFlag.Const in decl.modifiers
        var flags = if (isConst) SymbolFlags.ConstEnum else SymbolFlags.RegularEnum
        if (ModifierFlag.Export in decl.modifiers) {
            flags = flags or SymbolFlags.ExportValue
        }
        val symbol = declareSymbol(currentScope, decl.name.text, flags, decl)

        // Bind enum members into the enum's exports table
        if (symbol.exports == null) symbol.exports = symbolTable()
        val memberScope = symbol.exports!!
        for (member in decl.members) {
            val memberName = when (val n = member.name) {
                is Identifier -> n.text
                is StringLiteralNode -> n.text
                is NumericLiteralNode -> n.text
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
        val name = when (val n = decl.name) {
            is Identifier -> n.text
            is StringLiteralNode -> n.text
            else -> return
        }

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
        val symbol = declareSymbol(currentScope, name, flags, decl)

        // Bind the module body in a nested scope
        val body = decl.body
        if (body != null) {
            if (symbol.exports == null) symbol.exports = symbolTable()
            val savedScope = currentScope
            currentScope = symbol.exports!!
            when (body) {
                is ModuleBlock -> bindStatements(body.statements)
                is ModuleDeclaration -> bindModuleDeclaration(body)
                else -> { /* empty body */ }
            }
            currentScope = savedScope
        }
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
                    recordNodeSymbol(decl, nodeToSymbol[nodeKey(bindings.elements.first())]!!)
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
        scope[name] = symbol
        recordNodeSymbol(declarationNode, symbol)
        return symbol
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
        // Class + Module
        if (existing.hasAny(SymbolFlags.Class) && incoming.hasAny(SymbolFlags.Module)) return true
        if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Class)) return true
        // Function + Module
        if (existing.hasAny(SymbolFlags.Function) && incoming.hasAny(SymbolFlags.Module)) return true
        if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Function)) return true
        // Enum + Module
        if (existing.hasAny(SymbolFlags.Enum) && incoming.hasAny(SymbolFlags.Module)) return true
        if (existing.hasAny(SymbolFlags.Module) && incoming.hasAny(SymbolFlags.Enum)) return true
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
}

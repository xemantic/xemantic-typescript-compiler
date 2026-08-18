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

package com.xemantic.typescript.compiler.project

import com.xemantic.typescript.compiler.ArrayLiteralExpression
import com.xemantic.typescript.compiler.ArrowFunction
import com.xemantic.typescript.compiler.AsExpression
import com.xemantic.typescript.compiler.BinaryExpression
import com.xemantic.typescript.compiler.BindingElement
import com.xemantic.typescript.compiler.Block
import com.xemantic.typescript.compiler.BreakStatement
import com.xemantic.typescript.compiler.CaseClause
import com.xemantic.typescript.compiler.ClassDeclaration
import com.xemantic.typescript.compiler.ClassExpression
import com.xemantic.typescript.compiler.Constructor
import com.xemantic.typescript.compiler.ContinueStatement
import com.xemantic.typescript.compiler.DefaultClause
import com.xemantic.typescript.compiler.DoStatement
import com.xemantic.typescript.compiler.ElementAccessExpression
import com.xemantic.typescript.compiler.EnumDeclaration
import com.xemantic.typescript.compiler.EnumMember
import com.xemantic.typescript.compiler.ExportSpecifier
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.ExpressionStatement
import com.xemantic.typescript.compiler.ForInStatement
import com.xemantic.typescript.compiler.ForOfStatement
import com.xemantic.typescript.compiler.ForStatement
import com.xemantic.typescript.compiler.FunctionDeclaration
import com.xemantic.typescript.compiler.FunctionExpression
import com.xemantic.typescript.compiler.GetAccessor
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.ImportClause
import com.xemantic.typescript.compiler.ImportEqualsDeclaration
import com.xemantic.typescript.compiler.ImportSpecifier
import com.xemantic.typescript.compiler.InterfaceDeclaration
import com.xemantic.typescript.compiler.LabeledStatement
import com.xemantic.typescript.compiler.MetaProperty
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.ModifierFlag
import com.xemantic.typescript.compiler.ModuleBlock
import com.xemantic.typescript.compiler.ModuleDeclaration
import com.xemantic.typescript.compiler.NamedTupleMember
import com.xemantic.typescript.compiler.NamespaceExport
import com.xemantic.typescript.compiler.NamespaceImport
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.NonNullExpression
import com.xemantic.typescript.compiler.ObjectLiteralExpression
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.ParenthesizedExpression
import com.xemantic.typescript.compiler.PostfixUnaryExpression
import com.xemantic.typescript.compiler.PrefixUnaryExpression
import com.xemantic.typescript.compiler.PropertyAccessExpression
import com.xemantic.typescript.compiler.PropertyAssignment
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.QualifiedName
import com.xemantic.typescript.compiler.SatisfiesExpression
import com.xemantic.typescript.compiler.SetAccessor
import com.xemantic.typescript.compiler.ShorthandPropertyAssignment
import com.xemantic.typescript.compiler.SourceFile
import com.xemantic.typescript.compiler.SpreadAssignment
import com.xemantic.typescript.compiler.SpreadElement
import com.xemantic.typescript.compiler.Statement
import com.xemantic.typescript.compiler.SwitchStatement
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.TypeAliasDeclaration
import com.xemantic.typescript.compiler.TypeNode
import com.xemantic.typescript.compiler.TypeParameter
import com.xemantic.typescript.compiler.TypeQuery
import com.xemantic.typescript.compiler.VariableDeclaration
import com.xemantic.typescript.compiler.WhileStatement
import com.xemantic.typescript.compiler.isAssignmentOperator

/**
 * (API.7) WHERE IN THE GRAMMAR a caret sits — the classification a keyword list is
 * keyed on.
 *
 * The caret's own position, not a node's role: [SyntaxRoles.grammarPositionOf] takes
 * the [SourceIndex.pathAt] path and says which productions may begin there.
 */
internal enum class GrammarPosition {

    /** A STATEMENT or a declaration may begin here — which also admits an expression. */
    STATEMENT,

    /** An EXPRESSION may appear here and a declaration may not. */
    EXPRESSION,

    /** A TYPE may appear here. */
    TYPE,

    /**
     * None of the three, or a position this classifier declines to name: a class or
     * interface BODY (a member position), a heritage clause, an import/export clause,
     * a declaration's own name. Nothing is offered rather than something plausible.
     */
    NONE,
}

/**
 * (API.7) THE MECHANISM this round exists to build: what syntactic role a node — or
 * a caret — plays, answered by a PULL-BASED ASCENT of the parent chain.
 *
 * ## Why an ascent, and why pull
 *
 * INV.2(a) stamps `parent` on every node at the end of every parse, so a role is a
 * pointer walk from the node outwards and needs no side table, no second traversal
 * and no state maintained by whoever is walking. That is the same shape the INV.4
 * reach classifiers use inside the checker, and round 875 measured the alternative:
 * a PUSH-based status maintained by the walk is **11.1x more work**, because it must
 * compute a status for every classifier at every node while a pull folds only the
 * ancestors of the nodes actually asked about. Here the population is a handful of
 * nodes per query — one caret, or one identifier per reference hit — so the ascent
 * is not even on a hot path.
 *
 * ## Two questions, one traversal
 *
 * [referenceUse] answers about a NODE ("is this occurrence read or written") and
 * [grammarPositionOf] answers about a CARET ("what may be written here"), and the
 * second is expressed on the first: an identifier is in an EXPRESSION position
 * exactly when it is a value occurrence, which is what [referenceUse] decides.
 *
 * ## Identity, never equality
 *
 * Every `===` in this file is deliberate. AST nodes are Kotlin `data class`es, so
 * `==` and `hashCode()` DEEP-RECURSE the whole subtree (round 471) — a `current in
 * p.elements` would be a structural comparison of two arbitrary expressions where an
 * identity test was meant.
 */
internal object SyntaxRoles {

    /** The parent chain is finite, but a corrupt one must not hang a host. */
    private const val MAX_ASCENT = 256

    /**
     * How the occurrence [node] uses what it names.
     *
     * ## The WRITE set, stated completely
     *
     * - the left of a simple assignment (`x = 1`), including through parentheses,
     *   `!`, `as` and `satisfies`, and including a MEMBER (`o.p = 1`, where the
     *   occurrence is `p`);
     * - a DESTRUCTURING target, in array and object form, at any nesting depth, with
     *   defaults (`[x = 1] = pair`), shorthand (`({ x } = o)`), renaming
     *   (`({ a: x } = o)`) and rest (`[...xs] = arr`, `({ ...rest } = o)`);
     * - the head of a `for (x of xs)` / `for (x in o)` that is an expression rather
     *   than a declaration, destructured or not;
     * - a PARAMETER's own name, and a variable or binding-element declaration's own
     *   name — the occurrence at which the binding receives its first value.
     *
     * [ReferenceUse.READ_WRITE] is the compound assignments (`+=` and the rest, which
     * `isAssignmentOperator` enumerates) and the update operators (`++`, `--`, prefix
     * and postfix), each of which reads the old value before storing.
     *
     * ## What is UNCLASSIFIED, and why that is a state rather than a default
     *
     * A name that is not a value occurrence at all: a TYPE-position name, a
     * declaration name that binds no storage (a function, class, interface, type
     * alias, enum, namespace, type parameter, import or export specifier, class
     * member name), an object-literal KEY being declared, a binding element's SOURCE
     * property name, and a label. Reporting those as READS would be a lie a host
     * cannot detect, which is exactly why (API.5) refused the whole distinction
     * rather than shipping the easy half.
     *
     * Anything the ascent reaches without matching a rule is a READ, which is what an
     * occurrence in a value position is.
     */
    fun referenceUse(node: Node): ReferenceUse {
        val parent = parentOf(node) ?: return ReferenceUse.UNCLASSIFIED
        // A name in TYPE position names no value. `typeof x` is the exception the
        // grammar itself makes: its operand IS a value name.
        typePositionUse(node, parent)?.let { return it }
        // A declaration's own name, split by whether the declaration binds STORAGE.
        when (parent) {
            is Parameter -> if (parent.name === node) return ReferenceUse.WRITE
            is VariableDeclaration -> if (parent.name === node) return ReferenceUse.WRITE
            is BindingElement -> {
                if (parent.propertyName === node) return ReferenceUse.UNCLASSIFIED
                if (parent.name === node) return ReferenceUse.WRITE
            }
            is PropertyAssignment -> if (parent.name === node) return ReferenceUse.UNCLASSIFIED
            else -> Unit
        }
        if (isNonStorageDeclarationName(parent, node)) return ReferenceUse.UNCLASSIFIED
        return ascendUse(node)
    }

    /**
     * The use of a name sitting in a TYPE position, or null when it is not in one.
     *
     * A `QualifiedName` chain is followed to its root because `A.B.C` in a type is one
     * name spelled in three nodes; the chain's own parent decides. `typeof A.B` is a
     * VALUE reference wearing a type node, which is why the answer there is READ and
     * not UNCLASSIFIED.
     */
    private fun typePositionUse(node: Node, parent: Node): ReferenceUse? {
        if (parent is TypeQuery) return if (parent.exprName === node) ReferenceUse.READ else null
        var top: Node = node
        var above: Node = parent
        var steps = 0
        while (above is QualifiedName && steps++ < MAX_ASCENT) {
            top = above
            above = parentOf(top) ?: return ReferenceUse.UNCLASSIFIED
        }
        if (top !== node) {
            // The chain's root: a `typeof A.B` operand is a value, anything else is a type.
            return if (above is TypeQuery) ReferenceUse.READ else ReferenceUse.UNCLASSIFIED
        }
        return if (parent is TypeNode) ReferenceUse.UNCLASSIFIED else null
    }

    /**
     * True when [node] is the NAME of [parent] and [parent] declares something that is
     * not a storage location — see [referenceUse] for the split.
     */
    private fun isNonStorageDeclarationName(parent: Node, node: Node): Boolean = when (parent) {
        is FunctionDeclaration -> parent.name === node
        is FunctionExpression -> parent.name === node
        is ClassDeclaration -> parent.name === node
        is ClassExpression -> parent.name === node
        is InterfaceDeclaration -> parent.name === node
        is TypeAliasDeclaration -> parent.name === node
        is EnumDeclaration -> parent.name === node
        is EnumMember -> parent.name === node
        is ModuleDeclaration -> parent.name === node
        is TypeParameter -> parent.name === node
        is ImportClause -> parent.name === node
        is ImportSpecifier -> parent.name === node || parent.propertyName === node
        is ExportSpecifier -> parent.name === node || parent.propertyName === node
        is NamespaceImport -> parent.name === node
        is NamespaceExport -> parent.name === node
        is ImportEqualsDeclaration -> parent.name === node
        is PropertyDeclaration -> parent.name === node
        is MethodDeclaration -> parent.name === node
        is GetAccessor -> parent.name === node
        is SetAccessor -> parent.name === node
        is NamedTupleMember -> parent.name === node
        is MetaProperty -> parent.name === node
        is LabeledStatement -> parent.label === node
        is BreakStatement -> parent.label === node
        is ContinueStatement -> parent.label === node
        else -> false
    }

    /**
     * The ascent proper: climb out of whatever wraps the occurrence until something
     * says how it is used.
     *
     * Every arm is a PASS-THROUGH or a VERDICT, so the walk terminates at the first
     * construct that decides — which is why a destructuring pattern nested to any
     * depth needs no rule of its own: an array literal inside an object literal
     * inside an array literal is three pass-throughs and then one assignment test.
     */
    private fun ascendUse(node: Node): ReferenceUse {
        var current: Node = node
        var steps = 0
        while (steps++ < MAX_ASCENT) {
            val parent = parentOf(current) ?: return ReferenceUse.READ
            when {
                parent is ParenthesizedExpression && parent.expression === current ->
                    current = parent
                parent is NonNullExpression && parent.expression === current -> current = parent
                parent is AsExpression && parent.expression === current -> current = parent
                parent is SatisfiesExpression && parent.expression === current -> current = parent
                // `o.p = 1` writes `p`; `o` in the same expression is READ, which is
                // why only the NAME edge ascends and the receiver edge does not.
                parent is PropertyAccessExpression && parent.name === current -> current = parent
                parent is BinaryExpression && parent.left === current &&
                    isAssignmentOperator(parent.operator) ->
                    return if (parent.operator == SyntaxKind.Equals) {
                        ReferenceUse.WRITE
                    } else {
                        ReferenceUse.READ_WRITE
                    }
                parent is PrefixUnaryExpression && parent.operand === current &&
                    isUpdateOperator(parent.operator) -> return ReferenceUse.READ_WRITE
                parent is PostfixUnaryExpression && parent.operand === current &&
                    isUpdateOperator(parent.operator) -> return ReferenceUse.READ_WRITE
                parent is ArrayLiteralExpression && containsIdentical(parent.elements, current) ->
                    current = parent
                parent is SpreadElement && parent.expression === current -> current = parent
                parent is PropertyAssignment && parent.initializer === current -> current = parent
                parent is ShorthandPropertyAssignment && parent.name === current -> current = parent
                parent is SpreadAssignment && parent.expression === current -> current = parent
                parent is ObjectLiteralExpression && containsIdentical(parent.properties, current) ->
                    current = parent
                parent is ForOfStatement && parent.initializer === current -> return ReferenceUse.WRITE
                parent is ForInStatement && parent.initializer === current -> return ReferenceUse.WRITE
                else -> return ReferenceUse.READ
            }
        }
        return ReferenceUse.UNCLASSIFIED
    }

    private fun isUpdateOperator(operator: SyntaxKind): Boolean =
        operator == SyntaxKind.PlusPlus || operator == SyntaxKind.MinusMinus

    private fun containsIdentical(nodes: List<Node>, node: Node): Boolean {
        for (candidate in nodes) if (candidate === node) return true
        return false
    }

    /** [node]'s parent, or null at the root or on an unindexed node (INV.2(a)). */
    private fun parentOf(node: Node): Node? = (node as NodeBase).parent

    // --- (API.7) the caret question -------------------------------------------

    /**
     * Which productions may begin at the caret whose enclosing-node [path] this is —
     * innermost LAST, as [SourceIndex.pathAt] answers.
     *
     * ## The rule, in the order it is applied
     *
     * An IDENTIFIER innermost is the ordinary case: the user has typed a word, and the
     * parser has already decided what that word is. Its PARENT is then the whole
     * answer — an `ExpressionStatement` parent means the word IS a complete statement,
     * so a statement may begin there; a type node parent means a type may; anything
     * else that [referenceUse] calls a value occurrence is an expression position; and
     * a declaration name, a member name or a label is a position this classifier
     * declines to name.
     *
     * With NO identifier at the caret — a blank line, a fresh `{`, a caret whose word
     * is itself a complete keyword — the path is walked INNERMOST FIRST and the first
     * node that decides wins: a type node, a member-position body (a class, an
     * interface, an enum or an object literal), a statement list, a statement (whose
     * non-block interior is an expression slot), or an expression.
     *
     * ## What it deliberately gets coarsely
     *
     * A caret whose word is a complete keyword (`if|`) usually answers EXPRESSION
     * rather than STATEMENT, because the parser has already built the statement that
     * keyword starts and the caret is inside it. That LOSES suggestions; it never
     * invents one, which is the direction this whole feature has to fail in.
     */
    fun grammarPositionOf(path: List<Node>): GrammarPosition {
        // Past the last character of the file, or in its trailing trivia: the source
        // file's own body, where a statement may begin.
        if (path.isEmpty()) return GrammarPosition.STATEMENT
        val innermost = path[path.size - 1]
        if (innermost is Identifier) {
            val parent = if (path.size >= 2) path[path.size - 2] else null
            return when {
                parent == null -> GrammarPosition.STATEMENT
                parent is ExpressionStatement -> GrammarPosition.STATEMENT
                parent is TypeQuery -> GrammarPosition.EXPRESSION
                parent is TypeNode || parent is QualifiedName -> GrammarPosition.TYPE
                referenceUse(innermost) == ReferenceUse.UNCLASSIFIED -> GrammarPosition.NONE
                else -> GrammarPosition.EXPRESSION
            }
        }
        for (index in path.indices.reversed()) {
            val node = path[index]
            when {
                node is TypeNode -> return GrammarPosition.TYPE
                node is ObjectLiteralExpression ||
                    node is ClassDeclaration ||
                    node is ClassExpression ||
                    node is InterfaceDeclaration ||
                    node is EnumDeclaration -> return GrammarPosition.NONE
                node is Block ||
                    node is SourceFile ||
                    node is ModuleBlock ||
                    node is CaseClause ||
                    node is DefaultClause -> return GrammarPosition.STATEMENT
                node is Expression -> return GrammarPosition.EXPRESSION
                // A statement reached before any statement LIST means the caret is in
                // that statement's interior — a condition, an initializer, a returned
                // value — every one of which is an expression slot.
                node is Statement -> return GrammarPosition.EXPRESSION
            }
        }
        return GrammarPosition.NONE
    }

    /**
     * The keywords that may legally be written at the caret whose [path] this is —
     * (API.7)'s cashing of round 918's refusal, and DELIBERATELY A SHORT LIST.
     *
     * ## What is offered, exactly
     *
     * At a [GrammarPosition.STATEMENT] caret: the statement and declaration starters,
     * plus everything an [GrammarPosition.EXPRESSION] caret gets (a statement position
     * admits an expression statement). At an EXPRESSION caret: the expression starters
     * only — which is what keeps `interface` out of `f(|)`. At a
     * [GrammarPosition.TYPE] caret: the primitive type names plus `keyof` and
     * `typeof`. At [GrammarPosition.NONE]: nothing.
     *
     * ## What is CONTEXT-GATED, and this is the point of the round
     *
     * - `await` only when the nearest enclosing function-like is `async`;
     * - `yield` only when it is a generator;
     * - `super` only inside a class body;
     * - `return` only inside a function-like;
     * - `break` only inside a loop or a `switch`, and `continue` only inside a loop —
     *   the scan stopping at the first function-like, because a loop does not reach
     *   into a nested function;
     * - the module-level declaration starters (`import`, `export`, `declare`,
     *   `namespace`, `interface`, `type`, `enum`) only when the enclosing statement
     *   list is a source file or a namespace body, never inside a function body.
     *
     * ## What is NOT offered, and why saying so matters
     *
     * `else`, `case`, `default`, `catch`, `finally`, `extends`, `implements`, `in`,
     * `instanceof`, `as`, `satisfies`, `of`, `from`, `is`, `asserts`, `infer`,
     * `unique`, `readonly`, `out`, `override`, `accessor` and the accessibility
     * modifiers are all positions this classifier does not name — they are legal only
     * in continuations and clauses ([GrammarPosition.NONE] covers a class body, a
     * heritage clause and an import clause outright). A host that wants them merges
     * its own list, exactly as it did before this round; nothing here depends on the
     * list being complete, only on every item in it compiling where it is offered.
     *
     * Keywords are offered at FREE-NAME carets ONLY. After a `.` the candidates are a
     * type's members and no keyword may be written at all.
     */
    fun keywordsFor(path: List<Node>): List<String> =
        when (grammarPositionOf(path)) {
            GrammarPosition.NONE -> emptyList()
            GrammarPosition.TYPE -> TYPE_KEYWORDS
            GrammarPosition.EXPRESSION -> expressionKeywords(path)
            GrammarPosition.STATEMENT -> {
                val keywords = ArrayList(expressionKeywords(path))
                keywords.addAll(STATEMENT_KEYWORDS)
                if (atModuleLevel(path)) keywords.addAll(MODULE_LEVEL_KEYWORDS)
                if (enclosingFunctionLike(path) != null) keywords.add("return")
                val jump = enclosingJump(path)
                if (jump != Jump.NONE) keywords.add("break")
                if (jump == Jump.LOOP) keywords.add("continue")
                keywords.distinct().sorted()
            }
        }

    private fun expressionKeywords(path: List<Node>): List<String> {
        val keywords = ArrayList(EXPRESSION_KEYWORDS)
        when (val fn = enclosingFunctionLike(path)) {
            null -> Unit
            else -> {
                if (isAsyncFunctionLike(fn)) keywords.add("await")
                if (isGeneratorFunctionLike(fn)) keywords.add("yield")
            }
        }
        if (enclosingClass(path) != null) keywords.add("super")
        return keywords.sorted()
    }

    /** Always legal where an expression may appear. */
    private val EXPRESSION_KEYWORDS = listOf(
        "async", "class", "delete", "false", "function", "new", "null", "this", "true",
        "typeof", "void",
    )

    /** Always legal where a statement may begin, in any statement list. */
    private val STATEMENT_KEYWORDS = listOf(
        "abstract", "class", "const", "do", "for", "function", "if", "let", "switch",
        "throw", "try", "var", "while",
    )

    /** Legal where a statement may begin AND the list is a file or a namespace body. */
    private val MODULE_LEVEL_KEYWORDS = listOf(
        "declare", "enum", "export", "import", "interface", "namespace", "type",
    )

    /** Always legal where a type may appear — nothing here needs a following token. */
    private val TYPE_KEYWORDS = listOf(
        "any", "bigint", "boolean", "keyof", "never", "null", "number", "object",
        "string", "symbol", "typeof", "undefined", "unknown", "void",
    )

    /** What a bare `break` / `continue` would bind to, if anything. */
    private enum class Jump { NONE, SWITCH, LOOP }

    private fun enclosingFunctionLike(path: List<Node>): Node? {
        for (index in path.indices.reversed()) {
            val node = path[index]
            if (isFunctionLike(node)) return node
        }
        return null
    }

    private fun isFunctionLike(node: Node): Boolean =
        node is FunctionDeclaration || node is FunctionExpression || node is ArrowFunction ||
            node is MethodDeclaration || node is GetAccessor || node is SetAccessor ||
            node is Constructor

    private fun isAsyncFunctionLike(node: Node): Boolean = when (node) {
        is FunctionDeclaration -> ModifierFlag.Async in node.modifiers
        is FunctionExpression -> ModifierFlag.Async in node.modifiers
        is ArrowFunction -> ModifierFlag.Async in node.modifiers
        is MethodDeclaration -> ModifierFlag.Async in node.modifiers
        else -> false
    }

    private fun isGeneratorFunctionLike(node: Node): Boolean = when (node) {
        is FunctionDeclaration -> node.asteriskToken
        is FunctionExpression -> node.asteriskToken
        is ArrowFunction -> node.asteriskToken
        is MethodDeclaration -> node.asteriskToken
        else -> false
    }

    private fun enclosingClass(path: List<Node>): Node? {
        for (index in path.indices.reversed()) {
            val node = path[index]
            if (node is ClassDeclaration || node is ClassExpression) return node
        }
        return null
    }

    /**
     * The innermost construct a bare `break` / `continue` would bind to, stopping at
     * the first function-like — a loop does not reach into a nested function.
     */
    private fun enclosingJump(path: List<Node>): Jump {
        for (index in path.indices.reversed()) {
            val node = path[index]
            if (isFunctionLike(node)) return Jump.NONE
            if (node is ForStatement || node is ForOfStatement || node is ForInStatement ||
                node is WhileStatement || node is DoStatement
            ) {
                return Jump.LOOP
            }
            if (node is SwitchStatement) return Jump.SWITCH
        }
        return Jump.NONE
    }

    /**
     * True when the statement list the caret sits in is a source file's or a namespace
     * body's — the only places a declaration starter such as `import` may appear.
     */
    private fun atModuleLevel(path: List<Node>): Boolean {
        for (index in path.indices.reversed()) {
            val node = path[index]
            if (node is SourceFile || node is ModuleBlock) return true
            if (node is Block || node is CaseClause || node is DefaultClause) return false
        }
        return true
    }
}

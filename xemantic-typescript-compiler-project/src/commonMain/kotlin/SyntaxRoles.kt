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
import com.xemantic.typescript.compiler.ComputedPropertyName
import com.xemantic.typescript.compiler.Constructor
import com.xemantic.typescript.compiler.ContinueStatement
import com.xemantic.typescript.compiler.DefaultClause
import com.xemantic.typescript.compiler.DoStatement
import com.xemantic.typescript.compiler.ElementAccessExpression
import com.xemantic.typescript.compiler.EnumDeclaration
import com.xemantic.typescript.compiler.EnumMember
import com.xemantic.typescript.compiler.ExportAssignment
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
import com.xemantic.typescript.compiler.NoSubstitutionTemplateLiteralNode
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.NodeBase
import com.xemantic.typescript.compiler.NonNullExpression
import com.xemantic.typescript.compiler.ObjectLiteralExpression
import com.xemantic.typescript.compiler.Parameter
import com.xemantic.typescript.compiler.ParenthesizedExpression
import com.xemantic.typescript.compiler.ParserFlags
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
import com.xemantic.typescript.compiler.StringLiteralNode
import com.xemantic.typescript.compiler.Statement
import com.xemantic.typescript.compiler.SwitchStatement
import com.xemantic.typescript.compiler.SyntaxKind
import com.xemantic.typescript.compiler.TypeAliasDeclaration
import com.xemantic.typescript.compiler.TypeNode
import com.xemantic.typescript.compiler.TypeParameter
import com.xemantic.typescript.compiler.TypeQuery
import com.xemantic.typescript.compiler.VariableDeclaration
import com.xemantic.typescript.compiler.WhileStatement
import com.xemantic.typescript.compiler.forEachChild
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
                // (API.9) …and `o["p"] = 1` writes `p` by the same rule. Only the
                // ARGUMENT edge ascends, for the same reason the receiver edge does not.
                parent is ElementAccessExpression && parent.argumentExpression === current ->
                    current = parent
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


    // --- (API.8) the rename questions ---------------------------------------

    /**
     * (API.8) THE REPLACEMENT one occurrence needs — the heart of the edit plan, and
     * the reason rename is not "find references and swap the text".
     *
     * Three constructs spell a binding AND a property with ONE identifier, so a plain
     * replacement compiles and silently changes what the program means:
     *
     * - an object literal's SHORTHAND, `{ p }` — replacing it with `{ newName }`
     *   renames the object's KEY as well as the value it reads. The expansion
     *   `{ p: newName }` keeps the key and moves only the reference.
     * - a binding pattern's SHORTHAND, `const { p } = o` — same identifier, same
     *   trap, other direction: `{ newName }` would destructure a property that does
     *   not exist. The expansion is `{ p: newName }`.
     * A bare `export { p }` and a bare `import { p }` are deliberately NOT in that
     * list, though tsc expands both. Our symbol identity crosses the alias hop, so the
     * local and the export it names are ONE thing here and the whole group is being
     * renamed together — which makes the plain replacement the CONSISTENT one. Writing
     * `export { newName as p }` to preserve the module's public name would also make
     * `export { p }` behave differently from `export const p`, whose public name this
     * rename does change; and `import { p as newName }` would name an export that no
     * longer exists.
     *
     * (API.10) A SHORTHAND EXPANDS IN WHICHEVER DIRECTION IT WAS REACHED FROM, which is
     * what [asMember] decides and what makes it the whole feature's discriminator. The
     * one token names two things, so a rename of the LOCAL must keep the key
     * (`{ p: newName }`) and a rename of the MEMBER must keep the value
     * (`{ newName: p }`). Both were read out of tsc 7.0.2 rather than reasoned about,
     * and both compile — which is why writing the wrong one is silent, and why no
     * assertion about the EDIT COUNT can see it.
     *
     * [nameOffset] is where the NEW NAME begins inside [text]. The verification pass
     * needs it: it re-asks the compiler what the renamed occurrence resolves to, and
     * for a shorthand expansion that question is about a span three characters in — or,
     * when the member is what moved, about the span at offset zero.
     */
    fun renameRewrite(
        node: Node,
        oldName: String,
        newName: String,
        asMember: Boolean = false,
    ): Rewrite {
        val parent = parentOf(node)
        val shorthand = parent is ShorthandPropertyAssignment && parent.name === node ||
            parent is BindingElement && parent.propertyName == null && parent.name === node
        return when {
            shorthand && asMember -> Rewrite("$newName: $oldName", 0)
            shorthand -> Rewrite("$oldName: $newName", oldName.length + 2)
            else -> Rewrite(newName, 0)
        }
    }

    /** What [renameRewrite] answers: the replacement text and where the new name is in it. */
    data class Rewrite(val text: String, val nameOffset: Int)

    /**
     * (API.9) The NAME [node] spells — its own text for an identifier, and the text
     * between the quotes (or, since (API.16), the BACKTICKS) for the literal of an
     * `o["p"]`.
     *
     * The two occurrence populations meet here, so a caller comparing an occurrence
     * against the old name reads one function rather than casting to [Identifier] and
     * crashing on the literal. Anything else answers `""`, which matches no name.
     */
    fun occurrenceText(node: Node): String = when (node) {
        is Identifier -> node.text
        is StringLiteralNode -> node.text
        is NoSubstitutionTemplateLiteralNode -> node.text
        else -> ""
    }

    /**
     * (API.16) True when [node] is a LITERAL that can spell a member name — a string
     * or a no-substitution template.
     *
     * The exclusion is what this is for: a template WITH substitutions
     * (``o[`p${x}`]``) is a `TemplateExpression`, a different node class with no fixed
     * text, so it cannot be admitted by accident. tsc refuses that position outright —
     * zero references, and `prepareRename` answers "You cannot rename this element" —
     * so refusing it is parity rather than a limitation.
     */
    fun isMemberNameLiteral(node: Node): Boolean =
        node is StringLiteralNode || node is NoSubstitutionTemplateLiteralNode

    /**
     * True when [node] names a MEMBER OF A TYPE rather than something a scope binds.
     *
     * This is the axis the completeness net is split on, and the split matters in both
     * directions: an unresolved `p` in `interface I { p: string }` can never be an
     * occurrence of a local `p`, and an unresolved `p` in `o.p` can never be an
     * occurrence of a parameter. Treating either as a risk would refuse almost every
     * ordinary rename; treating neither as one is how a member rename misses an
     * implementor.
     */
    fun isMemberPosition(node: Node): Boolean {
        val parent = parentOf(node) ?: return false
        return when (parent) {
            is PropertyAccessExpression -> parent.name === node
            is QualifiedName -> parent.right === node
            is PropertyDeclaration -> parent.name === node
            is MethodDeclaration -> parent.name === node
            is GetAccessor -> parent.name === node
            is SetAccessor -> parent.name === node
            is EnumMember -> parent.name === node
            is PropertyAssignment -> parent.name === node
            is BindingElement -> parent.propertyName === node
            is NamedTupleMember -> parent.name === node
            // (API.9) `o["p"]` — a member position whose name is a string literal, and
            // since this round an occurrence rather than only an obstacle. (API.16) A
            // ``o[`p`]`` is the same position; a COMPUTED `o[i]` reaches here too and is
            // filtered where the population is built, never here, because "is this the
            // argument of an element access" is all this predicate claims.
            is ElementAccessExpression -> parent.argumentExpression === node
            // (API.17) A COMPUTED NAME, `{ ["p"]: v }` / `class C { ["p"] = 1 }`. Only
            // the LITERAL forms, and the asymmetry with the element-access arm above is
            // deliberate rather than an oversight: `{ [K]: v }` is a reference to the
            // binding `K` and to nothing else — tsc answers the const's own group there,
            // two spans, measured — so calling it a member position would flip the
            // completeness net's polarity for every ordinary `const K` rename.
            // ROUND 935 RE-MEASURED THIS AGAINST THE CHECKER AND CONFIRMED IT RATHER
            // THAN SUPERSEDING IT. That round made the CHECKER late-bind `{ [K]: v }` to
            // the member `p` (tsc's own `isTypeUsableAsPropertyName`), so the natural
            // reading is that this arm must widen to match — and tsc says no: asked for
            // the references of `Shape.p` on a file whose literal carries `[K]`, its LSP
            // answers TWO spans, the declaration and a plain `{ p: 2 }`, and not the key.
            // The checker and the language service DELIBERATELY disagree about what a
            // member name is, in tsc as here: the key SUPPLIES the member and SPELLS the
            // binding, and only the second is what a rename may edit.
            is ComputedPropertyName ->
                parent.expression === node && isMemberNameLiteral(node)
            else -> false
        }
    }

    /**
     * True when [node] is a SHORTHAND that hides a property name — an object literal's
     * `{ p }` or a binding pattern's `const { p } = o`.
     *
     * Both are occurrences of a PROPERTY that no identifier of that property's spelling
     * marks: the object literal's key comes from its CONTEXTUAL type and the binding
     * pattern's source property is named by the same token as the local it binds.
     *
     * (API.10) Since the capture resolves both, this is no longer the shape's whole
     * story — a shorthand whose member the search PLACED is an ordinary member of the
     * group and gets expanded. What is left is the residue: a shorthand whose source
     * type could not be decided at all (a literal with no contextual type, an
     * un-annotated destructured parameter). While renaming a MEMBER, such a token is a
     * place the plan would have to edit and cannot prove it should — a refusal, not an
     * omission.
     */
    fun isPropertyHidingShorthand(node: Node): Boolean {
        val parent = parentOf(node) ?: return false
        return when (parent) {
            is ShorthandPropertyAssignment -> parent.name === node
            is BindingElement -> parent.propertyName == null && parent.name === node
            else -> false
        }
    }

    /**
     * Every `o["…"]` in [root] whose member is named by a LITERAL, as
     * `(the literal node, its text)`.
     *
     * (API.9) A string literal is not an identifier, so such an access used to be
     * outside the population `Project.referencesAt` sweeps — it could be neither found
     * nor renamed, only DETECTED, and detecting one was what refused a member rename.
     * It is now the second half of `SourceIndex.occurrenceNodes`, i.e. part of the
     * population, and this enumeration is what puts it there. The other positions a
     * string literal can occupy are deliberately not enumerated: a literal that names
     * no member is not a candidate for anything.
     *
     * (API.16) A NO-SUBSTITUTION TEMPLATE names a member the same way, and until round
     * 931 it was the one occurrence kind this API missed SILENTLY: the rename applied,
     * the template kept spelling the old name, and the resulting program compiled
     * clean, so no gate here could see it. It is now the same population.
     *
     * ITERATIVE, as every full-tree walk in this module is: the corpus carries
     * expression chains deep enough to crash a recursive one.
     */
    fun stringElementAccesses(root: Node): List<Pair<Node, String>> {
        val accesses = stringMemberNameAccesses(root)
        val found = ArrayList<Pair<Node, String>>(accesses.size)
        for ((literal, _) in accesses) found.add(literal to occurrenceText(literal))
        return found
    }

    /**
     * (API.17) Every LITERAL in [root] that spells the name of a member — the `"p"` of
     * `o["p"]`, of ``o[`p`]``, of `{ "p": v }`, of `{ ["p"]: v }`, of
     * ``{ [`p`]: v }``, of `class C { ["p"] = 1 }` and of every other position
     * [isMemberPosition] accepts.
     *
     * ## One predicate, which is the whole point
     *
     * (API.9) enumerated element accesses, (API.16) widened that to templates, and each
     * time the population was the feature. This is the same question asked once: "is
     * this literal in a MEMBER NAME position", which is [isMemberPosition] — already
     * the predicate `Project.occurrenceCaret` uses to decide whether a caret ON such a
     * literal names anything, and already the predicate the completeness net splits its
     * obstacles on. So the set a caret can be placed in, the set a sweep reports and the
     * set a rename must edit are now ONE set by construction rather than three
     * definitions kept in step.
     *
     * ## Why a literal this API cannot RESOLVE still belongs here
     *
     * A class's or an interface's `["p"]` is a member declaration with no resolution
     * leg, so it enters the sweep and stays unplaced — and that is the useful outcome:
     * an occurrence the sweep can SEE and cannot place is a stated
     * `OCCURRENCES_INCOMPLETE` conflict, while one it cannot see at all is a silent
     * miss. § 14's gap 2 was exactly the second kind, and it was the last one: with the
     * contextual member OPTIONAL, stranding a computed key costs no diagnostic, so the
     * applied rename compiled clean with the old name still spelled in the literal and
     * no gate in this repository could see it.
     *
     * A computed name that is NOT a literal (`{ [K]: v }`) is deliberately absent: it
     * spells no fixed member name, and tsc 7.0.2 reads that position as a reference to
     * the binding `K` and to nothing else (measured — two spans, the `const` and this
     * use, and renaming it writes `[renamed]`). The ordinary free-name path already
     * answers it that way, so admitting it here would put one token in two populations.
     *
     * ITERATIVE, as every full-tree walk in this module is: the corpus carries
     * expression chains deep enough to crash a recursive one.
     */
    fun memberNameLiterals(root: Node): List<Node> {
        val found = ArrayList<Node>()
        val stack = ArrayList<Node>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (isMemberNameLiteral(node) && isMemberPosition(node)) found.add(node)
            forEachChild(node) { child -> stack.add(child) }
        }
        return found
    }

    /**
     * (API.12) The `o["…"]` whose member-name literal begins at [literalStart], or
     * null when the literal there names no member.
     *
     * The completion anchor's half of [stringElementAccesses], and deliberately the
     * SAME walk: "a string literal is a member name only in an element-access
     * position" is one predicate, and a completion that offered members at a position
     * the occurrence sweep does not recognise would invite the user to write text a
     * later rename could not find. Sharing the enumeration makes that agreement
     * structural rather than a matter of two definitions being kept in step.
     *
     * [literalStart] is the literal's own `pos`, which for a literal is the token's
     * first character — the parser records a token's start after skipping leading
     * trivia, so a block comment between the `[` and the quote does not shift it.
     *
     * The whole ACCESS is returned rather than its receiver, because the caller needs
     * both halves: the receiver is what a type is enumerated from and the literal is
     * what says whether the closing quote is there.
     */
    fun stringElementAccessAt(root: Node, literalStart: Int): ElementAccessExpression? {
        for ((literal, access) in stringMemberNameAccesses(root)) {
            if (literal.pos == literalStart) return access
        }
        return null
    }

    /**
     * Every `o["…"]` in [root], as `(the member-name literal, the access)`.
     *
     * ITERATIVE, as every full-tree walk in this module is: the corpus carries
     * expression chains deep enough to crash a recursive one.
     */
    private fun stringMemberNameAccesses(
        root: Node,
    ): List<Pair<Node, ElementAccessExpression>> {
        val found = ArrayList<Pair<Node, ElementAccessExpression>>()
        val stack = ArrayList<Node>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            if (node is ElementAccessExpression) {
                val argument = node.argumentExpression
                if (isMemberNameLiteral(argument)) found.add(argument to node)
            }
            forEachChild(node) { child -> stack.add(child) }
        }
        return found
    }

    /**
     * True when [name] is a single IDENTIFIER token, decided by the compiler's own
     * lexer rather than by a character predicate.
     *
     * Round 920's parse-as-lexer-oracle, in its smallest form: a hand-written
     * `isLetterOrDigit` test would disagree with the scanner about `$`, about `_`,
     * about a Unicode letter and about an escape, and the disagreement would be
     * invisible — the rename would simply produce a file that does not parse.
     */
    fun isIdentifierName(name: String): Boolean {
        if (name.isEmpty()) return false
        val index = SourceIndex.of(
            name,
            "rename-candidate.ts",
            ParserFlags(
                forceJsx = false,
                topLevelAwait = false,
                needsJsxFlag = false,
                noImplicitAny = false,
            ),
        )
        // Two tokens: the candidate and the zero-width end of file. Anything else
        // is whitespace, a second word, or a token that split.
        if (index.tokenKinds.size != 2 || index.tokenKinds[1] != SyntaxKind.EndOfFile) {
            return false
        }
        return index.tokenStarts[0] == 0 && index.tokenEnds[0] == name.length &&
            index.tokenKinds[0] == SyntaxKind.Identifier
    }

    /**
     * The words that may not become a binding's name.
     *
     * The ECMAScript reserved words plus the strict-mode reserved ones — every file
     * this compiler checks is a module or is checked under `strict`, and a rename must
     * not depend on which. `type`, `any`, `as`, `of` and the rest of TypeScript's
     * CONTEXTUAL keywords are deliberately absent: `const type = 1` is legal, and
     * refusing it would be inventing a rule the language does not have.
     *
     * [isIdentifierName] alone does not answer this — our scanner reports `class` as a
     * keyword token and `type` as one too, so the token kind cannot separate the
     * reserved from the contextual.
     */
    val RESERVED_WORDS: Set<String> = setOf(
        "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "enum", "export", "extends", "false",
        "finally", "for", "function", "if", "implements", "import", "in", "instanceof",
        "interface", "let", "new", "null", "package", "private", "protected", "public",
        "return", "static", "super", "switch", "this", "throw", "true", "try", "typeof",
        "var", "void", "while", "with", "yield",
    )

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

    // --- (INC.44) the SPELLINGS one symbol can be reached by ------------------

    /**
     * (INC.44) The OTHER spelling this occurrence links its symbol to, or null.
     *
     * `import { p as q }` and `export { p as q }` are the only two forms in which one
     * symbol carries two spellings that are BOTH written down — [Project.renameAt]
     * has always known this (its `ALIASED_SYMBOL` refusal exists because the
     * occurrence group can carry both), and a search narrowed by spelling has to know
     * it too or it drops the far half of the alias silently.
     *
     * Both directions, because the caret may be on either end: given the `p` of
     * `p as q` this answers `q`, and given the `q` it answers `p`.
     *
     * ## Why this is enough to CLOSE over, and where the closure is anchored
     *
     * A specifier's two names are both TOKENS OF THE FILE THAT WRITES IT, so a file
     * introducing an alias of `p` necessarily contains the text `p` — which is what
     * lets [Project] find every link by scanning only files it has already selected
     * for containing a name it is looking for, and iterate to a fixed point without
     * ever scanning the program. The forms where that is NOT true — a default import,
     * an `export =`, an `import x = require(...)` — bind a symbol to a spelling that
     * appears nowhere near the other one, and they are refused outright by
     * [isAliasEscape] rather than approximated here.
     */
    fun aliasLink(node: Node): String? {
        val parent = parentOf(node) ?: return null
        return when (parent) {
            is ImportSpecifier ->
                if (parent.propertyName === node) parent.name.text
                else if (parent.name === node) parent.propertyName?.text
                else null
            is ExportSpecifier ->
                if (parent.propertyName === node) parent.name.text
                else if (parent.name === node) parent.propertyName?.text
                else null
            else -> null
        }
    }

    /**
     * (INC.44) True when this occurrence's symbol can be spelled by a name NO
     * syntactic scan starting from this one can name — so a spelling-narrowed search
     * must give up and sweep the whole program.
     *
     * The escape hatch, and the reason a narrowed reference search is sound rather
     * than nearly sound. Four forms bind a symbol across a module boundary WITHOUT
     * writing both spellings anywhere:
     *
     * - `import d from "./m"` — `d` names `m`'s default export, whose own declaration
     *   may be called anything at all;
     * - `export default foo` / `export = foo` and an `export default` DECLARATION —
     *   the other end of the same edge, seen from the exporting file;
     * - `import x = require("./m")` and `import x = A.B` — an entity alias;
     * - `import * as ns` / `export * as ns` — a namespace binding, admitted here for
     *   conservatism rather than because a divergent spelling has been shown.
     *
     * Answering true costs the caller today's whole-program sweep, i.e. exactly the
     * behaviour that shipped before this narrowing existed. Answering false wrongly
     * would cost a MISSING reference, which is silent — so every form that cannot be
     * closed over by [aliasLink] belongs here, and the bias is stated rather than
     * assumed.
     */
    fun isAliasEscape(node: Node): Boolean {
        val parent = parentOf(node) ?: return false
        return when (parent) {
            // `import d from "./m"` — the clause's own name is the default binding.
            is ImportClause -> parent.name === node
            is NamespaceImport -> parent.name === node
            is NamespaceExport -> parent.name === node
            is ImportEqualsDeclaration -> parent.name === node
            // `export default foo` / `export = foo`: the expression names the symbol
            // leaving the module under a spelling the importer never has to repeat.
            is ExportAssignment -> parent.expression === node
            // `export default function foo() {}` / `export default class C {}`.
            else -> isDefaultExportedDeclarationName(node, parent)
        }
    }

    /**
     * The `foo` of `export default function foo() {}` — a declaration NAME whose
     * modifiers carry `export` and `default`, which is the second half of
     * [isAliasEscape]'s default-export edge.
     */
    private fun isDefaultExportedDeclarationName(node: Node, parent: Node): Boolean {
        val modifiers = when (parent) {
            is FunctionDeclaration -> if (parent.name === node) parent.modifiers else null
            is ClassDeclaration -> if (parent.name === node) parent.modifiers else null
            is InterfaceDeclaration -> if (parent.name === node) parent.modifiers else null
            is EnumDeclaration -> if (parent.name === node) parent.modifiers else null
            else -> null
        } ?: return false
        return ModifierFlag.Default in modifiers
    }
}

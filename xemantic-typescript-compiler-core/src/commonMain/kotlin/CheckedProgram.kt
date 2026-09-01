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
 * A consumer the check spine hands every checked node to, AS IT WALKS PAST IT.
 *
 * The direction is inwards, and for the same reason [TypeCaptureRequest]'s is:
 * the checker's answers are functions of walk-scoped state — [Checker]'s
 * `currentLocalTypes`, the cta frame stack, `currentFlowGraph` — which is empty
 * once the check is over. A backend that walks the tree AFTERWARDS and asks for
 * types does not get degraded answers, it gets confidently wrong ones: a
 * function-body local resolves to a same-named global, and a parameter and a
 * narrowed reference both read `any`. `TypeCaptureMeasurementTest` measures
 * exactly that, which is why this interface exists rather than a set of public
 * query methods.
 *
 * Unlike a capture, a sink is keyed by NODE IDENTITY rather than by a
 * `(pos, end)` span: a raw span does not always identify one node (a dangling
 * `.` at end of buffer gives the receiver and the property access the same
 * pair), and the capture facility resolves that by arbitration — first-wins for
 * types, deepest-wins for members. Arbitration DISCARDS a node, which a backend
 * that must lower every one of them cannot tolerate.
 *
 * Threaded as a `Checker` constructor parameter for [TypeCaptureRequest]'s
 * reason: it is DATA, not an option and not a process-global mode.
 */
interface CheckedNodeSink {

    /**
     * Called once per program file, immediately before its nodes are walked.
     *
     * The only route from a whole-PROGRAM check to the trees it checked: a
     * `ProjectCompiler.Result` retains no AST, and a consumer that re-parsed
     * the files would key its facts by nodes the checker never saw — silently,
     * since two parses of one file are equal and not identical, and every fact
     * here is keyed by node IDENTITY.
     */
    fun file(node: SourceFile) {}

    /** Called for every [Expression] the spine visits, in walk order. */
    fun expression(node: Expression, lens: CheckedLens)

    /**
     * Called for declaration-shaped nodes — declarations, class elements and
     * parameters. Defaulted away because the common consumer wants expressions
     * only, and answering this one costs the ambient reconstruction per node.
     */
    fun declaration(node: Node, lens: CheckedLens) {}

}

/**
 * The checker's own queries, forwarded, under the ambient in force at the node.
 *
 * **Valid only for the duration of the [CheckedNodeSink] call that received it.**
 * Retaining a lens and asking it later is undefined: the ambient it reads is
 * installed around the callback and restored afterwards. Retaining the [Type]
 * and [Symbol] objects it RETURNS is fine — those outlive the walk.
 *
 * This is deliberately not a public widening of [Checker]. Nothing private
 * becomes public; only the forwarding does, and only for as long as the answers
 * mean anything.
 */
interface CheckedLens {

    /** The type of an expression AT THIS POSITION — narrowed, body-local-correct. */
    fun typeOf(node: Expression): Type

    /** The checker's own rendering, so a backend diagnostic names a type as tsc does. */
    fun render(type: Type): String

    /**
     * Every member named [name] on [type], distributed over unions and
     * intersections and falling back to the apparent type for primitives.
     *
     * Deliberately not `getPropertyOfType`: that one answers an ASSIGNABILITY
     * question — a member present on only one union constituent answers null,
     * and one present on all of them answers the FIRST — so it is the wrong
     * helper for "where is this member", in both directions and silently.
     */
    fun membersOf(type: Type, name: String): List<Symbol>

    /** The type of a symbol, resolving it if this is the first ask. */
    fun typeOfSymbol(symbol: Symbol): Type

    /** The declared type of a symbol: what an annotation on it denotes. */
    fun declaredTypeOfSymbol(symbol: Symbol): Type

    /**
     * What a written TYPE ANNOTATION denotes, resolved here and now.
     *
     * The route to a type that belongs to no symbol: a DESTRUCTURING parameter
     * has no name to resolve and is deliberately absent from a `Signature`'s
     * parameter list, so its annotation is the only statement of its type — and
     * a `TypeNode` is syntax until something resolves it.
     */
    fun typeOfTypeNode(node: TypeNode): Type

    /** What a free name refers to at this position, through the lexical chain. */
    fun resolveName(name: String): Symbol?

    /**
     * What an IMPORT alias actually names, or null when [symbol] is not one.
     *
     * The one question a consumer of imported code cannot answer for itself: an
     * `import { X } from './m'` binds an alias whose own declaration is the
     * import specifier, and a backend needs the DECLARATION the name refers to
     * — the class, the function, the variable — to reach what it generated for
     * it. Re-deriving that would mean re-implementing module resolution.
     */
    fun aliasTarget(symbol: Symbol): Symbol?

    /**
     * (EXT.8) What a heritage-clause base expression names — the `X` of
     * `extends X` / `implements X<T>`, an [Identifier] or a qualified
     * `A.B.C` — resolved exactly as the checker resolves it for the clause
     * itself, imports included. [resolveName] cannot answer this: it reads
     * the walk-scoped lexical chain, which offers no import (the INV.2(c)
     * `symbols`-only rule), so an imported base would read as unresolved.
     * The answer may still be an import ALIAS; ask [aliasTarget] for what it
     * names. Null when the base does not resolve or is not exported from the
     * namespace it is qualified with.
     */
    fun heritageBaseSymbol(base: Expression): Symbol?

    /**
     * Is [source] assignable to [target]?
     *
     * The backend needs this for a reason unrelated to diagnostics: TypeScript
     * types are STRUCTURAL and JVM types are NOMINAL, so a generated class must
     * declare `implements` for every generated interface it structurally
     * satisfies. That closure is computable only because this compiler sees the
     * whole program at once, and only by asking the relation engine — a backend
     * re-deriving structural assignability would be a second, divergent checker.
     */
    fun isAssignableTo(source: Type, target: Type): Boolean

    /**
     * The call signatures of a callee expression, including the namespace,
     * module and enum EXPORT-table leg that a plain callee-type query cannot
     * answer — `ns.fn(...)` has zero signatures without it.
     */
    fun callSignatures(callee: Expression): List<Signature>

    /** The construct signatures reachable from a `new` expression's callee. */
    fun constructSignatures(node: NewExpression): List<Signature>

    /**
     * The overload the checker would pick for these arguments.
     *
     * Must be asked HERE. Overload selection is not memoised anywhere, and it is
     * not pure: it derives argument types through `currentLocalTypes` and takes a
     * second chance against the narrowed type, so the same signatures and the
     * same argument nodes can select differently at a different point in the
     * walk. Re-implementing it in a backend is the one alternative that is worse.
     */
    fun selectOverload(signatures: List<Signature>, arguments: List<Expression>): Signature?

    /**
     * An enum member's constant.
     *
     * Note this answers for an AMBIENT non-const enum member with no
     * initializer, where tsc has no value at all — we auto-number those because
     * the JavaScript emitter needs a value. A backend consuming this adopts that
     * invented number as its own ABI, which is a decision worth making
     * deliberately rather than discovering later.
     */
    fun enumMemberValue(memberNode: Node): ConstantValue?

}

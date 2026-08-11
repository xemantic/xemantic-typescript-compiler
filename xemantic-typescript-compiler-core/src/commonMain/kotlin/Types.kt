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

import kotlin.jvm.JvmInline
import kotlin.math.pow

// ---------------------------------------------------------------------------
// Symbol flags — bit field indicating what kind of entity a symbol represents
// ---------------------------------------------------------------------------

/**
 * Bit flags for [Symbol] classification, matching TypeScript's `SymbolFlags`.
 *
 * Uses plain [Int] bit operations for multiplatform compatibility
 * (no JVM-only `EnumSet` or `java.util.*`).
 */
@JvmInline
value class SymbolFlags(val value: Int) {
    operator fun contains(flag: SymbolFlags): Boolean = (value and flag.value) != 0
    infix fun or(other: SymbolFlags): SymbolFlags = SymbolFlags(value or other.value)
    infix fun and(other: SymbolFlags): SymbolFlags = SymbolFlags(value and other.value)
    fun hasAny(flags: SymbolFlags): Boolean = (value and flags.value) != 0
    fun hasNone(flags: SymbolFlags): Boolean = (value and flags.value) == 0

    companion object {
        val None = SymbolFlags(0)
        val FunctionScopedVariable = SymbolFlags(1 shl 0)
        val BlockScopedVariable = SymbolFlags(1 shl 1)
        val Property = SymbolFlags(1 shl 2)
        val EnumMember = SymbolFlags(1 shl 3)
        val Function = SymbolFlags(1 shl 4)
        val Class = SymbolFlags(1 shl 5)
        val Interface = SymbolFlags(1 shl 6)
        val ConstEnum = SymbolFlags(1 shl 7)
        val RegularEnum = SymbolFlags(1 shl 8)
        val ValueModule = SymbolFlags(1 shl 9)
        val NamespaceModule = SymbolFlags(1 shl 10)
        val TypeAlias = SymbolFlags(1 shl 11)
        val Alias = SymbolFlags(1 shl 12)
        val ExportValue = SymbolFlags(1 shl 13)
        val Method = SymbolFlags(1 shl 14)
        val GetAccessor = SymbolFlags(1 shl 15)
        val SetAccessor = SymbolFlags(1 shl 16)
        val TypeParameter = SymbolFlags(1 shl 17)

        /**
         * Round 728: this member was made OPTIONAL by a `?` mapped-type modifier
         * (`Partial<T>` = `{ [P in keyof T]?: T[P] }`). It is not a binder flag —
         * only [Checker]'s mapped-type materializer sets it, and only
         * `isOptionalProperty` reads it. A flag bit rather than an id-keyed
         * side-channel (the `-?` analogue [Checker.mappedRequiredMemberIds] is one)
         * because the arm that must consult it is the HOT one: every
         * declared-REQUIRED property reaches it, so a boxed-Int set lookup there
         * would be paid on the whole program.
         */
        val MappedOptional = SymbolFlags(1 shl 18)

        // Composite flags
        val Variable = FunctionScopedVariable or BlockScopedVariable
        val Enum = RegularEnum or ConstEnum
        val Value = SymbolFlags(
            Variable.value or Property.value or EnumMember.value or Function.value or
            Class.value or Enum.value or ValueModule.value or Method.value or
            GetAccessor.value or SetAccessor.value
        )
        val Type = SymbolFlags(
            Class.value or Interface.value or Enum.value or TypeAlias.value or TypeParameter.value
        )
        val Module = ValueModule or NamespaceModule
    }
}

// ---------------------------------------------------------------------------
// Symbol — a named entity in the program
// ---------------------------------------------------------------------------

/**
 * A symbol represents a named entity: variable, function, class, interface,
 * namespace, enum, type alias, parameter, property, or import alias.
 *
 * Created by the [Binder] during AST traversal. Multiple AST nodes can
 * contribute to the same symbol via declaration merging (e.g., two
 * `interface Foo` declarations merge into one symbol).
 */
class Symbol(
    var flags: SymbolFlags,
    val name: String,
    /**
     * Unique identifier for use as map keys. Defaults to the global sequence;
     * INV.2(c) lexical-scope symbols pass an id from the SEPARATE negative
     * space via [scopeSymbol] so their creation never shifts the global
     * sequence (symbol-id allocation order is load-bearing — the documented
     * ~350-test boundary reshuffle on id drift).
     */
    val id: Int = allocId(),
) {
    /** All declaration AST nodes that contribute to this symbol. */
    val declarations: MutableList<Node> = mutableListOf()

    /** The primary value-bearing declaration (e.g., for merged interface+class, the class). */
    var valueDeclaration: Node? = null

    /** Member symbols for classes and interfaces. */
    var members: SymbolTable? = null

    /** Exported symbols for modules and namespaces. */
    var exports: SymbolTable? = null

    /** Parent symbol (container scope). */
    var parent: Symbol? = null

    /** For import aliases: the resolved target symbol. Set by the checker. */
    var target: Symbol? = null

    /**
     * (PERF.HW.k) CHECKER-OWNED, i.e. tsc's `SymbolFlags.Transient` as a field.
     *
     * `false` on every `Symbol` the binder mints, `true` only on the copies
     * `Checker.cloneSymbolForMerge` makes. The merge reads it to decide whether it
     * may mutate in place: a transient symbol belongs to the checker that made it,
     * a non-transient one is BINDER-OWNED and must be copied first.
     *
     * That is what keeps binder output pristine, which is what lets N `--workers`
     * checkers share one bind (`docs/parallel-bind-sharing.md`). tsc has done this
     * since forever for a different reason — a bound `SourceFile` is reused across
     * `Program` instances in watch/incremental/the language service, so a checker
     * that mutated it would leak one program's merges into the next.
     */
    var transient: Boolean = false

    override fun toString(): String = "Symbol($name, flags=${flags.value})"

    companion object {
        /** INV.6(6c0): per-thread sequence — see [IntThreadLocal] for the rationale. */
        private val nextId = IntThreadLocal(1)

        /** INV.2(c): ids for lexical-scope symbols — negative, descending from −2 (−1 stays a sentinel). */
        private val nextScopeSymbolId = IntThreadLocal(-2)

        internal fun allocId(): Int = nextId.get().also { nextId.set(it + 1) }

        /**
         * Create a symbol in the lexical-scope id space (INV.2(c)). These ids are
         * always ≤ −2, disjoint from the global positive sequence, so id-keyed
         * checker maps can hold both without collision and creating scope symbols
         * never perturbs the ids of conventionally-bound symbols.
         */
        fun scopeSymbol(flags: SymbolFlags, name: String): Symbol =
            Symbol(flags, name, nextScopeSymbolId.get().also { nextScopeSymbolId.set(it - 1) })

        /**
         * INV.6(6c): re-base THIS thread's id sequences — called at parallel-worker
         * thread startup so worker-local ids sit far above the shared
         * singleton-intrinsic range (they never cross the worker boundary, but
         * they share id-keyed maps with the singletons inside the worker).
         */
        fun rebaseThreadIds(base: Int, scopeBase: Int) {
            nextId.set(base)
            nextScopeSymbolId.set(scopeBase)
        }

        /** INV.6(6c0): snapshot THIS thread's sequences (deep-stack handoff). */
        fun captureThreadIds(): Pair<Int, Int> = nextId.get() to nextScopeSymbolId.get()

        /** INV.6(6c0): restore THIS thread's sequences (deep-stack handoff). */
        fun restoreThreadIds(snapshot: Pair<Int, Int>) {
            nextId.set(snapshot.first)
            nextScopeSymbolId.set(snapshot.second)
        }

        /** Reset the ID counters (for testing). */
        fun resetIdCounter() { nextId.set(1); nextScopeSymbolId.set(-2) }
    }
}

/** A symbol table is a map from name to symbol. */
typealias SymbolTable = MutableMap<String, Symbol>

/** Create a new empty symbol table. */
fun symbolTable(): SymbolTable = mutableMapOf()

/**
 * INV.2(c): one lexical scope, produced by the [Binder]'s additive
 * lexical-binding pass and keyed in [BinderResult.lexicalScopes] by the owner
 * node's `nodeId`. UNCONSUMED until INV.4 — nothing in the checker reads these
 * tables yet; they exist so the single-pass spine can resolve names through a
 * real scope chain instead of the per-pass scope re-derivation.
 *
 * Two-table design: [symbols] holds ONLY the bindings the lexical pass itself
 * made (from the separate negative id space, [Symbol.scopeSymbol]); [existing]
 * ALIASES the main binder's pre-existing table for container scopes (file
 * locals for the [SourceFile] root, the merged `exports` for a namespace) and
 * is never mutated. Resolution order for a future consumer: `symbols` →
 * `existing` → [parent]. This keeps the main binder's output byte-unchanged —
 * the queue item's load-bearing constraint.
 */
class LexicalScope(
    /** The scope-owning node: SourceFile, ModuleDeclaration, or a function-like. */
    val owner: Node,
    /** The enclosing scope; null only for the SourceFile root. */
    val parent: LexicalScope?,
    /** Read-only alias of the main binder's table for this container, when one exists. */
    val existing: SymbolTable? = null,
) {
    /** Bindings made by the lexical pass (scope-id space). Never shared with [existing]. */
    val symbols: SymbolTable = symbolTable()
}

// ---------------------------------------------------------------------------
// Constant values — computed enum member values
// ---------------------------------------------------------------------------

/**
 * A constant value computed at compile time, used for enum member values
 * and const enum inlining.
 */
sealed interface ConstantValue {
    /** A numeric constant (TypeScript's `number` type uses IEEE 754 double). */
    data class NumberValue(val value: Double) : ConstantValue {
        override fun toString(): String {
            return if (value == value.toLong().toDouble() && !value.isInfinite())
                value.toLong().toString()
            else value.toString()
        }
    }

    /** A string constant. */
    data class StringValue(val value: String) : ConstantValue
}

// ---------------------------------------------------------------------------
// Module instance state
// ---------------------------------------------------------------------------

/**
 * Whether a module/namespace declaration produces runtime code.
 * Used for import elision: imports of non-instantiated modules can be removed.
 */
enum class ModuleInstanceState {
    /** Contains only type declarations (interfaces, type aliases, non-export imports). */
    NonInstantiated,
    /** Contains runtime code (variables, functions, classes, regular enums). */
    Instantiated,
    /** Contains only const enums (runtime code only if preserveConstEnums is set). */
    ConstEnumOnly,
}

// ---------------------------------------------------------------------------
// Node identity
// ---------------------------------------------------------------------------

/** Pack [pos]/[end] into a single Long for use as map keys. */
fun nodeKey(pos: Int, end: Int): Long =
    (pos.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)

/** Get the identity key for a [Node], based on its source position. */
fun nodeKey(node: Node): Long = nodeKey(node.pos, node.end)

/**
 * Parse a TypeScript NUMERIC LITERAL's source text to its numeric value,
 * honouring every base TypeScript allows plus `_` separators.
 *
 * EP.2b (round 675): the const-enum evaluators used `text.toDoubleOrNull()`,
 * which parses decimal only — Kotlin accepts a hex FLOAT (`0x1.8p3`) but not a
 * hex INTEGER (`0x7F`), so it returned null and the member silently became
 * un-inlinable. That is why tsc's `CharacterCodes` (almost entirely hex) kept
 * `ts_js_1.CharacterCodes.doubleQuote` in the emit while `SymbolFlags` and
 * `Extension` from the SAME file inlined: those are decimal and string valued.
 * 638 of the 675 residual un-inlined reads on the compiler profile were this
 * one enum.
 *
 * Returns null for genuinely non-constant text (BigInt `n` suffix included, as
 * a const enum member cannot be a BigInt).
 */
fun tsNumericLiteralToDouble(raw: String): Double? {
    val t = raw.replace("_", "")
    if (t.endsWith("n") || t.endsWith("N")) return null // BigInt literal
    return when {
        t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).toLongOrNull(16)?.toDouble()
        t.startsWith("0b") || t.startsWith("0B") -> t.substring(2).toLongOrNull(2)?.toDouble()
        t.startsWith("0o") || t.startsWith("0O") -> t.substring(2).toLongOrNull(8)?.toDouble()
        else -> t.toDoubleOrNull()
    }
}

/**
 * Constant-fold a numeric binary operator for const-enum evaluation, returning
 * null for any operator that cannot appear in a compile-time constant.
 *
 * EP.2f (round 677): shared so the Checker's cross-module evaluator and the
 * Transformer's same-file collector cannot drift. They had drifted: the Checker
 * folded shifts and bitwise ops while the collector accepted only literals, so a
 * same-file `const enum Connection { Up = 1 << 0, UpDown = Up | Down }` — tsc's
 * own `debug.ts` — silently stopped being inlinable at the first computed
 * member.
 *
 * Bitwise and shift results are taken through `Long`/`Int` exactly as JavaScript
 * specifies (`>>>` is an unsigned 32-bit shift, hence the `toInt()`).
 */
fun tsFoldNumericBinary(left: Double, operator: SyntaxKind, right: Double): Double? = when (operator) {
    SyntaxKind.Plus -> left + right
    SyntaxKind.Minus -> left - right
    SyntaxKind.Asterisk -> left * right
    SyntaxKind.Slash -> left / right
    SyntaxKind.Percent -> left % right
    SyntaxKind.AsteriskAsterisk -> left.pow(right)
    SyntaxKind.Bar -> (left.toLong() or right.toLong()).toDouble()
    SyntaxKind.Ampersand -> (left.toLong() and right.toLong()).toDouble()
    SyntaxKind.Caret -> (left.toLong() xor right.toLong()).toDouble()
    SyntaxKind.LessThanLessThan -> (left.toLong() shl right.toInt()).toDouble()
    SyntaxKind.GreaterThanGreaterThan -> (left.toLong() shr right.toInt()).toDouble()
    SyntaxKind.GreaterThanGreaterThanGreaterThan ->
        (left.toLong().toInt().ushr(right.toInt())).toDouble()
    else -> null
}

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
 * (INC.61) A read-only [SymbolTable] that OVERLAYS one table on a SHARED base
 * without copying the base.
 *
 * It exists because `Checker.buildPerFileScopes` built one flat table PER FILE by
 * copying every lib global into it, i.e. `files x libGlobals` insertions. That is
 * invisible on a project whose `lib` is small and dominant on one whose `lib` is
 * not: measured on the SAME 2,401-file program, changing `lib` from `["es2020"]`
 * to `["es2020", "dom"]` — which is what an ordinary project gets by DEFAULT —
 * took that pass from **13.5 ms to 175.6 ms**, 70% of the whole floor pass table
 * and ~46% of the per-keystroke floor.
 *
 * **The iteration ORDER is exactly what the copy produced**, which is what makes
 * this a drop-in: the copy inserted the base first and then the file's own locals,
 * and a `LinkedHashMap` keeps a key's ORIGINAL position when a later `put`
 * overwrites its value — so iterating the base (answering [own]'s value where it
 * shadows) and then [own]'s base-absent keys reproduces it entry for entry. That
 * matters because three consumers iterate this table, and round 776's law is that
 * a name order which stops being a function of the program is a cost counter that
 * becomes a property of the box.
 *
 * **Mutators THROW rather than being silently ignored.** Nothing mutates a
 * per-file scope after it is built, and a loud failure is the right way to find
 * out if that ever stops being true — a view that quietly accepted a write would
 * drop it, and the symptom would be a name resolving to a foreign module's local.
 */
internal class LayeredSymbolTable(
    private val base: Map<String, Symbol>,
    private val own: Map<String, Symbol>,
) : MutableMap<String, Symbol> {

    /** [own]'s keys that the base does not already carry, in [own]'s own order. */
    private val ownOnly: List<String> = own.keys.filter { it !in base }

    override val size: Int get() = base.size + ownOnly.size
    override fun isEmpty(): Boolean = base.isEmpty() && own.isEmpty()
    override fun containsKey(key: String): Boolean = own.containsKey(key) || base.containsKey(key)
    override fun get(key: String): Symbol? = own[key] ?: base[key]
    override fun containsValue(value: Symbol): Boolean = keySequence().any { get(it) === value }

    private fun keySequence(): Sequence<String> = base.keys.asSequence() + ownOnly.asSequence()

    override val keys: MutableSet<String>
        get() = object : AbstractMutableSet<String>() {
            override val size: Int get() = this@LayeredSymbolTable.size
            override fun contains(element: String) = containsKey(element)
            override fun add(element: String) = throw UnsupportedOperationException(READ_ONLY)
            override fun iterator(): MutableIterator<String> = readOnly(keySequence().iterator())
        }

    override val values: MutableCollection<Symbol>
        get() = object : AbstractMutableCollection<Symbol>() {
            override val size: Int get() = this@LayeredSymbolTable.size
            override fun add(element: Symbol) = throw UnsupportedOperationException(READ_ONLY)
            override fun iterator(): MutableIterator<Symbol> =
                readOnly(keySequence().map { get(it)!! }.iterator())
        }

    override val entries: MutableSet<MutableMap.MutableEntry<String, Symbol>>
        get() = object : AbstractMutableSet<MutableMap.MutableEntry<String, Symbol>>() {
            override val size: Int get() = this@LayeredSymbolTable.size
            override fun add(element: MutableMap.MutableEntry<String, Symbol>) =
                throw UnsupportedOperationException(READ_ONLY)
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Symbol>> =
                readOnly(keySequence().map { Entry(it, get(it)!!) as MutableMap.MutableEntry<String, Symbol> }.iterator())
        }

    private class Entry(
        override val key: String,
        override val value: Symbol,
    ) : MutableMap.MutableEntry<String, Symbol> {
        override fun setValue(newValue: Symbol): Symbol = throw UnsupportedOperationException(READ_ONLY)
    }

    private fun <T> readOnly(source: Iterator<T>): MutableIterator<T> = object : MutableIterator<T> {
        override fun hasNext(): Boolean = source.hasNext()
        override fun next(): T = source.next()
        override fun remove() = throw UnsupportedOperationException(READ_ONLY)
    }

    override fun put(key: String, value: Symbol): Symbol? = throw UnsupportedOperationException(READ_ONLY)
    override fun putAll(from: Map<out String, Symbol>) = throw UnsupportedOperationException(READ_ONLY)
    override fun remove(key: String): Symbol? = throw UnsupportedOperationException(READ_ONLY)
    override fun clear() = throw UnsupportedOperationException(READ_ONLY)

    private companion object {
        const val READ_ONLY = "a per-file scope is read-only (INC.61)"
    }
}

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

    /**
     * (WARM.29) CENSUS-ONLY, and null on every production compile: a parallel-array
     * view of [symbols]' keys, filled lazily by `MapCensus.lexAmp` when
     * `--lexLevelAmp` is armed.
     *
     * It lives on the scope rather than in a side map ON PURPOSE. The quantity the
     * amplifier has to measure is the cost of the FIRST probe of a level, which
     * round 901 showed is three dependent pointer loads (`HashMap` -> `table` ->
     * `Node`) and almost no hashing. Fetching the array out of a
     * `HashMap<LexicalScope, ·>` just before the timed loop would pull its header
     * into cache and measure a warm array against a cold map — a bias in exactly
     * the direction that flatters the candidate. Reached from `l`, the scan pays
     * the same kind of load the map arm pays.
     */
    var censusNames: Array<String>? = null
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

/**
 * Multiplicative finalizer for [nodeKey] — the golden-ratio odd constant
 * `0x9E3779B97F4A7C15`, the same one [LongKeyMap] uses.
 *
 * (HASH.1) round 889. Multiplication by an ODD constant modulo 2^64 is a
 * BIJECTION, so the packed key stays exact and collision-free as an identity;
 * only its bit pattern changes.
 */
private const val NODE_KEY_MIX: Long = -0x61c8864680b583ebL

/**
 * Pack [pos]/[end] into a single Long for use as map keys.
 *
 * **(HASH.1) round 889 — why the multiply is load-bearing, and not a style
 * choice.** `java.lang.Long.hashCode()` is `(int) (v xor (v ushr 32))`, so the
 * plain `(pos shl 32) or end` packing hashes to exactly `pos xor end` — and for
 * an AST node `end` is `pos` plus the node's LENGTH, so that XOR is dominated by
 * the low bits and its whole range is roughly "the set of node lengths in the
 * file" — a few hundred values, **however many nodes the file has**. Modelled on
 * a 20,000-node file (`nodeToFlow` is per FILE): the un-mixed key fills
 * **278 of 32,768 buckets**, `max bucket 1,765`, **98.3% of keys past the
 * treeify threshold** — i.e. the map was not a hash table but a handful of
 * red-black trees. With the finalizer: 14,896 buckets used, max bucket 6,
 * ZERO treeified. Measured consequence on the round-888 warm profile:
 * `HashMap$TreeNode.*` charged to `FlowGraphBuilder.recordFlow` alone was
 * **1.11% of compile-thread samples**, and the whole `nodeToFlow` owner group
 * 2.02% with **79% of it inside treeified buckets**
 * (`docs/perf/hash-key-spread.md`).
 *
 * **The soundness argument is that nothing may depend on the key's VALUE.** Two
 * things could and neither does: no consumer unpacks `pos`/`end` back out of a
 * node key (the only unpacking site in the repo is `PassTiming`'s
 * `redundantPairNanos`, a *site-id* pair, not a node key), and every container
 * keyed by one — `Binder.nodeToSymbol`, `Binder.moduleInstanceStates`,
 * `FlowGraphBuilder.nodeToFlow` — is a `mutableMapOf`, i.e. a
 * **LinkedHashMap whose iteration is INSERTION order**, so the one place a
 * `nodeToSymbol` is iterated (`TypeScriptCompiler`'s symbol frontier) cannot
 * move. A plain `HashMap` here would have made this an iteration-order change,
 * which is the rounds-754/776/778 hazard: invisible in every output diff.
 *
 * The sentinel comparison `key != nodeKey(-1, -1)` in `Binder` is unaffected —
 * it compares against the packer's own answer, not a literal.
 */
fun nodeKey(pos: Int, end: Int): Long =
    ((pos.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)) * NODE_KEY_MIX

/** Get the identity key for a [Node], based on its source position. */
fun nodeKey(node: Node): Long = nodeKey(node.pos, node.end)

/**
 * (WARM.23) round 896 — [nodeKey] shifted by one in BOTH coordinates, for the
 * ONE container that left `java.util` for [LongKeyMap]: `FlowGraph.nodeToFlow`.
 *
 * [LongKeyMap] reserves `0L` as its empty-slot sentinel, and `nodeKey(0, 0)`
 * **is** `0L` — a zero-width node at offset 0, which an error-recovery "missing"
 * node at the start of a file really is. The shift is a BIJECTION on `(pos, end)`
 * pairs (`nodeKey` itself is one: the pack is injective and `NODE_KEY_MIX` is
 * odd, so multiplying is a permutation mod 2^64), so it changes nothing about
 * the key's spread or its collision behaviour — only WHICH pair lands on the
 * sentinel. After it that pair is `(-1, -1)`, and both directions are then
 * right by construction:
 *
 *  * `recordFlow` refuses `pos < 0`, so every key WRITTEN has `pos + 1 >= 1` in
 *    the high 32 bits — exactly [LongKeyMap]'s documented invariant;
 *  * a synthetic node (`pos == -1`) asked at a READ site hashes to the sentinel
 *    and [LongKeyMap.get] answers `null` there, which is the same `null` the
 *    LinkedHashMap answered for a node nothing ever recorded.
 *
 * Do NOT re-use this for the other two `nodeKey`-keyed containers: `nodeToSymbol`
 * is ITERATED (see [nodeKey]'s own note), so it cannot move to an unordered
 * container at all.
 */
fun flowKey(pos: Int, end: Int): Long = nodeKey(pos + 1, end + 1)

/** [flowKey] for a [Node], based on its source position. */
fun flowKey(node: Node): Long = flowKey(node.pos, node.end)

/**
 * Pack a pair of 32-bit ids into one Long map key — the checker's house idiom
 * since M0.3(iii) — with [nodeKey]'s multiplicative finalizer applied.
 *
 * **(HASH.1)(b) round 890 — why the multiply is load-bearing here too, and what
 * it is worth.** `java.lang.Long.hashCode()` is `(int) (v xor (v ushr 32))`, so
 * a plain `(a shl 32) or b` packing hashes to exactly `a xor b`. Type and Symbol
 * ids are minted SEQUENTIALLY, and the pairs a relation actually asks about are
 * overwhelmingly NEIGHBOURS — an instantiation against its target, a union
 * against a member it was built from — so `a xor b` piles them into the handful
 * of buckets `1, 2, 3, …`. Measured over the compiler profile's real key
 * populations (`scripts/round890_bucket_model.py`, and note this is not a model
 * of the ids but the ids themselves):
 *
 *  - `Relation.cache` — 43,080 keys collapse onto **18,201 distinct hashes**,
 *    the commonest being `XOR == 1` with **1,140 keys in one bucket**
 *    (`(2k, 2k+1)` pairs). 17,486 of 65,536 buckets used, **27.3% of keys past
 *    the treeify threshold**. With the finalizer: 31,532 buckets, max **6**,
 *    ZERO treeified.
 *  - `resolvedPropertyTypes` — max bucket 10, 2.1% treeified -> max 6, zero.
 *
 * Round 889's JFR attribution priced the `Relation.cache` group at 0.97% of
 * compile-thread samples with **56% of it inside `HashMap$TreeNode` frames**.
 *
 * **The soundness argument is [nodeKey]'s, and it must be re-checked per site:
 * nothing may UNPACK the key, and nothing may depend on ITERATION ORDER.**
 * Every container keyed through here is membership-or-lookup only and none is
 * iterated (`Relation.cache`, `resolvedPropertyTypes`, `relationComparisonStack`,
 * `elaborationStack`, `functionElaborationStack`, `ts2403IdentityStack`,
 * `enumTypesRelationCache`). `PassTiming.redundantPairNanos` is the repo's one
 * key that IS unpacked (`k and 0xFFFF_FFFFL`) — it must never be routed here.
 *
 * Two other packed keys were measured and are FINE as they stand, which is why
 * they do not call this: `Checker.internKey` (`(internSalt, pos)`, max bucket 4)
 * and `Checker.walkMemoKey` (whose `* 31` folds of the walk kind and input
 * digest already spread it, max bucket 6).
 */
internal fun packIdPair(a: Int, b: Int): Long =
    ((a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)) * NODE_KEY_MIX

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

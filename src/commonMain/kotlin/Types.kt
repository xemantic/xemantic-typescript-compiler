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

    /** Unique identifier for use as map keys. */
    val id: Int = nextId++

    /** For import aliases: the resolved target symbol. Set by the checker. */
    var target: Symbol? = null

    override fun toString(): String = "Symbol($name, flags=${flags.value})"

    companion object {
        private var nextId = 1

        /** Reset the ID counter (for testing). */
        fun resetIdCounter() { nextId = 1 }
    }
}

/** A symbol table is a map from name to symbol. */
typealias SymbolTable = MutableMap<String, Symbol>

/** Create a new empty symbol table. */
fun symbolTable(): SymbolTable = mutableMapOf()

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

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
 * (P18.271) (CHK.204) — the names a template-literal TYPE references, for the unused-declaration
 * check (TS6133 / TS6196 / TS6205).
 *
 * **[TemplateLiteralType] is not a structured node in this parser**: `skipTemplateType` parses
 * every `${…}` placeholder and then DROPS it, so `templateSpans` is always empty and the whole
 * raw source slice (backticks included) lives in `head.rawText` (CLAUDE.md archive, "TemplateLiteralType
 * parsing is SHALLOW"). Every hand-written reference collector therefore saw nothing inside a
 * template type, and a type used ONLY there — `` `${Trim<P>}` ``, hono's `AutoFill*` unions —
 * read as "declared but never used" where tsgo is silent.
 *
 * This is a lexical scan of that raw slice, sound in the direction that matters here: it can
 * only ADD names to a reference set, so its failure mode is a missed unused row, never a false
 * one. It reads identifiers inside `${…}` only — never the literal text between placeholders
 * (`` `Unq` `` names no type, and tsgo reports an alias used only that way) — and refuses the
 * tokens that are not references: the name an `infer X` DECLARES, the right side of a `.`
 * (`A.B` references `A`), a property name inside a type literal (`{ a: X }`), the contents of
 * a quoted string, and the type keywords. A nested template's literal text is skipped the same
 * way as the outer one's. [TemplateTypeRef.afterTypeof] marks the operand of `typeof`, the one
 * VALUE-meaning reference a type can make.
 */
internal data class TemplateTypeRef(val name: String, val afterTypeof: Boolean)

internal fun templateTypeReferenceNames(raw: String): List<TemplateTypeRef> {
    val out = ArrayList<TemplateTypeRef>()
    if (raw.indexOf("\${") < 0) return out
    // Bracket stack inside code: '$' marks a `${` placeholder, '`' a nested template, '{' '(' '[' '<'
    // ordinary nesting. An empty stack means template TEXT.
    val stack = ArrayList<Char>()
    var i = 0
    val n = raw.length
    // The outer slice starts with its own backtick; text mode until the first `${`.
    if (i < n && raw[i] == '`') i++
    var prevWord: String? = null
    var prevSignificant = ' '
    while (i < n) {
        val inText = stack.isEmpty() || stack.last() == '`'
        val c = raw[i]
        if (inText) {
            when {
                c == '\\' -> i += 2
                c == '$' && i + 1 < n && raw[i + 1] == '{' -> { stack.add('$'); i += 2; prevWord = null; prevSignificant = '{' }
                c == '`' -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); i++; prevWord = null; prevSignificant = '`' }
                else -> i++
            }
            continue
        }
        when {
            c == '\'' || c == '"' -> {
                i++
                while (i < n && raw[i] != c) { if (raw[i] == '\\') i++; i++ }
                i++
                prevWord = null; prevSignificant = c
            }
            c == '`' -> { stack.add('`'); i++; prevWord = null; prevSignificant = '`' }
            c == '=' && i + 1 < n && raw[i + 1] == '>' -> { i += 2; prevWord = null; prevSignificant = '>' }
            c == '{' || c == '(' || c == '[' || c == '<' -> { stack.add(c); i++; prevWord = null; prevSignificant = c }
            c == '}' || c == ')' || c == ']' || c == '>' -> {
                if (stack.isNotEmpty() && stack.last() != '`') stack.removeAt(stack.size - 1)
                i++; prevWord = null; prevSignificant = c
            }
            c.isLetter() || c == '_' || c == '$' -> {
                val start = i
                while (i < n && (raw[i].isLetterOrDigit() || raw[i] == '_' || raw[i] == '$')) i++
                val word = raw.substring(start, i)
                val afterDot = prevSignificant == '.'
                val declaredByInfer = prevWord == "infer"
                val isProperty = stack.lastOrNull() == '{' && followedByColon(raw, i)
                if (!afterDot && !declaredByInfer && !isProperty && word !in TEMPLATE_TYPE_KEYWORDS) {
                    out.add(TemplateTypeRef(word, afterTypeof = prevWord == "typeof"))
                }
                prevWord = word; prevSignificant = 'a'
            }
            c.isWhitespace() -> i++
            else -> { i++; prevWord = null; prevSignificant = c }
        }
    }
    return out
}

/** True when the identifier ending at [end] is followed (after whitespace and an optional `?`) by
 *  a `:` — the shape of a type-literal property name. */
private fun followedByColon(raw: String, end: Int): Boolean {
    var j = end
    while (j < raw.length && raw[j].isWhitespace()) j++
    if (j < raw.length && raw[j] == '?') j++
    while (j < raw.length && raw[j].isWhitespace()) j++
    return j < raw.length && raw[j] == ':'
}

private val TEMPLATE_TYPE_KEYWORDS = hashSetOf(
    "string", "number", "bigint", "boolean", "symbol", "object", "any", "unknown", "never", "void",
    "undefined", "null", "true", "false", "this", "infer", "extends", "keyof", "typeof", "readonly",
    "unique", "is", "asserts", "in", "out", "new", "const", "abstract",
)

/**
 * (P18.271) True for a statement written with `declare`: tsgo's `reportUnused` drops every
 * unused-declaration row on a node carrying `NodeFlagsAmbient`, and every node under a
 * `declare` statement carries it — a module augmentation's interfaces, a `declare namespace`'s
 * members, a `declare class C<T>`'s type parameters (measured against tsgo 7.0.2).
 */
internal fun isAmbientUnusedRoot(stmt: Statement): Boolean {
    val modifiers = when (stmt) {
        is ModuleDeclaration -> stmt.modifiers
        is ClassDeclaration -> stmt.modifiers
        is InterfaceDeclaration -> stmt.modifiers
        is TypeAliasDeclaration -> stmt.modifiers
        is FunctionDeclaration -> stmt.modifiers
        is EnumDeclaration -> stmt.modifiers
        is VariableStatement -> stmt.modifiers
        else -> return false
    }
    return ModifierFlag.Declare in modifiers
}

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
 * A parsed JSON value. Deliberately tiny — just enough to read `tsconfig.json`
 * (JSONC: `//` and `/* */` comments + trailing commas) and `package.json`.
 * Use [parseJson]; it returns `null` on malformed input rather than throwing,
 * so the project driver degrades gracefully on a broken config.
 */
sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue()

    /** Member [key] of an object, or null (also null for non-objects). */
    operator fun get(key: String): JsonValue? = (this as? Obj)?.entries?.get(key)

    /** This value as a string, or null. */
    val string: String? get() = (this as? Str)?.value

    /** This value as a boolean, or null. */
    val bool: Boolean? get() = (this as? Bool)?.value

    /** This value's items if it is an array, else a single-element list, else empty. */
    val asStringList: List<String>
        get() = when (this) {
            is Arr -> items.mapNotNull { it.string }
            is Str -> listOf(value)
            else -> emptyList()
        }
}

/** Parses a JSONC string into a [JsonValue], or returns `null` if it is malformed. */
fun parseJson(text: String): JsonValue? = try {
    val p = JsonParser(text)
    p.skipTrivia()
    val v = p.parseValue()
    p.skipTrivia()
    v
} catch (_: Throwable) {
    null
}

private class JsonParser(private val s: String) {
    private var i = 0

    fun parseValue(): JsonValue {
        skipTrivia()
        if (i >= s.length) error("unexpected end")
        return when (val c = s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"', '\'' -> JsonValue.Str(parseString())
            't', 'f' -> parseBool()
            'n' -> { expectWord("null"); JsonValue.Null }
            else -> if (c == '-' || c in '0'..'9') parseNumber() else error("unexpected '$c'")
        }
    }

    private fun parseObject(): JsonValue.Obj {
        i++ // {
        val map = LinkedHashMap<String, JsonValue>()
        skipTrivia()
        if (peek() == '}') { i++; return JsonValue.Obj(map) }
        while (true) {
            skipTrivia()
            val key = parseString()
            skipTrivia()
            if (peek() != ':') error("expected ':'")
            i++
            val value = parseValue()
            map[key] = value
            skipTrivia()
            when (peek()) {
                ',' -> { i++; skipTrivia(); if (peek() == '}') { i++; break } }
                '}' -> { i++; break }
                else -> error("expected ',' or '}'")
            }
        }
        return JsonValue.Obj(map)
    }

    private fun parseArray(): JsonValue.Arr {
        i++ // [
        val items = mutableListOf<JsonValue>()
        skipTrivia()
        if (peek() == ']') { i++; return JsonValue.Arr(items) }
        while (true) {
            items.add(parseValue())
            skipTrivia()
            when (peek()) {
                ',' -> { i++; skipTrivia(); if (peek() == ']') { i++; break } }
                ']' -> { i++; break }
                else -> error("expected ',' or ']'")
            }
        }
        return JsonValue.Arr(items)
    }

    private fun parseString(): String {
        val quote = peek()
        if (quote != '"' && quote != '\'') error("expected string")
        i++
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            when (c) {
                quote -> return sb.toString()
                '\\' -> {
                    val e = s[i++]
                    when (e) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        '/' -> sb.append('/'); '\\' -> sb.append('\\')
                        '"' -> sb.append('"'); '\'' -> sb.append('\'')
                        'u' -> { val hex = s.substring(i, i + 4); i += 4; sb.append(hex.toInt(16).toChar()) }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
        }
        error("unterminated string")
    }

    private fun parseNumber(): JsonValue.Num {
        val start = i
        if (peek() == '-') i++
        while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        return JsonValue.Num(s.substring(start, i).toDouble())
    }

    private fun parseBool(): JsonValue.Bool =
        if (s.startsWith("true", i)) { i += 4; JsonValue.Bool(true) }
        else { expectWord("false"); JsonValue.Bool(false) }

    private fun expectWord(w: String) {
        if (!s.startsWith(w, i)) error("expected '$w'")
        i += w.length
    }

    private fun peek(): Char = if (i < s.length) s[i] else ' '

    fun skipTrivia() {
        while (i < s.length) {
            val c = s[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF' -> i++
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> { while (i < s.length && s[i] != '\n') i++ }
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++
                    i += 2
                }
                else -> return
            }
        }
    }
}

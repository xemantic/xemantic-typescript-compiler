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
 * (P18.287) (CHK.216) template literal TYPES — tsgo's `getTemplateLiteralType` normalisation,
 * the literal-to-template relation (`isTypeMatchedByTemplateLiteralType`,
 * `inferFromLiteralPartsToTemplateLiteral`, `isValidTypeForTemplateLiteralPlaceholder`) and the
 * display (`typeToTypeNode`'s template arm + the printer's backtick escaping).
 *
 * Before this round a template resolved to a fresh `string` intrinsic carrying its source text
 * for display, so `'5' extends \`-${string}\`` was TRUE. A [Type.TemplateLiteral] still IS that
 * intrinsic for every other reader; the relation consults [relateToTemplate] for a PRECISE,
 * non-generic template TARGET only.
 *
 * Deliberately conservative where this checker's types are weaker than tsgo's:
 * - a span resolving to `any` / `error` / an enum / an intersection / an object makes the
 *   template IMPRECISE (it relates as plain `string`, exactly the old model) — this checker
 *   answers `any` for types it cannot resolve, and tsgo would have a real type there;
 * - a plain `string` SOURCE stays assignable to a template target: tsgo types a template
 *   EXPRESSION in a template context as a template, this checker types it `string`, so the
 *   strict answer would be a false positive on legal code (a missing row instead);
 * - a GENERIC template (a span holding a type parameter) is never decided as a target.
 */
internal class TemplateLiteralTypes(private val checker: Checker) {

    private val interned = HashMap<String, Type.TemplateLiteral>()

    /** > 0 while a conditional type's `extends` is being decided: there the check type is a TYPE,
     *  never a `string`-typed template expression, so a plain `string` source is decided too. */
    var conditionalDepth = 0

    /** tsgo `getTypeFromTemplateTypeNode`. [spanType] resolves one span's type node. */
    fun fromNode(node: TemplateLiteralType, spanType: (TypeNode) -> Type): Type {
        val texts = ArrayList<String>(node.templateSpans.size + 1)
        texts.add(node.head.text)
        val types = ArrayList<Type>(node.templateSpans.size)
        for (span in node.templateSpans) {
            texts.add((span.literal as? StringLiteralNode)?.text ?: "")
            types.add(spanType(span.type))
        }
        if (types.isEmpty()) return Type.StringLiteral(texts[0])
        return get(texts, types, node.head.rawText?.takeIf { it.isNotEmpty() })
    }

    /** tsgo `getTemplateLiteralType` (texts.size == types.size + 1). */
    fun get(texts: List<String>, types: List<Type>, rawDisplay: String? = null): Type {
        val unionIndex = types.indexOfFirst { it === neverType || it is Type.Union || it === booleanType }
        if (unionIndex >= 0) {
            var product = 1L
            for (t in types) {
                product *= constituents(t).size.toLong().coerceAtLeast(1)
                if (product > 100_000) return imprecise(texts, types, rawDisplay)
            }
            val parts = constituents(types[unionIndex]).map { c ->
                get(texts, types.toMutableList().also { it[unionIndex] = c }, null)
            }
            return checker.getUnionType(parts)
        }
        val newTexts = ArrayList<String>()
        val newTypes = ArrayList<Type>()
        val sb = StringBuilder(texts[0])
        // Returns false when a span holds a type the model cannot place.
        fun addSpans(ts: List<String>, tys: List<Type>): Boolean {
            for (i in tys.indices) {
                val t = tys[i]
                val str = templateStringFor(t)
                when {
                    str != null -> { sb.append(str); sb.append(ts[i + 1]) }
                    t is Type.TemplateLiteral -> {
                        if (!t.precise) return false
                        sb.append(t.texts[0])
                        if (!addSpans(t.texts, t.types)) return false
                        sb.append(ts[i + 1])
                    }
                    isPlaceholder(t) -> {
                        newTypes.add(t); newTexts.add(sb.toString())
                        sb.setLength(0); sb.append(ts[i + 1])
                    }
                    else -> return false
                }
            }
            return true
        }
        if (!addSpans(texts, types)) return imprecise(texts, types, rawDisplay)
        if (newTypes.isEmpty()) return Type.StringLiteral(sb.toString())
        newTexts.add(sb.toString())
        if (newTexts.all { it.isEmpty() } && newTypes.all { it === stringType }) return stringType
        val generic = newTypes.any { it is Type.TypeParam }
        val key = newTexts.joinToString("\u0000") + "\u0001" + newTypes.joinToString(",") { it.id.toString() }
        return interned.getOrPut(key) { Type.TemplateLiteral(newTexts, newTypes, precise = true, generic = generic) }
    }

    private fun constituents(t: Type): List<Type> = when {
        t === neverType -> emptyList()
        t === booleanType -> listOf(falseType, trueType)
        t is Type.Union -> t.types
        else -> listOf(t)
    }

    private fun imprecise(texts: List<String>, types: List<Type>, rawDisplay: String?): Type =
        Type.TemplateLiteral(texts, types, precise = false, generic = false, rawDisplay = rawDisplay)

    /** A pattern placeholder (tsgo `isPatternLiteralPlaceholderType` / `isGenericIndexType`),
     *  minus `any`: this checker's `any` is too often an unresolved type. */
    private fun isPlaceholder(t: Type): Boolean =
        t === stringType || t === numberType || t === bigintType || t is Type.TypeParam ||
            t is Type.StringMapping // (P18.302) `${Uppercase<string>}` — matched by [isValidTypeForTemplateLiteralPlaceholder]

    /** tsgo `getTemplateStringForType`, or null for a non-literal. */
    private fun templateStringFor(t: Type): String? = when {
        t is Type.StringLiteral -> t.value
        t is Type.NumberLiteral -> jsNumberToString(t.value)
        t is Type.BigIntLiteral -> t.value.removeSuffix("n")
        t === trueType -> "true"
        t === falseType -> "false"
        t === nullType -> "null"
        t === undefinedType -> "undefined"
        else -> null
    }

    /** Whether [target] is a template the relation decides itself. */
    fun isDecidableTarget(target: Type): Boolean =
        target is Type.TemplateLiteral && target.precise && !target.generic

    /**
     * The verdict of relating [source] to the decidable template [target], or null when the
     * caller's ordinary path decides (a union / intersection source decomposes there; a plain
     * `string` or an imprecise / generic template source keeps the old permissive answer).
     */
    fun relateToTemplate(source: Type, target: Type.TemplateLiteral, compare: (Type, Type) -> Boolean): Boolean? {
        return when {
            source is Type.StringLiteral -> isTypeMatchedByTemplateLiteralType(source, target, compare)
            source is Type.TemplateLiteral -> {
                if (!source.precise || source.generic) null
                else if (source.texts == target.texts &&
                    source.types.indices.all { compare(source.types[it], target.types[it]) }
                ) true
                else isTypeMatchedByTemplateLiteralType(source, target, compare)
            }
            source is Type.Union || source is Type.Intersection -> null
            source === stringType && conditionalDepth > 0 -> false
            source.flags.hasAny(TypeFlags.String) -> null
            // number / boolean / object …: a template is a string — the ordinary path rejects.
            else -> null
        }
    }

    private fun isTypeMatchedByTemplateLiteralType(
        source: Type, target: Type.TemplateLiteral, compare: (Type, Type) -> Boolean,
    ): Boolean {
        val inferences = inferTypesFromTemplateLiteralType(source, target) ?: return false
        for (i in inferences.indices) {
            if (!isValidTypeForTemplateLiteralPlaceholder(inferences[i], target.types[i], compare)) return false
        }
        return true
    }

    /** tsgo `inferTypesFromTemplateLiteralType` — also PART 2's entry point for `infer`. */
    fun inferTypesFromTemplateLiteralType(source: Type, target: Type.TemplateLiteral): List<Type>? = when (source) {
        is Type.StringLiteral -> inferFromLiteralPartsToTemplateLiteral(listOf(source.value), emptyList(), target)
        is Type.TemplateLiteral -> inferFromLiteralPartsToTemplateLiteral(source.texts, source.types, target)
        else -> null
    }

    /** tsgo `inferFromLiteralPartsToTemplateLiteral` (UTF-16 code-unit stepping, as Strada). */
    private fun inferFromLiteralPartsToTemplateLiteral(
        sourceTexts: List<String>, sourceTypes: List<Type>, target: Type.TemplateLiteral,
    ): List<Type>? {
        val lastSourceIndex = sourceTexts.size - 1
        val sourceStartText = sourceTexts[0]
        val sourceEndText = sourceTexts[lastSourceIndex]
        val targetTexts = target.texts
        val lastTargetIndex = targetTexts.size - 1
        val targetStartText = targetTexts[0]
        val targetEndText = targetTexts[lastTargetIndex]
        if (lastSourceIndex == 0 && sourceStartText.length < targetStartText.length + targetEndText.length ||
            !sourceStartText.startsWith(targetStartText) || !sourceEndText.endsWith(targetEndText)
        ) return null
        val remainingEndText = sourceEndText.substring(0, sourceEndText.length - targetEndText.length)
        var seg = 0
        var pos = targetStartText.length
        val matches = ArrayList<Type>()
        fun sourceText(index: Int): String = if (index < lastSourceIndex) sourceTexts[index] else remainingEndText
        fun addMatch(s: Int, p: Int) {
            val matchType: Type = if (s == seg) {
                Type.StringLiteral(sourceText(s).substring(pos, p))
            } else {
                val matchTexts = ArrayList<String>()
                matchTexts.add(sourceTexts[seg].substring(pos))
                for (k in seg + 1 until s) matchTexts.add(sourceTexts[k])
                matchTexts.add(sourceText(s).substring(0, p))
                get(matchTexts, sourceTypes.subList(seg, s))
            }
            matches.add(matchType)
            seg = s
            pos = p
        }
        for (i in 1 until lastTargetIndex) {
            val delim = targetTexts[i]
            if (delim.isNotEmpty()) {
                var s = seg
                var p = pos
                while (true) {
                    val d = sourceText(s).indexOf(delim, p)
                    if (d >= 0) { p = d; break }
                    s++
                    if (s == sourceTexts.size) return null
                    p = 0
                }
                addMatch(s, p)
                pos += delim.length
            } else if (pos < sourceText(seg).length) {
                addMatch(seg, pos + 1)
            } else if (seg < lastSourceIndex) {
                addMatch(seg + 1, 0)
            } else {
                return null
            }
        }
        addMatch(lastSourceIndex, sourceText(lastSourceIndex).length)
        return matches
    }

    /**
     * (P18.288) (CHK.216) part 2 — `S extends \`…${infer X}…\``: match [source] against the template
     * PATTERN [node] (tsgo `inferToTemplateLiteralType` followed by the conditional's relation check).
     * [spanType] resolves a span without an `infer`, [constraintOf] an `infer`'s constraint (null for
     * none). Answers the new bindings, [NO_MATCH], or null for a shape not modelled.
     */
    fun matchInferPattern(
        source: Type, node: TemplateLiteralType, spanType: (TypeNode) -> Type, constraintOf: (InferType) -> Type?,
        compare: (Type, Type) -> Boolean,
    ): Map<String, Type>? {
        // A checked span's type, resolved once (null for an `infer` span).
        val spanTypes = node.templateSpans.map { span ->
            var st = span.type
            while (st is ParenthesizedType) st = st.type
            if (st is InferType) null else spanType(st)
        }
        // (P18.307) tsgo's `getTemplateLiteralType` DISTRIBUTES a union span (`${infer R}${Whitespace}` is a union of
        // templates, one per member), and inference to a union target tries each member: answer the one alternative
        // that matches, NO_MATCH when none does, and leave several differing matches to the old path.
        val unionAt = spanTypes.indexOfFirst { t -> t is Type.Union && t.types.all { templateStringFor(it) != null } }
        if (unionAt >= 0) {
            val members = (spanTypes[unionAt] as Type.Union).types
            if (members.size > MAX_SPAN_DISTRIBUTION) return null
            var found: Map<String, Type>? = null
            for (m in members) {
                val r = matchInferPatternWith(source, node, spanTypes.toMutableList().also { it[unionAt] = m }, constraintOf, compare)
                    ?: return null
                if (r === NO_MATCH) continue
                if (found != null && found != r) return null
                found = r
            }
            return found ?: NO_MATCH
        }
        return matchInferPatternWith(source, node, spanTypes, constraintOf, compare)
    }

    private fun matchInferPatternWith(
        source: Type, node: TemplateLiteralType, spanTypes: List<Type?>, constraintOf: (InferType) -> Type?,
        compare: (Type, Type) -> Boolean,
    ): Map<String, Type>? {
        // The pattern's texts, with a literal span folded into its neighbours.
        val texts = arrayListOf(node.head.text)
        val spans = ArrayList<Any>() // InferType, or the placeholder Type of a checked span
        for ((spanIdx, span) in node.templateSpans.withIndex()) {
            val lit = (span.literal as? StringLiteralNode)?.text ?: ""
            var st = span.type
            while (st is ParenthesizedType) st = st.type
            if (st is InferType) { spans.add(st); texts.add(lit); continue }
            val t = spanTypes[spanIdx]!!
            val str = templateStringFor(t)
            when {
                str != null -> texts[texts.size - 1] = texts.last() + str + lit
                t === stringType || t === numberType || t === bigintType -> { spans.add(t); texts.add(lit) }
                else -> return null
            }
        }
        when {
            source is Type.StringLiteral -> {}
            source is Type.TemplateLiteral -> if (!source.precise || source.generic) return null
            source === stringType -> return NO_MATCH
            source is Type.NumberLiteral || source is Type.BigIntLiteral || source === numberType ||
                source === bigintType || source === booleanType || source === trueType || source === falseType ||
                source === nullType || source === undefinedType || source === unknownType -> return NO_MATCH
            else -> return null
        }
        val target = Type.TemplateLiteral(texts, spans.map { (it as? Type) ?: stringType }, precise = true, generic = false)
        val matches = inferTypesFromTemplateLiteralType(source, target) ?: return NO_MATCH
        val out = HashMap<String, Type>()
        for (i in spans.indices) {
            val sp = spans[i]
            val m = matches[i]
            if (sp is Type) {
                if (!isValidTypeForTemplateLiteralPlaceholder(m, sp, compare)) return NO_MATCH
                continue
            }
            val infer = sp as InferType
            val name = infer.typeParameter.name.text
            if (name in out) return null
            val constraint = constraintOf(infer)
            if (constraint === errorType) return null
            val bound = if (constraint == null || constraint === anyType || constraint === unknownType) m else {
                val candidate = (m as? Type.StringLiteral)?.let { literalForConstraint(it, constraint) } ?: m
                if (compare(candidate, constraint)) candidate else constraint
            }
            // The relation of [source] to the instantiated pattern: a literal binding re-stringifies to the text.
            if (constraint != null && !(m is Type.StringLiteral && templateStringFor(bound) == m.value) &&
                !isValidTypeForTemplateLiteralPlaceholder(m, bound, compare)
            ) return NO_MATCH
            out[name] = bound
        }
        return out
    }

    /** tsgo's `inferToTemplateLiteralType` reduction over a constraint: the literal the matched text
     *  [source] denotes in the constraint's domain (a round-tripping number / bigint, a boolean, `null`,
     *  `undefined`), or the text itself when the constraint admits strings; null when none applies. */
    private fun literalForConstraint(source: Type.StringLiteral, constraint: Type): Type? {
        val members = ((constraint as? Type.Union)?.types ?: listOf(constraint)).flatMap { constituents(it) }
        if (members.any { it.flags.hasAny(TypeFlags.String) || it is Type.StringLiteral }) return source
        val v = source.value
        for (t in members) {
            when {
                t is Type.NumberLiteral -> if (isValidNumberString(v) && jsStringToNumber(v) == t.value) return t
                t === numberType -> if (isValidNumberString(v) && jsNumberToString(jsStringToNumber(v)) == v)
                    return Type.NumberLiteral(jsStringToNumber(v))
                t is Type.BigIntLiteral -> if (v == t.value) return t
                t === bigintType -> if (isValidBigIntString(v) && !v.startsWith("0x") && !v.startsWith("0o") &&
                    !v.startsWith("0b") && v != "-0" && (v.trimStart('-').length == 1 || !v.trimStart('-').startsWith("0"))
                ) return Type.BigIntLiteral(v)
                t === trueType -> if (v == "true") return t
                t === falseType -> if (v == "false") return t
                t === nullType -> if (v == "null") return t
                t === undefinedType -> if (v == "undefined") return t
            }
        }
        return null
    }

    /** tsgo `isValidTypeForTemplateLiteralPlaceholder`. */
    private fun isValidTypeForTemplateLiteralPlaceholder(
        source: Type, target: Type, compare: (Type, Type) -> Boolean,
    ): Boolean {
        if (target is Type.Intersection) return target.types.all { isValidTypeForTemplateLiteralPlaceholder(source, it, compare) }
        if (target === stringType || compare(source, target)) return true
        if (source is Type.StringLiteral) {
            val value = source.value
            return target === numberType && isValidNumberString(value) ||
                target === bigintType && isValidBigIntString(value) ||
                target is Type.TemplateLiteral && target.precise && !target.generic &&
                isTypeMatchedByTemplateLiteralType(source, target, compare)
        }
        if (source is Type.TemplateLiteral) {
            return source.texts.size == 2 && source.texts[0].isEmpty() && source.texts[1].isEmpty() &&
                compare(source.types[0], target)
        }
        return false
    }

    /** The display of a template — tsgo's template node through the printer's backtick escaping. */
    fun display(t: Type.TemplateLiteral): String {
        if (!t.precise && t.rawDisplay != null) return t.rawDisplay
        val sb = StringBuilder("`")
        escapeInto(sb, t.texts[0])
        for (i in t.types.indices) {
            sb.append("\${").append(checker.typeToString(t.types[i])).append('}')
            escapeInto(sb, t.texts[i + 1])
        }
        return sb.append('`').toString()
    }

    private fun escapeInto(sb: StringBuilder, s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> sb.append("\\\\")
                c == '`' -> sb.append("\\`")
                c == '$' && i + 1 < s.length && s[i + 1] == '{' -> sb.append("\\$")
                c == '\r' && i + 1 < s.length && s[i + 1] == '\n' -> { sb.append("\\r\\n"); i++ }
                c == '\r' -> sb.append("\\r")
                c == '\n' -> sb.append('\n')
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c == '\u000b' -> sb.append("\\v")
                c == '\u0000' -> sb.append(if (i + 1 < s.length && s[i + 1].isDigit()) "\\x00" else "\\0")
                c == ' ' || c == ' ' || c == '\u0085' || c < ' ' || c in '\uD800'..'\uDFFF' &&
                    !(c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) &&
                    !(c.isLowSurrogate() && i > 0 && s[i - 1].isHighSurrogate()) ->
                    sb.append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0'))
                else -> sb.append(c)
            }
            i++
        }
    }

    companion object {
        /** [matchInferPattern]'s "definitely not matched" answer (compared by identity). */
        val NO_MATCH: Map<String, Type> = HashMap()

        /** The largest union span [matchInferPattern] distributes over (type-fest's `Whitespace` has 25 members). */
        private const val MAX_SPAN_DISTRIBUTION = 64

        private val DECIMAL = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

        /** tsgo `isValidNumberString(s, roundTripOnly = false)`: JavaScript `+s` is finite. */
        fun isValidNumberString(s: String): Boolean {
            if (s.isEmpty()) return false
            val n = jsStringToNumber(s)
            return !n.isNaN() && !n.isInfinite()
        }

        /** tsgo `isValidBigIntString(s, roundTripOnly = false)`: `-`? then a bigint literal without
         *  separators (decimal, or a 0x / 0o / 0b integer). */
        fun isValidBigIntString(s: String): Boolean {
            var t = s
            if (t.startsWith("-")) t = t.substring(1)
            if (t.isEmpty()) return false
            if (t.length > 2 && t[0] == '0' && t[1].lowercaseChar() in "xob") {
                val digits = t.substring(2)
                val ok: (Char) -> Boolean = when (t[1].lowercaseChar()) {
                    'x' -> { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }
                    'o' -> { c -> c in '0'..'7' }
                    else -> { c -> c == '0' || c == '1' }
                }
                return digits.isNotEmpty() && digits.all(ok)
            }
            if (!t.all { it.isDigit() }) return false
            // A legacy octal-like `012n` is a scanner error; `0n` is fine.
            return !(t.length > 1 && t[0] == '0')
        }

        /** JavaScript `ToNumber(string)`. */
        fun jsStringToNumber(raw: String): Double {
            val s = raw.trim { it.isWhitespace() || it == '﻿' }
            if (s.isEmpty()) return 0.0
            if (s.length > 2 && s[0] == '0' && s[1].lowercaseChar() in "xob") {
                val radix = when (s[1].lowercaseChar()) { 'x' -> 16; 'o' -> 8; else -> 2 }
                var v = 0.0
                for (c in s.substring(2)) {
                    val d = c.digitToIntOrNull(radix) ?: return Double.NaN
                    v = v * radix + d
                }
                return v
            }
            val body = s.removePrefix("+").removePrefix("-")
            if (body == "Infinity") return if (s.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            // StrDecimalLiteral: digits [. digits] [e[+-]digits], or . digits …
            if (!DECIMAL.matches(s)) return Double.NaN
            return s.toDoubleOrNull() ?: Double.NaN
        }

        /** JavaScript `Number.prototype.toString()` for a finite double (ECMAScript Number::toString). */
        fun jsNumberToString(d: Double): String {
            if (d.isNaN()) return "NaN"
            if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
            if (d == 0.0) return "0"
            if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e21) {
                if (kotlin.math.abs(d) < 9.0e15) return d.toLong().toString()
            }
            // Shortest round-trip digits from Kotlin's representation, re-laid out per ECMAScript.
            val neg = d < 0
            val rep = kotlin.math.abs(d).toString()
            val eIdx = rep.indexOfFirst { it == 'E' || it == 'e' }
            val mantissa = if (eIdx >= 0) rep.substring(0, eIdx) else rep
            var exp10 = if (eIdx >= 0) rep.substring(eIdx + 1).toInt() else 0
            val dot = mantissa.indexOf('.')
            val intPart = if (dot >= 0) mantissa.substring(0, dot) else mantissa
            val fracPart = if (dot >= 0) mantissa.substring(dot + 1) else ""
            var digits = (intPart + fracPart).trimStart('0')
            val leadingZeros = (intPart + fracPart).length - (intPart + fracPart).trimStart('0').length
            // n: position of the decimal point relative to the digit string (ECMAScript's `n`).
            val n = intPart.length - leadingZeros + exp10
            digits = digits.trimEnd('0')
            if (digits.isEmpty()) return "0"
            val k = digits.length
            val sb = StringBuilder()
            if (neg) sb.append('-')
            when {
                n in k..21 -> { sb.append(digits); repeat(n - k) { sb.append('0') } }
                n in 1..21 -> { sb.append(digits, 0, n).append('.').append(digits, n, k) }
                n in -5..0 -> { sb.append("0."); repeat(-n) { sb.append('0') }; sb.append(digits) }
                else -> {
                    exp10 = n - 1
                    sb.append(digits[0])
                    if (k > 1) sb.append('.').append(digits, 1, k)
                    sb.append('e').append(if (exp10 >= 0) "+" else "-").append(kotlin.math.abs(exp10))
                }
            }
            return sb.toString()
        }
    }
}

/**
 * (P18.287) The COOKED value of a template literal's raw text segment (the scanner keeps template
 * text raw, with CRLF already normalised to LF): the template-literal TYPE's texts are cooked
 * values, as tsgo's `Text()` of a template head / middle / tail.
 */
internal fun cookTemplateText(raw: String): String {
    if (raw.indexOf('\\') < 0) return raw
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '\\' || i + 1 >= raw.length) { sb.append(c); i++; continue }
        val e = raw[i + 1]
        i += 2
        when (e) {
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000c')
            'v' -> sb.append('\u000b')
            '0' -> sb.append('\u0000')
            '\n' -> {}
            ' ', ' ' -> {}
            'x' -> {
                val h = raw.substring(i, minOf(i + 2, raw.length)).toIntOrNull(16)
                if (h != null && i + 2 <= raw.length) { sb.append(h.toChar()); i += 2 } else sb.append('x')
            }
            'u' -> {
                if (i < raw.length && raw[i] == '{') {
                    val close = raw.indexOf('}', i)
                    val cp = if (close > i) raw.substring(i + 1, close).toIntOrNull(16) else null
                    if (cp != null && cp <= 0x10FFFF) {
                        if (cp >= 0x10000) {
                            val v = cp - 0x10000
                            sb.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
                        } else sb.append(cp.toChar())
                        i = close + 1
                    } else sb.append('u')
                } else {
                    val h = if (i + 4 <= raw.length) raw.substring(i, i + 4).toIntOrNull(16) else null
                    if (h != null) { sb.append(h.toChar()); i += 4 } else sb.append('u')
                }
            }
            else -> sb.append(e)
        }
    }
    return sb.toString()
}

/**
 * (P18.288) The value of a numeric literal's source text — separators (`1_000`) and the `0x` / `0o` /
 * `0b` radixes included, as tsgo's scanner-computed value; null where it is not a number.
 */
internal fun numericLiteralTextValue(text: String): Double? {
    val v = TemplateLiteralTypes.jsStringToNumber(text.replace("_", ""))
    return if (v.isNaN()) null else v
}

/**
 * (P18.288) A bigint literal's NORMALISED decimal value (tsgo's `PseudoBigInt` text): `1_000n` is `1000`,
 * `0xFFn` is `255`. The display and the template stringification both read it.
 */
internal fun bigIntLiteralTextValue(text: String): String {
    val t = text.removeSuffix("n").replace("_", "")
    if (t.length <= 2 || t[0] != '0' || t[1].lowercaseChar() !in "xob") return t.trimStart('0').ifEmpty { "0" }
    val radix = when (t[1].lowercaseChar()) { 'x' -> 16; 'o' -> 8; else -> 2 }
    val digits = IntArray(1) // little-endian decimal digits
    var len = 1
    var acc = digits
    for (c in t.substring(2)) {
        var carry = c.digitToIntOrNull(radix) ?: return t
        for (i in 0 until len) {
            val v = acc[i] * radix + carry
            acc[i] = v % 10; carry = v / 10
        }
        while (carry > 0) {
            if (len == acc.size) acc = acc.copyOf(acc.size * 2)
            acc[len++] = carry % 10; carry /= 10
        }
    }
    val sb = StringBuilder()
    for (i in len - 1 downTo 0) sb.append(acc[i])
    return sb.toString()
}

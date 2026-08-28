"""
Derive the Kotlin/Native KIR runtime from the JVM one.

USE:  python3 scripts/kir_native_runtime.py <JsRuntime.kt> <out.kt>

A TRANSFORMATION rather than a FORK, and that is the whole design: the two
runtimes must answer identically, so the native one is generated from the JVM
one on every build and there is no second copy to drift. Everything
platform-neutral is copied verbatim; what is replaced is exactly what Kotlin/
Native does not have -- java.math.BigInteger, java.time, java.util.regex, a
concurrent map, String.format, Character.digit, code-point handling, and the
java.lang.reflect fallback (which is REFUSED rather than approximated).

Every replacement is anchored on text that must occur EXACTLY once. A missed
anchor exits non-zero: the failure mode this exists to prevent is a file that
still compiles and quietly means something else.
"""
import sys, pathlib

src = pathlib.Path(sys.argv[1]).read_text()
out = pathlib.Path(sys.argv[2])

def sub(old, new, count=1):
    global src
    n = src.count(old)
    if n != count:
        raise SystemExit(f"anchor occurs {n} times, expected {count}: {old[:90]!r}")
    src = src.replace(old, new)

# ---- (1) bigint: java.math.BigInteger -> a runtime-provided JsBigInt --------
sub(
    'public fun jsBigInt(digits: String): java.math.BigInteger = java.math.BigInteger(digits)',
    '''public class JsBigInt(digits: String) {
    /** Normalized: no leading '+' and no leading zeroes, so equality is textual. */
    public val digits: String = normalize(digits)
    public fun toDouble(): Double = digits.toDoubleOrNull() ?: Double.NaN
    override fun toString(): String = digits
    override fun equals(other: Any?): Boolean = other is JsBigInt && other.digits == digits
    override fun hashCode(): Int = digits.hashCode()
    private companion object {
        fun normalize(text: String): String {
            val negative = text.startsWith("-")
            val body = text.trimStart('+', '-').trimStart('0')
            return if (body.isEmpty()) "0" else if (negative) "-$body" else body
        }
    }
}

public fun jsBigInt(digits: String): JsBigInt = JsBigInt(digits)''')
sub('''public fun jsBigIntOf(value: Any?): java.math.BigInteger = when (value) {
    is java.math.BigInteger -> value
    is Double -> java.math.BigInteger.valueOf(value.toLong())
    else -> java.math.BigInteger(jsToString(value))
}''',
    '''public fun jsBigIntOf(value: Any?): JsBigInt = when (value) {
    is JsBigInt -> value
    is Double -> JsBigInt(value.toLong().toString())
    else -> JsBigInt(jsToString(value))
}''')
sub('    is java.math.BigInteger -> "bigint"', '    is JsBigInt -> "bigint"')
sub('    is java.math.BigInteger -> value.toString()', '    is JsBigInt -> value.toString()')
sub('    is java.math.BigInteger -> value.toDouble()', '    is JsBigInt -> value.toDouble()')

# ---- (2) Date: java.time -> civil-date arithmetic ---------------------------
sub('public constructor() : this(System.currentTimeMillis().toDouble())',
    'public constructor() : this(kotlin.system.getTimeMillis().toDouble())')
sub('''        val instant = java.time.Instant.ofEpochMilli(millis.toLong())
        return java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(instant)''',
    '        return formatIso(millis.toLong())')
sub('''        fun parseIso(text: String): Double = try {
            when {
                text.isEmpty() -> Double.NaN
                text.endsWith("Z") || Regex("[+-]\\\\d\\\\d:?\\\\d\\\\d$").containsMatchIn(text) ->
                    java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli().toDouble()
                text.contains('T') ->
                    java.time.LocalDateTime.parse(text)
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli().toDouble()
                else ->
                    java.time.LocalDate.parse(text)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                        .toEpochMilli().toDouble()
            }
        } catch (_: java.time.format.DateTimeParseException) {
            Double.NaN
        }''',
    '''fun parseIso(text: String): Double = parseIsoCivil(text)''')

CIVIL = r'''
// ---------------------------------------------------------------------------
// Civil-date arithmetic
//
// The JVM runtime gets ISO parsing and formatting from `java.time`; Kotlin/Native
// has no equivalent in the standard library, so both directions are Howard
// Hinnant's days-from-civil / civil-from-days, which are exact for every
// proleptic-Gregorian date and involve no calendar object at all.
// ---------------------------------------------------------------------------

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = ((month + 9) % 12).toLong()
    val doy = (153 * mp + 2) / 5 + (day - 1).toLong()
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
}

/**
 * The broken-down calendar fields [JsDate.local] answers, in UTC.
 *
 * A data holder rather than a calendar object: Kotlin/Native has no
 * `java.time`, and everything the getters need is already computed by
 * [civilFromDays] plus three divisions.
 */
private class CivilTime(
    val year: Int,
    val month: Int,
    val day: Int,
    /** `0` is Sunday, as JavaScript numbers the week. */
    val weekday: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

private fun formatIso(millis: Long): String {
    var days = millis / 86_400_000L
    var rest = millis % 86_400_000L
    if (rest < 0) { rest += 86_400_000L; days -= 1 }
    val (year, month, day) = civilFromDays(days)
    val ms = (rest % 1000L).toInt()
    val seconds = rest / 1000L
    return pad(year, 4) + "-" + pad(month, 2) + "-" + pad(day, 2) + "T" +
        pad((seconds / 3600L).toInt(), 2) + ":" + pad(((seconds / 60L) % 60L).toInt(), 2) +
        ":" + pad((seconds % 60L).toInt(), 2) + "." + pad(ms, 3) + "Z"
}

/**
 * The subset `java.time` accepts that this runtime does, hand-parsed:
 * `YYYY-MM-DD`, optionally `THH:MM[:SS[.fff]]`, optionally `Z` or `+HH:MM`.
 * Anything else is `NaN` -- a value a program can test, where a throw would be
 * one it cannot.
 */
private fun parseIsoCivil(text: String): Double {
    if (text.length < 10) return Double.NaN
    val year = text.substring(0, 4).toIntOrNull() ?: return Double.NaN
    if (text[4] != '-' || text[7] != '-') return Double.NaN
    val month = text.substring(5, 7).toIntOrNull() ?: return Double.NaN
    val day = text.substring(8, 10).toIntOrNull() ?: return Double.NaN
    if (month !in 1..12 || day !in 1..31) return Double.NaN
    var millis = daysFromCivil(year, month, day) * 86_400_000L
    if (text.length == 10) return millis.toDouble()
    if (text[10] != 'T' && text[10] != ' ') return Double.NaN
    var i = 11
    if (text.length < i + 5 || text[i + 2] != ':') return Double.NaN
    val hour = text.substring(i, i + 2).toIntOrNull() ?: return Double.NaN
    val minute = text.substring(i + 3, i + 5).toIntOrNull() ?: return Double.NaN
    millis += hour * 3_600_000L + minute * 60_000L
    i += 5
    if (i < text.length && text[i] == ':') {
        val second = text.substring(i + 1, i + 3).toIntOrNull() ?: return Double.NaN
        millis += second * 1000L
        i += 3
        if (i < text.length && text[i] == '.') {
            var end = i + 1
            while (end < text.length && text[end].isDigit()) end++
            val fraction = text.substring(i + 1, end)
            millis += (fraction + "000").substring(0, 3).toInt().toLong()
            i = end
        }
    }
    if (i == text.length) return millis.toDouble()
    if (text[i] == 'Z' && i == text.length - 1) return millis.toDouble()
    val sign = when (text[i]) { '+' -> -1L; '-' -> 1L; else -> return Double.NaN }
    val offset = text.substring(i + 1).replace(":", "")
    if (offset.length != 4) return Double.NaN
    val offsetHour = offset.substring(0, 2).toIntOrNull() ?: return Double.NaN
    val offsetMinute = offset.substring(2, 4).toIntOrNull() ?: return Double.NaN
    return (millis + sign * (offsetHour * 3_600_000L + offsetMinute * 60_000L)).toDouble()
}
'''

# ---- (3) RegExp: java.util.regex -> kotlin.text.Regex -----------------------
sub('''    private val pattern: java.util.regex.Pattern = compiledPattern(source, flags)''',
    '''    private val regex: Regex = compiledRegex(source, flags)''')
# `test` itself is platform-neutral -- it drives the DFA matcher, which is pure
# Kotlin and is copied verbatim. Only the ORACLE underneath it is replaced, and
# it is worth more here than on the JVM: `kotlin.text.Regex` is 5.2x
# `java.util.regex` on the same pattern (levers doc, section 6).
sub('''    private var reusable: java.util.regex.Matcher? = null

    private fun matcher(input: String): java.util.regex.Matcher {
        val existing = reusable
        if (existing != null) return existing.reset(input)
        val fresh = pattern.matcher(input)
        reusable = fresh
        return fresh
    }

    /** The REFERENCE engine's answer, and the differential oracle's. */
    private fun oracleTest(input: String): Boolean = matcher(input).find()''',
    '''    /** The REFERENCE engine's answer, and the differential oracle's. */
    private fun oracleTest(input: String): Boolean = regex.containsMatchIn(input)''')
sub('''    public fun exec(input: String): JsArray? {
        val matcher = matcher(input)
        if (!matcher.find()) return null
        val groups = ArrayList<Any?>(matcher.groupCount() + 1)
        for (index in 0..matcher.groupCount()) groups.add(matcher.group(index))
        return JsArray(groups)
    }

    internal fun matcherFor(input: String): java.util.regex.Matcher = pattern.matcher(input)''',
    '''    public fun exec(input: String): JsArray? {
        val match = regex.find(input) ?: return null
        val groups = ArrayList<Any?>(match.groupValues.size)
        for (index in match.groupValues.indices) groups.add(match.groups[index]?.value)
        return JsArray(groups)
    }

    /** `String.prototype.replace`, with [replacement] taken LITERALLY as JavaScript does. */
    internal fun replaceIn(input: String, replacement: String): String =
        if (global) regex.replace(input) { replacement }
        else {
            val match = regex.find(input)
            if (match == null) input
            else input.substring(0, match.range.first) + replacement +
                input.substring(match.range.last + 1)
        }

    /**
     * `String.prototype.replace` with a REPLACER FUNCTION.
     *
     * [build] is handed the match and its capture groups (index 0 is the whole
     * match, exactly as `Matcher.group` numbers them) together with the offset,
     * which is what the JVM form reads off its `Matcher`.
     */
    internal fun replaceInBy(
        input: String,
        build: (List<Any?>, Int) -> String
    ): String {
        val out = StringBuilder()
        var last = 0
        var match = regex.find(input)
        while (match != null) {
            out.append(input, last, match.range.first)
            val groups = ArrayList<Any?>(match.groupValues.size)
            for (index in match.groupValues.indices) groups.add(match.groups[index]?.value)
            out.append(build(groups, match.range.first))
            last = if (match.range.isEmpty()) match.range.first else match.range.last + 1
            if (!global) break
            match = match.next()
        }
        out.append(input, last, input.length)
        return out.toString()
    }''')
# The fast matcher's own cache: pure Kotlin apart from the concurrent map.
sub('''private val compiledRegexPrograms =
    java.util.concurrent.ConcurrentHashMap<String, Any>()''',
    '''private val compiledRegexPrograms = HashMap<String, Any>()''')

sub('''private val compiledPatterns =
    java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern>()''',
    '''private val compiledPatterns = HashMap<String, Regex>()''')
# The `$`-anchor translation is deliberately NOT carried over: it is a fix to
# the JVM ORACLE's `$`, and Kotlin/Native's own regex engine is not known here
# to accept `\z`. Native's fallback therefore keeps the pre-existing meaning,
# while its fast matcher -- which handles `$` structurally -- is already right.
sub('''private fun compiledPattern(source: String, flags: String): java.util.regex.Pattern =
    compiledPatterns.computeIfAbsent("$flags\\u0000$source") {
        java.util.regex.Pattern.compile(
            jsEndAnchorTranslated(source, flags),
            (if ('i' in flags) java.util.regex.Pattern.CASE_INSENSITIVE else 0) or
                (if ('m' in flags) java.util.regex.Pattern.MULTILINE else 0) or
                (if ('s' in flags) java.util.regex.Pattern.DOTALL else 0)
        )
    }''',
    '''private fun compiledRegex(source: String, flags: String): Regex =
    compiledPatterns.getOrPut("$flags\\u0000$source") {
        val options = mutableSetOf<RegexOption>()
        if ('i' in flags) options.add(RegexOption.IGNORE_CASE)
        if ('m' in flags) options.add(RegexOption.MULTILINE)
        if ('s' in flags) options.add(RegexOption.DOT_MATCHES_ALL)
        Regex(source, options)
    }''')
# `splitOf` is the one member the exec replacement above does not carry, and it
# is the same limit either way: Kotlin's `Regex.split` keeps a trailing empty
# field, which is Java's `split(input, -1)` and JavaScript's own answer.
sub('''    internal fun splitOf(input: String): List<Any?> =
        pattern.split(input, -1).toList()''',
    '''    internal fun splitOf(input: String): List<Any?> = regex.split(input)''')

sub('''    // `Matcher.quoteReplacement`, because JavaScript's replacement string has
    // its own escape language (`$1`, `$&`) and Java's is a DIFFERENT one — so a
    // replacement containing `$` or `\\` would otherwise mean something else.
    val quoted = java.util.regex.Matcher.quoteReplacement(replacement)
    val matcher = expression.matcherFor(value)
    return if (expression.global) matcher.replaceAll(quoted) else matcher.replaceFirst(quoted)''',
    '''    return expression.replaceIn(value, replacement)''')


src = src.rstrip() + "\n" + CIVIL




sub("public constructor() : this(kotlin.system.getTimeMillis().toDouble())",
    "public constructor() : this(nowMillis())")

# ---- Date COMPONENTS: java.time -> the civil arithmetic already here --------
#
# `new Date(y, m, …)` and the `getFullYear`/`getMonth`/… family are LOCAL time in
# JavaScript, and the JVM runtime honours that through `ZoneId.systemDefault()`.
# Kotlin/Native's standard library has NO TIMEZONE DATABASE, so the native side
# computes them in UTC.
#
# THAT IS A STATED DIVERGENCE, not an oversight: the two runtimes agree exactly
# when the host is UTC and differ by the host's offset otherwise. It is recorded
# here, in the generated source, and in `docs/perf/kir-backend-levers.md`. The
# alternative — refusing on native, as the reflect fallback does — was rejected
# because the shape that reaches this is a YEAR round trip
# (`new Date(parseInt(s), 1).getFullYear()`, cronstrue), which no offset moves.
sub("""    private fun local(): java.time.LocalDateTime? =
        if (millis.isNaN()) null else java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(millis.toLong()),
            java.time.ZoneId.systemDefault()
        )""",
    """    /**
     * The calendar fields, in UTC — Kotlin/Native has no timezone database.
     *
     * The JVM runtime uses the host's zone here, as JavaScript does, so the two
     * agree when the host is UTC and differ by its offset otherwise.
     */
    private fun local(): CivilTime? {
        if (millis.isNaN()) return null
        var days = millis.toLong() / 86_400_000L
        var rest = millis.toLong() % 86_400_000L
        if (rest < 0) { rest += 86_400_000L; days -= 1 }
        val (year, month, day) = civilFromDays(days)
        val seconds = rest / 1000L
        return CivilTime(
            year, month, day,
            ((days % 7 + 11) % 7).toInt(),
            (seconds / 3600L).toInt(),
            ((seconds / 60L) % 60L).toInt(),
            (seconds % 60L).toInt()
        )
    }""")

sub("    public fun getFullYear(): Double = local()?.year?.toDouble() ?: Double.NaN",
    "    public fun getFullYear(): Double = local()?.year?.toDouble() ?: Double.NaN")
sub("    public fun getMonth(): Double = local()?.monthValue?.minus(1)?.toDouble() ?: Double.NaN",
    "    public fun getMonth(): Double = local()?.month?.minus(1)?.toDouble() ?: Double.NaN")
sub("    public fun getDate(): Double = local()?.dayOfMonth?.toDouble() ?: Double.NaN",
    "    public fun getDate(): Double = local()?.day?.toDouble() ?: Double.NaN")
sub("""    public fun getDay(): Double =
        local()?.dayOfWeek?.value?.rem(7)?.toDouble() ?: Double.NaN""",
    """    public fun getDay(): Double = local()?.weekday?.toDouble() ?: Double.NaN""")
sub("    public fun getHours(): Double = local()?.hour?.toDouble() ?: Double.NaN",
    "    public fun getHours(): Double = local()?.hour?.toDouble() ?: Double.NaN")
sub("    public fun getMinutes(): Double = local()?.minute?.toDouble() ?: Double.NaN",
    "    public fun getMinutes(): Double = local()?.minute?.toDouble() ?: Double.NaN")
sub("    public fun getSeconds(): Double = local()?.second?.toDouble() ?: Double.NaN",
    "    public fun getSeconds(): Double = local()?.second?.toDouble() ?: Double.NaN")

sub("""            return try {
                // The components are ADDED rather than passed to a constructor,
                // so an out-of-range one rolls over as JavaScript's do —
                // `new Date(2020, 12)` is January 2021, not an error.
                java.time.LocalDateTime.of(fullYear, 1, 1, 0, 0, 0)
                    .plusMonths(month.toLong())
                    .plusDays(day.toLong() - 1)
                    .plusHours(hour.toLong())
                    .plusMinutes(minute.toLong())
                    .plusSeconds(second.toLong())
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                    .toDouble() + millisecond
            } catch (_: Exception) {
                Double.NaN
            }""",
    """            // The month is ADDED rather than passed to a constructor, so an
            // out-of-range one rolls over as JavaScript's does — `new Date(2020,
            // 12)` is January 2021, not an error. UTC, see `local()`.
            val totalMonths = fullYear.toLong() * 12L + month.toLong()
            val y = totalMonths.floorDiv(12L).toInt()
            val m = totalMonths.mod(12L).toInt() + 1
            val days = daysFromCivil(y, m, 1) + (day.toLong() - 1L)
            return (days * 86_400_000L +
                hour.toLong() * 3_600_000L +
                minute.toLong() * 60_000L +
                second.toLong() * 1000L).toDouble() + millisecond""")

# ---- locale-sensitive case mapping -----------------------------------------
#
# Kotlin/Native has no locale database, so `toLocaleUpperCase` maps exactly as
# `toUpperCase` does. Every JavaScript engine without ICU behaves the same way,
# and the difference is confined to the handful of languages whose case mapping
# is not the default one (Turkish `i` above all).
sub("""public fun jsStrToLocaleUpperCase(value: String): String =
    value.uppercase(java.util.Locale.getDefault())

public fun jsStrToLocaleLowerCase(value: String): String =
    value.lowercase(java.util.Locale.getDefault())""",
    """public fun jsStrToLocaleUpperCase(value: String): String = value.uppercase()

public fun jsStrToLocaleLowerCase(value: String): String = value.lowercase()""")

# ---- stderr ----------------------------------------------------------------
sub("""public fun consoleError(vararg values: Any?) {
    System.err.println(values.joinToString(" ") { jsToString(it) })
}""",
    """public fun consoleError(vararg values: Any?) {
    printErr(values.joinToString(" ") { jsToString(it) })
}""")

# ---- the REPLACER-callback overload ----------------------------------------
#
# The JVM form walks a `java.util.regex.Matcher` for the groups and the offset;
# the native side has `kotlin.text.Regex`, whose `replace` hands the same three
# things to a lambda.
sub("""public fun jsStrReplace(value: String, expression: JsRegExp, replacer: Any?): String {
    val matcher = expression.matcherFor(value)
    val out = StringBuilder()
    var last = 0
    while (matcher.find()) {
        out.append(value, last, matcher.start())
        val arguments = ArrayList<Any?>(matcher.groupCount() + 3)
        arguments.add(matcher.group())
        for (group in 1..matcher.groupCount()) arguments.add(matcher.group(group))
        // The offset is a NUMBER, i.e. a `Double` here — a replacer that
        // returns it would otherwise print an `Int`, which JavaScript has not.
        arguments.add(matcher.start().toDouble())
        arguments.add(value)
        out.append(jsToString(jsCall(replacer, *arguments.toTypedArray())))
        last = matcher.end()
        // Without `g` only the FIRST match is replaced, which is the whole
        // difference the flag makes.
        if (!expression.global) break
    }
    out.append(value, last, value.length)
    return out.toString()
}""",
    """public fun jsStrReplace(value: String, expression: JsRegExp, replacer: Any?): String =
    expression.replaceInBy(value) { groups, offset ->
        val arguments = ArrayList<Any?>(groups.size + 2)
        for (group in groups) arguments.add(group)
        // The offset is a NUMBER, i.e. a `Double` here — a replacer that returns
        // it would otherwise print an `Int`, which JavaScript has not.
        arguments.add(offset.toDouble())
        arguments.add(value)
        jsToString(jsCall(replacer, *arguments.toTypedArray()))
    }""")


# The JVM side takes `Locale.ROOT` deliberately (a locale-sensitive `toFixed`
# answered "2,0" on a comma-locale box); `formatFixed` is locale-free by
# construction, so the native side needs no equivalent — but the ANCHOR has to
# carry the locale argument or it stops matching, which is what this assertion
# is for.
sub('    else String.format(java.util.Locale.ROOT, "%.${digits.toInt()}f", value)',
    "    else formatFixed(value, digits.toInt())")

sub("""            else -> if (ch < ' ') out.append("\\\\u%04x".format(ch.code)) else out.append(ch)""",
    """            else -> if (ch < ' ') out.append("\\\\u" + hex4(ch.code)) else out.append(ch)""")

sub("    while (index < text.length && Character.digit(text[index], base) >= 0) {",
    "    while (index < text.length && digitValue(text[index], base) >= 0) {")

sub("""public fun jsStrFromCodePoint(code: Any?): String {
    val builder = StringBuilder()
    builder.appendCodePoint(jsToNumber(code).toInt())
    return builder.toString()
}""",
    "public fun jsStrFromCodePoint(code: Any?): String = fromCodePoint(jsToNumber(code).toInt())")

sub("    return if (i < 0 || i >= value.length) null else value.codePointAt(i).toDouble()",
    "    return if (i < 0 || i >= value.length) null else codePointAt(value, i).toDouble()")

REFLECTIVE_OLD = """private fun reflectiveGet(receiver: Any, name: String): Any? {
    receiver.javaClass.fields.firstOrNull { it.name == name }?.let { return it.get(receiver) }
    receiver.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        ?.let { return it.invoke(receiver) }
    throw JsTypeError("'$name' is not a member of ${receiver.javaClass.simpleName}")
}

private fun reflectiveSet(receiver: Any, name: String, value: Any?) {
    receiver.javaClass.fields.firstOrNull { it.name == name }?.let {
        it.set(receiver, value)
        return
    }
    throw JsTypeError("'$name' is not a settable member of ${receiver.javaClass.simpleName}")
}

private fun reflectiveInvoke(receiver: Any, name: String, arguments: Array<out Any?>): Any? {
    val method = receiver.javaClass.methods.firstOrNull {
        it.name == name && it.parameterCount == arguments.size
    } ?: throw JsTypeError("'$name' is not a member of ${receiver.javaClass.simpleName}")
    return method.invoke(receiver, *arguments)
}"""

REFLECTIVE_NEW = """// REFUSED, not approximated. The JVM runtime reaches a generated class member
// dynamically through java.lang.reflect; Kotlin/Native has no such facility, and
// answering `undefined` would turn a missing member into a silently wrong value.
private fun reflectiveGet(receiver: Any, name: String): Any? =
    throw JsTypeError("dynamic member read '$name' is not supported on Kotlin/Native")

private fun reflectiveSet(receiver: Any, name: String, value: Any?): Unit =
    throw JsTypeError("dynamic member write '$name' is not supported on Kotlin/Native")

private fun reflectiveInvoke(receiver: Any, name: String, arguments: Array<out Any?>): Any? =
    throw JsTypeError("dynamic member call '$name' is not supported on Kotlin/Native")"""

sub(REFLECTIVE_OLD, REFLECTIVE_NEW)

HELPERS = r'''
// ---------------------------------------------------------------------------
// What the JVM runtime gets from java.lang / java.util and Kotlin/Native does not.
// ---------------------------------------------------------------------------

/**
 * `console.warn` / `console.error`'s stream.
 *
 * Kotlin/Native has no `System.err`, so stderr is libc's. Writing these to
 * STDOUT instead would be the one thing that must not happen: a library that
 * warns would then corrupt the output of any program whose answer is piped,
 * which is the whole reason the JVM side routes them away from stdout.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun printErr(line: String) {
    platform.posix.fprintf(platform.posix.stderr, "%s\n", line)
    platform.posix.fflush(platform.posix.stderr)
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun nowMillis(): Double =
    kotlin.time.Clock.System.now().toEpochMilliseconds().toDouble()

private fun hex4(value: Int): String {
    val digits = "0123456789abcdef"
    return charArrayOf(
        digits[(value shr 12) and 0xF], digits[(value shr 8) and 0xF],
        digits[(value shr 4) and 0xF], digits[value and 0xF]
    ).concatToString()
}

/** `Character.digit`: the value of [ch] in [base], or -1. */
private fun digitValue(ch: Char, base: Int): Int {
    val value = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'z' -> ch - 'a' + 10
        in 'A'..'Z' -> ch - 'A' + 10
        else -> return -1
    }
    return if (value < base) value else -1
}

private fun fromCodePoint(codePoint: Int): String =
    if (codePoint <= 0xFFFF) codePoint.toChar().toString()
    else {
        val rest = codePoint - 0x10000
        charArrayOf(
            (0xD800 + (rest shr 10)).toChar(),
            (0xDC00 + (rest and 0x3FF)).toChar()
        ).concatToString()
    }

private fun codePointAt(value: String, index: Int): Int {
    val high = value[index]
    if (high.isHighSurrogate() && index + 1 < value.length) {
        val low = value[index + 1]
        if (low.isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
    }
    return high.code
}

/**
 * `Number.prototype.toFixed`, which the JVM runtime gets from `String.format`.
 *
 * Scales, rounds half-away-from-zero and re-inserts the point. A magnitude at or
 * past 1e15 has no exact `Long` scaling, so it degrades to the ordinary number
 * formatting rather than printing a wrong digit.
 */
private fun formatFixed(value: Double, digits: Int): String {
    val magnitude = kotlin.math.abs(value)
    if (magnitude >= 1e15) return jsNumberToString(value)
    var scale = 1L
    repeat(digits) { scale *= 10L }
    // TIES AWAY FROM ZERO, spelled out rather than left to `kotlin.math.round`,
    // whose tie behaviour is not the same on every target: `(0.5).toFixed(0)`
    // answered "0" natively against "1" on Node and on the JVM, which is what
    // ECMAScript requires — of the two integers equally close, `toFixed` takes
    // the LARGER. The magnitude is already absolute, so larger IS away from zero.
    val exact = magnitude * scale
    val floor = kotlin.math.floor(exact)
    val scaled = (if (exact - floor >= 0.5) floor + 1.0 else floor).toLong()
    val whole = (scaled / scale).toString()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    if (digits == 0) return sign + whole
    val fraction = (scaled % scale).toString().padStart(digits, '0')
    return sign + whole + "." + fraction
}
'''

out.write_text(src.rstrip() + "\n" + HELPERS)
print("wrote %s (%d lines)" % (out, len(src.splitlines())))

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
 * (LEGACY.0b) F6a — the ONE place TypeScript 7's missing-property HEAD SUPPRESSION
 * is decided; since (P18.101) M4 also the readonly-vs-mutable / excessive-complexity one
 * (see [parseTwoArgLeaf]).
 *
 * tsgo's `Relater.reportRelationError` (`internal/checker/relater.go` ~4809) does not
 * report the outer *not assignable* head at all when the error chain's innermost entry
 * is one of the three missing-property messages **about the same two types**:
 *
 * ```go
 * case diagnostics.Property_0_is_missing_in_type_1_but_required_in_type_2:
 *     if !isConversionOrInterfaceImplementationMessage(message) &&
 *         r.chainArgsMatch(nil, generalizedSourceType, targetType) { return }
 * ```
 *
 * so `foo({ name: "hello" })` against `{ id: number; name?: string }` is a bare
 * `TS2741: Property 'id' is missing in type '{ name: string; }' but required in type
 * '{ id: number; name?: string | undefined; }'.` rather than a `TS2345` head carrying
 * that sentence as its first chain line.
 *
 * ### Why this is a post-hoc rewrite rather than a parameter of every emitter
 *
 * The message is produced at **61 distinct sites** of `Checker.kt` reached by the corpus
 * (runtime census, 2026-09-13) and this compiler has no relation-error funnel to host a
 * per-site decision. It does not need one: **tsgo's own condition is a comparison of
 * RENDERED STRINGS** — `chainArgsMatch` compares the head's `generalizedSourceType` /
 * `targetType` (both `string`) against the chain entry's own arguments — so deciding it
 * from the finished [Diagnostic] is not an approximation of tsgo's rule, it *is* tsgo's
 * rule. Every checker diagnostic leaves through [Checker.getDiagnostics], which is
 * therefore the one place that sees the whole population.
 *
 * A head this file cannot parse is left alone, so an unrecognised shape keeps today's
 * answer (fail-closed).
 *
 * ### The mirror direction
 *
 * A site that *pre-suppresses* — emitting the missing-property sentence as its own head
 * having thrown the outer one away — cannot be corrected from here, because the head it
 * dropped is gone. Such a site must emit the un-suppressed pair and let this decide;
 * `Checker.caeMissingPropertyHead` is the shipped instance, and it is what makes
 * `classImplementsClass4` / `inheritance1` (where the missing property is declared on a
 * *base* of the target, so the two renderings differ) keep their `TS2322` head as
 * TypeScript 7 does.
 */
internal object RelationHeadSuppression {

    /**
     * `Property '{0}' is missing in type '{1}' but required in type '{2}'.` — the chain
     * entry whose presence triggers the suppression, and the head that replaces it.
     */
    private const val MISSING_PROPERTY_CODE = 2741

    /** `Type '{0}' is missing the following properties from type '{1}': {2}` */
    private const val MISSING_PROPERTIES_CODE = 2739

    /** `Type '{0}' is missing the following properties from type '{1}': {2}, and {3} more.` */
    private const val MISSING_PROPERTIES_AND_MORE_CODE = 2740

    /** `The type '{0}' is 'readonly' and cannot be assigned to the mutable type '{1}'.` */
    private const val READONLY_TO_MUTABLE_CODE = 4104

    /** `Excessive complexity comparing types '{0}' and '{1}'.` */
    private const val EXCESSIVE_COMPLEXITY_CODE = 2859

    /** `Excessive stack depth comparing types '{0}' and '{1}'.` */
    private const val EXCESSIVE_STACK_DEPTH_CODE = 2321

    /**
     * Applies the rule to a whole diagnostic list.
     *
     * Cheap for the overwhelming majority of diagnostics: a diagnostic with no message
     * chain, or whose first chain entry does not begin `Property '` / `Type '`, is
     * returned untouched without any parsing.
     */
    fun apply(diagnostics: List<Diagnostic>): List<Diagnostic> {
        var changed = false
        val out = ArrayList<Diagnostic>(diagnostics.size)
        for (d in diagnostics) {
            val s = suppressHead(d)
            if (s !== d) changed = true
            out.add(s)
        }
        return if (changed) out else diagnostics
    }

    /**
     * tsgo's three-part condition, in its own order.
     *
     * Returns [d] itself whenever any conjunct fails — including when the head is a
     * shape this file does not know, which is the fail-closed direction: an unparsed
     * head keeps today's answer rather than losing one.
     */
    fun suppressHead(d: Diagnostic): Diagnostic {
        val rawFirst = d.messageChain.firstOrNull() ?: return d
        val first = rawFirst.trimStart()
        // (1) `getChainMessage(0)` is one of the three missing-property messages, or one of
        //     the three two-argument messages of the readonly / excessive-complexity case.
        val leaf = parseMissingProperty(first) ?: parseTwoArgLeaf(first) ?: return d
        val head = parseRelationHead(d.message) ?: return d
        // (2) `!isConversionOrInterfaceImplementationMessage(message)` — a conjunct of the
        //     missing-property case ONLY; the readonly case has no such exclusion.
        if (leaf.missingProperty && head.conversionOrInterfaceImplementation) return d
        // (3) `chainArgsMatch(nil, generalizedSourceType, targetType)` — the property
        //     name is a wildcard, the two TYPE displays are compared; for the two-argument
        //     leaves it is `chainArgsMatch(generalizedSourceType, targetType)`, both compared.
        if (head.source != leaf.source || head.target != leaf.target) return d
        val indent = rawFirst.length - first.length
        return d.copy(
            message = first,
            code = leaf.code,
            messageChain = d.messageChain.drop(1).map { it.deIndent(indent) },
        )
    }

    private fun String.deIndent(by: Int): String {
        var n = 0
        while (n < by && n < length && this[n] == ' ') n++
        return substring(n)
    }

    /**
     * (P18.135) — the OTHER branch of the same `chainArgsMatch` condition, asked while the
     * relation is still BUILDING its chain rather than of a finished head.
     *
     * tsgo reaches a nested generic instantiation through `typeArgumentsRelatedTo`, and the
     * failing argument pair's own `isRelatedToEx` frame ends in `reportErrorResults` →
     * [reportRelationError], which pushes a `Type '<srcArg>' is not assignable to type
     * '<tgtArg>'.` entry UNLESS the entry already on the chain is a missing-property sentence
     * **about that very pair**. So `Inv<Small>` vs `Inv<Big>` prints the sentence alone while
     * `Inv<Inv<Small>>` vs `Inv<Inv<Big>>` prints an intermediate header for `Inv<Small>` /
     * `Inv<Big>` above it — one header per nesting level, and none at the innermost one.
     *
     * [suppressHead] cannot decide this: the header it would have to weigh was never produced,
     * and the two type displays it compares are not recoverable from the finished strings. The
     * decision therefore lives at the one site that still holds the pair — the same-target
     * generic-reference branch of `Checker.getPropertyElaborationChain` — and asks this, so the
     * emitting and the suppressing halves of tsgo's one condition cannot drift apart.
     *
     * Returns false for any chain entry that is not one of the three missing-property messages,
     * which is the EMITTING direction: an unrecognised entry gets its header, as tsgo's `switch`
     * (whose every other arm falls through to `reportError`) does.
     */
    fun leafNamesTypePair(chainEntry: String, source: String, target: String): Boolean {
        val leaf = parseMissingProperty(chainEntry.trimStart()) ?: return false
        return leaf.source == source && leaf.target == target
    }

    /** The two type displays a relation-error head carries, and whether it is excluded. */
    private class Head(
        val source: String,
        val target: String,
        val conversionOrInterfaceImplementation: Boolean,
    )

    /** `Property '{0}' …` / `Type '{0}' …` / `The type '{0}' is 'readonly' …`: the two type
     *  displays plus the code; [missingProperty] says which of tsgo's two `case` arms it is. */
    private class Leaf(val source: String, val target: String, val code: Int, val missingProperty: Boolean = true)

    /**
     * Every head this compiler emits with a missing-property first chain entry, measured
     * over the whole corpus (2026-09-13): TS2322, TS2344, TS2345, TS2352, TS2416, TS2420,
     * TS2696, TS2720 and TS2328. The ones that carry a `(source, target)` pair are listed
     * here; a head not listed (TS2416's *Property 'p' in type …*, TS2328's *Types of
     * parameters …*, TS2696's *The 'Object' type …*) has no pair to compare and keeps its
     * head, which is what tsgo does with them too.
     *
     * The last three entries are `isConversionOrInterfaceImplementationMessage`'s own
     * members that have a type pair. They are parsed rather than ignored ON PURPOSE: tsgo
     * computes their arguments and then excludes them by MESSAGE, so the exclusion is a
     * real conjunct — `noImplicitAnyInCastExpression`, `bases`, `classImplementsClass2`
     * and 20 more active baselines carry a head whose pair DOES match its chain entry and
     * which TypeScript 7 nevertheless keeps.
     */
    private fun parseRelationHead(message: String): Head? {
        pair(message, "Argument of type '", "' is not assignable to parameter of type '", "'.")
            ?.let { return Head(it.first, it.second, false) }
        pair(
            message, "Argument of type '", "' is not assignable to parameter of type '",
            "' with 'exactOptionalPropertyTypes: true'. Consider adding 'undefined' to the " +
                "types of the target's properties.",
        )?.let { return Head(it.first, it.second, false) }
        pair(message, "Type '", "' is not assignable to type '", "'.")
            ?.let { return Head(it.first, it.second, false) }
        pair(
            message, "Type '", "' is not assignable to type '",
            "'. Two different types with this name exist, but they are unrelated.",
        )?.let { return Head(it.first, it.second, false) }
        pair(
            message, "Type '", "' is not assignable to type '",
            "' with 'exactOptionalPropertyTypes: true'. Consider adding 'undefined' to the " +
                "types of the target's properties.",
        )?.let { return Head(it.first, it.second, false) }
        pair(message, "Type '", "' does not satisfy the constraint '", "'.")
            ?.let { return Head(it.first, it.second, false) }
        pair(message, "Type '", "' is not comparable to type '", "'.")
            ?.let { return Head(it.first, it.second, false) }
        pair(
            message, "Conversion of type '", "' to type '",
            "' may be a mistake because neither type sufficiently overlaps with the other. " +
                "If this was intentional, convert the expression to 'unknown' first.",
        )?.let { return Head(it.first, it.second, true) }
        pair(message, "Class '", "' incorrectly implements interface '", "'.")
            ?.let { return Head(it.first, it.second, true) }
        conversionClassPair(message)?.let { return Head(it.first, it.second, true) }
        return null
    }

    /**
     * `Class '{0}' incorrectly implements class '{1}'. Did you mean to extend '{1}' and
     * inherit its members as a subclass?` — the only head whose target display appears
     * TWICE, so it cannot be matched by a single prefix/infix/suffix split.
     */
    private fun conversionClassPair(message: String): Pair<String, String>? {
        val p = "Class '"
        val infix = "' incorrectly implements class '"
        if (!message.startsWith(p)) return null
        val i = message.indexOf(infix)
        if (i < 0) return null
        val source = message.substring(p.length, i)
        val rest = message.substring(i + infix.length)
        val j = rest.indexOf("'. Did you mean to extend '")
        if (j < 0) return null
        return source to rest.substring(0, j)
    }

    /**
     * `Property '{0}' is missing in type '{1}' but required in type '{2}'.` (TS2741) and
     * `Type '{0}' is missing the following properties from type '{1}': {2}` (TS2739, or
     * TS2740 when the list ends `, and {3} more.`).
     */
    private fun parseMissingProperty(chainEntry: String): Leaf? {
        if (chainEntry.startsWith("Property '")) {
            val infix = "' is missing in type '"
            val i = chainEntry.indexOf(infix)
            if (i < 0) return null
            val rest = chainEntry.substring(i + infix.length)
            val p = pairOf(rest, "' but required in type '", "'.") ?: return null
            return Leaf(p.first, p.second, MISSING_PROPERTY_CODE)
        }
        if (chainEntry.startsWith("Type '")) {
            val infix = "' is missing the following properties from type '"
            val i = chainEntry.indexOf(infix)
            if (i < 0) return null
            val source = chainEntry.substring("Type '".length, i)
            val rest = chainEntry.substring(i + infix.length)
            val j = rest.indexOf("': ")
            if (j < 0) return null
            val target = rest.substring(0, j)
            val listed = rest.substring(j + 3)
            val code = if (listed.endsWith(" more.") && ", and " in listed)
                MISSING_PROPERTIES_AND_MORE_CODE else MISSING_PROPERTIES_CODE
            return Leaf(source, target, code)
        }
        return null
    }

    /**
     * (P18.101) M4 — tsgo's OTHER suppressing `case`, `relater.go` ~4798:
     *
     * ```go
     * case diagnostics.Excessive_complexity_comparing_types_0_and_1,
     *     diagnostics.Excessive_stack_depth_comparing_types_0_and_1,
     *     diagnostics.The_type_0_is_readonly_and_cannot_be_assigned_to_the_mutable_type_1:
     *     if r.chainArgsMatch(generalizedSourceType, targetType) { return }
     * ```
     *
     * `The type '{0}' is 'readonly' and cannot be assigned to the mutable type '{1}'.` (TS4104),
     * `Excessive complexity comparing types '{0}' and '{1}'.` (TS2859) and `Excessive stack depth
     * comparing types '{0}' and '{1}'.` (TS2321). Both arguments are compared and there is no
     * conversion/interface-implementation exclusion — so `arryFn(point)` with a `readonly [3, 4]`
     * argument is a bare TS4104 head (`readonlyTupleAndArrayElaboration`), while `variadicTuples1`'s
     * `Type 'T' is not assignable to type '[...T]'.` keeps its head over `The type 'readonly
     * unknown[]' …` because the generalized source (`T`) is not the chain entry's (`readonly unknown[]`).
     */
    private fun parseTwoArgLeaf(chainEntry: String): Leaf? {
        pair(chainEntry, "The type '", "' is 'readonly' and cannot be assigned to the mutable type '", "'.")
            ?.let { return Leaf(it.first, it.second, READONLY_TO_MUTABLE_CODE, missingProperty = false) }
        pair(chainEntry, "Excessive complexity comparing types '", "' and '", "'.")
            ?.let { return Leaf(it.first, it.second, EXCESSIVE_COMPLEXITY_CODE, missingProperty = false) }
        pair(chainEntry, "Excessive stack depth comparing types '", "' and '", "'.")
            ?.let { return Leaf(it.first, it.second, EXCESSIVE_STACK_DEPTH_CODE, missingProperty = false) }
        return null
    }

    /** Splits `<prefix>A<infix>B<suffix>` into `A to B`, or null when the shape differs. */
    private fun pair(s: String, prefix: String, infix: String, suffix: String): Pair<String, String>? {
        if (!s.startsWith(prefix)) return null
        return pairOf(s.substring(prefix.length), infix, suffix)
    }

    /** Splits `A<infix>B<suffix>` into `A to B`, or null when the shape differs. */
    private fun pairOf(s: String, infix: String, suffix: String): Pair<String, String>? {
        if (!s.endsWith(suffix)) return null
        val body = s.substring(0, s.length - suffix.length)
        val i = body.indexOf(infix)
        if (i < 0) return null
        return body.substring(0, i) to body.substring(i + infix.length)
    }
}

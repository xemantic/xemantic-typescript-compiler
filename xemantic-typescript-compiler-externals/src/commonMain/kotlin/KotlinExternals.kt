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

package com.xemantic.typescript.compiler.externals

import com.xemantic.typescript.compiler.Binder
import com.xemantic.typescript.compiler.CheckedLens
import com.xemantic.typescript.compiler.CheckedNodeSink
import com.xemantic.typescript.compiler.Checker
import com.xemantic.typescript.compiler.CompilerOptions
import com.xemantic.typescript.compiler.Diagnostic
import com.xemantic.typescript.compiler.DiagnosticCategory
import com.xemantic.typescript.compiler.Expression
import com.xemantic.typescript.compiler.Identifier
import com.xemantic.typescript.compiler.InterfaceDeclaration
import com.xemantic.typescript.compiler.MethodDeclaration
import com.xemantic.typescript.compiler.ModifierFlag
import com.xemantic.typescript.compiler.Node
import com.xemantic.typescript.compiler.Parser
import com.xemantic.typescript.compiler.PropertyDeclaration
import com.xemantic.typescript.compiler.SemicolonClassElement
import com.xemantic.typescript.compiler.anyType
import com.xemantic.typescript.compiler.computeParserFlags
import com.xemantic.typescript.compiler.runWithDeepStack

/**
 * Kotlin `external` declarations generated from a CHECKED TypeScript program.
 *
 * This is the (EXT.1) first cut: every EXPORTED, non-generic
 * [InterfaceDeclaration] of one source file, rendered as a Kotlin
 * `external interface` whose member types are the CHECKER's answers — resolved
 * types, never re-read annotation syntax. That is the gap Dukat and Karakum
 * never closed: both translate `.d.ts` SYNTAX, so a member typed by an alias
 * (`p: Species` where `type Species = string`) comes out as an opaque
 * `Species` there and as `String` here, because the type was resolved by the
 * same engine that type-checked the program.
 *
 * ## Error policy
 *
 * A program with checker ERRORS is still rendered — refusing would make the
 * generator unusable on any program this checker is not yet perfect on — and
 * the diagnostics are exposed on [diagnostics]/[errors] so a caller can decide
 * for itself whether to trust the output.
 *
 * ## Collection model
 *
 * Facts are collected DURING the [CheckedNodeSink] callback into immutable
 * model values carrying only strings — no AST node, [com.xemantic.typescript.compiler.Type]
 * or [com.xemantic.typescript.compiler.Symbol] is retained, and nothing is
 * keyed by a node — which is what keeps this module entirely in `commonMain`
 * (the kir `CheckedFacts` alternative keys by node identity via
 * `IdentityHashMap`, which is `java.*` and forces `jvmMain`). The one identity
 * question left — "was this node visited before?" (the spine may walk a tree
 * more than once) — is answered by a linear `===` scan, the same pattern
 * `CheckedFacts.file` uses, which is safe where a `HashMap` keyed by a data-class
 * node would deep-recurse `hashCode()` over the whole subtree.
 */
public class KotlinExternals internal constructor(
    /** The generated Kotlin source: `public external interface` declarations. */
    public val kotlin: String,
    /**
     * The SAME rendering with the `external` modifier omitted — produced by the
     * renderer's own flag, never by text surgery.
     *
     * Exists solely for the compile gate: `KotlinMetadataCompiler` refuses the
     * `external` modifier on an interface (`modifier 'external' is not
     * applicable to 'interface'` — it is a Kotlin/JS platform notion, and a
     * metadata compilation has no platform), so what the gate grades is the
     * TYPE MAPPING, and the `external` modifier is outside it.
     */
    public val compileCheckSource: String,
    /** Everything the parser and checker reported, in that order. */
    public val diagnostics: List<Diagnostic>,
) {

    /** The diagnostics that are errors — the caller's reason to distrust [kotlin]. */
    public val errors: List<Diagnostic>
        get() = diagnostics.filter { it.category == DiagnosticCategory.Error }

}

/**
 * Parses, binds and checks [source], and renders Kotlin `external` declarations
 * for every exported interface it declares.
 *
 * The shape of the front half is `kir`'s `checkTypeScript`, and both of its
 * non-obvious choices are inherited deliberately: `useRealLibs` because an
 * unknown name degrades to `any` SILENTLY (so the failure would be a wrong
 * declaration rather than a diagnostic), and [runWithDeepStack] because the
 * checker recurses deeply on ordinary input and `Checker`'s `init` block IS the
 * check — the sink has already fired by the time the constructor returns.
 */
public fun generateKotlinExternals(
    fileName: String,
    source: String,
    options: CompilerOptions = CompilerOptions(useRealLibs = true),
): KotlinExternals {
    val flags = computeParserFlags(fileName, source, options)
    val parser = Parser(
        source,
        fileName,
        forceJsx = flags.forceJsx,
        topLevelAwait = flags.topLevelAwait,
        needsJsxFlag = flags.needsJsxFlag,
        noImplicitAny = flags.noImplicitAny,
    )
    val sourceFile = parser.parse()
    val parseDiagnostics = parser.getDiagnostics()
    val binderResult = Binder(options).bind(sourceFile)
    val collector = ExternalsCollector()
    val checker = runWithDeepStack {
        Checker(
            options,
            listOf(binderResult),
            isMultiFileSource = true,
            checkedSink = collector,
        )
    }
    return KotlinExternals(
        kotlin = renderKotlinExternals(collector.declarations, external = true),
        compileCheckSource = renderKotlinExternals(collector.declarations, external = false),
        diagnostics = parseDiagnostics + checker.getDiagnostics(),
    )
}

/**
 * Collects exported interfaces as the checker walks past their declarations.
 *
 * Everything the lens is asked happens INSIDE [declaration] — a [CheckedLens]
 * is valid only for the duration of the callback that received it, so member
 * types are resolved and mapped to Kotlin TEXT on the spot, and the model
 * retains no checker object at all.
 */
private class ExternalsCollector : CheckedNodeSink {

    val declarations = mutableListOf<ExternalDeclaration>()

    /**
     * Interfaces already collected, compared by IDENTITY: the spine may visit
     * the same tree more than once (a `declarationOnly` pre-pass walks the same
     * nodes), and first wins — the first visit is the one under the tightest
     * ambient the node ever has.
     */
    private val seen = mutableListOf<Node>()

    override fun expression(node: Expression, lens: CheckedLens) {}

    override fun declaration(node: Node, lens: CheckedLens) {
        if (node !is InterfaceDeclaration) return
        // A non-exported interface is not part of the module's surface, so it
        // is not generated — deliberately without a marker: nothing consuming
        // the module could have named it.
        if (ModifierFlag.Export !in node.modifiers) return
        if (seen.any { it === node }) return
        seen.add(node)
        declarations.add(collectInterface(node, lens))
    }

    private fun collectInterface(
        node: InterfaceDeclaration,
        lens: CheckedLens,
    ): ExternalDeclaration {
        // Generics are (EXT.2+): refused LOUDLY as a rendered marker rather
        // than dropped, because a silently absent exported declaration is the
        // failure direction nothing downstream can see.
        if (node.typeParameters != null) {
            return SkippedDeclaration("generic interface ${node.name.text}")
        }
        val members = mutableListOf<ExternalMember>()
        if (node.heritageClauses != null) {
            // Heritage is (EXT.2+) too: the supertype may be un-generated (a
            // lib type, a non-exported interface), so the clause is marked
            // rather than rendered or silently dropped.
            members.add(SkippedMember("heritage clause"))
        }
        for (member in node.members) {
            when (member) {
                is PropertyDeclaration -> collectProperty(member, lens, members)
                is MethodDeclaration -> collectMethod(member, lens, members)
                // A stray `;` between members is pure syntax — there is
                // nothing to generate and nothing to mark.
                is SemicolonClassElement -> {}
                else -> members.add(
                    SkippedMember(member::class.simpleName ?: "member")
                )
            }
        }
        return ExternalInterface(node.name.text, members)
    }

    private fun collectProperty(
        member: PropertyDeclaration,
        lens: CheckedLens,
        members: MutableList<ExternalMember>,
    ) {
        val name = (member.name as? Identifier)?.text
        if (name == null) {
            members.add(SkippedMember("member with a non-identifier name"))
            return
        }
        // The annotation is resolved by the CHECKER — `typeOfTypeNode` is
        // `getTypeFromTypeNode` under the walk's own ambient — so an alias
        // arrives as what it denotes. An absent annotation IS implicit `any`,
        // which is exactly what the checker would answer.
        val type = member.type?.let { lens.typeOfTypeNode(it) } ?: anyType
        members.add(
            ExternalProperty(
                name = name,
                type = kotlinTypeText(
                    type,
                    optional = member.questionToken,
                    returnPosition = false,
                    lens = lens,
                ),
                readOnly = ModifierFlag.Readonly in member.modifiers,
            )
        )
    }

    private fun collectMethod(
        member: MethodDeclaration,
        lens: CheckedLens,
        members: MutableList<ExternalMember>,
    ) {
        val name = (member.name as? Identifier)?.text
        if (name == null) {
            members.add(SkippedMember("member with a non-identifier name"))
            return
        }
        if (member.questionToken) {
            // An optional METHOD is a nullable function-typed property in
            // Kotlin, which is (EXT.2+) shape work — marked, not guessed at.
            members.add(SkippedMember("optional method $name"))
            return
        }
        val parameters = member.parameters
            .filterNot { it.isCommentPlaceholder }
            .mapIndexed { index, parameter ->
                // A destructuring parameter has no name of its own; a
                // positional one keeps the declaration readable.
                val parameterName =
                    (parameter.name as? Identifier)?.text ?: "p$index"
                val parameterType =
                    parameter.type?.let { lens.typeOfTypeNode(it) } ?: anyType
                ExternalParameter(
                    name = parameterName,
                    type = kotlinTypeText(
                        parameterType,
                        optional = parameter.questionToken,
                        returnPosition = false,
                        lens = lens,
                    ),
                )
            }
        val returnType = member.type?.let { lens.typeOfTypeNode(it) } ?: anyType
        members.add(
            ExternalFunction(
                name = name,
                parameters = parameters,
                returnType = kotlinTypeText(
                    returnType,
                    optional = false,
                    returnPosition = true,
                    lens = lens,
                ),
            )
        )
    }

}

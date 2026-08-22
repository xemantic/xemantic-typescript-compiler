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

package com.xemantic.typescript.compiler.kir.api

/**
 * A Kotlin type as it appears in an exported API surface.
 *
 * Structured rather than a rendered string, because the two consumers ask
 * different questions of it: the source renderer wants text, and the pins want
 * to assert that `string | undefined` erased to a NULLABLE `String` without
 * matching on punctuation.
 *
 * The codomain is deliberately small — `docs/kir-kotlin-metadata.md` §3 — and
 * every type outside it erases to [ANY]. Nothing here names a class from this
 * project's runtime: a metadata klib that referenced `JsObject` would only
 * resolve for a consumer who also had a COMMON metadata artifact declaring it,
 * and there is none yet.
 */
public sealed class KotlinType {

    public abstract val nullable: Boolean

    /** The type as Kotlin source, parenthesised where the grammar needs it. */
    public abstract fun render(): String

    /** The same type, admitting `null` — what a `T | undefined` member gives. */
    public abstract fun asNullable(): KotlinType

    /**
     * The same type, not admitting `null`.
     *
     * Used to ask whether two erasures are the SAME SHAPE, which is the
     * question union erasure asks of its members (§3.2) and which must not be
     * answered by string surgery on a rendering — `((Any?) -> Any?)?` does not
     * become a function type by dropping its last character.
     */
    public abstract fun asNonNullable(): KotlinType

    override fun toString(): String = render()

    override fun equals(other: Any?): Boolean =
        other is KotlinType && other.render() == render()

    override fun hashCode(): Int = render().hashCode()

    /** An ordinary named type, with erased type arguments where it has any. */
    public class Named(
        public val fqName: String,
        public val arguments: List<KotlinType> = emptyList(),
        override val nullable: Boolean = false,
    ) : KotlinType() {

        override fun render(): String = buildString {
            append(fqName)
            if (arguments.isNotEmpty()) {
                append(arguments.joinToString(", ", "<", ">") { it.render() })
            }
            if (nullable) append('?')
        }

        override fun asNullable(): KotlinType =
            if (nullable) this else Named(fqName, arguments, nullable = true)

        override fun asNonNullable(): KotlinType =
            if (!nullable) this else Named(fqName, arguments, nullable = false)

    }

    /**
     * A function type of [arity] parameters, uniformly `(Any?, …) -> Any?`.
     *
     * Uniform for the reason `ErasedTypes.function` gives: TypeScript's function
     * assignability is BIVARIANT and Kotlin's `FunctionN` is not, so giving the
     * parameters their own erased types would reject handlers the library's own
     * type system accepts. The price is a cast at the use site, which is where
     * union erasure already pays.
     */
    public class Function(
        public val arity: Int,
        override val nullable: Boolean = false,
    ) : KotlinType() {

        override fun render(): String {
            val signature = (0 until arity).joinToString(", ", "(", ") -> Any?") { "Any?" }
            return if (nullable) "($signature)?" else signature
        }

        override fun asNullable(): KotlinType =
            if (nullable) this else Function(arity, nullable = true)

        override fun asNonNullable(): KotlinType =
            if (!nullable) this else Function(arity, nullable = false)

    }

    public companion object {
        public val DOUBLE: KotlinType = Named("kotlin.Double")
        public val STRING: KotlinType = Named("kotlin.String")
        public val BOOLEAN: KotlinType = Named("kotlin.Boolean")
        public val UNIT: KotlinType = Named("kotlin.Unit")
        public val NOTHING: KotlinType = Named("kotlin.Nothing")
        /** `Nothing?` — the type of a `null`/`undefined` constant, per §3.1. */
        public val NULL: KotlinType = Named("kotlin.Nothing", nullable = true)
        public val ANY: KotlinType = Named("kotlin.Any", nullable = true)
        /** Non-null `Any` — a union whose members disagree but none is nullish. */
        public val ANY_NON_NULL: KotlinType = Named("kotlin.Any")
    }

}

/** One parameter of an exported function, method or constructor. */
public class KotlinParameter(
    public val name: String,
    public val type: KotlinType,
) {
    override fun toString(): String = "$name: ${type.render()}"
}

/** A declaration on the exported surface. */
public sealed class KotlinDeclaration {

    public abstract val name: String

    /** Where in the TypeScript sources it came from, for diagnostics. */
    public abstract val origin: String

}

public class KotlinFunction(
    override val name: String,
    public val parameters: List<KotlinParameter>,
    public val returnType: KotlinType,
    override val origin: String,
    /**
     * Rendered as `operator fun`, so `a[0]` reaches it.
     *
     * Never set from TypeScript — an exported TypeScript function is an
     * ordinary one — and set for the RUNTIME surface, whose `get`/`set` are
     * indexing operators in the Kotlin the runtime is written in.
     */
    public val isOperator: Boolean = false,
) : KotlinDeclaration()

public class KotlinProperty(
    override val name: String,
    public val type: KotlinType,
    /** A `var` where TypeScript wrote `let`/`var` or a mutable member. */
    public val mutable: Boolean,
    override val origin: String,
) : KotlinDeclaration()

public class KotlinClass(
    override val name: String,
    /** The primary constructor's parameters, or null when there is none to call. */
    public val constructorParameters: List<KotlinParameter>?,
    public val members: List<KotlinDeclaration>,
    override val origin: String,
) : KotlinDeclaration()

/**
 * A TypeScript `enum`, as an object of constants.
 *
 * An enum has no runtime object in this backend — a member access is replaced
 * by its constant — so a Kotlin `enum class` would be a claim about a runtime
 * representation that does not exist. An `object` holding one `val` per member,
 * typed as the members' VALUES are, is what the erasure actually is.
 */
public class KotlinConstantObject(
    override val name: String,
    public val members: List<KotlinProperty>,
    override val origin: String,
) : KotlinDeclaration()

/**
 * One TypeScript module's exported surface, as one Kotlin package.
 *
 * A package rather than a file per TypeScript module: the KIR backend already
 * puts a whole program in one package, and a metadata klib has no notion of
 * "file" a consumer can see — only packages and declarations.
 */
public class KotlinApiModule(
    public val packageName: String,
    public val declarations: List<KotlinDeclaration>,
)

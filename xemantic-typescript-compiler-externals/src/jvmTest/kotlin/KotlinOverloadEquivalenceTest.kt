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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (EXT.11c) The MEASURED table behind `KotlinSignatureKeys.kt`: which pairs
 * of Kotlin declarations the metadata compiler this build uses refuses —
 * `Conflicting overloads`, `Conflicting declarations`, a `var` override
 * whose type differs, `overrides nothing` — and which it accepts. Each row
 * is a pair of declarations compiled together through the same
 * [compileCheck] the gates use; the expectation is what was measured when
 * the keys were derived, so a Kotlin bump that moves the rule reddens the
 * row that names it rather than a gate three rungs away.
 *
 * The rows are the evidence for every sentence in [overloadSignature]'s and
 * [overrideSignature]'s contracts: a free own type parameter erases to its
 * bound, a pinned one keeps a nullability-carrying identity up to renaming,
 * variance composes, the type-parameter COUNT is part of the key, a class's
 * type parameter is an ordinary type, and the override relation is stricter
 * than the conflict relation.
 */
class KotlinOverloadEquivalenceTest {

    private class Row(val label: String, val a: String, val b: String, val conflicts: Boolean)

    private fun conflict(label: String, a: String, b: String) = Row(label, a, b, conflicts = true)

    private fun distinct(label: String, a: String, b: String) = Row(label, a, b, conflicts = false)

    private val prelude = "class Box<T>\nopen class Base\n"

    /** The labels of every row whose measured verdict is not the recorded one. */
    private fun mismatches(rows: List<Row>): List<String> =
        rows.filter { row ->
            val check = compileCheck(prelude + row.a + "\n" + row.b + "\n")
            check.successful == row.conflicts
        }.map { it.label }

    @Test
    fun `free own type parameters erase to their bound and the type-parameter count is part of the key`() {
        val rows = listOf(
            distinct("concrete nullability", "fun f(x: String): Unit = null!!", "fun f(x: String?): Unit = null!!"),
            conflict("tp nullability", "fun <D> f(x: D): Unit = null!!", "fun <D> f(x: D?): Unit = null!!"),
            conflict("tp names", "fun <T, D> f(a: Any?, b: D): Unit = null!!", "fun <T, S> f(a: Any?, b: S): Unit = null!!"),
            distinct("tp list length", "fun <A> f(x: Any?): Unit = null!!", "fun <A, R> f(x: Any?): Unit = null!!"),
            distinct("bare tp vs Any? - no tp list", "fun <T> f(x: T): Unit = null!!", "fun f(x: Any?): Unit = null!!"),
            conflict("bare tp vs Any? - equal tp count", "fun <T> f(x: T): Unit = null!!", "fun <A> f(x: Any?): Unit = null!!"),
            conflict("bare tp? vs Any?", "fun <T> f(x: T?): Unit = null!!", "fun <U> f(x: Any?): Unit = null!!"),
            distinct("bare tp vs Any", "fun <T> f(x: T): Unit = null!!", "fun <U> f(x: Any): Unit = null!!"),
            distinct("bare tp vs String", "fun <T> f(x: T): Unit = null!!", "fun <U> f(x: String): Unit = null!!"),
            conflict("bounded tp vs bound", "fun <T : Base> f(x: T): Unit = null!!", "fun <U> f(x: Base): Unit = null!!"),
            distinct("bounded tp vs bound?", "fun <T : Base> f(x: T): Unit = null!!", "fun <U> f(x: Base?): Unit = null!!"),
            conflict("bounded tp? vs bound?", "fun <T : Base> f(x: T?): Unit = null!!", "fun <U> f(x: Base?): Unit = null!!"),
            distinct("bounded vs unbounded tp", "fun <T> f(x: T): Unit = null!!", "fun <T : Base> f(x: T): Unit = null!!"),
            conflict("tp order swapped", "fun <T, U> f(a: T, b: U): Unit = null!!", "fun <U, T> f(a: T, b: U): Unit = null!!"),
            conflict("two tps used vs one twice", "fun <T, U> f(a: T, b: U): Unit = null!!", "fun <A, B> f(a: A, b: A): Unit = null!!"),
            conflict("tp twice vs tp and Any?", "fun <T> f(a: T, b: T): Unit = null!!", "fun <U> f(a: U, b: Any?): Unit = null!!"),
            distinct("tp twice vs two tps - count", "fun <T> f(a: T, b: T): Unit = null!!", "fun <T, U> f(a: T, b: U): Unit = null!!"),
            conflict("return type only", "fun f(x: String): String = null!!", "fun f(x: String): Int = null!!"),
            conflict("parameter names only", "fun f(x: String): Unit = null!!", "fun f(y: String): Unit = null!!"),
            distinct("arity", "fun f(x: String): Unit = null!!", "fun f(x: String, y: String): Unit = null!!"),
            distinct("vararg vs array", "fun <T> f(vararg x: T): Unit = null!!", "fun <T> f(x: Array<T>): Unit = null!!"),
            conflict("vararg tp vs vararg Any? - out", "fun <T> f(vararg x: T): Unit = null!!", "fun <U> f(vararg x: Any?): Unit = null!!"),
            distinct("array tp vs array Any? - invariant", "fun <T> f(x: Array<T>): Unit = null!!", "fun <U> f(x: Array<Any?>): Unit = null!!"),
        )
        val failing = mismatches(rows)
        assert(failing.isEmpty())
    }

    @Test
    fun `pinned own type parameters keep a nullability-carrying identity up to renaming and variance composes`() {
        val rows = listOf(
            distinct("generic arg tp vs Any?", "fun <T> f(x: Box<T>): Unit = null!!", "fun <U> f(x: Box<Any?>): Unit = null!!"),
            distinct("generic arg tp vs Any", "fun <T> f(x: Box<T>): Unit = null!!", "fun <U> f(x: Box<Any>): Unit = null!!"),
            distinct("generic arg tp vs tp?", "fun <T> f(x: Box<T>): Unit = null!!", "fun <U> f(x: Box<U?>): Unit = null!!"),
            conflict("generic arg tp names", "fun <T> f(x: Box<T>): Unit = null!!", "fun <U> f(x: Box<U>): Unit = null!!"),
            conflict("generic arg tp? names", "fun <T> f(x: Box<T?>): Unit = null!!", "fun <U> f(x: Box<U?>): Unit = null!!"),
            distinct("generic arg bounded vs unbounded", "fun <T> f(x: Box<T>): Unit = null!!", "fun <U : Base> f(x: Box<U>): Unit = null!!"),
            distinct("generic arg nested tp vs Any?", "fun <T> f(x: Box<Box<T>>): Unit = null!!", "fun <U> f(x: Box<Box<Any?>>): Unit = null!!"),
            distinct("generic top nullability", "fun <T> f(x: Box<T>): Unit = null!!", "fun <U> f(x: Box<U>?): Unit = null!!"),
            distinct("generic arg differs", "fun f(x: Box<String>): Unit = null!!", "fun f(x: Box<Int>): Unit = null!!"),
            distinct("generic arg nullability", "fun f(x: Box<String>): Unit = null!!", "fun f(x: Box<String?>): Unit = null!!"),
            distinct("two pinned vs one twice", "fun <T, U> f(a: Box<T>, b: Box<U>): Unit = null!!", "fun <A, B> f(a: Box<A>, b: Box<A>): Unit = null!!"),
            conflict("two pinned swapped", "fun <T, U> f(a: Box<T>, b: Box<U>): Unit = null!!", "fun <A, B> f(a: Box<B>, b: Box<A>): Unit = null!!"),
            conflict("unused tp", "fun <T, U> f(a: Box<T>): Unit = null!!", "fun <A, B> f(a: Box<B>): Unit = null!!"),
            distinct("pinned tp does not erase at top level", "fun <T> f(a: T, b: Box<T>): Unit = null!!", "fun <U> f(a: Any?, b: Box<U>): Unit = null!!"),
            distinct("pinned tp does not erase at top level - reversed", "fun <T> f(a: Box<T>, b: T): Unit = null!!", "fun <U> f(a: Box<U>, b: Any?): Unit = null!!"),
            distinct("pinned vs free of another name", "fun <T> f(a: T, b: Box<T>): Unit = null!!", "fun <U> f(a: U, b: Box<Any?>): Unit = null!!"),
            distinct("pinned vs free - two tps", "fun <T, U> f(a: T, b: Box<T>): Unit = null!!", "fun <A, B> f(a: A, b: Box<B>): Unit = null!!"),
            conflict("pinned renamed everywhere", "fun <T> f(a: T, b: Box<T>): Unit = null!!", "fun <U> f(a: U, b: Box<U>): Unit = null!!"),
            distinct("fn param tp vs Any? - in", "fun <T> f(x: (T) -> Unit): Unit = null!!", "fun <U> f(x: (Any?) -> Unit): Unit = null!!"),
            distinct("fn param tp vs tp? - in", "fun <T> f(x: (T) -> Unit): Unit = null!!", "fun <U> f(x: (U?) -> Unit): Unit = null!!"),
            conflict("fn param tp names - in", "fun <T> f(x: (T) -> Unit): Unit = null!!", "fun <U> f(x: (U) -> Unit): Unit = null!!"),
            conflict("fn return tp vs Any? - out", "fun <T> f(x: () -> T): Unit = null!!", "fun <U> f(x: () -> Any?): Unit = null!!"),
            conflict("fn return tp vs tp? - out", "fun <T> f(x: () -> T): Unit = null!!", "fun <U> f(x: () -> U?): Unit = null!!"),
            distinct("fn receiver tp vs Any? - in", "fun <T> f(x: T.(Base) -> Unit): Unit = null!!", "fun <U> f(x: Any?.(Base) -> Unit): Unit = null!!"),
            conflict("fn receiver tp names - in", "fun <T> f(x: T.(Base) -> Unit): Unit = null!!", "fun <U> f(x: U.(Base) -> Unit): Unit = null!!"),
            distinct("nullable fn param tp vs Any?", "fun <T> f(x: ((T) -> Unit)?): Unit = null!!", "fun <U> f(x: ((Any?) -> Unit)?): Unit = null!!"),
            distinct("fn type nullability", "fun f(x: (String) -> Unit): Unit = null!!", "fun f(x: ((String) -> Unit)?): Unit = null!!"),
            distinct("fn return nullability", "fun f(x: () -> String): Unit = null!!", "fun f(x: () -> String?): Unit = null!!"),
            conflict("out inside out", "fun <T> f(x: () -> () -> T): Unit = null!!", "fun <U> f(x: () -> () -> Any?): Unit = null!!"),
            distinct("in inside out", "fun <T> f(x: () -> (T) -> Unit): Unit = null!!", "fun <U> f(x: () -> (Any?) -> Unit): Unit = null!!"),
            distinct("out inside in", "fun <T> f(x: (() -> T) -> Unit): Unit = null!!", "fun <U> f(x: (() -> Any?) -> Unit): Unit = null!!"),
            conflict("in inside in is out", "fun <T> f(x: ((T) -> Unit) -> Unit): Unit = null!!", "fun <U> f(x: ((Any?) -> Unit) -> Unit): Unit = null!!"),
            distinct("invariant inside out", "fun <T> f(x: () -> Box<T>): Unit = null!!", "fun <U> f(x: () -> Box<Any?>): Unit = null!!"),
            distinct("out inside invariant", "fun <T> f(x: Box<() -> T>): Unit = null!!", "fun <U> f(x: Box<() -> Any?>): Unit = null!!"),
            distinct("in inside vararg", "fun <T> f(vararg x: (T) -> Unit): Unit = null!!", "fun <U> f(vararg x: (Any?) -> Unit): Unit = null!!"),
            distinct("invariant inside vararg", "fun <T> f(vararg x: Box<T>): Unit = null!!", "fun <U> f(vararg x: Box<Any?>): Unit = null!!"),
            conflict("explicit out projection", "fun <T> f(x: Array<out T>): Unit = null!!", "fun <U> f(x: Array<out Any?>): Unit = null!!"),
        )
        val failing = mismatches(rows)
        assert(failing.isEmpty())
    }

    @Test
    fun `an enclosing class's type parameter is an ordinary type and members follow the same rule`() {
        val rows = listOf(
            distinct("class tp vs Any?", "class C<T> { fun f(x: T): Unit = null!!; fun f(x: Any?): Unit = null!! }", ""),
            distinct("class tp nullability", "class C<T> { fun f(x: T): Unit = null!!; fun f(x: T?): Unit = null!! }", ""),
            distinct("class tp vs own tp - equal count", "class C<T> { fun <U> f(x: T): Unit = null!!; fun <U> f(x: U): Unit = null!! }", ""),
            distinct("class tp vs same-named own tp - count", "class C<T> { fun f(x: T): Unit = null!!; fun <T> f(x: T): Unit = null!! }", ""),
            distinct("boxed class tp vs boxed own tp", "class C<T> { fun <U> f(x: Box<T>): Unit = null!!; fun <U> f(x: Box<U>): Unit = null!! }", ""),
            distinct("boxed class tp vs boxed Any?", "class C<T> { fun f(x: Box<T>): Unit = null!!; fun f(x: Box<Any?>): Unit = null!! }", ""),
            conflict("own tp vs Any? - equal count", "class C<T> { fun <U> f(x: Any?): Unit = null!!; fun <U> f(x: U): Unit = null!! }", ""),
            conflict("own tp nullability", "class C<T> { fun <D> f(x: D): Unit = null!!; fun <D> f(x: D?): Unit = null!! }", ""),
            conflict("own tp names", "class C<T> { fun <U> f(x: U): Unit = null!!; fun <V> f(x: V): Unit = null!! }", ""),
            conflict("interface own tp nullability", "interface I { fun <D> f(x: D): Unit; fun <D> f(x: D?): Unit }", ""),
        )
        val failing = mismatches(rows)
        assert(failing.isEmpty())
    }

    @Test
    fun `a value and a type of one name conflict and a function conflicts only with a matching constructor`() {
        val rows = listOf(
            conflict("value vs interface", "val Foo: Int = null!!", "interface Foo"),
            conflict("interface vs value", "interface Foo", "val Foo: Int = null!!"),
            conflict("var vs interface", "var Foo: Int = null!!", "interface Foo"),
            conflict("value vs class", "val Foo: Int = null!!", "class Foo"),
            conflict("value vs typealias", "val Foo: Int = null!!", "typealias Foo = String"),
            conflict("value vs value", "val Foo: Int = null!!", "val Foo: String = null!!"),
            distinct("function vs value", "fun Foo(): Int = null!!", "val Foo: Int = null!!"),
            distinct("function vs interface", "fun Foo(): Int = null!!", "interface Foo"),
            distinct("function vs interface with companion", "fun Foo(): Int = null!!", "interface Foo { companion object }"),
            distinct("function vs sealed interface", "fun Foo(): Int = null!!", "sealed interface Foo { companion object { val A: Foo = null!! } }"),
            conflict("function vs class implicit constructor", "fun Foo(): Int = null!!", "abstract class Foo"),
            distinct("function vs class implicit constructor - arity", "fun Foo(x: String): Int = null!!", "abstract class Foo"),
            distinct("function vs generic class implicit constructor - tp count", "fun Foo(): Int = null!!", "class Foo<T>"),
            conflict("function vs class same constructor", "fun Foo(x: String): Int = null!!", "class Foo(y: String)"),
            distinct("function vs class distinct constructor", "fun Foo(x: String): Int = null!!", "class Foo(y: Int)"),
            conflict("generic function vs generic class same constructor", "fun <T> Foo(x: T): Int = null!!", "class Foo<T>(x: T)"),
            distinct("function Any? vs generic class constructor tp - tp count", "fun Foo(x: Any?): Int = null!!", "class Foo<T>(x: T)"),
            conflict("function vs typealias String", "fun Foo(): Int = null!!", "typealias Foo = String"),
            conflict("function vs typealias String?", "fun Foo(): Int = null!!", "typealias Foo = String?"),
            conflict("function vs typealias Double", "fun Foo(): Int = null!!", "typealias Foo = Double"),
            conflict("function vs typealias Boolean", "fun Foo(): Int = null!!", "typealias Foo = Boolean"),
            distinct("function vs typealias Double - arity", "fun Foo(x: String): Int = null!!", "typealias Foo = Double"),
            distinct("function vs typealias function type", "fun Foo(): Int = null!!", "typealias Foo = (String) -> Unit"),
            distinct("function vs typealias Array", "fun Foo(): Int = null!!", "typealias Foo = Array<String>"),
            conflict("function vs typealias class instantiation", "fun Foo(x: String): Int = null!!", "typealias Foo = Box2<String>\nclass Box2<T>(x: T)"),
        )
        val failing = mismatches(rows)
        assert(failing.isEmpty())
    }

    @Test
    fun `a var override repeats the inherited type exactly and the override relation is positional`() {
        val rows = listOf(
            conflict("narrowed var override", "abstract class B { open var s: Box<Any?>? = null!! }", "abstract class D : B() { override var s: Box<String> = null!! }"),
            conflict("narrowed var override - nullability only", "abstract class B { open var s: Box<Any?>? = null!! }", "abstract class D : B() { override var s: Box<Any?> = null!! }"),
            conflict("narrowed var override - interface", "interface B { var s: Box<Any?>? }", "interface D : B { override var s: Box<Any?> }"),
            conflict("narrowed val over var", "abstract class B { open var s: Any? = null!! }", "abstract class D : B() { override val s: String = null!! }"),
            distinct("covariant val override", "abstract class B { open val s: Box<Any?>? = null!! }", "abstract class D : B() { override val s: Box<Any?> = null!! }"),
            distinct("covariant val override - narrower", "abstract class B { open val s: Any? = null!! }", "abstract class D : B() { override val s: String = null!! }"),
            distinct("var over val", "abstract class B { open val s: Any? = null!! }", "abstract class D : B() { override var s: Any? = null!! }"),
            distinct("var over val - narrower", "abstract class B { open val s: Any? = null!! }", "abstract class D : B() { override var s: String = null!! }"),
            distinct("generic member substituted", "abstract class B<T> { open var s: Box<T>? = null!! }", "abstract class D<U> : B<U>() { override var s: Box<U>? = null!! }"),
            distinct("override own tp renamed", "abstract class B { open fun <U> f(x: Box<U>): Unit = null!! }", "abstract class D : B() { override fun <T> f(x: Box<T>): Unit = null!! }"),
            conflict("override own tp over Any? - overrides nothing", "abstract class B { open fun <U> f(x: Any?): Unit = null!! }", "abstract class D : B() { override fun <T> f(x: T): Unit = null!! }"),
            distinct("own tp beside inherited Any? - no conflict", "abstract class B { open fun <U> f(x: Any?): Unit = null!! }", "abstract class D : B() { fun <T> f(x: T): Unit = null!! }"),
            conflict("override tp? over tp - overrides nothing", "abstract class B { open fun <U> f(x: U): Unit = null!! }", "abstract class D : B() { override fun <T> f(x: T?): Unit = null!! }"),
            distinct("tp? beside inherited tp - no conflict", "abstract class B { open fun <U> f(x: U): Unit = null!! }", "abstract class D : B() { fun <T> f(x: T?): Unit = null!! }"),
            distinct("Any? beside inherited own tp - no conflict", "abstract class B { open fun <U> f(x: U): Unit = null!! }", "abstract class D : B() { fun <T> f(x: Any?): Unit = null!! }"),
            conflict("interface override own tp over Any? - overrides nothing", "interface B { fun <U> f(x: Any?): Unit }", "interface D : B { override fun <T> f(x: T): Unit }"),
            distinct("interface own tp beside Any? - no conflict", "interface B { fun <U> f(x: Any?): Unit }", "interface D : B { fun <T> f(x: T): Unit }"),
        )
        val failing = mismatches(rows)
        assert(failing.isEmpty())
    }

    @Test
    fun `measured - a function beside an object of one name and nested declarations in a class and an interface`() {
        val rows = listOf(
            distinct("function vs object", "fun assert(value: Any?): Unit = null!!", "object assert { fun ok(value: Any?): Unit = null!! }"),
            distinct("object vs function", "object assert { fun ok(value: Any?): Unit = null!! }", "fun assert(value: Any?): Unit = null!!"),
            distinct("function overloads vs object", "fun test(name: String): Unit = null!!\nfun test(name: String, n: Int): Unit = null!!", "object test { val skip: Int = null!! }"),
            distinct("class nesting", "abstract class EE { fun emit(name: String): Boolean = null!!\n interface Abortable { var signal: Double? }\n abstract class Inner { fun f(): Int = null!! }\n object Opts { val v: Int = null!! }\n companion object { fun once(e: EE): Any? = null!!\n var d: Double = null!! } }", "fun useIt(a: EE.Abortable, i: EE.Inner): EE.Opts = null!!"),
            distinct("interface nesting", "interface I { var x: Int\n interface Nested { var n: Int }\n object O { val v: Int = null!! }\n companion object { fun f(): Int = null!! } }", "fun useIt(a: I.Nested): I.O = null!!"),
            distinct("sealed interface nesting", "sealed interface K { interface Nested { var n: Int }\n companion object { val A: K = null!!\n fun f(): Int = null!! } }", "fun useIt(a: K.Nested): K = null!!"),
        )
        val failing = mismatches(rows)
        println("MEASURED metadata mismatches: $failing")
        assert(failing.isEmpty())
    }

}

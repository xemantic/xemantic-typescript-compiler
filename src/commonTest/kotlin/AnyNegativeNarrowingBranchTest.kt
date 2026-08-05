package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (NARROW.2)(b) round 838 — an `any` subject must SURVIVE a negative narrowing branch.
 *
 * Round 837 reported this as "a guard's narrow LEAKS out of its `if` into the next
 * sibling `if`". It is not a scoping leak — the `if` scoping is correct, and a UNION
 * subject behaves perfectly. The defect is one relation call: both single-type
 * negative branches (a type-predicate guard's, and `narrowByInstanceOf`'s) decide
 * "the subject IS the target, so the false branch is impossible" with the ASSIGNABLE
 * relation, and `any` is assignable to everything. So the else branch of a guard on
 * an `any` subject was `never`, and the flow join of (`Foo` from then, `never` from
 * else) produced `Foo` — which reads at the next statement exactly like a narrow that
 * outlived its block, and ACCUMULATES across sibling guards (`Error | Function` after
 * two of them, and nothing in the output names `never` anywhere).
 *
 * tsc reaches its assumeFalse filter through `isTypeSubsetOf`, which for a non-union
 * candidate is plain identity, so `any` survives there by construction.
 *
 * Deliberately NOT touched: the POSITIVE branch. `if (isError(x))` on an `any` subject
 * already narrows to `Error` here, and the remaining `any` narrowing work — tsc's
 * `Function`/`Object` exemption, and letting the property-access walker read a narrowed
 * `any` receiver — is (NARROW.2)(c), whose blast radius is of a different order.
 *
 * **A `never` narrow is NOT observable in this compiler** — it is assignable to every
 * probe target, a `never` receiver's property access is silent, and an argument
 * position reads the DECLARED type — so no control can be written for "this subject
 * still collapses to `never`", and the controls below instead name the neighbouring
 * populations the fix must not disturb.
 */
class AnyNegativeNarrowingBranchTest {

    private val guards = """
        declare function isFunction(v: unknown): v is Function;
        declare function isError(v: unknown): v is Error;
        declare function isDate(v: unknown): v is Date;

    """.trimIndent() + "\n"

    // ---- positives -------------------------------------------------------------------

    @Test
    fun `an any subject survives the negative branch of a type-predicate guard`() {
        val d = diagnose(
            guards +
                """
                function f(x: any) {
                    if (isError(x)) {
                    } else {
                        if (isDate(x)) {
                            const p: number = x;
                        }
                    }
                }
                """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'Date' is not assignable to type 'number'.")
    }

    @Test
    fun `an any subject survives the negative branch of an instanceof`() {
        val d = diagnose(
            guards +
                """
                function f(x: any) {
                    if (x instanceof Error) {
                    } else {
                        if (isDate(x)) {
                            const p: number = x;
                        }
                    }
                }
                """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'Date' is not assignable to type 'number'.")
    }

    @Test
    fun `a guard's narrow does not outlive its if statement for an any subject`() {
        // Before the fix this reported `Type 'Error | Function' is not assignable to
        // type 'number'` — two guards' worth of accumulated then-branch narrows, read
        // at a statement neither `if` encloses.
        diagnose(
            guards +
                """
                function f(x: any) {
                    if (isFunction(x)) {
                    }
                    if (isError(x)) {
                    }
                    const p: number = x;
                }
                """.trimIndent(),
        ) should { have(none { it.code == 2322 }) }
    }

    // ---- controls --------------------------------------------------------------------

    @Test
    fun `negative control - a later guard on an any subject still narrows to its own target`() {
        // Written as a positive ("the second guard sees the declared `any`, not the
        // first guard's leftover") and MEASURED NOT TO DISCRIMINATE that: it is green
        // on the pre-change binary too, because narrowing the leaked `Function` by
        // `is Error` happened to answer `Error` anyway. It DOES discriminate the
        // both-branches ablation below, so it is recorded here for what it actually
        // tests — that a guard reached after another guard still narrows.
        val d = diagnose(
            guards +
                """
                function f(x: any) {
                    if (isFunction(x)) {
                    }
                    if (isError(x)) {
                        const p: number = x;
                    }
                }
                """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'Error' is not assignable to type 'number'.")
    }

    @Test
    fun `negative control - the positive branch on an any subject still narrows`() {
        // The fix is gated to the NEGATIVE branch. Applying it to both — the obvious
        // "just let `any` survive narrowing" slip — silences this.
        val d = diagnose(
            guards +
                """
                function f(x: any) {
                    if (isError(x)) {
                        const p: number = x;
                    }
                }
                """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'Error' is not assignable to type 'number'.")
    }

    @Test
    fun `negative control - an unknown subject is unaffected`() {
        val d = diagnose(
            guards +
                """
                function f(x: unknown) {
                    if (isError(x)) {
                    } else {
                        const p: number = x;
                    }
                }
                """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'unknown' is not assignable to type 'number'.")
    }

    @Test
    fun `negative control - a union subject still subtracts in the negative branch`() {
        // A union takes the union negative branch, which this fix does not reach at all.
        val d = diagnose(
            """
            declare class Err { message: string; }
            declare function isErr(v: unknown): v is Err;
            function f(y: Err | string) {
                if (isErr(y)) {
                } else {
                    const p: number = y;
                }
            }
            """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'string' is not assignable to type 'number'.")
    }
}

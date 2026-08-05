package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (NARROW.2)(a) round 838 — an `instanceof` whose RHS is a CONSTRUCTOR VALUE.
 *
 * `resolveInstanceOfRhsType` required `SymbolFlags.Class`, so it answered only for a
 * class DECLARATION. Every ambient constructor in the lib is written the other way —
 * `interface Error { … }` plus `declare var Error: ErrorConstructor` — so `Error`,
 * `Date`, `RegExp`, `Map`, `Set`, `Promise` and friends narrowed NOTHING, in either
 * branch and at every consumer of `narrowByInstanceOf`, while a user-written
 * `class C` narrowed correctly. That asymmetry is invisible on the eight dashboard
 * profiles (tsc's own sources `instanceof` their own classes) and is why no gate
 * caught it for 800 rounds.
 *
 * The fix is tsc's `getInstanceType`: the constructor type's `prototype` property,
 * falling back to a lone construct signature's return type. It is deliberately
 * bounded — no `[Symbol.hasInstance]` leg, no `mapType` over a union-typed RHS, no
 * signature erasure, and where tsc falls back to `emptyObjectType` we return null
 * (i.e. no narrowing at all), because a `{}` narrow would be a new and wrong type at
 * every consumer while null preserves the pre-838 answer.
 *
 * The two negative controls below name the two mistakes that bound is protecting
 * against: narrowing on a value that is not a constructor, and narrowing on a name
 * that has no value meaning at all.
 *
 * Probe discipline (CLAUDE.md): a narrowing probe targets a PRIMITIVE, because an
 * object-shaped target is silently assignable from `never` and cannot tell
 * "narrowed" from "washed"; and it is an ASSIGNMENT, because an argument position
 * would read the declared type at several gates. `const p: number = r` prints the
 * flow type verbatim in its TS2322.
 */
class InstanceOfConstructorValueNarrowingTest {

    // ---- positives -------------------------------------------------------------------

    @Test
    fun `an instanceof against the Error constructor value narrows a union to Error`() {
        val d = diagnose(
            """
            function f(r: Error | string) {
                if (r instanceof Error) {
                    const p: number = r;
                }
            }
            """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'Error' is not assignable to type 'number'.")
    }

    @Test
    fun `an instanceof against the Date constructor value narrows through its prototype`() {
        // `DateConstructor` carries FOUR construct signatures, so only the `prototype`
        // leg can answer here — the lone-construct-signature fallback cannot.
        val d = diagnose(
            """
            function f(r: Date | string) {
                if (r instanceof Date) {
                    const p: number = r;
                }
            }
            """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'Date' is not assignable to type 'number'.")
    }

    @Test
    fun `the negative branch of an instanceof on a constructor value subtracts the instance type`() {
        val d = diagnose(
            """
            function f(r: Error | string) {
                if (r instanceof Error) {
                } else {
                    const p: number = r;
                }
            }
            """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'string' is not assignable to type 'number'.")
    }

    @Test
    fun `a property missing on the narrowed instance type is reported`() {
        val d = diagnose(
            """
            function f(r: Error | string) {
                if (r instanceof Error) {
                    r.nope;
                }
            }
            """.trimIndent(),
        ).single { it.code == 2339 }
        assert(d.message == "Property 'nope' does not exist on type 'Error'.")
    }

    // ---- controls --------------------------------------------------------------------

    @Test
    fun `negative control - a value that is not a constructor does not narrow`() {
        // `plain` has neither a `prototype` property nor a construct signature, so
        // `getInstanceType` has no answer. tsc would fall back to `{}`; we fall back to
        // NO narrowing, which is what keeps this widening from inventing a type.
        val d = diagnose(
            """
            declare var plain: { x: number };
            function f(r: Error | string) {
                if (r instanceof plain) {
                    const p: number = r;
                }
            }
            """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'string | Error' is not assignable to type 'number'.")
    }

    @Test
    fun `negative control - a name with no value meaning does not narrow`() {
        // An interface has no constructor value at all; the value-position gate
        // (`Variable or Function or Property`) refuses it before any type is resolved.
        val ds = diagnose(
            """
            interface Shape { s: string; }
            function f(r: Error | string) {
                if (r instanceof Shape) {
                    const p: number = r;
                }
            }
            """.trimIndent(),
        )
        assert(ds.single { it.code == 2322 }.message == "Type 'string | Error' is not assignable to type 'number'.")
        ds should { have(any { it.code == 2693 }) }
    }

    @Test
    fun `negative control - a user-declared class still narrows to its instance type`() {
        val d = diagnose(
            """
            declare class Err { message: string; }
            function f(r: Err | string) {
                if (r instanceof Err) {
                    const p: number = r;
                }
            }
            """.trimIndent(),
        ).single { it.code == 2322 }
        assert(d.message == "Type 'Err' is not assignable to type 'number'.")
    }
}

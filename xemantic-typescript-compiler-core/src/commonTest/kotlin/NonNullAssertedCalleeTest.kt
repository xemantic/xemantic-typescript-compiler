package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (CHK.61d) `f!()` — the non-null ASSERTION must reach the CALLEE, at both of the two
 * places that classify one.
 *
 * ## The defect
 *
 * [Checker.getCalleeType] answered `is NonNullExpression -> getCalleeType(expr.expression)`
 * and [Checker.getReturnTypeOfCallExpression]'s wrapper-unwrap loop rewrote
 * `callee = callee.expression` — both DISCARD the assertion. So a callee of type
 * `T | undefined` arrived as a UNION and each half failed in its own direction: the
 * callability check reported TS2349 where tsc is silent, and the return-type
 * classification's `if (calleeType !is Type.Object) return anyType` answered `any`, so
 * every check over the call's RESULT was silently vacuous.
 *
 * Both halves are fixed by the same rule — restore what the `!` asserted with
 * [Checker.narrowByExcludingNullUndefined], which is exactly what
 * [Checker.getTypeOfExpression]'s own `NonNullExpression` arm already did for a
 * non-callee operand.
 *
 * ## Why it mattered beyond the shape itself
 *
 * It is the gate on (CHK.61)(b) — giving an OPTIONAL property access its `| undefined`.
 * Measured: with (b) applied and this defect present, the 8-profile grid gains **19** rows
 * on the compiler profile of which **17** are this one class (`host.readDirectory!(…)`,
 * `resolutionHost.realpath!(…)`, `host.writeFile!(…)` — tsc's own sources use the idiom
 * everywhere); with this fixed, (b)'s cost falls to **3**.
 *
 * All three positives are byte-exact against tsc 7.0.2 over `build/chk61/p5/a.ts`.
 *
 * RESIDUE, measured and deliberately recorded rather than fixed: WITHOUT the `!` we report
 * TS2349 `This expression is not callable.` where tsc reports TS2722 `Cannot invoke an
 * object which is possibly 'undefined'.`, and at a different column for a member callee
 * (ours `(7,30)`, tsc `(7,23)`). The control below pins TODAY'S behaviour, not tsc's.
 */
class NonNullAssertedCalleeTest {

    private val prelude = """
        declare let zzzFn: (() => number) | undefined;
        interface ZzzBox { zzzG: (() => number) | undefined }
        declare const zzzBox: ZzzBox;
        declare let zzzPlain: () => number;
    """.trimIndent() + "\n"

    /**
     * POSITIVE — an asserted callee is CALLABLE. tsc 7.0.2 is silent for both forms.
     */
    @Test
    fun `an asserted callee is callable`() {
        val d = diagnose(prelude + "const zzzR1: number = zzzFn!()\nconst zzzR2: number = zzzBox.zzzG!()\n")
        assert(d.none { it.code == 2349 })
        assert(d.none { it.code == 2722 })
        assert(d.none { it.code == 2322 })
    }

    /**
     * POSITIVE with the wording — the asserted callee's RETURN TYPE is resolved, so a
     * mis-assignment reports. Before the fix the return type was `any` and this was
     * silent. tsc 7.0.2: `a.ts(4,7): error TS2322: Type 'number' is not assignable to
     * type 'string'.`
     */
    @Test
    fun `an asserted call resolves its return type - identifier callee`() {
        val d = diagnose(prelude + "const zzzS1: string = zzzFn!()\n")
        val row = d.single { it.code == 2322 }
        assert(row.message == "Type 'number' is not assignable to type 'string'.")
        assert(row.character == 7)
    }

    /**
     * POSITIVE with the wording — the same through a MEMBER callee, which reaches
     * [Checker.getCalleeType]'s `PropertyAccessExpression` arm rather than its Identifier
     * one. tsc 7.0.2: `a.ts(6,7)`.
     */
    @Test
    fun `an asserted call resolves its return type - member callee`() {
        val d = diagnose(prelude + "const zzzS2: string = zzzBox.zzzG!()\n")
        val row = d.single { it.code == 2322 }
        assert(row.message == "Type 'number' is not assignable to type 'string'.")
        assert(row.character == 7)
    }

    /**
     * CONTROL — WITHOUT the assertion the union callee still reports. This pins TODAY'S
     * answer (TS2349), which diverges from tsc's TS2722; see the class KDoc's residue
     * note. Its job here is to prove the fix is confined to the ASSERTED form.
     */
    @Test
    fun `control - an un-asserted nullish callee still reports`() {
        val d = diagnose(prelude + "const zzzR3: number = zzzFn()\n")
        assert(d.any { it.code == 2349 || it.code == 2722 })
    }

    /**
     * CONTROL — a callee that was never nullish is untouched, in both directions.
     */
    @Test
    fun `control - a non-nullish callee is unaffected`() {
        val ok = diagnose(prelude + "const zzzR4: number = zzzPlain()\n")
        assert(ok.none { it.code == 2322 || it.code == 2349 })
        val bad = diagnose(prelude + "const zzzS3: string = zzzPlain()\n")
        val row = bad.single { it.code == 2322 }
        assert(row.message == "Type 'number' is not assignable to type 'string'.")
    }
}

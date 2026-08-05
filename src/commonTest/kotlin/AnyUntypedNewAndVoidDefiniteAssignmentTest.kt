package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (M3.0/ANY.1) round 837 — the two bounded `types/any` conformance gaps.
 *
 * **TS2347 through `new`.** tsc reaches `resolveUntypedCall` from `resolveNewExpression`
 * as well as `resolveCallExpression`, so explicit type arguments on an `any` constructee
 * are the same error as on an `any` callee (`anyAsConstructor`'s `new x<any>(x)`). We had
 * only the call leg.
 *
 * **`assumeInitialized` for `void` and `typeof undefined`.** tsc's TS2454 guard is
 * `type.flags & (AnyOrUnknown | Void)` OR the type's facts carrying `IsUndefined`. We
 * covered `any` / `unknown` / a syntactic `undefined` constituent but neither `void` nor a
 * `typeof undefined` query, so `assignEveryTypeToAny`'s `var d: void` and
 * `var e2: typeof undefined` were reported as used-before-assigned.
 *
 * The exemption is deliberately NOT folded into the shared `typeIncludesUndefined`
 * predicate: its other callers are TS2564 property sites, where tsc tests
 * `containsUndefinedType` instead and a `void` property genuinely wants the diagnostic.
 * `a void property still reports TS2564` is the control for exactly that mistake.
 */
class AnyUntypedNewAndVoidDefiniteAssignmentTest {

    // ---- TS2347 on an untyped `new` -------------------------------------------------

    @Test
    fun `explicit type arguments on an any constructee report TS2347`() {
        diagnose(
            """
            var x: any;
            var d = new x<any>(x);
            """.trimIndent(),
        ) should { have(any { it.code == 2347 }) }
    }

    @Test
    fun `the TS2347 span covers the whole new expression`() {
        val d = diagnose(
            """
            var x: any;
            var d = new x<any>(x);
            """.trimIndent(),
        ).single { it.code == 2347 }
        // `new x<any>(x)` — 13 characters, starting at the `new` keyword (column 9).
        assert(d.length == 13)
        assert(d.character == 9)
    }

    @Test
    fun `an untyped new through a property access chain reports TS2347`() {
        diagnose(
            """
            declare var o: any;
            var oo = new o.p<string>();
            """.trimIndent(),
        ) should { have(any { it.code == 2347 }) }
    }

    @Test
    fun `negative control - an untyped new without type arguments is silent`() {
        diagnose(
            """
            var x: any;
            var c = new x(x);
            var b = new x('hello');
            """.trimIndent(),
        ) should { have(none { it.code == 2347 }) }
    }

    @Test
    fun `negative control - type arguments on a real generic class are silent`() {
        diagnose(
            """
            class K<T> {
                v: T | undefined;
            }
            var kk = new K<string>();
            """.trimIndent(),
        ) should { have(none { it.code == 2347 }) }
    }

    // ---- TS2454 assumeInitialized ---------------------------------------------------

    @Test
    fun `a void annotated variable is assumed initialized`() {
        diagnose(
            """
            var x: any;
            var d: void;
            x = d;
            """.trimIndent(),
        ) should { have(none { it.code == 2454 }) }
    }

    @Test
    fun `a typeof undefined annotated variable is assumed initialized`() {
        diagnose(
            """
            var x: any;
            var e2: typeof undefined;
            x = e2;
            """.trimIndent(),
        ) should { have(none { it.code == 2454 }) }
    }

    @Test
    fun `a union with a void constituent is assumed initialized`() {
        diagnose(
            """
            var x: any;
            var vs: void | string;
            x = vs;
            """.trimIndent(),
        ) should { have(none { it.code == 2454 }) }
    }

    @Test
    fun `negative control - a plain primitive variable still reports TS2454`() {
        diagnose(
            """
            var x: any;
            var s: string;
            x = s;
            """.trimIndent(),
        ) should { have(any { it.code == 2454 }) }
    }

    @Test
    fun `negative control - an object typed variable still reports TS2454`() {
        diagnose(
            """
            var x: any;
            var g: { foo: string };
            x = g;
            """.trimIndent(),
        ) should { have(any { it.code == 2454 }) }
    }

    // ---- TS2631 in an assignment target ---------------------------------------------

    @Test
    fun `assigning to a namespace reports TS2631 and not TS2708`() {
        diagnose(
            """
            var x: any;
            namespace M {
                export var foo = 1;
            }
            M = x;
            """.trimIndent(),
        ) should {
            have(any { it.code == 2631 })
            have(none { it.code == 2708 })
        }
    }

    @Test
    fun `the TS2631 message names the assignment not the value use`() {
        val d = diagnose(
            """
            var x: any;
            namespace M {
                export var foo = 1;
            }
            M = x;
            """.trimIndent(),
        ).single { it.code == 2631 }
        assert(d.message == "Cannot assign to 'M' because it is a namespace.")
    }

    @Test
    fun `negative control - assigning to a non-instantiated namespace keeps TS2708`() {
        // A namespace with no value members has no value meaning, so tsc's value-position
        // TS2708 fires before the write is ever judged (`assignToModule`,
        // `assignmentToReferenceTypes`). `SymbolFlags.Module` is the UNION of ValueModule and
        // NamespaceModule, so a gate reading it cannot separate this from the case above.
        diagnose(
            """
            namespace A { }
            A = undefined;
            """.trimIndent(),
        ) should {
            have(any { it.code == 2708 })
            have(none { it.code == 2631 })
        }
    }

    @Test
    fun `negative control - assigning to a class still reports TS2629`() {
        diagnose(
            """
            var x: any;
            class D { }
            D = x;
            """.trimIndent(),
        ) should { have(any { it.code == 2629 }) }
    }

    @Test
    fun `negative control - reading a type-only namespace as a value still reports TS2708`() {
        // TS2708 keeps its own, DIFFERENT job: a namespace with no value members read in a
        // value position. Only the WRITE positions moved to TS2631.
        diagnose(
            """
            namespace M2 {
                export interface I { }
            }
            M2;
            """.trimIndent(),
        ) should { have(any { it.code == 2708 }) }
    }

    @Test
    fun `negative control - a void property still reports TS2564`() {
        diagnose(
            """
            class C {
                bar: void;
            }
            """.trimIndent(),
        ) should { have(any { it.code == 2564 }) }
    }
}

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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * INV.4(d) walker 3 (round 532): checkImplicitAnyParameters (TS7005/TS7006/
 * TS7008/TS7013/TS7019/TS7031/TS7032/TS7051) migrated onto the check spine.
 * The recursive checkImplicitAnyInStatements/-InClassElement(Core)/-InExpr
 * walkers are DELETED; emissions dispatch at node enters with PUSH-maintained
 * context frames (the downward contextual-typing state: contextualType /
 * contextuallyTyped / viaUnionWithPrimitive / ctxAnnotation / ctxViaAssignment)
 * and the three implicit-any scope stacks push/popped at function-body edges.
 * Every pin here was verified against the OLD walker first — a pure
 * behavior-preserving migration, including the bug-compat reach quirks
 * (while/do/switch/try/for-in/for-of bodies UNREACHED; call CALLEES,
 * conditional CONDITIONS, as-cast operands, object-literal accessors and
 * class static blocks unwalked).
 */
class Inv4SpineBatch23Test {

    // ── basic emission shapes ───────────────────────────────────────────────

    @Test
    fun `unannotated function parameter fires TS7006`() {
        diagnose("""
            function f(x) { return x; }
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `declare function parameter fires TS7006`() {
        diagnose("""
            declare function f(x): void;
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `nested function parameter fires TS7006`() {
        diagnose("""
            function outer() {
                function inner(y) { return y; }
            }
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `ambient var without type fires TS7005`() {
        diagnose("""
            declare var loose;
        """) should {
            have(any { it.code == 7005 })
        }
    }

    @Test
    fun `ambient var inside declare namespace fires TS7005`() {
        diagnose("""
            declare namespace N {
                var loose;
            }
        """) should {
            have(any { it.code == 7005 })
        }
    }

    @Test
    fun `interface property without type fires TS7008`() {
        diagnose("""
            interface I { m; }
        """) should {
            have(any { it.code == 7008 })
        }
    }

    @Test
    fun `ambient class public property without type fires TS7008`() {
        diagnose("""
            declare class C { m; }
        """) should {
            have(any { it.code == 7008 })
        }
    }

    @Test
    fun `ambient class public method parameter fires TS7006`() {
        diagnose("""
            declare class C { go(x): void; }
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - ambient class private method parameter does not fire TS7006`() {
        diagnose("""
            declare class C { private go(x); }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `interface method type-like parameter name fires TS7051`() {
        diagnose("""
            interface I { f(string): void; }
        """) should {
            have(any { it.code == 7051 })
        }
    }

    @Test
    fun `construct signature without return annotation fires TS7013`() {
        diagnose("""
            var c: { new (); };
        """) should {
            have(any { it.code == 7013 })
        }
    }

    @Test
    fun `destructured parameter binding element fires TS7031`() {
        diagnose("""
            function f({ a, b }) { return a; }
        """) should {
            have(any { it.code == 7031 })
        }
    }

    @Test
    fun `rest parameter fires TS7019`() {
        diagnose("""
            function f(...rest) {}
        """) should {
            have(any { it.code == 7019 })
        }
    }

    @Test
    fun `setter without annotation and no sibling getter fires TS7032 and TS7006`() {
        diagnose("""
            class C { set s(v) {} }
        """) should {
            have(any { it.code == 7032 })
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - setter with sibling getter suppresses TS7032`() {
        diagnose("""
            class C {
                get s(): number { return 1; }
                set s(v) {}
            }
        """) should {
            have(none { it.code == 7032 })
        }
    }

    @Test
    fun `static class property without type or initializer fires TS7008`() {
        diagnose("""
            class C { static m; }
        """) should {
            have(any { it.code == 7008 })
        }
    }

    @Test
    fun `negative control - static block assignment suppresses static property TS7008`() {
        diagnose("""
            class C {
                static m;
                static { this.m = 1; }
            }
        """, directives = "// @strict: true\n// @target: es2022") should {
            have(none { it.code == 7008 })
        }
    }

    @Test
    fun `function-type var annotation parameter fires TS7006`() {
        diagnose("""
            var f: (x) => string;
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `type-like parameter name in var function-type annotation fires TS7051`() {
        diagnose("""
            var f: (string) => string;
        """) should {
            have(any { it.code == 7051 })
        }
    }

    @Test
    fun `interface property with function-type annotation fires TS7006 for its parameter`() {
        diagnose("""
            interface I { f: (x) => string; }
        """) should {
            have(any { it.code == 7006 })
        }
    }

    // ── contextual-typing suppressions ──────────────────────────────────────

    @Test
    fun `arrow initializer of annotated var is contextually typed`() {
        diagnose("""
            var v: (a: number) => number = a => a;
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `arrow initializer of unannotated var fires TS7006`() {
        diagnose("""
            var v = (a) => a;
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `parameter beyond the annotation arity fires TS7006 - B224`() {
        diagnose("""
            const f7: () => any = (x?) => 0;
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `assignment to annotated body local contextually types the arrow`() {
        diagnose("""
            function g() {
                let h: (a: number) => void;
                h = a => {};
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `assignment to declared-untyped local keeps TS7006 firing`() {
        diagnose("""
            function g() {
                let mark;
                mark = tag => tag;
            }
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `assignment to initializer-typed local contextually types the arrow`() {
        diagnose("""
            function g() {
                let h = (a: number) => {};
                h = a => {};
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `assignment to destructured local resolves context from the source member`() {
        diagnose("""
            function g(state: { cb: (s: string) => void }) {
                let { cb } = state;
                cb = s => {};
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `assignment to file-level annotated var contextually types the arrow`() {
        diagnose("""
            var h: (x: number) => void;
            function g() {
                h = x => {};
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `this-property assignment resolves context from the class property annotation`() {
        diagnose("""
            class C {
                skip: (pos: number) => number;
                constructor() {
                    this.skip = pos => pos;
                }
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `call argument arrow is contextually typed by a resolvable callee`() {
        diagnose("""
            function g(cb: (x: number) => void) {}
            g(x => {});
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `call argument arrow of an unresolvable callee fires TS7006`() {
        diagnose("""
            unknownFn(x => x);
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `object literal member arrow is contextually typed by the annotation member type`() {
        diagnose("""
            interface Handlers { onFoo: (a: number) => void; }
            var h: Handlers = { onFoo: a => {} };
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `object literal method is contextually typed by the annotation member type`() {
        diagnose("""
            interface Handlers { onFoo(a: number): void; }
            var h: Handlers = { onFoo(a) { } };
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `object literal member under a union-with-primitive contextual type fires TS7006`() {
        diagnose("""
            var v: string | { r: (m: string) => string } = { r: m => m };
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `returned object literal factory members inherit the return annotation context`() {
        diagnose("""
            interface Checker { isX: (s: number) => boolean; }
            function create(): Checker {
                return { isX: s => true };
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `parenthesized returned object literal keeps the return annotation context`() {
        diagnose("""
            interface Checker { isX: (s: number) => boolean; }
            function create(): Checker {
                return ({ isX: s => true });
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `arrow expression body inherits the contextual signature return type`() {
        diagnose("""
            interface Inner { f: (a: number) => boolean; }
            var o: { make: (s: string) => Inner } = { make: s => ({ f: a => true }) };
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `logical-or operands inherit the annotated var context`() {
        diagnose("""
            declare let maybe: ((x: number) => void) | undefined;
            const c: (x: number) => void = maybe || (x => {});
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `namespace-local alias annotation resolves for the assignment context`() {
        diagnose("""
            namespace B {
                export type Fn = (a: number) => void;
                export function g() {
                    let h: Fn;
                    h = a => {};
                }
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `array element object literal inherits the element type context`() {
        diagnose("""
            interface P { high: (t: string) => number; }
            const priorities: P[] = [{ high: t => t.length }];
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `bare arrow element under a direct array annotation is contextually typed`() {
        diagnose("""
            type Cb = (x: number) => void;
            var arr: Cb[] = [x => {}];
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `destructuring default with typed function contextually types the matched property`() {
        diagnose("""
            const { fn1 = (x: number) => 0 } = { fn1: x => x + 1 };
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `destructuring default without typed function keeps TS7006 on the property arrow`() {
        diagnose("""
            const { fn2 = 3 } = { fn2: x => x + 1 };
        """) should {
            have(any { it.code == 7006 })
        }
    }

    // ── reach quirks (bug-compat pins) ──────────────────────────────────────

    @Test
    fun `negative control - while body statements are not walked`() {
        diagnose("""
            function g() {
                while (1) {
                    var v = (a) => a;
                }
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - switch clause statements are not walked`() {
        diagnose("""
            function g(k: number) {
                switch (k) {
                    default:
                        var v = (a) => a;
                }
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - try block statements are not walked`() {
        diagnose("""
            function g() {
                try {
                    var v = (a) => a;
                } catch (e) {}
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - for-of body statements are not walked`() {
        diagnose("""
            function g(items: string[]) {
                for (const it of items) {
                    var v = (a) => a;
                }
            }
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `if branches and for bodies are walked`() {
        diagnose("""
            function g(k: number) {
                if (k) {
                    var v1 = (a) => a;
                }
                for (;;) {
                    var v2 = (b) => b;
                }
            }
        """) should {
            have(count { it.code == 7006 } == 2)
        }
    }

    @Test
    fun `negative control - a call callee function expression is not walked`() {
        diagnose("""
            (function (a) { return a; })(1);
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - a conditional condition is not walked`() {
        diagnose("""
            var r = ((a) => a) ? 1 : 2;
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - an as-cast operand is not walked`() {
        diagnose("""
            var v = ((a) => a) as any;
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - object literal accessor bodies are not walked`() {
        diagnose("""
            var o = {
                get g() {
                    var v = (a) => a;
                    return 1;
                }
            };
        """) should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `negative control - class static block bodies are not walked`() {
        diagnose("""
            class C {
                static {
                    var v = (a) => a;
                }
            }
        """, directives = "// @strict: true\n// @target: es2022") should {
            have(none { it.code == 7006 })
        }
    }

    @Test
    fun `namespace body statements are walked`() {
        diagnose("""
            namespace N {
                export function g(x) { return x; }
            }
        """) should {
            have(any { it.code == 7006 })
        }
    }

    @Test
    fun `method and constructor bodies are walked`() {
        diagnose("""
            class C {
                constructor() {
                    var v1 = (a) => a;
                }
                m() {
                    var v2 = (b) => b;
                }
            }
        """) should {
            have(count { it.code == 7006 } == 2)
        }
    }

    @Test
    fun `class expression setter fires TS7032 even with a sibling getter`() {
        diagnose("""
            var C = class {
                get s(): number { return 1; }
                set s(v) {}
            };
        """) should {
            have(any { it.code == 7032 })
        }
    }
}

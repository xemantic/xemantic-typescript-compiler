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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (M0.4, round 650): pins for the checkConstructorParamInInitializers
 * (TS2301 — an instance field initializer referencing a ctor param /
 * ctor-body var; TS2663 — the parameter-property variant with no real
 * outer binding, "Did you mean the instance member 'this.X'?") spine
 * migration. The legacy pass reaches class declarations/expressions via a
 * mutually-recursive statement + expression walk and calls the emission
 * leaf checkConstructorParamInClassMembers per reached class; the emission
 * leaf has its OWN nested-scope-shadowing walk of the class's own property
 * initializers. Frozen REACH quirks pinned in BOTH directions: statement
 * bodies (fn/namespace/block/if-then/loop-body/try/catch/switch-clause) are
 * reached but loop/switch/if HEADS and ternary CONDITIONS are not;
 * arrow/fn-expr bodies route into a RESTRICTED walk (only Expression/
 * Return/Variable statements — a class DECLARATION directly in an arrow
 * body is NOT reached), class DECLARATIONS descend method/ctor/accessor
 * bodies AND property initializers while class EXPRESSIONS descend property
 * initializers ONLY. TS2301 is unique to this pass; TS2663 is also emitted
 * by the general spelling-suggestion path, so its counts are calibrated
 * against the pre-migration walker. All expectations verified against the
 * legacy pass FIRST.
 */
class M04CtorParamInitSpineMigrationTest {

    private fun ts2301(ds: List<Diagnostic>) = ds.count { it.code == 2301 }
    private fun ts2663(ds: List<Diagnostic>) = ds.count { it.code == 2663 }

    // ── TS2301 — the core emission (unique to this pass) ───────────────────

    @Test
    fun `top-level class - ctor param in field initializer draws TS2301`() {
        val ds = diagnose(
            """
            class C {
              field = param;
              constructor(param: number) {}
            }
            """
        )
        assert(ts2301(ds) == 1)
        val d = ds.single { it.code == 2301 }
        assert(d.length == "param".length)
    }

    @Test
    fun `ctor body var in field initializer draws TS2301`() {
        val ds = diagnose(
            """
            class C {
              field = localVar;
              constructor() {
                var localVar = 5;
              }
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `negative control - ctor body let does NOT count - only var is hoisted`() {
        val ds = diagnose(
            """
            class C {
              field = localVar;
              constructor() {
                let localVar = 5;
              }
            }
            """
        )
        assert(ts2301(ds) == 0)
    }

    @Test
    fun `negative control - static field initializer is not checked`() {
        val ds = diagnose(
            """
            class C {
              static field = param;
              constructor(param: number) {}
            }
            """
        )
        assert(ts2301(ds) == 0)
    }

    // ── TS2663 — parameter-property variant (calibrated) ───────────────────

    @Test
    fun `parameter property in field initializer draws TS2663 not TS2301`() {
        val ds = diagnose(
            """
            class C {
              field = param;
              constructor(private param: number) {}
            }
            """
        )
        assert(ts2301(ds) == 0)
        // The message is unique to the instance-member suggestion.
        assert(ds.any { it.code == 2663 && it.message.contains("this.param") } == true)
    }

    @Test
    fun `parameter property WITH a real outer binding draws TS2301 not TS2663`() {
        // A file-level `var param` is a real outer binding, so the pass
        // reports TS2301 (cannot reference the ctor param) rather than the
        // did-you-mean TS2663.
        val ds = diagnose(
            """
            var param: number = 1;
            class C {
              field = param;
              constructor(private param: number) {}
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `shorthand property assignment referencing ctor param draws TS2301`() {
        val ds = diagnose(
            """
            class C {
              field = { param };
              constructor(param: number) {}
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    // ── REACH: statement-walk positions (reached) ──────────────────────────

    @Test
    fun `class in function body is reached`() {
        val ds = diagnose(
            """
            function f() {
              class C {
                field = param;
                constructor(param: number) {}
              }
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class in namespace body is reached`() {
        val ds = diagnose(
            """
            namespace N {
              class C {
                field = param;
                constructor(param: number) {}
              }
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class in if-then block is reached`() {
        val ds = diagnose(
            """
            declare const cond: boolean;
            if (cond) {
              class C {
                field = param;
                constructor(param: number) {}
              }
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class in while-body is reached`() {
        val ds = diagnose(
            """
            declare const cond: boolean;
            while (cond) {
              class C {
                field = param;
                constructor(param: number) {}
              }
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class in try block is reached`() {
        val ds = diagnose(
            """
            try {
              class C {
                field = param;
                constructor(param: number) {}
              }
            } catch (e) {}
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class in catch block is reached`() {
        val ds = diagnose(
            """
            try {} catch (e) {
              class C {
                field = param;
                constructor(param: number) {}
              }
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class in switch case clause is reached`() {
        val ds = diagnose(
            """
            declare const n: number;
            switch (n) {
              case 1:
                class C {
                  field = param;
                  constructor(param: number) {}
                }
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    // ── REACH: expression-walk positions (reached) ─────────────────────────

    @Test
    fun `class expression in variable initializer is reached`() {
        val ds = diagnose(
            """
            const c = class {
              field = param;
              constructor(param: number) {}
            };
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class expression in a call argument is reached`() {
        val ds = diagnose(
            """
            declare function use(x: unknown): void;
            use(class {
              field = param;
              constructor(param: number) {}
            });
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class expression in an array element is reached`() {
        val ds = diagnose(
            """
            const arr = [class {
              field = param;
              constructor(param: number) {}
            }];
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class expression as object-literal property value is reached`() {
        val ds = diagnose(
            """
            const o = { key: class {
              field = param;
              constructor(param: number) {}
            } };
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class expression in a binary operand is reached`() {
        val ds = diagnose(
            """
            declare const x: unknown;
            const c = x && class {
              field = param;
              constructor(param: number) {}
            };
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class expression in ternary whenTrue is reached`() {
        val ds = diagnose(
            """
            declare const cond: boolean;
            const c = cond ? class {
              field = param;
              constructor(param: number) {}
            } : null;
            """
        )
        assert(ts2301(ds) == 1)
    }

    // ── REACH: expression-walk positions (NOT reached) ─────────────────────

    @Test
    fun `class expression in ternary CONDITION is NOT reached`() {
        val ds = diagnose(
            """
            const c = (class {
              field = param;
              constructor(param: number) {}
            }) ? 1 : 2;
            """
        )
        assert(ts2301(ds) == 0)
    }

    // ── REACH: class member descent (DECL vs EXPR asymmetry) ───────────────

    @Test
    fun `nested class in a class-DECLARATION method body is reached`() {
        val ds = diagnose(
            """
            class Outer {
              method() {
                class Inner {
                  field = param;
                  constructor(param: number) {}
                }
              }
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `nested class in a class-EXPRESSION method body is NOT reached`() {
        val ds = diagnose(
            """
            const c = class {
              method() {
                class Inner {
                  field = param;
                  constructor(param: number) {}
                }
              }
            };
            """
        )
        assert(ts2301(ds) == 0)
    }

    @Test
    fun `class expression in a class-EXPRESSION property initializer is reached`() {
        val ds = diagnose(
            """
            const c = class {
              field2 = class Inner {
                inner = param;
                constructor(param: number) {}
              };
            };
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `nested class in a class-DECLARATION property initializer is reached`() {
        val ds = diagnose(
            """
            class Outer {
              field2 = class Inner {
                inner = param;
                constructor(param: number) {}
              };
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    // ── REACH: arrow / function-expression restricted body ─────────────────

    @Test
    fun `class expression in a variable initializer inside an arrow body is reached`() {
        val ds = diagnose(
            """
            const factory = () => {
              const c = class {
                field = param;
                constructor(param: number) {}
              };
            };
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class DECLARATION directly in an arrow body is NOT reached`() {
        // The arrow-body walk is RESTRICTED to Expression/Return/Variable
        // statements — a ClassDeclaration statement is not one of them.
        val ds = diagnose(
            """
            const factory = () => {
              class C {
                field = param;
                constructor(param: number) {}
              }
            };
            """
        )
        assert(ts2301(ds) == 0)
    }

    @Test
    fun `class expression in an arrow expression-body is reached`() {
        val ds = diagnose(
            """
            const factory = () => class {
              field = param;
              constructor(param: number) {}
            };
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `class DECLARATION inside an arrow-body if-statement is NOT reached`() {
        val ds = diagnose(
            """
            declare const cond: boolean;
            const factory = () => {
              if (cond) {
                class C {
                  field = param;
                  constructor(param: number) {}
                }
              }
            };
            """
        )
        assert(ts2301(ds) == 0)
    }

    // ── EMISSION: nested-scope shadowing inside the class's own initializer ─

    @Test
    fun `ctor param referenced from an arrow in a field initializer draws TS2301`() {
        val ds = diagnose(
            """
            class C {
              field = () => param;
              constructor(param: number) {}
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    @Test
    fun `a BLOCK-bodied arrow param shadowing the ctor param suppresses TS2301`() {
        val ds = diagnose(
            """
            class C {
              field = (param: string) => { return param; };
              constructor(param: number) {}
            }
            """
        )
        assert(ts2301(ds) == 0)
    }

    @Test
    fun `an EXPRESSION-bodied arrow param does NOT shadow the ctor param - frozen quirk`() {
        // The emission leaf collects arrow params for shadowing only for a
        // BLOCK body; an expression-bodied arrow walks its body with the
        // UN-shadowed ctor-name set, so the ctor param still fires.
        val ds = diagnose(
            """
            class C {
              field = (param: string) => param;
              constructor(param: number) {}
            }
            """
        )
        assert(ts2301(ds) == 1)
    }

    // ── declare class ──────────────────────────────────────────────────────

    @Test
    fun `negative control - declare class is not checked`() {
        val ds = diagnose(
            """
            declare class C {
              field: number;
              constructor(param: number);
            }
            """
        )
        assert(ts2301(ds) == 0)
        assert(ts2663(ds) == 0)
    }
}

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
 * INV.4(c)(iii) batch 3 (round 526): the checkUnresolvedNames family's
 * CLASS-ELEMENT emissions migrated onto the check spine —
 * `checkUnresolvedInClassElement` is deleted; property/method decorators and
 * computed names dispatch at the member's enter (the pre-population moment =
 * the legacy pre-registration view, B98.r111), TP/param/return-type positions
 * at child enters via the shared `spineUResFnSigDispatch` (per-member-kind
 * flags reproduce each legacy arm's exact coverage: methods/constructors
 * check param decorators + initializers, set-accessors param TYPES only,
 * get-accessors return type only), index signatures at enter in the CLASS
 * scope. All gated to class decl/expr parents — interface members stay with
 * the batch-2 interface handler, objlit/type-literal members with their
 * still-legacy walkers.
 */
class Inv4SpineBatch17Test {

    @Test
    fun `method computed name does not see its own params or TPs`() {
        // B98.r111: `[p]` is evaluated at class-definition time — the method's
        // own parameter `p` is NOT in scope there.
        diagnose(
            """
            class C {
                [p]<T>(p: number): void {}
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2304 || it.code == 2552 })
        }
    }

    @Test
    fun `negative control - method param type sees the method TP`() {
        diagnose(
            """
            class C {
                m<T>(x: T): T { return x; }
            }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `method TP constraint with unresolved name fires TS2304`() {
        diagnose(
            """
            class C {
                m<T extends MissingConstraint>(x: T): void {}
            }
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `method param type and initializer are checked`() {
        diagnose(
            """
            class C {
                m(x: MissingParamType, y = missingInit) {}
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(count { it.code == 2304 } == 2)
        }
    }

    @Test
    fun `method return type is checked`() {
        diagnose(
            """
            class C {
                m(): MissingReturnType { return null as any; }
            }
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `constructor param property initializer is checked`() {
        diagnose(
            """
            class C {
                constructor(public x: number = missingName) {}
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `setter param type is checked`() {
        diagnose(
            """
            class C {
                set s(v: MissingT) {}
            }
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `getter return type is checked`() {
        diagnose(
            """
            class C {
                get g(): MissingT { return null as any; }
            }
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `class index signature value and key types are checked`() {
        diagnose(
            """
            class C {
                [k: string]: MissingValueT;
            }
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `property initializer referencing ctor param is TS2301 territory not TS2304`() {
        // The legacy propScope adds ctor param names so TS2304/TS2552 stay
        // silent (TS2301 owns the semantic error) — instance members only.
        diagnose(
            """
            class C {
                p = cp;
                constructor(cp: number) {}
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(none { it.code == 2304 || it.code == 2552 })
        }
    }

    @Test
    fun `static property initializer does NOT see ctor param names`() {
        diagnose(
            """
            class C {
                static sp = cp;
                constructor(cp: number) {}
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `property type annotation and initializer are checked`() {
        diagnose(
            """
            class C {
                p: MissingPropType = missingInit;
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(count { it.code == 2304 } == 2)
        }
    }

    @Test
    fun `property computed name is checked`() {
        diagnose(
            """
            class C {
                [missingKey]: string;
            }
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `method decorator expression is checked`() {
        diagnose(
            """
            class C {
                @missingDec
                m(): void {}
            }
            """,
            directives = "// @strict: false\n// @experimentalDecorators: true",
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `method param decorator expression is checked`() {
        diagnose(
            """
            class C {
                m(@missingParamDec x: number): void {}
            }
            """,
            directives = "// @strict: false\n// @experimentalDecorators: true",
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `class expression member signatures are checked`() {
        diagnose(
            """
            const C = class {
                m(x: MissingT): void {}
            };
            """,
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `class expression property initializer is checked`() {
        diagnose(
            """
            const C = class {
                p = missingInit;
            };
            """,
            directives = "// @strict: false",
        ) should {
            have(any { it.code == 2304 })
        }
    }

    @Test
    fun `declare class member signatures stay unchecked`() {
        diagnose(
            """
            declare class C {
                p: MissingPropType;
                m(x: MissingParamType): MissingReturnType;
                get g(): MissingT;
                [k: string]: MissingIndexT;
            }
            """,
        ) should {
            have(none { it.code == 2304 })
        }
    }

    @Test
    fun `negative control - interface members are still checked once`() {
        // Interface members belong to the batch-2 interface handler — the
        // class-member dispatch must not double-emit for them.
        diagnose(
            """
            interface I {
                m(x: MissingT): void;
            }
            """,
        ) should {
            have(count { it.code == 2304 } == 1)
        }
    }

    @Test
    fun `class member positions emit exactly once`() {
        diagnose(
            """
            class C {
                m(x: MissingT): void {}
            }
            """,
        ) should {
            have(count { it.code == 2304 } == 1)
        }
    }
}

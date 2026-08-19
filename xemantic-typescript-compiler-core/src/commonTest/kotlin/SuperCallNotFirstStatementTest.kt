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
 * TS2376 — `A 'super' call must be the first statement in the constructor …`.
 *
 * PRISTINE tsc does NOT require `super()` to be literally first: it walks the
 * constructor's own statement list until either the super call or the first statement
 * that IMMEDIATELY references `this`/`super` (tsc's `nodeImmediatelyReferencesSuperOrThis`
 * + `isThisContainerOrFunctionBlock`), and only the second outcome is an error. Round 941
 * measured this against pristine's own `derivedClassSuperProperties` — **13 false
 * positives**, one per nested function-like shape that is legal before `super()`.
 *
 * The negative pins below are those shapes; the positive controls are the ones that must
 * keep firing, because an FP fix that simply disables a check passes every negative pin.
 */
class SuperCallNotFirstStatementTest {

    private val target = "// @target: es2015"

    @Test
    fun `a function declaration before super is not TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    function declaration() {
                        return this;
                    }
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }

    @Test
    fun `an arrow function invoked before super is not TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    (() => this)();
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }

    @Test
    fun `an object literal method body using this before super is not TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    const obj = {
                        getProp() {
                            return this;
                        }
                    };
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }

    @Test
    fun `an object literal accessor body using this before super is not TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    const obj = {
                        get prop() {
                            return this;
                        },
                        set prop(_: any) {}
                    };
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }

    @Test
    fun `a class expression method body using this before super is not TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    const inner = class {
                        method() {
                            return this;
                        }
                    };
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }

    @Test
    fun `a class expression property initializer using this before super is not TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    const inner = class {
                        bar = this;
                    };
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }

    @Test
    fun `a plain statement before super is not TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    const n = 1;
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }

    @Test
    fun `a parenthesized super call counts as the super call statement`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    (super());
                    this.prop;
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }

    @Test
    fun `a prologue directive before super is not TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    "use strict";
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }

    // ------------------------------------------------------------------ positive controls

    @Test
    fun `a this reference before super is still TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    const n = this;
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(any { it.code == 2376 }) }
    }

    @Test
    fun `a super property access before super is still TS2376`() {
        diagnose(
            """
            class Base { m() {} }
            class Derived extends Base {
                prop = true;
                constructor() {
                    super.m();
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(any { it.code == 2376 }) }
    }

    @Test
    fun `a computed member NAME using this before super is still TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                propName = "p";
                constructor() {
                    const obj = {
                        [this.propName]: true,
                    };
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(any { it.code == 2376 }) }
    }

    @Test
    fun `a parameter property still requires the super call to come first`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                constructor(public p: number) {
                    this.p;
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(any { it.code == 2376 }) }
    }

    @Test
    fun `a computed accessor NAME using this before super is still TS2376`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                propName = "p";
                constructor() {
                    const obj = {
                        get [this.propName]() {
                            return true;
                        }
                    };
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(any { it.code == 2376 }) }
    }

    @Test
    fun `a plain member NAME spelled this is not a this reference`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop = true;
                constructor() {
                    const obj = { this: 1 };
                    super();
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }

    @Test
    fun `a derived class with no initialized property is silent`() {
        diagnose(
            """
            class Base {}
            class Derived extends Base {
                prop: boolean;
                constructor() {
                    const n = 1;
                    super();
                    this.prop = true;
                }
            }
            """,
            directives = target,
        ) should { have(none { it.code == 2376 }) }
    }
}

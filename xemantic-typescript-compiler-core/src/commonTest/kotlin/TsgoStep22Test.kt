package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

/**
 * (LEGACY.0b) step 22 — ONE reference line with three faces: in TypeScript 7 a bare
 * `this.p;` expression statement declares NOTHING.
 *
 * `ast.IsExpandoPropertyDeclaration` is `node != nil && IsBinaryExpression(node)`
 * (`utilities.go:4523`), and tsgo's binder reaches `bindThisPropertyAssignment` /
 * `bindExpandoPropertyAssignment` only from its `KindBinaryExpression` and
 * `KindCallExpression` arms (`binder.go:622-692`); a `KindPropertyAccessExpression`
 * statement gets a flow node and nothing else. `GetAssignmentDeclarationKind`
 * (`utilities.go:1522`) additionally requires the operator to be `=` and the left to be an
 * ACCESS expression, so a compound assignment and a parenthesized target declare nothing
 * either. TypeScript 6 declared all of them, which is where our three divergences came from.
 *
 * The three faces, each measured against `tools/tsgo-7.0.2/lib/tsc` before it was written:
 *
 *  1. A JS class's instance-field set — the input to TS2855 `Class field 'X' … via super.`
 *     — is built from `this.X = …` ASSIGNMENTS only ([Checker.collectClassInstanceFields]).
 *  2. A JSDoc `@type {T}` above a non-declaration statement is DROPPED by the reparser
 *     (`reparser.go:369` takes an ExpressionStatement host only when its expression is an
 *     assignment-shaped BinaryExpression), so it cannot make `T` referenced.
 *  3. With (2), a `@template`-carrying class whose only use of its parameters was such a tag
 *     becomes all-unused, which lands on TS6205 — whose anchor is tsgo's
 *     `rangeOfTypeParameters` (`utilities.go:1531`), `[list.Pos() - 1,
 *     skipTrivia(text, list.End()) + 1)`, over the DECLARATION's whole list and never per tag.
 *
 * The TS6205 line/column/length values below are the ones `corpus-screen.sh` proved
 * byte-identical to tsgo's own `unusedTypeParameters_templateTag2` baseline — its multi-line
 * squiggle block renders with no difference at all, which pins the width as well as the start.
 *
 * Every non-control pin here was proven RED against the pre-change binary.
 */
class TsgoStep22Test {

    // ---------------------------------------------------------------- 1. TS2855 field set

    private val jsDirectives = "// @strict: true\n// @allowJs: true\n// @checkJs: true\n// @target: esnext"

    /**
     * Every shape in one class, exactly as measured on tsgo: `this.a = 1` and
     * `this['d'] = 2` DECLARE (so `super.a` / `super['d']` are TS2855); the bare
     * `this.b;` / `this['e'];`, the compound `this.c += 1` and the parenthesized
     * `(this.f) = 3` declare NOTHING, so tsgo reports TS2339/TS7053 for them instead —
     * rows we do not yet emit at all, which is the residue this round names.
     */
    private val shapes = """
        class CB {
            constructor() {
                this.a = 1;
                this.b;
                this.c += 1;
                this['d'] = 2;
                this['e'];
                (this.f) = 3;
            }
        }
        class CD extends CB {
            m() { return [super.a, super.b, super.c, super.d, super.e, super.f]; }
        }
    """

    private fun shapeRows() = diagnose(shapes, jsDirectives, "index.js").filter { it.code == 2855 }

    @Test
    fun `a bare this property statement declares no field`() {
        assert(shapeRows().none { it.message.contains("'b'") })
    }

    @Test
    fun `a bare this element access statement declares no field`() {
        assert(shapeRows().none { it.message.contains("'e'") })
    }

    @Test
    fun `a compound assignment to a this property declares no field`() {
        assert(shapeRows().none { it.message.contains("'c'") })
    }

    @Test
    fun `a parenthesized assignment target declares no field`() {
        assert(shapeRows().none { it.message.contains("'f'") })
    }

    /**
     * Control — the two ASSIGNMENTS still declare, and they are the ONLY two rows.
     * tsgo: `index.js(12,25)` and `index.js(12,52)`, same message and same quoting.
     */
    @Test
    fun `control - only the two this assignments declare a field`() {
        val rows = shapeRows().sortedBy { it.start }
        assert(rows.size == 2)
        assert(rows[0].code == 2855)
        assert(rows[0].message ==
            "Class field 'a' defined by the parent class is not accessible in the child class via super.")
        assert(rows[0].line == 12)
        assert(rows[0].character == 25)
        assert(rows[0].length == 1)
        assert(rows[1].code == 2855)
        assert(rows[1].message ==
            "Class field ''d'' defined by the parent class is not accessible in the child class via super.")
        assert(rows[1].line == 12)
        assert(rows[1].character == 52)
        // `super.d` is a PROPERTY access, so the squiggle is the name — the `''d''`
        // quoting comes from the DECLARATION shape (`this['d'] = 2`), not from the read.
        assert(rows[1].length == 1)
    }

    /**
     * Control — an assignment reached only through an `accessor` property initializer's
     * ARROW still declares, which is what keeps `classFieldSuperNotAccessibleJs`'s `foo`
     * row (tsgo emits TS2855 for it). The walk descends arrows and stops at real function
     * boundaries; a bare read there must still declare nothing.
     */
    @Test
    fun `control - an assignment inside an accessor initializer arrow still declares`() {
        val d = diagnose(
            """
            class AB {
                accessor b = () => { this.foo = 10; this.bar; };
            }
            class AD extends AB {
                m() { return [super.foo, super.bar]; }
            }
            """,
            jsDirectives, "index.js",
        ).filter { it.code == 2855 }
        assert(d.size == 1)
        assert(d[0].message ==
            "Class field 'foo' defined by the parent class is not accessible in the child class via super.")
    }

    /**
     * residue — under `allowJs` WITHOUT `checkJs` tsgo checks the file at all and is
     * SILENT for every row above; we still report TS2855, because
     * `Checker.checkClassFieldSuperAccessJs` is gated on the file EXTENSION and not on
     * `checkJs` ((P18.92)'s hazard). Unchanged by this round in both arms and recorded
     * here so the next reader meets a decision rather than a guarantee; the honest fix is
     * a file-level "an unchecked JS file reports nothing" gate, not a patch on one walker.
     */
    @Test
    fun `residue - allowJs without checkJs still reports TS2855 where tsgo is silent`() {
        val d = diagnose(shapes, "// @strict: true\n// @allowJs: true\n// @target: esnext", "index.js")
        assert(d.count { it.code == 2855 } == 2)
    }

    // ------------------------------------------------- 2. a dropped JSDoc @type tag

    private val jsdocDirectives =
        "// @target: es2015\n// @allowJs: true\n// @checkJs: true\n// @noUnusedParameters: true"

    @Test
    fun `a type tag on a bare this statement references no type parameter`() {
        val d = diagnose(
            """
            /**
             * @template T
             * @template V
             */
            class C1 {
                constructor() {
                    /** @type {T} */
                    this.p;
                }
            }
            """,
            jsdocDirectives, "a.js",
        )
        assert(d.size == 1)
        assert(d[0].code == 6205)
        assert(d[0].message == "All type parameters are unused.")
    }

    @Test
    fun `a type tag on a call statement references no type parameter`() {
        val d = diagnose(
            """
            function zzzCall(x) { return x; }
            /**
             * @template T
             * @template V
             */
            class C2 {
                constructor() {
                    /** @type {T} */
                    zzzCall(1);
                }
            }
            """,
            jsdocDirectives, "a.js",
        )
        assert(d.size == 1)
        assert(d[0].code == 6205)
    }

    /**
     * Control — a `@type` tag on a `this.p = …` ASSIGNMENT IS reparsed
     * (`JSDeclarationKindThisProperty`), so `T` is referenced and only `V` is reported.
     * This is the boundary the rule turns on: same tag, same class, different host.
     */
    @Test
    fun `control - a type tag on a this assignment does reference a type parameter`() {
        val d = diagnose(
            """
            /**
             * @template T
             * @template V
             */
            class C3 {
                constructor() {
                    /** @type {T} */
                    this.p = null;
                }
            }
            """,
            jsdocDirectives, "a.js",
        )
        assert(d.none { it.code == 6205 })
        assert(d.count { it.code == 6196 } == 1)
        assert(d.single { it.code == 6196 }.message == "'V' is declared but never used.")
    }

    /** Control — a `@type` tag on a VariableStatement is reparsed onto its declarator. */
    @Test
    fun `control - a type tag on a variable statement does reference a type parameter`() {
        val d = diagnose(
            """
            /**
             * @template T
             * @template V
             */
            class C4 {
                constructor() {
                    /** @type {T} */
                    let x;
                    x;
                }
            }
            """,
            jsdocDirectives, "a.js",
        )
        assert(d.none { it.code == 6205 })
        assert(d.count { it.code == 6196 } == 1)
        assert(d.single { it.code == 6196 }.message == "'V' is declared but never used.")
    }

    // ------------------------------------------------- 3. the TS6205 list span

    /**
     * Two `@template` tags of ONE class are ONE list. tsgo: `/a.js(2,3)`, and the span runs
     * from one character before the first `@` to one past the ` *` of the closing line —
     * 12 characters of line 2, all 14 of line 3, and the first 2 of line 4, i.e. 30.
     */
    @Test
    fun `two template tags of one class aggregate to one TS6205 over the whole list`() {
        val d = diagnose(
            """
            /**
             * @template T
             * @template V
             */
            class D1 {
                constructor() {
                    /** @type {T} */
                    this.p;
                }
            }
            """,
            jsdocDirectives, "a.js",
        )
        assert(d.size == 1)
        val r = d[0]
        assert(r.code == 6205)
        assert(r.message == "All type parameters are unused.")
        assert(r.line == 2)
        assert(r.character == 3)
        assert(r.length == 30)
    }

    /**
     * A single multi-name tag, the shape whose baseline column this round moved: tsgo says
     * `(2,3)` where TypeScript 6 said `(2,4)`, and the span is one wider at each end —
     * 14 characters of the tag line plus the newline plus the ` *` of the closing line.
     */
    @Test
    fun `a single multi name template tag takes the same one character wider span`() {
        val d = diagnose(
            """
            /**
             * @template T,V
             */
            class D2 { }
            """,
            jsdocDirectives, "a.js",
        )
        assert(d.size == 1)
        val r = d[0]
        assert(r.code == 6205)
        assert(r.line == 2)
        assert(r.character == 3)
        assert(r.length == 17)
    }

    /**
     * There is NO per-tag aggregation. Measured on tsgo: with `@template T,V` used and
     * `@template X,Y` unused, the answer is two per-parameter TS6196 rows at `(3,14)` and
     * `(3,16)` — never a TS6205 over the second tag, which is what we used to emit.
     */
    @Test
    fun `a tag whose siblings on another tag are used never aggregates`() {
        val d = diagnose(
            """
            /**
             * @template T,V
             * @template X,Y
             */
            class D3 {
                /** @param {T} a */
                m(a) { return a; }
                /** @param {V} b */
                n(b) { return b; }
            }
            """,
            jsdocDirectives, "a.js",
        )
        assert(d.none { it.code == 6205 })
        val rows = d.filter { it.code == 6196 }.sortedBy { it.start }
        assert(rows.size == 2)
        assert(rows[0].message == "'X' is declared but never used.")
        assert(rows[0].line == 3)
        assert(rows[0].character == 14)
        assert(rows[1].message == "'Y' is declared but never used.")
        assert(rows[1].line == 3)
        assert(rows[1].character == 16)
    }

    /**
     * Control — a WRITTEN `<…>` list is unaffected: its span is the angle brackets, which
     * is the same `rangeOfTypeParameters` formula reached by the other branch. tsgo on the
     * same file: `t.ts(1,8)`.
     */
    @Test
    fun `control - a written angle bracket list keeps its own TS6205 span`() {
        val d = diagnose(
            """
            class G<T, V> { }
            G;
            """,
            "// @strict: true\n// @noUnusedParameters: true",
            "t.ts",
        )
        val r = d.single { it.code == 6205 }
        assert(r.line == 1)
        assert(r.character == 8)
        assert(r.length == 6)
    }
}

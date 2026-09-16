package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (LEGACY.0b) step 19 — the leading-vs-follow-on index of a duplicate-declaration
 * diagnostic's related information is **per merge CALL**, not per diagnostic.
 *
 * tsgo's `addDuplicateDeclarationError` (checker.go:14158) reads
 * `leading = len(err.RelatedInformation()) == 0`, which READS like a per-diagnostic index
 * and is not one: `lookupOrIssueError` finds an existing diagnostic through
 * `ast.CompareDiagnostics`, whose LAST comparison is `compareRelatedInfo`. The probe a
 * second call builds carries an empty related list, so it no longer compares equal to the
 * diagnostic the first call already decorated — the lookup MISSES, a SECOND diagnostic is
 * issued at the same location, and it too starts empty. `SortAndDeduplicateDiagnostics` ->
 * `compactAndMergeRelatedInfos` (program.go:1444) then folds every diagnostic that is
 * `EqualDiagnosticsNoRelatedInfo` into one and unions their related lists.
 *
 * So the first related node of each CALL is TS6203 `'{0}' was also declared here.` and the
 * rest of THAT call's nodes are TS6204 `and here.`. Verified mechanically against every
 * tsgo baseline carrying such a row: 128 files, 317 diagnostics, 0 unexplained.
 *
 * The two witnesses below are the rule; a pin of only one of them is blind to the other
 * half, because each is satisfied by an over-broad rule the other refuses.
 *  - N SEPARATE FILES -> N-1 calls of one node each -> all TS6203
 *    (tsgo's `duplicateIdentifierRelatedSpans1`);
 *  - ONE symbol with N declarations -> ONE call -> `[6203, 6204, 6204]`
 *    (tsgo's `recursiveComplicatedClasses`).
 *
 * Every expectation here is transcribed from tsgo 7.0.2's own baselines under
 * `typescript-go-repo/testdata/baselines/reference/submodule/compiler/`.
 */
class TsgoStep19Test {

    // ---------------------------------------------------------------------------
    // WITNESS 1 — N separate files, i.e. N-1 merge calls: every related row is TS6203.
    // ---------------------------------------------------------------------------

    /** tsgo `duplicateIdentifierRelatedSpans1.errors.txt`, the `Foo` group. */
    @Test
    fun `a name declared in three files relates from its hub with two TS6203 rows and no follow-on`() {
        val diagnostics = diagnose(
            """
            // @Filename: file1.ts
            class Foo { }
            // @Filename: file2.ts
            type Foo = number;
            // @Filename: file3.ts
            type Foo = 54;
            """,
            directives = "// @target: es2015",
        )
        diagnostics.single { it.code == 2300 && it.fileName == "file1.ts" } should {
            have(message == "Duplicate identifier 'Foo'.")
            have(line == 1)
            have(character == 7)
            have(length == 3)
            val rel = relatedInformation
            have(rel.size == 2)
            rel[0] should {
                have(code == 6203)
                have(message == "'Foo' was also declared here.")
                have(fileName == "file2.ts")
                have(line == 1)
                have(character == 6)
                have(length == 3)
            }
            rel[1] should {
                have(code == 6203)
                have(message == "'Foo' was also declared here.")
                have(fileName == "file3.ts")
                have(line == 1)
                have(character == 6)
                have(length == 3)
            }
        }
    }

    /** The same fixture's `Bar` group: TS2451, and the follow-on index is the same one. */
    @Test
    fun `a block-scoped name declared in three files relates with two TS6203 rows`() {
        val diagnostics = diagnose(
            """
            // @Filename: file1.ts
            const Bar = 3;
            // @Filename: file2.ts
            class Bar {}
            // @Filename: file3.ts
            let Bar = 42
            """,
            directives = "// @target: es2015",
        )
        diagnostics.single { it.code == 2451 && it.fileName == "file1.ts" } should {
            have(message == "Cannot redeclare block-scoped variable 'Bar'.")
            val rel = relatedInformation
            have(rel.size == 2)
            have(rel.all { it.code == 6203 })
            have(rel.all { it.message == "'Bar' was also declared here." })
            have(rel[0].fileName == "file2.ts")
            have(rel[0].line == 1)
            have(rel[0].character == 7)
            have(rel[1].fileName == "file3.ts")
            have(rel[1].line == 1)
            have(rel[1].character == 5)
        }
    }

    /** tsgo `duplicateIdentifierRelatedSpans_moduleAugmentation.errors.txt`. */
    @Test
    fun `two augmentations of one module are two merge calls so both related rows are TS6203`() {
        val diagnostics = diagnose(
            """
            // @Filename: /dir/a.ts
            export const x = 0;
            // @Filename: /dir/b.ts
            export {};

            declare module "./a" {
                export const x = 0;
            }

            declare module "../dir/a" {
                export const x = 0;
            }
            """,
            directives = "// @module: commonjs\n// @target: es2015",
        )
        diagnostics.single { it.code == 2451 && it.fileName == "/dir/a.ts" } should {
            have(message == "Cannot redeclare block-scoped variable 'x'.")
            have(line == 1)
            have(character == 14)
            val rel = relatedInformation
            have(rel.size == 2)
            have(rel.all { it.code == 6203 })
            have(rel.all { it.message == "'x' was also declared here." })
            have(rel.all { it.fileName == "/dir/b.ts" })
            have(rel[0].line == 4)
            have(rel[0].character == 18)
            have(rel[1].line == 8)
            have(rel[1].character == 18)
            have(rel.all { it.length == 1 })
        }
    }

    /** tsgo `exportAsNamespace_augment.errors.txt` — a UMD global plus a module augmentation. */
    @Test
    fun `a UMD namespace augmentation and a module augmentation are two calls so both rows are TS6203`() {
        val diagnostics = diagnose(
            """
            // @Filename: /a.d.ts
            export as namespace a;
            export const x = 0;
            export const conflict = 0;
            // @Filename: /b.ts
            import * as a2 from "./a";

            declare global {
                namespace a {
                    export const y = 0;
                    export const conflict = 0;
                }
            }

            declare module "./a" {
                export const z = 0;
                export const conflict = 0;
            }

            a.x + a.y + a.z + a.conflict;
            a2.x + a2.y + a2.z + a2.conflict;
            """,
            directives = "// @module: commonjs\n// @target: es2015",
        )
        diagnostics.single { it.code == 2451 && it.fileName == "/a.d.ts" } should {
            have(message == "Cannot redeclare block-scoped variable 'conflict'.")
            have(line == 3)
            have(character == 14)
            val rel = relatedInformation
            have(rel.size == 2)
            have(rel.all { it.code == 6203 })
            have(rel.all { it.message == "'conflict' was also declared here." })
            have(rel.all { it.fileName == "/b.ts" })
            have(rel[0].line == 6)
            have(rel[0].character == 22)
            have(rel[1].line == 12)
            have(rel[1].character == 18)
        }
    }

    // ---------------------------------------------------------------------------
    // WITNESS 2 — ONE symbol with N declarations, i.e. ONE call: 6203 then 6204*.
    // This is a control for the change (green on both arms) and the SOLE detector of
    // the over-broad "every related row is TS6203" rule the first witness alone invites.
    // ---------------------------------------------------------------------------

    /** tsgo `recursiveComplicatedClasses.errors.txt`: the merged lib `Symbol`, three declarations. */
    @Test
    fun `a class shadowing the merged lib Symbol is one call so its list is 6203 then two 6204`() {
        val diagnostics = diagnose(
            """
            class Symbol {
            }
            """,
            directives = "// @target: es2015",
        )
        diagnostics.single { it.code == 2300 } should {
            have(message == "Duplicate identifier 'Symbol'.")
            val rel = relatedInformation
            have(rel.size == 3)
            rel[0] should {
                have(code == 6203)
                have(message == "'Symbol' was also declared here.")
                have(fileName == "lib.es5.d.ts")
                have(line == null)
                have(character == null)
            }
            rel[1] should {
                have(code == 6204)
                have(message == "and here.")
                have(fileName == "lib.es2015.symbol.d.ts")
                have(line == null)
                have(character == null)
            }
            rel[2] should {
                have(code == 6204)
                have(message == "and here.")
                have(fileName == "lib.es2015.symbol.wellknown.d.ts")
            }
        }
    }

    /** tsgo `promiseDefinitionTest(target=es2015).errors.txt`: FOUR declarations, one call. */
    @Test
    fun `a class shadowing the merged lib Promise is one call so its list is 6203 then three 6204`() {
        val diagnostics = diagnose(
            """
            class Promise {
            }
            """,
            directives = "// @target: es2015",
        )
        diagnostics.single { it.code == 2300 } should {
            val rel = relatedInformation
            have(rel.size == 4)
            have(rel[0].code == 6203)
            have(rel[0].message == "'Promise' was also declared here.")
            have(rel.drop(1).all { it.code == 6204 })
            have(rel.drop(1).all { it.message == "and here." })
            have(rel.map { it.fileName } == listOf(
                "lib.es5.d.ts",
                "lib.es2015.iterable.d.ts",
                "lib.es2015.promise.d.ts",
                "lib.es2015.symbol.wellknown.d.ts",
            ))
        }
    }

    // ---------------------------------------------------------------------------
    // CONTROLS — shapes whose related list must not change.
    // ---------------------------------------------------------------------------

    /** Two declarations: ONE related row, and the two rules agree on it. */
    @Test
    fun `negative control - a two-file duplicate still relates with a single TS6203 row`() {
        val diagnostics = diagnose(
            """
            // @Filename: file1.ts
            class Foo { }
            // @Filename: file2.ts
            type Foo = number;
            """,
            directives = "// @target: es2015",
        )
        diagnostics.single { it.code == 2300 && it.fileName == "file1.ts" } should {
            val rel = relatedInformation
            have(rel.size == 1)
            have(rel[0].code == 6203)
            have(rel[0].message == "'Foo' was also declared here.")
            have(rel[0].fileName == "file2.ts")
        }
        diagnostics.single { it.code == 2300 && it.fileName == "file2.ts" } should {
            val rel = relatedInformation
            have(rel.size == 1)
            have(rel[0].code == 6203)
            have(rel[0].fileName == "file1.ts")
        }
    }

    /**
     * Every NON-hub declaration of the three-file shape points back at the hub alone, with
     * exactly one TS6203 row — the half of the hub model the change does not touch.
     */
    @Test
    fun `negative control - each non-hub declaration of a three-file duplicate relates only to the hub`() {
        val diagnostics = diagnose(
            """
            // @Filename: file1.ts
            class Foo { }
            // @Filename: file2.ts
            type Foo = number;
            // @Filename: file3.ts
            type Foo = 54;
            """,
            directives = "// @target: es2015",
        )
        for (name in listOf("file2.ts", "file3.ts")) {
            val d = diagnostics.single { it.code == 2300 && it.fileName == name }
            assert(d.relatedInformation.size == 1)
            assert(d.relatedInformation[0].code == 6203)
            assert(d.relatedInformation[0].fileName == "file1.ts")
            assert(d.relatedInformation[0].line == 1)
            assert(d.relatedInformation[0].character == 7)
        }
    }

    /**
     * The OTHER producer of TS6204 in this compiler — tsgo's `checker.go:14859`, whose
     * leading row is TS2728 `'{0}' is declared here.` — is a different family and must
     * keep its own index. `importNonExportedMember3` is tsgo's baseline for it.
     */
    @Test
    fun `negative control - the TS2728 related family keeps its own leading row`() {
        val diagnostics = diagnose(
            """
            // @Filename: a.ts
            interface Foo {}
            interface Foo {}
            // @Filename: b.ts
            import { Foo } from "./a";
            declare const f: Foo;
            """,
            directives = "// @target: es2015",
        )
        diagnostics.single { it.code == 2459 } should {
            have(message == "Module '\"./a\"' declares 'Foo' locally, but it is not exported.")
            val rel = relatedInformation
            have(rel.size == 2)
            have(rel[0].code == 2728)
            have(rel[0].message == "'Foo' is declared here.")
            have(rel[0].fileName == "a.ts")
            have(rel[0].line == 1)
            have(rel[1].code == 6204)
            have(rel[1].message == "and here.")
            have(rel[1].fileName == "a.ts")
            have(rel[1].line == 2)
        }
    }
}

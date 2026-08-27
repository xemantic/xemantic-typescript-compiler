package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.test.Test

/**
 * (CHK.61c) A type reference written INSIDE a `namespace` body resolves against that
 * namespace's own exports BEFORE the file/global scope.
 *
 * ## The defect, and why nothing here could see it
 *
 * `getTypeFromTypeReference` consulted [Checker.resolveTypeNameToSymbol] first and used
 * [Checker.lookupTypeSymbolInInferenceNamespace] only as a FALLBACK — i.e. the enclosing
 * namespace was asked only when nothing else answered. So a namespace member whose name
 * ALSO exists at file/global scope resolved to the OUTER declaration, which is the
 * opposite of TypeScript's scoping rule and is silent in the direction that matters: the
 * outer type is a real type, so the annotation is judged against the WRONG shape rather
 * than against none.
 *
 * Measured against tsc 7.0.2 over `build/chk61/p6/a.ts`, three rows byte-exact after and
 * one of them absent before. The shape it was found through is tsc's own
 * `variableDeclaratorResolvedDuringContextualTyping` corpus case, where
 * `namespace WinJS { declare class Promise { then(): Promise } }` resolved `Promise` to
 * the LIB `Promise<T>` — the pristine baseline reports nothing at that line and this
 * compiler reported `Type 'Promise<T>' is missing the following properties from type
 * 'TPromise<IUploadResult>': done, cancel` the moment a `this` receiver made the call
 * reachable at all.
 *
 * ## Why the reorder is safe rather than merely plausible
 *
 * The namespace lookup is bounded: it walks ONLY the `SymbolFlags.Module` parent chain of
 * the inference-namespace stack's top entry and answers only for a `SymbolFlags.Type`
 * export, so outside a namespace body it is a null-returning no-op and inside one it can
 * only answer with a name the namespace really declares. A TYPE PARAMETER still wins over
 * both, because `currentTypeParamScope` is consulted ABOVE this line — that is the third
 * pin below, and it is a CONTROL.
 *
 * RESIDUE, measured in the same run and deliberately not taken: a QUALIFIED reference
 * `ZzzNs.ZzzColl` from OUTSIDE the namespace still falls through the round-444
 * last-segment `globals[name]` fallback to the global generic and reports a spurious
 * TS2314. That path takes a `QualifiedName` and is untouched here.
 */
class NamespaceTypeNameShadowingTest {

    private val prelude = """
        interface ZzzColl<T> { zzzA(): T }
        interface ZzzOnlyGlobal { zzzG(): number }
        namespace ZzzNs {
          export declare class ZzzColl { zzzA(): number; zzzB(): string }
          export interface ZzzTp { zzzN: number }
          export declare class ZzzHolder {
            zzzM(): ZzzColl;
            zzzO(): ZzzOnlyGlobal;
          }
          export declare class ZzzGen<ZzzTp> {
            zzzId(x: ZzzTp): ZzzTp;
          }
        }
        declare const zzzH: ZzzNs.ZzzHolder;
        declare const zzzG: ZzzNs.ZzzGen<string>;
    """.trimIndent() + "\n"

    /**
     * POSITIVE — the namespace's own `ZzzColl` wins, so its `zzzB` exists and returns
     * `string`. Before the fix the reference resolved to the GLOBAL `ZzzColl<T>`, which
     * declares no `zzzB` at all, and the row was `TS2339: Property 'zzzB' does not exist`.
     */
    @Test
    fun `a namespace-local type wins over a same-named global`() {
        val d = diagnose(prelude + "const zzzS1: string = zzzH.zzzM().zzzB()\n")
        d should { have(none { it.code == 2339 }) }
        assert(d.none { it.code == 2322 })
    }

    /**
     * POSITIVE with the wording — the member's real return type reaches the write probe.
     * tsc 7.0.2 reports exactly this row at `(16,7)` of `build/chk61/p6/a.ts`.
     */
    @Test
    fun `the namespace-local member type reaches an assignment`() {
        val d = diagnose(prelude + "const zzzS2: number = zzzH.zzzM().zzzB()\n")
        val row = d.single { it.code == 2322 }
        assert(row.message == "Type 'string' is not assignable to type 'number'.")
        assert(row.character == 7)
    }

    /**
     * CONTROL — a name the namespace does NOT export still resolves to the global, so the
     * reorder cannot be a blanket "the namespace answers everything". tsc agrees on both
     * halves; the second is what fails if the global stops being reachable.
     */
    @Test
    fun `control - a name the namespace does not export still resolves globally`() {
        val ok = diagnose(prelude + "const zzzS3: number = zzzH.zzzO().zzzG()\n")
        assert(ok.none { it.code == 2322 || it.code == 2339 })
        val bad = diagnose(prelude + "const zzzS4: string = zzzH.zzzO().zzzG()\n")
        val row = bad.single { it.code == 2322 }
        assert(row.message == "Type 'number' is not assignable to type 'string'.")
    }

    /**
     * CONTROL — a TYPE PARAMETER named like one of the namespace's exports still shadows
     * it, because `currentTypeParamScope` is consulted above the symbol lookup. Without
     * that ordering `zzzId` would answer the interface `ZzzTp` and this row would name it.
     */
    @Test
    fun `control - a type parameter still shadows a namespace export of the same name`() {
        val d = diagnose(prelude + "const zzzS6: number = zzzG.zzzId(\"a\")\n")
        val row = d.single { it.code == 2322 }
        assert(row.message == "Type 'string' is not assignable to type 'number'.")
    }
}

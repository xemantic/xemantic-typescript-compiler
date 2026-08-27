package com.xemantic.typescript.compiler

import com.xemantic.kotlin.test.assert
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
        interface ZzzColl { zzzA(): boolean }
        interface ZzzOnlyGlobal { zzzG(): number }
        namespace ZzzNs {
          export declare class ZzzColl { zzzA(): number; zzzB(): string }
          export declare const ZzzOnlyGlobal: number;
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
     * POSITIVE — the namespace's own `ZzzColl` wins, so `zzzA()` returns `number` (the
     * global's returns `boolean`) and both rows below are silent, as they are in tsc 7.0.2.
     *
     * TWO DRAFTS OF THIS PIN WERE BLIND AND THE ABLATION IS WHAT SAID SO. Asserting the
     * ABSENCE of `TS2339: Property 'zzzB' does not exist` reads GREEN against an ablated
     * binary in BOTH shapes of the global: written generic (`interface ZzzColl<T>`) the
     * wrong resolution answers `errorType` through TS2314 and no member is ever looked
     * up; written non-generic the member lookup is still not what reports. Only a
     * DIFFERING RETURN TYPE discriminates, which is why the two declarations of `zzzA`
     * disagree on purpose.
     */
    @Test
    fun `a namespace-local type wins over a same-named global`() {
        val d = diagnose(prelude + "const zzzS0: number = zzzH.zzzM().zzzA()\n")
        assert(d.none { it.code == 2322 })
        assert(d.none { it.code == 2339 })
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
     * CONTROL — a name the namespace exports only as a VALUE (`export declare const
     * ZzzOnlyGlobal: number`) does NOT shadow the global TYPE of that name, which is
     * TypeScript's rule and is what [Checker.lookupTypeSymbolInInferenceNamespace]'s
     * `SymbolFlags.Type` filter buys. Dropping that filter is ablation arm a4 and this
     * is the only pin that sees it. tsc 7.0.2 agrees on both halves.
     */
    @Test
    fun `control - a value-only namespace export does not shadow a global type`() {
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

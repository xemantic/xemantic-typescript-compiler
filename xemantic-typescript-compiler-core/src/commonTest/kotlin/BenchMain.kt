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

// NOT `com.xemantic.typescript.compiler` — this file declares a top-level
// `main(Array<String>)`, and so does `src/commonMain/kotlin/Main.kt`. On the JVM those
// are two distinct classes (`MainKt` / `BenchMainKt`) and coexist happily; Kotlin/Native
// mangles a top-level function to `kfun:<package>#main(kotlin.Array<kotlin.String>){}`
// with NO file component, so in one package they are ONE symbol — and the test binary
// links the main klib together with the test klib, which makes it a hard `ld.lld:
// duplicate symbol` at link time (round 775). Only the native TEST link can see this:
// the main executable link never pulls in commonTest. Keep any future commonTest
// entry point out of the compiler's own package.
package com.xemantic.typescript.compiler.bench

import com.xemantic.typescript.compiler.ArgSections
import com.xemantic.typescript.compiler.CallSections
import com.xemantic.typescript.compiler.CpaSections
import com.xemantic.typescript.compiler.CtaSections
import com.xemantic.typescript.compiler.TavGate
import com.xemantic.typescript.compiler.FltmCensus
import com.xemantic.typescript.compiler.FrontEnd
import com.xemantic.typescript.compiler.ReachCensus
import com.xemantic.typescript.compiler.ReachMemoCensus
import com.xemantic.typescript.compiler.LibTypeCensus
import com.xemantic.typescript.compiler.IterCensus
import com.xemantic.typescript.compiler.MapCensus
import com.xemantic.typescript.compiler.CrawlParseCache
import com.xemantic.typescript.compiler.NameCensus
import com.xemantic.typescript.compiler.SpineSections
import com.xemantic.typescript.compiler.SrcScan
import com.xemantic.typescript.compiler.ParallelCheckMode
import com.xemantic.typescript.compiler.NodeAnswers
import com.xemantic.typescript.compiler.ShareBind
import com.xemantic.typescript.compiler.PassTiming
import com.xemantic.typescript.compiler.ProjectCompiler
import com.xemantic.typescript.compiler.SpineAmp
import com.xemantic.typescript.compiler.SpineDispatch
import com.xemantic.typescript.compiler.SystemVfs
import kotlin.time.measureTimedValue

/**
 * Warm in-process whole-project benchmark entry point (NOT a test — carries no
 * `@Test` methods, so `jvmTest` runs are unaffected). Each iteration performs a
 * complete rebuild: tsconfig load, glob discovery, module resolution, parse,
 * bind and check.
 *
 * This runs **check-only by default**: `noEmit = true` reaches `ProjectCompiler`'s
 * `skipEmitOutputs`, so since round 738 nothing is transformed or emitted at all
 * (the earlier "emit-to-memory, only the disk writes are skipped" note here
 * predates that gate and was stale). The number is therefore directly comparable
 * to the arc's `--noEmit` figures — `ab-interleaved.sh` and `cost_gate.py` — and
 * NOT to the emit-mode CI ratio in `bench-history/`.
 *
 * **Round 863 adds a 5th argument, `emit`**, which flips exactly that literal.
 * It exists because a check-only harness is STRUCTURALLY blind to everything
 * round 738's gate skips — `Transformer.transform` and `Emitter.emit` have zero
 * calls under `--noEmit`, so a whole-program cost inside them is invisible to
 * every warm instrument in this repo, to `cost_gate.py`, and to the
 * `--noEmit --listAll` 8-profile grid at once. Name the MODE with any ratio
 * taken from it (round 739): the two modes are different compiles.
 *
 * **This is the only harness that measures a WARM compile.** `bench-compile-tsc.sh`
 * forks a fresh JVM per run, so its `--warmup N` warms the page cache and never
 * the JIT; every archived CI row is a cold single-shot.
 *
 * ## The WARM per-pass table (opt-in 4th argument)
 *
 * Every `--passTiming` attribution table in `docs/perf` was produced by a COLD
 * one-shot `MainKt` JVM, so every per-pass row on record prices interpreted or
 * half-compiled code. Passing `passTiming` as the 4th argument makes this
 * harness run ONE ADDITIONAL instrumented rebuild **after** the measured loop
 * and dump [PassTiming]'s table from it — i.e. attribution over a fully
 * JIT-compiled compiler, which no other harness can produce.
 *
 * Two properties make that number trustworthy, and a change here must keep
 * both:
 *
 *  1. **The probe never runs inside a measured iteration.** The instrumentation
 *     is not free (round 733 measured the probe alone moving `checkSpine` by
 *     +29 ms, and the section probes cost far more), so the reported `medianMs`
 *     must stay probe-free. The instrumented rebuild prints its OWN wall ms on
 *     a separate `instrumented` line, which is what prices the probe against
 *     the uninstrumented median rather than hiding inside it.
 *  2. **It self-falsifies like every other iteration.** An instrumented rebuild
 *     that answers a different program is not a measurement of this one, so its
 *     `files`/`errors` are compared against the measured iterations and a
 *     disagreement aborts with a marked line and a non-zero exit.
 *
 * Usage:
 * ```
 * java -cp <test-classpath> com.xemantic.typescript.compiler.bench.BenchMainKt \
 *     <projectDir> <warmupIters> <measuredIters> [passTiming|<tier>[,<tier>...]]
 * ```
 *
 * Since round 846 the 4th argument may instead be a comma-separated list of
 * probe TIERS (`rows` | `spine` | `full`), one instrumented rebuild each, in
 * the given order — `rows,full,rows,full` measures the SAME warm code at ~513
 * probe boundaries and at ~2 M, twice each, in one process. `full − rows` is
 * then the probe's own price, differentially, with nothing else varying; and
 * the `rows` table's absolutes are the ones a warm lever can be sized against.
 * `passTiming` remains an exact alias for `full`.
 *
 * Round 847 adds a fourth tier name, `dispatch`, which is NOT a `PassTiming`
 * tier at all: it leaves the pass probe off and runs `SpineDispatch.PROBE`
 * (round 732) for one rebuild, printing the per-handler x per-kind report and
 * its CSV. That is the only instrument that can attribute `checkSpine` — 66% of
 * the warm artifact — below the enter/leave split, and until now it had only
 * ever been run in a COLD one-shot JVM.
 * Prints one JSON object per measured iteration, then a `summary` line — and,
 * with the 4th argument present, an `instrumented` line followed by the INV.0
 * pass-timing table. The 4th argument is OFF unless it is `passTiming` / `true`
 * / `1`; omitting it leaves this harness behaving exactly as it did before the
 * argument existed (this is what `scripts/ab-warm.sh` invokes, and its parser
 * reads only the `iter` lines).
 */
/**
 * The probe tiers this harness understands as its 4th argument.
 *
 * `rows` / `spine` / `full` are [PassTiming]'s three tiers (round 846) and
 * `dispatch` is round 847's per-handler [SpineDispatch] probe. Round 849 adds
 * the four INTRA-HANDLER probes that until now had only ever been run in a COLD
 * one-shot JVM — `cta` (`spineCtaM3StatementAnchor`, 17.7% of the warm artifact),
 * `cpa` (`cpaSpineLeave`, 12.8%) and `arg` (`checkArgumentsAgainstSignature`,
 * reached from the largest handler `ccetSpineLeave`) — each with its `*coarse`
 * counterpart, because round 734's law is that a probe boundary may only be
 * priced by an ON-vs-COARSE DIFFERENTIAL and never by an empty-span loop. Round
 * 847 measured that a probe boundary is ~1.85x more expensive cold than warm, so
 * every cold section table on record needs its own warm calibration before its
 * rows can be read as warm shares. `libtypes` is round 849's (WARM.3) census.
 */
internal val TIERS = listOf(
    "rows", "spine", "full", "dispatch",
    "cta", "ctacoarse", "cpa", "cpacoarse", "arg", "argcoarse", "libtypes",
    // (WARM.5) round 851 — the LAST unprobed region of the warm top four:
    // `checkSingleCallExpressionTypes`, i.e. the ~60% of `ccetSpineLeave` that
    // `arg` does not reach (callee resolution, overload selection, the round-793
    // prologue).
    "call", "callcoarse",
    // (WARM.6) round 859 — the FRONT END, which is 11.1% of the warm artifact
    // and whose only attribution (`docs/perf/front-end-attribution.md`, round
    // 738; its bind level, round 801) was taken COLD. The `rows` tier prices the
    // front end only as a RESIDUAL (wall - checker-init); `FrontEnd` is the
    // instrument that splits it into config / crawl / parse / imports / bind and
    // bind into its three components. It has no `*coarse` twin and needs none:
    // its spans are per-FILE (78 files, ~20 pairs each) rather than per-node, so
    // its boundary cost is microseconds against a ~900 ms region — round 738
    // stated that and it is regime-independent.
    "frontend",
    // (SPINE.1) round 908 — round 733's `SpineSections`, which had NEVER been
    // run warm: it is the only probe that partitions `cpaSpineLeave` and
    // `ccetSpineLeave` THEMSELVES (anchor / owner / restores / vardecl / frame
    // pop, plus the two frame-ambient installs and the three ancestor climbs as
    // nested sub-measures), where `cpa` and `call` partition their PAYLOADS one
    // frame down. That is exactly the (SPINE.1) thesis — "per-node bookkeeping
    // that exists to reproduce the deleted walkers' ambient state" — and every
    // number ever taken for it (rounds 733, 799) is a COLD one-shot `MainKt`
    // run, where a probe boundary is 2.5-5x more expensive than warm (round
    // 850) and the handlers' own passes were 29-40% larger.
    //
    // It has no `*coarse` twin and cannot get one cheaply: its sections are a
    // running-timestamp SPLIT of two handlers rather than a nest of levels, so
    // there is no anchor set to keep. Its boundary is therefore priced against
    // the PROBE-FREE median instead — `overheadMs` divided by the boundary count
    // the report itself prints — which is the same differential in its crudest
    // form (the zero-boundary arm is the measured loop) and is what round 733
    // used cold. Give it TWO draws per process: the probe's own code is cold on
    // the first instrumented rebuild (round 846).
    "spinesections",
    // (WARM.19) round 895 — the whole-source substring-scan family, ON and OFF.
    // These two tiers exist because the round's whole claim is a WARM one and its
    // census had only ever been taken COLD: `String.indexOf` warms ~3.8x here and
    // the hand-written filter build warms on its own schedule, so the cold ratio
    // is an inference and these are the measurement. They arm counters plus ONE
    // timestamp pair per scan and per build — affordable, uniquely here, because
    // a whole-source scan is tens of microseconds against a ~90 ns pair.
    "srcscan", "srcscanoff",
    // (WARM.9) round 861 — `init:buildFileLocalTypeMaps`, the ONLY tail pass over
    // 1% warm (268.4 ms = 3.56%, `warm-tail-attribution.md` § 3). Round 829
    // censused it COLD with `--fltmCensus` and closed it at 0.8-1.0%; warm it is
    // 1.28x more important, and projecting a cold population share onto a warm ms
    // row is arithmetic, not measurement. This tier arms that census — and,
    // deliberately, the `rows` pass probe alongside it, so the census's ms and the
    // pass row it must be read against come from the SAME rebuild. They cannot be
    // taken from two: the row's warm draw spread is 41%, the widest in § 3's top
    // twelve.
    "fltm",
    // (WARM.13) round 866 — the two tiers that make a warm A/B possible inside
    // ONE process, and neither arms a timing probe at all.
    //
    // `gated` runs `SpineDispatch.GATED` (round 732): the spine consults only
    // `enterTable[kindId]`/`leaveTable[kindId]` instead of all 59 handlers. It
    // records NOTHING — no timestamps, no counters — so unlike every other tier
    // here its `ms` is a production-comparable wall time and `overheadMs` is the
    // GATED-minus-plain delta directly. It is NOT a stand-in for a production
    // per-kind table: it pays a `when(h)` tableswitch plus a loop and an extra
    // call frame per KEPT handler that a production table would not, so what it
    // measures is a LOWER bound on such a table's prize, never an upper one
    // (`docs/perf/dispatch-table.md` § 8).
    "gated",
    // `plain` arms nothing whatsoever — the NULL arm this harness never had. Its
    // rebuild sits in the tier loop beside `gated`, so `gated,plain,gated,plain`
    // is a rotated interleave of the two arms in one warm process, rather than a
    // tier rebuild held against a median taken before it. It must have its OWN
    // arm in [tierBegin]: the `else` branch ENABLES the pass probe, which would
    // make the control arm the more expensive one and invert the answer.
    "plain",
    // …and the tier that answers the same question with the RIGHT denominator.
    // The wall carries the front end and the ~416 tail passes — together ~34% of
    // a warm rebuild — which are, for this question, pure noise: the table can
    // only ever move `checkSpine`. `gatedrows` arms the GATED dispatch AND the
    // `rows` pass probe, so `gatedrows,rows,gatedrows,rows` gives the ONE row
    // that can move, twice per arm per process, in one warm process. Round 846
    // measured `rows` as the probe-free tier (+0.0% warm), and it is armed
    // IDENTICALLY in both arms, so its boundaries cancel (round 793).
    "gatedrows",
    // …and the arm that makes the other two INTERPRETABLE. `gatedrows` measures
    // `G - R`: the gated machinery's own price MINUS the work the table skips —
    // one equation in two unknowns, which is why round 732's cold GATED run
    // could never bound the prize (its own § 5 reports it as "not proof that the
    // idea must lose"). `gatedfull` runs the SAME machinery over a table that
    // holds every handler for every kind, so it skips NOTHING by construction
    // and its delta against `rows` is `G` at 59 consultations per node, with no
    // `R` in it. Differencing the two arms then cancels the per-node fixed cost
    // and yields the per-consultation dispatch price directly
    // (`docs/perf/dispatch-table.md` § 8.3).
    //
    // It is a COST arm, not a behaviour arm: a full table IS the production
    // handler set, so its output is identical — the tables are swapped in
    // [tierBegin] and restored in [tierStop], and a missed restore degrades to
    // running production semantics slowly, never to a wrong answer.
    "gatedfull",
    // (WARM.21) round 874 — the TAV pass's OFF arm and nothing else. Like
    // `plain` it arms NO probe, so its `ms` is a production-comparable wall and
    // `tavoff,plain,plain,tavoff` is an ABBA rotation of the two arms in one
    // warm process. It must have its own [tierBegin] arm for the same reason
    // `plain` does: the `else` branch would enable the pass probe and make the
    // control arm the more expensive one.
    //
    // Its falsifier is free: with the pass off the compile loses its
    // TS2693/TS2708 emissions, so the reported `errors` MOVES. An arm that
    // reports the same error count as `plain` did not run.
    "tavoff",
    // (WARM.21) — the POPULATION arm. It arms [FrontEnd] plus the INERT
    // classification, whose second chain walk is real work and is therefore
    // deliberately absent from the plain `frontend` tier, which carries the
    // [FrontEnd.TAV] span. Round 801's order: the census decides the shape of
    // the fix and the span prices it, and neither may contaminate the other.
    "tavcensus",
    // (WARM.21) — the pre-874 arm of the name-candidate gate, WITH the
    // [FrontEnd.TAV] span. `frontend,tavgateoff,tavgateoff,frontend` is the
    // controlled row in one warm process: the gate returns early INSIDE the
    // span, so both arms take exactly 381,670 timestamp pairs and their
    // difference carries no boundary at all (round 793/795).
    "tavgateoff",
    // (WARM.22) round 875 — the INV.4 reach machinery's per-classifier census.
    // COUNTERS ONLY, no timestamp anywhere: the family is 43 classifiers whose
    // largest is 0.86% of a rebuild, so every one of them is below the price of
    // its own boundary (round 850), and the quantity a design change acts on —
    // EDGE EVALUATIONS — is a count of structure, identical on every rebuild.
    // That determinism is its own falsifier: two rebuilds of one binary that
    // disagree mean the probe is broken, not that the compile varied.
    "reach",
    // (WARM.22) — the edge amplifier's r values. Enumerated rather than
    // parameterised because only these four are ever used and TIERS' closed
    // vocabulary is what rejects a typo.
    "reachamp8",
    "reachamp16",
    "reachamp24",
    "reachamp32",
    // (WARM.30) round 903 — the census for `nodeTypes`' deep AST-VALUE key.
    // COUNTERS plus one `forEachChild` subtree walk per probe, and no timestamp
    // pair anywhere: the quantity it answers (mean key subtree size, and the
    // hit/miss/bypassed split) is a count of structure and is identical on every
    // rebuild, which is its own falsifier. Its amplified sibling is `tnkamp<N>`.
    "typenodekey",
    // (WARM.31) round 904 — per-SITE population counts for the residual
    // boxed-primitive map/set keys. Counters only, no timestamp pair anywhere,
    // so its rebuild is otherwise a `plain` one and the numbers are
    // deterministic (which is their own falsifier).
    "boxedkey",
    // (WARM.32) round 905 — the ITERATOR-ALLOCATION family's population: list
    // iterations and ELEMENTS at `forEachChild`'s 70 child positions and the
    // INV.4 edge classifiers' 145 identity tests. Counters and histograms only,
    // deterministic, no timestamp pair. Its amplified sibling is `iteramp<N>`.
    "itercensus",
    // (WARM.33) every ACCESS to the 45 per-file reach/depth memos, plus a
    // set-associative model of both layouts. Arms `reach` TOO, deliberately:
    // the reconstruction's falsifier is that its WRITE count reproduces round
    // 875's per-classifier `folds`, and a cross-draw comparison of two
    // deterministic counters taken in different rebuilds proves nothing.
    "reachmemo",
)

/**
 * (WARM.30) round 903 — the FOURTH parameterised tier family: `tnkamp<N>` runs
 * the three-arm deep-key amplifier at `N` probes per arm per call for one
 * rebuild.
 *
 * A parameter is unavoidable for the same reason `amp<N>` needs one: the answer
 * is a SLOPE, so one process must be able to run `tnkamp8,tnkamp32,tnkamp32,tnkamp8`
 * and get both `r` at one warmth. A single `r` measures a boundary it cannot
 * separate — and here the boundary also cancels BETWEEN the three arms at equal
 * `r`, which is the only thing that makes a first-probe rate readable.
 *
 * `tnkamp0` is rejected: the zero arm is not the base of this slope (the arms
 * take no bracket at all at `r == 0`), so a zero here is a typo and nothing else.
 */
internal fun bkAmpReps(tier: String): Int? {
    if (!tier.startsWith("bkamp")) return null
    val digits = tier.removePrefix("bkamp")
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    val n = digits.toIntOrNull() ?: return null
    return if (n <= 0) null else n
}

/**
 * (WARM.31) round 904 — the boxed-vs-primitive KEY amplifier at `N` probes per
 * arm per call. Same slope discipline as `tnkamp<N>`: one process runs two `r`
 * so the timestamp boundary cancels algebraically, and a FLAT p(r) between them
 * is the hoisting falsifier (round 903), not the sink.
 */
internal fun tnkAmpReps(tier: String): Int? {
    if (!tier.startsWith("tnkamp")) return null
    val digits = tier.removePrefix("tnkamp")
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    val n = digits.toIntOrNull() ?: return null
    return if (n <= 0) null else n
}

/**
 * (WARM.32) round 905 — the iterator-vs-indexed amplifier at `N` repetitions per
 * arm per call, IN SITU at both of the family's real call sites. Same slope
 * discipline: one process runs two `r` so `p(r) = cost + boundary/r` can be
 * fitted PER ARM, which is what round 904 showed a single-`r` `A - B` cannot do.
 */
internal fun iterAmpReps(tier: String): Int? {
    if (!tier.startsWith("iteramp")) return null
    val digits = tier.removePrefix("iteramp")
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    val n = digits.toIntOrNull() ?: return null
    return if (n <= 0) null else n
}

/**
 * (WARM.24) round 897 — the THIRD parameterised tier family: `namecensus<N>`
 * arms [NameCensus] for one rebuild and then replays its captured populations
 * `N` times.
 *
 * `N` is a rep count and not an amplification factor: the arms are ALREADY
 * whole-population passes (2 M probes, 1.5 M tokens), so each one is tens of
 * milliseconds against a ~90 ns timestamp pair and needs no algebraic boundary
 * cancellation. What the reps buy is the ABBA rotation — rounds 869/891 —
 * without which a single leading draw sets the answer.
 *
 * The tier ALSO disables [CrawlParseCache] for its rebuild, and that is not an
 * incidental: the cache serves the program's parse from the previous request,
 * so on a warm rebuild the Scanner does not run and the token population the
 * COST arm needs does not exist to be captured. The disable is restored in
 * [tierStop] — left set, it silently re-parses the program on every later
 * rebuild in the process, which reads as a regression in whatever tier follows.
 */
internal fun nameCensusReps(tier: String): Int? {
    if (!tier.startsWith("namecensus")) return null
    val digits = tier.removePrefix("namecensus")
    if (digits.isEmpty()) return 6
    if (!digits.all { it in '0'..'9' }) return null
    val n = digits.toIntOrNull() ?: return null
    return if (n <= 0) null else n
}

/**
 * (WARM.14) round 867 — the ONE parameterised tier family: `amp<N>` runs the
 * [SpineAmp] amplifier at `N` extra consultation passes per node for one
 * rebuild, and `ampc<N>` runs its CONTROL arm (the identical loop with every
 * consultation suppressed) at the same `N`.
 *
 * A parameter is unavoidable here — the whole instrument is a SLOPE, so a
 * single process must be able to run `amp8,ampc8,amp16,ampc16,amp32,ampc32`
 * and give both arms at three amplification factors at one warmth. Returns
 * null for anything that is not of this family, so [TIERS]' closed vocabulary
 * still rejects a typo (`amp` with no number, `ampx8`, `amp0`) rather than
 * silently measuring nothing.
 */
/**
 * (WARM.16) round 869 — the SECOND parameterised tier family: `copyamp<N>` arms
 * [FrontEnd] (so the copy CENSUS runs) and sets [FrontEnd.copyAmp] to `N`, i.e.
 * `N` EXTRA whole-map copies at every censused per-scope copy site.
 *
 * `copyamp0` is the census with no amplification and is the arm every other one
 * is differenced against, so it is deliberately ACCEPTED where [ampReps]
 * rejects a zero: here the zero arm is the base of the slope, not a typo.
 *
 * No timestamp pair is taken anywhere in this family. At the sizes involved one
 * probe boundary (97-202 ns warm, round 850) would exceed the quantity being
 * measured, so the instrument is round 759's amplification read off the
 * WHOLE-REBUILD wall: `wall(r) = base + r * C`, and two values of `r` cancel
 * `base` algebraically. Its falsifier is arithmetic — [FrontEnd.copyAmpSink]
 * must be exactly `r x` the censused entry count on every rebuild.
 */
internal fun copyAmpReps(tier: String): Int? {
    if (!tier.startsWith("copyamp")) return null
    val digits = tier.removePrefix("copyamp").dropWhile { it in 'a'..'z' }
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    return digits.toIntOrNull()
}

/**
 * (WARM.16) — which copy families the `copyamp*` tier arms, as [FrontEnd]'s
 * bitmask. `copyampos<r>` arms the two ANNOTATION-scope families and nothing
 * else, so the prize of replacing exactly those two is measured on the binary
 * that still has them, instead of as the difference of two whole-family slopes
 * each carrying its own error.
 */
internal fun copyAmpKinds(tier: String): Int = when {
    tier.startsWith("copyampos") -> (1 shl FrontEnd.CP_OS) or (1 shl FrontEnd.CP_PD)
    // (WARM.18) round 891 — the two `CtaFrame` families, which are what round
    // 869 left behind. `cv` is `varTypes` alone (the family this round replaces),
    // `cl` the localTypes/declNodes/shadowedNames fn-body copies (refused), and
    // `cta` both. Same reason as `os`: the prize of replacing exactly ONE family
    // is measured on the binary that still has it.
    tier.startsWith("copyampcv") -> 1 shl FrontEnd.CP_CTA_VAR
    tier.startsWith("copyampcl") -> 1 shl FrontEnd.CP_CTA_LOCAL
    tier.startsWith("copyampcta") -> (1 shl FrontEnd.CP_CTA_VAR) or (1 shl FrontEnd.CP_CTA_LOCAL)
    // (WARM.25) round 894's candidate (8) — the `EpochMap`/`EpochSet` copies
    // that rounds 891/892 left behind. They need their OWN arms and not the
    // default `-1`: rounds 869/891/892 converted four of the six families, so a
    // plain `copyamp<r>` now arms two live families and four dead ones, and its
    // slope is the SUM — which is exactly the "difference of two whole-family
    // slopes" shape `copyampos` was introduced to avoid.
    tier.startsWith("copyampem") -> 1 shl FrontEnd.CP_EPOCH_MAP
    tier.startsWith("copyampes") -> 1 shl FrontEnd.CP_EPOCH_SET
    // (WARM.25) round 894's candidate (6) — `spineArgListOverlay` alone (`al`),
    // and every `SpineArgCtx` map copy including the fn-boundary edges (`ag`).
    tier.startsWith("copyampal") ->
        (1 shl FrontEnd.CP_ARG_OVERLAY) or (1 shl FrontEnd.CP_ARG_SHADOW)
    tier.startsWith("copyampag") ->
        (1 shl FrontEnd.CP_ARG_OVERLAY) or (1 shl FrontEnd.CP_ARG_SHADOW) or
            (1 shl FrontEnd.CP_ARG_EDGE)
    else -> -1
}

internal fun ampReps(tier: String): Int? {
    val control = tier.startsWith("ampc")
    if (!tier.startsWith("amp")) return null
    val digits = tier.removePrefix(if (control) "ampc" else "amp")
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    val n = digits.toIntOrNull() ?: return null
    if (n <= 0) return null
    return if (control) -n else n
}

/**
 * (WARM.13) — the derived tables, saved while [TIERS]' `gatedfull` arm holds its
 * full-table replacement. Non-null exactly while that arm is armed.
 */
private var savedEnterTable: Array<IntArray>? = null
private var savedLeaveTable: Array<IntArray>? = null

/** Swap in a table that keeps every handler for every kind, and remember the real one. */
internal fun installFullDispatchTables() {
    if (savedEnterTable != null) return
    savedEnterTable = Array(SpineDispatch.KINDS) { SpineDispatch.enterTable[it] }
    savedLeaveTable = Array(SpineDispatch.KINDS) { SpineDispatch.leaveTable[it] }
    val allEnter = IntArray(SpineDispatch.enterCount) { it }
    val allLeave = IntArray(SpineDispatch.leaveCount) { it }
    for (k in 0 until SpineDispatch.KINDS) {
        SpineDispatch.enterTable[k] = allEnter
        SpineDispatch.leaveTable[k] = allLeave
    }
}

/** Put the derived tables back. Idempotent; a no-op when none were swapped out. */
internal fun restoreDispatchTables() {
    val e = savedEnterTable ?: return
    val l = savedLeaveTable ?: return
    for (k in 0 until SpineDispatch.KINDS) {
        SpineDispatch.enterTable[k] = e[k]
        SpineDispatch.leaveTable[k] = l[k]
    }
    savedEnterTable = null
    savedLeaveTable = null
}

/**
 * Arm the probe a tier names, and zero its counters.
 *
 * Split out of [main] so the ORDER of arm → measure → **report** → disarm is a
 * property a test can pin. Round 850 found the pre-851 code disarming before
 * dumping, which made every `*coarse` report print `mode: ON` — the data was
 * unaffected but the label was always wrong, and a label is what a reader
 * classifies an arm by.
 */
internal fun tierBegin(tier: String) {
    PassTiming.enabled = false
    PassTiming.reset()
    // (WARM.14) — the amplifier arms NO other probe: its bracket is its own and
    // its answer is a slope, so an armed pass probe would only add boundaries
    // that do not cancel between two `r` values taken in different rebuilds.
    val amp = ampReps(tier)
    if (amp != null) {
        SpineAmp.reset()
        SpineAmp.reps = amp
        return
    }
    // (WARM.16) — the copy amplifier arms the FrontEnd census and nothing else:
    // the census counters cost the same in every arm of the slope, so they
    // cancel, while a pass or section probe would not (its boundaries are not
    // proportional to `r`).
    // (WARM.24) — counters and captures only; the timed arms run in [tierReport],
    // AFTER the rebuild, so nothing they do lands inside the compile they census.
    if (nameCensusReps(tier) != null) {
        NameCensus.reset()
        NameCensus.on = true
        CrawlParseCache.enabled = false
        return
    }
    // (WARM.30) — the amplifier arms NO other probe, for the reason (WARM.14)
    // gives: its answer is a slope taken from its own brackets, and an armed pass
    // probe would only add boundaries that do not cancel between two `r`.
    val bkAmp = bkAmpReps(tier)
    if (bkAmp != null) {
        MapCensus.reset()
        MapCensus.boxedKeyAmp = bkAmp
        MapCensus.on = true
        return
    }
    val iterAmp = iterAmpReps(tier)
    if (iterAmp != null) {
        IterCensus.reset()
        IterCensus.amp = iterAmp
        IterCensus.on = true
        return
    }
    val tnkAmp = tnkAmpReps(tier)
    if (tnkAmp != null) {
        MapCensus.reset()
        MapCensus.typeNodeKeyAmp = tnkAmp
        MapCensus.on = true
        return
    }
    val copyAmp = copyAmpReps(tier)
    if (copyAmp != null) {
        FrontEnd.reset()
        FrontEnd.copyAmp = copyAmp
        FrontEnd.copyAmpKinds = copyAmpKinds(tier)
        FrontEnd.mode = FrontEnd.ON
        return
    }
    when (tier) {
        "dispatch" -> { SpineDispatch.reset(); SpineDispatch.mode = SpineDispatch.PROBE }
        // (WARM.13) round 866 — the A/B pair. `gated` arms the derived table and
        // NOTHING else; `plain` arms nothing at all and exists so the control
        // rebuild sits in the same loop, at the same warmth, as the treated one.
        "gated" -> { SpineDispatch.reset(); SpineDispatch.mode = SpineDispatch.GATED }
        "plain" -> { /* the null arm: no probe, no counters, no boundaries */ }
        // The `rows` arm of `tierBegin`'s `else`, plus the gated dispatch. Its
        // control is the bare `rows` tier, whose probe state this reproduces
        // EXACTLY — the two arms must differ in the dispatch and in nothing
        // else, or the row difference is the probe's.
        "gatedrows" -> {
            SpineDispatch.reset()
            SpineDispatch.mode = SpineDispatch.GATED
            PassTiming.detail = false
            PassTiming.spineDetail = false
            PassTiming.enabled = true
        }
        // The cost-only arm: the same machinery over a table that skips nothing.
        "gatedfull" -> {
            SpineDispatch.reset()
            installFullDispatchTables()
            SpineDispatch.mode = SpineDispatch.GATED
            PassTiming.detail = false
            PassTiming.spineDetail = false
            PassTiming.enabled = true
        }
        "cta" -> { CtaSections.reset(); CtaSections.mode = CtaSections.ON }
        "ctacoarse" -> { CtaSections.reset(); CtaSections.mode = CtaSections.COARSE }
        "cpa" -> { CpaSections.reset(); CpaSections.mode = CpaSections.ON }
        "cpacoarse" -> { CpaSections.reset(); CpaSections.mode = CpaSections.COARSE }
        "arg" -> { ArgSections.reset(); ArgSections.mode = ArgSections.ON }
        "argcoarse" -> { ArgSections.reset(); ArgSections.mode = ArgSections.COARSE }
        "call" -> { CallSections.reset(); CallSections.mode = CallSections.ON }
        "callcoarse" -> { CallSections.reset(); CallSections.mode = CallSections.COARSE }
        // (SPINE.1) round 908 — round 733's probe, warm for the first time.
        "spinesections" -> { SpineSections.reset(); SpineSections.mode = SpineSections.ON }
        "libtypes" -> { LibTypeCensus.reset(); LibTypeCensus.enabled = true }
        "srcscan" -> { SrcScan.reset(); SrcScan.on = true }
        "srcscanoff" -> { SrcScan.reset(); SrcScan.on = true; SrcScan.off = true }
        "frontend" -> { FrontEnd.reset(); FrontEnd.mode = FrontEnd.ON }
        // (WARM.21) — arms nothing but the pass's OFF switch. Deliberately NOT
        // `FrontEnd.mode = ON`: the census's own extra chain walks would land
        // inside the measurement this arm exists to take.
        "tavoff" -> { FrontEnd.tavOff = true }
        "tavgateoff" -> {
            FrontEnd.reset(); FrontEnd.mode = FrontEnd.ON; TavGate.off = true
        }
        "tavcensus" -> {
            FrontEnd.reset(); FrontEnd.mode = FrontEnd.ON; FrontEnd.tavInertCensus = true
        }
        // (WARM.22) — arms the reach census and NOTHING else, so its rebuild is
        // otherwise a `plain` one and the counters cannot be contaminated by a
        // second probe's own walks (round 874's `tavcensus`/`frontend` split).
        "reach" -> { ReachCensus.reset(); ReachCensus.on = true }
        // (WARM.30) — counters and subtree walks only, no timestamp pair, so its
        // rebuild is otherwise a `plain` one. Deliberately does NOT arm `rows`:
        // the quantity is deterministic, so it needs no row to be read against.
        "typenodekey" -> { MapCensus.reset(); MapCensus.typeNodeKeyCensus = true; MapCensus.on = true }
        // (WARM.31) — counters only; deliberately does NOT arm `rows`.
        "boxedkey" -> { MapCensus.reset(); MapCensus.boxedKeyCensus = true; MapCensus.on = true }
        // (WARM.32) — counters and histograms only, no timestamp pair; deliberately
        // does NOT arm `rows`, because the quantity is deterministic.
        "itercensus" -> { IterCensus.reset(); IterCensus.census = true; IterCensus.on = true }
        "reachmemo" -> {
            ReachCensus.reset(); ReachCensus.on = true
            ReachMemoCensus.reset(); ReachMemoCensus.on = true
        }
        // (WARM.22) — the edge amplifier at N extra evaluations per fold. It is
        // a SLOPE instrument, so a process must be able to run
        // `reachamp8,reachamp24,reachamp8,reachamp24` and get both r values at
        // one warmth; a single r measures a boundary it cannot separate.
        "reachamp8", "reachamp16", "reachamp24", "reachamp32" -> {
            ReachCensus.reset()
            ReachCensus.on = true
            ReachCensus.amp = tier.removePrefix("reachamp").toInt()
        }
        // (WARM.9) the ONE tier that arms two probes, and the pairing is the
        // point: the census prices a SUB-POPULATION of a pass whose own row it
        // does not measure, so without the `rows` table from the same rebuild the
        // two numbers would be a cross-draw ratio against a 41% spread. `rows` is
        // the tier round 846 measured as probe-FREE for the tail rows (+0.0%
        // warm), so pairing costs the row nothing that the census does not.
        "fltm" -> {
            FltmCensus.reset()
            FltmCensus.on = true
            PassTiming.detail = false
            PassTiming.spineDetail = false
            PassTiming.enabled = true
        }
        else -> {
            PassTiming.detail = tier == "full"
            PassTiming.spineDetail = tier != "rows"
            PassTiming.enabled = true
        }
    }
}

/**
 * The tier's report text — produced while the probe is still armed, so a
 * `report()` that labels itself from its own `mode` labels itself correctly.
 */
internal fun tierReport(tier: String): String = if (ampReps(tier) != null) {
    // Taken while `reps` still holds the arm, so the report labels itself from
    // its own state (round 850) — a CONTROL row and a REAL row are otherwise
    // distinguishable only by a zero in the middle of the table.
    SpineAmp.report()
} else if (nameCensusReps(tier) != null) {
    // (WARM.24) — the counters are OFF before the replay runs, so the replay's
    // own map traffic cannot enter the census it is reporting. The reps come
    // from the tier name and are set here rather than in `tierBegin` for the
    // same reason: a `replayReps` armed during the compile would make every
    // `globalsForFile` call attempt a whole-population replay.
    NameCensus.on = false
    NameCensus.replayReps = nameCensusReps(tier)!!
    NameCensus.replay()
    NameCensus.report()
} else if (bkAmpReps(tier) != null) {
    // (WARM.31) — taken while the amplifier still holds its arm, so `amplified r=`
    // prints from the live field (round 850).
    MapCensus.report()
} else if (iterAmpReps(tier) != null) {
    // (WARM.32) — taken while the amplifier still holds its arm, so `r=` prints
    // from the live field (round 850).
    IterCensus.report()
} else if (tnkAmpReps(tier) != null) {
    // (WARM.30) — taken while the amplifier still holds its arm, so `amplified
    // r=` prints from the live field and an arm labels itself from its own state
    // (round 850).
    MapCensus.report()
} else if (copyAmpReps(tier) != null) {
    // (WARM.16) — taken while the census still holds its counters, and it
    // prints `amp=` from the live field, so an arm labels itself from its own
    // state rather than from its tier name (round 850).
    FrontEnd.report()
} else when (tier) {
    "dispatch" -> SpineDispatch.report() + "\n== (DISPATCH.1) csv ==\n" + SpineDispatch.csv()
    // (WARM.13) — a GATED rebuild counts nothing, so `SpineDispatch.report()`
    // would print an all-zero table and read as a failed measurement. What it
    // CAN state is the arm it ran, taken from the live `mode` (round 850: an arm
    // must label itself from its own state, never from its tier name) plus the
    // table's own shape, which is what makes the arm non-vacuous.
    "gated" -> "== (DISPATCH.1) gated arm — mode: ${SpineDispatch.mode} " +
        "(OFF=${SpineDispatch.OFF} PROBE=${SpineDispatch.PROBE} GATED=${SpineDispatch.GATED}) " +
        "enter ${SpineDispatch.enterTable.sumOf { it.size }}/" +
        "${SpineDispatch.KINDS * SpineDispatch.enterNames.size} leave " +
        "${SpineDispatch.leaveTable.sumOf { it.size }}/" +
        "${SpineDispatch.KINDS * SpineDispatch.leaveNames.size} (handler,kind) pairs kept =="
    "plain" -> "== (WARM.13) plain arm — no probe armed, mode: ${SpineDispatch.mode}, " +
        "passTiming: ${PassTiming.enabled} =="
    // Label FIRST, from the live mode, then the pass table — a reader must be
    // able to tell which arm a `checkSpine` row came from without counting rows
    // (round 850), and here the two arms' tables are otherwise identical in
    // shape and differ only in one number.
    "gatedrows", "gatedfull" ->
        "== (WARM.13) $tier arm — mode: ${SpineDispatch.mode}, " +
            "enter ${SpineDispatch.enterTable.sumOf { it.size }}/" +
            "${SpineDispatch.KINDS * SpineDispatch.enterCount} (handler,kind) pairs kept ==\n" +
            buildString { PassTiming.enabled = false; PassTiming.dump { appendLine(it) } }
    "cta", "ctacoarse" ->
        CtaSections.report() + "\n== (TYPE.2) csv ==\n" + CtaSections.csv() + "== (TYPE.2) csv end =="
    "cpa", "cpacoarse" ->
        CpaSections.report() + "\n== (ENGINE.2) csv ==\n" + CpaSections.csv() + "== (ENGINE.2) csv end =="
    "arg", "argcoarse" ->
        ArgSections.report() + "\n== (CALL.2) csv ==\n" + ArgSections.csv() + "== (CALL.2) csv end =="
    "call", "callcoarse" ->
        CallSections.report() + "\n== (CALL.1) csv ==\n" + CallSections.csv() + "== (CALL.1) csv end =="
    "spinesections" ->
        SpineSections.report() + "\n== (SPINE.1) csv ==\n" + SpineSections.csv() + "== (SPINE.1) csv end =="
    "libtypes" -> LibTypeCensus.report()
    "srcscan", "srcscanoff" -> SrcScan.report()
    "typenodekey", "boxedkey" -> MapCensus.report()
    "itercensus" -> IterCensus.report()
    "reachmemo" -> ReachCensus.report() + ReachMemoCensus.report()
    "reach", "reachamp8", "reachamp16", "reachamp24", "reachamp32" -> ReachCensus.report() +
        "\n== (WARM.22) csv ==\n" + ReachCensus.csv() + "== (WARM.22) csv end =="
    "frontend", "tavcensus", "tavgateoff" ->
        FrontEnd.report() + "\n== (FRONT.1) csv ==\n" + FrontEnd.csv() + "== (FRONT.1) csv end =="
    // Census FIRST, then the pass table — the census must be read while
    // `FltmCensus` still holds its counters, i.e. before `tierStop` releases
    // them, and the pass row it is read against is in the same string.
    "fltm" -> FltmCensus.report() +
        buildString { PassTiming.enabled = false; PassTiming.dump { appendLine(it) } }
    // The pass probe is disarmed BEFORE its dump, exactly as it was pre-851 —
    // only the section probes need to stay armed through their report, and only
    // because each labels its arm from its own `mode`.
    else -> buildString { PassTiming.enabled = false; PassTiming.dump { appendLine(it) } }
}

/**
 * Arm [tier], run [build], take its report **while still armed**, disarm.
 *
 * This exists so the ORDER is a seam a test can hold. Round 850's label defect
 * lived in `main`'s loop, which no test can run (it compiles a whole project),
 * so a pin on [tierReport] alone stayed green against a binary with the
 * disarm-before-dump fault restored — round 807's blind-pin mechanism, measured
 * by round 851's ablation and then FIXED rather than claimed as coverage. With
 * the order behind this function a stub `build` is enough to see it.
 */
internal fun <T> measureTier(tier: String, build: () -> T): Pair<T, String> {
    tierBegin(tier)
    val value = build()
    val text = tierReport(tier)
    tierStop()
    return value to text
}

/**
 * (WARM.10) round 863 — parse the 5th argument, the EMIT-mode switch.
 *
 * Split out of [main] for the reason round 851 gave: a pin on a helper that
 * [main] does not call is blind, and [main] itself compiles a whole project so
 * no test can run it. This IS the code [main] runs.
 *
 * Deliberately a CLOSED vocabulary. An unknown 5th argument is an error rather
 * than a silent `false`, because the failure it prevents is not a crash — it is
 * a run that quietly measures the OTHER mode, and the two modes are different
 * compiles (round 739). `noEmit` is accepted as an explicit spelling of the
 * default so a script can name the mode it means.
 */
internal fun parseEmitFlag(arg: String?): Boolean = when (val flag = arg?.lowercase()) {
    null, "", "false", "0", "off", "noemit" -> false
    "emit", "true", "1", "on" -> true
    else -> error("usage: 5th argument must be `emit`, `noEmit`, or omitted — not '$flag'")
}

/**
 * (PERF.HW.b) — the 6th argument's `workers<N>` form, e.g. `workers4`.
 *
 * Deliberately NOT a bare integer: the 4th and 5th arguments are word-shaped, so
 * a bare number in the 6th slot is exactly the sort of thing that lands there by
 * a shifted argument list, and a harness that silently accepts it would report a
 * PARALLEL median under a run everyone reads as sequential. The prefix makes the
 * arm unambiguous in the shell history that produced the number.
 */
internal fun parseWorkersFlag(arg: String?): Int {
    val flag = arg?.lowercase()
    if (flag == null || flag == "" || flag == "off") return 1
    val n = flag.removePrefix("workers").toIntOrNull()
    if (!flag.startsWith("workers") || n == null || n < 1) {
        error("usage: 6th argument must be `workers<N>` (N >= 1) or omitted — not '$flag'")
    }
    return n
}

/** Disarm every probe and release its counters. Safe to call for any tier. */
internal fun tierStop() {
    PassTiming.enabled = false
    SpineDispatch.mode = SpineDispatch.OFF
    // (WARM.13) — before anything else reads the tables. A missed restore is not
    // a correctness bug (a full table is the production handler set) but it
    // would silently turn every later `gatedrows` arm into a `gatedfull` one,
    // i.e. into the arm whose whole point is that it skips nothing.
    restoreDispatchTables()
    CtaSections.mode = CtaSections.OFF
    CpaSections.mode = CpaSections.OFF
    ArgSections.mode = ArgSections.OFF
    CallSections.mode = CallSections.OFF
    SpineSections.mode = SpineSections.OFF
    LibTypeCensus.enabled = false
    // (WARM.19) — an `off` left set silently restores the pre-895 unfiltered
    // path for every LATER rebuild in this process, which reads as a regression
    // in whatever tier follows; an `on` left set leaves a timestamp pair on
    // every scan of every later rebuild.
    SrcScan.on = false
    SrcScan.off = false
    FrontEnd.mode = FrontEnd.OFF
    // (WARM.16) — same hazard as `SpineAmp.reps` below: a `copyAmp` left set
    // costs every LATER rebuild in this process `r` extra whole-map copies per
    // scope push, which is silent and looks exactly like a regression in
    // whatever tier follows.
    FrontEnd.copyAmp = 0
    FrontEnd.copyAmpKinds = -1
    // (WARM.21) — same hazard as `copyAmp`: a `tavOff` left set silently
    // removes a whole diagnostic pass from every LATER rebuild in this process,
    // which reads as a speed-up and changes the error count.
    FrontEnd.tavOff = false
    FrontEnd.tavInertCensus = false
    // (WARM.21) — a `TavGate.off` left set silently restores the pre-874 path
    // for every LATER rebuild in this process, which reads as a regression in
    // whatever tier follows.
    TavGate.off = false
    // (WARM.22) — a `ReachCensus.on` left set costs every LATER rebuild in this
    // process 43 static reads per classifier consultation, which is small but
    // is not zero and would land inside whatever tier follows.
    ReachCensus.on = false
    ReachCensus.amp = 0
    ReachCensus.reset()
    FrontEnd.reset()
    FltmCensus.on = false
    FltmCensus.reset()
    // (WARM.14) — the amplifier is the one tier that must be disarmed even when
    // its own rebuild never ran: `reps != 0` costs every later rebuild in this
    // process `r` extra passes per node, which is a large, silent, and entirely
    // plausible-looking slowdown of whatever tier follows it.
    // (WARM.24) — three restores, each of which is silent if missed: `on` left
    // set costs every later rebuild a capture per identifier AND per name probe,
    // `replayReps` left set makes a later armed rebuild replay 2 M probes per
    // `globalsForFile` call, and a disabled parse cache re-parses the whole
    // program on every later rebuild — a ~270 ms regression (round 871) in
    // whatever tier follows.
    NameCensus.on = false
    NameCensus.replayReps = 0
    NameCensus.reset()
    CrawlParseCache.enabled = true
    // (WARM.30) — three restores, each silent if missed. `typeNodeKeyCensus` left
    // set costs every LATER rebuild in this process a `forEachChild` subtree walk
    // per `getTypeFromTypeNodeCore` call AND a whole-cache sweep at the end of the
    // check; `typeNodeKeyAmp` left set costs it `3 * r` extra probes per cacheable
    // resolution, which is a large, silent and entirely plausible-looking
    // slowdown of whatever tier follows.
    MapCensus.typeNodeKeyCensus = false
    MapCensus.boxedKeyCensus = false
    MapCensus.boxedKeyAmp = 0
    MapCensus.typeNodeKeyAmp = 0
    MapCensus.on = false
    MapCensus.reset()
    // (WARM.32) — same hazard as above: `census` left set costs every LATER
    // rebuild a size read and a histogram bump at every list child position and
    // every edge membership test, and `amp` left set costs it `2 * r` extra
    // whole-list iterations at each of them.
    IterCensus.census = false
    IterCensus.amp = 0
    IterCensus.on = false
    IterCensus.reset()
    SpineAmp.reps = 0
    SpineAmp.reset()
    SpineDispatch.reset()
    CtaSections.reset()
    CpaSections.reset()
    ArgSections.reset()
    CallSections.reset()
    LibTypeCensus.reset()
    SrcScan.reset()
}

fun main(args: Array<String>) {
    val project = args.getOrNull(0)
        ?: error("usage: BenchMainKt <projectDir> <warmup> <iters> [passTiming]")
    // SIX, not three (2026-08-10). Measured A/A — two 16-iteration ladders in
    // separate JVMs on one binary, re-sliced per setting — the gap between two
    // IDENTICAL process medians is 3.3% at warmup 2, 2.0% at 3, 0.8% at 6 and
    // 0.9% at 8. Under three the measured window sits in the JIT ramp, which is
    // common-mode in a paired delta but is exactly the between-process spread
    // `ab-warm.sh` refuses a verdict on. Six buys the band; eight buys nothing.
    val warmup = args.getOrNull(1)?.toIntOrNull() ?: 6
    val iters = args.getOrNull(2)?.toIntOrNull() ?: 10
    // Opt-in, and deliberately NOT a "any 4th argument means yes" test: a typo
    // would then silently buy a probe-contaminated extra rebuild.
    //
    // (WARM.1)(c) round 846: the argument is a COMMA-SEPARATED LIST of probe
    // TIERS, each of which runs its own instrumented rebuild after the measured
    // loop. `passTiming` is `full` (the pre-846 behaviour, exactly). A list such
    // as `rows,full,rows,full` is the DIFFERENTIAL the tiers exist for: the SAME
    // code measured at ~513 boundaries and at ~2 M, twice each, inside ONE warm
    // process, so `full − rows` is the probe's own price with nothing else
    // varying (round 734's law — never an empty-span loop).
    val tiers: List<String> = when (val flag = args.getOrNull(3)?.lowercase()) {
        null, "", "false", "0", "off" -> emptyList()
        "passtiming", "true", "1", "on" -> listOf("full")
        else -> flag.split(",").map { it.trim() }.filter { it.isNotEmpty() }.also { list ->
            val bad = list.filter {
                it !in TIERS && ampReps(it) == null && copyAmpReps(it) == null &&
                    nameCensusReps(it) == null && tnkAmpReps(it) == null &&
                    bkAmpReps(it) == null && iterAmpReps(it) == null
            }
            if (list.isEmpty() || bad.isNotEmpty()) {
                error(
                    "usage: 4th argument must be `passTiming`, omitted, or a comma-separated " +
                        "list of tiers (${TIERS.joinToString("|")}) — not '$flag'"
                )
            }
        }
    }
    val instrumented = tiers.isNotEmpty()

    // (WARM.10) round 863 — the 5th argument, `emit`, is the ONLY way this
    // harness can measure the transform/emit path at all. Every warm number in
    // `docs/perf` is check-only because `noEmit = true` was a literal in three
    // places here, and round 738's gate makes that skip `Transformer.transform`
    // and `Emitter.emit` ENTIRELY — so a whole-program cost living there is
    // invisible to `rows`, to `frontend`, to `cost_gate.py` and to the
    // `--noEmit --listAll` grid alike. It is deliberately NOT "any 5th argument
    // means yes": a typo would silently change which compile is being timed,
    // and the two modes are different compiles (round 739).
    val emit: Boolean = parseEmitFlag(args.getOrNull(4))
    val noEmit = !emit

    // (PERF.HW.b) — the 6th argument, `workers<N>`, is the ONLY way this harness
    // can measure a PARALLEL compile at all, and the parallel path is where the
    // remaining concurrency work lives. Every warm number in `docs/perf` was
    // taken sequentially, while every `--workers` number ever recorded
    // (rounds 740/824/826) is COLD — so the two regimes have never met, and a
    // cold table cannot stand in for a warm one here: a cold run is dominated by
    // the JIT ramp (25.1 s -> 7.0 s over six requests), and worker threads
    // compete for exactly the compiler threads that ramp is running on.
    //
    // Set ONCE, before the warm-up, so the whole process is one arm. That is
    // round 867's law and it is sharper for workers than for anything else it
    // was written about: two worker levels in one JVM share every compiled
    // method in the binder and the checker, so whichever ran first writes the
    // branch profile the other is compiled against. Compare worker levels
    // ACROSS processes, never inside one.
    val workers: Int = parseWorkersFlag(args.getOrNull(5))
    ParallelCheckMode.workers = workers
    // (PERF.HW.i) the 7th argument, `shareBind`, arms the one-bind-for-all-workers
    // arm. Set once per process for the same reason the worker level is.
    val shareBind: Boolean = when (val f = args.getOrNull(6)?.lowercase()) {
        null, "", "off", "false" -> false
        "sharebind", "on", "true" -> true
        else -> error("usage: 7th argument must be `shareBind`, `off`, or omitted — not '$f'")
    }
    ShareBind.enabled = shareBind
    // (INV.1) the 8th argument, `nodeAnswers`, arms the per-file node-answer
    // store for the whole process — one arm per JVM, round 867's law, and the
    // ONLY warm instrument for the flag-on recording cost the design asks for.
    // (INV.2) `nodeAnswers:<channel>` records the type plus ONE companion channel
    // (`symbols` / `calls` / `contextual`), or the type alone (`types`), so the
    // Stage-2 recording cost can be attributed channel by channel (design § 9b).
    val nodeAnswersArg = args.getOrNull(7)?.lowercase()
    val nodeAnswers: Boolean = when (nodeAnswersArg) {
        null, "", "off", "false" -> false
        "nodeanswers", "on", "true" -> true
        "nodeanswers:types", "nodeanswers:symbols", "nodeanswers:calls", "nodeanswers:contextual" -> true
        else -> error(
            "usage: 8th argument must be `nodeAnswers`, `nodeAnswers:<types|symbols|calls|contextual>`, " +
                "`off`, or omitted — not '$nodeAnswersArg'",
        )
    }
    NodeAnswers.enabled = nodeAnswers
    NodeAnswers.channels = when (nodeAnswersArg) {
        "nodeanswers:types" -> 0
        "nodeanswers:symbols" -> NodeAnswers.SYMBOLS
        "nodeanswers:calls" -> NodeAnswers.CALLS
        "nodeanswers:contextual" -> NodeAnswers.CONTEXTUAL
        else -> NodeAnswers.ALL
    }
    println("""{"mode":"${if (emit) "emit" else "noEmit"}","workers":$workers,"shareBind":$shareBind,"nodeAnswers":$nodeAnswers}""")

    repeat(warmup) {
        ProjectCompiler(SystemVfs).build(project, noEmit = noEmit)
    }

    val times = mutableListOf<Double>()
    var files = 0
    var errors = 0
    // The measured loop's own answer, for the instrumented rebuild to be held
    // against. Recorded from the FIRST iteration; `measuredDrift` remembers
    // whether the loop was even self-consistent, since an instrumented rebuild
    // cannot be validated against a reference that already moved.
    var refFiles = -1
    var refErrors = -1
    var measuredDrift = false
    repeat(iters) { i ->
        val (result, duration) = measureTimedValue {
            ProjectCompiler(SystemVfs).build(project, noEmit = noEmit)
        }
        val ms = duration.inWholeMicroseconds / 1000.0
        times.add(ms)
        files = result.programFiles.size
        errors = result.errorCount
        if (refFiles < 0) {
            refFiles = files; refErrors = errors
        } else if (files != refFiles || errors != refErrors) {
            measuredDrift = true
        }
        // The probe's own falsification: an in-process rebuild shares whatever state
        // the pipeline does not reset (id counters, interning caches, the Vfs object),
        // so a WARM number is only a measurement while every iteration still answers
        // the SAME program. A drifting errors/files column means state is leaking and
        // the timings below it measure a different compile, not a faster one.
        println("""{"iter":$i,"ms":$ms,"files":${result.programFiles.size},"errors":${result.errorCount}}""")
        if (nodeAnswers) {
            // (INV.2) the per-rebuild receipt, as the CLI prints it; cleared per rebuild.
            println(
                """{"nodeAnswers":{"channels":${NodeAnswers.channels},"recorded":${NodeAnswers.recordedTotal},""" +
                    """"symbols":${NodeAnswers.symbolsTotal},"calls":${NodeAnswers.callsTotal},""" +
                    """"contextual":${NodeAnswers.contextualTotal}}}""",
            )
            NodeAnswers.reset()
        }
    }

    val sorted = times.sorted()
    val median = sorted[sorted.size / 2]
    println(
        """{"summary":true,"project":"$project","files":$files,"errors":$errors,""" +
            """"warmup":$warmup,"iters":$iters,"medianMs":$median,"minMs":${sorted.first()},"maxMs":${sorted.last()}}"""
    )

    if (!instrumented) return

    // --- the WARM per-pass table --------------------------------------------
    // AFTER the summary, so nothing above this line has paid for a probe. The
    // enabled=false/reset()/enabled=true sequence is deliberate: `reset()` does
    // NOT clear `enabled` (nor `censusMode`/`disabledPasses`), so clearing it
    // first is what guarantees the counters start from zero even if some
    // earlier code in this process had the instrumentation on.
    for ((run, tier) in tiers.withIndex()) {
        // (WARM.4) round 847 — the `dispatch` tier is NOT a PassTiming tier: it
        // leaves the pass probe entirely OFF and runs the round-732
        // `SpineDispatch` PROBE instead, which is the only instrument that
        // attributes `checkSpine` PER HANDLER x PER KIND. Every warm row in
        // `docs/perf/dispatch-table.md` was a COLD one-shot `MainKt` run; this
        // makes the same table takeable inside a JIT-warm process, and — because
        // the probe's own code is cold on its first instrumented rebuild exactly
        // as round 846 measured for tier 3 — a tier LIST must give it at least
        // two draws per process before a number is quoted.
        // (WARM.4)(b) round 849 — the INTRA-handler probes, warm. Each is its
        // own object with its own `mode`, and each `*coarse` twin keeps ONLY
        // that probe's partition anchors, so the ON-minus-COARSE difference
        // prices its boundary differentially inside one process.
        val (measured, report) = measureTier(tier) {
            measureTimedValue { ProjectCompiler(SystemVfs).build(project, noEmit = noEmit) }
        }
        val (probeResult, probeDuration) = measured
        val probeMs = probeDuration.inWholeMicroseconds / 1000.0
        val probeFiles = probeResult.programFiles.size
        val probeErrors = probeResult.errorCount
        // `overheadMs` is the whole point of printing this separately: it is the
        // price of the instrumentation on an otherwise identical warm rebuild, so a
        // reader can say how much of any per-pass row is the probe.
        println(
            """{"instrumented":true,"tier":"$tier","run":$run,"ms":$probeMs,""" +
                """"files":$probeFiles,"errors":$probeErrors,""" +
                """"medianMs":$median,"overheadMs":${probeMs - median}}"""
        )
        if (measuredDrift || probeFiles != refFiles || probeErrors != refErrors) {
            println(
                """{"instrumentedFalsified":true,"tier":"$tier","expectedFiles":$refFiles,""" +
                    """"expectedErrors":$refErrors,"gotFiles":$probeFiles,""" +
                    """"gotErrors":$probeErrors,"measuredDrift":$measuredDrift}"""
            )
            PassTiming.reset()
            PassTiming.detail = true
            PassTiming.spineDetail = true
            error(
                "!! ABORT — the instrumented rebuild (tier $tier) answered a DIFFERENT program " +
                    "than the measured iterations (expected $refFiles files / $refErrors errors, " +
                    "got $probeFiles / $probeErrors; measured iterations themselves drifted: " +
                    "$measuredDrift). A per-pass table taken from a different compile " +
                    "attributes nothing about the one that was timed — the instrumentation " +
                    "is either changing behaviour, or in-process state is leaking across " +
                    "rebuilds. No table is printed."
            )
        }
        // Taken by `measureTier` WHILE THE PROBE WAS STILL ARMED — round 850's
        // label defect. Every one of these reports labels its arm from its own
        // `mode`, so a disarm before the dump printed `mode: ON` on every
        // `*coarse` table. Pinned by `BenchTierReportTest`.
        println(report)
    }
    PassTiming.detail = true
    PassTiming.spineDetail = true
    // Leave the process's instrumentation exactly as this harness found it: the
    // table is dumped, so the counters (and the multi-million-entry distinct-node
    // set behind them) have no further reader.
    PassTiming.reset()
}

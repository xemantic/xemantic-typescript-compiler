# (SPINE.1) round 908 — the six spine handlers' frame bookkeeping, RE-TAKEN WARM and CLOSED

**Verdict: REFUSED, and (SPINE.1) is CLOSED.** The six handlers are **2,025 ms
= 40.1% of a warm rebuild**, and after the four probes that open them are run on
today's binary, the part of that which is *bookkeeping rather than the migrated
passes' own checking work* is **~137 ms = 2.7%**, spread over a dozen rows whose
largest is **19.6 ms (0.39%)** and of which the census says **at most ~8 ms is
deletable**. Round 733's cold 88.4%-is-checking reproduces warm at **91.4%**
(`SpineSections`), **94%** (`cta`), **~100%** (`cpa`) and **93%** (`call`) — four
probes, two regimes, one answer, now for the sixth time.

Binary `71abccfb` + this round's census. Profile `build/bench/tsc-project-637d5746`
(78 files, **46 errors on every one of the 22 instrumented rebuilds**). Warm,
`BenchMain <proj> 6 6 <tiers>`, eight processes, captures under
`build/bench/round908{,b,c}/`.

**Denominator, measured here: 5,050 ms** — the mean of the eight probe-free
process medians (4,793.6 / 4,981.4 / 5,205.5 / 5,058.0 / 5,002.8 / 4,877.4 /
5,202.6 / 5,276.0; range 9.6%). **1% = 50.5 ms**; the arc's ~17 ms refusal floor
is **0.34%** here. Every decisive comparison below is in **ms**, so the choice
inside that range moves nothing.

---

## 1. Why the round had to start by re-taking the denominator

Round 847's per-handler table is against an **8,095 ms** rebuild and round
850/851's intra-handler tables against **7,076** and **7,369 ms** ones. The
compiler is now **~5,050 ms**. Neither an absolute nor a share travels across
that: round 830's law says a region's SHARE rises when the rest of the function
gets faster with its own cost unchanged, and this round contains the cleanest
instance of it yet recorded —

> `spineCtaM3StatementAnchor` measured **649 ms = 9.17%** at round 850 and
> **640 ms = 12.7%** today. **The ms did not move (−1.4%); the share rose 39%.**

so converting either of round 850's columns into today's terms would have been
wrong in both directions at once.

---

## 2. The per-HANDLER table, warm, today (`dispatch` tier, n = 4 draws / 2 processes)

`net` = raw minus the in-situ timestamp pair (**36–43 ns**, four readings) times
856,962 consultations.

| handler | **net ms today** | **% warm** | *round 847 (STALE, 8,095 ms)* | *its % then* |
|---|---:|---:|---:|---:|
| enter `spineCtaM3StatementAnchor` | **620** | **12.28%** | *853* | *10.54%* |
| leave `cpaSpineLeave` | **584** | **11.56%** | *617* | *7.62%* |
| leave `ccetSpineLeave` | **433** | **8.57%** | *876* | *10.82%* |
| enter `spineIanyEnterNode` | **147** | **2.91%** | *171* | *2.11%* |
| enter `ctaSpineEnter` | **129** | **2.55%** | *359* | *4.43%* |
| enter `spineArithEnterNode` | **113** | **2.24%** | *153* | *1.89%* |
| leave `spineArithLeaveNode` | 92 | 1.83% | | |
| enter `ccetSpineEnter` | 88 | 1.75% | | |
| enter `spineUbdEnterNode` | 73 | 1.46% | | |
| enter `cpaSpineEnter` | 73 | 1.45% | | |
| enter `spineDaEnterNode` | 72 | 1.44% | | |
| enter `spineCeEnterNode` | 65 | 1.30% | | |
| *(34 further handlers)* | *676* | *13.4%* | | |
| **TOTAL (probed spine)** | **3,234** | **64.0%** | *4,812* | *59.4%* |
| **the SIX** | **2,025** | **40.1%** | *3,029* | *37.4%* |

**The six are still the six** (#6 is 113 ms against #7's 92) and still **62.6% of
the probed spine** — round 847 read 63.0%. **But the ORDER has swapped again:**
round 847 warm had `ccetSpineLeave` first; today it is **third**, at −51% in ms,
while `cpaSpineLeave` fell only 5% and therefore *rose* from 7.62% to 11.56% of
the artifact. Round 847's own headline (2) said a handler's warm share is its
cold share times a ratio that differs per handler; this round adds that the
share is not stable across ROUNDS either, for the same reason.

**Partition check.** The 46 handler nets sum to **3,234 ms** against the `spine`
tier's independently measured `enter + leave` of **1,958 + 1,146 = 3,104 ms** =
**104.2%**. Two probes sharing no code agree within 4.2% (round 847: 102.6%).

| `spine` sub-row | **warm ms** | % spine |
|---|---:|---:|
| `spineEnterNode` | 1,958 | 55.1% |
| `spineLeaveNode` | 1,146 | 32.2% |
| unresolved-names | 339 | 9.5% |
| `forEachChild` | 74 | 2.1% |
| scope | 38 | 1.1% |
| **SUM** | **3,554** | = **70.4% of the warm rebuild** |

### 2.1 A correction every future reader of this table needs

**The `dispatch` tier BYPASSES round 888's `spineEnterMask`.** `spineEnterNode`
opens `if (SpineDispatch.mode != OFF) { spineEnterNodeProbed(node); return }`, so
the probe consults all 46 handlers at all 856,962 nodes and prices the *pre-888*
dispatch regime. Round 867 measured that population at **73.2 ms**, so the table
above overstates its own total by ~2.3% — small, but it means **the per-handler
table can never show what the mask bought**, and a next agent must not read a
handler's row as "what production spends consulting it".

This round measured the mask's effect from the other side, by accident and
exactly: `CtaSections`' eligibility-gate row is reached **120,026** times today
against round 850's **915,543**. The difference, **795,517**, is precisely the
gap between round 850's cta ON boundary total (2,324,766) and this round's
(1,529,249) — the two arms' *difference* (968,811) is identical to the digit
across the two rounds, which is the instrument's cross-round falsifier.

---

## 3. The deflation, applied BEFORE anything is costed

Round 733: **88.4% of the `…SpineLeave` time is the migrated passes' own
checking work.** Re-measured warm on today's binary by four probes that share no
code:

| probe | object | **net ms** | **% warm** | **checking** | **bookkeeping** |
|---|---|---:|---:|---:|---:|
| `cta` level A | `spineCtaM3StatementAnchor` | **640** | 12.7% | ~600 (**94%**) | **37 (6%)** |
| `cpa` level P | `checkPropertyAccessInExpr` | **462** | 9.2% | 462 (**~100%**) | **≤0** |
| `call` | `checkSingleCallExpressionTypes` | **381** | 7.5% | 352 (**93%**) | **28.5** |
| `spinesections` | `cpaSpineLeave` + `ccetSpineLeave`, whole | **912** | 18.1% | 833 (**91.4%**) | **80** |

Boundary prices, all by the round-850 nesting-aware ON-minus-COARSE estimator
(outermost level's Δms ÷ ALL extra boundaries): `cta` **188 ns**, `cpa`
**113 ns**, `call` **140 ns**, `spinesections` **55 ns** (that last against the
`plain` arm — see § 5). Round 850 read 127 / 97 / 202 ns for the first three.

**Containment checks against § 2, which is the other instrument:** `cta`
640 vs 620 = **97%**, `spinesections` 912 vs (584 + 433) = **90%**, `cpa` 462 of
584 = **79%**, `call` 381 of 433 = **88%**. Round 850's weakest check was 68%;
every one is tighter now.

### 3.1 `spineCtaM3StatementAnchor` — 94% checking, and the 6% itemised

| row | raw ms | closes | ns/close | **net ms** | class |
|---|---:|---:|---:|---:|---|
| `checkVarDeclAssignability` | 256.0 | 14,735 | 17,374 | 253.2 | checking |
| `checkReturnAssignability` | 249.5 | 9,926 | 25,136 | 247.6 | checking |
| `checkAssignmentExpression` | 160.0 | 16,538 | 9,675 | 156.9 | checking |
| `walkFunctionBodiesInExpr` | 144.5 | 28,940 | 4,993 | 139.1 | checking |
| **frame + ambient install + ns push** | 27.0 | 58,581 | 461 | **16.0** | bookkeeping |
| **eligibility gate + parent climbs** | 37.0 | 120,026 | 308 | **14.4** | bookkeeping |
| **dispatch + decl loop** | 35.5 | 137,143 | 259 | **9.7** | bookkeeping |
| **`finally` (truncate + restore)** | 7.5 | 58,581 | 128 | **−3.5** | UNRESOLVED |

(The checking rows' nets above are before subtracting the 1,060,384 level-B/C/D/E
boundaries that execute *inside* them; doing so takes checking to ~600 ms and
makes 37 + 600 = 637 against level A's own 640 — a 0.6% partition check.)

Round 850 measured **44 ms / 6%**; today **37 ms / 6%**, at a denominator 29%
smaller. And the largest of the four is **16 ms — below the floor** — while the
gate, which is the (SPINE.1) thesis in its purest form, **has already been cut**:
round 888's mask took its population from 915,543 to 120,026, i.e. **87% of it is
already gone** and what is left is 14 ms.

### 3.2 `checkPropertyAccessInExpr` — the bookkeeping is measured at or below one boundary

| row | raw ms | closes | ns/close | net ms |
|---|---:|---:|---:|---:|
| `checkSinglePropertyAccess` (level Q) | 623.0 | 66,747 | 9,334 | 615.5 |
| **dispatch + pass-through arms (the walk)** | 77.5 | 801,892 | **97** | **UNRESOLVED** |
| `cpaComputeArgCtxTypes` | 36.0 | 51,967 | 693 | 30.1 |
| wrapper transition (probe-only) | 17.5 | 399,336 | 44 | UNRESOLVED |
| **call-argument ctx loop** | 13.0 | 141,410 | **92** | **UNRESOLVED** |
| **arrow/fn-expr SCOPE bookkeeping** | 9.5 | 4,971 | 1,911 | **8.9** |

Against `b = 113 ns`, every bookkeeping row except the arrow/fn-expr one is
**below one probe boundary** — its true cost lies in `[0, raw]` and this
instrument cannot resolve it. Round 850 read the walk at 93 ns against a 97 ns
boundary and said the same. **Two independent draws, two rounds: the traversal is
at the measurement floor and the cost is the calls.**

### 3.3 `checkSingleCallExpressionTypes` — 93% checking

`net = 381 ms`. `getCalleeType` 81 ms, the single-signature branch 260 ms (of
which `checkArgumentsAgainstSignature` 239). The whole **dedicated-walker layer**
— B216, the six round-793 prologue walkers, TS2722, TS2347, the five single-sig
walkers — is **~28.5 ms = 0.56%** (round 851: 37.7 ms = 0.51%), and the
round-793 pre-gate **refuses 51,394 of 52,413 invocations (98.1%)**. The exit
census still reads **0 of 52,413 invocations EMIT** on this profile — round 792's
law is the whole reading of that zero.

---

## 4. `SpineSections` warm for the first time — the (SPINE.1) thesis, itemised

Rounds 733 and 799 ran this probe COLD, in a one-shot `MainKt`, where a boundary
is 2.5–5× more expensive (round 850) and the handlers' own passes were 29–40%
larger. It is the only probe that partitions the two LEAVE handlers
*themselves* rather than their payloads. All eleven disjoint sections are
consulted at all **856,962** nodes (neither handler is masked), so **each section
carries 47 ms of pure boundary at `b = 55 ns`** — which is exactly why a cold
table's `net` column cannot be inherited.

| section | raw ms (4 draws) | **net ms** | **% warm** |
|---|---|---:|---:|
| `ccet`: call/new/tagged anchor (m3) | 454 419 458 473 | **403.9** | 8.00% |
| `cpa`: anchor stmt (m3a/m3b) | 451 390 403 399 | **363.6** | 7.20% |
| `cpa`: owner cond/subject (m3b) | 207 190 191 190 | **147.4** | 2.92% |
| `cpa`: VariableDeclaration recordings | 87 68 74 67 | 26.9 | 0.53% |
| `ccet`: VariableDeclaration recordings | 61 57 57 56 | 10.6 | 0.21% |
| `cpa`: frame pop | 49 47 48 48 | **0.9** | — |
| `ccet`: frame pop | 47 44 44 45 | **−2.1** | UNRESOLVED |
| `cpa`: loop-var restores | 43 40 41 40 | **−6.1** | UNRESOLVED |
| `cpa`: heritage EWTA (m3c) | 41 38 40 39 | **−7.6** | UNRESOLVED |
| `cpa`: PropertyDeclaration init (m3c) | 38 36 38 37 | **−9.9** | UNRESOLVED |
| `ccet`: override restores | 33 31 33 31 | **−15.1** | UNRESOLVED |
| *nested:* `withCpaFrameAmbient` (install+work) | 556 497 491 504 | 507.6 | |
| *nested:* `withCcetFrameAmbient` (install+work) | 411 383 411 433 | 405.8 | |
| *nested:* **cpa ambient install+restore ONLY** | 89 57 53 52 | **54.0** | 1.07% |
| *nested:* **ccet ambient install+restore ONLY** | 31 28 38 36 | **25.8** | 0.51% |
| *nested:* `ccetM3ChainOk` (depth 9) | 15 14 14 14 | **11.3** | 0.22% |
| *nested:* `cpaM2ChainOk` (depth 6) | 11 10 11 11 | **6.1** | 0.12% |
| *nested:* `cpaM2StmtPosition` | 9 4 8 3 | **2.2** | 0.04% |

**Round 733's split, re-derived warm:**

| | round 733 (cold) | round 799 (cold) | **round 908 (WARM)** |
|---|---:|---:|---:|
| partition total, net | 8,195 | 5,831 | **912** |
| — the passes' OWN checking work | 88.4% | 84.3% | **91.4%** |
| — ambient install + restore | 4.4% | 5.8% | **8.7% (79.8 ms)** |
| — outside the ambient (gates, pops, restores) | 7.2% | 9.8% | **~0** |
| — the three ancestor climbs | **2.1%** | 3.2% | **2.1% (19.6 ms)** |

**Every frame pop and every restore is at or below one probe boundary.** That is
the "per-node bookkeeping that exists to reproduce the deleted walkers' ambient
state" the queue entry names, and it is at the measurement floor: five of the
eleven sections read NEGATIVE after their own boundary is subtracted.

---

## 5. The one row that looked like a lever, and the census that refuted it

**The two frame-ambient installs are 54.0 + 25.8 = 79.8 ms = 1.58% of a warm
rebuild** — the only object in (SPINE.1) above the floor. Their bodies are the
round-869 per-scope-copy shape: save the enclosing namespace/class stacks into a
fresh `ArrayList`, `clear()` them, and REBUILD them by scanning the whole frame
stack, then `clear()` + `addAll` on the way out.

So this round censused what that rebuild actually does, at the one call site the
probe already brackets (`sec >= 0`, i.e. only `cpaSpineLeave` /
`ccetSpineLeave`). **That test is true in PRODUCTION too** — only the timing
inside `close` is mode-gated — so the census's own mode test had to go at the
CALL SITE: round 900's law is that a callee's `if (off) return` cannot protect
its own arguments, and production must not make even the three `size` reads.
With it there, production pays one static read and a not-taken branch.
**Identical to the digit in all four draws:**

| | installs | frames scanned | mean | max | **entries REBUILT** | **entries SAVED** |
|---|---:|---:|---:|---:|---:|---:|
| `withCpaFrameAmbient` | 79,865 | 232,373 | **2.91** | 8 | 6,835 | **0** |
| `withCcetFrameAmbient` | 67,707 | 199,761 | **2.95** | 8 | 5,861 | **0** |

**The mechanism is vacuous.** The "O(frames) rebuild" walks **2.9 frames**, not a
deep stack; **91.4% of installs produce no entry at all**; and the save copies
**zero entries at every one of the 147,572 installs** — the namespace/class
stacks are empty whenever a frame installs. Priced with this arc's own measured
rates (round 905: 11.95 ns for an iterator-bearing call; ~15 ns for a small
allocation; round 904: 6.58 ns for a boxed map op as the reference for a
few-instruction operation), the whole deletable population —

* 215,279 `ArrayList(<empty collection>)` constructions,
* 147,572 frame-stack iterator allocations over 432,134 iterations,
* 295,144 `clear()` calls and 147,572 `addAll` of an empty list

— is **~8 ms = 0.16%**, refused by 2×. Making the stacks incremental (maintained
at frame push/pop, so the install is a no-op) is the fix, and it is worth half
the floor.

### 5.1 …and the row itself fails round 896's divide-by-population test

54.0 ms over 79,865 installs is **676 ns per install** for ~16 field
save/restores, one copy of an EMPTY collection and a 2.9-iteration loop. A
`getfield`/`putfield` is sub-nanosecond; that is ~20× physically impossible, and
round 896's law says an owner row not divided by its own population has not been
priced. The mechanism is known: **a timestamp is an optimizer barrier.**
`System.nanoTime()` cannot be reordered across, so bracketing a block of field
saves and restores forces every one of them to happen, in order, where
production lets the JIT coalesce and register-allocate them. **The 79.8 ms is
therefore an upper bound containing an unknown instrument component, and the
CENSUS — not the row — is what bounds the prize.**

---

## 6. The verdict, row by row

Everything in (SPINE.1) that is not the migrated passes' own checking work,
ranked by what DELETING it would return:

| candidate | **ms** | **% warm** | why it is not a lever |
|---|---:|---:|---|
| the three ancestor climbs | **19.6** | 0.39% | round 733's hypothesis #1, refused there by 6–17× and again here: the climbs cost 73 / 213 / 32 ns per call at mean depths 6 / 9, and a classifier is consulted at most once per node (round 875), so a memo can never answer its own query — round 907's law, *a cache in front of work shorter than its own probe cannot win* |
| `cta` frame + ambient install + ns push | 16.0 | 0.32% | load-bearing — it IS the handler; and at the floor |
| `cta` eligibility gate + parent climbs | 14.4 | 0.29% | **already cut 87% by round 888's mask** (915,543 → 120,026 consultations) |
| `cta` dispatch + decl loop | 9.7 | 0.19% | load-bearing |
| the namespace/class stack save+rebuild+restore | **~8** | 0.16% | § 5 — the census prices it at half the floor |
| `cpa` arrow/fn-expr SCOPE bookkeeping | 8.9 | 0.18% | below the floor |
| `cpa`/`ccet` VariableDeclaration recordings | 37.5 | 0.74% | *checking* — they record types the later passes read |
| `cpa` walk, call-arg ctx loop, all frame pops and restores | `[0, 200]` raw | — | **every one below one probe boundary**, in two independent rounds |
| `call` dedicated-walker layer | 28.5 | 0.56% | *checking* — 98.1% already pre-gated (round 793); round 851 refused it at 0.51% |
| the whole (DISPATCH.1) per-kind table | 40–120 | 0.8–2.4% | closed cold (732), warm (847) and priced exactly (867: 73.2 ms); **the skeleton landed at round 888** |

**Nothing clears ~17 ms as a deletable region.** The largest object, at 19.6 ms,
is the one round 733 refused first, and it is refused again by the same
arithmetic in a second regime.

---

## 7. What this closes, and what it corrects

* **(SPINE.1) is CLOSED.** The six handlers are 40.1% of a warm rebuild and
  **~91–100% of that is type-system work**, measured by four probes sharing no
  code, in both regimes, across rounds 733 / 799 / 850 / 851 / 908. The
  bookkeeping is **~137 ms = 2.7%** in total, and its largest single row is
  0.39%.
* **The standing six-handler list is stale in ORDER for the second time.**
  `ccetSpineLeave` was #1 warm at round 847 and is **#3** today; the list's
  membership is unchanged and its 62.6% share is unchanged.
* **Round 850's numbers reproduce and its share column does not.** 649 → 640 ms
  is −1.4%; 9.17% → 12.7% is +39%. Quote the ms.
* **The `dispatch` tier prices a regime production no longer runs** (§ 2.1) —
  it bypasses `spineEnterMask`. Worth ~73 ms on its total, and structurally
  blind to the one lever this region has already banked.
* **A row that survives every deflation can still fail the divide-by-population
  test** (§ 5.1). The install row is 1.58% and its own census says the mechanism
  inside it is vacuous.

## 7a. Gates

| gate | result |
|---|---|
| `jvmTest`, 4 modules, real XML parser over `*/build/test-results/jvmTest/*.xml` | **14,439 / 0 failures / 3 skipped** — the 14,437 baseline plus this round's 2 pins, exactly |
| `cost_gate.py` | **+0.00% on every counter**, 46 errors / 78 files |
| `huge_methods.py --fail-over 0` | **0 over the limit** (732 classes, 15,698 methods) |
| all 22 instrumented rebuilds | **78 files / 46 errors**, and `BenchMain` aborts non-zero if an instrumented rebuild disagrees with its own measured loop — none did |
| the census itself | **identical to the digit in all four draws**, which is the instrument's own falsifier |

Both gates ran **before** the daemon stop (round 851) and the class directory was
non-empty at the first sample. `cost_gate.py`'s zero is read as a CONTROL, not a
verdict (round 876): the round changes no checker decision, so a moved counter
would have meant a hook that is not inert. **No 8-profile grid** — the only
`commonMain` change is two lines behind `SpineSections.mode != OFF`, which is
`OFF` in production, and the negative-control pin asserts the census records
nothing while off.

The two new pins are in `SpineSectionProbeTest`: *the install census counts one
record per frame-ambient install* (the record count must be exactly half the
`CPA_INSTALL`/`CCET_INSTALL` close count, which is what says the census sits at
the install and not on some other path) and *the install census records nothing
while the probe is off*. The first carries round 849's positive control — the
fixture declares a `namespace` and the pin demands `rebuilt > 0`, because a
`rebuilt == 0` from a census hooked in the wrong place is indistinguishable from
the real zero this round is reporting.

## 8. For the next agent

* **Reusable constants**: the two frame-ambient installs run **79,865** and
  **67,707** times a warm rebuild, scan **2.91 / 2.95** frames (max 8), produce
  an entry on **8.6%** of installs and copy **zero** entries out on **100%** of
  them. `checkSingleCallExpressionTypes` is 52,413 invocations, 0 emitting.
  `checkPropertyAccessInExpr` is 399,336 in-window / 64,458 outermost;
  `checkSinglePropertyAccess` 66,747; `checkMemberAccessMissing` 46,321.
  `spineCtaM3StatementAnchor` opens level A **61,459** times and its eligibility
  gate is now consulted **120,026** times, not 915,543.
* **The general law this round adds**: *a probe boundary is an optimizer
  barrier, so a section that is nothing but field save/restores is over-read by
  the instrument in proportion to how well the JIT would have removed it.* The
  test is round 896's — divide the row by its population and ask whether the
  per-operation cost is physically possible — and when it is not, the CENSUS of
  what the block does, not the timing of it, is what bounds the prize.
* **And the corollary**: before believing an O(n) walk is a cost, census `n`.
  Here `n` is 2.9 and the walk's result is empty 91% of the time.
* **Do not re-open** the ancestor climbs (three rounds), the frame pops and
  restores (below the boundary in two), the per-kind dispatch table (four
  rounds, and its skeleton has landed), or the per-handler `dispatch` table as a
  price list (§ 2.1).

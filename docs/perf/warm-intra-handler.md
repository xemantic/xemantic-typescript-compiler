# (WARM.4)(b) — the WARM INTRA-handler attribution of the three biggest spine handlers

*Round 850, 2026-08-08. Fifteenth in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → … → `warm-spine-attribution.md` (847) →
`lib-type-rederivation.md` (849) → here. Round 849 committed the harness and
validated its path; this round ran it and reduced it.*

> **HEADLINE — FOUR THINGS.**
>
> **(1) THE VERDICT THE ARC WAS OWED: THE WARM COST INSIDE THESE HANDLERS IS
> STRUCTURAL, NOT RESTRUCTURABLE.** Opened warm, `spineCtaM3StatementAnchor` is
> **94% its four checking calls and 6% scaffolding** (44 ms of 649 = 0.62% of a
> warm rebuild), and `checkPropertyAccessInExpr` is **98% / 2%** (7 ms of 369 =
> 0.10%). Round 733's cold 88.4% reproduces warm, and *tighter*. The frame
> installs, the eligibility gate, the dispatch `when` and the `finally` restores
> — the whole (SPINE.1) "legacy-parity frame bookkeeping" thesis — are together
> **~51 ms = 0.72% of the warm artifact**, and the single largest of them, the
> eligibility gate consulted **915,543** times, measures **88 ns/call raw, below
> one probe boundary**, i.e. indistinguishable from free.
>
> **(2) NO LEVER CLEARS THE ±1.0% WARM BAND.** Every row worth ≥1% is the
> passes' own type-system work. The best non-checking candidate in the whole
> 18.75% of the artifact these three probes attribute directly is the **nine
> pre-emission probes in `checkSinglePropertyAccess`, 70 ms = 0.99%** — at the
> band edge, on a row whose own draw spread is 16%, behind a
> `ccetPrologueMayFire`-shaped correspondence trap and round 792's law. It is
> handed on as a *measured candidate*, not a cleared lever. This is the fifth
> consecutive priced negative in the warm arc.
>
> **(3) THE WARM PROBE BOUNDARY IS ~97–202 ns, MEASURED THREE INDEPENDENT WAYS,
> AGAINST 501 ns COLD — so every cold section table's `net` column
> over-subtracts warm by 2.5–5×, and this round's rows had to be recomputed
> rather than inherited.** The estimator matters: these partitions are LAYERED,
> and summing each level's ON-minus-COARSE delta over each level's own extra
> boundaries **overstates the price by 1.7–2.2×** (215 vs 97 ns for `cpa`)
> because a deep boundary executes inside every level above it.
>
> **(4) THE PARTITION CHECKS, STATED PLAINLY: 87% for `cta`, 68% for `cpa`.**
> Against round 847's per-handler dispatch probe, as shares of the artifact.
> Where they disagree, THESE are the better numbers: round 847's probe cost
> +4,482 to +5,670 ms (it more than doubles a warm rebuild) while these cost
> +100 to +900 ms.
>
> **Nothing was optimized this round. Nothing under `src/` changed.**

---

## 1. How this was measured

One binary, one profile (`build/bench/tsc-project-637d5746` — the compiler
profile: 78 files, 46 errors), `--noEmit`, this box (8 cores, 15.6 GB, zero
swap). `cost_gate.py` (exit 0) and `huge_methods.py --fail-over 0` (exit 0) ran
**before** the daemon stop and long before any sample; Gradle and Kotlin daemons
were stopped **inside** the measuring script between the build and the first
sample (round 800's trap); `free -m` showed 10.2 GB free at the first sample. The
box was not touched while the script ran (round 774).

| probe | tier list | processes | ON draws | COARSE draws |
|---|---|---:|---:|---:|
| `CtaSections` (TYPE.2) | `cta,ctacoarse,cta,ctacoarse` | 2 | 4 | 4 |
| `CpaSections` (ENGINE.2) | `cpa,cpacoarse,arg,argcoarse` ×2 | 2 | 4 | 4 |
| `ArgSections` (CALL.2) | *(same two processes)* | 2 | 4 | 4 |

All **24** instrumented rebuilds answered **78 files / 46 errors**, and
`BenchMain` aborts non-zero if an instrumented rebuild disagrees with its own
measured loop — none did. Every deterministic counter in each probe (invocation
counts, reached counts, arm censuses, exit censuses) is **bit-identical across
all four draws of its tier**; that is the falsification, and it is what lets a
4-draw mean be quoted for the nanos.

**Denominator.** Probe-free warm medians: 6,914.9 / 7,180.5 / 7,114.2 / 7,095.9
ms, **mean 7,076.4 ms**. Every `% warm` below is of that. Per CLAUDE.md's
cross-round rule this absolute is a THIS-ROUND figure — round 847 read 8,095 ms
on the same code — so only the within-round shares travel.

**Reproducing:**

```bash
bash scripts/round849-warm-sections.sh 2   # builds, gates, stops daemons, measures
python3 scripts/round850_analyze.py        # reduces the four logs to §§ 2-5
```

---

## 2. § The WARM probe boundary, and why the obvious estimator is wrong

Round 734 fixed the method: never an empty-span loop, always a differential —
run the same code at N boundaries and at 1, divide by the extra boundaries. The
`*coarse` twins are that, inside the same warm process.

What round 734 did not have to handle is that **these partitions are LAYERED**.
`CtaSections` opens level B inside level A's `checkVarDeclAssignability` row, C
inside `checkReturnAssignability`, D inside `walkFunctionBodiesInExpr`, E inside
`checkAssignmentExpression`; `CpaSections` is a straight chain P ⊃ Q ⊃ R. A
boundary opened at a deeper level *executes inside* the shallower level's span,
so it inflates every level above it too. Summing each level's Δms over each
level's own Δboundaries therefore counts a deep boundary once per level above
it:

| probe | naive Σ estimator | **nesting-aware** (outermost level's Δ ÷ ALL extra boundaries) | probe's own in-situ |
|---|---:|---:|---:|
| `cta` | 214 ns | **127 ns** | not measured |
| `cpa` | 215 ns | **97 ns** | not measured |
| `arg` (single level) | 202 ns | **202 ns** | **134 ns** (mean of 4; 174 ns on draw 1) |

**The check that this is right, and it is not circular for the inner levels:**
the boundary price is chosen to make the ON and COARSE arms agree at the
*outermost* level, but nothing forces the inner ones to follow — and they do.

| level | ON raw | **ON net** | COARSE raw | **COARSE net** |
|---|---:|---:|---:|---:|
| `cta` A | 945 | **649** | 821 | **649** |
| `cta` B | 264 | 251 | 246 | 244 |
| `cta` C | 222 | 203 | 198 | 197 |
| `cta` E | 143 | 128 | 130 | 128 |
| `cpa` P | 662 | **369** | 451 | **369** |
| `cpa` Q | 472 | 326 | 321 | 310 |
| `cpa` R | 290 | 210 | 187 | 182 |

Two arms whose boundary counts differ by **2.5×** (cta: 2,324,766 vs 1,355,955;
cpa: 3,011,148 vs 847,282) land within **0–5%** on every level, and exactly on
the two outermost. `cta` D is the exception (39 vs 49 ms) — it carries 685,984
boundaries against 126 ms of work, the worst ratio in the round, and is the one
level whose absolute should not be quoted.

**Against the cold record.** Round 849 measured an in-situ empty pair at **501 ns
cold**; round 847 measured the `spine` tier's per-node boundary at **1,475 ns
cold vs 797 ns warm**. So the warm boundary here (97–202 ns, ~130 ns central) is
**2.5–5× cheaper than the cold figure a pre-847 table subtracted**. Any `net`
column inherited from a cold section table is therefore over-subtracted by that
factor, which is precisely why every row below was recomputed from raw nanos.

**A row whose raw ns/call is below the boundary is UNRESOLVED, not free.** Its
true cost lies in `[0, raw]` and the table says so rather than printing a
negative as if it were a saving.

---

## 3. § What each probe attributes, and the partition checks

| probe | object | **warm ms** | **% of the warm artifact** | vs round 847 |
|---|---|---:|---:|---|
| `cta` level A | `spineCtaM3StatementAnchor`, the whole handler | **649** | **9.17%** | 853 ms / 8,095 = 10.54% → **87%** |
| `cpa` level P | `checkPropertyAccessInExpr` (the `cpaSpineLeave` payload) | **369** | **5.21%** | 617 / 8,095 = 7.62% → **68%** |
| `cpa` level Q | `checkSinglePropertyAccess` | 310–326 | 4.38% | *(inside P)* |
| `cpa` level R | `checkMemberAccessMissing` | 182–210 | 2.57% | *(inside Q)* |
| `arg` | `checkArgumentsAgainstSignature` | **309** | **4.37%** | `ccetSpineLeave` 876 / 8,095 = 10.82% → arg is **40%** of it |
| | **the three, combined and disjoint** | **1,327** | **18.75%** | |

**Reading the two partition checks honestly.** 87% and 68% are weaker than round
847's own 99.3% / 102.6%, and the round-847 comparison is *cross-round*, which
CLAUDE.md forbids for absolutes — that is why both sides are expressed as shares
of their own round's warm rebuild. Three things are known about the gap:

* **These probes are 5–50× cheaper than the one they are checked against.**
  Round 847's `dispatch` tier costs **+4,482 to +5,670 ms** on a ~8,000 ms
  rebuild; these cost **+100 to +900 ms**. Round 847 itself says its absolutes
  are "sound for RELATIVE attribution only". Converting its per-handler share of
  the *probed spine* into a share of the *artifact* assumes the probe scales
  everything uniformly; where the two disagree, the cheaper instrument's number
  is the better estimate of an artifact share.
* **`arg` is a containment check, not a partition check.**
  `checkArgumentsAgainstSignature` is one region reached from `ccetSpineLeave`,
  so ≤100% is expected; **40%** is the finding, and it means the other ~60% of
  the largest warm handler is still un-partitioned (see § 6).
* **`cpa`'s 68% is the one genuinely open number.** Level P covers every
  `checkPropertyAccessInExpr` invocation (399,336 in-window, 64,458 outermost, 0
  outside — the probe says so itself), so the ~2.4 points not accounted for are
  either the handler's own pre-call bookkeeping outside the window, or
  cross-round drift. This round cannot separate those.

**The correction to a number the queue has been quoting.** "These three probes
cover 48.7% of the warm artifact" is wrong: **48.7% is their share of the warm
SPINE** (round 847 § 4), which is **29.0% of the artifact** — and since `arg` is
40% of its handler rather than all of it, what is now *directly attributed* is
**18.75%**.

---

## 4. § THE DELIVERABLE — checking work vs scaffolding

The question the arc is owed: is the warm cost inside these handlers the passes'
own **checking** work, or the frame/gate/dispatch **scaffolding**? Rows are
classified by what they call, not by where they sit.

### 4.1 `spineCtaM3StatementAnchor` (level A, 649 ms = 9.17%)

| row | raw ms | **net ms** | closes | ns/call raw | class |
|---|---:|---:|---:|---:|---|
| `checkVarDeclAssignability` | 269.8 | **255.2** | 14,735 | 18,309 | checking |
| `checkReturnAssignability` | 226.2 | **205.4** | 9,926 | 22,792 | checking |
| `checkAssignmentExpression` | 147.1 | **129.6** | 16,538 | 8,893 | checking |
| `walkFunctionBodiesInExpr` | 136.4 | **45.6** | 28,940 | 4,714 | checking |
| `checkFlowNoOverlapCondition` | 6.6 | 5.0 | 12,878 | 512 | checking |
| `registerConstLiteralUnionNarrowing` + `checkPropertyInitAssignability` | 1.8 | 0.2 | 11,517 | — | checking |
| **eligibility gate + parent climbs** | 80.5 | **0 (UNRESOLVED)** | **915,543** | **88** | scaffolding |
| frame + ambient install + ns push | 29.7 | 22.3 | 58,581 | 507 | scaffolding |
| dispatch + decl loop | 36.9 | 19.5 | 137,143 | 269 | scaffolding |
| `finally` (truncate + restore) | 9.6 | 2.2 | 58,581 | 163 | scaffolding |
| | | | | | |
| **CHECKING** | 787.9 | **≈641** | | | **94%** |
| **SCAFFOLDING** | 156.7 | **≈44 = 0.62% of the artifact** | | | **6%** |

(The two columns sum to 685 against the level's own 649 — 5.5% over, which is
the round's noise floor; the split, not the sum, is the claim. Net subtracts each
row's own closes *and* every nested level's boundaries that execute inside it,
which is why `walkFunctionBodiesInExpr` — 685,984 level-D boundaries inside it —
falls hardest.)

**The eligibility gate is the (SPINE.1) thesis, and it is measured out.** It is
consulted at **915,543** nodes — the round-732 "consultation cost" in its purest
form — and costs **88 ns per consultation raw, which is below one probe
boundary**. Its true cost lies in [0, 80.5] ms, i.e. at most 1.14% of the
artifact and plausibly zero. Nothing here supports the standing "cta/cpa/ccet
legacy-parity frame bookkeeping" reading of the six-handler list.

### 4.2 `checkPropertyAccessInExpr` (level P, 369 ms = 5.21%)

| row | raw ms | **net ms** | closes | class |
|---|---:|---:|---:|---|
| `checkSinglePropertyAccess` (level Q) | 485.7 | **334.1** | 66,747 | checking |
| `cpaComputeArgCtxTypes` | 39.9 | 34.9 | 51,967 | checking |
| `checkSingleElementAccess` | 8.5 | 8.3 | 1,709 | checking |
| binary left-spine own work | 8.7 | 5.7 | 31,267 | checking |
| block body + objlit/arrow contextual members | 4.6 | 2.9 | 17,272 | checking |
| **dispatch + pass-through arms (the walk)** | 74.9 | **0 (UNRESOLVED)** | **801,892** (93 ns/call) | scaffolding |
| wrapper transition (probe-only) | 18.2 | 0 (UNRESOLVED) | 399,336 (46 ns) | probe |
| call-argument ctx loop | 14.0 | 0.3 | 141,410 (99 ns) | scaffolding |
| arrow/fn-expr SCOPE bookkeeping | 7.3 | 6.8 | 4,971 | scaffolding |
| | | | | |
| **CHECKING** | 546.5 | **≈386** | | **98%** |
| **SCAFFOLDING** | 115.1 | **≈7 = 0.10% of the artifact** | | **2%** |

**The walk itself — 801,892 pass-through arm closes at 93 ns each — is below one
boundary too.** Both handlers give the same answer from independent probes: the
traversal and bookkeeping are at or under the measurement floor; the cost is the
calls.

### 4.3 One level deeper — where the checking work itself sits

| row | net ms | % warm | note |
|---|---:|---:|---|
| `cpa` Q: `checkMemberAccessMissing` (THE ENGINE) | 328.7 | **4.64%** | 85.4% of level Q |
| `cta` A: `checkVarDeclAssignability` | 255.2 | 3.77% | of which B: unannotated-init inference **133.2 = 1.88%** |
| `cta` A: `checkReturnAssignability` | 205.4 | 3.17% | of which C: flow narrowing 60.8, C: the SOURCE type 47.9 |
| `arg`: `loop: argType computation` | 209.4 | **2.96%** | see § 5 |
| `cpa` R: `type = union-receiver narrowing` | 103.3 | 1.46% | 44,843 closes |
| `cpa` R: identifier-receiver special cases | 30.7 | 0.43% | |

---

## 5. § The `arg` exit census — round 759's cold law, priced warm

`ArgSections` already carries an (AUDIT.2) exit census that partitions its
largest row by whether the argument ever reaches the assignability relation. It
costs no new boundary (round 796) and it is the sharpest object in the round:

| | net ms | % warm | args | ns/arg |
|---|---:|---:|---:|---:|
| `loop: argType computation` (the whole row) | 209.4 | 2.96% | 39,034 | 5,565 |
| — argType of args **exiting BEFORE the relation** | **191.2** | **2.70%** | 28,084 | 7,009 |
| — argType of args **reaching** it | 18.2 | 0.26% | 10,950 | 1,861 |
| of which: its `getTypeOfExpression`, exiting | 75.6 | 1.07% | 28,084 | 2,895 |
| of which: its **narrowing walks, exiting** | 48.7 | **0.69%** | **851** | **57,471** |
| `narrow calls returning the INPUT type` | 22.8 | 0.32% | 357 | 64,101 |
| the (CALL.5) pre-gate relation | 34.3 | 0.49% | 9,823 | 3,698 |

**91% of the argument-typing cost is spent on arguments that never reach the
assignability check** — round 759 measured 89% cold, and it now has a warm price:
**2.70% of a warm rebuild.** The concentration inside it is extreme: **851
narrowing walks at 57 µs each = 0.69% of the whole artifact**, and **357 of the
953 narrowing calls return the INPUT type unchanged** (22.8 ms = 0.32%).

None of it clears the band on its own, and two standing laws say the realisable
part is smaller than the row: round 759's (*paying for a value is not wasting
it* — the 89% is consumed by eleven downstream blocks) and round 788's (*skipping
a CACHED resolution moves the work, it does not delete it*).

---

## 6. § The lever question, answered with numbers

Every candidate in the 18.75% these probes attribute, ranked by what deleting it
would return:

| candidate | prize | why it is not landed |
|---|---:|---|
| the nine pre-emission probes in level Q (all `exits 0`, all paid at **every** one of 66,747 property accesses) | **70 ms = 0.99%** | at the band edge; its dominant row's own draw spread is 16%; needs a `ccetPrologueMayFire`-shaped pre-gate, i.e. a superset of nine first gates kept in exact correspondence (a documented trap), and round 792's law says a "cannot fire" gate on this function killed 7 corpus baselines while measuring 0 emitting calls on this very profile |
| — of which `emitTs18048 closure-captured receiver` alone | 58.8 ms = 0.83% | below band |
| `cta` scaffolding (frame install + dispatch + `finally`) | 44 ms = 0.62% | load-bearing; the gate half is already UNRESOLVED |
| `arg`'s narrowing walks on arguments that exit | 48.7 ms = 0.69% | rounds 759 + 788 |
| the (CALL.5) pre-gate relation | 34.3 ms = 0.49% | below band |
| `arg` narrow calls returning the INPUT | 22.8 ms = 0.32% | below band |
| `cpaComputeArgCtxTypes` | 34.9 ms = 0.49% | round 788 measured this exact skip: 94.3% of calls skipped, row fell 109 ms, the engine row rose by about as much — **MOVED** |
| (DISPATCH.1) per-kind table | 40–120 ms = 0.5–1.5% | re-priced warm and re-closed at round 847 |

**Nothing clears ±1.0%.** Everything at or above 1% is the type system doing its
job: `checkMemberAccessMissing` 4.64%, `checkVarDeclAssignability` 3.77%,
`checkReturnAssignability` 3.17%, argument typing 2.96%, unannotated-init
inference 1.88%, union-receiver narrowing 1.46%. Each of those has already been
opened by a dedicated round (789/791 for the first, 801 for the flow graph, 759
for the fourth) and none produced a lever either.

---

## 7. § What this does NOT show

* **One profile, one box, `--noEmit`, no A/B.** No arm was built; nothing was
  optimized.
* **~60% of the largest warm handler is still un-partitioned.** `arg` accounts
  for 40% of `ccetSpineLeave`; the rest of `checkSingleCallExpressionTypes` —
  callee resolution, overload selection, the round-793 prologue — has no warm
  section probe. That is the only remaining place in the top four where a warm
  table would show something new, and it is the natural next instrument.
* **`cpa`'s 2.4-point partition gap is unexplained** (§ 3).
* **Rows below ~5 ms are not resolved at n=4** — the per-draw spreads reach 160%
  on small rows and are printed for every row so a reader can see which.
* **`cta` level D's absolute is unquotable** (685,984 boundaries against 126 ms).
* The classification in § 4 is by CALLEE, and a "checking" row includes whatever
  scaffolding lives *inside* the called pass — round 733's split of
  `ccetSpineLeave` is the precedent, and neither round can see below its own
  partition.

---

## 8. § The instrument defect a reader must know about

**A `*coarse` table prints `mode: ON` (`CpaSections` prints `mode=OFF`).**
`BenchMain` clears each probe's `mode` immediately after the instrumented
rebuild and *before* dumping the report, so `report()`'s
`if (mode == COARSE) "COARSE" else "ON"` label is always wrong for a COARSE arm.
The data is unaffected and the arm is unambiguous from the payload — a COARSE
run prints only the anchor/wrapper rows (3 rows for `cta`, 1 per level for
`cpa`/`arg`) and carries ~2.5× fewer boundaries — but the label alone would send
a reader to the wrong conclusion about which arm they are holding. Fixing it is a
`commonTest` reorder (dump, then clear) and was not done this round; it is queued.

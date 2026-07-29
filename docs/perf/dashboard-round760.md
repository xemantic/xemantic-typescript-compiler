# The 8×2 dashboard, re-measured — round 760

`--noEmit --listAll` on all eight tsc-source profiles, bucketed by code, under both
lib arms. The last end-to-end measurement of this table was **round 730**; rounds
731–759 gated per-round on the *compiler* profile alone, and round 754 already
caught one drift that had entered unnoticed. This is the re-measurement.

The bench TSVs are **gitignored** (round 739), so the dashboard has no committed
home other than this file. Numbers live here, not only in `bench/*.tsv`.

## 0. The arms, and one honest caveat about arm C

| arm | libs | `types` | what it is |
|---|---|---|---|
| **A** | embedded | `[]` | the historical dashboard arm; needs no `node_modules` at all |
| **C** | real | `["node"]` | what a project build actually does since round 730 flipped `projectDefaults()` |

**Arm C is NOT round 730's arm C.** Round 730 measured it against the real
`@types/node` v22.20.1, left in `build/bench/tsc-project-*` by an earlier online
session. `bench-compile-tsc.sh` `rm -rf`s that directory on any run without
`--node-stub`, and it is gone — there is no real `@types/node` anywhere on this box
(checked). This round's arm C therefore uses the script's own all-`any` **stub**,
which can only ever SUPPRESS a diagnostic, never add a typed one. So arm C here is
a *floor*, and its absolute numbers are not comparable to round 730's column;
**arm A is the arm that compares directly**, and it is the one every judgement
below rests on.

## 1. The table

| profile | A @730 | **A @760** | Δ | C @730 † | **C @760** † |
|---|---:|---:|:--:|---:|---:|
| compiler | 46 | **46** | — | 13 | 1 |
| tsc-cli | 46 | **46** | — | 13 | 1 |
| jsTyping | 46 | **46** | — | 13 | 1 |
| deprecatedCompat | 46 | **46** | — | 13 | 1 |
| typingsInstallerCore | 46 | **46** | — | 13 | 1 |
| services | 46 | **47** | **+1** | 13 | 2 |
| server | 46 | **47** | **+1** | 13 | 2 |
| harness | 94 | **95** | **+1** | 43 | 21 |

† arm-C columns are measured against DIFFERENT `@types/node` (real v22.20.1 vs
stub); the Δ is deliberately omitted because it would not mean anything.

Composition, arm A:

| profile | by code |
|---|---|
| the five at 46 | `TS2591×43, TS2304×2, TS2584×1` — **identical to round 730** |
| services / server | the above **+ `TS2345×1`** |
| harness (95) | `TS2591×66, TS2304×10, TS2584×6, TS2503×6, TS7006×3, TS2339×2, TS2593×1` **+ `TS2345×1`** |

## 2. Per-line judgement on every profile that moved

**Five profiles did not move at all** — same count AND same composition as round
730. Fifty-odd commits across rounds 725–759 touched the relation, narrowing,
overload selection, name resolution, enum typing, the real-lib path and the emit
gate, and the compiler-profile dashboard is where it started. That is the headline.

**services, server and harness each moved +1, and it is ONE line, not three.**

    src/services/completions.ts:2237:61 - error TS2345:
        Argument of type 'Node' is not assignable to parameter of type 'Identifier'.

All three profiles that gained a line are exactly the three that contain
`src/services`, and the byte-identical line appears in each. **Direction: UP, and
it is a regression** — real tsc reports zero errors on its own source, so every
line here is a false positive by construction. Round 754 found it, confirmed a
build of unmodified HEAD emits it, and attributed it to rounds 741–753 rather than
to its own fix. Root-caused this round: see § 4.

**Nothing else on the grid is a defect.** Arm C's 21 harness lines are `console`
(the stub declares none), `BufferEncoding`, `Error.captureStackTrace` and mocha's
`it` — all env-legit absences of the stub, all of which the real `@types/node`
supplies. The single TS2584 on the other seven arm-C runs is the same `console`.
**Across the whole 8×2 grid there is exactly ONE non-env-legit line**, and it is
the one above.

## 3. Emit — the path every gate since round 738 has skipped

Round 738 (`3570483c`) gated emit off for `--noEmit` project builds. Every gate
since has passed `--noEmit`, so the emitting path had gone un-exercised end to end
for twenty-two rounds.

- The gate is `skipEmitOutputs = noEmit || config.options.noEmit`
  (`ProjectCompiler.kt:144`), read at exactly one site
  (`TypeScriptCompiler.kt:1646`). An emitting build is untouched **by
  construction**.
- Empirically: the compiler profile run WITHOUT `--noEmit` writes **78 `.js`
  files** to `dist/`, which is exactly the `emitted=78` recorded in every pre-738
  row of `bench/self-compile-tsc.tsv`, and the same 46 diagnostics as the
  `--noEmit` run. Output is well-formed CommonJS.
- Byte-level emit is gated continuously by the corpus's own `.js` baseline
  subtests, which are generated pre-738 and unchanged since; the ENTIRE generated
  corpus — all 25 classes — ran green this round.

Forward baseline, so a future round can diff without a rebuild — `sha256` of the
concatenation of the 78 emitted `.js` files in sorted path order, compiler profile,
arm A:

    e31fafba50001da1043553c6311332d90174f04aafea50f35d566f3c8f20ccfc

## 4. Root cause of `completions.ts:2237`, and what was and was not fixed

Reduced from 300k LOC to **12 lines**, cross-file. Four ingredients, each shown
necessary by ablation (a variant missing any one of them is clean):

1. a `kind` discriminant typed as an **enum**;
2. **two** type-predicate guards on the same reference;
3. the first guard's consequent **early-returns**;
4. the guards are declared in **another file**.

Mechanism, measured rather than reasoned — a `const s: string = node` probe reports
the narrowed type at each point, and `never` is assignable to `string`, so a washed
reference makes the probe SILENT:

| point | before | after | tsc |
|---|---|---|---|
| after `if (isMod(node)) return …;` | *(silent)* = `never` | `Node` | `Node` |
| inside `if (isIdent(node))` | *(silent)* = `never` | `Ident` | `Ident` |

`Node { kind: K }` is not a subtype of `Mod { kind: K.A }` — an enum is the union
of its members' literal types. The structural engine cannot see that:
`getDeclaredTypeOfEnumMember` mints member types carrying **no members**, so the two
`Type.Object`s relate **vacuously**. So `!isMod(node)` collapsed `node` to `never`,
and the next guard had nothing left to narrow.

Round 472 knew this and vetoed the collapse with `kindDomainProvesNotSubtype`.
Round 753 deleted the veto, stating that (REL.1)(a)/(b) had taught the relation to
make the distinction itself. **It had — for member-vs-member. The enum-vs-MEMBER
direction is still decided vacuously**, and `checkTypeRelatedToCore` says so in a
comment written the same round: *"The enum → MEMBER direction is deliberately NOT
decided here … our leniency there is pre-existing and unmeasured."*

### What landed

`enumMemberDomainProvesNotSubtype`, consulted at the ONE call site that needs the
answer — the type-guard negative branch — and only ever to DECLINE a collapse to
`never`. Six pins; on unmodified `aef21e76` the **three targets FAIL and the three
negative controls PASS**. Corpus: all 25 generated classes, 0 failures
(1,989 + 3,416 + 1,038 + 1,533 + 862 across five batches).

### What was sized and refused — two engine defects, both measured

**(REL.2) — closing the enum → MEMBER direction globally.** Written, compiled,
measured, reverted. It is *correct*: `K` stops being assignable to `K.A`. It costs
**compiler 46 → 52 and services 47 → 57**, because the vacuous `true` is currently
masking that many **flow-narrowing gaps** — every new line is a `SyntaxKind` value
tsc has narrowed and we have not:

- `parser.ts:2629`, `3762`(×2) — a type guard used as a **ternary condition**
  (`isTemplateLiteralKind(kind) ? factory.createTemplateLiteralLikeNode(kind, …)`);
- `parser.ts:8444`, `8728` — `===` enum narrowing **across an `||`**
  (`currentToken === WithKeyword || (currentToken === AssertKeyword && …)`);
- `scanner.ts:905` — `SyntaxKind` → `CommentKind`.

So the relation rule is not the unit of work; **the narrowing features are**, and
they must land first. Recorded in place in `checkTypeRelatedToCore` so the next
agent finds the price beside the leniency.

> **CORRECTED IN PLACE, round 761 — this subsection's two headline claims are
> FALSIFIED.** (1) The member does not resolve to `any`; it resolves to the
> **unsubstituted type PARAMETER**. The probe used below is a READ into `string`,
> and an unconstrained type parameter relates leniently in that direction, so it
> cannot tell `any` from `T` — a WRITE probe (`s2.v = "x"`) reports
> `not assignable to type 'TFWD'`. (2) **tsc's real shape is NOT
> `interface AbstractKeyword extends KeywordToken<…>`** — `src/compiler/types.ts`
> line 1632 writes `export type AbstractKeyword =
> ModifierToken<SyntaxKind.AbstractKeyword>`, a type ALIAS, which takes the working
> `resolveGenericPropertyType` path. Probed against the real source: `.kind` is
> `SyntaxKind.AbstractKeyword`, correct, and always was. **So this is not the root
> cause of `completions.ts:2237`, and "it is why the landed veto cannot fire" does
> not follow** — the round-761 fix for the defect below fires **zero** times on all
> eight profiles (shadow A/B counter). The line's visible cause is that inside
> `if (isIdentifier(node))` the reference stays `Node`: the (REL.2) narrowing
> family. Details: PLAN-PHASE-5.md round 761.

**(REL.3) — a type argument is LOST after two heritage hops.** This, not the enum
rule, is the actual root cause at `completions.ts:2237`, which is why the landed
veto does not move that line. tsc's real shape is
`AbstractKeyword extends KeywordToken<SyntaxKind.AbstractKeyword>`,
`KeywordToken<TKind> extends Token<TKind>`, `Token<TKind> extends Node { kind: TKind }`.
Measured, and **not enum-specific**:

| shape | `.` member resolves to |
|---|---|
| `Token<K.A>` directly | `K.A` ✓ |
| `interface L1 extends Token<K.A> {}` | `K.A` ✓ |
| `interface L2 extends KeywordToken<K.A> {}` where `KeywordToken<T> extends Token<T>` | **`any`** ✗ |
| `interface BoxA extends Box<K.A> {}` | `K.A` ✓ |
| `interface FwdA extends Fwd<K.A> {}` where `Fwd<T> extends Box<T>` | **`any`** ✗ |

One hop substitutes; a second hop through an intermediate that **forwards its own
type parameter** to its base degrades to `any`. `getTypeFromBaseTypeExpression`
interns `Reference(KeywordToken, [K.A])`, but resolving *that* reference's own base
`Token<TKind>` needs the outer mapper applied to a type argument that is itself a
type parameter, and the substitution is not carried. This is M3.1 (generic
instantiation) territory — an arc, not a round.

Because `AbstractKeyword.kind` is `any`, the landed veto cannot fire on the real
shape: there is no `EnumLiteral` on the target side to see. **The eight profiles
are byte-identical before and after the fix**, which is stated here as a limit on
the evidence, not as a credential (round 753's own rule: an ablation that never
fires proves nothing).

## 5. Predictions, scored

Stated before measuring, per rounds 758/759.

| # | prediction | outcome |
|---|---|---|
| 1 | arm A moves on ≥1 profile | ✓ three, all one line |
| 2 | the mover is `completions.ts:2237` | ✓ |
| 3 | ≥1 OTHER profile also drifted from 50 commits | ✗ **five unmoved, code for code** |
| 4 | the reduction is contained enough to fix | ✗ the *reduction* was; the *profile line* was not |
| 5 | the enum→member relation rule is the whole fix | ✗ it is neither necessary nor sufficient — (REL.3) is |
| 6 | emit is fine | ✓ 78 files, matching the pre-738 record |

**3 of 6.** Predictions 3 and 5 are the ones that moved the map: the codebase is
more stable under fifty commits than assumed, and the enum story that rounds
741–753 spent themselves on is a *layer above* a substitution defect nobody had
measured.

## 6. Reproduction

    PROFILES="compiler tsc jsTyping deprecatedCompat typingsInstallerCore services server harness" \
    ARMS="A C" bash <driver>

Arm A writes `"useRealLibs": false` + `"types": []` and removes
`node_modules/@types/node`; arm C writes `"types": ["node"]`, leaves `useRealLibs`
at the `projectDefaults()` value, and materialises the stub from
`scripts/bench-compile-tsc.sh`. Logs: `build/bench/dash760/` (pre-fix) and
`build/bench/dash760b/` (post-fix); the two differ in no byte.

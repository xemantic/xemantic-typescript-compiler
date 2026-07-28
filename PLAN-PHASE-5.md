# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `docs/history/PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Round 744 (2026-07-28) — (REL.1)(b) LANDED. THE TWO BLOCKING FPs WERE BOTH ENUM-FREE, BOTH
PRE-EXISTING, AND BOTH REDUCED ON THE FIRST ATTEMPT ONCE THE MODEL STOPPED COPYING THE
DIAGNOSTIC MESSAGE.** Corpus **12,971 / 0 failures / 3 skipped** (+15 pins over three commits;
skipped 8 → 3 because **all five `@Ignore`s in `EnumMemberRelationTest` are ON**, the item's stated
acceptance criterion). Compiler profile `--listAll` **BYTE-IDENTICAL at 46** — diffed line-by-line
against the pre-(b) 46, not just counted. Three commits on main, `9a8088a5` / `c1ed5cd5` /
`e787480d`; **no branch is parked any more — (REL.1)(b) is on main.**

**WHY ROUND 743's REDUCTIONS CAME BACK CLEAN, AND WHAT THE MISSING INGREDIENT ACTUALLY WAS.** Both
FPs are describable in one sentence, and in both cases that sentence is WRONG because it is the
message's sentence:

- `declarations.ts:846` reads *"the `if (!input) return` truthiness narrow does not strip
  `undefined` from a `T | undefined`"*. **It strips it correctly.** The reported type
  `T | undefined` is the DECLARED type, printed by an elaboration that never sees the flow type;
  the actual type at `return input` is `T | StringLiteral | NoSubstitutionTemplateLiteral`, and the
  failing constituent is `NoSubstitutionTemplateLiteral`, which appears nowhere in the message. The
  probe that settled it was four `const z: number = input` assignments at four flow points, whose
  messages DO print the flow type.
- `utilities.ts:4175` reads as *"intersection-over-union distribution"*, which is right, but the
  reduction only bites when the two sides' union constituents are written in a **different ORDER** —
  same-order sides are the same interned instance and pass on the `source === target` fast path
  before any rule runs. A tidied-up model passes vacuously.

**BUG 1 — A TYPE GUARD ON A TYPE PARAMETER REPLACED IT INSTEAD OF INTERSECTING** (commit
`9a8088a5`). tsc's `getNarrowedType` can never take either subtype arm for a bare type parameter
(nothing is a subtype of `T`; `T` is a subtype of nothing), so it falls to the *instantiable* tail
and answers `T & candidate`, per candidate constituent. Ours asked the relation instead — and the
relation answers "the candidate is assignable to `T`" **whenever the candidate is a UNION**, while
correctly rejecting a single-type candidate. The second lenience (`T` relates to any object target)
then returned bare `T` for the single case, so **the two lenience directions CANCEL for one
candidate and COMPOUND for a union**: the guard handed back the whole candidate and dropped `T`.
`if (guard(x)) {}` — an EMPTY body — is enough to reproduce, and no enum, no discriminant, no
`undefined` and no truthiness narrow is load-bearing. Every one of those was tried as an
explanation first.

**BUG 2 — AN INTERSECTION SOURCE CARRYING A UNION DID NOT DISTRIBUTE** (commit `c1ed5cd5`).
`A & (B | C)` denotes `(A & B) | (A & C)`; we store it un-distributed and every rule then asks the
WHOLE intersection against one union member at a time, so `A & (B | C)` vs `B` fails (neither
`A → B` nor `B | C → B`). `intersectionSourceDistributes` is a strict FALLBACK — consulted only
after the plain rule already answered false, so it can only turn a rejection into an acceptance —
distributing the FIRST union constituent only and bailing above 8 members. Applied at the union-target
and intersection-source rules; the intersection-TARGET rule reaches them by recursion. **Interning a
union by SORTED constituent ids would also have hidden this and must not be done**: union display
order is pinned to pristine tsc's source order.

**BOTH ARE PRE-EXISTING AND ENUM-FREE, PROVED BY ABLATION, WHICH IS WHY THEY LANDED ON MAIN FIRST.**
A build of pristine main (`bfae3125`) fails both reductions and passes both controls. So they are
their own commits with their own pins (`TypeParamTypeGuardNarrowingTest`,
`IntersectionOverUnionRelationTest`), each gated by its own full suite, and each measuring
**+0.00% on all 20 cost counters with the profile unchanged at 46** — i.e. neither shape occurs on
the profile until (b) makes a sibling member reject.

**WHAT (b) IS** (commit `e787480d`). One rule in `checkTypeRelatedToCore` — two enum-member types
relate only when they denote the SAME member — with a STRUCTURAL verdict
(`enumMemberTypesAreSameMember`, tsc's `isEnumTypeRelatedTo`) rather than identity, because
`canonicalEnumSymbol` can only canonicalize through `globals[name]` and INV.3(d) retired that merge
for module-only names, so an identity verdict declares `SyntaxKind.StringLiteral` disjoint from
itself. Landing with it, because (b) is what makes their premise true:

1. **The round-459 AST enum-member key gate in `signatureAcceptsArgs` is RETIRED.** Its three
   original pins keep passing unchanged, which IS the ablation evidence that the general type path
   now reaches the same verdict.
2. **A CONSTRAINT check for round 481's bare-`Type.TypeParam` lenience.** Two GENERIC overloads
   distinguished only by their type parameters' constraints always picked the first. **The pin had
   to be rewritten once**: with GENERIC return types every direction goes silent (a parameterized
   interface relates leniently to another), so the first version asserted silence on a build that
   never picks right — non-generic returns make all four directions sharp, two of them errors.
3. **(a)'s string TARGET leg is deleted** — tsc rejects `string → Ext.Dts`; a string enum is
   nominal. That is the fifth `@Ignore`.
4. **B266 (`checkNsEnumUnionOne`) RETRACTS the general TS2322 at its position.** With (b) the
   relation reaches the same verdict for a union annotation of enum members, and the two co-emitted
   on three of `enumLiteralAssignableToEnumInsideUnion`'s five lines. Only B266's DISPLAY is tsc's
   (a fully-covered member set collapses to the bare enum name, `boolean | Foo`).

**COST GATE.** Commits 1 and 2: every counter +0.00%. Commit 3 moved one past tolerance and
rebaselined it in the same commit: `narrow.memoServed` **+3.16%** (44,354 → 45,757). That is the
round-459 retire paying its trade — an enum-member argument now goes through the TYPE path
(`typeOfExpr.calls` +0.72%, the relation, round 743's second-chance flow narrow) instead of an AST
key-set comparison, so narrowing REQUESTS rise while real `narrow.walks` move only **+0.37%**: 84%
of the extra requests are served free by the round-664 memo. **The retire also REMOVES work**:
`globals.lookups` **−1.64%** and `globals.misses` **−1.67%** (−18.9k), which is
`canonicalEnumSymbol`'s `globals[name]` consult inside `enumMemberKeysOfTypeNode` /
`enumMemberKeyOfExpr` disappearing. Net trade: ~18.9k globals lookups for ~5k expression typings.

**PART 3 — (c) IS BLOCKED, AND THE CENSUS SAYS SO IN NUMBERS.** Round 740's inventory lists the
three AST-only enum passes as "100% artifacts, deletable". **They are not, and none of them is
deletable today.** A PassLab ablation (`build/pass-lab.txt`, `disable <pass>`, zero recompile) run
first as a whole-suite census and then per-walker through the CLI:

| walker | test | with | without |
|---|---|---:|---:|
| `checkEnumToEnumAssignments` | `enumAssignmentCompat3` | 12 | **0** |
| `checkEnumLiteralAssignments` | `enumAssignmentCompat5` | 3 | **0** |
| `checkNamespaceEnumUnionAssignments` | `enumLiteralAssignableToEnumInsideUnion` | 5 | **3** |

Each is uniquely load-bearing for exactly one corpus test, and all three are the same missing rule:
**the relation is VALUE-BLIND**, which step (a) chose deliberately. `enumAssignmentCompat3`/`5` are
about VALUE equality across enums (`E.A = 0` vs `F.A = 0`, `Computed.A = 1`), and B266's surviving
two lines are its whole-enum targets. **So (c)'s first deletion is not any of the three passes — it
is a VALUE-AWARE disjointness rule**, and only after that does a pass become deletable. None of the
twelve AST key-space helpers is orphaned either (checked by reference count; retiring the round-459
gate left `enumMemberKeysOfTypeNode` and `enumMemberKeyOfExpr` with other consumers).
`discriminantPropAnnotation` has five call sites woven through switch-narrowing and type-guard
filtering — it is a multi-step replacement, not a one-commit deletion, and it is the riskiest part
of (c), not the first.

**METHOD NOTE, and it is the round's transferable lesson.** Both reductions succeeded on the first
try by building the shape from the REAL declarations in `build/bench/tsc-project-*/src/compiler/`
(copy the interfaces verbatim, then delete ingredients one at a time) and running them through a
**1.3-second scratch-project CLI loop** (`java -cp … MainKt --noEmit --listAll <dir>` on a
throwaway tsconfig) instead of the test suite. That loop, not instrumentation, is what made twelve
bisection variants affordable; no `Diagnostic`-constructor hook was needed this round. The
diagnostic-message trap is the same one round 742/743 recorded: **the message names the declared
type, and the elaboration names a constituent the relation never rejected** — probe the flow type
with a deliberate mis-assignment rather than reading the message.

**Round 743 (2026-07-28) — (REL.1) PART 1 LANDED: THE OVERLOAD PICKER WAS DOING TWO THINGS
WRONG, AND NEITHER OF THEM IS ABOUT ENUMS.** Corpus **12,956 / 0 failures / 8 skipped** (+7 pins),
compiler profile `--listAll` **BYTE-IDENTICAL at 46** after both commits. **(b) is parked again,
strictly further along, on `wip/round743-rel1b` (94c8014b) — 4 profile FPs down to 2.**

**BUG 1 — THE B136 CONCRETE-OVERLOAD SWAP RE-PICKED AN OVERLOAD THE SELECTION LOOP HAD ALREADY
REJECTED** (commit `214e8cf1`). `getReturnTypeOfCallExpression`, when the chosen signature's return
still carries an un-inferred type parameter, looks for a NON-GENERIC overload with a concrete return
— and took the FIRST such overload **without ever asking whether it accepts the arguments**. So
`factory.createToken(SyntaxKind.ReadonlyKeyword)` selected the correct generic
`<TKind extends ModifierSyntaxKind>` overload and was then swapped back to
`createToken(token: SyntaxKind.SuperKeyword): SuperExpression` purely because that one is
non-generic with a concrete return. The omission was invisible for the Array predicate pair the rule
was written for, where the non-generic overload accepts whatever the generic one does. The type-based
verdict is now extracted as `signatureAcceptsArgs`, so any site adopting a signature asks the SAME
question, and the swap candidate is gated on it; when NOTHING accepts the arguments — i.e.
`resolveCallOverload` itself fell through to its first-arity-match tail — the test has no signal and
the pre-743 behaviour stands, so the change is behaviour-preserving by construction outside the bug.

**AND THE REDUCTION IS WHY TWO EARLIER ATTEMPTS FAILED: IT NEEDS THE GENERIC OVERLOAD.** Round 742
wrote the repro as "two same-arity overloads whose parameters are DIFFERENT enum members pick the
first". That shape **selects correctly** — the round-459 AST key gate handles it, verified here as
its own probe. What reproduces is a non-generic enum-member overload FOLLOWED BY a generic one; the
generic overload's un-inferred return is what arms the swap. Both earlier reductions blamed the
barrel and the enum size and came back clean for want of that ingredient.

**BUG 2 — OVERLOAD SELECTION COULD NOT SEE AN `asserts` NARROW** (commit `190d34b7`).
`getTypeOfIdentifier` answers from `currentLocalTypes` and the declaration tables. An
`if (isFoo(x))` narrow is RECORDED there by the condition pass, so selection saw it all along; an
`asserts` narrow lives ONLY in the flow graph, reached by `getNarrowedTypeForReference`, which every
emission site opts into individually and `resolveCallOverload` never did. So
`Debug.assert(isKeywordOrPunctuation(kind)); … tokenToString(kind)` picked
`tokenToString(t: SyntaxKind): string | undefined` over the `PunctuationOrKeywordSyntaxKind` overload
returning `string` (parser.ts:2494). `signatureAcceptsArgs` now takes a SECOND CHANCE against the
flow-narrowed type when the un-narrowed one is rejected — deliberately second rather than primary, so
it can only turn a rejection into an acceptance and no overload that already matched can start
failing, and the flow walk is paid for only on the rejecting path.

**BOTH REDUCTIONS ARE ENUM-FREE, WHICH IS WHAT MAKES THE PINS BITE ON MAIN.** The profile diagnostics
are visible only with (b) applied — without member disjointness the wrong overload's parameter
accepted the argument anyway — but neither gap has anything to do with enums, and a pin written in
the enum shape would have been vacuous on main. `AssertNarrowedOverloadSelectionTest` is
`unknown` + `x is string` + a two-overload function; `EnumMemberLiteralOverloadSelectionTest`'s new
trio keeps the enum only because that IS the round-459 gate's subject, and states the mirror
direction (`takeSuper(createToken(SK.ReadonlyKeyword))` must ERROR) so a build that still picks the
first overload fails it.

**COST GATE.** Commit 1 +0.16% (rebaselined). Commit 2 **FAILED and was justified + rebaselined in
the same commit**: `narrow.memoServed` +9.93% and `typeNode.bypassed` +23.26%, while `narrow.walks`
moves only +0.83% — i.e. the second chance issues ~4,600 extra narrowing requests of which 87% the
round-664 flow-walk memo serves for free, and the +584 real walks resolve the assert predicates and
their targets (the 19,429 extra context-bypassed typeNode resolutions). It does NOT convert into wall
time: interleaved against the same build without the fix, 26,098 / 26,593 ms vs 26,312 / 26,394 ms,
inside the +-2.87% drift band.

**PART 2 — (b) IS PARKED AGAIN, AT 2 FPs INSTEAD OF 4** (`wip/round743-rel1b`, all five `@Ignore`s
ON). `checker.ts:7997` and `parser.ts:2494` are gone, fixed at the root on main. What remains, both
meaning-level so no logical-parity divergence applies:

- **`utilities.ts:4175`** — TS2322, intersection-over-union distribution:
  `(Expression & StringLiteral) | NoSubstitutionTemplateLiteral | NumericLiteral` against
  `MemberName | (Expression & (NumericLiteral | StringLiteralLike))`.
- **`declarations.ts:846`** — TS2322 `'T | undefined'` vs `'T | StringLiteral'`: the
  `if (!input) return undefined!` truthiness narrow does not strip `undefined` from a `T | undefined`
  where `T extends Node`.

**NEITHER REDUCED.** A hand-built model of each shape (the same interfaces, the same annotation, the
same narrow) passes on the (b) build. Per round 742's rule that is a missing ingredient, not an
absent bug — and it is exactly the state bug 1's reduction was in before the generic overload was
added, so the next round should look for what the model omits rather than re-deriving the diagnosis.

**THE ROUND-459 GATE'S PREMISE IS NOW FALSE, AND THE RELATION CAN ANSWER — BUT ONLY WITH (b).** The
gate's own comment says enum members "are not modeled as literal types"; since (a)/(b0) they are, and
for an ANNOTATED parameter the general type path in `signatureAcceptsArgs` reaches the same verdict
**provided the relation rejects a sibling member**, which is precisely what (b) adds. On main today
the gate is still load-bearing (sibling members relate vacuously) and it additionally answers for a
parameter whose annotation the type path resolves to `anyType`. So it is (c) material, not (c) work:
retire it in the same commit that lands (b), never before. Its doc now says so.

**ALSO FOUND, NOT FIXED — a second, independent overload-selection hole with the same witness.** When
BOTH candidate overloads are generic, round 481's lenience (`paramType is Type.TypeParam` matches any
argument) never checks the type parameter's CONSTRAINT, so
`createToken<TK extends SuperSK>(token: TK)` wins over
`createToken<TKind extends ModifierSK>(token: TKind)` for a `ModifierSK` argument. The probe
(`SuperExpr<SK.ReadonlyKeyword>` where `ModifierTok<SK.ReadonlyKeyword>` is correct) is recorded
here rather than pinned, because a constraint check is only SHARP once (b) makes an enum-member union
discriminate — before that it accepts everything and the change would be inert.

**Round 742 (2026-07-28) — (REL.1) STEP (b0) LANDED: AN ENUM-MEMBER *EXPRESSION* IS A MEMBER
TYPE, NOT `any` — AND THAT, NOT THE RELATION, WAS WHAT (b) WAS BLOCKED ON.** Corpus
**12,949 / 0 failures / 8 skipped** (+9 pins), compiler profile `--listAll` back to **46**
(byte-identical), cost gate **+0.35% max** on the first commit (rebaselined) and **+0.00%** on
the second. Two commits on main; **(b) is parked again, on `wip/round742-rel1b`.**

**WHAT (b0) IS, AND WHY IT EXISTED.** Step (a) minted the enum-member type, but an enum's own
`Type.Object` carries no member table — so `getPropertyOfType(enumType, "A")` missed and
`computeRawTypeOfPropertyAccess` fell ALL the way through to its `anyType` tail. Only
ANNOTATIONS ever reached `getDeclaredTypeOfEnumMember`. `enumMemberAccessType` now types the
ACCESS as the member, deliberately as a targeted branch rather than by planting members on the
enum's type: a member table would also make the enum a structurally NON-EMPTY relation TARGET,
so `x: E = <anything>` would start demanding those members. `widenType` widens a member back to
its enum, exactly as a string literal widens to `string`.

**THE PINS ARE STATED SO THEY FAIL ON `anyType`.** `take(E.A)` against a `string` parameter and
`take(Ext.Dts)` against a `number` parameter must ERROR — `any` relates to everything, so a pin
expecting silence measures nothing. Round 740's `take(Ext.Dts)` control was exactly that kind of
vacuous pin, and this is what fixes it.

**THE CLASSIFIER FAMILY AUDIT — the round-741 gotcha applied as an audit, not as firefighting.**
Six more sites moved onto `isEnumFlavoredObjectType` (the TS2339 empty-receiver skip, both
TS7053/TS7052 element-access skips, the B244 write path, the assignment-narrow gate), and
`isNumericEnumObjectType`/`isStringEnumObjectType` now answer PER MEMBER from the member's own
constant (sharper than the whole-enum answer, and required, because a member symbol carries
`EnumMember`, not `Enum`). **Two sites were WRONG in a direction a mechanical widening would have
missed:** `comparabilityCategory`'s blanket `EnumLiteral → number` would have called a STRING
member a number and FP'd a cross-category TS2365 against a string operand; and the discriminant
filter's "an OBJECT-typed discriminant can never strictly-equal a primitive literal" rule
silently stopped excluding enum-flavored objects the moment a discriminant was a member type
(the exclusion is load-bearing — a string-enum VALUE *can* equal a string at runtime).

**THREE PRE-EXISTING BUGS THE `any` HAD BEEN MASKING. All three fixed at the root; the round did
NOT re-mask any of them.** (b0) took the profile 46 → 53, and every one of the 7 was a gap that
had always been there and had always been hidden by an `any` operand short-circuit:

1. **`isComparableType` never resolved a TYPE PARAMETER's constraint** (6 FPs) — nodeFactory.ts
   `createToken`'s `token >= SyntaxKind.FirstToken` with `TKind extends SyntaxKind`. The sibling
   rule has been in `isValidArithmeticOperand` since it was written; only the relational path
   lacked it. 53 → 47.
2. **The arithmetic pass's first-wins local recording refused a genuine SHADOW** (1 FP) —
   generators.ts declares `let state: Identifier` in `transformGenerators` and the nested
   `endExceptionBlock()` declares its own `const state = exception.state`. The pass copies its
   locals map per scope frame and restores at the frame's leave, so the enclosing recording is
   still visible while the nested body is walked, and `declName !in currentLocalTypes` refused
   the shadow. `spineArithInheritedName` decides inheritance by INSTANCE IDENTITY against the
   innermost saved map — an entry made in this scope is either absent outside or a different
   instance — so first-wins still holds WITHIN a scope. 47 → **46**.
3. **`checkVarDeclAssignabilityCore` has its OWN inline literal-widener, separate from
   `widenType`** (23 FPs, found by putting (b) on top of (b0) and reading the fallout) — and it
   is what the TS2322 assignment check later reads as the target's declared type. So
   `let flags = TransformFlags.None; flags = TransformFlags.ContainsESNext` — the way tsc's own
   sources start every flags accumulator — inferred `flags` as the MEMBER type and made the
   second line an error (74 → 51). The same widening must also DISTRIBUTE over a UNION, because
   `let variance = mods & Out ? (mods & In ? VarianceFlags.Invariant : …) : …` infers a union of
   members (51 → 50). `widenEnumMemberTypes` does both and is a no-op on anything carrying no
   member type. **These landed on MAIN without the (b) rule**, because the INFERRED type was
   wrong either way — merely invisible while two sibling members related vacuously.

**DISPLAY.** Member types now print QUALIFIED (`SyntaxKind.Identifier`), as tsc does. The bare
member name is also the name of an unrelated interface, and (b0) puts these types into every
enum-member expression in the program, so the bare form would start naming the wrong thing.

**(b) IS PARKED AGAIN — `wip/round742-rel1b` (4252f2d2) — BUT IT IS STRICTLY FURTHER ALONG.**
On that branch **all FIVE `@Ignore`s flip ON**, including the fourth (`const e: E.X = E.Y`,
which needed (b0)) and the fifth, unblocked by DELETING the string TARGET half of step (a)'s
base-primitive leg — round 741 named that "the cheapest thing (b) can remove", and it is right:
tsc rejects `string → Ext.Dts` because a string enum is nominal, and unlike the numeric direction
there is no bit-flag compatibility rule to justify it.

**WHAT BLOCKS IT IS EXACTLY ROUND 741's FOUR FPs**, unchanged in identity: `checker.ts:7997`
(TS2769), `parser.ts:2494` (TS2345, assertion-narrow + `tokenToString` overload pick),
`declarations.ts:846` (TS2322 `T | undefined` vs `T | StringLiteral`), `utilities.ts:4175`
(TS2322, intersection-over-union). **That the count returns to FOUR after (b0) — a change that
puts member types into every enum-member expression in the program — is itself evidence for round
741's read that these are relation/narrowing gaps passing vacuously, not enum work.**

**DIAGNOSED, NOT FIXED, AND IT IS THE NEXT ROUND'S STARTING POINT: `checker.ts:7997` IS AN
OVERLOAD-PICK BUG, WITH THE ENUM ONLY AS WITNESS.** `factory.createToken(SyntaxKind.ReadonlyKeyword)`
selects the FIRST overload — `createToken(token: SyntaxKind.SuperKeyword)` — and returns
`SuperExpression`, which then fails `createIndexSignature`'s `ModifierLike[]` parameter. It has
ALWAYS picked that overload; while `kind: SyntaxKind.SuperKeyword` and
`kind: SyntaxKind.AbstractKeyword` were mutually assignable, the wrong return type related to the
right one anyway. Reduced to a standalone repro (no barrels, no big enum): **two same-arity
overloads whose parameters are DIFFERENT enum members pick the FIRST, while the same pair
distinguished by ARITY picks correctly** — so `resolveCallOverload`'s type-based loop is not
rejecting the mismatching member parameter, even though both its round-459 AST key-space gate and
the (b) relation rule should. That loop is where to look, and note that the round-459 gate
(`enumMemberKeysOfTypeNode`/`enumMemberKeyOfExpr`) exists verbatim because "enum members are not
modeled as literal types" — which is no longer true, so it is also (c) deletion material.

**METHOD NOTE.** The two widening bugs were found by LANDING (b) on top of (b0) and reading its
fallout, then splitting the result: the fallout that was really (b0) incompleteness went to main,
the rest stayed parked. A round that had only measured (b0) in isolation would have shipped both
latent, because sibling members related vacuously and no diagnostic could see the wrong type.

**Round 741 (2026-07-28) — (REL.1) STEP (a) LANDED: THE ENUM-MEMBER TYPE NOW EXISTS, AND IT
CHANGED NO ANSWER.** Corpus **12,940 / 0 failures / 8 skipped** (+5 pins, +1 `@Ignore`),
compiler profile `--listAll` **byte-identical at 46 errors** — the whole output, not just the
count — and the cost gate green at **max +0.40%** (rebaselined in this commit, see below).
`TypeFlags.EnumLiteral` has a WRITER for the first time, so the widening rule
`if (sf.hasAny(EnumLiteral) && tf.hasAny(Enum)) return true` (Checker.kt ~143100), written
long ago and never once executed, now fires.

**WHAT (a) IS.** `getDeclaredTypeOfSymbolWorker` gained an `EnumMember` branch
([getDeclaredTypeOfEnumMember]) minting a distinct `Type.Object(Object or EnumLiteral)`,
INTERNED on `"<canonicalEnumSymbol.id>#<memberName>"`; the enum's own type is now
`Type.Object(Object or Enum)`. The interning key is why `canonicalEnumSymbol` survives
(REL.1) — `declaredTypes[symbol.id]` memoizes per SYMBOL INSTANCE, and the same enum arrives
as the merged global, a file-local and a barrel-resolved alias, so a per-symbol mint hands two
non-equal types to one member. That is the catastrophe `canonicalEnumSymbol`'s own doc records
for the discriminant key space, and it would have been re-created here.

**BOTH PRIMITIVE LEGS WERE NEEDED, IN BOTH DIRECTIONS, AND THE MECHANISM IS ONE LINE.** The
two pre-existing enum↔primitive rules (`isNumericEnumObjectType` / `isStringEnumObjectType`,
Checker.kt ~143118/143124) classify by `type.symbol.flags.hasAny(SymbolFlags.Enum)` — and a
member type's symbol carries `SymbolFlags.EnumMember`, so **neither can fire for a member type
in either direction**. Measured, not assumed — an ABLATION build (mint in, leg block deleted,
compiled and run) fails **all four** leg pins: numeric member → `number`, `number` → numeric
member (both shapes), and string member → `string`. Only the string TARGET direction
(`string` → a string member) is unverified, because tsc REJECTS it and its pin is therefore
`@Ignore`d expecting the error; it is in only for behaviour-preservation symmetry with the
former `anyType`, and **it is the first thing (b) should try deleting**. The same ablation
exposed that one of round 740's own four controls — `take(Ext.Dts)` — is VACUOUS with respect
to this change: an enum-member EXPRESSION types as the enum, so it rides the pre-existing
`isStringEnumObjectType` rule and never touches a member type. That is why the four new pins
annotate through `declare const d: Ext.Dts` instead. The legs are
deliberately **VALUE-BLIND** at step (a): `let a: E.A = 2` where `A === 0` is a real error but
it is `checkEnumLiteralAssignments`' error today, and the relation co-emitting it is exactly
the 4 spurious TS2322 round 740's probe measured on `enumAssignmentCompat5`. Tightening to
tsc's value-equality rule belongs with (b)/(c), which retire that walker.

**THE ONE KNOCK-ON, AND IT IS A GENERAL LESSON, NOT A ONE-OFF.** Round 740 predicted "knock-ons
where a union no longer collapses now that its members are distinct" and named
`Partial<CreateSourceFileOptions> | ESNext | CommonJS`. It reproduced EXACTLY, as a single new
TS2339 at program.ts:1341, and the cause is not union collapse at all: **`typeof x ===
"object"` classified the member as an OBJECT.** Its verdict function asks
`symbol.flags.hasAny(SymbolFlags.Enum)` — true for an enum, false for its member — so
`ModuleKind.ESNext` survived a narrow it should never survive. Fixed with one shared predicate,
`isEnumFlavoredObjectType`, applied at BOTH sites of that family (`typeofTagOfType` :89097 and
the `typeof === "object"` union filter :110277). **Any classifier whose rule is "an enum value
is a number/string at runtime, never an object" must accept `Enum or EnumMember`** — before
this round it could not tell, because members were `anyType`. Recorded as a CLAUDE.md gotcha.

**BOTH LATENT HAZARDS CLOSED.** Round 740 flagged that `getTypeFromTypeReference` looks `SK.A`
up under the BARE last segment `"A"`. (1) `currentTypeParamScope` / `currentTypeAliasArgs` were
consulted with that bare name, so an in-scope type parameter named `A` captured the enum
member — both consults are now gated on `node.typeName is Identifier` (a qualified name never
denotes a type parameter). (2) The round-444 `?: globals[name]` last-segment recovery bound
`E.Member` to an unrelated global type named `Member` on a resolution failure — now skipped
when the LEFT segment is an enum (`typeRefQualifiedLeftIsEnum`), taking errorType over a
foreign type of the same simple name. Neither moved the corpus or the profile.

**WHAT (a) DELIBERATELY DID NOT DO.** No disjointness rule: two members of the same enum still
relate to each other structurally (both are member-less `Type.Object`s, so it is vacuous), and
the four `@Ignore`d expectations in `EnumMemberRelationTest` are still `@Ignore`d — they are
(b)'s acceptance criterion, not (a)'s. No scaffolding deleted: that is (c).

**(b) WAS ATTEMPTED IN THE SAME SESSION AND IS PARKED, NOT LANDED — branch
`wip/round741-rel1b-disjointness` (045d0be5).** The rule is one line in
`checkTypeRelatedToCore` ("two enum-member types relate only when they are the same member")
and it WORKS: **three of the four `@Ignore`s flip ON** — sibling member, and BOTH directions of
the sibling-node-interface case. Two things stop it landing, and both are useful to the next
round:

**(i) 4 new FPs on the compiler profile (46 → 50), one family.** `checker.ts:7997` TS2769 "No
overload matches this call"; `parser.ts:2494` TS2345 `'string | undefined'` vs `'string |
number'` (a `Debug.assert(isKeywordOrPunctuation(kind))` assertion-narrow feeding a
`tokenToString` overload pick); `declarations.ts:846` TS2322 `'T | undefined'` vs `'T |
StringLiteral'`; `utilities.ts:4175` TS2322 against `MemberName | (Expression & (NumericLiteral
| StringLiteralLike))` — an intersection-over-union distribution. **The family is:** an
enum-member union that used to COLLAPSE to a single `anyType` member now has real constituents,
and a relation/narrowing path that was passing VACUOUSLY stops passing. These are meaning-level
FPs, not form, so no `LogicalParityDivergence` applies — they are work.

**(ii) The 4th pin does not flip at all, and it measures something else than the other three.**
`enum E { X, Y }; const e: E.X = E.Y` stays silent because an enum-member ACCESS EXPRESSION
types as the **enum**, not as the member — only ANNOTATIONS reach
`getDeclaredTypeOfEnumMember`. Typing the expression as its member type is a separate change
with its own blast radius (it is also what would make `take(Ext.Dts)` finally exercise the
member path), and it should be its own sub-step.

**AND A MEASURED NEGATIVE RESULT THAT IS WORTH MORE THAN THE RULE.** The obvious explanation
for (i) — that ONE member splits into several `Type` instances, because `canonicalEnumSymbol`
canonicalizes through `globals[name]` and INV.3(d) retired the merge for module-only names, so
every enum in tsc's OWN sources (`SyntaxKind` included) has no global to canonicalize to — is
**FALSE**. Replacing the identity verdict with tsc's structural one
(`enumMemberTypesAreSameMember`: same member name + an owning enum that is the same symbol,
canonicalizes to the same symbol, or shares an `EnumDeclaration` node) leaves the profile at the
**same 4 FPs**. So the split is either not happening or not what these FPs are about; the next
round should not spend a session on it. The structural comparison is kept on the branch anyway
— it is strictly more correct and free.

**COST-GATE REBASELINE, justified.** Every counter moved by < 0.5% (largest `narrow.walks`
+0.40%, `typeOfExpr.calls` +0.18%, `typeNode.bypassed` +0.12%); `spine.nodes`,
`globals.conflated` and the error/file counts are unchanged. The cause is exactly the mint:
unions that used to collapse to one `anyType` member now carry their real constituents, so
narrowing walks and expression typings see more of them. This is the accounting the gate asks
for, not a regression — it buys a type where there was none.

**NEW PINS (5, all in `EnumMemberRelationTest`).** Four state the legs directly — numeric
member → `number`, `number` → numeric member, string member → `string`, and (`@Ignore`d,
honest about the divergence) `string` → string member, which tsc REJECTS because string enums
are nominal while step (a)'s both-ways leg accepts. The fifth pins the `typeof === "object"`
knock-on in a dependency-free form. A pin that passes before AND after measures nothing, so
each was checked against the ablation build: all four non-ignored leg pins fail there. The
knock-on pin is a reduction of the profile diagnostic itself — program.ts:1341 was present on
the build that had the mint but not `isEnumFlavoredObjectType`, and absent after — so it is
evidenced by the profile rather than by a second ablation.

**Round 740 (2026-07-28) — PART 1, (PERF.HW): THE CORES ARE REAL. THE QUESTION WAS WRONG.
A "SINGLE-THREADED" xtsc RUN ALREADY CONSUMES 3.15 OF THE 4 CORES.** The item asked whether
this VPS has real cores so that M2 is worth reviving, with unpark condition ">= 8 real cores".
Both halves of that framing are now measured, and the second one is answered by a number
nobody in this arc had taken: **85.6 seconds of USER CPU for a 27.1 second wall.** 79% of the
machine is spoken for before the first worker is created.

**THE BOX, MEASURED NOT ASSUMED.** `nproc` 4; AMD EPYC-Rome @ 2445 MHz; **4 distinct `core
id`s each with ONE thread sibling — no SMT**; **no cgroup `cpu.max`**; **steal 0.0 mean, 0 max
across 72 `vmstat 5` samples** spanning the entire probe. Steal alone does NOT settle "are the
cores real" — a hypervisor quota can be enforced without steal accounting — so they were tested
directly with a tiny-working-set pure-CPU loop (no allocation, no JIT): **1.00x / 1.56x /
3.45x / 3.61x at 1/2/4/8-way concurrency.** Four real, independent, unthrottled cores. (The
2-way point is low only because a ~2 s job is dominated by process start.)

**THE TABLE** (compiler profile, `--noEmit`, `-Xmx4g`, one cold JVM per run, 3 reps,
**round-robin INTERLEAVED across levels** — round 666 ran the levels in blocks, which lets
drift land on one level):

| level | self ms (r1 / r2 / r3) | median | per-rep median delta | wins vs w1 | user CPU | **cores** | peak RSS |
|---|---|---:|---:|:---:|---:|---:|---:|
| **w1** | 27,126 / 26,356 / 27,904 | **27,126** | — | — | 85.6 s | **3.15** | 822 MB |
| **w2** | 23,961 / 24,458 / 24,452 | **24,452** | **-11.67%** | **3/3** | 85.3 s | 3.49 | 1,555 MB |
| **w4** | 25,976 / 26,452 / 25,838 | **25,976** | -4.24% | 2/3 | 92.6 s | 3.57 | 1,705 MB |
| **w8** | 32,184 / 32,212 / 33,310 | **32,212** | **+19.37%** | **0/3** | 117.7 s | 3.65 | 2,240 MB |

**Drift band re-derived rather than reused: +-2.87%** (w1's own three reps around their median).
**Read the win rates, not the medians** — w2's per-rep deltas (-11.67 / -7.20 / -12.37%) never
change sign; **w4's STRADDLE ZERO** (-4.24 / +0.36 / -7.40%) so w4 decides nothing except that
it is not better than w2; w8 is decisive at 0/3 with a tight +18.7..+22.2% range. This
reproduces round 666 (seq 27,873 / w2 24,669 = -11.5% / w4 27,905 = flat) on a now-check-only
compile, and adds the w8 point the item asked for.

**THE EXPLANATION.** The compile thread can be at most 1.00 of those 3.15 cores. Attributed by
starving each subsystem in turn: `-XX:CICompilerCount=2` -> **2.34 cores / 62.8 s user (JIT
~21.7 s of CPU)**; `-XX:ParallelGCThreads=1 -XX:ConcGCThreads=1` -> 3.06 cores (**GC only
~2.7 s**). It is JIT, not GC: C2 compiling a ~110k-line `Checker.kt` never finishes inside a
27 s run. **Self time is FLAT across all four configurations (26,641 / 26,824 / 26,703 /
27,780 ms)** — so the JIT threads are NOT stealing from the compile thread (round 618's "JVM
flag hunting is DEAD" stands); they are consuming the cores a WORKER would need. **Headroom for
parallelism on this box is ~0.85 cores, not 3.**

**THE WHOLE CURVE FOLLOWS FROM THAT.** Every level saturates at the same ~3.6-core ceiling
(3.49 / 3.57 / 3.65). What changes with worker count is not parallelism obtained but TOTAL WORK
done, because each worker re-binds every file and runs all ~318 program-wide collectors:
**user CPU w1 85.6 -> w2 85.3 (+0%) -> w4 92.6 (+8%) -> w8 117.7 (+37%).** w2 divides work
without adding any (the duplication is absorbed by the free ~0.85 cores) and banks -11.7%;
w4 and w8 add 8% and 37% more CPU to a machine that has none left, and it converts directly
into wall time.

**AND IT IS NOT A JIT ARTIFACT — TESTED, NEGATIVE.** If w4's flatness were JIT threads crowding
out workers, freeing them would fix it. w2: 24,305 -> 24,323 (unchanged). w4: 25,870 (3.62
cores) -> 25,574 (**3.28 cores**). ~0.34 cores demonstrably released and the wall did not move.
**The w4 ceiling is the parallel design's own duplicated work, not core starvation.**

**THE FOUR-CONCURRENT-PROCESS TEST, CORRECTLY INTERPRETED.** Four INDEPENDENT solo compiles run
at once took 103-105 s each (3.85x solo), aggregate throughput **1.03x** — which looks
catastrophic until 3.15 is applied: 4 x 85.6 = 342 core-seconds on 4 cores has an **86 s
floor**, and 105 s against that floor is **82% parallel efficiency**. **The box parallelises
fine. Our compile does not, because one copy of it already occupies 79% of the machine.**

**AMDAHL, AND WHERE THE MODEL BREAKS.** `seq = R + P`, `wN = R + P/N`: the w1/w2 fit gives
**P = 5,348 ms divisible (19.7%)**, R = 21,778 ms — an infinite-worker floor of **-19.7%
(1.25x), ever**. The w1/w4 fit gives P = 1,533 ms (5.7%). **The two disagree by 3.5x**, which
per the rule fixed BEFORE the run means the model is contention-broken beyond w2 and only the
w1/w2 fit is meaningful. Round 666 fitted 23%, this round 19.7%: **not resolvable at this
precision** (P is twice a delta whose own per-rep spread is 1,554 ms, so P carries +-5.7
points). Stated so nobody reads a trend into it — and the prior written down before the run,
that removing emit from R should RAISE the divisible share, is therefore **untested, not
falsified**.

**ALSO FOUND, AND QUEUED AS (PERF.HW.a): `--workers N` IS NOT BEHAVIOUR-PRESERVING.** Sequential
emits **46** diagnostics; **every** parallel level emits **62**. The 16 extras are one family in
one file — `src/compiler/utilities.ts:11349..11410`, TS2322 *"Type `EvaluatorResult<number>` is
not assignable to type `EvaluatorResult`"* and its `<string>` instantiations. **Identical at
w2, w4 AND w8** (not count-dependent) and **deterministic across reps** (not a race): the
round-609 signature, a program-wide COLLECTOR iterating the INV.6 partition view instead of
`binderResults`. So the table above compares a correct sequential run against a diverging
parallel one; the 16 extra emissions are negligible in cost so the timings stand, but **no
`--workers` wall number can be a claim until this closes.**

**VERDICT.** Cores real; only four; 3.15 already spoken for. **M2 stays parked and its unpark
condition is REWRITTEN in place**: ">= 8 real cores" is necessary but INSUFFICIENT — the
measured requirement is **>= 8 cores net of the ~3.2 the JVM's own JIT/GC consume, i.e.
realistically >= 12** — and unlike the workers that tax is FIXED per JVM, so a bigger host
simply out-sizes it. **Is shrinking the 77% duplicated term worth attempting? Not now**, for
three reasons in increasing order of difficulty: the ceiling is 1.25x even if the duplication
vanished entirely; there is no machine here to spend it on; and the mode is incorrect.

**PREDICTIONS SCORED, 5 of 6** (all stated in full before any measurement): P1 drift <= +-3%
**HELD** (+-2.87%); P2 w2 an 8-15% win at >= 2/3 **HELD** (-11.7%, 3/3); P3 w4 flat and not
better than w2 **HELD** marginally; P4 w8 worse by >= 5% **HELD** (+19.4%, 0/3); **P5
FALSIFIED** — w8 fits `-Xmx4g` comfortably (peak RSS 2,240 MB; GC 1.1 s of the 5.4 s
regression, ~20% of it), so **no level was skipped for want of RAM**; P6 verdict **HELD** but
for an unpredicted reason. The miss and the surprise point the same way: **memory was never the
constraint, and CPU was constrained by something nobody had measured rather than by the core
count everybody had been arguing about.**

**NO `src/` CHANGE** — stated explicitly rather than skipped silently, so no suite run; 12,927
stands. Full derivation: **`docs/perf/worker-scaling-round740.md`**.

**Round 740 — PART 2, (REL.1) DECOMPOSED AND SIZED (fix deliberately NOT attempted): THE
BLAST RADIUS EVERYONE FEARED IS **ONE** CORPUS BASELINE, AND THE ROOT CAUSE IS NOT "the
relation is lenient" — IT IS `anyType`.** The item said "blast radius is the reason it is a
separate item: every enum-typed comparison in the corpus goes through this, TS2322/TS2367/
TS2345 baselines included". Measured: **12,927 tests, 1 failure.**

**THE DEFECT REPRODUCES, and worse than written.** `declare enum SK { A, B }` with sibling
interfaces differing ONLY in `readonly kind: SK.A` vs `SK.B` compiles to **zero errors in BOTH
directions** — mutually assignable, exactly as the item claims. Same for a plain `enum`, a
`const enum`, and the bare `const k: SK.A = SK.B`.

**ROOT CAUSE, LOCATED.** `getTypeFromTypeReference` (Checker.kt:102093) reduces `SK.A` to the
BARE member name `"A"`, resolves it via `resolveQualifiedName` to the enum's `exports["A"]`
(a `SymbolFlags.EnumMember` symbol), and `getDeclaredTypeOfSymbolWorker` (:102387) **has no
branch for that flag** — it falls to `else -> anyType` (:102509), cached in
`declaredTypes[symbol.id]`. So `SK.A` and `SK.B` are *the same `Type` instance*, and the
relation was never asked a question it could get wrong. **Corollary worth its own line:
`TypeFlags.EnumLiteral` (Type.kt:55) is SET NOWHERE — all ~11 read sites are dead code,
including the widening rule `if (sf.hasAny(EnumLiteral) && tf.hasAny(Enum)) return true` at
:143100, which is already written and waiting for a flag that never arrives.**

**THE MEASUREMENT — a throwaway 3-edit probe, built, measured on two axes, and REVERTED**
(`git diff src/commonMain` empty; profile `--listAll` byte-identical to the pre-probe run at
46 errors; cost gate all 20 counters +0.00% — that triple is the revert's proof, not a
formality). The probe: an `EnumMember` branch minting a distinct `Type.Object(Object or
EnumLiteral)` per member symbol (already interned by `declaredTypes[symbol.id]`, so no new
cache); the enum's own type flagged `Object or Enum` so the dead rule at :143100 fires; and an
enum-literal disjointness rule at the top of `checkTypeRelatedToCore`. It WORKS — the repro
goes to 2 x TS2322 with a correct elaboration chain (`Types of property 'kind' are
incompatible. Type 'A' is not assignable to type 'B'.`) while both negative controls stay
silent.

| axis | before | with probe | delta |
|---|---:|---:|---|
| corpus suite | 12,927 / 0 | 12,927 / **1** | `enumAssignmentCompat5` |
| compiler profile `--listAll` | 46 | 52 | +6 |
| profile self time | 26.5-27.1 s band | 26,192 ms | no measurable cost |

**AND THE SINGLE FAILURE IS THE MISSING LEG, NOT THE ADDED ONE** — which is why this sizes as
a session. It is 4 spurious `TS2322: Type 'number' is not assignable to type 'A'`: a numeric
enum member type must stay assignable FROM `number`, and from a numeric literal equal to the
member's value (`let a: E.A = 0` legal because `A === 0`; `a = 2` not; `Computed.A = 1` not,
because a computed member has no literal — all three already pinned in that one baseline).
The profile's +6 is the same family: `Extension.Dts` (a STRING enum member) not assignable to
`string`, plus knock-ons where a union no longer collapses now that its members are distinct
(`Partial<CreateSourceFileOptions> | ESNext | CommonJS`). **The entire measured gap is ONE
rule family: enum member <-> its base primitive.**

**DECOMPOSITION (three landable sub-steps), WHICH CONSUMERS DIE, AND WHICH ONE SURVIVES** —
written into the queue item rather than repeated here. The headline: **`discriminantPropAnnotation`,
`kindDomainProvesNotSubtype`, `kindDomainKeysExceed` and THREE whole AST-only passes
(`checkEnumLiteralAssignments`, `checkNamespaceEnumUnionAssignments`, `checkEnumToEnumAssignments`)
are 100% artifacts and go**; twelve more AST-side key-space helpers go with their consumers;
but **`canonicalEnumSymbol` SURVIVES in modified form** — the duplicate-`Symbol`-instance
problem it solves is independent of the relation, and a naive per-symbol mint would produce two
non-equal types for the same member and reproduce the catastrophe its own doc records at
:109775. Step (a) must intern on the canonical symbol, or compare structurally as tsc's
`isEnumTypeRelatedTo` does. Also recorded: **two latent hazards that go LIVE the moment the
member type is distinct** — `SK.A` is looked up in `currentTypeParamScope`/`currentTypeAliasArgs`
under the BARE name `"A"` (:102125/:102129), and falls back to `globals["A"]` (:102132), so an
unrelated type named `A` captures it. Invisible today because everything collapses to `any`.

**LANDED: `src/commonTest/kotlin/EnumMemberRelationTest.kt`** — four `@Ignore`d currently-failing
expectations naming the item (so the gap stays VISIBLE in the skipped count rather than
vanishing) plus **four NOT-ignored positive controls** which are precisely the shapes the probe
over-rejected, i.e. the FP firewall step (a) has to satisfy. Suite **12,927 -> 12,935 / 0
failures / 7 skipped** (+8 tests, +4 ignored).

**Round 739 (2026-07-28) — PART 1, (BENCH.1): THE YARDSTICK WAS BROKEN IN A DIFFERENT PLACE
THAN ROUND 738 THOUGHT, AND ROUND 738's CORRECTION IS RETRACTED.** The item was queued on
round 738's inference that "every published xtsc-vs-tsc `--no-emit` ratio compared our
check+emit against tsc's check-only, so the honest gap is ~2.15x, not 2.4x". **I read the
scripts instead of assuming, and the premise is false.**

**WHAT EACH SIDE ACTUALLY RUNS** (verified in-file, not inferred):

| script | xtsc | tsc / tsgo | decides |
|---|---|---|---|
| `bench-3way.sh` (CI -> `bench-history/`) | `MainKt <proj>` — **emits** | `-p tsconfig --outDir tmp` — **emits** | the published ratio |
| `ab-interleaved.sh` | `MainKt --noEmit` | — | every perf A/B of this arc |
| `cost_gate.py` | `MainKt --noEmit --passTiming` | — | the 20 cost counters |
| `bench-compile-tsc.sh` | either (`--no-emit`) | — | `bench/*.tsv` |

**The 2.4x is an EMIT-mode ratio with emit on BOTH sides.** `skipEmitOutputs` is set only from
`ProjectCompiler`'s `noEmit` parameter (`ProjectCompiler.kt:144`), so the CI numerator is
byte-for-byte the same work it was before round 738 and the ratio cannot have moved.
**Round 738's ~2.15x is retracted in place in six files.** Its arithmetic multiplied the ratio
by our own emit fraction `(1 - s_xtsc)` while implicitly taking tsc's `s_tsc` as ZERO — that
is the ratio's FLOOR, not its value, and the error ran in the direction that flattered us.

**AND IT IS NOW CONFIRMED EMPIRICALLY, NOT ONLY BY READING.** While this round ran, CI posted a
3-way row for `3570483cf3da` — round 738's OWN HEAD, the commit that landed `skipEmitOutputs` —
at **30.82s / 12.69s = 2.43x**, against **2.44x** for `728faeed137b` immediately before it. The
gate does not move the published column, exactly as the code path predicts. A prediction stated
before the data arrived and scored after it.

**THE REAL MISMATCH, WHICH WAS STILL OPEN: two different compiles.** ARCHITECTURE-RETHINK
§ 0.1's budget ("take the whole compile as 100 units") is a `--noEmit` compile; the 2.4x it is
compared against is emit-on-both-sides. Before round 738 these were nearly the same number
(our `--noEmit` emitted anyway, it just did not WRITE), which is exactly why nobody noticed;
after 738 they differ. **Measured, same binary, 4 interleaved pairs, compiler profile:
check-only 26,896 ms vs emit 29,194 ms self — the emit work is 2,298 ms = 8.5% of a check-only
compile / 7.9% of an emit-inclusive one, B slower 4/4, per-pair +1,959..+2,422 ms.**

**AND THE COLUMN THAT MATTERS WAS NEVER MEASURED AT ALL:** the bench has never run tsc or tsgo
with `--noEmit`, so the mode this entire perf arc profiles has no reference number. It is
bounded, not free: `R_ck = R_emit x (1 - 0.079) / (1 - s_tsc)`, so it EQUALS `R_emit` when
tsc's emit share equals ours, bottoms at `0.921 x R_emit` only if tsc's emit were free, and
**exceeds `R_emit` as soon as tsc's emit costs more than 7.9% of its run** — the likely case,
since our checker is the slow part and our emitter is not. With `R_emit` = 2.40x (median of the
last 30 CI runs) the check-only ratio is **>= 2.21x and probably >= 2.4x**.

**THE HONEST PUBLISHED RATIO, with its basis: 2.28x median over ALL 341 CI runs, 2.40x over the
last 30, EMIT mode, wall clock.** Per-row spread **1.87x-2.72x** on a compiler whose real change
over that span was far smaller — because xtsc is ONE cold JVM run per row against tsc's median
of three. **No single row is the ratio**; that alone is +-18%, wider than every landed win of
this arc combined.

**A SECOND, INDEPENDENT BUG IN THE SAME YARDSTICK.** `bench-3way.sh` parsed LOC with
`grep -oE '[0-9]+ LOC' | tail -1`, which also matches the `throughput: N LOC/s` line and takes
it — so **every one of the 340 archived run reports published THROUGHPUT as its LOC count**
("78 files, 7,004 LOC" for a 194,702-LOC program) and then divided that by the wall time for
its LOC/s column. Wall times, error counts and ratios are unaffected. Fixed.

**LANDED (no `src/` change, so no suite run — stated rather than skipped silently).**
(1) `bench-3way.sh` now measures **both modes on all three compilers** (`--modes`, default
both; tsc/tsgo get `--noEmit` for check-only), fixes the LOC parse, and fixes the wall parse to
read the summary median rather than run 1. (2) `bench-history/README.md` restructured: a
marked (`<!-- BENCH-ROWS-START/END -->`) two-mode table above a labelled **archive** of the 341
pre-739 emit-only rows, whose note records both caveats. Verified end-to-end twice with stub
reference binaries, including marker preservation across runs. (3) All **8 profiles
re-baselined check-only** (compiler 26,518 / tsc-cli 26,426 / jsTyping 28,434 / deprecatedCompat
26,607 / typingsInstallerCore 26,400 / services 34,813 / server 36,395 / harness 37,129 ms
self), each TSV carrying a **MODE DISCONTINUITY** block at the boundary — because `emitted=0`
alone does NOT distinguish "did not emit" from "emitted and threw it away", only the date does.
(4) `~2.15x` retracted in ARCHITECTURE-RETHINK (§ 0 header + § 0.1 + a new **§ 0.2** carrying
the table, the 8.5% measurement and the bound), `front-end-attribution.md`, CLAUDE.md,
STATUS.md and the round-738 note above.

**WHAT DID NOT WORK / WAS NOT POSSIBLE.** The queue item said "re-run the 3-way on all 8
profiles". **The 3-way cannot run here at all** — there is no `node`, no `tsc` and no `tsgo` on
this box, and no network; the reference binaries are npm-installed inside the CI job. So the
corrected check-only ratio is a BOUND, not a measurement, until the next CI run fills the new
column. Mixing our local wall against a CI tsc number would have been exactly the class of
error this round exists to fix, so it was not done.

**ALSO FOUND: `bench/` is gitignored** (`.gitignore:62`), so the "dashboard" the mission
statement points at is a LOCAL file — the re-baselined rows above do not travel to the next
agent. The shared record is `bench-history/` (CI-written), STATUS.md and these notes. Recorded
as a CLAUDE.md gotcha.

**ROUND 738's OWN HEADLINE, HONESTLY.** Three estimates of the same quantity now exist: the
phase measurement 2,623 ms (8.4% of the then-31,235 ms compile), this round's same-binary mode
delta 2,298 ms (8.5% of a check-only compile), and 738's cross-binary A/B **-3,570 ms
(-11.42%)** — which is ~1.3 s LARGER than a mode delta that additionally includes writing 78
files. Two of three cluster at 8-9%. **State the landed value of (FRONT.1) as ~8-9%, with
11.42% the high end**; the A/B is not wrong, it measured a different pair on a slower box.

**Round 739 — PART 2, (ENGINE.1): THE 14x DOES NOT SURVIVE CONTACT WITH ITS OWN SITE, AND THAT
NEEDED NO NEW MEASUREMENT.** The item said "measure two more sites before believing the 14x".
Before spending a round on instrumentation I checked the ratio against the data it came from,
and **the denominator is the wrong quantity**: 265/19 compares the FP-firewall walkers against
**the final relation call alone**, and that call is **2.2% of the function it lives in**. A
general rule engine does not consist of the relation call — it must still resolve the target
type node, compute the source type, infer the type of an unannotated initializer, and narrow.
All of that is in the same partition, in the same function, already measured by round 738.

**Re-classifying round 738's own level-B rows by "would a general rule engine also do this"**
(`checkVarDeclAssignability`, 15,116 invocations, 872 ms): **engine work 483 ms (55.4%)** —
unannotated-init inference 405 + SOURCE type computation 57 + canUseTypeEngine/RELATION 19 +
target getTypeFromTypeNode 1 + narrowing 1; **dedicated-walker layer 326 ms (37.4%)** — the
four prologue groups 265 + clodule/B96/B231 6 + foreign-TP/B112/B207 4 + the ~30 post-relation
walkers 51; bookkeeping 54 ms. **So the layer is 0.67x the engine work, not 14x it.**

**THE NUMBER A SCOPE DECISION TURNS ON is not a ratio: it is 326 ms of a 26,896 ms check-only
compile = 1.21%** — and **deletable is less than that**. The weak-type rule alone is 165 ms,
HALF the layer, and it is real TypeScript semantics that tsc implements INSIDE
`checkTypeRelatedTo`; we hold it in dedicated walkers only because a weak target passes our
relation vacuously. It MOVES into a general engine, it does not vanish. Honest range for this
site: **0.6-1.2% of the compile.**

**METHOD CORRECTION THE NEXT ROUND MUST ADOPT, or its two sites will not be comparable:**
report the layer in ms and as a share of the COMPILE, never as a ratio against the relation
call; and split it into "re-implements a rule tsc also has" (moves) vs "corrects our own
relation" (deletes). **And a grep census will not work**: `checkReturnAssignability` (802
lines) has **ZERO `tryEmit*` calls** — its firewall is inline `if (...) return` guards
(`aliasUnionContainsNullishKeyword`, `returnUnionSyntacticallyContainsLiteral`, the
QualifiedName-suggestion guard, `arrayLiteralSatisfiesTupleTarget`) — while
`checkAssignmentExpression` (1,427 lines) has 11 and `checkVarDeclAssignabilityCore` (1,504)
has 15. A naming-convention census would have scored site 2 at zero. Static census for scale:
805 `check*` + 181 `emit*` + 60 `tryEmit*` = 1,046 functions in 171,934 lines, which confirms
section 0.1's "~1,005".

**NOT STARTED, DELIBERATELY: the sites-2-and-3 instrumentation.** Each of rounds 733-738 spent
a full session partitioning ONE function, and these two are 802 and 1,427 lines; starting one
thin at the end of this round would have produced a number nobody could trust. Predictions
E1-E4 for those sites are written down in `docs/perf/engine-rule-price.md` § 4 so the next
round scores them instead of re-deriving them. **The scope question is NOT ready for the owner
yet — but the number it would have been put with (14x) is now known to be the wrong statistic,
and the one measured site says single-digit tenths of a percent.**

**Round 738 (2026-07-28) — PART 2, (FRONT.1): THE FIRST FRONT-END ATTRIBUTION, AND THE
ARC'S LARGEST LANDED WIN: `-11.42%` median, B wins 6/6.** Section 0.1's stage 5 said "the
front end, ~20%, unprofiled". **The front end is 11.0%. The OTHER 9.2% of that never-measured
region was `Transformer.transform` + `Emitter.emit` producing JavaScript that a `--noEmit`
build immediately threw away** — `noEmit` was consulted ONLY where outputs are WRITTEN
(`ProjectCompiler.build`), so the compile core transformed and emitted all 78 program files
regardless: **2,623 ms of a 31,235 ms compile (8.4%)**, `Transformer` 2,211 + `Emitter` 412.

**THE MAP (compiler profile, before the fix; 31,174 of 31,235 ms accounted for):** config
102 ms (0.3%), **import-graph crawl WALL 1,683 ms (5.4%)**, **core parse loop 0 ms — 78 of 78
pre-parses REUSED**, `extractRelativeImports` 17 ms (0.05%), **bind 1,622 ms (5.2%)**, checker
24,872 ms (79.7%), post-checker 2,876 ms (9.2%). **Reading, decoding and PARSING 9,977,097
characters costs 1,683 ms of wall in total** — the crawl already overlaps it 16-in-flight and
the core re-parses nothing. **There is no 20% in the front end and no lever in it**: the
largest row after the crawl is bind, at 5.2%, in-band.

**THE FIX AND ITS GATE.** Verified first that the Phase-3 loop contributes NO diagnostics
(no `diagnostics.add`/`remove` anywhere between the checker and the `CompilationResult`), so
skipping it is diagnostic-neutral by construction. The gate is a NEW
`CompilerOptions.skipEmitOutputs` set ONLY by `ProjectCompiler` from its own `noEmit`
parameter — **deliberately NOT `options.noEmit`, which 440 corpus tests set as a DIRECTIVE**
and whose baselines were produced by a core that still emits. `SkipEmitOutputsTest`'s fourth
test is the negative control for exactly that.

**A/B: median A=31,250 B=27,680, -3,570 ms = -11.42%, B wins 6/6**, per-pair median -3,221 ms,
range [-4,099, -2,773] against a +-590 ms band. The A/B delta exceeds the phase measurement by
~950 ms: the emit builds and discards ~10 MB of output strings (allocation/GC no span brackets)
and the post-checker residue itself fell 2,876 -> 182 ms.

**THIS IS A SCOPE CORRECTION, NOT AN ALGORITHMIC SPEED-UP, AND IT MUST BE REPORTED AS ONE.**
Real `tsc --noEmit` does not run its emitter either. ~~So every published xtsc-vs-tsc
`--no-emit` ratio before this compared our check+emit against tsc's check-only; the honest
single-thread figure is ~2.15x.~~ **RETRACTED IN PLACE, ROUND 739 (BENCH.1): there was no
published `--no-emit` ratio.** `bench-3way.sh` runs xtsc, tsc AND tsgo WITH EMIT, so the 2.4x
was already like-for-like, and `skipEmitOutputs` — which fires only under `--noEmit` — does not
move it by construction. The check-only ratio has never been measured on either side; see the
round-739 note at the top of this file and `docs/ARCHITECTURE-RETHINK.md` § 0.2.

**METHOD NOTE the next agent must not misread: the crawl's per-file read/pre-parse sums are
ELAPSED-WITH-SUSPENSION, not CPU.** They bracket a `withContext(...)`, so a file's "parse"
span includes waiting for a dispatcher slot — which is why the pre-parse sum (17,958 ms) is
10.7x the crawl WALL (1,683 ms): that ratio is the effective in-flight concurrency, not a cost.
**Only the crawl WALL is a wall-clock price**, and the report excludes the two sub-sums from
its own total. Race-freedom was designed in rather than hoped for: each flow element carries
its OWN nanos back on its `CrawledFile` and the SINGLE-THREADED collector sums them (a `+=`
from the workers would race exactly as `PassTiming.nodeKindHistogram` does).

**SECTION 0.1 AS A WHOLE — the honest status, since three of five stages now have numbers.**
Stage 1 (DISPATCH.1, claimed 11-19%): measured **4.8% upper bound, ~100-300 ms realistic**;
this round's per-handler gate row (194 ms over 857k nodes at 212 ns) is a third confirmation
=> **~0.3-1%**. Stage 2 (M0.4 tail migration, claimed ~14% AFTER stage 1): **its stated basis
is VOID** — it was priced as "stage 1 retroactively unlocks it" and stage 1 does not deliver.
Stage 3 (getTypeOfExpression recompute, claimed ~9%): **STRUCK** (ceiling 2.9%, sound residue
0.16%). Stage 4 (flow narrowing): **the only stage that paid** — -4.53% landed, ~2% residue
mostly in-band. Stage 5 (front end, claimed 20%): 11.0% with no lever, plus the 9.2% scope
error now landed. **So "~1.4-1.7x of today" is NOT SUPPORTED**: it assumed stages 1+2 were
worth 25-33% and stage 3 another 9%; measured, stages 1+3 are ~1-4% combined, stage 2's premise
is gone, and stage 5 has nothing. **The staged plan's remaining honest value is single digits.**
**The realistic remaining route to parity**: 88% of the compile is now the checker, and SEVEN
consecutive intra-function attributions have found the same shape — cost spread over hundreds
of dedicated walkers at 0.1-1% each, with no single lever above the band left inside it, and
every predicted lever coming in 3-65x too small. Three moves remain and only the first changes
the constant: (1) the architecture change section 0.1's own "endgame" paragraph names —
replacing ~1,005 `check*` walkers with general engine rules, for which this round produced the
FIRST price on one instance (the var-decl FP-firewall prologue is **265 ms against the 19 ms
relation it exists to correct, 14x**) — a SCOPE decision that trades the property which made
the byte-identical corpus reachable; (2) parallelism, now cheaper than M2 measured because a
worker's duplicated bind is only 5.2%, still needing >=8 real cores ((PERF.HW), unmeasured);
(3) accept the gap — ~~~2.15x~~ **corrected round 739 to 2.28x (median of 340 CI runs) / 2.40x
(last 30) in EMIT mode, with the check-only ratio unmeasured and bounded below by 2.21x.**

Suite 12,923 -> **12,927 / 0 / 3** (+4 `SkipEmitOutputsTest` pins); profile `--listAll`
byte-identical before and after the gate (46 errors); cost gate **18 of 20 counters +0.00%**,
with `globals.lookups` -9.03% and `globals.misses` -9.11% FALLING (the Transformer's own
checker queries, no longer made) and rebaselined in the same commit. Full derivation:
**`docs/perf/front-end-attribution.md`**.

**Round 738 (2026-07-27) — (TYPE.2) DONE, PART 1. BOTH OF THE ITEM'S PRIORS ARE FALSE,
THE SECOND BY 65x, AND `checkVarDeclAssignability` IS NOT WHAT ITS NAME SAYS.** The item
asked whether the 36 us per initializer is mostly FLOW NARROWING (prior i) and whether the
~2,470 ms outside the typing is the ASSIGNABILITY RELATION (prior ii). Measured inside the
function with a new two-level partition (`CtaSections`, `--ctaSections{,Coarse}`):
**narrowing is 1 ms of 872 ms (0.11%)** and **`checkTypeRelatedTo` is 13 ms (1.5%)**.
Round 735 found the SAME relation prior wrong by 48x one function over; it is now falsified
in BOTH of the compiler's largest assignability sites, so "an assignability check's cost is
the relation" should not be proposed a third time without measuring.

**WHAT IS ACTUALLY THERE — the exit profile decided it.** Of 15,116 invocations, **12,960
(85.7%) leave in the UNANNOTATED-initializer branch**: no annotation, nothing to check, the
work is `getTypeOfExpression(init)` plus a widening and a map write — **405 ms = 46% of the
function**. Only **797 (5.3%)** reach the target-type computation at all. So there are two
populations sharing a name: **12,960 unannotated declarations at 34 us each and 1,881
annotated ones at 227 us each** — round 737's 36 us was their mean.

**THE HANDLER, and the round's second-most-useful number.** Level A was deliberately opened
on `spineCtaM3StatementAnchor` itself rather than on `ctaM3StmtAnchor`, so the ELIGIBILITY
decision (kind test, parent test, the `ctaM3NestedChainOk`/`ctaM3FnBodyAnchorScope`/
`ctaM3NearestList` parent-chain climbs) is a partition ROW instead of an unexplained
remainder that would have invited "it must be the dispatch machinery". **The handler is
2,363 ms; the gate over ALL 856,976 nodes is 194 ms (212 ns/node) and the whole ambient
install/restore/dispatch scaffolding is 158 ms — together 1.2% of the compile.** The other
85% is four callees' own checking: `checkVarDeclAssignability` 891, `checkReturnAssignability`
615, `checkAssignmentExpression` 318, `walkFunctionBodiesInExpr` 181. Round 733's rule, third
confirmation: a handler's nanos are its WORK, never its scaffolding.

**NOTHING LANDED, correctly.** The one candidate the attribution surfaced — hoist the
unannotated branch above the ~18-walker prologue (265 ms) — is worth **~0**: every prologue
walker's first or second line is `decl.type ?: return false`, so the 12,960 unannotated decls
already pay one field read. The 265 ms is spent on the 1,881 ANNOTATED decls at 141 us each,
and it is **14x the 19 ms relation it exists to correct** — the first price tag on section
0.1's "endgame" paragraph. An estimate of "265 ms x 86%" would have been wrong for exactly
the reason section 0's law keeps producing: the population that looked skippable was already
the cheap one.

**PREDICTION SCORED (stated in full before the run): P1 HELD** (narrowing <=25%; really
0.11%), **P2 HELD** (relation <=10%; really 1.5%), **P3 FALSIFIED** (A_VDECL largest but
<50% AND walkFunctionBodiesInExpr >=15% — the first clause held at 37.7%, the second failed
at 7.7%), **P4 HELD** (level-A partition 2,000-3,000 ms; really 2,363). Three of four, against
two of four in each of rounds 732-737.

**METHOD, and one honest admission.** Level B's ON-vs-COARSE differential works: +11 ms over
84,737 extra boundaries = **130 ns each**, same order as rounds 734/735's independently-derived
86-89 ns, bounding level-B inflation at 1.3%. **Level A's differential does NOT work** — its
delta is -102 ms over +334,104 boundaries, i.e. NEGATIVE and entirely swamped by drift (the two
runs' walls differ by 1,490 ms on a +-13% box). Level A's inflation is therefore COMPUTED from
the established per-read cost (1,264,382 x 89 ns = ~113 ms of 2,363 ms = 4.8%), not measured,
and every millisecond is relative attribution. The in-situ empty-span calibration was
deliberately NOT taken: it over-read by 3.6x and 4.4x in consecutive rounds and re-deriving a
known-wrong number is not evidence.

Also recorded: the level-B cost distribution has round 735's shape again — **71 invocations
(0.47%) carry 406 ms (47%) at 5.7 ms each**; which population they belong to was not recorded
and is this round's one loose end (in-band either way at 1.4%). Suite 12,916 -> **12,923 / 0 / 3**
(+7 `CtaSectionProbeTest` pins); `--listAll` byte-identical in production, ON and COARSE
(46 errors); cost gate **all 20 counters +0.00%**. Full derivation:
**`docs/perf/var-decl-attribution.md`**.

**Round 737 (2026-07-27) — (TYPE.1) DONE. ARCHITECTURE-RETHINK section 0.1 STAGE 3 IS
STRUCK: its MECHANISM is exactly right and its SIZE is wrong by 3.2x.** The 701,463
`getTypeOfExpression` calls were attributed BY CALLER for the first time
(`--typeOfExprCallers`, opt-in; only the OUTERMOST call walks the stack, so a whole
expression subtree is attributed to the handler that ASKED for it and the recursion
cannot inflate a caller's own factor). **The claim "several handlers independently type
the same node" is CONFIRMED and pervasive: 177 sites initiate typing, 45.2% of the
254,069 typed nodes (114,750) carry MORE THAN ONE origin — modal count three, maximum
17 — and 75.8% of all calls land on those nodes. The x2.76 factor decomposes cleanly as
2.05x CROSS-HANDLER times 1.34x recursion, and per-caller factors are 1.00-1.11
everywhere: no handler re-types anything by itself.** **The money is not there.** A
PERFECT per-node cache — the ceiling for this stage in ANY shape, ignoring soundness and
every ambient install — saves **823 ms = 2.9% of a 28.7 s compile**; single-visit
discipline (repeat OUTERMOST typings only) **670 ms = 2.3%**; the largest single
handler-pair merge in the compiler **166 ms = 0.58%** against a re-derived +-2% band of
~590 ms; and the SOUND memo re-measures at **46 ms**. **NOTHING WAS LANDED**, correctly.

**TWO CORRECTIONS THE ROUND FORCES.** (1) **"getTypeOfExpression = 3,911 ms" is a DOUBLE
COUNT** — `typeOfExprNanos` sums every call's span including nested ones, charging a
subtree once per level. **The true total cost of all expression typing is 2,439 ms =
8.5% of checker-init**, so 8.5% was always stage 3's ceiling even if typing were free.
(2) **74.4% of the calls are OUTERMOST, not nested** — the intuitive "the factor is just
recursion" reading (which was this round's stated prior P1) is false by 3x.

**SECTION 0's LAW AGAIN, IN ITS SHARPEST INSTANCE AND WITH NO CACHE IN IT.** Sorted by
COUNT the co-occurrence head is 141,388 repeat typings worth **71 ms (0.5 us each)** —
the property-access receiver trio (`emitTs18048ForClosureCapturedUndefinedReceiver` +
`checkMemberAccessMissing` x2, 90,948 repeats for 51 ms), `getTypeOfObjectLiteral`
re-entering itself (30,040 for 6 ms), `calleeParamGivesNoContext` re-entering itself
(27,133 for 23 ms). Sorted by TIME the head is **2,603 repeat typings worth 166 ms
(64 us each)**, `collectUncalledTypedLocalsFromBody -> checkVarDeclAssignability`. The
two orderings share nothing. Cross-origin repeats are 547 ms over 188,068 typings,
same-origin only 68 ms over 61,215 — the redundancy is genuinely BETWEEN handlers, and
it is genuinely cheap.

**PREDICTION SCORED (stated in full before the run): P1 FALSIFIED** (>50% nested; really
25.6%), **P2 HELD** (0.5-2.0 s prize; really 670 ms), **P3 HELD** (top-3 pairs <50% of
the redundant time; really 41%), **P4 FALSIFIED** (top-5 origins >=60% of outermost;
really 52.5%). The falsifier — any single pair >600 ms — was not met, by 3.6x. Two of
four wrong, the same hit rate as 732-735; the one that mattered was P1, where the reason
to distrust stage 3 turned out to be the OPPOSITE of the expected one and the mechanism
section 0.1 named was right all along.

**WHAT DID NOT WORK.** (a) A per-node type cache of any shape: ceiling 823 ms, and that
ceiling assumes ignoring `currentFlowGraph`/`currentLocalTypes`/`currentTypeParamScope`
and the object-literal freshness rule — the SOUND version measures 46 ms in the same run.
(b) Merging the property-access receiver trio, the biggest co-occurrence GROUP in the
compiler at 90,948 repeat typings: **51 ms**. (c) Merging the var-decl cluster, the most
EXPENSIVE group (~12,100 repeats, ~326 ms): spread over five ordered pairs whose members
type under different ambient installs — which is exactly why the same node costs 64 us
when `checkVarDeclAssignability` re-types it and 21 us when `collectUncalledTypedLocals`
first does, and exactly why round 596's memo across them was unsound.

**METHOD.** The probe costs **16.7 us per attributed outermost call** (checker-init
37,429 ms attributed vs 28,694 ms plain, over 522,102 `StackWalker` walks) — 190x round
735's 89 ns timestamp read — so this round is deliberately COUNT-heavy: every
deterministic counter is byte-identical between the two runs and to `cost-counters.txt`,
which makes the calls / distinct / outermost / co-occurrence figures exact and
load-independent, while every millisecond is RELATIVE (nested probe work inflates an
outermost span by ~4%). Node keys mix pos+end+nodeId because `nodeId` is only PER-FILE
preorder; the report prints a file-salted distinct count beside the unsalted one
(254,069 vs 272,124, a 7.1% gap) so the residual is measured rather than assumed.

Suite 12,910 -> **12,916 / 0 / 3** (+6 `TypeOfExprCallerAttributionTest` pins); profile
`--listAll` **byte-identical** production vs attribution (46 errors); cost gate **all 20
counters +0.00%** (the probe is additive-only inside the existing `PassTiming.enabled`
guard). Full derivation: **`docs/perf/type-of-expression-attribution.md`**. Follow-on:
**(TYPE.2)** — the by-caller table's own pointer, `checkVarDeclAssignability` under
`spineCtaM3StatementAnchor`: the largest single typing origin (431 ms over 11,933
top-level initializers = 36 us each) inside the third-largest spine handler (2,900 ms,
round 732) that no round has opened, and its typing is only 15% of it.

**Round 736 (2026-07-27) — (CALL.3) DONE, AND THE ARC HAS ITS FIRST LANDED WIN:
`-4.53%` median, B wins 6/6, outside the +-2% band by 2.3x.** Round 735 handed forward
394 walks costing 1,485 ms and forbade designing before two numbers existed. Both were
measured with a new opt-in probe (`NarrowSections`/`NarrowProbe`, `--narrowSections{,Coarse}`),
and they point at one line. **(i) ARRIVALS vs DISTINCT: a tail walk arrives at 1,900 flow
nodes but only 214 DISTINCT ones — revisit factor 8.85 against 1.48 for a typical walk.
The tail is not a bigger graph; it is the same small graph walked nine times.
(ii) THE PER-ARRIVAL SPLIT: 51% of the whole narrowing population is
`applyConditionNarrowing` (1,412 ms over 759,784 calls at 1,858 ns), and the tail's
arrival MIX is what makes its arrivals 6.3x costlier — `FlowCondition` is 41% of tail
arrivals against 18% overall, `FlowBranchLabel` 22% against 9%, while cheap `FlowCall`
pass-throughs fall 57% -> 19%.**

**THE FIX, and it is a soundness argument rather than a green suite.**
`NarrowFlowMemo.served(id, depth)` required `depth <= storedDepth`, so a node reached
again by a LONGER path recomputed its whole antecedent subtree — 631,585 arrivals
compile-wide, **426,753 of them at `FlowCondition` nodes**, versus 290,011 serves. That
condition guards exactly ONE thing: a deeper entry has less depth budget and might
truncate at `NARROW_MAX_DEPTH`=2000 where the stored computation did not. That is
**decidable**: `depth` influences the result through no other channel (grep the walker —
it appears only in the cap test, the two memo calls, and `depth + 1`), and only
NON-truncated results are ever stored. So an entry now carries `hi`, the max depth its
own subtree reached, and serves a deeper probe iff `depth + (hi - storedDepth) < maxDepth`.
**The one non-obvious part: a served hit must fold `depth + servedHeight` into the
CALLER's height, or an ancestor records the shortcut's height instead of a fresh
recomputation's and the disjunct goes unsound under nesting.** Mirrored into
`narrowTypeFromFlowFollowLoopEntry` per the walker-mirror invariant.

**EFFECT (counters, deterministic): `narrowTypeFromFlowCore` invocations 1,455,915 ->
659,592 (-55%); arrivals 4,759,476 -> 3,500,214 (-26%) with DISTINCT nodes UNCHANGED at
3,212,764; `applyConditionNarrowing` calls 759,784 -> 333,031 (-56%); `getUnionType` at
branch labels -59%; the `>=1 ms` tail 429 walks -> 230 and its arrivals 815,259 -> 34,490
(-96%), revisit factor 8.85 -> 1.34.** Distinct unchanged to the node is the signature of
a correct memoisation change: the same work is discovered, it is simply not repeated.
Measured context that made the argument checkable: maxDepth reached is **249 of 2000**,
and depth/budget/cycle truncations are **0/0/0** (the cycle bail is structurally
unreachable here — the only back-edges are loop back-edges and `narrowTypeFromFlow`
returns the declared type at `FlowLoopLabel` without recursing, so it is a DAG walk).

**WHAT DID NOT WORK — two candidates priced and rejected, record them.** (1) **A "does
this condition mention the name" pre-test in front of `applyConditionNarrowing`**:
95.6% of its calls return their INPUT unchanged, which reads like a huge prize and is a
trap — the identity calls cost **949 ns each against 21,708 ns for the calls that
narrow**, so the whole population is 689 ms raw before this round's fix and 468 ms after,
~410 ms net = 1.3%, INSIDE the band before paying for the pre-test. Section 0's law in a
shape that is not a cache: *what you can skip cheaply is what was already cheap*.
(2) **Memoising the fast-forward chain's pass-through nodes**: `FlowCall` is 57% of all
arrivals with 2,743,997 memo absences — the biggest miss population in the table and the
cheapest, ~131 ns per arrival, so its entire revisit share is <=120 ms against a 74 ns
extra `putIfDeeper` per invocation to capture it.

**METHOD carried forward.** The per-arrival population (4.8 M) is large enough that one
timestamp PAIR per arrival would have added ~850 ms to a 2.75 s population — the probe
would have BEEN the measurement. So the two per-arrival structures are priced in **probe
STEPS** (a deterministic counter inside their open-addressing loops), not nanos. And:
one profile run in this round was taken at 239 MB available and reported the walk anchor
at **83,074 ms against a true 2,751 ms** (30x) while its COUNTERS were byte-identical to
the clean run — which is exactly why the decisive numbers here are counters.

Suite 12,899 -> **12,910 / 0 / 3** (+4 `IntKeyMapTest` height pins, +7 `NarrowMemoDepthTest`);
profile `--listAll` **byte-identical A vs B** (46 errors); cost gate: `output.errors`,
`spine.nodes`, `narrow.walks`, `typeOfExpr.distinct`, `mapped.*` all +0.00%, and **four
counters FELL and were rebaselined in this commit** — `typeNode.cacheable` -10.6%,
`typeNode.cacheHits` -14.6%, `globals.lookups` -8.4%, `globals.misses` -8.5%, all
downstream of the 56% cut in `applyConditionNarrowing` (which resolves names and type
nodes). Full derivation: **`docs/perf/narrow-walk-attribution.md`**.
**A TEST-DISCIPLINE NOTE worth keeping**: two of this round's negative controls failed on
first run (TS2339 for `x.toFixed(2)` on a `string`-narrowed reference, and for a
loop-widened receiver). Both fail IDENTICALLY on the baseline build — pre-existing
emitter gaps, not regressions — and were rewritten onto TS2345 at a call argument, the
path this round actually profiles. **Run a failing negative control against the baseline
before believing OR dismissing it.**

**Round 735 (2026-07-27) — (CALL.2) DONE. THE PRIOR HOLDS BY 48x, AND ITS SUPPORTING
EVIDENCE POINTS AT THE WRONG TERM.** The item's falsifiable expectation was "most of the
61 us is argument TYPE computation, not `checkTypeRelatedTo`". **It is: 924 ms of the
function's 1,624 ms is the `argType` computation against 19 ms for the whole
`checkTypeRelatedTo`+TS2345 section (10 ms for the relation call itself).** But the prior
reasoned from `getTypeOfExpression` (3,911 ms, recompute x2.7) — and **inside this
function `getTypeOfExpression` is 196 ms (12%) while FLOW NARROWING is 600 ms (37%)**.
So (CALL.2) does NOT reach ARCHITECTURE-RETHINK section 0.1 **stage 3**; it reaches
**stage 4**. Full derivation: `docs/perf/argument-check-attribution.md`. Suite 12,892 ->
**12,899 / 0 / 3** (+7 pins, `ArgSectionProbeTest`); cost gate all 20 counters +0.00%;
profile `--listAll` identical in production, `--argSections` and `--argSectionsCoarse`
(46 errors). **No optimisation was landed** — every candidate inside this function prices
below the +-2% band, and the one target that is ABOVE it was disproved three times over
in the same run (below).

**THE COMPILE-WIDE FINDING, and it is the round's real product.** The walk histogram was
also added to `flowWalkWithTripCheck` under `--passTiming`, so it covers all 70,037 walks
from all 11 call-site kinds: `<10us=40,046/159ms  <100us=25,695/811ms  <1ms=3,902/827ms`
and **`>=1ms=394 walks/1,485 ms`**. **394 walks (0.56%) carry 47% of all flow narrowing
and 4.9% of a 30.5 s compile** — the first single target measured ABOVE the +-2% drift
band (~610 ms) since round 731, by 2.4x. By kind: WK_NARROW 336, WK_NARROW_LOOP 47,
WK_BASE_EXPR 11, so it is one walk function, not one caller.

**WHAT DID NOT WORK — three mechanisms for those 394, all DISPROVED by the same run.**
(1) "They are TRIPPED walks, which are deliberately never memoized, so they re-run in
full at every visit" — **FALSE, `narrowWalk tripped: 0 walks`; nothing trips on this
profile.** (2) "They exhaust the 1,000,000-visit budget" — **FALSE: the 394 consume
630,641 flow-node arrivals TOTAL, 1,601 each, max 19,515**, two orders of magnitude
below it. (3) "The walk memo would serve them" — **FALSE: `walkMiss split: cold=69,968
epochInvalidated=69`**, so essentially every launched walk is a first sighting and no
cache reaches it (section 0's law from a fourth direction; the LIVE memo already serves
40,709). What is left is arithmetic: all walks average **372 ns per node arrival**, the
394 monsters **2,354 ns** — 13x more arrivals AND 6.3x more expensive arrivals, and
neither factor alone explains it.

**ALSO PRICED AND BELOW BAND, so recorded rather than attempted.** 86% of this function's
9,615 narrowing walks (8,299) return the INPUT type unchanged, costing 237 ms — so a
pre-test proving "this reference has no flow facts" is worth at most 0.8% of the compile.
And the exit profile: **only 10,146 of 38,247 loop iterations (27%) ever reach the
assignability check** (14,663 leave at the weak-target section, 12,280 in the
`!isSimpleCheckableType` function-vs-function block) — yet all 37,379 pay the full
`argType` computation, because every intervening block consumes `argType`. That is a real
structural observation, not a lever: the work cannot be deferred past its consumers.

**THE CALIBRATION, THIRD TIME, AND THE FIX IS NOW A MODE.** Round 734's lesson was
"calibrate DIFFERENTIALLY, not with an empty span". This round generalises it:
`--argSectionsCoarse` keeps only three anchors, so every other boundary costs a static
read and a not-taken branch while the partition still spans the same wall time. ON is
1,624 ms over 404,358 boundaries + ~293k nested reads; COARSE is **1,569 ms over 83,085**
-> **89 ns per timestamp read**, independently reproducing round 734's 86-92 ns by a
different construction. The unrolled in-situ empty-span calibration in the SAME run
reported **391 ns — 4.4x too high**, the identical error round 734 saw at 306 vs 86.
**Total probe inflation 55 ms of 1,624 = 3.4%.**

**THE NEXT UNIT is (CALL.3), and it needs two numbers before anything is designed.**
(a) Node ARRIVALS versus DISTINCT flow nodes per monster walk: the intra-walk memo
(`NarrowFlowMemo`, tsc's `sharedFlowNodes`) serves only entries stored at a
same-or-deeper entry depth (`served(id, depth)` requires `depth <= stored`), so a revisit
reached by a LONGER path misses — if arrivals >> distinct, that depth condition is the
lever. (b) The per-arrival split of `narrowTypeFromFlow` (`applyConditionNarrowing`,
`flowAssignmentMightNarrow`, `flowCallMightNarrow`, the `FlowBranchLabel` fan-out) —
2,354 ns is not graph traversal. Anything proposed before those exist repeats the error
of rounds 732/733/734, which predicted levers 5x, 6-17x and >=2x too large.

**Round 734 (2026-07-27) — (CALL.1) DONE. THE MEASUREMENT CHOSE BRANCH B, by 4x, and
Branch A is disproved on TWO independent grounds.** The item asked which of two things
`checkSingleCallExpressionTypes`'s 2.9 s is: (A) per-call PRE-work that never-firing
emission sites pay before they know they will not fire — "hoisting removes 1-2 s" — or
(B) signature resolution and argument relations, i.e. genuine type-system work whose
lever is M3.1 and not this function. **It is B. 78% of the function (2,007 of 2,564 ms
raw) is type-system work**: `checkArgumentsAgainstSignature` **1,357 ms (53%)**,
`getCalleeType` **474 ms (18%)**, the TS2793 impl-would-have-succeeded probe 101,
`checkArgumentsAgainstOverloads` 53, `getCallSignaturesOfType` 19. **No optimisation was
landed** — per the item's own instruction for this outcome, and per round 733's
precedent. Full derivation: `docs/perf/call-expression-attribution.md`. Suite 12,887 ->
**12,892 / 0 / 3** (+5 pins, `CallSectionProbeTest`); cost gate all 20 counters +0.00%;
profile `--listAll` identical probe-on vs probe-off (46 errors).

**BRANCH A IS WRONG STATICALLY, BEFORE ANY TIMING.** The item's counts are right — 22
`getLineAndCharacterOfPosition`, 17 `expressionTrueEnd`, 11 `typeToString`, 18
`diagnostics.add` — but its claim that they are "all computed before the gate that
decides whether to emit" is false for **every single site**: 16 of the 22 sit literally
inside `if (length > 0) {`, and the rest inside `if (display != null)` /
`if (pname != null)` / a `run{}` past all its `?: return@run` gates. **There is no
pre-gate emission work to hoist. The gates already ARE the cheap pre-test the item
proposed adding.** (Reproduce by listing the line preceding each call site over the
function's line range — a 30-second check that would have retargeted the round.)

**AND WRONG DYNAMICALLY.** Everything in the function that is not type-system work
totals **557 ms**, ~70 ms of which is the probe itself → a theoretical maximum prize of
**~490 ms = 1.6% of a 30.5 s compile**, i.e. INSIDE the +-2% drift band (~610 ms). The
prize is smaller than the noise that would have to measure it, so no A/B is offered —
the same restraint round 733 exercised. The largest hoistable block, the seven
never-firing prologue walkers measured as ONE span, is **253 ms**.

**THE EXIT PROFILE, which the partition gives away for free** (`calls[s]` = invocations
that REACHED section s, so the drop between sections is the exit count): of 52,413
invocations, **26,496 (50.6%) leave at the `calleeType === anyType || errorType` bail** —
after `getCalleeType` has run in full; 22,145 (42.2%) reach the single-signature branch;
3,640 the overload branch; 101 the explicit-type-argument branch; 31 the union branch;
and **0 reach the ~240-line `signatures.isEmpty()` branch** with its seven emission
sites, so its `binderResults x top-level-statements` scan — the one genuinely pre-gate
computation in the function — never runs on this profile. **0 of the seven prologue
walkers fire, and they still cost 253 ms**: that cost is gate evaluation, which is
exactly why there is nothing to hoist.

**WHAT WAS BUILT.** `CallSections` (in SpineDispatch.kt) + boundaries inside the
function, opt-in via `--callSections`, behaviour-free when off. The function was split
into a wrapper and `…Core`: the wrapper branches once on `mode` and otherwise calls the
core directly — no `try`/`finally`, no bookkeeping. Unlike a spine handler this function
has ~20 early `return`s, so the running section lives in the object and is closed by
`end()` from the wrapper's `finally`; the pay-off is the free exit profile above. 16
partition boundaries in source order, plus six nested sub-measures (the two
`checkArgumentsAgainstSignature` call sites, `checkArgumentsAgainstOverloads`, the TS2793
probe, the five dedicated single-sig walkers, and the whole prologue as ONE span).

**WHAT DID NOT WORK — the calibration, twice, and the fix is a METHOD not a constant.**
Round 733's lesson was "calibrate in situ, not at startup". In situ is necessary and NOT
sufficient. Draft 1 measured the empty span from `begin()` to the core's first boundary
and read **922 ns** — it spans the wrapper's non-inlinable call into a 3,587-bytecode
method, a cold transition rather than a timestamp pair — which drove SIX sections
negative. Draft 2 used `repeat(8) { at(...) }` and read **360 ns**: a `repeat` loop puts
a **back-edge SAFEPOINT POLL inside every empty span**, so stop-the-world pauses are
attributed to the calibration. Unrolling gave **306 ns** — still 3x too high. **The
honest figure is DIFFERENTIAL: the prologue is measured twice in the same run, as seven
sections (280 ms) and as one span (253 ms); six extra boundaries over 52,413 invocations
= 86 ns each** (the prior run gave 92 ns on the same construction). That independently
agrees with the round-733 technique — `--passTiming` with vs without `--callSections`
moved `checkSpine` by **+29 ms**, +0.1%. Total probe inflation inside the partition is
~70 ms of 2,564 ms = 2.7%, so the report's `raw` column is an upper bound and its `net`
(which subtracts the pessimistic 306 ns) a lower one.

**A NON-FINDING WORTH RECORDING.** The 922 ns first reading made "HotSpot refuses to JIT
this method" look plausible (`DontCompileHugeMethods` skips >8,000 bytecodes, and the
function is 920 lines). `javap` settles it in one command: the core is **3,587
bytecodes**, well under the limit. Check the bytecode size before theorising about the
JIT.

**THE REAL LEVER, and it is queued as (CALL.2).** `checkArgumentsAgainstSignature` is
now the largest single measured cost in the compiler: **1,357 ms over 22,145 calls =
61 us each**, in a **1,534-line function** — larger than the one this round attributed.
Whole-compile counters for the same run (`getTypeOfExpression` 3,911 ms / 701,736 calls,
recompute x2.7; relations at depth 0 only 699 ms) make the falsifiable prior that most
of the 61 us is argument TYPE computation rather than `checkTypeRelatedTo`. Secondary:
`getCalleeType` costs 474 ms and half its results are thrown away three sections later —
ask whether the bail's verdict is knowable more cheaply than by resolving (NOT a caching
question; ARCHITECTURE-RETHINK § 0 closed those).

**Round 733 (2026-07-27) — (SPINE.1) step (a) DONE, and it FALSIFIES the item TWICE.** The
two hottest spine leave handlers were attributed INTERNALLY (not guessed — round 732's
whole lesson), and the item's premise does not survive: **`cpaSpineLeave` + `ccetSpineLeave`
are not "legacy-parity frame bookkeeping". 88.4% of their measured time is the cpa and ccet
passes' OWN checking work** (`checkPropertyAccessInExpr`, `checkSingleCallExpressionTypes`)
running inside the frame-ambient block. And the item's named target — the ancestor climbs —
is **176 ms, not the predicted 1-3 s**. Full derivation, per-section table and reproduction:
`docs/perf/spine-leave-attribution.md`. Suite 12,882 -> **12,887 / 0 / 3** (+5 pins,
`SpineSectionProbeTest`); cost gate all 20 counters +0.00%; profile `--listAll` identical
probe-on vs probe-off (46 errors).

**WHAT WAS BUILT.** `SpineSections` (in SpineDispatch.kt) + splits in the two handlers,
opt-in via `--spineSections` and behaviour-free when off (`t`/`split`/`close`/`hit` are
`inline`, so production pays a load-and-branch, never a call). The sections PARTITION each
handler (7 cpa + 4 ccet) via a running-timestamp split inserted BETWEEN existing sections —
no control flow was restructured. Nested sub-measures wrap the three ancestor climbs (each
split into a timing wrapper + an untouched `…Core`) and both frame-ambient installs (a
`sec`/`kind` param defaulting to `NONE`, so only the LEAVE call sites record), and the
ambient is reported twice: whole-wrapper and install+restore ONLY — that second row is what
separates scaffolding from work.

**THE NUMBERS** (compiler profile, 856,962 nodes, net of a 42 ns probe pair; partition total
8,195 ms net vs round 732's un-split 7,412 ms, so ~10% probe-inflated — relative attribution
only). cpaSpineLeave 4,934 ms: anchor stmt **3,304** (45,626 hits), owner cond/subject
**1,349** (19,551), VariableDeclaration recordings 180, frame pop 48, loop-var restores 25,
heritage EWTA 17 (2 hits), PropertyDeclaration 11 (5 hits). ccetSpineLeave 3,254 ms:
call/new/tagged anchor **3,136** (52,972), VariableDeclaration recordings 78, frame pop 33,
override restores 7. **THE SPLIT THAT MATTERS: inside the frame-ambient block 7,601 ms
(92.8%), of which the ambient install+restore is only 360 ms — so the passes' own work is
7,241 ms = 88.4%. Outside the ambient: 587 ms (7.2%), of which the three climbs are 176 ms
(2.1%).**

**BOTH STEP-(b) HYPOTHESES PRICED AND DEAD.** (1) Memoizing `cpaM2ChainOk`/`cpaM2StmtPosition`:
the entire cpa climb population is **85 ms** (77 + 8), 176 ms including `ccetM3ChainOk` —
falsified by 6-17x, and § 0's law bites (a keyed probe cannot pay for a 932 ns walk over mean
ancestor depth 6). (2) A parent-kindId dispatch axis: the three parent-keyed sections are
`owner` 1,349 ms (of which ~1,340 is its 19,551 hits' WORK), EWTA 17 ms and PropertyDeclaration
11 ms — **worth <=60 ms**. Five of cpaSpineLeave's seven sections cost 281 ms across 856,962
consultations EACH; consultation is not the expense here either.

**WHAT THE NUMBERS DO POINT AT — the next unit of work.** `checkSingleCallExpressionTypes` is
**53.6 us per CallExpression** (2,931 ms over 52,413 anchors, minus the 2.3 us install). It is
a **920-line straight-line function with 18 `diagnostics.add` sites and 7 `run{}` blocks, run
in full for every call expression in the program** — 2.9 s, ~10% of a ~28 s compile, in ONE
function, and the largest per-node cost measured anywhere in this compiler. Unlike the cpa
anchors (which walk a statement subtree, so tens of us is expected) this is per-NODE. Round
732's per-kind table agrees: it put CALL_EXPRESSION at 3,636 ms across all 59 handlers, and
3,082 ms of that is ccetSpineLeave alone.

**WHAT DID NOT WORK / WHAT WAS NOT DONE.** (a) NOTHING was optimised, deliberately: every
candidate the item named measures below the 560 ms drift band of a 28 s compile, and the
largest remaining scaffolding item (the 360 ms ambient install+restore) is ~1.2% — landing a
speculative change to have something to show is exactly what round 732's falsification bought
the right not to do. (b) The first probe calibrated at JVM STARTUP and read 40,573 ns per
timestamp pair (the cold interpreter), making every net figure NEGATIVE; the calibration is
now IN SITU — an empty span at the top of `cpaSpineLeave`, once per node, reading 42 ns. (c)
A checked-and-discarded lead: the legacy `checkCallTypesInExpr` still calls
`checkSingleCallExpressionTypes` and truncates the diagnostics via `ccetM3IsAnchored`, which
looks like emit-twice — it is not, because the ccet/cpa/cta legacy PASSES were retired
(rounds 585/592), so those truncation marks are inert residue. No duplicate work.

---

### QUEUE — work top-to-bottom; promote unblockers per protocol

**Reading convention (stated round 687, after it cost a scan):** a superseded
item is kept for its history as `- [ ] ~~Name (original)~~ — …` directly BELOW
the `- [x]` entry that replaced it. Those struck-through lines are INERT — a
top-down scan for the next `- [ ]` must skip anything whose title is `~~…~~`,
and must also skip a parent whose every live child is `[x]` or owner-parked.

(Restored 2026-07-12, round 481 — the queue/backlog/inventory sections had been
swept into PLAN-PHASE-5-HISTORY.md by an over-eager session-note trim; they are
LIVE structure, not history. v1's offline-verifiable legs LANDED at round 481, so
M5 is now the active arc per the owner directive; the Post-v1 backlog below is the
"any TypeScript project" horizon and stays parked until the owner re-scopes. The
M1–M3 campaign items still unchecked in the history file (M2.2/M2.3/M3.1–M3.4/M1.12)
hit their re-scoped v1 acceptance bar — "the shapes tsc's source uses" — when the
burn-down reached zero real FPs; reviving their full-completeness form is a
backlog-horizon decision, not queue debt.)

**TOP OF QUEUE (owner-requested 2026-07-26, round 684) — work this before PERF.**

- [ ] **(REL.1) Enum-member types do not discriminate in the relation AT ALL —
  discovered round 729 while the `Exclude` distribution fix was being measured, NOT
  fixed there.** The one-line repro is
  `declare enum SK { A, B }; const k: SK.A = SK.B` — we emit NOTHING; tsc reports
  TS2322. Consequence: two AST interfaces that differ only in
  `readonly kind: SK.Identifier` vs `readonly kind: SK.PrivateIdentifier` read as
  MUTUALLY ASSIGNABLE, so a sibling node type is a structural subtype of every other
  sibling. That is invisible as long as nothing acts on it, which is exactly why it
  surfaced only when `Exclude<T, U>` started filtering: the filter promptly dropped
  `Identifier` out of `Exclude<PropertyName, PrivateIdentifier>` and invented two
  false positives (factory/utilities.ts:1056/1061). Round 729 closed THOSE by
  applying round 472's `.kind` DOMAIN veto (`kindDomainKeysExceed`) inside
  `evaluateConditional` — a per-site patch over a general gap, and the third such
  patch in this family (`kindDomainProvesNotSubtype` at the narrowing sites is the
  first two). **The real fix is a `Type` for an enum MEMBER that is distinct from
  the enum's own type, so the relation can reject a sibling by itself.** Blast
  radius is the reason it is a separate item: every enum-typed comparison in the
  corpus goes through this, TS2322/TS2367/TS2345 baselines included, and the
  existing kind-domain readers (`enumMemberKeysOfTypeNode`,
  `enumSwitchKeysFromTypeNode`, `discriminantPropAnnotation`) exist precisely
  because the relation could not answer. Decompose before starting; expect the
  per-site patches to become deletable once it lands, which is the measurable win.

  **DECOMPOSED AND SIZED, ROUND 740. IT IS A SESSION, NOT AN ARC — the measured blast
  radius is ONE corpus baseline.** (Fix deliberately NOT attempted; the probe below was
  reverted and the tree is clean.)

  **ROOT CAUSE, LOCATED — it is not "the relation is lenient", it is `anyType`.**
  `getTypeFromTypeReference` (Checker.kt:102093) reduces `SK.A` to the BARE member name
  `"A"` via `getTypeReferenceLastName`, resolves it through `resolveQualifiedName` to the
  enum's `exports["A"]` — a `SymbolFlags.EnumMember` symbol — and
  `getDeclaredTypeOfSymbolWorker` (:102387) **has no branch for that flag**, so it falls
  through to `else -> anyType` (:102509) and is cached in `declaredTypes[symbol.id]`.
  `kind: SK.A` and `kind: SK.B` are therefore *the same `Type` instance*. Corollary:
  **`TypeFlags.EnumLiteral` (Type.kt:55) is SET NOWHERE** — all ~11 read sites are dead,
  **including the widening rule `if (sf.hasAny(EnumLiteral) && tf.hasAny(Enum)) return
  true` at Checker.kt:143100, which is already written and waiting for a flag to exist.**

  **BLAST RADIUS — MEASURED, not guessed.** A throwaway 3-edit probe (an `EnumMember`
  branch minting a distinct `Type.Object(Object or EnumLiteral)` per member symbol —
  already interned by `declaredTypes[symbol.id]`; the enum's own type flagged
  `Object or Enum` so the dead rule fires; an enum-literal disjointness rule at the top
  of `checkTypeRelatedToCore`) was built, measured and reverted:
  - **Corpus: 12,927 tests, `1` failure** — `enumAssignmentCompat5`. Not "every enum-typed
    comparison"; ONE.
  - **The one failure is the MISSING leg, not the added one:** 4 spurious
    `TS2322: Type 'number' is not assignable to type 'A'`. A numeric enum member type must
    stay assignable FROM `number`, and from a numeric literal equal to the member's value
    (`let a: E.A = 0` is legal because `A === 0`; `a = 2` is not; `Computed.A = 1` is not,
    because a computed member has no literal — all three already in that baseline).
  - **Compiler profile: 46 -> 52 (+6).** Same family: `Extension.Dts` (a STRING enum
    member) not assignable to `string`; plus knock-ons where a union no longer collapses
    now that its enum members are distinct (`Partial<CreateSourceFileOptions> | ESNext |
    CommonJS`) and two generic/overload cascades.
  - **No perf cost:** probe profile self 26,192 ms against a 26.5-27.1 s baseline band.
  So the entire measured gap is **ONE rule family: enum member <-> its base primitive.**

  **DECOMPOSITION — three sub-steps, each landable alone and suite-gated.**
  - **(a) Mint the type, change no answer. DONE round 741** — corpus 12,940 / 0 / 8,
    compiler profile `--listAll` byte-identical at 46 (whole output, not just the count),
    cost gate max +0.40% and rebaselined in the same commit. `getDeclaredTypeOfSymbolWorker`
    has an `EnumMember` branch ([getDeclaredTypeOfEnumMember]) minting
    `Type.Object(Object or EnumLiteral)` interned on
    `"<canonicalEnumSymbol.id>#<memberName>"`; the enum's own type is
    `Type.Object(Object or Enum)`; `TypeFlags.EnumLiteral` finally has a writer and the
    rule at :143100 finally fires. **The base-primitive legs are VALUE-BLIND on purpose**
    — the value judgement (`let a: E.A = 2` where `A === 0`) is still
    `checkEnumLiteralAssignments`', and the relation co-emitting it is precisely the 4
    spurious TS2322 the round-740 probe measured. Both latent hazards closed. The one
    knock-on was NOT union collapse but `typeof x === "object"` classifying a member as an
    object — see `isEnumFlavoredObjectType`.
  - **(b0) Type the enum-member ACCESS EXPRESSION as the member. DONE round 742** —
    corpus 12,949 / 0 / 8 (+9 pins), profile back to 46, cost gate +0.35% then +0.00%.
    `enumMemberAccessType` (a targeted branch in `computeRawTypeOfPropertyAccess`, NOT a
    member table on the enum's type — that would make the enum a structurally non-empty
    relation TARGET) plus widening back to the enum in THREE places: `widenType`,
    `checkVarDeclAssignabilityCore`'s own inline literal-widener (which is what the TS2322
    assignment check reads as the declared type), and `widenEnumMemberTypes` distributing
    over a union. Six more classifier sites onto `isEnumFlavoredObjectType`; the two
    arithmetic classifiers answer per MEMBER. **Three pre-existing bugs the `any` had been
    masking, all fixed at the root**: `isComparableType` not resolving a TYPE PARAMETER's
    constraint; the arithmetic pass's first-wins recording refusing a nested body's shadow
    of an ENCLOSING body's binding (`spineArithInheritedName`); and the two widening paths.
    Member types print QUALIFIED now.
  - **(b) Let the relation reject. DONE round 744** — corpus 12,971 / 0 / 3, compiler profile
    `--listAll` BYTE-IDENTICAL at 46, **ALL FIVE `@Ignore`s in `EnumMemberRelationTest` ON**
    (which is why the skipped count fell 8 → 3). One rule in `checkTypeRelatedToCore` ("two
    enum-member types relate only when they are the same member"), verdict via the STRUCTURAL
    `enumMemberTypesAreSameMember` — never identity, because `canonicalEnumSymbol` can only
    canonicalize through `globals[name]` and INV.3(d) retired that merge for module-only names,
    so identity declares `SyntaxKind.StringLiteral` disjoint from itself. Landed WITH it, per
    the plan: the round-459 AST key gate in `signatureAcceptsArgs` RETIRED (its three original
    pins keep passing unchanged = the ablation evidence), a CONSTRAINT check for round 481's
    bare-`Type.TypeParam` lenience, the deletion of (a)'s string TARGET half, and a retraction
    in B266 (`checkNsEnumUnionOne`) of the general TS2322 it now co-emits with on three of
    `enumLiteralAssignableToEnumInsideUnion`'s five lines — only B266's DISPLAY is tsc's (a
    fully-covered member set collapses to the bare enum name), so when (c) retires B266 that
    collapse rule must move into the union display with it.
    - **THE TWO BLOCKING FPs WERE BOTH ENUM-FREE AND PRE-EXISTING**, landed on main as their own
      commits BEFORE (b), each ablation-verified against pristine main and each +0.00% on all 20
      cost counters: `declarations.ts:846` was a type guard on a bare TYPE PARAMETER REPLACING it
      with the candidate instead of intersecting (`9a8088a5`), and `utilities.ts:4175` was an
      intersection source carrying a union not DISTRIBUTING (`c1ed5cd5`). **Neither is what its
      message said** — see the round-744 session note for the two traps and the 1.3-second
      scratch-project CLI loop that made the bisection affordable.
    - **CLEARED round 743, at the root, on main — do not re-investigate:** `checker.ts:7997`
      was the B136 concrete-overload swap re-picking an overload the type-based loop had
      already rejected (`214e8cf1`); `parser.ts:2494` was overload selection being unable to
      see an `asserts` narrow, which lives only in the flow graph (`190d34b7`).
    - **NEGATIVE RESULT, do not re-spend a session on it:** the "one member splits into several
      `Type` instances because `canonicalEnumSymbol` cannot canonicalize a module-scoped enum
      post-INV.3(d)" hypothesis is FALSE — the structural verdict left the profile at the SAME
      FPs. It is kept anyway: strictly more correct and free.
  - **(c) Delete the scaffolding — REDIRECTED round 744 BY ABLATION. The first deletion is NOT
    any of the three AST-only passes; it is a VALUE-AWARE disjointness rule, and every pass
    stays until that lands.** Round 740's inventory called those three "100% artifacts,
    deletable"; a PassLab census (`build/pass-lab.txt`, `disable <pass>`, ZERO recompile — run it
    whole-suite for the failing set, then per-walker through the scratch-project CLI for
    attribution) measures every one of them uniquely load-bearing:
    `checkEnumToEnumAssignments` owns **all 12** of `enumAssignmentCompat3`,
    `checkEnumLiteralAssignments` **all 3** of `enumAssignmentCompat5`, and
    `checkNamespaceEnumUnionAssignments` **2 of 5** of `enumLiteralAssignableToEnumInsideUnion`.
    All three are the same gap: **the relation is VALUE-BLIND**, which step (a) chose
    deliberately (`let a: E.A = 2` where `A === 0`, `Computed.A = 1`, `E.A = 0` vs `F.A = 0`).
    `enumMemberTypeIsStringValued` already resolves a member's `ConstantValue` through the
    canonical enum, which is the whole input such a rule needs.
    - **None of the twelve AST key-space helpers is orphaned yet** (checked by reference count
      after the round-459 retire — `enumMemberKeysOfTypeNode` / `enumMemberKeyOfExpr` keep other
      consumers), so there is no free deletion to take first.
    - `discriminantPropAnnotation` (:110308) has FIVE call sites woven through switch-narrowing
      and type-guard filtering. It is a multi-step replacement, not a one-commit deletion, and it
      is the riskiest part of (c) — last, not first. Then `kindDomainProvesNotSubtype` /
      `kindDomainKeysExceed` (including the round-729 `evaluateConditional` patch).

  **WHICH CONSUMERS BECOME DELETABLE** (census round 740, all line numbers in Checker.kt).
  **FALSIFIED IN PART, round 744 — read the (c) sub-step above before trusting the first
  bullet:** the three AST-only passes are NOT artifacts of the missing member TYPE, they are
  artifacts of the missing member VALUE, and ablation measures all three still uniquely
  load-bearing with (b) landed. The rest of the census is untested.
  - **~~100% artifacts, deletable~~ (see above):** `discriminantPropAnnotation`,
    `kindDomainProvesNotSubtype`, `kindDomainKeysExceed`, `checkEnumLiteralAssignments`,
    `checkNamespaceEnumUnionAssignments`, `checkEnumToEnumAssignments`.
  - **AST-side machinery that only computes what a `Type` would carry** — deletable with
    their consumers: `enumMemberKeysOfTypeNode` :109867, `enumSwitchKeysFromTypeNode` :89651,
    `kindDomainTypeDeclSymbol` :109113, `kindDomainKeysFromTypeNode` :109077,
    `ifaceKindDomainKeys` :109123, `typeGuardMemberDisjoint` :109168, `discriminantKindKeys`
    :109177, `kindDomainKeysOfType` :109056, `filterUnionByEnumDiscriminant` :110021,
    `enumMemberKeyOfExpr` :109855, `enumSwitchKeysFromType` :89703,
    `literalDiscriminantKeyOfType` :109950.
  - **SURVIVES, in modified form — do NOT plan to delete it:** `canonicalEnumSymbol`
    :109783. The duplicate-`Symbol`-instance problem it solves (the same enum arriving as
    the merged global, a file-local, and a barrel-resolved alias) is INDEPENDENT of the
    relation; a naive per-symbol mint produces two non-equal types for the same member and
    reproduces the catastrophe its doc records at :109775. Step (a) must intern on the
    canonical symbol, or compare structurally (enum name + member name + value) as tsc's
    `isEnumTypeRelatedTo` does.
  - **Orthogonal, not caused by this:** `resolveImportedEnumSymbol` :109798 (the barrel
    `_namespaces/ts.js` module-resolution hop) stays either way.

  **TWO LATENT HAZARDS that go LIVE the moment the member type is distinct** (both in
  `getTypeFromTypeReference`, both invisible today because everything collapses to `any`):
  :102125/:102129 look `SK.A` up in `currentTypeParamScope`/`currentTypeAliasArgs` under
  the **bare name `"A"`**, so an in-scope type parameter named `A` captures it; and :102132
  falls back to `globals["A"]` on qualified-resolution failure, binding `SK.A` to an
  unrelated global type named `A`. Step (a) must key on the QUALIFIED name.

  **PIN LANDED (round 740):** `src/commonTest/kotlin/EnumMemberRelationTest.kt` — four
  `@Ignore`d currently-failing expectations naming this item (so the gap stays visible in
  the skipped count) plus **four NOT-ignored positive controls** that must keep passing
  throughout: member widens to its enum, member assignable to itself, `number` -> numeric
  member, string member -> `string`. Those last two are precisely the shapes the probe
  over-rejected, i.e. they are the FP firewall for step (a).

  **PINS EXTENDED (round 741), and one of round 740's was VACUOUS.** `take(Ext.Dts)` —
  the "string member -> `string`" control — passes with or without the member type,
  because an enum-member EXPRESSION types as the ENUM and rides the pre-existing
  `isStringEnumObjectType` rule; it never reaches a member type. The four added leg pins
  annotate through `declare const d: Ext.Dts` / `declare const a: E.A` instead and were
  verified non-vacuous against an ablation build. A fifth pins the `typeof === "object"`
  knock-on, and a sixth (`@Ignore`d) records the one place step (a) is knowingly more
  lenient than tsc.


- [x] **(CATCH.1) Defensive-`catch` audit — DONE round 685, six batches: 193 of
  Checker.kt's 197 removed as dead residue, 3 kept with stated reasons, 1 real
  bug found and fixed, and the 20 sites OUTSIDE Checker.kt audited and found to
  be a different population that should NOT be removed.** Owner flagged
  `Checker.kt`'s `val app = try { getApparentType(localType) } catch (_: Exception)
  { null }` as a code smell and asked what else looks like it. **The census:** 218
  `catch` sites in `src/commonMain`, **197 in Checker.kt**, every one the same
  shape — swallow and return a default: 84 `null`/`return null`, 57
  `return`/`continue`/`return@…`, 26 `false`/`true`, 9 empty or fall-through, ~14
  type-valued (`anyType`, `errorType`, `"any"`, `Ts2403Cmp.UNKNOWN`). **Why they
  are residue rather than design:** git blame on the flagged site shows it was
  born `catch (_: Throwable)` (round 351) in the era of inline
  `StackOverflowError` guards, and the 2026-07-04 sweep (3b950156) narrowed all
  135 such sites to `Exception` **mechanically** — so this guard no longer catches
  the thing it was written for (SOE is an `Error`), and no named exception is
  documented for what it wraps. That sweep's own CLAUDE.md entry says removing the
  catches ENTIRELY is "a separate, per-site root-cause effort — do not do it
  blind"; this item IS that effort, done in gated batches rather than blind.
  **Method** (repeat per batch, one commit each): (a) pick a batch whose guarded
  expression is a small, near-total helper — start with the `getApparentType` /
  `getPropertyOfType` cluster the owner pointed at; (b) DELETE the try/catch,
  keeping the expression; (c) gate with the full corpus suite **plus `--listAll`
  ×8** (a swallowed exception's default can be corpus-invisible but profile-live);
  (d) classify each site by the result — **byte-identical ⇒ dead residue, delete
  it; now crashes ⇒ a real modelling bug**, so file it as its own queue item with
  the stack trace and RESTORE the catch for that site only, with a comment naming
  the exception it actually absorbs. **Record the ledger** (sites removed / bugs
  found per batch) in the session note; a batch that finds a bug has paid for
  itself even if the catch goes back. **Do NOT** blanket-remove, and do NOT
  re-widen any of these to `Throwable` — the `Exception` narrowing is what lets an
  `Error` reach the init boundary guard (→ TS2589) instead of becoming wrong
  output. Expect this to run over several rounds; ~200 sites is the population,
  not the target for one session.
  **Batch ledger.** *(1) round 685 — `getApparentType`/`getPropertyOfType`, 30
  sites removed, 0 restored, byte-identical on corpus + `--listAll` ×8 ⇒ all dead
  residue; 1 bug found and fixed (unguarded type-param constraint recursion →
  stack overflow on `<T extends U, U extends T>`). Checker.kt 198 → 168.
  (2) round 685 — `getTypeOfSymbol` (16) / `resolveStructuredTypeMembers` (6), 22
  removed, 0 restored, 0 bugs; byte-identical the same way. Checker.kt 168 → 146.
  These two are deep resolvers, but each already carries the guard the catches
  stood in for — a per-symbol in-progress sentinel (B202.1) and the heritage cycle
  guard — so the catches were a redundant outer layer.
  (3) round 685 — `getTypeFromTypeNode` (39), 0 restored, 0 bugs. Checker.kt
  146 → 107. Its B202.2 sentinel covers only the CACHEABLE path, so the pins drive
  the cache-BYPASSING contexts (type-param scope / inference namespace / alias
  args), where the alias depth bail is the protection instead.
  (4) round 685 — `getTypeOfExpression` (40), 0 restored, 0 bugs. Checker.kt
  107 → 67. No sentinel and none needed: it is a kind dispatcher over a finite
  acyclic tree, delegating to guarded resolvers and iterative walkers, so only a
  DECLARATION cycle can recurse — which the pins drive.
  (5) round 685 — the SINGLE-LINE tail (34: relation engine, type printer, alias
  and heritage resolution, widening, `getTypeOfIdentifier`, singletons), 0
  restored, 0 bugs. Checker.kt 67 → **33** (commonMain 218 → 53 over five
  batches). **One site KEPT by judgement, with a comment**: the `Parser(...)` in
  `resolveRequireModuleShape`, whose input is arbitrary external `.json` file
  content — "the corpus did not crash" is weaker evidence for an unbounded
  external input than for a compiler-internal path.*
  (6) round 685 — the 28 MULTI-LINE blocks, hand-spliced (one exact
  whole-construct swap per site with an asserted occurrence count, because a
  scripted multi-line rewrite is the documented mangle hazard); ten collapsed to
  something simpler than the original. Checker.kt 33 → **3**.*
  **CLOSING VERDICT.** Checker.kt's 197: **193 removed, 0 restored, 3 kept** —
  the SOE boundary guard (load-bearing per the SOE doctrine), the `FriBail`
  control-flow catch (never defensive), and the `Parser(...)` on external `.json`
  content (kept on the evidence asymmetry). **One bug found and fixed.**
  **The 20 sites outside Checker.kt are NOT the same population and were left
  alone deliberately** — audited this round: Vfs's 3 are filesystem I/O (a missing
  or unreadable file must yield null, not crash); Parser's 2 guard parsing of
  externally-sourced JSDoc type text; TsBuildInfo / TsConfigLoader /
  ModuleResolver name their exception (`SerializationException`,
  `IllegalArgumentException`) over external JSON; Transformer's one names
  `NumberFormatException`; Emitter's and Flow's "catch" greps are comments and
  emit-helper source strings. Every one either guards an external input or names
  what it absorbs — which is exactly what the residue did not do. The item's
  premise ("~200 sites, all the same shape") holds for Checker.kt alone.*
  **Method addendum from batch 1:** write the batch's corner-case pins FIRST and
  run them against unmodified HEAD — the pins, not the removal, are what find
  bugs, and the HEAD run tells you whether a failure is pre-existing or yours.
  **Rule of thumb from batch 2:** grep the guarded helper for its OWN cycle
  guard / in-progress sentinel first; where one exists the call-site catch is
  redundant by construction and the batch is very likely byte-neutral.
  Next up: the two deep resolvers `getTypeFromTypeNode` (39) and
  `getTypeOfExpression` (41) in small slices, plus the ~30-site singleton tail.

**PERF — the post-inversion performance arc (owner-approved 2026-07-20, round 618:
"proceed according to your recommendations"; measurements + rationale in the
round-618 session note and the rewritten docs/ARCHITECTURE-RETHINK.md § 6). Ground
rules: the INV rules unchanged, PLUS wall-clock claims are decided ONLY by
interleaved A/B medians — anything priced below the ±2% drift band folds into a
structural item instead of landing alone.**

**ROUND-716 RE-SCOPE (owner: "do anything needed … we are free to completely
redesign this project, if the performance gain is on the horizon"). The arc's
diagnosis was wrong and is corrected in docs/ARCHITECTURE-RETHINK.md § 0 — READ IT
FIRST. Headline: the type system is 5.0 s of an 18 s compile (28%); the dispatch
and handler machinery is ~7.6 s (42%); the entire context-cache prize INV.5(c)
exists for is 68 ms. Work (DISPATCH.1) before any further cache/identity work.**

**WORK ORDER (round 716, after the owner's four decisions). The protocol says
top-to-bottom, and the order below IS deliberate — read this before picking:**
**(PARITY.1)** and **(COST.1)** first: both are cheap, and they are what make the
rest safe — PARITY.1 removes the byte-gate veto that priced out general-engine work
(and unblocks LIB.1's ~30 baselines), COST.1 stops the campaign silently
re-accumulating the very overhead it exists to remove. Then **(LIB.1)**, the
silent-wrong-answer fix the owner asked for. Then **(DISPATCH.1)**, the measured perf
lever and the prerequisite for reviving the M0.4 tail migration. **(PERF.HW)** is
opportunistic — run it only with spare budget; it must not preempt DISPATCH.1.

- [x] **(PARITY.1) DONE round 717 — the policy is now a MECHANISM, not a habit.**
  (a) `docs/logical-parity.md`: the owner directive, the form-vs-meaning decision
  procedure as two ALLOWLIST tables (7 meaning axes / 6 form axes, each form axis
  carrying its equivalence obligation; anything in neither table is MEANING by
  default), the four-step per-case procedure, and the generated ledger. (b) The
  mechanism: a `LogicalParityDivergence(baseline, round, pinnedBy, reason)` in
  build.gradle.kts's `logicalParityDivergences` is the SINGLE source of truth — the
  generator emits that subtest `@kotlin.test.Ignore`d with the reason inline (so it
  stays VISIBLE as skipped: a silently-dropped test cannot hide behind an unchanged
  total), rewrites the ledger region in the doc, and FAILS the build on either of the
  two rot modes — a baseline matching no generated test, or a `pinnedBy` class that
  does not exist under src/commonTest. Keyed by baseline FILE name because that is
  exactly one generated subtest (bare/parameterized × errors/emit), all four emission
  sites wired. Self-tested all three paths (valid entry → `@Ignore` + ledger row;
  stale baseline → build fails; missing `pinnedBy` → build fails), then reverted to
  the empty list, which is the healthy state. **Gate: with no entries the generated
  corpus is BYTE-IDENTICAL** (diff -r of the whole generated tree, before vs after),
  so the mechanism costs nothing until used; suite 12,765/0/3 unchanged. (c) is a
  STANDING rule rather than a deliverable, and is written into the doc § 1: every
  "DEAD — regressed N tests" entry in CLAUDE.md and the archive is now a LEAD, and
  re-examining one means re-running the change and classifying its N diffs.
  **The judgement worth keeping:** a form-only diff is a *candidate*, not an
  entitlement — the owner's cost clause ("byte parity is secondary *if it can be
  achieved without extra cost*") means byte parity is still preferred where it is
  free, so a divergence needs a reason it is WORTH having.
- [ ] ~~(PARITY.1) Adopt the logical-parity policy in the gate (original)~~ —
  **owner directive 2026-07-26, and the single biggest unblock in this arc.** "Logical parity is
  important even if we don't reach byte-by-byte parity. If there are tests where we
  diverge but the logic stays the same, create a new test case and switch off the old
  one. The logical value of the compiler output at maximal performance should always
  be the deciding factor; byte-by-byte parity is secondary if it can be achieved
  without extra cost." **What this changes:** a corpus baseline that differs only in
  FORM (union member order, an equivalent message, an equivalent elaboration shape)
  stops being a veto — replace it with a test pinning the LOGIC and disable the old
  one, recording the divergence and why it is equivalent. A baseline differing in
  MEANING is still a hard regression. **Do (a) FIRST, it is cheap and it is what
  makes the rest safe:** (a) add `docs/logical-parity.md` — the form-vs-meaning
  decision procedure, the disable mechanism, and a running LEDGER of every
  switched-off baseline with its justification (an unlogged disable is
  indistinguishable from hiding a regression, so the ledger IS the control); (b)
  extend the generator/harness so a case can be marked logically-divergent with a
  reason string rather than commented out; (c) as engine work proceeds, re-examine
  the "DEAD — regressed N tests" entries in CLAUDE.md and the archive — many were
  never checked for whether the N were form or meaning, so each is now a LEAD.
  **Do NOT** use this to wave through a diff you have not read: the burden is
  demonstrating equivalence per case, in the note.

- [x] **(COST.1) DONE round 717 — `scripts/cost_gate.py`, and the determinism check
  caught a racy counter on its first use.** Runs the compiler profile with
  `--passTiming`, extracts 20 deterministic counters, diffs them against the tracked
  `docs/perf/cost-counters.txt`, fails above ±2% (per-counter), and exits nonzero so it
  drops into the round gate next to the suite. `--update` rebaselines, `--from-log`
  re-parses an existing run (free re-scoring, and how the rebaseline below was done),
  `--tolerance` tunes the bar. Coverage: the front end (pre-parse reuse), the spine
  (nodes walked), the type system (getTypeOfExpression calls/distinct/outside-init,
  narrowing walks, memo serves), type-node resolution (cacheable/hits/bypassed, the
  INV.5(c) mapped cache, fingerprint builds), name resolution (globals
  lookups/conflated/misses) — **plus the compiler's ANSWER (error count, program file
  count), because a cost drop that changes the output is not a win and the gate has
  to be able to see that.** Four counters baseline at ZERO
  (`ctxFingerprint.builds`, `globals.conflated`, `narrow.walksOutsideInit`,
  `preparse.fresh`) and are therefore tripwires: any nonzero value is flagged.
  **Baseline at 41bedb73:** errors 46, spine.nodes 856,962, typeOfExpr 696,933 calls
  over 250,057 distinct nodes, narrowWalks 69,903 (40,546 memo-served), typeNode
  210,397 cacheable / 89,883 bypassed, globals 1,377,511 lookups at 98.9% miss.
  **THE FINDING — the AST census is racy, and it is exactly what (DISPATCH.1) was
  told to derive its table from.** Two runs of the same binary: every counter
  bit-identical EXCEPT the `indexSourceFile` node census, 857,350 vs 854,550
  (−0.33%). `indexSourceFile` runs on the crawl's concurrent parse threads
  (`readAndScanBatch`, Dispatchers.Default, FRONTEND_CONCURRENCY in flight) and
  `PassTiming.nodeKindHistogram` is a plain HashMap, so increments are lost and the
  census always undercounts. Instrumentation-only (no production impact), but it
  means the census is sound for "which kinds dominate" and NOT sound as an exact
  per-kind population. Excluded from the gate (a nondeterministic row teaches people
  to ignore the gate) and warned about at the source in PassTiming.kt; DISPATCH.1's
  derivation needs an exact census — see the note on that item.
- [ ] ~~(COST.1) Enforce the cost gate (original)~~ — **owner-approved 2026-07-26
  ("yes, I want to enforce it, to counter performance regressions").** Round 713 added ~72k
  `getTypeOfExpression` calls (+11.5%, ≈70–200 ms) for one conformance diagnostic and
  nothing noticed, because the round gates are the corpus and `--listAll` and neither
  sees cost. Over 200 rounds that is how ~118 handler consultations per node
  accumulate. **Make it mechanical, not a habit:** a script that runs the compiler
  profile with `--passTiming`, extracts the DETERMINISTIC counters
  (`getTypeOfExpression` calls, `narrowWalks`, `spineNodes`, per-kind enter totals,
  `typeNodeCacheable`/`bypassed`), writes them to a tracked file, and DIFFS against
  the committed baseline — failing loudly above a threshold (start ±2%, tune once
  there is history). Counters, not wall time: they are load-independent, which is the
  whole point (a laptop shows ±13% wall). Wire it into the round protocol next to the
  suite run, and record the baseline in the same commit as any accepted increase,
  with the justification.

- [x] **(BUILD.1) DONE round 720 — owner approved the heap raise; the settled figure is
  5g for the Kotlin daemon plus a 2g→1g cut to Gradle's own, and the from-scratch
  compile now completes in ~6.5 min.** The measured ladder: 2g GC-thrashes forever
  (looks exactly like a hang — zero class files, because Kotlin writes output only at
  the end), 3g dies mid-compile, 4g fails in ~6 min with an explicit `Not enough memory
  to run compilation`, 5g succeeds. Gradle's daemon had to shrink because 5g + 2g
  oversubscribed the 7.7 GB box and the kernel killed the compile daemon mid-run with
  nothing in any log. **Both earlier diagnoses were wrong and are corrected in place:**
  round 719 blamed a local bench loop (those commits are `github-actions[bot]`, remote),
  and rounds 718–719 read "40-minute compiles" that were really ~6-minute ones being
  restarted — the agent's own polling was preempting its builds, so the sleeps never ran.
  Launch long builds DETACHED (`nohup … &`) and wait on ONE timer. Original item below.
- [ ] ~~(BUILD.1) BLOCKED-PENDING-USER: raise the Kotlin daemon heap (original)~~ — **a cold compile
  does not fit in the inherited `-Xmx2g` and HANGS instead of failing.** Measured
  round 717, and it cost that round ~30 minutes. `gradle.properties` sets
  `org.gradle.jvmargs=-Xmx2g`, which the Kotlin compile daemon inherits. An
  INCREMENTAL compile fits; a COLD one does not — and the failure mode is not an
  OutOfMemoryError, it is a GC death spiral that looks exactly like a hang: 350% CPU,
  RSS pinned at the ceiling, `stime` ~5 s against 3,000 s of user time, and **zero
  class files** (Kotlin's backend writes output only at the end, so there is no
  partial progress to read). It ran 14 minutes with no progress; the same build with
  `-Dkotlin.daemon.jvmargs=-Xmx3g` took **2m 33s**. **How you get there:** the
  documented memory ritual before a self-compile — `./gradlew --stop && pkill -9 -f
  KotlinCompileDaemon` — is what makes the next build cold, so the trap is reachable
  from the instructions themselves. **ESCALATED round 718 — this is now the binding
  constraint on the edit-test loop, not a nuisance.** That round diagnosed and wrote a
  complete fix and then could NOT LAND IT, because no compile would finish: `-Xmx2g`
  hung (14 min, zero classes), `-Xmx3g` ran 16 minutes and the daemon died and
  restarted from scratch, and only `-Xmx4g` got the main compile through. Four cold
  compiles were burned in one session. The work is parked on
  `wip/round718-required-minus-optional` purely for want of a gate.
  **REFINED round 719 — the distinction that actually matters is INCREMENTAL vs
  FROM-SCRATCH, not the heap number.** A retry at 4 g on a quiet box sat in
  `compileKotlinJvm` for 40+ minutes with the daemon showing REAL WORK (same PID,
  utime 210 s → 277 s over 2.5 min, RSS 2.38 GB under a 4 GB ceiling, stime ~1.5 s) —
  not the round-717 pinned-at-ceiling spiral. Round 717's **2m 33s** figure for "the
  same cold compile" is NOT comparable: Gradle re-executing the task does not force a
  non-incremental Kotlin compile, so that one ran against warm caches. A genuinely
  from-scratch compile of ~110k lines of Checker.kt plus a 566 KB generated lib file
  simply costs tens of minutes here. (An earlier version of this entry blamed a
  competing local bench loop; that was wrong — the `chore(bench)` commits are authored
  by `github-actions[bot]` and run in remote CI.)
  **So the operational rule comes first, and it is free:** do NOT hard-kill the Kotlin
  daemon (`pkill -9`), because that is what converts a 2-minute incremental compile
  into a 40-minute from-scratch one — the documented memory ritual is the trap.
  **PROPOSAL (owner decision, build-system change = Guardrail):** add
  `kotlin.daemon.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m` to gradle.properties, so
  that when a from-scratch compile IS unavoidable it does not thrash at 2 g. Cost: up
  to 2 GB more resident during a compile on a 7.7 GB box, which means a compile and a
  4 g self-compile can no longer overlap. Cost: up to
  2 GB more resident during a compile on a 7.7 GB box — which means a compile and a
  4 g self-compile can no longer overlap, and the memory ritual becomes mandatory
  BEFORE a bench run rather than before a build. That trade is worth stating plainly:
  today the ritual is what CAUSES the hang, and a round can lose an hour to it.
  **A second option worth the owner's consideration:** a bigger box. This one is
  7.7 GB / ~4 cores, the corpus suite takes 7 minutes, a cold compile 3–15, and
  (PERF.HW) already wants ≥8 real cores to answer the parallel-scaling question.
  Workaround until then, recorded in CLAUDE.md: pass
  `-Dkotlin.daemon.jvmargs=-Xmx4g` on the command line.

- [ ] **(LIB.1) Ship the DOM/webworker libs and stop real builds silently running
  UNCHECKED — owner-approved 2026-07-26 ("yes, please fix it"), PROMOTED out of the
  post-v1 backlog because it is a silent wrong answer, not a missing feature.**
  **(a) IS DONE (round 730): the flip LANDED and it is FREE.** `projectDefaults()`
  (CompilerOptions.kt) now starts both project entry points — `TsConfigLoader.load`
  and `ProjectCompiler.build`'s bare-source-file path — with `useRealLibs = true`;
  the CONSTRUCTOR default stays false so the corpus path is untouched, and a
  tsconfig may still opt out with `"useRealLibs": false`. Gated by a four-arm ×
  8-profile measurement (the table is in the round-730 session note): the embedded
  and real arms are IDENTICAL code for code (46/46/46/46/46/46/46/94), and under
  `types: ["node"]` the real arm is strictly BETTER (server 18 → 13, harness 48 →
  43). The +5 that measurement first found on services/server/harness were ONE
  lib-free defect — a `this` pseudo-parameter counted into `minArgumentCount` while
  dropped from `parameters`, so a `this`-carrying function type was not assignable
  to ITSELF — now fixed at all 19 signature builders.
  **(b) IS DONE (round 731): the DOM / webworker / scripthost sets are SHIPPED** —
  all 108 `src/lib` files, 566 KB → 3.71 MB, plus the `*.generated` →
  distributed-name mapping in `keyToDistFileName`. Proven by MEMBER probes
  (`RealLibsDomTest`: unknown member reported, member type honoured, method arity
  enforced, on all three host sets), every target verified failing on unmodified
  HEAD. **The cost was in the BUILD, not the compiler:** the payload is 3.14 MB
  (not the ~1 MB estimated; `dom.generated` alone is 2.35 MB, 66% MDN comments)
  and emitting it as one generated file OOMs the Kotlin daemon at the BUILD.1 5 GB
  pin after 7m34s — so the emission is split over 16 `RealLibFilesPart*.kt`, after
  which a COLD compile takes 2m25s, i.e. CHEAPER than the old single-file shape at
  a sixth of the payload. Dashboard unmoved (profiles pin `"lib": ["es2020"]`),
  corpus unmoved (still the embedded lib), cost gate +0.00% on every counter.
  **(c)'s zero-risk half landed here too:** `RealLibResolverTest` now pins that
  every `libMap` name and every `ScriptTarget` default resolves with `unavailable`
  AND `unknownNames` empty, and that every distributed lib file name round-trips
  through `distFileNameToKey`/`keyToDistFileName` unchanged (which is what the
  `lib.dom.generated.d.ts` bug above would have tripped).
  **REMAINING: (c)'s user-facing half — and it SHRANK.** With everything shipped,
  `Resolution.unavailable` is EMPTY for every resolution (pinned), so the
  "requested but unshipped" case (c) was written for no longer exists — a
  non-empty `unavailable` can now only mean a pin bump outran the generator, which
  argues for a build error, not a user diagnostic. What is left for the user is
  `Resolution.unknownNames` (a `lib` entry not in `libMap` at all — **zero
  consumers today**; tsc reports TS6046). **Its corpus risk depends entirely on
  where it is emitted, and that is (c)'s real design decision:** the corpus runs
  the EMBEDDED lib and never consults `RealLibResolver`, so a diagnostic raised
  from the real-lib resolution path moves ZERO baselines, while one raised from a
  raw `options.lib` × `libMap` check reaches all 259 `@lib:` cases. See the
  round-731 note.
  THE DEFECT (measured rounds 687–688, FIXED in (b) above): `RealLibFiles` shipped no
  `dom.generated`/`dom.iterable.generated`/`webworker*`, so `"lib": ["dom"]` records
  the file in `Resolution.unavailable`, which **nothing outside RealLibs.kt ever
  consumes** — no diagnostic, no failure. Consequence on a 3-line program:
  `HTMLElement` resolves, `document` resolves, and `e.definitelyNotAMember` on an
  `HTMLElement` parameter **compiles CLEAN**. A browser project gets a green build
  with its DOM code entirely untyped. **Worse, and the real root:** `useRealLibs`
  defaults FALSE and NOTHING in the project path sets it (`ProjectCompiler` /
  `TsConfigLoader` never do; the only writer is a test directive), so **every real
  build — all 8 dashboard profiles included — runs on the curated embedded
  `BUILTIN_LIB_SOURCE`** and the whole real-lib machinery is test-only. The owner has
  now authorised the generation change the round-688 note left owner-gated.
  **(a) IS NOW MEASURED (round 717) — the answer is affordable, and the number is 35.**
  No code was needed: every `compilerOptions` key flows through `applyDirective`, so
  `"useRealLibs": true` in the bench project's tsconfig flips the whole real-lib path
  on. Four arms of the `compiler` profile, `--noEmit --listAll`:
  | libs | `types` | errors | composition |
  |---|---|---:|---|
  | embedded | `[]` | 46 | the dashboard number — ALL env-legit |
  | real | `[]` | 81 | +33 node globals (`process`/`global`), +35 real |
  | real | `["node"]` | 48 | 13 env + **35 real** |
  | embedded | `["node"]` | **13** | 13 env (TS2591 only), nothing else |
  So the real-lib switch costs **exactly 35 checker FPs** — TS2722 ×11 ("Cannot
  invoke an object which is possibly 'undefined'" — a narrowing gap on lib members
  the curated lib declared non-optional), TS2322 ×8, TS2345 ×4, TS2344 ×4, TS2339 ×4,
  TS2349 ×2, TS2769, TS2739 — and **no measurable wall time** (28.7 s, inside the
  band). Two corollaries worth having: (i) today's "46 FPs, env-legit only" is
  13 stub-residue + 33 node globals, confirmed by arm D collapsing to 13; (ii) the
  embedded lib is quietly MORE PERMISSIVE than the real one, which is what makes the
  silent-unchecked defect possible in the first place.
  **DECISION for (a), on that evidence: a real project build should use the REAL
  libs** — the mismatch is the root defect, the cost is bounded and enumerable rather
  than open-ended, and it buys faithfulness on every future project. Sequencing:
  burn the 35 down FIRST (they are ordinary FP work, TS2722 being over a third of
  them and probably one narrowing shape), THEN flip the default, so the dashboard
  never goes red. Re-measure services/server/harness before flipping — this is the
  `compiler` profile only, and the bigger profiles will have their own deltas.
  Raw logs: the four arms were run at 8100a78e; reproduce by adding
  `"useRealLibs": true` to `build/bench/tsc-project-*/tsconfig.json`.
  **(a1) LANDED round 720 — 11 of the 35 gone; real-lib FPs 35 → 24, and the fix is
  FREE (every cost counter unchanged). Corpus 12,765 → 12,770 / 0 / 3 (+5 pins), the
  embedded-lib profile still 46.** THE DEFECT: `Parser.kt`'s mapped-type modifier
  scan records `-?` as a plain `?`, so `Required<T> = { [P in keyof T]-?: T[P] }`
  behaves exactly like `Partial<T>` — inverted. `-readonly` got its own flag in M1.10;
  the `?` analogue was never done. THE MECHANISM IS NOT THE OBVIOUS ONE: TS2722 does
  not look at the member TYPE for `| undefined` (the codebase deliberately never adds
  it — the emitter says so), it gates on `isOptionalProperty(propSym)`, and a
  homomorphic mapped member CARRIES ITS SOURCE DECLARATION for related info, so the
  source's `?` is what it sees. The fix mirrors M1.10 exactly: a
  `mappedRequiredMemberIds` side-channel, probed in `isOptionalProperty` only when the
  declaration says optional (preserving the documented hot-path property).
  **Two dead ends worth not repeating, both caught by CONTROLS:** hand-rolling the
  mapped types locally (`type MyRequired<T> = { [P in keyof T]-?: T[P] }`) does not
  reproduce anything — we emit NOTHING for user-defined mapped types, so the controls
  came back empty and the target assertions passed vacuously; and asserting
  assignability through `Partial` measures an axis we do not model. The live repro
  needs `@useRealLibs` plus TS2722 assertions. Verified against unmodified HEAD: the
  target fails, the control passes.
  **Also learned:** the embedded lib declares NO utility types at all, which is why
  the whole family is invisible on the default path — `Required<…>` is an unresolved
  name degrading to `any`, and `any` is silent. That is the LIB.1 defect in miniature.
  **(a2) NEGATIVE RESULT round 721 — do NOT re-run this one.** The three TS2322 whose
  source and target print identically (`NodeArray<T>` → `NodeArray<T>`;
  `WatchCompilerHostOfFilesAndCompilerOptions<T>` → a union it is the first member of)
  are NOT a generic-self-assignability bug: five probes covering self-return,
  constrained self-return, member-of-union and return-through-a-local all pass, with a
  live control. Pinned as `GenericSelfAssignabilityTest`. **The surviving lead is
  symbol-keyed members**, because these FPs exist only under real libs and the same run
  reports TS2739 "missing `[Symbol.iterator]`, `[Symbol.toStringTag]` from `Set<T>`" —
  and `NodeArray<T> extends ReadonlyArray<T>`, which carries exactly those in the real
  lib but not in the curated one. Probe that under `@useRealLibs`.
  **(a3) LANDED round 723 — computed well-known-symbol keys in an object literal are
  members now; real-lib FPs 24 → 22.** `getTypeOfObjectLiteral` named a computed key via
  `computedLiteralKey(n) ?: continue`, which accepts only string/numeric literals — so
  `[Symbol.iterator]: …` was DROPPED and the target then reported it missing. New
  `computedSymbolKey` names a DOTTED path (`Symbol.iterator`) as `[Symbol.iterator]`,
  matching how symbol members are named everywhere else (it is what TS2739 prints);
  wired into BOTH the PropertyAssignment and MethodDeclaration branches — the method
  form did not handle computed names at all. Deliberately NOT applied to a bare `[foo]`
  dynamic key, since naming that would let any literal satisfy a symbol-keyed target —
  pinned by its own control. **It fixed one MORE than predicted:** TS2739 ×1 → 0 AND
  TS2322 8 → 7, so a dropped member was failing an assignability check too. Corpus
  12,775 → 12,781 / 0 / 3. **COST.1 tripped and was accepted:** `mapped.keyed` +3.12%,
  `mapped.hits` +6.66% (+744 keyed lookups, +372 hits, ≈1.6 ms) — the direct price of
  typing members we previously discarded, and the hits rose faster than the keys, so the
  added lookups are mostly the cheap kind. Rebaselined in the landing commit.
  **(a5) LANDED round 725 — the TS2344 family is gone; real-lib FPs 22 → 18 (arm C
  35 → 31, both sides MEASURED), and the fix is FREE (all 20 cost counters +0.00%).
  Corpus 12,781 → 12,788 / 0 / 3 (+7 pins), embedded-lib profile still 46.** THE DEFECT:
  the real lib declares `NonNullable<T> = T & {}`, so a `Visitor<NonNullable<TIn>, …>`
  type argument resolves to `Intersection[TypeParam(TIn), {}]` — and
  `checkConstraintsForTypeArgs` applies the constraint chain only to a BARE
  `Type.TypeParam` arg (and to a `Type.Union` arg since 440-b), never through an
  intersection. Round 724's instrumentation-free hypothesis ("we compare against the RAW
  constraint `Node | undefined` and just need to strip the nullish part") is DISPROVEN by
  the case where TIn is already non-null: it failed identically, because the constituent's
  constraint is not consulted AT ALL. The relation cannot cover it either — the engine has
  no TypeParam-source-via-constraint rule on purpose (round 456 measured adding one as
  net-zero and reverted), so this is the THIRD arm of a per-emission-site rule that
  already had two. THE FIX: `intersectionSatisfiesViaTypeParamConstraint`, evaluated only
  inside the already-failing branch (hence zero cost), takes each TypeParam constituent's
  constraint (`T ⊆ constraint(T)`) and compares it; a SEPARATELY GATED second step drops
  the constraint's nullish members when an `{}` constituent is present, since `X & {}` is
  tsc's non-nullish marker. Non-TypeParam constituents are left to `checkTypeRelatedTo`,
  which already does "some constituent relates" plus its merged-contradiction guard.
  **THE TRAP, pinned by four negative controls:** the blanket
  `argType is Type.Intersection -> continue` passes every target case and silently deletes
  a real diagnostic class — controls cover an intersection with no type parameter
  (`A & B`), `NonNullable` of an unconstrained parameter, `NonNullable` of a parameter
  constrained to an unrelated type, and a nullable-constrained parameter used WITHOUT
  `NonNullable` (which proves the strip is tied to the `& {}` marker, not applied to every
  constraint). The four fixed sites are parser.ts:3491/3492 and visitorPublic.ts:124/144.
  **REMAINING 18, by code:** TS2322 ×7, TS2345 ×4, TS2339 ×4, TS2349 ×2, TS2769 ×1 —
  the TS2322 group is the one with two eliminated hypotheses already ((a2), (a4)); per
  that note, dump the actual relation failure rather than guess a third time.
  **(a6) LANDED round 726 — the TS2322 family is mostly gone; real-lib FPs 18 → 14 (arm C
  31 → 27, both sides MEASURED, TS2322 ×7 → ×3, no other code moved). Corpus 12,788 →
  12,795 / 0 / 3 (+7 pins), cost gate PASSES (largest counter −0.68%, an improvement),
  embedded-lib profile still 46.** THE DEFECT, found by DUMPING the relation as the note
  above demanded: `getTypeFromTypeReference` built a `Type.Reference` only when EVERY type
  argument resolved, and otherwise returned `getDeclaredTypeOfSymbol(Iface)` — the RAW OPEN
  GENERIC, which carries its own type parameter and relates to no `Type.Reference`. A
  return annotation is resolved with `currentTypeParamScope == null`, so the function's own
  `T` came back errorType and the annotation silently became the open generic — hence the
  identical-looking `NodeArray<T>` → `NodeArray<T>`: the display renders the ANNOTATION,
  the comparison used the RAW GENERIC. THE FIX mirrors tsc (whose `errorType` is
  Any-flagged and instantiates regardless): substitute `any` for unresolved argument
  positions and instantiate anyway; TS2304 already reports the name. Sites: parser.ts:3583,
  watchPublic.ts:371/383, utilities.ts:12378. **THE PROBE TRAP, and why (a2)/(a4) missed
  it:** the degradation is invisible unless the interface reaches its TP through a GENERIC
  BASE (`extends ReadonlyArray<T>`); a flat `interface Box<T> { v: T }` relates to its own
  raw form anyway, so a pin built on one is silent before AND after — verified by running
  the pin against unmodified HEAD and getting byte-identical output. Three negative
  controls guard the fix (a resolvable-but-wrong argument still errors, an unresolvable one
  masks neither an unrelated source nor missing members). **REMAINING 14:** TS2345 ×4,
  TS2339 ×4, TS2322 ×3, TS2349 ×2, TS2769 ×1 — the three TS2322 are three DISTINCT causes
  (parser.ts:3558 an `Intersection[TP & {}]` type ARGUMENT, utilities.ts:4258 an `Exclude<…>`
  conditional, program.ts:1366 an object literal vs `Partial<…>`), so pick the next family
  by cause, not by histogram height.
  **(a7) LANDED round 727 — the TS2339 family is gone; real-lib FPs 14 → 10 (arm C 27 →
  23, both sides MEASURED, TS2339 ×4 → 0, no other code moved). Corpus 12,795 → 12,799 /
  0 / 3 (+4 pins), cost gate PASSES (largest counter −0.68%, an improvement),
  embedded-lib profile still 46.** THE DEFECT: `narrowByCallPredicate`'s SINGLE-TYPE
  positive branch compared the reference against the guard target AS A WHOLE
  (`candidate <: t ? candidate : t <: candidate ? t : candidate`) — so when the target is
  a UNION and neither whole-union relation holds, it handed back the ENTIRE candidate
  union. tsc's `getNarrowedType` never does that: it `mapType`s over the CANDIDATE,
  keeping per constituent whichever of `t`/`c` is the subtype and dropping `c` when
  neither direction relates. Live shape: esDecorators.ts `visitAssignmentElement`'s
  `isAssignmentExpression(node, true) && isNamedEvaluation(node, …)`, where
  `NamedEvaluation` is a 9-member union of unrelated `X & {…}` intersections — every
  later `node.left`/`node.right`/`node.operatorToken` resolved on that union's tiny
  common property set. THE FIX filters the candidate constituents, reached ONLY on the
  previously-`else` path (both relating branches stay byte-identical) and falling back to
  the whole union when nothing survives. Sites: esDecorators.ts:2066/2069/2070/2071.
  **(a8) LANDED round 727 (same session) — the TS2345 group too; real-lib FPs 10 → 7 (arm
  C 23 → 20, both sides MEASURED, TS2345 ×4 → ×1, no other code moved). Corpus 12,799 →
  12,804 / 0 / 3 (+5 pins), cost gate byte-identical to the (a7) run, embedded-lib profile
  still 46.** THE DEFECT: a CONSTRUCT SIGNATURE's own type parameter escaped into the
  new-expression's type. The real lib declares
  `interface SetConstructor { new <T = any>(values?: readonly T[] | null): Set<T> }`, so
  `new Set()` yielded `Set<T>` — the raw signature TP — and
  `(state.hasCalledUpdateShapeSignature ||= new Set()).add(path)` resolved `.add` on the
  UNION `Set<Path> | Set<T>`, where the B516 union-of-callables rule CORRECTLY intersects
  the two parameters into `Path & T`. The combining rule was never the bug; the leaked TP
  was. THE FIX substitutes an uninferred signature TP's DECLARED DEFAULT (what TypeScript
  specifies), only for TPs that HAVE one and only at the return reference's top level — a
  defaultless TP keeps today's behaviour. This is the construct-signature analogue of the
  B56.1 rule already applied to a generic CLASS callee, which cannot fire here because a
  constructor interface carries no type parameters of its own. Sites: builderState.ts:396/457,
  resolutionCache.ts:1109. **WHY IT IS LOW-RISK ON THE CORPUS:** the EMBEDDED lib declares
  `SetConstructor { new(): Set<any> }` — non-generic — so no corpus baseline can move
  through `Set`/`Map` at all; the pins therefore declare their own constructor interface.
  **REMAINING 7:** TS2322 ×3, TS2349 ×2, TS2769 ×1, TS2345 ×1 — the last TS2345 is
  utilities.ts:12082, a lone exhaustive-switch `assertType<never>(node)`, i.e. a
  full-switch-narrowing question with nothing in common with the three just fixed.
  **(a9)/(a10)/(a11) LANDED round 728 — THREE causes in one session; real-lib FPs 7 → 3
  (arm C 20 → 16, both sides MEASURED: TS2349 ×2 → 0, TS2769 ×1 → 0, TS2322 ×3 → ×2, no
  other code moved). Corpus 12,804 → 12,822 / 0 / 3 (+18 pins), cost gate PASSES with the
  largest counter −0.68%, embedded-lib profile still 46.** (a9) TS2349 ×2: the B516
  combining gate required every parameter to be REQUIRED, so a trailing optional
  (`forEach(cb, thisArg?)`) sent the union down the "none of those signatures are
  compatible" path; tsc's `combineSignaturesOfUnionMembers` takes the longest parameter
  list, intersects position-wise and maxes the minArgumentCount. Only three corpus
  baselines pin that message and all survive on other gates. (a10) TS2322 ×1: the mapped
  materializer never recorded the plain `?`, so `Partial<T>`'s member of a required source
  property stayed required — round 718's `-?` defect in the mirror, marked by a
  `SymbolFlags` BIT because that arm is the hot one. (a11) TS2769 ×1: an object literal's
  string-literal property widens to `string`, and overload resolution compares each
  candidate against the RAW argument type while the single-signature path contextually
  types it — the `overloadingOnConstants2` rule now has a per-property analogue, evaluated
  only inside the already-failing branch.
  **(a12)/(a13) LANDED round 729 — THE BURN-DOWN IS FINISHED: real-lib FPs 3 → 0 (arm C
  16 → 13, both sides MEASURED; the 13 that remain are ALL env-legit TS2591). Corpus
  12,822 → 12,842 / 0 / 3 (+20 pins), cost gate PASSES UNCHANGED TO THE DIGIT at every
  step, embedded-lib profile still 46.**
  (a12) parser.ts:3558 — **round 728's TYPE-PARAMETER NAME COLLISION reading is
  DISPROVEN.** One dump at the inference-time constraint check shows both arms of that
  four-case matrix failing IDENTICALLY (`Isect[TP(T)#38[c=NodeX], {m:1}]` vs `NodeX`,
  under either name); the renamed variant was only ACCIDENTALLY clean, its equally
  un-inferred return type swallowed downstream. The cause is round 725's rule at a THIRD
  site: `tryInferSingleTypeParamFromArgs` asked "does the inferred candidate satisfy the
  constraint" with a bare `checkTypeRelatedTo`, and on failure bails WHOLESALE — so the
  callee's return type comes back UN-INSTANTIATED and the mismatch surfaces as a TS2322
  whose two sides print nearly the same text. That display is what misled two rounds.
  (a13) utilities.ts:4258 AND utilities.ts:12082, ONE cause — **`Exclude<T, U>` was an
  IDENTITY FUNCTION.** The distribution loop evaluated each constituent's branch under the
  UNSHIFTED alias-argument map, so `T` in a branch still meant the whole union and every
  non-matching constituent handed it all back. The `assertType<never>` site round 728
  rated least tractable and failed to reproduce twice was never a narrowing question:
  `HasInferredType` is built with `Exclude`. Only a NAKED type parameter distributes,
  which bounds the rebinding. **TWO COMPANION DEFECTS, both surfaced by the first arm-C
  measurement of that change and neither optional:** enum-member types do not discriminate
  in our relation at all, so a working `Exclude` DROPPED sibling AST interfaces
  (`Exclude<PropertyName, PrivateIdentifier>` lost `Identifier` — two brand-new FPs at
  factory/utilities.ts:1056/1061), closed by applying round 472's `.kind` DOMAIN veto
  inside the conditional evaluator; and a USER alias shadowing a lib one lost to
  `firstOrNull`, so a local `type Omit` resolved through the lib body (its own pin had
  been passing only because Exclude was inert). **The enum-member relation gap itself is
  NOT fixed — it is queued as (REL.1) at the top of the queue.**
  **ALSO LANDED round 729, not on this list:** round 725's rule now has all THREE arms —
  `checkCallTypeArgConstraints` (the EXPLICIT `f<NonNullable<U>>(…)` site round 728 found
  in passing) shares the same helper as the type-reference and inference sites.
  **(a14) LANDED round 730 — the measurement AND the flip.** The four-arm × 8-profile
  table is in the round-730 session note and is the baseline every future round compares
  against. The bigger profiles carried exactly 5 non-env diagnostics, all TS2322 and all
  ONE cause: `buildSignatureForFunctionLikeTypeNode` counted the `this` pseudo-parameter
  into `minArgumentCount` while `getParameterSymbols` dropped it from `parameters`, so
  `minArgumentCount` EXCEEDED `parameters.size` and every arity gate read the signature as
  "target provides too few arguments" — a `this`-carrying function type was not assignable
  to ITSELF (tell: the self-contradictory "Expected 3 or more, but got 3"). The same
  builder also zipped the surviving symbols POSITIONALLY against the declaration list,
  shifting every parameter type by one. Round 460 had fixed both at the function-
  DECLARATION site only; the arity rule is now one `requiredParameterCount` helper at all
  19 builders (16 were counting `this`). Invisible under the embedded lib (it declares no
  `this` parameters); under real libs it made every `Array`/`ReadonlyArray` member taking a
  `thisArg` mutually non-assignable, and through them `SortedArray<T>` vs
  `SortedReadonlyArray<T>` and `T[][]` vs `any[][]`. **NOT predicted:** the second symptom's
  chain blamed `concat(...)` — a message chain names where the structural walk STOPPED, not
  what broke. Cost gate rebaselined: `mapped.keyed` +3.81% is the price of resolving the
  real lib's mapped utility types (the embedded lib declares none), `typeNode.bypassed`
  −7.74% is an improvement, `spine.nodes`/`globals.lookups`/`output.errors` unmoved.
  **NEXT for this item: (b) and (c) below — both now unblocked by the flip.**
  **(a4) SECOND HYPOTHESIS ELIMINATED:** an interface extending `ReadonlyArray<T>` IS
  self-assignable (live control). With (a2), both explanations for the parser.ts:3583 /
  watchPublic.ts:371 TS2322 are closed — do NOT guess at generic identity a third time;
  dump the actual relation failure for one of them instead.
  **ORDER — the original (a) framing, now answered above:** (a) decide what a real
  project build uses for libs at all (the embedded lib is a curated subset; the shipped real
  libs are unreachable outside tests — that mismatch is the root, and it is a design
  choice); (b) ship the DOM/webworker/scripthost sets (changes real-lib generation in
  build.gradle.kts, ~1 MB of generated source); (c) report a user-REQUESTED lib that
  is unavailable — **`Resolution.unavailable` is NOT the right key** (a `full` default
  lib transitively references DOM/host files, so an ordinary target-default resolution
  has a non-empty `unavailable` and must stay silent); it needs a new
  `unavailableRequested` field, and a working implementation is in the round-688
  reflog. **TRAP that wasted a round:** the control "does `HTMLElement` resolve?"
  PASSES while everything is broken — when an unknown name degrades to `any`, name
  resolution proves nothing. The decisive control is a MEMBER probe
  (`e.notAMember` must error). **CORPUS IMPACT, now unblocked by (PARITY.1):** 259
  corpus cases carry `@lib:`, of which 23 request `dom` plus webworker×4 and others,
  all currently green because we silently ignore the request; reporting on the
  embedded path moves ~30 baselines. Under the byte gate that blocked this item;
  under logical parity, judge each as form-vs-meaning and re-pin.

- [x] **(DISPATCH.1) Per-kind handler dispatch table — DERIVED AND FALSIFIED
  (round 732). Steps (a) DONE; (b)/(c)/(d) CLOSED — do NOT land the dispatch as
  specified.** The table was derived by instrumentation, not guessed, and
  verified by running the WHOLE corpus suite with it applied (12,882 / 0 / 3)
  plus a byte-identical compiler-profile `--listAll`. **The measured prize is
  883 ms of an 18.5 s spine — an UPPER bound, inflated by the probe's own
  `when(h)` indirection; production-realistic is ~100-300 ms (0.3-1%), against
  the 1.0-2.5 s / 6-14% this item predicted.** The item's own falsification
  clause therefore applies: *"If a landed slice measures below the drift band
  AND the per-kind counters do not fall, the premise is wrong — say so and
  stop."* Consultations do fall (59/node -> 21.65/node, 64% removed) — and
  **64% of the consultations are 4.8% of the time.**
  **THE MECHANISM OF THE ERROR (for the next estimate):** round 716 inferred
  consultation overhead from "IDENTIFIER costs 2,746 ns/node and almost no
  handler wants an identifier". In fact 22 of the 59 handlers ACT at an
  identifier — the ones keyed on PARENT edges, FRAME-owner identity and nodeId
  REGISTRIES cannot be closed by the node's own kind, and they are also the
  expensive ones. The "skip `spineEnterNode` for bare Identifiers ->
  byte-identical" probe skipped real work the compiler profile happens not to
  need; it never measured consultation.
  **WHAT LANDED AND STAYS:** `SpineDispatch.kt` (the opt-in `--dispatchProbe` /
  `--dispatchGated` harness, behaviour-free when off), the by-id twins
  `spineEnterHandlerById`/`spineLeaveHandlerById`, `spineEnterKindDispatch`,
  the three named `spineCtaM3*Anchor` handlers, 23 `SpineDispatch.work()`
  probes, and `SpineDispatchProbeTest`. The derived table, its per-handler
  soundness justification, the OPEN list and the reproduction steps are in
  **`docs/perf/dispatch-table.md`** — read that before proposing anything
  shaped like this again.

- [x] **(SPINE.1) Attribute and shrink `cpaSpineLeave` + `ccetSpineLeave`** —
  **step (a) DONE round 733, and it FALSIFIES the item; step (b) must NOT be
  landed as specified.** The intra-handler attribution (`--spineSections`,
  compiler profile, 856,962 nodes) shows the item's premise is wrong on both
  counts. (1) These handlers are NOT "legacy-parity frame bookkeeping":
  **88.4% of their time (7,241 of 8,195 ms) is the cpa and ccet passes' OWN
  checking work** — `checkPropertyAccessInExpr` and
  `checkSingleCallExpressionTypes` inside the frame-ambient block. The ambient
  install+restore is 360 ms and the whole non-work scaffolding ~950 ms. (2)
  The named target, the ancestor climbs, is **176 ms** (`cpaM2ChainOk` 77 +
  `cpaM2StmtPosition` 8 + `ccetM3ChainOk` 91) — the 1-3 s prediction is wrong
  by 6-17x, and § 0's law forbids the memo (932 ns per climb over mean
  ancestor depth 6). The parent-kindId dispatch axis prices at <=60 ms. Full
  per-section table, the work/scaffolding split and reproduction steps:
  **`docs/perf/spine-leave-attribution.md`** — read it before proposing
  anything shaped like this again. LANDED: `SpineSections` + `--spineSections`
  (opt-in, behaviour-free when off) and `SpineSectionProbeTest`. NOT landed:
  any optimisation — every candidate measures below the 560 ms drift band of a
  28 s compile.

- [x] **(CALL.1) Attribute INSIDE `checkSingleCallExpressionTypes` — 2.9 s,
  53.6 us per CallExpression, the largest per-node cost measured anywhere in
  this compiler (round 733).** It is a **920-line straight-line function with
  18 `diagnostics.add` sites and 7 `run{}` blocks, executed in full for every
  one of the program's 52,413 call expressions** (2,931 ms over 52,413
  anchors, minus a 2.3 us ambient install). Unlike the cpa anchors — which
  walk a statement subtree, so tens of us is expected — this is per-NODE, and
  ~10% of a ~28 s compile sits in one function. **STEP (a): attribute by
  section, do not guess.** The `SpineSections` harness from round 733 is the
  model: split the function's top-level regions with a running timestamp
  (opt-in, behaviour-free when off, pinned by its own test) and separate the
  type-system calls (`getCalleeType`, `getCallSignaturesOfType`,
  `checkArgumentsAgainstSignature`, `checkTypeRelatedTo`) from the per-call
  PRE-work each emission site does before it knows whether it will fire
  (`getLineAndCharacterOfPosition` x22, `expressionTrueEnd` x16,
  `typeToString` x12, the `cjsDefaultNsShapes`-style per-call map builds).
  **EXPECTED VALUE, stated so a future round can falsify it:** if the
  never-firing emission sites' pre-work dominates, hoisting it behind a cheap
  pre-test removes **1-2 s**; if the cost is in signature resolution and
  argument relations, it is genuine type-system work and the lever is the
  relation engine (M3.1), not this function — say so and stop.
  **A CAUTION carried forward from rounds 732 and 733:** both of those rounds
  predicted a lever from a plausible reading of an aggregate and were wrong by
  5x and 6-17x respectively. Price the population BEFORE building anything;
  counters decide, `scripts/ab-interleaved.sh` medians AND win rate confirm.
  Gate: corpus suite + `--listAll` + `cost_gate.py`.
  **>>> DONE round 734. THE MEASUREMENT CHOSE BRANCH B, by 4x. <<<**
  **78% of the function is type-system work** (2,007 of 2,564 ms raw):
  `checkArgumentsAgainstSignature` **1,357 ms (53%)**, `getCalleeType`
  **474 ms (18%)**, the TS2793 impl probe 101, `checkArgumentsAgainstOverloads`
  53, `getCallSignaturesOfType` 19. **Branch A is disproved twice over.**
  STATICALLY: all 22 `getLineAndCharacterOfPosition`, all 17
  `expressionTrueEnd` and all 11 `typeToString` sites are DOWNSTREAM of the
  emission decision (16 literally inside `if (length > 0) {`) — there is no
  pre-gate work to hoist, the gates already ARE the cheap pre-test the item
  proposed adding. DYNAMICALLY: everything non-type-system totals **557 ms**,
  ~70 ms of it the probe, so the theoretical maximum prize is **~490 ms = 1.6%
  of a 30.5 s compile — inside the +-2% drift band (~610 ms), i.e. smaller than
  the noise that would have to measure it.** NOTHING was landed but the
  harness. Full derivation, the exit profile (half of all 52,413 invocations
  are discarded at the any/error bail AFTER `getCalleeType` has run; the
  240-line `signatures.isEmpty()` branch is never entered on this profile) and
  the two calibration artifacts: **`docs/perf/call-expression-attribution.md`**
  — read it before proposing anything shaped like this again. LANDED:
  `CallSections` + `--callSections` (opt-in, behaviour-free when off) and
  `CallSectionProbeTest`. Follow-on: **(CALL.2)** below.

- [ ] **(CALL.2) Attribute INSIDE `checkArgumentsAgainstSignature` — 1,357 ms
  over 22,145 calls = 61 us each, now the largest single measured cost in this
  compiler (round 734).** It is a **1,534-line function** — larger than the one
  (CALL.1) attributed — and it is 53% of `checkSingleCallExpressionTypes`,
  which is itself ~10% of the compile. **STEP (a): attribute by section, do not
  guess** — the `CallSections` harness generalises (it needs only new section
  constants), and its two calibration traps are recorded in
  `docs/perf/call-expression-attribution.md` § 2: a boundary costs ~90 ns
  measured DIFFERENTIALLY (N sections vs the same code as 1 span), while a
  back-to-back empty span reads 3x that because a `repeat` loop's back-edge
  safepoint poll — and, even unrolled, an invocation's first timestamp read —
  attracts stop-the-world attribution. **The split to price: argument TYPE
  computation vs RELATION work.** Whole-compile counters for the same run put
  `getTypeOfExpression` at 3,911 ms / 701,736 calls (recompute x2.7) and
  relations at depth 0 at only 699 ms, so the prior is that most of the 61 us
  is arg-type computation rather than `checkTypeRelatedTo` — **state that as
  the falsifiable expectation and let the measurement decide.** Secondary, from
  the same round: `getCalleeType` is 474 ms and **half its results are
  discarded** at the any/error bail three sections later (26,496 of 52,413) —
  ask whether that verdict is knowable more cheaply than by resolving; this is
  NOT a caching question (ARCHITECTURE-RETHINK § 0 closed those). **CAUTION
  carried forward from rounds 732, 733 and 734:** all three predicted a lever
  from a plausible reading of an aggregate and were wrong by 5x, 6-17x and
  >=2x. Price the population BEFORE building anything; counters decide,
  `scripts/ab-interleaved.sh` medians AND win rate confirm. Gate: corpus suite
  + `--listAll` + `cost_gate.py`.
  **>>> DONE round 735. THE PRIOR HOLDS BY 48x — AND ITS EVIDENCE MISNAMED THE
  MECHANISM. <<<** The split the item asked for: argument TYPE computation
  **924 ms** of the function's 1,624 ms raw, against **19 ms** for the whole
  `checkTypeRelatedTo`+TS2345 section (**10 ms** for the relation call itself).
  But the prior reasoned from `getTypeOfExpression`, and inside this function
  that is only **196 ms (12%)** while **flow narrowing is 600 ms (37%)** —
  9,615 walks at 62 us, of which the B469 union-argument site is 284 ms/2,339
  walks and the M3.4 site 316 ms/7,271. **So (CALL.2) does NOT reach section 0.1
  stage 3 (the `getTypeOfExpression` x2.7 recompute): this function types each
  argument exactly ONCE and makes 5.3% of the compile's calls at the
  compile-mean cost, so it is not a recompute site. It reaches stage 4.**
  Secondary answers: 86% of the narrowing walks (8,299) return the INPUT type,
  worth at most 237 ms; and only 10,146 of 38,247 loop iterations (27%) reach
  the assignability check at all, yet all 37,379 pay the full `argType`
  computation because every intervening block consumes it. NOTHING was landed
  but the harness. Full derivation, the exit profile, the three disproved
  mechanisms and the calibration mode:
  **`docs/perf/argument-check-attribution.md`**. LANDED: `ArgSections` +
  `--argSections`/`--argSectionsCoarse` (opt-in, behaviour-free when off), the
  compile-wide narrow-walk histogram in `PassTiming`, and
  `ArgSectionProbeTest`. Follow-on: **(CALL.3)** below.

- [x] **(CALL.3) Attribute INSIDE a monster narrowing walk — DONE round 736,
  and it LANDED THE ARC'S FIRST WIN: `-4.53%` median, B wins 6/6, outside the
  +-2% band by 2.3x.** Both numbers the item demanded were measured first.
  (i) A `>= 1 ms` walk arrives at **1,900 flow nodes but only 214 DISTINCT**
  ones — revisit factor **8.85 against 1.48** for a typical walk; the tail is
  the same small graph walked nine times, not a bigger graph. (ii) The
  per-arrival split: **51% of the whole narrowing population is
  `applyConditionNarrowing`** (1,412 ms / 759,784 calls / 1,858 ns), and the
  tail's arrivals are 6.3x costlier because of their MIX — `FlowCondition` 41%
  of tail arrivals vs 18% overall, `FlowBranchLabel` 22% vs 9%, cheap
  `FlowCall` pass-throughs 57% -> 19%. **THE FIX:** `NarrowFlowMemo.served`
  required `depth <= storedDepth`, costing 631,585 recomputes compile-wide
  (426,753 at `FlowCondition`); an entry now carries `hi` = the max depth its
  own subtree reached and also serves when `depth + (hi - storedDepth) <
  NARROW_MAX_DEPTH`, which is the exact condition under which a fresh
  computation cannot trip and therefore provably reproduces the value. Result:
  invocations -55%, arrivals -26% with DISTINCT UNCHANGED, the `>= 1 ms` tail
  429 -> 230 walks and its arrivals -96%. Suite **12,910 / 0 / 3**, `--listAll`
  byte-identical, four cost counters FELL and were rebaselined. LANDED:
  `NarrowSections`/`NarrowProbe` + `--narrowSections{,Coarse}` (opt-in,
  behaviour-free when off), the memo height disjunct in both walker mirrors,
  `IntKeyMapTest` height pins and `NarrowMemoDepthTest`. Full derivation, the
  soundness argument and the two priced-and-rejected candidates:
  **`docs/perf/narrow-walk-attribution.md`**. Follow-on: **(CALL.4)** below.

- [ ] **(CALL.4) `applyConditionNarrowing`'s 33,307 genuinely-narrowing calls
  at 21,708 ns each — the largest unattributed per-call number this arc has
  produced (round 736).** After (CALL.3) the function is 333,031 calls / ~1,016
  ms, of which 93% return their input unchanged for **468 ms raw (~410 ms net,
  1.3%)** and the remaining 7% carry the rest. **Do NOT re-propose the "does
  this condition mention the name" pre-test — round 736 priced it at ~410 ms,
  INSIDE the band, before the pre-test's own cost** (the identity calls are the
  CHEAP tail at 949 ns against 21,708 ns; § 0's law again, in a shape that is
  not a cache). The open question is what the 21,708 ns IS: split
  `narrowByEquality` / `narrowByInstanceOf` / `narrowByInOperator` /
  `narrowByCallPredicate` / `narrowByTruthiness` and the `getReferencePath`
  string building they all key on, using the `NarrowSections` harness (new
  section constants only). **Note the size honestly before starting: the whole
  narrowing population is now ~723 ms of genuinely-narrowing work = 2.4% of the
  compile, only just outside the band** — so a partial win here is in-band and
  the item may well end as a measurement. (The "attribute the 701,463
  `getTypeOfExpression` calls BY CALLER" leg that used to hang off this item is
  DONE — see (TYPE.1) below.) Gate: corpus suite + `--listAll` +
  `cost_gate.py` + `scripts/ab-interleaved.sh` medians AND win rate.

- [x] **(TYPE.1) Attribute the 701,463 `getTypeOfExpression` calls BY CALLER —
  DONE round 737, and it STRIKES § 0.1 stage 3.** Stage 3's mechanism
  ("several handlers independently type the same node") is **CONFIRMED and
  pervasive** — 177 initiating sites, **45.2% of the 254,069 typed nodes carry
  more than one origin** (modal three, max 17), 75.8% of calls land on them,
  and the ×2.76 factor decomposes as **2.05× cross-handler × 1.34× recursion**
  with per-caller factors of 1.00–1.11 (no handler re-types alone). **Its size
  is wrong by 3.2×**: a PERFECT per-node cache saves **823 ms (2.9%)**,
  single-visit discipline **670 ms (2.3%)**, the largest handler-pair merge
  **166 ms (0.58%)**, the SOUND memo **46 ms** — against a ±2% band of ~590 ms.
  **NOTHING LANDED**, correctly. Two corrections forced: "3,911 ms" is a DOUBLE
  COUNT (`typeOfExprNanos` charges a subtree once per nesting level; the true
  total is **2,439 ms = 8.5% of checker-init**), and **74.4% of the calls are
  OUTERMOST**, so recursion never was the explanation. § 0's law in a
  cache-free shape: the four biggest co-occurrence pairs by COUNT are 141,388
  repeats worth 71 ms (0.5 µs each), the biggest by TIME is 2,603 repeats worth
  166 ms (64 µs each). LANDED: `--typeOfExprCallers` + the
  `captureCallerFrames` expect/actual (JVM `StackWalker`, native `""`), the
  outermost-only walk with inherited origin, the co-occurrence masks, the
  single-visit and PERFECT-cache prize meters, and
  `TypeOfExprCallerAttributionTest`. Suite **12,916 / 0 / 3**, `--listAll`
  byte-identical, cost gate all 20 counters +0.00%. Full derivation:
  **`docs/perf/type-of-expression-attribution.md`**. Follow-on: **(TYPE.2)**.

- [x] **(TYPE.2) Attribute inside `checkVarDeclAssignability` /
  `spineCtaM3StatementAnchor` — DONE round 738. BOTH PRIORS FALSE; the second
  by 65x, and the function is not what its name says.** Measured inside:
  **flow narrowing is 1 ms of 872 ms (0.11%)** and the **relation 13 ms
  (1.5%)** — prior (i) wrong by ~200x, prior (ii) by ~65x (round 735 found the
  SAME relation prior wrong by 48x one function over; it is now falsified in
  BOTH of the compiler's largest assignability sites). What is actually there:
  **12,960 of 15,116 invocations (86%) never reach an assignability check** —
  they are UNANNOTATED declarations that only type an initializer and record it,
  **405 ms = 46% of the function**. Two populations: 12,960 unannotated at
  **34 us** each, 1,881 annotated at **227 us** each (round 737's 36 us was
  their mean). **The handler is 2,363 ms and 85% of it is four callees' own
  work** (`checkVarDeclAssignability` 891, `checkReturnAssignability` 615,
  `checkAssignmentExpression` 318, `walkFunctionBodiesInExpr` 181); the
  eligibility gate + parent climbs over ALL 856,976 nodes are **194 ms
  (212 ns/node)** and the ambient scaffolding 158 ms — together 1.2% of the
  compile. **NOTHING LANDED**: the only candidate lever (hoist the unannotated
  branch above the ~18-walker prologue) is worth **~0**, because every prologue
  walker already bails on `decl.type ?: return false`; the prologue's 265 ms is
  spent on the 1,881 ANNOTATED decls and is **14x the relation it exists to
  correct** — the first price tag on section 0.1's "endgame" paragraph. LANDED:
  `CtaSections` + `--ctaSections{,Coarse}` (opt-in, behaviour-free when off;
  level A opens on the HANDLER so the eligibility gate is a ROW, not an
  unmeasured remainder) and `CtaSectionProbeTest`. Suite **12,923 / 0 / 3**,
  `--listAll` byte-identical in all three modes, cost gate all 20 counters
  +0.00%. Full derivation: **`docs/perf/var-decl-attribution.md`**. The original
  item text follows.

  ORIGINAL: **the third-largest spine handler, 2,900 ms, and
  no round has opened it (pointed at by round 737's by-caller table).**
  `checkVarDeclAssignability:29166` under `ctaM3StmtAnchor` is the **largest
  single expression-typing origin in the compiler**: 33,653 calls, 11,933
  top-level typings, 431 ms of typing at **36 µs per initializer**, factor 1.05
  — expensive, not redundant. Its typing is only ~15% of the enclosing
  handler's 2,900 ms (round 732's per-handler table), so ~2,470 ms is
  unattributed and the handler is ~10% of a 28.7 s checker-init — **the largest
  un-opened single target left**. Method: round 735's verbatim — split the
  function into a wrapper + `…Core`, add partition rows plus a `Coarse` mode for
  the differential calibration, and price the sections; round 736's lesson adds
  "look for a memo or a condition failing one level down", not only for raw
  work. **State a falsifiable expectation first.** Two priors worth testing:
  (i) that the 36 µs per initializer is mostly flow narrowing, as round 735
  found for the argument path (37%) — if so it is (CALL.4)'s population, not a
  new one; (ii) that the ~2,470 ms outside the typing is the assignability
  RELATION — round 735 found that term to be 1.2% on the argument path, i.e.
  the same prior was wrong by 48× one function over. Gate: corpus suite +
  `--listAll` + `cost_gate.py` + `ab-interleaved.sh` medians AND win rate if
  anything lands.**

- [x] **(FRONT.1) The first front-end attribution — DONE round 738, and it landed the
  arc's largest win, `-11.42%` median with B winning 6/6.** Section 0.1 stage 5's
  "front end, ~20%, unprofiled" is **11.0%**; the OTHER 9.2% of that region was
  `Transformer.transform` + `Emitter.emit` running under `--noEmit` and discarding
  its output (2,623 ms of 31,235). Gated by a NEW `CompilerOptions.skipEmitOutputs`
  set only by `ProjectCompiler` — deliberately NOT `options.noEmit`, which 440 corpus
  tests set as a directive. **A SCOPE correction, not an algorithmic speed-up: real
  `tsc --noEmit` does not emit either.** ~~so every published xtsc-vs-tsc `--no-emit`
  ratio compared our check+emit against tsc's check-only — the honest gap is ~2.15x,
  not 2.4x.~~ **RETRACTED round 739: the CI 3-way emits on ALL THREE sides, so the
  2.4x was already like-for-like and this gate does not move it.**
  The front end proper has NO lever: crawl WALL 1,683 ms (5.4%, and it
  already contains reading+decoding+PARSING all 9,977,097 chars, 16 in flight), core
  parse loop **0 ms** (78/78 pre-parses reused), bind 1,622 ms (5.2%), config 102 ms,
  `extractRelativeImports` 17 ms. LANDED: `FrontEnd` + `--frontEnd` (opt-in),
  `skipEmitOutputs`, `SkipEmitOutputsTest` (4 pins incl. the directive negative
  control). Suite **12,927 / 0 / 3**, `--listAll` byte-identical, cost gate 18/20 at
  +0.00% with two globals counters FALLING 9% (rebaselined same commit). Full
  derivation: **`docs/perf/front-end-attribution.md`**.

- [x] **(BENCH.1) DONE round 739 — and the premise it was queued on was FALSE.** The item
  assumed the published 2.4x compared our check+emit against tsc's check-only. It did not:
  `bench-3way.sh` runs xtsc, tsc AND tsgo **with emit**, so the CI ratio was already
  like-for-like and `skipEmitOutputs` (which fires only under `--noEmit`) cannot move it.
  **Round 738's "~2.15x" is retracted everywhere it was written** — it applied our own emit
  fraction while implicitly taking tsc's as zero, which is the ratio's floor, not its value.
  **The real mismatch is the other one and it was still open: § 0.1's budget model is a
  `--noEmit` compile compared against an EMIT-mode ratio.** Measured (same binary, 4
  interleaved pairs, compiler profile): check-only 26,896 ms vs emit 29,194 ms, **emit work =
  2,298 ms = 8.5% of a check-only compile**, B slower 4/4. **The check-only ratio has never
  been measured on either side** (the bench never ran tsc with `--noEmit`); it is bounded by
  `R_ck = R_emit x 0.921 / (1 - s_tsc)` => **>= 2.21x, and > 2.4x as soon as tsc's emit costs
  more than 7.9% of its run**. LANDED: `bench-3way.sh` measures BOTH modes on all three
  compilers (`--modes`), the LOC parse bug is fixed (every archived report published
  THROUGHPUT as its LOC count), `bench-history/README.md` is restructured with a marked
  two-mode table over a labelled pre-739 archive, and all 8 `bench/*.tsv` profiles are
  re-baselined check-only with a MODE-DISCONTINUITY block at the boundary. Honest published
  ratio: **2.28x (median of 340 CI runs) / 2.40x (last 30), EMIT mode**; per-row spread
  1.87x-2.72x because xtsc is one cold run against tsc's median of three. Full derivation:
  `docs/ARCHITECTURE-RETHINK.md` § 0.2.

- [ ] **(ENGINE.1) Price the dedicated-walker layer on two more sites — IN PROGRESS,
  round 739 did the part that needed no measurement and it already overturns the 14x.**
  **The 14x does not survive contact with its OWN site.** 265/19 compares the firewall
  walkers against the final relation call ALONE, and that call is 2.2% of the function
  it lives in; a general rule engine must still resolve the target node, compute the
  source type, infer unannotated initializers and narrow. Re-classifying round 738's
  own level-B rows by "would a general engine also do this": **engine work 483 ms
  (55.4%), dedicated-walker layer 326 ms (37.4%), bookkeeping 54 ms** — so the layer is
  **0.67x the engine work, not 14x it**, and on this site it is **326 ms of a 26,896 ms
  check-only compile = 1.21%**. Deletable is LESS: the weak-type rule is 165 ms = half
  the layer and is real TypeScript semantics tsc implements inside `checkTypeRelatedTo`,
  so it MOVES rather than vanishes => honest range **0.6-1.2%** for this site. **Method
  correction the two remaining sites must adopt** (else their numbers are not
  comparable): report the layer as ms and as a share of the COMPILE, never as a ratio
  against the relation; and split it into "re-implements a rule tsc also has" (moves)
  vs "corrects our own relation" (deletes). **A grep-based census will NOT work:
  `checkReturnAssignability` (802 lines) has ZERO `tryEmit*` calls — its firewall is
  inline `if (...) return` guards — while `checkAssignmentExpression` (1,427 lines) has
  11.** Both need a real intra-function partition (rounds 735/738's method); round 739
  deliberately did not start it thin. Scored predictions E1-E4 are written down in
  `docs/perf/engine-rule-price.md` § 4 — score them.

- [x] **(PERF.HW) DONE round 740 — the cores are REAL, and the question was the
  wrong one: a SEQUENTIAL run already consumes 3.15 of the 4 cores.** Artifact:
  `docs/perf/worker-scaling-round740.md`.
  **The box:** `nproc` 4, AMD EPYC-Rome @ 2445 MHz, 4 distinct `core id`s with ONE
  thread sibling each (**no SMT**), **no cgroup `cpu.max`**, steal **0.0 mean / 0
  max over 72 vmstat samples**. Steal alone does not settle it (a hard quota need
  not be accounted as steal), so the cores were tested directly with a
  tiny-working-set pure-CPU loop: **1.00x / 1.56x / 3.45x / 3.61x at 1/2/4/8-way**
  — four real, independent, unthrottled cores.
  **The table** (compiler profile, `--noEmit`, -Xmx4g, 3 reps, **round-robin
  interleaved across levels** rather than round 666's blocks; drift band re-derived
  from w1's own reps at **+-2.87%**):

  | level | median self | per-rep median delta | wins vs w1 | user CPU | cores |
  |---|---:|---:|:---:|---:|---:|
  | w1 | 27,126 ms | — | — | 85.6 s | **3.15** |
  | w2 | 24,452 ms | **-11.67%** | **3/3** | 85.3 s | 3.49 |
  | w4 | 25,976 ms | -4.24% (deltas STRADDLE ZERO = undecided) | 2/3 | 92.6 s | 3.57 |
  | w8 | 32,212 ms | **+19.37%** | **0/3** | 117.7 s | 3.65 |

  Reproduces round 666 (seq 27,873 / w2 24,669 / w4 flat) and adds the w8 point.
  **THE EXPLANATION, never measured before: 85.6 s of USER CPU for a 27.1 s wall.**
  Attributed by starvation — `-XX:CICompilerCount=2` -> 2.34 cores / 62.8 s user
  (**JIT ~21.7 s of CPU**), `-XX:ParallelGCThreads=1 -XX:ConcGCThreads=1` -> 3.06
  (**GC only ~2.7 s**): C2 compiling a ~110k-line `Checker.kt` never finishes inside
  a 27 s run. **Self time is FLAT across all four configurations (26.6-27.8 s)** —
  the JIT is not stealing from the compile thread (round 618 holds), it is consuming
  the cores a WORKER would need, leaving **~0.85 free**. Every level saturates at the
  same **~3.6-core ceiling**; what changes with worker count is TOTAL WORK (user CPU
  +0% / +8% / +37%), because each worker re-binds every file and runs all ~318
  collectors. **Not a JIT artifact — tested, negative:** freeing ~0.34 cores at w4
  left the wall unmoved (25,870 -> 25,574). Four INDEPENDENT concurrent compiles ran
  3.85x slower each (aggregate 1.03x) — which is **82% parallel efficiency** once
  3.15 is applied (4 x 85.6 core-seconds on 4 cores floors at 86 s; measured 105 s).
  **The box parallelises fine; our compile does not, because one copy already
  occupies 79% of it.**
  **Amdahl:** the w1/w2 fit gives P = 5,348 ms divisible (**19.7%**), R = 21,778 ms,
  infinite-worker floor **-19.7% (1.25x) ever**; the w1/w4 fit gives 5.7%, a 3.5x
  disagreement, so per the rule fixed before the run the model is contention-broken
  beyond w2. 19.7% vs round 666's 23% is NOT resolvable (P is twice a delta whose
  per-rep spread is 1,554 ms).
  **Is shrinking the 77% duplicated term worth attempting? NOT NOW, three reasons:**
  (1) the ceiling is 1.25x even if the duplication vanished; (2) there is no machine
  here to spend it on; (3) the mode is INCORRECT — see the next item.
  Predictions **5 of 6** (P5 falsified: w8 peak RSS 2,240 MB fits -Xmx4g easily, GC
  1.1 s of the 5.4 s regression — **no level was skipped for want of RAM**).

- [ ] **(PERF.HW.a) `--workers N` IS NOT BEHAVIOUR-PRESERVING — found by the round-740
  probe, NOT fixed there.** Sequential emits **46** diagnostics on the compiler
  profile; **every** parallel level emits **62**. The 16 extras are one family in one
  file — `src/compiler/utilities.ts:11349..11410`, TS2322 *"Type
  `EvaluatorResult<number>` is not assignable to type `EvaluatorResult`"* (and the
  `<string>` instantiations). Classification: **identical at w2, w4 AND w8** (so not
  a count-dependent partitioning effect) and **deterministic across reps** (so not a
  race) — the round-609 signature, i.e. a program-wide COLLECTOR iterating the INV.6
  partition view (`checkedResults`) instead of `binderResults`, so a partition worker
  never sees the context that suppresses it. **`--partitionCheck N` is the existing
  harness for exactly this and should reproduce it sequentially** (one run: if
  `--partitionCheck 2` also diverges, the bug is the partition MODEL and is
  debuggable single-threaded; if it does not, the bug is in the parallel path's fresh
  per-worker bind). This is a prerequisite for ANY future `--workers` wall-time
  claim, and it is cheap relative to M2 itself. No v1 impact — `--workers` is opt-in
  and off by default.

- [ ] ~~(cache/identity work of any shape)~~ — **CLOSED round 716 by measurement,
  do NOT re-open without new evidence.** (1) The context-bypassed resolution
  population is **68 ms** total (31,571 outermost calls @ 2.2 µs) — 0.35% of the
  compile. (2) Widening the round-548 INV.5(c) gate lifts hits 23% → 46% and
  measures **+28% wall** (6 interleaved pairs); memoizing the fingerprint (builds
  53,765 → 13,293) still measures **+11.9%**. (3) Pure identity keying (tsc's
  mapper-object shape) gets **4.1%** hits, because the context maps are
  re-allocated per install rather than reused per region. (4) The widened key also
  exposed that the context fingerprint is INCOMPLETE — 1,269 shape-different
  serves, all lib generic signatures (`(value: T, …)` served where
  `(value: Declaration, …)` was correct), i.e. the substitution input is ambient
  state captured by none of nsStack/tpScope/aliasArgs; that would have to be fixed
  BEFORE any widening, for a prize of 68 ms. Third independent confirmation of the
  round-659/665 law: **the cacheable population is the cheap tail.**

- [x] **(M0.1) Tail triage — CLOSED round 620 with the deletion hypothesis doubly
  dead.** Phases (a)–(c) ran round 619 (PassLab facility, corpus census —
  artifact `docs/perf/pass-census-round619.txt`, now carrying a correction
  header); the (d) consumer trace (round 620) OVERTURNED the "23 census-silent
  → deletion-ready" verdict, which rested on two flaws: (i) the census records
  only net-POSITIVE deltas — wipe-and-pin walkers (removeAll+pinDiag, net 0),
  rewriters, retractors, and collectors are census-silent while load-bearing —
  and (ii) Phase B's suite green was a FALSE GREEN: Inv0PassTimingTest's
  cleanup assigned `PassTiming.disabledPasses = emptySet()`, re-enabling the
  lab's disables for every test class after 'I' (the whole generated corpus);
  fixed to save-and-restore. The honest disable experiment (fixed cleanup,
  `--rerun`) fails 26 tests: 20 of the 23 are corpus-pinned (incl. one LOCAL
  pin — Inv4SpineBatch27Test for checkCrossFileUseBeforeDeclaration —
  invisible to a corpus-only census). DELETED (the real pool, 3 pure adders):
  checkModuleNoneConflict (TS1148) + checkExportAssignmentInSystem (TS1218) —
  module `none`/`system` are tsgo-removed kinds, their corpus tests
  generator-skipped — and checkUnicodeSurrogatePairImportBinding
  (unicodeEscapesInNames02's TS1127/TS2305 now flow from the general
  scanner/module-member paths; its errors subtest stays green without it),
  plus orphaned helpers. Gates: full suite 11,379/0; `--listAll` ×8
  byte-identical pre-vs-post on all 8 profiles; build warning-clean. Net wall
  value ≈ nil — the whole ~6.2 s tail is pinned; (M0.4) migration carries the
  lever. LAB DISCIPLINE addenda: `build/pass-lab.txt` is NOT a Gradle input
  (always `--rerun` a lab experiment), and a lab-run verdict is unverified
  until the disable is proven active in the SAME JVM that ran the tests.
- [x] **(M0.2) kindId table dispatch — DONE round 621 (2026-07-20), three
  commits.** NodeBase.kindId (dense per-CLASS Int, stamped by each class's
  `init` block — survives `copy()`, unlike nodeId/parent) + NodeKind.kt (138
  dense consts + the sealed-exhaustive `nodeKindIdOf` compile gate);
  forEachChild → javap-verified tableswitch 0..137; the 3 hot checkSpine
  dispatchers (spineEnterNode terminal when / spineUResEnter /
  spineUResDispatch) + the 13 remaining per-node walker whens → kindId
  lookupswitch (~5 int compares over sparse arm subsets). ccetSpineEnter
  deliberately SKIPPED (5-arm when with `is Block -> when (parent) {` +
  a union-smart-cast-dependent multi-class arm; cost/benefit). MEASURED:
  interleaved A/B (5 pairs, compiler profile) A 31,747 → D 30,713 ms median =
  **−3.3%, D wins 4/5 pairs** — inside the priced 2–4%. Gates: suite 11,385/0
  (+6 NodeKindIdTest pins), listAll ×8 byte-identical at each commit,
  warning-clean. Lesson: the scripted conversion mis-cut FOUR two-line
  `if`-header arms into empty-if mangles — corpus caught 3, a structural scan
  (line ending `{` + dedented bare `}`) the 4th; see the session note.
- [x] **(M0.3) CLOSED round 670 — the three landed slices were the arc's most
  reliable wins (−3.9%, −2.6%, −2.2%); the three REMAINING ones are priced
  below the drift band or are multi-session structural work, so none may land
  alone under the PERF ground rules.** Pricing, done this round rather than
  assumed: (i)'s cheap half — the globals-miss short-circuit — is worth
  **≲0.2%**. The probe claim is exactly right (measured live: **1,234,034
  globals lookups, 1,219,892 misses = 98.9%**), but 1.22M skipped HashMap
  probes at a realistic 20–40 ns each is only **25–50 ms of a ~28 s compile**
  (0.09–0.17%; even a generous 100 ns gives 0.44%). (v)'s undo-log is the same
  size — JFR put it at 1.1% of samples, and round 623 established that a JFR
  self-% is not a wall price. (ii) NodeLinks/SymbolLinks consolidation and
  (i)'s FULL form (Identifier → Int atom at scan time + int-keyed scope/member
  maps) are the only pieces that could clear ±2%, and both are multi-session
  structural changes touching the binder and every map. NOTE they are the same
  CLASS as the arc's winners — those replaced allocation-heavy per-call
  structures on hot paths (LongKeyMap/IntKeyMap/NarrowSeen), and atomization
  removes String hashing/equality from hot map traffic — so if perf work ever
  resumes, full atomization is the one lever left worth sizing. It must be
  PRICED first (the arc's rule): instrument the actual time in the map
  operations it would replace, do NOT trust the JFR "~15% in HashMap+String
  equality" figure that opened this item. Original item text follows.
  ORIGINAL: Layout campaign** (JFR-evidenced ~15% of wall in HashMap+String
  equality with NO single hot map — structure-class work, one interleaved-A/B'd
  slice per commit): (i) name atomization (Identifier → Int atom at scan time;
  int-keyed scope/member maps; a globals-miss bitset — the 1.48M probes are 99%
  miss); (ii) NodeLinks/SymbolLinks record consolidation over per-file dense
  nodeId arrays (tsc's exact structure; symbol ids need per-worker dense spaces
  under INV.6 — node ids are per-file dense already); (iii)+(iv) **DONE round
  621: −3.9% wall (31,180 → 29,955 ms median, 5/5 pairs)** — `LongKeyMap`
  (open-addressing Long→V, EXACT packed-id keys, 0L sentinel) fast-paths the
  three intern caches' dominant shapes (null/empty/1-arg refs — null/empty
  pack alike, reproducing the old string key's `"id|"` conflation
  byte-exactly; 2-member unions/intersections; bigger shapes keep the string
  maps) + the `normalizePath` memo; (vi) **DONE round 622: −2.2% wall
  (30,364 → 29,697 ms median, post wins 5/5 pairs)** — `IntKeyMap`
  (open-addressing Int→V, `Int.MIN_VALUE` sentinel: symbol ids span the
  positive main space AND the ≤−2 INV.2(c) scope space, so 0/negative are
  legal keys) replaces `HashMap<Int, ·>` for symbolTypes/declaredTypes/
  symbolTargets, and `NarrowFlowMemo` (parallel int-key/int-depth/Type
  arrays, serve/overwrite depth rules byte-exact, pinned both directions in
  IntKeyMapTest) replaces the narrowing walks' per-invocation
  `MutableMap<Int, Pair<Int, Type>>` — a fresh map per depth-0 walk
  (~111k/compile) allocating a boxed key + `Pair` + map node per store on
  the hottest checker path; (vii) **DONE round 622: −2.6% wall (30,124 →
  29,351 ms median, wins 4/5 pairs)** — int-specialized `NarrowSeen`
  (open-addressing IntArray slots + tombstone removal — popToMark removes
  in reverse insertion order, which linear probing cannot slot-shift;
  EMPTY slots only from rehash, so present-id probes never meet EMPTY
  early — + IntArray add-log; was a double-boxing HashSet+ArrayList on
  every flow-node visit), pinned by a 60k-op randomized oracle vs the old
  form; (v) undo-log
  (the proven NarrowSeen mark/pop pattern) replacing HashMap(other) scope
  copies (putMapEntries 1.1%) — also reduces M1's epoch churn. Do NOT reach
  for a JVM-only map library (build-change guardrail + multiplatform);
  `LongKeyMap`/`IntKeyMap` are the in-repo reusable pieces for later slices
  (IntKeyMap values are non-null and never iterated — the compiler flags
  both constraints at any unsuitable conversion site); (viii) **DONE round
  623, measured NEUTRAL (−0.30% median over 10 interleaved pairs, post wins
  6/10 — below the drift band, NO wall claim)** — lazy/unboxed Parser line
  starts (the eager per-parse table was 5.3% of JFR self samples, only ever
  consumed by diagnostic line/col formatting), the
  `fileDeclaresNonGenericType` fileResults-index + `file|name` memo (was an
  un-memoized per-type-reference top-level statement scan — quadratic
  insurance for bigger projects), and ccetSpineEnter's kindId dispatch (the
  one dispatcher M0.2 skipped, now hand-converted). Landed as structural
  slices on the corpus + listAll ×8 byte-identity gates; the JFR lesson
  (counted-loop self-% is safepoint-bias-inflated + parallel-crawl savings
  don't move serial-dominated wall — A/B before believing any self entry)
  is in the round-623 session note.
- [x] **(M0.4) CLOSED at 35 passes by the round-659 arc measurement — see
  (M0.4-AB) for the number and the verdict. NOT a wall-clock lever: 75% of a
  migrated pass's cost reappears inside checkSpine (the 35 deleted rows summed
  3,146 ms; checkSpine grew +2,358 ms), the interleaved arc A/B is +0.24% on
  compiler / −1.6% on harness = inside the drift band, and finishing the
  remaining ~90 rows would buy ~1.1 s (~4%) for ~90 rounds. Do NOT migrate
  another tail pass for performance; migrate one only when it is on the path of
  another change, and keep this item's migration-pattern zoo as the reference
  for HOW (it is complete and each shape is documented below).** The original
  item text, and the per-round record of all 35 migrations, follows —
  Migrate the surviving pinned tail into the spine (the documented
  migration-pattern zoo), cost-descending; retire dead migration scaffolding as
  it goes (emit-twice arms whose legacy side is gone, the dead m3
  truncation-mark blocks). Post-round-619 this carries the WHOLE tail lever
  (~6.2 s, all corpus-pinned — the deletion pool measured 59 ms): the worklist
  is the `--passTiming` cost table intersected with
  `docs/perf/pass-census-round619.txt` (top by cost at the round-624 HEAD
  table: checkObjectSpreadInvalidTypes 165.6 ms — **MIGRATED round 624**,
  checkArrayPushDiscriminatedUnionElements 138 ms — **MIGRATED round 624**,
  checkImplicitThis 127 ms — **MIGRATED round 625** (the frameless variant:
  a pass threading ONLY downward context — no statement-ordered state —
  migrates as a pure pull-based per-anchor ancestor fold, no frames, no
  leave hook, no memo when anchors are rare),
  checkFnTypedParamCalls 119 ms — **MIGRATED round 626** (the downward-MAP
  variant: FnParamCtx rebuilt-at-boundaries/accumulated-through-boundaries
  reproduces as the pull-based fold WITH a per-boundary-child ctx memo —
  anchors are every Identifier-callee call, too frequent for the round-625
  memo-free form — plus a memoized BINARY reach classifier: no multi-state
  statuses needed when every (parent kind, child slot) pair decides descent
  unambiguously),
  checkAbstractClassInstantiation 113 ms — **MIGRATED round 627** (the
  collector-prepass variant: four FILE-scoped collectors reproduce as
  per-file spine-setup state, not frames; the statement-LIST overlay
  (add-abstract-then-remove-shadowed, a pure function of the ancestor
  list-owner chain SourceFile/Block/ModuleBlock/CaseClause/DefaultClause)
  rebuilds pull-based per anchor with a per-owner memo; the
  `[A].map(cls => …)` callback-param typeof extension recovers on the
  anchor climb folded OUTERMOST-first — node coverage is identical
  between the legacy handled/unhandled branches, so the reach classifier
  needs no special case; no ambient sandwich — the emission reads no
  checker ambient),
  checkSymbolToStringConversions 108 ms — **MIGRATED round 628** (the
  downward-SETS variant: accumulate-only (symbolNames, tpNames) sets
  rebuild pull-based per anchor; the per-body whole-list locals PREPASS
  reproduces as per-boundary LEVELS with only fn bodies and ModuleBlocks
  as collection boundaries — inner Block/clause re-collects were always
  subsets; two reach edges differ from the fp/ai classifiers: case-clause
  and bare for-initializer EXPRESSIONS are reached),
  checkDefiniteAssignmentViaFlowGraph 105 ms — **MIGRATED round 629** (the
  FILE-END variant: a pass whose per-file body is a positional dedup scan
  over prior diagnostics + whole-file flow walks migrates as a dispatch in
  checkSpine's per-file loop AFTER spineWalkFile returns — never
  per-anchor — so the dedup scan sees the file's spine-emitted TS2454s;
  the walker family stays verbatim, the only ambient install is
  currentFlowGraph save/restore, and the B223 sibling stays at its own
  pass slot since it scans no prior diagnostics),
  checkSameTargetReferenceCastOverlap ~123 ms — **MIGRATED round 630** (the
  SHARED-WALKER variant: only the pass's whole-file driver is deleted — the
  walkTypeAssertionsInStmt/-InExpr recursion SURVIVES for the cast-overlap
  sibling passes, so the reach classifier mirrors the shared walker's arms
  and must stay IN SYNC with any future walker-arm change; the first
  TYPE-RESOLVING tail migration — per-anchor getTypeOfExpression/
  getTypeFromTypeNode/relation calls interleave into the spine walk, gated
  clean by corpus + listAll ×8; ambient sandwich = currentCheckFileName +
  a nulled currentFlowGraph around the emission pair),
  checkBindingPatternComputedIndexSig ~120 ms — **MIGRATED round 631** (the
  MULTI-ANCHOR-KIND variant: three emission families dispatch from one
  enter hook over seven anchor kinds, member-parameter emissions gated on
  the member's PARENT kind — objlit/class-EXPRESSION members emit,
  FunctionDeclaration/class-DECLARATION members never do; the reach
  classifier is a FROZEN copy of the deleted walker's arms, deliberately
  NOT shared with the surviving cast walker's spineCoEdge, which it
  matches except FunctionDeclaration parameter defaults; the TS2537
  emitters install the spine-entry RESTING currentFileLocals per emission
  — the legacy pass never installed it),
  checkConstEnumDiagnostics ~123 ms — **MIGRATED round 632** (the
  FILE-GATED variant: the legacy whole-file collectConstEnumDecls gate
  reproduces as per-file setup state — anchors inert in files without
  their own const enum; the TS2567 top-level merge scan rides setup; a
  resolution-CONDITIONAL walker descent (property/element-access bases
  skipped when the base IS a const enum) reproduces as an unconditional
  edge + an anchor-side parent pre-filter, exactly equivalent because
  neither branch can emit at a base — keeping the classifier purely
  structural), then
  checkNullTypeAssertionOverlap ~104 ms — **MIGRATED round 633** (the
  FLAG-ARM-LIFT variant: the `inNullCastOverlapPass`-gated emitters
  lift out of the SHARED walker onto the round-630 anchors —
  spineCoStatus/spineCoEdge reused verbatim; binderResults-iterating
  driver → the spine's partition view, gated `--partitionCheck 2`
  EQUIVALENT ×8), then
  SKIP checkCrossFileModuleAugmentationDuplicates (114 ms — CROSS-FILE
  aggregation, not per-file spine material), then
  checkProtectedMemberReadAccess ~103 ms — **MIGRATED round 635** (the
  PUSH-BASED ORDER-DEPENDENT variant, the round-531 arith pattern's first
  M0.4 application: a pass whose downward map is statement-order MUTATED
  (per-declaration `vars[nm] = …` recordings that LEAK through
  block/if/loop/arrow descents and COPY at nested-fn boundaries)
  reproduces as LIFO frames at fn-like boundaries + per-declaration
  recordings at VariableDeclaration LEAVES (the legacy walk-then-record
  order), with a 5-STATE reach classifier — CONTAINER_FILE/CONTAINER_NS
  split because only FILE-level ExpressionStatements are walked with the
  per-file topVars map, installed by INSTANCE so IIFE-body recordings
  persist across top-level statements; the `=`-LHS write skip is an edge
  (LHS subtree never read-walked, the write check fires at the
  BinaryExpression anchor under the frame-maintained pmrInClassMethod
  gate)), then
  checkPropertyInitialization ~99 ms — **MIGRATED round 636** (the
  MULTIPLICITY variant: the legacy ClassDeclaration statement arm
  double-walks member bodies — checkClassPropertyInit's nested recursion
  PLUS the arm's own member loop — so nested classes emit 2^depth
  duplicate TS2564s, reproduced by an INT-valued reach classifier
  returning a VISIT COUNT (spinePiMult: a bottom-up climb multiplying
  per-edge factors {0,1,2}; every factor local to one edge, no
  multi-state fold — the arrow/fn-expr partial-body restriction resolves
  by peeking at the Block's parent); the anchors repeat the split-out
  checkClassPropertyInitEmit that many times; the recursion walkers
  SURVIVE for the B439 declarationOnly dispatch — the round-630
  shared-walker rule, spinePiEdge mirrors them);
  checkGenericIndexWrite 117.3 ms — **MIGRATED round 637** (the
  DOWNWARD-MAP variant's third application: the (tparams, tpProps,
  refs) triple rebuilds pull-based per anchor with a per-boundary-child
  memo — tparams ACCUMULATE through class/fn boundaries, refs REBUILD
  per fn-like boundary from params + the body-WIDE collectTpLocalsMap
  prepass (whose descent is NARROWER than the scan's — switch/try
  locals uncollected, frozen + pinned), tpProps from the nearest
  enclosing class member (RESET by a nested FunctionDeclaration,
  cleared for property initializers); anchors are `=` binaries with a
  paren-unwrapped ElementAccess LHS; zero TS2862 on all 8 profiles →
  the listAll gate pins pure non-perturbation);
  checkArgumentsCollision 116.8 ms — **MIGRATED round 638** (the
  CONSTANT-CONTEXT variant, the simplest yet: the only downward value is
  the per-file isModule boolean, so no frames, no ctx memo — the
  per-construct declare/body gates re-derive at the anchor from the
  construct node + its parent kind (class-DECLARATION members need
  body + !class-declare and its set-accessors never param-check, while
  class-EXPRESSION/objlit members param-check unconditionally — frozen
  asymmetries, pinned); a WIDER reach than gIdx (arrows/fn-exprs/
  class-expr members/objlit members/template spans/typeof operands
  descend; if/ternary conditions, loop/switch heads, class-decl property
  initializers, declare-namespace bodies stay silent) = a fresh edge
  set; the run-level dispatch gate (target < ES2015 || any non-dts
  module file) becomes the run-active flag);
  checkEvolvingEmptyArrayImplicitAny 103.2 ms — **MIGRATED round 639** (the
  PER-LIST-OWNER variant: a per-STATEMENT-LIST scope pass dispatches each
  scope's list ONCE at its owning SourceFile/Block/ModuleBlock enter, gated
  by a multi-state reach classifier carrying the deleted evRecurseScopes'
  level-skipping quirks — try/catch/finally clause statements and
  case-clause statements recurse WITHOUT forming a scope list (a candidate
  declared directly there never fires) while a Block statement inside them
  IS a scope; arrow/fn-expr bodies and class EXPRESSIONS are never scopes;
  a dotted `namespace A.B` IS one (the parser keeps a direct ModuleBlock
  body — the scope map's "never" guess was wrong, caught by the pins);
  Part 2 is TYPE-RESOLVING → per-dispatch ambient sandwich of resting
  currentFileLocals + per-file currentCheckFileName + a nulled
  currentFlowGraph);
  checkUndefinedClassInterfaceName 123.9 ms — **MIGRATED round 640** (the
  TWO-INTERLEAVED-WALKS variant: a pass running two recursions with
  disjoint node sets — the statement-only name-check walk (never descends
  fn/class-member bodies) + the yield walk started at name-reached
  FunctionDeclarations — reproduces as ONE multi-state classifier whose
  statuses carry the walk identity AND the downward generator flag
  (UY_NAME / UY_YGEN / UY_YNON, plus UY_MEMBER bridging a yield-walked
  container's member to its body/initializer); the frozen member filters
  ride the container edges — class DECLARATIONS walk accessor bodies +
  prop initializers, class EXPRESSIONS method/ctor only, objlit members
  methods only, accessors never; the legacy left-spine BinaryExpression
  fold reduces to plain left/right edges, reach-equivalent; zero
  emissions on all 8 profiles → the listAll gate pins pure
  non-perturbation);
  checkSuperRefInRebindingScope 113.1 ms — **MIGRATED round 641** (the
  rebound-boolean-as-status variant: the walk's one downward boolean
  rides the classifier status — fn-decl/fn-expr bodies reset to rebound,
  arrows/ModuleBlocks preserve, class-member bodies/prop initializers
  reset to clear via a member-carrier status; the frozen `super(...)`
  CALLEE skip is the anchor's direct-parent gate so a parenthesized
  super callee still fires; object literals skipped entirely — the
  sibling checkSuperInObjectLiterals is position-disjoint);
  checkInvalidAssignmentTargets 105.8 ms — **MIGRATED round 642** (the
  INT-depth classifier's second application: the shared `checkDepth`
  frame counter reproduced per node with +1 on every expression parent's
  outgoing edge — statement lists nested inside expressions inherit the
  elevated ambient — and NO right-spine absorption, so deep chains prune
  at the 200 cap, pinned at the exact boundary; the orphaned checkDepth
  counter deleted from Checker + CheckerState);
  checkTypeParameterDefaults 150 ms — **MIGRATED round 643** (the
  SPLIT-PRODUCER variant + the first PARSE-RECORDED candidate set: a
  pass whose side-set write cannot ride the spine — cross-file/
  earlier-in-file display consumption — SPLITS: the TS2368/TS2744
  emissions anchor at the ten TP-list-bearing construct kinds over a
  binary reach classifier, and the pre-spine producer consumes
  SourceFile.typeAliasesWithTpDefaults (recorded at the parse site,
  moduleSpecifiers-style — no tree walk; 0.4 ms vs the legacy 150 ms
  row) FILTERED through the SAME classifier — one frozen edge set
  serves both halves, and a speculative-parse discard classifies
  unreached via its detached parent chain. Producer-scan lesson: a
  forEachChild worklist re-scan of the tree costs MORE than the legacy
  walk it replaces (264 ms raw, 218 ms TypeNode-pruned) — parse-time
  recording is the shape for future split producers);
  checkExpandoFunctionNestedReads 99 ms — **MIGRATED round 644** (the
  file-gated + pull-based-shadow combination: the write collector runs
  at per-file SETUP — it never descends function-likes, so the
  double-walk of top-level expression code is bounded and the anchors
  emit inline against the COMPLETE declared map, no buffering; the
  ChainedNameSet shadow chain rebuilds pull-based per anchor — every
  fn-like ancestor of a reached anchor was entered through its walked
  interior, so each contributes its layer; anchors pre-gate on the
  candidate-receiver TEXT, so the memo-free rare-anchor rule applies);
  checkStrictModeIdentifiers 96 ms — **MIGRATED round 645** (the
  MODE-ROUTED variant: the first pass whose SourceFile root edges
  route by a per-file MODE decided at setup — module/strict/fn-local —
  and whose statuses carry the walk IDENTITY across two interleaved
  families: the strict emission walk and the fn-local SEARCHING walk,
  with prologue-tested flips at fn-body edges; the module top-level
  specials continue INTO the strict walk at initializer/body edges;
  class subtrees unreached by construction — the legacy class-element
  walk ran with an EMPTIED restricted set, so it could never emit; the
  `var eval` TS2300/TS6203 pair rides the VariableStatement anchor);
  checkConstLiteralComparisons 95 ms — **MIGRATED round 646** (the
  SINGLE-ADDING-ARM variant: a downward-MAP pass where only ONE arm
  ADDS entries — the for-init const-literal transform; the whole-list
  shadow prepass and fn-param boundaries only REMOVE — needs no
  per-boundary memo: the map is empty at any anchor without a
  ForStatement ancestor whose const init adds one of the anchor's
  operand names, so a cheap parent-climb pre-filter guards the precise
  memo-free reach+scope fold; the legacy left-spine binary iteration
  dissolves into plain left/right edges);
  checkSuperInObjectLiterals 91 ms — **MIGRATED round 647** (the
  boolean-as-status shape's second application with OBJLIT anchors: the
  legacy ObjectLiteralExpression arm SPLITS — its per-property EMISSION
  half becomes the anchor-called emitObjLitSuperProperties running the
  bounded findObjLitSuperRefs leaves, while its walk-continuation half
  dissolves into classifier edges (objlit method/accessor bodies →
  SU_VALID via the SU_OMEMBER carrier; a PropertyAssignment initializer
  is a plain PRESERVE edge — the legacy fn-expr/arrow initializer
  dispatch reproduces exactly on the general FunctionExpression-resets/
  ArrowFunction-preserves arms); the classHasExtends boolean rides the
  CARRIER CHOICE (SU_CMEMBER_EXT/SU_CMEMBER_NOEXT), not a separate
  channel; anchors pre-gate on the emission shape before the memoized
  climb);
  checkTypeParamStrictSubtypeCast 93.7 ms — **MIGRATED round 648** (the
  FOLD-THROUGH variant: the first classifier reusing ANOTHER pass's edge
  set — TC_SHARED hands off to spineCoEdge; pull-based TP-scope layering
  rebuild with method-param typing; the B402 empty-objlit local set as a
  per-list-memoized union over enclosing TPC lists);
  checkDeleteOperator 86.8 ms — **MIGRATED round 649** (a straight
  template application: binary reach classifier over the deleted walker
  arms, one per-file isStrict setup boolean, resting-currentFileLocals +
  null-flow sandwich with currentCheckFileName deliberately untouched);
  checkConstructorParamInInitializers 85.5 ms — **MIGRATED round 650** (the
  multi-state class-anchored reach classifier: CP_STMT/CP_EXPR reproduce
  the two deleted routing walks, CP_ABODY the restricted arrow/fn-expr body
  — its three permitted statement kinds handed straight to CP_STMT, which
  descends them to CP_EXPR identically to the legacy inline loop, so no
  extra restricted-body statuses — and CP_MEMBER the class-member conduit
  carrying the DECLARATION-vs-EXPRESSION descent asymmetry, member bodies +
  property initializers for a class DECL, property initializers only for a
  class EXPR; fully syntactic, no ambient sandwich);
  checkAbstractMemberContext 81.6 ms — **MIGRATED round 651** (the
  AMBIENT-CLIMB variant: a downward BOOLEAN that is a pure function of the
  ancestor chain need not ride the classifier status (round 641) NOR a
  frame stack — it is re-derived by a SEPARATE cheaper ancestor climb
  (spineAbInAmbient), halving the status space to AB_STMT/AB_EXPR/AB_MEMBER;
  sound because `inAmbient` is monotone (`|| Declare in modifiers` at
  ClassDeclaration/ModuleDeclaration, pass-through everywhere else) and the
  ONLY walked edges out of those two kinds are into member BODIES / the
  MODULE BLOCK, so for a REACHED node "some `declare` class/module ancestor
  exists" IS the threaded OR — the climb must therefore run only AFTER the
  reach check passes; one AB_MEMBER conduit serves both class DECLARATIONS
  and class EXPRESSIONS since Ab recurses member BODIES only, never property
  initializers, so there is no DECL/EXPR asymmetry to encode; four
  deliberate divergences from the same-shaped round-650 CP fold, each pinned
  both directions: NO declare-skip anywhere (the flag suppresses only the
  EMISSION), arrow/fn-expr Block bodies are the FULL statement walk (a class
  DECLARATION in an arrow body IS reached), and the switch SUBJECT and
  ternary CONDITION ARE walked);
  checkImplicitAnyYieldExpressions 107.2 ms — **MIGRATED round 652** (the
  ANCHOR-SIDE-GATE variant of the round-641 boolean-as-status shape: the
  downward `inGen` boolean rides the status — it is RESET by every nested
  function-like, so it is NOT monotone and round 651's ambient climb does
  NOT apply — while a frozen EMISSION SKIP whose condition is decidable
  from the ANCHOR's OWN parent chain (the round-479 discarded-result rule:
  a statement-position `yield x;`, parens transparent, draws nothing) is
  re-expressed as a four-line paren-climb AT THE ANCHOR instead of a reach
  state, which would have doubled the status space; ONE arm set serves both
  deleted walks since statement and expression node classes are disjoint —
  no walk-identity channel; IY_MEMBER carries class member bodies AND
  property initializers, both → IY_NON, so no DECL/EXPR asymmetry and class
  EXPRESSIONS are never walked);
  checkAbstractMemberAccessInConstructor 68.4 ms — **MIGRATED round 653**
  (the SPLIT-AT-THE-RE-ENTRY-BOUNDARY variant: a pass whose per-anchor
  leaf can RE-ENTER the pass on a nested anchor splits at that boundary
  and KEEPS the routing walkers alive — the spine reproduces the
  ROOT-driven reach, the surviving recursion the LEAF-driven reach, and
  the two compose to the legacy multiplicity (a class expression in a
  PROCESSED constructor is processed TWICE) with no INT-valued round-636
  classifier; the round-630 sync rule applies to the survivors. Second
  move: the legacy VariableStatement NAME OVERRIDE is recovered
  ANCHOR-side from the parent declaration's classifier status — round
  652's anchor-side gate applied to a NAME, since that arm's reach is
  identical to the plain initializer edge. Reach is PURELY STRUCTURAL —
  the routing walk threads no downward value and the emission walk's
  inDeferredFn lives inside the surviving leaf, so neither a status
  channel nor an ancestor climb is needed; the file-scoped classMap
  prepass rides setup);
  checkIncDecTypeParamOperands 68.3 ms — **MIGRATED round 654** (the
  STRUCTURAL-TWIN variant, the cheapest migration class: when the next
  tail pass is a structural twin of an already-migrated one — here round
  637's checkGenericIndexWrite, whose own source comment says it mirrors
  THIS pass's scope threading — the migration is a TRANSCRIPTION of the
  twin's shape (same boundary-child set: fn-decl/method/ctor/accessor
  BODIES + class-property INITIALIZERS; same pull-based per-anchor ctx
  memoized per boundary child; same memoized binary reach classifier),
  and the whole cost is (a) diffing the two legacy walkers' arm sets and
  (b) pinning the differences — here exactly TWO expression arms
  (TypeAssertion + satisfies casts are transparent to this walk, absent
  from gx's). The downward triple is gx's with SETS instead of maps:
  tparams accumulate, tpProps rebuild from the nearest enclosing class
  DECLARATION (reset by a nested FunctionDeclaration), tpLocals rebuild
  per fn-like BODY from the body-wide prepass);
  checkConflictMarkers 67.8 ms — **NOT SPINE MATERIAL, OPTIMIZED IN PLACE
  round 654 tail** (a pure per-file SOURCE-TEXT scan with no AST walk at
  all: nothing to fold into the spine, cost INTRINSIC, lever ALGORITHMIC.
  A marker is meaningful only at a LINE START, so the scan now hops line
  starts via `indexOf('\n')` instead of testing every character —
  67.8 → 26.5 ms, 2.6×; the intermediate four-`indexOf(marker)` form was
  REJECTED at 45.1 ms because `=`/`<`/`>` false-start on nearly every
  line. The pass keeps its own slot; gated by 9 new pins + the ACTIVE
  generated conflictMarker* `.errors.txt` subtests + listAll ×8);
  checkImplicitAnyNewExpressions 66.9 ms — **MIGRATED round 655** (the
  NO-DOWNWARD-VALUE variant, the simplest class: when the deleted
  recursion's parameter list is CONSTANT — every recursive call passes
  the arguments it received — there is no ctx rebuild, no frames, no
  leave hook and no status channel; the whole migration is the round-649
  spineDelStatus shape with a different edge set. Two per-migrator
  notes: the ambient install is the FILE's own binder locals because the
  legacy DRIVER installed them itself (unlike the resting-locals
  captures of rounds 624/625/631/649), and the arm diff against the
  same-shaped `del` classifier is real — objlit method/accessor bodies,
  `for`-head DECL-LIST initializers and switch case EXPRESSIONS are
  walked here and not there);
  checkArgumentsInClassFieldInitializers 82.9 ms — **MIGRATED round 656**
  (the round-640 TWO-INTERLEAVED-WALKS variant's second application, with
  one template refinement: when two interleaved walks share MOST of their
  arms — here ~30 of ~45, every shared arm having an identical child set
  whose child simply KEEPS the parent's status — write the fold keyed on
  the node KIND and branch on `pStatus` only inside the differing arms,
  so the walk identity "rides along" a pass-through arm (`-> pStatus`)
  instead of duplicating 30 arms under an outer `when (pStatus)`; round
  640's outer-status form is right only when the two walks' node sets are
  near-DISJOINT. Three statuses: AF_ROUTE (class-finding), AF_EMIT
  (inside a property initializer / static block, where the `arguments`
  Identifier anchor fires), AF_MEMBER (the class/objlit member conduit
  whose member KIND picks the resuming walk); the five reach asymmetries
  — EMISSION-only if/loop heads + switch subject + case expressions,
  EMISSION-only objlit method/accessor bodies, EMISSION-only arrow
  parameter defaults, a ClassExpression `declare`-gated in ROUTING and
  UNGATED in EMISSION, ROUTING-only namespaces and `export =` — are the
  whole risk surface and are pinned both directions; multiplicity 1
  everywhere, fully syntactic, NO ambient install at all);
  checkArrayToClassCastOverlap 72.5 ms — **MIGRATED round 657** (the FOLD-IN
  class, the cheapest there is: the pass OWNED NO WALK — it only DROVE the
  SHARED walkTypeAssertionsInStmt/-InExpr recursion with its emitter as the
  callback, and a sibling driving the SAME walker was already on the spine
  (round 630), so CO_REACHED IS its reach by construction and the whole
  migration is one leaf call added to that arm + the driver deleted. No
  classifier, no edge diff, no frames/ctx/status/memo. Two placement details
  carry the correctness: the leaf goes LAST in the arm because its legacy slot
  ran after the round-630/632 passes (insertion order at a shared position),
  and the legacy ambient needs no new install — checkSpine's per-file loop
  already sets the file's binder locals and the shared arm installs
  currentCheckFileName. BEFORE picking any next tail pass, grep its driver for
  a shared-walker call: a fold-in is orders of magnitude less work);
  checkTypeParamTypedOps 71.0 ms — **MIGRATED round 658** (the round-635
  PUSH-BASED ORDER-DEPENDENT variant, and the first whose downward context
  includes a TYPE-SYSTEM AMBIENT rather than only plain data: `tpVars` is
  MUTATED in statement order, LEAKS through block/if/loop/try/namespace
  descents and REBUILDS at every fn-like body from its own parameters, so it
  rides a LIFO of frames at exactly the legacy new-map/new-scope boundaries —
  while the legacy `withInternedTpScope` REGION, which a spine migration
  cannot hold open across nodes, is reproduced by CAPTURING its result: run
  it at the boundary for its interning + constraint-materialization side
  effects, read currentTypeParamScope/currentTypeParamAstForOps from inside
  the block, carry the pair on the frame and install it around each dispatch
  only. The VariableStatement two-loop order — record all declarations, then
  emit on the initializers — reproduces as a recording dispatch at that
  statement's ENTER. Reach is unusually NARROW: `for` heads, `switch`, object
  and array literals, templates, all four cast forms, await/yield,
  typeof/void/delete operands, spreads, comma chains and — the big one —
  ARROW and function-EXPRESSION bodies have NO arm, and on the class side only
  method/ctor/accessor BODIES are reached);
  next per-file candidates by cost (round-656 table, the migrated rows
  gone): **checkVarHoistRedeclaration 68.9 ms**,
  checkCallTypeArgCount 66.2 ms,
  checkIllegalSuperCallsInNestedFunctions 62.7 ms,
  checkTypeArgumentConstraints 62.7 ms, checkSpreadPropertyOverrides
  62.5 ms
  (checkCrossFileModuleAugmentationDuplicates, now 109.7 ms, stays
  SKIP — cross-file aggregation, not per-file spine material; the tail is
  now VERY FLAT — no per-file row above 73 ms, so per-pass wall value is
  small and the remaining ~90 passes >20 ms carry the residual ~4.3 s). Migration protocol per
  pass (the round-624 template): slot-move pre-gate commit (intact pass to the
  post-spine slot, corpus + listAll ×8), then the migration commit (frames at
  the legacy copy edges, memoized reach classifier, per-dispatch ambient
  sandwich + pull-based TP rebuild, local pins, corpus + listAll ×8). A
  single-pass wall delta (~0.5%) is BELOW the drift band — the per-item
  evidence is the `--passTiming` table (the pass's row gone, checkSpine's row
  not inflated), not an interleaved A/B; A/B the ARC once several passes land.
- [x] **(M0.4-AB) ARC MEASUREMENT PAID — round 659. VERDICT: STOP the arc at 35
  passes; (M1) is next.** The number the arc owed since round 624 is in, and it
  says the migration is NOT a wall-clock lever. Method as queued: pre-arc binary
  `4b0dfcc7` (round-623 HEAD) vs HEAD `e9d8279d`, both class dirs kept, NO
  recompile between measurements, alternating within-pair order.
  **compiler profile, 6 interleaved pairs: pre median 28,945 ms → post 29,015 ms
  = +0.24%, post wins 3/6** (per-pair deltas −667…+1,190 ms — the noise spread
  is ~4% of total, an order of magnitude above the effect). **harness profile,
  2 pairs: 40,256 → 39,605 = −1.6%, post wins 2/2.** So the true effect is a
  SMALL gain somewhere in 0–2%, entirely inside the ±2% drift band the ground
  rules refuse to land on.
  **THE MECHANISM, measured (this is the transferable part): 75% of a migrated
  pass's cost REAPPEARS INSIDE checkSpine.** Same-run `--passTiming` both sides:
  the 35 deleted rows summed **3,146 ms**, while checkSpine grew
  **18,896 → 21,253 = +2,358 ms**. The tail was NOT redundant traversal that a
  single walk eliminates — it is per-node work that a single walk still has to
  do, now as ~35 `when (kindId)` dispatches plus memoized ancestor-climb reach
  classifiers on EVERY node of EVERY file. The multiplication moved from "N
  walks over the tree" to "N dispatches per node", which is the same order.
  **THE RATE ARITHMETIC that closes the arc:** the residual is ~25% of migrated
  cost. The remaining tail is ~90 rows >20 ms ≈ 4.3 s, so finishing it buys
  ~25% × 4.3 s ≈ **1.1 s ≈ 4% of wall — for ~90 single-pass rounds.** (M1)
  targets ≤15–20 s from ~29 s = **30–45%**. The arc stops here; the 35 landed
  migrations keep their real value (they are behaviour-preserving, they deleted
  ~8 k lines of walker recursion, and the spine is now the single place per-node
  checks live), but no further pass is migrated FOR PERFORMANCE. Migrate one
  only when it is on the path of another change. Bench TSV rows carry both
  medians locally (`bench/` is gitignored — the round-659 session note is the
  durable record and carries every per-pair number).
- [x] **(M1) COMPLETE (rounds 660–665) — banked 0.83 s (−2.93%), which is the
  arc's only live win; the rest of the advertised prize was never there.**
  Ledger: an original "≤15–20 s path" (30–45%), retired at round 660 for a
  measured ~3.3 s, corrected to ~2.5 s at round 662 when a key collision was
  found in the instrument, of which round 664's live dependency-keyed flow-walk
  memo banked 0.83 s and round 665 showed the remaining ~1.1 s expression half
  was a 35× over-estimate (real value 30 ms). What survives as reusable
  machinery: the tagged epoch (`bumpExprEpoch`), the dependency-keyed live walk
  memo, and three shadow classifiers that made every one of those corrections
  cheap. Original framing, retained for context. Realistic prize ~3.3 s of ~29 s = 11–13% — NOT the
  "≤15–20 s path" this item used to claim (that figure was never measured; it
  is retired).** Ceiling arithmetic, from the round-660 `--passTiming` run:
  narrowWalks cost 3,942 ms over 111,248 walks ≈ 35 µs/walk, so a PERFECT walk
  memo saves the 1,000 ms of already-identical repeats plus ~34.2k × 35 µs
  ≈ 1.2 s → ~2.2 s; the getTypeOfExpression shadow memo could serve 149,742 of
  484,628 calls (31%) ≈ 1.1 s. Both together ≈ 3.3 s. Still the biggest single
  lever left (3× the whole remaining M0.4 tail), but size the work to it.
  - [x] **(a) DONE round 660 — attribution instrumented, and the item's premise
    was WRONG.** Every fence bump is now tagged (`bumpExprEpoch(src)` →
    `epochBumps`) and the walk probe's `walkMiss` is split cold vs
    epoch-invalidated with a result comparison + blame tag. (1) Of 80,034
    misses, **45,476 (57%) are COLD** — a first sighting of that reference, so
    no fence design recovers them; the old "80k walks run at fresh epochs"
    framing conflated cold with churn. (2) But the fence IS far too coarse: of
    the 34,558 invalidated repeats **99.6% recompute to an IDENTICAL result**
    (only 133 differ). (3) The coarseness is NOT noise, so **"fence per map"
    will not fix it**: meanEpochDelta is 218 (the fence moves ~218× between two
    walks of one reference) and blame concentrates in currentLocalTypes swaps
    (67%) + currentFlowGraph swaps (29%) = 96% — the spine's per-scope and
    per-file installs, i.e. GENUINE state changes. ALSO LANDED: no-op guards on
    all 13 fenced setters (`if (field !== v)`), which remove 1.46 M pure no-op
    bumps (28% of fence traffic) and drop meanEpochDelta to 154 — but recover
    only ~200 of the 34.5k invalidated repeats (0.6%), which is finding (3)
    measured from the other side. Kept: correct, sharpens the blame table, and
    the live memo will need it. (The epoch is PROBE-ONLY today — read only under
    `--passTiming` — so none of this can change compiler behaviour.)
  - [x] **(b) DEPENDENCY-KEYED validity — SOUND, gate MET (rounds 661–662).**
    Each memo entry records the FlowGraph identity plus the Type INSTANCE bound
    to the reference's ROOT NAME in currentLocalTypes / narrowedDeclaredTypes,
    and a repeat is served while those match — so a swap to a DIFFERENT scope map
    that still binds the root to the SAME instance is not an invalidation, which
    is the population the global fence discarded. Shadow numbers with the
    CORRECTED key (round 662): **serve 41,389, all identical, `depServeWrong` =
    0**, cold 69,790, invalidated 69 (localType 68, localType+narrowed 1 — the
    graph identity never invalidates alone). Round 661's 65,575/165 is
    SUPERSEDED: those 165 were a KEY COLLISION between three walk functions over
    11 call sites with different starting types and paths, not a dependency gap,
    so `flowWalkWithTripCheck` now takes a `kind` tag plus an `inputId` folding
    the starting type id with the path hash. **(b1) is therefore closed WITHOUT
    the read-set recorder** — the walk's dependencies ARE name-enumerable, and
    the recorder would have been solving a problem that did not exist. Prize
    correction: ~34 µs/walk × 41,389 = **~1.4 s** for the walk half (not the
    ~2.2 s rounds 660/661 reported off the coarse key), so M1's total lands at
    **~2.5 s ≈ 8–9%** with typeOfExpr's ~1.1 s.
    - [x] **(b2) DROPPED round 663 — measured, and the reachable prize is
      ~0.1 s.** The re-measure-before-investing instruction paid off. The
      expression memo's whitelist (Intrinsic/Interface/Reference) silently
      excludes ~134 k of ~618 k getTypeOfExpression calls (22%) — obj 102,102,
      unions ~29 k, Intersection 1,719 — precisely because those kinds are
      "freshly minted per call", which IS the non-canonical-output problem. Of
      those excluded calls, **62,949 are same-epoch STRUCTURAL repeats** (only
      347 genuinely differ), so interning would make them servable. But the
      by-kind split is the deciding number: **obj = 47,629 (76%)**, unions
      ≈ 12.7 k, Intersection 495. Object-type freshness is DELIBERATELY
      load-bearing (the round-435 freshObjLitRange relation machinery — the
      whitelist comment says so explicitly), so the 47.6 k / ~0.34 s half is
      not available without reopening relation semantics; the safely internable
      union+intersection part is ~13.2 k ≈ **~0.1 s**. Against the ~1.4 s live
      walk memo that is already sound at zero wrong serves, that is not worth
      the risk — so (b2) is dropped and (c) goes straight to the live memo.
      Revisit only if union interning becomes desirable for another reason
      (INV.5 canonical types would subsume it).
  - [x] **(c) LIVE — landed round 664 at −2.93% wall (−833 ms), the arc's
    first measured win.** `flowWalkWithTripCheck` serves from a memo keyed
    `(reference nodeId, fileHash, walkKind, inputId)` carrying the dependencies
    the walk read (FlowGraph instance + the Type instances bound to the
    reference's ROOT NAME in currentLocalTypes / narrowedDeclaredTypes).
    Interleaved A/B, 6 pairs, alternating order, no recompile between
    measurements: pre median 28,433 ms → post 27,600 ms, **−833 ms = −2.93%,
    post wins 5/6**. Instrumented: walks executed 111,248 → 69,859 (40,542
    served), narrowWalks 3,791 → 2,756 ms. The net is SMALLER than the ~1.4 s
    the shadow predicted because the memo pays key+dependency lookup on ALL
    walks to skip 37% — worth remembering when sizing the next memo: shadow
    "servable time" is an upper bound, not a forecast. All three round-663
    hazards handled (never store a tripped walk; `Any?` + cast sound because
    walkKind is in the key; shadow classification retained under
    `--passTiming`). Gates: suite 12,507/0; `--listAll` ×8 byte-identical on all
    eight profiles — no diagnostic moves anywhere, including no TS2563 drift;
    `--partitionCheck 2` EQUIVALENT ×8; warning-clean.
  - [x] **(d) DEAD before it was built — round 665 measured the would-save at
    30 ms, not ~1.1 s.** The measure-before-building instruction round 664 wrote
    into this item is what caught it. Instrument: decide with EXACTLY the live
    test (a confirmed shadow entry at the current epoch), decide BEFORE the core
    runs, and accumulate the core time of the OUTERMOST servable call only.
    Result: **30 ms over 71,310 outermost served calls ≈ 0.42 µs each = 0.12%**
    of a ~24 s compile — and a live memo would pay per-call overhead on ~618 k
    calls to collect it, so it must LOSE. WHY the round-660 estimate was 35×
    off: it multiplied the shadow's 149,742 hits by a MEAN call cost, but the
    servable population is the CHEAP TAIL (trivial identifiers/literals whose
    resolution is already cached) while the expensive calls — fresh minting,
    narrowing, relation work — are precisely the non-instance-stable ones the
    whitelist excludes. Applying an aggregate mean to a non-uniform population
    is the same error class as round 662's key collision. It also EXPLAINS the
    documented round-596 dead-end (a live per-node expression memo measured 1–3%
    SLOWER interleaved): that was observed but unexplained, and 30 ms is the
    explanation. Do not revive without a NEW mechanism that makes the expensive
    calls servable — canonical types (INV.5) would be that mechanism, not a
    better fence.
- [x] **(M2) SIZED round 666 and PARKED as not-locally-demonstrable — the box,
  not the design, is the binding constraint.** Probed BEFORE writing code (the
  discipline round 665 asked for), using the `--workers N` share-nothing mode
  that already exists (INV.6(6c1)). Compiler profile, 2 reps each:
  **seq 27,873 ms | w2 24,669 (−11.5%) | w4 27,905 (+0.1%)** — w2 helps, w4 is
  flat, exactly the "w4 flat" the item recorded, and STILL flat after M1's memo.
  Solving seq-vs-w2 as `seq = R + P`, `w2 = R + P/2`: only **P ≈ 6.4 s (23%)
  divides**, with **R ≈ 21.5 s (77%) non-divisible**, so the infinite-worker
  floor is ~21.5 s = a 23% best case even before contention. WHY, from the code:
  each worker does `sourceList.map { workerBinder.bind(it) }` — a FULL re-bind of
  EVERY file — and then builds a full `Checker` whose ~318 program-wide
  collectors all run; only the per-file spine is narrowed by
  `assignedFileNames`. So the duplicated-per-worker term is the whole of R, and
  Phase 1 (compute the collectors once, freeze, share) attacks at most the
  non-spine part of checker-init, measured at **~3.3 s** (checker-init 24.2 s −
  checkSpine 20.9 s + outside-pass). On 4 saturated cores that is ~2.5 s of CPU
  reclaimed but no wall win — w4 is already contention-bound, which is why it
  regresses against w2. VERDICT: the work is sound and would matter on a bigger
  machine, but on 4 cores / 7.7 GB it cannot be demonstrated, and this arc's rule
  is not to land unmeasurable perf work. ~~What would change the verdict: a host
  with ≥8 real cores (re-run this exact probe first)~~ **UNPARK CONDITION REWRITTEN
  ROUND 740 (PERF.HW) — "≥8 real cores" is NECESSARY BUT INSUFFICIENT.** The probe
  was re-run and measured the thing this item never checked: **a SEQUENTIAL run
  already consumes 3.15 of the 4 cores** (85.6 s user CPU / 27.1 s wall; ~2.2 cores
  of it JIT, 0.11 GC), so only ~0.85 cores are free and every worker level saturates
  at the same ~3.6-core ceiling. The measured requirement is therefore **≥8 cores
  *net of* the ~3.2 the JVM's own JIT/GC consume during a ~27 s cold run, i.e.
  realistically ≥12** — and that tax is **FIXED per JVM** (it does not grow with
  worker count), so a larger host simply out-sizes it. Revival order, unchanged in
  spirit but now with numbers behind it: **(a) close the `--workers` correctness
  divergence (PERF.HW.a) — it emits 62 diagnostics against sequential's 46; (b)
  shrink R — the full per-worker re-bind is still the single biggest identified
  duplication; (c) only then re-probe, on a ≥12-core host.** The prize is capped at
  **1.25x** by the w1/w2 Amdahl fit regardless.

**EP — Emit parity (owner-authorized 2026-07-12: "output parity, including reported errors").**
The offline v1 DoD checked emit COMPLETENESS (all files emitted, exit 0) but not
emit-BYTE parity with tsc. The round-483 emit diff (`scripts/emit-diff-tsc.sh`, xtsc
vs npm `tsc@6.0.3` on the `compiler` profile) found 8/78 byte-identical, 70/78
differing — but **none are miscompiles**; xtsc's output is semantically correct and
runnable. Three systematic families explain nearly all changed lines (sequenced
cheap-first to shrink the diff before tackling the hard cross-file one):

- [x] **EP.3 Logical/nullish-assignment downleveling** (`||=`/`&&=`/`??=` below
  ES2021). DONE round 484 (2026-07-12): `Transformer.downlevelLogicalAssignment` —
  `a ||= b` → `a || (a = b)` etc., with side-effecting property/element receivers
  captured into temps (`(_a = obj())[_b = key()] || (_a[_b] = 6)`, tsc-faithful temp
  naming). ~284 sites in the compiler profile. Gated `effectiveTarget < ES2021`;
  corpus has ZERO files exercising these operators so it's pinned by
  `LogicalAssignmentDownlevelTest` only. KNOWN RESIDUAL: a `??=` target BELOW ES2020
  keeps a native `??` (not further downleveled — ES2020 is the tested/dashboard
  target); close when a sub-ES2020 `??=` case appears.
- [x] **EP.2 CLOSED — every live sub-item landed (2a, 2b, 2d/e/f, 2g, 2h) and
  2c was SKIPPED-BY-OWNER; checkbox reconciled round 687. RE-SCOPED round 673
  by classifying the residual: it is NOT mostly formatting.** Every differing hunk in the 47 remaining files was
  classified (1,335 hunks total): **482 residual qualified access**, 173 other,
  **128 whitespace/wrap only**. So formatting is under 10% of the residual and
  the const-enum family — supposedly 96% closed — still dominates. Three
  distinct sub-targets, in value order:
  - [x] **EP.2a DONE round 674 — 128 → 1.** `emitArrayLiteral` re-emits each
    element's same-line trailing comments after `emitExpression(element)`,
    guarded by `element !is NumericLiteralNode` because a numeric literal
    already emits its own; `StringLiteralNode` does the same and was NOT
    excluded, so string-valued const enums printed their label twice (hence only
    `Extension.*` showed it). BOTH array branches carry the guard — I patched
    the MULTILINE one first and the repro did not change, which is what pointed
    at the single-line branch where the real trigger was; both fixed. An
    emitter probe proved the NODE held exactly ONE comment, localising the fault
    away from the transformer in one run. Measured: double-comments 128 → 1,
    total differing hunks 1,335 → 1,307, byte-identical files unchanged at 31/78
    (those hunks live in files that still differ for other reasons — hunk-level
    and file-level progress are different measurements). Gates: 6 pins
    (ArrayLiteralConstEnumCommentTest — they COUNT occurrences, since a
    substring check passes on doubled output, plus a negative control that a
    genuine source comment still survives); suite 12,526/0 with every JS
    baseline byte-exact despite touching the printer. RESIDUAL: 1 occurrence of
    a different shape, worth a look when convenient.
  - [ ] ~~EP.2a (original)~~ — **THE DOUBLE-COMMENT DEFECT (128 occurrences) — do this first,
    it is malformed output, not a cosmetic.** We emit
    `".jsx" /* Extension.Jsx */ /* Extension.Jsx */` where tsc emits one
    comment. REPRO IS THREE LINES (saved at `scratchpad/dblcomment`): a const
    enum imported cross-module, then `export const arr = [Ext.Cts, Ext.Cjs]`
    emits each element's label TWICE while a plain `const one = Ext.Cts` is
    correct — so the ARRAY-LITERAL element path transforms the element twice.
    Real source shape: checker.ts:2550
    `fileExtensionIsOneOf(fileName, [Extension.Cts, Extension.Cjs])`.
  - [x] **EP.2b DONE round 675 — it was HEX literals; gap 675 → 70 reads.**
    `CharacterCodes` resisted while `SymbolFlags`/`Extension` inlined from the
    same types.ts because those are decimal/string-valued and CharacterCodes is
    almost entirely hex. All THREE const-enum evaluators parsed with
    `text.toDoubleOrNull()` — decimal-only (Kotlin takes a hex FLOAT `0x1.8p3`
    but not a hex INTEGER `0x7F`), so the member became silently un-inlinable
    with no error at all. Fixed with one shared `tsNumericLiteralToDouble`
    (Types.kt: hex/binary/octal + `_` separators, rejects BigInt) wired into the
    Transformer's same-file collector, the Checker's `literalConstantValue`, and
    the Checker's `evaluateEnumInitializer` (the cross-module `enumValues`
    table). Fixing the first two left cross-module broken — the same-file repro
    went green while the direct-import one did not, which is how the third site
    surfaced. Measured: const-enum reads 17,443 → **18,048** (tsc 18,118),
    byte-identical files unchanged at 31/78. Gates: 8 pins
    (HexConstEnumInliningTest — all three paths, binary/octal, negative+zero
    guard, the parser across bases, plus BigInt/garbage negative controls since
    a wrong value would silently corrupt emitted constants); suite 12,534/0;
    `--listAll` ×8 byte-identical, which matters here because the change alters
    enum VALUES. RESIDUAL: 70 reads, plus `tracing.Phase.Bind` (const enum
    behind a namespace) and one import-elision difference.
  - [ ] ~~EP.2b (original)~~ — **The 675-read const-enum residual, dominated by
    `CharacterCodes` (638 of the qualified-access occurrences).** Why that one
    enum resists while SymbolFlags/Extension inline is the question to answer
    first — it is declared in types.ts like the others, so the difference is
    likely in how its members are reached or valued (it is large and
    char-code-valued). Also visible: `tracing.Phase.Bind` (a const enum behind
    a namespace) and one import-elision difference (`ts_js_1.version` vs
    `version` in builder.js).
  - [x] **EP.2d/e/f DONE round 677 — the const-enum family is CLOSED at 18,118
    inlined reads vs tsc's 18,118.** Three unrelated causes, each found by the
    gate: (2d) parameter DEFAULT VALUES were never transformed at ES2018+ —
    `flattenRestParameters` returned the parameters raw from its early return,
    and it owns the plain FunctionDeclaration branch, function/arrow
    expressions and constructors, so every default there skipped
    `transformExpression` wholesale (invisible to the corpus, whose emit tests
    sit mostly BELOW that threshold); (2e) a const enum nested in a NAMESPACE
    did not inline through a barrel — the star closure yields the namespace and
    the binder flags a const-enum-only namespace `ConstEnum`, so the flag test
    passed while the id-keyed `enumValues` lookup missed (`descendToConstEnum`);
    (2f) COMPUTED initializers did not fold in the same-file collector —
    `const enum Connection { Up = 1 << 0, …, UpDown = Up | Down }` in tsc's
    debug.ts, worth 25 of that file's 121 reads. 2f surfaced only because 2e did
    NOT move the count: re-running the gate with `--keep` and counting per file
    beat a confident wrong model, the third time this arc (cf. 669, 672). The
    numeric operator table now lives once in `tsFoldNumericBinary`. Gates: suite
    12,566/0/3 (+32 pins), `--listAll` ×8 unchanged (46×7, harness 94).
  - [x] **EP.2g DONE round 678 — the const-enum family is closed FOR REAL
    (true gap 34 → 0; byte-identical 31 → 32).** A const enum reached through a
    VARIABLE whose declared type is the namespace (`export let tracing: typeof
    tracingEnabled | undefined`, used as `tracing.Phase.Bind`) never inlined:
    resolution stops at the variable, tsc goes through the type.
    `namespaceBehindTypeofVariable` follows the `typeof` annotation, wired into
    both the import and direct paths; the variable keeps its runtime identity.
    **This also CORRECTS round 677's "closed at parity" claim** — the script's
    family-1 counter requires a NUMERIC value, so string-valued enums are
    invisible to it on both sides. Measure with a per-file count of all
    `/* X.Y */` comments. Suite 12,573/0/3, `--listAll` ×8 unchanged.
  - [x] **EP.2h DONE round 678 — the 32 "extra blank line" hunks were an
    ordinary printer defect, not part of the formatting subsystem.**
    `emitInnerComments` wrote a newline after a `//` comment and then the next
    comment wrote a second for its `hasPrecedingNewLine`, so consecutive line
    comments gained a blank between them (tsc keeps a comment block before an
    `else if` adjacent). Four lines, byte-identical to tsc on the repro
    including the source-has-a-blank case. Hunks **368 → 302**, add-a-line
    family **32 → 0** (also cleared 34 entangled CONTENT hunks), byte-identical
    **32 → 33**/78. Suite 12,579/0/3, all JS baselines byte-exact.
  - [x] **EP.2c SKIPPED-BY-OWNER (round 678, 2026-07-25).** Asked explicitly and
    the answer was "skip — move on": byte-parity is NOT a v1 exit criterion
    (v1 = zero FPs + all files emitted + zero crashes, all three already met),
    so a multi-round printer subsystem with no v1 impact is not where rounds
    should go. The emit arc therefore CLOSES at **33/78 byte-identical**, with
    families 1 (const enums) and 3 (logical-assign) at full parity and the two
    genuine defects found along the way (EP.2a double comment, EP.2h blank
    line) fixed. If ever revived, the residual and its shape are recorded
    below — do not re-derive it. After rounds 677–678 the residual is
    **302 hunks: 266 CONTENT** (the ternary/binary wrap-and-indent structure —
    the genuine subsystem), **35 indent-only**, **1 collapsed wrap**. Nothing
    cheap remains: the two shapes that looked separable (EP.2a's double comment,
    EP.2h's blank line) were both ordinary defects and are fixed. Classified:
    **78 same line count but different continuation INDENT DEPTH**, differing in
    BOTH directions (checker.js has us indenting 4 too many in one wrapped `&&`
    chain and 4 too few in another — no single constant fixes it); **47 where
    tsc has MORE lines** because we COLLAPSE a wrap it keeps (binder.ts's
    `const name = isComputedName ? A` / `    : B ? C` / `    : D` — tsc
    reproduces the SOURCE's line structure with `:` at line start); **7 where we
    ADD a wrap** tsc does not. All three need the emitter to model tsc's
    line-breaking AND indent decisions for wrapped binary/ternary expressions —
    source-structure preservation for expressions, analogous to the existing
    `multiLine` flags on object/array literals but much broader. SIZING: 132 of
    1,307 residual hunks (~10%), few files would flip to byte-identical on its
    own (they carry other diffs), and it is the highest corpus-regression risk
    in the codebase — this printer is pinned by all 12,534 tests. If it goes
    ahead: one rule per commit, full suite after each, gate re-run to confirm
    the diff SHRINKS.
  - [ ] ~~EP.2c (original)~~ — **Multi-line expression formatting** — the original item, now
    known to be ~128 hunks: tsc puts a wrapped ternary's `:` at LINE START
    (`? [...]` / newline / `: [`) where xtsc trails it. Highest
    corpus-regression risk (this is the printer the 12,520-test corpus pins),
    lowest count — so it goes LAST, one placement rule per commit, full suite
    after each, and re-run the gate to confirm the diff SHRINKS.
  SUPERSEDED NOTE: — **WAS UNBLOCKED round 672**
  (the emit-diff gate is live, which its own text required), and it is now the
  LARGEST remaining emit-parity family: 47/78 files still differ while the
  const-enum family is 96% closed, so most of that residual is formatting. The
  shape is visible in the utilities.js diff — tsc puts a wrapped ternary's `:`
  at LINE START (`? [...]` / newline / `: [`) where xtsc trails it at line end.
  Corpus-regression risk is real (this is the printer the 12,520-test corpus
  pins), so: one placement rule per commit, full suite after each, and re-run
  the gate to confirm the diff SHRINKS rather than merely changes.
  SUPERSEDED NOTE: — **WAS BLOCKED OFFLINE
  (round 667).** Its own text requires "the emit-diff gate in place", and that
  gate needs a reference tsc: this box has **no `node`, no `npx`, no `tsc`, and
  no `tsc.js` anywhere** (the bench-history tsc/tsgo columns come from CI, not
  locally). Do not start EP.2 here — without the gate there is no way to know
  whether a printer change moves the diff toward or away from tsc, and the
  printer is exactly what the green corpus pins. Revive when a reference tsc is
  available (see EP.0).
- [x] **EP.1 DONE round 669 — and its dashboard premise is falsified too.** The
  barrel hop now inlines: `1 /* Kind.B */`, `"x" /* Names.X */`,
  `0 /* B.Kind.A */`, with the import elided and no `__importStar` helper —
  tsc's exact shape. Cause was the same as EP.1a's: both const-enum entry points
  (`resolveConstEnumMemberAccess`, `isConstEnumAlias`) reach the enum through
  `resolveAlias`/`resolveNamePath`, which walk `symbol.exports`, and a star
  re-export never populates the barrel's own export table.
  `constEnumSymbolThroughStars` follows the target module's star closure and
  returns a symbol ONLY when it carries `SymbolFlags.ConstEnum` —
  const-enum-only by construction, so it can never feed a general type
  resolution, which is what keeps it clear of the `resolveAlias` star dead-end
  (TS2315 ×466). **MEASURED, not assumed: ZERO effect on the tsc profiles.**
  Before AND after, the emitted `compiler` dist has 1,663 numeric + 18 string
  inlines and **0 residual `ts_N.X.Y`** — identical (verified by stash/rebuild
  precisely so the 1,681 inlines would not be mis-attributed to this change). So
  EP.1's "highest impact, ~93% of the changed lines" sizing is stale exactly like
  its premise was: the tsc profile already inlined everything, and the barrel gap
  is a shape those profiles never hit. Kept because the gap was real (repro +
  pins) — general-correctness value for the post-v1 "any project" horizon, NOT a
  dashboard win. Gates: 7 pins (BarrelConstEnumInliningTest, incl. a two-barrel
  chain and two negative controls for regular enums and
  preserveConstEnums/isolatedModules); suite 12,520/0 with every JS baseline
  byte-exact; `--listAll` ×8 byte-identical.
- [x] **EP.0 DONE round 672 — the gate is LIVE (owner authorised the network
  install).** Node v24.18.0 + `typescript@6.0.3` under `build/tools`
  (gitignored; tarball, not apt — no system mutation). Run it with
  `scripts/emit-diff-tsc.sh --ref-tsc build/tools/tsc-ref/node_modules/.bin/tsc`
  (put `build/tools/node/bin` on PATH first). The reference is npm tsc 6.0.3
  against a pinned repo whose package.json says 6.0.0, so the three FAMILY
  counts are trustworthy (version-stable behaviours) while the small residual
  tail carries version noise — building tsc at the pinned commit remains the
  ideal and is still open. Its FIRST RUN earned its keep by falsifying round
  669 (see EP.1). Baseline at round 672: **31/78 byte-identical**, const-enum
  reads 17,443 vs tsc 18,118, logical-assign 15 vs 15.
  SUPERSEDED NOTE: — **WAS BLOCKED OFFLINE
  (round 667): there is no reference tsc on this box** (no node/npx/tsc/tsc.js;
  `scripts/emit-diff-tsc.sh` exists but cannot run). Unblocking needs either a
  network install of node + `typescript`, or building tsc at the pinned commit —
  both outside the offline envelope, so this is a user-gated decision, not
  agent work. Until then EP progress is limited to what the CORPUS and local
  pins can gate (EP.1/EP.1a qualify; EP.2 does not).

Session note (round 484) has the full family breakdown + methodology.

**INV — the M5 architecture-inversion arc (re-scoped 2026-07-13, owner; supersedes
M5.1–M5.7 — mapping and full design in `docs/ARCHITECTURE-RETHINK.md`, READ IT FIRST).**
Ground rules for every INV item: corpus suite green + 8-profile FP floors unchanged +
`--listAll` byte-diff empty for behavior-preserving steps + a bench TSV row per landed
item; decompose into the smallest standalone suite-gated commits; micro-opt rounds
against the flat profile are CLOSED (only an INV.0-evidenced ≥5% single lever may
interrupt the arc).

- [x] **(cta-m3e) Lift the anchor-SIMPLE restriction — reproduce the legacy
  nested-dispatch localTypes recordings spine-side (queued round 570c with the
  design from the BarrelCheckDefinedReturnTest root-cause).** The blocker: legacy
  nested-scope dispatches RECORD into the shared `currentLocalTypes` and the spine
  frames have no reproduction, so an anchored statement after a switch/if/loop
  reads an incomplete map. Design notes (verified in-code round 570): (a) the leak
  is PER-ARM — switch clauses LEAK (clause dispatch shares the map), a NARROWING-
  wrapped if-then (extractNullNarrowing non-null — a pure function of the
  condition, callable at spine time) DISCARDS its recordings on restore, a
  non-narrowed if-then Block LEAKS (the Block arm copies varTypes but NOT
  currentLocalTypes), loop/try bodies leak via the same Block arm; (b) the
  mechanism: a RECORDING-ONLY sandwich at nested VariableStatement enters within
  an active fn frame — install the frame maps, run the real
  checkVarDeclAssignability under a diagnostics mark, truncate ALL its
  diagnostics (nested statements stay legacy-owned for emission), keep the map
  writes; skip inside narrowing-discarded regions; (c) spine statement-position
  Block/clause frames already model the map SHARING — the narrowed-if discard
  needs a COPIED-map frame rule keyed on extractNullNarrowing; (d) gates: the
  barrel repro shape as a local pin (switch-clause recording feeding a later
  anchored statement's member reduction), corpus + listAll ×8. Alternative if the
  recording-only sandwich disturbs first-touch caches: migrate the nested
  dispatchers' arms themselves (bigger). DONE round 571 — the recording-only
  sandwich landed clean (one extra invariant found: TS2563 trip-state suppression
  during recordOnly, CfaTooLargeBailTest); see the session note.
- [x] **INV.0 Instrument the multiplier.** DONE round 491 (2026-07-13):
  `PassTiming.kt` + non-inline `pass(name) {}` around all 514 init dispatch calls +
  the three counters (`getTypeOfExpression` calls/distinct with per-pass attribution,
  `nodeTypes` cacheable/bypassed/hit, depth-0 flow walks at `flowWalkWithTripCheck`),
  behind the `--passTiming` CLI flag; off-mode byte-identical (listAll A/B + wall
  parity) + suite green (+7 local). The table (round-491 session note): checker-init
  = 83% of wall; top-3 passes 38.6% (property-access / assignability / call-types,
  458k of 595k getTypeOfExpression calls, 84k flow walks — 68% from
  checkPropertyAccess); 474 sub-100 ms passes sum 36.5% = the multiplication tail.
  That note's cost-ordered worklist IS the INV.4 migration order.
- [x] **INV.1 Concurrent front-end — the owner's Flow beachhead (owner-approved
  kotlinx-coroutines-core dependency, 2026-07-13).** Sub-steps: (a) DONE round 492 —
  the dep was already in commonMain; landed the `runCompilerPipeline` expect/actual
  seam (JVM `runBlocking`) + the import-graph crawl as a cold sequential Flow
  (`crawlImportGraph`, ProjectCompiler) with the load-bearing emission-order
  contract documented at the seam (suite +3, listAll A/B byte-identical); (b) DONE
  round 493 — read+decode on `Dispatchers.IO` (`pipelineIoDispatcher`
  expect/actual), extraction parse on `Dispatchers.Default`, bounded
  `flatMapMerge(16)` per frontier (`readAndScanBatch`); resolution + emission stay
  sequential per frontier so emission stays first-discovery order (the binder stays
  sequential; parser audited — no shared mutable state); (c) DONE round 493 —
  corpus green (+6 local) + 3× `--listAll` byte-identical vs the (a) binary; (d)
  DONE round 493 — interleaved A/B −0.8 s (~3%) on the compiler profile + bench
  TSV row.
- [x] **INV.1(e) Kill the double parse — reuse the crawl's parses in the core.**
  DONE round 494 (2026-07-13): `computeParserFlags` (the shared single source of
  truth for the option-derived `Parser` flags, used by the core's parse sites AND
  the crawl), `ParsedSource.preParsed` carrying `PreParsedFile(content, flags,
  sourceFile, diagnostics)`, and the core's multi-file site reusing an entry ONLY
  on an exact content+flags match (else re-parse — reuse is a pure optimization).
  Verified: suite +6 (Inv1PreParseReuseTest — sentinel-tree reuse proof + both
  mismatch gates + driver-path counters), `--listAll` byte-identical on compiler
  AND services, reuse fires 78/78 (`--passTiming` counters), interleaved wall A/B
  neutral within noise on both profiles (the parse leg is small next to the
  checker; the point is one canonical tree per file — the INV.2 enabler).
  CLAUDE.md gotcha: a new option-derived Parser argument must extend
  `ParserFlags`, never a parse site inline, or the match reuses a wrong tree.
- [x] **INV.2 Bind the world** — COMPLETE round 499 (all four sub-items landed;
  the tables' mass consumption is INV.4's migration). Decomposed round 494
  (facts verified in-code:
  `Node` is a sealed interface + ~138 data classes with single-interface supertypes
  `) : Expression/Node/TypeNode/Statement/Declaration/ClassElement`; there is NO
  generic child-walk anywhere; nodes have no parent/id fields; `Symbol.id` is a
  GLOBAL companion `nextId++` (Types.kt:116–127, the ~350-test reshuffle anchor);
  `nodeKey` is the cross-file-colliding `(pos<<32)|end`). Work the sub-items in
  order, one commit each:
  - [x] **INV.2(a) AST identity foundations.** DONE round 495 (2026-07-13):
    `NodeBase` (nodeId/parent, NOT implementing Node — preserves sealed-`when`
    exhaustiveness) + 138 supertype edits + `SourceFile.nodeCount`; canonical
    `forEachChild` (exhaustive sealed `when`) + iterative preorder
    `indexSourceFile` hooked into `Parser.parse()`. Pinned by the jvmTest
    reflection oracle (`ForEachChildOracleTest` — componentN diff over fixtures +
    all 78 real tsc sources) + `Inv2NodeIndexTest` (dense preorder / parent
    chains / copy-unindexed / 30k-chain-on-plain-thread). Suite +10 (10,228),
    `--listAll` byte-identical, wall neutral. Gotchas: NodeBase LUB trap +
    power-assert node-toString trap.
  - [x] **INV.2(b) Pilot consumer.** DONE round 496 (2026-07-13):
    `FlowGraph.flowAt` — nodeId arrays pre-computed from the finished map
    (preserves the nodeKey extent-ALIASING) + identity ownership check
    (synthesized/foreign nodes take the legacy path); 5 checker sites migrated;
    suite +3, listAll byte-identical, wall neutral. JFR verdict: getNode ≈6.7%
    of samples but nodeToFlow only ~4% of that slice (~0.3% wall) — mechanism
    validated; the mass-migration targets are the HOT maps (walk memos, INV.4
    per-node type cache), not more cold tables. `nodeTypes` rejected as pilot
    (structural cross-file keying — INV.5 territory).
  - [x] **INV.2(c) Full lexical binding, additive.** Scope symbols from a SEPARATE
    id space (never the global `nextId` sequence — the reshuffle hazard); existing
    `locals`/`globals` byte-unchanged; new tables unconsumed until INV.4.
    - (i) DONE round 497 (2026-07-13): function-like containers —
      `bindLexicalScopes` (Binder.kt) walks the whole tree iteratively after
      conventional binding, building per-nodeId `LexicalScope`s
      (`BinderResult.lexicalScopes`): SourceFile root aliases file locals,
      ModuleDeclaration aliases the merged exports (chained per dotted segment,
      the B512 rule), the 7 function-like kinds + static blocks get fresh tables
      (type params, params minus `this`, fn-expr self-name, body-top-level
      decls, `var`s hoisted from any block depth). `Symbol.scopeSymbol` mints
      ids ≤ −2; a delta-probe test pins zero global-id consumption. Suite +14
      (Inv2LexicalScopeTest), listAll byte-identical, interleaved wall
      position-balanced +0.8% (noise band).
    - (ii) DONE round 498 (2026-07-13, same session): block-scope containers —
      every Block that is not a function-like's immediate body, for/for-in/for-of
      headers, CatchClause (binds the catch variable, destructuring included),
      SwitchStatement standing in for tsc's CaseBlock (our AST has none — the
      switch EXPRESSION routes to the OUTER scope by hand) — plus class scopes
      (type params; named class-expression self-name; class decorators outer),
      interface/type-alias scopes (type params), and enum scopes (aliasing
      main-bound exports; nested enums bind scope-space members also published
      on the scope symbol's exports, gated `id ≤ −2` so main symbols stay
      untouched). Design dividend: the phase-(i) `isDirectBodyChild` gates for
      block-scoped declarations DISSOLVE into `scope.existing == null` (every
      fresh scope IS the correct nearest block-scope container); `var` gains the
      real `varHoistTarget` walk-up. Block-nested function declarations use
      strict/module semantics (bind to the block). Suite +6 (20 total in
      Inv2LexicalScopeTest — the phase-(i) negative controls flipped to
      positive location asserts), listAll byte-identical, interleaved wall ×6
      both orders neutral.
  - [x] **INV.2(d) B83.5 dissolution pilots.** DONE round 499 (2026-07-13): the
    canonical site — `checkPropertyAccessInStatement`'s ClassDeclaration branch —
    now resolves a block-scoped class via `lexicalScopeSymbol` (parent-chain walk
    over `currentLexicalScopes`, set per file in `checkPropertyAccess`; legacy
    transient synthesis kept as the unindexed-tree fallback). Fidelity proven:
    suite green, listAll byte-identical on compiler AND services; and the pilot
    FIXES a real FP — a block-level `interface B` + `class B` merge now
    contributes interface members to `this` (the transient class-only symbol
    could not see them; measured: the pre-pilot checker emitted a false TS2339).
    Candidate analysis: the other two `Symbol(SymbolFlags.Class, …)` syntheses
    are NOT B83.5 scope-binding shapes and stay — the B511 clodule recovery
    (the class symbol is main-bound then OVERWRITTEN by last-wins, so it is in
    neither table) and the classExpressionAssignment display synthesis (a
    ClassExpression is never a scope binding). Mass consumption of the tables
    (the ~59 synthesis sites, `buildNestedFunctionMap`, the per-pass scope
    machinery) is INV.4's migration proper.
- [x] **INV.3 Per-file scoping — ARC COMPLETE round 513** ((a)-(d) all landed; checkbox reconciled round 612) — decomposed round 500 (facts verified in-code:
  `perFileScope` EXISTS and is already consumed at 4 sites — the 17.32b–e flips
  (TS2663-vs-TS2301, TS2552 candidate pool, resolveExpressionToSymbol, file-root
  TS2304) — so the earlier "never consumed" note was stale; the remaining
  migration surface is ~400 keyed `globals` consults; import aliases free-ride on
  the conflation because the general `resolveAlias` cannot follow ESM-`.js`
  specifiers / `export *` barrels / NamespaceImports — the FLOW-ONLY resolvers
  can, and the general-fallback variant measured a TS2315×466 flood at round
  409). End state: module files resolve own-locals + imports + true globals;
  the `mergeSymbolTable` conflation is retired for module files; the conflation
  ecology is deleted. Also lays the cross-file value-resolution groundwork EP.1
  needs. Work the sub-items in order, one commit each:
  - [x] **INV.3(a) Instrument the conflation dependency.** DONE round 500
    (2026-07-13): `globals` constructed as `InstrumentedSymbolTable` under
    `--passTiming` (plain map otherwise — zero added code on the hottest map);
    every keyed lookup classified against the per-file visibility model
    (TRUE_GLOBAL / SHARED / OWN_LOCAL / CONFLATED / UNSCOPED — see
    `GlobalsLookupClass`) by a classifier installed after init step 1b, with
    per-name + per-pass conflated/unscoped tables in the dump. Measured
    (compiler / services profiles): 2.71M / 4.92M keyed lookups — 71% / 79%
    MISSES (globals probed as a maybe-fallback everywhere), ownLocal
    530k/703k (flips to per-file trivially), CONFLATED 157k/217k concentrated
    in 608/845 names (almost all `types.ts` type names reached through barrel
    imports; services adds the round-442 value-space leaks `parent`/`error`)
    and 14–15 passes with the top 3 = 95–96% of conflated traffic = INV.0's
    top-3 wall passes (checkPropertyAccess / checkCallExpressionTypes /
    checkTypeAssignability), SHARED only 2.9k/4.0k (the chimera ecology's
    cost is per-lookup bail checks, not hit volume), unscoped 71.8k/97.1k
    (checkUnresolvedNames + outside-dispatch). Worklist: (b)'s primitive must
    resolve barrel-imported TYPE names; (c) starts at the three hot passes.
    Suite +5 (Inv3GlobalsLookupTest), `--listAll` byte-identical (off-mode),
    bench row in band.
  - [x] **INV.3(b) Per-file resolution primitive.** COMPLETE round 502:
    - (i) DONE round 501 (2026-07-13): `lookupPerFile(fileName, name)`
      (internal, unconsumed by checker paths) — perFileScope lookup with an
      ImportSpecifier-alias local resolved onward through
      `resolveImportedSymbolGeneral` (the kind-AGNOSTIC generalization of the
      flow-only resolver skeleton: ESM-`.js` strip + `export *` barrels +
      renamed re-exports via the star walk's NamedExports arm + re-import
      hops; memoized `importedSymbolGeneralCache`; ADDITIVE — the three
      kind-specific legacy variants stay untouched, their per-decl
      kind-filter-then-continue semantics differ; never wired into
      `resolveAlias` per the round-409 flood gotcha). KEY TRAP hit and
      pinned: mergeSymbolTable FLAG pollution means an Alias flag cannot
      identify an import alias — a barrel-imported name's TARGET symbol
      acquires the Alias bit from the importing file's merge, so the hop
      test must be declaration-based (`isImportBindingDecl` — the
      isValueExport gotcha applied to alias hopping). Degradations
      documented in the KDoc: unresolvable import / default-import /
      `import * as ns` / `import =` aliases return the alias symbol itself
      (callers keep their existing handling — extend when a (c) flip needs
      them); null strictly means "no per-file meaning" (the conflation
      leak). Pinned by Inv3PerFileLookupTest (direct
      `Checker(options, binderResults)` construction — a first for local
      tests — asserting symbol IDENTITY with the declaring file's binder
      locals across direct-`.js`/barrel/renamed-re-export/own-local/
      script-global/lib shapes + the foreign-module-local null and
      alias-degradation negative controls).
    - (ii) DONE round 502 (2026-07-13): pilot consumer — the TS2315/TS2346
      heritage-base "not generic" gate (`checkTypeArgumentConstraints`, the
      smallest nonzero pass in the (a) conflated-by-pass table with DIRECT
      pass-local consults) resolves through the NEW
      `globalsForFile(fileName, name)`, THE (c) flip shape: return the
      merged-globals INSTANCE whenever the name has a per-file meaning (a
      non-module-only name, or a module-only name the file declares/imports
      — probed via `lookupPerFile`; substituting the primitive's return
      directly would change symbol identity for lib/script names), null
      exactly where the legacy consult leaked a foreign module file's local
      (suppression-only at this site: real tsc never emits TS2315 for an
      unresolvable base). Supporting infra always-on: init 1b2 became
      `computePerFileVisibility` — publishes `moduleOnlyGlobalNames`
      (module-file local names minus lib/script/augmentation-visible), the
      INV.3(a) classifier installs on top of the same sets. Both mirrored
      consult sites flipped together (kept-in-sync contract); the conflated
      branch never touches `globals`, so the `--passTiming` conflated
      tables keep measuring only UN-migrated traffic. MEASUREMENT LESSON
      for (c): the post-flip instrumented run shows the pass STILL at 11
      conflated with the total lookup count EXACTLY unchanged (2,711,601)
      — the pass's conflated traffic comes from DEEPER shared machinery
      (`checkConstraintsForTypeArgs` → `getTypeFromTypeNode`), not the
      direct pass-local consults, which measured ZERO conflated hits on
      the compiler profile. Per-PASS attribution ≠ per-SITE: a hot-pass
      (c) flip needs per-site reasoning about which consults inside the
      pass actually carry the conflated traffic. Suite +7
      (Inv3GlobalsForFileTest — both leak-kill tests FAIL on the pre-flip
      checker, verified via stash; five preservation controls pass on
      both); `--listAll` byte-identical on compiler AND services.
  - [x] **INV.3(c) Flip resolution families onto the primitive** — COMPLETE
    round 509 (all four sub-items landed; conflated 157k → 917, the residue
    being the INV.3(d)-scoped shadow ecology). Decomposed
    round 503 from a MEASURED per-site attribution (a temporary 1:200
    stack-sampling probe on the classifier's CONFLATED branch, ~790 samples,
    probe reverted — evidence in the round-503 session note). The guessed
    site list above was WRONG: `getTypeFromTypeReference`'s globals fallback
    measured ZERO conflated hits and `resolveTypeNameToSymbol`'s Identifier
    entry only ~1.2% — the actual distribution:
    **~82% is ONE family, the enum-discriminant/kind-domain narrowing
    machinery** (`kindDomainKeysFromTypeNode` → `enumSwitchKeysFromTypeNode` /
    `enumMemberKeysOfTypeNode` / `kindDomainTypeDeclSymbol` /
    `resolveEnumSymbolForDiscriminant`, reached from `narrowByCallPredicate`
    via `applyConditionNarrowing`, plus smaller entries from
    `filterUnionByEnumDiscriminant`/`resolveCallOverload`), which resolves
    type names read from FOREIGN AST nodes — types.ts's union-member `.kind`
    annotations — while `currentFileLocals` points at the CHECKING file
    (exactly the top conflated names: JSDocFunctionType / FunctionTypeNode /
    ConstructorTypeNode / MappedTypeNode / ConditionalTypeNode). The
    per-file-correct key there is the NODE'S OWNING FILE (tsc semantics: a
    types.ts annotation resolves in types.ts's scope), NOT
    `currentCheckFileName` — a naive `globalsForFile(currentCheckFileName,…)`
    flip would silently kill narrowing in files that don't import the name.
    The rest: `identifier.fallback` ~3.8k + `propAccess.objExpr` ~3k (tagged
    counts), `checkPrivateMemberAccess`, `getTypeOfIdentifier ←
    isCalleeResolvable`, `resolveFlowCalleeDecl ←
    flowCalleeMayHaveAssertEffects`, `computeRawTypeOfPropertyAccess ←
    getCalleeType`, `typeNodeDefinitelyNonNullish`, `pmrCheckAccess`,
    `mam.objectExpr`/`mam.recvSym` (~63 each). Sub-items, one commit each,
    every flip suite+listAll-gated on compiler AND services:
    - (i) DONE round 504 (2026-07-13): the node-keyed resolution primitive —
      `owningSourceFile(node)` (NodeWalk.kt: parent-chain walk to the
      SourceFile, null for unindexed `copy()`/synthesized/detached nodes,
      defensive hop bound) + `lookupPerFileForNode(node, name)` =
      `globalsForFile(owner.fileName, name)` with legacy-merged-consult
      degradation for ownerless nodes. Additive/unconsumed; pinned by
      Inv3NodeKeyedLookupTest (direct construction — a foreign-node
      annotation resolves under its OWNING file's visibility to the same
      merged instance; an owner without the name yields null (the leak);
      an importing owner keeps resolving; an unindexed copy degrades to
      legacy; lib names never nulled).
    - (ii) DONE round 505 (2026-07-13): the kind-domain/enum-discriminant
      family (~82% of conflated traffic) flipped onto the node-keyed
      primitive — `resolveEnumSymbolForDiscriminant`/`kindDomainTypeDeclSymbol`
      thread a `keyNode` (all 5 call sites), and the alias fallbacks in
      `enumSwitchKeysFromTypeNode`/`enumMemberKeysOfTypeNode` (incl. the
      round-477 import-alias fallback) consult `lookupPerFileForNode(node,
      name)`; `currentFileLocals` stays the first consult everywhere.
      Companion: `globalsForFile`'s proven-visible branch reads UNCLASSIFIED
      (`InstrumentedSymbolTable.getUnclassified`) under `--passTiming`, so a
      legitimate foreign-node hit — CONFLATED against the CHECKING file's
      locals — no longer pollutes the migration tables. Suite +5
      (Inv3KindDomainNodeKeyTest — leak-kill FAILS pre-flip via stash;
      4 preservation controls pass both sides); listAll byte-identical on
      compiler AND services.
    - (iii) **Flip the current-file-keyed value/callee sites** — these read
      names from the CURRENT file's own AST; node-keying by the name's
      IDENTIFIER node is the uniform shape (equals current-file keying for
      own nodes); suppression-only where the name classifies conflated.
      Phase 1 DONE round 506 (2026-07-13): the protected-member cluster
      (pw/pmr/pm, TS2445/TS2446 — `pmrCheckAccess`'s static consult, the
      ctor-init consult, and the `pwResolveClass`/`pmrResolveClass` funnels
      every heritage walker feeds) keys by the name Identifier via
      `lookupPerFileForNode` — the heritage walkers wrap a REAL indexed
      Identifier in a synthesized TypeReference, so keying by `typeName`
      (never the wrapper) needs zero signature changes; a fully-synthesized
      identifier (pmrLocalClass's from-text one) degrades to the legacy
      consult inside the primitive. Suite +5 (Inv3ProtectedNodeKeyTest —
      both leak-kill tests FAIL pre-flip via stash: the leaked resolution
      manufactured bogus TS2445 about a class the file never imports);
      listAll byte-identical on compiler AND services. Phase 2 DONE round
      507 (2026-07-13): the bare-Identifier VALUE/receiver/callee cluster —
      checkPrivateMemberAccess, getCalleeType's Identifier branch,
      resolveFlowCalleeDecl (+ the extracted currentFileNestedPredicateDecl
      preserving round-471 narrowing from the direct==null fallback too),
      resolveNamespaceMemberFnDecl, the three ns-fallback receiver
      resolvers (computeRawTypeOfPropertyAccess /
      resolvePropertyAccessToSymbol / propertyAccessChainIsNamespaceQualified),
      isCalleeResolvable, checkPropertyAccessAssignment's ns base, the two
      mam receiver consults, and the protected-ctor heritage walks
      (findEffectiveConstructorVisibility/classExtendsOrIs) — all keyed by
      the name's own Identifier node. Conflated 20,941 → 10,034 (−52%);
      suite +9 (Inv3ValueCalleeNodeKeyTest — 4 leak-kills FAIL pre-flip via
      stash); listAll byte-identical on compiler AND services; bench in
      band. Phase 3 DONE round 507b (2026-07-13) — (iii) COMPLETE:
      `getTypeOfIdentifier`'s globals fallback node-keyed (the round-442
      by-NAME dead-end does NOT reproduce per-FILE — imports resolve
      through the visibility probe to the same merged instance; pinned by
      Inv3IdentifierTypingNodeKeyTest incl. the import-driven
      initializer-inference control from the round-442 regression family),
      plus a fast path in `lookupPerFileForNode` (non-module-only names
      skip the parent walk — the fallback is ~2M calls/compile). Conflated
      10,034 → 6,165 (cumulative 20,941 → 6,165, −71%); `factory` gone;
      checkImplicitAnyParameters 2,608 → 171, checkUncalledFunctions 968 →
      189. Suite green 10,298 → 10,302 (+4); listAll byte-identical on
      compiler AND services; bench +4.0% single-run = the documented
      box-drift band (~126k parent walks ≈ negligible by construction).
      Residue ~6.2k = the (iv) type-position tail (types.ts type names
      reached via typeNodeDefinitelyNonNullish / resolveTypeNameToSymbol /
      getTypeFromBaseTypeExpression) + ~500 value-name lookups in the
      shadow-detection ecology (registerNestedGlobalShadow*/
      applyBodyLocalShadowing/shadowNestedFunctionNames ask "does a merged
      global collide" — they die with INV.3(d), do not flip them) + tiny
      tail sites (emitTs2345ForBareTpArgToConstrainedTpParam,
      getOverloadImplementationRelated, calleeReturnAnnotationForImplicitAny
      — fold into (iv)'s re-measure).
    - (iv) **Flip the type-position tail**. Leg 1 DONE round 508 (2026-07-13):
      `resolveTypeNameToSymbol`'s Identifier branch + `typeNodeDefinitelyNonNullish`'s
      two fallbacks flipped JOINTLY per the round-507c order constraint, with
      the two call-site trailing `?: globals[name]` fallbacks
      (`getTypeFromTypeReference`, `checkConstraintsInTypeNode`'s TS2315
      emitter) gated to QualifiedName — for Identifier names they were
      byte-redundant pre-flip and would silently RE-LEAK the node-keyed null
      post-flip (the trap now in the CLAUDE.md INV.3(c) entry). The full
      suite caught a REAL visibility gap the flip exposed:
      `lookupPerFileForNode` now grants a node inside a `declare module
      "<relative-spec>"` AUGMENTATION block the augmented module's direct
      named exports (the round-443 rule; the innermost string-named
      ModuleDeclaration is captured during the parent walk, unclassified
      under --passTiming) — without it the flip nulled `UnionType` inside
      services-style `declare module "./types.js"` blocks and this-predicate
      narrowing died (ThisPredicateNarrowingTest's augmentation pin).
      Test-design lesson: the ADDITIVE leak-kill direction is SHADOWED by
      any-degradation (an unresolvable callee annotation degrades the
      assigned reference to `any` — proven with a never-declared `Zorp`
      control — masking the TS18048/TS2322 consumers), so the flow
      observable uses the SUPPRESSION direction: a foreign UNIMPORTED
      NULLABLE alias return-annotation pre-flip types the reference as the
      leaked union and manufactures TS18048 on a closure-captured read;
      post-flip it degrades to any and the leaked TS18048 dies (tsc-faithful).
      Suite +9 (Inv3TypePositionNodeKeyTest — 3 leak-kills FAIL pre-flip via
      stash: the flow TS18048, annotation-position TS2322, TS2315; 6
      preservation controls pass both sides); `--listAll` byte-identical on
      compiler AND services. Leg 2 DONE round 509 (2026-07-13) — **(iv) and
      the whole (c) migration COMPLETE**: getTypeFromBaseTypeExpression's
      Identifier fallback (PropertyAccess last-segment fallback kept legacy —
      the QualifiedName convention), emitTs2345ForBareTpArgToConstrainedTpParam,
      getOverloadImplementationRelated (keyed by the overload DECL's own name
      node — a nested/foreign collision no longer hands TS2793 a wrong-file
      impl pointer), calleeReturnAnnotationForImplicitAny (the
      uniqueFunctionDeclByName fallback still covers program-wide-unique
      names). Suite +5 (Inv3TypePositionLeg2NodeKeyTest — 2 leak-kills FAIL
      pre-flip via stash: a leaked foreign heritage base grafting members
      manufactured TS2741 on `const d: D = {}`, a leaked foreign
      constrained-TP callee manufactured TS2345; 3 preservation controls);
      listAll byte-identical on compiler AND services. RE-MEASURE (compiler
      profile): CONFLATED 6,165 → **917** (−85%; from the pre-migration 157k
      → −99.4%), 97 names / 9 passes, top 318/284/273 — the residue is the
      deliberately-legacy shadow-detection ecology (`diag`/`clone`/`map`/
      `factory` collision questions) + tiny tails, i.e. INV.3(d)'s scope.
      INV.3(d) is UNLOCKED.
  - [x] **INV.3(d) Retire the merge + delete the ecology — COMPLETE round 513** (checkbox reconciled round 612; the body below records the full campaign). Stop merging
    module-file locals into `globals`; delete `moduleFileLocalVarNames`,
    `conflatedTypeAliasFiles`, `conflatedInterfaceFiles`,
    `conflatedEnumFileSubsets`, the per-file interface views, and the chimera
    bails — walker-by-walker, each deletion suite- and listAll-gated (each
    removes hot-path work from `checkMemberAccessMissing`).
    **THE RETIRE IS MERGED TO MAIN (round 512): sub-items (i)–(iv) all DONE —
    suite fully green (10,346/0/3) and ALL 8 profiles byte-identical to the
    pre-retire baselines. Remaining: (v) the ecology deletions (the round-473
    Identifier dispatch is already deleted as the (iv) residual fix — its
    removal is what restored the server/harness baselines).** What the branch
    proved (measured round 510): the retire
    must be STAGED BY NAME CLASS — retire only MODULE-ONLY names; SHARED names
    (module local colliding with a lib/script global: `Symbol`/`Node`/
    `Performance` riding the lib names) must KEEP merging until every lib-name
    consumer resolves per-file (the naive full retire measured 861 compiler
    FPs, the module-only cut 34, each traced to an unflipped consult by the
    classifier-MISS stack-probe technique). Sub-items to finish it, in order:
    - (i) DONE round 511 (2026-07-14): the ambiguous-constrained→foreign leg
      REVERTED (declaration-IDENTITY leg kept) — flipped the whole TP family
      (17 tests: the 8 corpus TP pins + 3 local negative controls +
      tsxTypeArgumentPartialDefinitionStillErrors ×2 + WhileTrueDefiniteAssignTest
      ×4, the last two collateral of the over-aggressive classification);
      checker.ts:7358 re-solved at the INFERENCE side —
      `tryInferSingleTypeParamFromArgs` soft-skips a CallExpression arg whose
      type still carries a TypeParam at forReturnType sites (tpSawAnyArg →
      anyType, the pre-retire any-degradation behavior; round-468
      CallExpression gate keeps own-TP identifier args anchoring). Pinned by
      ForeignTpInferenceSoftSkipTest (6); compiler+services listAll
      byte-identical.
    - (ii) DONE round 512 — all 14 corpus multi-file failures fixed (the last 6:
      union-discriminant objlit drill node-keyed; ns-import static TS2339 +
      the dir-relative resolveAlias legs; TS2749 file-keyed with the
      typeSideImportFallback gate; the B585 contextual-display hops; the JSDoc
      ImportType own-specifier resolution; the TS2415 imported-base flip).
      Round-511 record follows:
      heritage/implements walkers node-keyed (interfaceDeclaration3,
      interfaceImplementation6 — incl. the B563 ownership-gate mirror that
      killed the double TS2420), checkConstraintsForTypeArgs keyNode +
      ImportType presetSymbol (divergentAccessorsTypes6,
      unmetTypeConstraintInImportCall), checkTypeNameResolved's leftSym →
      globalsForFile (augmentExportEquals1/2 + decoratorMetadataWithImport…7),
      the mam type-only-winner + namespace-import value-side bail
      (noCrashOnImportShadowing), **and the session's critical find: the
      import hop (`resolveImportedSymbolGeneral`) lacked the DIR-RELATIVE
      resolver leg, so path-shaped extensionless imports (`/proj/src/f1.ts` →
      `./lib`) never hopped and EVERY import-mediated type died on real
      on-disk projects — masked pre-retire by the merge, invisible to the
      `.js`-specifier tsc profiles; found via the EnclosingImportIndexTest
      pins + a MainKt scratch-repro matrix.** REMAINING 6 (per-test roots,
      each needs a probe dig): exportStarFromEmptyModule (X.A.r static
      TS2339 through a local-shadowed star chain),
      allowImportClausesToMergeWithTypes (TS2749 default-import-of-value used
      as type), allowJscheckJsTypeParameterNoCrash (display regression:
      `WatchHandler<any>` unfolds to the fn-type — alias display lost),
      checkJsdocTypeTagOnExportAssignment2 (JS `@type import("./a").Foo`
      excess-prop TS2353 — the JSDoc path's cross-file resolution),
      declarationEmitPrivateSymbolCausesVarDeclarationEmit2 (TS2415 with
      cross-file computed `[x]` private members),
      indirectDiscriminantAndExcessProperty (single-file module: TS2322
      member-vs-discriminant `"foo" | "bar"` — the objlit-member drill's
      resolution; NOT tryEmitObjectVsNamedUnionArg, whose anonymous
      constituents defer to the discriminant walker).
    - (iii) DONE round 512 — the last 4 were 2 real resolver gaps (the
      `export * as` arm in namespaceAliasMemberSymbol; the ns-member objlit ctx
      flips) + 2 pre-retire ACCIDENTAL PASSES fixed tsc-faithfully (all-missing
      all-anonymous union TS2339; primitive-vs-plain-object-bag TS2345).
      Round-511 record follows:
      (Inv3NodeKeyedLookupTest's unindexed-copy degradation → null for
      module-only names; Inv3GlobalsLookupTest's leak assertions inverted to
      the emptied-worklist victory condition); 3 more of the original 9
      flipped as REAL code fixes (EnclosingImportIndexTest ×2 +
      Inv3NodeKeyedLookupTest imports-keep-resolving via the dir-relative hop
      leg; ExtendsImplementsSameClassTest + NamespaceImportQualifiedTypeTest
      via the (ii) walker flips). REMAINING 4, all look like REAL
      suppressions to dig (scratch repros r7/r8 reproduce two):
      ConflatedTypeAliasLeakTest ×2 (own-file `type X` union TS2339 /
      own-file TS2345 both silent — receiver/param resolution in the alias's
      own file returns something unexpected post-retire),
      NamespaceQualifiedBaseInheritanceTest (export-star-as barrel base →
      TS2339 FP returned), BuilderChainAndNsMemberCtxTest (ns-member objlit
      contextual params → TS7006 FP returned).
    - (iv) DONE round 512 — all three residual families closed: deprecate.ts
      `compareTo` (an anyType shadow now BAILS mam instead of falling through
      to the outer import); session.ts protocol.Diagnostic (the round-473
      Identifier DISPATCH into conflatedPerFileInterfaceType REMOVED — the
      first (v) deletion, see the session note); fourslashImpl `'array'`
      (namedUnionMemberCouldAcceptArray hops import aliases). **Full 8-profile
      listAll A/B vs pre-retire main: ALL BYTE-IDENTICAL**; suite fully green;
      branch merged to main.
    - (v) DONE round 513 — ALL FOUR deletion groups landed (each suite- and
      8-profile-listAll-gated byte-identical): `moduleFileLocalVarNames` (+2
      masked narrowing gaps fixed), `conflatedTypeAliasFiles` (2 helpers
      re-keyed onto non-conflation conditions), `conflatedInterfaceFiles`
      objlit/relation chimera bails + TS2430/heritage view arms, and the
      per-file-view core (`conflatedPerFileInterfaceType`/`perFileInterfaceType`/
      owner-context threading) + `conflatedEnumFileSubsets`. SURVIVORS
      (deliberate): `moduleInterfaceNames`+`isLibPhantomMemberOfModuleInterface`
      (lib+module SHARED merges persist), `interfaceDeclsForCurrentFileView`
      discriminant reading, the re-keyed augmentation/alias-union bridges, the
      `A && objlit` falsy-remainder emitter, and the `nodeTypes` bypass re-keyed
      as `isPerFileDependentRefNode` on `multiFileModuleTypeNames` (the
      structural cache's cross-file position collisions are NOT
      conflation-specific — see the session note). **INV.3(d) is COMPLETE; the
      INV.3 arc is COMPLETE. NEXT: INV.4.**
- [x] **INV.4 Single-pass check spine — CLOSED round 599** (see the round-599 note: migration + retirements banked −13% wall + ONE authoritative walk; the (f) memo/fold designs are measured dead-ends until INV.5 canonical types). `checkSourceFileOnce` per-node dispatch;
  migrate walker families in INV.0's cost order — every migration deletes a full-tree
  pass and its private scope machinery. Once ONE authoritative walk state exists, land
  the two things that are unsound today: a per-node expression-type cache, and flow
  narrowing folded into reference typing once (collapsing the rounds-408–479
  per-consumer wiring). Decomposed round 514. Cross-cutting rules for every
  sub-item: (1) the spine is dispatched as ONE `pass("checkSpine")` at a FIXED
  init position (the earliest migrated pass's slot); passes migrating in from
  LATER positions move their emissions earlier in insertion order — the stable
  diagnostic sort (start→length→code→message) hides all but exact 4-tuple ties,
  and the per-migration corpus + listAll gates decide each case. (2) A spine
  handler sees ALL nodes: a hand-walk's accidental under-visits (arrow bodies,
  class/function expressions, initializers) become visits — per migrated pass,
  decide widen-vs-gate by the CLAUDE.md emission-direction rule (a
  position-independent tsc grammar rule widens faithfully; an FP-firewalled
  heuristic walker must reproduce its descent gates via parent-chain checks).
  (3) Every migrated pass with no local pins gets them BEFORE migration (the
  corpus pins emit bytes, not checker diagnostics — `.errors.txt` is disabled,
  so local tests are the primary under-emission gate). (4) Suite green +
  8-profile listAll + bench row per landed commit.
  - [x] **INV.4(a) Spine skeleton + pilot migration.** DONE round 514
    (2026-07-14): `checkSpine()` at the old checkAccessorModifierTarget slot —
    iterative enter/leave preorder walk per file (explicit parallel stacks;
    10k-chain pinned), per-file spine context fields declared BEFORE `init`,
    per-node `when` dispatch in `spineEnterNode`/`spineLeaveNode` (tsc
    checkSourceElement-style; plain private handler funs), active-handler
    gate skips the walk when every migrated handler is off (the profiles
    target ES2020 → pilot handler off → bench-neutral by construction).
    Pilot: TS18045 migrated — threaded `inAmbient` became an INV.2
    parent-chain ancestry check ([spineInAmbientContext]); the 78-line
    private walk deleted; coverage widened faithfully to class expressions /
    arrow bodies (position-independent grammar rule; both directions pinned).
    Suite +9 (Inv4SpineAccessorModifierTest), listAll byte-identical on
    compiler AND services (46/46; header-only argv difference), bench row in
    band. The leave hook is the scope-pop extension point — its pairing gets
    its first real pin when the first stateful migration lands.
  - [x] **INV.4(b) Tail-pass batches.** Migrate the 474-pass sub-100 ms tail
    (7.3 s = 36.5% of checker-init, round-491 table) in batches of ~5–15 per
    commit, most-mechanical first (zero-typing grammar/AST-shape walkers with
    per-file prepasses moving to a file-enter hook); each batch deletes its
    walks. Re-measure `--passTiming` every few batches; stop batching a shape
    that resists (stateful scope machinery) and queue it for (c)/(d) instead.
    Batch 1 DONE round 514 (2026-07-14): checkInvalidGlobalAugmentations
    (TS2669/TS2670) + checkReservedWordInterfaceParams (TS7051/TS7006) —
    both old walks descended ONLY through module bodies, so reachability is
    reproduced as a module-chain parent-walk gate (the template for
    module-scope-only walkers); the reserved-params handler deliberately does
    NOT widen to function/class-nested interfaces (a behavior change to make
    on a signal, not as a migration side effect); currentFileLocals is now
    set per file in checkSpine's loop (isTypeLikeParamName consults it); the
    spine walk is ALWAYS-ON from this batch (the TS2669 handler is
    unconditional and covers .d.ts — the .d.ts fast-skip lifted into
    per-handler gates). Suite +10 (Inv4SpineBatch1Test), listAll
    byte-identical on compiler AND services. WALK-COST measurement
    (interleaved 3-pair A/B vs the pre-batch binary — the round-493 rule): the
    first-cut enter/leave walk cost a REAL +1.0 s median on the compiler
    profile (boxing ArrayList<Boolean> phase stack + a leave frame per LEAF);
    fixed same commit — primitive BooleanArray phase stack + leaf shortcut
    (leave fires inline for childless nodes, no re-push) → re-interleaved
    NEUTRAL within noise (pair deltas +861/−1063/+574 ms, mean +124 ms).
    Per-frame costs are the whole game in a walk that visits every node —
    the walk KDoc carries the warning. Batch 2 DONE round 515 (2026-07-14):
    checkNonArrayRestParameters (TS2370 — the two differently-shaped walks
    became ONE Parameter-enter handler dispatching on the parameter's PARENT
    kind: value-position parents get the keyword rule, type-position parents
    the optional-rest rule; both widened faithfully — position-independent
    per-signature grammar) + checkIteratorMethodExtraParameters
    (TS2488/TS2504) + checkAsyncYieldStarThenable (TS1320) — the prepass
    pair became spine COLLECTION (VariableDeclaration enter, VariableStatement
    parent gate) plus BUFFERED iteration positions/yield* candidates resolved
    at file END (spineResolveDeferredIterationChecks — preserves the old
    prepasses' use-before-decl semantics with NO extra walk; the template for
    collect-then-scan walkers). TS1320's statement-level-only reachability
    widened to a nearest-function-ancestor async-generator gate. 16 walker
    funs deleted (~460 lines), 3 init slots removed. Suite +21
    (Inv4SpineBatch2Test), listAll error lines identical on ALL 8 profiles,
    wall in band. Batch 3 DONE same round: checkForOfNonIterable (TS2495 —
    the per-run lib-exclusion gate became spineForOfNonIterableActive; the
    verdict helper checkForOfExprNonIterable retained unchanged) +
    checkAbstractAccessorReturnTypes (TS7033 — GetAccessor-enter handler;
    the ClassDeclaration-parent gate keeps class-EXPRESSION members
    unchecked; the `.js`/`.jsx` skip is deliberately NOT spineIsJsLike —
    the old pass ran on .mjs/.cjs); 6 more walker funs + the round-514
    orphaned TS18045 KDoc deleted. Suite +9 (Inv4SpineBatch3Test), listAll
    identical on ALL 8 profiles. Batch 4 DONE round 516 (2026-07-14):
    checkSetterParameterCount (TS1054/TS1049/TS1095 as Get/SetAccessor-enter
    handlers — TS1054/TS1049 widened faithfully to class expressions +
    interface/type-literal accessors, TS1095 widened exactly to class
    expressions (the objlit/interface parses never store a setter return
    annotation); TS2808 as a ClassDeclaration-enter pair check KEPT at the
    old ClassDeclaration-only gate) + checkRestParameterLast (TS1014 — a
    second Parameter-enter handler; widened to FunctionType/ConstructorType/
    type-literal methods per tsc checkGrammarParameterList; GetAccessor
    parents stay excluded) + checkMultipleDefaults (TS1113 —
    SwitchStatement-enter, one-per-switch latch preserved) +
    checkInterfacePropertyInitializers (TS1246 — InterfaceDeclaration-enter;
    the parser owns the common shape). 17 walker funs (~733 lines) deleted,
    4 init slots removed. Suite +22 (Inv4SpineBatch4Test), listAll identical
    on ALL 8 profiles, bench in band. Batch 5 DONE round 516 (same session):
    checkConstWithoutInitializer (TS1155) + checkDestructuringWithoutInitializer
    (TS1182/TS7031) as VariableDeclaration-enter handlers — shared owner gate
    (VariableStatement non-declare/non-ambient via spineInDeclareModuleChain,
    the parent-walk equivalent of the old isAmbient threading which reset at
    every non-module descent; or a for(;;) initializer; for-in/for-of
    excluded); emitTs1182IfMissingInit retained; for-of/for-in BODIES are a
    faithful widening (the old walks had no ForOf/ForIn case). Plus
    checkComputedPropertyNameLiteral (TS1166/TS1169 by PropertyDeclaration
    parent kind; TypeLiteral stays unchecked) + spineCheckClassExprComputedProps
    (the TS1206 legacy-decorator short-circuit, position-GATED to the old
    expression-statement-only reach — pinned negative). 7 walker funs
    (~318 lines) deleted, 3 init slots removed. Suite +16
    (Inv4SpineBatch5Test), listAll identical on ALL 8 profiles, bench in
    band. Batch 6 DONE round 517 (2026-07-14): checkDuplicateModifiers
    (TS1030/TS1029/TS1044 — statement-kind handlers over 10 node kinds; the
    threaded inAmbientContext + atTopLevel pair became ONE parent-chain walk,
    `spineDupModContext`, where the INNERMOST flag-deciding ancestor wins per
    flag — fn/member bodies reset ambient, Block decides atTopLevel=false,
    ModuleBlock resets it true — and any non-descended ancestor kind returns
    null = the old no-visit; checkModifiers/checkInvalidImportEqualsModifiers
    retained as FP-firewalled text heuristics, reach NOT widened per B69.6) +
    checkAmbientInitializers (TS1039/TS1254/TS1066/TS1031 — Enum/
    VariableStatement/ClassDeclaration enter handlers over
    `spineAmbientInitContext`; .d.ts top-level-ambient preserved at the
    SourceFile terminal; class-member/arrow bodies stay unreached — pinned
    negative, a signal-driven widening candidate; the B162 same-enum sibling
    scan reproduced via `spineSiblingStatements`) + checkSwitchCaseComparable
    (TS2678 — the per-statement-LIST const/annotated binding maps reproduced
    as a preceding-sibling scan at the SWITCH node,
    `spineSwitchSubjectBinding`; single-statement positions degrade to
    `listOf(stmt)` = the old fresh-map wraps). 9 walker funs (~453 lines)
    deleted, 3 init slots removed. Suite +27 (Inv4SpineBatch6Test, pins run
    against the OLD walkers first), listAll error lines identical on ALL 8
    profiles, bench in band. Batch 7 DONE same round: checkRestElementPropertyNames
    (TS2566 — pure-syntax, ObjectBindingPattern-enter handler; widened
    faithfully to catch-clause patterns, each nested pattern gets its own
    enter) + checkRestBindingPatternElements (TS1186/TS2493/TS2322 —
    `checkRestBindingParam` retained as the Parameter-dispatch core; widened
    to object-literal-method/class-expression params) +
    checkAmbientImplementation (TS1183 — the most intricate reach walk so
    far, `spineAmbientImplContext`: ambient fn/class-member bodies were never
    descended (own-declare → null + the [passedDeclBody] declare-module-above
    rule), while arrow/fn-expr/class-EXPRESSION-member/objlit-method bodies
    RESET ambient unconditionally (passedDeclBody cleared — the expression
    walk descended them with false even under ambient); statement containers
    position-checked (conditions/for-headers/switch-subjects/case-exprs
    unreached), expressions pass generically; interface arm is de-facto
    dormant — the parse drops interface method bodies, cf. the TS1246 note) +
    checkAmbientRelativeModuleNames (TS2436 — top-level-of-script-file gate =
    a SourceFile parent check). 15 walker funs (~551 lines) deleted, 4 init
    slots removed. Suite +21 (Inv4SpineBatch7Test — 19 pre-verified against
    the OLD walkers, 2 widening pins fail pre-migration as expected). Batch 8
    DONE round 518 (2026-07-14): the parameter-initializer family — SIX
    passes as three Parameter-enter handlers + one SetAccessor-enter handler:
    checkOptionalParamWithInitializer (TS1015 — the corpus-tuned requireType
    gate preserved: declarations need a type annotation or param-property
    modifier, arrow/fn-expr params fire regardless; interface/type-literal
    signatures and objlit/class-expr GET accessors stay excluded per the old
    reach) + checkOptionalBindingPatternParams (TS2463 — uniform
    owner-has-body gate per parent kind) + checkParamInitializerForbidden
    (TS2523/TS2524/TS2372/TS2502/TS18048 — walkParamInitForbidden + the
    binding-name walk + collectParamSelfRefs retained as the per-parameter
    core; the per-file code@pos dedup set became spineParamForbiddenEmitted;
    the walkParamForbiddenExprForFns nested-fn descent dissolves into
    per-Parameter enters; findParamSelfRef deleted as already-dead) +
    checkParameterInitializerInNonImpl (TS2371 — widened faithfully to EVERY
    FunctionType/ConstructorType position per tsc checkParameter (initializer
    + missing containing body); old reach was var annotations/aliases/casts
    only; accessors stay excluded) + checkSetAccessorInitializer/
    checkSetAccessorRestParameter (TS1052/TS1053 — parent gate widened from
    class declarations to class expressions + object literals per tsc
    checkGrammarAccessor; interface/type-literal setters excluded, a
    signal-driven candidate). 24 walker funs (~902 lines) deleted, 6 init
    dispatches removed. Suite +29 (Inv4SpineBatch8Test — 23 pre-verified
    against the OLD walkers, 6 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (518a vs 517b).
    Re-measured --passTiming (pre-batch): checker-init 21.6 s, spine 529 ms
    carrying 24 passes; this batch's six summed ~292 ms of old pass time.
    Batch 9 DONE same round: checkForInLhsTypeAnnotation (TS2404 —
    ForInStatement-enter; widened faithfully to arrow/fn-expr bodies the old
    statement walk never descended) + checkEmptyTypeArguments (TS1099 on
    calls/new — CallExpression/NewExpression-enter; the type-POSITION TS1099
    emitter sharing emitTS1099 is untouched; reportEmptyTypeArgs deleted as
    orphaned) + checkSetterReturns (TS2408 — SetAccessor-enter;
    checkSetterBodyReturns retained as the per-setter body scan, fn-boundary
    semantics unchanged; widened to await operands etc.) + checkWithStatements
    (TS1101/TS1300/TS2410 — WithStatement-enter; the threaded isInWith/isInAsync
    pair became ONE parent-chain walk: first WithStatement ancestor before any
    function-like boundary → inner-with suppression of TS1300/TS2410; nearest
    fn boundary's Async modifier decides TS1300, ARROWS still reset async to
    false (old behavior, tsc's AwaitContext would fire — signal-driven
    candidate, pinned negative); TS2410's balanced-paren span scan preserved;
    TS1101 gated on alwaysStrict != false via spineWithStrictActive). 16
    walker funs (~606 lines) deleted, 4 init slots removed. Suite +18
    (Inv4SpineBatch9Test — 14 pre-verified against the OLD walkers, 4 widening
    pins fail pre-migration as expected); listAll error lines IDENTICAL on ALL
    8 profiles (518b vs 518a). Batch 10 DONE round 519 (2026-07-14):
    checkParamInitForwardRef (TS2373 + the ES5 hoisted-body-var TS2454
    companion) — checkForwardRefsInParams (+ findForwardParamRefs /
    findForwardParamRefsInBlock / collectHoistedVarNamesFromStmts) retained
    as the per-function core, dispatched from spineCheckParamForwardRefs at
    every BODIED function-like's enter; widened faithfully to arrows /
    fn-exprs / objlit methods / class-EXPRESSION members
    (position-independent per-signature tsc grammar); bodyless signatures
    keep the old no-check (TS2371 territory), GetAccessor params stay
    unchecked (TS1054 territory). 2 walker funs (~70 lines) deleted, 1 init
    dispatch removed. Suite +14 (Inv4SpineBatch10Test — 10 pre-verified
    against the OLD walker, 4 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519a vs 518b). Batch 11
    DONE same round: the checkJumpTargets family (TS1104/TS1105/TS1107/
    TS1115/TS1116 + TS1344) — the threaded inIteration/inSwitch/labelNames/
    crossedFunctionBoundary flags became ONE parent-chain walk
    (spineCheckJumpTarget) mirroring tsc
    checkGrammarBreakOrContinueStatement's `while (current)` loop: first
    function-like ancestor → TS1107 (class static blocks now count — a
    faithful widening); a matching LabeledStatement resolves the jump, with
    tsc's isIterationStatement(lookInLabeledStatements=true) nested-label
    unwrap for labeled `continue` — a faithfulness FIX over the old
    immediate-child test (`L1: L2: for(;;){continue L1}` no longer
    false-fires TS1115); an iteration ancestor legalizes unlabeled jumps, a
    SwitchStatement legalizes unlabeled `break`, a ModuleBlock ancestor
    suppresses unlabeled `break` (the old inSwitch=true namespace rule);
    TS1344 label-on-declaration became a LabeledStatement-enter handler
    (widened to arrow-in-condition positions). 4 walker funs (~306 lines)
    deleted, 1 init dispatch removed; emitJumpDiagnostic /
    isDeclarationStatement retained as the per-jump core. Suite +18
    (Inv4SpineBatch11Test — 14 pre-verified against the OLD walker, 3
    widening + 1 faithfulness-fix pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519b vs 519a). Batch 12
    DONE same round: checkObjectLiteralModifiers (TS1042/TS1184) — the
    near-full-tree explicit-stack expression walk became a pure
    ObjectLiteralExpression-enter handler (spineCheckObjLitModifiers;
    OBJLIT_ACCESS_MODIFIERS companion-hosted per the init-order gotcha);
    nested literals get their own enters; parameter-default and
    spread-operand positions are faithful widenings. 3 walker funs
    (~206 lines) deleted, 1 init dispatch removed. Suite +10
    (Inv4SpineBatch12Test — 2 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519c vs 519b). Batch 13
    DONE round 520 (2026-07-14): checkDuplicateObjectLiteralProperties
    (TS1117/TS1118/TS2300 — [checkObjectLiteralDuplicates] retained as the
    per-literal core dispatched from the ObjectLiteralExpression enter; the
    destructuring-assignment-LHS skip became the came-from-child parent walk
    `spineObjLitInDestructuringLhs`: climb through pattern-position parents
    — object/array literals, a PropertyAssignment when the child is its
    INITIALIZER, spread positions — and skip iff a `=` BinaryExpression is
    reached with the climbed child as its LEFT; a ShorthandPropertyAssignment
    default VALUE terminates the climb, so `({q = {a,a}} = o)` is now checked
    — a tsc-faithful widening alongside ternary conditions, parameter
    defaults, and object-literal METHOD bodies) + checkReservedWordIdentifiers
    (TS1359 — checkAwaitParams retained, dispatched from every async
    function-like's enter; the enum void/await/yield name rule as an
    EnumDeclaration-enter handler; widenings: class property-initializer
    arrows, new-expression var initializers, var-init arrow expression
    bodies) — 6 walker funs (~370 lines incl. the already-dead reservedWords
    val) deleted, 2 init dispatches removed. Suite +23 (Inv4SpineBatch13Test
    — 16 pre-verified against the OLD walkers, 7 widening pins fail
    pre-migration as expected); listAll error lines IDENTICAL on ALL 8
    profiles (520a vs 519c). Batch 14 DONE same round:
    checkStrictModeReservedWords (TS1212/TS1213/TS1214/TS2480/TS18006 — the
    most stateful zero-typing walker yet): the threaded isStrict/
    isExpressionStrict/inClass/realStrict flags became ONE shared
    ancestor-chain context (`spineStrictReservedCtx`: collect the parent
    chain, walk it DOWN applying the old descent arms —
    Block/If/ForIn/ForOf/ModuleBlock/ModuleDeclaration transparent, a
    FunctionDeclaration entered ONLY under the strictness at ITS position
    with a "use strict" prologue upgrading realStrict for its subtree, a
    ClassDeclaration entered only through METHOD/CONSTRUCTOR members
    (auto-strict: inClass + both strictness flags forced), any other
    ancestor kind → null = the old no-visit); ten per-statement-kind
    handlers (var-statement incl. fn-expr-name/type-annot/class-expr-init
    legs, for-in/of header decls, fn decl, class decl incl. TS18006 +
    member params, interface, enum, import-equals, import bindings,
    namespace name, expression statement); per-file flags
    (spineStrictFile* — binding strictness by effectiveTarget, EXPRESSION
    strictness by RAW target, the explicitNonStrict suppression) computed
    in checkSpine's loop; the two strictReserved* instance flags moved to
    the pre-init spine block, assigned per position from the ctx. Reach
    deliberately NOT widened (corpus-tuned family — interfaceNaming1 /
    commonMissingSemicolons / constructorStaticParamName): while/do/for/
    switch/try bodies, accessor bodies, arrow/fn-expr bodies, and
    class-expression members stay unvisited, pinned negative as
    signal-driven widening candidates; the load-bearing reach QUIRK — fn
    bodies UNVISITED in non-strict files (no TS2480 for `let let` there) —
    is reproduced by the ctx walk and pinned. 3 walker funs (~250 lines)
    deleted, 1 init dispatch removed. Suite +25 (Inv4SpineBatch14Test —
    ALL 25 pre-verified against the OLD walker; a pure reach-preserving
    migration, no widenings); listAll error lines IDENTICAL on ALL 8
    profiles (520b vs 520a). --passTiming RE-MEASURE (round 520, post
    batch 14): checker-init 20.0 s (21.6 s pre-batch-8); spine 718 ms
    carrying ~34 migrated passes; 459 passes recorded (~55 dispatches
    removed since INV.0's 514); top-3 giants unchanged
    (checkPropertyAccess 3.53 s / checkTypeAssignability 2.33 s /
    checkCallExpressionTypes 2.06 s = 7.9 s); the next-biggest non-giant
    passes are EXACTLY the INV.4(c) pair — checkUnresolvedNames 744 ms +
    checkTypeUsedAsValue 739 ms — then the (d) cohort
    (checkUncalledFunctionsInConditions 454 ms, checkArithmeticOperandTypes
    335 ms, checkImplicitAnyParameters 279 ms); the remaining zero-typing
    tail is mostly sub-100 ms each (checkAwaitContext 93 ms — stateful
    isAsync threading + the TS1262 top-level prepass + the batch-8 TS2524
    param-default ownership boundary; decompose when reached, low yield).
    Batch 15 DONE round 521 (2026-07-14) — **(b) COMPLETE**: checkAwaitContext
    (TS1308/TS1103/TS2311/TS1262 — the threaded isAsync/enclosingFunc pair)
    became THREE rare-node enter handlers (spineCheckAwaitExpr /
    spineCheckForAwait / spineCheckAwaitCall) driven by ONE full parent-chain
    walk (`spineAwaitCtx`): the FIRST function-like boundary decides the flags
    (async modifier; the TS1356 related-info FuncRef — ctor/accessor/prop-init
    boundaries force sync), and EVERY chain step up to the SourceFile must be
    an old-walked position (parameter defaults are TS2524's, enum member
    initializers / computed names / static blocks / heritage / shorthand
    destructuring defaults / objlit ACCESSOR bodies stay unreached — pinned
    negative); ModuleDeclaration bodies are TRANSPARENT, preserving the
    namespace-inherits-module-asyncness quirk (pinned); the TS1262 top-level
    `await`-binding scan (checkTopLevelAwaitNames, retained) runs per module
    file from checkSpine's loop and sets the TS2311 suppression flag. 4 walker
    funs (~310 lines) deleted, 1 init dispatch removed. Suite +27
    (Inv4SpineBatch15Test — ALL pre-verified against the OLD walker; a pure
    reach-preserving migration); listAll error lines IDENTICAL on ALL 8
    profiles (521a vs 520b). Closure decisions: checkConflictMarkers STAYS an
    init pass (a per-file TEXT scan — the spine walks nodes; there is no walk
    to delete); checkMixinClassConstructor is TP-scope-stateful → (d). The
    remaining stateful walkers are (c)/(d) territory.
  - [x] **INV.4(c) The name-resolution pair** — COMPLETE round 529 (all four
    sub-items landed; both families' recursive walkers deleted).
    checkUnresolvedNames (846 ms) +
    checkTypeUsedAsValue (734 ms): fold their private NameScope chains into
    spine-maintained authoritative lexical state backed by the INV.2(c)
    `lexicalScopes` tables (their planned mass consumption). Decomposed round
    522 (facts verified in-code: the checkUnresolvedNames family is ~3,000
    lines — statement/class-element/expression/type/JSX walkers threading a
    `NameScope` chain whose content closely mirrors `lexicalScopes` (params,
    hoisted vars, block bindings, type params + constraints) plus per-file
    root extras (KNOWN_GLOBALS seeding, DOM/host @lib filtering, ambient-
    module-name exclusion, `declare global` handling, JS @typedef regex
    types) and walk-threaded flags (classContext / inFunction / hasArguments);
    checkTypeUsedAsValue is ~700 lines threading THREE ScopeNameSet chains
    (typeOnly/value/namespaceOnly) built from AST surveys — NOT symbol-shaped,
    and its reach is corpus-tuned per the round-42 over-emission gotcha (no
    loop/switch/try descent)). Sub-items, one commit each, every step suite-
    and 8-profile-listAll-gated:
    - [x] **(c)(i) Spine-maintained lexical scope state (infrastructure,
      always-on).** DONE round 522 (2026-07-15 — the checkbox was missed in
      that round's commit; see the round-522 session note for the full
      landing record). The walk maintains `spineCurrentScope` — push at a scope
      owner's enter (BEFORE its own handlers dispatch), pop after its leave —
      via a per-file nodeId→LexicalScope ARRAY built from
      `result.lexicalScopes` (the INV.2(b) boxing-avoidance trick; cleared by
      re-nulling only written ids); a SwitchStatement's scope is re-keyed
      onto its CLAUSE nodeIds at fill so the switch EXPRESSION stays in the
      outer scope (the binder's routing); function-body Blocks share the fn
      scope automatically (no map entry); decorator outer-scope routing is a
      documented deferred divergence (both the walk and the binder tables
      currently agree). `spineScopeLookup(name)` resolves symbols → existing
      → parent. Pinned by a test-only AUDIT mode (companion statics — tests
      cannot reach the Checker instance): every spine enter verifies the
      incremental scope against a parent-chain derivation, and identifier
      enters record `spineScopeLookup` resolutions into a trace the tests
      assert on (shadowing id splits, scope-space ids ≤ −2, switch-expression
      isolation, catch/enum/self-name/var-hoist shapes). Bench row (the walk
      gains one array probe per enter+leave).
    - [x] **(c)(ii) checkUnresolvedNames STATE swap.** DONE round 523
      (2026-07-15): the NameScope content queries (`has` / `isTypeParam` /
      `hasType` / `typeParamConstraintOf` / `hasLocalShadow` / the TS2552
      candidate pool) are hybrid — each NameScope carries `lex` (the binder
      [LexicalScope] a TRUSTED scope-owner site links; population SKIPPED
      when linked) and queries interleave the threaded sets with the lex
      levels each NameScope level introduced (`lex` down to `parent.lex`,
      preserving shadowing order). Trusted links: statement lists via a new
      `checkUnresolvedInStatements(owner)` param (Block / SourceFile / the
      FUNCTION node for fn bodies — body Blocks have no binder entry),
      for/for-in/for-of headers, catch, switch (binder keys the case scope
      by the switch nodeId — the expression is checked before linking, so
      no re-keying needed), class/class-expr/interface/type-alias TP scopes.
      Function SIGNATURE positions stay threaded (params/TPs) — the binder's
      flat fn table would leak body decls into param defaults (sub-ES2015
      pre-collect is the only path that may see them; pinned both ways).
      Untrusted levels skipped in queries: ModuleDeclaration (the walk's
      buildNamespaceScope is EXPORT-filtered; binder aliases ALL merged
      members), EnumDeclaration (EnumMember-filtered), SourceFile existing
      filtered by a per-file exclusion set (ambient external module names +
      the declare-global quirk); type-level scopes (mapped TP / infer /
      fn-TYPE params) stay threaded. Unindexed trees: every probe misses →
      legacy behavior by construction. Equivalence-gated: corpus green +
      8-profile listAll error-line-identical; walk-threaded flags stay
      threaded until (c)(iii).
    - [x] **(c)(iii) checkUnresolvedNames WALK swap.** Move the emission
      positions onto the spine (delete the ~15 recursive walkers); reach
      reproduced per the emission-direction rule (this family is (b)-class —
      direct emitters — so under-visits are reproduced via parent-chain
      gates, widenings only on a signal). Batch 1 DONE round 524 (2026-07-15):
      the spine maintains the family's NameScope chain (`spineUResStack` —
      lazy signature population / deferred-activation regions / decorator
      pre-population views reproduce the legacy walk's sequential-mutation
      order on the spine's fixed preorder; per-file ROOT shared via
      `unresolvedFileRootFor`, enabled by the `computeTypeLibResolution`
      split), audited per-Identifier against the legacy walk's scope
      fingerprints (Inv4UnresolvedSpineScopeTest, 2 deliberate-breakage
      sharpness probes). classContext / inFunction / hasArguments ride the
      maintained NameScope levels (no parent-chain re-derivation needed).
      Batch 2 DONE round 525 (2026-07-15): the STATEMENT-LEVEL walk swap —
      checkUnresolvedInStatements/InStatement(Core) DELETED; per-statement
      dispatch in spineUResDispatch against the maintained levels;
      FunctionDeclaration signature positions at child enters
      (lazy-population staging); the with-body / skipped-return /
      declare-fn+class under-visits as suppressed-region levels and the
      declare-module post-filter as the filter2304 level flag, both enforced
      by the spineUResEmit wrapper (which also nulls currentFileLocals — the
      legacy pass ran unscoped); the 10 statement descents in the
      expr/class-element walkers cut; checkUnresolvedNames retained only as
      the declarationOnly minimal driver (spineUResOnly). listAll gate:
      error-line SETS identical on all 8 profiles; within-file PRINT order
      shifts (emission order — the corpus suite gates the sorted output
      byte-identical). Batch 3 DONE round 526 (2026-07-15):
      checkUnresolvedInClassElement DELETED — class-member decorators/
      computed-names at member enter (the pre-population moment = the legacy
      B98.r111 view), TP/param/return positions via the shared
      spineUResFnSigDispatch with per-member-kind coverage flags, index
      signatures in the class scope; gated to class decl/expr parents
      (interface members stay with the batch-2 handler). Batch 4 DONE round
      527 (2026-07-16): the EXPRESSION walk swap — expression positions
      self-emit at their own enters, gated by `spineUResExprChecked` (a
      per-file nodeId-memoized ancestor walk over `spineUResExprEdge`
      ROOT/DESCEND/NONE verdicts reproducing the recursive walker's exact
      reach); NaN/shorthand/embedded-type/class-expr-heritage/JSX handlers
      dispatch per node kind; spineUResFnSigDispatch reduced to TYPE
      positions (checkTps flag = the legacy fn-expr/objlit-method
      no-constraint-check asymmetry); the TS2422 skip became the
      spineUResHeritageSkip nodeId set; arrow/fn-expr/objlit-method levels
      carry exprOwned so recursion-owned regions keep the retained walker.
      checkUnresolvedInExpr(Core) retained SOLELY for the type walker's
      TypeLiteral computed-name positions. Batch 5 DONE round 528
      (2026-07-16) — **(c)(iii) COMPLETE, all the family's recursive walkers
      are DELETED** (checkUnresolvedInType(Core), the retained
      checkUnresolvedInExpr(Core), the JSX attribute/child helpers — ~660
      lines): type positions self-emit at their own enters. Unlike batch 4's
      static classifier, the type ROOTs are MARKED — every dispatch site that
      called the walker now calls `spineUResMarkTypeRoot` (strictly before
      the marked subtree walks; the sites stay the single source of truth),
      and `spineUResTypeChecked` (per-file nodeId memo) walks ancestors over
      `spineUResTypeDescends` edges = the deleted walker's recursion arms
      (mapped-TP constraint / conditional-infer / fn-type / type-literal
      member staging comes from the batch-1 maintained levels). Self-emitting
      kinds: TypeReference (names + TS2314 + utility TS2344 + TS1099),
      IndexedAccessType, TypeQuery, FunctionType/ConstructorType (TS2842),
      TypeLiteral (member computed-name TS2690/TS2693/TS2464 in one batch at
      the literal's enter). The last recursion-owned expression region — a
      TL member's computed NAME — became an expression ROOT gated on
      `spineUResTypeChecked(typeLiteral)`, flipping `exprOwned` true there so
      the fn-sig dispatch covers what the retained walker's arms did.
      Verified: suite 10,804 → 10,832 (+28 Inv4SpineBatch19Test, ALL
      verified identical on the OLD walker via stash — pure
      reach-preserving; 0 regressions); listAll error lines IDENTICAL on
      ALL 8 profiles (528a vs 527a; header-only timing diffs); bench row
      recorded.
    - [x] **(c)(iv) checkTypeUsedAsValue.** DONE round 529 (2026-07-16): the
      recursive checkTypeAsValueInStatement(s)/checkTypeAsValueInExpr walkers
      + ScopeNameSet DELETED (~700 lines). Identifiers self-emit
      TS2693/TS2708 (+ the TS2585 forward-lib routing) at their enters, gated
      by `spineTavStatus` — a memoized 3-state ancestor-chain classifier over
      `spineTavEdge` (the deleted walker's exact dispatch arms, incl. the
      corpus-tuned NON-descent into for/while/do/switch/try bodies, class
      accessors/EXPRESSIONS, shorthand properties, and objlit-method param
      defaults; the plain-`=`-LHS TS2708 suppression is the REACHED_NONS
      status minted on the Equals-left edge — checkConstAssignment owns the
      assignment-target TS2708). The set chains stayed set-based as planned
      but became PULL-BASED memoized levels (`tavLevelAt`/`tavLevelFor` —
      the family's surveys are position-independent, so no batch-1-style
      lazy staging; the one order-sensitive spot, an objlit method's
      computed NAME seeing the OUTER scope, is a came-from-child owner
      skip). The file survey (TS18042 emission + currentForwardLibTypeNames
      included, verbatim) builds eagerly per file in checkSpine's loop
      (`tavBuildFileRoot`); TS2689 classifies at the CLASS enter and marks
      `spineTavHeritageSkip` before the heritage subtree walks (the deleted
      either/or: TS2689 OR the generic walk, never both). Suite
      10,832 → 10,872 (+40 Inv4SpineBatch20Test, ALL verified against the
      OLD walker first; 0 regressions); listAll error lines IDENTICAL on
      ALL 8 profiles (529a vs 528a); bench row recorded.
  - [x] **INV.4(d) Mid-weight stateful walkers.** COMPLETE round 541 (walkers
    1–13; the round-529 cost-ordered list is fully migrated — a fresh
    --passTiming table at round 542 shows the remaining non-giant tail is a
    flat sea of sub-160 ms mostly-stateless passes, none of them the
    scope-machinery shape this item targeted; they get absorbed
    opportunistically or superseded by (e)/(f)). Each walker moved its scope
    machinery onto the shared spine state; decompose per walker when reached.
    MEASURED cost order (round-529 --passTiming, post-(c): checker-init
    20.6 s; spine 2,247 ms carrying both name-resolution families + ~40 tail
    passes; giants unchanged 3.92/2.34/2.17 s):
    checkUncalledFunctionsInConditions 435 ms (38,986 getTypeOfExpression
    calls — a typing pass, not zero-typing), checkArithmeticOperandTypes
    309 ms (68,946 calls), checkImplicitAnyParameters 272 ms,
    checkDuplicateIdentifiers 260 ms (zero-typing), checkDefiniteAssignment
    241 ms, checkArgumentCounts 230 ms, checkUseBeforeDeclaration 205 ms,
    checkImplicitReturns 199 ms, checkConstAssignment 170 ms, then a long
    ~100–165 ms tail (checkAlwaysTruthy, checkNullUndefinedUsage, …).
    - (w1) DONE round 530 (2026-07-16): checkUncalledFunctionsInConditions
      (TS2774/TS2801) — the first (d)-class TYPING-pass migration; template
      extends (c)(iv): boolean reach classifier + PULL-BASED per-emission
      stack rebuild with per-owner memoized LAZY levels (functions with no
      conditions never pay the collection's typing calls), ambient state
      (currentFlowGraph/currentCheckFileName) save-set-restored around EACH
      dispatch never walk-wide. 36 pins (Inv4SpineBatch21Test) pre-verified
      on the OLD walker; suite 10,872 → 10,908; listAll error-line identical
      on ALL 8 profiles; ~270 walker lines deleted. See the round-530
      session note for the quirks pinned.
    - (w3) DONE round 532 (2026-07-16): checkImplicitAnyParameters
      (TS7005/TS7006/TS7008/TS7013/TS7019/TS7031/TS7032/TS7051) — the first
      DOWNWARD-CONTEXT-THREADING migration: the checkImplicitAnyInExpr
      recursion's five explicit context parameters (contextuallyTyped /
      contextualType / viaUnionWithPrimitive / ctxAnnotation / ctxViaAssignment)
      become ONE push-maintained SpineIanyCtx value with frames defined at
      EXACTLY the edges the legacy recursion passed arguments over (a missed
      edge silently LEAKS the parent context — every reached expression-position
      edge must define, even to null); the binary left-spine loop dissolves into
      per-edge rules (right operand by operator; left inherits for `||`/`??`
      only); returnCtxAnnotation + inAmbientContext pull-derive from parent
      chains; the three implicit-any scope stacks stay the same checker fields,
      pushed at body edges + recorded at declarator enters. No ambient install
      needed (slot-move A/B ×8 error-identical + corpus green pre-gated the
      move past the 4 sibling TS7xxx passes). 56 pins (Inv4SpineBatch23Test)
      ALL pre-verified on the OLD walker — incl. the reach quirks (while/do/
      switch/try/for-in/for-of bodies, call CALLEES, conditional CONDITIONS,
      as-casts, objlit accessors, static blocks all unreached) and the
      class-expression setter TS7032-with-sibling-getter bug-compat fire.
      The recursive walkers (checkImplicitAnyInStatements/-InClassElement(Core)/
      -InExpr) + the pass driver are DELETED (~770 lines); suite 10,948 →
      11,004; listAll error lines identical on ALL 8 profiles. See the
      round-532 session note.
    - (w2) DONE round 531 (2026-07-16): checkArithmeticOperandTypes — the
      first ORDER-DEPENDENT stateful migration (statement-ordered recordings
      that leak across blocks → PUSH-maintained frames on the spine, not the
      pull-based rebuild) and the first pass from AFTER the three giants
      (slot-move pre-gate found the currentParamBindingNames leak as the ONLY
      order coupling — kept pass-private now). Left-spine flatten = chain-root
      LEAVE emission; ambient install per emission/recording. The CORPUS caught
      a second, subtler coupling the profiles could not: the pass CONSUMED the
      TS2322 walk's namespace-level recording residue (qualify.ts) — reproduced
      as the pass's own ModuleBlock-gated identifier-init chain recording. 39
      pins (Inv4SpineBatch22Test); suite 10,908 → 10,948; listAll error-line
      identical on ALL 8 profiles; the pass driver deleted (the recursive
      walkers stay as checkComputedDestructKey's utility). See the round-531
      session note.
    - (w12+w13) DONE round 541 (2026-07-17): the ORDER-COUPLED pair
      checkCommaOperatorUnused (TS2695) + checkNullishPredicates (TS2871/
      TS2869 + while/do truthiness) migrated TOGETHER — the ordering
      contracts dissolve structurally (comma pre-order → ENTER anchors; np
      post-order → LEAVE anchors; while/do truthiness at the CONDITION's
      leave; same-position comma-first BY CONSTRUCTION since enters precede
      leaves — the legacy slot contract retired). Separate verbatim
      classifiers (their reach differs: objlit method bodies np-only;
      tagged-templates/yield/delete/typeof/comma-lists comma-only). 10 pins
      (Inv4SpineBatch32Test) pre-verified; suite 11,233 → 11,243; listAll
      ×8 identical; ~470 walker lines deleted. See the round-541 session
      note.
    - (w11) DONE round 540 (2026-07-17): checkNullUndefinedUsage (TS18050 +
      the for-of empty-[] TS2488 shape) — pure anchors, no ambient; the
      classifier carries the legacy checkDepth ≤ 200 STATEMENT-frame cap as
      a depth-encoded ShortArray status, with legacy frameless body Blocks
      as CARRIER blocks at the parent's depth. 12 pins (Inv4SpineBatch31Test)
      pre-verified; suite 11,221 → 11,233; listAll ×8 identical; ~230 walker
      lines deleted. See the round-540 session note.
    - (w10) DONE round 539 (2026-07-17): checkAlwaysTruthy (TS2872/TS2873 +
      TS1345/TS2845 + the `!`-operand falsy check) — frameless: both walk
      states pull-derive (the never-reset B69.11 inArrowExprBody flag; the
      if-else-chain prevTruthy via elseStatement ancestor links); per-chain-
      node dispatch at IfStatement enters. Condition-reach asymmetry pinned:
      if/while/do/ternary condition sub-exprs never walked, FOR conditions
      fully walked. 13 pins (Inv4SpineBatch30Test) pre-verified; suite
      11,208 → 11,221; listAll ×8 identical; ~230 walker lines + the
      threading field deleted. See the round-539 session note.
    - (w9) DONE round 538 (2026-07-17) — checkConstAssignment (TS2588/TS2628/TS2629/TS2630/TS2708 +
      TS2540 readonly writes + TS2357 inc/dec targets + scanRegExpFull's
      TS1538/regex-grammar family riding the same walker). SCOUTED
      (2026-07-17, in-code): the most stateful (d) walker yet — a w2+w5
      hybrid. (1) constNames is a statement-ordered LIVE MutableMap per
      activated list (collect const/class/enum/fn/ns THEN check, let/var
      REMOVES an inherited name) → DA-style core frames with per-statement
      collect steps at direct-child enters; spawn rules are ASYMMETRIC:
      Block/switch-clause/try-blocks/ModuleBlock/class-member bodies COPY
      the top frame's live map, FunctionDeclaration/fn-expr/arrow-Block/
      IIFE-arrow-Block bodies get a FRESH EMPTY map (an outer const is NOT
      flagged inside a fn body — bug-compat), SourceFile seeds from the
      program-wide sharedConsts overlay (script files only; module files
      empty). (2) The For header is an EDGE overlay: condition/incrementor/
      body see outer+header consts, the INIT EXPRESSION sees outer only.
      (3) currentClassForThis/currentThisMemberIsCtorDirect pull-derive from
      the ancestor chain: per-member staticness, Constructor→ctorDirect,
      property-initializer→ctorDirect=false, fn-expr NULLS the class, arrow
      keeps it with ctorDirect=false, and an IIFE-ARROW is TRANSPARENT to
      ctorDirect (the CallExpression arm's immediatelyInvokedArrowCallee).
      (4) FunctionDeclaration bodies install currentLocalTypes/
      currentParamBindingNames copies + populateParameterLocalTypes (B116 —
      fn DECLS only, not methods/fn-exprs/arrows) — cumulative through
      nested fn decls; per-anchor pull-rebuild with per-owner memo (w1
      template). (5) This is a TYPING pass (checkReadonlyAssignmentTarget
      resolves receiver types) — slot-move pre-gate with the CORPUS
      mandatory; check for diagnostics-list probes before choosing
      enter-vs-leave dispatch (the round-537 lesson). Anchors: assignment-op
      BinaryExpressions (left-spine loop — emissions are per-spine-node, at
      each binary's own reach), ++/-- Prefix/Postfix, RegularExpressionLiteralNode.
      LANDED as scouted (enter-dispatch — no diagnostics probes); 19 pins
      (Inv4SpineBatch29Test) pre-verified on the OLD walker; suite 11,189 →
      11,208; listAll ×8 identical; ~330 walker lines deleted. See the
      round-538 session note.
    - (w8) DONE round 537 (2026-07-17): checkImplicitReturns
      (TS7030/TS2355/TS2366/TS2378/TS7023 + arrow concise-body TS2322).
      SLOT-MOVE PRE-GATE LANDED AND VERIFIED (intact pass at the spine slot;
      corpus 11,170/0 + listAll ×8 error-line identical) — the ambient
      residue at the spine slot is proven equivalent, and the pass stays
      BEFORE checkTypeAssignability, whose end-of-pass filter suppresses
      TS7030 at its own TS2322 positions (it EXPECTS this pass's TS7030s to
      exist — do not move it past the giants). SCOUTED migration design
      (w1-template): 4-state reach classifier (STMT/EXPR/MEMBER/NONE) over
      walkStmtForImplicitReturns/walkExprForImplicitReturns arms; anchors at
      FunctionDeclaration/MethodDeclaration/GetAccessor/FunctionExpression/
      ArrowFunction enters (the retained check*ForImplicitReturn bodies
      minus their trailing walkForImplicitReturns recursion); per-dispatch
      ambient install of implicitReturnFlowGraph + currentCheckFileName +
      the PRE-SPINE resting currentFileLocals/currentFunctionParams
      (checkGetAccessorForImplicitReturn reads currentFunctionParams'
      RESTING value — it never sets it; capture both at checkSpine entry
      like spineArithBase). Per-file gate: !isDts && (checkJs || !(.js|.jsx))
      — NOTE .mjs/.cjs are NOT skipped by the legacy gate (spineIsJsLike is
      the wrong predicate). Sharp reach quirks to pin (verified in-code):
      GENERATOR bodies never descend (the anchors early-return before their
      trailing recursion); class-DECL Constructor/SetAccessor bodies and
      class-DECL PropertyDeclaration initializers unreached while class-EXPR
      prop inits ARE reached; objlit SetAccessor bodies unreached; arrow
      CONCISE (expression) bodies never descend (both annotated and not);
      return/throw/export= EXPRESSIONS and if/while conditions and for
      headers unreached in statement position; GetAccessor sentinel body
      (pos == -1) skips. LANDED: anchors dispatch at LEAVE (the 17.135
      TS2304/TS2314 diagnostics-list probes must see the annotation's own
      spine emissions — enter-dispatch over-emitted TS2355 on exactly 2
      corpus tests); 19 pins (Inv4SpineBatch28Test); suite 11,170 → 11,189;
      listAll ×8 identical; ~140 walker lines deleted. See the round-537
      session note.
    - (w7) DONE round 536 (2026-07-17): checkUseBeforeDeclaration (TS2448/
      TS2449/TS2450 + TS2454 co-emit + static-init TS2729) — 5-state reach
      classifier + per-list-owner memoized blockScopedDecls; the retained
      BOUNDED checkUBDForwardRefs walk anchors at DIRECT statements of
      activated lists (it recurses if/labeled itself — nested statements
      never re-anchor); loop-header self-ref checks re-host at For/ForIn/
      ForOf enters. TWO order couplings resolved by slot placement:
      populateAmbientCyclicBaseClasses (the TS2449 suppression-set producer)
      moved BEFORE the spine, and the TS2454 co-emits becoming visible to
      checkDefiniteAssignmentViaFlowGraph's dedup scan measured INERT
      (slot-move pre-gate: corpus green + listAll ×8 identical). Cross-file
      leg stays a separate pass at the spine slot. 33 pins
      (Inv4SpineBatch27Test) ALL pre-verified on the OLD walker first run;
      suite 11,137 → 11,170; listAll error-line identical on ALL 8 profiles;
      ~195 walker lines deleted. See the round-536 session note.
    - (w6) DONE round 535 (2026-07-17): checkArgumentCounts (TS2554/TS2555/
      TS2575) — the first DEPTH-valued reach classifier (the legacy
      argCountDepth recursion counter reproduced per edge, ≤200 cap; binary
      right-spine absorption = no depth) and the first MAP-valued pull-based
      downward context (funcParams/ctorParams/fnDepth/superCtor rebuilt at
      each emission from per-list-owner memoized levels — sound because every
      list overlay reads its WHOLE statement list). TRAP: a pull rebuild that
      RE-ENTERS itself through its own memoized levels must reuse its shared
      ascent buffer MARK-based, never clear()-based (the for-of loop-shadow
      edge silently dropped; one pin caught it). Producer sibling
      checkSpreadNonIterableIntoFixedArity moved BEFORE the spine. 46 pins
      (Inv4SpineBatch26Test) ALL pre-verified on the OLD walker; suite
      11,091 → 11,137; listAll error-line identical on ALL 8 profiles;
      ~650 walker lines + 3 threading fields deleted. See the round-535
      session note.
    - (w5) DONE round 534 (2026-07-16): checkDefiniteAssignment (the SET-based
      TS2454 pass) — the first per-statement-LIST ordered walker with a
      DOWNWARD leak context: legacy list activations become CORE FRAMES
      (pushed at SourceFile/fn-body/Block/ModuleBlock owners, per-statement
      steps at direct-child enters — the collect/checkUses/mark/nestedLeak
      loop body retained verbatim), the recursion walkers become a memoized
      10-state ancestor classifier (spineDaStatus/spineDaEdge), and the
      downward leak set is READ from the top frame's per-statement
      currentLeak via LEAK-flavored statuses (sound: leak-preserving paths
      never cross a core spawn). The flow-graph siblings (ViaFlowGraph
      dedups one-directionally against this pass) moved to right after the
      spine, preserving set-pass-first order; slot-move pre-gate ×8
      identical. 39 pins (Inv4SpineBatch25Test) pre-verified on the OLD
      walker; suite 11,052 → 11,091; listAll error-line identical on ALL 8
      profiles; ~370 walker lines deleted. See the round-534 session note.
    - (w4) DONE round 533 (2026-07-16): checkDuplicateIdentifiers (TS2300
      family) — the lightest (d) shape: STATELESS (the two
      checkDuplicateDeclarations flags derive at the anchor) and ZERO-TYPING,
      so the migration is a pure boolean reach classifier
      ([spineDupIdReached] over [spineDupIdEdge], the deleted
      checkDuplicatesInStatement(s)/InExpr/InClassElement arms verbatim) +
      anchor dispatch at node enters running the RETAINED bounded leaf
      utilities; class/objlit MEMBER emissions dispatch uniformly at the
      member's own enter (objlit edges never admit accessors, so a reached
      SetAccessor/Constructor is class-only). Per-file top-level scans ride
      checkSpine's loop in the legacy within-file order, each wrapped in a
      currentFileLocals=null install (the legacy pass ran with it null —
      checkClassNamespacePrototypeConflict's `?: globals` consult makes it
      load-bearing). Slot-move pre-gate: error-line-identical ×8 (no residue
      coupling). 48 pins (Inv4SpineBatch24Test) ALL pre-verified on the OLD
      walker first run; suite 11,004 → 11,052; listAll error-line identical
      on ALL 8 profiles; ~215 walker lines deleted. See the round-533
      session note.
  - [x] **INV.4(e) The top-3 giants — COMPLETE round 592** (cta 586 / cpa 585 / ccet 592 all retired; checkbox reconciled round 612). checkPropertyAccess (3.66 s @ round-542
    table) → checkTypeAssignability (2.62 s) → checkCallExpressionTypes
    (2.13 s) — one at a time (together ~38% of checker-init; 458k of 595k
    getTypeOfExpression calls). **g1 SUB-PLAN (scouted round 542, in-code):
    checkPropertyAccess's walker core is compact (checkPropertyAccessInStatement
    293 lines / 22 arms + checkPropertyAccessInExpr 414 lines / 26 arms —
    the mass is in the called emission machinery, retained as leaf
    utilities). State model per the (d) templates: (1) statement-ordered
    currentLocalTypes recordings (w2 arith shape — PUSH-maintained frames,
    PASS-PRIVATE on the spine per the w2 currentParamBindingNames lesson;
    the pass also does applyBodyLocalShadowing at fn-decl/arrow/fn-expr
    boundaries per the round-447 gotcha — those calls stay in the frame
    installs); (2) contextualType downward threading with clear-before-body
    edges (w3 iany shape — push ctx with frames at exactly the legacy
    assignment edges); (3) enclosingClassType threaded param + inStaticClassMethod
    (pull-derivable from the member chain); (4) propertyAccessEnclosingNamespaces
    (its OWN stack, deliberately separate from inferenceNamespaceStack per
    the two-stacks gotcha — push at ModuleDeclaration edges); (5) per-file
    ambient currentFileLocals/currentCheckFileName/currentFlowGraph/
    currentLexicalScopes (per-dispatch install, w1 discipline — NOTE
    currentFlowGraph walk-wide is the 78-test hazard, so install around
    emissions only). SUB-STEPS, one commit each: (g1a) slot-move pre-gate —
    move the intact pass from its slot to the spine slot; this REORDERS it
    before the other two giants, so expect residue coupling (the w2
    corpus-only lesson): listAll ×8 + FULL corpus mandatory; if the
    pre-gate diffs, bisect the coupling with restore-after-pass probes
    before any migration. (g1b) pins (~50, the largest batch yet — reach
    quirks per arm; pre-verify on OLD). (g1c) the migration. (g1d) after
    g1 lands, re-measure; g2/g3 decompose the same way when reached.**
    **g1a MEASURED (round 542, both experiment directions run and REVERTED —
    the working tree keeps the legacy giant order): the giants are
    order-entangled in BOTH directions, and the couplings are CORPUS-ONLY
    (all 8 profiles sorted-error-line-identical in both experiments).
    (1) checkPropertyAccess moved before checkTypeAssignability →
    noImplicitAnyForIn loses a TS7053: the element-access receiver's type
    (`var k1 = x[i]` → `{}`) comes from the assignability walk's
    currentLocalTypes RESIDUE — the w2 residue class; fix = the pass records
    its own receiver types (w2's own-recording template).
    (2) checkTypeAssignability moved to the spine slot →
    typeArgumentDefaultUsesConstraintOnCircularDefault's TS2353 display
    flips `Test<any>` → `Test` (aliasDisplayMap/declaredTypes first-touch)
    AND relationComplexityError gains 2 FP TS2322 (relation-cache/
    complexity-budget state) — CACHE first-touch couplings against the small
    passes between the spine and slot 64, each needing a root-cause before
    the giant can move. NEXT STEP for g1: bisect WHICH intermediate pass's
    first-touch the two failures depend on (binary-search the slot
    position), then either neutralize the dependency (pass-own state /
    explicit cache warm) or migrate the giant IN PLACE (dispatch from the
    spine but buffer emissions to the legacy slot — a new template).**
    **g1a BISECT COMPLETE (round 543) — STRATEGIC FINDING, the (e) tier is
    BLOCKED ON INV.5: three targeted probes pinned both g1a' couplings to
    exactly TWO small producer passes (checkTypeParameterDefaults — its
    first-touch of the circular-default alias caches the `Test<any>`
    display; checkTemplateUnionIntersectionComplexity — its TS2859
    complexity verdicts make the giant's relation SKIP the failing
    comparison), but applying the established producer-move pattern (both
    before the spine + the giant at the spine slot) dragged a coupling
    CHAIN: 5 NEW generic-family corpus failures
    (genericsWithoutTypeParameters1, genericRecursiveImplicitConstructor-
    Errors3, noTypeArgumentOnReturnType1, conflictingTypeParameterSymbol-
    Transfer, returnTypeTypeArguments) + a harness listAll diff — the moved
    producers have their OWN upstream first-touch dependencies. Buffered
    emission does not help either: the COMPUTATION (type resolution into
    shared caches) is what is order-sensitive, not the emission. CONCLUSION:
    the giants cannot migrate by slot manipulation while nodeTypes/
    declaredTypes/aliasDisplayMap/relation caches are first-touch-order-
    sensitive. The (e) tier's prerequisite is INV.5's cache re-keying
    (`nodeTypes` keyed (node, mapper) — always valid; canonical type
    identity), which makes resolution order-INSENSITIVE. RE-SEQUENCED:
    work INV.5 next; return to (e) when the caches are order-free. All
    probe edits REVERTED — the tree keeps the legacy giant order.**
    **SUPERSEDED (rounds 555/556): the 542/543 conclusions above are STALE —
    the probe/slot-move scripts matched a COMMENT containing
    `pass("checkSpine")` and inserted the giant ~100 passes early (see the
    round-555 CLAUDE.md gotcha), so the "coupling chain" / "blocked on
    INV.5" findings were position artifacts (possibly compounded — the
    INV.5 (a)/(c)/(d1)/(e) landings since may also have genuinely
    order-freed some caches). At the CORRECT position, with exactly the two
    round-543 producers hoisted (landed round 555), ALL THREE giants
    slot-moved to the spine block corpus-green + listAll-×8-identical
    (landed round 556; legacy relative order g-cta → g-cpa → g-ccet
    preserved). g1a/slot-move pre-gates: DONE for all three. (g1b) DONE
    rounds 557/558 — 33 reach pins (Inv4SpineG1PinsTest statement arms,
    Inv4SpineG1PinsExprTest expression arms), all verified on the current
    walker.**
    **(g1c) DESIGN (round 559, from the g1b arm reads): the migration ORDER
    must be cta FIRST — the giants share a CROSS-PASS residue channel:
    checkPropertyAccess's driver does NOT reset currentLocalTypes per file,
    so it consumes checkTypeAssignability's recordings (round 542's
    noImplicitAnyForIn TS7053 finding: the `var k1 = x[i]` receiver type is
    cta residue). Migrating cpa into the spine FIRST would run its per-node
    work BEFORE the still-slot-resident cta → the residue disappears.
    Migrating cta first preserves cta-before-cpa; note per-node
    interleaving ≠ pass-after-pass for BACKWARD residue reads (a node
    consuming a LATER node's recording) — the pass-after-pass semantics let
    cpa see cta's COMPLETE final state incl. later files; audit any
    backward consumption during the cta migration (candidate remedy: the
    w2 own-recording template — each pass records what it consumes).
    Frame model per the INV.4(d) playbook: (1) per-dispatch ambient install
    of currentFlowGraph/currentLexicalScopes (NEVER walk-wide on the spine
    — the 78-test hazard; the legacy walk-wide set is reproduced by
    installing around every g1 emission); (2) fn-like scope copies
    (fn-decl/method/ctor/set-accessor/arrow/fn-expr) as push-frames at
    body enters (save map refs, install copies + populateParameterLocalTypes
    + applyBodyLocalShadowing/applyAmbiguousBlockScopedLocals), popped at
    leaves — GetAccessor bodies deliberately have NO scope copy (chunk-1
    pin); (3) contextualType as a kinded downward carrier at call-arg /
    objlit-property / arrow-body edges (the w3 template; cleared at
    fn-expr body and spread edges); (4) propertyAccessEnclosingNamespaces
    pushed at non-declare ModuleDeclaration enters; (5) enclosingClassType
    as a pull-derived member-chain context (null across fn-decl/fn-expr
    boundaries, KEPT through arrows — chunk-2 pins), with the this-param
    override at method enters; (6) inStaticClassMethod save/set/restore at
    class-member enters; (7) currentEnclosingEnum at EnumDeclaration
    enters; (8) reach quirks as classifier edges: for-INIT unreached,
    tagged-template spans unreached, interface bodies unreached,
    shorthand-property initializers unreached.**
    **(g2 = cpa DECOMPOSITION, queued round 576 — the cta migration (rounds
    560–576, m1..m3m) is COMPLETE for the emission surface; work these
    top-to-bottom, one commit each, mirroring the proven cta sequence):**
    - [x] **(cpa-m1) Legacy-side audit instrumentation** — DONE round 577. (the cta-m2a
      pattern): a test-only `cpaAuditRecord` at the top of
      checkPropertyAccessInStatement fingerprinting the threaded+ambient
      context per DIRECT statement — enclosingClassType (threaded param),
      currentLocalTypes/currentParamBindingNames/currentEnumConstrainedParams/
      currentShadowedNames (fn-boundary copies), inStaticClassMethod,
      propertyAccessEnclosingNamespaces depth, contextualType. FINGERPRINT
      HAZARD (scouted): cpa's currentLocalTypes maps name→Type, not strings
      like cta's varTypes — Type.id is resolution-order-sensitive between
      legacy-time and spine-time, so fingerprint by sorted name set +
      per-name typeToString (test-only cost), never by id.
    - [x] **(cpa-m2-prep) Close the residue channel legacy-side** — DONE
      round 578: per-file `currentLocalTypes` reset in the cpa driver + the
      element-access own-recording; corpus green + listAll ×8 byte-identical.
    - [x] **(cpa-m2) Spine-side frame skeleton** — COMPLETE round 580 (tier 2:
      unified edge-reach walker, arrow/fn-expr/ClassExpression frames,
      cpaCtxAt/cpaEctAt; full bidirectional audit equality).
      tier 1 (statements) DONE round 579 ((cpa-m2a): fn-decl/method/ctor/
      accessor frames, ns frames, loop-var overrides, per-decl-leave
      recordings, the immediate-position fingerprint gate); REMAINING
      (cpa-m2b): tier 2 — DESIGN COMPLETE (scouted round 579b, in-code):
      (i) arrow Block-body frames: 3-map copy + populate + shadowing +
      ambiguous + contextual param registration from ctx-at-arrow;
      ect/inStatic PRESERVED through arrows; (ii) fn-expr body frames:
      3-map copy + the fn-expr's OWN param semantics (annotated -> set,
      UN-annotated -> REMOVE from localTypes — not populate!) +
      destructured-name collection + contextual registration + shadowing +
      ambiguous; body walks with ect = NULL; (iii) ClassExpression member
      bodies: the tier-1 class-member frames extended to ClassExpression
      owners with a per-visit synthetic anon-class type (display
      "(Anonymous class)" — fingerprint-equal across fresh synthetics);
      (iv) ctx PULL-derivation cpaCtxAt(node): STOP-null at any statement
      edge; DEFINE at call-arg (the argCtxTypes computation: single-sig +
      B86.1b inference mapper + literal mapper; multi-sig strictSelect /
      every-overload-callable), objlit PropertyAssignment initializer
      (propCtx from ctx(O).members, non-any/error else null), SpreadAssignment
      (null), arrow EXPRESSION body (bodyCtx = single-sig return); INHERIT
      through paren/conditional/binary/array-literal/template-span/as/
      nonnull/prefix/postfix/await/spread AND NewExpression args (a legacy
      quirk: new's args inherit the OUTER ctx — no clearing); ctx is
      provably NULL at every statement dispatch (arrow Block bodies get
      bodyCtx=null; fn-exprs null explicitly); (v) the tier-2 chain test
      needs an expression-edge REACH classifier (the spineUResExprEdge
      pattern) — legacy expr-walk quirks: TaggedTemplate walks the TAG only
      (spans unreached), ForStatement INITIALIZER unreached (condition +
      incrementor reached), ForIn/ForOf initializer AND iterable expression
      unreached (ForOf's getTypeOfExpression is not a walk), decorators
      unreached, objlit METHOD bodies unreached (else -> {}),
      ShorthandPropertyAssignment unreached, CommaList unreached,
      arrow/fn-expr PARAM DEFAULTS unreached; statement-edge expression
      roots: Var initializers / ExprStmt / Return / If condition / While-Do
      condition / Switch subject + case exprs / Throw / With /
      ExportAssignment / Enum member inits / Class heritage + members.
      (the cta-m2b/m2c pattern — expect quirk-extraction cycles; the known
      quirks from the g1c design: GetAccessor bodies have NO scope copy,
      enclosingClassType is KEPT through arrows / nulled at fn-decl+fn-expr
      boundaries, contextualType clears before bodies, the pass is
      PASS-PRIVATE for currentParamBindingNames per the w2 lesson, and the
      driver does NOT reset currentLocalTypes per file — cpa consumes cta
      RESIDUE cross-file (round-542 noImplicitAnyForIn TS7053), which the
      frames must reproduce or own-record).
    - [x] **(cpa-m3…) Emission moves** — COMPLETE rounds 581-583; **(cpa-retire)
      LANDED round 585: the checkPropertyAccess legacy pass is DELETED** (the
      first giant off emit-twice; audit scaffolding removed with it).
    - [x] **(cta-retire) LANDED round 586: the checkTypeAssignability legacy
      pass is DELETED** (both migrated giants off emit-twice; audit
      scaffolding removed).
    **(g3 = ccet DECOMPOSITION, queued round 588 from the in-code scout —
    the LAST giant; mirror the twice-proven cpa sequence, one commit each):**
    - [x] **(ccet-m1) State-model scout — COMPLETE round 588b.** Additional
      facts: the expr walker has NO contextualType channel (plain recursion);
      arrow/fn-expr arms copy 2 maps (localTypes+paramBindings) + register
      own params anyType + Block-body shadowing; the ObjectLiteral arm does
      a SCOPED localTypes copy around member walks; EMISSIONS ARE
      PER-CALL-NODE (checkSingleCallExpressionTypes at CallExpressions,
      checkSingleNewExpressionTypes at NewExpressions) — so the m3 anchor is
      per-Call/New-node at ITS OWN LEAVE (the probe discipline), with frames
      supplying ambient; no emit-via-containing-walk ownership complication
      (nested-fn-body calls anchor at their own nodes under spine-maintained
      frames). DECISION: pins-first — NO fingerprint audit (CcetAnchorTest
      exactly-once pins + corpus/listAll gates; the audit pattern's quirk
      extraction is replaced by the gates, which caught all three cpa-m3a
      quirks anyway).
      ORIGINAL ITEM: **(ccet-m1) State-model scout completion + audit-or-pins decision.**
      Scouted so far (in-code, round 588): the driver resets currentLocalTypes
      per file since round 584 (residue-free); FunctionDeclaration arm copies
      currentLocalTypes + currentParamBindingNames AND pushes the fn's OWN
      TPs onto currentTypeParamScope (constraint materialization included),
      then populateParameterLocalTypes + applyCallTypesBodyLocalShadowing +
      shadowNestedFunctionNames (the M1.11 ecology — presence-only consults,
      the first-touch cache-poisoning hazard is documented in the helpers);
      ClassDeclaration arm pushes class TPs + resolves the class symbol via
      globals ?: inferenceNamespaceStack.last().exports; ModuleDeclaration
      pushes inferenceNamespaceStack via resolveModuleDeclNamespaceSymbol
      (DOTTED namespaces handled — unlike cpa's arm); the IfStatement arm
      does a SCOPED single-name union-narrowing override (save/write/restore
      around the then-walk); the VariableStatement arm ORDER-RECORDS
      annotated-callable + B98.r126 + callable-shadow entries. REMAINING to
      scout: the expr walker's arms (contextual channels?), the class-member
      dispatch, funcParams/currentFunctionParams overlay production, and
      currentEnclosingEnum/classForThis usage. DECISION POINT: rounds
      585/586 showed the audits end as deleted scaffolding — consider going
      pins-first (CcetAnchorTest exactly-once) + frame-skeleton-with-
      corpus-gates instead of the full fingerprint audit; the audit earned
      its keep on cta/cpa quirk EXTRACTION, so keep it only if the frame
      skeleton's first corpus gates diff untraceably.
    - [x] **(ccet-m2) LANDED round 589 — box checked round 671 after verifying
      in code** (`ccetSpineEnter` / `ccetSpineFileReset` are called
      unconditionally from spineEnterNode and the per-file loop, so the frames
      are always-on; its dependent (ccet-m3) landed round 591 and
      (ccet-retire) round 592, which could not have happened otherwise). The
      two in-code "inert until the anchors land" comments were stale and are
      corrected. Spec retained below for reference. FULL SPEC (round 588c
      in-code read of every arm):** CcetFrame fields: localTypes(HashMap) +
      paramBindings(HashSet) [copied at fn-decl/method/ctor/contextual-fn
      boundaries + arrow/fn-expr expr-arms], tpScope+tpAst [fn-decl pushes
      OWN TPs with interning + constraint materialization; class arm pushes
      the DECLARED class type's TPs resolved via
      globals ?: inferenceNamespaceStack.last().exports; STATIC methods POP
      the class scope but mint FRESH TPs for their own typeParameters],
      superBaseSig/superBaseType [ctor gets both, method gets Type only —
      from the per-class baseResolution computed under the class TP scope],
      nsSymbol [ModuleDeclaration arm, NON-declare only, dotted-aware via
      resolveModuleDeclNamespaceSymbol], classSym [callWalkerClassStack
      push], the method-body `this` registration [instance methods:
      currentLocalTypes["this"] = getDeclaredTypeOfSymbol(classSym)],
      GetAccessor/SetAccessor bodies walk with NO copies. Var-arm ORDERED
      recordings (interleaved with initializer walks — the cta interleave
      lesson): callable-annotated + union-of-callables + literal-union +
      callable-shadow anyType; the B246 CONTEXTUAL fn-expr channel
      (FunctionType-annotated var + fn-expr/arrow init → params typed from
      the annotation with ?-undefined unions — a frame VARIANT, replaces
      the plain initializer walk); the If-arm SCOPED type-guard narrowing
      override (resolveUserTypeGuardNarrowing at the If enter, save/write/
      restore around the then — the cta-m3i narrowing-frame precedent);
      ForIn/ForOf withForLoopVarShadow around bodies. REACH QUIRKS (differ
      from BOTH prior giants): For-INITIALIZER expressions ARE walked
      (decl initializers + expression form); param DEFAULT initializers ARE
      walked at fn-decl/method/ctor arms (BEFORE the body frame — under the
      OUTER ambient); DoStatement walks body BEFORE condition;
      declare-module bodies are SKIPPED entirely (Declare gate — unlike
      cpa); DOTTED namespace bodies are RECURSED (unlike cpa);
      heritage expressions walk UNDER the class TP scope + class stack;
      objlit arm does a scoped localTypes copy. There is also a
      maxCheckDepth recursion guard (callTypeCheckDepth) at the statement
      dispatcher — reproduce as an int-valued reach cap if fidelity
      requires (the round-535 spineArgDepth precedent). LAST FACTS (588d):
      withForLoopVarShadow copies BOTH maps but ONLY when a loop-header
      binding name COLLIDES (in globals or currentLocalTypes, not already
      in paramBindings) — colliding names are REMOVED from localTypes +
      added to paramBindings; no collision → NO copy (share). Declare-module
      subtrees need a frame `dead` flag (anchors skip; children inherit).
      The If-arm narrowing + ForIn/ForOf shadows reproduce as scoped
      override frames with restore records at the body node's leave (the
      cpa loop-var-restore mechanism). ARROW/FN-EXPR frames push at the FN
      node's enter (the copies wrap BOTH body kinds — expression-body calls
      see the registered params too). Class frames push at ClassDeclaration
      enters (tpScope + classSym + the baseResolution pair computed under
      the class scope), maps SHARED; member-body frames derive from them.
      Implementation staging: (ccet-m2) frames always-on, gates must stay
      IDENTICAL (no emissions move yet — any diff is a first-touch
      coupling to bisect); then (ccet-m3) per-call anchors + marks + pins.
    - [x] **(ccet-m3) LANDED round 591** (merged; the gap-signature gate made
      the interleave FP order-free) + **(ccet-retire) LANDED round 592 — ALL
      THREE GIANTS OFF EMIT-TWICE.** (history: round 590 blocked state:) per-Call/New/TaggedTemplate anchors at
      leaves + the full per-edge reach classifier + legacy marks +
      CcetAnchorTest (8/8, incl. the static class-TP skip-gate pin) + the
      re-enabled decl recordings (the round-589 flip is MOOT under anchors:
      the legacy verdict truncates). Corpus GREEN (11,347/0). BLOCKER: ONE
      interleave FP — the cta return anchor at services.ts:1327 (the
      objectAllocator objlit vs ObjectAllocator) sees CCET-WARMED caches
      (per-node interleaving ≠ pass-after-pass, the round-559 warning) and
      resolves TP-carrying member types (`() => NodeObject<TKind>`) → a
      TS2322 the legacy order never produced (services/server/harness +1).
      A typeContainsForeignTypeParam construct-sig extension did NOT
      suppress (on the branch; possibly resolvedReturnType null at gate
      time, or a non-gate emitter). NEXT WINDOW: (1) identify the emitter
      with the round-472 Diagnostic-init probe keyed (2322, the 1327 start
      offset) on the services profile; (2) fix the gate's REACH or gate
      that emitter (order-free-verdict discipline, both cache states
      silent); (3) structural fallback: defer ccet anchors to a per-file
      second walk. Then merge the branch + gates + (ccet-retire).
      ORIGINAL: **(ccet-m3…) Emission moves** with the leave-dispatch discipline
      (cpa's probe lesson: anchor at statement/expression LEAVES) + the
      recorded-set truncation, then **(ccet-retire)** via the round-585
      experiment template (no-op the dispatch → gates → delete).
  - [x] **INV.4(f) CLOSED round 599 — both wins are measured dead-ends at
    the current cost structure** (f1 memo: the servable calls are cheap;
    f2 fold: confirm-once tax + epoch churn → noise); the real INV.4 win
    was the retirements (−13% wall) + ONE authoritative walk. Revive the
    memo designs after INV.5's canonical types. ORIGINAL: **The two unlocked soundness wins.** Once one authoritative
    walk state exists: the per-node expression-type cache (594,779 calls over
    ~221,844 distinct nodes = ×2.6 recompute), and flow narrowing folded into
    reference typing once (84,469 depth-0 walks, 68% from property access).
    Re-measure against the ≤10 s single-threaded compiler-profile target.
- [x] **INV.5 Canonical types + explicit instantiation — SUBSTANCE COMPLETE round 604** (interning (a), mapper flip (b2), context-keyed nodeTypes (c), budget (d1), generic gate + pin sweep (e) all landed; residuals are deferred/demoted/blocked: (bN) behind the frame redesign, (c2) cosmetic, (d2) hygiene — checkbox reconciled round 612) (absorbs M5.2/M5.3;
  NOW THE ACTIVE ARC ITEM — the round-543 g1a bisect proved the INV.4(e)
  giants are blocked on exactly this: first-touch-order-sensitive shared
  caches). Decomposed round 544, one commit each, every step suite +
  listAll-×8 gated:
  - [x] **INV.5(a) Union/intersection interning.** DONE round 545 (see the session note — landed with the ternaryOfArrayLiterals gate extension after the round-544 near-miss). `getUnionType` (Checker.kt
    ~103k, "mints a fresh Type.Union(sorted) with a new id — does NOT
    intern") + `getIntersectionType` intern by sorted member-id key (the
    `referenceCache` pattern; preserves display member order by keeping the
    FIRST-built instance). Directly serves order-insensitivity: an interned
    union has the same id regardless of which pass builds it first. KNOWN
    HAZARDS (from the gotcha corpus): (1) aliasDisplayMap is id-keyed — an
    interned union SHARED across contexts must not receive one context's
    alias name (the singleton-intrinsic display-corruption hazard
    generalized; union alias display already has the structural
    `unionAliasStructural` map — union registrations in aliasDisplayMap may
    need to move there entirely); (2) the id-only dedup gotcha (duplicate
    structurally-identical members) is UNCHANGED by interning — do not
    conflate the two; (3) the round-424 structural wash-gate workaround
    stays correct (it stops RELYING on fresh ids but never assumed them);
    (4) relation-cache/cycle-stack behavior only gains hits (same-id
    identical pairs). Verify: suite + listAll ×8 + re-run the round-542/543
    probe experiments to measure how much of the giant entanglement
    dissolves.
    **FIRST ATTEMPT (round 544, REVERTED): a minimal interning of both
    canonical constructors (CheckerState caches by member-id key; unions by
    sorted order, intersections in-order) measured CORPUS 100% GREEN
    (11,243/0) with EXACTLY ONE new FP, identical on all 8 profiles —
    watch.ts:533:19 TS2322 `(string | DiagnosticMessage)[]` ⊄
    `DiagnosticAndArguments` (the round-446 VARIADIC-TUPLE alias family).
    Remarkably contained for a change canonicalizing every union in the
    program — the hazard list's display fears did NOT materialize; the one
    regression is a relation/suppression path keyed on union identity
    (candidates: a relation-cache FALSE shared across contexts, an id-keyed
    side channel hitting a shared instance, or the
    arrayLiteralSatisfiesTupleTarget suppression's engine fallback). NEXT:
    root-cause with a targeted probe (temporary Diagnostic-init stack-trace
    probe keyed on code=2322 + the watch.ts:533 start per the round-472
    recipe), fix the one path, re-land.**
    **PROBE RE-RUN (round 546, post-(a)): the g1a' couplings PERSIST under
    canonical union identity (both typeArgumentDefaultUsesConstraintOn-
    CircularDefault and relationComplexityError still fail with the giant at
    the spine slot; probe reverted). The residual first-touch sensitivity is
    NOT union-identity — it lives in declaredTypes/aliasDisplayMap
    resolution TIMING (the Test<any> display) and the relation/complexity
    verdict state — i.e. exactly the (b)/(c) territory (explicit mappers +
    keyed nodeTypes). The INV.5 sequencing holds; continue with (b).**
    **PROBE RE-RUN 2 (round 548b, post-(c)): both g1a' couplings STILL
    persist — the residual first-touch state is specifically (1)
    `declaredTypes` (SYMBOL-keyed alias resolutions — the Test<any>
    display; a different cache from nodeTypes) and (2) the TS2859
    relation/complexity verdict state. The giant unblock therefore needs a
    declaredTypes context-keying sibling of (c) plus a
    complexity-verdict-state audit — queue them as (c2)/(c3) when
    returning to the giants; the two probe tests
    (typeArgumentDefaultUsesConstraintOnCircularDefault,
    relationComplexityError) are the standing acceptance gate for any such
    step. Probe reverted.**
    **(c2) SCOUTED (round 549): the Test<any> coupling is a
    LAZY-MATERIALIZATION first-touch, not a cache-keying one —
    `Type.TypeParam.constraint`/`.default` are MUTABLE fields set at 8+
    scattered sites by whichever pass resolves the TP first (the
    typeParamInternCache shares the instance program-wide), so a no-args
    generic reference instantiates with defaults ONLY IF some earlier pass
    already materialized `.default`. DESIGN: EAGER TP materialization — one
    fixed init step (after globals merge, before any check pass) resolving
    every TypeParameter's constraint/default under its declaration's
    sibling-TP scope (the checkTpListDefaults scope-building pattern),
    making the fields order-free; the 8 lazy setters become no-ops
    (already-set guards) and eventually delete. Acceptance: the two probe
    tests + full gates.**
    **(c2) HYPOTHESIS FALSIFIED (round 549b, attempt REVERTED): a minimal
    eager top-level TP materialization (constraint+default fields filled at
    a fixed init point) did NOT dissolve the probe failure — the coupling's
    mechanism is the EFFECTIVE-default-via-constraint computation inside
    reference instantiation (the probe test's own name:
    typeArgumentDefaultUsesConstraintOnCircularDefault — tsc substitutes
    the CONSTRAINT when the default is circular), i.e. resolution-path
    state beyond the raw fields. Next root-cause step: instrument WHAT
    the legacy checkTpListDefaults slot changes that the later TS2353
    display consumes (candidate: the referenceCache entry for Test<any>
    minted during its constraint-relation checks, which the annotation
    resolution then reuses vs mints bare). Deferred behind (b2+)/other
    INV.5 work — the display-only coupling is cosmetic, not semantic.**
  - [x] **INV.5(b) Explicit mapper objects — installer flip COMPLETE round
    604 (b2a-b2d4): 87 write sites → 4; the survivors are the spine frame
    LIFO writers (restore-at-leave — not region-formable; the designed
    residual until frames carry mappers). (bN) ambient-field REMOVAL
    stays open behind that frame redesign.** Replace the ambient
    `currentTypeAliasArgs`/`currentTypeParamScope` instantiation contexts
    with an explicit mapper threaded through the resolution entry points —
    the enabler for (c). MEASURED SURFACE (round 546): 87 write sites in 34
    functions (top installers: checkCallTypesInStatement ×7,
    walkStmtsForTypeParamCasts ×6, checkReturnAssignability /
    resolveGenericPropertyTypeWorker / getTypeFromTypeReference /
    resolveInterfaceMembersCore / checkConstraintsInStatements ×4 each) +
    ~90 read sites inside the resolution family. DECOMPOSITION (bridge
    pattern — each step suite + listAll-×8 gated): (b1) a `TypeMapper`
    value (aliasArgs + tpScope + a stable fingerprint for cache keying) +
    an optional `mapper` param on `getTypeFromTypeNode`/
    `getTypeFromTypeReference` DEFAULTING to the ambient (behavior-
    identical bridge; the `cacheable` gate reads the param); (b2+) flip
    installer families to pass explicitly — (b2a) DONE round 549c: all 6
    simple aliasArgs installers flipped via aliasMapper/layeredAliasMapper
    (b2b) DONE round 549d: the remaining 3
    aliasArgs installers flipped too — alias substitution ~93.8k,
    constraint-retry ~89.6k, mapped-type per-key ~140.4k; the aliasArgs
    ambient is now single-writer (the bridge); tpScope families next);
    (b2c/b2c'-''', rounds 550a-550d) DONE: ALL resolution-internal tpScope
    installers flipped to the REGION form (`withInstantiationContext(
    scopeMapper(...)) { ... }` — inline, non-local returns preserved):
    resolveGenericPropertyTypeWorker (outer + inner method scope),
    resolveBaseTypesLazy, resolveInterfaceMembersCore (sig + index), the
    getTypeOf* lazies, buildBaseConstructorSignatureForSuper,
    buildSignatureForFunctionLikeTypeNode, reresolveSigParamsUnderClassScope,
    getTypeFromTypeLiteral's method branch, checkConstraintsForTypeArgs.
    REMAINING (deliberately deferred): the walker-level installers (die
    with INV.4(e)), the dual-ambient-field installers
    (checkConstraintsInStatements + currentTypeParamDecls;
    checkMixinClassInStatements + mixinValueScope), the 84067 interleaved
    implicit-any site, and the paired pushFunctionTypeParamsScope; (bN)
    remove the ambient fields (blocked on those). NOTE (c) only needs the mapper AT THE CACHE CONSULT — it can
    start right after (b1) with ambient-bridged installers still in place
    (key = (nodeId, mapper.fingerprint); the context-bypass `cacheable`
    rule dies there).
  - [x] **INV.5(c) `nodeTypes` keyed (node, mapper) — LANDED round 548
    (option iii — the conservative pinned-checking-file gate; see the
    session note; widen the gate as INV.3(d) retires checking-file-dependent
    resolution, and cache the fingerprint per-install if the +5.4%
    single-run wall cost proves real).** Kills
    the context-bypass rule and the first-touch hazard class outright (the
    round-543 blocker). DESIGN (scouted round 547b — the surface is TINY,
    exactly 2 use sites inside getTypeFromTypeNode): a SECOND cache
    (`mappedNodeTypes`) for context-bearing resolutions keyed by an
    IDENTITY node key (=== equality with nodeId-based hashCode — cross-file
    nodeId collisions only share buckets, never results; unindexed nodes
    skip) + a context fingerprint (ns-stack symbol ids + sorted tpScope
    name:id pairs + sorted aliasArgs name:id pairs). The existing
    empty-context cache and its isPerFileDependentRefNode bypass stay
    untouched (identity keys make that hazard structurally impossible in
    the NEW cache). **SOUNDNESS CONSTRAINT (the reason this is not yet
    implemented): context-bearing resolutions ALSO depend on the CHECKING
    file — `currentFileLocals?.get ?: globals` consults are
    checking-file-keyed (the conflation ecology), so a fingerprint that
    excludes that dimension re-creates the first-touch disease inside the
    cache. Either (i) include a reliable checking-file identity in the
    fingerprint (currentCheckFileName is a stale-prone proxy — audit the
    setters first), or (ii) wait for INV.3(d)'s completion to eliminate
    checking-file-dependent resolution, or (iii) start with a
    CONSERVATIVE fingerprint that additionally requires
    currentFileLocals === the node's owning file's locals (node-keyed
    consult, cheap via owningSourceFile with a per-file memo) and skips
    caching otherwise.** Option (iii) is self-validating and incremental —
    preferred.
  - [x] **INV.5(d) — (d1) budget DONE round 552; (d2) DEMOTED to hygiene round 611 (checkbox reconciled round 612).**
    **(d2) DEMOTED round 611 (evidence-based): the round-598 depth-0
    attribution puts the ENTIRE relation family at ~927ms — the (d2)
    allocation redesign is no longer a perf lever (the levers are the
    walks + typeOfExpr, both blocked on canonical types). Remaining (d2)
    value is hygiene only: `resolvedPropertyTypes` caches under the
    first-touch ambient scope (a context-keying hole like the pre-548
    nodeTypes) and never caches null results. Re-open only if a
    correctness drift traces here.**
    Delete `resolveGenericPropertyType` fresh-minting + its depth-4 OOM cap
    (the per-recursion-level cache-miss gotcha). **(d1) DONE round 552: the
    depth-4 cap is DELETED — replaced by the per-top-level-relation
    instantiation budget + the param-side foreign-TP gate in
    tryEmitObjectVsNamedUnionArg (see the session note). Remaining: the
    member-table-on-reference allocation redesign ((d2), optional now that
    the budget bounds allocation) and the fresh-minting deletion.**
    **CAP-LIFT PROBE FALSIFIED (round 551, reverted): removing
    `relationDepth < 4` with (a)-interning + the (ref.id, prop.id) memo in
    place still KILLS performanceComparisonOfStructurallyIdentical-
    InterfacesWithGenericSignatures — the deep-stack thread dies after ~20 s
    (OOM → NPE at runWithDeepStack's result unwrap). The blowup is BREADTH,
    not depth: each comparison level mints genuinely NEW (target, args)
    references (growing arg shapes), so the memo never hits and the
    deeply-nested 5-occurrence heuristic (which fires at relation ENTRY)
    doesn't bound the per-level member/signature instantiation between
    bails. The real (d) fix is tsc-shaped: an instantiation-count budget
    (tsc's instantiationDepth/instantiationCount → TS2589) plus member
    tables cached ON the reference, NOT a cap lift. Keep the depth-4 cap
    until then.**
    **BUDGETED-LIFT PROBE (round 551b, also reverted): a per-top-level-
    relation budget of 2,000 fresh worker computations (reset at depth-0
    relation entry, consumed on memo miss, raw fallback on trip) TAMES the
    perf-bomb — corpus fully green 11,252/0 — but exposes exactly ONE new
    FP on all 8 profiles: program.ts:2924 TS2345 `(readonly Diagnostic[] |
    undefined)[]` ⊄ `T[][] | readonly (T | …)[]` (tsc's flatten<T> — the
    documented M3.1 masked gap: tsc infers T, we don't, and the old
    depth-≥4 trivial-pass masked it). A TP-free gate on DEEP substitution
    results does NOT kill it — the outcome flips inside the relation
    (target side), not at the substitution result. VERDICT: the cap
    deletion is blocked on generic inference (M3.1) / the (e)-era
    engine-opening work, not on allocation strategy — sequence (d) with
    (e), and consider a param-side foreign-TP bail at the call-arg
    emission as the enabling slice (corpus-gated; the round-431 gate
    family's rationale applies verbatim to un-inferred PARAM types).**
  - [x] **INV.5(e) Open `canUseTypeEngine`'s generic gate; delete superseded
    pin walkers** (suite-gated per deletion). DONE round 600: sweep verdict
    15/16 load-bearing, checkGenericFnTypeBipartition deleted. Then RETURN to INV.4(e).
    **FIRST HALF DONE round 553: the hasUnresolvedTypeParams skip is
    DELETED (corpus + listAll ×8 identical; the Box<T>-vs-Box<string>
    false negative now fires — Inv5GenericGateTest). Remaining: the
    pin-walker deletion sweep.**

- [x] **INV.6 Parallelism — Phase 0 CLOSED round 609** (6a-6d1: --workers 2 = −17% wall, output sorted-identical, all-8-profile partition equivalence; w4 flat at the per-worker redundancy ceiling — Phase 1 shared frozen collectors is the reopener, gated on an immutability audit; (6e) parallel emit deferred: emit workers would race the shared checker's lazy caches, and benches are --noEmit). Share-nothing checker workers per
  `docs/parallel-caching.md` (trivially partitionable once INV.4 gives a per-file
  check entry); parallel emit on Default + IO write sink; deterministic partition +
  merge via the existing diagnostic sort. Structured concurrency from INV.1.
  - [x] **(6a) The spine partition seam** — DONE round 605: `assignedFileNames`
    gates both spine per-file loops; sequential-equivalence contract pinned by
    SpinePartitionEquivalenceTest.
  - [x] **(6b) Profile-scale equivalence A/B** — DONE round 606:
    `--partitionCheck N` harness; EQUIVALENT on all 8 profiles (w=2) + the
    two stress profiles (w=4). Zero divergences — (6c) unblocked.
  - [x] **(6c) The parallel driver** — DONE rounds 607-608 (6c0 thread-local
    id sequences + deep-stack handoff; 6c1 runInDeepStackWorkers +
    `--workers N`). Measured: w2 −14% wall, w4 flat (per-worker redundant
    fixed cost — see the round-608 note); output sorted-identical to
    sequential.
  - [x] **(6d1) Widen the partitioned region** — DONE round 609: 193
    emission-pass loops on `checkedResults` (318 pure collectors stay
    program-wide); all-8-profile equivalent; w2 −17%, w4 flat. Deeper
    widening = Phase-1 shared frozen collectors (immutability audit) —
    queue that only after INV.5 canonical types or on a >4-core box.
  - [ ] **(6e) Parallel emit** on Default + IO write sink (INV.1's Flow
    foundation; no dashboard delta expected — benches are --noEmit).
- [x] **INV.7 Productization — CLOSED for queue purposes (checkbox reconciled
  round 687): 7a/7c1/7d1/7d2/7d3 all landed and the only remaining child, (7b)
  release binary + native bench row, is PARKED-BY-OWNER.** (absorbs M5.5/M5.6). Native re-enable (the big-input
  GC inversion should largely dissolve post INV.4/5); watch mode driven by a
  file-event Flow; `.tsbuildinfo`-style incremental reuse.
  - [x] **(INV.7c1) `--watch` minimal watch mode** — DONE round 613 (full
    rebuild per debounced change batch; fileEvents Flow expect/actual;
    end-to-end verified, 46ms warm rebuild). Incremental reuse is (7d).
  - [x] **(INV.7d1) Watch-mode incremental recheck** — DONE round 614
    (reverse-dependency closure over the INV.6 partition seam; full-rebuild
    bails for non-local changes; --watchVerify field gate; equivalence
    pinned by WatchIncrementalTest).
  - [x] **(INV.7d2) The shared-name residual bail** — DONE round 615
    (sharedNameFiles: lib-global KNOWN_GLOBALS ∪ script top-level names;
    bidirectional bail via eligibility + outcome validation; +2 pins).
    Real-lib names outside the curation stay on the --watchVerify net.
  - [x] **(INV.7d3) Cross-process `.tsbuildinfo` persistence** — DONE round
    617 (owner approved the generateBuildInfo build change 2026-07-19):
    `XTSC_BUILD_ID` (git sha, `.dirty`/`unknown` never persist nor reuse)
    stamps `tsconfig.xtsbuildinfo`; cold start hash-validates inputs (incl.
    every `.json` config read via RecordingVfs) and runs the (7d1) closure
    protocol for the changed set under `--incremental --noEmit`; new files
    caught by the outcome shape check. TsBuildInfoTest (+11).
  - [x] **(INV.7a) linuxX64 re-enabled** — DONE round 610: compiles/links/runs
    byte-correct (compiler profile = the exact 46-error floor, 196s debug
    binary; smoke 82ms). EpochMap/Set now composition (K/N HashMap is final).
  - [ ] **(INV.7b) Release binary + native bench row.** PARKED-BY-OWNER
    (round 617, 2026-07-19: "we can switch it off for now"). History:
    BLOCKED-ON-RESOURCES at round 610b — the optimizing link OOM-kills the
    daemon on the 7.7GB box (twice, incl. -Xmx5g + daemons stopped). If ever
    revived: re-attempt on a ≥16GB builder; the debug binary carries
    correctness meanwhile.

Numeric targets (proposed, doc § 6): post INV.4/5 single-threaded compiler profile
≤ 10 s (≈ JS tsc) + harness RSS ≤ 1 GB; post INV.6 compiler ≤ 5 s on 4 cores;
INV.7 stretch: native cold ≤ 2× tsgo.

### Post-v1 backlog — the "any TypeScript project" horizon (UNPARKED round 679)

**UNPARKED 2026-07-25 (round 679).** v1 was declared at round 481 and was
RE-VERIFIED at HEAD this round, 200 rounds later: all 8 profiles exit 0, emit
EVERY input file (81/81, 312/312, 84/84, 78/78, 274/274, 252/252, 80/80,
88/88), zero crash frames, and every one of the 140 diagnostics is a missing
Node ambient (`process`/`Buffer`/`require`/`NodeJS`/`console`) under a
`"types": []` tsconfig — i.e. config/env artifacts, not compiler faults. With
EP.2c skipped by the owner and the remaining M5/INV items parked or
zero-value on this box, **this section is now the live queue**.

**SUPERSEDED 2026-07-26 (round 716) — THIS SECTION IS NO LONGER FIRST.** Owner
directive: "do anything needed … to increase the performance", followed by "how
should we proceed to match the tsc performance on a single thread". The PERF
section above is the live queue again, and **(DISPATCH.1) is the top unchecked
item**; work it before anything here. This section stays OPEN and unparked — it
is not cancelled, and it holds the only known SILENT-WRONG-ANSWER defect in the
codebase (M2.4: with `"lib": ["dom"]` a browser project's DOM code compiles
CLEAN and entirely unchecked) plus the "real project" gaps (declaration emit,
sourcemaps, JSX, nodenext). **The trade being made is explicit: matching tsc's
speed is being prioritised over making the compiler usable on non-tsc projects.**
Revisit when the perf arc reaches its staged target or stalls.

(Historical note: the loop was to skip this section until v1 landed. It landed
at 481; the section stayed parked ~200 rounds because nothing re-read the
condition. Worth remembering as a queue-hygiene failure mode in its own right.)

- [x] **M4.8 DONE round 680 — `/// <reference path|types>` pulls files into the
  program.** Resolution-KIND confusion: the parser recorded directives into
  `moduleSpecifiers`, which the crawl resolves as MODULE specifiers, but a
  `path=` target is a file path relative to the referencing file and a `types=`
  target is a type-root package. Split onto `SourceFile.referencedPaths` /
  `referencedTypes`; the crawl resolves each correctly and TRANSITIVELY. TS6053
  needed no change (the checker asks whether the target is in the program, so it
  goes silent exactly when resolution succeeds — pinned both ways). Measured with
  `@types/node`: program 79 → 146, TS2591 43 → 13. Dashboard untouched (all 8
  profiles identical in errors AND program size); suite 12,598/0/3 (+19 pins).
- [x] **M4.9 DONE round 686 — 30 → 13 on the `"types": ["node"]` profile, and
  every survivor is the env-legit TS2591 class** (a file using
  `require`/`process` without importing node types — the same class the eight
  dashboard profiles carry by design). ONE cause behind the whole residual:
  `mergeModuleAugmentations` published every export of a FILELESS `declare module
  "spec"` into `globals`. Right for an AUGMENTATION (globals is its only
  visibility channel); wrong for the identical syntax in a SCRIPT `.d.ts`, which
  DECLARES the ambient module — those members are reachable only through an
  import of the specifier. The damage was not a stray name but a WRONG WINNER:
  the published member outranked a file's own import alias, so tsc's sys.ts
  resolved its own `WatchOptions` to `@types/node`'s `fs.WatchOptions` and every
  downstream check disagreed with the source. Gating on the declaring file being
  an external module (tsc's own augmentation-vs-declaration distinction;
  `moduleFiles` is already populated before this pass) cleared TS2353×7,
  TS2339×3, TS2322×2, TS2345, TS7006, TS1345, TS2709 and TS2558 at a stroke.
  **Found by discrimination, not search:** a four-file repro, then a probe type
  declared ONLY inside the ambient module — it drew TS2304 (not in the TS2304
  walker's scope) while its MEMBERS resolved (in the type-position scope), which
  located the split in one run. Gates: suite 12,651/0/3 (+4 pins), `--listAll` ×8
  byte-identical (the dashboard's `"types": []` keeps it off this path).
  Round-681 part 1 (below) landed `skipLibCheck` and the parameter-shadows-
  namespace bail. **A NINTH dashboard profile for `"types": ["node"]` is still
  worth adding** — do NOT alter the existing eight.
- [ ] ~~M4.9 (part 1, round 681)~~ — Landed:
  `skipLibCheck` is now honoured (it was parsed and never consulted — TS7008×15
  + TS7010×2 were being reported against DefinitelyTyped's own declaration
  files), and a PARAMETER now shadows a same-named namespace that reached
  globals from an ambient module body (TS2339×18 → 3; tsc's
  `formatJSDocLink(link: …)` vs `fs.d.ts`'s `export namespace link`). REMAINING
  on that profile: 13 TS2591 (`require`/`process` where the file references node
  types without importing them), **7 TS2353** (`fs.WatchOptions` vs the
  compiler's own `WatchOptions` in an object literal — the next-largest
  cluster), 3 TS2339, plus TS2322×2/TS7006/TS2709/TS2558/TS2345/TS1345
  singletons. Repro: copy the profile tsconfig with `"types": ["node"]`
  (fixture gitignored at `build/bench/tsc-project-637d5746/node_modules/@types/node`).
  Consider a NINTH dashboard profile to track it — do NOT alter the existing
  eight, whose `"types": []` is deliberate.
- [ ] ~~M4.9 (original)~~ — **The gaps `@types/node` exposes once it loads** (found round 680,
  directly downstream of M4.8). With `"types": ["node"]` on the compiler profile
  the missing-ambient errors mostly clear (TS2591 43 → 13) and what remains is
  REAL, previously masked by the unresolved names: **TS2339×18** (e.g.
  `Property 'kind' does not exist on type 'typeof link'`), **TS7008×15**
  (implicitly-any members), **TS2353×7** (`'watchFile' does not exist in type
  'WatchOptions'` — our `fs.WatchOptions` vs the compiler's own `WatchOptions`),
  TS2322×2, TS7010×2, TS7006, TS2709. Reproduce by copying the profile tsconfig
  with `"types": ["node"]` (fixture already at
  `build/bench/tsc-project-637d5746/node_modules/@types/node`, gitignored).
  Consider adding it as a NINTH dashboard profile so the numbers are tracked —
  but do NOT change the existing eight, whose `"types": []` is deliberate.
- [ ] ~~M4.8 (original)~~ — **`/// <reference path|types="…" />` must ADD files to the program**
  (found round 679; the single highest-impact gap for "any TypeScript project").
  Our handling — `TypeScriptCompiler.kt` ~2168, gated on
  `includeReferencePathDeps`, i.e. `outFile` only — merely ORDERS files ALREADY
  in `allTsFileNames`. tsc's `processReferencedFiles` **pulls the referenced
  file into the program**. Consequence, measured: `@types/node`'s `index.d.ts`
  is 64 `/// <reference path>` lines and little else, with `globals.d.ts`
  declaring `var process` and `namespace NodeJS` — so enabling
  `"types": ["node"]` on the compiler profile took the program from 78 to just
  **79** files and left all 46 diagnostics standing. Every real Node project is
  affected the same way. Fixture already installed (gitignored) at
  `build/bench/tsc-project-637d5746/node_modules/@types/node`; the probe config
  was a temporary `tsconfig.node.json` (deleted — recreate by copying the
  profile tsconfig with `"types": ["node"]`). The dashboard tsconfig
  deliberately keeps `"types": []` and our handling of THAT is correct per tsc
  semantics — do not "fix" the baseline; add a separate profile if one is wanted.
- [ ] ~~M2.4 DOM libs~~ — **SUPERSEDED round 716 by (LIB.1) at the top of the queue** (owner: "yes, please fix it"; the owner-gated lib-shipping decision it was blocked on is now granted). Body kept for its measurements.
- [ ] ~~M2.4 (original)~~ — RE-SCOPED round 687 by measurement: the premise is wrong
  and there is a SILENT-WRONG-ANSWER bug underneath it.** The item asked to
  measure dom.generated.d.ts's parse/bind cost. That cost is **not measurable
  because the DOM libs are NOT SHIPPED**: `RealLibFiles` contains no
  `dom.generated` / `dom.iterable.generated` / `webworker*` entry (its only "dom"
  occurrences are `/// <reference lib="dom" />` lines inside OTHER libs' text).
  **What `"lib": ["dom"]` does today:** `RealLibResolver.resolve` records the file
  in `Resolution.unavailable` and the final `ordered` list filters it out —
  and `Resolution.unavailable` is **never consumed outside RealLibs.kt**, so
  nothing is reported. Measured consequence on a 3-line program: `HTMLElement`
  resolves, `document` resolves, and `e.definitelyNotAMember` on an `HTMLElement`
  parameter compiles **CLEAN** — i.e. a browser project gets a green build with
  its DOM code entirely unchecked. (Without `dom` in `lib` the same name draws
  TS2552 "Did you mean 'HTMLLIElement'?", because DOM names are in KNOWN_GLOBALS
  for the TS2304 walker — which is why adding `dom` LOOKS like it worked.)
  **Round 688 CORRECTION — follow-up (i) was attempted and REVERTED as dead
  code, which uncovered the bigger fact: `useRealLibs` defaults to FALSE and
  NOTHING in the project path turns it on** (`ProjectCompiler`/`TsConfigLoader`
  never set it; the only writer is the `usereallibs` test directive). So the
  entire real-lib machinery — `RealLibResolver`, `RealLibSnapshots`,
  `Checker.bindRealLibs`, and `Resolution.unavailable` with it — is exercised
  ONLY by tests that opt in. **Every real project build, including all eight
  dashboard profiles, runs on the EMBEDDED `BUILTIN_LIB_SOURCE`.** A diagnostic
  wired into `bindRealLibs` therefore never executes; it was implemented, seen
  not to fire, and reverted rather than landed. Two further facts the attempt
  established, both needed by whoever picks this up: **(a) `unavailable` must not
  be the key** — a `full` default lib (`lib.d.ts`, `lib.es2020.full.d.ts`)
  transitively references the DOM/host files, so an ordinary target-default
  resolution has a non-empty `unavailable` and must stay silent; only a name the
  USER wrote is reportable, which needs a new field, not the existing one (a
  working `unavailableRequested` implementation is in the round-688 reflog if
  wanted). **(b) the corpus blocks the embedded-path fix**: 259 corpus cases
  carry `@lib:`, of which **23 request `dom`** plus `webworker`×4,
  `webworker.iterable`×2, `webworker.asynciterable`, `scripthost`,
  `esnext.temporal`, `esnext.intl` — all unshipped, all currently GREEN, so
  reporting on the embedded path breaks ~30 baselines that were generated by a
  real tsc which HAS those libs.
  **So the real follow-ups are, in order:** (i) **decide what real project builds
  should use for libs at all** — the embedded lib is a curated subset while the
  shipped real libs are unreachable outside tests; that mismatch is the root, and
  it is a design decision, not a patch; (ii) **ship the DOM/webworker/scripthost
  sets** — changes the real-lib GENERATION in build.gradle.kts and adds ~1 MB of
  generated source, so **owner-gated**; (iii) only then is the original
  parse/bind cost question answerable, and only then can an unshipped-lib
  diagnostic be both correct and reachable.
  **Method note worth keeping:** the first control I ran — "does `HTMLElement`
  resolve with `dom` in lib?" — PASSED, and a clean 5-pair interleaved A/B then
  showed the cost inside the noise band. Both were measuring nothing. When an
  unknown name degrades to `any`, name resolution proves nothing; the control
  that decides is a **MEMBER probe** (`e.notAMember` must error).
- [ ] **M3.0 Conformance generator extension — INFRASTRUCTURE DONE round 690; FOUR
  categories adopted (round 695); the remaining categories are measured, not guessed.**
  **Round-695 redness table** — twelve candidate categories added to the allowlist in ONE
  suite run (+236 tests, 91 failures), then all but the tractable ones reverted. Failures
  per category, so a future round can pick by cost instead of re-measuring:
  `es6/defaultParameters` **0** · `es6/restParameters` **1** · `expressions/commaOperator`
  **2** · `expressions/asOperator` 5 · `types/any` 6 · `types/conditional` 8 ·
  `types/nonPrimitive` 9 · `statements/labeledStatements` 9 · `types/typeAliases` 9 ·
  `expressions/contextualTyping` 9 · `expressions/typeSatisfaction` 12 ·
  `expressions/optionalChaining` 21. The first three were adopted; the rest are each a
  round's worth of gap work. Two caveats worth carrying: `statements/labeledStatements`
  is 9 failures from only 8 files (proportionally the reddest), and its failures include
  **JS-emit** subtests, which `conformanceDeferredErrorBaselines` cannot defer — an emit
  gap must be FIXED before that category can land. Measuring a batch this way costs one
  ~7-minute run and is much cheaper than adopting a category and discovering it is red.
  Extend `generateTypeScriptTests` with a
  per-category allowlist for `tests/cases/conformance/` (keep all tsgo set-B
  filters). Each category lands only when its failures are triaged into queue
  items — never leave a category half-red without notes. Owner approval
  (2026-07-02) stands.
  **Verified round 689 (do NOT re-derive):**
  1. **The sources ARE readable offline.** `typescript-repo` is a BLOBLESS partial
     clone (`remote.origin.partialclonefilter = blob:none`, `promisor = true`) and
     its sparse checkout lists only `tests/cases/compiler` +
     `tests/baselines/reference` — so this looked network-gated. It is not: a
     `git cat-file -p HEAD:tests/cases/conformance/…` probe returns content, so
     the needed blobs are already local.
  2. **Baselines need no work** — the sparse checkout already takes the WHOLE
     `tests/baselines/reference`, which is flat and holds the conformance ones.
  3. **The variant-baseline convention is ALREADY implemented.** Conformance uses
     `name(target=es5).errors.txt`; the generator's `computeVariations` /
     `paramBaselineName` produce exactly `name(key=value).ext`.
  4. **ZERO basename collisions** between ALL of conformance and the 6,537
     compiler cases, so the generated flat backtick function names need no
     disambiguation.
  5. **Sizing for the M3.1-matching categories:** `expressions/functions` **7
     files** (the right first category), `types/typeParameters` 46,
     `types/typeRelationships` 263.
  **The three edits:** (a) `sparsePaths` (build.gradle.kts ~271) += the
  allowlisted category dirs; (b) the `testFiles` collection (~547) currently
  `testsDir.listFiles { flat }` must also walk the allowlisted conformance dirs
  RECURSIVELY (categories have subdirs); (c) the generated bodies hardcode
  `Path("${'$'}typeScriptCasesDir/<name>.ts")` where `typeScriptCasesDir` is
  `tests/cases/compiler` (TypeScriptTestSupport.kt:38) — a conformance case needs
  its own path, so emit a per-file relative path or add a second constant.
- [x] **(M3.0-gap-1) DONE round 692 — `arrowFunctionContexts` passes and is
  un-deferred.** Three defects, one per round-690 triage line. The TS2403 ×2 FALSE
  POSITIVE was a generic arrow mistyped `<T>(n: T) => any` (round 691: the arrow's
  own type parameters were interned only when the `Signature` was built, i.e.
  after the return had been inferred). The two MISSING codes landed this round:
  TS18033 fired only for a STRING-typed computed member, so a function-valued one
  (`enum E { x = () => 4 }`) drew nothing — extended to a syntactically
  arrow/function-expression initializer, FP-safe by construction since such a
  member can never satisfy the numeric domain; and the TS2332 walker skipped arrow
  bodies alongside function bodies, but an ARROW DOES NOT REBIND `this`, so
  `(() => this).length` in an enum initializer is just as illegal — the descent
  emits TS2332 only, because the reference baseline has no companion TS2683 for
  the arrow-nested form.
- [ ] **(M3.0-gap-2) PARKED round 714 — everything worth having from this case has
  shipped; the case itself stays deferred by DECISION, not by omission.** Fixed across
  rounds 693/704/706/707: the over-emitted TS7019/TS7006 (IIFE parameters are
  contextually typed, so tsc reports nothing for them), the contextual TYPING itself
  (from the call's arguments, in `populateParameterLocalTypes`), and all three TS18048 —
  including the pure-`undefined` reference case, which also fixed the literal-vs-reference
  boundary against TS18050. Round 713 additionally closed the argument-context TS7006
  hole the case exposed, under noImplicitAny.
  **Why it will not un-defer:** its remaining TS7006 ×2 are on argument arrows in a file
  whose only directive is `@strictNullChecks` — pure-default mode, where the full
  implicit-any walker is deliberately OFF and the narrow default-mode walker covers one
  shape on purpose. Closing it requires broadening that walker, which is the change
  recorded as having regressed ~19 tests. Not worth it for one conformance case; revisit
  only if the default-mode walker is broadened for its own reasons.
  ORIGINAL: **the FALSE-POSITIVE half is FIXED (round 693); the missing codes remain.** tsc
  contextually types an IIFE's parameters from the call ARGUMENTS, so it reports
  no implicit-any for them even when the call passes none; we emitted TS7019 ×3 +
  TS7006 ×2. `isImmediatelyInvokedFunctionParam` (owner walked up through
  parentheses to a CallExpression whose unwrapped callee is that function)
  suppresses both, in BOTH emitters — the general parameter walker AND the
  dedicated rest-parameter walker, which carries its own TS7019 and TS7006 and is
  the one live for these shapes.
  **ROUND 704: the parameters ARE now typed from the arguments** (in
  `populateParameterLocalTypes`, per the round-694 finding — `((a) => a.nope)("x")`
  reports TS2339 on `string`), with two pieces still open. **(i)** Only ARROWS are
  typed; a function EXPRESSION IIFE is not, and the site responsible is NOT the
  no-contextual-annotation branch that blanket-registers `any` for a callback's own
  parameters (deferring there was measured inert). A limitation pin records this.
  **(ii)** The typed parameters now produce the RIGHT analysis with the WRONG code:
  `((j?) => j + 1)(12)` reports **TS2365** ("Operator '+' cannot be applied to types
  'number | undefined' and '1'") where tsc reports **TS18048** ('j' is possibly
  'undefined') — the documented round-415 hazard, where a union carrying `undefined`
  fails the arithmetic operand classifier. tsc checks possibly-undefined FIRST, so the
  fix is a nullish-operand rule ahead of TS2362/2363/2365 in the arithmetic pass.
  That is what still keeps the case deferred.
  **ROUND 706: the rule LANDED — direction confirmed against a SECOND reference baseline
  (`circularOptionalityRemoval` reports TS18048 for `x > 0` with `x: number | undefined`,
  as `contextuallyTypedIifeStrict` does for `j + 1`), and the two TS2362 baselines that
  also mention "possibly undefined" were checked and are unrelated (their operand is a
  `delete` expression, a boolean). The nine local pins were updated to expect TS18048,
  their intent unchanged, and one paired positive control strengthened to exclude TS18048
  too.** **ROUND 707 closed the `k`/`o` half too** — a REFERENCE typed exactly `undefined`
  now reports TS18048 like a union does (the arithmetic walker's strictNullChecks early
  return deferred those to TS18050, which is right only for the LITERAL operand). All
  three TS18048 of the case now fire at the baseline's positions. **What remains is only
  the two TS7006** for the INNER function's parameter in `(f => f(12))(i => i)`.
  **ROUND 708 probed it and the framing "argument arrows are not reached" is WRONG** —
  four contrasted shapes under `@strictNullChecks: true`: `take(i => i)` against an
  annotated `(x: number) => number` parameter is correctly SILENT; `anyCb(j => j)`
  against an `any` parameter correctly FIRES, so the walker does reach an argument
  arrow and does emit there; `(f => f(12))(k => k)` is silent (the gap); and — the
  surprise — a plain `function plain(m) { return m; }` is ALSO silent in the same file.
  That last one is not about IIFEs or arguments at all, so the next round should start
  by settling the GATE question (which shapes emit TS7006 under which options, and why
  a top-level function declaration's parameter differs from a callback's here) before
  touching the IIFE case. Do not assume the callee-typing path is at fault.
  **ROUND 710 CORRECTS ROUND 709: the two-walker split is DELIBERATE and documented, so
  "unify the gates" is the wrong instruction — do not follow it.** `checkImplicitAny
  DefaultVarFunctions` runs ONLY in pure-default mode and covers ONE shape
  (`var v = <arrow|fn-expr>` with an untyped parameter), because the full
  `checkImplicitAnyParameters` walker is gated on noImplicitAny/strict for a MEASURED
  reason: broadening it regressed ~19 tests (FunctionDeclaration params, type-annotation
  walking, ambient TS7005/7008, JS files, object-literal contextual-typing gaps). The two
  are mutually exclusive by construction so they never double-emit.
  **What survives from round 709 as a real finding** is narrower and still worth fixing:
  `anyCb(j => j)` (an arrow argument against an `any` parameter) is reported in
  pure-default mode but NOT under noImplicitAny — turning the stricter option ON loses a
  diagnostic, which cannot be right whatever the walker split is. And gap-2's
  `(f => f(12))(k => k)` is uncovered in BOTH modes. So the target is a COVERAGE hole in
  `checkImplicitAnyParameters` (argument arrows whose callee parameter provides no
  contextual type), not the gates.
  **ROUND 711 located it exactly: a CONTRACT MISMATCH.** The argument edge is built as
  `SpineIanyCtx(kind = 1, typed = isCalleeResolvable(node.expression))` (~53248), i.e. it
  uses "can I resolve the callee NAME" as a proxy for "does this argument have a
  contextual type". Those come apart in precisely the two shapes that are missing:
  `anyCb(j => j)` — the callee resolves, so `typed = true` suppresses, but its parameter
  is `any` and therefore supplies NO contextual signature, which is why tsc reports it;
  and `(f => f(12))(k => k)` — the callee is a parenthesized ARROW, so
  `isCalleeResolvable` returns its default `true` and suppresses again.
  **The fix is to consult the callee's PARAMETER TYPE at the argument's position** (no
  contextual signature when it is `any`, unresolved, or not function-shaped) rather than
  the callee's resolvability. Note `isCalleeResolvable` also has a deliberate B182 arm
  (a LIB_MIN_TARGET-dropped method has no contextual signature) — the same idea, applied
  to one case; this generalises it. **Gate carefully:** broadening this walker is the
  change documented as having regressed ~19 tests, so expect the corpus to arbitrate,
  and run `--listAll` ×8 as well since callback parameters are everywhere in tsc's own
  source. Round 709's framing below is kept only to mark the
  **ROUND 712 IMPLEMENTED IT AND REVERTED — two narrowings are already spent, start
  from them.** The edge change is small and works: at the argument consumer (~53370) the
  index IS available (`p.arguments.indexOfFirst { it === node }`), so `typed` becomes
  `callCtx.typed && !calleeParamIsPositivelyAny(p, idx)`; with it all three target shapes
  fire under noImplicitAny — including gap-2's `(f => f(12))(k => k)` — and
  `take(i => i)` stays silent. **(1) Our resolved `anyType` is NOT tsc's `any`:** deciding
  on the RESOLVED parameter type red-lined three corpus baselines
  (contextualPropertyOfGenericFilteringMappedType,
  contextualTypeFunctionObjectPropertyIntersection, normalizedIntersectionTooComplex),
  because a generic or mapped annotation we cannot resolve lands on `anyType` too and
  those DO have contextual types — the test must be SYNTACTIC (the annotation is literally
  the `any` keyword, or absent), which makes the corpus green. **(2) The EMBEDDED LIB's
  `any`s are placeholders:** with the syntactic rule the PROFILES gain FPs (46 → 47,
  harness 94 → 98) on `.replace(/\./g, s => s.substring(1))` and
  `JSON.stringify(f, (_, v) => …)`, since our lib simplifies those callback signatures
  where tsc states them precisely. Excluding the builtin-lib decl sets is the right
  direction and is precedented (the TS2554 lib gate), but the exclusion I wrote did NOT
  catch the `.replace` site — establish first which set holds a resolved lib METHOD's
  parameter for a PropertyAccess callee, then it should land.
  correction. ORIGINAL (WRONG): **the two TS7006 emitters have INVERTED option gates.** Same four shapes, two configs:
  | shape | strictNullChecks only | + noImplicitAny |
  | `take(i => i)` (annotated context) | silent (right) | silent (right) |
  | `anyCb(j => j)` (`any` parameter) | **FIRES** | **SILENT** |
  | `(f => f(12))(k => k)` | silent | silent |
  | `function plain(m) {}` | **SILENT** | **FIRES** |
  Turning `noImplicitAny` ON switches OFF the emitter that was firing, and vice versa —
  so no single configuration reports both shapes, and `anyCb(j => j)` going silent under
  noImplicitAny is a plain bug (tsc reports it). Relevant context for whoever fixes this:
  TS7006 fires BY DEFAULT in the corpus — 12 of 22 sampled TS7006 baselines have no
  `@noImplicitAny`/`@strict` directive at all — so the default-on convention
  (`!strictExplicitlyFalse`) is the one that matches the reference, and the
  `noImplicitAny || strict` gate is the odd one out. Unify the two gates on the
  default-on convention FIRST, re-gate, and only then look at the IIFE shape; it may
  well fall out, since the conformance case sets only `@strictNullChecks: true`.
  ROUND 705's framing, kept for the reasoning: **the rule works — but it collides with
  NINE LOCAL PINS, and resolving that collision is a decision, not a patch.** The rule (a possibly-undefined
  check ahead of TS2362/TS2363/TS2365 in the three arithmetic emitters, strictNullChecks
  only, plain references only, `any`/`unknown` excluded) turns `((j?) => j + 1)(12)` into
  the TS18048 the reference baseline wants. The CORPUS stays green — but nine hand-written
  pins in ArithmeticAmpAmpNarrowingTest, ArithmeticReassignmentNarrowingTest,
  Inv4SpineBatch22Test and NonNullArithmeticOperandTest assert that a maybe-undefined
  operand fires **TS2362**, e.g. `negative control - genuinely maybe-undefined operand
  still fires TS2362`. **The evidence says those pins encode OUR old behaviour rather than
  tsc's:** the `contextuallyTypedIifeStrict` reference baseline reports TS18048 for exactly
  this shape (`j: number | undefined`, `j + 1`), and the corpus is green either way, so it
  does not discriminate. Their INTENT — "narrowing did not apply, so it still fires" — is
  preserved by TS18048; only the code changes. So the next round should update those nine
  to expect TS18048, having first confirmed the direction against one more real baseline,
  and then re-gate. The rule was reverted rather than landed with nine red pins.
  **Still missing after it, for the record:** `k`/`o` (an optional parameter with NO
  corresponding argument types as `undefined`, and nothing fires for it yet) and the two
  TS7006 for the INNER function's parameter in `(f => f(12))(i => i)`.
  **ORIGINAL REMAINING:** the reference's **TS18048 ×3** ('j'/'k'/'o' possibly undefined, from optional IIFE
  parameters under strictNullChecks) and **TS7006 ×2** (lines 28–29 — the INNER
  function's parameter in `(f => f(12))(i => i)`, which tsc genuinely reports)
  do not fire.
  **Round 694 established WHERE the hook must go, by writing it in the wrong place
  first.** Typing the parameters in `getTypeOfArrowFunction` (next to
  `applyContextualParameterTypes`, writing `symbolTypes[param.id]`) is
  UNOBSERVABLE: `((a) => a.nope)("x")` still reports nothing, because the BODY
  walkers do not read `symbolTypes` for parameters — they read `currentLocalTypes`,
  filled by **`populateParameterLocalTypes`**, which records a parameter ONLY when
  it carries an ANNOTATION (`if (paramType != null && paramName is Identifier)`).
  So an un-annotated parameter is invisible to them no matter what the signature
  says. That implementation was written, measured, and REVERTED rather than landed.
  **The real change is therefore in `populateParameterLocalTypes`** (or wherever
  else a walker derives parameter locals): record an argument-derived type for an
  un-annotated parameter whose owner is an IIFE callee — reusing
  `immediatelyInvokingCall`-style parent-walking, which round 693 already proved
  out. Expect a WIDE blast radius: it gives types to parameters that were `any`
  everywhere, in ~26 call sites' worth of walkers, so it needs the corpus and the
  `--listAll` ×8 gate and probably its own round.
- [ ] **(M3.0-gap-3) `commaOperatorOtherInvalidOperation` — (A) and (B1) are DONE
  (rounds 697/700/701); only (B2) remains, so the case stays deferred.** What is left is
  the second TS2322, `var result: T1 = (x, y)` — TypeParam-vs-TypeParam, blocked by the
  relation's "two unconstrained type parameters always relate" leniency, whose correct
  form was measured in round 695 at exactly 2 corpus tests (both masking an
  un-substituted class type parameter in a member) — plus `canUseTypeEngine` refusing a
  TypeParam-vs-concrete pair, which is what keeps `var s: string = x` silent even though
  the relation already answers correctly. ORIGINAL TEXT follows.
  Two missing TS2322, both from the same root: `function foo(x: number, y: string)
  { return x, y; }` must infer the return type `string` (so `var r: number = foo(...)`
  errors), and `var result: T1 = (x, y)` — with `x: T1`, `y: T2` — must report
  `Type 'T2' is not assignable to type 'T1'` plus the "could be instantiated with an
  arbitrary type" chain line and a TS2208 related info at the `T2` declaration.
  We already emit the case's other two diagnostics (TS2454 ×2), so this is additive.
  **(A) IS DONE (round 697)** — `inferReturnTypeFromBody` gained a Comma arm typing the
  right operand from the OWNING function's parameter annotations (`commaReturnOperandType`);
  corpus green, all 8 profiles byte-identical, +6 pins. Only (B) remains, so the case
  stays deferred.
  **Round 695 isolated both halves — read this before starting, two of the obvious
  routes are already excluded.** A five-line probe (`function baz(...): string` beside
  the inferred `foo`, and a `var direct: T1 = y` beside the comma one) splits the case:
  **(A)** the comma itself is only half the story — `combineBinaryTypes` ALREADY types
  a comma as its right operand (`SyntaxKind.Comma -> getTypeOfExpression(right)`), and
  the annotated `baz` errors correctly, so what is missing is
  `inferReturnTypeFromBody`, whose `BinaryExpression` arm has no Comma case. Note its
  deliberate conservatism: its `Identifier` arm returns null for anything but
  `true`/`false`, because it runs in the CALLER's scope, where resolving a callee's
  parameter by name would hit the documented shadowing hazard. So a Comma arm cannot
  just call `getTypeOfExpression(right)` — the honest fix types the right operand
  against the OWNING function's parameter annotations (reachable via the body's
  `parent`), which also fixes the more general `return <param>` gap.
  **(B)** is NOT a comma problem at all — `var direct: T1 = y` (no comma) is equally
  silent — and, measured, it is **not the TypeParam-vs-TypeParam relation either**:
  making two unconstrained type parameters relate only when their names match left the
  case silent, so the emission is suppressed UPSTREAM (the round-431e foreign-TP source
  gate on the var-decl path is the prime suspect — `T2` is a TypeParam in the source).
  Start there, not in the relation engine.
  **Measured cost of the correct relation rule, recorded so nobody re-runs it:** exactly
  **2** corpus tests (`inferFromGenericFunctionReturnTypes1`/`2`), both the same
  `Type 'SetOf<B>' is not assignable to type 'SetOf<B>'` shape — identical display, so
  the leniency is masking an UN-SUBSTITUTED class type parameter in a member (`_store:
  A[]` substituted on one side only). Restricting the strict rule to top-level
  comparisons (`relationComparisonStack.size <= 1`) dodges both regressions, but buys
  nothing while (B)'s real blockers stand.
  **(B)'s real blockers, found by marker probe (round 695 tail) — TWO of them, and
  neither is the relation.** A four-case probe (`f1<T>(x: T) { var s: string = x }`,
  `f2<T1,T2>(y: T2) { var r: T1 = y }`, an array variant, and a fully concrete control)
  printing `typeToString` of both sides plus `canUseTypeEngine`/`checkTypeRelatedTo`
  at `checkVarDeclAssignability`'s gate reports:
  **(B1) a type-parameter annotation on a function-BODY variable resolves to `any`** —
  `var r: T1` gives `tgt=any` (and `var r2: T1[]` gives `any[]`) while the PARAMETER
  annotation `y: T2` resolves correctly, because a parameter is resolved while building
  the signature with the type parameters in `currentTypeParamScope` and a body variable
  annotation is not. So no relation could ever fail here — the same class of bug as
  round 691's generic arrow, one scope level out.
  **(B2) `canUseTypeEngine` refuses a TypeParam-vs-concrete pair** — for `var s: string
  = x` the relation ALREADY returns the correct `false` (`foreign=false`, `rel=false`),
  but `canUse=false` means the emission never runs. tsc reports TS2322 there.
  Fix (B1) first (it is the one that makes `T1` a real type at all); (B2) then decides
  whether the correct verdict is allowed to be emitted. Both have M3.1-flavoured blast
  radius — body variables annotated with type parameters stop being `any` — so each
  wants the corpus and `--listAll` ×8, and the round-431e foreign-TP gate is what should
  keep un-inferred callee TPs out of the new emissions.
  `typeParams` threading is NOT a suspect: the probe shows it arriving correctly
  (`tp=[T]`, `tp=[T1, T2]`) and the foreign-TP gate not firing.
  **(B1)'s ONE-LINE fix is known and was measured (round 696, attempt 1, reverted) —
  do this as ONE change with the chain-parity work below, never alone.** The cta frame
  ALREADY computes the type-parameter scope (`CtaFrame.fnTpScope`, built beside
  `fnTpDecls` at frame-build time) and **never reads it** — `grep fnTpScope` returns its
  declaration and its single write. The per-statement dispatch installs
  `currentTypeParamDecls = frame.fnTpDecls` but not the scope, so annotations resolved
  during that dispatch see no type parameters. Adding
  `currentTypeParamScope = frame.fnTpScope ?: <saved>` to the same save/install/restore
  sandwich works — probe: `var r: T1` goes `any` → `T1`, `var r2: T1[]` → `T1[]`.
  **Its measured cost: 27 corpus tests, and the classification is the useful part** —
  of ~32 changed baseline lines, **~29 are REMOVED `'T' could be instantiated with an
  arbitrary type which could be unrelated to 'null'/'undefined'` chain lines**, i.e. the
  emission survives and only its chain is lost. Mechanism: with `T` resolving to a real
  `Type.TypeParam`, these `return null`-in-a-generic shapes stop falling through to the
  STRING fallback `emitTS2322(..., typeParams)`, which adds that chain when
  `targetBaseName in typeParams` (Checker.kt ~149892), and are handled by a type-engine
  emitter that does not. The var-decl (~95363) and assignment (~98644) paths already
  have the `tt is Type.TypeParam` chain block; the return path's engine emitter is the
  one to give parity. Only **3** lines were additions: one chain-FORM flip
  (constraint-form → arbitrary-form, `errorMessagesIntersectionTypes03`) and two genuinely
  NEW diagnostics (`Type 'Q' is not assignable to type 'InferBecauseWhyNot<Q>'`,
  `Type 'any[]' is not assignable to type 'T'`) that need their own verdict.
  **Attempt 2 (round 696, also reverted) took it from 27 failures to FOUR — the recipe
  below is ~5 minutes of re-typing, so start there rather than re-deriving.**
  *Edit 1 — the scope install:* in the cta per-statement dispatch sandwich (beside
  `currentTypeParamDecls = frame.fnTpDecls ?: emptyMap()`), save `currentTypeParamScope`,
  set it to `frame.fnTpScope ?: <saved>`, restore it in the same `finally` as
  `currentTypeParamDecls`.
  *Edit 2 — chain parity* in `checkReturnAssignability`'s engine emitter, inserted
  immediately BEFORE its "B60.6f (mirror): TS2208 related info" block: when
  `chain.isEmpty() && targetType is Type.TypeParam`, add the constraint form
  (`'<src>' is assignable to the constraint of type '<T>', but …`) when the constraint
  is non-null AND `checkTypeRelatedTo(sourceType, constraint)` AND
  `!anonymousObjectHasExcessVsConstraint(...)`, else the arbitrary form — exactly the
  block the var-decl (~95363) and assignment (~98644) paths carry. This alone clears
  **23 of the 27**.
  **Attempt 3 (round 698) took it to THREE, and named the mechanism behind the last
  two. Add to the recipe:** in the new chain block, the constraint must be treated as
  absent when it is `anyType` **OR `errorType`** — an unconstrained `<T>` arrives here
  with an UNRESOLVED constraint, and errorType DISPLAYS as `'any'` (B58.1), which is
  what made `declFileGenericType` read as `constraint 'any'`. That one guard fixes
  residual (a). Remaining: (b), (c), (d) below.
  **(c) and (d) are DOUBLE EMISSIONS, not false positives — the baselines contain both
  diagnostics, our error COUNT grows by one.** The `Diagnostic`-init stack-trace probe
  named the other emitter for (c): the dedicated pin walker
  **`checkDeeplyNestedMappedTypes`**, which exists precisely because the engine could
  not produce that diagnostic — and its display is the CORRECT one
  (`{ level1: { level2: { foo: string; }; }; }[]`) while the engine renders the source
  as `any[]`, because the case's `Input`/`Output` mapped aliases resolve to any. So the
  engine does NOT supersede the walker here and the walker must not be deleted. Note
  the ORDER, which decides the fix: the engine (cta anchor) emits FIRST and the walker
  later, so a "has anything already reported here?" probe in the engine cannot see it —
  the retraction has to live in the WALKER (documented precedent: a later pass that
  retracts/edits an earlier pass's diagnostics, cf. checkCloduleTest2 removing TS2554 at
  NewExpression positions). (d) was not probed but shows the identical signature
  (baseline has the diagnostic; our count goes 8 → 9), so expect another dedicated
  walker and the same disposition.
  **Attempt 4 (round 699) got the corpus to ZERO with the whole change — and then the
  PROFILES killed it. This is the real blocker; read it before touching (B1) again.**
  Corpus 12,731 / 0 / 3 with all four residuals fixed (see the completed recipe below),
  but `--listAll` ×8 went 46 → **49 on every profile** (harness 94 → 97): three NEW
  false positives, the same three everywhere, all in `compiler/utilitiesPublic.ts`:
  `Type 'Node | undefined' is not assignable to type 'T | undefined'` (777),
  `Type 'JSDocTag | undefined' …` (1280), `Type 'JSDocTag[]' is not assignable to type
  'readonly T[]'` (1285). **All three are TYPE-GUARD-DRIVEN GENERIC INFERENCE:**
  `getFirstJSDocTag<T extends JSDocTag>(…, predicate: (tag: JSDocTag) => tag is T)`
  returns `find(tags, predicate)`, `getAllJSDocTags` returns
  `getJSDocTags(node).filter(predicate)`, and `tryCast`-shaped code returns
  `nodeTest(node) ? node : undefined`. tsc binds the callee's own type parameter to the
  CALLER's `T` through the `tag is T` predicate, so the sources are `T | undefined` /
  `readonly T[]`; we bind the concrete `JSDocTag` and therefore see a mismatch. These
  were invisible while the return annotation resolved to `any` — resolving the target is
  what exposes them. **v1's dashboard is at ZERO real FPs, so this cannot land until
  they are gone.** Two ways forward: make guard-driven inference bind the caller's type
  parameter (independently valuable — round 430 already built "TP-from-PREDICATE
  binding" for exactly this family, so start by finding why it yields `JSDocTag` here),
  or add a TARGET-side companion to the round-431e foreign-TP gate. Prefer the first:
  a target-side gate must still let `function f<T>(): T { return null; }` error, which
  the corpus pins, so it would be a heuristic in the place heuristics are most likely to
  silently lose real errors.
  **THE COMPLETED RECIPE (corpus-green; all four residuals fixed):** edits 1 and 2 as
  described, plus — (a) treat `anyType` OR `errorType` as "no constraint"; (b) report
  the APPARENT constraint by following the interned chain to its first non-TypeParam
  link and, when that yields nothing usable, following the DECLARATION's constraint
  chain by name and resolving its first concrete link (factored as
  `apparentConstraintOfTypeParam`, needed by the ASSIGNMENT path too — that is where
  `errorMessagesIntersectionTypes03`'s `V extends U extends A` is decided, not the
  return path); (c)+(d) register every return-path engine TS2322 in a pre-`init` list
  and, at the end of `init`, drop it by IDENTITY if another TS2322 shares its position —
  dedicated pin walkers run after the spine and own some of these positions with better
  displays, so the engine cannot probe for them at emission time.
  **The FOUR residuals, each already diagnosed:**
  (a) `declFileGenericType` — `export function F5<T>(): T { return null; }` is
  UNCONSTRAINED, yet the interned TypeParam arrives with `constraint == anyType`, so the
  new block picks the constraint form where tsc uses the arbitrary one. Fix: treat an
  `any` constraint as unconstrained (the sibling TS2208 block right below already has an
  "effectively unconstrained" notion, for the self-circular case).
  (b) `errorMessagesIntersectionTypes03` — the reverse: tsc wants the CONSTRAINT form
  (`'A & B' is assignable to the constraint of type 'V'…`) and we produce the arbitrary
  one. Round 698 narrowed the cause: the constraint does not RESOLVE (same errorType
  situation as (a)), so no relation can be run and the engine has no `'A'` to print —
  which is exactly why the old string fallback got it right, reading the constraint
  TEXT out of `currentTypeParamDecls` (`emitTS2322`'s B60.6c path). The fix is to give
  the engine block the same syntactic fallback: when the RESOLVED constraint is
  unusable, take the declaration's constraint node text and decide the form the way
  B60.6c does, rather than dropping to the arbitrary form.
  (c) `deeplyNestedMappedTypes` and (d) `conditionalTypeAssignabilityWhenDeferred` are
  genuinely NEW emissions, not chain problems — `Type 'any[]' is not assignable to type
  'T'` and `Type 'Q' is not assignable to type 'InferBecauseWhyNot<Q>'`. Both are targets
  that only became checkable once the scope resolved them, and both are M3-depth (a
  mapped-type return and a DEFERRED conditional type, which tsc relates under rules we
  do not model). Expect these two to need a gate of their own — the round-431e foreign-TP
  gate is the family precedent — and note they are also the two most likely to appear on
  the profiles, so `--listAll` ×8 is mandatory before landing.
- [x] **(M3.0-gap-4) DONE (rounds 702/703) — `readonlyRestParameters` passes and is
  un-deferred.** Two rules, both narrower than they first look. **TS2556:** an unbounded
  array spread into a fixed-arity call cannot be arity-checked, so tsc rejects it — with
  four narrowings that each came from a red test rather than from reasoning (a TUPLE
  spread is legal; an ARRAY LITERAL spread is legal, tsc counting `...[6, 7]` as two
  arguments; spreading INTO a rest parameter is legal; and an already-too-many call
  reports the COUNT instead). A rest parameter's type does not resolve in the arg-count
  pass, so the operand is classified from its ANNOTATION when the resolved type is
  unavailable, which also handles `readonly string[]` for free. **TS2554:** a rest
  parameter annotated with a fixed TUPLE has fixed arity, and a tuple-typed spread
  argument contributes its element count. The trap that made round 702's first attempt
  inert: the excess anchor is an ARGUMENT INDEX, not the expanded count —
  `emitTS2554TooMany` opens with `if (firstExcessIdx >= args.size) return`, so passing a
  count of 2 with 2 arguments returned silently.
- [ ] **M3.5 Per-file scopes** (Blocker #3: stop merging all file locals into
  `globals`; per-file scope construction with explicit import visibility). Revisit
  before v1 ONLY if dashboard FPs trace to cross-file scope conflation on tsc sources.
- [ ] **M4.1 Full nodenext resolution**: package.json `exports`/`imports` maps,
  symlink/realpath (pnpm layouts), `typesVersions`, package self-references. (The tsc
  repo itself uses relative imports + @types — unused for v1.)
- [ ] **M4.2 Real declaration emitter.** `.d.ts` output for arbitrary code (the corpus
  strips most `.d.ts` sections, so almost none exists today; `declaration: true` is
  table stakes for "any project"). Test bed: conformance decl baselines + self-compile
  d.ts diffing. Pull into v1 only if the owner defines "fully compile tsc" to include
  declaration output.
- [ ] **M4.3 JSX end-to-end** (`jsx: react-jsx`/`react`/`preserve` transforms on real
  React-shaped code).
- [ ] **M4.4 Sourcemaps — the parenthetical "inline maps exist" is STALE (checked
  round 695): NOTHING generates map content.** `grep sourceMappingURL` over
  `src/commonMain` hits only `TypeScriptCompiler.kt`'s option-conflict validation
  (TS5053 for `mapRoot`/`sourceMap` with `inlineSourceMap`), and `Emitter.kt` has
  no mappings emitter at all. `BaselineFormatter` takes `sourceMap`/
  `inlineSourceMap`/`sourceRoot`/`mapRoot` parameters, which is presumably where
  the belief came from — those shape the BASELINE layout, not the output. So this
  is a full implementation (segment tracking through the transformer, VLQ
  encoding, the `//# sourceMappingURL=` trailer, sidecar `.js.map` writing), not
  the small "also write the file" task the entry implied.
- [ ] **M4.5 Decision point**: project references / composite / incremental scope
  (tsgo supports them; needed for large monorepos — decide build vs defer with owner).
- [ ] **M4.6 `package.json "type": "module"` module-format detection in
  `ProjectCompiler`** (found compiling zod, 2026-07-07): under `module: NodeNext`
  with a `"type": "module"` package.json, real tsc emits ESM but we emit CJS — the
  `collectPackageJsonTypes` machinery exists only for the multi-file TEST-source path
  and is not wired into the on-disk project pipeline. Repro: zod (see M4.7); the
  emitted CJS only runs in a `"type": "commonjs"` context. Unused for v1 (the
  tsc-source bench project has no package.json → CJS default is correct there).
- [ ] **M4.7 zod as a second dashboard profile** (validated 2026-07-07, round 432
  session note): shallow-clone `github.com/colinhacks/zod`, compile
  `packages/zod/src` (107 files, ~31k LOC) via a `tsconfig.xtsc.json` extending zod's
  real `.configs/tsconfig.base.json` (strict, exactOptionalPropertyTypes,
  noUnusedLocals, NodeNext), include `src/**/*.ts`, exclude tests/benchmarks — real
  tsc 6.0.3 reports 0 errors on it, so every xtsc diagnostic is an FP. Baseline
  2026-07-07: 1,665 FPs (top: TS7006×447 contextual params, TS2694×284 namespace
  members via `export *` barrels, TS7029×211 switch-fallthrough, TS2344×182), 0
  crashes, all 107 files emit, output passes a runtime smoke test. Complements the
  tsc-source profiles: stresses generic method chaining + noFallthroughCasesInSwitch,
  which tsc's own source doesn't.

### Offline asset inventory (verified 2026-07-02)

- `typescript-repo` object DB is complete (sparse checkout, full objects): any
  `src/**` path extractable via `git archive HEAD <path>`; `src/lib/` holds the 110
  real lib `.d.ts` files; `tests/cases/conformance/` holds 5,907 `.ts`/`.tsx` cases.
- Node/tsc/tsgo are NOT currently installed — differential testing (M0 optional) and
  real `@types/node` (M1.3) wait for network.
- The benchmark project cache lives under `build/bench/` (cheap to rebuild); results
  TSVs under `bench/` (gitignored, machine-specific).

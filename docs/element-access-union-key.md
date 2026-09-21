<!--
  PRESERVED IN THE REPO BY (P18.165). This is the read-only sizing brief for (CHK.139) —
  written while (CHK.136) was in flight, which is why its header says the compiler was never
  run: the class dir was being rebuilt. Every reference figure is tsgo 7.0.2. Treat the
  MEASURED rows as measured and the rows labelled INFERRED as hypotheses to re-take.
-->

# (CHK.136) SUCCESSOR — sizing brief: an element access whose INDEX TYPE is a union of literals

**Status: READ-ONLY sizing. No repo file was modified, no Gradle task was run, our compiler was
never executed** (the class dir was being rebuilt by the orchestrator). Every reference figure below
was taken with `tools/tsgo-7.0.2/lib/tsc --noEmit -p <dir>`; the fixtures are in `fx/` and the raw
output in `tsgo-raw.txt`. Anything I could not measure is labelled INFERRED.

---

## 0. One-line statement of the defect

`mem[mkey]` where `mkey: keyof Members` (or any literal-union-typed index) resolves to `anyType` in
`Checker.elementAccessResultType`. tsgo resolves it to the **UNION** of the per-key member types for
a READ and to their **INTERSECTION** for a WRITE. Because `cheaGeneralElementWrite` ((CHK.136), just
landed) uses `getTypeOfElementAccess` as both its slot source *and* its firewall, the write check is
silent for exactly this shape — which is `marked`'s three ours-only TS2578 rows.

---

## 1. THE REFERENCE RULE — measured against tsgo 7.0.2, not inferred

### 1.0 The rule in one sentence

> Distribute over the **receiver** union with a UNION in both modes; distribute over the **key**
> union with a UNION for a READ and an **INTERSECTION** for a WRITE; then let the ordinary union /
> intersection reduction run.

This is tsc's `getIndexedAccessType(…, AccessFlags.Writing)`. I did **not** read tsgo's Go source for
this round — the sentence is a *fit to 12 measured fixtures*, and §1.9 is the sharp one that
discriminates it from every simpler rule I could think of.

### 1.1 Read = union, write = intersection  (`fx/r1.ts`, `fx/r2.ts`)

```ts
interface Members { alpha: (t: {type: string}) => string; beta: (t: {type: string}) => number }
declare const mem: Members; declare const mkey: keyof Members;
const a = mem[mkey];   //  READ
mem[mkey] = brand;     //  WRITE
```
```
r1.ts(5,28): TS2322: Type '((t: { type: string; }) => string) | ((t: { type: string; }) => number)'
                     is not assignable to type 'never'.
r1.ts(6,1):  TS2322: Type '{ __b: 1; }' is not assignable to type
                     '((t: { type: string; }) => string) & ((t: { type: string; }) => number)'.
  Type '{ __b: 1; }' is not assignable to type '(t: { type: string; }) => string'.
    Type '{ __b: 1; }' provides no match for the signature '(t: { type: string; }): string'.
```
Method note: the READ type is read out of a `const p: never = r` probe — (PARITY.1)'s `never` target,
which tsgo does **not** generalize, so the printed string is the real type.

### 1.2 Constituents that DIFFER  (`fx/r2.ts`) — the `never` the orchestrator saw is just reduction

`interface Diff { a: string; b: number }`, key `keyof Diff`:
* read  → `string | number`
* write → `Type '{ __b: 1; }' is not assignable to type **'never'**.`

`never` here is **not** a special case: it is `string & number` after the ordinary primitive-domain
reduction. Our `Checker.getIntersectionType` (Checker.kt:176015) already performs exactly that
reduction (`distinctPrimitiveKinds.size >= 2 → neverType`), so building the intersection with the
existing helper reproduces tsgo's rendering for free. **MEASURED, not inferred** — `fx/r9.ts` §1.9
shows the same reduction inside a union.

### 1.3 `keyof T` vs `"a" | "b"` vs `Exclude<keyof T, "c">` vs a literal-union ALIAS  (`fx/r3.ts`)

**All four are IDENTICAL.** `keyof T3` on `{a: string; b: number; c: boolean}` reads
`string | number | boolean`; the other three (which all denote `"a" | "b"`) read `string | number`,
and all three write targets reduce to `never`. There is no `keyof`-specific machinery: the rule is a
property of the index **type** (a union of literal types), not of how it was spelled. So the fix must
key on `Type.Union`-of-literals, never on the syntax.

### 1.4 A key union member that is NOT a property of the receiver  (`fx/r4.ts`)

```
r4.ts(4,11): TS7053: Element implicitly has an 'any' type because expression of type
                     '"a" | "zzz"' can't be used to index type 'R'.
  Property 'zzz' does not exist on type 'R'.
r4.ts(4,25): TS2322: Type 'any' is not assignable to type 'never'.
```
**All-or-nothing**: the whole access is `any` (the `p: never = v` probe prints `any`), *plus* a
TS7053 naming the first absent key. The WRITE position emits the same TS7053 (r4.ts(5,1)) and no
TS2322. The chain line names only the FIRST missing property.

**This is the single most important constraint on the fix**: the new arm must be all-or-nothing,
because answering "the union of the members that DO exist" would be strictly narrower than `any`
and is therefore an FP generator (§4 R1).

### 1.5 An OPTIONAL member in the union  (`fx/r5.ts`)

`interface O { a: string; b?: number }`, key `keyof O`, strictNullChecks on:
* read  → `string | number | **undefined**`
* write → `never` (`string & (number | undefined)`)

Our `getIndexedAccessType` already adds the `| undefined` at its string-literal arm ((CHK.96),
Checker.kt:~175450), so a per-constituent delegation to it inherits this correctly. Our
`elementAccessResultType`'s own string-literal arm does **NOT** — it is a bare `getTypeOfSymbol(prop)`.
That asymmetry is pre-existing and is a live divergence the fix will either inherit or have to
decide about deliberately (§4 R6).

### 1.6 A receiver that ALSO has an index signature  (`fx/r6.ts`)

`interface Ix { a: string; b: number; [k: string]: string | number }`:
* key `keyof Ix` — `keyof` of a type with a string index signature is `string | number`, i.e. **not a
  literal union at all** → read `string | number`, write target `string | number` (the plain index
  signature lookup, NOT an intersection).
* key `"a" | "b"` (an explicit literal union) → read `string | number`, write `never`.

So **the declared properties win over the index signature** whenever the key is a literal union, and
the index-signature path is only reached when the key is the open `string`/`number` type. This
matches our existing preference order in `applicableIndexTypeForName` / `getIndexedAccessType`.

### 1.7 CLASS instance receiver  (`fx/r7.ts`) — identical to an interface

`class C { m1(x: number): string; m2(x: number): number; f = 1 }`, `keyof C`:
read `number | ((x: number) => string) | ((x: number) => number)`, write the corresponding
3-way intersection. Methods and fields are not distinguished. Restricting the key to `"m1" | "m2"`
drops `f` from both. **No class-specific rule.**

### 1.8 GENERIC receiver `T[K]`, `K extends keyof T`  (`fx/r8.ts`) — stays generic

```
r8.ts(3,25): TS2322: Type 'T[K]' is not assignable to type 'never'.
  Type 'T[keyof T]' is not assignable to type 'never'.
    Type 'T[string] | T[number] | T[symbol]' is not assignable to type 'never'.
r8.ts(4,3):  TS2322: Type '{ __b: 1; }' is not assignable to type 'T[K]'.
  'T[K]' could be instantiated with an arbitrary type which could be unrelated to '{ __b: 1; }'.
```
tsgo keeps a **deferred indexed-access type**, which this checker has no representation for. An
*instantiated* generic signature returning `T[K]` DOES resolve (`g3(s, sk)` → `string | number`).
**Recommendation: leave the generic case answering `anyType`.** It is not needed for `marked`, and
`marked`'s own sites are already concrete (`Exclude<keyof _Tokenizer<P,R>, …>` on a concrete
instantiation — §3.2 shows tsgo resolving them to a 24-member intersection).

### 1.9 THE SHARP ONE — receiver union × key union  (`fx/r9.ts`)

```ts
interface A { a: string; b: number }
interface B { a: boolean; b: boolean }
declare const ab: A | B; declare const k: "a" | "b";
const r = ab[k];   // read
ab[k] = brand;     // write
```
```
r9.ts(5,24): Type 'string | number | boolean' is not assignable to type 'never'.
r9.ts(6,1):  Type '{ __b: 1; }' is not assignable to type '**boolean**'.
```
`boolean` = `(string & number) | (boolean & boolean)` = `never | boolean`. This **falsifies** every
"just intersect everything" and every "just union everything" reading and is what pins §1.0's
two-axis rule. Also in r9: a TUPLE with key `0 | 1` behaves identically (read `string | number`,
write `never`), and a MIXED `0 | "a"` key works across the numeric/string kinds.

### 1.10 A WRITE that fails — exact code, message, chain and line:col  (`fx/r11.ts`)

Source (6 spaces of indent):
```ts
      w[wk] = "not a function";
      w["alpha"] = "not a function";
```
```
r11.ts(4,7): error TS2322: Type 'string' is not assignable to type
             '((t: { type: string; }) => string) & ((t: { type: string; }) => number)'.
  Type 'string' is not assignable to type '(t: { type: string; }) => string'.
r11.ts(5,7): error TS2322: Type 'string' is not assignable to type '(t: { type: string; }) => string'.
```
* **code** TS2322.
* **anchor** column 7 = the START of the element access (`w`), spanning the access — exactly the
  `target.expression.pos .. expressionTrueEnd(target)` anchor `cheaGeneralElementWrite`'s KDoc already
  records as measured.
* **chain** the intersection target elaborates into the FIRST failing constituent, 2-space indent.
  The single-key control (line 5) has NO chain line.

### 1.11 `readonly` / getter-only in the key union  (`fx/r10.ts`) — TS2540, not TS2322

```
r10.ts(4,4): TS2540: Cannot assign to 'a' because it is a read-only property.
r10.ts(7,3): TS2540: Cannot assign to 'a' because it is a read-only property.
```
Anchor is the **index expression** (col 4 = `rok`), and the message names the FIRST readonly
constituent by name. Our compiler does not do this today for a union key; not doing it after the fix
is a MISSING diagnostic (safe direction), but it must not turn into a TS2322 instead (§4 R7).

### 1.12 Things I measured and deliberately did NOT turn into a rule

* `mx[mxk]++` where the read is `number | string` is **silent** in tsgo (r12.ts:21 produces no row)
  while `mx[mxk] += 1` reports TS2322 against the `never` write target (r12.ts:20). Recorded as a
  measurement; I have no mechanism for it and did not chase it.
* `keyof string[]` produces a ~35-member union including every `Array` method (fx run earlier). Our
  display of that would certainly differ from tsgo's `… | ... 30 more ... | …` elision.
* The intersection's member ORDER is not declaration order (`marked` prints
  `((s) => number) & ((s) => string)` where the read prints the reverse). Display-only; a
  `LogicalParityDivergence` question if it ever reaches a baseline, not a semantic one.

---

## 2. THE CODE SITE

### 2.1 Where the `anyType` comes from — `Checker.elementAccessResultType` (Checker.kt:133405)

Trace for `mem[mkey]`, `mkey: keyof Members`, receiver a `Type.Interface`:

1. `objectType is Type.Union`? No.
2. `indexExpr is StringLiteralNode`? No — it is an `Identifier`. **The whole string-literal branch,
   including `applicableIndexTypeForName`, is skipped.**
3. `indexExpr is NumericLiteralNode`? No.
4. The index-signature block: `indexType = getTypeOfExpression(indexExpr)` = the union
   `"alpha" | "beta"`, i.e. a `Type.Union`.
   * `indexType.flags.hasAny(TypeFlags.StringLike)` — a `Type.Union`'s own flags do not carry
     `StringLike` (INFERRED from the code; I did not run a probe), so this is false. **Even if it
     were true**, the branch only consults `apparent.stringIndexInfo`, which `Members` does not have,
     so it would still fall through.
   * `NumberLike` likewise.
5. `return anyType`.

**There is no arm anywhere in this function that looks at the index type's CONSTITUENTS.** That is
the defect, and it is a single missing arm rather than a wrong one.

### 2.2 The helper that already implements the distribution — `getIndexedAccessType` (Checker.kt:175417)

`getTypeFromIndexedAccess(node: IndexedAccessType)` (175399) → `getIndexedAccessType(objectType, indexType)`
(175417) is the TYPE-POSITION resolver for `T[K]`, and **it already has the union-index arm**:

```kotlin
// Union index: T[A | B] → T[A] | T[B]
if (indexType is Type.Union) {
    val types = indexType.types.map { getIndexedAccessType(objectType, it) }
        .filter { it !== anyType }
    if (types.isNotEmpty()) return getUnionType(types)
    return anyType
}
```

It also already carries three things the expression path lacks and needs:
* the union-**object** all-or-nothing arm (B516),
* the (CHK.96) `| undefined` for an optional property (§1.5),
* the (R783) `propertyTypeOnCarrier` read, which is what stops a type-parameter-typed interface
  member from globally caching as `any`.

**So the answer to "is the fix a route rather than a new rule?" is YES for the READ — with one
blocking caveat.**

#### 2.2.a THE CAVEAT: the type-level union-index arm is `.filter { it !== anyType }`, i.e. NOT all-or-nothing

tsgo's answer for a key union with an absent member is `any` + TS7053 (§1.4). The arm above instead
drops the absent constituent and answers the union of the survivors — **narrower than `any`**, which
is the FP direction. Routing the expression path through it verbatim would type `r[("a"|"zzz")]` as
`string` where tsgo says `any`.

Two ways out, and the round must pick one **explicitly**:
* **(A) preferred — a thin expression-side arm.** In `elementAccessResultType`, after the numeric
  branch and BEFORE the index-signature block: if `indexType is Type.Union` and every constituent is
  a `Type.StringLiteral`/`Type.NumberLiteral`, map each constituent through
  `getIndexedAccessType(objectType, <constituent>)` and apply **`elementAccessResultType`'s own**
  all-or-nothing convention (`if (parts.any { it === anyType || it === errorType }) return anyType`),
  then `getUnionType(parts)`. This inherits (CHK.96) + (R783) and changes nothing in type position.
* **(B) fix the type-level arm to be all-or-nothing too.** Correct in principle, but it is a change
  to every `T[K]` **type annotation** in the corpus and the profiles — a second, independent blast
  radius, and one the round does not need. **Recommend NOT doing this in the same round;** if it is
  done, it needs its own ablation arm.

### 2.3 The WRITE half — `cheaGeneralElementWrite` (Checker.kt:~111070, landed this round)

```kotlin
val slotRaw = getTypeOfElementAccess(target)
if (slotRaw === anyType || slotRaw === errorType) return
```
The write check is a pure consumer of the READ type. Three consequences:

1. **The write check switches on automatically** the moment the read resolves — no new dispatch.
2. **But it would then check against the UNION, which is WRONG** (§1.0). The union is a strict
   superset of the intersection, so it is *permissive*: every FP the intersection would raise is
   suppressed, and only true positives that fail against *every* constituent survive. That is a
   safe-direction error, but it produces the wrong MESSAGE (`'A | B'` instead of `'A & B'`) and it
   is not what tsgo does.
3. Therefore the round needs a second entry point — e.g.
   `elementAccessWriteType(objectType, indexExpr)` — that is the §1.0 mirror: receiver union folds
   with `getUnionType`, key union folds with **`getIntersectionType`** (which already exists at
   Checker.kt:176015 and already reduces `string & number → never`, §1.2). `cheaGeneralElementWrite`
   then asks for the write type and keeps its `anyType`/`errorType` firewall unchanged.
   `cheaThisRootedIndexWrite` runs first and is unaffected (it resolves through `varTypes`).

**Does marked need the intersection, or would the union do?** MEASURED, and the answer is *the union
happens to be enough for marked but the intersection is still the right rule*: the RHS at
`Instance.ts:202` is `(...args: unknown[]) => unknown`, whose `unknown` return is not assignable to
ANY constituent, so it fails against the union too. Do not read marked's closure as evidence that the
union is correct — `fx/e1`-class probes show shapes where only the intersection rejects.

### 2.4 `getPropertyOfType` per constituent, and the right conservatism

`getPropertyOfType` on a non-union receiver answers the member symbol or null. Per CLAUDE.md its
UNION arm "answers an assignability question and then returns ONE constituent's symbol", so **it must
be asked per constituent and never about the union**, which is exactly what §2.2(A)'s per-constituent
delegation does.

All-or-nothing IS the right conservatism here, for a reason stronger than the existing union-RECEIVER
precedent: it is what tsgo does (§1.4, measured), and it is also the only rule that keeps the fix in
the *permissive* direction where a key cannot be resolved.

---

## 3. THE BLAST RADIUS — counts, not arguments

### 3.1 Reach census  (`census3.py`, `census-C3.csv`; masker reused from the (CHK.136) census)

Element accesses per population. **These are per-population figures; the 8 profiles are the SAME tsc
codebase under different `include`s, so the TOTAL row double-counts and must not be quoted as a
program size.** The scanner refuses to run unless it finds exactly 8 `build/bench/tsc-*-637d5746`
profiles (it found 8).

| population | files | all element accesses | **A: computed index** (UPPER BOUND) | B: bare-identifier index | **C: key-like** (HEURISTIC) |
|---|---:|---:|---:|---:|---:|
| tsc-deprecatedCompat | 81 | 1803 | 1178 | 876 | 26 |
| tsc-harness | 312 | 2865 | 1838 | 1350 | 48 |
| tsc-jsTyping | 84 | 1805 | 1175 | 870 | 26 |
| tsc-project | 78 | 1796 | 1171 | 870 | 26 |
| tsc-server | 274 | 2521 | 1608 | 1188 | 34 |
| tsc-services | 252 | 2391 | 1515 | 1121 | 32 |
| tsc-tsc | 80 | 1796 | 1171 | 870 | 26 |
| tsc-typingsInstallerCore | 88 | 1808 | 1178 | 871 | 26 |
| **marked** (src) | 13 | 206 | 52 | 38 | **10** |
| **cronstrue** | 47 | 142 | 43 | 33 | **0** |

* **A is a SOUND UPPER BOUND**: every element access whose index is not a plain string or numeric
  literal. It includes `arr[i]`, `map[someString]`, `o[n]` — the overwhelming majority, none of which
  this change touches. **1,171–1,838 per profile.**
* **B** narrows A to a bare identifier index (the only shape whose type this scanner could even try
  to characterise). **870–1,350 per profile.**
* **C is a HEURISTIC, in both directions.** It counts a bare-identifier index whose name appears, in
  the same file, in a declaration / annotation / `as` cast mentioning `keyof`, `Exclude<keyof`, a
  literal-union annotation, a local literal-union `type` alias, or a `cond ? "a" : "b"` initializer.
  **17–39 per profile without the ternary pattern; 26–48 with it** — i.e. adding ONE more inference
  shape moved the count by ~40%, which is the honest measure of how soft this bucket is.
  - It **over-counts**: an identifier declared `keyof T` in one function and reused as a plain string
    elsewhere in the file is counted at every access.
  - It **under-counts, structurally**: a key whose literal-union type arrives by inference through a
    call, a `for…of` over a literal-union array, an imported alias, or a destructured parameter is
    invisible to a text scanner. `checker.ts:14176`'s `const key = … ? "inner" : "outer"` is the
    family I found by reading and then added a pattern for; there are certainly others.
* **An EXACT count needs the type checker and is therefore a job for the fix's own counter**, not for
  this brief. Recommended receipt for the round: a `CliMode` counter incremented at the new arm
  (admissions, and separately "admitted and answered non-any"), read on the 8 profiles + both
  libraries — exactly (CHK.124)'s instrument, which is what separates a gate from a control here.

### 3.2 The prize — MEASURED on `marked` with the directives removed

`marked` is clean under tsgo (`--noEmit -p build/bench/marked-history` → exit 0, no rows). A scratch
COPY with the three `// @ts-expect-error cannot type … dynamically` comments stripped gives exactly
three semantic rows:

```
src/Instance.ts(202,11): TS2322: Type '(...args: unknown[]) => unknown' is not assignable to type
  '((src: string) => Link | undefined) & ((src: string) => Blockquote | undefined) & … 19 more … '.
  Type '(...args: unknown[]) => unknown' is not assignable to type '(src: string) => Link | undefined'.
    Type 'unknown' is not assignable to type 'Link | undefined'.
src/Instance.ts(229,13): TS2322 (same shape, the `_Hooks` intersection)
src/Instance.ts(242,13): TS2322 (same shape)
```

Those are the three checks we never run, and the three ours-only **TS2578 `Unused '@ts-expect-error'`**
rows are their shadows. **So the prize is `marked` 18 → 15**, taking the checker lane's second
sized item off the board. It does NOT touch (CHK.33)'s 8 or (CHK.35)'s 5.

**And the matching negative control is in the same file**: `renderer[rendererProp] = …` at
`Instance.ts:177` has NO directive and tsgo is SILENT there (no row at 177 in the stripped run),
because `_Renderer`'s members all return `RendererOutput` and the RHS returns `RendererOutput`.
If the fix reports anything at line 177 it is an FP, and that one line is the sharpest available
grading case (§4 R3).

### 3.3 What the corpus and the grid can say

Both are CONTROLS at best until counted. The C-bucket per profile is 26–48 sites out of ~1,800
element accesses, so an 8×`added=0 removed=0` grid is compatible with the change being completely
inert there; the round must print the admission counter before reading the grid as coverage
((CHK.124)'s law). `cronstrue`'s C count is **0** — it cannot grade this change at all.

---

## 4. RISK LIST — each with the fixture that exposes it

| # | Risk | Mechanism | Exposing fixture |
|---|---|---|---|
| **R1** | **Absent key resolved anyway** | Routing through `getIndexedAccessType`'s `.filter { it !== anyType }` union arm answers the union of the PRESENT keys where tsgo answers `any` — a strictly narrower type feeding every downstream reader. | `fx/r4.ts`: `interface R { a: string; b: number }`, `bad: "a" \| "zzz"`, `const p: never = r[bad]` must print `Type 'any'`. |
| **R2** | **Calls through a union key start reporting TS2345** | The resolved read is a union of call signatures; tsc collapses their parameters to an intersection → `never`. Today we answer `any` and never check. `cd[cdk](1)` becomes `Argument of type '1' is not assignable to parameter of type 'never'`. This is tsgo-CORRECT but it is a NEW row on code that was silent, and any place our signature-union reader differs from tsc's is an FP. | `fx/r12.ts` lines 2–7: the differing pair MUST report TS2345/`never`, the identical pair `Cs` MUST stay silent. |
| **R3** | **A write that should pass starts failing** | The write target is an N-way intersection; our `getIntersectionType` reduces, our relation engine then compares an N-way intersection target. Any weakness there (method bivariance, an optional member, `this` parameters) turns a legal write into TS2322. | `marked/src/Instance.ts:177` (`renderer[rendererProp] = (...args) => …`) — tsgo SILENT, measured §3.2. Plus `fx/r1.ts`-shaped positives to prove the arm is live. |
| **R4** | **Member access on the resolved union starts reporting TS2339** | `p2[p2k].n` where the constituents have different shapes: tsgo says `Property 'n' does not exist on type '{ n: number; } \| { s: string; }'`. Correct — but it fires wherever our union member lookup is weaker than tsc's, and CLAUDE.md records `getPropertyOfType`'s union arm as exactly that. | `fx/r12.ts:8-10`. |
| **R5** | **Argument position** | `takesString(sn[snk])` → TS2345 `'string \| number'` not assignable to `'string'`. Every newly-typed read now flows into `checkArgumentsAgainstSignature`. | `fx/r12.ts:11-13`. |
| **R6** | **Optionality divergence between the two element-access paths** | `elementAccessResultType`'s own string-literal arm is a bare `getTypeOfSymbol(prop)` with NO (CHK.96) `\| undefined`, while `getIndexedAccessType`'s has it. Per-constituent delegation gives the union key the `\| undefined` and leaves `o["b"]` without it — two spellings of one access disagreeing. | `fx/r5.ts` + `fx/r13.ts`. **MEASURED**: tsgo reads `o["b"]` AND `o.b` as `number \| undefined` (r13.ts(4,25) and (5,25)), so if our element-access single-literal arm omits it that is a pre-existing divergence this round will make VISIBLE by contrast. |
| **R7** | **`readonly` in the union becomes a TS2322 instead of a TS2540** | tsgo reports `Cannot assign to 'a' because it is a read-only property.` at the INDEX expression (§1.11). If the write arm builds an intersection and rejects the RHS, we emit the wrong code at the wrong span on the same line. Safer: BAIL (emit nothing) when any constituent is readonly. | `fx/r10.ts`. |
| **R8** | **Generic `T[K]` resolved through the constraint** | If the new arm reaches a `Type.TypeParam` receiver via `getApparentType` (as `getTypeFromIndexedAccess` deliberately does at line 175401), a generic body's `t[k]` starts resolving where tsgo keeps a deferred `T[K]` with a different message. | `fx/r8.ts` lines 2–5: both rows must keep tsgo's `T[K]` shape, or we must be SILENT — never a third answer. |
| **R9** | **`keyof` of a type with an index signature is NOT a literal union** | `keyof Ix` is `string \| number`; an arm that assumes "the key came from `keyof`, therefore literals" is wrong. Gate on the TYPE (`Type.Union` of `StringLiteral`/`NumberLiteral`), never on the syntax. | `fx/r6.ts`: `ix[ixk]` must read `string \| number` via the index signature, `ix[ixn]` via the declared members. |
| **R10** | **A giant union blows up display / cost** | `keyof string[]` is a ~35-member union of Array methods; `Exclude<keyof _Tokenizer<…>, …>` is 24. Every such access now interns a large union and a large intersection. `cost_gate.py` counts type RESOLUTIONS, so it will see this — but a display divergence will not show on the grid ((PARITY.1): all 8 profiles' rows are `Cannot find name …` and name no type). | `fx/r12.ts` enum row + a `keyof string[]` read; and a scratch project asserting we do not hang. |
| **R11** | **Receiver-union write folded with the wrong operator** | §1.9: the receiver union folds with UNION even in write mode. Folding both axes with intersection gives `never` where tsgo gives `boolean`, i.e. a confident FP on legal code. | `fx/r9.ts` lines 2–6 — the discriminating fixture; assert the write target renders `boolean`. |
| **R12** | **The RHS's contextual type changes** | A resolved slot may newly supply a contextual type to a function-expression RHS, which is (CHK.35)/family-5 territory. If the intersection contextual-types `(...args) => …`'s parameters, TS7019/TS2683 rows can move in either direction. | `marked/src/Instance.ts:118` (the recorded (CHK.35) site) re-measured before and after. |

---

## 5. Recommended shape of the round (one paragraph)

Land the READ arm as §2.2(A) — a narrow, all-or-nothing, literal-union-only arm in
`elementAccessResultType` delegating per constituent to the existing `getIndexedAccessType`, leaving
the type-position arm untouched — and the WRITE arm as a sibling `elementAccessWriteType` folding the
key axis with `getIntersectionType` and the receiver axis with `getUnionType`, consumed by
`cheaGeneralElementWrite`. Gate with: the admission counter on 8 profiles + both libraries (a green
grid means nothing without it), `marked` 18 → 15 with `Instance.ts:177` still silent, `cronstrue`
1 → 1, the corpus screen over BOTH channels, `cost_gate.py`, and one ablation arm per risk R1 / R9 /
R11 (those three are the ones whose mistake is a confident wrong answer rather than a silence).

---

## 6. Artifacts

* `fx/` — 13 reference fixtures + `tsconfig.json` (`strict`, `target es2020`).
* `tsgo-raw.txt` — the full tsgo 7.0.2 output for r1–r12 (r13 measured separately, quoted in R6).
* `marked/` — a scratch COPY of `build/bench/marked-history/{src,tsconfig.json}` with the three
  `@ts-expect-error` comments stripped (`src/Instance.ts.orig` is the untouched original). The repo
  copy was never modified.
* `census2.py` / `census3.py` / `census-C.csv` / `census-C3.csv` — the reach census; `census3.py` is
  the one whose numbers are in §3.1. Both import the audited masker from the (CHK.136) census's
  `scan.py`.

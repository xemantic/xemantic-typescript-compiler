**(INC.46) QUEUED AND PRICED — AND MEASURING IT REFUTED THE QUEUE'S OWN EXPLANATION OF WHY
PROJECT-WIDE DIAGNOSTICS CANNOT BE INCREMENTAL (2026-08-29, owner's idea).** The standing
story, from round 772 and (INC.35), is that a dependency closure buys nothing on tsc because
its sources are `export *` barrels. **The barrels were never the cause.** A SYMBOL-level use
graph — which is free, since `capturedDefinitions` already records span -> declaration —
re-checks **100% of the program's characters at the median edit, the same as the file-level
graph** (94.9% of imported names placed, so not an under-count): those files genuinely use
symbols from most other files and the relation is transitive. **What collapses it is asking
whether an edit moved any EXPORTED SIGNATURE**, not which symbols a file uses: a body-only
edit moves none, so no dependent re-checks and the cost is one narrowed build — **108-113 ms
against 4,864-5,096 ms, a factor of 45**, already measured by (INC.31)/(INC.37). **91.6% of
the program's characters are inside brace bodies** (a proxy for edit POSITION, optimistic
because an inferred return type leaks, pessimistic because it counts `interface` bodies).
**This needs no corpus and no owner call** — a signature hash pays on DENSE code too, so
unlike (INC.35) it is gradable on the dashboard profile. **THE SHARP HAZARD IS RECORDED**:
`typeToString` is the wrong hash source in BOTH directions — `aliasDisplayMap` is a
first-wins global so it is not a pure function of the type (spurious invalidation), and B58.1
renders `errorType` as `"any"` so a degraded resolution hashes as a genuine `any` (a MISSED
invalidation, silently). The hash must be an id-free structural fingerprint; (INC.16) already
built one to copy. Cost input censused: **3,398 exported declarations, mean 44/file, max 874
in `types.ts`**; its runtime is the first thing to measure, with a stated refusal threshold.
**No code landed — the entry is the deliverable.**

**(INC.45) — `renameAt` IS NARROWED TOO, AND ITS ABLATION FOUND A BLIND PIN SET
(2026-08-29).** The rename sweep took (INC.44)'s spelling closure and hands the resulting
file set to the compiler as a check partition. Two things make it more than a copy.
**Both of a rename's builds must share ONE partition** — `verifyRename` compares
diagnostics as a `(file, code)` MULTISET, which a partition filters, so a narrowed
"before" against a whole-program "after" reports every unswept row as removed; the
soundness argument for narrowing it at all is that a rename edits only files the plan
names and an unedited file's meaning can change only through a name it imports, which it
must then SPELL. **And the population is the closure UNION every occurrence of the NEW
name**, because `verifyRename`'s third check — the only one that can see a rename which
compiles and means something else — scans for occurrences already spelling it and would
otherwise pass VACUOUSLY. **THE ABLATION'S FINDING**: arm b2 (the after-build forgets the
partition) reddened **NOTHING**, because every fixture was a CLEAN program and both bags
were empty whatever either build walked — one file carrying a diagnostic and spelling
neither name takes it to **2 RED**. Arm b3 (never narrow) is **UNDISCRIMINATED and
recorded as such**: the change is equivalence-preserving by construction, so what stands
in its place is one pin on the shipped DEFAULT with no mode install in it ((INC.16)'s
lesson). **MEASURED**: an ordinary rename is **~1.0-1.3 s against ~15 s (12-14.5x)** —
`emitFiles` 2 of 78 files at 1,304 ms, `transformNodes` 3 of 78 at 1,025,
`checkSourceElement` 1 of 78 (but that file is `checker.ts`) at 4,725.
**GATES.** Suite **16,440 / 0 / 3** (+18 over the session's 16,422 baseline, exactly the new pins); rename differential **EQUIVALENT** — 8 carets,
7 narrowed, 6 producing an APPLICABLE plan, 1,691 edits compared plan for plan, 0
diverged, 56.5 s against 114.2 s; three ablation arms b1 **1 RED** / b2 **2 RED** / b3
undiscriminated with a reason.

**(INC.44) — `referencesAt` IS NARROWED BY *SPELLING*, AND THE DOC CLAIM THAT IT "CANNOT
BE" CONFUSED THE CLAIM WITH THE EVIDENCE (2026-08-29).** `docs/language-service.md` said in
three places that find-references and rename "are NOT narrowed and will not be: their claim
is about every file, so there is nothing to narrow to". The claim is program-wide; the
EVIDENCE is not — an occurrence can only be an answer if it SPELLS a name the symbol is
reachable by. `referencesAt` now selects that population before typing it and `captureIn`'s
partition, which has always been DERIVED from the request's spans, narrows the check with
it: **no new mechanism**. On tsc's own 78 sources an ordinary name costs **510–553 ms
against 8.8–11.1 s (17–18x)**, `checker.ts`-only names 1,940 ms (4.8x), and the worst
realistic case (`SyntaxKind`, 9,827 hits in 49 files) still wins at 4,904 ms; a repeat is
free (119–150 ms) because the narrow path reaches a memo the whole-program one never did.
The closure over `import { p as q }` / `export { p as q }` terminates because both spellings
are tokens of the file DECLARING the alias; everything else — a default export, a default
import's local, `export =`, `import x = require(…)`, a namespace binding, the spelling
`default` — REFUSES and runs the old sweep. **The near-miss worth remembering**: the obvious
substring file filter is not exact, because `StringLiteralNode.text` is the COOKED value and
`\a` is an identity escape, so `o["pl\ain"]` names `plain` — a file may be skipped only if
it holds no backslash at all (29 of 78 do, carrying 78.2% of the characters). **The
ablation's honest half**: arm a3 reddens only the REFUSAL pins, so the escape guards are
CONSERVATISM — kept because tsc answers **6** references where we answer **2** on a
`export { renamed as default }` edge, which is now pinned so the day it closes is loud.
**GATES.** Suite **16,434 / 0 / 3** (+12 from a re-verified 16,422 baseline, exactly the new pins); reference differential **EQUIVALENT** — 60 carets drawn by stride over all 381,775 occurrences, **59 of them actually narrowed** (the control), **0 diverged**, 12,248 hits compared element for element; mean partition **17.5 of 78 files**, aggregate 182.0 s narrowed against 561.6 s whole-program (**3.09x** on a draw that lands proportional to occurrence count, i.e. on the hottest names);
four ablation arms, four DISTINCT red sets; `cost_gate.py` / `huge_methods.py` are CONTROLS here (no `-core` source
touched) and both are green: `cost_gate.py` exit 0 with `output.errors` **46** and a largest move of **+0.08%**
(`globals.lookups`/`globals.misses` — the profile is unchanged, this is its standing
run-to-run residual), `huge_methods.py --fail-over 0` clean.


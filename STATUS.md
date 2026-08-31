# Status

**(INC.76) — THE LANGUAGE SERVICE WAS PAYING (INC.60)'s DEFECT IN FULL, THROUGH A WRAPPER THAT
DID NOT OVERRIDE (2026-08-31).** `Vfs.listEntries`'s default body is
`list(path).map { VfsEntry(it, isDirectory(it)) }`, and (INC.60) added that member precisely
because asking the kind per entry is kotlinx-io's `metadataOrNull` — **up to FIVE `stat`s**.
`OverlayVfs` never overrode it, so **every `Project` build handed the whole saving back**,
silently, since the answers are identical either way.
**MEASURED STANDALONE over the build's own 50 directories / 2,451 entries: 6.34 ms taking the
kinds from the delegate's listing against 19.54 ms asking per entry — and 19.5 is what the
build's `vfs.listEntries + sort` row read.** That match turned a 3x probe-vs-row gap into a
diagnosis. **LANDED, and it costs NO promise, so both arms gain**: that row **20.70 -> 9.73
ms**, the whole root-file glob **28.14 -> 18.44**, the per-keystroke query **153/145 ->
123/125 ms** trusted and **156/162 -> 140/138** untrusted.
Pins are a DIFFERENTIAL against the default body — a wrong kind drops a file from the program
or adopts a directory as a root, and (CFG.1) says nothing here notices — including the one
asymmetry an obvious implementation gets wrong (an on-disk FILE the overlay has given children
is a DIRECTORY). The cost pin had to be restated as a COMPLEXITY claim at two program sizes:
`isDirectoryCalls == 0` is false and correctly so, because a build asks about specific PATHS.
`CountingVfs` had the same omission and is fixed with it; an audit found no third case.
**TRANSFERABLE: a defaulted interface member added for speed is a silent regression waiting
for the next wrapper**, and the instrument is a row measured STANDALONE against the same row
measured IN THE BUILD.

**(INC.56) — AN IntelliJ-CLASS HOST CAN SKIP THE RE-READ, AND THE ROW IT WAS AIMED AT WAS A
*LOCATION* (2026-08-31).** Two opt-in halves in the embedding API: `Project.trustFilesystem`
(the host promises the bytes of a file will not change without this project being told —
through `updateFile`, `deleteFile` or the new `reloadFile`) and `Vfs.readTextIfResident` /
`Vfs.retainRead` (the crawl skips its per-file THREAD HANDOFF for content already in memory).
Retention is written ONLY from the crawl's single-threaded fold — round 825, because the crawl
reads from N concurrent workers.
**MEASURED**, 8 instrumented draws per arm, one JVM per arm, arms rotated across processes,
both rotations agreeing, with the untouched sequential specifier-resolution row as the control:
crawl WALL **30.6/37.0 -> 21.7/19.4 ms** at 2,401 small files and **13.7/14.2 -> 9.5/7.8 ms**
on tsc's 78 huge ones; `read+decode` **132.6/176.1 -> 1.52/1.39** and **65.4/63.2 ->
0.076/0.057**.
**AND THE REFUTATION IS WORTH MORE THAN THE ROW: THE QUEUE PRICED THIS FROM `FrontEnd.READ`,
WHICH IS ELAPSED-WITH-SUSPENSION — A LOCATION, NOT A PRICE.** Retaining the content WITHOUT
skipping the hop served **33,350 reads from memory and moved the crawl's wall by NOTHING** on
the 2,401-file project, while halving it on tsc's 78 huge sources. **The read is a BYTE cost;
the row that made it look like a FILE cost was the hop's suspension** — so the fix that works
on both shapes removes the HANDOFF, not the read.
**THE PROMISE IS NARROWER THAN THE ENTRY FEARED, AND IT IS PINNED:** additions and deletions
are still discovered on every build (nothing caches the file SET), and `.json` is never
trusted. 18 pins including the documented LIMIT (an unreported content change IS missed) and a
REGIME pin that the crawl really takes the resident path; 4 of 5 ablation arms discriminate and
the fifth is recorded as a REDUNDANT GUARD rather than claimed.
**GATES.** Suite **16,586 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% — a CONTROL,
since `SystemVfs` resides nothing and the CLI path is provably unchanged; `huge_methods.py
--fail-over 0` clean.
**SUCCESSOR:** the crawl's remaining halves — sequential specifier resolution ~11-13 ms
(non-syscall remainder; its syscall half is refused by (INC.73)(a)) and a ~7-9 ms concurrent
residue that is the `flatMapMerge` machinery itself, i.e. (INC.64)'s question with the last hop
gone.

**(INC.73) — A 2.5 ms ROW, AND THE TWO REFUTATIONS THAT COST NOTHING TO FIND (2026-08-31).**
`init:moduleTypeNameIndex` — the largest single row left in the floor's per-pass table after
(INC.69)/(INC.70)/(INC.71) — is built on FIRST ASK; GO/NO-GO first, per (INC.16):
`moduleTypeNameIndexBuilds` **0 on a floor build, 1 on a full one**.
**ITS VALUE RECEIPT IS THE 8 PROFILES AND THE CORPUS IS A CONTROL — the ablation that never
builds it reddens ZERO of the ~13k baselines and 3 of the 8 profiles (+2 rows each: harness,
server, services)**, which is exactly where rounds 471 and 513 got their evidence. **A family
can have no corpus coverage at all and still be load-bearing; the way to find out is to ablate
and grid, not to reason about it.**
**AND THE HONEST PART: neither the floor wall (medians 117/124 before against 119/127 after —
no separation) nor a 2-process phase A/B can resolve 2.5 ms.** The receipts are the pass row
from the clean single-binary decomposition plus the deterministic count, and the round is
written up as the 2.5 ms landing it is.
**TWO REFUTATIONS FROM THE SAME RECON, BOTH WORTH MORE THAN THE ROW.**
**(a) `SystemVfs.exists` IS ONE SYSCALL** — 1130.7 ns/call against `java.io.File.exists`'s
1108.8, **1.02x**, ABBA inside one process over the fixture's own 2,401 paths. So (INC.60)'s
five-stat finding is specific to `metadataOrNull` and does NOT generalise; and the resolver
already probes exactly ONCE per resolution (**2,351 `exists` + 10 `isDirectory` for 2,351
distinct pairs**, because `.ts` is first in `allExtensions`), so there is no syscall lever in
the crawl's 11 ms resolution row.
**(b) `init:collectUmdGlobalsAndModuleFiles` (2.32) and `init:mergeFileLocalsIntoGlobals`
(2.06) ARE NOT DEFERRABLE**, and the reason is not their readers but their readers' SCHEDULE:
`umdGlobalNames` is read by the merge itself and `moduleFiles` by `collectModuleAugmentations`,
both LATER INIT PASSES that run unconditionally. Combined prize ~5 ms of a 94 ms floor —
refused on arithmetic.
**GATES.** Suite **16,568 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% (including
`typeNode.bypassed` 145,723, the direct receipt that `multiFileModuleTypeNames` answers
identically); `huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`.
**SUCCESSOR:** the init dispatch has no non-walker row above ~1.4 ms left, so what remains
there is (INC.7)'s partition question one walker at a time; **the floor's largest row is the
CRAWL and its READ half is (INC.56)** — now the only row left with a double-digit prize, and
the one an IntelliJ-class host can simply hand us.

**(INC.72) — THE SURPLUS WAS THE CRAWL, AND BOTH OF THIS SESSION'S WALL FIGURES ARE RETRACTED
(2026-08-31).** (INC.70) and (INC.71) each reported an ABBA-rotated floor wall about **three
times** what their pass row explained, and that gap was queued as a mechanism to hunt. It was
not a mechanism. Running the SAME two binaries with the per-PHASE instrument — two processes
per arm, rotated, second instrumented draw — attributes the change and nothing else:
**init-block pass dispatch 39.87 -> 25.06 ms (-14.81)**, which is what the two pass rows said,
while the UNTOUCHED **import-graph crawl swung +18.01** in the same run, its
elapsed-with-suspension `read+decode` sum moving **147.8 -> 249.9 ms**. Every other phase is
flat to within 0.7 ms.
**So (INC.70)'s "160.0 -> 136.5 (-23.5)" and (INC.71)'s "142.5 -> 120.0 (-22.5)" are each one
batch's reading of a quantity carrying a ±20 ms concurrent term; the same binaries read
128.5 -> 116.5 in this round's batch. What ships is -14.81 ms of init-block dispatch,
phase-attributed, and that is the number to carry.**
**THE LESSON IS NOT "ROTATE MORE" — IT IS "PICK AN INSTRUMENT WHOSE VARIANCE DOES NOT CONTAIN
THE ANSWER".** (INC.68) showed a BLOCKED batch inventing a delta that rotation removed; this is
the next step out — a ROTATED batch of a COMPOSITE quantity still cannot separate two of its
terms, and 4 processes x 8 draws per arm did not help, because the noise is a real, large,
unrelated phase rather than run-to-run jitter. For a checker-side floor change the receipt is
now `FrontEnd`'s phase row plus the deterministic population count; the floor wall is a sanity
check. `FloorAbMain` grows an `fe` mode so that decomposition is a two-BINARY A/B.
**SESSION TOTAL, re-taken on the SAME INSTRUMENT rather than inferred from the A/B arms —
`scripts/floor-decomposition.sh`, same fixture, same warm-ups, same `PLAIN late` slot: the
2,401-file `dom` floor is 122 -> 94 ms (`PLAIN early` 144 -> 105).** Two runs of one recipe
have no arm-rotation problem to get wrong, which is (INC.72)'s lesson applied to the
REPORTING. **The ranking has changed and the next round must start from it: the CRAWL is now
the largest floor row (29 ms, 36%) for the first time in this arc — its READ half is (INC.56),
the one row costing a soundness promise and the one an IntelliJ-class host can hand us — with
the init-block dispatch at 22 (28%), config+glob 12, bind 8, post 5.** The pass table is
**22.43 ms over 418 rows**, headed by three whole-program INDEX builds
(`init:moduleTypeNameIndex` 2.52, `init:collectUmdGlobalsAndModuleFiles` 2.32,
`init:mergeFileLocalsIntoGlobals` 2.06) — none of them a per-file table, so the
(INC.70)/(INC.71) deferral shape does not transfer unchanged, and the GO/NO-GO for each is
(INC.16)'s counter: who forces the index, and is it anyone on a floor build?

**(INC.71) — THE PER-FILE VISIBILITY SETS, AND A FLOOR WALL THAT KEEPS OUTRUNNING THE PASS
TABLE (2026-08-31).** `init:computePerFileVisibility` walks every program file's `locals` to
publish `moduleOnlyGlobalNames` and `libValueShadowNames`, whose only three readers —
`globalsForFile`, `globalsForFileNode`, `libValueBehindTypeOnlyShadow` — are all NAME
RESOLUTION. So a build that checks nothing reads neither.
**THE POPULATION DECIDED IT BEFORE ANY IMPLEMENTATION, for the price of one temporary
counter: 0 asks on a floor build of the 2,401-file fixture against 335,881 on a full one.**
(INC.16)'s law used as a GO/NO-GO rather than as a post-hoc explanation.
**THE ORDERING CLAIM WAS CHECKED**: the pass compares `globals.keys` against
`init:snapshotPreAugGlobalKeys`' snapshot, and all three writers of `globals` run at earlier
init steps. **The one place it is deliberately NOT lazy is the probe** — the INV.3(a)
classifier is still installed at the pass's moment and FORCES the sets from inside its lambda,
so `globals.lookups` reads 783,383, **+0.00%**.
**MEASURED:** row **-> 0.002-0.003 ms** from 5.5-7.2; ABBA-rotated floor
**142.5 -> 120.0 ms (-15.8%)**.
**THE VALUE RECEIPT IS THE CORPUS, AND THAT IS NOW A RULE RATHER THAN AN ACCIDENT:** ablation
c2 (sets stay empty) reddens **492** core tests, while the hand-written `-project` value pin
stays GREEN — the second round running where a `-project` pin cannot discriminate the
mechanism and the corpus discriminates it in the hundreds. For the INV.3 visibility model the
`-project` pins gate the REGIME (which builds do the work) and the corpus gates the ANSWER.
**GATES.** Suite **16,565 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean; 8-profile grid `added=0 removed=0`.
**SUCCESSOR IS A MEASUREMENT QUESTION, NOT A ROW ((INC.72)):** twice in a row the rotated
floor WALL moved about **three times** what the pass table explains (-23.5 against ~4 ms,
-22.5 against ~7). Both changes also removed thousands of RETAINED allocations per build,
which round 801 says is a plausible mechanism and not a measured one. Decompose BOTH arms with
`--frontEnd` before opening another init row: either the surplus is outside the init block, or
the `rows`-tier probe under-reports and every ranking taken from it needs re-reading.


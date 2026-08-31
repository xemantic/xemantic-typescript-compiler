# Status

**(INC.79) — THE CRAWL ASKED THE FILESYSTEM ABOUT FILES THE GLOB HAD ALREADY LISTED
(2026-08-31).** (INC.73)(a) refused this row's syscall half by arithmetic — "2,351 distinct
resolutions at exactly one `exists` each, so ~2.6 ms is irreducible". **That is true of the
resolver in isolation and false of the BUILD**: the root-file glob has already listed every
directory of the project and proved which files are there, off the same `Vfs`, ~20 ms earlier
in the same build. A per-component refusal can be right about its component and wrong about
the program, and what says so is asking who else already knows the answer.
**DECOMPOSED FIRST** (one binary, ABBA-rotated, population checked against the build's own
4,701 specifiers / 2,351 distinct): `resolve` **9.4-9.8 ms**, of which `existsOnly` **4.4-4.6**
(2,350 probes at ~1.9 us), `joinOnly` **3.7-3.9**, `dirnameOnly` 0.8, `keyOnly` 1.2,
`bookkeeping` 0.5-0.8 — so the syscalls are the largest piece and the path arithmetic the
next, neither of which the row itself could say.
`ModuleResolver` now memoizes `exists`/`isDirectory` for the build and is SEEDED from the
glob. **It adds no assumption**: (INC.65) already memoizes the whole ANSWER per
`(importerDir, specifier)`, strictly stronger, over the same one-build lifetime. **The seed
may only say YES** — a file can exist and be excluded from the program.
**MEASURED:** the row **10.2-12.0 -> 5.8-6.5 ms**, and the receipt is the count the build
prints — **2,351 questions, 0 reached the filesystem**.
**THE ABLATION FOUND THE PIN SET INCOMPLETE, WHICH IS WHAT IT IS FOR:** keying the memo by
BASENAME reddened only the COUNT pins, because every value pin happened to ask about names
existing on both sides — a wrong PROGRAM, silent per (CFG.1). The missing pin was added and
b2 then reddens it. Three arms, three distinct red sets (4 / 3 / 3).
**GATES.** Suite **16,618 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00%;
`huge_methods.py --fail-over 0` clean.
**SUCCESSOR, measured and named:** `PathUtil.join`/`normalize` at ~810 ns x 4,701
(**3.7-3.9 ms**, a `normalize` that must process `..` segments, which (INC.68)'s fast path
cannot help) and `dirname` + the memo key at **~1.5 ms**, which the crawl loop could hoist
per FILE.

**(INC.78) — THE ROOT-FILE GLOB ASKED AN *ACCEPTING* REGEX PER CANDIDATE, AND NO REFUSAL
FILTER COULD HAVE HELPED IT (2026-08-31).** `collectRootFiles` ran
`excludeRegexes.none { } && includeRegexes.any { }` for every candidate of every build — i.e.
on every keystroke of a language-service host — at **4.66-8.08 ms, 1.9-3.4 us per candidate**
on a ~90-110 ms incremental floor at 2,401 files.
**THE ATTRIBUTION INVERTS THE OBVIOUS FIX.** (INC.77) proposed a cheap prefix/extension
pre-filter; measured standalone on one binary, the EXCLUDE half is **191 ns/candidate** (its
literal prefix fails on the first character) and the INCLUDE half is **2,239** — `src/**/*`
compiles to `^…/src/(?:[^/]+/)*[^/]*(?:\.ts|…)$`, which backtracks over every directory
segment and **runs to a MATCH for every file in the project**. A filter can only refuse, so
the lever is an EXACT shortcut and the proposal was aimed at the half that was already cheap.
`GlobMatcher` keeps the regex as its DEFINITION and answers the
`<literal>` + `**` segment + bare `*` leaf + literal tail shape — `src`, `src/**/*`,
`src/**/*.ts`, `dist`, `**/*.spec.ts`, i.e. what tsconfigs contain — from the head and the
tail. Two corrections came from EXTENDING the differential grid rather than reading it: an
EMPTY SEGMENT is the one remainder `(?:[^/]+/)*[^/]*` cannot match (a doubled separator now
falls back to the oracle), and that test is exact only because the head ends at a directory
boundary. The length guard is provably unreachable and is recorded as a REDUNDANT GUARD.
**THE WALL COULD NOT CARRY THE CLAIM: the same one-process ratio read 12x, then 5x, then 3x
over four processes of one binary** (round 867's arm instability). The receipt is
`FrontEnd.globRegexEvals` — decisions that reach the regex — **4,802 -> 0**, pinned at TWO
program sizes with a positive control that a constrained pattern still runs it once per
candidate. In-build `CFG_MATCH` **4.66 -> 0.61 ms**, root-file glob row **14.46 -> 9.16**.
Gate is a DIFFERENTIAL, not a green suite ((CFG.1): a wrong root-file set is silent here);
4 ablation arms, 4 distinct red sets, and the no-fast-path arm reddens ONLY the cost and
regime pins while every value pin stays green.
**GATES.** Suite **16,610 / 0 / 3**; `cost_gate.py` exit 0, every counter +0.00% including
`output.programFiles` 78 -> 78; `huge_methods.py --fail-over 0` clean.

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

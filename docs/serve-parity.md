# (SERVE.2) The CLI-vs-daemon answer-parity matrix

Round 873, 2026-08-09. Driver: `scripts/serve_parity.py`. Positive control:
`scripts/round873-ablate.sh`.

A separate document from `docs/perf/warm-serve-request-attribution.md` on
purpose: that one is round 871's ATTRIBUTION of where a warm request spends its
time. This one is a CONTRACT and a re-runnable gate — what `xtsc` answers, and
whether the answer depends on which path served it.

---

## 0. Why the sweep happened

Two divergences between the one-shot CLI and the daemon had surfaced, **both by
accident and neither by a test**:

* **round 848** — fifteen process-global mode flags that a one-shot CLI leaks
  harmlessly (the process exits) and a daemon leaks into *every later request*;
* **round 872** — a `--daemon` compile **served by a daemon** exited **0** on a
  failing project where the CLI and the in-process fallback both exit **1**. A CI
  false-green, one layer above a server whose response had the right code in it
  all along.

The daemon is the shipping artifact of the client-server architecture and the
vehicle for every warm gain this arc has landed. Nothing had ever swept the
boundary deliberately.

## 1. The matrix

**31 cells, 45 invocation pairs.** Every cell runs the SAME command line twice —
once as the one-shot CLI, once through a warm daemon — over two identical copies
of the same fixture tree, and diffs **every** observable:

| observable | how |
| --- | --- |
| process exit code | compared directly |
| stdout | byte-compared after normalizing the run's own tree root and the `time:` line |
| stderr | byte-compared, same normalization |
| the emitted tree | sha256 of every file under the arm's root, so an emit difference cannot hide |

Both arms are the same binary and the same entry point (`XtscMainKt`), differing
only by `--daemon --socket`, so the boundary is the only variable.

### Axes and cells

**Outcome** (absolute path, `--noEmit`): `clean`, `typeerr`, `syntax`,
`unresolved`, `empty`.

**Invocation form** — where the cwd defects lived: `--listAll`; `-p tsconfig.json`;
a relative project path; **no path argument at all**; an explicit `.` from inside
the project; an absolute path that does not exist; a relative path that names
nothing; a bare `.ts` file (tsc's single-file mode); `--workers 4` with no path
(the `4` must not be mistaken for a project); `--help`.

**Emit** — the tree is diffed: `outDir` from the config; `--outDir` relative and
not yet existing; `--outDir` absolute and not yet existing; `-p` on a relative
tsconfig while emitting; `--noEmit` writes nothing.

**Sequence** — the axis a one-shot CLI structurally cannot have: the same request
three times; A → B → A across two projects; a request carrying a process-global
MODE flag (`--passTiming`, then `--workers 4`) followed by a plain one; a failing
request then a passing one; a syntax-error project then a normal one; a
nonexistent project then a normal one; a REFUSED request (`--watch`) then a normal
one; **compile → EDIT a file → compile → revert → compile**; compile → ADD a file
→ compile; emit then `--noEmit`.

### The daemon runs in a decoy project

The daemon's working directory is a *third* project that compiles cleanly and
contains a uniquely-named symbol. A realistic daemon was started from some other
directory, and a request that resolves a relative path against the SERVER's cwd
has to be visible rather than accidentally right because both processes happen to
sit in the same place. This is what turned the cwd defects from invisible into
obvious.

## 2. The driver cannot be blind

CLAUDE.md records more than one instrument that was green and dead (round 853's
`+0.00%` streak from a frozen classpath; rounds 855/856's ablation driver that
dispatched no arm and printed `complete`). Four guards:

1. **`--selftest`** feeds the comparator a synthetic divergence on each of the
   four observables and asserts it reddens, plus an identical pair it must pass.
   It runs automatically before the matrix and REFUSES to sweep if it fails.
2. **Served-by-the-daemon control.** Every daemon-arm step reads the server's own
   `request N served` count before and after and requires exactly +1, AND
   requires that the dispatcher's `no compile server on …` fallback message did
   NOT appear. Without this the single most likely instrument failure — the
   daemon died, so `--daemon` compiled in-process and the two arms agreed
   trivially — would render as a clean sweep.
3. **A cell that raises, times out, or runs the wrong number of steps is ERROR,
   never PASS.** A daemon-arm timeout also RESTARTS the daemon, so one wedged
   request cannot turn the rest of the matrix into meaningless ERRORs.
4. **No "this cell may differ" marker.** One existed while three cells were
   divergent; the ablation caught it absorbing an unrelated exit-code divergence
   into its own documented excuse and printing as KNOWN. It is gone: a cell
   passes or it is a finding.

## 3. Positive control — the matrix DOES see a divergence

Three arms, **one deliberate mistake at a time** (round 807), each built,
swept, and reverted before the next. A1 and A2 are defects this repo has actually
shipped.

| arm | the mistake | red cells | which |
| --- | --- | --- | --- |
| **A1** | round 872's bug: a daemon-served compile reports exit 0 | **20 of 31** | every cell whose compile finds errors |
| **A2** | round 873's fix removed: the server ignores `request.workingDirectory` | **7 of 31** | `form-relative-path`, `form-no-path`, `form-dot-path`, `form-missing-relative`, `form-numeric-option-value`, `emit-outdir-relative`, `emit-project-flag-relative` |
| **A3** | a stale-answer daemon: an identical request replays the first answer | **2 of 31** | `seq-edit-between-requests`, `seq-edit-adds-file` |

A2's set is exactly the cwd-dependent forms and nothing else. A3's two cells are
the sequence axis's own uniquely-its-own failure — nothing else in the matrix can
tell a daemon that re-runs a request from one that only appears to.

## 4. What the first sweep found

### 4.1 A request did not say WHERE it was typed — four cells, one root cause

`CompileRequest` carried the argument vector and nothing else. The daemon runs in
a different directory and **a JVM cannot change its own cwd**, so both clients had
grown the same heuristic — *"rewrite an argument that names something existing
here"* — which cannot work, because a client does not parse the compiler's
options and so cannot tell a project path from the `4` in `--workers 4`. Two
holes, and the matrix walked into both:

* **no path argument at all.** `CliArgs.project` defaults to `"."`. So
  `xtsc --daemon --noEmit` in a project full of errors compiled the DAEMON's own
  directory and exited **0** — the wrong tree, silently, and the same CI
  false-green family as round 872 one layer down;
* **`--outDir out`, not yet existing.** Not "something that exists here", so it
  was forwarded verbatim and the daemon wrote the user's compiled JavaScript into
  its own directory. Observed as a real `a.js` under the daemon's cwd while the
  client's tree got nothing.

Plus two cosmetic ones (`project: typeerr` vs `project: /abs/…/typeerr` on a
relative or `.` path) which are the same cause seen in the echo line.

**The fix**: `CompileRequest.workingDirectory` (**protocol version 2**), installed
for the duration of one request as `SystemVfs.workingDirectory` — the single
funnel `ProjectCompiler.build` absolutizes both the project path and a `--outDir`
override through. The question moves to the one place that HAS the option table:
the compiler. Both clients' rewriting is deleted, and the native client is finally
the strict pass-through its own KDoc always claimed. The version bump is
load-bearing: a version-1 daemon would answer a version-2 request by resolving it
against its own directory, and `protocolProblem` is what stops that.

### 4.2 A project path that does not exist crawled the whole filesystem

Found because the matrix's `form-missing-project` cell **wedged the daemon** and
every later cell reported "not served by the daemon".

`xtsc /nonexistent-project` resolved its config path to that missing file, took
`dirname` — **`/`** — and, because an unreadable config fell back to the default
recursive everything-glob, went looking for every source file under the root:
**over 30 minutes of CPU** before it was killed, having emitted the TS5083
"cannot read file" diagnostic first and then gone walking anyway.

This one is not daemon-specific in its cause and is far worse in its
consequences: the server runs requests sequentially on one thread by design, so a
single such request holds it **for good** — every later client blocks, with no
timeout anywhere to notice.

**The fix**: a config file that does not EXIST includes nothing (tsc reports the
path and compiles nothing). A config that exists but does not PARSE keeps the
default — there the directory is real and the user did name it.

### 4.3 What came back green, and it is the round's other result

**The entire sequence axis passed on the first sweep.** Round 848's mode ledger
holds under `--passTiming` and `--workers 4` followed by a plain request; round
871's cross-request `CrawlParseCache` is correct across an edit, a revert and an
added file (arm A3 proves those cells would have caught a stale answer); a
refused request, a syntax-error project and a nonexistent project all leave the
daemon answering normally afterwards. Nothing about repeat, ordering, or
state-between-requests diverged.

## 5. What this matrix does NOT cover

Stated so the next agent does not read a green sweep as more than it is.

* **The native client.** Both arms here are the JVM `XtscMainKt`, which isolates
  the daemon boundary. The Kotlin/Native client shares the request-building code
  and `ClientArgumentsTest` pins its pass-through, but it is not swept end to end.
* **The launcher.** `scripts/xtsc`'s arm selection, the AOT decision and the
  `XTSC_CLIENT_UNAVAILABLE` fallback are round 872's pins, not cells here.
* **Real projects.** Fixtures are 1–2 files. The compiler profile's own
  CLI-vs-daemon output identity is round 871 § G's 8-profile grid.
* **Concurrency.** One client at a time. The start race and the single-thread
  invariant are `CompileThreadInvariantTest`'s.
* **`--watch` on the CLI arm.** It never terminates, so that step asserts the
  daemon's constant-time refusal only.
* **A wedged daemon is still wedged.** `CompileServer` has no per-request timeout
  and `CompileServer.request` has no client-side one, so a genuinely pathological
  compile still holds the server and blocks every client. § 4.2 removed the one
  known way to trigger it cheaply; the general property is unchanged and
  deliberate — a legitimately slow compile must not be killed.

## 6. Running it

```bash
./gradlew assemble                     # the staged lib dir is the classpath
python3 scripts/serve_parity.py --out build/serve-parity
python3 scripts/serve_parity.py --selftest          # comparator only
bash scripts/round873-ablate.sh build/serve-parity-ablate   # the positive control
```

Exit 0 iff every cell passes. The ablation refuses to run on a dirty tree: it
reverts arms with `git checkout --`, which also destroys uncommitted work in
those files (rounds 789 and 851).

# Subagent workflow

Moved here from `CLAUDE.md` on 2026-04-17 to keep the auto-loaded context
light. Read this file only when you are about to dispatch subagents — day-to-day
surgical fixes rarely need them.

---

Long multi-session conversations accumulate dead-end investigations and
compacted summaries that dilute signal. Prefer **focused subagent tasks**
over extending the main context indefinitely.

## Subagent brief template

A well-formed subagent brief for a fix in this codebase includes:

1. **The failing test(s)**: exact test name(s) to run, e.g. `./gradlew jvmTest --tests '*.commentOnBinaryOperator1*'`
2. **Expected vs actual diff**: paste the `--- expected / +++ actual` output so the agent sees the target immediately
3. **The source file**: path in `typescript-repo/tests/cases/compiler/` so the agent can read the TypeScript input
4. **The likely fix area**: name the file and function (e.g. "look at `emitBinaryExpression` in `Emitter.kt`")
5. **Relevant CLAUDE.md gotchas**: copy any gotcha entries that apply to the area being changed
6. **Regression guard**: "run the full suite (`./gradlew jvmTest 2>&1 | grep -a 'tests completed'`) before finishing and report the before/after count"

## Parallelism and branch isolation

Run parallel subagents in **separate branches** (use `isolation: "worktree"` in the Agent tool call). Limit to **max 2 parallel subagents** to keep resource usage and merge conflicts manageable — nearly every fix touches `Parser.kt`, `Transformer.kt`, or `Emitter.kt`.

Dispatch in **waves** to keep merge conflicts manageable:
- Pick fixes that touch *different* primary files for a wave
- Merge + resolve conflicts between waves before starting the next
- Fixes that touch the same file heavily (e.g. two Transformer changes) should be sequential

## Merge workflow (between waves)

After all subagents in a wave complete, merge their worktree branches sequentially into `main`:

```bash
git fetch
git merge <worktree-branch> --no-ff -m "merge: task <X> fix"
# Conflicts are typically in different functions of the same file — resolve manually
git push
```

## Worktree waves in THIS repo (learned 2026-09-01, the (EXT.1)/(LSP.1) wave)

- **One gradle invocation per BOX, not per agent**: every agent gradle call goes through
  `flock /tmp/xtsc-gradle.lock ./gradlew …`, launched `run_in_background` with FULL
  output redirected to a file (never piped). Agents must never `--stop` or pkill a
  daemon, and must run only their module's QUALIFIED task — bare `jvmTest` runs every
  module's 16k-test corpus.
- **A fresh worktree lacks `typescript-repo/` and `tools/`** (gitignored), and core's
  `generateRealLibSources` would CLONE TypeScript over the network without them — agents
  symlink both from the main checkout as their first action. **The `.gitignore`
  trailing-slash patterns (`/tools/`, `/typescript-repo/`) do NOT match symlinks**
  (dir-only patterns), so the links show as `??` forever: delete them before ending with
  a clean tree, and re-create them before any further build in that worktree.
- **Pre-scaffold shared files in the MAIN context** (settings.gradle.kts module
  registration, build.gradle.kts skeletons) so the agents' file sets are provably
  disjoint; forbid agents from editing anything outside their module (docs and
  CLAUDE.md included — they REPORT candidate gotchas instead).
- Expect each worktree's first build to be a COLD core compile (~6-8 min), serialized
  behind the lock. The merge gate (full suite + cost_gate + huge_methods) runs ONCE, in
  the main context, after all branches merge.

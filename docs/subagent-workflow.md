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

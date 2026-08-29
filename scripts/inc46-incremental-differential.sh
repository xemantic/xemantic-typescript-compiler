#!/usr/bin/env bash
# (INC.46) STEP 3 GATE — the incremental project-wide diagnostics differential: over
# REAL edits, Project.diagnostics() after an edit must equal a project opened fresh on
# the edited text, row for row. Needs no baseline (both arms answer the same question)
# and REFUSES a run in which nothing was served incrementally.
#
# It reuses (INC.46)(2)'s edit corpus, which contains a signature-CHANGING edit and a
# body-only one by construction (27 of 40 move no fingerprint, 13 do) — the queue's
# stated requirement, and the thing a hand-written fixture cannot promise.
#
# The queue's stated refusal threshold is ~70%: below it the 45x prize is diluted to
# nothing and the round should refuse. Step (1) established the fingerprint is cheap,
# rebuild-stable and partition-stable; NONE of that says how often a real edit leaves
# it alone, which is where all of the mechanism's value lives.
#
# WHY A SEPARATE CLONE. `typescript-repo` is a depth-1 shallow clone AND a build-pinned
# input (`typeScriptCommit` in build.gradle.kts) — deepening it would change what the
# corpus suite generates. This fetches its own history under build/bench (gitignored).
#
# WHY WHOLE TREES. Each case materialises the FULL src/compiler at the commit's parent
# and at the commit. A file taken from another era beside a tree from this one resolves
# against symbols that may not exist, degrading its exports to `any` in a way that is
# neither the before nor the after.
#
# REFUSES rather than skips when its inputs are absent (rounds 853/873).
#
# Usage: scripts/inc46-incremental-differential.sh [<cases> [<projectDir>]]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
CASES="${1:-40}"
BASE="637d5746b70257028fb95aad32ddec6b26ab0a14"

HIST="$ROOT/build/bench/ts-history"
[[ -d "$HIST/.git" ]] || {
  echo "REFUSED: no history clone at $HIST — see the header of this script" >&2; exit 2; }

PROFILE="${2:-}"
if [[ -z "$PROFILE" ]]; then
  shopt -s nullglob
  for candidate in build/bench/tsc-project-*; do
    [[ -f "$candidate/tsconfig.json" ]] && PROFILE="$candidate"
  done
  shopt -u nullglob
fi
[[ -n "$PROFILE" && -d "$PROFILE/src/compiler" ]] || {
  echo "REFUSED: no profile with src/compiler at '${2:-build/bench/tsc-project-*}'" >&2
  exit 2; }

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/Inc46IncrementalDifferentialMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# A SCRATCH copy of the profile: the corpus overwrites src/compiler wholesale, and the
# real profile is an input to every other gate in this repo.
SCRATCH="$ROOT/build/bench/inc46-scratch"
if [[ ! -d "$SCRATCH/src/compiler" ]]; then
  echo "materialising scratch profile from $PROFILE"
  rm -rf "$SCRATCH"; mkdir -p "$SCRATCH"
  cp -a "$PROFILE/." "$SCRATCH/"
fi

CORPUS="$ROOT/build/bench/inc46-corpus"
if [[ ! -d "$CORPUS" ]]; then
  echo "building the edit corpus: $CASES commits touching src/compiler"
  mkdir -p "$CORPUS"
  cd "$HIST"
  # Commits that touch src/compiler, newest first from the profile's own base commit.
  # `--no-merges`: a merge's "changed files" are not one author's edit.
  mapfile -t SHAS < <(git log --no-merges --format=%H -n 4000 "$BASE" -- src/compiler \
    | head -n "$((CASES * 3))")
  i=0
  for sha in "${SHAS[@]}"; do
    (( i >= CASES )) && break
    parent="$(git rev-parse "$sha^" 2>/dev/null || true)"
    [[ -n "$parent" ]] || continue
    # Only cases whose touched set is entirely FILES under src/compiler that exist on
    # both sides — a rename or an added file is a different question (the name set of
    # the PROGRAM changes) and is out of scope for a per-file signature gate.
    mapfile -t touched < <(git diff --name-only --diff-filter=M "$parent" "$sha" -- src/compiler \
      | grep -E '^src/compiler/[^/]+\.ts$' || true)
    (( ${#touched[@]} > 0 )) || continue
    dir="$CORPUS/$(printf '%03d' "$i")-${sha:0:8}"
    mkdir -p "$dir/before" "$dir/after"
    : > "$dir/touched.txt"
    ok=1
    for side in before after; do
      rev="$parent"; [[ "$side" == after ]] && rev="$sha"
      while read -r f; do
        [[ "$f" =~ ^src/compiler/[^/]+\.ts$ ]] || continue
        git show "$rev:$f" > "$dir/$side/$(basename "$f")" 2>/dev/null || { ok=0; break; }
      done < <(git ls-tree --name-only "$rev" src/compiler/)
    done
    if (( ok == 0 )); then rm -rf "$dir"; continue; fi
    for f in "${touched[@]}"; do basename "$f" >> "$dir/touched.txt"; done
    i=$((i+1))
  done
  cd "$ROOT"
  echo "corpus: $(find "$CORPUS" -maxdepth 1 -mindepth 1 -type d | wc -l) cases"
fi

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "commit:  $(git rev-parse --short HEAD)  date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.Inc46IncrementalDifferentialMainKt "$CORPUS" "$SCRATCH"

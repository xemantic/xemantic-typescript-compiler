#!/usr/bin/env bash
# (INC.50) THE STABILITY RATE ON *LAYERED* CODE — the one question (INC.47) left open.
#
# (INC.46) measured 67% of 40 real commits to tsc's own `src/compiler` moving no exported
# signature, and (INC.47) then removed every escape and measured the rate UNCHANGED, with
# all 40 per-case verdicts identical. So the residual 33% is commits that each genuinely
# move a signature, and no refinement of the fingerprint can serve them.
#
# What is NOT known is whether 67% is a property of the mechanism or of tsc's own sources.
# tsc is one codebase's style — `export *` barrels, a file declaring the whole type
# universe, 78 files in one flat directory. Ordinary application and library code is
# layered, and (INC.35)/(INC.50) both turn on whether that changes the answer.
#
# WHY cronstrue. It is the only library outside the corpus on which this checker agrees
# with tsgo 7.0.2 EXACTLY — 0 errors on both sides (`docs/kir-library-readiness.md`) — and
# it has NO dependencies. Both matter, and the first one is a CONTROL rather than a
# convenience: a library our checker reports errors on has types degraded to `any`, and a
# degraded type is artificially STABLE, which would inflate the very rate being measured.
# 52 files, 8,812 lines, a nested `src` with an i18n locale layer, 1,092 commits.
#
# WHY ITS OWN COMPILER OPTIONS. The rate is a claim about a real project, so the project's
# own `strict`/`target`/`module` settings stand; only the emit-side keys are dropped
# (nothing is emitted) and `include` is re-pointed at the swapped tree.
#
# REFUSES rather than skips when its inputs are absent (rounds 853/873).
#
# Usage: scripts/inc50-stability-lib.sh [<cases>]
#        LIB=marked REPO=https://github.com/markedjs/marked.git \
#          TSCONFIG=<path to a compilerOptions json> scripts/inc50-stability-lib.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
CASES="${1:-40}"
LIB="${LIB:-cronstrue}"
REPO="${REPO:-https://github.com/bradymholt/cRonstrue.git}"

HIST="$ROOT/build/bench/$LIB-history"
if [[ ! -d "$HIST/.git" ]]; then
  echo "cloning $LIB (blob-filtered, depth 3000) into $HIST"
  git clone --filter=blob:none --depth 3000 -q "$REPO" "$HIST"
fi

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/Inc46StabilityMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

CORPUS="$ROOT/build/bench/inc50-corpus-$LIB"
if [[ ! -d "$CORPUS" ]]; then
  echo "building the edit corpus: $CASES commits touching src"
  mkdir -p "$CORPUS"
  cd "$HIST"
  # `--no-merges`: a merge's "changed files" are not one author's edit.
  mapfile -t SHAS < <(git log --no-merges --format=%H -n 4000 HEAD -- src \
    | head -n "$((CASES * 6))")
  i=0
  for sha in "${SHAS[@]}"; do
    (( i >= CASES )) && break
    parent="$(git rev-parse "$sha^" 2>/dev/null || true)"
    [[ -n "$parent" ]] || continue
    # MODIFIED `.ts` files only: a rename or an addition changes the program's NAME SET,
    # which is a different question from whether a file's exported signature moved.
    mapfile -t touched < <(git diff --name-only --diff-filter=M "$parent" "$sha" -- src \
      | grep -E '^src/.*\.ts$' || true)
    (( ${#touched[@]} > 0 )) || continue
    dir="$CORPUS/$(printf '%03d' "$i")-${sha:0:8}"
    mkdir -p "$dir/before" "$dir/after"
    : > "$dir/touched.txt"
    ok=1
    for side in before after; do
      rev="$parent"; [[ "$side" == after ]] && rev="$sha"
      # The WHOLE tree on each side (rounds: a file from another era beside a tree from
      # this one resolves against symbols that may not exist, degrading its exports to
      # `any` in a way that is neither the before nor the after).
      while read -r f; do
        rel="${f#src/}"
        mkdir -p "$dir/$side/$(dirname "$rel")"
        git show "$rev:$f" > "$dir/$side/$rel" 2>/dev/null || { ok=0; break; }
      done < <(git ls-tree -r --name-only "$rev" src/)
    done
    if (( ok == 0 )); then rm -rf "$dir"; continue; fi
    for f in "${touched[@]}"; do echo "${f#src/}" >> "$dir/touched.txt"; done
    i=$((i+1))
  done
  cd "$ROOT"
  echo "corpus: $(find "$CORPUS" -maxdepth 1 -mindepth 1 -type d | wc -l) cases"
fi

# A SCRATCH project: a root tsconfig plus the `src` tree the corpus swaps.
SCRATCH="$ROOT/build/bench/inc50-scratch-$LIB"
if [[ ! -f "$SCRATCH/tsconfig.json" ]]; then
  mkdir -p "$SCRATCH/src"
  # The library's own options, minus the emit-side keys (nothing is emitted here).
  # TSCONFIG overrides it for a library whose own settings differ.
  # A `nodenext` project's module format comes from the nearest enclosing package.json
  # ((CHK.29)), which is a file the crawl reads and no fixture has: a library whose own
  # tsconfig says NodeNext needs its manifest beside the tsconfig or every file is read
  # as CommonJS.
  [[ -n "${PKGJSON:-}" ]] && cp "$PKGJSON" "$SCRATCH/package.json"
  if [[ -n "${TSCONFIG:-}" ]]; then cp "$TSCONFIG" "$SCRATCH/tsconfig.json"; else
  cat > "$SCRATCH/tsconfig.json" <<'JSON'
{
  "compilerOptions": {
    "module": "commonjs",
    "target": "ES5",
    "noImplicitAny": true
  },
  "include": ["src/**/*.ts"]
}
JSON
  fi
fi

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "library: $LIB  commit: $(git rev-parse --short HEAD)  date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.Inc46StabilityMainKt "$CORPUS" "$SCRATCH" src

#!/usr/bin/env bash
# (INC.40) RE-PRICE the re-entrant replay (Recheck.kt / ProgramRecheck) for the
# DIAGNOSTICS CHANNEL ONLY — the one channel `scripts/replay-differential.sh`
# grades EQUIVALENT.
#
# CLAUDE.md's standing law: the replay's advantage fell 3.06x -> 1.91x -> 1.68x
# across the (INC.*) arc without the replay changing at all, because every round
# that shrinks the incremental FLOOR shrinks its reason to exist. Quote a replay
# speed-up with the floor it was measured against; this runner re-measures both in
# the same process.
#
# Unlike replay-differential.sh, NEITHER arm asks for a capture — a whole-file
# capture is +9..17 ms per query (INC.13) that both arms would pay and that dilutes
# the ratio.
#
# REFUSES rather than skips when its inputs are absent (rounds 853/873).
#
# Usage: scripts/inc40-replay-cost.sh [<projectDir> [rotations [warmups [groupSizes]]]]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

PROJECT="${1:-}"
if [[ -z "$PROJECT" ]]; then
  shopt -s nullglob
  for candidate in build/bench/tsc-project-*; do
    [[ -f "$candidate/tsconfig.json" ]] && PROJECT="$candidate"
  done
  shopt -u nullglob
fi
[[ -n "$PROJECT" && -f "$PROJECT/tsconfig.json" ]] || {
  echo "REFUSED: no project at '${1:-build/bench/tsc-project-*}' — materialize one with" \
       "scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log" >&2
  exit 2; }

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/Inc40ReplayCostMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

echo "project: $PROJECT"
echo "commit:  $(git rev-parse --short HEAD 2>/dev/null || echo '<unknown>')  date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.Inc40ReplayCostMainKt \
  "$PROJECT" "${2:-3}" "${3:-6}" "${4:-1,2,8}"

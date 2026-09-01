#!/usr/bin/env bash
# Our half of the tsgo-vs-us incremental comparison — the same four cells
# scripts/tsgo-incremental-bench.sh measures, over the same tree and the same edits.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
P="${1:-build/bench/ours-bench}"
EDIT_REL="${2:-src/compiler/binder.ts}"
EDITS="${3:-/tmp/claude-1000}"
REPS="${4:-3}"
[[ -f "$P/tsconfig.json" ]] || { echo "REFUSED: no project at $P" >&2; exit 2; }
CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/IncrementalComparisonMainKt.class" ]] || {
  echo "REFUSED: runner not built" >&2; exit 2; }
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"
exec java -Xmx6g -cp "$CP" \
  com.xemantic.typescript.compiler.project.IncrementalComparisonMainKt \
  "$ROOT/$P" "$EDIT_REL" "$EDITS" "$REPS"

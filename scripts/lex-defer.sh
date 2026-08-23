#!/usr/bin/env bash
# (INC.16) — the `bindLexicalScopes` deferral, ONE ARM PER PROCESS.
#
# Runs `LexDeferMain` twice — eager (the shipped build) and deferred — and diffs
# the two arms' per-file INV.2(c) FINGERPRINTS. That diff is hazard (a):
# `moduleLexicalScope` and the `EnumDeclaration` arm read the BINDER's accumulated
# `nodeToSymbol`, whose `(pos, end)` keys collide across files and are last-wins in
# bind order, so a scope built at first ask can alias a different symbol's exports.
#
# REFUSES rather than skips when its inputs are absent (round 853).
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
  echo "REFUSED: no project at '${1:-build/bench/tsc-project-*}'" >&2; exit 2; }

CLASSES="$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/test"
[[ -f "$CLASSES/com/xemantic/typescript/compiler/project/LexDeferMainKt.class" ]] || {
  echo "REFUSED: runner not built — ./gradlew :xemantic-typescript-compiler-project:compileTestKotlinJvm" >&2
  exit 2; }

# shellcheck source=scripts/lib/dep-classpath.sh
source "$ROOT/scripts/lib/dep-classpath.sh"
DEPS="$(xtsc_dep_classpath)"
CP="$CLASSES:$ROOT/xemantic-typescript-compiler-project/build/classes/kotlin/jvm/main"
CP="$CP:$ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS"

OUT="${XTSC_LEX_OUT:-build/bench/inc16}"
mkdir -p "$OUT"
WARM="${2:-3}"

echo "project: $PROJECT   out: $OUT"
for arm in eager deferred verify; do
  defer=0; verify=0
  [[ "$arm" == deferred ]] && defer=1
  [[ "$arm" == verify ]] && { defer=1; verify=1; }
  rm -f "$OUT/$arm.fp" "$OUT/$arm.log"
  XTSC_LEX_DEFER="$defer" XTSC_LEX_VERIFY="$verify" XTSC_LEX_FP="$OUT/$arm.fp" \
    java -Xmx6g -cp "$CP" \
      com.xemantic.typescript.compiler.project.LexDeferMainKt "$PROJECT" "$WARM" \
      > "$OUT/$arm.log" 2>&1 || { echo "REFUSED: $arm arm failed"; tail -20 "$OUT/$arm.log"; exit 3; }
  echo "--- $arm ---"
  grep -E '^(COUNTS|FORCEDBY|program:|FP wrote|WALL|QUERY)' "$OUT/$arm.log"
done

echo "--- fingerprints ---"
[[ -s "$OUT/eager.fp" && -s "$OUT/deferred.fp" ]] || { echo "REFUSED: an empty fingerprint file"; exit 4; }
a=$(wc -l < "$OUT/eager.fp"); b=$(wc -l < "$OUT/deferred.fp")
echo "rows: eager=$a deferred=$b"
if diff -q "$OUT/eager.fp" "$OUT/deferred.fp" >/dev/null; then
  echo "HAZARD-A: IDENTICAL — every file's INV.2(c) tables are the same in both arms"
else
  echo "HAZARD-A: DIVERGED"
  diff "$OUT/eager.fp" "$OUT/deferred.fp" | head -40
  echo "diverged files: $(diff --unchanged-line-format= --old-line-format='%L' --new-line-format= "$OUT/eager.fp" "$OUT/deferred.fp" | wc -l)"
fi

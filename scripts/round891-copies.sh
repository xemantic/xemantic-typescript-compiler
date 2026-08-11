#!/usr/bin/env bash
# (WARM.16) round 869 — the PER-SCOPE WHOLE-MAP COPY census and its price.
#
# Round 868's leaf profile left two candidates unpriced, and said in as many
# words that a leaf is a CANDIDATE until an independent instrument prices it.
# This harness runs the independent instrument for candidate (C2):
#
#   * `copyamp0`  — the CENSUS. Counts, entry volume, mean/max size and WRITES
#                   per copy family. Round 801's produced-versus-consumed test:
#                   an undo-log costs O(writes) where a copy costs O(size).
#   * `copyamp<r>` — the PRICE. `r` extra whole-map copies at every censused
#                   site, no timestamp pair anywhere, read off the WHOLE-REBUILD
#                   wall (round 759's amplification). Two values of `r` cancel
#                   the base algebraically; the falsifier is arithmetic
#                   (`ampSink == r * entries`, printed by the census itself).
#
# Shape and ORDER are round 851's: every gradle-invoking step BEFORE the daemon
# stop, class dirs checked non-empty in between (round 853's positive control).
#
# Usage: round869-warm-copies.sh setup | tier <tierlist> <n> [iters]
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round891
mkdir -p "$OUT"
STAGE="${1:-setup}"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
TEST=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test

if [[ "$STAGE" == "setup" ]]; then
  date > "$OUT/started"
  ./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build.log" 2>&1
  grep -q 'BUILD SUCCESSFUL' "$OUT/build.log" || { echo "BUILD FAILED" >> "$OUT/started"; exit 1; }
  scripts/lib/dep-classpath.sh --print > "$OUT/deps.txt" || { echo "DEPS FAILED" >> "$OUT/started"; exit 1; }
  ./gradlew --stop >> "$OUT/build.log" 2>&1
  pkill -f 'KotlinCompile[D]aemon'
  sleep 10
  free -m > "$OUT/free.txt"
  [[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || {
    echo "REFUSED: main class dir does not hold MainKt" >> "$OUT/started"; exit 1; }
  [[ -f "$TEST/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]] || {
    echo "REFUSED: test class dir does not hold BenchMainKt" >> "$OUT/started"; exit 1; }
  echo "main classes: $(find "$MAIN" -name '*.class' | wc -l)" >> "$OUT/started"
  ls -d build/bench/tsc-project-* | head -1 > "$OUT/proj.txt"
  echo "setup ok" >> "$OUT/started"
  exit 0
fi

DEPS="$(cat "$OUT/deps.txt")"
CPW="$MAIN:$TEST:$DEPS"
PROJ="$(cat "$OUT/proj.txt")"

if [[ "$STAGE" == "tier" ]]; then
  T="${2:-copyamp0}"; N="${3:-1}"; IT="${4:-2}"
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 "$IT" "$T" > "$OUT/warm-$N.log" 2>&1
  echo "tier $T $N exit=$? $(date)" >> "$OUT/started"
  exit 0
fi

echo "unknown stage: $STAGE" >&2
exit 2

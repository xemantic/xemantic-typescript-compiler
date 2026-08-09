#!/usr/bin/env bash
# (WARM.21) round 874 — the TAV pass: its CENSUS and its PRICE.
#
# The candidate is `spineTavIdentifier` (INV.4(c)(iv), the migrated
# `checkTypeUsedAsValue`), which the third warm leaf profile reads at
# 2.20%/2.03% INCLUSIVE — ~140 ms of a 6.6 s warm rebuild — spread over eight
# rows none of which is above 0.6% on its own. A FAMILY aggregation of that
# table is what surfaced it; two earlier re-takes walked past it.
#
# Two stages, and the ORDER is round 801's law (the produced-versus-consumed
# census comes BEFORE any timing, because it decides the shape of the fix and a
# timing cannot):
#
#   census — `frontend,frontend`, deterministic counters, no wall claimed.
#   arms   — `plain` vs `tavoff`, ABBA-rotated inside ONE warm process. Neither
#            arms a probe, so both walls are production-comparable and no
#            boundary accounting is needed (round 793). NO timestamp pair is
#            taken anywhere: a per-identifier span would cost 390k x 97-202 ns
#            (round 850) and BE the measurement.
#
# The OFF arm's falsifier is free and unambiguous: with the pass off the
# compile loses its TS2693/TS2708 emissions, so the reported `errors` MOVES. An
# arm that reports `plain`'s error count did not run.
#
# Round 851's order throughout: every gradle-invoking step BEFORE the daemon
# stop, class dirs positively controlled in between (round 853).
#
# Usage: round874-tav.sh setup | census <n> | arms <n>
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round874
mkdir -p "$OUT"
STAGE="${1:-setup}"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
TEST=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test

if [[ "$STAGE" == "setup" ]]; then
  date > "$OUT/tav-started"
  ./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/tav-build.log" 2>&1
  grep -q 'BUILD SUCCESSFUL' "$OUT/tav-build.log" || { echo "BUILD FAILED" >> "$OUT/tav-started"; exit 1; }
  scripts/lib/dep-classpath.sh --print > "$OUT/deps.txt" || { echo "DEPS FAILED" >> "$OUT/tav-started"; exit 1; }
  ./gradlew --stop >> "$OUT/tav-build.log" 2>&1
  pkill -f 'KotlinCompile[D]aemon'
  sleep 10
  free -m > "$OUT/tav-free.txt"
  [[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || {
    echo "REFUSED: main class dir does not hold MainKt" >> "$OUT/tav-started"; exit 1; }
  [[ -f "$TEST/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]] || {
    echo "REFUSED: test class dir does not hold BenchMainKt" >> "$OUT/tav-started"; exit 1; }
  echo "main classes: $(find "$MAIN" -name '*.class' | wc -l)" >> "$OUT/tav-started"
  ls -d build/bench/tsc-project-* | head -1 > "$OUT/proj.txt"
  echo "setup ok" >> "$OUT/tav-started"
  exit 0
fi

DEPS="$(cat "$OUT/deps.txt")"
CPW="$MAIN:$TEST:$DEPS"
PROJ="$(cat "$OUT/proj.txt")"

if [[ "$STAGE" == "census" ]]; then
  N="${2:-1}"
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 6 frontend,frontend > "$OUT/tav-census$N.log" 2>&1
  echo "census $N exit=$? $(date)" >> "$OUT/tav-started"
  exit 0
fi

# The SPAN arm: `frontend` carries the [FrontEnd.TAV] row (one pair per
# dispatched identifier). The row is NEVER a price on its own — its boundary is
# 37-77 ms of itself — it exists to be differenced against the same row on the
# gated arm, where the call count is identical (round 793/795).
if [[ "$STAGE" == "span" ]]; then
  N="${2:-1}"
  ARM="${3:-frontend}"
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 5 "$ARM" > "$OUT/tav-span-$N.log" 2>&1
  echo "span $ARM $N exit=$? $(date)" >> "$OUT/tav-started"
  exit 0
fi

if [[ "$STAGE" == "arms" ]]; then
  N="${2:-1}"
  # ABBA x3: round 846's law is that the FIRST instrumented rebuild in a process
  # is the slowest draw, and an un-rotated ladder puts that bias entirely on
  # whichever arm runs first.
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 5 plain,tavoff,tavoff,plain,plain,tavoff,tavoff,plain \
     > "$OUT/tav-arms$N.log" 2>&1
  echo "arms $N exit=$? $(date)" >> "$OUT/tav-started"
  exit 0
fi

echo "unknown stage: $STAGE" >&2
exit 2

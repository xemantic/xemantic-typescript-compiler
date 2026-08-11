#!/usr/bin/env bash
# (WARM.22) round 888 — the warm leaf profile RE-TAKEN a FOURTH time, thirteen
# rounds after round 874's third take.
#
# Recipe IDENTICAL to scripts/round874-warm-leaf.sh (same window, same warm-up
# ladder, same two processes) so that the only variable between the two tables
# is the BINARY: rounds 876-887 landed the sequential-bind removal, the
# balanced worker partition, the merge forwarding table and the anchor-mark
# deletion. Round 755's law — re-measure a number before spending a round
# inside it — and round 870's — a JFR share is a share of WALL TIME, so every
# unchanged cost's share RISES after a win, and only ms/rebuild compares.
#
# Order is round 851's: every gradle-invoking step BEFORE the daemon stop,
# class dirs checked non-empty in between (round 853's positive control).
#
# Usage: round888-warm-leaf.sh setup | jfr <n> | tier <name> <n>
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round888
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

if [[ "$STAGE" == "jfr" ]]; then
  N="${2:-1}"
  java -Xmx4g \
     -XX:FlightRecorderOptions=stackdepth=1024 \
     -XX:StartFlightRecording=settings=profile,delay=60s,duration=90s,filename="$OUT/warm$N.jfr" \
     -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 20 > "$OUT/warm-jfr$N.log" 2>&1
  echo "jfr $N exit=$? $(date)" >> "$OUT/started"
  exit 0
fi

if [[ "$STAGE" == "tier" ]]; then
  T="${2:-frontend}"; N="${3:-1}"
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 8 "$T" > "$OUT/warm-$T-$N.log" 2>&1
  echo "tier $T $N exit=$? $(date)" >> "$OUT/started"
  exit 0
fi

echo "unknown stage: $STAGE" >&2
exit 2

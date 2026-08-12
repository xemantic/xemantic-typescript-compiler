#!/usr/bin/env bash
# Round 893 stage 2: (a) a GC arm per binary — the cheap test of the
# "the excess is reduced GC pressure" hypothesis; (b) the fresh warm LEAF
# profile of HEAD, recipe IDENTICAL to scripts/round888-warm-leaf.sh so the
# only variable against round 888's table is the binary.
set -uo pipefail
SP="${ARMS_DIR:?set ARMS_DIR to a dir holding arms/A_main, arms/B_main, arms/TEST_head and deps.txt}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO/build/bench/round893"
mkdir -p "$OUT"
DEPS="$(cat "$SP/deps.txt")"
TEST="$SP/arms/TEST_head"
PROJ="$(ls -d "$REPO"/build/bench/tsc-project-* | head -1)"
echo "started $(date)" > "$OUT/started"

# --- (a) GC, one process per arm, same shape as the A/B samples -------------
for ARM in A B; do
  java -Xmx4g -Xlog:gc:file="$OUT/gc$ARM.log" \
    -cp "$SP/arms/${ARM}_main:$TEST:$DEPS" \
    com.xemantic.typescript.compiler.bench.BenchMainKt "$PROJ" 6 8 \
    > "$OUT/gc$ARM.jsonl" 2>&1
  echo "gc $ARM exit=$? $(date)" >> "$OUT/started"
done

# --- (b) the warm leaf profile of HEAD (arm B), two processes ---------------
for N in 1 2; do
  java -Xmx4g \
    -XX:FlightRecorderOptions=stackdepth=1024 \
    -XX:StartFlightRecording=settings=profile,delay=60s,duration=90s,filename="$OUT/warm$N.jfr" \
    -cp "$SP/arms/B_main:$TEST:$DEPS" \
    com.xemantic.typescript.compiler.bench.BenchMainKt "$PROJ" 3 20 \
    > "$OUT/warm-jfr$N.log" 2>&1
  echo "jfr $N exit=$? $(date)" >> "$OUT/started"
  jfr print --stack-depth 512 --events jdk.ExecutionSample "$OUT/warm$N.jfr" \
    > "$OUT/deep$N.txt" 2> "$OUT/deep$N.err"
  echo "print $N exit=$? lines=$(wc -l < "$OUT/deep$N.txt") $(date)" >> "$OUT/started"
done
echo "done $(date)" >> "$OUT/started"
touch "$OUT/DONE"

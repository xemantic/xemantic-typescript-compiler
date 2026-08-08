#!/usr/bin/env bash
# (WARM.4) round 847 — the WARM per-kind / per-handler attribution of `checkSpine`.
#
# One binary, one profile, one detached run. Round 800's trap is why the daemon
# stop lives INSIDE this script, between the build and the first sample: a batch
# that builds and then immediately measures reads every row ~270x too large and
# no internal consistency check can see it.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round847
mkdir -p "$OUT"
date > "$OUT/started"

./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build.log" 2>&1
if ! grep -q 'BUILD SUCCESSFUL' "$OUT/build.log"; then
  echo "BUILD FAILED" >> "$OUT/started"; date > "$OUT/done"; exit 1
fi

./gradlew --stop >> "$OUT/build.log" 2>&1
pkill -f 'KotlinCompile[D]aemon'
sleep 10
free -m > "$OUT/free.txt"

PROJ=$(ls -d build/bench/tsc-project-* | head -1)
CP=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$(cat build/bench/cp.txt)
CPW=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test:$(tr '\n' ':' < build/bench/cp-warm.txt)
echo "PROJ=$PROJ" >> "$OUT/started"

# --- WARM: two independent processes, two draws of each tier each. The second
#     draw is the one that is quoted — round 846 measured the PROBE's own code
#     being cold on its first instrumented rebuild (3,457 -> 1,856 ms).
for i in 1 2; do
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 3 8 spine,dispatch,spine,dispatch > "$OUT/warm$i.log" 2>&1
done

# --- COLD, SAME binary: the cross-regime comparison. Every row in
#     docs/perf/dispatch-table.md is a cold run of a binary ~115 rounds old.
for i in 1 2; do
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
       --noEmit --dispatchProbe "$PROJ" > "$OUT/cold-dispatch$i.log" 2>&1
done
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --passTimingSpine "$PROJ" > "$OUT/cold-spine.log" 2>&1

date > "$OUT/done"

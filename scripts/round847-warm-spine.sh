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
# ROUND 858: this COLD arm used to read build/bench/cp.txt, a hand-frozen Jul-8
# dependency tail (kotlin-stdlib 2.4.0 / kotlinx-io 0.9.0 / serialization 1.9.0)
# while the WARM arm below read the CURRENT one - so every cold/warm ratio this
# script produced compared two different dependency tails. Now both arms resolve
# through the validating shared resolver.
. scripts/lib/dep-classpath.sh
DEPS="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || exit 1
CP=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$DEPS
CPW=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test:$DEPS
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

#!/usr/bin/env bash
# (SPINE.1) round 908, third run — the frame-ambient install's POPULATION.
#
# The warm `spinesections` table says the two installs cost 54 + 26 ms. What a
# time cannot say is whether the O(frames) rebuild inside them produces
# anything; this run reads the census that answers it, in the SAME rebuild as
# the row (round 861: a sub-population and the row it is read against must come
# from one rebuild, or the ratio is a cross-draw quotient).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round908c
mkdir -p "$OUT"
date > "$OUT/started"

./gradlew compileKotlinJvm compileTestKotlinJvm > "$OUT/build.log" 2>&1
if ! grep -q 'BUILD SUCCESSFUL' "$OUT/build.log"; then
  echo "BUILD FAILED" >> "$OUT/started"; date > "$OUT/done"; exit 1
fi

# Gates BEFORE the daemon stop (round 851: a gradle-invoking step after the
# stop can have its daemon killed mid-flight and leave an empty class dir).
python3 scripts/cost_gate.py > "$OUT/cost-gate.log" 2>&1; echo "cost_gate=$?" >> "$OUT/started"
python3 scripts/huge_methods.py --fail-over 0 > "$OUT/huge.log" 2>&1; echo "huge=$?" >> "$OUT/started"

./gradlew --stop >> "$OUT/build.log" 2>&1
pkill -f 'KotlinCompile[D]aemon'
sleep 10
free -m > "$OUT/free.txt"

PROJ=$(ls -d build/bench/tsc-project-* | head -1)
. scripts/lib/dep-classpath.sh
DEPS="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || { date > "$OUT/done"; exit 1; }
CPW=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test:$DEPS
echo "$CPW" | grep -q 'xemantic-typescript-compiler-core/build/classes' || {
  echo "CLASSPATH REFUSED" >> "$OUT/started"; date > "$OUT/done"; exit 1; }

for i in 1 2; do
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 6 6 spinesections,plain,spinesections,plain > "$OUT/sec$i.log" 2>&1
  echo "sec$i done $(date)" >> "$OUT/started"
done

date > "$OUT/done"

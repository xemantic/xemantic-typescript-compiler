#!/usr/bin/env bash
# (SPINE.1) round 908, second run — round 733's `SpineSections` partition of
# `cpaSpineLeave` + `ccetSpineLeave`, WARM, for the first time.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round908b
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
. scripts/lib/dep-classpath.sh
DEPS="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || { date > "$OUT/done"; exit 1; }
CPW=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test:$DEPS
echo "$CPW" | grep -q 'xemantic-typescript-compiler-core/build/classes' || {
  echo "CLASSPATH REFUSED" >> "$OUT/started"; date > "$OUT/done"; exit 1; }

# Two draws per process: the probe's own code is cold on the first instrumented
# rebuild (round 846), and `plain` between them is the null arm — it arms NOTHING,
# so the process also carries an uninstrumented rebuild at the same warmth as a
# control on the median the overhead is taken against.
for i in 1 2; do
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 6 6 spinesections,plain,spinesections,plain > "$OUT/sec$i.log" 2>&1
  echo "sec$i done $(date)" >> "$OUT/started"
done

date > "$OUT/done"

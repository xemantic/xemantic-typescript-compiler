#!/usr/bin/env bash
# (WARM.13) round 866 — the WARM A/B of the round-732 per-kind dispatch table's
# GATED stand-in against a null arm, inside ONE process each time.
#
# Round 732 ran GATED once per mode, cold, non-interleaved, with no drift band
# (docs/perf/dispatch-table.md § 5's own ROUND-758 caveat). Round 847 took the
# probe's UPPER bound warm but never ran GATED at all. This is the LOWER bound,
# warm, paired.
#
# No build here: round 851's order is that every gradle step happens BEFORE the
# daemon stop, and this script is what runs after it. It refuses to start unless
# the test class dir holds a class that did not exist before this round
# (round 853's positive control) — a stale dir cannot make the arms agree.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round866
mkdir -p "$OUT"
date > "$OUT/started"

CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm
if [[ ! -f "$CLASSES/test/com/xemantic/typescript/compiler/bench/BenchGatedTierTest.class" ]]; then
  echo "ABORT — BenchGatedTierTest.class absent: the class dir predates round 866" | tee -a "$OUT/started"
  exit 1
fi
if [[ ! -f "$CLASSES/main/com/xemantic/typescript/compiler/MainKt.class" ]]; then
  echo "ABORT — the core class dir has no MainKt: it is not the compiler" | tee -a "$OUT/started"
  exit 1
fi

. scripts/lib/dep-classpath.sh
DEPS="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || exit 1
CPW="$CLASSES/main:$CLASSES/test:$DEPS"
PROJ=$(ls -d build/bench/tsc-project-* | head -1)
echo "PROJ=$PROJ" >> "$OUT/started"
free -m >> "$OUT/started"

# Two BATCHES (round 840(c)/858: a sign-consistent paired batch is not a result;
# only a second batch separates drift landing on an arm from an effect), each of
# two processes whose tier ORDER is rotated, so neither arm always holds the
# first — and therefore coldest — instrumented slot (round 846's law, 4/4 across
# this arc).
for batch in 1 2; do
  for order in "gated,plain,gated,plain" "plain,gated,plain,gated"; do
    tag="b${batch}-$(echo "$order" | cut -c1-5)"
    java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
         "$PROJ" 3 6 "$order" > "$OUT/$tag.log" 2>&1
    echo "$tag done $(date +%T)" >> "$OUT/started"
  done
done

date > "$OUT/done"

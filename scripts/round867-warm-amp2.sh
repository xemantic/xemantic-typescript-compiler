#!/usr/bin/env bash
# (WARM.14) round 867, BATCH 2 — the same measurement with two artifacts of
# batch 1 removed, and it is the batch the round quotes.
#
# Batch 1 ran BOTH arms in one process, at r = 8/16/32.  Two problems, both
# visible in its own numbers (`docs/perf/dispatch-table.md` § 9.2):
#
#  1. THE TWO ARMS SHARE ONE COMPILED `spineAmpPass`.  They differ only in the
#     mask they pass it, so the arm that runs first writes the branch profile
#     the other one is compiled against — and an arm whose consultation
#     branches were profiled as NEVER TAKEN pays an uncommon trap for every one
#     of them.  Batch 2 gives each arm its own JVM: nothing else changes.
#  2. THE FIRST AMPLIFIED REBUILD IN A PROCESS IS THE ONE THAT WARMS
#     `spineAmpPass` — it is never exercised by the uninstrumented loop, so its
#     first tier is systematically dearer per pass.  Batch 2 discards a leading
#     throwaway amp rebuild.
#
# And r = 16/48/96 rather than 8/16/32: the boundary is a per-node CONSTANT, so
# a wider spread divides the same noise by a larger denominator.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round867amp2
mkdir -p "$OUT"
date > "$OUT/started"

CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm
if [[ ! -f "$CLASSES/test/com/xemantic/typescript/compiler/bench/BenchAmpTierTest.class" ]]; then
  echo "ABORT — BenchAmpTierTest.class absent: the class dir predates round 867" | tee -a "$OUT/started"
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

# Batch 2 runs eight JVMs and takes ~12 minutes, which is longer than one
# foreground call may hold; `$1` selects a HALF (`a` = processes 1-4, `b` = 5-8,
# omitted = all).  Both halves write into the same directory and the analyzer
# reads whatever is there, so the split is a scheduling detail and not an arm.
HALF="${1:-all}"
i=0
# The leading tier of every list is the THROWAWAY that warms the amplified path;
# the analyzer drops it by position.  The remaining three are a rotation, so no
# `r` always holds the slot straight after the throwaway.
for spec in \
    "amp:48,16,48,96"  "ampc:48,16,48,96" \
    "amp:48,48,96,16"  "ampc:48,48,96,16" \
    "amp:48,96,16,48"  "ampc:48,96,16,48" \
    "amp:48,16,96,48"  "ampc:48,16,96,48" ; do
  arm="${spec%%:*}"; reps="${spec#*:}"
  order=""
  for r in ${reps//,/ }; do order="${order:+$order,}$arm$r"; done
  i=$((i + 1))
  if [[ "$HALF" == "a" && $i -gt 4 ]]; then continue; fi
  if [[ "$HALF" == "b" && $i -le 4 ]]; then continue; fi
  tag="p$i-$arm"
  java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
       "$PROJ" 3 2 "$order" > "$OUT/$tag.log" 2>&1
  echo "$tag [$order] done $(date +%T)" >> "$OUT/started"
done

[[ "$HALF" == "a" ]] || date > "$OUT/done"

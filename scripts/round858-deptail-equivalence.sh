#!/usr/bin/env bash
# ROUND 858 — does the STALE dependency tail actually change anything?
#
# THE QUESTION. Rounds 847 and 849 ran their WARM arms against the current
# dependency tail (build/bench/cp-warm.txt: kotlin-stdlib 2.4.10, kotlinx-io
# 0.9.1, serialization 1.11.0) and their COLD arms against build/bench/cp.txt, a
# hand-frozen Jul-8 file naming 2.4.0 / 0.9.0 / 1.9.0. Both arms link fine (the
# jars are ABI-compatible on the compile path — verified), so every cold/warm
# RATIO those rounds published compared two different dependency tails. This
# script measures that confound directly instead of hand-waving it.
#
# THE DESIGN. One binary, one profile, one main class dir; the ONLY difference
# between the arms is the dependency tail. Rotated interleave so drift cannot
# align with an arm, and a `--listAll` digest per arm because a dependency that
# changed BEHAVIOUR would matter far more than one that changed timing.
#
# Box must be quiet: daemons are stopped INSIDE this script, between any build
# and the first sample (round 800).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler

OUT=build/bench/round858
mkdir -p "$OUT"
date > "$OUT/started"

PAIRS="${PAIRS:-3}"
MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
PROJ=$(ls -d build/bench/tsc-project-* | head -1)

# ARM A — the STALE tail, exactly as rounds 847/849's cold arms had it.
[ -s build/bench/cp.txt ] || { echo "no build/bench/cp.txt — nothing to compare" >&2; exit 1; }
CP_STALE="$(cat build/bench/cp.txt)"

# ARM B — the CURRENT tail, through the round-858 validating resolver.
. scripts/lib/dep-classpath.sh
CP_FRESH="$(xtsc_dep_classpath build/bench/cp-warm.txt)" || exit 1

{
  echo "PROJ=$PROJ"
  echo "stale: $(printf '%s' "$CP_STALE" | tr ':' '\n' | sed 's|.*/||' | tr '\n' ' ')"
  echo "fresh: $(printf '%s' "$CP_FRESH" | tr ':' '\n' | sed 's|.*/||' | tr '\n' ' ')"
} >> "$OUT/started"

./gradlew --stop >/dev/null 2>&1
pkill -f 'KotlinCompile[D]aemon'
sleep 10
free -m > "$OUT/free.txt"

now_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

run_one() {  # run_one <arm> <cp> <tag>
  local arm="$1" cp="$2" tag="$3" out start end
  out="$OUT/run-$tag.log"
  start="$(now_ms)"
  java -Xmx4g -cp "$MAIN:$cp" com.xemantic.typescript.compiler.MainKt \
       --noEmit "$PROJ" > "$out" 2>&1
  end="$(now_ms)"
  local self err
  self="$(sed -n 's/^time: *\([0-9]*\) ms.*/\1/p' "$out" | head -1)"
  err="$(sed -n 's/^diagnostics: *\([0-9]*\) error.*/\1/p' "$out" | head -1)"
  echo "$arm $((end - start)) ${self:-NA} ${err:-NA}" >> "$OUT/samples.txt"
}

: > "$OUT/samples.txt"
for i in $(seq 1 "$PAIRS"); do
  # rotate the within-pair order so drift cannot align with an arm
  if [ $((i % 2)) -eq 1 ]; then
    run_one stale "$CP_STALE" "stale$i"; run_one fresh "$CP_FRESH" "fresh$i"
  else
    run_one fresh "$CP_FRESH" "fresh$i"; run_one stale "$CP_STALE" "stale$i"
  fi
done

# --- BEHAVIOUR, which matters more than the timing -------------------------
for a in stale fresh; do
  cp_v="$CP_STALE"; [ "$a" = fresh ] && cp_v="$CP_FRESH"
  java -Xmx4g -cp "$MAIN:$cp_v" com.xemantic.typescript.compiler.MainKt \
       --noEmit --listAll "$PROJ" > "$OUT/listall-$a.txt" 2>&1
  grep 'error TS' "$OUT/listall-$a.txt" | sort > "$OUT/digest-$a.txt"
done

date > "$OUT/done"

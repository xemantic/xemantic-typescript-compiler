#!/usr/bin/env bash
# (WARM.23) round 896 — price round 894's map-key candidates BEFORE building any
# of them (CLAUDE.md's first law).
#
# Three independent instruments, one binary:
#   1. --mapCensus            the three in-progress sentinel sets' populations and
#                             MAX LIVE SIZE (round 890's law), plus the
#                             perFileScope file-PATH probe count;
#   2. --perFileScopeAmp N    round 759's amplification of ONE probe — two positive
#                             N cancel the timestamp pair algebraically;
#   3. --flowMapReplay N      each file's real nodeToFlow key sequence replayed into
#                             a fresh mutableMapOf and a fresh LongKeyMap, ABBA.
#
# Every run is its own JVM: round 867's law (two arms sharing one compiled method
# are not independent arms) applies to the amplification pair above all.
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round896
mkdir -p "$OUT"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
CP="$MAIN:$DEPS"
# round 853: a gate reading a class DIRECTORY needs a positive control that the
# code under test is in it.
[[ -f "$MAIN/com/xemantic/typescript/compiler/MapCensus.class" ]] || {
  echo "REFUSED: class dir predates round 896 (no MapCensus)"; exit 1; }

PROJ=build/bench/tsc-project-637d5746
[[ -d "$PROJ" ]] || { echo "REFUSED: no compiler profile"; exit 1; }

run() {
  local tag="$1"; shift
  echo "=== $tag ==="
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
    --noEmit "$@" "$PROJ" > "$OUT/$tag.txt" 2>&1
  grep -a "WARM.23" -A 20 "$OUT/$tag.txt" | head -30
  grep -acE "error TS" "$OUT/$tag.txt" | sed 's/^/  diagnostics: /'
}

run counters   --mapCensus
run amp4       --perFileScopeAmp 4
run amp16      --perFileScopeAmp 16
run ampempty   --perFileScopeAmp -1
run replay8    --flowMapReplay 8

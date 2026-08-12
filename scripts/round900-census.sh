#!/usr/bin/env bash
# (WARM.27) round 900 — the POPULATIONS behind round 899 § 33.8's candidates (1)
# and (5), taken before a line of fix (CLAUDE.md's first law, and round 898's
# admission test: divide the JFR ms by the operation count and ask whether the
# implied per-operation cost is physically possible).
#
#   (1) resolveImportedSymbolGeneral — 24.3 ms JFR, of which 21.9 is
#       `HashMap.containsKey` on an Int-keyed (therefore boxed) cache probed
#       TWICE per hit. Real only at ~0.7-1.5 M probes/rebuild; `--mapCensus`
#       prints calls / top-level / hits, i.e. the probe population and the
#       removable half of it.
#   (5) SuffixNameSet.materialize — 21.6 ms JFR, insert 100%. `HashSet.add` with
#       a cached String hash is ~20-40 ns, so the row needs ~0.5-1.0 M adds;
#       `--frontEnd` prints FlowScan's always-counted created/materialized pair,
#       now with the NAMES INSERTED that decides it.
#
# Both counters are deterministic, so one run of each answers; the second run of
# each is a reproducibility control, not a sample. Every run is its own JVM
# (round 867).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round900
mkdir -p "$OUT"

MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
CP="$MAIN:$DEPS"
# round 853: a gate reading a class DIRECTORY needs a positive control that the
# code under test is in it. `MapCensus` alone no longer discriminates (round 896
# put it there), so grep the round's own counter out of the class file.
[[ -f "$MAIN/com/xemantic/typescript/compiler/MapCensus.class" ]] || {
  echo "REFUSED: class dir predates round 896 (no MapCensus)"; exit 1; }
grep -qa "risgTopLevel" "$MAIN/com/xemantic/typescript/compiler/MapCensus.class" || {
  echo "REFUSED: class dir predates round 900 (no risgTopLevel)"; exit 1; }
grep -qa "setEntries" "$MAIN/com/xemantic/typescript/compiler/FlowScan.class" || {
  echo "REFUSED: class dir predates round 900 (no FlowScan.setEntries)"; exit 1; }

PROJ=build/bench/tsc-project-637d5746
[[ -d "$PROJ" ]] || { echo "REFUSED: no compiler profile"; exit 1; }

run() {
  local tag="$1"; shift
  echo "=== $tag ==="
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
    --noEmit "$@" "$PROJ" > "$OUT/$tag.txt" 2>&1
  grep -a "WARM.27\|suffix sets" "$OUT/$tag.txt"
  grep -ac "error TS" "$OUT/$tag.txt" | sed 's/^/  diagnostics: /'
}

run risg1  --mapCensus
run risg2  --mapCensus
run sfx1   --frontEnd
run sfx2   --frontEnd

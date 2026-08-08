#!/usr/bin/env bash
# round-838 scratch: capture all 8 dashboard profiles with --noEmit --listAll.
# Usage: scripts/grid838.sh <outdir>
#
# ROUND-841 WARNING — THE md5 THIS PRINTS MATCHES NOTHING IN THE ROUND NOTES.
# A digest is a property of (output x RECIPE). This script's recipe strips the
# absolute project prefix (the `sed` below), which no round has ever recorded a
# digest under: on an identical capture it prints 84bbe7f0... where the value
# written all over PLAN-PHASE-5.md (`59d930db...`, rounds 826/836-840) is the
# SAME 46 lines WITHOUT the strip, and rounds 828-835's `4caacf24...` is the
# whole 54-line stdout minus `[`/`time:` lines. Comparing across recipes reads
# as a regression that is not there. Use added/removed against a rebuilt
# before-arm for cross-round work; the md5 is for the within-run, many-runs
# check. Full derivation: docs/perf/aot-cache.md section 11.
# (The strip is the BEST of the three recipes - the only portable one - so
# re-baseline onto it deliberately rather than deleting the sed.)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$1"
mkdir -p "$OUT"
CP="$(cat "$ROOT/build/bench/xtsc-classpath.txt")"
# ROUND-852 GUARD (the round-MOD.3 trap, and it had already fired): the file
# found in the tree was a PRE-SPLIT one — its only class directory was the root
# project's `build/classes/kotlin/jvm/main`, a stale leftover with 582 classes
# against the core module's 649, so every capture this script produced ran a
# compiler from before the module split with NO error and NO tell. `ab-*.sh`
# refuse such a file by grepping it for the module name; this one did not.
# Regenerate with the `xtscPrintJvmRuntimeClasspath` init script (see
# scripts/cost_gate.py `resolve_classpath`), prepending the CORE module's
# classes dir.
case "$CP" in
  *xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main*) ;;
  *) echo "error: build/bench/xtsc-classpath.txt is stale (pre-module-split) —" \
          "it names no core classes dir; regenerate it" >&2; exit 1 ;;
esac
# A capture is only comparable if it is COMPLETE: a truncated one ("... and N
# more error(s)") made round 811 read 0 added / 16 removed on all eight profiles
# from a byte-identical compiler, and an empty one looks like a clean profile.
FAIL=0
declare -A DIRS=(
  [compiler]="$ROOT/build/bench/tsc-project-637d5746"
  [tsc-cli]="$ROOT/build/bench/tsc-tsc-637d5746"
  [jsTyping]="$ROOT/build/bench/tsc-jsTyping-637d5746"
  [deprecatedCompat]="$ROOT/build/bench/tsc-deprecatedCompat-637d5746"
  [typingsInstallerCore]="$ROOT/build/bench/tsc-typingsInstallerCore-637d5746"
  [services]="$ROOT/build/bench/tsc-services-637d5746"
  [server]="$ROOT/build/bench/tsc-server-637d5746"
  [harness]="$ROOT/build/bench/tsc-harness-637d5746"
)
for P in compiler tsc-cli jsTyping deprecatedCompat typingsInstallerCore services server harness; do
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --listAll "${DIRS[$P]}" > "$OUT/$P.raw" 2>&1 || true
  grep -a 'error TS' "$OUT/$P.raw" | sed "s#${DIRS[$P]}/##g" | sort > "$OUT/$P.txt"
  n=$(wc -l < "$OUT/$P.txt")
  trunc=$(grep -ac 'more error(s)' "$OUT/$P.raw" || true)
  md5=$(md5sum "$OUT/$P.txt" | cut -d' ' -f1)
  echo "$P count=$n trunc=$trunc md5=$md5"
  if [[ "$trunc" != "0" ]]; then echo "  REFUSED: capture truncated" >&2; FAIL=1; fi
  if [[ "$n" == "0" ]]; then echo "  REFUSED: capture empty" >&2; FAIL=1; fi
done
exit "$FAIL"

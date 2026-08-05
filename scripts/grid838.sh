#!/usr/bin/env bash
# round-838 scratch: capture all 8 dashboard profiles with --noEmit --listAll.
# Usage: scripts/grid838.sh <outdir>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$1"
mkdir -p "$OUT"
CP="$(cat "$ROOT/build/bench/xtsc-classpath.txt")"
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
done

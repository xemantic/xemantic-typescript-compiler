#!/usr/bin/env bash
# (CHK.45) capture the 8-profile --listAll grid into a named directory.
# usage: scripts/chk45-capture.sh <outdir-name>
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT="build/bench/chk45/$1"
mkdir -p "$OUT"
MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
CP="$MAIN:$DEPS"
[[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: no MainKt"; exit 1; }
[[ -f "$MAIN/com/xemantic/typescript/compiler/SourceScanFilter.class" ]] || { echo "REFUSED: stale class dir"; exit 1; }
shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do
  [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d")
done
if [[ "${#profiles[@]}" -lt 8 ]]; then echo "REFUSED: only ${#profiles[@]} profile(s)"; exit 1; fi
status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --listAll "$proj" > "$OUT/$name.raw" 2>&1
  grep -a 'error TS' "$OUT/$name.raw" | sed "s#$proj/##" | sort > "$OUT/$name.txt"
  if grep -qa 'more error(s)' "$OUT/$name.raw"; then echo "REFUSED $name: truncated"; status=1; fi
  if [[ ! -s "$OUT/$name.txt" ]]; then echo "REFUSED $name: empty"; status=1; fi
  echo "$name: $(wc -l < "$OUT/$name.txt") diagnostics"
done
echo "capture exit=$status -> $OUT"
exit $status

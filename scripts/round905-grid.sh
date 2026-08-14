#!/usr/bin/env bash
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
OUT=build/bench/round905-grid; mkdir -p "$OUT"
MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
CP="$MAIN:$DEPS"
[[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: no MainKt"; exit 1; }
[[ -f "$MAIN/com/xemantic/typescript/compiler/IterCensus.class" ]] || { echo "REFUSED: class dir predates round 905"; exit 1; }
n=0
for d in build/bench/tsc-*/; do
  [[ -f "$d/tsconfig.json" ]] || continue
  n=$((n+1)); name=$(basename "$d")
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --listAll "$d" > "$OUT/$name.txt" 2>&1
  if grep -aq "and .* more error" "$OUT/$name.txt"; then echo "REFUSED $name: truncated capture"; exit 1; fi
  [[ -s "$OUT/$name.txt" ]] || { echo "REFUSED $name: empty capture"; exit 1; }
  printf "%-42s errors=%-6s exceptions=%s\n" "$name" \
    "$(grep -ac 'error TS' "$OUT/$name.txt")" \
    "$(grep -ac 'Exception\|error: ' "$OUT/$name.txt")"
done
echo "profiles captured: $n"
[[ $n -eq 8 ]] || { echo "REFUSED: expected 8 profiles, found $n"; exit 1; }
touch "$OUT/done"

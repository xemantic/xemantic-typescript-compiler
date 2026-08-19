#!/usr/bin/env bash
# Round 945 — (CHK.21): the 8-profile `--listAll` capture for ONE arm.
#
# Usage: scripts/round945-grid.sh before|after <classesDir>
#
# Unlike round 944's twin this takes the class dir as an ARGUMENT, because both arms of
# this round were built once and snapshotted (/tmp/r945/cls-{before,after}); the sha256 of
# the three sources under test is recorded per arm either way, so a capture is still tied
# to the tree that produced it.
#
# Profiles are enumerated by the presence of a `tsconfig.json` and the run REFUSES below 8
# (CLAUDE.md: every committed "8-profile grid" before round 895 was a ONE-profile grid).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
ARM="${1:?usage: round945-grid.sh before|after <classesDir>}"
MAIN="${2:?usage: round945-grid.sh before|after <classesDir>}"

SRC=(xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
     xemantic-typescript-compiler-core/src/commonMain/kotlin/CompilerOptions.kt
     xemantic-typescript-compiler-core/src/commonMain/kotlin/RealLibs.kt)
OUT=build/bench/round945-grid
mkdir -p "$OUT"
sha256sum "${SRC[@]}" > "$OUT/sources.$ARM.sha256"
sha256sum "$MAIN/com/xemantic/typescript/compiler/CompilerOptions.class" >> "$OUT/sources.$ARM.sha256"
cat "$OUT/sources.$ARM.sha256"

shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d"); done
[[ "${#profiles[@]}" -ge 8 ]] || { echo "REFUSED: only ${#profiles[@]} profile(s)"; exit 1; }
echo "profiles: ${#profiles[@]}"

DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
CP="$MAIN:$DEPS"
[[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: no MainKt"; exit 1; }

status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
    --noEmit --listAll "$proj" > "$OUT/$name.$ARM.raw" 2>&1
  grep -a 'error TS' "$OUT/$name.$ARM.raw" | sort > "$OUT/$name.$ARM.txt"
  grep -qa 'more error(s)' "$OUT/$name.$ARM.raw" && { echo "REFUSED $name: truncated"; status=1; }
  [[ -s "$OUT/$name.$ARM.txt" ]] || { echo "REFUSED $name: empty"; status=1; }
done

if [[ "$ARM" == "after" ]]; then
  echo "=== 8-profile diff ==="
  for proj in "${profiles[@]}"; do
    name="$(basename "$proj")"
    [[ -s "$OUT/$name.before.txt" ]] || { echo "REFUSED $name: no before capture"; status=1; continue; }
    added=$(comm -13 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
    removed=$(comm -23 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
    echo "$name: added=$added removed=$removed total=$(wc -l < "$OUT/$name.after.txt")"
    comm -3 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | head -20
  done
fi
exit $status

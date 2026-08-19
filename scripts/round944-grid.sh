#!/usr/bin/env bash
# Round 944 — the 8-profile `--listAll` capture for ONE arm, plus (for the after arm) the
# diff against the before arm and the PRISTINE 630-fixture sweep.
#
# Usage: scripts/round944-grid.sh before|after
#
# Both arms are captured from a REBUILT binary in this round (no reuse), and each arm
# records the sha256 of the three sources the round touches, so a capture can be tied to
# the exact tree that produced it (round 943's provenance rule, generalised to a
# multi-file change).
#
# Profiles are enumerated by the presence of a `tsconfig.json` and the run REFUSES below 8
# (CLAUDE.md: every committed "8-profile grid" before round 895 was a ONE-profile grid).
set -uo pipefail
cd /home/claude/git/xemantic-typescript-compiler
ARM="${1:?usage: round944-grid.sh before|after}"

SRC=(xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
     xemantic-typescript-compiler-core/src/commonMain/kotlin/CompilerOptions.kt
     xemantic-typescript-compiler-core/src/commonMain/kotlin/RealLibs.kt)
OUT=build/bench/round944-grid
MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
mkdir -p "$OUT"; rm -f "$OUT/DONE.$ARM"

sha256sum "${SRC[@]}" | tee "$OUT/sources.$ARM.sha256"

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
  python3 scripts/pristine_sweep.py --classes "$MAIN" \
     --out "$OUT/sweep.after.json" --work build/bench/round944-sweep-after \
     > "$OUT/sweep.after.txt" 2>&1
  echo "sweep(after): $(tail -1 "$OUT/sweep.after.txt")"

  echo "=== 8-profile diff ==="
  for proj in "${profiles[@]}"; do
    name="$(basename "$proj")"
    [[ -s "$OUT/$name.before.txt" ]] || { echo "REFUSED $name: no before capture"; status=1; continue; }
    added=$(comm -13 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
    removed=$(comm -23 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
    echo "$name: added=$added removed=$removed total=$(wc -l < "$OUT/$name.after.txt")"
    comm -3 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | head -20
  done

  python3 scripts/round944_sweep_diff.py build/bench/round944-sweep-before.json "$OUT/sweep.after.json"
fi
echo "$status" > "$OUT/DONE.$ARM"
exit $status

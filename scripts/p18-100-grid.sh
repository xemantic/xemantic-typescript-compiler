#!/usr/bin/env bash
# (LEGACY.0b) step 15 — the 8-profile BEFORE/AFTER grid, in BOTH modes.
#
# EXPECT TWO CONTROLS.  The change is a MODULE-TRANSFORM one and `--noEmit` skips the
# transformer entirely (round 738's `skipEmitOutputs`), so the `--listAll` half is a control
# by construction.  The EMIT half is the one that could move — but tsc's own 78 compiler
# sources contain no exported destructuring declaration at all (the census over
# `tests/cases` found 21 such files in the whole TypeScript corpus and none of tsc's own),
# so `diff -r` is expected to read identical too.  COUNT it rather than assume it: the
# script prints how many emitted files differ, and any difference is a finding.
#
# The corpus EMIT channel of `scripts/corpus-screen.sh` is the real gate for this family.
#
# REFUSES when the two arms' Transformer.class is byte-identical (an accidental
# self-comparison prints a clean bill of health — CLAUDE.md rounds 853/946), when a capture
# is empty or TRUNCATED, and below 8 profiles (the pre-895 `tsc-project-*` glob matched one).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18-100-orch; mkdir -p "$OUT/grid"
BEFORE="$OUT/classes-before"
AFTER="$OUT/classes-after"
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
for d in "$BEFORE" "$AFTER"; do
  [[ -f "$d/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: $d holds no MainKt"; exit 1; }
done
# (P18.100) may touch the Checker, Parser, Scanner or TypeScriptCompiler; refuse only when NONE moved.
same=1
for cls in Checker Parser Scanner TypeScriptCompiler; do
  a="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/$cls.class" | cut -d' ' -f1)"
  b="$(sha256sum "$AFTER/com/xemantic/typescript/compiler/$cls.class" | cut -d' ' -f1)"
  echo "before $cls.class $a"
  echo "after  $cls.class $b"
  [[ "$a" != "$b" ]] && same=0
done
[[ "$same" == 1 ]] && { echo "REFUSED: the two arms' Checker/Parser/Scanner/TypeScriptCompiler classes are all byte-identical"; exit 1; }
shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d"); done
[[ "${#profiles[@]}" -lt 8 ]] && { echo "REFUSED: only ${#profiles[@]} profile(s)"; exit 1; }
echo "profiles: ${#profiles[@]}"
status=0
for proj in "${profiles[@]}"; do
  name="$(basename "$proj")"
  for arm in before after; do
    dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
    java -Xmx4g -cp "$dir:$DEPS" com.xemantic.typescript.compiler.MainKt \
      --noEmit --listAll "$proj" > "$OUT/grid/$name.$arm.raw" 2>&1
    [[ -s "$OUT/grid/$name.$arm.raw" ]] || { echo "$name/$arm: REFUSED — empty capture"; status=1; continue; }
    grep -q "and [0-9]* more error" "$OUT/grid/$name.$arm.raw" && { echo "$name/$arm: REFUSED — truncated"; status=1; continue; }
    grep 'error TS' "$OUT/grid/$name.$arm.raw" | sed "s|$proj/||" | sort > "$OUT/grid/$name.$arm.txt"
  done
  added=$(comm -13 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | wc -l)
  removed=$(comm -23 "$OUT/grid/$name.before.txt" "$OUT/grid/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/grid/$name.after.txt")
  echo "$name: rows=$n added=$added removed=$removed"
  [[ "$added" -ne 0 || "$removed" -ne 0 ]] && status=1
done
# ── EMIT mode over the compiler profile: the one instrument here that runs the transformer.
proj="$(ls -d build/bench/tsc-project-* | head -1)"
for arm in before after; do
  dir="$BEFORE"; [[ "$arm" == after ]] && dir="$AFTER"
  rm -rf "$OUT/emit-$arm"
  java -Xmx4g -cp "$dir:$DEPS" com.xemantic.typescript.compiler.MainKt \
    --outDir "$OUT/emit-$arm" "$proj" > "$OUT/emit-$arm.log" 2>&1
done
nb=$(find "$OUT/emit-before" -name '*.js' | wc -l)
na=$(find "$OUT/emit-after" -name '*.js' | wc -l)
echo "emit: before=$nb file(s) after=$na file(s)"
[[ "$nb" -lt 70 || "$na" -lt 70 ]] && { echo "REFUSED: an emit arm produced almost nothing"; status=1; }
d=$(diff -rq "$OUT/emit-before" "$OUT/emit-after" | wc -l)
echo "emit: $d differing file(s)"
diff -rq "$OUT/emit-before" "$OUT/emit-after" | head -20
[[ "$d" -ne 0 ]] && status=1
echo "grid status=$status"
exit $status

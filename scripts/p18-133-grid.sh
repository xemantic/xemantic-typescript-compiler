#!/usr/bin/env bash
# (P18.133) — `simulatedVersion`'s default moves "6.0" -> "7.0", so every TS7-removed option
# reports tsgo's TS5102/TS5108 "has been removed" instead of TypeScript 6's TS5101/TS5107
# "deprecated, will stop functioning" ladder — the 8-profile BEFORE/AFTER grid, BOTH modes.
#
# THE GRID IS A **CONTROL** THIS ROUND, AND THE COUNT IS WHY: measured 2026-09-17, all eight
# profiles set `target: es2020`, `module: NodeNext`, `moduleResolution: NodeNext` and
# `alwaysStrict: true` and NOTHING else from the thirteen flipping families — no `baseUrl`, no
# `outFile`, no `downlevelIteration`, no interop `false`. So no profile can emit ANY of the four
# codes and none can move. A green grid says the flip did not disturb a project that uses no
# removed option; the CORPUS (two pending rows close) and the round's own pins are the gate.
#
# REFUSES when the two arms' Checker, TypeScriptCompiler and TypeScriptCompilerKt classes are ALL
# byte-identical (CLAUDE.md rounds 853/946). The `Kt` twin is listed deliberately: a top-level
# private function of `TypeScriptCompiler.kt` compiles into `TypeScriptCompilerKt.class`, so a
# one-line default change may land in either and checking one alone can read it as unbuilt.
# `Checker.class` may legitimately NOT move this round — the edit is in the options path — which
# is why the refusal is "all three identical", never "Checker identical".
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/simver-orch; mkdir -p "$OUT/grid"
BEFORE="$OUT/classes-before"
AFTER="$OUT/classes-after"
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
for d in "$BEFORE" "$AFTER"; do
  [[ -f "$d/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: $d holds no MainKt"; exit 1; }
done
# (P18.133) touches the options path; refuse only when NONE of the three moved.
same=1
for cls in Checker TypeScriptCompiler TypeScriptCompilerKt; do
  a="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/$cls.class" | cut -d' ' -f1)"
  b="$(sha256sum "$AFTER/com/xemantic/typescript/compiler/$cls.class" | cut -d' ' -f1)"
  echo "before $cls.class $a"
  echo "after  $cls.class $b"
  [[ "$a" != "$b" ]] && same=0
done
[[ "$same" == 1 ]] && { echo "REFUSED: the two arms' Checker/TypeScriptCompiler/TypeScriptCompilerKt classes are all byte-identical"; exit 1; }
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

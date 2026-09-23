#!/usr/bin/env bash
# (P18.179) the 8-profile BEFORE/AFTER binary grid for (CHK.143) — `instanceof` on the
# POSITIVE branch must produce tsgo's INTERSECTION where we currently REPLACE.
#
# THIS GRID IS A **CONTROL**, and the reason is the shape of the profiles' own code: their
# `instanceof` right-hand sides are overwhelmingly DERIVED-shape receivers (`Array` 27,
# `operator` 24, `Map` 16, `__await` 16, `OperationCanceledException` 12, then the compiler's
# own classes), i.e. exactly the cells where ours and tsgo already AGREE.  The divergence
# needs a reference and a candidate that are UNRELATED, which tsc's own sources essentially
# never write.  The grid is green today and a green run here is evidence that nothing else
# moved.
#
# THE REAL GATE IS THE CORPUS: 22 conformance/compiler cases mention `instanceof` and carry
# an `.errors.txt` baseline, and ALL 22 ARE ACTIVE.  Both library probes are structurally
# blind — `grep -c ' instanceof '` is **0** in `build/bench/inc50-scratch-marked` and in
# `build/bench/inc50-scratch-cronstrue`.
#
# Expect the change to REMOVE the ours-only TS2339 family and to ADD rows where the union arm
# currently washes to `never` (tsgo reports there too, so those are corrections that
# nonetheless move baselines).
#
# BEFORE is the round's snapshotted pre-change class dir, AFTER is the built tree.
# REFUSES when the arms' Checker.class are byte-identical (an accidental self-comparison
# prints added=0 removed=0 on all eight and reads exactly like a clean bill of health —
# CLAUDE.md rounds 853/946), when a capture is empty or TRUNCATED (`and N more error(s)` —
# round 811), and below 8 profiles (the pre-895 `tsc-project-*` glob matched exactly ONE).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18179/grid; mkdir -p "$OUT"
BEFORE=build/bench/p18179/classes-before
AFTER=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
DEPS="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
for d in "$BEFORE" "$AFTER"; do
  [[ -f "$d/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: $d holds no MainKt"; exit 1; }
done
a="$(sha256sum "$BEFORE/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
b="$(sha256sum "$AFTER/com/xemantic/typescript/compiler/Checker.class" | cut -d' ' -f1)"
[[ "$a" == "$b" ]] && { echo "REFUSED: the two arms' Checker.class are byte-identical"; exit 1; }
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
      --noEmit --listAll "$proj" > "$OUT/$name.$arm.raw" 2>&1
    [[ -s "$OUT/$name.$arm.raw" ]] || { echo "$name/$arm: REFUSED — empty capture"; status=1; continue; }
    grep -q "and [0-9]* more error" "$OUT/$name.$arm.raw" && { echo "$name/$arm: REFUSED — truncated"; status=1; continue; }
    grep 'error TS' "$OUT/$name.$arm.raw" | sed "s|$proj/||" | sort > "$OUT/$name.$arm.txt"
  done
  added=$(comm -13 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  removed=$(comm -23 "$OUT/$name.before.txt" "$OUT/$name.after.txt" | wc -l)
  n=$(wc -l < "$OUT/$name.after.txt")
  echo "$name: rows=$n added=$added removed=$removed"
  [[ "$added" -ne 0 || "$removed" -ne 0 ]] && status=1
done
echo "grid status=$status"
exit $status

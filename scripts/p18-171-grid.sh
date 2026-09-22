#!/usr/bin/env bash
# (P18.171) the 8-profile BEFORE/AFTER binary grid for (CHK.142)(b) — the discriminated-union
# SOURCE-SPLITTING leg in the union-target arm of the relation.
#
# WHAT THIS GRID CAN AND CANNOT SEE, BY COUNT.  A census of the previous round's captures
# (`build/bench/p18170/grid/*.after.txt`) says the eight profiles carry **ZERO** TS2322 and
# TS2345 rows between them — their whole diagnostic population is TS2591 x367 (the absent
# `@types/node`), TS2304 x24, TS2584 x13, TS2503 x6, TS7006 x3, TS2339 x2, TS2593 x1.  So:
#
#   * as a CONTROL for the acceptance itself it is vacuous — an acceptance-only relation leg
#     can only ever DELETE an assignability row, and there is none here to delete;
#   * as a GATE for the SIDE EFFECT it is real and mandatory.  CLAUDE.md records that an
#     "acceptance-only" leg CAN still add a diagnostic, because the relation feeds NARROWING:
#     a newly-related type gets SUBTRACTED and the reference can collapse to `never`, which
#     reports somewhere else entirely.  That is the row this grid exists to catch.
#
# The mechanism's own reference test (`assignmentCompatWithDiscriminatedUnion`, the fixture
# tsgo's `relater.go:3997` comment cites) has NO case file in this sparse clone and zero
# mentions in the generated tree, so the corpus is close to blind here too — the round's pins
# and that reconstructed fixture are the real gate, not this grid and not the screen.
#
# BEFORE is the round's snapshotted pre-change class dir, AFTER is the built tree.
# REFUSES when the arms' Checker.class are byte-identical (an accidental self-comparison
# prints added=0 removed=0 on all eight and reads exactly like a clean bill of health —
# CLAUDE.md rounds 853/946), when a capture is empty or TRUNCATED (`and N more error(s)` —
# round 811), and below 8 profiles (the pre-895 `tsc-project-*` glob matched exactly ONE).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18171/grid; mkdir -p "$OUT"
BEFORE=build/bench/p18171/classes-before
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

#!/usr/bin/env bash
# (P18.178) the 8-profile BEFORE/AFTER binary grid for (CHK.148) — a callee type parameter
# inferred from the CONTEXTUAL RETURN position, instead of falling back to `unknown`.
#
# THIS GRID IS A CONTROL FOR THE *REMOVE* DIRECTION AND A GATE FOR THE *ADD* ONE, and the
# count says which is which.  The eight profiles carry **ZERO TS2322 and ZERO TS2345** rows
# between them (their whole population is TS2591 x367, TS2304 x24, TS2584 x13, TS2503 x6,
# TS7006 x3, TS2339 x2, TS2593 x1), so the ours-only TS2322 family this change REMOVES cannot
# be seen here at all.
#
# But it is NOT purely a control, and the round must watch two things:
#   * **harness carries 3 TS7006 rows**, and a contextual-parameter change can move or delete
#     them;
#   * the change is TWO-DIRECTIONAL.  A member READ on a parameter that is `unknown` today is
#     SILENT; once the parameter is inferred to a real type those reads start being checked,
#     so new TS2339/TS2551/TS2345 can appear — measured on a cell where tsgo reports
#     `TS2551: Property 'toFixed' does not exist on type 'string'` and we are silent.
# tsc's own sources are CLEAN under tsgo 7.0.2, so any row this grid ADDS is a false positive
# by definition and must be adjudicated against tsgo, never explained.
#
# BEFORE is the round's snapshotted pre-change class dir, AFTER is the built tree.
# REFUSES when the arms' Checker.class are byte-identical (an accidental self-comparison
# prints added=0 removed=0 on all eight and reads exactly like a clean bill of health —
# CLAUDE.md rounds 853/946), when a capture is empty or TRUNCATED (`and N more error(s)` —
# round 811), and below 8 profiles (the pre-895 `tsc-project-*` glob matched exactly ONE).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18178/grid; mkdir -p "$OUT"
BEFORE=build/bench/p18178/classes-before
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

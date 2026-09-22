#!/usr/bin/env bash
# (P18.170) the 8-profile BEFORE/AFTER binary grid for (CHK.144) — `inferReturnTypeFromBody`
# gaining an `Identifier` arm, so a BLOCK-bodied unannotated function whose `return` is a
# bare identifier stops inferring `any`.
#
# THIS GRID IS A **GATE**, AND THE LARGEST ONE THIS FAMILY HAS HAD — the census says so.
# `grep -rhoE '^\s*return [A-Za-z_$][A-Za-z0-9_$]*;\s*$'` over each profile's `.ts` sources
# counts **4,109-5,671** bare-identifier returns per profile (4,109 on the smallest arm,
# 5,671 on harness).  Every one of those is a site the change can reach, and tsc's own
# sources are CLEAN under tsgo 7.0.2, so **every row this grid adds is a false positive by
# definition** and must be adjudicated against tsgo rather than explained.
#
# That direction is the whole risk: this change REMOVES a suppression, so unlike the two
# rounds before it, it ADDS diagnostics.  A green grid here is a real result; a red one is
# the round's answer, not an inconvenience.
#
# BEFORE is the round's snapshotted pre-change class dir, AFTER is the built tree.
# REFUSES when the arms' Checker.class are byte-identical (an accidental self-comparison
# prints added=0 removed=0 on all eight and reads exactly like a clean bill of health —
# CLAUDE.md rounds 853/946), when a capture is empty or TRUNCATED (`and N more error(s)` —
# round 811), and below 8 profiles (the pre-895 `tsc-project-*` glob matched exactly ONE
# directory, so every "8-profile grid" in this repo was a one-profile grid until round 895).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/p18170/grid; mkdir -p "$OUT"
BEFORE=build/bench/p18170/classes-before
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

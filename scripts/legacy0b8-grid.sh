#!/usr/bin/env bash
# (LEGACY.0b) step 8 — the 8-profile BEFORE/AFTER binary grid for the F2 duplicate-identifier
# family (TS2300 at every declaration, the method gate on TS2717, the TS6200 removal, and the
# one-name-per-group rendering).
#
# EXPECT A CONTROL, NOT A GATE — and COUNT it rather than assuming ((CHK.124)). tsc's own
# sources compile cleanly in the shapes this round changes: a duplicate class or interface
# member is a hard error nobody checks in, and the cross-file 8-name conflict the TS6200
# collapse served does not occur either. So the per-profile TS2300/TS2717/TS6200 counts below
# are the receipt that the changed path is not merely quiet but UNREACHABLE here; the gates
# that did the work are the 3,021-subtest active-corpus screen (`scripts/corpus-screen.sh`),
# the full suite, and 22 hand-written pins measured against tsgo 7.0.2.
#
# REFUSES below 8 profiles (the pre-895 `tsc-project-*` glob matched exactly ONE), on an empty
# or TRUNCATED capture (`and N more error(s)` — round 811), and when the two arms' Checker.class
# are byte-identical (an accidental self-comparison prints added=0 removed=0 everywhere and
# reads exactly like a clean bill of health — CLAUDE.md 853/946).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/legacy0b8/grid; mkdir -p "$OUT"
BEFORE=build/bench/legacy0b8/classes-before
AFTER=build/bench/legacy0b8/classes-after
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
  c() { grep -c "error $2" "$OUT/$name.$1.txt"; }
  echo "$name: rows=$n added=$added removed=$removed | TS2300 b/a=$(c before TS2300)/$(c after TS2300) TS2717 b/a=$(c before TS2717)/$(c after TS2717) TS6200 b/a=$(c before TS6200)/$(c after TS6200)"
  [[ "$added" -ne 0 || "$removed" -ne 0 ]] && status=1
done
echo "grid status=$status"
exit $status

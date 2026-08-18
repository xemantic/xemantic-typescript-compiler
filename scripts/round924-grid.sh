#!/usr/bin/env bash
# (BUG.4) round 924 — the 8-profile `--listAll` output grid.
#
# TWO BINARIES, not two flags: the change has no runtime switch, so the arms are
# HEAD's `Checker.kt` and the round's. The claim under test is that (BUG.4) is
# invisible to a compile — `typeCaptureReportedType` is reachable only from
# `typeCaptureRecord`, itself reached only from `typeCaptureVisit`, which returns
# at its first line when no capture was
# requested, which is every production build — so the expected result is
# `added=0 removed=0` on all eight, and this grid is the CONTROL that says so
# rather than a claim that says so.
#
# Profiles are enumerated by the presence of a tsconfig.json and the run REFUSES
# below 8: `build/bench/tsc-project-*` is the COMPILER profile alone, and every
# committed "8-profile grid" before round 895 was silently a one-profile grid.
# The differ refuses an empty capture (round 804) and one containing
# `and N more error(s)` (round 811, a truncated `--listAll`).
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=build/bench/round924-grid
mkdir -p "$OUT"
CHK=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
MAIN=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main

shopt -s nullglob
profiles=()
for d in build/bench/tsc-*; do
  [[ -d "$d" && -f "$d/tsconfig.json" ]] && profiles+=("$d")
done
[[ "${#profiles[@]}" -ge 8 ]] || { echo "REFUSED: only ${#profiles[@]} profile(s)"; exit 1; }
echo "profiles: ${#profiles[@]}"

capture() {  # capture <arm>
  local arm="$1"
  local deps; deps="$(scripts/lib/dep-classpath.sh --print)" || { echo "DEPS FAILED"; exit 1; }
  [[ -f "$MAIN/com/xemantic/typescript/compiler/MainKt.class" ]] || { echo "REFUSED: no MainKt"; exit 1; }
  for d in "${profiles[@]}"; do
    local name; name="$(basename "$d")"
    java -cp "$MAIN:$deps" com.xemantic.typescript.compiler.MainKt \
        --noEmit --listAll "$d" > "$OUT/$name.$arm.txt" 2>&1
    grep -a 'error TS' "$OUT/$name.$arm.txt" | sed 's#.*/src/#src/#' | LC_ALL=C sort \
        > "$OUT/$name.$arm.diag"
  done
}

echo "=== arm A: HEAD (pre-fix) ==="
cp "$CHK" "$OUT/Checker.round924.kt"
git show HEAD:"$CHK" > "$CHK"
./gradlew compileKotlinJvm > "$OUT/build.A.log" 2>&1 || { echo "BUILD A FAILED"; cp "$OUT/Checker.round924.kt" "$CHK"; exit 1; }
grep -aq 'BUILD SUCCESSFUL' "$OUT/build.A.log" || { echo "BUILD A NOT SUCCESSFUL"; cp "$OUT/Checker.round924.kt" "$CHK"; exit 1; }
capture head

echo "=== arm B: round 924 ==="
cp "$OUT/Checker.round924.kt" "$CHK"
./gradlew compileKotlinJvm > "$OUT/build.B.log" 2>&1 || { echo "BUILD B FAILED"; exit 1; }
grep -aq 'BUILD SUCCESSFUL' "$OUT/build.B.log" || { echo "BUILD B NOT SUCCESSFUL"; exit 1; }
capture r924

status=0
for d in "${profiles[@]}"; do
  name="$(basename "$d")"
  for arm in head r924; do
    [[ -s "$OUT/$name.$arm.diag" ]] || { echo "REFUSED $name/$arm: EMPTY capture"; status=1; }
    if grep -aq 'and [0-9]* more error' "$OUT/$name.$arm.txt"; then
      echo "REFUSED $name/$arm: TRUNCATED --listAll"; status=1
    fi
  done
  added=$(comm -13 "$OUT/$name.head.diag" "$OUT/$name.r924.diag" | wc -l)
  removed=$(comm -23 "$OUT/$name.head.diag" "$OUT/$name.r924.diag" | wc -l)
  n=$(wc -l < "$OUT/$name.r924.diag")
  printf '%-40s diagnostics=%-6s added=%-4s removed=%-4s\n' "$name" "$n" "$added" "$removed"
  [[ "$added" -eq 0 && "$removed" -eq 0 ]] || status=1
done
[[ $status -eq 0 ]] && echo "GRID CLEAN — all 8 profiles identical" || echo "GRID DIRTY"
exit $status

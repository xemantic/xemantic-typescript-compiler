#!/usr/bin/env bash
# (WARM.28) round 901 — the ablation for the `lexLevelHasName` census.
#
# ONE MISTAKE AT A TIME (round 807): a combined ablation credits pins with
# discrimination they do not have. Each arm is dry-run for a real diff before it
# is trusted (round 855: `"${@:-A1 A2}"` expands its default as ONE word, so a
# driver that dispatched nothing prints a clean sweep), and the tree is
# COMMITTED before any of this runs (round 789 — the revert is `git checkout`,
# which also destroys uncommitted work in the same file).
#
# The arms, and what each is designed to catch:
#
#   A1  the EMPTY/REAL split collapsed — every probe classified as real. This is
#       the round's deciding distinction; a census without it prices 271,684
#       free operations at the 20-50 ns reference.
#   A2  the queried-scope set stops de-duplicating. Changes no answer anywhere —
#       round 900's A3 shape — so only a counter identity can see it.
#   A3  the `real` flag frozen false, so the refusable population reads zero. A
#       zero from a blind instrument is indistinguishable from a real negative
#       (round 849), which is exactly what this arm asserts a pin refuses.
#   A4  the amplifier's map arm dropped. The slope it produces is then the
#       filter's alone and the round's whole number is wrong, silently.
#   A5  a census hook hoisted OUT of its `MapCensus.on` guard — round 900's
#       ninety-nine-round defect, reproduced deliberately.
set -uo pipefail
cd "$(dirname "$0")/.."

CHK=xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt
CEN=xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt
LOG=build/bench/round901-ablate
mkdir -p "$LOG"

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(A1 A2 A3 A4 A5)

apply() {
  case "$1" in
    A1) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p).read()
old="            real = l.symbols.isNotEmpty()\n            if (real) MapCensus.lexSymProbe++ else MapCensus.lexSymEmpty++"
new="            real = true\n            MapCensus.lexSymProbe++"
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    A2) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p).read()
old="        if (!lexScopes.add(l)) return\n        lexScopesQueried++"
new="        lexScopes.add(l)\n        lexScopesQueried++"
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    A3) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p).read()
old="            real = l.symbols.isNotEmpty()"
new="            real = false"
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    A4) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p).read()
old="        if (lexAmpCalls and 1L == 0L) { lexAmpMap(l, name, r); lexAmpFilter(mask, name, r) }\n        else { lexAmpFilter(mask, name, r); lexAmpMap(l, name, r) }"
new="        lexAmpFilter(mask, name, r)"
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    A5) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/Checker.kt'
s=open(p).read()
old="        if (MapCensus.on) MapCensus.lexCalls++\n        val owner = l.owner"
new="        MapCensus.lexCalls++\n        val owner = l.owner"
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    *) echo "unknown arm $1"; return 1;;
  esac
}

for arm in "${ARMS[@]}"; do
  git checkout -- "$CHK" "$CEN"
  if ! apply "$arm"; then echo "$arm: APPLY FAILED"; continue; fi
  n=$(git diff --shortstat | tr -dc '0-9,' )
  if [[ -z "$(git diff --shortstat)" ]]; then
    echo "$arm: NO DIFF — the edit did not land, arm is DEAD"; continue
  fi
  echo "== $arm: $(git diff --shortstat)"
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*LexLevelProbeCensusTest*' \
      > "$LOG/$arm.log" 2>&1
  if ! grep -qa 'BUILD SUCCESSFUL\|BUILD FAILED' "$LOG/$arm.log"; then
    echo "$arm: BUILD DID NOT COMPLETE — see $LOG/$arm.log"; continue
  fi
  red=$(grep -ac 'FAILED$' "$LOG/$arm.log" || true)
  echo "-- $arm: $(grep -a 'tests completed' "$LOG/$arm.log" || echo 'compile error')"
  grep -a 'FAILED$' "$LOG/$arm.log" | sed 's/^/     /' | head -12
done

git checkout -- "$CHK" "$CEN"
echo "tree restored: $(git status --porcelain "$CHK" "$CEN" | wc -l) modified"

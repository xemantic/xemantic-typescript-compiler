#!/usr/bin/env bash
# (WARM.29) round 902 — the ablation for the two new amplifier arms and the
# probe-weighted size census.
#
# ONE MISTAKE AT A TIME (round 807): a combined ablation credits pins with
# discrimination they do not have.  Each arm is checked for a REAL diff before it
# is trusted (round 855), and the tree is COMMITTED before any of this runs
# (round 789 — the revert is `git checkout`, which also destroys uncommitted work
# in the same file).
#
# What every arm here has in common is round 900's A3 and round 901's A4: none of
# these mistakes changes an ANSWER.  The census emits no diagnostic and the
# amplifier decides nothing, so no output assertion anywhere can see any of them,
# and each arm is a test of whether a COUNTER IDENTITY can.
#
#   B1  the scan arm never runs.  With one shared sink this is invisible; the
#       split sinks are what make it a failure.
#   B2  the hybrid arm never runs — the arm carrying the round's actual decision.
#   B3  the scan stops one element short.  The only arm whose mistake is a WRONG
#       ANSWER rather than a missing measurement, and the reason the scan and
#       hybrid arms assert EQUALITY with the map where the filter could only
#       assert a superset.
#   B4  the array built from `symbols` PLUS `existing` — round 748's mistake,
#       which puts every INV.3 name back in play.
#   B5  the size histogram de-duplicated per scope, i.e. scope-weighted.  This is
#       precisely the population round 901 § 5 priced the successor from, injected
#       deliberately: it is the round's deciding distinction and it must not be
#       possible to make it silently.
#   B6  the probe size recorded as 1 instead of the level's length, so the
#       re-weighting reads flat.
set -uo pipefail
cd "$(dirname "$0")/.."

CEN=xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt
LOG=build/bench/round902-ablate
mkdir -p "$LOG"

ARMS=("$@")
[[ ${#ARMS[@]} -eq 0 ]] && ARMS=(B1 B2 B3 B4 B5 B6)

apply() {
  case "$1" in
    B1) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p).read()
old="    private fun lexAmpScan(l: LexicalScope, name: String, r: Int) {\n        val t0 = PassTiming.nowNanos()"
new="    private fun lexAmpScan(l: LexicalScope, name: String, r: Int) {\n        if (r > 0) return\n        val t0 = PassTiming.nowNanos()"
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    B2) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p).read()
old="    private fun lexAmpHybrid(l: LexicalScope, name: String, r: Int) {\n        val t0 = PassTiming.nowNanos()"
new="    private fun lexAmpHybrid(l: LexicalScope, name: String, r: Int) {\n        if (r > 0) return\n        val t0 = PassTiming.nowNanos()"
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    B3) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p).read()
old="                var j = 0\n                while (j < names.size) {\n                    if (names[j] == name) { seen++; break }\n                    j++\n                }\n            }\n            i++\n        }\n        lexAmpScanNanos"
new="                var j = 0\n                while (j < names.size - 1) {\n                    if (names[j] == name) { seen++; break }\n                    j++\n                }\n            }\n            i++\n        }\n        lexAmpScanNanos"
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    B4) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p).read()
old="        val names = l.censusNames ?: l.symbols.keys.toTypedArray().also { l.censusNames = it }"
new=("        val names = l.censusNames\n"
     "            ?: (l.symbols.keys + (l.existing?.keys ?: emptySet())).toTypedArray()\n"
     "                .also { l.censusNames = it }")
new="".join(new)
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    B5) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p).read()
old="        lexProbeSizeSum += names.size.toLong()\n        lexProbeSizeHistogram[if (names.size >= 9) 9 else names.size]++"
new="        if (lexScopes.contains(l)) {\n            lexProbeSizeSum += names.size.toLong()\n            lexProbeSizeHistogram[if (names.size >= 9) 9 else names.size]++\n        }"
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    B6) python3 - <<'EOF'
p='xemantic-typescript-compiler-core/src/commonMain/kotlin/MapCensus.kt'
s=open(p).read()
old="        lexProbeSizeSum += names.size.toLong()"
new="        lexProbeSizeSum += 1L"
assert s.count(old) == 1, s.count(old)
open(p,'w').write(s.replace(old, new))
EOF
       ;;
    *) echo "unknown arm $1"; return 1;;
  esac
}

for arm in "${ARMS[@]}"; do
  git checkout -- "$CEN"
  if ! apply "$arm"; then echo "$arm: APPLY FAILED"; continue; fi
  if [[ -z "$(git diff --shortstat)" ]]; then
    echo "$arm: NO DIFF — the edit did not land, arm is DEAD"; continue
  fi
  echo "== $arm: $(git diff --shortstat)"
  ./gradlew :xemantic-typescript-compiler-core:jvmTest --tests '*LexLevelProbeCensusTest*' \
      > "$LOG/$arm.log" 2>&1
  if ! grep -qa 'BUILD SUCCESSFUL\|BUILD FAILED' "$LOG/$arm.log"; then
    echo "$arm: BUILD DID NOT COMPLETE — see $LOG/$arm.log"; continue
  fi
  # Round 901's lesson: gradle prints "N tests completed, M failed" ONLY when
  # something failed, so its ABSENCE is a GREEN run — a BLIND arm, not a compile
  # error. A driver whose vocabulary lacks a word for "the mistake landed and
  # nothing noticed" hides exactly the finding the ablation exists to produce.
  if grep -qa 'tests completed' "$LOG/$arm.log"; then
    echo "-- $arm: $(grep -a 'tests completed' "$LOG/$arm.log")"
  elif grep -qa 'BUILD SUCCESSFUL' "$LOG/$arm.log"; then
    echo "-- $arm: ALL PINS GREEN — BLIND ARM"
  else
    echo "-- $arm: COMPILE FAILED — see $LOG/$arm.log"
  fi
  grep -a 'FAILED$' "$LOG/$arm.log" | sed 's/^/     /' | head -12
done

git checkout -- "$CEN"
echo "tree restored: $(git status --porcelain "$CEN" | wc -l) modified"

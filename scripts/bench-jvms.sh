#!/usr/bin/env bash
# Compare N JVMs on ONE box, in ONE rotated block, on the same bytecode.
#
# WHAT THIS ANSWERS that nothing else here does: which JDK vendor / JIT the
# compiler should be RUN on. Every other harness in this repo varies the
# compiler and holds the JVM fixed; this one does the opposite.
#
# WHY IT MUST BE ONE BLOCK. An absolute ms figure on this class of box is not
# stable across sessions at the ~10% level (round 826 measured the same code
# 12.8% apart from round 824), so only a WITHIN-ROUND paired delta is quotable.
# Arms are therefore rotated per rep and every arm gets its OWN JVM process
# (round 867: two arms sharing a process share the branch profile of every
# compiled method, and per-arm sd blew out to 16-38%).
#
# BOTH REGIMES, ALWAYS. The Graal JIT trades warm-up for peak: measured
# 2026-08-10 it is -5.4% check-only / -10.2% emit WARM against Temurin and
# +8.2% / +4.6% COLD. Reporting one regime answers half the question and picks
# the winner by accident. See docs/perf/jdk-jit-vendor-comparison.md.
#
# REPLICATION IS NOT OPTIONAL. The same box produced a clean sign-consistent
# 3/3 result that REVERSED when the arm order was inverted (round 840(c), and
# § 3 of that doc). Three warm reps is the floor; two cannot distinguish an
# effect from a draw.
#
# Usage:
#   scripts/bench-jvms.sh --arm name=/path/to/java [--arm ...] \
#       [--baseline NAME] [--profile DIR] [--warmup N] [--iters N]
#       [--warm-reps N] [--cold-reps N] [--modes a,b] [--out DIR]
set -uo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

declare -a ARM_NAMES=() ARM_PATHS=()
BASELINE=""; PROFILE=""; WARMUP=6; ITERS=6; WARM_REPS=3; COLD_REPS=2
MODES="noEmit,emit"; OUT_DIR="$REPO_ROOT/build/bench/jvms"; HEAP=4g

while [[ $# -gt 0 ]]; do
    case "$1" in
        --arm)       ARM_NAMES+=("${2%%=*}"); ARM_PATHS+=("${2#*=}"); shift 2 ;;
        --baseline)  BASELINE="$2"; shift 2 ;;
        --profile)   PROFILE="$2"; shift 2 ;;
        --warmup)    WARMUP="$2"; shift 2 ;;
        --iters)     ITERS="$2"; shift 2 ;;
        --warm-reps) WARM_REPS="$2"; shift 2 ;;
        --cold-reps) COLD_REPS="$2"; shift 2 ;;
        --modes)     MODES="$2"; shift 2 ;;
        --out)       OUT_DIR="$2"; shift 2 ;;
        --heap)      HEAP="$2"; shift 2 ;;
        --help|-h)   sed -n '2,/^set -uo/p' "$0" | sed '$d;s/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
done

[[ ${#ARM_NAMES[@]} -ge 2 ]] || { echo "error: need at least two --arm name=/path/to/java" >&2; exit 2; }
[[ -n "$BASELINE" ]] || BASELINE="${ARM_NAMES[0]}"
IFS=',' read -r -a MODE_LIST <<<"$MODES"

MAIN="$REPO_ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"
TEST="$REPO_ROOT/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test"

# --- everything that may invoke gradle happens BEFORE the daemon stop -------
# (round 851: a gate that re-triggers a compile which the stop then kills leaves
# a WIPED class dir behind an earlier BUILD SUCCESSFUL.)
if [[ ! -f "$TEST/com/xemantic/typescript/compiler/bench/BenchMainKt.class" ]]; then
    ./gradlew -q compileTestKotlinJvm || { echo "error: could not build test classes" >&2; exit 1; }
fi
if [[ -z "$PROFILE" ]]; then
    PROFILE="$(ls -d "$REPO_ROOT"/build/bench/tsc-project-* 2>/dev/null | head -1 || true)"
fi
if [[ -z "$PROFILE" || ! -d "$PROFILE" ]]; then
    scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log --iterations 1
    PROFILE="$(ls -d "$REPO_ROOT"/build/bench/tsc-project-* | head -1)"
fi
. "$REPO_ROOT/scripts/lib/dep-classpath.sh"
CP_TAIL="$(xtsc_dep_classpath "$REPO_ROOT/build/bench/cp-warm.txt")" || {
    echo "error: could not resolve the dependency tail" >&2; exit 1; }

./gradlew --stop >/dev/null 2>&1 || true
pkill -f 'KotlinCompile[D]aemon' 2>/dev/null
sleep 3

# --- positive controls: is the code under test actually loadable? ----------
n_main=$(find "$MAIN" -name '*.class' 2>/dev/null | wc -l)
[[ "$n_main" -gt 500 ]] || { echo "error: only $n_main class files in $MAIN" >&2; exit 1; }
for idx in "${!ARM_NAMES[@]}"; do
    jb="${ARM_PATHS[$idx]}"
    [[ -x "$jb" ]] || { echo "error: arm '${ARM_NAMES[$idx]}' has no executable java at $jb" >&2; exit 1; }
    printf '%-14s ' "${ARM_NAMES[$idx]}"; "$jb" -version 2>&1 | head -1
done
echo "controls OK: $n_main main classes, profile=$PROFILE"

mkdir -p "$OUT_DIR"
TSV="$OUT_DIR/results.tsv"
: > "$TSV"; printf 'block\tarm\tmode\trep\tms\terrors\n' >> "$TSV"
CP="$MAIN:$TEST:$CP_TAIL"
NARMS=${#ARM_NAMES[@]}

arm_path() { local n="$1" i; for i in "${!ARM_NAMES[@]}"; do
    [[ "${ARM_NAMES[$i]}" == "$n" ]] && { printf '%s' "${ARM_PATHS[$i]}"; return; }; done; }

warm_run() { # arm mode rep
    local arm="$1" mode="$2" rep="$3" jb out emitarg=off
    jb="$(arm_path "$arm")"; [[ "$mode" == emit ]] && emitarg=emit
    out="$(mktemp)"
    "$jb" "-Xmx$HEAP" -cp "$CP" com.xemantic.typescript.compiler.bench.BenchMainKt \
        "$PROFILE" "$WARMUP" "$ITERS" off "$emitarg" >"$out" 2>&1
    python3 - "$out" "$arm" "$mode" "$rep" "$TSV" <<'PY'
import json, statistics, sys
path, arm, mode, rep, tsv = sys.argv[1:6]
its=[]; errors=-1
for line in open(path, errors="replace"):
    line=line.strip()
    if not line.startswith("{"): continue
    try: o=json.loads(line)
    except Exception: continue
    if "iter" in o: its.append(o["ms"]); errors=o["errors"]
if not its:
    print(f"WARN warm {arm}/{mode} rep{rep}: no iterations parsed"); sys.exit()
med=statistics.median(its)
open(tsv,"a").write(f"warm\t{arm}\t{mode}\t{rep}\t{med:.1f}\t{errors}\n")
print(f"warm {arm:14s} {mode:7s} rep{rep} median={med:8.1f} n={len(its)} "
      f"min={min(its):.0f} max={max(its):.0f} err={errors}")
PY
    rm -f "$out"
}

cold_run() { # arm mode rep
    local arm="$1" mode="$2" rep="$3" jb out t0 t1 ms errors
    local nef=(); [[ "$mode" == noEmit ]] && nef=(--noEmit)
    jb="$(arm_path "$arm")"
    out="$(mktemp)"
    t0=$(date +%s%N)
    "$jb" "-Xmx$HEAP" -cp "$CP" com.xemantic.typescript.compiler.MainKt \
        ${nef[@]+"${nef[@]}"} "$PROFILE" >"$out" 2>&1
    t1=$(date +%s%N)
    ms=$(( (t1 - t0) / 1000000 ))
    errors=$(grep -c "error TS" "$out" 2>/dev/null | head -1)
    printf 'cold\t%s\t%s\t%s\t%s\t%s\n' "$arm" "$mode" "$rep" "$ms" "${errors:-0}" >> "$TSV"
    printf 'cold %-14s %-7s rep%s wall=%6d ms err=%s\n' "$arm" "$mode" "$rep" "$ms" "${errors:-0}"
    rm -f "$out"
}

echo "=== $(date -Is) COLD block ($COLD_REPS reps x ${#MODE_LIST[@]} modes x $NARMS arms)"
for ((rep=1; rep<=COLD_REPS; rep++)); do
  for mode in "${MODE_LIST[@]}"; do
    off=$(( (rep - 1) % NARMS ))
    for ((i=0; i<NARMS; i++)); do cold_run "${ARM_NAMES[$(( (i + off) % NARMS ))]}" "$mode" "$rep"; done
  done
done

echo "=== $(date -Is) WARM block ($WARM_REPS reps x ${#MODE_LIST[@]} modes x $NARMS arms)"
for ((rep=1; rep<=WARM_REPS; rep++)); do
  for mode in "${MODE_LIST[@]}"; do
    off=$(( (rep - 1) % NARMS ))
    for ((i=0; i<NARMS; i++)); do warm_run "${ARM_NAMES[$(( (i + off) % NARMS ))]}" "$mode" "$rep"; done
  done
done

echo "=== $(date -Is) report"
REPORT="$OUT_DIR/report.md"
BASELINE="$BASELINE" TSV="$TSV" REPORT="$REPORT" PROFILE="$PROFILE" \
WARMUP="$WARMUP" ITERS="$ITERS" python3 - <<'PY'
import csv, os, statistics as st
from collections import defaultdict
tsv, report, base = os.environ["TSV"], os.environ["REPORT"], os.environ["BASELINE"]
rows=list(csv.DictReader(open(tsv), delimiter="\t"))
arms=[];
for r in rows:
    if r["arm"] not in arms: arms.append(r["arm"])
modes=[]
for r in rows:
    if r["mode"] not in modes: modes.append(r["mode"])
g=defaultdict(list)
for r in rows: g[(r["block"], r["arm"], r["mode"])].append(float(r["ms"]))

L=[f"# JVM comparison — {os.path.basename(os.environ['PROFILE'])}", "",
   f"Warm = median of {os.environ['ITERS']} in-process rebuilds after "
   f"{os.environ['WARMUP']} warm-ups, one JVM per arm. Cold = process wall clock.",
   "Arms rotated per rep; all arms in ONE block, so deltas are within-round.",
   f"Baseline for deltas: **{base}**.", ""]

for block in ("warm","cold"):
    present=[(a,m) for a in arms for m in modes if (block,a,m) in g]
    if not present: continue
    L += [f"## {block}", "", "| arm | mode | median ms | sd% | vs baseline | wins |",
          "|---|---|---:|---:|---:|---|"]
    for m in modes:
        for a in arms:
            v=g.get((block,a,m))
            if not v: continue
            med=st.median(v); sd=st.pstdev(v)/med*100 if len(v)>1 else 0.0
            if a==base: delta="—"; wins="—"
            else:
                A={r["rep"]:float(r["ms"]) for r in rows if r["block"]==block and r["arm"]==a and r["mode"]==m}
                B={r["rep"]:float(r["ms"]) for r in rows if r["block"]==block and r["arm"]==base and r["mode"]==m}
                d=[(A[k]-B[k])/B[k]*100 for k in sorted(A) if k in B]
                delta=f"{st.median(d):+.2f}%" if d else "n/a"
                wins=f"{sum(1 for x in d if x<0)}/{len(d)}" if d else "n/a"
            L.append(f"| {a} | {m} | {med:.0f} | {sd:.1f} | {delta} | {wins} |")
    L.append("")

L += ["> A negative delta means the arm is FASTER than the baseline. Read `wins` "
      "with it: a median without a consistent sign is a draw, and on this class of "
      "box a sign-consistent 3/3 has been seen to reverse on replication "
      "(docs/perf/jdk-jit-vendor-comparison.md § 3). Two reps cannot decide anything.", ""]
open(report,"w",encoding="utf-8",newline="\n").write("\n".join(L))
print("\n".join(L))
PY
echo "=== $(date -Is) DONE — $TSV / $REPORT"

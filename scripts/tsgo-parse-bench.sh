#!/usr/bin/env bash
# (TSGO.1) parse-throughput gate: the ported tsgo parser vs -core's Parser on tsc's 78
# sources, JVM warm (docs/tsgo-port-plan.md § 4.1; results in docs/goport-perf.md).
#
#   scripts/tsgo-parse-bench.sh [--rounds N] [--warmup W] [--iters I] [--no-build] [--out DIR]
#
# 1. builds -tsgo's jvmTest classes ONCE through Gradle (serialized with flock on $GRADLE_LOCK),
#    and prints its jvmTestRuntimeClasspath through a throwaway init script;
# 2. SNAPSHOTS every non-Gradle-cache classpath entry (class dirs, -core's jar) into $OUT/cp,
#    so a concurrent regeneration of gen/ cannot change the classes mid-measurement;
# 3. runs ParseBenchMain one arm per JVM, ABBA-rotated across processes
#    (tsgo core core tsgo | tsgo core core tsgo ...), --rounds pairs of each arm;
# 4. prints per-process medians, the per-arm median of medians, spread, win rate and the ratio.
#
# TSGO_PREPEND=<class dir> puts an EXPERIMENTAL class dir in front of the tsgo arm only (a
# measurement-only patch of generated classes, e.g. docs/goport-perf.md § 3's suffix-slice fix);
# the core arm is unchanged, so the ratio then prices the patched port.
#
# Requires build/goport/oracle/manifest.json (it names tsc's 78 sources). Do not watch the run:
# your shell commands compete for the cores the measurement needs (CLAUDE.md, round 774).
set -euo pipefail
REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ROUNDS=4; WARMUP=8; ITERS=12; BUILD=1
OUT="${OUT:-$REPO/build/tsgo-parse-bench}"
GRADLE_LOCK="${GRADLE_LOCK:-/tmp/claude-1000/-home-claude-git-xemantic-typescript-compiler/1f6f3793-7d44-451b-8154-8c3e87e48d6a/scratchpad/gradle.lock}"
JAVA_OPTS_BENCH="${JAVA_OPTS_BENCH:--Xms2g -Xmx3g}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --rounds) ROUNDS="$2"; shift 2;;
    --warmup) WARMUP="$2"; shift 2;;
    --iters) ITERS="$2"; shift 2;;
    --no-build) BUILD=0; shift;;
    --out) OUT="$2"; shift 2;;
    *) echo "unknown arg $1" >&2; exit 2;;
  esac
done
mkdir -p "$OUT"
[[ -f "$REPO/build/goport/oracle/manifest.json" ]] || { echo "missing build/goport/oracle/manifest.json" >&2; exit 2; }

if [[ $BUILD -eq 1 ]]; then
  INIT="$OUT/print-cp.init.gradle.kts"
  cat > "$INIT" <<'G'
rootProject {
    findProject(":xemantic-typescript-compiler-tsgo")?.let { p ->
        p.tasks.register("printParseBenchClasspath") {
            dependsOn("jvmTestClasses")
            val cp = p.configurations.getByName("jvmTestRuntimeClasspath")
            doLast {
                val dirs = listOf("main", "test").map { p.layout.buildDirectory.dir("classes/kotlin/jvm/$it").get().asFile }
                println("PARSEBENCH_CP=" + (dirs + cp.files).joinToString(":"))
            }
        }
    }
}
G
  mkdir -p "$(dirname "$GRADLE_LOCK")"
  (cd "$REPO" && flock "$GRADLE_LOCK" ./gradlew -I "$INIT" :xemantic-typescript-compiler-tsgo:printParseBenchClasspath) > "$OUT/gradle.log" 2>&1 \
    || { tail -40 "$OUT/gradle.log"; exit 1; }
  CP_LINES=$(grep -ac '^PARSEBENCH_CP=' "$OUT/gradle.log" || true)
  [[ "$CP_LINES" == 1 ]] || { echo "expected exactly one PARSEBENCH_CP line, got $CP_LINES" >&2; exit 1; }
  RAW_CP=$(grep -a '^PARSEBENCH_CP=' "$OUT/gradle.log" | sed 's/^PARSEBENCH_CP=//')
  rm -rf "$OUT/cp"; mkdir -p "$OUT/cp"
  SNAP=(); i=0
  IFS=: read -ra ENTRIES <<< "$RAW_CP"
  for e in "${ENTRIES[@]}"; do
    [[ -e "$e" ]] || continue
    case "$e" in
      "$HOME"/.gradle/*|"$HOME"/.m2/*) SNAP+=("$e");;
      *) i=$((i+1)); dst="$OUT/cp/$i-$(basename "$e")"; cp -a "$e" "$dst"; SNAP+=("$dst");;
    esac
  done
  (IFS=:; echo "${SNAP[*]}") > "$OUT/classpath.txt"
  { echo "commit=$(git -C "$REPO" rev-parse HEAD)"
    echo "gen_dirty_files=$(git -C "$REPO" status --porcelain -- xemantic-typescript-compiler-tsgo/src/commonMain/kotlin/gen | wc -l)"
    echo "snapshot=$(date -Is)"; } > "$OUT/provenance.txt"
fi
CP=$(cat "$OUT/classpath.txt")
# Positive control: the snapshot must contain the bench main and -core's Parser.
for cls in com/xemantic/typescript/tsgo/ParseBenchMainKt.class; do
  ls "$OUT"/cp/*/"$cls" >/dev/null 2>&1 || { echo "snapshot lacks $cls" >&2; exit 1; }
done
cat "$OUT/provenance.txt"; [[ -n "${TSGO_PREPEND:-}" ]] && echo "TSGO_PREPEND=$TSGO_PREPEND"
echo "box: nproc=$(nproc) load=$(cut -d' ' -f1-3 /proc/loadavg) other-java=$(pgrep -c java || true)"

ORDER=()
for ((r=0; r<ROUNDS; r++)); do
  if (( r % 2 == 0 )); then ORDER+=(tsgo core); else ORDER+=(core tsgo); fi
done
# ABBA: pair rounds as (tsgo core)(core tsgo)...
n=0
: > "$OUT/results.txt"
for arm in "${ORDER[@]}"; do
  n=$((n+1))
  log="$OUT/run-$n-$arm.log"
  ARMCP="$CP"; [[ "$arm" == tsgo && -n "${TSGO_PREPEND:-}" ]] && ARMCP="$TSGO_PREPEND:$CP"
  java $JAVA_OPTS_BENCH -cp "$ARMCP" com.xemantic.typescript.tsgo.ParseBenchMainKt "$arm" "$REPO" "$WARMUP" "$ITERS" > "$log" 2>&1 \
    || { echo "run $n ($arm) failed:"; tail -20 "$log"; exit 1; }
  r=$(grep -a '^RESULT' "$log"); echo "run $n: $r (load $(cut -d' ' -f1 /proc/loadavg))"
  echo "$n $r" >> "$OUT/results.txt"
done

python3 - "$OUT/results.txt" <<'P'
import sys, re, statistics as st
rows=[l.split() for l in open(sys.argv[1])]
d={'tsgo':[], 'core':[]}; conv=[]; seq=[]
for r in rows:
    kv=dict(x.split('=',1) for x in r[2:])
    d[kv['arm']].append(float(kv['median_ms'])); seq.append((kv['arm'], float(kv['median_ms'])))
    if kv['arm']=='tsgo': conv.append(float(kv['conv_median_ms']))
    print(f"  {kv['arm']:5} median={kv['median_ms']:>8} min={kv['min_ms']:>8} max={kv['max_ms']:>8} checksum={kv['checksum']}")
for a,v in d.items():
    print(f"{a}: median of process medians {st.median(v):.1f} ms  range {min(v):.1f}-{max(v):.1f}  spread {(max(v)-min(v))/st.median(v)*100:.1f}%  n={len(v)}")
print(f"tsgo text conversion (UTF-16 -> byte string): {st.median(conv):.2f} ms/iteration")
# adjacent pairs (tsgo, core) in run order -> win rate (core faster)
pairs=[(seq[i],seq[i+1]) for i in range(0,len(seq)-1,2)]
ratios=[]
for a,b in pairs:
    t=a[1] if a[0]=='tsgo' else b[1]; c=b[1] if b[0]=='core' else a[1]; ratios.append(t/c)
print("paired ratios tsgo/core:", " ".join(f"{x:.2f}" for x in ratios), f"| core faster in {sum(x>1 for x in ratios)}/{len(ratios)}")
R=st.median(d['tsgo'])/st.median(d['core'])
print(f"RATIO tsgo/core = {R:.2f}x  (gate: go <= 1.5x, no-go > 3x)")
P

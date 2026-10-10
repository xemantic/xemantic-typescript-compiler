#!/usr/bin/env bash
# Warm A/B of the ported compiler (docs/goport-perf.md): each arm in its own JVM, ABBA + BAAB.
#
#   scripts/tsgo-ab-warm.sh <tsconfig.json> <single|parallel> [rounds=2] [warmup=6] [iters=10]
#
#   A_CP / B_CP       classpath of each arm (default: $CP_FILE contents, i.e. the same build)
#   A_FLAGS / B_FLAGS extra JVM flags of each arm (default: empty)
#   CP_FILE           a file holding the default classpath (as printed by tsgo-jvm-profile.sh's init script)
#   TSGO_JAVA         the java binary (default `java`)
#
# Prints, per process, the median warm total_ms / gc_ms / alloc_mb (this thread) / talloc_mb (every thread)
# and the digest set, then the
# median of the process medians per arm and B's paired wins. Refuses a run whose digests disagree
# (two arms answering differently are not two speeds of one compiler).
set -euo pipefail
CFG=$1; THREADS=$2; ROUNDS=${3:-2}; WARMUP=${4:-6}; ITERS=${5:-10}
REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="${TSGO_JAVA:-java}"
DEF_CP=""; [[ -n "${CP_FILE:-}" ]] && DEF_CP=$(cat "$CP_FILE")
A_CP=${A_CP:-$DEF_CP}; B_CP=${B_CP:-$DEF_CP}
[[ -n "$A_CP" && -n "$B_CP" ]] || { echo "no classpath (set CP_FILE or A_CP/B_CP)"; exit 2; }
export XTSC_TSGO_LIB_DIR="${XTSC_TSGO_LIB_DIR:-$REPO/tools/tsgo-7.0.2/lib}"
OUT=$(mktemp -d)
run() { # arm idx
  local arm=$1 idx=$2 cp flags log="$OUT/$1-$2.log"
  if [[ $arm == A ]]; then cp=$A_CP; flags=${A_FLAGS:-}; else cp=$B_CP; flags=${B_FLAGS:-}; fi
  # shellcheck disable=SC2086
  "$JAVA" -Xms2g -Xmx6g $flags -cp "$cp" com.xemantic.typescript.tsgo.CheckBenchMainKt \
    "$CFG" "$WARMUP" "$ITERS" nolib "$THREADS" > "$log" 2>&1 || { echo "arm $arm run $idx FAILED"; tail -20 "$log"; exit 1; }
  python3 - "$log" "$arm" <<'PY'
import re, statistics, sys
log, arm = sys.argv[1], sys.argv[2]
rows = [l for l in open(log) if l.startswith("iter") and " warm " in l]
if not rows: print("no warm rows in", log); sys.exit(1)
f = lambda k: [float(re.search(k + r"=([\d.]+)", r).group(1)) for r in rows]
dig = sorted({re.search(r"digest=(\S+)", r).group(1) + "/" + re.search(r"diags=(\d+)", r).group(1) for r in rows})
print(f"{arm} total_ms={statistics.median(f('total_ms')):.0f} gc_ms={statistics.median(f('gc_ms')):.0f} "
      f"alloc_mb={statistics.median(f('alloc_mb')):.0f} talloc_mb={statistics.median(f('talloc_mb')):.0f} n={len(rows)} digest={','.join(dig)}")
PY
}
ORDER=()
for ((r = 0; r < ROUNDS; r++)); do
  if (( r % 2 == 0 )); then ORDER+=(A B B A); else ORDER+=(B A A B); fi
done
i=0; : > "$OUT/summary"
for arm in "${ORDER[@]}"; do run "$arm" $((i++)) | tee -a "$OUT/summary"; done
python3 - "$OUT/summary" <<'PY'
import re, statistics, sys
rows = [l.split() for l in open(sys.argv[1])]
digs = {r[-1] for r in rows}
if len(digs) != 1: print("REFUSED: digests disagree:", digs); sys.exit(1)
val = lambda r, k: float(next(x for x in r if x.startswith(k + "=")).split("=")[1])
for k in ("total_ms", "gc_ms", "alloc_mb", "talloc_mb"):
    a = [val(r, k) for r in rows if r[0] == "A"]; b = [val(r, k) for r in rows if r[0] == "B"]
    ma, mb = statistics.median(a), statistics.median(b)
    wins = sum(1 for x, y in zip(a, b) if y < x)
    print(f"{k:9s} A={ma:8.0f} [{min(a):.0f}-{max(a):.0f}]  B={mb:8.0f} [{min(b):.0f}-{max(b):.0f}]  "
          f"delta={100 * (mb - ma) / ma:+.1f}%  B wins {wins}/{min(len(a), len(b))}")
print("digest", digs.pop())
PY

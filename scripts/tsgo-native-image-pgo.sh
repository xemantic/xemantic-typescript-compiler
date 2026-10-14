#!/usr/bin/env bash
# (TSGO.6) The profile-guided GraalVM image of the ported tsc CLI: instrument → train → rebuild.
#
#   scripts/tsgo-native-image-pgo.sh [--graalvm DIR] [--gc G1|epsilon] [PROJECT_DIR ...]
#
# Builds `xtsc-tsgo-instrumented` (`--pgo-instrument`), runs it over each training project — a `--noEmit`
# check, and for the FIRST project a check+emit into a scratch outDir as well (an emit-free profile leaves the
# emitter on static heuristics) — then builds `build/native/xtsc-tsgo-pgo` with `--pgo=<all profiles> -O3`.
# Needs ORACLE GraalVM (Community Edition has no PGO). Default training set: tsc's compiler profile and zod.
# `--gc epsilon` builds the opt-in no-GC image instead, `build/native/xtsc-tsgo-pgo-epsilon` (instrumented and trained
# under the same collector): faster on small projects, out of memory on very large ones (build.gradle.kts `nativeImage`).
#
# Measured 2026-10-09 (Oracle GraalVM 25.0.4, G1, rotated medians of 3, output byte-identical to tsgo in every
# arm): `-O3` alone 5-11% faster than the default image, `-march=native` nothing on Zen 2, PGO + `-O3` 15-23% —
# on the TRAINED compiler profile 2.95 → 2.47 s and on held-out projects too (tsc's services 4.00 → 3.08 s,
# date-fns 0.66 → 0.59 s); tsgo 1.73 / 2.36 / 0.36 s. docs/goport-cli.md § 4.
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd)
GRAAL=$R/tools/graalvm-25
GC=G1
while [[ ${1:-} == --* ]]; do
  case $1 in
    --graalvm) GRAAL=$2 ;;
    --gc) GC=$2 ;;
    *) echo "REFUSED: unknown option $1" >&2; exit 2 ;;
  esac
  shift 2
done
SUFFIX=; [[ $GC == G1 ]] || SUFFIX=-${GC,,}
PROJECTS=("$@")
if [[ ${#PROJECTS[@]} -eq 0 ]]; then
  PROJECTS=("$(ls -d "$R"/build/bench/tsc-project-* | head -1)")
  [[ -d $R/build/scratch-p18265-census/zod/packages/zod ]] && PROJECTS+=("$R/build/scratch-p18265-census/zod/packages/zod")
fi
for p in "${PROJECTS[@]}"; do [[ -f $p/tsconfig.json ]] || { echo "REFUSED: no tsconfig.json in $p" >&2; exit 2; }; done
N=$R/xemantic-typescript-compiler-tsgo/build/native
WORK=$(mktemp -d); trap 'rm -r -- "$WORK"' EXIT

# The bundled lib.*.d.ts parsed at IMAGE BUILD TIME into the image heap (com.xemantic.typescript.tsgo.TsgoLibSnapshot,
# docs/goport-perf.md § 21): the port is initialized at build time, except what reads the running process — GoProcs
# (the CPU count, TSGO_GOMAXPROCS), GoSyscall (TSGO_IO_STATS), go.time's monotonic origin and
# osvfs (the file system's case sensitivity, probed through the executable's path). A run then finds the libs parsed (same options and text, else it parses as before).
SNAPSHOT="-Dxtsc.tsgo.libSnapshot=true --initialize-at-build-time=com.xemantic.typescript.tsgo,kotlin --initialize-at-run-time=com.xemantic.typescript.tsgo.go.sync.GoProcs,com.xemantic.typescript.tsgo.go.os.GoSyscall,com.xemantic.typescript.tsgo.go.time.TimeKt,com.xemantic.typescript.tsgo.vfs.osvfs.OsKt"

cd "$R"
./gradlew -q :xemantic-typescript-compiler-tsgo:nativeImage -PgraalvmHome="$GRAAL" -PnativeImageGc="$GC" \
  "-PnativeImageArgs=--pgo-instrument $SNAPSHOT" -PnativeImageOutput=xtsc-tsgo-instrumented$SUFFIX

export XTSC_TSGO_LIB_DIR=${XTSC_TSGO_LIB_DIR:-$R/tools/tsgo-7.0.2/lib}
profiles=(); i=0
for p in "${PROJECTS[@]}"; do
  i=$((i + 1))
  # tsc exits 1/2 when the project has errors: a status, not a failure of the training run.
  "$N/xtsc-tsgo-instrumented$SUFFIX" --noEmit -p "$p" -XX:ProfilesDumpFile="$WORK/check$i.iprof" > /dev/null || true
  profiles+=("$WORK/check$i.iprof")
  if [[ $i == 1 ]]; then
    "$N/xtsc-tsgo-instrumented$SUFFIX" -p "$p" --outDir "$WORK/emit" -XX:ProfilesDumpFile="$WORK/emit.iprof" > /dev/null || true
    profiles+=("$WORK/emit.iprof")
  fi
done
for f in "${profiles[@]}"; do [[ -s $f ]] || { echo "REFUSED: training wrote no profile $f" >&2; exit 1; }; done
pgo=$(IFS=,; echo "${profiles[*]}")
./gradlew -q :xemantic-typescript-compiler-tsgo:nativeImage -PgraalvmHome="$GRAAL" -PnativeImageGc="$GC" \
  "-PnativeImageArgs=--pgo=$pgo -O3 $SNAPSHOT" -PnativeImageOutput=xtsc-tsgo-pgo$SUFFIX
echo "PGO image: $N/xtsc-tsgo-pgo$SUFFIX (gc $GC, trained on ${#PROJECTS[@]} project(s))"

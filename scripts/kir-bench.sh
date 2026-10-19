#!/usr/bin/env bash
#
# The KIR RUNTIME benchmark: two real libraries, three arms, one source.
#
#   arm 1  tsgo 7.0.2   -> JavaScript   -> node     (what these libraries run on)
#   arm 2  xtsc -kir    -> JVM bytecode -> java     (the Kotlin-IR backend)
#   arm 3  xtsc         -> JavaScript   -> node     (OUR JavaScript — the tsgo port, `TsgoMain` — same runtime)
#   arm 4  xtsc -kir    -> Kotlin/Native -> kexe    (KIR_BENCH_NATIVE=1, opt-in)
#   arm 5  tsgo 7.0.2   -> JavaScript   -> bun      (KIR_BENCH_BUN=1, opt-in)
#   arm 6  xtsc         -> JavaScript   -> bun      (KIR_BENCH_BUN=1, opt-in)
#   arm 7  Perry        -> native (LLVM) -> elf     (KIR_BENCH_PERRY=1, opt-in)
#
# The PERRY arm ((BENCH.4)) is the TS-to-native PEER: the one compiler of that
# category that takes the fixture bytes UNMODIFIED, so the equivalence gate can
# hold it like any other arm. It has no type checker (SWC parses, LLVM
# generates), so it is a peer for what it computes, not for what it accepts. It
# is opt-in for the reason `wasi` is, only more so: measured 2026-10-18, toml is
# 24x node's time (197 s a process) and ONE mitt process did not finish in an
# hour, because each round is slower than the last (docs/perf/kir-bench.md).
# It needs clang (Perry assembles its statepoint rewrite with it).
#
# The BUN arms re-run arms 1 and 3's OWN emitted files on a second JavaScript
# engine, so the pair (1,5) and the pair (3,6) each hold the compiler AND the
# emitted bytes fixed and vary only the runtime -- which is the only way a
# node-vs-bun gap here can be attributed to the engine rather than to a compiler.
#
# The NATIVE arm is opt-in because building it is two konanc links on a box with
# ZERO swap (CLAUDE.md), not because it is optional evidence: when it is asked
# for it is built, gated and timed like every other arm, and a failure REFUSES
# the run. What must never happen is a run that quietly drops it, so the header
# below prints the arms it actually ran rather than a fixed count.
#
# Arm 3 is the CONTROL, and it is the reason this file exists rather than a pair
# of ad-hoc commands: arms 1 and 2 differ in BOTH the compiler and the target, so
# a gap between them cannot be attributed. Arm 3 holds the runtime fixed and
# varies only the compiler, which splits "our front end" from "our backend".
# Since (TSGO.7-g) our JavaScript is the tsgo port's, which the run requires to be
# BYTE-IDENTICAL to tsgo's (`diff -r` below), so arms 1 and 3 time the same bytes:
# their gap is the run's own noise floor, and the arm 2 gap is all backend. (Runs
# before 2026-10-14 emitted arm 3 with `-core`'s compiler.)
#
# WHAT IT MEASURES. Two numbers with different meanings, both reported:
#   * STEADY-STATE throughput — the driver warms up, then times rounds itself and
#     reports its own best. This is the backend comparison.
#   * ONE-SHOT wall clock — the acceptance program, start to exit. This is the
#     JVM-startup property, and it is a different question.
#
# THE EQUIVALENCE GATE IS NOT OPTIONAL AND RUNS BEFORE ANY TIMING. Every arm
# prints a `sink=` accumulator, and the run REFUSES unless all three agree: an
# arm that computes something else is not a faster arm. The failure this guards
# is specific and quiet — a JS file that throws on import prints nothing, and a
# wall-clock harness reads that as the fastest arm in the batch.
#
# USE:  scripts/kir-bench.sh [processes]      (default 5)
#       KIR_BENCH_NATIVE=1 scripts/kir-bench.sh 3   (adds the Kotlin/Native arm)
#       KIR_BENCH_WASM=1      scripts/kir-bench.sh 3   (adds WebAssembly under V8)
#       KIR_BENCH_WASM_WASI=1 scripts/kir-bench.sh 1   (adds WebAssembly under wasmtime)
#       KIR_BENCH_PERRY=1     scripts/kir-bench.sh 3   (adds Perry, the TS-to-native peer)
#
# THE TWO WASM ARMS ARE SEPARATE FLAGS BECAUSE THEY COST THREE ORDERS OF
# MAGNITUDE APART. `wjs` is an ordinary arm. `wasi` runs `toml` at ~1.7 ms/parse
# against Node's ~22 us, so ONE process of it is ~9 MINUTES where every other arm
# is seconds, and a 3-process run is dominated by it for about an hour. That is a
# property of the HOST, not of the lowering: the identical module under V8 is
# 13.9x faster, and `-C collector=null` alone takes wasmtime 1,675 -> 790
# us/parse, so over half of it is wasmtime's reference-counting GC.
#
set -uo pipefail

REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
# (BENCH.3) `--record` keeps the run: its JSON goes to bench-history/kir-runs/ and
# scripts/kir_bench_report.py regenerates docs/perf/kir-bench.md from it — the
# table is never typed by hand. Without it the JSON is still written, to $WORK.
RECORD=0
if [ "${1:-}" = "--record" ]; then RECORD=1; shift; fi
PROCESSES="${1:-5}"
WORK="${KIR_BENCH_WORK:-$REPO/build/bench/kir-bench}"
NATIVE="${KIR_BENCH_NATIVE:-0}"
PROJECTS="$REPO/xemantic-typescript-compiler-kir/src/jvmTest/resources/projects"
DRIVERS="$REPO/scripts/kir-bench/drivers"
NODE="${KIR_BENCH_NODE:-$REPO/tools/node/bin/node}"
BUN_ARM="${KIR_BENCH_BUN:-0}"
BUN="${KIR_BENCH_BUN_BIN:-$REPO/tools/bun/bin/bun}"
TSGO="${KIR_BENCH_TSGO:-$REPO/tools/tsgo-7.0.2/lib/tsc}"
WASM_ARM="${KIR_BENCH_WASM:-0}"
WASI_ARM="${KIR_BENCH_WASM_WASI:-0}"
WASMTIME="${KIR_BENCH_WASMTIME:-wasmtime}"
PERRY_ARM="${KIR_BENCH_PERRY:-0}"
PERRY="${KIR_BENCH_PERRY_BIN:-$REPO/tools/perry/node_modules/@perryts/perry-linux-x64/bin/perry}"
PERRY_CLANG="${PERRY_LLVM_CLANG:-$REPO/tools/llvm-22/bin/clang}"
# (BENCH.2) PLACEMENT — an OPTION, never a silent default. KIR_BENCH_PIN=<cpu list> runs
# every single-threaded-loop arm (node, bun, native, wasm) under `taskset -c <list>`;
# the JVM arm is NEVER pinned by it, because its JIT compiler threads are part of the
# arm: round 824 measured a "single-threaded" run on ~4.2 of 8 cores, and pinning it
# to one would serialise C1/C2 against the program and time a different one. Which
# placement each arm got is printed at the top and recorded in the run's JSON.
PIN="${KIR_BENCH_PIN:-}"
# Kotlin/Wasm emits GC types and the new exception proposal. A host without them
# REFUSES the module rather than running it slowly, so these are not tuning.
WASMTIME_FLAGS="-W gc=y,function-references=y,exceptions=y"

die() { echo "kir-bench: $*" >&2; exit 2; }

# REFUSE rather than skip. A benchmark that quietly drops an arm on a box where
# its toolchain is absent reports a two-arm run as a three-arm one (CLAUDE.md,
# rounds 853/873/895 — a gate that passes quietly where it cannot see is the
# thing that keeps being wrong here).
[ -x "$NODE" ] || die "no node at '$NODE' — set KIR_BENCH_NODE. It is NOT in the repo:
  curl -sL https://nodejs.org/dist/v22.20.0/node-v22.20.0-linux-x64.tar.xz | tar xJ -C tools
  mv tools/node-v22.20.0-linux-x64 tools/node"
[ -x "$TSGO" ] || die "no tsgo at '$TSGO' — set KIR_BENCH_TSGO"
# Opt-in, and then REFUSED rather than skipped: an asked-for arm that quietly
# vanishes reports a four-arm run as a six-arm one.
if [ "$BUN_ARM" = "1" ]; then
    [ -x "$BUN" ] || die "no bun at '$BUN' — set KIR_BENCH_BUN_BIN. It is NOT in the repo:
  curl -fsSL -o /tmp/bun.zip https://github.com/oven-sh/bun/releases/latest/download/bun-linux-x64.zip
  python3 -c \"import zipfile; zipfile.ZipFile('/tmp/bun.zip').extractall('tools/bun-dl')\"
  mkdir -p tools/bun/bin && mv tools/bun-dl/bun-linux-x64/bun tools/bun/bin/bun && chmod +x tools/bun/bin/bun"
fi
if [ "$WASI_ARM" = "1" ]; then
    command -v "$WASMTIME" > /dev/null \
        || die "no wasmtime on PATH — set KIR_BENCH_WASMTIME. It is NOT in the repo:
  curl https://wasmtime.dev/install.sh -sSf | bash
The wasm-js arm additionally needs the node above, which already gates."
fi
if [ "$PERRY_ARM" = "1" ]; then
    [ -x "$PERRY" ] || die "no perry at '$PERRY' — set KIR_BENCH_PERRY_BIN. It is NOT in the repo:
  mkdir -p tools/perry && (cd tools/perry && ../node/bin/npm install @perryts/perry@0.5.1520)"
    # Perry's LLVM is 22.1.8, and it refuses to compile at all without a clang to
    # assemble with (`perry doctor` says which). Only bin/clang* and lib/clang/ are needed.
    [ -x "$PERRY_CLANG" ] || die "no clang at '$PERRY_CLANG' — set PERRY_LLVM_CLANG. It is NOT in the repo:
  mkdir -p tools/llvm-22 && curl -sL https://github.com/llvm/llvm-project/releases/download/llvmorg-22.1.8/LLVM-22.1.8-Linux-X64.tar.xz \\
    | tar xJ -C tools/llvm-22 --strip-components=1 --wildcards 'LLVM-22.1.8-Linux-X64/bin/clang*' 'LLVM-22.1.8-Linux-X64/lib/clang/*'"
fi
[ -d "$PROJECTS/toml" ] || die "no acceptance projects at '$PROJECTS'"

# ---- classpaths ------------------------------------------------------------
# One resolution: the KIR module's runtime classpath carries the tsgo port too
# (arm 3's `TsgoMain`), since (TSGO.7-g) in place of `-core`'s `MainKt`.
# `findProject`, never `project(":…")`, because an init script applies to every
# build in the invocation and `build-logic` has no such project.
KIR_CLASSES="$REPO/xemantic-typescript-compiler-kir/build/classes/kotlin/jvm/main"

resolve_kir_classpath() {
    local init="$REPO/build/bench/print-kir-classpath.init.gradle.kts"
    mkdir -p "$(dirname "$init")"
    cat > "$init" <<'K'
rootProject {
    findProject(":xemantic-typescript-compiler-kir")?.tasks?.register("kirPrintClasspath") {
        doLast {
            val cp = project.configurations.getByName("jvmRuntimeClasspath")
                .resolve().joinToString(":") { it.absolutePath }
            println("KIR_CLASSPATH=$cp")
        }
    }
}
K
    local log="$WORK/kir-classpath.log"
    "$REPO/gradlew" --console=plain -I "$init" \
        :xemantic-typescript-compiler-kir:compileKotlinJvm \
        :xemantic-typescript-compiler-kir:kirPrintClasspath > "$log" 2>&1
    # A build is verified by its VERDICT, never by exit status alone: a killed
    # run can leave a wiped class dir behind an earlier success (round 851/947).
    grep -q "BUILD SUCCESSFUL" "$log" || die "gradle did not report BUILD SUCCESSFUL — see $log"
    grep -a '^KIR_CLASSPATH=' "$log" | head -1 | cut -c15-
}

mkdir -p "$WORK"
echo "kir-bench: resolving classpaths ..." >&2
KIR_TAIL="$(resolve_kir_classpath)"
[ -n "$KIR_TAIL" ] || die "could not resolve the KIR classpath"

# POSITIVE CONTROL: the code under test must be in the directories we prepend,
# or the run measures whatever else the classpath happens to carry (round 853).
[ -f "$KIR_CLASSES/com/xemantic/typescript/compiler/kir/TypeScriptToKotlinIrKt.class" ] \
    || die "no KIR classes under $KIR_CLASSES"

KIR_CP="$KIR_CLASSES:$KIR_TAIL"

# What a GENERATED program links against, and nothing else: the Kotlin standard
# library and the KIR runtime. Taken from the classpath rather than guessed.
STDLIB="$(tr ':' '\n' <<< "$KIR_TAIL" | grep -m1 'kotlin-stdlib')"
[ -n "$STDLIB" ] || die "no kotlin-stdlib on the KIR classpath"
RUN_CP_TAIL="$STDLIB:$KIR_CLASSES"

javac -nowarn -cp "$KIR_CP" -d "$WORK" "$REPO/scripts/kir-bench/KirBench.java" \
    || die "could not compile the KIR compile harness"

# ---- project assembly ------------------------------------------------------
# The library sources are NOT duplicated here: a bench project is the acceptance
# project with its driver swapped, so the benchmark can never drift from what the
# acceptance test compiles.
assemble() {                                   # assemble <lib> <acceptance-dir> <driver>
    local lib="$1" src="$2" driver="$3"
    local dir="$WORK/src-$lib"
    rm -rf "$dir"; mkdir -p "$dir/src"
    cp "$src"/src/*.ts "$dir/src/"
    cp "$driver" "$dir/src/main.ts"
    cp "$src/tsconfig.json" "$dir/tsconfig.json"
}
assemble mitt "$PROJECTS/mitt-consumer" "$DRIVERS/mitt-main.ts"
assemble toml "$PROJECTS/toml"          "$DRIVERS/toml-main.ts"

# An EMITTING config, which the acceptance ones are not: toml's sets `noEmit`
# (xtsc honours it, so `--outDir` alone emits nothing) and neither sets `outDir`
# or `rootDir`, without which tsgo refuses with TS5011.
emitting_tsconfig() {                          # emitting_tsconfig <lib> <dir>
    if [ "$1" = toml ]; then
        cat > "$2/tsconfig.json" <<'J'
{ "compilerOptions": { "strict": true, "target": "ES2022", "module": "ESNext",
    "moduleResolution": "bundler", "allowImportingTsExtensions": true,
    "rewriteRelativeImportExtensions": true, "outDir": "out", "rootDir": "src" },
  "include": ["src/**/*.ts"] }
J
    else
        cat > "$2/tsconfig.json" <<'J'
{ "compilerOptions": { "strict": true, "target": "ES2020", "module": "ESNext",
    "moduleResolution": "bundler", "outDir": "out", "rootDir": "src" },
  "include": ["src/**/*.ts"] }
J
    fi
    echo '{"type":"module"}' > "$2/package.json"
}

# Node ESM resolves a specifier LITERALLY: `./mitt` and `./mitt.ts` are both
# refused, and neither compiler is wrong about that — tsgo rewrites `.ts` -> `.js`
# under `rewriteRelativeImportExtensions` and leaves the extensionless one alone.
# xtsc now implements the FIRST half too ((KIR.EMIT.1), 2026-08-21), so the first
# `sed` below is a no-op on both arms and is kept only as a belt: the second one,
# which invents an extension mitt's sources never wrote, is still a benchmark
# expedient and no compiler's job.
# `sed -i` WITHOUT a suffix is GNU-only: BSD sed reads the next argument as the
# suffix and then fails on the script ("invalid command code"), which leaves the
# emitted files UNREWRITTEN and shows up far away as a Node module-not-found.
# `-i.bak` is the form both accept; the backups are removed, not left in a
# directory whose `*.js` glob is read again.
runnable_esm() {                               # runnable_esm <out-dir>
    sed -i.bak "s#\(from '\./[^']*\)\.ts'#\1.js'#g" "$1"/*.js
    sed -i.bak "s#\(from '\./[a-zA-Z0-9_-]*\)'#\1.js'#g" "$1"/*.js
    rm -f "$1"/*.js.bak
}

ARMS=(tsgo xtsc kir)
[ "$NATIVE" = "1" ] && ARMS+=(nat)
# TWO Wasm arms, because they answer different questions: `wasi` is a standalone
# module under a WASI host — the "instead of a container" shape — and `wjs` is
# the same lowering under V8, which is the Cloudflare-Worker shape. Holding the
# compiler fixed and varying only the runtime is what separates the two.
[ "$WASM_ARM" = "1" ] && ARMS+=(wjs)
[ "$WASI_ARM" = "1" ] && ARMS+=(wasi)
# The bun arms need NO build of their own: they run the files arms 1 and 3 emit.
[ "$BUN_ARM" = "1" ] && ARMS+=(tbun xbun)
[ "$PERRY_ARM" = "1" ] && ARMS+=(perry)

# The banner is the positive control on the instrument: a run says where it ran.
placement_of() {                                # placement_of <arm>
    if [ -n "$PIN" ] && [ "$1" != "kir" ]; then echo "taskset -c $PIN"; else echo "os scheduler"; fi
}
if [ -n "$PIN" ]; then
    taskset -c "$PIN" true 2> /dev/null || die "KIR_BENCH_PIN='$PIN' is not a usable CPU list for taskset"
fi
echo "kir-bench: placement:" >&2
for arm in "${ARMS[@]}"; do echo "  $arm: $(placement_of "$arm")" >&2; done

echo "kir-bench: building ${#ARMS[@]} arms (${ARMS[*]}) ..." >&2
for lib in mitt toml; do
    for compiler in tsgo xtsc; do
        dir="$WORK/$compiler-$lib"
        rm -rf "$dir"; cp -r "$WORK/src-$lib" "$dir"
        emitting_tsconfig "$lib" "$dir"
        if [ "$compiler" = tsgo ]; then
            "$TSGO" -p "$dir" > "$WORK/emit-$compiler-$lib.log" 2>&1
        else
            java -Xss4m -cp "$KIR_CP" com.xemantic.typescript.tsgo.cli.TsgoMainKt -p "$dir" \
                > "$WORK/emit-$compiler-$lib.log" 2>&1
        fi
        [ -f "$dir/out/main.js" ] || die "$compiler emitted no main.js for $lib — see $WORK/emit-$compiler-$lib.log"
        runnable_esm "$dir/out"
    done
    # The port emits what tsgo emits (the emit differential's invariant); a
    # difference here is a defect to chase, never a faster arm.
    diff -r "$WORK/tsgo-$lib/out" "$WORK/xtsc-$lib/out" > "$WORK/emit-diff-$lib.log" 2>&1 \
        || die "xtsc's JavaScript for $lib differs from tsgo's — see $WORK/emit-diff-$lib.log"
    java -Xmx4g -cp "$KIR_CP:$WORK" KirBench "$WORK/src-$lib" main.ts "$WORK/jvm-$lib" \
        > "$WORK/emit-kir-$lib.log" 2>&1
    grep -q '^KIR_SUCCESS=true' "$WORK/emit-kir-$lib.log" \
        || die "the KIR backend did not compile $lib — see $WORK/emit-kir-$lib.log"
    # The NATIVE arm goes through the SAME lowering as the JVM one — the whole
    # point of § 6's claim that a backend is a phase rather than a compiler — so
    # it is built from the same assembled sources by the same Gradle task.
    if [ "$NATIVE" = "1" ]; then
        # konanc appends `.kexe` to whatever `-o` names, which the task's own
        # closing line says and this had to learn the hard way.
        rm -f "$WORK/native-$lib.kexe"
        "$REPO/scripts/kir-native.sh" "$WORK/src-$lib" main.ts "$WORK/native-$lib" \
            > "$WORK/emit-nat-$lib.log" 2>&1
        # A konanc run that finds no plugin exits 0 having compiled the empty
        # seed, so the binary EXISTING is not the check — the task's own
        # positive control is, and a missing file is a second, cheaper one.
        [ -x "$WORK/native-$lib.kexe" ] \
            || die "no native binary for $lib — see $WORK/emit-nat-$lib.log"
    fi
    # The WASM arms go through the same lowering again — `scripts/kir-wasm.sh`
    # carries its own positive control that the plugin ran, so a module built
    # from the empty seed cannot reach the gate below.
    # The PERRY arm compiles the SAME assembled sources, in a copy of its own:
    # perry writes a node_modules/.cache into the directory it runs in.
    if [ "$PERRY_ARM" = "1" ]; then
        rm -rf "$WORK/perry-src-$lib"; cp -r "$WORK/src-$lib" "$WORK/perry-src-$lib"
        rm -f "$WORK/perry-$lib"
        (cd "$WORK/perry-src-$lib" && PERRY_LLVM_CLANG="$PERRY_CLANG" "$PERRY" compile src/main.ts \
            -o "$WORK/perry-$lib") > "$WORK/emit-perry-$lib.log" 2>&1
        [ -x "$WORK/perry-$lib" ] || die "perry built no binary for $lib — see $WORK/emit-perry-$lib.log"
    fi
    if [ "$WASM_ARM" = "1" ] || [ "$WASI_ARM" = "1" ]; then
        targets=()
        [ "$WASM_ARM" = "1" ] && targets+=(wasm-js)
        [ "$WASI_ARM" = "1" ] && targets+=(wasm-wasi)
        for target in "${targets[@]}"; do
            rm -rf "$WORK/$target-$lib"
            "$REPO/scripts/kir-wasm.sh" "$WORK/src-$lib" main.ts \
                "$WORK/$target-$lib" "$lib" "$target" \
                > "$WORK/emit-$target-$lib.log" 2>&1 \
                || die "no $target module for $lib — see $WORK/emit-$target-$lib.log"
        done
    fi
done

# ---- arms ------------------------------------------------------------------
run_arm() {                                    # run_arm <arm> <lib>
    local pin=()
    [ -n "$PIN" ] && [ "$1" != "kir" ] && pin=(taskset -c "$PIN")
    case "$1" in
        tsgo) "${pin[@]}" "$NODE" "$WORK/tsgo-$2/out/main.js" ;;
        xtsc) "${pin[@]}" "$NODE" "$WORK/xtsc-$2/out/main.js" ;;
        tbun) "${pin[@]}" "$BUN"  "$WORK/tsgo-$2/out/main.js" ;;
        xbun) "${pin[@]}" "$BUN"  "$WORK/xtsc-$2/out/main.js" ;;
        kir)  java -cp "$WORK/jvm-$2:$RUN_CP_TAIL" program.MainKt ;;
        nat)  "${pin[@]}" "$WORK/native-$2.kexe" ;;
        wasi) "${pin[@]}" "$WASMTIME" $WASMTIME_FLAGS "$WORK/wasm-wasi-$2/$2.wasm" ;;
        wjs)  "${pin[@]}" "$NODE" "$WORK/wasm-js-$2/$2.mjs" ;;
        perry) "${pin[@]}" "$WORK/perry-$2" ;;
    esac
}

# EQUIVALENCE BEFORE TIMING. One `sink=` per arm; a disagreement means the arms
# are not running the same program and no timing below would mean anything.
echo "kir-bench: equivalence gate ..." >&2
EQUIV="$WORK/equivalence.tsv"; : > "$EQUIV"
for lib in mitt toml; do
    sinks=""
    for arm in "${ARMS[@]}"; do
        out="$(run_arm "$arm" "$lib")" || die "arm '$arm' failed to run $lib"
        s="$(grep -o 'sink=[-0-9]*' <<< "$out")"
        [ -n "$s" ] || die "arm '$arm' printed no sink for $lib: $out"
        sinks="$sinks $arm:$s"
        printf '%s\t%s\t%s\n' "$lib" "$arm" "${s#sink=}" >> "$EQUIV"
    done
    distinct="$(tr ' ' '\n' <<< "$sinks" | sed 's/.*sink=//' | sort -u | grep -c .)"
    [ "$distinct" = 1 ] || die "the arms disagree on $lib —$sinks"
    echo "  $lib: all ${#ARMS[@]} arms agree ($(sed 's/.* //' <<< "$sinks"))" >&2
done

# ---- steady-state throughput ----------------------------------------------
TSV="$WORK/throughput.tsv"; : > "$TSV"
echo "kir-bench: $PROCESSES processes per arm, interleaved ..." >&2
for i in $(seq 1 "$PROCESSES"); do
    for lib in mitt toml; do
        for arm in "${ARMS[@]}"; do
            printf '%s\t%s\t%s\n' "$arm" "$lib" \
                "$(run_arm "$arm" "$lib" | grep -o 'best_ms=[0-9]*' | cut -d= -f2)" >> "$TSV"
        done
    done
    echo "  cycle $i/$PROCESSES" >&2
done

JSON="$WORK/kir-bench.json"
KB_COMMIT="$(git -C "$REPO" rev-parse HEAD)" \
KB_DIRTY="$(git -C "$REPO" status --porcelain | grep -c . || true)" \
KB_CPU="$(grep -m1 'model name' /proc/cpuinfo | sed 's/.*: //')" \
KB_CORES="$(nproc)" \
KB_MEM_GB="$(awk '/MemTotal/ {printf "%.1f", $2 / 1048576}' /proc/meminfo)" \
KB_JAVA="$(java -version 2>&1 | head -1 | sed 's/"//g')" \
KB_NODE="$("$NODE" --version)" \
KB_PERRY="$([ "$PERRY_ARM" = "1" ] && "$PERRY" --version)" \
KB_ARMS="${ARMS[*]}" KB_PROCESSES="$PROCESSES" KB_PIN="$PIN" \
python3 - "$TSV" "$EQUIV" "$JSON" <<'PY'
import collections, datetime, json, os, statistics, sys
rows = collections.defaultdict(list)
for line in open(sys.argv[1]):
    arm, lib, ms = line.split()
    rows[(lib, arm)].append(int(ms))
equivalence = collections.defaultdict(dict)
for line in open(sys.argv[2]):
    lib, arm, sink = line.split()
    equivalence[lib][arm] = sink
UNIT = {"mitt": (4_000_000, 1e6, "ns/emit"), "toml": (20_000, 1e3, "us/parse")}
NAME = {"tsgo": "tsgo  -> JS     -> node", "xtsc": "xtsc  -> JS     -> node",
        "kir":  "xtsc  -> JVM    -> java", "nat": "xtsc  -> NATIVE -> kexe",
        "wasi": "xtsc  -> WASM   -> wasmtime", "wjs": "xtsc  -> WASM   -> node",
        "tbun": "tsgo  -> JS     -> bun ", "xbun": "xtsc  -> JS     -> bun ",
        "perry": "perry -> NATIVE -> elf"}
ORDER = [a for a in ("tsgo", "tbun", "perry", "xtsc", "xbun", "kir", "nat", "wasi", "wjs")
         if (("mitt", a) in rows)]
for lib in ("mitt", "toml"):
    ops, scale, unit = UNIT[lib]
    base = statistics.median(rows[(lib, "tsgo")])
    print(f"\n{lib}  ({ops:,} ops/round, median of {len(rows[(lib,'tsgo')])} processes)")
    for arm in ORDER:
        v = sorted(rows[(lib, arm)])
        med = statistics.median(v)
        rel = med / base
        tag = "baseline" if arm == "tsgo" else (f"{1/rel:.2f}x faster" if rel < 1 else f"{rel:.2f}x slower")
        print(f"  {NAME[arm]:<26}  {med:7.0f} ms  {med*scale/ops:8.2f} {unit}  "
              f"[{v[0]}..{v[-1]}]  {tag}")
# (BENCH.3) The run as data, schema 1 — what scripts/kir_bench_report.py renders and refuses.
arms = os.environ["KB_ARMS"].split()
run = {
    "schema": 1,
    "commit": os.environ["KB_COMMIT"],
    "dirty": int(os.environ["KB_DIRTY"] or 0) > 0,
    "utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "processes": int(os.environ["KB_PROCESSES"]),
    "arms": arms,
    "placement": {arm: (f"taskset -c {os.environ['KB_PIN']}" if os.environ["KB_PIN"] and arm != "kir"
                        else "os scheduler") for arm in arms},
    "libs": ["mitt", "toml"],
    "box": {"cpu": os.environ["KB_CPU"], "cores": int(os.environ["KB_CORES"]),
            "mem_gb": float(os.environ["KB_MEM_GB"]), "java": os.environ["KB_JAVA"],
            "node": os.environ["KB_NODE"],
            **({"perry": os.environ["KB_PERRY"]} if os.environ["KB_PERRY"] else {})},
    "units": {lib: {"ops": UNIT[lib][0], "scale": UNIT[lib][1], "name": UNIT[lib][2]} for lib in UNIT},
    "equivalence": {lib: {"verdict": "agree" if len(set(s.values())) == 1 and set(s) == set(arms) else "disagree",
                          "sinks": s} for lib, s in equivalence.items()},
    "rows": [{"lib": lib, "arm": arm, "samples": rows[(lib, arm)],
              "median": statistics.median(rows[(lib, arm)]),
              "sigma": statistics.pstdev(rows[(lib, arm)]),
              "min": min(rows[(lib, arm)]), "max": max(rows[(lib, arm)])}
             for lib in ("mitt", "toml") for arm in arms if (lib, arm) in rows],
}
json.dump(run, open(sys.argv[3], "w"), indent=1)
PY
if [ "$RECORD" = "1" ]; then
    stamp="$(date -u +%Y%m%dT%H%M%SZ)-$(git -C "$REPO" rev-parse --short=12 HEAD)"
    mkdir -p "$REPO/bench-history/kir-runs"
    cp "$JSON" "$REPO/bench-history/kir-runs/$stamp.json"
    python3 "$REPO/scripts/kir_bench_report.py" \
        || die "the report generator REFUSED this run — bench-history/kir-runs/$stamp.json kept for inspection"
    echo "kir-bench: recorded bench-history/kir-runs/$stamp.json" >&2
fi

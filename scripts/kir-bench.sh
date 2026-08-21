#!/usr/bin/env bash
#
# The KIR RUNTIME benchmark: two real libraries, three arms, one source.
#
#   arm 1  tsgo 7.0.2   -> JavaScript   -> node     (what these libraries run on)
#   arm 2  xtsc -kir    -> JVM bytecode -> java     (the Kotlin-IR backend)
#   arm 3  xtsc -core   -> JavaScript   -> node     (OUR JavaScript, same runtime)
#
# Arm 3 is the CONTROL, and it is the reason this file exists rather than a pair
# of ad-hoc commands: arms 1 and 2 differ in BOTH the compiler and the target, so
# a gap between them cannot be attributed. Arm 3 holds the runtime fixed and
# varies only the compiler, which splits "our front end" from "our backend".
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
#
set -uo pipefail

REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PROCESSES="${1:-5}"
WORK="${KIR_BENCH_WORK:-$REPO/build/bench/kir-bench}"
PROJECTS="$REPO/xemantic-typescript-compiler-kir/src/jvmTest/resources/projects"
DRIVERS="$REPO/scripts/kir-bench/drivers"
NODE="${KIR_BENCH_NODE:-$REPO/tools/node/bin/node}"
TSGO="${KIR_BENCH_TSGO:-$REPO/tools/tsgo-7.0.2/lib/tsc}"

die() { echo "kir-bench: $*" >&2; exit 2; }

# REFUSE rather than skip. A benchmark that quietly drops an arm on a box where
# its toolchain is absent reports a two-arm run as a three-arm one (CLAUDE.md,
# rounds 853/873/895 — a gate that passes quietly where it cannot see is the
# thing that keeps being wrong here).
[ -x "$NODE" ] || die "no node at '$NODE' — set KIR_BENCH_NODE. It is NOT in the repo:
  curl -sL https://nodejs.org/dist/v22.20.0/node-v22.20.0-linux-x64.tar.xz | tar xJ -C tools
  mv tools/node-v22.20.0-linux-x64 tools/node"
[ -x "$TSGO" ] || die "no tsgo at '$TSGO' — set KIR_BENCH_TSGO"
[ -d "$PROJECTS/toml" ] || die "no acceptance projects at '$PROJECTS'"

# ---- classpaths ------------------------------------------------------------
# The CORE tail resolves through the shared guard, which refuses a cache older
# than `gradle/libs.versions.toml` (round 858). The KIR module needs its own
# resolution; `findProject`, never `project(":…")`, because an init script
# applies to every build in the invocation and `build-logic` has no such project.
# shellcheck source=scripts/lib/dep-classpath.sh
. "$REPO/scripts/lib/dep-classpath.sh"
CORE_CLASSES="$REPO/xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main"
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
CORE_TAIL="$(xtsc_dep_classpath)" || die "could not resolve the core classpath"

# POSITIVE CONTROL: the code under test must be in the directories we prepend,
# or the run measures whatever else the classpath happens to carry (round 853).
[ -f "$CORE_CLASSES/com/xemantic/typescript/compiler/MainKt.class" ] \
    || die "no MainKt under $CORE_CLASSES — run ./gradlew :xemantic-typescript-compiler-core:compileKotlinJvm"
[ -f "$KIR_CLASSES/com/xemantic/typescript/compiler/kir/TypeScriptToKotlinIrKt.class" ] \
    || die "no KIR classes under $KIR_CLASSES"

CORE_CP="$CORE_CLASSES:$CORE_TAIL"
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
# under `rewriteRelativeImportExtensions` and leaves the extensionless one alone,
# while xtsc implements neither. This is emit POST-PROCESSING for the benchmark's
# sake and is recorded as a finding, not a fix (see (BENCH.1) in PLAN-PHASE-5.md).
runnable_esm() {                               # runnable_esm <out-dir>
    sed -i "s#\(from '\./[^']*\)\.ts'#\1.js'#g" "$1"/*.js
    sed -i "s#\(from '\./[a-zA-Z0-9_-]*\)'#\1.js'#g" "$1"/*.js
}

echo "kir-bench: building the three arms ..." >&2
for lib in mitt toml; do
    for compiler in tsgo xtsc; do
        dir="$WORK/$compiler-$lib"
        rm -rf "$dir"; cp -r "$WORK/src-$lib" "$dir"
        emitting_tsconfig "$lib" "$dir"
        if [ "$compiler" = tsgo ]; then
            "$TSGO" -p "$dir" > "$WORK/emit-$compiler-$lib.log" 2>&1
        else
            java -cp "$CORE_CP" com.xemantic.typescript.compiler.MainKt "$dir" \
                > "$WORK/emit-$compiler-$lib.log" 2>&1
        fi
        [ -f "$dir/out/main.js" ] || die "$compiler emitted no main.js for $lib — see $WORK/emit-$compiler-$lib.log"
        runnable_esm "$dir/out"
    done
    java -Xmx4g -cp "$KIR_CP:$WORK" KirBench "$WORK/src-$lib" main.ts "$WORK/jvm-$lib" \
        > "$WORK/emit-kir-$lib.log" 2>&1
    grep -q '^KIR_SUCCESS=true' "$WORK/emit-kir-$lib.log" \
        || die "the KIR backend did not compile $lib — see $WORK/emit-kir-$lib.log"
done

# ---- arms ------------------------------------------------------------------
run_arm() {                                    # run_arm <arm> <lib>
    case "$1" in
        tsgo) "$NODE" "$WORK/tsgo-$2/out/main.js" ;;
        xtsc) "$NODE" "$WORK/xtsc-$2/out/main.js" ;;
        kir)  java -cp "$WORK/jvm-$2:$RUN_CP_TAIL" program.MainKt ;;
    esac
}

# EQUIVALENCE BEFORE TIMING. Three arms, one `sink=` each; disagreement means the
# arms are not running the same program and no timing below would mean anything.
echo "kir-bench: equivalence gate ..." >&2
for lib in mitt toml; do
    sinks=""
    for arm in tsgo xtsc kir; do
        out="$(run_arm "$arm" "$lib")" || die "arm '$arm' failed to run $lib"
        s="$(grep -o 'sink=[-0-9]*' <<< "$out")"
        [ -n "$s" ] || die "arm '$arm' printed no sink for $lib: $out"
        sinks="$sinks $arm:$s"
    done
    distinct="$(tr ' ' '\n' <<< "$sinks" | sed 's/.*sink=//' | sort -u | grep -c .)"
    [ "$distinct" = 1 ] || die "the arms disagree on $lib —$sinks"
    echo "  $lib: all three arms agree ($(sed 's/.* //' <<< "$sinks"))" >&2
done

# ---- steady-state throughput ----------------------------------------------
TSV="$WORK/throughput.tsv"; : > "$TSV"
echo "kir-bench: $PROCESSES processes per arm, interleaved ..." >&2
for i in $(seq 1 "$PROCESSES"); do
    for lib in mitt toml; do
        for arm in tsgo xtsc kir; do
            printf '%s\t%s\t%s\n' "$arm" "$lib" \
                "$(run_arm "$arm" "$lib" | grep -o 'best_ms=[0-9]*' | cut -d= -f2)" >> "$TSV"
        done
    done
    echo "  cycle $i/$PROCESSES" >&2
done

python3 - "$TSV" <<'PY'
import collections, statistics, sys
rows = collections.defaultdict(list)
for line in open(sys.argv[1]):
    arm, lib, ms = line.split()
    rows[(lib, arm)].append(int(ms))
UNIT = {"mitt": (4_000_000, 1e6, "ns/emit"), "toml": (20_000, 1e3, "us/parse")}
NAME = {"tsgo": "tsgo  -> JS   -> node", "xtsc": "xtsc  -> JS   -> node",
        "kir":  "xtsc  -> JVM  -> java"}
for lib in ("mitt", "toml"):
    ops, scale, unit = UNIT[lib]
    base = statistics.median(rows[(lib, "tsgo")])
    print(f"\n{lib}  ({ops:,} ops/round, median of {len(rows[(lib,'tsgo')])} processes)")
    for arm in ("tsgo", "xtsc", "kir"):
        v = sorted(rows[(lib, arm)])
        med = statistics.median(v)
        rel = med / base
        tag = "baseline" if arm == "tsgo" else (f"{1/rel:.2f}x faster" if rel < 1 else f"{rel:.2f}x slower")
        print(f"  {NAME[arm]}  {med:7.0f} ms  {med*scale/ops:8.2f} {unit}  "
              f"[{v[0]}..{v[-1]}]  {tag}")
PY

#!/usr/bin/env bash
# (TSGO.6-f) Profile the port on the JVM with async-profiler (docs/goport-perf.md § 7).
#
#   scripts/tsgo-jvm-profile.sh warm <tsconfig.json> <cpu|alloc> [single|parallel] [delay_s=45] [dur_s=30]
#   scripts/tsgo-jvm-profile.sh cold <project dir> cpu
#
# warm: CheckBenchMain rebuilds the program forever in one JVM; the profiler ATTACHES after `delay_s`
#       (past JIT warm-up) for `dur_s`, so the samples are steady-state rebuilds.
# cold: one `tsc --noEmit -p <dir>` run (TsgoMain) with the agent from JVM start, all threads,
#       Java frames annotated (interpreted / C1 / C2) — the JIT ramp IS the subject.
# Output: build/tsgo-jvm-profile/<mode>-<event>.collapsed; read it with scripts/tsgo_ap_stacks.py.
# async-profiler 4.1 is downloaded once into tools/async-profiler (gitignored). Needs
# kernel.perf_event_paranoid <= 2 for `cpu` (1 on this box; a reboot resets it). The classpath is
# printed by Gradle (serialized with flock on $GRADLE_LOCK) and must be built beforehand.
set -euo pipefail
REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MODE=$1; TARGET=$2; EVENT=$3; THREADS=${4:-single}; DELAY=${5:-45}; DUR=${6:-30}
OUT="$REPO/build/tsgo-jvm-profile"; mkdir -p "$OUT"
AP="$REPO/tools/async-profiler"
if [[ ! -x "$AP/bin/asprof" ]]; then
  mkdir -p "$AP"
  curl -sSL https://github.com/async-profiler/async-profiler/releases/download/v4.1/async-profiler-4.1-linux-x64.tar.gz \
    | tar xz -C "$AP" --strip-components=1
fi
GRADLE_LOCK="${GRADLE_LOCK:-/tmp/xtsc-gradle.lock}"
INIT="$OUT/print-cp.init.gradle.kts"
cat > "$INIT" <<'G'
rootProject {
    findProject(":xemantic-typescript-compiler-tsgo")?.let { p ->
        p.tasks.register("printJvmProfileClasspath") {
            dependsOn("jvmTestClasses")
            val cp = p.configurations.getByName("jvmTestRuntimeClasspath")
            doLast {
                val dirs = listOf("classes/kotlin/jvm/main", "classes/kotlin/jvm/test", "processedResources/jvm/main", "processedResources/jvm/test")
                    .map { p.layout.buildDirectory.dir(it).get().asFile }
                println("JVMPROFILE_CP=" + (dirs + cp.files).joinToString(":"))
            }
        }
    }
}
G
(cd "$REPO" && flock "$GRADLE_LOCK" ./gradlew -q -I "$INIT" :xemantic-typescript-compiler-tsgo:printJvmProfileClasspath) > "$OUT/gradle.log" 2>&1
[[ $(grep -ac '^JVMPROFILE_CP=' "$OUT/gradle.log") == 1 ]] || { cat "$OUT/gradle.log"; exit 1; }
CP=$(grep -a '^JVMPROFILE_CP=' "$OUT/gradle.log" | sed 's/^JVMPROFILE_CP=//')
export XTSC_TSGO_LIB_DIR="$REPO/tools/tsgo-7.0.2/lib"
case "$MODE" in
  warm)
    java -Xms2g -Xmx6g -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -cp "$CP" \
      com.xemantic.typescript.tsgo.CheckBenchMainKt "$TARGET" 1000 0 nolib "$THREADS" > "$OUT/warm-run.log" 2>&1 &
    PID=$!
    sleep "$DELAY"
    if [[ $EVENT == alloc ]]; then "$AP/bin/asprof" -d "$DUR" -e alloc --alloc 512k -o collapsed -f "$OUT/warm-alloc.collapsed" $PID
    else "$AP/bin/asprof" -d "$DUR" -e cpu -i 1ms --threads -o collapsed -f "$OUT/warm-cpu.collapsed" $PID; fi
    kill $PID; wait $PID 2>/dev/null || true
    # positive control: the rebuilds the profile covered must agree with each other (diagnostic count + digest)
    grep '^iter' "$OUT/warm-run.log" | awk '{print $(NF-1), $NF}' | sort | uniq -c
    ;;
  cold)
    cd "$TARGET"
    java -Xss4m -agentpath:"$AP/lib/libasyncProfiler.so"=start,event=cpu,interval=1ms,threads,ann,file="$OUT/cold-cpu.collapsed",collapsed \
      -cp "$CP" com.xemantic.typescript.tsgo.cli.TsgoMainKt --noEmit -p . --pretty false > "$OUT/cold-run.log" 2>&1 || true
    grep -c 'error TS' "$OUT/cold-run.log" || true
    ;;
  *) echo "mode: warm|cold" >&2; exit 2;;
esac
ls -la "$OUT"/*.collapsed

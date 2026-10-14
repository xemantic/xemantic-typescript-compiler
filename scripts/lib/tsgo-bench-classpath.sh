#!/usr/bin/env bash
# The `-tsgo` port's JVM classpath for a measurement: main + test classes (the warm harness
# `CheckBenchMain` lives in jvmTest) and Gradle's `jvmTestRuntimeClasspath`, resolved by Gradle itself
# through an init script, so no jar list is ever cached or hand-written (scripts/lib/dep-classpath.sh
# records what a frozen list cost).
#
# USE, sourced (compiles jvmTest; run it BEFORE stopping the Gradle daemons):
#     . "$REPO_ROOT/scripts/lib/tsgo-bench-classpath.sh"
#     CP="$(xtsc_tsgo_bench_classpath "$WORK")" || exit 1
xtsc_tsgo_bench_classpath() { # $1 = a scratch directory ; prints the ':'-joined classpath
    local work="$1" root
    root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
    cat > "$work/print-cp.init.gradle.kts" <<'G'
rootProject {
    findProject(":xemantic-typescript-compiler-tsgo")?.let { p ->
        p.tasks.register("printBenchClasspath") {
            dependsOn("jvmTestClasses")
            val cp = p.configurations.getByName("jvmTestRuntimeClasspath")
            doLast {
                val dirs = listOf("classes/kotlin/jvm/main", "classes/kotlin/jvm/test", "processedResources/jvm/main", "processedResources/jvm/test")
                    .map { p.layout.buildDirectory.dir(it).get().asFile }
                println("BENCH_CP=" + (dirs + cp.files).joinToString(":"))
            }
        }
    }
}
G
    (cd "$root" && ./gradlew -q --console=plain -I "$work/print-cp.init.gradle.kts" \
        :xemantic-typescript-compiler-tsgo:printBenchClasspath) > "$work/cp.log" 2>&1 \
        || { cat "$work/cp.log" >&2; return 1; }
    [[ $(grep -ac '^BENCH_CP=' "$work/cp.log") == 1 ]] || { cat "$work/cp.log" >&2; return 1; }
    grep -a '^BENCH_CP=' "$work/cp.log" | sed 's/^BENCH_CP=//'
}

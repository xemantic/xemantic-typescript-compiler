#!/usr/bin/env bash
# aot-corpus-suite - run the WHOLE corpus suite in one plain `java` process, with
# and without the shipped AOT cache, and diff the two per-class result files.
#
# Landed round 839 to close the "the corpus suite has never been run through a
# cached JVM" residue of docs/perf/aot-cache.md. Re-run it after any change to
# the launcher, the cache contract or the JDK.
#
#   scripts/aot-corpus-suite.sh          (needs: ./gradlew jvmJar jvmTestClasses,
#                                          then scripts/xtsc-aot train <project>)
#
# Gradle's test worker cannot carry an AOT arm, so the suite is driven by
# CorpusRunner in one plain `java` process. The classpath is the TRAINED one
# (jar + the 7 dependency jars, in that exact order) with the test classes and
# the JUnit jars APPENDED — CDS/AOT accepts a runtime classpath that has the
# dump-time one as a prefix, which is what makes the shipped cache usable here.
set -uo pipefail
cd "$(dirname -- "${BASH_SOURCE[0]}")/.."
O=build/aot-suite-check
mkdir -p "$O"
JAR=build/libs/xemantic-typescript-compiler-jvm-0.1.0-SNAPSHOT.jar
G=~/.gradle/caches/modules-2/files-2.1
TESTJARS="$(find $G/org.jetbrains.kotlin/kotlin-test-junit/2.4.10 -name '*.jar' | head -1)"
TESTJARS="$TESTJARS:$(find $G/org.jetbrains.kotlin/kotlin-test/2.4.10 -name '*.jar' | grep -v sources | head -1)"
TESTJARS="$TESTJARS:$(find $G/junit/junit/4.13.2 -name '*.jar' | head -1)"
TESTJARS="$TESTJARS:$(find $G/org.hamcrest/hamcrest-core/1.3 -name '*.jar' | head -1)"
TESTJARS="$TESTJARS:$(find $G/com.xemantic.kotlin/xemantic-kotlin-test-jvm/1.17.5 -name '*.jar' | head -1)"
TESTJARS="$TESTJARS:$(find $G/org.jetbrains.kotlin/kotlin-stdlib/2.4.10 -name 'kotlin-stdlib-2.4.10.jar' | head -1)"
# The power-assert RUNTIME (kotlin.powerassert.CallExplanation) is a separate
# artifact from the stdlib; without it every `assert`/`have` in the suite dies
# with NoClassDefFoundError - 4,998 of the 13,897.
TESTJARS="$TESTJARS:$(find $G/org.jetbrains.kotlin/kotlin-power-assert-runtime-jvm/2.4.10 -name '*.jar' | grep -v sources | head -1)"
# Appended last so the jar still wins for class loading and the AOT prefix is
# untouched. EXPECT 10 FAILURES IN BOTH ARMS, and only these: 8 in
# AotCacheGuardTest ("URI is not hierarchical") and 2 in HugeMethodLimitTest
# ("main classes are not a directory on the classpath"). Both read the classpath
# LAYOUT and need Gradle's exploded dir; this harness runs from the jar by
# construction, because a jar is what an AOT cache can be trained from at all.
# They are green under `./gradlew jvmTest`. What matters here is that the two
# arms agree, not that the count is zero.
CP="$JAR:$(cat build/bench/cp.txt):build/classes/kotlin/jvm/test:$TESTJARS:$O/classes:build/classes/kotlin/jvm/main"
CACHE="$(ls ~/.cache/xtsc/xtsc-*.aot 2>/dev/null | head -1)"
echo "cache = $CACHE"

echo "=== $(date -u +%T) compile the runner"
mkdir -p "$O/classes"
javac -cp "$(find $G/junit/junit/4.13.2 -name '*.jar' | head -1)" -d "$O/classes" scripts/CorpusRunner.java || exit 1

echo "=== $(date -u +%T) smoke: one class, uncached then cached"
java -Xmx4g -cp "$CP" CorpusRunner build/classes/kotlin/jvm/test "$O/smoke.plain.txt" DeepStackHandoffTest 2>&1 | tail -3
java -XX:AOTCache="$CACHE" -Xlog:aot*=off:stdout -Xmx4g -cp "$CP" CorpusRunner build/classes/kotlin/jvm/test "$O/smoke.cached.txt" DeepStackHandoffTest 2>&1 | tail -3
if ! grep -aq 'run=[1-9]' "$O/smoke.plain.txt"; then echo "SMOKE FAILED - classpath wrong, aborting"; cat "$O/smoke.plain.txt"; exit 1; fi

echo "=== $(date -u +%T) suite, UNCACHED"
/usr/bin/time -v java -Xmx4g -cp "$CP" CorpusRunner build/classes/kotlin/jvm/test "$O/suite.plain.txt" \
  > "$O/suite.plain.log" 2>&1
tail -3 "$O/suite.plain.log"; grep -a '^TOTAL' "$O/suite.plain.txt"

echo "=== $(date -u +%T) suite, CACHED (with a class-load log, to prove the cache is USED)"
/usr/bin/time -v java -XX:AOTCache="$CACHE" -Xlog:aot*=off:stdout -Xlog:class+load=info:file="$O/classload.txt" \
  -Xmx4g -cp "$CP" CorpusRunner build/classes/kotlin/jvm/test "$O/suite.cached.txt" \
  > "$O/suite.cached.log" 2>&1
tail -3 "$O/suite.cached.log"; grep -a '^TOTAL' "$O/suite.cached.txt"

echo "=== $(date -u +%T) REPORT"
echo "--- com.xemantic classes served from the AOT cache during the CACHED suite run:"
tot=$(grep -ac 'com.xemantic' "$O/classload.txt")
sh=$(grep -a 'com.xemantic' "$O/classload.txt" | grep -ac 'shared objects file')
echo "    $sh of $tot"
echo "--- per-class result diff (plain vs cached), FAILURE lines included:"
diff "$O/suite.plain.txt" "$O/suite.cached.txt" && echo "    EMPTY"
echo "--- wall:"
grep -a 'Elapsed (wall clock)' "$O/suite.plain.log" "$O/suite.cached.log"
grep -a 'Maximum resident' "$O/suite.plain.log" "$O/suite.cached.log"
echo "=== $(date -u +%T) stage3 done"

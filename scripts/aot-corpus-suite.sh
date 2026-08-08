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
# CorpusRunner in one plain `java` process. The classpath STARTS with the TRAINED
# one, verbatim, with the test classes and the JUnit jars APPENDED — CDS/AOT
# accepts a runtime classpath that has the dump-time one as a prefix, which is
# what makes the shipped cache usable here.
#
# ROUND 857 (MOD.7): that prefix is now READ FROM THE STAGED LIB DIR, exactly as
# `scripts/xtsc-aot-lib.sh` resolves it (`find -maxdepth 1 -name '*.jar' |
# LC_ALL=C sort`) — never rebuilt by hand. It used to be `<core jar>:$(cat
# build/bench/cp.txt)`, i.e. the COMPILER's dependency tail in the compiler's
# order, which the module split turned into something that is NOT a prefix of the
# trained classpath: post-split the training run sees 14 jars beginning with
# annotations-23.0.0.jar and including ktor, slf4j and the -api/-daemon jars,
# while cp.txt lists 7 and is itself stale. The failure mode is the round-842 one
# — the JVM quietly declines the cache in AOTMode=auto and BOTH arms run
# uncached, agreeing perfectly and proving nothing — which is why the class-load
# count below is now a hard assertion rather than a printed number.
set -uo pipefail
cd "$(dirname -- "${BASH_SOURCE[0]}")/.."
O=build/aot-suite-check
mkdir -p "$O"
LIB=xemantic-typescript-compiler-daemon/build/install/lib
[ -d "$LIB" ] || { echo "error: no $LIB — run ./gradlew assemble first" >&2; exit 1; }
LIBCP="$(find "$LIB" -maxdepth 1 -name '*.jar' | LC_ALL=C sort | tr '\n' ':')"; LIBCP="${LIBCP%:}"
JAR="$(find "$LIB" -maxdepth 1 -name 'xemantic-typescript-compiler-jvm-*.jar' | head -1)"
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
# untouched. EXPECT A SMALL, IDENTICAL FAILURE COUNT IN BOTH ARMS, from the pins
# that read the classpath LAYOUT and need Gradle's exploded dir — this harness
# runs from the jar by construction, because a jar is what an AOT cache can be
# trained from at all. Before the module split that was 13 (11 AotCacheGuardTest
# + 2 HugeMethodLimitTest); AotCacheGuardTest moved to the daemon module at
# MOD.4, so it is no longer among the CORE test classes this harness runs.
# They are green under `./gradlew jvmTest`. What matters here is that the two
# arms agree, not that the count is zero.
MAIN_CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main
TEST_CLASSES=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test
# A missing entry here does NOT fail the run — it produces two arms that agree
# because both are equally broken, which is exactly the shape this harness is
# supposed to detect.
for d in "$JAR" "$MAIN_CLASSES" "$TEST_CLASSES"; do
  [ -e "$d" ] || { echo "error: missing classpath entry $d — build first" >&2; exit 1; }
done
CP="$LIBCP:$TEST_CLASSES:$TESTJARS:$O/classes:$MAIN_CLASSES"
CACHE="$(ls ~/.cache/xtsc/xtsc-*.aot 2>/dev/null | head -1)"
echo "cache = $CACHE"

echo "=== $(date -u +%T) compile the runner"
mkdir -p "$O/classes"
javac -cp "$(find $G/junit/junit/4.13.2 -name '*.jar' | head -1)" -d "$O/classes" scripts/CorpusRunner.java || exit 1

echo "=== $(date -u +%T) smoke: one class, uncached then cached"
java -Xmx4g -cp "$CP" CorpusRunner "$TEST_CLASSES" "$O/smoke.plain.txt" DeepStackHandoffTest 2>&1 | tail -3
java -XX:AOTCache="$CACHE" -Xlog:aot*=off:stdout -Xmx4g -cp "$CP" CorpusRunner "$TEST_CLASSES" "$O/smoke.cached.txt" DeepStackHandoffTest 2>&1 | tail -3
if ! grep -aq 'run=[1-9]' "$O/smoke.plain.txt"; then echo "SMOKE FAILED - classpath wrong, aborting"; cat "$O/smoke.plain.txt"; exit 1; fi

echo "=== $(date -u +%T) suite, UNCACHED"
/usr/bin/time -v java -Xmx4g -cp "$CP" CorpusRunner "$TEST_CLASSES" "$O/suite.plain.txt" \
  > "$O/suite.plain.log" 2>&1
tail -3 "$O/suite.plain.log"; grep -a '^TOTAL' "$O/suite.plain.txt"

echo "=== $(date -u +%T) suite, CACHED (with a class-load log, to prove the cache is USED)"
/usr/bin/time -v java -XX:AOTCache="$CACHE" -Xlog:aot*=off:stdout -Xlog:class+load=info:file="$O/classload.txt" \
  -Xmx4g -cp "$CP" CorpusRunner "$TEST_CLASSES" "$O/suite.cached.txt" \
  > "$O/suite.cached.log" 2>&1
tail -3 "$O/suite.cached.log"; grep -a '^TOTAL' "$O/suite.cached.txt"

echo "=== $(date -u +%T) REPORT"
echo "--- com.xemantic classes served from the AOT cache during the CACHED suite run:"
tot=$(grep -ac 'com.xemantic' "$O/classload.txt")
sh=$(grep -a 'com.xemantic' "$O/classload.txt" | grep -ac 'shared objects file')
echo "    $sh of $tot"
# THE A/A DETECTOR (round 842's trap, round 857's fix). In AOTMode=auto a cache
# the JVM declines - a stale one, or one whose dump-time classpath is not a
# prefix of this run's - produces a diagnostic and a perfectly normal run, so the
# two arms agree and the whole harness passes while measuring nothing. A served
# count of zero is that state and it must be loud.
if [ "$sh" -eq 0 ]; then
  echo "ERROR: the CACHED arm served ZERO classes from the cache — this was an A/A." >&2
  echo "       Retrain after the last build: scripts/xtsc-aot train <project-dir>" >&2
  exit 1
fi
echo "--- per-class result diff (plain vs cached), FAILURE lines included:"
diff "$O/suite.plain.txt" "$O/suite.cached.txt" && echo "    EMPTY"
echo "--- wall:"
grep -a 'Elapsed (wall clock)' "$O/suite.plain.log" "$O/suite.cached.log"
grep -a 'Maximum resident' "$O/suite.plain.log" "$O/suite.cached.log"
echo "=== $(date -u +%T) stage3 done"

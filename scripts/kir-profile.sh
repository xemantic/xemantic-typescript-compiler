#!/usr/bin/env bash
#
# A LEAF-FRAME profile of one compiled library, on the JVM arm.
#
# The instrument that named the four levers in PLAN-PHASE-5.md's 2026-08-21
# entry, made repeatable. It profiles the program `scripts/kir-bench.sh` already
# compiled — never a fresh compile — so the thing measured is the one the
# benchmark timed, and the two can be read against each other.
#
# WHY LEAF FRAMES. The generated program's own code and the runtime's are
# different packages (`program.*` against `…kir.runtime.*`), so a leaf census
# splits "the library's logic" from "the JavaScript semantics we emulate" with
# no attribution guesswork. Inclusive time would not: everything is inside
# `program.MainKt.main`.
#
# The three warnings CLAUDE.md attaches to any JFR reading apply here and are
# not restated per row: `jfr print` truncates a stack to five frames unless
# told otherwise (so `--stack-depth` is passed and the depth is asserted), a
# leaf share is a share of the RECORDING WINDOW rather than of a parse, and a
# stdlib leaf's attribution is unstable across processes — which is why this
# runs the profile TWICE and prints both.
#
# USE:  scripts/kir-profile.sh [mitt|toml]      (default toml)
#
set -uo pipefail

REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="${1:-toml}"
WORK="${KIR_BENCH_WORK:-$REPO/build/bench/kir-bench}"
CLASSES="$WORK/jvm-$LIB"

die() { echo "kir-profile: $*" >&2; exit 2; }

[ -d "$CLASSES" ] || die "no compiled program at '$CLASSES' — run scripts/kir-bench.sh first"

. "$REPO/scripts/lib/dep-classpath.sh"
KIR_CLASSES="$REPO/xemantic-typescript-compiler-kir/build/classes/kotlin/jvm/main"
STDLIB="$(xtsc_dep_classpath | tr ':' '\n' | grep -m1 'kotlin-stdlib')"
[ -n "$STDLIB" ] || die "no kotlin-stdlib on the classpath"

for round in 1 2; do
    dump="$WORK/profile-$LIB-$round.jfr"
    java -XX:StartFlightRecording="settings=profile,filename=$dump,dumponexit=true" \
        -cp "$CLASSES:$STDLIB:$KIR_CLASSES" program.MainKt > /dev/null 2>&1 \
        || die "the program failed to run"
    jfr print --stack-depth 512 --events jdk.ExecutionSample "$dump" > "$WORK/profile-$LIB-$round.txt"
    python3 - "$WORK/profile-$LIB-$round.txt" "$round" <<'PY'
import collections, re, sys
text = open(sys.argv[1]).read()
samples = text.split("jdk.ExecutionSample {")[1:]
leaves, deepest = collections.Counter(), 0
for sample in samples:
    frames = re.findall(r"^\s+([\w.$]+)\.\w+\(.*\) line:", sample, re.M)
    lines = [l for l in sample.splitlines() if " line: " in l]
    deepest = max(deepest, len(lines))
    match = re.search(r"^\s+([\w.$<>]+\.[\w$<>]+)\(", sample, re.M)
    if match:
        leaves[match.group(1)] += 1
total = sum(leaves.values())
# A truncated stack is not a shallower answer, it is a different one — refuse.
assert deepest < 512, f"stacks hit the {deepest}-frame cap; raise --stack-depth"
print(f"\n  round {sys.argv[2]}: {total} samples, deepest stack {deepest}")
FAMILY = (
    ("the program's own code", lambda n: n.startswith("program.")),
    ("property bag", lambda n: "JsObject" in n),
    ("hash containers", lambda n: "HashMap" in n or "HashSet" in n),
    ("regex", lambda n: "java.util.regex" in n),
    ("boxing", lambda n: n in ("java.lang.Double.valueOf", "java.lang.Integer.valueOf")),
    ("string equality", lambda n: n in ("java.lang.String.equals", "kotlin.jvm.internal.Intrinsics.areEqual")),
    ("dynamic call", lambda n: "JsRuntimeKt.jsCall" in n or "TypeIntrinsics" in n),
    ("the rest of the runtime", lambda n: ".kir.runtime." in n),
)
claimed = set()
for label, predicate in FAMILY:
    rows = [(n, c) for n, c in leaves.items() if n not in claimed and predicate(n)]
    for n, _ in rows:
        claimed.add(n)
    count = sum(c for _, c in rows)
    if count:
        print(f"    {label:26s} {100*count/total:5.1f}%  {count}")
rest = total - sum(c for n, c in leaves.items() if n in claimed)
print(f"    {'everything else':26s} {100*rest/total:5.1f}%  {rest}")
print("    top leaves:")
for name, count in leaves.most_common(12):
    print(f"      {100*count/total:5.1f}%  {name}")
PY
done

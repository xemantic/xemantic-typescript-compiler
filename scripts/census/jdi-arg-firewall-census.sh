#!/usr/bin/env bash
# (P18.183) driver for JdiArgFirewallCensus.java — see that file's header.
# usage: jdi-arg-firewall-census.sh <frozenClassDir> <projectAbsDir> <outFile> [port]
set -uo pipefail
cd "$(dirname "$0")/../.."
CLS="$1"; PROJ="$2"; OUT="$3"; PORT="${4:-5912}"
[[ "$CLS" == *xemantic-typescript-compiler-core/build/classes* ]] && { echo "REFUSED: use a FROZEN copy of the class dir"; exit 1; }
J=build/census-jdi; mkdir -p "$J"
javac -d "$J" scripts/census/JdiArgFirewallCensus.java || exit 1
DEPS="$(scripts/lib/dep-classpath.sh --print)" || exit 1
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:$PORT -Xmx4g -cp "$CLS:$DEPS" \
  com.xemantic.typescript.compiler.MainKt --noEmit --listAll "$PROJ" > "$OUT.compile.txt" 2>&1 &
java -cp "$J" JdiArgFirewallCensus "$PORT" "$OUT"
wait
grep -a 'diagnostics:' "$OUT.compile.txt"

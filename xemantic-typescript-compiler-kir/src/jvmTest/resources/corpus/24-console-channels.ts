// `console` beyond `log`, and WHICH STREAM each member writes to.
//
// (LIB.4). `cronstrue` warns on a deprecated option and on an unknown locale;
// both must stay off stdout, or the answer of any program that pipes the
// library's output is corrupted by a diagnostic.
//
// This program's `.expected` is its STDOUT, so the `warn` and `error` lines
// are pinned by their ABSENCE from it — which is the same thing `node` prints
// on stdout for this file.

console.log("log goes to stdout");
console.info("info goes to stdout");
console.debug("debug goes to stdout");
console.warn("warn does NOT go to stdout");
console.error("error does NOT go to stdout");
console.log("two", "arguments", 3);
console.log("after the warnings");

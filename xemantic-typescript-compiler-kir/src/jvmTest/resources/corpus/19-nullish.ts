// (LIB.4) rung 4 — nullish coalescing.
//
// `??` is not a spelling of `||`: they differ exactly on the FALSY-but-present
// values, which is what the two adjacent lines of output below pin. `undefined`
// and `null` are one value in this runtime, and one null test covers both —
// which is the same pair JavaScript's own `??` tests for.

function coalesce(value: string | null | undefined): string {
  return value ?? "fallback";
}

// `??` differs from `||` exactly on the FALSY-but-present values, which is the
// whole reason it exists: an empty string and a zero are kept.
function keepsFalsy(value: string): string {
  return "[" + (value ?? "fallback") + "]";
}

function keepsZero(value: number | undefined): number {
  return value ?? -1;
}

function shortCircuits(): string {
  let calls = 0;
  const right = function (): string {
    calls = calls + 1;
    return "right";
  };
  const kept: string | null = "left";
  const a = kept ?? right();
  const b: string | null = null;
  const c = b ?? right();
  return a + "/" + c + "/" + calls;
}

console.log(coalesce("v"));
console.log(coalesce(null));
console.log(coalesce(undefined));
console.log(keepsFalsy(""));
console.log("" || "fallback");
console.log(keepsZero(0));
console.log(keepsZero(undefined));
console.log(shortCircuits());

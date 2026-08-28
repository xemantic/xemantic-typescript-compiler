// A callback may have FEWER parameters than the slot it is stored in.
//
// (LIB.4). `cronstrue` passes `(s) => s` to a
// `(t: string, form?: number) => string` parameter six times over. TypeScript
// allows it; the JVM does not, because `Function1` is not a `Function2` — so
// the value is reshaped where it is STORED, by the same padding-and-dropping
// `jsCall` has always done at the call side.

function take(f: (t: string, form?: number) => string, value: string): string {
  return f(value, 7);
}

function takesThree(f: (a: string, b: string, c: string) => string): string {
  return f("a", "b", "c");
}

// Fewer parameters than the slot: the extra arguments are DROPPED.
function fewer(): string {
  return take((s) => s, "kept");
}

// Exactly as many: no reshaping happens, and the second argument arrives.
function exact(): string {
  return take((s, form) => s + "/" + form, "both");
}

// More parameters than the caller supplies: the missing ones are `undefined`,
// which is `null` here — so the check is for absence, not for a value.
function more(): string {
  return takesThree((a) => a);
}

function droppedArgumentIsNotEvaluatedAway(): string {
  let seen = "";
  const f = (s: string) => {
    seen = seen + s;
    return s;
  };
  take(f, "x");
  take(f, "y");
  return seen;
}

// A function stored in a slot, then read back out and called through the value
// — the adaptation must survive the round trip.
function throughAVariable(): string {
  const stored: (t: string, form?: number) => string = (s) => s + "!";
  return stored("v", 1);
}

// The mirror of `fewer`: the VALUE's type declares two parameters, one of them
// optional, and the CALL supplies one. The erased arity counts the optional, so
// an equality check on it would refuse what the checker accepts.
function callsWithFewerArguments(): string {
  const stored: (t: string, form?: number) => string = (s, form) =>
    s + "/" + String(form === undefined);
  return stored("only");
}

console.log(fewer());
console.log(exact());
console.log(more());
console.log(droppedArgumentIsNotEvaluatedAway());
console.log(throughAVariable());
console.log(callsWithFewerArguments());

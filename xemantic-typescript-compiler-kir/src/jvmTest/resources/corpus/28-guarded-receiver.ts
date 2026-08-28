// A member call on a receiver the program has GUARDED but whose recorded type
// is still the nullish union.
//
// (LIB.4). `cronstrue` writes `if (s && s.startsWith(", ")) s = s.substring(2)`.
// Both compilers agree it is well typed — the checker narrows `s` to `string`
// for its diagnostics — but the type this backend reads is
// `getTypeOfExpression`, which by design never flow-narrows. So the receiver
// arrives as `string | null` and its member table must still be the string one.

function produce(value: string): string | null {
  return value === "" ? null : value;
}

function guardedString(input: string): string {
  let value: string | null = produce(input);
  if (value && value.startsWith(", ")) {
    value = value.substring(2);
  }
  return value === null ? "null" : value;
}

function guardedNumber(input: number): string {
  const value: number | null = input > 0 ? input : null;
  if (value && value.toFixed(1) === "2.0") {
    return "two";
  }
  return "other";
}

function guardedArray(present: boolean): string {
  const values: string[] | null = present ? ["a", "b"] : null;
  if (values && values.length > 1) {
    return values.join("-");
  }
  return "none";
}

// The guard may be an early return rather than an `if` around the use.
function guardedByEarlyReturn(input: string): string {
  const value: string | null = produce(input);
  if (!value) {
    return "none";
  }
  return value.toUpperCase();
}

console.log(guardedString(", trimmed"));
console.log(guardedString("kept"));
console.log(guardedNumber(2));
console.log(guardedNumber(-1));
console.log(guardedArray(true));
console.log(guardedArray(false));
console.log(guardedByEarlyReturn("shout"));

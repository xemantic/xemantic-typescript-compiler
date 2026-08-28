// `for…in` — an indexed walk over the subject's KEYS.
//
// (LIB.4), found past the five rungs the queue named: `cronstrue`'s day-name
// and month-name tables are index-signature object literals walked this way.
//
// The keys are STRINGS, which is the part a program notices — an array
// enumerates "0", "1", … and not 0, 1, … — and the snapshot is taken once,
// because JavaScript leaves a property added DURING the walk unspecified.

const days: { [key: string]: number } = { SUN: 0, MON: 1, TUE: 2 };

function keysOf(table: { [key: string]: number }): string {
  let out = "";
  for (const key in table) {
    out = out + key + "=" + table[key] + ";";
  }
  return out;
}

function concatenatesIndicesAsStrings(): string {
  const values = [7, 8];
  let out = "";
  for (const index in values) {
    out = out + index;
  }
  return out;
}

// An object with no keys walks zero times — the loop must not run its body,
// and the binding must still be in scope after it.
function walksNothingForEmpty(): string {
  const empty: { [key: string]: number } = {};
  let count = 0;
  for (const key in empty) {
    count = count + 1;
  }
  return "count=" + count;
}

function continuesAndBreaks(table: { [key: string]: number }): string {
  let out = "";
  for (const key in table) {
    if (key === "SUN") {
      continue;
    }
    if (key === "TUE") {
      break;
    }
    out = out + key;
  }
  return out;
}

// A `var` binding in the head is function-scoped and outlives the loop.
function bindingOutlivesTheLoop(table: { [key: string]: number }): string {
  var seen = "none";
  for (var seen in table) {
    // nothing
  }
  return seen;
}

console.log(keysOf(days));
console.log(concatenatesIndicesAsStrings());
console.log(walksNothingForEmpty());
console.log(continuesAndBreaks(days));
console.log(bindingOutlivesTheLoop(days));

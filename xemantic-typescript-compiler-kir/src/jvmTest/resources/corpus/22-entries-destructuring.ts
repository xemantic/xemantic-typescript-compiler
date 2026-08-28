// `Object.entries` and a DESTRUCTURING binding in a loop head.
//
// (LIB.4) rung 2 — `cronstrue` walks its verbosity-replacement table as
// `for (const [key, value] of Object.entries(table))`, which is both features
// in one statement.

const table: { [key: string]: string } = { a: "1", b: "2", c: "3" };

function entriesWalk(): string {
  let out = "";
  for (const [key, value] of Object.entries(table)) {
    out = out + key + value + ";";
  }
  return out;
}

function keysAndValues(): string {
  return Object.keys(table).join(",") + "|" + Object.values(table).join(",");
}

// The pair is an ordinary two-element array, so it destructures like one and
// the same binder handles a plain declaration.
function destructuredDeclaration(): string {
  const pair = Object.entries(table)[0];
  const [k, v] = pair;
  return k + "=" + v;
}

function tuplesFromLiterals(): string {
  const pairs: [string, number][] = [["x", 1], ["y", 2]];
  let out = "";
  for (const [label, count] of pairs) {
    out = out + label + count;
  }
  return out;
}

function continuesInsideDestructuredWalk(): string {
  let out = "";
  for (const [key, value] of Object.entries(table)) {
    if (key === "b") {
      continue;
    }
    out = out + key + value;
  }
  return out;
}

console.log(entriesWalk());
console.log(keysAndValues());
console.log(destructuredDeclaration());
console.log(tuplesFromLiterals());
console.log(continuesInsideDestructuredWalk());

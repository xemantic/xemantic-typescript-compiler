// `new Array(…)`, whose one-argument form is not the others.
//
// (LIB.4). `cronstrue` writes `this.expressionParts = new Array(5)` — a LENGTH,
// not an element. `new Array("5")` and `new Array(1, 2)` hold their arguments,
// so no arity-resolved constructor can express the rule.

function lengthForm(): string {
  const holes = new Array(5);
  return String(holes.length) + "/" + String(holes[0] === undefined);
}

function elementsForm(): string {
  const values = new Array("a", "b", "c");
  return String(values.length) + "/" + values.join(",");
}

// A single NON-number argument is one element, not a length.
function singleStringIsAnElement(): string {
  const one = new Array("5");
  return String(one.length) + "/" + one.join(",");
}

function emptyForm(): string {
  const empty = new Array();
  return String(empty.length);
}

// A holed array is writable at any slot, and `length` follows.
function holesAreWritable(): string {
  const holes: (string | undefined)[] = new Array(3);
  holes[1] = "x";
  return String(holes.length) + "/" + String(holes[0] === undefined) + "/" + String(holes[1]);
}

console.log(lengthForm());
console.log(elementsForm());
console.log(singleStringIsAnElement());
console.log(emptyForm());
console.log(holesAreWritable());

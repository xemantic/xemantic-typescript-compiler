// `var`: FUNCTION scoping and hoisting, which are a semantic difference from
// `let` rather than a spelling of it.
//
// (LIB.4) rung 3. Every `var` in `cronstrue` is one of these shapes — a method
// body declaring one inside an `if` branch and reading it after, and a second
// declaration of the same name in the sibling branch.
//
// The pre-assignment VALUE is deliberately not observed: TypeScript refuses to
// read a `var` before it is assigned (TS2454) whatever JavaScript does, so what
// this program pins is the SCOPING — a binding declared in a block and read
// after it, one name declared twice, and a loop variable shared by every
// closure the loop makes.

function blockScoped(flag: boolean): string {
  if (flag) {
    var inner = "yes";
  } else {
    var inner = "no";
  }
  return inner;
}

// A bare block and a `try` block both ALWAYS run, which is what lets the
// binding be read after them under `strict` — the declaration itself has to
// have left the block for this to compile at all.
function declaredInsideBlock(): string {
  {
    var deep = "deep";
  }
  return deep;
}

function declaredInsideTry(): string {
  try {
    var got = "ok";
  } finally {
    // nothing
  }
  return got;
}

function shadowedByLet(): string {
  var name = "outer";
  let out = "";
  {
    let name = "inner";
    out = out + name;
  }
  return out + "/" + name;
}

function loopCapture(): string {
  const fns: (() => number)[] = [];
  for (var i = 0; i < 3; i++) {
    fns.push(function (): number { return i; });
  }
  return fns[0]() + "," + fns[1]() + "," + fns[2]();
}

function letLoopCapture(): string {
  const fns: (() => number)[] = [];
  for (let j = 0; j < 3; j++) {
    fns.push(function (): number { return j; });
  }
  return fns[0]() + "," + fns[1]() + "," + fns[2]();
}

// A `var` belongs to ITS OWN function, so the inner one shadows rather than
// assigns — the hoisting table is per frame, not per program.
function nested(): string {
  var outer = "a";
  const innerFn = function (): string {
    var outer = "b";
    return outer;
  };
  return innerFn() + outer;
}

// A `var` whose INITIALIZER builds an object literal. The literal becomes a
// generated shape class whose constructor is synthesized in the middle of
// lowering this very statement, so the hoisted declaration must not be emitted
// into THAT body — which is a wrong-parent IR error rather than a wrong answer.
function varHoldingAnObjectLiteral(): string {
  var table: { [key: string]: number } = { SUN: 0, MON: 1 };
  var second: { [key: string]: number } = { TUE: 2 };
  return String(table["SUN"]) + String(table["MON"]) + String(second["TUE"]);
}

console.log(blockScoped(true));
console.log(blockScoped(false));
console.log(declaredInsideBlock());
console.log(declaredInsideTry());
console.log(shadowedByLet());
console.log(loopCapture());
console.log(letLoopCapture());
console.log(nested());
console.log(varHoldingAnObjectLiteral());

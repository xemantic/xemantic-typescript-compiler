// A `function` declared inside a function body or a block — a closure
// capability, not a spelling of `const f = function () {}`.
//
// (P18.129). Censused before that round: not ONE of the 29 programs here
// declared a function inside a body, which is why the gap survived — 18 reaches
// for the EXPRESSION form instead. A nested `function helper() {}` is ordinary
// TypeScript, and every reference to one used to refuse at *cannot lower the
// reference*.
//
// Every expected line is `node`'s, over `tsgo`'s emit of this same file.

// HOISTING is the one way this form is not the expression form: the name is
// usable ABOVE its own textual position, which is how "helpers at the bottom"
// is written.
function helpersBelow(): string {
  const doubled = [1, 2].map(twice).join(",");
  const summed = add(3, 4);
  return doubled + "/" + summed;

  function twice(n: number): number {
    return n * 2;
  }
  function add(a: number, b: number): number {
    return a + b;
  }
}

// CAPTURE: a parameter, a `let` written through, and a `var`.
function captures(seed: number): string {
  let acc = seed;
  var counted = 0;
  function bump(by: number): void {
    acc = acc + by;
    counted = counted + 1;
  }
  bump(1);
  bump(2);
  return acc + ":" + counted;
}

// The closure ESCAPES its frame, and two invocations do not share it.
function makeCounter(start: number): () => number {
  let n = start;
  function tick(): number {
    n = n + 1;
    return n;
  }
  return tick;
}

// Recursion and MUTUAL recursion — two reads of two slots that both exist
// before either body runs.
function parity(n: number): string {
  function isEven(x: number): boolean {
    return x === 0 ? true : isOdd(x - 1);
  }
  function isOdd(x: number): boolean {
    return x === 0 ? false : isEven(x - 1);
  }
  return isEven(n) ? "even" : "odd";
}

function factorial(n: number): number {
  function go(x: number): number {
    return x <= 1 ? 1 : x * go(x - 1);
  }
  return go(n);
}

// A declaration in a BLOCK rather than in a body, closing over the loop
// variable — block-scoped, exactly as a module's `function` is.
function loopBlocks(): string {
  const parts: string[] = [];
  for (let i = 0; i < 3; i++) {
    function tag(): string {
      return "i" + i;
    }
    parts.push(tag());
  }
  return parts.join(",");
}

// An inner declaration SHADOWS a top-level one of the same name.
function label(): string {
  return "top";
}

function shadowing(): string {
  function label(): string {
    return "inner";
  }
  return label() + "/" + labelOfModule();
}

function labelOfModule(): string {
  return label();
}

// Two SIBLING nested functions sharing one captured variable, handed out as an
// object literal — the shape a module-private pair of helpers takes.
function pair(): string {
  let n = 0;
  function inc(): number {
    n = n + 1;
    return n;
  }
  function get(): number {
    return n;
  }
  inc();
  inc();
  return inc() + ":" + get();
}

// Inside a class METHOD, beside a `this` read, and inside an arrow.
class Holder {
  n: number = 7;
  run(): number {
    function helper(): number {
      return 1;
    }
    return helper() + this.n;
  }
}

const inArrow = (): number => {
  function helper(): number {
    return 12;
  }
  return helper();
};

// A REST parameter and a DEFAULTED one, which the value carrier's shape turns
// on, plus OVERLOAD signatures above the implementation.
function parameterShapes(): string {
  function counted(a: number, ...rest: number[]): string {
    return a + ":" + rest.length;
  }
  function defaulted(a: number = 4): number {
    return a * 2;
  }
  function pick(a: number): string;
  function pick(a: string): string;
  function pick(a: any): string {
    return "v:" + a;
  }
  return counted(1, 2, 3) + "/" + defaulted() + "/" + defaulted(1) + "/" + pick(1) + pick("x");
}

console.log(helpersBelow());
console.log(captures(10));
const first = makeCounter(0);
const second = makeCounter(100);
console.log(first() + "," + first() + "," + second());
console.log(parity(4) + "," + parity(7));
console.log(factorial(5));
console.log(loopBlocks());
console.log(shadowing());
console.log(pair());
console.log(new Holder().run());
console.log(inArrow());
console.log(parameterShapes());

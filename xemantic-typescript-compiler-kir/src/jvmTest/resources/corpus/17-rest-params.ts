// Rest parameters, in the three positions a real library puts them.
//
// (LIB.4) rung 1, driven by `cronstrue`'s `StringUtilities`, whose whole file
// is these two shapes: a static method collecting its trailing arguments, and
// a function VALUE handed to a runtime member that calls it back with however
// many arguments the match produced.

function joinAll(separator: string, ...parts: string[]): string {
  return parts.join(separator);
}

function countArgs(...args: number[]): number {
  return args.length;
}

class StringUtilities {
  static format(template: string, ...values: string[]): string {
    let result: string = template;
    for (const value of values) {
      result = result.replace("%s", value);
    }
    return result;
  }
}

console.log(joinAll("-", "a", "b", "c"));
console.log(joinAll("-"));
console.log(countArgs());
console.log(countArgs(1, 2, 3, 4));
console.log(StringUtilities.format("%s and %s", "x", "y"));

// A function VALUE with a rest parameter: its arity is not fixed, so it cannot
// be a `FunctionN`. It is called here through the dynamic path, which is the
// same path a runtime member's callback goes through.
const variadic = function (first: string, ...rest: string[]): string {
  return first + "/" + rest.length + "/" + rest.join(",");
};
console.log(variadic("a", "b", "c"));
console.log(variadic("a"));

// A rest parameter is an ordinary array: it may be empty, indexed, and walked.
function describe(label: string, ...items: string[]): string {
  if (items.length === 0) {
    return label + ": none";
  }
  return label + ": " + items[0] + " (+" + (items.length - 1) + " more)";
}
console.log(describe("empty"));
console.log(describe("one", "a"));
console.log(describe("three", "a", "b", "c"));

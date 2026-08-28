// The replacer CALLBACK overload of String.replace: the argument list is
// (match, ...captureGroups, offset, wholeString), so its length depends on the
// pattern rather than on the call site — which is why the variadic carrier and
// this overload are one piece of work.

function upper(text: string): string {
  return text.replace(/[a-z]+/g, function (match: string): string {
    return match.toUpperCase();
  });
}

function withGroups(text: string): string {
  return text.replace(/(\d)-(\d)/g, function (match: string, a: string, b: string): string {
    return b + "-" + a;
  });
}

function withOffset(text: string): string {
  return text.replace(/x/g, function (match: string, offset: number): string {
    return String(offset);
  });
}

function variadicReplacer(text: string): string {
  return text.replace(/%s/g, function (substring: string, ...args: unknown[]): string {
    return "<" + substring + ":" + args.length + ">";
  });
}

function nonGlobalReplacesOnce(text: string): string {
  return text.replace(/a/, function (m: string): string { return "!"; });
}

function arrowReplacer(text: string): string {
  return text.replace(/\d/g, (t) => "(" + t + ")");
}

console.log(upper("ab CD ef"));
console.log(withGroups("1-2 and 3-4"));
console.log(withOffset("axbxc"));
console.log(variadicReplacer("%s and %s"));
console.log(nonGlobalReplacesOnce("banana"));
console.log(arrowReplacer("a1b2"));

// `<T>expr` — the older spelling of `expr as T`, and the SAME operation.
//
// (LIB.4). `cronstrue` reassembles its destructured options with
// `let options = <Options>{ … }`, which is the shape below.
//
// The `.expected` here comes from the SAME program spelled with `as`, run by
// `node` — the angle-bracket form is the one thing node's type stripper will
// not parse (it is ambiguous with JSX). Deriving the oracle from the twin is
// exactly the claim under test: the two spellings are one operation.

interface Options {
  verbose: boolean;
  locale: string;
}

function assembles(verbose: boolean, locale: string): string {
  const options = <Options>{
    verbose: verbose,
    locale: locale,
  };
  return options.locale + "/" + options.verbose;
}

function widensThenNarrows(value: string): number {
  // Two assertions in a row, which is the idiom for a cast the checker would
  // otherwise refuse — and the OUTER one is what the value must end up as.
  const opaque = <unknown>value;
  const back = <string>opaque;
  return back.length;
}

function assertsAnElement(values: string[]): string {
  return (<string>values[0]).toUpperCase();
}

console.log(assembles(true, "en"));
console.log(widensThenNarrows("abcd"));
console.log(assertsAnElement(["ab", "cd"]));

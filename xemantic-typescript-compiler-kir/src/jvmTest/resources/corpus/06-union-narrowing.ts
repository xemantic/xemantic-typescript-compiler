function describe(x: string | number): string {
  if (typeof x === "string") {
    return "str:" + x;
  }
  return "num:" + (x + 1);
}
console.log(describe("a"));
console.log(describe(41));

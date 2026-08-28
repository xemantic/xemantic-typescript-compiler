// The array members that take a CALLBACK, and the arguments it is given.
//
// (LIB.4). JavaScript passes `(element, index, array)` to every one of these,
// not the element alone — a runtime member typed to take a one-parameter
// function would drop the index SILENTLY, which is the failure this program
// exists to catch.

const values = [10, 20, 30];

function indexIsPassed(): string {
  return values.map((v, i) => i + ":" + v).join(",");
}

function arrayIsPassed(): string {
  return values.map((v, i, all) => String(all.length)).join(",");
}

// A callback that takes fewer parameters simply ignores the rest.
function fewerParametersIsFine(): string {
  return values.filter((v) => v > 15).join(",");
}

function filterSeesTheIndex(): string {
  return values.filter((v, i) => i !== 1).join(",");
}

function findAndFindIndex(): string {
  const found = values.find((v) => v > 15);
  const missing = values.find((v) => v > 100);
  return String(found) + "/" + values.findIndex((v) => v > 15) + "/" +
    values.findIndex((v) => v > 100) + "/" + String(missing === undefined);
}

function someAndEvery(): string {
  const empty: number[] = [];
  return String(values.some((v) => v > 25)) + "/" + String(values.some((v) => v > 100)) +
    "/" + String(values.every((v) => v > 5)) + "/" + String(empty.every((v) => false));
}

// `at` counts a NEGATIVE index from the end.
function atCountsFromTheEnd(): string {
  return String(values.at(0)) + "/" + String(values.at(-1)) + "/" +
    String(values.at(2)) + "/" + String(values.at(3) === undefined);
}

// `reverse` is IN PLACE and answers the same array.
function reverseIsInPlace(): string {
  const local = [1, 2, 3];
  const answer = local.reverse();
  return local.join(",") + "/" + String(answer === local);
}

function forEachSeesTheIndex(): string {
  let out = "";
  values.forEach((v, i) => {
    out = out + i + v + ";";
  });
  return out;
}

console.log(indexIsPassed());
console.log(arrayIsPassed());
console.log(fewerParametersIsFine());
console.log(filterSeesTheIndex());
console.log(findAndFindIndex());
console.log(someAndEvery());
console.log(atCountsFromTheEnd());
console.log(reverseIsInPlace());
console.log(forEachSeesTheIndex());

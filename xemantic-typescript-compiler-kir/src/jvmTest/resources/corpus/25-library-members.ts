// The built-in members `cronstrue` reaches for that this runtime did not have.
//
// (LIB.4). Each is a place where the JavaScript rule is not the intuitive one,
// so each line below is chosen to fail if the intuitive rule were implemented.

// `substr` is not `substring`: the second argument is a LENGTH, and a negative
// start counts from the end.
function substrRules(): string {
  const s = "abcdef";
  return [s.substr(1), s.substr(1, 2), s.substr(-2), s.substr(-2, 1), s.substr(2, 0)].join("|");
}

// `sort()` with no comparator compares the STRING forms, so 10 sorts before 9.
function defaultSortIsLexicographic(): string {
  const values = [10, 9, 1];
  return values.sort().join(",");
}

function comparatorSortIsNumeric(): string {
  const values = [10, 9, 1];
  return values.sort((a, b) => a - b).join(",");
}

// Sorting is IN PLACE and answers the same array.
function sortIsInPlace(): string {
  const values = [3, 1, 2];
  const answer = values.sort((a, b) => a - b);
  return values.join(",") + "/" + String(answer === values);
}

// `new Date(y, m)` takes a ZERO-BASED month, in local time, and `getFullYear`
// reads it back.
function dateComponents(): string {
  const d = new Date(2020, 1, 15);
  return d.getFullYear() + "-" + d.getMonth() + "-" + d.getDate();
}

function twoDigitYearIsNineteenHundred(): string {
  return new Date(99, 0, 1).getFullYear().toString();
}

function monthRollsOver(): string {
  const d = new Date(2020, 12, 1);
  return d.getFullYear() + "-" + d.getMonth();
}

// `toFixed` produces a `.` in EVERY locale — JavaScript defines it that way,
// and `String.format` without an explicit locale does not. This line answered
// "2,0" on a machine whose locale uses a comma, and is invisible on an en-US
// one, which is a property of who RUNS the program rather than of the program.
function toFixedIsLocaleIndependent(): string {
  return (2).toFixed(1) + "/" + (1234.5678).toFixed(2) + "/" + (0.5).toFixed(0);
}

function localeCase(): string {
  return "straße".toLocaleUpperCase() + "/" + "ABC".toLocaleLowerCase();
}

console.log(substrRules());
console.log(defaultSortIsLexicographic());
console.log(comparatorSortIsNumeric());
console.log(sortIsInPlace());
console.log(dateComponents());
console.log(twoDigitYearIsNineteenHundred());
console.log(monthRollsOver());
console.log(toFixedIsLocaleIndependent());
console.log(localeCase());

for (var forInNumber in 5) {}
export function emptyArrayLoop() {
    for (const [] of []) {}
    for (var incr = 0; incr < 1; incr = "s") {}
}
export const forOfLet = [1];
for (let of of forOfLet) {}
export { forInNumber };

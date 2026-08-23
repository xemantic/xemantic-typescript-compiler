export namespace Outer {
    export const value = 1;
    function inner() { return this; }
    export namespace Inner { export const deep = 2; }
}
const asType: Outer = 1;
declare namespace Amb { export default class Nope {} }
export namespace Dup { export const q = 1; }
export namespace Dup { export const q = 2; }
export { asType };

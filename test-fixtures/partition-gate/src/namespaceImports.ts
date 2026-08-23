export namespace HasRequire {
    import fs = require("some-module");
    export const used = fs;
}
namespace NotTop {
    export const inner = 1;
}
export function fn() {
    namespace Nested { export const q = 1; }
    return Nested.q;
}
export { NotTop };

export class ReturnsPrimitive {
    constructor() { return 1; }
    foo() {}
}
export const rp2 = new ReturnsPrimitive();
export class BaseR {}
export class ReturnsNull extends BaseR {
    constructor() { super(); return null; }
}
export class ReturnsTp<T> {
    constructor(v: T) { return v; }
}

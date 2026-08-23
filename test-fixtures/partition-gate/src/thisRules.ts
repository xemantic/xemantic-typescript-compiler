export const objLit = {
    self: this,
    get accessor(): number { return "no"; },
};
export function freeThis() { return this.missing; }
export class ThisParam {
    private secret = 1;
}
function reads(this: ThisParam) { return this.secret; }
export { reads };

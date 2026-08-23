export class UseBefore {
    a = this.b;
    b = 1;
}
export function captured() {
    const read = () => later;
    let later = 1;
    return read;
}
export function tryOnly() {
    let assigned: number;
    try { assigned = 1; } catch {}
    return assigned;
}

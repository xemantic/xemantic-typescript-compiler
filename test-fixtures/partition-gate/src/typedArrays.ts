export const i8: Int8Array = new Int32Array(1);
export const bigints: bigint = 1n;
export const asNumber: number = 2n;
export const shifted = 3n << 2n;
export const mapped = new Map<string, number>();
mapped.set(1, "a");
export const iter: Iterator<number> = "no";

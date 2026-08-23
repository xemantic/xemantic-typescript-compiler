export async function p() {
    const v = await Promise.resolve({ a: 1 });
    return v.missing;
}
export function notAsync() { return await 1; }
export type SelfAwait = Awaited<SelfAwait>;

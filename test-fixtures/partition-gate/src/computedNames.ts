const key = "k";
export class Computed {
    [Computed.name] = 1;
    static [key]: number;
}
export const late = { [key]: 1 };
const { [key]: destructured } = late;
export { destructured };

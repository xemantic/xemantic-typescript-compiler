export type Mode = 'flow' | 'block' | 'quoted'

export const DEFAULT_MODE: Mode = 'block'
export const WIDTH = 80

// A module body that RUNS: this line is evaluated once, before any importer.
export const BANNER = describe(DEFAULT_MODE, WIDTH)

export function describe(mode: Mode, width: number): string {
    return mode + '@' + width
}

export let counter = 0
export function bump(): number {
    counter += 1
    return counter
}

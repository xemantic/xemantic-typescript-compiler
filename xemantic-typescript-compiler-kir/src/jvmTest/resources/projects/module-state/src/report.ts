import { BANNER, DEFAULT_MODE, WIDTH, describe, bump } from './config'

export const HEADER = '[' + BANNER + ']'

export function report(): string {
    return HEADER + ' ' + describe(DEFAULT_MODE, WIDTH * 2) + ' #' + bump()
}

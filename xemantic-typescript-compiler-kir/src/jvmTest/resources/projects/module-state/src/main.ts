import { report } from './report'
import { BANNER, counter, bump } from './config'

console.log(BANNER)
console.log(report())
console.log(report())
bump()
console.log(counter)

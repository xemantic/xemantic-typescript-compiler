let total = 0
total += 5
total -= 1
total *= 3
total /= 2
total %= 4
console.log(total)

let i = 0
i++
++i
console.log(i)
let j = 5
console.log(j--)
console.log(j)
console.log(--j)

const flags = 0b1010
console.log(flags & 0b0110)
console.log(flags | 0b0101)
console.log(flags ^ 0b0011)
console.log(flags << 2)
console.log(flags >> 1)
console.log(~flags)
console.log(-1 >>> 0)

const text: string = "n="
let label = text
label += 7
console.log(label)

const maybe: string | null = null
console.log(maybe == null)
console.log(1 == 1)
console.log("2" == "2")

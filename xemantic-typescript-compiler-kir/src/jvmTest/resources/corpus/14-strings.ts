const language: string = "TypeScript"
console.log(language.length)
console.log(language.charAt(0))
console.log(language.indexOf("Script"))
console.log(language.slice(4))
console.log(language.slice(0, 4))
console.log(language.slice(-6))
console.log(language.substring(4, 0))
console.log(language.toUpperCase())
console.log(language.toLowerCase())
console.log(language.startsWith("Type"))
console.log(language.includes("peSc"))
console.log("  padded  ".trim())
console.log("ab".repeat(3))
console.log("a-b-c".split("-").join("+"))
console.log("a-b-c".replace("-", "+"))

const count = 3
console.log(`there are ${count} items in ${language}`)
console.log(`${count + 1} next`)
const plain = `no substitutions`
console.log(plain)

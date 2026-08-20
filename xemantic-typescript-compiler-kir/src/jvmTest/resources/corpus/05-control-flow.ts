function fib(n: number): number {
  if (n < 2) {
    return n;
  }
  let a = 0;
  let b = 1;
  let i = 2;
  while (i <= n) {
    const next = a + b;
    a = b;
    b = next;
    i = i + 1;
  }
  return b;
}
for (let k = 0; k < 8; k = k + 1) {
  console.log(fib(k));
}

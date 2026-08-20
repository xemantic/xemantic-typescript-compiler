class Counter {
  private value: number;
  constructor(start: number) {
    this.value = start;
  }
  increment(by: number): number {
    this.value = this.value + by;
    return this.value;
  }
}
const c = new Counter(10);
console.log(c.increment(5));
console.log(c.increment(1));

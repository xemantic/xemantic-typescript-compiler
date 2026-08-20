function label(name: string | undefined): string {
  if (name === undefined) {
    return "anonymous";
  }
  return name;
}
console.log(label("ada"));
console.log(label(undefined));

import * as React from "react";
export const App = (p: { name: string }) => <div title={p.name}>{p.name.toUpperCase()}</div>;

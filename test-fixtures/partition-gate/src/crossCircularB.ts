import type { CircA } from "./crossCircularA";
export interface CircB extends CircA {}
export class CircClassB extends CircClassARef {}
import { CircClassA as CircClassARef } from "./crossCircularA";

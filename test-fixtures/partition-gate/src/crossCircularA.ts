import type { CircB } from "./crossCircularB";
export interface CircA extends CircB {}
export class CircClassA extends CircClassBRef {}
import { CircClassB as CircClassBRef } from "./crossCircularB";

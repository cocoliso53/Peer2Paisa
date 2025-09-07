// @ts-ignore
import * as ps from '../../purescript/output/Main/index.js';

console.log(ps.step({ state: "Idle" })({ tag: "Start" }));
console.log(ps.step({ state: "Running" })({ tag: "Stop" }));
console.log(ps.step({ state: "Idle" })({ tag: "Stop" }));
console.log(ps.step({ state: "Idle" })({ tag: "Foo" }));
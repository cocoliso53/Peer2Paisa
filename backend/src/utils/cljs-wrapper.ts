// eslint-disable-next-line @typescript-eslint/no-var-requires
const fsmLib = require('../../dist/fsm-lib.js');

export const greet = fsmLib.greet as (name: string) => string;
export const processData = fsmLib.processData as (data: number[]) => number[];
export const fibonacci = fsmLib.fibonacci as (n: number) => number;
export const readFileAsync = fsmLib.readFileAsync as (
  filePath: string, 
  callback: (err: Error | null, data: string | null) => void
) => void;
export const transformJson = fsmLib.transformJson as (jsonStr: string) => string;
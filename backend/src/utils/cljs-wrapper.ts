// eslint-disable-next-line @typescript-eslint/no-var-requires
const fsmLib = require('../../dist/fsm-lib.js');

export const deltaWrapped = fsmLib.deltaWrapped as (state: any, event:any) => any;
export const transformJson = fsmLib.transformJson as (jsonStr: string) => string;
export const pruebaData = fsmLib.pruebaData as (data: any) => any;
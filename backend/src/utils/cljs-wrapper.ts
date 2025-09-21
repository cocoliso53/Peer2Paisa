// eslint-disable-next-line @typescript-eslint/no-var-requires
const fsmLib = require('../../dist/fsm-lib.js');

export const deltaWrapped = fsmLib.deltaWrapped as (state: any, event:any) => any;
import type { FsmEvent, FsmResult, OrderDocument } from '../types/fsm'

// eslint-disable-next-line @typescript-eslint/no-var-requires
const fsmLib = require('../../dist/fsm-lib.js');

export const deltaWrapped = fsmLib.deltaWrapped as (
    state: OrderDocument,
    event: FsmEvent,
) => FsmResult;

declare module '../../dist/fsm-lib.js' {
  import type { FsmEvent, FsmResult, OrderDocument } from '../types/fsm';

  export function deltaWrapped(state: OrderDocument, event: FsmEvent): FsmResult;
}

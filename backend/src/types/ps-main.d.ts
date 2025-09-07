// give the module a nice alias
declare module "ps-main" {
  export type State = { state: string };
  export type StEvent = { tag: string };
  export type StateEffect = { stuff: State; msg: string };
  export function step(state: State, event: StEvent): StateEffect;
}

module Main
  ( main
  , step
  )
  where

import Prelude

import Effect (Effect)
import Effect.Class.Console (error)
import Effect.Console (log)

-- JsOrder / JsEvent / JsEffect as plain records
type State = { state :: String }
type Event = { tag :: String }
type StateEffect = { stuff :: State, msg :: String }
type InvalidTransition = { error :: String }

-- our pure transition
step :: State -> Event -> StateEffect
step order event =
  case order.state, event.tag of
    "Idle", "Start" -> { stuff: { state: "Running"}, msg: "ok"} 
    "Running", "Stop" ->  { stuff: { state: "Idle"}, msg: "ok"}
    "Idle", "Stop" -> { stuff: { state: "Idle"}, msg: "no effect"}
    "Running", "Start" -> { stuff: { state: "Running" }, msg: "no effect"}
    _, _ -> { stuff: { state: "None"}, msg: "Invalid state or invalid event"}


-- dummy main (needed so spago run works)
main :: Effect Unit
main = log "PureScript FSM toy ready"

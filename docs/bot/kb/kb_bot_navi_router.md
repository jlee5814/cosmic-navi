---
name: kb_bot_navi_router
description: How an unmatched navi whisper reaches a tool. Patterns first, then a NaviRouter call that only picks a tool and bot, its own thread because the chat gate drops calls, and navi yes for rescue and park. Read before touching BotNaviManager, NaviRouter or the chat LLM gate.
type: project
---
# Navi routing: patterns run the work, the model only picks

A navi whisper (owner only, checked in `BotManager.handleWhisperToBot`) goes through these stages.

1. **Patterns** (`BotNaviManager.dispatch`): `llm`, the fleet commands (`BotFleetOps.FLEET`, `ACTION`) and the
   email pattern. A match runs at once with no model call.
2. **Route** (`server.bots.llm.NaviRouter.route`), only when no pattern matched, the request isn't help, and
   OpenAI compatible chat is on. A greedy call under a JSON schema returns `{"tool", "bot"}` from a closed list
   (email, fleet, why, rescue, park, llm, none). `Route.command()` turns it into the exact text the patterns
   take ("why 26"), and `dispatch` runs it, so there is one implementation of every tool.
   `BotNaviManagerTest.everyRoutedCommandIsOneTheCommandPatternsAlreadyRun` keeps the two in step.
3. **Confirm** for rescue and park when the model picked them: the command waits in `pending` for one minute
   until `navi yes`. A typed `navi rescue 26` never waits.

## Why routing has its own thread

`BotLlmReplyManager` gates chat calls with `Semaphore.tryAcquire` and **drops** a call when every permit is
busy (`BOT_LLM_MAX_CONCURRENT`). Ambient chatter can hold every permit, so an owner command sharing that gate
would vanish without a reply. Routing runs on a single daemon thread (`navi-router`) instead: owner requests
are rare, so they queue there and never compete with chat. `BotTurn.classify` still runs inside the chat
gate, since it only serves a chat reply.

## Cost and accuracy

Each call is recorded in `LlmStats` as kind `navi_route`, so `navi llm` and `cache/llm-stats/calls.tsv` show
its latency apart from chat. `NaviRouter.ACK_AFTER_MS` (1 s) is when the bot whispers an ack while the call
runs; set it from that p99. Phrasing accuracy is scored against the live model by
`tools/llm-bench/bot_eval/navi_route.py`, whose lines are all ones the patterns miss.

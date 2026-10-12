---
name: Edge give up (runtime replan around a failing edge)
description: A bot that can't finish a nav edge gives it up and its own route search charges the edge 60 s more for 10 to 15 minutes, so it takes another way. BotEdgeFailures; read before touching committed routes, runSearch costs or the sweep tool.
type: project
---
# Edge give up

**Why:** the builder authors edges from simulated physics. The sweep (`tools/navgate`) catches the ones
that fail in the sim, but some still fail live. New Leaf City 600000000 x 1715 was one: a walk off drop
where the first step past the ledge walks up a small wall onto the rest of the chain, so the bot never
fell. The builder fix came in graph v72, but until then the bot replanned the same cheapest route and
retried the edge for hours. The stale edge give up (`navBlockedPosTicks`) only clears the edge; the next
plan picks it again.

**How (`BotEdgeFailures`):**
- `BotNavigationManager.resolveTarget` calls `track` on every AI tick with the edge the bot follows.
- The clock arms once the bot stands within `ARM_PX` (60) of the edge's launch window in its source
  region. Budget: the edge's own cost plus `SLACK_MS` (5 s) plus up to `SLACK_JITTER_MS` (3 s).
- Attempt time is game time: `AI_TICK_MS` per tracked AI tick, the clock physics and edge costs run
  on. A lagging server or the sim lab (much faster than real time) judges an edge the same way, and
  ticks spent elsewhere (a fight, a break) never count. Wall time only dates the mark. The attempt is keyed by geometry (type, start, end), so a graph swap or a nav clear
  does not restart the clock; a different edge does.
- Past the budget and still in the source region (not mid air): the bot pauses one tick
  (`lastNavDecision = edge-failed`), drops the edge and the committed route, and logs
  `Bot N gave up a TYPE edge on map M from (x,y) to (x,y)`.
- The mark lasts 10 to 15 minutes per bot and map. `runSearch` adds `PENALTY_MS` (60 s) to the edge
  for that bot, and `computeCommittedRoute` skips the shared baked portal route when it uses a marked
  edge. A penalty, not a ban: with no other way the bot keeps trying.
- Reaching the edge's destination later clears the mark.

**Sweep:** `BotEdgeSweepCli` sets `-Dbots.edge-give-up=off` for forced edges (the edge itself is under
test, a reroute would hide a phantom) and on for `--route` and `--probe`. A system property, not a cfg
reference, so a newer sweep tool still runs against a base that lacks the class.

**Not covered:** the fallback per hop planner (`findNextEdge`, used when a route can't be committed)
reads the shared next hop cache, which can't see one bot's marks.

**Follow up:** a give up line in the log is a builder or physics bug. Fix it at the source (CLAUDE.md
rule 9) and run it through the nav gate; the give up only keeps the bot moving meanwhile.

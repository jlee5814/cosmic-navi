# Agent coordination

Several agent sessions share one live server, one main branch and one Mac. On Oct 20 two of them fixed the same
Buddha freeze an hour apart (#189 and a PR that GitHub then refused to merge), and one restarted the server while
the other watched a bot. Code, tests and analysis can run in parallel. Deploys, live bot commands, parks, settings
and database writes go one at a time. These tools make both visible.

Everything shared lives on the Mac in `~/Library/Application Support/cosmic-agents` (override with
`COSMIC_AGENTS_DIR`). A cloud session runs the commands there through the device bridge, from the repo root.

## Every session

1. **Read the journal first.** `python3 tools/agents/agents.py journal` prints the last 20 lines: who started
   what, who released what, every deploy.
2. **Check, then claim, before work.** `agents.py check bot:255 issue:188` lists live claims on those subjects
   (exit 1 when there are some). If one is live, read its note and take other work, or ask the owner. Then
   `agents.py claim --session NAME --issue 188 --intent "Buddha freeze" bot:255 map:800020130`. With `--issue`
   the GitHub issue also gets the `claimed` label and a comment. A claim lasts 2 hours; `refresh ID` extends it.
3. **Watching bots live?** Claim them with `--watch`. Deploys refuse while a watch claim is live, and the navi
   responder leaves any claimed bot alone (no rescue, no park) until the claim ends.
   Fleet ops in the server skips a claimed bot's auto rescue the same way; it reads the claims through a read
   only mount, a line in the local `compose.localonly.yaml` under the maplestory service's volumes:
   `- "${HOME}/Library/Application Support/cosmic-agents:/opt/server/agents:ro"`.
4. **Deploy only through** `tools/agents/deploy.sh --session NAME`. It takes the deploy lock, refuses during a
   watch (`--force` overrides and says so in the journal), records the bots online, merges origin/main,
   rebuilds and restarts, respawns those bots, and writes the deploy to the journal.
5. **Release when done.** `agents.py release ID --note "what came of it"` removes the claim and the label.

Subjects are free tokens: `bot:<id>`, `map:<id>`, `issue:<n>`, `area:<combat|nav|economy|...>`,
`file:<ClassName>` for a hot file like BotCombatManager. Claims conflict when they share a token.

Tests: `python3 -m unittest tools/agents/test_agents.py`.

# Fleet strategist (read only)

The planning tier: a larger model looks at the whole fleet once in a while and proposes changes GrindAdvisor can't make one bot at a time. Nothing is sent to the game; the output is a report.

```sh
python3 fleet_snapshot.py runs/snapshot-$(date +%Y%m%d-%H%M).json      # dashboard + MySQL + WZ names
python3 strategist.py runs/snapshot-<...>.json runs/plan-<label> --url http://127.0.0.1:31000 --model-label <label>
```

- `fleet_snapshot.py`: every roster bot (level, job, role, cap, map, destination, party, EXP, mesos, gear, top skills, and GrindAdvisor's current status) plus every map in use with its mobs.
- `strategist.py`: one request per bot with the whole fleet as a shared prefix (the server's prefix cache reuses it), constrained to a JSON plan (keep, move to a listed map, or party with a named bot), plus one request for fleet notes. Each plan is checked against the snapshot: the map must exist, a partner must be within 5 levels, capped bots stay put. Writes `plans.json` and `report.md`.
- Works with any OpenAI compatible server. On SGLang, start it with `--mlx-enable-sampling --constrained-json-disable-any-whitespace`; without the second flag the JSON grammar lets the model pad with newlines until it runs out of tokens.

## First run: Qwen3-8B as a stand-in (`runs/plan-8b/`)

50 bots, 55 maps, 8,200 token shared prompt, 51 requests in 184 s.

- It kept all 50 bots and proposed nothing: it rubber stamps GrindAdvisor.
- Three of its five fleet notes were false (it called SipsBuddy26, 34 and 41 idle; none were).
- What the snapshot shows that a planner should weigh: 15 of 50 bots are quest farmers idle at their level 43 cap, by design.

Conclusion: the pipeline works end to end; an 8B model is too weak to plan. The next run uses Claude.

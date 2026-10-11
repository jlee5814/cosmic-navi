# Bot chat on SGLang (MLX backend): results, Oct 8 2026

Setup: M4 Pro, 24 GB. SGLang `main` from `~/Projects/inference/sglang` (`my-venv`, MLX 0.32), `SGLANG_USE_MLX=1`,
`mlx-community/Qwen3-0.6B-4bit`, `--mem-fraction-static 0.25 --enable-cache-report`. Traffic: one message to each of
the 50 live roster bots per wave, prompts built like `server.bots.llm.PromptBuilder`, 24 output tokens.
One fresh server per wave (`run_matrix.sh`), cache flushed before each wave, memory guard at 10 GB.

## Prompt layout, one request at a time

| Layout | Cache hit | TTFT p50 | Latency p50 | Requests/s |
|---|---|---|---|---|
| current: bot IGN, level and job open the system prompt | 3.5% | 86 ms | 122 ms | 5.9 |
| shared_first: shared rules first, bot identity in the user turn | 38.5% | 60 ms | 109 ms | 6.9 |

Moving per bot facts after the shared rules lets RadixAttention reuse the common prefix across all bots:
11x more cached prompt tokens, 30% faster first token, 17% more requests per second.

## Concurrency: blocked by scheduler memory growth

Every wave with 8 or more concurrent requests tripped the 10 GB guard within seconds on a fresh server:

| Wave | Requests finished | Scheduler footprint at guard |
|---|---|---|
| current c8 | 11 | 11.2 GB |
| current c16 | 3 | 13.3 GB |
| current c50 | 3 | 13.6 GB |
| shared_first c8 | 15 | 12.7 GB |
| shared_first c16 | 18 | 15.5 GB |
| shared_first c50 | 3 | 16.4 GB |

Sequential traffic also grows memory (`shape_growth_probe.py`: 30 identical requests took the scheduler from
1.7 GB to 7.8 GB; 30 more of varied length added nothing), but concurrency accelerates it sharply. Earlier
unguarded runs reached 15 to 19 GB, mostly GPU buffers (vmmap: 13.3 GB IOAccelerator in 4,079 regions), then
stalled while swapping. `SGLANG_MLX_CLEAR_CACHE_STEPS=16` did not help. The MLX runner sets
`mx.set_wired_limit` to the full recommended working set (19.07 GB here) and sets no cache limit.

Next: find what allocates per request and per batch in the MLX runner; until then, keep bot chat off SGLang on
a machine shared with the game server.

## Files

- `bot_chat_bench.py`: the load generator (two layouts, streaming TTFT, cached token accounting)
- `run_matrix.sh`: one fresh server per wave with the memory guard
- `run_bench.sh`: single server run with a footprint monitor
- `shape_growth_probe.py`: sequential growth probe (identical versus varied prompt lengths)

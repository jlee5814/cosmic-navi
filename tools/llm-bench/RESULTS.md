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

## Concurrency, stock server: blocked by scheduler memory growth

Every wave with 8 or more concurrent requests tripped the 10 GB guard within seconds on a fresh server:

| Wave | Requests finished | Scheduler footprint at guard |
|---|---|---|
| current c8 | 11 | 11.2 GB |
| current c16 | 3 | 13.3 GB |
| current c50 | 3 | 13.6 GB |
| shared_first c8 | 15 | 12.7 GB |
| shared_first c16 | 18 | 15.5 GB |
| shared_first c50 | 3 | 16.4 GB |

Sequential traffic also grew memory (`shape_growth_probe.py`: 1.7 GB to 7.8 GB over 30 identical requests), and
earlier unguarded runs reached 15 to 19 GB, then stalled while swapping.

## Root cause: a full 4096 token KV buffer per request, pooled forever

Traced with `mlx_memlog/` (a `sitecustomize` hook that logs MLX's own counters and the runner's cache objects
once a second) and `mlx_memory_ladder.py` (sequential, then concurrency 2, 4, 8 and 50). All numbers are MLX
active memory unless marked footprint.

1. **Each request gets its own contiguous KV buffer sized for 4096 tokens.** Separate from the shared radix KV
   pool, `MlxModelRunner._new_native_cache` builds a `ContiguousAttentionKVCache(max_seq_len=4096)` for every
   attention layer: 28 layers x K and V x 8 heads x 4096 x 128 x bf16 = **470 MB per request**, whatever the
   prompt length. Bot chat prompts are about 300 tokens, so about 93% of each buffer is never written.
2. **Buffers go back to a pool that never shrinks.** `_release_cache` appends finished caches to `_cache_pool`
   for reuse, with no cap. Memory ratchets to peak concurrency x 470 MB and stays there. 50 concurrent bots
   would need 23.5 GB, past the 19 GB wired limit: that is the swap stall.
3. **Release lags a step.** A finished request's cache is released only when a later decode batch runs
   without it (`tp_worker._cleanup_stale_rids`), so new prefills allocate fresh caches meanwhile, and the
   last request's cache stays live while the server is idle.
4. **The one time jump in sequential runs is the radix KV pool, not a leak.** `--mem-fraction-static 0.25`
   sizes it at 30,477 token slots (3.5 GB), allocated lazily on the first real request: active memory went
   from 1.3 GB to 4.7 GB on request one, then held.

Stock server, ladder at the moment the guard tripped (c8): 3.4 GB radix pool + 12 per request caches x 470 MB
(5.6 GB) + 0.3 GB weights = 9.4 of 9.8 GB active. MLX's freed-buffer cache held only 0.2 GB then, which is why
`SGLANG_MLX_CLEAR_CACHE_STEPS` and an MLX cache limit alone did nothing.

## With the fix (patched at runtime, SGLang checkout untouched)

`MLX_PATCH_INIT_SEQ=256 MLX_PATCH_POOL_MAX=8 MLX_PATCH_CACHE_LIMIT_GB=1` (see `mlx_memlog/sitecustomize.py`):
start each per request buffer at 256 tokens (it still doubles on overflow; bot chat settles at 512, 59 MB per
request), keep at most 8 idle buffers, cap MLX's freed-buffer cache at 1 GB. Same matrix as above:

| Wave | Finished | Peak footprint | Requests/s | TTFT p50 | Latency p50 |
|---|---|---|---|---|---|
| current c1 | 50/50 | 6.1 GB | 6.3 | 83 ms | 113 ms |
| current c8 | 50/50 | 6.6 GB | 7.5 | 244 ms | 833 ms |
| current c16 | 50/50 | 7.0 GB | 7.5 | 430 ms | 2.3 s |
| current c50 | 50/50 | 8.9 GB | 7.7 | 3.1 s | 6.2 s |
| shared_first c1 | 50/50 | 5.8 GB | 7.8 | 59 ms | 97 ms |
| shared_first c8 | 50/50 | 6.7 GB | 8.9 | 188 ms | 767 ms |
| shared_first c16 | 50/50 | 7.1 GB | 8.9 | 932 ms | 1.7 s |
| shared_first c50 | 50/50 | 9.1 GB | 9.0 | 3.2 s | 5.3 s |

Every wave finished; none came near the 10 GB guard. Four back to back c50 waves on one server plateaued at
6.5 to 7.5 GB footprint, so nothing accumulates. 3.5 GB of each figure is the radix pool, which
`--mem-fraction-static` controls. Throughput tops out near 9 requests per second by concurrency 8; past that,
extra concurrency only adds queueing, so the game server should cap in-flight bot chat at about 8.

Upstream fix, in `hardware_backend/mlx/model_runner.py`: size the per request buffer from the request (or start
small and rely on `_grow`), bound `_cache_pool`, and release a request's cache when it finishes rather than on
the next decode batch.

## Files

- `bot_chat_bench.py`: the load generator (two layouts, streaming TTFT, cached token accounting)
- `run_matrix.sh`: one fresh server per wave with the memory guard
- `run_bench.sh`: single server run with a footprint monitor
- `shape_growth_probe.py`: sequential growth probe (identical versus varied prompt lengths)
- `mlx_memlog/sitecustomize.py`: per second MLX memory log for the scheduler, plus the `MLX_PATCH_*` experiment knobs
- `mlx_memory_ladder.py`, `run_memory_ladder.sh`: fresh server, sequential then concurrency ladder, phase marks

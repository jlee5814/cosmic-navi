# Memory logger for SGLang's MLX backend. Put this directory on PYTHONPATH and set MLX_MEMLOG_DIR;
# every Python process then starts a daemon thread, and the one that owns an MlxModelRunner (the
# scheduler) writes one JSON line per second to $MLX_MEMLOG_DIR/memlog-<pid>.jsonl with MLX's own
# counters and the bytes held by the runner's per-request contiguous KV caches.
import os
import threading


def _nbytes(cache_list):
    total = 0
    for layer in cache_list or ():
        for name in ("keys", "values"):
            arr = getattr(layer, name, None)
            if arr is not None:
                total += arr.nbytes
    return total


def _seq_lens(cache_list):
    return sorted({getattr(c, "max_seq_len", 0) for c in cache_list or () if getattr(c, "keys", None) is not None})


def _pool_bytes(pool):
    total = 0
    for v in vars(pool).values() if pool is not None else ():
        items = v if isinstance(v, (list, tuple)) else [v]
        for a in items:
            total += getattr(a, "nbytes", 0) if type(a).__name__ == "array" else 0
    return total


def _census():
    """Unique live mx.arrays reachable from gc-tracked holders, grouped by (holder type, shape)."""
    import gc
    import mlx.core as mx
    seen, groups = set(), {}
    for holder in gc.get_objects():
        for a in gc.get_referents(holder):
            if type(a) is not mx.array or id(a) in seen:
                continue
            seen.add(id(a))
            key = f"{type(holder).__name__} {tuple(a.shape)} {a.dtype}"
            n, b = groups.get(key, (0, 0))
            groups[key] = (n + 1, b + a.nbytes)
    top = sorted(groups.items(), key=lambda kv: -kv[1][1])[:12]
    return [{"holder_shape": k, "count": n, "gb": round(b / 1e9, 3)} for k, (n, b) in top]


def _run(out_dir):
    import gc
    import json
    import sys
    import time

    runner = None
    quiet, censused = 0, False
    path = os.path.join(out_dir, f"memlog-{os.getpid()}.jsonl")
    while True:
        time.sleep(1)
        if runner is None:
            if "sglang.srt.hardware_backend.mlx.model_runner" not in sys.modules:
                continue
            runner = next((o for o in gc.get_objects()
                           if type(o).__name__ == "MlxModelRunner" and hasattr(o, "_cache_pool")), None)
            if runner is None:
                continue
        try:
            import mlx.core as mx
            pool = list(runner._cache_pool)
            live = list(runner._req_caches.values())
            rec = {
                "t": round(time.time(), 2),
                "active_gb": mx.get_active_memory() / 1e9,
                "cache_gb": mx.get_cache_memory() / 1e9,
                "peak_gb": mx.get_peak_memory() / 1e9,
                "pooled_caches": len(pool),
                "live_caches": len(live),
                "pooled_gb": sum(_nbytes(c) for c in pool) / 1e9,
                "live_gb": sum(_nbytes(c) for c in live) / 1e9,
                "seq_lens": sorted({n for c in pool + live for n in _seq_lens(c)}),
                "kv_pool_gb": _pool_bytes(getattr(runner, "_attention_kv_pool", None)) / 1e9,
                "runner_max_seq_len": runner._max_seq_len,
            }
            # One census per quiet spell (no live request for 5 s after activity): it pauses the scheduler.
            quiet = quiet + 1 if len(live) <= 1 else 0
            if quiet == 0:
                censused = False
            if os.environ.get("MLX_MEMLOG_CENSUS") and quiet >= 5 and not censused and rec["peak_gb"] > 1:
                rec["census"], censused = _census(), True
        except Exception as exc:  # the scheduler mutates these dicts concurrently; skip a beat
            rec = {"t": round(time.time(), 2), "error": repr(exc)}
        with open(path, "a") as f:
            f.write(json.dumps(rec) + "\n")


_dir = os.environ.get("MLX_MEMLOG_DIR")
if _dir:
    os.makedirs(_dir, exist_ok=True)
    threading.Thread(target=_run, args=(_dir,), daemon=True, name="mlx-memlog").start()


# Experiment knobs (unset = stock behavior). Patches the runner class as soon as its module is
# imported, before the runner exists, so the SGLang checkout itself stays untouched.
#   MLX_PATCH_INIT_SEQ=256  first allocation of each per-request contiguous KV cache (stock: 4096);
#                           the cache still doubles on overflow
#   MLX_PATCH_POOL_MAX=8    idle cache lists kept for reuse; extras are dropped so MLX can free them
#   MLX_PATCH_CACHE_LIMIT_GB=1  cap MLX's pool of freed buffers in the scheduler (mx.set_cache_limit)
def _patch():
    import sys
    import time

    init_seq = int(os.environ.get("MLX_PATCH_INIT_SEQ", "0"))
    pool_max = int(os.environ.get("MLX_PATCH_POOL_MAX", "-1"))
    cache_limit_gb = float(os.environ.get("MLX_PATCH_CACHE_LIMIT_GB", "-1"))
    name = "sglang.srt.hardware_backend.mlx.model_runner"
    while name not in sys.modules or not hasattr(sys.modules[name], "MlxModelRunner"):
        time.sleep(0.02)
    cls = sys.modules[name].MlxModelRunner
    orig_init = cls.__init__

    def __init__(self, *a, **kw):
        orig_init(self, *a, **kw)
        if init_seq:
            self._max_seq_len = init_seq
        if cache_limit_gb >= 0:
            import mlx.core as mx
            mx.set_cache_limit(int(cache_limit_gb * 1e9))
    cls.__init__ = __init__
    if pool_max >= 0:
        orig_release = cls._release_cache

        def _release_cache(self, cache):
            if len(self._cache_pool) < pool_max:
                orig_release(self, cache)
        cls._release_cache = _release_cache


if _dir and any(k.startswith("MLX_PATCH_") for k in os.environ):
    threading.Thread(target=_patch, daemon=True, name="mlx-patch").start()

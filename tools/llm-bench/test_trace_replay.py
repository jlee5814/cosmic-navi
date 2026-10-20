"""python3 -m unittest tools/llm-bench/test_trace_replay.py (no server or aiohttp needed)."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import trace_replay as tr  # noqa: E402

HEADER = "at_ms\tbackend\tmodel\tkind\toutcome\tlatency_ms\tprompt_tokens\tcompletion_tokens\tcached_tokens\n"


def write_trace(rows):
    f = tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False)
    f.write(HEADER)
    for r in rows:
        f.write("\t".join(str(x) for x in r) + "\n")
    f.close()
    return f.name


class LoadTrace(unittest.TestCase):
    def test_arrival_is_completion_minus_latency_in_arrival_order(self):
        path = write_trace([
            (10_500, "openai", "default", "chat", "ok", 500, 300, 20, 100),       # arrived 10.0 s
            (10_200, "openai", "default", "chat", "ok", 1_200, 310, 22, 100),     # arrived 9.0 s, first
            (12_000, "openai", "default", "navi_route", "ok", 100, 200, 8, 180),  # arrived 11.9 s
        ])
        calls = tr.load_trace(path)
        self.assertEqual([c.offset_s for c in calls], [0.0, 1.0, 2.9])
        self.assertEqual([c.prompt for c in calls], [310, 300, 200])
        self.assertEqual(calls[0].live_latency_ms, 1_200)

    def test_unknown_sizes_take_the_median_of_their_kind(self):
        path = write_trace([
            (1_000, "openai", "default", "chat", "ok", 100, 300, 20, -1),
            (2_000, "openai", "default", "chat", "ok", 100, 500, 30, -1),
            (20_000, "openai", "default", "chat", "timeout", 15_000, -1, -1, -1),
            (21_000, "openai", "default", "navi_route", "failed", 0, -1, -1, -1),
        ])
        calls = tr.load_trace(path)
        timeout = [c for c in calls if c.live_latency_ms == 15_000][0]
        self.assertEqual((timeout.prompt, timeout.output), (400, 25))
        route = [c for c in calls if c.kind == "navi_route"][0]
        self.assertEqual((route.prompt, route.output), (400, 25), "no known navi_route: overall median")

    def test_kinds_filter(self):
        path = write_trace([
            (1_000, "openai", "default", "chat", "ok", 100, 300, 20, 0),
            (2_000, "openai", "default", "navi_route", "ok", 100, 200, 8, 0),
        ])
        self.assertEqual([c.kind for c in tr.load_trace(path, {"navi_route"})], ["navi_route"])
        self.assertEqual(tr.load_trace(path, {"generate"}), [])


class Windows(unittest.TestCase):
    def test_busiest_picks_the_densest_window_and_rebases_it(self):
        offsets = [0, 5, 100, 101, 102, 103, 400]
        calls = [tr.Call(o, "chat", 300, 20, 100) for o in offsets]
        picked = tr.busiest(calls, minutes=1)
        self.assertEqual([c.offset_s for c in picked], [0, 1, 2, 3])
        self.assertEqual(tr.busiest([], 1), [])

    def test_peak_in_flight(self):
        self.assertEqual(tr.peak_in_flight([(0, 2), (1, 3), (1.5, 1.8), (5, 6)]), 3)
        self.assertEqual(tr.peak_in_flight([(0, 1), (1, 2)]), 1, "one ends as the next starts")
        calls = [tr.Call(0, "chat", 1, 1, 2_000), tr.Call(1, "chat", 1, 1, 2_000)]
        self.assertEqual(tr.live_peak(calls), 2)


class Helpers(unittest.TestCase):
    def test_filler_is_repeatable_per_call_and_varies_between_calls(self):
        self.assertEqual(tr.filler(50, 7), tr.filler(50, 7))
        self.assertNotEqual(tr.filler(50, 7), tr.filler(50, 8))
        self.assertEqual(len(tr.filler(50, 7).split()), 50)
        self.assertEqual(tr.filler(-3, 1), "")

    def test_percentile_is_nearest_rank_like_llm_stats(self):
        xs = [100, 200, 300, 400, 500, 600, 700, 800, 900, 1000]
        self.assertEqual(tr.percentile(xs, 50), 500)
        self.assertEqual(tr.percentile(xs, 95), 1000)
        self.assertEqual(tr.percentile([100], 99), 100)
        self.assertIsNone(tr.percentile([], 50))

    def test_summary_counts_errors_apart_and_reports_live_numbers(self):
        calls = [tr.Call(0, "chat", 300, 20, 200), tr.Call(1, "chat", 300, 20, 400)]
        results = [
            {"sent_s": 0.0, "latency": 0.2, "ttft": 0.05, "prompt": 300, "output": 20, "cached": 150, "error": None},
            {"sent_s": 1.0, "latency": 0.01, "error": "ClientConnectorError"},
        ]
        s = tr.summarize(calls, results, wall_s=2.0, live_calls=calls)
        self.assertEqual((s["completed"], s["errors"]), (1, 1))
        self.assertEqual(s["error_types"], {"ClientConnectorError": 1})
        self.assertEqual(s["first_error_s"], 1.0)
        self.assertEqual(s["latency_ms"]["p99"], 200)
        self.assertEqual(s["live_latency_ms"], {"p50": 200, "p99": 400})
        self.assertEqual(s["cache_hit_pct_replay"], 50.0)
        self.assertEqual(s["output_tok_per_s"], 10.0)


if __name__ == "__main__":
    unittest.main()

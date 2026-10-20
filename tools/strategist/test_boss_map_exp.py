"""Unit tests for boss_map_exp's per map EXP rates and dry pick shares.
Run: python3 -m unittest tools/strategist/test_boss_map_exp.py"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import boss_map_exp as bm  # noqa: E402

BUDDHA = 800020130


def row(ts, exp, map_id, grinding="1", name="SipsBuddy2"):
    return {"ts": str(ts), "id": "255", "name": name, "level": "97", "total_exp": str(exp), "map": str(map_id),
            "grinding": grinding, "errand": "-1"}


class BossMapExpTest(unittest.TestCase):
    def test_rates_split_boss_map_time_from_the_rest(self):
        rows = [row(0, 0, BUDDHA), row(300, 1000, BUDDHA), row(600, 1000, BUDDHA),        # 600 s, 1000 EXP
                row(900, 1000, 551030100), row(1200, 4000, 551030100),               # map change pair skipped
                row(1500, 4000, 551030100, grinding="0"),                            # not grinding: skipped
                row(5000, 9000, 551030100)]                                          # gap over 12 min: skipped
        acc = bm.map_rates(rows, 0, {BUDDHA})["SipsBuddy2"]
        self.assertEqual(acc["boss"][BUDDHA], [1000, 600])
        self.assertEqual(acc["else"], [3000, 300])

    def test_dry_picks_count_holds_where_the_mob_never_lost_hp(self):
        recs = [{"t": 10, "bot": 255, "map": BUDDHA, "out": {"holdMs": 30_000, "died": False, "mobHpDrop": 0.0}},
                {"t": 11, "bot": 255, "map": BUDDHA, "out": {"holdMs": 30_000, "died": True, "mobHpDrop": 1.0}},
                {"t": 12, "bot": 255, "map": BUDDHA, "out": {"holdMs": 600_000, "died": False, "mobHpDrop": 0.0}},
                {"t": 13, "bot": 255, "map": 1, "out": {"holdMs": 5_000, "died": False, "mobHpDrop": 0.4}}]
        with tempfile.TemporaryDirectory() as d:
            Path(d, "grind-target-20261020.jsonl").write_text("\n".join(json.dumps(r) for r in recs))
            dry = bm.dry_picks(d, 0, {BUDDHA})[255]
        self.assertEqual(dry["boss"][BUDDHA], [1, 2])   # the 10 minute hold is a break, not a pick
        self.assertEqual(dry["else"], [0, 1])


if __name__ == "__main__":
    unittest.main()

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_arms as c  # noqa: E402

MIN = 60_000


def line(t, bot, exp, arm=None, hold=10_000, died=True):
    r = {"t": t, "bot": bot, "out": {"holdMs": hold, "exp": exp, "died": died, "mobHpDrop": 1.0 if died else 0.0}}
    if arm:
        r["arm"] = arm
    return r


class CompareArmsTest(unittest.TestCase):
    def test_each_bot_is_compared_with_itself_before_the_split(self):
        since = 1000 * MIN
        recs = []
        for bot, before, after, arm in [(1, 100, 130, "model"), (2, 300, 390, "model"),
                                        (3, 100, 100, "rule"), (4, 400, 400, "rule")]:
            for i in range(300):  # 50 min a side at 10 s a pick
                recs.append(line(since - 600 * MIN + i * 10_000, bot, before))
                recs.append(line(since + i * 10_000, bot, after, arm))
        s = c.summarize(c.per_bot(recs, since, 24 * 60 * MIN), boots=200)
        self.assertEqual(2, s["model"]["bots"])
        self.assertAlmostEqual(0.30, s["model"]["mean_change"])  # +30% each, whatever its own level
        self.assertAlmostEqual(0.0, s["rule"]["mean_change"])
        self.assertAlmostEqual(0.30, s["difference"])

    def test_a_bot_with_too_little_grinding_on_a_side_does_not_count(self):
        recs = [line(10 * MIN, 1, 100), line(1000 * MIN, 1, 100, "model")]
        self.assertIsNone(c.change(c.per_bot(recs, 500 * MIN, 24 * 60 * MIN)[1]))


if __name__ == "__main__":
    unittest.main()

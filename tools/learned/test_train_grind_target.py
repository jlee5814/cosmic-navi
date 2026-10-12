import random
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import train_grind_target as t  # noqa: E402


def decision(ts, pick, p, routes, out_exp=100, hold=1000, bot=1, lvl=30, crowd=None):
    cands = [{"mob": 1, "mobLvl": 30, "mobHp": 1.0, "dx": 50 * (i + 1), "dy": 0, "route": r, "local": r,
              "region": i, "crowd": (crowd or [1] * len(routes))[i]} for i, r in enumerate(routes)]
    return {"t": ts, "bot": bot, "map": 100, "lvl": lvl, "hp": 1.0, "rope": False, "cands": cands,
            "pick": pick, "p": p, "out": {"holdMs": hold, "exp": out_exp}}


class SupportTest(unittest.TestCase):
    def test_a_certain_pick_supports_only_the_rule(self):
        self.assertEqual({0: 1.0}, t.support(decision(1, 0, 1.0, [1000, 1100])))

    def test_exploration_support_follows_the_band(self):
        sup = t.support(decision(1, 0, 0.9, [1000, 1400, 9000]))
        self.assertEqual({0: 0.9, 1: 0.1}, {k: round(v, 3) for k, v in sup.items()})
        sup = t.support(decision(1, 2, 0.05, [1000, 1400, 2400, 2450]))
        self.assertEqual({0: 0.9, 1: 0.05, 2: 0.05}, {k: round(v, 3) for k, v in sup.items()})

    def test_lines_that_do_not_match_the_policy_are_dropped(self):
        rows = t.prepare([decision(1, 1, 0.1, [1000, 9000])])  # explored a candidate outside the band
        self.assertEqual([], rows)


class ValueTest(unittest.TestCase):
    def test_value_is_exp_above_the_bots_own_pace(self):
        rows = t.prepare([decision(1, 0, 1.0, [1000], out_exp=300, hold=1000),
                          decision(2, 0, 1.0, [1000], out_exp=100, hold=1000)])
        self.assertEqual([100.0, -100.0], [round(r["value"], 6) for r in rows])

    def test_breaks_are_not_outcomes(self):
        self.assertEqual([], t.prepare([decision(1, 0, 1.0, [1000], hold=t.MAX_HOLD_MS + 1)]))


class LearnsTest(unittest.TestCase):
    def test_learns_that_the_crowded_alternative_pays_and_the_replay_sees_it(self):
        # Ground truth: the 2nd candidate sits in a crowd and pays 3x. The rule never knows; exploration does.
        rnd = random.Random(1)
        recs = []
        for ts in range(6000):
            explore = rnd.random() < t.EXPLORE
            pick = 1 if explore else 0
            exp = 300 if pick == 1 else 100
            recs.append(decision(ts, pick, 0.1 if explore else 0.9, [1000, 1200], out_exp=exp + rnd.randint(-20, 20),
                                 crowd=[1, 4]))
        rows = t.prepare(recs)
        cut = int(len(rows) * 0.75)
        model = t.fit(rows[:cut])
        ev = t.evaluate(model, rows[cut:], boots=200)
        self.assertEqual(ev["decisions"], ev["differs_from_rule"], "always takes the crowded one")
        self.assertGreater(ev["gain_90ci"][0], 0)

    def test_no_signal_means_no_gain_above_noise(self):
        rnd = random.Random(2)
        recs = []
        for ts in range(3000):
            explore = rnd.random() < t.EXPLORE
            recs.append(decision(ts, 1 if explore else 0, 0.1 if explore else 0.9, [1000, 1200],
                                 out_exp=100 + rnd.randint(-50, 50)))
        rows = t.prepare(recs)
        cut = int(len(rows) * 0.75)
        ev = t.evaluate(t.fit(rows[:cut]), rows[cut:], boots=200)
        self.assertLess(ev["gain_90ci"][0], 0)


if __name__ == "__main__":
    unittest.main()

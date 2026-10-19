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



class TreesTest(unittest.TestCase):
    @staticmethod
    def interaction_log(n, seed):
        # The 2nd candidate pays 250 when it is far and nearly dead, or near and fresh; otherwise 0. The rule's
        # first always pays 150, more than the 2nd's average, so a weighted sum (which can't separate the two
        # cases by distance or mob HP alone) keeps the rule; only a model of the interaction gains.
        rnd = random.Random(seed)
        recs = []
        for ts in range(n):
            explore = rnd.random() < t.EXPLORE
            far, low = rnd.random() < 0.5, rnd.random() < 0.5
            pays = far == low
            pick = 1 if explore else 0
            exp = (250 if pays else 0) if pick == 1 else 150
            r = decision(ts, pick, 0.1 if explore else 0.9, [1000, 1200], out_exp=exp + rnd.randint(-20, 20))
            r["cands"][1]["dx"] = 600 if far else 60
            r["cands"][1]["mobHp"] = 0.2 if low else 1.0
            recs.append(r)
        return t.prepare(recs)

    def test_trees_learn_an_interaction_the_linear_model_cannot(self):
        rows = self.interaction_log(20000, 3)
        cut = int(len(rows) * 0.75)
        trees = t.evaluate(t.fit_trees(rows[:cut], trees=40, min_leaf=50.0), rows[cut:], boots=200)
        linear = t.evaluate(t.fit(rows[:cut]), rows[cut:], boots=200)
        self.assertGreater(trees["gain_90ci"][0], 0)
        self.assertGreater(trees["gain"], linear["gain"] + 20)
        self.assertLessEqual(linear["gain_90ci"][0], 0)

    def test_trees_find_no_gain_where_there_is_none(self):
        rnd = random.Random(4)
        recs = []
        for ts in range(4000):
            explore = rnd.random() < t.EXPLORE
            recs.append(decision(ts, 1 if explore else 0, 0.1 if explore else 0.9, [1000, 1200],
                                 out_exp=100 + rnd.randint(-50, 50)))
        rows = t.prepare(recs)
        cut = int(len(rows) * 0.75)
        ev = t.evaluate(t.fit_trees(rows[:cut], trees=40, min_leaf=50.0), rows[cut:], boots=200)
        self.assertLessEqual(ev["gain_90ci"][0], 0)  # the gate needs the lower end above zero

    def test_raw_threshold_scoring_matches_the_binned_fit(self):
        rows = self.interaction_log(3000, 5)
        model = t.fit_trees(rows, trees=10, min_leaf=50.0)
        for row in rows[:200]:
            x = t.features(row["rec"], row["rec"]["pick"])
            b = [t.bin_of(model["cuts"][j], x[j]) for j in range(len(x))]
            for tree in model["trees"]:
                self.assertEqual(t.leaf_of(tree, b), t.tree_value(tree, x))



class Stage3Test(unittest.TestCase):
    def test_a_line_that_logs_its_support_is_weighted_by_it(self):
        r = decision(1, 0, 0.05, [1000, 1400])
        r["sup"] = {"0": 0.05, "1": 0.95}  # the model arm put 1 - EXPLORE on candidate 1
        r["pick"], r["p"] = 1, 0.95
        rows = t.prepare([r])
        self.assertEqual({0: 0.05, 1: 0.95}, rows[0]["support"])

    def test_a_challenger_is_compared_with_the_served_champion_on_the_same_rows(self):
        rows = TreesTest.interaction_log(20000, 6)
        cut = int(len(rows) * 0.75)
        trees = t.fit_trees(rows[:cut], trees=40, min_leaf=50.0)
        linear = t.fit(rows[:cut])
        linear["version"] = "linear-old"
        vs = t.compare(trees, linear, rows[cut:], boots=200)
        self.assertGreater(vs["gain_90ci"][0], 0)
        self.assertEqual("linear-old", vs["champion_version"])
        self.assertAlmostEqual(0.0, t.compare(linear, linear, rows[cut:], boots=50)["gain"])


if __name__ == "__main__":
    unittest.main()

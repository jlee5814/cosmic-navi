"""Unit tests for stuck_tracker's spot grouping, recurrence rule and issue matching.
Run: python3 -m unittest tools/strategist/test_stuck_tracker.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import stuck_tracker as st  # noqa: E402


def s(bot, taken_at, x, y, flag="frozen", map_id=600020100):
    return {"taken_at": taken_at, "bot": bot, "bot_id": 1, "map": map_id, "map_name": "m", "flag": flag,
            "x": x, "y": y, "heading_to": None, "status": ""}


class StuckTrackerTest(unittest.TestCase):
    def test_two_bots_at_one_jump_are_one_recurring_spot(self):
        groups = st.spots([s("SipsBuddy32", "2026-10-09 19:50", -1316, 200),
                           s("SipsBuddy35", "2026-10-09 20:29", -1290, 210)])
        self.assertEqual(len(groups), 1)
        self.assertTrue(st.recurs(groups[0]))

    def test_one_bot_needs_snapshots_half_an_hour_apart(self):
        self.assertFalse(st.recurs([s("SipsBuddy2", "2026-10-09 20:29", 0, 0), s("SipsBuddy2", "2026-10-09 20:40", 0, 0)]))
        self.assertTrue(st.recurs([s("SipsBuddy2", "2026-10-09 19:50", 0, 0), s("SipsBuddy2", "2026-10-09 20:29", 0, 0)]))

    def test_spots_split_by_distance_flag_and_map(self):
        groups = st.spots([s("a", "2026-10-09 20:00", 0, 0), s("b", "2026-10-09 20:00", 400, 0),
                           s("c", "2026-10-09 20:00", 0, 0, flag="falling"),
                           s("d", "2026-10-09 20:00", 0, 0, map_id=682010200)])
        self.assertEqual(len(groups), 4)

    def test_a_falling_column_matches_on_x_alone(self):
        self.assertTrue(st.same_spot(s("a", "t", 100, 0, "falling"), s("b", "t", 120, 9000, "falling")))

    def test_the_open_issue_wins_over_a_closed_one_for_the_same_spot(self):
        marker = "<!-- stuck-spot map=600020100 flag=frozen x=-1316 y=200 -->"
        issues = [{"number": 3, "state": "CLOSED", "body": marker}, {"number": 9, "state": "OPEN", "body": marker},
                  {"number": 12, "state": "OPEN", "body": "<!-- stuck-spot map=600020100 flag=frozen x=900 y=200 -->"}]
        self.assertEqual(st.matching_issue(s("x", "t", -1300, 205), issues)["number"], 9)
        self.assertIsNone(st.matching_issue(s("x", "t", 0, 0, map_id=1), issues))

    def test_sightings_need_a_position(self):
        snap = {"taken_at": "2026-10-09 21:00", "maps": [{"id": 600020100, "name": "m"}], "bots": [
            {"id": 1, "name": "a", "map": 600020100, "flags": ["frozen"], "motion": {"pos": [5, 6]}},
            {"id": 2, "name": "b", "map": 600020100, "flags": ["frozen"], "motion": {"changed_map": True}},
            {"id": 3, "name": "c", "map": 600020100, "flags": ["no_exp"], "motion": {"pos": [5, 6]}}]}
        out = st.sightings_from(snap)
        self.assertEqual([(o["bot"], o["x"], o["y"]) for o in out], [("a", 5, 6)])


if __name__ == "__main__":
    unittest.main()

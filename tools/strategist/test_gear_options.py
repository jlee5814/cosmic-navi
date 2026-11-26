"""Unit tests for gear_options' stat gated weapon report and shop ceiling flag.
Run: python3 -m unittest tools/strategist/test_gear_options.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gear_options as go  # noqa: E402

MAPLE_STAFF = 1382012   # worn, no requirements
MYSTIC_CANE = 1382011   # shop staff: +4 magic attack over the worn one, needs LUK 40
STRONG_STAFF = 1382099  # made up: +50 magic attack, needs LUK 40
STAVES = {
    MAPLE_STAFF: {"req_level": 43, "attack": 0, "magic_attack": 63, "req_job": 0,
                  "req": {"str": 0, "dex": 0, "int": 0, "luk": 0}},
    MYSTIC_CANE: {"req_level": 51, "attack": 0, "magic_attack": 67, "req_job": 2,
                  "req": {"str": 0, "dex": 0, "int": 140, "luk": 40}},
    STRONG_STAFF: {"req_level": 51, "attack": 0, "magic_attack": 113, "req_job": 2,
                   "req": {"str": 0, "dex": 0, "int": 140, "luk": 40}},
}


class GearOptionsTest(unittest.TestCase):
    def setUp(self):
        self.prev = go.weapon_info
        go.weapon_info = STAVES.get

    def tearDown(self):
        go.weapon_info = self.prev

    def enrich(self, shop):
        cleric = {"id": 259, "lvl": 61, "detail": {"job": 230, "meso": 6_000_000, "str": 4, "dex": 4, "int": 300, "luk": 4}}
        entry = {"map": 1, "cap_reached": True}
        go.enrich(cleric, entry, [], {}, {259: (MAPLE_STAFF, 0, 63)}, shop)
        return entry

    def test_a_staff_worth_less_than_the_luk_it_needs_is_not_reported(self):
        entry = self.enrich({MYSTIC_CANE: (2_000_000, "Shop")})
        self.assertNotIn("upgrade_blocked_by_stats", entry, "36 AP in INT beats +4 magic attack")
        self.assertTrue(entry["weapon"]["shop_sells_better"])

    def test_a_staff_worth_more_than_the_luk_it_needs_is_reported_with_its_ap_cost(self):
        entry = self.enrich({MYSTIC_CANE: (2_000_000, "Shop"), STRONG_STAFF: (3_000_000, "Shop")})
        blocked = entry["upgrade_blocked_by_stats"]
        self.assertEqual(113, blocked["attack"])
        self.assertEqual(36, blocked["ap_cost"])
        self.assertEqual({"luk": 40}, blocked["needs"])

    def test_no_stronger_shop_weapon_marks_the_shop_ceiling(self):
        self.assertFalse(self.enrich({})["weapon"]["shop_sells_better"])


if __name__ == "__main__":
    unittest.main()

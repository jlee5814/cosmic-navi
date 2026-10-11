import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import responder  # noqa: E402


class ResponderTest(unittest.TestCase):
    def test_a_bot_is_asked_about_once_an_hour_per_kind(self):
        incidents = [{"id": 254, "kind": "errand_loop"}, {"id": 279, "kind": "no_exp_on_errand"}]
        state = {"254": {"kind": "errand_loop", "at": 1000}, "279": {"kind": "refreezing", "at": 1000}}
        self.assertEqual([279], [i["id"] for i in responder.due(incidents, state, 1000 + 60, 3)])
        self.assertEqual([254, 279], [i["id"] for i in responder.due(incidents, state, 1000 + 3601, 3)])

    def test_the_run_and_day_budgets_cap_model_calls(self):
        incidents = [{"id": n, "kind": "errand_loop"} for n in range(10)]
        self.assertEqual(3, len(responder.due(incidents, {}, 0, 3)))
        self.assertEqual([], responder.due(incidents, {}, 0, 0))

    def test_the_owner_whisper_reason_is_one_clean_line(self):
        self.assertEqual("sold its return scrolls, holding errands",
                         responder.clean_reason('sold its "return scrolls", holding errands\n'))
        self.assertEqual(120, len(responder.clean_reason("x" * 300)))
        self.assertNotIn('"', responder.clean_reason('a "quoted" reason'))

    def test_the_model_can_only_answer_from_the_menu(self):
        self.assertEqual(responder.ACTIONS, responder.SCHEMA["properties"]["action"]["enum"])
        self.assertIn("none", responder.ACTIONS)

    def test_the_prompt_carries_the_evidence_but_not_private_fields(self):
        p = responder.prompt_for({"id": 254, "name": "SipsBuddy1", "_log": ["bot-sell: SipsBuddy1 sold forced"],
                                  "_chat": ["09:12 no room in my bag for Waffle"]})
        self.assertIn("bot-sell: SipsBuddy1 sold forced", p)
        self.assertIn("no room in my bag for Waffle", p)
        self.assertNotIn('"_log"', p)


if __name__ == "__main__":
    unittest.main()

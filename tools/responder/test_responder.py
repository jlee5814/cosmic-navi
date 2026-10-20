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

    def test_a_bot_under_an_agent_claim_gets_no_model_call_and_no_action(self):
        import json
        import os
        import tempfile
        from unittest import mock
        with tempfile.TemporaryDirectory() as d:
            os.environ["COSMIC_AGENTS_DIR"] = d + "/agents"
            try:
                with mock.patch.object(responder.agents, "gh", return_value=True):
                    responder.agents.claim("cloud-claude", ["bot:255"], "watching the Buddha freeze", watch=True)
                inc = Path(d, "inc.json")
                inc.write_text(json.dumps([
                    {"id": 255, "name": "SipsBuddy2", "kind": "refreezing", "map": 800020130, "_log": [], "_chat": []},
                    {"id": 254, "name": "SipsBuddy1", "kind": "errand_loop", "map": 240000000, "_log": [], "_chat": []}]))
                none = {"diagnosis": "x", "action": "none", "reason": "x", "file_issue": False}
                argv = ["responder.py", "--incidents", str(inc), "--runs", d + "/runs"]
                with mock.patch.object(responder, "ask_model", return_value=(none, 0.1)) as ask, \
                        mock.patch.object(sys, "argv", argv):
                    responder.main()
                    responder.main()  # a second run logs the hold once, not again
                self.assertEqual([254], [c.args[0]["id"] for c in ask.call_args_list])  # 254 once an hour, 255 never
                log = [json.loads(x) for x in Path(d, "runs", "log.jsonl").read_text().splitlines()]
                skips = [r for r in log if r.get("skipped")]
                self.assertEqual(["SipsBuddy2"], [r["bot"] for r in skips])
                self.assertIn("cloud-claude", skips[0]["claims"][0])
            finally:
                os.environ.pop("COSMIC_AGENTS_DIR", None)


if __name__ == "__main__":
    unittest.main()

"""Unit tests for the agent claims, locks and journal.
Run: python3 -m unittest tools/agents/test_agents.py"""
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agents  # noqa: E402


class AgentsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        os.environ["COSMIC_AGENTS_DIR"] = self.dir.name
        self.gh = mock.patch.object(agents, "gh", return_value=True).start()

    def tearDown(self):
        mock.patch.stopall()
        self.dir.cleanup()
        os.environ.pop("COSMIC_AGENTS_DIR", None)

    def test_a_second_session_cannot_claim_the_same_bot_but_can_take_other_work(self):
        # This morning's collision: two sessions on SipsBuddy2's Buddha freeze.
        mine, _ = agents.claim("one", ["bot:255", "map:800020130"], "Buddha freeze", issue=188)
        theirs, clash = agents.claim("two", ["bot:255"], "same freeze")
        self.assertIsNone(theirs)
        self.assertEqual([mine["id"]], [c["id"] for c in clash])
        other, clash = agents.claim("two", ["bot:254"], "Tomb II loop")
        self.assertIsNotNone(other)
        self.assertEqual([], clash)
        self.assertIn("issue:188", mine["subjects"])
        self.gh.assert_called()  # the issue got its label and comment

    def test_an_expired_claim_blocks_nothing_and_a_released_one_is_gone(self):
        c, _ = agents.claim("one", ["area:combat"], "x", hours=0.5)
        self.assertEqual(1, len(agents.conflicts(["area:combat"], "two")))
        self.assertEqual([], agents.conflicts(["area:combat"], "two", now=time.time() + 3600))
        agents.release(c["id"], "done")
        self.assertEqual([], agents.live_claims())
        journal = (Path(self.dir.name) / "journal.md").read_text()
        self.assertIn("claimed " + c["id"], journal)
        self.assertIn("released " + c["id"] + ": done", journal)

    def test_the_deploy_lock_has_one_holder_until_it_expires(self):
        self.assertIsNone(agents.lock("deploy", "one", minutes=20))
        self.assertEqual("one", agents.lock("deploy", "two")["session"])
        self.assertFalse(agents.unlock("deploy", "two"))
        self.assertIsNone(agents.lock("deploy", "two", now=time.time() + 21 * 60), "a stale lock is taken over")
        self.assertTrue(agents.unlock("deploy", "two"))
        self.assertIsNone(agents.lock("deploy", "one"))


if __name__ == "__main__":
    unittest.main()

"""python3 -m unittest tools/navgate/test_compare_sweeps.py"""
import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import compare_sweeps as cs  # noqa: E402

HEADER = "map\tfrom\tto\ttype\tstepX\tstartX\tstartY\tendX\tendY\tendRegion\tfinalX\tfinalY\tmovedPx\tverdict"


def row(map_id, typ, sx, sy, ex, ey, verdict, step=0):
    return f"{map_id}\t1\t2\t{typ}\t{step}\t{sx}\t{sy}\t{ex}\t{ey}\t2\t{ex}\t{ey}\t100\t{verdict}"


class CompareSweepsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tsv(self, name, *rows):
        path = os.path.join(self.dir, name)
        with open(path, "w") as f:
            f.write("\n".join((HEADER,) + rows) + "\n")
        return path

    def run_compare(self, base, head):
        cand, probes = os.path.join(self.dir, "cand.txt"), os.path.join(self.dir, "probes.txt")
        with contextlib.redirect_stdout(io.StringIO()):
            cs.compare(base, head, cand, probes)
        return open(cand).read().splitlines(), open(probes).read().splitlines()

    def run_confirm(self, *paths):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = cs.confirm(*paths)
        return code, out.getvalue()

    def test_identical_sweeps_have_nothing_to_confirm_or_probe(self):
        rows = (row(100, "WALK", 0, 0, 200, 0, "ok"), row(100, "DROP", 300, -100, 320, 50, "stayed"))
        cand, probes = self.run_compare(self.tsv("b", *rows), self.tsv("h", *rows))
        self.assertEqual([], cand)
        self.assertEqual([], probes)

    def test_a_working_move_with_nothing_like_it_on_head_is_probed(self):
        base = self.tsv("b", row(100, "JUMP", 0, 0, 150, -60, "ok"), row(100, "WALK", 0, 0, 200, 0, "ok"))
        head = self.tsv("h", row(100, "WALK", 0, 0, 200, 0, "ok"))
        _, probes = self.run_compare(base, head)
        self.assertEqual(["100\t0\t0\t150\t-60"], probes)

    def test_a_move_that_shifted_a_little_or_changed_type_is_not_lost(self):
        base = self.tsv("b", row(100, "JUMP", 0, 0, 150, -60, "ok"))
        head = self.tsv("h", row(100, "WALK", 30, 4, 200, -55, "stayed", step=5))
        _, probes = self.run_compare(base, head)
        self.assertEqual([], probes, "the stuck head move is the regression path's job, not a lost way")

    def test_a_head_move_that_errored_does_not_stand_in_for_a_lost_one(self):
        base = self.tsv("b", row(100, "JUMP", 0, 0, 150, -60, "ok"))
        head = self.tsv("h", row(100, "JUMP", 0, 0, 150, -60, "error:NullPointerException"))
        _, probes = self.run_compare(base, head)
        self.assertEqual(1, len(probes))

    def test_moves_on_another_map_do_not_match(self):
        base = self.tsv("b", row(100, "WALK", 0, 0, 200, 0, "ok"))
        head = self.tsv("h", row(101, "WALK", 0, 0, 200, 0, "ok"))
        _, probes = self.run_compare(base, head)
        self.assertEqual(["100\t0\t0\t200\t0"], probes)

    def test_a_lost_way_fails_the_gate_only_when_head_fails_and_base_works(self):
        trip = lambda verdict: row(100, "TRIP", 0, 0, 150, -60, verdict)  # noqa: E731
        empty = os.path.join(self.dir, "missing.tsv")
        code, out = self.run_confirm(empty, empty, self.tsv("pb", trip("fails:1/10")),
                                     self.tsv("ph", trip("fails:9/10")))
        self.assertEqual(1, code)
        self.assertIn("confirmed lost ways: 1 of 1", out)

        code, _ = self.run_confirm(empty, empty, self.tsv("pb2", trip("fails:7/10")),
                                   self.tsv("ph2", trip("fails:9/10")))
        self.assertEqual(0, code, "fails on both sides: the way was flaky already")

        code, _ = self.run_confirm(empty, empty, self.tsv("pb3", trip("fails:0/10")),
                                   self.tsv("ph3", trip("fails:3/10")))
        self.assertEqual(0, code, "the router still gets there most of the time")

    def test_stuck_regressions_still_fail_the_gate_without_probes(self):
        cand = row(100, "DROP", 300, -100, 320, 50, "fails:10/10")
        code, out = self.run_confirm(self.tsv("cb", row(100, "DROP", 300, -100, 320, 50, "fails:0/10")),
                                     self.tsv("ch", cand))
        self.assertEqual(1, code)
        self.assertIn("NAV GATE: FAIL", out)


if __name__ == "__main__":
    unittest.main()

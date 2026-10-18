import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "distribution/demo"))
spec = importlib.util.spec_from_file_location("panel_server", ROOT / "panel/server.py")
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)
demo = server.demo


class PanelTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.original_data = server.DATA
        server.DATA = Path(self.directory.name)

    def tearDown(self):
        server.DATA = self.original_data
        demo.OBSERVER = None
        self.directory.cleanup()

    def test_mismatch_is_recorded_before_failing(self):
        rows = []
        demo.OBSERVER = lambda kind, payload: rows.append(payload)
        with self.assertRaises(AssertionError):
            demo.equal({"points": 7}, {"points": 8}, "Marcador")
        self.assertEqual(rows[0], {"scenario": "Marcador", "metric": "resultado.points", "expected": 8, "actual": 7, "passed": False})

    def test_namespace_updates_references_without_changing_game_or_ghosts(self):
        original = {"eventId": "e", "matchId": "m", "affectedPlayerId": "p", "gameId": "PACMAN",
                    "data": {"shotEventId": "e", "driverIds": ["p"], "ghostIds": ["BLINKY"], "classification": [{"playerId": "p", "position": 1}]}}
        actual = demo.namespace(original, "unique-")
        self.assertEqual(actual["data"]["shotEventId"], actual["eventId"])
        self.assertEqual(actual["data"]["classification"][0]["playerId"], actual["affectedPlayerId"])
        self.assertEqual(actual["data"]["driverIds"], ["unique-p"])
        self.assertEqual(actual["data"]["ghostIds"], ["BLINKY"])
        self.assertEqual(actual["gameId"], "PACMAN")
        self.assertEqual(original["eventId"], "e")

    def test_concurrent_start_is_rejected_and_failure_persisted(self):
        started, release = threading.Event(), threading.Event()
        def fail(**kwargs):
            started.set()
            release.wait(3)
            raise OSError("Scala no disponible")
        with patch.object(demo, "run", side_effect=fail):
            run = server.start()
            self.assertTrue(started.wait(1))
            try:
                self.assertIsNone(server.start())
            finally:
                release.set()
            for _ in range(100):
                if server.ACTIVE is None:
                    break
                time.sleep(.01)
        saved = server.load(run["id"])
        self.assertEqual(saved["status"], "failed")
        self.assertIn("Scala", saved["error"])
        self.assertEqual(saved["checks"], [])
        self.assertEqual(saved["groups"], [])

    def test_restart_marks_unfinished_checks_as_interrupted(self):
        run = {"id": "a" * 32, "started": "now", "status": "running", "checks": []}
        server.save(run)
        server.recover()
        self.assertEqual(server.load(run["id"])["status"], "interrupted")
        with self.assertRaises(FileNotFoundError):
            server.load("../secret")


if __name__ == "__main__":
    unittest.main()

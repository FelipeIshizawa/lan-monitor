import uuid
import unittest
from unittest.mock import patch, MagicMock
from pathlib import Path
from lan_agent.__main__ import Spool, synthetic


class AgentTests(unittest.TestCase):
    def test_spool_persists_and_limits(self):
        path = str(Path.cwd() / ("test-" + str(uuid.uuid4()) + ".sqlite"))
        try:
            spool = Spool(path, limit=1)
            spool.push({"batchId": "one", "samples": []})
            with self.assertRaises(RuntimeError):
                spool.push({"batchId": "two"})
            spool.db.close()
            reopened = Spool(path)
            self.assertEqual(reopened.db.execute("SELECT COUNT(*) FROM batches").fetchone()[0], 1)
            reopened.db.close()
        finally:
            Path(path).unlink(missing_ok=True)

    def test_synthetic_identity(self):
        samples = synthetic()
        self.assertEqual(len({s["mac"] for s in samples}), 12)
        self.assertTrue(all(s["reachable"] and s["latencyUs"] > 0 for s in samples))

    def test_failed_upload_keeps_batch_then_ack_removes_it(self):
        spool = Spool(":memory:")
        spool.push({"batchId": "retry", "samples": []})
        with patch("urllib.request.urlopen", side_effect=OSError("offline")):
            with self.assertRaises(OSError):
                spool.flush("http://localhost/api", "test-token")
        self.assertEqual(spool.db.execute("SELECT COUNT(*) FROM batches").fetchone()[0], 1)
        response = MagicMock()
        response.__enter__.return_value.status = 200
        with patch("urllib.request.urlopen", return_value=response):
            spool.flush("http://localhost/api", "test-token")
        self.assertEqual(spool.db.execute("SELECT COUNT(*) FROM batches").fetchone()[0], 0)
        spool.db.close()

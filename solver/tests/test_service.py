import json
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, build_opener, ProxyHandler
from unittest.mock import patch
import subprocess

from heatrouting.service import SolverServer
from heatrouting.validate import validate
from test_current_spec import fixture

urlopen = build_opener(ProxyHandler({})).open


class ServiceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = SolverServer(("127.0.0.1", 0), engine="python", max_bytes=100000)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = "http://127.0.0.1:" + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def request(self, data):
        req = Request(
            self.url + "/solve",
            data=json.dumps(data).encode(),
            headers={"Content-Type": "application/json"},
        )
        try:
            response = urlopen(req, timeout=30)
        except HTTPError as exc:
            response = exc
        with response:
            return response.status, json.load(response)

    def test_real_solve_validated(self):
        data = fixture()
        status, output = self.request(data)
        self.assertEqual(status, 200, output)
        self.assertTrue(validate(data, output)["valid"])
        self.assertTrue(
            any(
                f["properties"]["object_type"] == "variant_summary"
                for f in output["features"]
            )
        )

    def test_invalid_input(self):
        self.assertEqual(self.request({})[0], 422)

    def test_busy(self):
        self.server.slots.acquire()
        try:
            self.assertEqual(self.request(fixture())[0], 503)
        finally:
            self.server.slots.release()

    def test_size_limit(self):
        self.assertEqual(self.request({"padding": "x" * 100001})[0], 413)

    def test_timeout_releases_slot(self):
        with patch(
            "heatrouting.service.subprocess.run",
            side_effect=subprocess.TimeoutExpired("solver", 1),
        ):
            self.assertEqual(self.request(fixture())[0], 504)
        self.assertTrue(self.server.slots.acquire(blocking=False))
        self.server.slots.release()

    def test_health(self):
        with urlopen(self.url + "/health") as response:
            self.assertEqual(json.load(response)["status"], "ok")

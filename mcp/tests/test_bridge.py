"""No-device tests for Hidora MCP's ADB/JSON transport.

These tests intentionally prove request identity, explicit pairing and unknown
outcome handling without pretending to validate physical Mac USB HID behavior.
"""
import json
import socket
import sys
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bridge import BridgeClient, ascii_stroke, key_stroke


class FakePhone:
    def __init__(self, responses):
        self.server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(4)
        self.port = self.server.getsockname()[1]
        self.received = []
        self.responses = responses
        self.worker = threading.Thread(target=self.run, daemon=True)
        self.worker.start()

    def run(self):
        for reply in self.responses:
            conn, _ = self.server.accept()
            with conn:
                data = bytearray()
                while not data.endswith(b"\n"):
                    chunk = conn.recv(1024)
                    if not chunk:
                        break
                    data.extend(chunk)
                self.received.append(json.loads(data.decode("utf-8")))
                if reply is not None:
                    conn.sendall((json.dumps(reply) + "\n").encode("utf-8"))
        self.server.close()

    def finish(self):
        self.worker.join(timeout=4)
        assert not self.worker.is_alive(), "fake bridge received fewer calls than expected"


class MappingTests(unittest.TestCase):
    def test_ascii_us_layout_and_shift(self):
        self.assertEqual(ascii_stroke("a"), {"code": 4, "mods": 0})
        self.assertEqual(ascii_stroke("A"), {"code": 4, "mods": 2})
        self.assertEqual(ascii_stroke("1"), {"code": 30, "mods": 0})
        self.assertEqual(ascii_stroke("!"), {"code": 30, "mods": 2})
        self.assertEqual(ascii_stroke("?"), {"code": 56, "mods": 2})
        self.assertEqual(ascii_stroke("\n"), {"code": 40, "mods": 0})
        self.assertEqual(ascii_stroke("\\"), {"code": 49, "mods": 0})
        with self.assertRaises(ValueError):
            ascii_stroke("中")

    def test_mac_shortcuts(self):
        self.assertEqual(key_stroke("a", ["cmd"]), {"code": 4, "mods": 8})
        self.assertEqual(key_stroke("LEFT", ["shift", "option"]), {"code": 80, "mods": 6})
        self.assertEqual(key_stroke("F12"), {"code": 69, "mods": 0})
        with self.assertRaises(ValueError):
            key_stroke("a", ["root"])


class TransportTests(unittest.TestCase):
    def test_pair_secret_stays_inside_mcp_process(self):
        token = "ab" * 32
        phone = FakePhone([
            {"ok": True, "status": "authorized", "token": token, "remaining_seconds": 600},
            {"ok": True, "status": "usb_written", "request_id": "test-request-01",
             "target_observed": False},
            {"ok": True, "status": "usb_written", "request_id": "test-request-01",
             "target_observed": False},
        ])
        client = BridgeClient()
        client._local_port = phone.port
        self.assertNotIn("token", client.request_control())
        self.assertEqual(client.action("mouse_move", {"dx": 15, "dy": -4},
                                       "test-request-01")["status"], "usb_written")
        self.assertEqual(client.receipt("test-request-01")["status"], "usb_written")
        phone.finish()
        self.assertEqual(phone.received[0]["op"], "pair")
        self.assertEqual(phone.received[1]["token"], token)
        self.assertEqual(phone.received[1]["request_id"], "test-request-01")
        self.assertEqual(phone.received[2]["op"], "receipt")

    def test_unknown_action_is_not_retried(self):
        phone = FakePhone([None])
        client = BridgeClient()
        client._local_port = phone.port
        client._token = "ab" * 32
        answer = client.action("mouse_click", {"button": "left", "count": 1},
                               "unknown-request-1")
        phone.finish()
        self.assertEqual(answer["status"], "unknown")
        self.assertTrue(answer["do_not_replay"])
        self.assertEqual(answer["request_id"], "unknown-request-1")
        self.assertEqual(len(phone.received), 1)

    def test_denied_when_not_paired(self):
        client = BridgeClient()
        result = client.action("key_sequence", {"strokes": [{"code": 4, "mods": 0}]})
        self.assertEqual(result["status"], "denied")
        self.assertEqual(len(result["request_id"]), 32)

    def test_invalid_id_cannot_reach_phone(self):
        client = BridgeClient()
        client._token = "ab" * 32
        with self.assertRaises(ValueError):
            client.action("mouse_move", {"dx": 1, "dy": 1}, "bad id")


if __name__ == "__main__":
    unittest.main()

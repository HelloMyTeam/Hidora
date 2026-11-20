"""ADB-loopback transport for Hidora. No MCP or Android dependencies in this module.

ADB is paired by the human. Hidora then requires a second, time-limited
on-device approval. No Android listener binds to a non-loopback interface.
"""
from __future__ import annotations

import os
import json
import re
import socket
import subprocess
import threading
import uuid
from typing import Any

ANDROID_PORT = 39083
MAX_RESPONSE_BYTES = 16_384
TOKEN_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
REQUEST_ID_PATTERN = re.compile(r"[A-Za-z0-9_-]{8,64}\Z")

SHIFT = 0x02
MODIFIER_BITS = {
    "CTRL": 0x01,
    "CONTROL": 0x01,
    "SHIFT": SHIFT,
    "ALT": 0x04,
    "OPTION": 0x04,
    "CMD": 0x08,
    "COMMAND": 0x08,
    "GUI": 0x08,
    "WIN": 0x08,
}
SPECIAL_KEYS = {
    "ENTER": 0x28,
    "RETURN": 0x28,
    "ESC": 0x29,
    "ESCAPE": 0x29,
    "BACKSPACE": 0x2A,
    "TAB": 0x2B,
    "SPACE": 0x2C,
    "DELETE": 0x4C,
    "INSERT": 0x49,
    "HOME": 0x4A,
    "PAGEUP": 0x4B,
    "END": 0x4D,
    "PAGEDOWN": 0x4E,
    "RIGHT": 0x4F,
    "LEFT": 0x50,
    "DOWN": 0x51,
    "UP": 0x52,
}
SPECIAL_KEYS.update({f"F{i}": 0x39 + i for i in range(1, 13)})

PLAIN = {
    " ": 0x2C, "-": 0x2D, "=": 0x2E, "[": 0x2F, "]": 0x30,
    "\\": 0x31, ";": 0x33, "'": 0x34, "`": 0x35, ",": 0x36,
    ".": 0x37, "/": 0x38, "\n": 0x28, "\t": 0x2B,
}
SHIFTED = {
    "_": "-", "+": "=", "{": "[", "}": "]", "|": "\\",
    ":": ";", '"': "'", "~": "`", "<": ",", ">": ".", "?": "/",
    "!": "1", "@": "2", "#": "3", "$": "4", "%": "5",
    "^": "6", "&": "7", "*": "8", "(": "9", ")": "0",
}


def ascii_stroke(character: str) -> dict[str, int]:
    """Map one US-layout ASCII character to a USB HID press."""
    if len(character) != 1:
        raise ValueError("expected a single character")
    mods = 0
    key = character
    if "A" <= key <= "Z":
        key = key.lower()
        mods = SHIFT
    if character in SHIFTED:
        key = SHIFTED[character]
        mods = SHIFT
    if "a" <= key <= "z":
        code = ord(key) - ord("a") + 0x04
    elif "1" <= key <= "9":
        code = ord(key) - ord("1") + 0x1E
    elif key == "0":
        code = 0x27
    else:
        code = PLAIN.get(key)
    if code is None:
        raise ValueError("Only US-layout ASCII is supported; use target-side IME for Unicode")
    return {"code": code, "mods": mods}


def key_stroke(name: str, modifiers: list[str] | None = None) -> dict[str, int]:
    if not isinstance(name, str) or not name:
        raise ValueError("key must be a non-empty string")
    norm = name.replace("_", "").replace("-", "").upper()
    code = SPECIAL_KEYS.get(norm)
    base_mods = 0
    if code is None:
        stroke = ascii_stroke(name)
        code, base_mods = stroke["code"], stroke["mods"]
    for modifier in modifiers or []:
        bit = MODIFIER_BITS.get(modifier.upper())
        if bit is None:
            raise ValueError("Unsupported modifier: " + modifier)
        base_mods |= bit
    return {"code": code, "mods": base_mods}


class BridgeError(Exception):
    pass


class BridgeClient:
    def __init__(self, adb: str = "adb", serial: str | None = None):
        self.adb = adb
        self.serial = serial or os.environ.get("HIDORA_ADB_SERIAL")
        self._local_port: int | None = None
        self._token: str | None = None
        self._lock = threading.Lock()

    def _adb_command(self, *arguments: str) -> list[str]:
        return [self.adb] + (["-s", self.serial] if self.serial else []) + list(arguments)

    def _ensure_forward(self) -> int:
        if self._local_port is not None:
            return self._local_port
        try:
            result = subprocess.run(
                self._adb_command("forward", "tcp:0", f"tcp:{ANDROID_PORT}"),
                text=True, capture_output=True, timeout=10, check=True,
            )
            port = int(result.stdout.strip())
            if not 1 <= port <= 65535:
                raise BridgeError("ADB returned an invalid local port")
            self._local_port = port
            return port
        except (subprocess.SubprocessError, OSError, ValueError) as exc:
            raise BridgeError(
                "ADB forwarding failed. Pair wireless ADB and select HIDORA_ADB_SERIAL "
                "if multiple phones are connected."
            ) from exc

    def _send(self, payload: dict[str, Any], timeout: float = 4.0) -> dict[str, Any]:
        # The host-side port is created by ADB; no public TCP service is exposed.
        with self._lock:
            port = self._ensure_forward()
            with socket.create_connection(("127.0.0.1", port), timeout=timeout) as conn:
                conn.settimeout(timeout)
                conn.sendall((json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8"))
                data = bytearray()
                while True:
                    chunk = conn.recv(4096)
                    if not chunk:
                        raise BridgeError("Connection closed before a receipt was received")
                    data.extend(chunk)
                    if len(data) > MAX_RESPONSE_BYTES:
                        raise BridgeError("Oversized bridge receipt")
                    if b"\n" in data:
                        return json.loads(bytes(data.split(b"\n", 1)[0]))

    def info(self) -> dict[str, Any]:
        try:
            state = self._send({"op": "info"})
            if self._token:
                authorized = self._send({"op": "status", "token": self._token})
                if authorized.get("ok"):
                    return {"paired": True, **authorized}
                self._token = None
            return {"paired": False, **state}
        except (BridgeError, OSError, ValueError, TimeoutError) as exc:
            return {"paired": False, "ok": False, "status": "offline", "message": str(exc)}

    def request_control(self) -> dict[str, Any]:
        if self._token:
            state = self.info()
            if state.get("paired"):
                return state
        try:
            result = self._send({"op": "pair"}, timeout=25)
            secret = result.pop("token", None)
            if result.get("ok"):
                if not isinstance(secret, str) or not TOKEN_PATTERN.fullmatch(secret):
                    raise BridgeError("Malformed authorization response")
                self._token = secret
                return {"paired": True, **result}
            return {"paired": False, **result}
        except (BridgeError, OSError, ValueError, TimeoutError) as exc:
            return {"paired": False, "ok": False, "status": "unknown", "message": str(exc)}

    def action(self, op: str, values: dict[str, Any] | None = None,
               request_id: str | None = None) -> dict[str, Any]:
        rid = request_id or uuid.uuid4().hex
        if not REQUEST_ID_PATTERN.fullmatch(rid):
            raise ValueError("request_id must be 8-64 ASCII letters/digits/_/-")
        if not self._token:
            return {"ok": False, "status": "denied", "request_id": rid,
                    "message": "Call hidora_request_control and approve on Android first."}
        payload = {"op": op, "token": self._token, "request_id": rid, **(values or {})}
        try:
            response = self._send(payload)
            if (response.get("status") == "denied" and
                    response.get("error") == "approval_required_or_expired"):
                self._token = None
            if op == "stop" and response.get("ok"):
                self._token = None
            return response
        except (BridgeError, OSError, ValueError, TimeoutError) as exc:
            # The USB write may have happened. Never transparently retry.
            return {"ok": False, "status": "unknown", "request_id": rid,
                    "message": str(exc), "do_not_replay": True}

    def receipt(self, request_id: str) -> dict[str, Any]:
        if not REQUEST_ID_PATTERN.fullmatch(request_id):
            raise ValueError("invalid request ID")
        if not self._token:
            return {"ok": False, "status": "unknown", "request_id": request_id}
        try:
            return self._send({
                "op": "receipt", "token": self._token, "request_id": request_id
            })
        except (BridgeError, OSError, ValueError, TimeoutError):
            return {"ok": False, "status": "unknown", "request_id": request_id,
                    "do_not_replay": True}

    def close(self) -> None:
        port = self._local_port
        self._local_port = None
        self._token = None
        if port is not None:
            try:
                subprocess.run(self._adb_command("forward", "--remove", f"tcp:{port}"),
                               timeout=5, capture_output=True, check=False)
            except (OSError, subprocess.SubprocessError):
                pass

"""Hidora USB keyboard/mouse MCP server (official MCP Python SDK v2, stdio).

Run on the trusted Agent host, never on the target Mac. Phone USB stays
connected to the target; Agent-to-phone transport is paired wireless ADB.
"""
from __future__ import annotations

import atexit
from typing import Any

from mcp.server import MCPServer

from bridge import BridgeClient, ascii_stroke, key_stroke

mcp = MCPServer("hidora-usb-hid")
bridge = BridgeClient()
atexit.register(bridge.close)


@mcp.tool()
def hidora_status() -> dict[str, Any]:
    """Read phone bridge and authorization state. HID output is NOT a Mac screenshot."""
    status = bridge.info()
    status["observation"] = "unavailable: connect a separate camera/HDMI/screenshot tool"
    return status


@mcp.tool()
def hidora_request_control() -> dict[str, Any]:
    """Request a single ten-minute control grant. A human must tap Allow in Hidora."""
    return bridge.request_control()


@mcp.tool()
def hidora_mouse_move(dx: int, dy: int, request_id: str = "") -> dict[str, Any]:
    """Move the target mouse RELATIVELY, max +/-512 per axis. Observe after moving."""
    if not -512 <= dx <= 512 or not -512 <= dy <= 512:
        raise ValueError("dx/dy must be between -512 and 512")
    return bridge.action("mouse_move", {"dx": dx, "dy": dy}, request_id or None)


@mcp.tool()
def hidora_mouse_click(button: str = "left", count: int = 1,
                       request_id: str = "") -> dict[str, Any]:
    """Click left/middle/right, once or twice; requires recent target observation."""
    if button not in ("left", "middle", "right") or count not in (1, 2):
        raise ValueError("invalid button/count")
    return bridge.action("mouse_click", {"button": button, "count": count}, request_id or None)


@mcp.tool()
def hidora_mouse_scroll(vertical: int = 0, horizontal: int = 0,
                        request_id: str = "") -> dict[str, Any]:
    """Scroll vertical/horizontal, signed -10..10, using USB Wheel/AC Pan."""
    if not -10 <= vertical <= 10 or not -10 <= horizontal <= 10:
        raise ValueError("scroll values must be between -10 and 10")
    return bridge.action("mouse_scroll", {
        "vertical": vertical, "horizontal": horizontal
    }, request_id or None)


@mcp.tool()
def hidora_key_press(key: str, modifiers: list[str] | None = None,
                     request_id: str = "") -> dict[str, Any]:
    """Press one USB HID key. Modifiers: CTRL, SHIFT, ALT/OPTION, CMD/COMMAND."""
    return bridge.action("key_sequence", {
        "strokes": [key_stroke(key, modifiers)]
    }, request_id or None)


@mcp.tool()
def hidora_type_text(text: str, request_id: str = "") -> dict[str, Any]:
    """Type <=64 US-keyboard ASCII characters. Unicode/Chinese IME is NOT supported."""
    if not 1 <= len(text) <= 64:
        raise ValueError("text length must be 1..64")
    strokes = [ascii_stroke(character) for character in text]
    return bridge.action("key_sequence", {"strokes": strokes}, request_id or None)


@mcp.tool()
def hidora_release_all(request_id: str = "") -> dict[str, Any]:
    """Release USB keyboard modifiers/buttons. Safe recovery from stuck keys."""
    return bridge.action("release", request_id=request_id or None)


@mcp.tool()
def hidora_get_receipt(request_id: str) -> dict[str, Any]:
    """Query an earlier request ID after an unknown result; NEVER blindly replay it."""
    return bridge.receipt(request_id)


@mcp.tool()
def hidora_stop_control(request_id: str = "") -> dict[str, Any]:
    """Revoke Agent control on the phone and release USB input. User can also tap Stop."""
    return bridge.action("stop", request_id=request_id or None)


if __name__ == "__main__":
    mcp.run()

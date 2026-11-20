---
name: hidora-control
description: Operate an authorized Mac/PC using Hidora's Android USB HID mouse and keyboard through MCP tools. Use when the user asks an Agent to interact with a computer via a rooted Android phone and wireless ADB, especially system recovery or OS-independent control.
compatibility: Requires Hidora USB HID, a separately paired wireless ADB connection, and the local hidora MCP stdio server.
---

# Hidora Agent Control

The phone is a USB keyboard/mouse connected to the **target computer**. This Skill only supplies **input**. It cannot see the target computer's display; use independent HDMI/camera/desktop observation tools and confirm their target identity before acting.

## Startup and approval

1. Call `hidora_status`. If offline, ask the user to open Hidora's active keyboard+mouse page, check Root USB HID, and ensure wireless ADB from the Agent machine is paired.
2. Call `hidora_request_control`. Ask the user to tap **Allow 10 min** on the Android UI. The server stores the temporary token privately; the model does not need or receive it.
3. Authorization does not start automatically or survive app background, user Stop, device stop, or expiry. Never bypass the phone approval with direct ADB input or shell commands.

## Working loop

1. State the user's concrete task and permitted target. Before clicking, typing, or choosing a screen position, obtain a **recent, independent observation** of the target Mac/PC. HID itself does not return screenshots or absolute coordinates.
2. For one bounded step, call `hidora_mouse_move` (relative deltas), `hidora_mouse_click`, `hidora_mouse_scroll` (vertical/horizontal), `hidora_key_press` (with modifiers, e.g. `CMD` for macOS Command), or `hidora_type_text` (US-layout ASCII, max 64 characters).
3. Every write returns a `request_id`. `usb_written` proves only that Android wrote to the USB HID endpoint; observe the actual target screen to confirm the outcome. Keep the action batches short.
4. If the result is `unknown`/timeout, query `hidora_get_receipt(request_id)`. If still unknown, **do not replay** the click/key/text blindly. Observe actual state or ask the user.
5. If the user takes over, calls Stop, or the phone is backgrounded, halt control. Use `hidora_release_all` for stuck input only when still authorized. Finish with `hidora_stop_control` when control is no longer required.

## Guardrails

- Do not type passwords, security codes, delete files, perform purchases, change BIOS/security settings, or execute terminal commands without clear user authorization specific to those actions.
- No blind mouse clicking based only on assumed coordinates. Mouse movement is relative, and OS cursor acceleration varies.
- Mac recovery mode is not guaranteed to have HDMI video output. Mac power-button long-press is physical and cannot be emulated by this USB HID interface.
- USB HID text is US-layout ASCII only; do not send Chinese or arbitrary Unicode and claim it worked.
- Keep approval in one context on the Android screen. The user can revoke immediately; do not extend or restore expired grants.

For setup and testing see [../../mcp/README.md](../../mcp/README.md).

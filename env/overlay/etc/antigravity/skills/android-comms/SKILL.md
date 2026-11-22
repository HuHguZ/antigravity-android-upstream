---
name: android-comms
description: Read incoming Android notifications, send WearOS-style Quick Replies to messaging apps (Telegram, WhatsApp, SMS), check call status, answer/end calls, toggle speakerphone, inspect call history, search contacts, and dial numbers via the `android-device` MCP server.
---

# Android Notification Quick Reply, Calls & Contacts (`android-device` MCP)

The `android-device` MCP server exposes native Android notification inbox and telephony/contacts tools backed by `AgyNotificationListenerService`, `NotificationInboxHandler`, and `CallHandler` in `SystemGatewayServer`.

## 1. Notification Inbox & Quick Reply

Requires Android **Notification Access** enabled by the user (**Settings -> Android Tools -> Notification Access (Quick Reply)**).

| Tool | Purpose | Key Arguments |
| :--- | :--- | :--- |
| `android_inbox_list` | List active/recent notifications & Quick Reply capability | `package?` (e.g. `org.telegram.messenger`, `com.whatsapp`), `replyable_only?`, `limit?` |
| `android_inbox_reply` | Send an inline WearOS-style Quick Reply | `key` (from `android_inbox_list`), `message` |
| `android_inbox_dismiss` | Dismiss a notification from the shade | `key` |

- If `enabled: false` or `connected: false`, instruct the user to enable Notification Access under **Settings -> Android Tools -> Notification Access (Quick Reply)**.
- Always check `can_reply: true` before calling `android_inbox_reply`, and confirm outgoing messages with the user unless explicitly pre-authorized.

## 2. Native Call Control & Contacts

Requires runtime permissions granted by the user (**Settings -> Android Tools -> Phone & Contacts (Calls)**). Missing permissions return `{ok: false, error: "permission_denied", missing_permission: "..."}`.

| Tool | Purpose | Key Arguments |
| :--- | :--- | :--- |
| `android_call_status` | Live call state (`idle`/`ringing`/`offhook`), speakerphone, caller info, permissions | *(none)* |
| `android_call_answer` | Answer a ringing call (API 26+, `ANSWER_PHONE_CALLS`) | *(none)* |
| `android_call_end` | Reject ringing or hang up active call (API 28+, `ANSWER_PHONE_CALLS`) | *(none)* |
| `android_call_speaker` | Toggle speakerphone on/off | `enabled` (`true`/`false`) |
| `android_call_history` | Read recent or missed calls (`READ_CALL_LOG`) | `limit?`, `missed_only?` |
| `android_contacts_search` | Search contacts by name or phone number (`READ_CONTACTS`) | `query`, `limit?` |
| `android_call_dial` | Open dialer prefilled (default) or direct call (`direct: true` + `CALL_PHONE`) | `number`, `direct?` |

### Safety Rule
Prefer `android_call_dial(number=..., direct=false)` so the user reviews the number in the Android dialer unless they explicitly ask to place the call immediately.

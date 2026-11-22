---
name: telegram-assistant
description: Read, search, summarize unread digests, reply, and manage personal Telegram channels, groups, and direct chats via the `telegram` MTProto MCP server (`agy-mcp-telegram`) and `agy-tg-setup`.
---

# Telegram Assistant (`telegram` MCP Server)

Antigravity for Android pre-registers the `telegram` stdio MCP server (`$HOME/runtime/bin/agy-mcp-telegram`) in `$HOME/.gemini/config/mcp_config.json`. It uses Telethon MTProto with owner-only `0600` credentials under `$HOME/.gemini/antigravity-app/telegram/`.

## 1. First-Time Setup Check

Always start by calling `tg_status`:
- If `authorized: true`, proceed immediately with Telegram tools.
- If `telethon_installed: false` or `authorized: false`, ask the user to run **`agy-tg-setup`** in the Antigravity terminal (it installs `telethon`, prompts for `API_ID` & `API_HASH` from `https://my.telegram.org/apps`, and signs in interactively).

## 2. Available MCP Tools

| Tool | Purpose | Key Arguments |
| :--- | :--- | :--- |
| `tg_status` | Verify Telethon install, config, and authorization | *(none)* |
| `tg_list_dialogs` | List chats/channels/groups with unread counts | `limit` (default 30), `filter` (`all`/`channels`/`groups`/`users`/`unread`) |
| `tg_unread_digest` | Fetch unread messages across many chats in one call | `max_chats`, `messages_per_chat`, `include_channels`, `include_groups`, `include_users` |
| `tg_read_messages` | Read recent or unread messages from one chat | `chat` (`@username`, numeric ID, or title), `limit`, `unread_only` |
| `tg_search_messages` | Search messages globally or within a single chat | `query`, `chat?`, `limit` |
| `tg_send_message` | Send a reply or message to a chat | `chat`, `text`, `reply_to_msg_id?` |
| `tg_mark_read` | Mark messages in a chat as read | `chat`, `max_id?` |

## 3. Recommended Workflows

- **Unread Channel & Chat Digest:** Call `tg_unread_digest(max_chats=15, messages_per_chat=10)` and group key highlights by channel/person with action items.
- **Safe Sending:** Before calling `tg_send_message` to an external person or public group, confirm the exact recipient and message text with the user (or use `android_notify(approval=true)` if running autonomously).
- **Scheduled Morning Briefings:** Combine `agy-daemon` (`android_schedule_task`) with `tg_unread_digest` to generate scheduled daily Telegram summaries.

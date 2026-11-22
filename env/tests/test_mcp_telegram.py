#!/usr/bin/env python3
"""
Unit & stdio protocol tests for env/overlay/bin/agy-mcp-telegram and agy-tg-setup:
1. Live subprocess stdio test when telethon/config is missing (verifies lazy import
   and clean startup + actionable `agy-tg-setup` instructions).
2. Full in-process mocked Telethon test covering all 7 Telegram MCP tools
   (tg_status, tg_list_dialogs, tg_read_messages, tg_unread_digest,
   tg_search_messages, tg_send_message, tg_mark_read) + error codes.
3. `agy-tg-setup --status` smoke test.
"""

import os
import sys
import json
import types
import shutil
import tempfile
import datetime
import subprocess
import unittest
from importlib.machinery import SourceFileLoader
from importlib.util import spec_from_loader, module_from_spec

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
ENV_DIR = os.path.dirname(TESTS_DIR)
TG_MCP_SCRIPT = os.path.join(ENV_DIR, "overlay", "bin", "agy-mcp-telegram")
TG_SETUP_SCRIPT = os.path.join(ENV_DIR, "overlay", "bin", "agy-tg-setup")


def _load_tg_module():
    loader = SourceFileLoader("agy_mcp_telegram_mod", TG_MCP_SCRIPT)
    spec = spec_from_loader("agy_mcp_telegram_mod", loader)
    mod = module_from_spec(spec)
    loader.exec_module(mod)
    return mod


class _FakeUser:
    def __init__(self, uid, first_name, last_name="", username=None, phone=None):
        self.id = uid
        self.first_name = first_name
        self.last_name = last_name
        self.username = username
        self.phone = phone


class _FakeEntity:
    def __init__(self, eid, title="", username=None):
        self.id = eid
        self.title = title
        self.username = username


class _FakeDialog:
    def __init__(self, did, name, username=None, is_channel=False, is_group=False, is_user=False, unread_count=0):
        self.id = did
        self.name = name
        self.title = name
        self.entity = _FakeEntity(did, title=name, username=username)
        self.is_channel = is_channel
        self.is_group = is_group
        self.is_user = is_user
        self.unread_count = unread_count
        self.date = datetime.datetime(2026, 11, 22, 4, 0, 0, tzinfo=datetime.timezone.utc)


class _FakePhotoMedia:
    pass


class _FakeMessage:
    def __init__(self, mid, text, sender=None, media=None):
        self.id = mid
        self.message = text
        self.text = text
        self.sender = sender or _FakeUser(7, "Alice", username="alice")
        self.sender_id = self.sender.id
        self.date = datetime.datetime(2026, 11, 22, 4, 5, 0, tzinfo=datetime.timezone.utc)
        self.media = media


class _FakeTelegramClient:
    sent_messages = []
    marked_read = []

    def __init__(self, session_path, api_id, api_hash):
        self.session_path = session_path
        self.api_id = api_id
        self.api_hash = api_hash
        self._connected = False
        self._dialogs = [
            _FakeDialog(1001, "Tech News", username="technews", is_channel=True, unread_count=3),
            _FakeDialog(2002, "Dev Squad", username="devsquad", is_group=True, unread_count=2),
            _FakeDialog(3003, "Bob Smith", username="bob", is_user=True, unread_count=0),
        ]

    def is_connected(self):
        return self._connected

    async def connect(self):
        self._connected = True

    async def disconnect(self):
        self._connected = False

    async def is_user_authorized(self):
        return True

    async def get_me(self):
        return _FakeUser(42, "Nikita", "L", username="nikita", phone="+10000000000")

    async def get_dialogs(self, limit=30):
        return self._dialogs[:limit]

    async def get_entity(self, ref):
        if isinstance(ref, int):
            for d in self._dialogs:
                if d.id == ref:
                    return d.entity
        s = str(ref).lstrip("@").lower()
        for d in self._dialogs:
            if (d.entity.username or "").lower() == s:
                return d.entity
        raise ValueError(f"not found: {ref}")

    async def get_messages(self, entity, limit=20, search=None):
        if search:
            return [_FakeMessage(901, f"Search hit for {search}")]
        eid = getattr(entity, "id", 0)
        msgs = [
            _FakeMessage(101, f"Latest post in {eid}", media=_FakePhotoMedia()),
            _FakeMessage(100, f"Earlier post in {eid}"),
            _FakeMessage(99, f"Oldest post in {eid}"),
        ]
        return msgs[:limit]

    async def send_message(self, entity, text, reply_to=None):
        self.__class__.sent_messages.append({
            "entity_id": getattr(entity, "id", None),
            "text": text,
            "reply_to": reply_to,
        })
        return _FakeMessage(555, text)

    async def send_read_acknowledge(self, entity, max_id=None):
        self.__class__.marked_read.append({
            "entity_id": getattr(entity, "id", None),
            "max_id": max_id,
        })


class TestMcpTelegramUnconfiguredStdio(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="agy_tg_unconf_")
        env = os.environ.copy()
        env["HOME"] = self.tmp_dir
        env["PREFIX"] = os.path.join(self.tmp_dir, "usr")
        env["AGY_TELEGRAM_DIR"] = os.path.join(self.tmp_dir, "tg_state")

        self.proc = subprocess.Popen(
            [sys.executable, "-B", TG_MCP_SCRIPT],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            env=env,
        )

    def tearDown(self):
        if self.proc.poll() is None:
            try:
                self.proc.stdin.close()
                self.proc.wait(timeout=5)
            except Exception:
                self.proc.kill()
                self.proc.wait(timeout=5)
        for s in (self.proc.stdin, self.proc.stdout, self.proc.stderr):
            try:
                if s and not s.closed:
                    s.close()
            except Exception:
                pass
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def _rpc(self, msg):
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()
        if "id" not in msg or str(msg.get("method", "")).startswith("notifications/"):
            return None
        line = self.proc.stdout.readline()
        if not line:
            self.fail(f"agy-mcp-telegram exited unexpectedly; stderr={self.proc.stderr.read()}")
        return json.loads(line)

    def test_starts_cleanly_and_returns_setup_instructions(self):
        init = self._rpc({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05"}})
        self.assertEqual(init["result"]["serverInfo"]["name"], "telegram")
        self._rpc({"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.assertEqual(self._rpc({"jsonrpc": "2.0", "id": 2, "method": "ping"})["result"], {})

        tools = self._rpc({"jsonrpc": "2.0", "id": 3, "method": "tools/list"})["result"]["tools"]
        names = {t["name"] for t in tools}
        self.assertEqual(names, {
            "tg_status", "tg_list_dialogs", "tg_read_messages",
            "tg_unread_digest", "tg_search_messages", "tg_send_message", "tg_mark_read",
        })

        # tg_status works even without telethon/config
        st_resp = self._rpc({
            "jsonrpc": "2.0",
            "id": 4,
            "method": "tools/call",
            "params": {"name": "tg_status", "arguments": {}},
        })
        st = json.loads(st_resp["result"]["content"][0]["text"])
        self.assertTrue(st["ok"])
        self.assertFalse(st["authorized"])
        self.assertIn("agy-tg-setup", st["next_step"])

        # tg_list_dialogs returns graceful setup instructions (no crash)
        dlg_resp = self._rpc({
            "jsonrpc": "2.0",
            "id": 5,
            "method": "tools/call",
            "params": {"name": "tg_list_dialogs", "arguments": {}},
        })
        self.assertTrue(dlg_resp["result"]["isError"])
        dlg = json.loads(dlg_resp["result"]["content"][0]["text"])
        self.assertEqual(dlg["setup_command"], "agy-tg-setup")

        # Smoke test agy-tg-setup --status
        env = os.environ.copy()
        env["AGY_TELEGRAM_DIR"] = os.path.join(self.tmp_dir, "tg_state")
        setup_res = subprocess.run(
            [sys.executable, "-B", TG_SETUP_SCRIPT, "--status"],
            capture_output=True,
            text=True,
            env=env,
            timeout=10,
        )
        self.assertEqual(setup_res.returncode, 0)
        self.assertIn("config.json:", setup_res.stdout)


class TestMcpTelegramMockedTelethon(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="agy_tg_mock_")
        self.tg_dir = os.path.join(self.tmp_dir, "telegram")
        os.makedirs(self.tg_dir, exist_ok=True)
        with open(os.path.join(self.tg_dir, "config.json"), "w", encoding="utf-8") as f:
            json.dump({"api_id": 123456, "api_hash": "abcdef1234567890"}, f)
        with open(os.path.join(self.tg_dir, "user.session"), "wb") as f:
            f.write(b"mock-session")

        self._old_env = os.environ.get("AGY_TELEGRAM_DIR")
        os.environ["AGY_TELEGRAM_DIR"] = self.tg_dir

        _FakeTelegramClient.sent_messages = []
        _FakeTelegramClient.marked_read = []
        self.mod = _load_tg_module()
        fake_pkg = types.SimpleNamespace(TelegramClient=_FakeTelegramClient)
        self.mod._load_telethon = lambda: (fake_pkg, None)

    def tearDown(self):
        if self.mod._LOOP is not None and not self.mod._LOOP.is_closed():
            self.mod._LOOP.run_until_complete(self.mod._close_client())
            self.mod._LOOP.close()
        if self._old_env is None:
            os.environ.pop("AGY_TELEGRAM_DIR", None)
        else:
            os.environ["AGY_TELEGRAM_DIR"] = self._old_env
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def _call(self, req_id, name, args=None):
        resp = self.mod.handle_message({
            "jsonrpc": "2.0",
            "id": req_id,
            "method": "tools/call",
            "params": {"name": name, "arguments": args or {}},
        })
        self.assertNotIn("error", resp)
        res = resp["result"]
        return json.loads(res["content"][0]["text"]), res.get("isError", False)

    def test_all_seven_telegram_tools(self):
        # 1. tg_status
        st, err = self._call(1, "tg_status")
        self.assertFalse(err)
        self.assertTrue(st["authorized"])
        self.assertEqual(st["user"]["username"], "nikita")

        # 2. tg_list_dialogs (channels & unread filters)
        ch, err = self._call(2, "tg_list_dialogs", {"filter": "channels", "limit": 10})
        self.assertFalse(err)
        self.assertEqual(ch["count"], 1)
        self.assertEqual(ch["dialogs"][0]["username"], "@technews")

        unr, err = self._call(3, "tg_list_dialogs", {"filter": "unread"})
        self.assertFalse(err)
        self.assertEqual(unr["count"], 2)

        # 3. tg_read_messages (by @username and by title with unread_only)
        msgs, err = self._call(4, "tg_read_messages", {"chat": "@technews", "limit": 2})
        self.assertFalse(err)
        self.assertEqual(msgs["count"], 2)
        self.assertEqual(msgs["messages"][0]["media_type"], "_FakePhotoMedia")

        by_title, err = self._call(5, "tg_read_messages", {"chat": "Bob Smith", "unread_only": True})
        self.assertFalse(err)
        self.assertEqual(by_title["count"], 0)

        # 4. tg_unread_digest
        dig, err = self._call(6, "tg_unread_digest", {"max_chats": 10, "messages_per_chat": 2})
        self.assertFalse(err)
        self.assertEqual(dig["chats_count"], 2)
        self.assertEqual(dig["total_messages"], 4)

        # 5. tg_search_messages
        srch, err = self._call(7, "tg_search_messages", {"query": "release", "chat": "@devsquad"})
        self.assertFalse(err)
        self.assertIn("release", srch["messages"][0]["text"])

        # 6. tg_send_message
        sent, err = self._call(8, "tg_send_message", {"chat": "@bob", "text": "Hello Bob!", "reply_to_msg_id": 99})
        self.assertFalse(err)
        self.assertEqual(sent["id"], 555)
        self.assertEqual(_FakeTelegramClient.sent_messages[-1]["entity_id"], 3003)
        self.assertEqual(_FakeTelegramClient.sent_messages[-1]["reply_to"], 99)

        # 7. tg_mark_read
        mr, err = self._call(9, "tg_mark_read", {"chat": "@technews", "max_id": 101})
        self.assertFalse(err)
        self.assertEqual(_FakeTelegramClient.marked_read[-1]["entity_id"], 1001)
        self.assertEqual(_FakeTelegramClient.marked_read[-1]["max_id"], 101)


if __name__ == "__main__":
    unittest.main()

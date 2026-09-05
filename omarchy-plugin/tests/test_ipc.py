import os
import select
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from pomo_link.ipc import UnixCommandServer, send_command
from pomo_link.main import Engine
from pomo_link.persist import load_json


class UnixCommandServerTest(unittest.TestCase):
    def test_roundtrip(self):
        tmp = tempfile.TemporaryDirectory()
        path = os.path.join(tmp.name, "pomo.sock")
        server = UnixCommandServer(path)
        seen = []
        stop = threading.Event()

        def on_line(line):
            seen.append(line)
            return {"ok": True, "echo": line.strip()}

        def loop():
            while not stop.is_set():
                ready, _, _ = select.select(server.sockets(), [], [], 0.05)
                if ready:
                    server.pump(ready, on_line)

        thread = threading.Thread(target=loop, daemon=True)
        thread.start()
        try:
            result = send_command(path, {"cmd": "ping"})
            self.assertEqual(result["ok"], True)
            self.assertEqual(result["echo"], '{"cmd":"ping"}')
            self.assertEqual(len(seen), 1)
        finally:
            stop.set()
            thread.join(timeout=1)
            server.close()
            tmp.cleanup()


class EngineStatusFileTest(unittest.TestCase):
    def test_emit_writes_status_file(self):
        tmp = tempfile.TemporaryDirectory()
        try:
            status_path = os.path.join(tmp.name, "waybar.json")
            engine = Engine(
                directory=tmp.name,
                status_path=status_path,
                stdout_status=False,
            )
            engine.emit_status(force=True)
            data = load_json(status_path)
            self.assertEqual(data["type"], "status")
            self.assertIn(data["status"], ("stopped", "running", "paused"))
            self.assertFalse(data["has_token"])
        finally:
            tmp.cleanup()

    def test_toggle_offline_starts_local_timer(self):
        tmp = tempfile.TemporaryDirectory()
        try:
            engine = Engine(directory=tmp.name, stdout_status=False)
            engine.client.set_mode("OFFLINE")
            reply = engine.handle_line('{"cmd":"toggle"}')
            self.assertEqual(reply["status"], "running")
            self.assertEqual(reply["phase"], "work")
            self.assertGreater(reply["remaining"], 0)
        finally:
            tmp.cleanup()

    def test_ipc_gesture_burst_preserves_command_order(self):
        tmp = tempfile.TemporaryDirectory()
        try:
            engine = Engine(directory=tmp.name, stdout_status=False)
            engine.client.host = "phone"
            engine.client.port = 9876
            engine.client.token = "token"
            engine.client.set_mode("SYNCED")
            applied = []
            engine.client.send_gesture = lambda command: applied.append(command) or False

            engine._handle_ipc_line('{"cmd":"toggle"}')
            engine._handle_ipc_line('{"cmd":"skip"}')
            self.assertEqual(engine.pending_ipc_gestures, ["toggle", "skip"])
            # IPC arrival order is preserved in the timestamp queue as well.
            self.assertEqual(len(engine.pending_ipc_at), 2)
            self.assertLessEqual(engine.pending_ipc_at[0], engine.pending_ipc_at[1])
            engine.drain_pending_gesture()
            engine.drain_pending_gesture()
            self.assertEqual(applied, ["toggle", "skip"])
        finally:
            tmp.cleanup()

    def test_boot_local_ipc_toggle_runs_locally(self):
        # Wave-1 boot-local: fresh boot owns the clock, so an IPC toggle
        # applies immediately in arrival order instead of parking.
        tmp = tempfile.TemporaryDirectory()
        try:
            engine = Engine(directory=tmp.name, stdout_status=False)
            self.assertTrue(engine.model.local_owner)
            engine._handle_ipc_line('{"cmd":"toggle"}')
            self.assertEqual(engine.pending_ipc_gestures, [])
            self.assertEqual(engine.model.status, "running")
        finally:
            tmp.cleanup()

    def test_close_removes_status_file(self):
        tmp = tempfile.TemporaryDirectory()
        try:
            status_path = os.path.join(tmp.name, "waybar.json")
            engine = Engine(
                directory=tmp.name,
                status_path=status_path,
                stdout_status=False,
            )
            engine.emit_status(force=True)
            self.assertTrue(os.path.exists(status_path))
            engine.close()
            self.assertFalse(os.path.exists(status_path))
        finally:
            tmp.cleanup()

    def test_pair_rejected_returns_error(self):
        tmp = tempfile.TemporaryDirectory()
        try:
            engine = Engine(directory=tmp.name, stdout_status=False)
            reply = engine.handle_line('{"cmd":"pair","arg":"not-json{{{ "}')
            self.assertIsInstance(reply, dict)
            self.assertEqual(reply.get("type"), "error")
            self.assertTrue(reply.get("error"))
        finally:
            tmp.cleanup()


class UnixCommandServerSafetyTest(unittest.TestCase):
    def test_second_live_daemon_is_rejected(self):
        tmp = tempfile.TemporaryDirectory()
        try:
            path = os.path.join(tmp.name, "pomo.sock")
            first = UnixCommandServer(path)
            try:
                with self.assertRaises(OSError):
                    UnixCommandServer(path)
            finally:
                first.close()
        finally:
            tmp.cleanup()

    def test_refuses_non_socket_path(self):
        tmp = tempfile.TemporaryDirectory()
        try:
            path = os.path.join(tmp.name, "pomo.sock")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write("not a socket")
            with self.assertRaises(OSError):
                UnixCommandServer(path)
            self.assertTrue(os.path.exists(path))
        finally:
            tmp.cleanup()

    def test_close_does_not_unlink_replacement_socket(self):
        tmp = tempfile.TemporaryDirectory()
        try:
            path = os.path.join(tmp.name, "pomo.sock")
            first = UnixCommandServer(path)
            first_id = first._sock_id
            self.assertIsNotNone(first_id)
            # Simulate a replacement daemon taking over the path: close the
            # first listener without unlinking, then bind a fresh server.
            first.listen.close()
            try:
                os.unlink(path)
            except OSError:
                pass
            second = UnixCommandServer(path)
            try:
                first.close()
                self.assertTrue(os.path.exists(path))
            finally:
                second.close()
            self.assertFalse(os.path.exists(path))
        finally:
            tmp.cleanup()


class SocketDirSafetyTest(unittest.TestCase):
    def test_rejects_symlink_parent(self):
        from pomo_link.paths import ensure_socket_dir

        tmp = tempfile.TemporaryDirectory()
        try:
            real = os.path.join(tmp.name, "real")
            os.makedirs(real, mode=0o700)
            link = os.path.join(tmp.name, "link")
            os.symlink(real, link)
            with self.assertRaises(OSError):
                ensure_socket_dir(os.path.join(link, "pomo-link.sock"))
        finally:
            tmp.cleanup()


class InstallSymlinkFallbackTest(unittest.TestCase):
    def test_fallback_writes_exec_wrapper(self):
        import tempfile as _tempfile
        from unittest import mock

        from pomo_link import cli as cli_mod

        tmp = _tempfile.TemporaryDirectory()
        try:
            dest_dir = os.path.join(tmp.name, "bin")
            os.makedirs(dest_dir)
            exec_path = os.path.join(tmp.name, "real-pomo-link")
            with open(exec_path, "w", encoding="utf-8") as handle:
                handle.write("#!/bin/sh\nexit 0\n")
            with mock.patch.object(cli_mod, "local_bin_dir", return_value=dest_dir):
                with mock.patch.object(os, "symlink", side_effect=OSError("no symlink")):
                    dest = cli_mod._install_symlink(exec_path)
            self.assertIsNotNone(dest)
            with open(dest, "r", encoding="utf-8") as handle:
                body = handle.read()
            self.assertIn(exec_path, body)
            self.assertTrue(body.startswith("#!/bin/sh"))
            self.assertIn('"$@"', body)
            self.assertTrue(os.access(dest, os.X_OK))
        finally:
            tmp.cleanup()


if __name__ == "__main__":
    unittest.main()

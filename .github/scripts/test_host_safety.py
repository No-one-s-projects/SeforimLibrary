import importlib.util
import json
from pathlib import Path
import socket
import tempfile
import types
import unittest
from unittest import mock


HERE = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location("reap_linker_ner", HERE / "reap_linker_ner.py")
reaper = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(reaper)
LEASE_SPEC = importlib.util.spec_from_file_location("host_lease", HERE / "host_lease.py")
host_lease = importlib.util.module_from_spec(LEASE_SPEC)
assert LEASE_SPEC.loader is not None
LEASE_SPEC.loader.exec_module(host_lease)


class HostSafetyTest(unittest.TestCase):
    def state(self, pid=999_999_999):
        return {
            "schema_version": 1,
            "kind": "ner-gunicorn",
            "identity": {
                "pid": pid,
                "pgid": pid,
                "start_ticks": 123,
                "uid": 1000,
                "cmdline": "gunicorn app:create_app()",
            },
        }

    def test_stale_identity_is_removed_without_signal(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "scope.json"
            path.write_text(json.dumps(self.state(), sort_keys=True, separators=(",", ":")) + "\n")
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", 0))
                port = probe.getsockname()[1]
            args = types.SimpleNamespace(state=str(path), port=port, grace=0.01)
            self.assertEqual(reaper.reap(args), 0)
            self.assertFalse(path.exists())

    def test_stale_leader_with_live_descendants_preserves_scope_and_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "scope.json"
            path.write_text(json.dumps(self.state(), sort_keys=True, separators=(",", ":")) + "\n")
            args = types.SimpleNamespace(state=str(path), port=65534, grace=0.01)
            with mock.patch.object(reaper, "group_has_live_members", return_value=True):
                self.assertEqual(reaper.reap(args), 2)
            self.assertTrue(path.exists())

    def test_open_port_without_identity_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp, socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            args = types.SimpleNamespace(
                state=str(Path(tmp) / "missing.json"),
                port=listener.getsockname()[1],
                grace=0.01,
            )
            self.assertEqual(reaper.reap(args), 2)

    def test_duplicate_and_boolean_schema_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "scope.json"
            path.write_text('{"schema_version":1,"schema_version":1,"kind":"ner-gunicorn","identity":{}}')
            with self.assertRaises(ValueError):
                reaper.strict_json(path)
            value = self.state()
            value["schema_version"] = True
            path.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                reaper.strict_json(path)

    def test_lease_owner_is_start_time_and_runner_bound(self):
        with mock.patch.object(host_lease, "start_ticks", return_value=77), mock.patch.object(
            host_lease, "proc_cmdline", return_value="Runner.Worker --run"
        ):
            self.assertTrue(host_lease.owner_alive(123, 77))
            self.assertFalse(host_lease.owner_alive(123, 78))
        with mock.patch.object(host_lease, "start_ticks", side_effect=FileNotFoundError):
            self.assertFalse(host_lease.owner_alive(123, 77))

    def test_runner_owner_walks_ancestors_without_guessing(self):
        parents = {300: 200, 200: 100, 100: 1}
        commands = {300: "python host_lease.py", 200: "bash step.sh", 100: "Runner.Worker spawn"}
        with mock.patch.object(host_lease, "proc_parent", side_effect=lambda pid: parents[pid]), mock.patch.object(
            host_lease, "proc_cmdline", side_effect=lambda pid: commands[pid]
        ), mock.patch.object(host_lease, "start_ticks", return_value=55):
            self.assertEqual((100, 55), host_lease.find_runner_owner(300))


if __name__ == "__main__":
    unittest.main()
